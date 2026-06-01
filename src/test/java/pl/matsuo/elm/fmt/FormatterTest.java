package pl.matsuo.elm.fmt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pl.matsuo.elm.ast.Module;
import pl.matsuo.elm.html.HtmlRender;
import pl.matsuo.elm.interp.Interpreter;
import pl.matsuo.elm.interp.Show;
import pl.matsuo.elm.parser.AstSexpr;
import pl.matsuo.elm.parser.Parser;

class FormatterTest {

  /**
   * Corpus fuzz: format every real {@code .elm} file in the repo and assert two properties — the
   * formatter is idempotent ({@code format(format(x)) == format(x)}), and it doesn't change the AST
   * (the position-insensitive module signature is identical before and after). Catches both layout
   * instability and any semantic drift across actual code, not just hand-picked snippets.
   */
  @Test
  void formattingIsIdempotentAndPreservesAstAcrossTheCorpus() throws IOException {
    List<Path> roots = List.of(Path.of("src/main/resources/elm"), Path.of("examples"));
    int checked = 0;
    List<String> failures = new ArrayList<>();
    for (Path root : roots) {
      if (!Files.isDirectory(root)) {
        continue;
      }
      try (var walk = Files.walk(root)) {
        for (Path file : walk.filter(p -> p.toString().endsWith(".elm")).sorted().toList()) {
          String src = Files.readString(file, StandardCharsets.UTF_8);
          Module before;
          try {
            before = Parser.parseModule(src);
          } catch (RuntimeException notStandalone) {
            continue; // doesn't parse on its own — not the formatter's concern
          }
          String once;
          Module after;
          try {
            once = Formatter.format(src);
            after = Parser.parseModule(once);
          } catch (RuntimeException e) {
            failures.add(file + ": formatting/parse threw " + e);
            continue;
          }
          if (!once.equals(Formatter.format(once))) {
            failures.add(file + ": not idempotent");
          }
          if (!AstSexpr.module(before).equals(AstSexpr.module(after))) {
            failures.add(file + ": AST changed by formatting");
          }
          checked++;
        }
      }
    }
    assertTrue(checked >= 20, "expected to check a meaningful corpus, checked " + checked);
    assertTrue(failures.isEmpty(), "formatter issues:\n" + String.join("\n", failures));
  }

  private static String example(String slug) {
    try (InputStream in = FormatterTest.class.getResourceAsStream("/examples/" + slug + ".elm")) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void preservesCommentsInsideADeclarationBody() {
    // Comments inside a function body used to be dropped/misplaced; now the body is kept verbatim.
    String src =
        "module M exposing (f)\n\n\nf x =\n    let\n        -- double it\n        y = x * 2\n"
            + "    in\n    y + 1\n";
    String out = Formatter.format(src);
    assertTrue(out.contains("-- double it"), out);
    assertEquals(out, Formatter.format(out), "still idempotent"); // format is stable
  }

  @Test
  void producesElmFormatStyleLayout() {
    String out = Formatter.format(example("buttons"));
    assertTrue(out.startsWith("module Main exposing (..)\n"), out);
    assertTrue(out.contains("\nimport Browser\n"), out); // imports, one per line, sorted
    assertTrue(out.contains("type Msg\n    = Increment\n    | Decrement"), out); // union layout
    assertTrue(out.contains("    case msg of\n        Increment ->\n"), out); // case 4-space indent
    assertTrue(out.contains("\n\n\nupdate msg model ="), out); // two blank lines between top-levels
  }

  @Test
  void annotationGoesOnItsOwnLine() {
    String out = Formatter.format("f : Int -> Int\nf x = x + 1\n");
    assertTrue(out.contains("f : Int -> Int\nf x =\n    x + 1") || out.contains("f : Int -> Int\nf x = x + 1"), out);
    assertTrue(out.contains("f : Int -> Int"), out);
  }

  @ParameterizedTest
  @ValueSource(strings = {"hello", "groceries", "buttons", "forms", "text-fields", "numbers", "cards"})
  void formattingIsIdempotent(String slug) {
    String once = Formatter.format(example(slug));
    String twice = Formatter.format(once);
    assertEquals(once, twice, slug + " formatting should be stable");
  }

  @ParameterizedTest
  @ValueSource(strings = {"hello", "groceries"})
  void preservesRenderedOutput(String slug) {
    // The static-HTML examples must render identically before and after formatting.
    String before = HtmlRender.render(Interpreter.load(example(slug)).value("main"));
    String after = HtmlRender.render(Interpreter.load(Formatter.format(example(slug))).value("main"));
    assertEquals(before, after, slug);
  }

  @Test
  void preservesTopLevelComments() {
    String src =
        """
        -- a banner comment
        module Main exposing (..)

        {-| The module doc comment. -}


        -- before greet
        greet name =
            "Hi " ++ name


        main =
            greet "world"
        """;
    String out = Formatter.format(src);
    assertTrue(out.contains("-- a banner comment"), out);
    assertTrue(out.contains("{-| The module doc comment. -}"), out);
    assertTrue(out.contains("-- before greet"), out);
    // The doc comment stays in the header region, the section comment stays before its function.
    assertTrue(out.indexOf("{-| The module doc comment. -}") < out.indexOf("-- before greet"), out);
    assertTrue(out.indexOf("-- before greet") < out.indexOf("greet name ="), out);
  }

  @Test
  void commentPreservationIsIdempotent() {
    String src = "module Main exposing (..)\n\n\n-- note\nmain =\n    1\n";
    String once = Formatter.format(src);
    assertEquals(once, Formatter.format(once), "comment formatting should be stable");
    assertTrue(once.contains("-- note"), once);
  }

  @Test
  void preservesEvaluation() {
    String src = "main = List.sum (List.map (\\x -> x * 2) [ 1, 2, 3 ])\n";
    assertEquals(
        Show.plain(Interpreter.load(src).value("main")),
        Show.plain(Interpreter.load(Formatter.format(src)).value("main")));
  }
}
