package pl.matsuo.elm.bytecode;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import pl.matsuo.elm.ast.Pattern;
import pl.matsuo.elm.error.ElmRuntimeError;
import pl.matsuo.elm.interp.Apply;
import pl.matsuo.elm.interp.Operators;
import pl.matsuo.elm.interp.PatternMatcher;
import pl.matsuo.elm.interp.RuntimeEnv;
import pl.matsuo.elm.interp.Scope;
import pl.matsuo.elm.runtime.Builtin;
import pl.matsuo.elm.runtime.ElmList;
import pl.matsuo.elm.runtime.ElmRecord;
import pl.matsuo.elm.runtime.ElmTuple;

/** The bytecode virtual machine: a simple operand-stack interpreter for {@link Chunk}s. */
public final class VM {

  private VM() {}

  public static Object run(Chunk chunk, Scope initialScope, RuntimeEnv env) {
    List<Instr> code = chunk.code();
    Deque<Object> stack = new ArrayDeque<>();
    Deque<Scope> scopes = new ArrayDeque<>();
    Scope scope = initialScope;
    Object scrut = null;
    int ip = 0;

    while (true) {
      Instr in = code.get(ip++);
      switch (in.op()) {
        case PUSH_CONST -> stack.push(in.operand());
        case PUSH_VAR -> {
          String n = (String) in.operand();
          Object v = scope.lookup(n);
          stack.push(v != null ? v : env.resolveGlobal(n));
        }
        case PUSH_QUAL -> {
          String[] q = (String[]) in.operand();
          stack.push(env.resolveQualified(q[0], q[1]));
        }
        case PUSH_CTOR -> stack.push(env.constructorValue((String) in.operand()));
        case PUSH_OPFUNC -> stack.push(Operators.asFunction((String) in.operand()));
        case PUSH_ACCESSOR -> {
          String f = (String) in.operand();
          stack.push(new Builtin("." + f, 1, a -> ((ElmRecord) a[0]).get(f)));
        }
        case MAKE_LIST -> {
          int n = in.arg();
          Object[] tmp = new Object[n];
          for (int i = n - 1; i >= 0; i--) {
            tmp[i] = stack.pop();
          }
          ElmList list = ElmList.NIL;
          for (int i = n - 1; i >= 0; i--) {
            list = ElmList.cons(tmp[i], list);
          }
          stack.push(list);
        }
        case MAKE_TUPLE -> {
          int n = in.arg();
          Object[] vs = new Object[n];
          for (int i = n - 1; i >= 0; i--) {
            vs[i] = stack.pop();
          }
          stack.push(new ElmTuple(vs));
        }
        case MAKE_RECORD -> {
          String[] names = (String[]) in.operand();
          Object[] vals = new Object[names.length];
          for (int i = names.length - 1; i >= 0; i--) {
            vals[i] = stack.pop();
          }
          Map<String, Object> m = new LinkedHashMap<>();
          for (int i = 0; i < names.length; i++) {
            m.put(names[i], vals[i]);
          }
          stack.push(new ElmRecord(m));
        }
        case RECORD_UPDATE -> {
          String[] names = (String[]) in.operand();
          Object[] vals = new Object[names.length];
          for (int i = names.length - 1; i >= 0; i--) {
            vals[i] = stack.pop();
          }
          ElmRecord base = (ElmRecord) stack.pop();
          Map<String, Object> upd = new LinkedHashMap<>();
          for (int i = 0; i < names.length; i++) {
            upd.put(names[i], vals[i]);
          }
          stack.push(base.withAll(upd));
        }
        case ACCESS -> {
          ElmRecord rec = (ElmRecord) stack.pop();
          stack.push(rec.get((String) in.operand()));
        }
        case APPLY -> {
          Object arg = stack.pop();
          Object fn = stack.pop();
          stack.push(Apply.apply(fn, arg));
        }
        case BINOP -> {
          Object r = stack.pop();
          Object l = stack.pop();
          stack.push(Operators.binary((String) in.operand(), l, r));
        }
        case NEGATE -> stack.push(Operators.negate(stack.pop()));
        case JUMP -> ip = in.arg();
        case JUMP_IF_FALSE -> {
          if (!(Boolean) stack.pop()) {
            ip = in.arg();
          }
        }
        case MAKE_CLOSURE -> stack.push(new BytecodeClosure((Chunk) in.operand(), scope, env));
        case PUSH_SCOPE -> {
          scopes.push(scope);
          scope = scope.child();
        }
        case POP_SCOPE -> scope = scopes.pop();
        case SET_SCRUT -> scrut = stack.pop();
        case MATCH -> {
          if (!PatternMatcher.match((Pattern) in.operand(), scrut, scope)) {
            ip = in.arg();
          }
        }
        case BIND_PAT -> {
          Object v = stack.pop();
          PatternMatcher.match((Pattern) in.operand(), v, scope);
        }
        case ERROR -> throw new ElmRuntimeError((String) in.operand());
        case RETURN -> {
          return stack.pop();
        }
      }
    }
  }
}
