package pl.matsuo.elm;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import pl.matsuo.elm.bytecode.BytecodeDisassembler;
import pl.matsuo.elm.bytecode.BytecodeInterpreter;
import pl.matsuo.elm.bytecode.BytecodeProgram;
import pl.matsuo.elm.bytecode.BytecodeReader;
import pl.matsuo.elm.bytecode.BytecodeWriter;
import pl.matsuo.elm.codegen.js.JsCompiler;
import pl.matsuo.elm.error.ElmTypeError;
import pl.matsuo.elm.html.HtmlRender;
import pl.matsuo.elm.html.Tea;
import pl.matsuo.elm.interp.Interpreter;
import pl.matsuo.elm.interp.Show;
import pl.matsuo.elm.runtime.ElmData;

// CLI subcommands extracted from Main.java. Package-private top-level classes registered
// as picocli subcommands on Main; they call back to Main's shared helpers (Main.readElmSource,
// Main.typeError, Main.render) and constants. See docs/file-decomposition.md.

  @Command(
      name = "diff",
      description = "Compare two versions of a module's public API and report the semver magnitude.",
      footerHeading = "%nExample:%n",
      footer = {"  elm diff old/Main.elm new/Main.elm   # -> MAJOR/MINOR/PATCH and the changes"})
final class Diff implements Callable<Integer> {
    @Parameters(index = "0", description = "The old (baseline) .elm file.")
    Path oldFile;

    @Parameters(index = "1", description = "The new .elm file.")
    Path newFile;

    @Override
    public Integer call() throws IOException {
      var diff =
          pl.matsuo.elm.pkg.ApiDiff.compare(
              pl.matsuo.elm.doc.ApiDocs.of(Files.readString(oldFile)),
              pl.matsuo.elm.doc.ApiDocs.of(Files.readString(newFile)));
      System.out.println(diff.magnitude() + " change");
      if (diff.changes().isEmpty()) {
        System.out.println("  (no public API changes)");
      } else {
        diff.changes().forEach(c -> System.out.println("  " + c));
      }
      return 0;
    }
  }

  @Command(
      name = "bump",
      description = "Propose the next version from the API change since a baseline module.",
      footerHeading = "%nExample:%n",
      footer = {"  elm bump old/Main.elm new/Main.elm 1.2.0   # -> magnitude + the next version"})
final class Bump implements Callable<Integer> {
    @Parameters(index = "0", description = "The old (baseline) .elm file.")
    Path oldFile;

    @Parameters(index = "1", description = "The new .elm file.")
    Path newFile;

    @Parameters(
        index = "2",
        arity = "0..1",
        description = "Current version; defaults to the version in a sibling elm.json, else 1.0.0.")
    String current;

    @Override
    public Integer call() throws IOException {
      var diff =
          pl.matsuo.elm.pkg.ApiDiff.compare(
              pl.matsuo.elm.doc.ApiDocs.of(Files.readString(oldFile)),
              pl.matsuo.elm.doc.ApiDocs.of(Files.readString(newFile)));
      var currentVersion = current != null ? pl.matsuo.elm.pkg.Version.parse(current) : currentFrom(newFile);
      var next = pl.matsuo.elm.pkg.ApiDiff.bump(currentVersion, diff.magnitude());
      System.out.println(diff.magnitude() + " change: " + currentVersion + " -> " + next);
      return 0;
    }

    /** The current version: the {@code "version"} of a package elm.json next to {@code newFile} if
     * present, else 1.0.0. (Read directly, since a package manifest isn't an application elm.json.) */
    private static pl.matsuo.elm.pkg.Version currentFrom(Path newFile) {
      Path parent = newFile.toAbsolutePath().getParent();
      if (parent != null) {
        Path elmJson = parent.resolve("elm.json");
        if (Files.isRegularFile(elmJson)) {
          try {
            Object parsed = pl.matsuo.elm.json.JsonParse.parse(Files.readString(elmJson));
            if (parsed instanceof java.util.Map<?, ?> m && m.get("version") instanceof String s) {
              return pl.matsuo.elm.pkg.Version.parse(s);
            }
          } catch (IOException | RuntimeException ignored) {
            // Fall through to the default below.
          }
        }
      }
      return new pl.matsuo.elm.pkg.Version(1, 0, 0);
    }
  }

  @Command(
      name = "publish",
      description = "Publish preflight: type-check, write docs.json, and (with --bump-from) derive and validate the next version.",
      footerHeading = "%nExample:%n",
      footer = {
        "  elm publish src/Main.elm --out docs.json",
        "  elm publish src/Main.elm --bump-from prev/Main.elm --from-version 1.2.0 --version 1.3.0",
      })
final class Publish implements Callable<Integer> {
    @Parameters(index = "0", description = "The .elm module to publish.")
    Path file;

    @Option(names = "--out", description = "Write the module's docs.json to this file.")
    Path out;

    @Option(names = "--bump-from", description = "A baseline .elm of the previously published API; reports the semver bump.")
    Path bumpFrom;

    @Option(names = "--from-version", description = "Current published version, used with --bump-from (default 1.0.0).")
    String fromVersion = "1.0.0";

    @Option(names = "--version", description = "The version you intend to publish; checked against the bump (with --bump-from).")
    String version;

    @Option(names = "--registry", description = "Publish into this directory registry (the handshake: record elm.json + docs.json).")
    Path registry;

    @Option(names = "--name", description = "The package name (author/name), required with --registry.")
    String name;

    @Override
    public Integer call() throws IOException {
      String source = Files.readString(file);
      try {
        pl.matsuo.elm.types.TypeChecker.checkModule(source);
      } catch (ElmTypeError e) {
        System.out.println("x type error: " + e.getMessage());
        return 1;
      }
      System.out.println("ok type-checks");
      var api = pl.matsuo.elm.doc.ApiDocs.of(source);
      int entries = api.values().size() + api.unions().size() + api.aliases().size();
      System.out.println("ok docs.json: " + entries + " exposed entr" + (entries == 1 ? "y" : "ies"));
      if (out != null) {
        Files.writeString(out, api.toJson(), StandardCharsets.UTF_8);
        System.out.println("ok wrote " + out);
      }
      if (bumpFrom != null) {
        var diff =
            pl.matsuo.elm.pkg.ApiDiff.compare(
                pl.matsuo.elm.doc.ApiDocs.of(Files.readString(bumpFrom)), api);
        var next =
            pl.matsuo.elm.pkg.ApiDiff.bump(pl.matsuo.elm.pkg.Version.parse(fromVersion), diff.magnitude());
        System.out.println("-> " + diff.magnitude() + " change: " + fromVersion + " -> " + next);
        diff.changes().forEach(c -> System.out.println("    " + c));
        if (version != null && !version.equals(next.toString())) {
          System.out.println(
              "x version " + version + " is not the required next version " + next
                  + " for a " + diff.magnitude() + " change");
          return 1;
        }
        if (version != null) {
          System.out.println("ok version " + version + " matches the required bump");
        }
      }
      if (registry != null) {
        if (name == null) {
          System.err.println("Provide --name author/pkg to publish to a registry.");
          return 1;
        }
        var v = pl.matsuo.elm.pkg.Version.parse(version == null ? "1.0.0" : version);
        String manifest = pl.matsuo.elm.pkg.Publisher.manifest(name, v, api.moduleName());
        var res = pl.matsuo.elm.pkg.Publisher.publish(registry, name, v, manifest, api.toJson());
        System.out.println((res.ok() ? "ok " : "x ") + res.message());
        return res.ok() ? 0 : 1;
      }
      System.out.println("Preflight OK - ready to publish.");
      return 0;
    }
  }

  @Command(name = "init", description = "Initialise an Elm project (elm.json + src/).")
final class Init implements Callable<Integer> {
    @Parameters(index = "0", arity = "0..1", description = "Target directory (default: current).")
    Path dir = Path.of(".");

    @Override
    public Integer call() throws IOException {
      Path elmJson = dir.resolve("elm.json");
      if (Files.exists(elmJson)) {
        System.out.println("elm.json already exists — nothing to do.");
        return 0;
      }
      Files.createDirectories(dir.resolve("src"));
      Files.writeString(elmJson, Main.ELM_JSON, StandardCharsets.UTF_8);
      System.out.println("Created " + elmJson + " and " + dir.resolve("src") + "/");
      return 0;
    }
  }

  @Command(
      name = "install",
      description = "Add a package to elm.json and re-solve dependencies (against a local registry).",
      footerHeading = "%nExample:%n",
      footer = {
        "  elm install acme/strings                          # from the local cache",
        "  elm install acme/strings --from http://host/reg   # solve + download into the cache",
        "",
        "The cache is laid out as <root>/<author>/<name>/<version>/{elm.json, src/…} and defaults",
        "to $ELM_REGISTRY, else ~/.elm/registry. The solver pins a compatible version of every",
        "package in the transitive closure (constraints like \"1.0.0 <= v < 2.0.0\"). With --from it",
        "solves against, and downloads sources from, a remote registry; `project`/`check`/`run`",
        "then compile and run the installed package's modules alongside your own.",
      })
final class Install implements Callable<Integer> {
    @Parameters(index = "0", description = "Package to install, as author/name (e.g. elm/regex).")
    String pkg;

    @Option(
        names = "--registry",
        description = "Package-cache directory (default: $ELM_REGISTRY or ~/.elm/registry).")
    Path registry;

    @Option(
        names = {"-d", "--dir"},
        description = "Project directory containing elm.json (default: current directory).")
    Path dir = Path.of(".");

    @Option(
        names = "--from",
        description =
            "Remote registry base URL to solve against and download sources from into the cache "
                + "(the simple static-file protocol: versions.txt, files.txt, per-version elm.json).")
    String from;

    @Option(
        names = "--elm",
        arity = "0..1",
        fallbackValue = "https://package.elm-lang.org",
        description =
            "Use a package.elm-lang.org-style registry (all-packages + endpoint.json + zipball). "
                + "With no value, the public registry; pass a URL to point elsewhere.")
    String elm;

    @Option(
        names = "--dry-run",
        description = "Solve dependencies and report the result without writing elm.json/lockfile or downloading.")
    boolean dryRun;

    @Override
    public Integer call() throws IOException {
      Path registryRoot = registry != null ? registry : pl.matsuo.elm.pkg.Installer.defaultRegistryRoot();
      // Solve against: the public-style Elm registry (--elm), the simple remote (--from), else cache.
      pl.matsuo.elm.pkg.Registry reg;
      if (elm != null) {
        reg = new pl.matsuo.elm.pkg.ElmRegistry(elm);
      } else if (from != null) {
        reg = new pl.matsuo.elm.pkg.HttpRegistry(from);
      } else {
        reg = new pl.matsuo.elm.pkg.DirectoryRegistry(registryRoot);
      }
      try {
        var result = pl.matsuo.elm.pkg.Installer.install(dir, pkg, reg, dryRun);
        if (result.alreadyPresent()) {
          System.out.println(pkg + " is already a direct dependency (" + result.installed() + ").");
          return 0;
        }
        if (dryRun) {
          System.out.println("Would install " + pkg + " " + result.installed()
              + " (" + result.direct().size() + " direct, " + result.indirect().size()
              + " indirect). No files changed.");
          return 0;
        }
        if (elm != null || from != null) {
          // Download every resolved (non-bundled) package's sources into the cache so the project
          // loader and compiler can use them.
          var all = new java.util.TreeMap<>(result.indirect());
          all.putAll(result.direct());
          for (var dep : all.entrySet()) {
            if (!pl.matsuo.elm.project.ProjectLoader.BUNDLED.contains(dep.getKey())) {
              if (elm != null) {
                new pl.matsuo.elm.pkg.ElmPackageFetcher(elm).fetch(dep.getKey(), dep.getValue(), registryRoot);
              } else {
                new pl.matsuo.elm.pkg.PackageFetcher(from).fetch(dep.getKey(), dep.getValue(), registryRoot);
              }
            }
          }
        }
        System.out.println("Installed " + pkg + " " + result.installed() + ".");
        System.out.println(
            "Dependencies: " + result.direct().size() + " direct, "
                + result.indirect().size() + " indirect.");
        return 0;
      } catch (pl.matsuo.elm.pkg.Solver.Unsolvable | IllegalStateException e) {
        System.err.println("Install failed: " + e.getMessage());
        return 1;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        System.err.println("Install interrupted while downloading.");
        return 1;
      }
    }
  }

  @Command(
      name = "upgrade",
      description = "Re-solve direct dependencies to their latest available versions.")
final class Upgrade implements Callable<Integer> {
    @Option(
        names = "--registry",
        description = "Package-cache directory (default: $ELM_REGISTRY or ~/.elm/registry).")
    Path registry;

    @Option(
        names = {"-d", "--dir"},
        description = "Project directory containing elm.json (default: current directory).")
    Path dir = Path.of(".");

    @Option(
        names = "--dry-run",
        description = "Report the version changes without writing elm.json/lockfile.")
    boolean dryRun;

    @Override
    public Integer call() throws IOException {
      Path registryRoot =
          registry != null ? registry : pl.matsuo.elm.pkg.Installer.defaultRegistryRoot();
      var reg = new pl.matsuo.elm.pkg.DirectoryRegistry(registryRoot);
      try {
        var result = pl.matsuo.elm.pkg.Installer.upgrade(dir, reg, dryRun);
        if (!result.changed()) {
          System.out.println("All direct dependencies are already at their latest versions.");
          return 0;
        }
        System.out.println((dryRun ? "Would upgrade:" : "Upgraded:"));
        result.after().forEach((p, v) -> {
          var was = result.before().get(p);
          if (!v.equals(was)) {
            System.out.println("  " + p + "  " + was + " -> " + v);
          }
        });
        return 0;
      } catch (pl.matsuo.elm.pkg.Solver.Unsolvable | IllegalStateException e) {
        System.err.println("Upgrade failed: " + e.getMessage());
        return 1;
      }
    }
  }

  @Command(name = "uninstall", description = "Remove a direct dependency and re-solve the rest.")
final class Uninstall implements Callable<Integer> {
    @Parameters(index = "0", description = "Package to remove, as author/name.")
    String pkg;

    @Option(names = "--registry", description = "Package-cache directory (default: $ELM_REGISTRY or ~/.elm/registry).")
    Path registry;

    @Option(names = {"-d", "--dir"}, description = "Project directory containing elm.json (default: current).")
    Path dir = Path.of(".");

    @Override
    public Integer call() throws IOException {
      Path registryRoot = registry != null ? registry : pl.matsuo.elm.pkg.Installer.defaultRegistryRoot();
      try {
        var result = pl.matsuo.elm.pkg.Installer.uninstall(dir, pkg, new pl.matsuo.elm.pkg.DirectoryRegistry(registryRoot));
        if (!result.wasPresent()) {
          System.out.println(pkg + " is not a direct dependency.");
          return 0;
        }
        System.out.println("Removed " + pkg + ". Direct dependencies: " + result.direct().size() + ".");
        return 0;
      } catch (pl.matsuo.elm.pkg.Solver.Unsolvable | IllegalStateException e) {
        System.err.println("Uninstall failed: " + e.getMessage());
        return 1;
      }
    }
  }

  @Command(name = "outdated", description = "Report direct dependencies with a newer version in the registry.")
final class Outdated implements Callable<Integer> {
    @Option(names = "--registry", description = "Package-cache directory (default: $ELM_REGISTRY or ~/.elm/registry).")
    Path registry;

    @Option(names = {"-d", "--dir"}, description = "Project directory containing elm.json (default: current).")
    Path dir = Path.of(".");

    @Override
    public Integer call() throws IOException {
      Path registryRoot = registry != null ? registry : pl.matsuo.elm.pkg.Installer.defaultRegistryRoot();
      try {
        var result = pl.matsuo.elm.pkg.Installer.outdated(dir, new pl.matsuo.elm.pkg.DirectoryRegistry(registryRoot));
        if (result.behind().isEmpty()) {
          System.out.println("All direct dependencies are up to date.");
          return 0;
        }
        System.out.println("Outdated:");
        result.behind().forEach((p, v) -> System.out.println("  " + p + "  " + v[0] + " -> " + v[1]));
        return 0;
      } catch (IllegalStateException e) {
        System.err.println("Outdated failed: " + e.getMessage());
        return 1;
      }
    }
  }

  @Command(
      name = "verify",
      description = "Check elm.lock against elm.json and the registry (reproducible, tamper-evident).",
      footerHeading = "%nExample:%n",
      footer = {
        "  elm verify                     # verify the lockfile in the current project",
        "  elm verify -d path/to/project  # against an explicit registry with --registry",
        "",
        "Confirms every locked package still resolves to the same integrity hash and that elm.json's",
        "pinned versions match the lockfile. Exits non-zero (listing the problems) on any mismatch.",
      })
final class Verify implements Callable<Integer> {
    @Option(
        names = "--registry",
        description = "Package-cache directory (default: $ELM_REGISTRY or ~/.elm/registry).")
    Path registry;

    @Option(
        names = {"-d", "--dir"},
        description = "Project directory containing elm.json/elm.lock (default: current directory).")
    Path dir = Path.of(".");

    @Override
    public Integer call() throws IOException {
      Path registryRoot = registry != null ? registry : pl.matsuo.elm.pkg.Installer.defaultRegistryRoot();
      pl.matsuo.elm.pkg.Registry reg = new pl.matsuo.elm.pkg.DirectoryRegistry(registryRoot);
      var problems = pl.matsuo.elm.pkg.Lockfile.verifyProject(dir, reg);
      if (problems.isEmpty()) {
        System.out.println("elm.lock verified: all dependencies match and check out.");
        return 0;
      }
      System.err.println("Lockfile verification failed:");
      problems.forEach(p -> System.err.println("  - " + p));
      return 1;
    }
  }
