package pl.matsuo.elm.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import pl.matsuo.elm.interp.Project;
import pl.matsuo.elm.util.Resources;

/** Tests the POSIX-style script runner end to end (Elm `main : Posix.Io` -> real I/O). */
class ScriptRunnerTest {

  private static final String POSIX = Resources.read("/elm/lib/Posix.elm");
  private static final String WORDCOUNT = Resources.read("/elm/demos/wordcount.elm");

  private record Run(int code, String out) {}

  /** Loads `source` (plus Posix) and runs its `main` with the given args and stdin. */
  private Run runScript(String source, List<String> args, String stdin) {
    Object main = Project.load(source, POSIX).main();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int code =
        ScriptRunner.run(
            main,
            args,
            new BufferedReader(new StringReader(stdin)),
            new PrintStream(out, true, StandardCharsets.UTF_8));
    return new Run(code, out.toString(StandardCharsets.UTF_8));
  }

  @Test
  void wordcountCountsFilesAndPrintsTotal() throws Exception {
    Path dir = Files.createTempDirectory("wc-");
    Path a = dir.resolve("a.txt");
    Path b = dir.resolve("b.txt");
    Files.writeString(a, "abcde"); // 1 line, 1 word, 5 chars
    Files.writeString(b, "x y z"); // 1 line, 3 words, 5 chars

    Run r = runScript(WORDCOUNT, List.of(a.toString(), b.toString()), "");
    assertEquals(0, r.code());
    assertTrue(r.out().contains(a + ": 1 1 5"), r.out());
    assertTrue(r.out().contains(b + ": 1 3 5"), r.out());
    assertTrue(r.out().contains("total: 2 lines, 4 words, 10 chars"), r.out());
  }

  @Test
  void wordcountWithNoArgsExitsWithUsage() {
    Run r = runScript(WORDCOUNT, List.of(), "");
    assertEquals(2, r.code());
    assertTrue(r.out().contains("usage:"), r.out());
  }

  @Test
  void missingFileIsReportedAndExitsNonZero() {
    Run r = runScript(WORDCOUNT, List.of("/no/such/file.txt"), "");
    assertEquals(1, r.code());
    assertTrue(r.out().contains("error reading"), r.out());
  }

  @Test
  void readLineAndPrintEchoStdin() {
    String src =
        """
        module Main exposing (main)
        import Posix exposing (..)
        main : Io
        main =
            readLine (\\line -> print ("echo: " ++ line) done)
        """;
    Run r = runScript(src, List.of(), "hello\n");
    assertEquals(0, r.code());
    assertEquals("echo: hello", r.out().strip());
  }

  @Test
  void getArgsArePassedToTheScript() {
    String src =
        """
        module Main exposing (main)
        import Posix exposing (..)
        main : Io
        main =
            getArgs (\\args -> print (String.join "," args) (exit 3))
        """;
    Run r = runScript(src, List.of("one", "two", "three"), "");
    assertEquals(3, r.code());
    assertEquals("one,two,three", r.out().strip());
  }

  @Test
  void getEnvReadsEnvironmentVariables() {
    // A variable that effectively never exists -> the Nothing branch.
    String src =
        """
        module Main exposing (main)
        import Posix exposing (..)
        main : Io
        main =
            getEnv "DEFINITELY_NOT_SET_XYZ_123" (\\v ->
                case v of
                    Just s -> print ("got: " ++ s) done
                    Nothing -> print "unset" done)
        """;
    assertEquals("unset", runScript(src, List.of(), "").out().strip());
  }

  @Test
  void listDirListsDirectoryEntries() throws Exception {
    java.nio.file.Path dir = Files.createTempDirectory("ls-");
    Files.writeString(dir.resolve("a.txt"), "");
    Files.writeString(dir.resolve("b.txt"), "");
    String src =
        """
        module Main exposing (main)
        import Posix exposing (..)
        main : Io
        main =
            listDir "%s" (\\result ->
                case result of
                    Ok names -> print (String.join "," names) done
                    Err e -> print ("err: " ++ e) (exit 1))
        """
            .formatted(dir.toString().replace("\\", "\\\\"));
    Run r = runScript(src, List.of(), "");
    assertEquals(0, r.code());
    assertEquals("a.txt,b.txt", r.out().strip());
  }
}
