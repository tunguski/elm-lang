package pl.matsuo.elm.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import pl.matsuo.elm.util.Resources;

/** Exercises the bundled {@code Csv} parse/encode library through the interpreter. */
class CsvLibraryTest {

  private static final String LIB = Resources.read("/elm/lib/Csv.elm");

  private static final String SRC =
      """
      module Main exposing (simple, quotedCommas, quotedQuote, roundTrip, record, missing, trailing)

      import Csv exposing (..)

      simple : List (List String)
      simple = parse "a,b,c\\n1,2,3"

      quotedCommas : List String
      quotedCommas = Maybe.withDefault [] (List.head (parse "x,\\"a,b\\",y"))

      quotedQuote : String
      quotedQuote = Maybe.withDefault "" (List.head (Maybe.withDefault [] (List.head (parse "\\"he said \\"\\"hi\\"\\"\\"")) ))

      roundTrip : String
      roundTrip = encode [ [ "name", "city" ], [ "Ada", "London, UK" ] ]

      record : String
      record =
          case parseWithHeader "name,city\\nAda,Paris" of
              r :: _ -> Maybe.withDefault "?" (get "city" r)
              [] -> "none"

      missing : String
      missing =
          case parseWithHeader "name,city\\nAda,Paris" of
              r :: _ -> Maybe.withDefault "<none>" (get "age" r)
              [] -> "none"

      trailing : Int
      trailing = List.length (parse "a\\nb\\n")
      """;

  private static String value(String name) {
    return Show.plain(Project.load(SRC, LIB).value("Main", name));
  }

  @Test
  void parsesRowsAndQuotedFields() {
    assertEquals("[[\"a\",\"b\",\"c\"],[\"1\",\"2\",\"3\"]]", value("simple"));
    assertEquals("[\"x\",\"a,b\",\"y\"]", value("quotedCommas")); // the quoted field keeps its comma
    assertEquals("he said \"hi\"", value("quotedQuote")); // "" -> "
  }

  @Test
  void encodesWithQuotingAndRoundTrips() {
    assertEquals("name,city\nAda,\"London, UK\"", value("roundTrip"));
  }

  @Test
  void headerRecordsAndTrailingNewline() {
    assertEquals("Paris", value("record"));
    assertEquals("<none>", value("missing"));
    assertEquals("2", value("trailing")); // a trailing newline does not add an empty row
  }
}
