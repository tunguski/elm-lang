package pl.matsuo.elm.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Loads bundled classpath resources (the runtime JS kernel, gallery CSS, …) as UTF-8 strings. */
public final class Resources {

  private Resources() {}

  /** Reads the classpath resource at {@code path} (e.g. {@code "/elm/js/kernel.js"}) as text. */
  public static String read(String path) {
    try (InputStream in = Resources.class.getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalStateException("missing bundled resource: " + path);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
