package pl.matsuo.elm.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import pl.matsuo.elm.util.Resources;

/** Exercises the bundled {@code Sed} stream-editor library through the interpreter. */
class SedLibraryTest {

  private static final String LIB = Resources.read("/elm/lib/Sed.elm");

  private static final String SRC =
      """
      module Main exposing (subFirst, subAll, ampersand, dropped, kept, rng, tr, piped)

      import Sed exposing (..)

      text : String
      text = "foo 1 1\\n# comment\\nbar 2 2\\nbaz 3 3"

      subFirst : String
      subFirst = substitute "[0-9]" "#" "1 2 3"

      subAll : String
      subAll = substituteAll "[0-9]" "#" "1 2 3"

      ampersand : String
      ampersand = substituteAll "[0-9]+" "<&>" "a12 b3"

      dropped : String
      dropped = deleteMatching "^#" text

      kept : String
      kept = keepMatching "ba" text

      rng : String
      rng = lineRange 2 3 text

      tr : String
      tr = transliterate "abc" "xyz" "cabbage"

      piped : String
      piped = text |> deleteMatching "^#" |> substituteAll " +" " "
      """;

  private static String value(String name) {
    return Show.plain(Project.load(SRC, LIB).value("Main", name));
  }

  @Test
  void substitution() {
    assertEquals("# 2 3", value("subFirst")); // only the first digit on the line
    assertEquals("# # #", value("subAll"));
    assertEquals("a<12> b<3>", value("ampersand"));
  }

  @Test
  void deleteKeepAndRange() {
    assertEquals("foo 1 1\nbar 2 2\nbaz 3 3", value("dropped"));
    assertEquals("bar 2 2\nbaz 3 3", value("kept"));
    assertEquals("# comment\nbar 2 2", value("rng"));
  }

  @Test
  void transliterateAndPipe() {
    assertEquals("zxyyxge", value("tr")); // c->z a->x b->y b->y a->x g o e unchanged
    assertEquals("foo 1 1\nbar 2 2\nbaz 3 3", value("piped"));
  }
}
