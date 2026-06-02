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

/** Runs the bundled demo scripts that showcase the text-processing libraries (Awk/M4/Csv) end to
 *  end, with the same library set `elm script` makes available. */
class ScriptGalleryTest {

  private static final String[] LIBS = {
    "/elm/lib/Posix.elm", "/elm/lib/Bash.elm", "/elm/lib/Site.elm",
    "/elm/lib/Awk.elm", "/elm/lib/M4.elm", "/elm/lib/Sed.elm", "/elm/lib/Csv.elm",
  };

  /** Loads a demo script with the full scripting library set and runs its `main`. */
  private String run(String demo, List<String> args) {
    String[] sources = new String[LIBS.length + 1];
    sources[0] = Resources.read("/elm/demos/" + demo);
    for (int i = 0; i < LIBS.length; i++) {
      sources[i + 1] = Resources.read(LIBS[i]);
    }
    Object main = Project.load(sources).main();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ScriptRunner.run(
        main, args, new BufferedReader(new StringReader("")),
        new PrintStream(out, true, StandardCharsets.UTF_8));
    return out.toString(StandardCharsets.UTF_8);
  }

  private Path tempFile(String name, String content) throws Exception {
    Path dir = Files.createTempDirectory("elm-script-gallery-");
    Path f = dir.resolve(name);
    Files.writeString(f, content, StandardCharsets.UTF_8);
    return f;
  }

  @Test
  void awkSumScriptEmitsTheAwkCommand() throws Exception {
    // The Awk builder composes the awk program text; the demo prints the command to run.
    String out = run("awk-sum.elm", List.of("2", "sales.csv")).trim();
    assertEquals("awk '{ s += $2 } END { print s }' sales.csv", out);
  }

  @Test
  void m4ExpandScriptExpandsMacros() throws Exception {
    Path f = tempFile("config.m4", "define(greet, Hello $1!)greet(world)");
    assertEquals("Hello world!", run("m4-expand.elm", List.of(f.toString())).trim());
  }

  @Test
  void csvReportScriptRendersAnHtmlTable() throws Exception {
    Path f = tempFile("people.csv", "name,age\nAda,36\n\"Tu, ring\",41\n");
    String html = run("csv-report.elm", List.of(f.toString()));
    assertTrue(html.contains("<th>name</th>") && html.contains("<th>age</th>"), html);
    assertTrue(html.contains("<td>Ada</td>") && html.contains("<td>36</td>"), html);
    assertTrue(html.contains("<td>Tu, ring</td>"), "quoted field with a comma survives: " + html);
  }
}
