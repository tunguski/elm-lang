package pl.matsuo.elm.types;

import java.util.List;

/** A polymorphic type scheme {@code forall vars. body} (the result of let-generalization). */
public record Scheme(List<Ty.Var> vars, Ty body) {

  public static Scheme mono(Ty type) {
    return new Scheme(List.of(), type);
  }
}
