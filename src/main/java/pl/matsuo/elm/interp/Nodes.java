package pl.matsuo.elm.interp;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.RootCallTarget;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import pl.matsuo.elm.ast.Pattern;
import pl.matsuo.elm.error.ElmRuntimeError;
import pl.matsuo.elm.runtime.Builtin;
import pl.matsuo.elm.runtime.ElmList;
import pl.matsuo.elm.runtime.ElmRecord;
import pl.matsuo.elm.runtime.ElmTuple;

/** Concrete {@link ElmNode} implementations, one per expression form. */
public final class Nodes {

  private Nodes() {}

  /** A constant literal (Int/Float/String/Char/Unit). */
  public static final class Const extends ElmNode {
    @CompilationFinal private final Object value;

    public Const(Object value) {
      this.value = value;
    }

    @Override
    public Object execute(Scope scope) {
      return value;
    }
  }

  /** A (possibly qualified) variable reference. */
  public static final class Var extends ElmNode {
    private final String module;
    private final String name;
    private final RuntimeEnv env;
    private final pl.matsuo.elm.error.Position pos;

    public Var(String module, String name, RuntimeEnv env) {
      this(module, name, env, null);
    }

    public Var(String module, String name, RuntimeEnv env, pl.matsuo.elm.error.Position pos) {
      this.module = module;
      this.name = name;
      this.env = env;
      this.pos = pos;
    }

    @Override
    public Object execute(Scope scope) {
      try {
        if (module == null) {
          Object local = scope.lookup(name);
          if (local != null) {
            return local;
          }
          return env.resolveGlobal(name);
        }
        return env.resolveQualified(module, name);
      } catch (pl.matsuo.elm.error.ElmRuntimeError e) {
        throw e.at(pos); // attach the source location of this reference if not already located
      }
    }
  }

  /** A constructor used as a value, e.g. {@code Nothing} or {@code Just}. */
  public static final class Ctor extends ElmNode {
    private final String name;
    private final RuntimeEnv env;

    public Ctor(String name, RuntimeEnv env) {
      this.name = name;
      this.env = env;
    }

    @Override
    public Object execute(Scope scope) {
      return env.constructorValue(name);
    }
  }

  /** An operator used as a value, e.g. {@code (+)}. */
  public static final class OpFunc extends ElmNode {
    private final String op;

    public OpFunc(String op) {
      this.op = op;
    }

    @Override
    public Object execute(Scope scope) {
      return Operators.asFunction(op);
    }
  }

  public static final class ListLit extends ElmNode {
    @Children private final ElmNode[] items;

    public ListLit(ElmNode[] items) {
      this.items = items;
    }

    @Override
    public Object execute(Scope scope) {
      ElmList result = ElmList.NIL;
      for (int i = items.length - 1; i >= 0; i--) {
        result = ElmList.cons(items[i].execute(scope), result);
      }
      return result;
    }
  }

  public static final class TupleLit extends ElmNode {
    @Children private final ElmNode[] items;

    public TupleLit(ElmNode[] items) {
      this.items = items;
    }

    @Override
    public Object execute(Scope scope) {
      Object[] values = new Object[items.length];
      for (int i = 0; i < items.length; i++) {
        values[i] = items[i].execute(scope);
      }
      return new ElmTuple(values);
    }
  }

  public static final class RecordLit extends ElmNode {
    private final String[] names;
    @Children private final ElmNode[] values;

    public RecordLit(String[] names, ElmNode[] values) {
      this.names = names;
      this.values = values;
    }

    @Override
    public Object execute(Scope scope) {
      Map<String, Object> fields = new LinkedHashMap<>();
      for (int i = 0; i < names.length; i++) {
        fields.put(names[i], values[i].execute(scope));
      }
      return new ElmRecord(fields);
    }
  }

  public static final class RecordUpdate extends ElmNode {
    @Child private ElmNode base;
    private final String[] names;
    @Children private final ElmNode[] values;

    public RecordUpdate(ElmNode base, String[] names, ElmNode[] values) {
      this.base = base;
      this.names = names;
      this.values = values;
    }

    @Override
    public Object execute(Scope scope) {
      Object b = base.execute(scope);
      if (!(b instanceof ElmRecord rec)) {
        throw new ElmRuntimeError("Record update on a non-record value");
      }
      Map<String, Object> updates = new LinkedHashMap<>();
      for (int i = 0; i < names.length; i++) {
        updates.put(names[i], values[i].execute(scope));
      }
      return rec.withAll(updates);
    }
  }

  public static final class Access extends ElmNode {
    @Child private ElmNode target;
    private final String field;

    public Access(ElmNode target, String field) {
      this.target = target;
      this.field = field;
    }

    @Override
    public Object execute(Scope scope) {
      Object t = target.execute(scope);
      if (!(t instanceof ElmRecord rec)) {
        throw new ElmRuntimeError("Field access ." + field + " on a non-record value");
      }
      return rec.get(field);
    }
  }

  public static final class Accessor extends ElmNode {
    private final String field;

    public Accessor(String field) {
      this.field = field;
    }

    @Override
    public Object execute(Scope scope) {
      return new Builtin("." + field, 1, args -> ((ElmRecord) args[0]).get(field));
    }
  }

  /**
   * A constructor applied to exactly its arity, built directly as an {@link ElmData} — skipping the
   * curried {@link Builtin} and the {@link Apply}/{@link pl.matsuo.elm.runtime.PartialApp} chain a
   * one-argument-at-a-time application would allocate. (Saturated record-alias constructors compile
   * to a {@link RecordLit} instead.)
   */
  public static final class CtorApp extends ElmNode {
    private final String name;
    @Children private final ElmNode[] args;

    public CtorApp(String name, ElmNode[] args) {
      this.name = name;
      this.args = args;
    }

    @Override
    public Object execute(Scope scope) {
      Object[] values = new Object[args.length];
      for (int i = 0; i < args.length; i++) {
        values[i] = args[i].execute(scope);
      }
      return new pl.matsuo.elm.runtime.ElmData(name, values);
    }
  }

  public static final class App extends ElmNode {
    @Child private ElmNode fn;
    @Child private ElmNode arg;

    public App(ElmNode fn, ElmNode arg) {
      this.fn = fn;
      this.arg = arg;
    }

    @Override
    public Object execute(Scope scope) {
      return Apply.apply(fn.execute(scope), arg.execute(scope));
    }
  }

  /**
   * Binary operator node. {@code &&}/{@code ||} short-circuit; {@code + - * < ==} (and the derived
   * {@code > <= >= /=}) dispatch through self-specializing Truffle DSL nodes; the rest use
   * {@link Operators}.
   */
  public static final class BinOp extends ElmNode {
    private final String op;
    @Child private ElmNode left;
    @Child private ElmNode right;
    @Child private pl.matsuo.elm.interp.num.BinaryOp opNode;
    private final boolean swap;
    private final boolean negate;

    public BinOp(String op, ElmNode left, ElmNode right) {
      this.op = op;
      this.left = left;
      this.right = right;
      this.opNode = makeOpNode(op);
      this.swap = op.equals(">") || op.equals("<=");
      this.negate = op.equals("<=") || op.equals(">=") || op.equals("/=");
    }

    private static pl.matsuo.elm.interp.num.BinaryOp makeOpNode(String op) {
      return switch (op) {
        case "+" -> pl.matsuo.elm.interp.num.AddNodeGen.create();
        case "-" -> pl.matsuo.elm.interp.num.SubNodeGen.create();
        case "*" -> pl.matsuo.elm.interp.num.MulNodeGen.create();
        case "//" -> pl.matsuo.elm.interp.num.IntDivNodeGen.create();
        case "<", ">", "<=", ">=" -> pl.matsuo.elm.interp.num.LessNodeGen.create();
        case "==", "/=" -> pl.matsuo.elm.interp.num.EqualsNodeGen.create();
        default -> null;
      };
    }

    @Override
    public Object execute(Scope scope) {
      if (op.equals("&&")) {
        return (Boolean) left.execute(scope) ? right.execute(scope) : Boolean.FALSE;
      }
      if (op.equals("||")) {
        return (Boolean) left.execute(scope) ? Boolean.TRUE : right.execute(scope);
      }
      Object l = left.execute(scope);
      Object r = right.execute(scope);
      if (opNode == null) {
        return Operators.binary(op, l, r);
      }
      Object result = swap ? opNode.execute(r, l) : opNode.execute(l, r);
      return negate ? !(Boolean) result : result;
    }
  }

  public static final class Negate extends ElmNode {
    @Child private ElmNode operand;

    public Negate(ElmNode operand) {
      this.operand = operand;
    }

    @Override
    public Object execute(Scope scope) {
      return Operators.negate(operand.execute(scope));
    }
  }

  public static final class If extends ElmNode {
    @Child private ElmNode cond;
    @Child private ElmNode thenBranch;
    @Child private ElmNode elseBranch;

    public If(ElmNode cond, ElmNode thenBranch, ElmNode elseBranch) {
      this.cond = cond;
      this.thenBranch = thenBranch;
      this.elseBranch = elseBranch;
    }

    @Override
    public Object execute(Scope scope) {
      return (Boolean) cond.execute(scope)
          ? thenBranch.execute(scope)
          : elseBranch.execute(scope);
    }
  }

  /** Evaluates to a {@link Closure} capturing the current scope. */
  public static final class Lambda extends ElmNode {
    private final String name;
    private final RootCallTarget target;
    private final List<Pattern> params;

    public Lambda(String name, RootCallTarget target, List<Pattern> params) {
      this.name = name;
      this.target = target;
      this.params = params;
    }

    @Override
    public Object execute(Scope scope) {
      return new Closure(name, target, scope, params);
    }
  }

  public static final class Let extends ElmNode {
    private final Pattern[] targets;
    @Children private final ElmNode[] rhs;
    @Child private ElmNode body;

    public Let(Pattern[] targets, ElmNode[] rhs, ElmNode body) {
      this.targets = targets;
      this.rhs = rhs;
      this.body = body;
    }

    @Override
    public Object execute(Scope scope) {
      Scope local = scope.child();
      for (int i = 0; i < targets.length; i++) {
        Object value = rhs[i].execute(local);
        if (!PatternMatcher.match(targets[i], value, local)) {
          throw new ElmRuntimeError("Let binding pattern did not match");
        }
      }
      return body.execute(local);
    }
  }

  /**
   * Wraps a function body that contains self-tail-calls. Runs the body; if it yields a {@link
   * TailSignal}, rebinds the parameters in a fresh scope and loops instead of growing the stack.
   */
  public static final class TailLoop extends ElmNode {
    @Child private ElmNode body;
    private final Pattern[] params;

    public TailLoop(ElmNode body, Pattern[] params) {
      this.body = body;
      this.params = params;
    }

    @Override
    public Object execute(Scope scope) {
      Scope base = scope.parent(); // the captured scope the parameters were bound under
      Scope current = scope;
      while (true) {
        Object result = body.execute(current);
        if (result instanceof TailSignal signal) {
          Scope next = base.child();
          for (int i = 0; i < params.length; i++) {
            PatternMatcher.match(params[i], signal.args()[i], next);
          }
          current = next;
        } else {
          return result;
        }
      }
    }
  }

  /** A saturated tail call to the enclosing function; evaluates args and yields a {@link TailSignal}. */
  public static final class SelfTailCall extends ElmNode {
    @Children private final ElmNode[] args;

    public SelfTailCall(ElmNode[] args) {
      this.args = args;
    }

    @Override
    public Object execute(Scope scope) {
      Object[] values = new Object[args.length];
      for (int i = 0; i < args.length; i++) {
        values[i] = args[i].execute(scope);
      }
      return new TailSignal(values);
    }
  }

  public static final class Case extends ElmNode {
    @Child private ElmNode scrutinee;
    private final Pattern[] patterns;
    @Children private final ElmNode[] bodies;
    private final pl.matsuo.elm.error.Position pos;

    public Case(ElmNode scrutinee, Pattern[] patterns, ElmNode[] bodies) {
      this(scrutinee, patterns, bodies, null);
    }

    public Case(
        ElmNode scrutinee,
        Pattern[] patterns,
        ElmNode[] bodies,
        pl.matsuo.elm.error.Position pos) {
      this.scrutinee = scrutinee;
      this.patterns = patterns;
      this.bodies = bodies;
      this.pos = pos;
    }

    @Override
    public Object execute(Scope scope) {
      Object value = scrutinee.execute(scope);
      // One child frame reused across attempts: a failed branch runs no body, so nothing captures
      // its (reset) bindings; the matching branch keeps the frame and is never reset afterwards.
      Scope branch = scope.child();
      for (int i = 0; i < patterns.length; i++) {
        branch.reset();
        if (PatternMatcher.match(patterns[i], value, branch)) {
          return bodies[i].execute(branch);
        }
      }
      throw new ElmRuntimeError("Non-exhaustive pattern match on: " + value, pos);
    }
  }
}
