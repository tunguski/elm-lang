package pl.matsuo.elm.codegen.js;

import java.util.List;
import pl.matsuo.elm.error.ElmRuntimeError;

/**
 * Typed builders for the JavaScript fragments the JS backend emits.
 *
 * <p>The codegen used to assemble JS by hand with string concatenation — {@code "(" + fn + ")(" + arg
 * + ")"}, {@code "if(" + cond + "){" + body + "}"}, and so on — where a misplaced parenthesis, comma,
 * semicolon or quote is easy to introduce and hard to spot. This class centralises that syntax behind
 * named methods, so {@link JsCompiler} reads as {@code Js.call(fn, args)} / {@code Js.iife(...)} and
 * the shape of the emitted JS lives in one place. Every method returns rendered JS source.
 */
public final class Js {
  private Js() {}

  // ---------------------------------------------------------------- literals

  /** A double-quoted JS string literal, with the control characters and quotes escaped. */
  public static String str(String s) {
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

  /** A JS identifier for an Elm local, prefixed so it cannot clash with a JS keyword or global. */
  public static String local(String elmName) {
    return "_$" + elmName;
  }

  // ------------------------------------------------------------- expressions

  /** {@code fn(args…)}. */
  public static String call(String fn, List<String> args) {
    return fn + "(" + commas(args) + ")";
  }

  /** {@code fn(args…)}. */
  public static String call(String fn, String... args) {
    return call(fn, List.of(args));
  }

  /** {@code (e)} — parenthesised, e.g. to apply or negate a compound expression safely. */
  public static String paren(String e) {
    return "(" + e + ")";
  }

  /** {@code fn(arg)} where {@code fn} is itself an arbitrary expression — {@code (fn)(arg)}. */
  public static String apply(String fnExpr, String argExpr) {
    return paren(fnExpr) + "(" + argExpr + ")";
  }

  /** {@code obj[keyExpr]} — computed member access. */
  public static String index(String obj, String keyExpr) {
    return obj + "[" + keyExpr + "]";
  }

  /** {@code obj[i]} — numeric index. */
  public static String index(String obj, int i) {
    return obj + "[" + i + "]";
  }

  /** {@code obj.member} — static member access. */
  public static String dot(String obj, String member) {
    return obj + "." + member;
  }

  /** {@code param=>body} — an arrow function (body is an expression). */
  public static String arrow(String param, String body) {
    return param + "=>" + body;
  }

  /** {@code (cond ? then : els)}. */
  public static String ternary(String cond, String then, String els) {
    return "(" + cond + " ? " + then + " : " + els + ")";
  }

  /** {@code (-(e))}. */
  public static String negate(String e) {
    return "(-(" + e + "))";
  }

  /** {@code a === b}. */
  public static String strictEq(String a, String b) {
    return a + " === " + b;
  }

  /** {@code a && b && …}, or {@code "true"} when there are no conditions. */
  public static String and(List<String> conds) {
    return conds.isEmpty() ? "true" : String.join(" && ", conds);
  }

  /** {@code [items…]}. */
  public static String array(List<String> items) {
    return "[" + commas(items) + "]";
  }

  /** {@code ({entries…})} — an object literal from already-rendered {@code key:value} entries. */
  public static String object(List<String> entries) {
    return "({" + String.join(",", entries) + "})";
  }

  /** A single object-literal entry {@code "key":valueExpr} (the key is quoted). */
  public static String entry(String key, String valueExpr) {
    return str(key) + ":" + valueExpr;
  }

  // ----------------------------------------------------------- runtime kernel

  /** {@code $g("name")} — dynamic lookup of a runtime-kernel / cross-module binding. */
  public static String global(String name) {
    return call("$g", str(name));
  }

  /** {@code $char(codePoint)} — a {@code Char} value. */
  public static String charLit(int codePoint) {
    return call("$char", Integer.toString(codePoint));
  }

  /** {@code $data("ctor",[args…])} — a tagged union value / constructor application. */
  public static String data(String ctor, List<String> args) {
    return call("$data", List.of(str(ctor), array(args)));
  }

  /** {@code $list([items…])}. */
  public static String list(List<String> items) {
    return call("$list", List.of(array(items)));
  }

  /** {@code $tuple([items…])}. */
  public static String tuple(List<String> items) {
    return call("$tuple", List.of(array(items)));
  }

  /** {@code $update(base,{entries…})} — a record update from already-rendered entries. */
  public static String update(String baseExpr, List<String> entries) {
    return "$update(" + baseExpr + ",{" + String.join(",", entries) + "})";
  }

  /**
   * The binary-operator forms the JS runtime provides: arithmetic inline, structural equality via
   * {@code $eq}, ordering via {@code $cmp}, list {@code ++}/{@code ::} via {@code $append}/{@code
   * $cons}, pipes as application, and composition via {@code $compose}.
   */
  public static String binOp(String op, String l, String r) {
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

  // -------------------------------------------------------------- statements

  /** {@code return e;}. */
  public static String ret(String e) {
    return "return " + e + ";";
  }

  /** {@code var name=value;}. */
  public static String varDecl(String name, String value) {
    return "var " + name + "=" + value + ";";
  }

  /** {@code if(cond){body}}. */
  public static String ifStmt(String cond, String body) {
    return "if(" + cond + "){" + body + "}";
  }

  /** {@code throw new Error("message");}. */
  public static String throwError(String message) {
    return "throw new Error(" + str(message) + ");";
  }

  /** {@code function(param){body}}. */
  public static String function(String param, String body) {
    return "function(" + param + "){" + body + "}";
  }

  /** {@code (function(param){body})(arg)} — an immediately-invoked function expression. */
  public static String iife(String param, String body, String arg) {
    return paren(function(param, body)) + "(" + arg + ")";
  }

  /** {@code (function(){body})()} — an immediately-invoked function with no parameters. */
  public static String iife(String body) {
    return paren("function(){" + body + "}") + "()";
  }

  /** {@code lhs=rhs;} — a reassignment statement (no {@code var}). */
  public static String assign(String lhs, String rhs) {
    return lhs + "=" + rhs + ";";
  }

  /** {@code if(cond){thenBody}else{elseBody}}. */
  public static String ifElse(String cond, String thenBody, String elseBody) {
    return "if(" + cond + "){" + thenBody + "}else{" + elseBody + "}";
  }

  /** {@code {body}} — a brace-wrapped statement block. */
  public static String block(String body) {
    return "{" + body + "}";
  }

  /** {@code continue label;}. */
  public static String continueTo(String label) {
    return "continue " + label + ";";
  }

  /** {@code label: while(true){body}} — the labelled loop the self-tail-call optimisation emits. */
  public static String labeledWhileTrue(String label, String body) {
    return label + ": while(true){" + body + "}";
  }

  /** {@code switch(key){cases}}. */
  public static String switchStmt(String key, String cases) {
    return "switch(" + key + "){" + cases + "}";
  }

  /** {@code case label:{body break;}} — one labelled case that falls through to the default on no
   *  match (the trailing {@code break} leaves the switch so the post-switch default code runs). */
  public static String caseLabel(String label, String body) {
    return "case " + label + ":{" + body + "break;}";
  }

  /** A curried arrow chain {@code (p0=>p1=>…=>body)}; {@code body} may be a block or an expression. */
  public static String curried(List<String> params, String body) {
    String acc = body;
    for (int i = params.size() - 1; i >= 0; i--) {
      acc = arrow(params.get(i), acc);
    }
    return paren(acc);
  }

  /** A generated temporary or parameter name, e.g. {@code $s7} or {@code a0}. */
  public static String tmp(String prefix, int n) {
    return prefix + n;
  }

  // -------------------------------------------------------- program assembly

  /** A module-qualified top-level identifier {@code _$tag$name}. */
  public static String qualifiedId(String tag, String name) {
    return "_$" + tag + "$" + name;
  }

  /** Joins program sections (kernel, declarations, entry point, …) with newlines. */
  public static String lines(String... sections) {
    return String.join("\n", sections);
  }

  /** The prefix of a top-level binding, {@code var id = } — shared by emission and source maps. */
  public static String topLevelVarPrefix(String id) {
    return "var " + id + " = ";
  }

  /** A top-level binding {@code var id = expr;} on its own line. */
  public static String topLevelVar(String id, String expr) {
    return topLevelVarPrefix(id) + expr + ";\n";
  }

  /** {@code process.stdout.write($show(expr));} — a Node program's "print this value" entry point. */
  public static String printShow(String expr) {
    return call("process.stdout.write", call("$show", expr)) + ";";
  }

  /** {@code window.$start(mainId, document.getElementById('app'));} — mounts a browser app. */
  public static String mount(String mainId) {
    return call("window.$start", List.of(mainId, "document.getElementById('app')")) + ";";
  }

  /** A minimal HTML page that hosts {@code script} in an {@code <div id="app">} mount point. */
  public static String htmlAppPage(String script) {
    return "<!doctype html><html><head><meta charset=\"utf-8\"></head><body><div id=\"app\"></div>\n"
        + "<script>\n"
        + script
        + "\n</script></body></html>\n";
  }

  private static String commas(List<String> xs) {
    return String.join(", ", xs);
  }
}
