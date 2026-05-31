package pl.matsuo.elm.bundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Building the standalone executable JAR (its manifest entry point and embedded Elm app). */
class BundlerTest {

  private static final String SCRIPT =
      "module Main exposing (main)\n"
          + "import Bash exposing (..)\n"
          + "main : Io\n"
          + "main = print \"bundled!\" done\n";

  @Test
  void buildsAnExecutableJarWithLauncherAndEmbeddedApp(@TempDir Path dir) throws Exception {
    Path jar = dir.resolve("app.jar");
    // A minimal classpath (just our compiled classes) keeps the test fast; the real command bundles
    // the whole runtime classpath.
    Bundler.buildJar(List.of("target/classes"), SCRIPT, "script", 8080, jar);
    assertTrue(Files.exists(jar), "jar produced");
    try (JarFile jf = new JarFile(jar.toFile())) {
      assertEquals(Bundler.LAUNCHER, jf.getManifest().getMainAttributes().getValue("Main-Class"));
      assertNotNull(jf.getEntry("META-INF/elm/app.elm"), "embeds the Elm source");
      assertEquals("script", read(jf, "META-INF/elm/mode"));
      assertEquals("8080", read(jf, "META-INF/elm/port"));
      assertTrue(read(jf, "META-INF/elm/app.elm").contains("bundled!"), "source content embedded");
      // The launcher class itself is bundled, so the jar is self-launching.
      assertNotNull(jf.getEntry("pl/matsuo/elm/bundle/Standalone.class"), "launcher class present");
    }
  }

  @Test
  void serverModeBakesInThePort(@TempDir Path dir) throws Exception {
    Path jar = dir.resolve("srv.jar");
    Bundler.buildJar(List.of("target/classes"), SCRIPT, "server", 9090, jar);
    try (JarFile jf = new JarFile(jar.toFile())) {
      assertEquals("server", read(jf, "META-INF/elm/mode"));
      assertEquals("9090", read(jf, "META-INF/elm/port"));
    }
  }

  @Test
  void producedJarRunsStandalone(@TempDir Path dir) throws Exception {
    // End-to-end: build a self-contained jar from the packaged shaded jar and run it with `java -jar`,
    // proving the artifact needs no project files. Skipped until `mvn package` has produced target/elm.jar.
    Path shaded = Path.of("target/elm.jar");
    org.junit.jupiter.api.Assumptions.assumeTrue(
        Files.exists(shaded), "run after `mvn package` (needs target/elm.jar)");
    Path jar = dir.resolve("standalone.jar");
    Bundler.buildJar(List.of(shaded.toString()), SCRIPT, "script", 8080, jar);
    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    Process p = new ProcessBuilder(java, "-jar", jar.toString()).redirectErrorStream(true).start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    p.waitFor();
    assertTrue(out.contains("bundled!"), out);
  }

  @Test
  void isNotItselfRunningAsANativeImageUnderTest() {
    // The gating predicate: tests run on a normal JVM, never inside a native image.
    assertFalse(Bundler.runningAsNativeImage());
  }

  private static String read(JarFile jf, String entry) throws Exception {
    try (var in = jf.getInputStream(jf.getEntry(entry))) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
