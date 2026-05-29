package pl.matsuo.elm.codegen.js;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import pl.matsuo.elm.ast.Decl;
import pl.matsuo.elm.ast.Expr;
import pl.matsuo.elm.ast.Module;
import pl.matsuo.elm.ast.Pattern;
import pl.matsuo.elm.error.ElmRuntimeError;
import pl.matsuo.elm.interp.Prelude;
import pl.matsuo.elm.parser.Parser;

/**
 * Compiles an Elm {@link Module} (or single expression) to JavaScript source that runs on top of
 * {@link JsRuntime}. Functions become native curried arrow functions, so application is {@code
 * f(x)(y)}. The value model and {@code $show} mirror the interpreter exactly, enabling differential
 * testing.
 */
public final class JsCompiler {

  private final Set<String> builtinKeys = Prelude.builtins().keySet();
  private final Map<String, String> unqualified;
  private final Map<String, String> aliases = new HashMap<>();
  private final Map<String, Integer> ctorArity;
  private final Set<String> topLevelNames = new HashSet<>();
  private final String currentModule;
  private final Module module;

  private final Deque<Set<String>> localFrames = new ArrayDeque<>();
  private int counter = 0;

  private JsCompiler(Module module) {
    this.module = module;
    this.currentModule = module.name();
    this.ctorArity = Prelude.defaultCtorArity();
    this.unqualified = Prelude.defaultUnqualified();
    for (Decl d : module.decls()) {
      if (d instanceof Decl.Union u) {
        for (Decl.Union.Variant v : u.variants()) {
          ctorArity.put(v.name(), v.args().size());
        }
      }
      if (d instanceof Decl.Value v) {
        topLevelNames.add(v.name());
      }
    }
    for (Module.Import imp : module.imports()) {
      imp.alias().ifPresent(a -> aliases.put(a, imp.module()));
      if (imp.exposing().open()) {
        String prefix = imp.module() + ".";
        for (String key : builtinKeys) {
          if (key.startsWith(prefix)) {
            unqualified.put(key.substring(prefix.length()), key);
          }
        }
      } else {
        for (String name : imp.exposing().names()) {
          unqualified.put(name, imp.module() + "." + name);
        }
      }
    }
  }

  // --- public API --------------------------------------------------------

  /** Full program that prints {@code main} (a pure value) via {@code $show}. */
  public static String moduleProgram(String source) {
    JsCompiler c = new JsCompiler(Parser.parseModule(source));
    return JsRuntime.SOURCE + "\n" + c.declarations() + "\nprocess.stdout.write($show(_$main));\n";
  }

  /** Full program that prints the value of a single expression. */
  public static String expressionProgram(String expression) {
    Module empty =
        new Module(
            "Main",
            Module.Exposing.ALL,
            List.of(),
            List.of(),
            new pl.matsuo.elm.error.Position(1, 1, 0));
    JsCompiler c = new JsCompiler(empty);
    String e = c.compile(Parser.parseExpression(expression));
    return JsRuntime.SOURCE + "\nprocess.stdout.write($show((" + e + ")));\n";
  }

  /** Just the compiled top-level declarations (functions first, then values). */
  public String declarations() {
    StringBuilder sb = new StringBuilder();
    for (Decl d : module.decls()) {
      if (d instanceof Decl.Value v && !v.params().isEmpty()) {
        sb.append("var ").append(jsVar(v.name())).append(" = ")
            .append(compileLambda(v.params(), v.body())).append(";\n");
      }
    }
    for (Decl d : module.decls()) {
      if (d instanceof Decl.Value v && v.params().isEmpty()) {
        sb.append("var ").append(jsVar(v.name())).append(" = ")
            .append(compile(v.body())).append(";\n");
      }
    }
    return sb.toString();
  }

  // --- expression compilation -------------------------------------------

  private String compile(Expr e) {
    return switch (e) {
      case Expr.IntLit i -> Long.toString(i.value());
      case Expr.FloatLit f -> Double.toString(f.value());
      case Expr.StrLit s -> jsString(s.value());
      case Expr.CharLit c -> "$char(" + c.codePoint() + ")";
      case Expr.Shader s -> "$data(\"$Shader\",[" + jsString(s.source()) + "])";
      case Expr.Unit ignored -> "$unit";
      case Expr.Var v -> compileVar(v);
      case Expr.Ctor c -> compileCtor(c.name());
      case Expr.OpFunc o -> "(a=>b=>" + binJs(o.op(), "a", "b") + ")";
      case Expr.ListLit l -> "$list([" + compileList(l.items()) + "])";
      case Expr.Tuple t -> "$tuple([" + compileList(t.items()) + "])";
      case Expr.Record r -> compileRecord(r);
      case Expr.RecordUpdate u -> compileRecordUpdate(u);
      case Expr.RecordAccess a -> "(" + compile(a.target()) + ")[" + jsString(a.field()) + "]";
      case Expr.Accessor a -> "(r=>r[" + jsString(a.field()) + "])";
      case Expr.App app -> "(" + compile(app.fn()) + ")(" + compile(app.arg()) + ")";
      case Expr.BinOp b -> binJs(b.op(), compile(b.left()), compile(b.right()));
      case Expr.Negate n -> "(-(" + compile(n.operand()) + "))";
      case Expr.If iff ->
          "(" + compile(iff.cond()) + " ? " + compile(iff.thenBranch()) + " : "
              + compile(iff.elseBranch()) + ")";
      case Expr.Lambda l -> compileLambda(l.params(), l.body());
      case Expr.Let let -> compileLet(let);
      case Expr.Case c -> compileCase(c);
    };
  }

  private String compileList(List<Expr> items) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < items.size(); i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(compile(items.get(i)));
    }
    return sb.toString();
  }

  private String compileVar(Expr.Var v) {
    if (v.module() == null) {
      if (isLocal(v.name()) || topLevelNames.contains(v.name())) {
        return jsVar(v.name());
      }
      String canonical = unqualified.get(v.name());
      if (canonical != null && builtinKeys.contains(canonical)) {
        return "$g(" + jsString(canonical) + ")";
      }
      if (builtinKeys.contains(v.name())) {
        return "$g(" + jsString(v.name()) + ")";
      }
      throw new ElmRuntimeError("Unbound variable in JS codegen: " + v.name());
    }
    String realModule = aliases.getOrDefault(v.module(), v.module());
    String canonical = realModule + "." + v.name();
    if (builtinKeys.contains(canonical)) {
      return "$g(" + jsString(canonical) + ")";
    }
    if (realModule.equals(currentModule) && topLevelNames.contains(v.name())) {
      return jsVar(v.name());
    }
    throw new ElmRuntimeError("Unbound qualified name in JS codegen: " + v.module() + "." + v.name());
  }

  private String compileCtor(String name) {
    if (name.equals("True")) {
      return "true";
    }
    if (name.equals("False")) {
      return "false";
    }
    int arity = ctorArity.getOrDefault(name, 0);
    if (arity == 0) {
      return "$data(" + jsString(name) + ",[])";
    }
    StringBuilder args = new StringBuilder();
    StringBuilder chain = new StringBuilder();
    for (int i = 0; i < arity; i++) {
      chain.append("a").append(i).append("=>");
      if (i > 0) {
        args.append(",");
      }
      args.append("a").append(i);
    }
    return "(" + chain + "$data(" + jsString(name) + ",[" + args + "]))";
  }

  private String compileRecord(Expr.Record r) {
    StringBuilder sb = new StringBuilder("({");
    for (int i = 0; i < r.fields().size(); i++) {
      if (i > 0) {
        sb.append(",");
      }
      Expr.Record.Field f = r.fields().get(i);
      sb.append(jsString(f.name())).append(":").append(compile(f.value()));
    }
    return sb.append("})").toString();
  }

  private String compileRecordUpdate(Expr.RecordUpdate u) {
    StringBuilder sb = new StringBuilder("$update(").append(compileVar(new Expr.Var(null, u.base(), u.pos())));
    sb.append(",{");
    for (int i = 0; i < u.fields().size(); i++) {
      if (i > 0) {
        sb.append(",");
      }
      Expr.Record.Field f = u.fields().get(i);
      sb.append(jsString(f.name())).append(":").append(compile(f.value()));
    }
    return sb.append("})").toString();
  }

  private String compileLambda(List<Pattern> params, Expr body) {
    List<String> binds = new ArrayList<>();
    Set<String> names = new HashSet<>();
    String[] arg = new String[params.size()];
    for (int i = 0; i < params.size(); i++) {
      Pattern p = params.get(i);
      if (p instanceof Pattern.Var v) {
        arg[i] = jsVar(v.name());
        names.add(v.name());
      } else if (p instanceof Pattern.Wildcard) {
        arg[i] = "$p" + i;
      } else {
        arg[i] = "$p" + i;
        patBinds(p, "$p" + i, binds, names);
      }
    }
    localFrames.push(names);
    String b = compile(body);
    localFrames.pop();
    String inner = "{" + String.join("", binds) + "return " + b + ";}";
    StringBuilder sb = new StringBuilder(inner);
    for (int i = params.size() - 1; i >= 0; i--) {
      sb = new StringBuilder(arg[i] + "=>" + sb);
    }
    return "(" + sb + ")";
  }

  private String compileLet(Expr.Let let) {
    Set<String> names = new HashSet<>();
    for (Decl d : let.defs()) {
      if (d instanceof Decl.Value v) {
        names.add(v.name());
      } else if (d instanceof Decl.Destructure de) {
        collectNames(de.pattern(), names);
      }
    }
    localFrames.push(names);
    StringBuilder stmts = new StringBuilder();
    for (Decl d : let.defs()) {
      if (d instanceof Decl.Value v) {
        String rhs = v.params().isEmpty() ? compile(v.body()) : compileLambda(v.params(), v.body());
        stmts.append("var ").append(jsVar(v.name())).append("=").append(rhs).append(";");
      } else if (d instanceof Decl.Destructure de) {
        String tmp = "$d" + (counter++);
        stmts.append("var ").append(tmp).append("=").append(compile(de.body())).append(";");
        List<String> binds = new ArrayList<>();
        patBinds(de.pattern(), tmp, binds, new HashSet<>());
        stmts.append(String.join("", binds));
      }
    }
    String body = compile(let.body());
    localFrames.pop();
    return "(function(){" + stmts + "return " + body + ";})()";
  }

  private String compileCase(Expr.Case c) {
    String sv = "$s" + (counter++);
    String scrut = compile(c.scrutinee());
    StringBuilder body = new StringBuilder();
    for (Expr.Case.Branch br : c.branches()) {
      List<String> conds = new ArrayList<>();
      List<String> binds = new ArrayList<>();
      Set<String> names = new HashSet<>();
      matchJs(br.pattern(), sv, conds, binds, names);
      String cond = conds.isEmpty() ? "true" : String.join(" && ", conds);
      localFrames.push(names);
      String e = compile(br.body());
      localFrames.pop();
      body.append("if(").append(cond).append("){").append(String.join("", binds))
          .append("return ").append(e).append(";}");
    }
    body.append("throw new Error('non-exhaustive pattern');");
    return "(function(" + sv + "){" + body + "})(" + scrut + ")";
  }

  // --- pattern compilation ----------------------------------------------

  /** Emits structural test conditions plus binding statements for a {@code case} pattern. */
  private void matchJs(Pattern p, String subj, List<String> conds, List<String> binds, Set<String> names) {
    switch (p) {
      case Pattern.Wildcard ignored -> {}
      case Pattern.Unit ignored -> {}
      case Pattern.Var v -> {
        binds.add("var " + jsVar(v.name()) + "=" + subj + ";");
        names.add(v.name());
      }
      case Pattern.IntLit i -> conds.add(subj + " === " + i.value());
      case Pattern.StrLit s -> conds.add(subj + " === " + jsString(s.value()));
      case Pattern.CharLit c -> conds.add(subj + ".c === " + c.codePoint());
      case Pattern.Alias a -> {
        matchJs(a.pattern(), subj, conds, binds, names);
        binds.add("var " + jsVar(a.name()) + "=" + subj + ";");
        names.add(a.name());
      }
      case Pattern.Ctor c -> {
        if (c.name().equals("True")) {
          conds.add(subj + " === true");
        } else if (c.name().equals("False")) {
          conds.add(subj + " === false");
        } else {
          conds.add(subj + ".$ === " + jsString(c.name()));
          for (int i = 0; i < c.args().size(); i++) {
            matchJs(c.args().get(i), subj + "._[" + i + "]", conds, binds, names);
          }
        }
      }
      case Pattern.Tuple t -> {
        for (int i = 0; i < t.items().size(); i++) {
          matchJs(t.items().get(i), subj + ".vs[" + i + "]", conds, binds, names);
        }
      }
      case Pattern.Cons cons -> {
        conds.add(subj + ".$ === '::'");
        matchJs(cons.head(), subj + ".a", conds, binds, names);
        matchJs(cons.tail(), subj + ".b", conds, binds, names);
      }
      case Pattern.ListPat l -> {
        String cur = subj;
        for (Pattern item : l.items()) {
          conds.add(cur + ".$ === '::'");
          matchJs(item, cur + ".a", conds, binds, names);
          cur = cur + ".b";
        }
        conds.add(cur + ".$ === '[]'");
      }
      case Pattern.RecordPat r -> {
        for (String f : r.fields()) {
          binds.add("var " + jsVar(f) + "=" + subj + "[" + jsString(f) + "];");
          names.add(f);
        }
      }
    }
  }

  /** Like {@link #matchJs} but only emits bindings (used for irrefutable let/lambda patterns). */
  private void patBinds(Pattern p, String subj, List<String> binds, Set<String> names) {
    List<String> ignoredConds = new ArrayList<>();
    matchJs(p, subj, ignoredConds, binds, names);
  }

  private void collectNames(Pattern p, Set<String> names) {
    patBinds(p, "$x", new ArrayList<>(), names);
  }

  // --- operators & helpers ----------------------------------------------

  private String binJs(String op, String l, String r) {
    return switch (op) {
      case "+" -> "(" + l + " + " + r + ")";
      case "-" -> "(" + l + " - " + r + ")";
      case "*" -> "(" + l + " * " + r + ")";
      case "/" -> "(" + l + " / " + r + ")";
      case "//" -> "Math.trunc(" + l + " / " + r + ")";
      case "^" -> "Math.pow(" + l + ", " + r + ")";
      case "==" -> "$eq(" + l + ", " + r + ")";
      case "/=" -> "(!$eq(" + l + ", " + r + "))";
      case "<" -> "($cmp(" + l + ", " + r + ") < 0)";
      case ">" -> "($cmp(" + l + ", " + r + ") > 0)";
      case "<=" -> "($cmp(" + l + ", " + r + ") <= 0)";
      case ">=" -> "($cmp(" + l + ", " + r + ") >= 0)";
      case "&&" -> "(" + l + " && " + r + ")";
      case "||" -> "(" + l + " || " + r + ")";
      case "++" -> "$append(" + l + ", " + r + ")";
      case "::" -> "$cons(" + l + ", " + r + ")";
      case "|>" -> "(" + r + ")(" + l + ")";
      case "<|" -> "(" + l + ")(" + r + ")";
      case "<<" -> "$compose(" + l + ", " + r + ")";
      case ">>" -> "$compose(" + r + ", " + l + ")";
      default -> throw new ElmRuntimeError("Unknown operator in JS codegen: " + op);
    };
  }

  private boolean isLocal(String name) {
    for (Set<String> frame : localFrames) {
      if (frame.contains(name)) {
        return true;
      }
    }
    return false;
  }

  private static String jsVar(String name) {
    return "_$" + name;
  }

  /** Produces a JavaScript double-quoted string literal. */
  static String jsString(String s) {
    StringBuilder sb = new StringBuilder("\"");
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '\\' -> sb.append("\\\\");
        case '"' -> sb.append("\\\"");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    return sb.append("\"").toString();
  }
}
