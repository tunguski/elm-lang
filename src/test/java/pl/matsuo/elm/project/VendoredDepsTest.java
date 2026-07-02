package pl.matsuo.elm.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link VendoredDeps}: manifest parsing, local-override, include/exclude filtering, and git clone. */
class VendoredDepsTest {

  @TempDir Path tmp;

  @Test
  void readParsesDependencies() throws IOException {
    Path proj = Files.createDirectories(tmp.resolve("app"));
    Files.writeString(
        proj.resolve("elm.vendored.json"),
        """
        { "dependencies": [
          { "name": "lib", "repo": "https://ex/lib.git", "ref": "v1",
            "include": ["A.elm", "B/**"], "exclude": ["Main.elm"] }
        ] }
        """);
    List<VendoredDeps.Dep> deps = VendoredDeps.read(proj);
    assertEquals(1, deps.size());
    VendoredDeps.Dep d = deps.get(0);
    assertEquals("lib", d.name());
    assertEquals("v1", d.ref());
    assertEquals("src", d.source()); // default
    assertEquals(List.of("A.elm", "B/**"), d.include());
    assertEquals(List.of("Main.elm"), d.exclude());
  }

  @Test
  void localOverrideUsesTheWorkingTreeAndExcludeDropsMain() throws IOException {
    // A library checkout with a Main.elm we must NOT pull (it would clash) plus the library modules.
    Path lib = tmp.resolve("lib");
    Files.createDirectories(lib.resolve("src/Workspace"));
    Files.writeString(lib.resolve("src/Main.elm"), "module Main exposing (main)\nmain = 0\n");
    Files.writeString(lib.resolve("src/Workspace.elm"), "module Workspace exposing (v)\nv = 1\n");
    Files.writeString(lib.resolve("src/Workspace/Site.elm"), "module Workspace.Site exposing (s)\ns = 2\n");

    Path proj = tmp.resolve("app");
    Files.createDirectories(proj.resolve("src"));
    Files.writeString(proj.resolve("elm.json"), APP_ELM_JSON);
    Files.writeString(proj.resolve("src/App.elm"), "module App exposing (x)\nx = 1\n");
    Files.writeString(
        proj.resolve("elm.vendored.json"),
        """
        { "dependencies": [
          { "name": "lib", "repo": "unused", "ref": "unused",
            "include": ["Workspace.elm", "Workspace/**"], "exclude": ["Main.elm"] }
        ] }
        """);
    Files.writeString(proj.resolve("elm.vendored.local.json"), "{ \"lib\": \"../lib\" }");

    List<String> sources = ProjectLoader.loadSources(proj);
    assertTrue(has(sources, "module Workspace exposing"), "library module pulled in");
    assertTrue(has(sources, "module Workspace.Site"), "nested library module pulled in");
    assertTrue(has(sources, "module App exposing"), "the app's own module");
    assertFalse(has(sources, "module Main exposing"), "the dep's Main.elm is excluded");
  }

  @Test
  void includeGlobSelectsOnlyMatchingModules() throws IOException {
    Path lib = tmp.resolve("lib2/src");
    Files.createDirectories(lib);
    Files.writeString(lib.resolve("Chart.elm"), "module Chart exposing (c)\nc = 1\n");
    Files.writeString(lib.resolve("Draw.elm"), "module Draw exposing (d)\nd = 2\n");

    Path proj = tmp.resolve("app2");
    Files.createDirectories(proj.resolve("src"));
    Files.writeString(proj.resolve("elm.json"), APP_ELM_JSON);
    Files.writeString(
        proj.resolve("elm.vendored.json"),
        "{ \"dependencies\": [ { \"name\": \"lib2\", \"repo\": \"u\", \"ref\": \"u\","
            + " \"include\": [\"Chart.elm\"] } ] }");
    Files.writeString(proj.resolve("elm.vendored.local.json"), "{ \"lib2\": \"../lib2\" }");

    List<String> sources = ProjectLoader.loadSources(proj);
    assertTrue(has(sources, "module Chart"), "included module present");
    assertFalse(has(sources, "module Draw"), "non-included module absent");
  }

  @Test
  void gitCloneChecksOutTheRefAndAddsItsSources() throws Exception {
    assumeTrue(gitAvailable());
    // Build a real git repo to act as the remote.
    Path remote = tmp.resolve("remote");
    Files.createDirectories(remote.resolve("src"));
    Files.writeString(remote.resolve("src/Lib.elm"), "module Lib exposing (n)\nn = 42\n");
    Files.writeString(remote.resolve("src/Main.elm"), "module Main exposing (main)\nmain = 0\n");
    git(remote, "init", "-q");
    git(remote, "-c", "user.email=t@t", "-c", "user.name=t", "add", ".");
    git(remote, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", "init");
    String sha = capture(remote, "rev-parse", "HEAD").trim();

    Path proj = tmp.resolve("gitapp");
    Files.createDirectories(proj.resolve("src"));
    Files.writeString(proj.resolve("elm.json"), APP_ELM_JSON);
    Files.writeString(
        proj.resolve("elm.vendored.json"),
        "{ \"dependencies\": [ { \"name\": \"lib\", \"repo\": \""
            + remote.toString().replace("\\", "/")
            + "\", \"ref\": \""
            + sha
            + "\", \"exclude\": [\"Main.elm\"] } ] }");

    List<String> sources = ProjectLoader.loadSources(proj);
    assertTrue(has(sources, "module Lib exposing"), "cloned dep's module is compiled");
    assertFalse(has(sources, "module Main exposing"), "excluded Main.elm not pulled");
    assertTrue(Files.isDirectory(proj.resolve("git-deps/lib/.git")), "cloned into git-deps/");
  }

  // --- helpers -----------------------------------------------------------

  private static final String APP_ELM_JSON =
      """
      { "type": "application", "source-directories": ["src"], "elm-version": "0.19.1",
        "dependencies": { "direct": {}, "indirect": {} },
        "test-dependencies": { "direct": {}, "indirect": {} } }
      """;

  private static boolean has(List<String> sources, String needle) {
    return sources.stream().anyMatch(s -> s.contains(needle));
  }

  private static boolean gitAvailable() {
    try {
      return new ProcessBuilder("git", "--version").start().waitFor(10, TimeUnit.SECONDS);
    } catch (IOException | InterruptedException e) {
      return false;
    }
  }

  private static void git(Path cwd, String... args) throws Exception {
    String[] cmd = new String[args.length + 1];
    cmd[0] = "git";
    System.arraycopy(args, 0, cmd, 1, args.length);
    Process p = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true).start();
    p.getInputStream().readAllBytes();
    if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0) {
      throw new IllegalStateException("git " + String.join(" ", args) + " failed");
    }
  }

  private static String capture(Path cwd, String... args) throws Exception {
    String[] cmd = new String[args.length + 1];
    cmd[0] = "git";
    System.arraycopy(args, 0, cmd, 1, args.length);
    Process p = new ProcessBuilder(cmd).directory(cwd.toFile()).start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    p.waitFor(30, TimeUnit.SECONDS);
    return out;
  }
}
