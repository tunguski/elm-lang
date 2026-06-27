package pl.matsuo.elm.pkg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests {@code elm install} end to end against an on-disk {@link DirectoryRegistry}. */
class InstallerTest {

  private static void writePackage(Path registry, String pkg, String version, String depsJson)
      throws IOException {
    Path dir = registry.resolve(pkg).resolve(version);
    Files.createDirectories(dir);
    Files.writeString(
        dir.resolve("elm.json"),
        "{ \"type\": \"package\", \"name\": \"" + pkg + "\", \"version\": \"" + version
            + "\", \"dependencies\": " + depsJson + " }",
        StandardCharsets.UTF_8);
  }

  private static final String APP_ELM_JSON =
      """
      {
        "type": "application",
        "source-directories": [ "src" ],
        "elm-version": "0.19.1",
        "dependencies": { "direct": {}, "indirect": {} },
        "test-dependencies": { "direct": {}, "indirect": {} }
      }
      """;

  @Test
  void directoryRegistryListsVersionsAndDependencies(@TempDir Path registry) throws IOException {
    writePackage(registry, "elm/core", "1.0.5", "{}");
    writePackage(registry, "elm/core", "1.0.2", "{}");
    DirectoryRegistry reg = new DirectoryRegistry(registry);
    assertEquals(
        List.of(Version.parse("1.0.2"), Version.parse("1.0.5")), reg.versions("elm/core"));
    assertTrue(reg.dependencies("elm/core", Version.parse("1.0.5")).isEmpty());
  }

  @Test
  void installAddsDirectAndTransitiveDependencies(@TempDir Path root) throws IOException {
    Path registry = root.resolve("registry");
    writePackage(registry, "elm/core", "1.0.5", "{}");
    writePackage(registry, "elm/regex", "1.0.0", "{ \"elm/core\": \"1.0.0 <= v < 2.0.0\" }");

    Path project = root.resolve("project");
    Files.createDirectories(project);
    Files.writeString(project.resolve("elm.json"), APP_ELM_JSON, StandardCharsets.UTF_8);

    Installer.Result result = Installer.install(project, "elm/regex", new DirectoryRegistry(registry));

    assertFalse(result.alreadyPresent());
    assertEquals(Version.parse("1.0.0"), result.installed());
    assertEquals(Version.parse("1.0.0"), result.direct().get("elm/regex"));
    assertEquals(Version.parse("1.0.5"), result.indirect().get("elm/core"), "pulled in transitively");

    // The change is persisted, and re-parses as a valid application manifest.
    String written = Files.readString(project.resolve("elm.json"), StandardCharsets.UTF_8);
    assertTrue(written.contains("\"elm/regex\": \"1.0.0\""), written);
    assertTrue(written.contains("\"elm/core\": \"1.0.5\""), written);
    ElmJson reparsed = ElmJson.parse(written);
    assertEquals(Version.parse("1.0.0"), reparsed.direct().get("elm/regex"));

    // A lockfile is written pinning the whole solution, and it verifies against the registry.
    Path lock = project.resolve(Lockfile.FILENAME);
    assertTrue(Files.exists(lock), "elm.lock written");
    String lockText = Files.readString(lock, StandardCharsets.UTF_8);
    assertTrue(lockText.contains("elm/regex 1.0.0 sha256-"), lockText);
    assertTrue(lockText.contains("elm/core 1.0.5 sha256-"), lockText);
    assertTrue(Lockfile.verifyProject(project, new DirectoryRegistry(registry)).isEmpty(),
        "the freshly written lockfile verifies");
  }

  @Test
  void installingAnExistingDirectDependencyIsANoOp(@TempDir Path root) throws IOException {
    Path registry = root.resolve("registry");
    writePackage(registry, "elm/core", "1.0.5", "{}");

    Path project = root.resolve("project");
    Files.createDirectories(project);
    Files.writeString(
        project.resolve("elm.json"),
        APP_ELM_JSON.replace("\"direct\": {}", "\"direct\": { \"elm/core\": \"1.0.5\" }"),
        StandardCharsets.UTF_8);

    Installer.Result result = Installer.install(project, "elm/core", new DirectoryRegistry(registry));
    assertTrue(result.alreadyPresent());
    assertEquals(Version.parse("1.0.5"), result.installed());
  }

  @Test
  void dryRunInstallSolvesButWritesNothing(@TempDir Path root) throws IOException {
    Path registry = root.resolve("registry");
    writePackage(registry, "elm/core", "1.0.5", "{}");
    writePackage(registry, "elm/regex", "1.0.0", "{ \"elm/core\": \"1.0.0 <= v < 2.0.0\" }");

    Path project = root.resolve("project");
    Files.createDirectories(project);
    Files.writeString(project.resolve("elm.json"), APP_ELM_JSON, StandardCharsets.UTF_8);

    Installer.Result result =
        Installer.install(project, "elm/regex", new DirectoryRegistry(registry), true);
    assertEquals(Version.parse("1.0.0"), result.installed()); // the solve still happens

    // No files were written: elm.json is unchanged and no lockfile exists.
    assertEquals(APP_ELM_JSON, Files.readString(project.resolve("elm.json"), StandardCharsets.UTF_8));
    assertFalse(Files.exists(project.resolve(Lockfile.FILENAME)), "dry run writes no lockfile");
  }

  @Test
  void upgradeRaisesDirectDependenciesToLatest(@TempDir Path root) throws IOException {
    Path registry = root.resolve("registry");
    writePackage(registry, "elm/core", "1.0.0", "{}");
    writePackage(registry, "elm/core", "1.0.5", "{}");

    Path project = root.resolve("project");
    Files.createDirectories(project);
    Files.writeString(
        project.resolve("elm.json"),
        APP_ELM_JSON.replace("\"direct\": {}", "\"direct\": { \"elm/core\": \"1.0.0\" }"),
        StandardCharsets.UTF_8);

    // A dry-run upgrade reports the change but writes nothing.
    Installer.Upgrade dry = Installer.upgrade(project, new DirectoryRegistry(registry), true);
    assertTrue(dry.changed());
    assertEquals(Version.parse("1.0.0"), dry.before().get("elm/core"));
    assertEquals(Version.parse("1.0.5"), dry.after().get("elm/core"));
    assertTrue(
        Files.readString(project.resolve("elm.json"), StandardCharsets.UTF_8).contains("1.0.0"),
        "dry run leaves the old pin");

    // The real upgrade persists the new pin.
    Installer.Upgrade done = Installer.upgrade(project, new DirectoryRegistry(registry), false);
    assertTrue(done.changed());
    assertTrue(
        Files.readString(project.resolve("elm.json"), StandardCharsets.UTF_8).contains("1.0.5"),
        "upgrade writes the new pin");
  }

  @Test
  void uninstallRemovesADirectDependency(@TempDir Path root) throws IOException {
    Path registry = root.resolve("registry");
    writePackage(registry, "elm/core", "1.0.5", "{}");
    writePackage(registry, "elm/regex", "1.0.0", "{ \"elm/core\": \"1.0.0 <= v < 2.0.0\" }");

    Path project = root.resolve("project");
    Files.createDirectories(project);
    Files.writeString(project.resolve("elm.json"), APP_ELM_JSON, StandardCharsets.UTF_8);
    Installer.install(project, "elm/regex", new DirectoryRegistry(registry));

    Installer.Uninstall r = Installer.uninstall(project, "elm/regex", new DirectoryRegistry(registry));
    assertTrue(r.wasPresent());
    assertFalse(r.direct().containsKey("elm/regex"));
    String written = Files.readString(project.resolve("elm.json"), StandardCharsets.UTF_8);
    assertFalse(written.contains("\"elm/regex\""), written);

    // Removing something that isn't a direct dependency is a no-op.
    assertFalse(Installer.uninstall(project, "elm/nope", new DirectoryRegistry(registry)).wasPresent());
  }

  @Test
  void outdatedReportsNewerVersions(@TempDir Path root) throws IOException {
    Path registry = root.resolve("registry");
    writePackage(registry, "elm/core", "1.0.0", "{}");
    writePackage(registry, "elm/core", "1.0.5", "{}");

    Path project = root.resolve("project");
    Files.createDirectories(project);
    Files.writeString(
        project.resolve("elm.json"),
        APP_ELM_JSON.replace("\"direct\": {}", "\"direct\": { \"elm/core\": \"1.0.0\" }"),
        StandardCharsets.UTF_8);

    Installer.Outdated r = Installer.outdated(project, new DirectoryRegistry(registry));
    assertTrue(r.behind().containsKey("elm/core"));
    assertEquals(Version.parse("1.0.0"), r.behind().get("elm/core")[0]);
    assertEquals(Version.parse("1.0.5"), r.behind().get("elm/core")[1]);
  }
}
