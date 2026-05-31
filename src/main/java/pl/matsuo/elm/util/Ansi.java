package pl.matsuo.elm.util;

/**
 * Minimal ANSI colouring for terminal error output. Enabled only when stdout is a real terminal
 * ({@code System.console() != null}), {@code NO_COLOR} is unset and {@code TERM} isn't {@code dumb}
 * — so piped/redirected output, CI logs, the LSP and tests all get plain text.
 */
public final class Ansi {

  private Ansi() {}

  private static final boolean ENABLED =
      System.console() != null
          && System.getenv("NO_COLOR") == null
          && !"dumb".equals(System.getenv("TERM"));

  private static final String ESC = String.valueOf((char) 27);
  private static final String RESET = ESC + "[0m";
  private static final String RED = ESC + "[31m";
  private static final String BOLD = ESC + "[1m";
  private static final String DIM = ESC + "[2m";
  private static final String CYAN = ESC + "[36m";

  public static boolean enabled() {
    return ENABLED;
  }

  /**
   * Renders an error block: a red-bold {@code header}, then the located message with the {@code
   * N | source} gutter dimmed, the {@code ^} caret line in red, and a {@code Hint:} line in cyan.
   * Returns plain {@code header + " " + message} when colouring is disabled.
   */
  public static String error(String header, String message) {
    if (!ENABLED) {
      return header + " " + message;
    }
    StringBuilder out = new StringBuilder(BOLD).append(RED).append(header).append(RESET).append(' ');
    String[] lines = message.split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      String l = lines[i];
      if (l.matches("\\s*\\^+\\s*")) {
        out.append(BOLD).append(RED).append(l).append(RESET);
      } else if (l.matches("\\d+ \\| .*")) {
        int bar = l.indexOf('|');
        out.append(DIM).append(l, 0, bar + 1).append(RESET).append(l.substring(bar + 1));
      } else if (l.startsWith("Hint:")) {
        out.append(CYAN).append(l).append(RESET);
      } else {
        out.append(l);
      }
      if (i < lines.length - 1) {
        out.append('\n');
      }
    }
    return out.toString();
  }
}
