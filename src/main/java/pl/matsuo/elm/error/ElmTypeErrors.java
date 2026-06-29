package pl.matsuo.elm.error;

import java.util.List;

/**
 * Several type errors found in one type-check pass. Inference recovers after a failed top-level
 * definition (binding it to a fresh polymorphic type so dependents don't cascade) and keeps going,
 * so a single check can report every independent mistake at once.
 *
 * <p>It is an {@link ElmTypeError} — its {@link #position}/{@link #hint} and {@link #getMessage()}
 * are the first error's — so existing single-error consumers keep working, while callers that want
 * the full list (the LSP, the CLI report) can read {@link #errors}.
 */
public class ElmTypeErrors extends ElmTypeError {

  /** The individual errors, in source order. Each is already located (has a {@link Position}). */
  public final List<ElmTypeError> errors;

  public ElmTypeErrors(String message, List<ElmTypeError> errors) {
    super(
        message,
        errors.isEmpty() ? null : errors.get(0).position,
        errors.isEmpty() ? null : errors.get(0).hint,
        errors.isEmpty() ? null : errors.get(0).module);
    this.errors = List.copyOf(errors);
  }

  /** Tags every still-untagged inner error with {@code m} (the module they bubbled out of), so the
   * report renders each excerpt against the right module's source. */
  @Override
  public ElmTypeError inModule(String m) {
    if (m == null) {
      return this;
    }
    return new ElmTypeErrors(rawMessage(), errors.stream().map(e -> e.inModule(m)).toList());
  }
}
