package pl.matsuo.elm.types;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import pl.matsuo.elm.ast.Type;
import pl.matsuo.elm.parser.Parser;

/**
 * Converts a surface type-signature string (parsed by {@link Parser}) into a generalized {@link
 * Scheme}. Lowercase type variables become quantified {@link Ty.Var}s, with Elm's built-in
 * constraints inferred from the variable name (e.g. {@code number}, {@code comparable}).
 */
public final class SchemeParser {

  private SchemeParser() {}

  public static Scheme scheme(String signature) {
    Type type = Parser.parseTypeSignature(signature);
    Map<String, Ty.Var> vars = new LinkedHashMap<>();
    Ty body = convert(type, vars);
    return new Scheme(new ArrayList<>(vars.values()), body);
  }

  private static Ty convert(Type type, Map<String, Ty.Var> vars) {
    return switch (type) {
      case Type.Var v -> vars.computeIfAbsent(v.name(), SchemeParser::freshVar);
      case Type.Arrow a -> new Ty.Arrow(convert(a.from(), vars), convert(a.to(), vars));
      case Type.Unit ignored -> new Ty.Unit();
      case Type.Tuple t -> new Ty.Tuple(t.items().stream().map(x -> convert(x, vars)).toList());
      case Type.Con c -> new Ty.Con(c.name(), c.args().stream().map(x -> convert(x, vars)).toList());
      case Type.Record r -> {
        Map<String, Ty> fields = new LinkedHashMap<>();
        for (Type.Record.Field f : r.fields()) {
          fields.put(f.name(), convert(f.type(), vars));
        }
        Ty tail = r.base().map(b -> (Ty) vars.computeIfAbsent(b, SchemeParser::freshVar)).orElse(null);
        yield new Ty.Record(fields, tail);
      }
    };
  }

  private static Ty.Var freshVar(String name) {
    Ty.Constraint constraint;
    if (name.startsWith("compappend")) {
      constraint = Ty.Constraint.COMPAPPEND;
    } else if (name.startsWith("number")) {
      constraint = Ty.Constraint.NUMBER;
    } else if (name.startsWith("comparable")) {
      constraint = Ty.Constraint.COMPARABLE;
    } else if (name.startsWith("appendable")) {
      constraint = Ty.Constraint.APPENDABLE;
    } else {
      constraint = Ty.Constraint.NONE;
    }
    // Level 0: scheme variables are quantified, instantiated fresh on each use.
    return new Ty.Var(0, constraint);
  }
}
