package pl.matsuo.elm.pkg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Parsing an application elm.json, including its test-dependencies. */
class ElmJsonTest {

  @Test
  void parsesTestDependenciesSeparatelyFromRegularOnes() {
    String json =
        """
        { "type": "application",
          "source-directories": [ "src" ],
          "dependencies": {
            "direct": { "elm/core": "1.0.5" },
            "indirect": {}
          },
          "test-dependencies": {
            "direct": { "elm-explorations/test": "2.1.0" },
            "indirect": { "elm/random": "1.0.0" }
          }
        }
        """;
    ElmJson j = ElmJson.parse(json);
    assertTrue(j.all().containsKey("elm/core"), j.all().toString());
    assertTrue(!j.all().containsKey("elm-explorations/test"), "test deps are not regular deps");
    assertEquals(Version.parse("2.1.0"), j.testDependencies().get("elm-explorations/test"));
    assertEquals(Version.parse("1.0.0"), j.testDependencies().get("elm/random"));
    // allWithTests merges both.
    assertTrue(j.allWithTests().containsKey("elm/core") && j.allWithTests().containsKey("elm-explorations/test"));
  }
}
