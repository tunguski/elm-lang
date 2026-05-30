package pl.matsuo.elm.repl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Reader;
import pl.matsuo.elm.interp.Interpreter;
import pl.matsuo.elm.interp.Show;

/**
 * A small read-eval-print loop: each line is evaluated as an Elm expression by the Truffle
 * interpreter and its value printed. Errors are reported without ending the session. {@code :quit}
 * (or end-of-input) exits.
 */
public final class Repl {

  private Repl() {}

  /** Runs the loop against the given reader/writer (so it is testable without real stdin). */
  public static void loop(Reader in, PrintStream out) throws IOException {
    BufferedReader reader = in instanceof BufferedReader b ? b : new BufferedReader(in);
    out.println("elm-lang REPL — type an expression, or :quit to exit");
    String line;
    out.print("> ");
    out.flush();
    while ((line = reader.readLine()) != null) {
      String src = line.trim();
      if (src.equals(":quit") || src.equals(":q")) {
        break;
      }
      if (!src.isEmpty()) {
        try {
          out.println(Show.plain(Interpreter.eval(src)));
        } catch (RuntimeException e) {
          out.println("Error: " + e.getMessage());
        }
      }
      out.print("> ");
      out.flush();
    }
    out.println();
  }
}
