package pl.matsuo.elm.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.matsuo.elm.types.TypeChecker;

class ProjectLoaderTest {

  @Test
  void loadsSourcesFromElmJsonAndTypeChecks(@TempDir Path dir) throws Exception {
    Files.writeString(
        dir.resolve("elm.json"),
        "{ \"type\": \"application\", \"source-directories\": [\"src\", \"lib\"] }",
        StandardCharsets.UTF_8);
    Files.createDirectories(dir.resolve("src"));
    Files.createDirectories(dir.resolve("lib"));
    Files.writeString(
        dir.resolve("lib/Lib.elm"),
        "module Lib exposing (..)\ndouble n = n * 2\n",
        StandardCharsets.UTF_8);
    Files.writeString(
        dir.resolve("src/Main.elm"),
        "module Main exposing (..)\nimport Lib exposing (..)\nmain = double 21\n",
        StandardCharsets.UTF_8);

    List<String> sources = ProjectLoader.loadSources(dir);
    assertEquals(2, sources.size(), sources.toString());

    Map<String, String> types = TypeChecker.checkProject(sources.toArray(new String[0]));
    assertTrue(types.containsKey("main"), types.toString()); // cross-module project type-checks
  }
}
