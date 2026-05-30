package pl.matsuo.elm.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.matsuo.elm.interp.Show;

/**
 * Verifies that an installed third-party package's sources are loaded from the cache into the
 * project, so the type checker and interpreter see its modules — i.e. the package manager is
 * end-to-end usable, not just a manifest solver.
 */
class ProjectLoaderTest {

  /** A small package in the cache: acme/strings 1.0.0 exposing Acme.Strings.shout. */
  private static void writeAcmeStrings(Path registry) throws IOException {
    Path dir = registry.resolve("acme").resolve("strings").resolve("1.0.0");
    Files.createDirectories(dir.resolve("src").resolve("Acme"));
    Files.writeString(
        dir.resolve("elm.json"),
        "{ \"type\": \"package\", \"name\": \"acme/strings\", \"version\": \"1.0.0\","
            + " \"exposed-modules\": [\"Acme.Strings\"], \"dependencies\": {} }",
        StandardCharsets.UTF_8);
    Files.writeString(
        dir.resolve("src").resolve("Acme").resolve("Strings.elm"),
        "module Acme.Strings exposing (shout)\n\nshout : String -> String\nshout s =\n    s ++ \"!\"\n",
        StandardCharsets.UTF_8);
  }

  private static Path writeApp(Path root, String mainBody) throws IOException {
    Path project = root.resolve("app");
    Files.createDirectories(project.resolve("src"));
    Files.writeString(
        project.resolve("elm.json"),
        "{\n  \"type\": \"application\",\n  \"source-directories\": [ \"src\" ],\n"
            + "  \"elm-version\": \"0.19.1\",\n"
            + "  \"dependencies\": { \"direct\": { \"acme/strings\": \"1.0.0\" }, \"indirect\": {} },\n"
            + "  \"test-dependencies\": { \"direct\": {}, \"indirect\": {} }\n}\n",
        StandardCharsets.UTF_8);
    Files.writeString(project.resolve("src").resolve("Main.elm"), mainBody, StandardCharsets.UTF_8);
    return project;
  }

  @Test
  void loadsLocalAndDependencyPackageSources(@TempDir Path root) throws IOException {
    writeAcmeStrings(root.resolve("registry"));
    Path app =
        writeApp(
            root,
            "module Main exposing (main)\n\nimport Acme.Strings\n\nmain = Acme.Strings.shout \"hi\"\n");

    List<String> sources = ProjectLoader.loadSources(app, root.resolve("registry"));
    assertTrue(
        sources.stream().anyMatch(s -> s.contains("module Acme.Strings")),
        "the package's source module must be loaded");
  }

  @Test
  void importedPackageTypeChecksAndRuns(@TempDir Path root) throws IOException {
    writeAcmeStrings(root.resolve("registry"));
    Path app =
        writeApp(
            root,
            "module Main exposing (main)\n\nimport Acme.Strings exposing (shout)\n\nmain = shout \"hi\"\n");

    String[] sources = ProjectLoader.loadSources(app, root.resolve("registry")).toArray(new String[0]);

    var types = pl.matsuo.elm.types.TypeChecker.checkProject(sources);
    assertEquals("String", types.get("main"));

    Object main = pl.matsuo.elm.interp.Project.load(sources).main();
    assertEquals("hi!", Show.plain(main));
  }

  @Test
  void bundledPackagesAreNotDoubleLoaded(@TempDir Path root) throws IOException {
    Path core = root.resolve("registry").resolve("elm").resolve("core").resolve("1.0.5");
    Files.createDirectories(core.resolve("src"));
    Files.writeString(
        core.resolve("src").resolve("Sentinel.elm"),
        "module Sentinel exposing (x)\n\nx = 1\n",
        StandardCharsets.UTF_8);
    Path project = root.resolve("app");
    Files.createDirectories(project.resolve("src"));
    Files.writeString(
        project.resolve("elm.json"),
        "{ \"type\": \"application\", \"source-directories\": [ \"src\" ], \"elm-version\": \"0.19.1\","
            + " \"dependencies\": { \"direct\": { \"elm/core\": \"1.0.5\" }, \"indirect\": {} },"
            + " \"test-dependencies\": { \"direct\": {}, \"indirect\": {} } }",
        StandardCharsets.UTF_8);
    Files.writeString(
        project.resolve("src").resolve("Main.elm"),
        "module Main exposing (main)\n\nmain = 1\n",
        StandardCharsets.UTF_8);

    List<String> sources = ProjectLoader.loadSources(project, root.resolve("registry"));
    assertTrue(
        sources.stream().noneMatch(s -> s.contains("module Sentinel")),
        "bundled-package sources must not be loaded from the cache");
  }
}
