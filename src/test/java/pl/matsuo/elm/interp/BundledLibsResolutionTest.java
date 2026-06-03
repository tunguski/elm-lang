package pl.matsuo.elm.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import pl.matsuo.elm.types.TypeChecker;

/**
 * The pure bundled libraries (List.Extra, Maybe.Extra, …) resolve on demand: a program can import
 * them without the caller supplying their source. See {@link BundledLibs}.
 */
class BundledLibsResolutionTest {

  @Test
  void interpreterResolvesAnImportedBundledLib() {
    // Only the user module is supplied; List.Extra is pulled in automatically.
    String src =
        """
        module Main exposing (answer)

        import List.Extra as LE

        answer = LE.last [ 1, 2, 3 ]
        """;
    assertEquals("Just 3", Show.plain(Project.load(src).value("Main", "answer")));
  }

  @Test
  void interpreterResolvesTransitivelyAndMultipleLibs() {
    String src =
        """
        module Main exposing (out)

        import Maybe.Extra as ME
        import String.Extra as SE

        out = ME.values [ Just (SE.toSentenceCase "hi"), Nothing, Just "x" ]
        """;
    assertEquals("[\"Hi\",\"x\"]", Show.plain(Project.load(src).value("Main", "out")));
  }

  @Test
  void typeCheckerResolvesAnImportedBundledLib() {
    // Previously this failed with "Unknown name: LE.last".
    String src =
        """
        module Main exposing (main)

        import List.Extra as LE

        main = LE.last [ 1, 2, 3 ]
        """;
    assertTrue(TypeChecker.checkModule(src).containsKey("main"), "type-checks with the lib imported");
  }
}
