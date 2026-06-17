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
    // Operand stack as a plain array: control flow within a chunk is forward-only (recursion spawns a
    // fresh VM.run), so the depth never exceeds the instruction count — one allocation, no boxing of
    // the Deque's nodes, no growth checks.
    Object[] stack = new Object[code.size() + 1];
    int sp = 0;
    Deque<Scope> scopes = new ArrayDeque<>();
    Scope scope = initialScope;
    // The closure's captured scope (the parent of the param scope) — a self-tail-call rebinds params
    // into a fresh child of it, so deep tail recursion reuses this one VM frame.
    Scope captured = initialScope.parent() != null ? initialScope.parent() : initialScope;
    Object scrut = null;
    int ip = 0;

    while (true) {
      Instr in = code.get(ip++);
      switch (in.op()) {
        case PUSH_CONST -> stack[sp++] = in.operand();
        case PUSH_VAR -> {
          String n = (String) in.operand();
          Object v = scope.lookup(n);
          stack[sp++] = v != null ? v : env.resolveGlobal(n);
        }
        case PUSH_QUAL -> {
          String[] q = (String[]) in.operand();
          stack[sp++] = env.resolveQualified(q[0], q[1]);
        }
        case PUSH_CTOR -> {
          String[] ct = (String[]) in.operand();
          stack[sp++] = env.constructorValue(ct[0].isEmpty() ? null : ct[0], ct[1]);
        }
        case PUSH_OPFUNC -> stack[sp++] = Operators.asFunction((String) in.operand());
        case PUSH_ACCESSOR -> {
          String f = (String) in.operand();
          stack[sp++] = new Builtin("." + f, 1, a -> ((ElmRecord) a[0]).get(f));
        }
        case MAKE_LIST -> {
          int n = in.arg();
          ElmList list = ElmList.NIL;
          for (int i = 0; i < n; i++) {
            list = ElmList.cons(stack[--sp], list); // top of stack is the last element
          }
          stack[sp++] = list;
        }
        case MAKE_TUPLE -> {
          int n = in.arg();
          Object[] vs = new Object[n];
          for (int i = n - 1; i >= 0; i--) {
            vs[i] = stack[--sp];
          }
          stack[sp++] = new ElmTuple(vs);
        }
        case MAKE_RECORD -> {
          String[] names = (String[]) in.operand();
          Object[] vals = new Object[names.length];
          for (int i = names.length - 1; i >= 0; i--) {
            vals[i] = stack[--sp];
          }
          Map<String, Object> m = new LinkedHashMap<>();
          for (int i = 0; i < names.length; i++) {
            m.put(names[i], vals[i]);
          }
          stack[sp++] = new ElmRecord(m);
        }
        case RECORD_UPDATE -> {
          String[] names = (String[]) in.operand();
          Object[] vals = new Object[names.length];
          for (int i = names.length - 1; i >= 0; i--) {
            vals[i] = stack[--sp];
          }
          ElmRecord base = (ElmRecord) stack[--sp];
          Map<String, Object> upd = new LinkedHashMap<>();
          for (int i = 0; i < names.length; i++) {
            upd.put(names[i], vals[i]);
          }
          stack[sp++] = base.withAll(upd);
        }
        case ACCESS -> {
          ElmRecord rec = (ElmRecord) stack[--sp];
          stack[sp++] = rec.get((String) in.operand());
        }
        case APPLY -> {
          Object arg = stack[--sp];
          Object fn = stack[--sp];
          stack[sp++] = Apply.apply(fn, arg);
        }
        case BINOP -> {
          Object r = stack[--sp];
          Object l = stack[--sp];
          stack[sp++] = Operators.binary((String) in.operand(), l, r);
        }
        case NEGATE -> {
          stack[sp - 1] = Operators.negate(stack[sp - 1]);
        }
        case JUMP -> ip = in.arg();
        case JUMP_IF_FALSE -> {
          if (!(Boolean) stack[--sp]) {
            ip = in.arg();
          }
        }
        case MAKE_CLOSURE -> stack[sp++] = new BytecodeClosure((Chunk) in.operand(), scope, env);
        case PUSH_SCOPE -> {
          scopes.push(scope);
          scope = scope.child();
        }
        case POP_SCOPE -> scope = scopes.pop();
        case SET_SCRUT -> scrut = stack[--sp];
        case MATCH -> {
          if (!PatternMatcher.match((Pattern) in.operand(), scrut, scope)) {
            ip = in.arg();
          }
        }
        case BIND_PAT -> {
          Object v = stack[--sp];
          PatternMatcher.match((Pattern) in.operand(), v, scope);
        }
        case ERROR -> throw new ElmRuntimeError((String) in.operand());
        case TAIL_CALL -> {
          // Self-tail-call: the N argument values are on top of the stack (in source order). Rebind
          // the chunk's params into a fresh scope and jump to the start — no new Java frame.
          int n = in.arg();
          Object[] args = new Object[n];
          for (int i = 0; i < n; i++) {
            args[i] = stack[sp - n + i];
          }
          scope = captured.child();
          for (int i = 0; i < n; i++) {
            PatternMatcher.match(chunk.params().get(i), args[i], scope);
          }
          sp = 0;
          scopes.clear();
          ip = 0;
        }
        case RETURN -> {
          return stack[--sp];
        }
      }
    }
  }
}
