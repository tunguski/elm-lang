package pl.matsuo.elm.types;

import java.util.HashMap;
import java.util.Map;

/** Built-in type schemes: operators, core constructors and a curated set of prelude functions. */
public final class Signatures {

  private Signatures() {}

  private static final Map<String, Scheme> GLOBALS = new HashMap<>();
  private static final Map<String, Scheme> OPERATORS = new HashMap<>();

  public static Map<String, Scheme> globals() {
    return GLOBALS;
  }

  public static Scheme operator(String op) {
    return OPERATORS.get(op);
  }

  private static void g(String name, String sig) {
    GLOBALS.put(name, SchemeParser.scheme(sig));
  }

  private static void op(String symbol, String sig) {
    OPERATORS.put(symbol, SchemeParser.scheme(sig));
  }

  static {
    // Operators.
    op("+", "number -> number -> number");
    op("-", "number -> number -> number");
    op("*", "number -> number -> number");
    op("/", "Float -> Float -> Float");
    op("//", "Int -> Int -> Int");
    op("^", "number -> number -> number");
    op("==", "a -> a -> Bool");
    op("/=", "a -> a -> Bool");
    op("<", "comparable -> comparable -> Bool");
    op(">", "comparable -> comparable -> Bool");
    op("<=", "comparable -> comparable -> Bool");
    op(">=", "comparable -> comparable -> Bool");
    op("&&", "Bool -> Bool -> Bool");
    op("||", "Bool -> Bool -> Bool");
    op("++", "appendable -> appendable -> appendable");
    op("::", "a -> List a -> List a");
    op("|>", "a -> (a -> b) -> b");
    op("<|", "(a -> b) -> a -> b");
    op(">>", "(a -> b) -> (b -> c) -> a -> c");
    op("<<", "(b -> c) -> (a -> b) -> a -> c");

    // Constructors.
    g("True", "Bool");
    g("False", "Bool");
    g("Just", "a -> Maybe a");
    g("Nothing", "Maybe a");
    g("Ok", "a -> Result e a");
    g("Err", "e -> Result e a");
    g("LT", "Order");
    g("EQ", "Order");
    g("GT", "Order");

    // Basics (unqualified).
    g("identity", "a -> a");
    g("always", "a -> b -> a");
    g("not", "Bool -> Bool");
    g("negate", "number -> number");
    g("abs", "number -> number");
    g("clamp", "number -> number -> number -> number");
    g("min", "comparable -> comparable -> comparable");
    g("max", "comparable -> comparable -> comparable");
    g("compare", "comparable -> comparable -> Order");
    g("toFloat", "Int -> Float");
    g("round", "Float -> Int");
    g("floor", "Float -> Int");
    g("ceiling", "Float -> Int");
    g("truncate", "Float -> Int");
    g("sqrt", "Float -> Float");
    g("modBy", "Int -> Int -> Int");
    g("remainderBy", "Int -> Int -> Int");
    g("pi", "Float");
    g("e", "Float");
    g("sin", "Float -> Float");
    g("cos", "Float -> Float");
    g("tan", "Float -> Float");
    g("logBase", "Float -> Float -> Float");

    // List.
    g("List.map", "(a -> b) -> List a -> List b");
    g("List.indexedMap", "(Int -> a -> b) -> List a -> List b");
    g("List.filter", "(a -> Bool) -> List a -> List a");
    g("List.foldl", "(a -> b -> b) -> b -> List a -> b");
    g("List.foldr", "(a -> b -> b) -> b -> List a -> b");
    g("List.range", "Int -> Int -> List Int");
    g("List.length", "List a -> Int");
    g("List.reverse", "List a -> List a");
    g("List.member", "a -> List a -> Bool");
    g("List.append", "List a -> List a -> List a");
    g("List.concat", "List (List a) -> List a");
    g("List.sum", "List number -> number");
    g("List.product", "List number -> number");
    g("List.head", "List a -> Maybe a");
    g("List.tail", "List a -> Maybe (List a)");
    g("List.isEmpty", "List a -> Bool");
    g("List.map2", "(a -> b -> c) -> List a -> List b -> List c");
    g("List.singleton", "a -> List a");

    // String.
    g("String.length", "String -> Int");
    g("String.reverse", "String -> String");
    g("String.toUpper", "String -> String");
    g("String.toLower", "String -> String");
    g("String.trim", "String -> String");
    g("String.append", "String -> String -> String");
    g("String.fromInt", "Int -> String");
    g("String.fromFloat", "Float -> String");
    g("String.fromChar", "Char -> String");
    g("String.toInt", "String -> Maybe Int");
    g("String.join", "String -> List String -> String");
    g("String.split", "String -> String -> List String");
    g("String.contains", "String -> String -> Bool");
    g("String.isEmpty", "String -> Bool");

    // Maybe / Result.
    g("Maybe.withDefault", "a -> Maybe a -> a");
    g("Maybe.map", "(a -> b) -> Maybe a -> Maybe b");
    g("Maybe.andThen", "(a -> Maybe b) -> Maybe a -> Maybe b");
    g("Result.withDefault", "a -> Result e a -> a");
    g("Result.map", "(a -> b) -> Result e a -> Result e b");
    g("Result.toMaybe", "Result e a -> Maybe a");

    // Tuple.
    g("Tuple.pair", "a -> b -> ( a, b )");
    g("Tuple.first", "( a, b ) -> a");
    g("Tuple.second", "( a, b ) -> b");
  }
}
