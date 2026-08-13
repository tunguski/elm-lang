package pl.matsuo.elm.snapshot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import pl.matsuo.elm.html.HttpHandler;
import pl.matsuo.elm.html.Tea;
import pl.matsuo.elm.interp.Project;
import pl.matsuo.elm.project.ProjectLoader;

/**
 * A reusable harness for capturing a user journey through an Elm UI as a sequence of static HTML
 * snapshots — without a browser. It runs a Browser program headlessly in the JVM interpreter
 * ({@link Tea}), lets a test dispatch the messages a UI would ({@link #sendExpr}/{@link #send}),
 * optionally routing the program's HTTP through a real backend ({@link HttpHandler}), and writes the
 * rendered view after each step to {@code <outputRoot>/<name>/after_step_<n>.html}.
 *
 * <p>Each snapshot is a self-contained document: the CSS is inlined into a {@code <style>} element
 * and there is <b>no JavaScript</b> (the interpreter's HTML renderer drops event handlers). Links
 * and buttons are inert — the file is a faithful static picture of the UI at that step.
 *
 * <p>The harness is deliberately project-agnostic: a project supplies its own Elm sources (via an
 * {@code elm.json}), its CSS, and — for journeys that talk to a server — an {@link HttpHandler} that
 * forwards to its backend. See {@code pl.matsuo.elm.html.LiveHttpHandler} for a ready cookie-aware
 * live bridge.
 */
public final class UiSnapshotSession {

  private final Project project;
  private final String mainModule;
  private final Tea app;
  private final Path journeyDir;
  private final String name;
  private final String title;
  private final String css;
  private final String lang;
  private final List<Step> steps = new ArrayList<>();
  private int stepCounter = 0;

  private record Step(int index, String label, String file) {}

  private UiSnapshotSession(Builder b, Tea app) {
    this.project = b.project;
    this.mainModule = b.mainModule;
    this.app = app;
    this.name = b.name;
    this.title = b.title != null ? b.title : b.name;
    this.css = b.css != null ? b.css : "";
    this.lang = b.lang != null ? b.lang : "en";
    this.journeyDir = b.outputRoot.resolve(b.name);
  }

  // --- loading -----------------------------------------------------------

  /** Loads an Elm application project (local source-directories + cached deps) from its elm.json. */
  public static Project loadProject(Path elmJsonOrDir) {
    return Project.load(ProjectLoader.loadSources(elmJsonOrDir).toArray(new String[0]));
  }

  public static Builder builder() {
    return new Builder();
  }

  // --- driving -----------------------------------------------------------

  /** Dispatches a message the UI would produce (built by hand as an {@code ElmData}). */
  public UiSnapshotSession send(Object msg) {
    app.send(msg);
    return this;
  }

  /**
   * Builds a message by evaluating an Elm expression in the main module's scope and dispatches it —
   * e.g. {@code sendExpr("LoginMsg (Login.SetLogin \"admin\")")}. Requires the session to have been
   * created from a {@link Project}.
   */
  public UiSnapshotSession sendExpr(String elmExpression) {
    if (project == null) {
      throw new IllegalStateException("sendExpr requires a Project-backed session");
    }
    app.send(project.evalExpr(mainModule, elmExpression));
    return this;
  }

  /** The current model (an interpreter runtime value: ElmRecord/ElmData/…), for assertions. */
  public Object model() {
    return app.model();
  }

  /** The current view rendered to static HTML (no document wrapper, no JS). */
  public String html() {
    return app.html();
  }

  // --- snapshotting ------------------------------------------------------

  /**
   * Runs {@code action} (a sequence of {@link #sendExpr}/{@link #send} calls that make up one user
   * step) and then writes a snapshot labelled {@code label}.
   */
  public UiSnapshotSession step(String label, Runnable action) {
    action.run();
    return snapshot(label);
  }

  /** Writes the current view to {@code <outputRoot>/<name>/after_step_<n>.html} and advances n. */
  public UiSnapshotSession snapshot(String label) {
    int n = stepCounter++;
    String file = "after_step_" + n + ".html";
    write(journeyDir.resolve(file), document(n, label));
    steps.add(new Step(n, label, file));
    write(journeyDir.resolve("index.html"), indexDocument());
    return this;
  }

  /** The directory this journey's snapshots are written to. */
  public Path directory() {
    return journeyDir;
  }

  // --- rendering ---------------------------------------------------------

  private String document(int n, String label) {
    String body = app.html().strip();
    // application/document views already render as <body>…</body>; element/sandbox views don't.
    String bodyHtml = body.startsWith("<body") ? body : "<body>\n" + body + "\n</body>";
    String heading = title + " — step " + n + (label == null || label.isBlank() ? "" : ": " + label);
    return "<!DOCTYPE html>\n"
        + "<html lang=\"" + escape(lang) + "\">\n"
        + "<head>\n"
        + "<meta charset=\"utf-8\">\n"
        + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
        + "<title>" + escape(heading) + "</title>\n"
        + "<style>\n" + css + "\n</style>\n"
        + "</head>\n"
        + bodyHtml + "\n"
        + "</html>\n";
  }

  private String indexDocument() {
    StringBuilder items = new StringBuilder();
    for (Step s : steps) {
      items
          .append("<li>step ")
          .append(s.index())
          .append(": <a href=\"")
          .append(escape(s.file()))
          .append("\">")
          .append(escape(s.label() == null ? s.file() : s.label()))
          .append("</a></li>\n");
    }
    return "<!DOCTYPE html>\n<html lang=\"" + escape(lang) + "\">\n<head>\n"
        + "<meta charset=\"utf-8\">\n<title>" + escape(title) + " — snapshots</title>\n"
        + "<style>body{font-family:system-ui,sans-serif;margin:2rem;} li{margin:.25rem 0;}</style>\n"
        + "</head>\n<body>\n<h1>" + escape(title) + "</h1>\n<ol>\n"
        + items
        + "</ol>\n</body>\n</html>\n";
  }

  private static void write(Path path, String content) {
    try {
      Files.createDirectories(path.getParent());
      Files.writeString(path, content, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String escape(String s) {
    if (s == null) {
      return "";
    }
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
  }

  // --- builder -----------------------------------------------------------

  /** Fluent builder for a {@link UiSnapshotSession}; call {@link #start()} to load and run init. */
  public static final class Builder {
    private Project project;
    private Object program;
    private String mainModule = "Main";
    private HttpHandler http;
    private Path outputRoot = Path.of("target", "ui");
    private String name = "journey";
    private String title;
    private String css;
    private String lang;

    /** Drive the given loaded project (enables {@link #sendExpr}); its {@code main} is run. */
    public Builder project(Project project) {
      this.project = project;
      return this;
    }

    /** Drive an already-resolved program value (from {@code Interpreter.value("main")}); no sendExpr. */
    public Builder program(Object program) {
      this.program = program;
      return this;
    }

    /** The module whose scope {@link #sendExpr} expressions are evaluated in (default {@code Main}). */
    public Builder mainModule(String mainModule) {
      this.mainModule = mainModule;
      return this;
    }

    /** Route the program's HTTP through this handler (e.g. a live backend bridge). */
    public Builder http(HttpHandler http) {
      this.http = http;
      return this;
    }

    /** Root output directory; snapshots go under {@code <outputRoot>/<name>/} (default target/ui). */
    public Builder outputRoot(Path outputRoot) {
      this.outputRoot = outputRoot;
      return this;
    }

    /** Journey name — the snapshot sub-directory (e.g. the CUJ test name). */
    public Builder name(String name) {
      this.name = name;
      return this;
    }

    /** Human title shown in each snapshot's {@code <title>} (defaults to the name). */
    public Builder title(String title) {
      this.title = title;
      return this;
    }

    /** CSS inlined into every snapshot's {@code <style>}. */
    public Builder css(String css) {
      this.css = css;
      return this;
    }

    /** The document language attribute (default {@code en}). */
    public Builder lang(String lang) {
      this.lang = lang;
      return this;
    }

    /** Resolves the program, starts it headlessly (running init + its initial Cmd), returns the session. */
    public UiSnapshotSession start() {
      Object prog = program;
      if (prog == null) {
        if (project == null) {
          throw new IllegalStateException("Either project(...) or program(...) is required");
        }
        prog =
            "Main".equals(mainModule) ? project.main() : project.value(mainModule, "main");
      }
      Tea app = http != null ? Tea.start(prog, http) : Tea.start(prog);
      return new UiSnapshotSession(this, app);
    }
  }
}
