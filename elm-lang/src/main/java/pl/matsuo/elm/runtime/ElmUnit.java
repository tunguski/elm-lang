package pl.matsuo.elm.runtime;

/** The Elm unit value {@code ()}. */
public final class ElmUnit {

  public static final ElmUnit INSTANCE = new ElmUnit();

  private ElmUnit() {}

  @Override
  public String toString() {
    return "()";
  }
}
