package pl.matsuo.elm.ast;

import java.util.List;
import java.util.Optional;
import pl.matsuo.elm.error.Position;

/** A parsed Elm module: its header, imports and declarations. */
public record Module(
    String name, Exposing exposing, List<Import> imports, List<Decl> decls, Position pos) {

  /**
   * An {@code exposing} clause. {@code open} means {@code exposing (..)}; otherwise {@code names}
   * lists the exposed values/types/constructors (constructor sets like {@code Maybe(..)} are
   * flattened to the type name here, which is sufficient for the interpreter).
   */
  public record Exposing(boolean open, List<String> names) {
    public static final Exposing ALL = new Exposing(true, List.of());
  }

  /** {@code import Module.Name as Alias exposing (...)}. */
  public record Import(String module, Optional<String> alias, Exposing exposing, Position pos) {}
}
