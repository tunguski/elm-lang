package pl.matsuo.elm.ast;

import java.util.List;

/** Patterns used in function parameters, lambda parameters, {@code case} branches and {@code let}. */
public sealed interface Pattern {

  /** {@code _} — matches anything, binds nothing. */
  record Wildcard() implements Pattern {}

  /** A lowercase variable binding, e.g. {@code x}. */
  record Var(String name) implements Pattern {}

  /** {@code ()} */
  record Unit() implements Pattern {}

  record IntLit(long value) implements Pattern {}

  record StrLit(String value) implements Pattern {}

  record CharLit(int codePoint) implements Pattern {}

  /** A (possibly qualified) constructor pattern, e.g. {@code Just x} or {@code Maybe.Just x}. */
  record Ctor(String module, String name, List<Pattern> args) implements Pattern {}

  /** {@code ( a, b, ... )} with two or more sub-patterns. */
  record Tuple(List<Pattern> items) implements Pattern {}

  /** {@code [ a, b, ... ]} — matches a list of exactly this length. */
  record ListPat(List<Pattern> items) implements Pattern {}

  /** {@code head :: tail} */
  record Cons(Pattern head, Pattern tail) implements Pattern {}

  /** {@code { a, b }} — destructures record fields by name. */
  record RecordPat(List<String> fields) implements Pattern {}

  /** {@code pattern as name} */
  record Alias(Pattern pattern, String name) implements Pattern {}
}
