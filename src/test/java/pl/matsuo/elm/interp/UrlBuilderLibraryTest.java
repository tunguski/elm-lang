package pl.matsuo.elm.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import pl.matsuo.elm.util.Resources;

/** Exercises the bundled {@code Url.Builder} library through the interpreter. */
class UrlBuilderLibraryTest {

  private static final String LIB = Resources.read("/elm/lib/Url/Builder.elm");

  private static final String SRC =
      """
      module Main exposing (abs, rel, cross, noQuery)

      import Url.Builder as B

      abs : String
      abs = B.absolute [ "search" ] [ B.string "q" "a b", B.int "page" 2 ]

      rel : String
      rel = B.relative [ "a", "b" ] []

      cross : String
      cross = B.crossOrigin "https://x.com" [ "p" ] [ B.string "k" "v&v" ]

      noQuery : String
      noQuery = B.absolute [ "x", "y" ] []
      """;

  private static String value(String name) {
    return Show.plain(Project.load(SRC, LIB).value("Main", name));
  }

  @Test
  void buildsPathsAndQueryStrings() {
    assertEquals("/search?q=a%20b&page=2", value("abs"));
    assertEquals("a/b", value("rel"));
    assertEquals("https://x.com/p?k=v%26v", value("cross"));
    assertEquals("/x/y", value("noQuery")); // no params -> no '?'
  }
}
