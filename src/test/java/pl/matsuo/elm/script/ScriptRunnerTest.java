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
  private static final String BASH = Resources.read("/elm/lib/Bash.elm");
  private static final String SITE = Resources.read("/elm/lib/Site.elm");
  private static final String WORDCOUNT = Resources.read("/elm/demos/wordcount.elm");
  private static final String FOLDERREPORT = Resources.read("/elm/demos/folderreport.elm");

  private record Run(int code, String out) {}

  /** Loads `source` (plus Posix) and runs its `main` with the given args and stdin. */
  private Run runScript(String source, List<String> args, String stdin) {
    return runScript(source, args, stdin, false);
  }

  /** As {@link #runScript}, optionally also bundling the Bash module. */
  private Run runScript(String source, List<String> args, String stdin, boolean withBash) {
    Object main =
        (withBash ? Project.load(source, POSIX, BASH) : Project.load(source, POSIX)).main();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int code =
        ScriptRunner.run(
            main,
            args,
            new BufferedReader(new StringReader(stdin)),
            new PrintStream(out, true, StandardCharsets.UTF_8));
    return new Run(code, out.toString(StandardCharsets.UTF_8));
  }

  /** Runs a script with the Posix, Bash and Site libraries all available (the `elm script` set). */
  private Run runScriptWithSite(String source, List<String> args) {
    Object main = Project.load(source, POSIX, BASH, SITE).main();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int code =
        ScriptRunner.run(
            main, args, new BufferedReader(new StringReader("")),
            new PrintStream(out, true, StandardCharsets.UTF_8));
    return new Run(code, out.toString(StandardCharsets.UTF_8));
  }

  @Test
  void scriptGeneratesAPagePerFileInAFolderUsingSite() throws Exception {
    // The disk-driven site-generation pattern documented in docs/site.md: read each Markdown file in
    // a folder (Posix) and render it to an HTML page (Site), one page per file.
    Path dir = Files.createTempDirectory("gen-");
    Files.writeString(dir.resolve("intro.md"), "# Intro\n\nHello world.");
    String script =
        "module Main exposing (main)\n"
            + "import Posix exposing (..)\n"
            + "import Site\n"
            + "main : Io\n"
            + "main =\n"
            + "    getArgs (\\args ->\n"
            + "        case args of\n"
            + "            dir :: _ -> listDir dir (\\res -> case res of\n"
            + "                Ok names -> gen dir names\n"
            + "                Err e -> print e (exit 1))\n"
            + "            [] -> exit 1)\n"
            + "gen : String -> List String -> Io\n"
            + "gen dir names =\n"
            + "    case names of\n"
            + "        [] -> done\n"
            + "        name :: rest ->\n"
            + "            readFile (dir ++ \"/\" ++ name) (\\res -> case res of\n"
            + "                Ok content ->\n"
            + "                    writeFile (dir ++ \"/\" ++ name ++ \".html\")\n"
            + "                        (Site.render (Site.page (name ++ \".html\") name (Site.markdown content)))\n"
            + "                        (gen dir rest)\n"
            + "                Err _ -> gen dir rest)\n";
    Run r = runScriptWithSite(script, List.of(dir.toString()));
    assertEquals(0, r.code(), r.out());
    Path html = dir.resolve("intro.md.html");
    assertTrue(Files.exists(html), "a page was generated for the file");
    String page = Files.readString(html, StandardCharsets.UTF_8);
    assertTrue(page.contains("<h1>Intro</h1>"), page); // heading from the Markdown
    assertTrue(page.contains("<p>Hello world.</p>"), page); // body from the file's content
  }

  @Test
  void scriptDecodesAJsonManifestToDriveGeneration() throws Exception {
    // The JSON-manifest site-generation pattern: a script reads a manifest (Posix) and decodes it
    // (Json.Decode) to learn which files a topic gathers — the novel piece beyond reading a folder.
    Path dir = Files.createTempDirectory("topic-");
    Files.writeString(
        dir.resolve("parser.json"),
        "{ \"title\": \"Parser\", \"files\": [ \"a.elm\", \"b.elm\" ] }");
    String script =
        "module Main exposing (main)\n"
            + "import Posix exposing (..)\n"
            + "import Json.Decode as D\n"
            + "type alias Topic = { title : String, files : List String }\n"
            + "decoder = D.map2 Topic (D.field \"title\" D.string) (D.field \"files\" (D.list D.string))\n"
            + "main : Io\n"
            + "main =\n"
            + "    getArgs (\\args -> case args of\n"
            + "        m :: _ -> readFile m (\\res -> case res of\n"
            + "            Ok json -> (case D.decodeString decoder json of\n"
            + "                Ok t -> print (t.title ++ \" has \" ++ String.fromInt (List.length t.files) ++ \" files\") done\n"
            + "                Err e -> print e (exit 1))\n"
            + "            Err e -> print e (exit 1))\n"
            + "        [] -> exit 1)\n";
    Run r = runScriptWithSite(script, List.of(dir.resolve("parser.json").toString()));
    assertEquals(0, r.code(), r.out());
    assertTrue(r.out().contains("Parser has 2 files"), r.out());
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
  void scriptsCanReadTheClockAndDrawRandomness() {
    // now: the current time is a positive number of millis.
    Run clock =
        runScript(
            "module Main exposing (main)\nimport Posix exposing (..)\n"
                + "main = now (\\ms -> print (if ms > 0 then \"clock-ok\" else \"bad\") done)",
            List.of(),
            "");
    assertTrue(clock.out().contains("clock-ok"), clock.out());

    // randomInt with lo == hi is deterministic.
    Run fixed =
        runScript(
            "module Main exposing (main)\nimport Posix exposing (..)\n"
                + "main = randomInt 7 7 (\\n -> print (String.fromInt n) done)",
            List.of(),
            "");
    assertEquals("7", fixed.out().trim());

    // randomInt stays within the inclusive range.
    Run ranged =
        runScript(
            "module Main exposing (main)\nimport Posix exposing (..)\n"
                + "main = randomInt 1 6 (\\n -> print (String.fromInt n) done)",
            List.of(),
            "");
    int v = Integer.parseInt(ranged.out().trim());
    assertTrue(v >= 1 && v <= 6, "random in range: " + v);
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

  // ---- Structured Bash commands ----------------------------------------------------------------

  /** A path as an Elm string literal (Windows backslashes escaped). */
  private static String lit(Object path) {
    return path.toString().replace("\\", "\\\\");
  }

  @Test
  void bashLsReturnsStructuredEntriesWithSizes() throws Exception {
    Path dir = Files.createTempDirectory("bash-ls-");
    Files.writeString(dir.resolve("a.txt"), "hello"); // 5 bytes
    Files.writeString(dir.resolve("b.txt"), "hi"); // 2 bytes
    String src =
        """
        module Main exposing (main)
        import Bash exposing (..)
        main : Io
        main =
            ls "%s" (\\result ->
                case result of
                    Ok entries -> printLines (List.map (\\e -> e.name ++ " " ++ String.fromInt e.size ++ " " ++ boolStr e.isDir) entries) done
                    Err e -> print ("err: " ++ e) (exit 1))
        printLines lines rest = List.foldr print rest lines
        boolStr b = if b then "dir" else "file"
        """
            .formatted(lit(dir));
    Run r = runScript(src, List.of(), "", true);
    assertEquals(0, r.code());
    assertTrue(r.out().contains("a.txt 5 file"), r.out());
    assertTrue(r.out().contains("b.txt 2 file"), r.out());
  }

  @Test
  void bashGrepReturnsMatchesWithLineNumbers() throws Exception {
    Path dir = Files.createTempDirectory("bash-grep-");
    Path file = dir.resolve("notes.txt");
    Files.writeString(file, "first line\nTODO fix this\nthird line\nTODO and that\n");
    String src =
        """
        module Main exposing (main)
        import Bash exposing (..)
        main : Io
        main =
            grep "TODO" "%s" (\\result ->
                case result of
                    Ok ms -> printLines (List.map (\\m -> String.fromInt m.lineNumber ++ ":" ++ m.line) ms) done
                    Err e -> print ("err: " ++ e) (exit 1))
        printLines lines rest = List.foldr print rest lines
        """
            .formatted(lit(file));
    Run r = runScript(src, List.of(), "", true);
    assertEquals(0, r.code());
    assertTrue(r.out().contains("2:TODO fix this"), r.out());
    assertTrue(r.out().contains("4:TODO and that"), r.out());
  }

  @Test
  void bashWcCountsLinesWordsChars() throws Exception {
    Path dir = Files.createTempDirectory("bash-wc-");
    Path file = dir.resolve("text.txt");
    Files.writeString(file, "one two\nthree four five\n"); // 2 lines, 5 words
    String src =
        """
        module Main exposing (main)
        import Bash exposing (..)
        main : Io
        main =
            wc "%s" (\\result ->
                case result of
                    Ok c -> print (String.fromInt c.lines ++ " " ++ String.fromInt c.words) done
                    Err e -> print ("err: " ++ e) (exit 1))
        """
            .formatted(lit(file));
    Run r = runScript(src, List.of(), "", true);
    assertEquals(0, r.code());
    assertEquals("2 5", r.out().strip());
  }

  @Test
  void bashMkdirCopyMoveRemoveRoundTrip() throws Exception {
    Path dir = Files.createTempDirectory("bash-fs-");
    Path src1 = dir.resolve("src.txt");
    Files.writeString(src1, "payload");
    Path sub = dir.resolve("sub");
    Path copy = sub.resolve("copy.txt");
    Path moved = sub.resolve("moved.txt");
    String src =
        """
        module Main exposing (main)
        import Bash exposing (..)
        main : Io
        main =
            mkdir "%s" (\\_ ->
            cp "%s" "%s" (\\_ ->
            mv "%s" "%s" (\\_ ->
            print "ok" done)))
        """
            .formatted(lit(sub), lit(src1), lit(copy), lit(copy), lit(moved));
    Run r = runScript(src, List.of(), "", true);
    assertEquals(0, r.code());
    assertEquals("ok", r.out().strip());
    assertTrue(Files.exists(moved), "moved file exists");
    assertTrue(!Files.exists(copy), "copy was moved away");
    assertEquals("payload", Files.readString(moved));
  }

  @Test
  void bashHeadTailSortUniq() throws Exception {
    Path dir = Files.createTempDirectory("bash-lines-");
    Path file = dir.resolve("data.txt");
    Files.writeString(file, "banana\napple\napple\ncherry\n");
    String src =
        """
        module Main exposing (main)
        import Bash exposing (..)
        main : Io
        main =
            head 2 "%s" (\\h ->
            sort "%s" (\\s ->
            uniq "%s" (\\u ->
            case (h, s, u) of
                (Ok hs, Ok ss, Ok us) ->
                    print ("head=" ++ String.join "," hs)
                        (print ("sort=" ++ String.join "," ss)
                            (print ("uniq=" ++ String.join "," us) done))
                _ -> exit 1)))
        """
            .formatted(lit(file), lit(file), lit(file));
    Run r = runScript(src, List.of(), "", true);
    assertEquals(0, r.code());
    assertTrue(r.out().contains("head=banana,apple"), r.out());
    assertTrue(r.out().contains("sort=apple,apple,banana,cherry"), r.out());
    assertTrue(r.out().contains("uniq=banana,apple,cherry"), r.out()); // adjacent apples collapsed
  }

  @Test
  void bashDuAndTouchAndStat() throws Exception {
    Path dir = Files.createTempDirectory("bash-meta-");
    Files.writeString(dir.resolve("a.txt"), "12345"); // 5 bytes
    Path made = dir.resolve("new.txt");
    String src =
        """
        module Main exposing (main)
        import Bash exposing (..)
        main : Io
        main =
            touch "%s" (\\_ ->
            du "%s" (\\d ->
            case d of
                Ok bytes -> print ("du=" ++ String.fromInt bytes) done
                Err e -> print e (exit 1)))
        """
            .formatted(lit(made), lit(dir));
    Run r = runScript(src, List.of(), "", true);
    assertEquals(0, r.code());
    assertTrue(Files.exists(made), "touch created the file");
    assertTrue(r.out().contains("du=5"), r.out()); // only a.txt has bytes
  }

  @Test
  void bashExecCapturesStructuredResult() throws Exception {
    // Run the JVM's own `java -version` (always present); assert a structured Proc comes back.
    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    String src =
        """
        module Main exposing (main)
        import Bash exposing (..)
        main : Io
        main =
            exec "%s" [ "-version" ] (\\result ->
                case result of
                    Ok proc -> print ("exit=" ++ String.fromInt proc.exitCode) done
                    Err e -> print ("err: " ++ e) (exit 1))
        """
            .formatted(lit(java));
    Run r = runScript(src, List.of(), "", true);
    assertEquals(0, r.code());
    assertTrue(r.out().contains("exit=0"), r.out());
  }

  @Test
  void folderReportSummarisesADirectory() throws Exception {
    Path dir = Files.createTempDirectory("report-");
    Files.writeString(dir.resolve("a.elm"), "module A exposing (..)\n");
    Files.writeString(dir.resolve("b.elm"), "module B exposing (..)\n");
    Files.writeString(dir.resolve("readme.md"), "# hi\n");
    Run r = runScript(FOLDERREPORT, List.of(dir.toString()), "", true);
    assertEquals(0, r.code());
    assertTrue(r.out().contains("Folder report for " + dir), r.out());
    assertTrue(r.out().contains("Files:"), r.out());
    assertTrue(r.out().contains("By extension:"), r.out());
    assertTrue(r.out().contains(".elm: 2"), r.out()); // two .elm files grouped
  }
}
