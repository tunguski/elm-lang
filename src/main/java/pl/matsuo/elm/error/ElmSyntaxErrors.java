package pl.matsuo.elm.error;

import java.util.List;

/**
 * Several syntax errors found in one parse. The parser recovers after a malformed top-level
 * declaration (skipping to the next one) and keeps going, so a single parse can report every
 * independent mistake at once.
 *
 * <p>It is an {@link ElmSyntaxError} — its {@link #position()} and message are the first error's — so
 * existing single-error consumers keep working, while callers that want the full list (the LSP, the
 * CLI report) can read {@link #errors}.
 */
public class ElmSyntaxErrors extends ElmSyntaxError {

  /** The individual errors, in source order. */
  public final transient List<ElmSyntaxError> errors;

  public ElmSyntaxErrors(String message, List<ElmSyntaxError> errors) {
    super(message, errors.get(0).position());
    this.errors = List.copyOf(errors);
  }
}
