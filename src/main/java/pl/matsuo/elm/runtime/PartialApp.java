package pl.matsuo.elm.runtime;

/** A partially applied callable: arguments collected so far, fewer than the callable's arity. */
public record PartialApp(ElmCallable base, Object[] collected) {

  @Override
  public String toString() {
    return "<partial " + base.name() + " " + collected.length + "/" + base.arity() + ">";
  }
}
