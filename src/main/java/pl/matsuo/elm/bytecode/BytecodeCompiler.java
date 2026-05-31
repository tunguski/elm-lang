package pl.matsuo.elm.bytecode;

import java.util.ArrayList;
import java.util.List;
import pl.matsuo.elm.ast.Decl;
import pl.matsuo.elm.ast.Expr;
import pl.matsuo.elm.ast.Pattern;
import pl.matsuo.elm.error.ElmRuntimeError;
import pl.matsuo.elm.runtime.ElmChar;
import pl.matsuo.elm.runtime.ElmUnit;

/** Compiles the {@link Expr} AST into {@link Chunk}s of stack {@link Op} bytecode. */
public final class BytecodeCompiler {

  /** Compiles a function/lambda body (with parameter patterns) into a chunk. Self-tail-calls in the
   * body become a {@code TAIL_CALL} (rebind params + jump to 0) instead of a fresh VM frame, so deep
   * tail recursion runs in constant Java stack — matching the interpreter's tail-loop. */
  public Chunk compileChunk(List<Pattern> params, Expr body, String name) {
    List<Instr> code = new ArrayList<>();
    // Disable self-tail-call detection if a parameter shadows the function's own name.
    String tco = params.stream().anyMatch(p -> binds(p, name)) ? null : name;
    compileTail(code, body, tco, params.size());
    return new Chunk(code, params, name);
  }

  /**
   * Compiles {@code e} in tail position: every control path ends in either a {@code RETURN} or, when
   * the path is a full self-application of {@code name}/{@code arity}, a {@code TAIL_CALL}. {@code
   * name} is null when self-tail-call optimisation is disabled (e.g. the name is shadowed).
   */
  private void compileTail(List<Instr> c, Expr e, String name, int arity) {
    switch (e) {
      case Expr.If iff -> {
        compile(c, iff.cond());
        int toElse = emitJump(c, Op.JUMP_IF_FALSE);
        compileTail(c, iff.thenBranch(), name, arity);
        patch(c, toElse, c.size());
        compileTail(c, iff.elseBranch(), name, arity);
      }
      case Expr.Let let -> {
        c.add(Instr.of(Op.PUSH_SCOPE));
        boolean shadows = false;
        for (Decl d : let.defs()) {
          switch (d) {
            case Decl.Value v -> {
              if (v.params().isEmpty()) {
                compile(c, v.body());
              } else {
                c.add(Instr.of(Op.MAKE_CLOSURE, compileChunk(v.params(), v.body(), v.name())));
              }
              c.add(Instr.of(Op.BIND_PAT, new Pattern.Var(v.name())));
              shadows |= v.name().equals(name);
            }
            case Decl.Destructure de -> {
              compile(c, de.body());
              c.add(Instr.of(Op.BIND_PAT, de.pattern()));
              shadows |= binds(de.pattern(), name);
            }
            default -> throw new ElmRuntimeError("Unsupported declaration in let: " + d);
          }
        }
        // The let bindings stay live while a tail call's arguments are evaluated; TAIL_CALL resets the
        // scope, and a RETURN abandons it, so no POP_SCOPE is needed on these self-terminating paths.
        compileTail(c, let.body(), shadows ? null : name, arity);
      }
      case Expr.Case ca -> compileCaseTail(c, ca, name, arity);
      case Expr.App app when isSelfCall(app, name, arity) -> {
        for (Expr arg : appArgs(app)) {
          compile(c, arg);
        }
        c.add(Instr.ofArg(Op.TAIL_CALL, arity));
      }
      default -> {
        compile(c, e);
        c.add(Instr.of(Op.RETURN));
      }
    }
  }

  /** Whether {@code app} is a full application ({@code arity} args) of an unqualified variable named
   * {@code name} — i.e. a self-call eligible for tail-call optimisation. */
  private static boolean isSelfCall(Expr.App app, String name, int arity) {
    if (name == null) {
      return false;
    }
    List<Expr> args = appArgs(app);
    Expr head = app;
    while (head instanceof Expr.App a) {
      head = a.fn();
    }
    return args.size() == arity
        && head instanceof Expr.Var v
        && v.module() == null
        && v.name().equals(name);
  }

  /** The arguments of a curried application spine, in source order. */
  private static List<Expr> appArgs(Expr.App app) {
    List<Expr> args = new ArrayList<>();
    Expr head = app;
    while (head instanceof Expr.App a) {
      args.add(0, a.arg());
      head = a.fn();
    }
    return args;
  }

  /** Whether a pattern binds the name {@code n} (so it would shadow the function name). */
  private static boolean binds(Pattern p, String n) {
    return switch (p) {
      case Pattern.Var v -> v.name().equals(n);
      case Pattern.Alias a -> a.name().equals(n) || binds(a.pattern(), n);
      case Pattern.Ctor ct -> ct.args().stream().anyMatch(x -> binds(x, n));
      case Pattern.Tuple t -> t.items().stream().anyMatch(x -> binds(x, n));
      case Pattern.ListPat l -> l.items().stream().anyMatch(x -> binds(x, n));
      case Pattern.Cons cs -> binds(cs.head(), n) || binds(cs.tail(), n);
      case Pattern.RecordPat r -> r.fields().contains(n);
      default -> false;
    };
  }

  /** Like {@link #compileCase} but each branch body is compiled in tail position. */
  private void compileCaseTail(List<Instr> c, Expr.Case ca, String name, int arity) {
    compile(c, ca.scrutinee());
    c.add(Instr.of(Op.SET_SCRUT));
    for (Expr.Case.Branch br : ca.branches()) {
      c.add(Instr.of(Op.PUSH_SCOPE));
      int matchIdx = c.size();
      c.add(new Instr(Op.MATCH, -1, br.pattern()));
      // On a match the bound scope stays live for the tail body (which RETURNs or TAIL_CALLs away).
      compileTail(c, br.body(), binds(br.pattern(), name) ? null : name, arity);
      patch(c, matchIdx, c.size());
      c.add(Instr.of(Op.POP_SCOPE)); // failure path: undo PUSH_SCOPE then try the next branch
    }
    c.add(Instr.of(Op.ERROR, "Non-exhaustive pattern match"));
  }

  private void compile(List<Instr> c, Expr e) {
    switch (e) {
      case Expr.IntLit i -> c.add(Instr.of(Op.PUSH_CONST, i.value()));
      case Expr.FloatLit f -> c.add(Instr.of(Op.PUSH_CONST, f.value()));
      case Expr.StrLit s -> c.add(Instr.of(Op.PUSH_CONST, s.value()));
      case Expr.CharLit ch -> c.add(Instr.of(Op.PUSH_CONST, new ElmChar(ch.codePoint())));
      case Expr.Shader s ->
          c.add(Instr.of(Op.PUSH_CONST, new pl.matsuo.elm.runtime.ElmData("$Shader", new Object[] {s.source()})));
      case Expr.Unit ignored -> c.add(Instr.of(Op.PUSH_CONST, ElmUnit.INSTANCE));
      case Expr.Var v -> {
        if (v.module() == null) {
          c.add(Instr.of(Op.PUSH_VAR, v.name()));
        } else {
          c.add(Instr.of(Op.PUSH_QUAL, new String[] {v.module(), v.name()}));
        }
      }
      case Expr.Ctor ct -> c.add(Instr.of(Op.PUSH_CTOR, ct.name()));
      case Expr.OpFunc o -> c.add(Instr.of(Op.PUSH_OPFUNC, o.op()));
      case Expr.ListLit l -> {
        l.items().forEach(it -> compile(c, it));
        c.add(Instr.ofArg(Op.MAKE_LIST, l.items().size()));
      }
      case Expr.Tuple t -> {
        t.items().forEach(it -> compile(c, it));
        c.add(Instr.ofArg(Op.MAKE_TUPLE, t.items().size()));
      }
      case Expr.Record r -> {
        String[] names = new String[r.fields().size()];
        for (int i = 0; i < names.length; i++) {
          names[i] = r.fields().get(i).name();
          compile(c, r.fields().get(i).value());
        }
        c.add(Instr.of(Op.MAKE_RECORD, names));
      }
      case Expr.RecordUpdate u -> {
        c.add(Instr.of(Op.PUSH_VAR, u.base()));
        String[] names = new String[u.fields().size()];
        for (int i = 0; i < names.length; i++) {
          names[i] = u.fields().get(i).name();
          compile(c, u.fields().get(i).value());
        }
        c.add(Instr.of(Op.RECORD_UPDATE, names));
      }
      case Expr.RecordAccess a -> {
        compile(c, a.target());
        c.add(Instr.of(Op.ACCESS, a.field()));
      }
      case Expr.Accessor a -> c.add(Instr.of(Op.PUSH_ACCESSOR, a.field()));
      case Expr.App app -> {
        compile(c, app.fn());
        compile(c, app.arg());
        c.add(Instr.of(Op.APPLY));
      }
      case Expr.BinOp b -> compileBinOp(c, b);
      case Expr.Negate n -> {
        compile(c, n.operand());
        c.add(Instr.of(Op.NEGATE));
      }
      case Expr.If iff -> compileIf(c, iff);
      case Expr.Lambda l -> c.add(Instr.of(Op.MAKE_CLOSURE, compileChunk(l.params(), l.body(), "lambda")));
      case Expr.Let let -> compileLet(c, let);
      case Expr.Case ca -> compileCase(c, ca);
    }
  }

  private void compileBinOp(List<Instr> c, Expr.BinOp b) {
    if (b.op().equals("&&")) {
      compile(c, b.left());
      int toFalse = emitJump(c, Op.JUMP_IF_FALSE);
      compile(c, b.right());
      int toEnd = emitJump(c, Op.JUMP);
      patch(c, toFalse, c.size());
      c.add(Instr.of(Op.PUSH_CONST, Boolean.FALSE));
      patch(c, toEnd, c.size());
      return;
    }
    if (b.op().equals("||")) {
      compile(c, b.left());
      int toRight = emitJump(c, Op.JUMP_IF_FALSE);
      c.add(Instr.of(Op.PUSH_CONST, Boolean.TRUE));
      int toEnd = emitJump(c, Op.JUMP);
      patch(c, toRight, c.size());
      compile(c, b.right());
      patch(c, toEnd, c.size());
      return;
    }
    compile(c, b.left());
    compile(c, b.right());
    c.add(Instr.of(Op.BINOP, b.op()));
  }

  private void compileIf(List<Instr> c, Expr.If iff) {
    compile(c, iff.cond());
    int toElse = emitJump(c, Op.JUMP_IF_FALSE);
    compile(c, iff.thenBranch());
    int toEnd = emitJump(c, Op.JUMP);
    patch(c, toElse, c.size());
    compile(c, iff.elseBranch());
    patch(c, toEnd, c.size());
  }

  private void compileLet(List<Instr> c, Expr.Let let) {
    c.add(Instr.of(Op.PUSH_SCOPE));
    for (Decl d : let.defs()) {
      switch (d) {
        case Decl.Value v -> {
          if (v.params().isEmpty()) {
            compile(c, v.body());
          } else {
            c.add(Instr.of(Op.MAKE_CLOSURE, compileChunk(v.params(), v.body(), v.name())));
          }
          c.add(Instr.of(Op.BIND_PAT, new Pattern.Var(v.name())));
        }
        case Decl.Destructure de -> {
          compile(c, de.body());
          c.add(Instr.of(Op.BIND_PAT, de.pattern()));
        }
        default -> throw new ElmRuntimeError("Unsupported declaration in let: " + d);
      }
    }
    compile(c, let.body());
    c.add(Instr.of(Op.POP_SCOPE));
  }

  private void compileCase(List<Instr> c, Expr.Case ca) {
    compile(c, ca.scrutinee());
    c.add(Instr.of(Op.SET_SCRUT));
    List<Integer> endJumps = new ArrayList<>();
    for (Expr.Case.Branch br : ca.branches()) {
      c.add(Instr.of(Op.PUSH_SCOPE));
      int matchIdx = c.size();
      c.add(new Instr(Op.MATCH, -1, br.pattern()));
      compile(c, br.body());
      c.add(Instr.of(Op.POP_SCOPE));
      endJumps.add(emitJump(c, Op.JUMP));
      patch(c, matchIdx, c.size());
      c.add(Instr.of(Op.POP_SCOPE)); // failure path: undo PUSH_SCOPE then try next branch
    }
    c.add(Instr.of(Op.ERROR, "Non-exhaustive pattern match"));
    int end = c.size();
    for (int j : endJumps) {
      patch(c, j, end);
    }
  }

  // --- tiny assembler ----------------------------------------------------

  private int emitJump(List<Instr> c, Op op) {
    int idx = c.size();
    c.add(Instr.ofArg(op, -1));
    return idx;
  }

  private void patch(List<Instr> c, int idx, int target) {
    Instr old = c.get(idx);
    c.set(idx, new Instr(old.op(), target, old.operand()));
  }
}
