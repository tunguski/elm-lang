package pl.matsuo.elm;

import java.nio.file.Files;
import java.nio.file.Path;
import pl.matsuo.elm.bytecode.BytecodeInterpreter;
import pl.matsuo.elm.codegen.js.JsCompiler;
import pl.matsuo.elm.html.HtmlRender;
import pl.matsuo.elm.html.Tea;
import pl.matsuo.elm.interp.Interpreter;
import pl.matsuo.elm.interp.Show;
import pl.matsuo.elm.runtime.ElmData;

/**
 * Command-line entry point for the Elm implementation.
 *
 * <pre>
 *   run   &lt;file.elm&gt; [--backend interp|bytecode] [--value NAME]   evaluate and print a definition
 *   js    &lt;file.elm&gt;                                              compile to JavaScript (stdout)
 *   eval  "&lt;expression&gt;" [--backend interp|bytecode]              evaluate a single expression
 * </pre>
 */
public final class Main {

  public static void main(String[] args) throws Exception {
    if (args.length == 0) {
      usage();
      return;
    }
    if (args[0].equals("bench")) {
      long n = args.length > 1 ? Long.parseLong(args[1]) : 30;
      System.out.print(pl.matsuo.elm.bench.Benchmark.run(n, 50, 50));
      return;
    }
    if (args[0].equals("repl")) {
      pl.matsuo.elm.repl.Repl.loop(new java.io.InputStreamReader(System.in), System.out);
      return;
    }
    if (args[0].equals("lsp")) {
      new pl.matsuo.elm.lsp.LspServer().serve(System.in, System.out);
      return;
    }
    if (args[0].equals("format")) {
      // format <file.elm>            -> print formatted source to stdout
      // format <file.elm> --write    -> rewrite the file in place
      // format <elm.json|dir> --project [--write]  -> format every project module
      boolean write = flag(args, "--write");
      if (flag(args, "--project") && args.length > 1) {
        int formatted = 0;
        for (Path p : pl.matsuo.elm.fmt.Formatter.projectFiles(Path.of(args[1]))) {
          String out = pl.matsuo.elm.fmt.Formatter.format(Files.readString(p));
          if (write) {
            Files.writeString(p, out);
          }
          formatted++;
        }
        System.out.println((write ? "Formatted " : "Checked ") + formatted + " file(s)");
      } else if (args.length > 1) {
        String out = pl.matsuo.elm.fmt.Formatter.format(Files.readString(Path.of(args[1])));
        if (write) {
          Files.writeString(Path.of(args[1]), out);
          System.out.println("Formatted " + args[1]);
        } else {
          System.out.print(out);
        }
      } else {
        System.out.println("usage: format <file.elm> [--write] | format <elm.json|dir> --project [--write]");
      }
      return;
    }
    if (args[0].equals("project")) {
      if (args.length < 2) {
        System.out.println("usage: project <elm.json|dir> [check|run [value]]");
        return;
      }
      java.util.List<String> sources =
          pl.matsuo.elm.project.ProjectLoader.loadSources(Path.of(args[1]));
      String mode = args.length > 2 ? args[2] : "check";
      if (mode.equals("run")) {
        System.out.println(
            render(pl.matsuo.elm.interp.Project.load(sources.toArray(new String[0])).main()));
      } else {
        try {
          pl.matsuo.elm.types.TypeChecker.checkProject(sources.toArray(new String[0]))
              .forEach((name, type) -> System.out.println(name + " : " + type));
        } catch (pl.matsuo.elm.error.ElmTypeError e) {
          System.out.println("Type error: " + e.getMessage());
        }
      }
      return;
    }
    if (args[0].equals("site")) {
      if (args.length < 4) {
        System.out.println("usage: site <examplesDir> <Playground.elm> <outDir>");
        return;
      }
      pl.matsuo.elm.site.SiteGenerator.generate(
          Path.of(args[1]), Path.of(args[2]), Path.of(args[3]));
      return;
    }
    if (args.length < 2) {
      usage();
      return;
    }
    String command = args[0];
    String backend = option(args, "--backend", "interp");
    String valueName = option(args, "--value", "main");

    switch (command) {
      case "run" -> {
        String source = Files.readString(Path.of(args[1]));
        // --strict: type-check before evaluating and refuse to run on a type error.
        if (flag(args, "--strict")) {
          try {
            pl.matsuo.elm.types.TypeChecker.checkModule(source);
          } catch (pl.matsuo.elm.error.ElmTypeError e) {
            System.out.println("Type error: " + e.getMessage());
            return;
          }
        }
        Object value =
            backend.equals("bytecode")
                ? BytecodeInterpreter.load(source).value(valueName)
                : Interpreter.load(source).value(valueName);
        System.out.println(render(value));
      }
      case "js" -> {
        String source = Files.readString(Path.of(args[1]));
        if (flag(args, "--map")) {
          System.out.println(JsCompiler.moduleProgramWithSourceMap(source, args[1]).code());
        } else {
          String js = JsCompiler.moduleProgram(source);
          System.out.println(flag(args, "--min") ? JsCompiler.minify(js) : js);
        }
      }
      case "eval" -> {
        Object value =
            backend.equals("bytecode")
                ? BytecodeInterpreter.eval(args[1])
                : Interpreter.eval(args[1]);
        System.out.println(Show.plain(value));
      }
      case "check" -> {
        // One file -> single-module check; several files -> a multi-module project check (the
        // module defining `main` is the entry, e.g. `check Playground.elm Picture.elm`).
        java.util.List<String> files = new java.util.ArrayList<>();
        for (int i = 1; i < args.length; i++) {
          if (args[i].endsWith(".elm")) {
            files.add(Files.readString(Path.of(args[i])));
          }
        }
        try {
          var types =
              files.size() > 1
                  ? pl.matsuo.elm.types.TypeChecker.checkProject(files.toArray(new String[0]))
                  : pl.matsuo.elm.types.TypeChecker.checkModule(files.get(0));
          types.forEach((name, type) -> System.out.println(name + " : " + type));
        } catch (pl.matsuo.elm.error.ElmTypeError e) {
          System.out.println("Type error: " + e.getMessage());
        }
      }
      default -> usage();
    }
  }

  /** Renders a value: Browser programs and Html nodes become HTML, everything else uses Show. */
  private static String render(Object value) {
    if (value instanceof ElmData d) {
      switch (d.ctor()) {
        case "$Sandbox", "$Element", "$Document" -> {
          return Tea.start(value).html();
        }
        case "$Node", "$Text" -> {
          return HtmlRender.render(value);
        }
        default -> {}
      }
    }
    return Show.plain(value);
  }

  private static String option(String[] args, String name, String fallback) {
    for (int i = 0; i < args.length - 1; i++) {
      if (args[i].equals(name)) {
        return args[i + 1];
      }
    }
    return fallback;
  }

  private static boolean flag(String[] args, String name) {
    for (String a : args) {
      if (a.equals(name)) {
        return true;
      }
    }
    return false;
  }

  private static void usage() {
    System.out.println(
        """
        elm-lang - an Elm interpreter/compiler (JIT interpreter, bytecode VM, JS)

        Usage:
          run   <file.elm> [--backend interp|bytecode] [--value NAME] [--strict]
          js    <file.elm> [--min] [--map]
          eval  "<expression>" [--backend interp|bytecode]
          check <file.elm> [more.elm ...]      type-check a module or multi-module project
          bench [fibN]
          repl
          lsp                                  language server (LSP) over stdio
          format <file.elm> [--write] | format <elm.json|dir> --project [--write]
          project <elm.json|dir> [check|run]   load source-directories and check/run
          site  <examplesDir> <Playground.elm> <outDir>

        --strict type-checks before running and refuses to evaluate on a type error.
        """);
  }
}
