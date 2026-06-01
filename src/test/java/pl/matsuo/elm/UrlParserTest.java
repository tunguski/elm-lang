package pl.matsuo.elm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import pl.matsuo.elm.interp.Project;
import pl.matsuo.elm.interp.Show;
import pl.matsuo.elm.util.Resources;

/** The bundled Url.Parser typed router: matches path segments and captures ints/strings, combining
 *  with </>, and parses a Url (from Url.fromString) into a route value. */
class UrlParserTest {

  private static final String PARSER = Resources.read("/elm/lib/Url/Parser.elm");
  private static final String QUERY = Resources.read("/elm/lib/Url/Parser/Query.elm");

  private static final String SRC =
      """
      module Main exposing (home, user42, postHello, unknown, searchElm, searchNone, docTop)

      import Url
      import Url.Parser exposing (..)
      import Url.Parser.Query as Query

      type Route
          = Home
          | User Int
          | Post String
          | Search (Maybe String)
          | Doc (Maybe String)

      route : Parser (Route -> a) a
      route =
          oneOf
              [ map Home top
              , map User (s "user" </> int)
              , map Post (s "post" </> string)
              , map Search (s "search" <?> Query.string "q")
              , map Doc (s "doc" </> fragment identity)
              ]

      run : String -> Maybe Route
      run path =
          case Url.fromString ("http://example.com" ++ path) of
              Just url ->
                  parse route url

              Nothing ->
                  Nothing

      home = run "/"
      user42 = run "/user/42"
      postHello = run "/post/hello"
      unknown = run "/nope/nope"
      searchElm = run "/search?q=elm"
      searchNone = run "/search"
      docTop = run "/doc#section-1"
      """;

  private static String value(String name) {
    return Show.plain(Project.load(SRC, PARSER, QUERY).value("Main", name));
  }

  @Test
  void matchesTheRootRoute() {
    assertEquals("Just Home", value("home"));
  }

  @Test
  void capturesAnIntSegment() {
    assertEquals("Just (User 42)", value("user42"));
  }

  @Test
  void capturesAStringSegment() {
    assertEquals("Just (Post \"hello\")", value("postHello"));
  }

  @Test
  void unmatchedPathIsNothing() {
    assertEquals("Nothing", value("unknown"));
  }

  @Test
  void readsAQueryParameter() {
    assertEquals("Just (Search (Just \"elm\"))", value("searchElm"));
    assertEquals("Just (Search Nothing)", value("searchNone")); // absent param -> Nothing
  }

  @Test
  void readsTheUrlFragment() {
    assertEquals("Just (Doc (Just \"section-1\"))", value("docTop"));
  }
}
