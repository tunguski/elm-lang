package pl.matsuo.elm.repl;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ReplTest {

  private String session(String input) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Repl.loop(new StringReader(input), new PrintStream(out, true, StandardCharsets.UTF_8));
    return out.toString(StandardCharsets.UTF_8);
  }

  @Test
  void evaluatesExpressionsAndReportsErrors() throws Exception {
    String out = session("1 + 2 * 3\nList.map (\\x -> x * x) [1,2,3]\n:quit\n");
    assertTrue(out.contains("7"), out);
    assertTrue(out.contains("[1,4,9]"), out);
  }

  @Test
  void recoversFromAnErrorAndKeepsGoing() throws Exception {
    String out = session("nonexistentValue\n40 + 2\n");
    assertTrue(out.contains("Error:"), out); // unbound name reported
    assertTrue(out.contains("42"), out); // session continued after the error
  }

  @Test
  void definitionsPersistAcrossEntries() throws Exception {
    String out = session("double n = n * 2\nx = 21\ndouble x\n:quit\n");
    assertTrue(out.contains("42"), out); // both `double` and `x` were remembered
  }

  @Test
  void typeCommandShowsInferredType() throws Exception {
    String out = session(":type List.map\n:type 1 + 2\n:quit\n");
    assertTrue(out.contains("(a -> b) -> List a -> List b"), out);
    assertTrue(out.contains(": number"), out);
  }

  @Test
  void infoShowsTypeOfBuiltinsAndSessionDefinitions() throws Exception {
    // :info on a builtin shows its type; on a session definition it also echoes the source.
    String out = session(":info String.length\ndouble n = n * 2\n:info double\n:quit\n");
    assertTrue(out.contains("String.length : String -> Int"), out);
    assertTrue(out.contains("double : number -> number"), out);
    assertTrue(out.contains("double n = n * 2"), out); // the definition's source is shown
  }

  @Test
  void multiLineInputIsAccumulatedUntilComplete() throws Exception {
    String out = session("if 1 < 2 then\n  100\nelse\n  200\n:quit\n");
    assertTrue(out.contains("100"), out);
  }

  @Test
  void resetForgetsDefinitions() throws Exception {
    String out = session("y = 5\n:reset\ny\n:quit\n");
    assertTrue(out.contains("Error:"), out); // `y` is unknown again after :reset
  }

  @Test
  void loadBringsAFilesDefinitionsIntoScope() throws Exception {
    java.nio.file.Path f = java.nio.file.Files.createTempFile("repl-load-", ".elm");
    java.nio.file.Files.writeString(
        f, "module Helpers exposing (..)\n\nimport String\n\ndouble n = n * 2\n");
    String out = session(":load " + f + "\ndouble 21\n:quit\n");
    assertTrue(out.contains("loaded 1 definitions"), out);
    assertTrue(out.contains("42"), out); // the loaded `double` is now callable
    java.nio.file.Files.deleteIfExists(f);
  }

  @Test
  void historyListsEntries() throws Exception {
    String out = session("1 + 1\nx = 5\n:history\n:quit\n");
    assertTrue(out.contains("1  1 + 1"), out);
    assertTrue(out.contains("2  x = 5"), out);
  }

  @Test
  void topLevelDefsDropsHeadersAndImports() {
    var defs = Repl.topLevelDefs("module M exposing (..)\n\nimport String\n\nfoo = 1\n\nbar n = n\n");
    assertTrue(defs.stream().anyMatch(d -> d.startsWith("foo")), defs.toString());
    assertTrue(defs.stream().anyMatch(d -> d.startsWith("bar")), defs.toString());
    assertTrue(defs.stream().noneMatch(d -> d.startsWith("module") || d.startsWith("import")), defs.toString());
  }

  @Test
  void projectModulesArePreloadedIntoScope() throws Exception {
    // Two project modules' top-level definitions are in scope from the start (no :load needed).
    String mathSrc = "module Math exposing (square)\n\nsquare n = n * n\n";
    String strSrc = "module Strings exposing (shout)\n\nimport String\n\nshout s = s ++ \"!\"\n";
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Repl.loop(
        new StringReader("square 5\nshout \"hi\"\n:quit\n"),
        new PrintStream(out, true, StandardCharsets.UTF_8),
        java.util.List.of(mathSrc, strSrc));
    String s = out.toString(StandardCharsets.UTF_8);
    assertTrue(s.contains("loaded") && s.contains("definitions from the project"), s);
    assertTrue(s.contains("25"), s); // square 5
    assertTrue(s.contains("hi!"), s); // shout "hi"
  }

  @Test
  void loadAcceptsSeveralFilesAtOnce() throws Exception {
    java.nio.file.Path a = java.nio.file.Files.createTempFile("repl-a-", ".elm");
    java.nio.file.Path b = java.nio.file.Files.createTempFile("repl-b-", ".elm");
    java.nio.file.Files.writeString(a, "module A exposing (..)\n\ndouble n = n * 2\n");
    java.nio.file.Files.writeString(b, "module B exposing (..)\n\ntriple n = n * 3\n");
    String out = session(":load " + a + " " + b + "\ndouble (triple 7)\n:quit\n");
    assertTrue(out.contains("loaded 1 definitions from " + a), out);
    assertTrue(out.contains("loaded 1 definitions from " + b), out);
    assertTrue(out.contains("42"), out); // both files' definitions are in scope: double (triple 7)
    java.nio.file.Files.deleteIfExists(a);
    java.nio.file.Files.deleteIfExists(b);
  }

  @Test
  void browseListsSessionBindingsWithTypes() throws Exception {
    String out = session("double n = n * 2\nx = 21\n:browse\n:quit\n");
    assertTrue(out.contains("double : number -> number"), out);
    assertTrue(out.contains("x : number"), out);
    assertTrue(out.contains("built-ins in scope"), out);
  }

  @Test
  void completeListsInScopeNamesByPrefix() throws Exception {
    String out = session("doubler n = n * 2\n:complete doub\n:quit\n");
    assertTrue(out.contains("doubler"), out); // the session binding matches
    String builtins = session(":complete String.to\n:quit\n");
    assertTrue(builtins.contains("String.toUpper") || builtins.contains("String.toInt"), builtins);
  }

  @Test
  void completionsHelperUnionsSessionAndBuiltins() {
    var matches = Repl.completions(java.util.List.of("myValue = 1"), "my");
    assertTrue(matches.contains("myValue"), matches.toString());
    assertTrue(Repl.completions(java.util.List.of(), "List.ma").contains("List.map"),
        Repl.completions(java.util.List.of(), "List.ma").toString());
  }

  @Test
  void completeDetectsBalanceAndContinuations() {
    assertTrue(Repl.complete("1 + 2"));
    assertTrue(Repl.complete("begin")); // ends in "in" but isn't the keyword
    org.junit.jupiter.api.Assertions.assertFalse(Repl.complete("(1 +"));
    org.junit.jupiter.api.Assertions.assertFalse(Repl.complete("if x then"));
    org.junit.jupiter.api.Assertions.assertFalse(Repl.complete("x ="));
  }
}
