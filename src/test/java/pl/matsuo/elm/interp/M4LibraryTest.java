package pl.matsuo.elm.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import pl.matsuo.elm.util.Resources;

/** Exercises the bundled {@code M4} library, which *builds* m4 macro source (it does not run m4). */
class M4LibraryTest {

  private static final String LIB = Resources.read("/elm/lib/M4.elm");

  private static final String SRC =
      """
      module Main exposing (greet, prog, ifText, ifdefText, withArgs, builtins)

      import M4 exposing (..)

      -- define(`greet', `Hello $1!')
      greet : String
      greet = define "greet" ("Hello " ++ arg 1 ++ "!")

      -- a two-line m4 document: define then call
      prog : String
      prog =
          program
              [ define "greet" ("Hello " ++ arg 1 ++ "!") ++ dnl
              , call "greet" [ quote "world" ]
              ]

      ifText : String
      ifText = ifelse (quote "x") (quote "x") (quote "yes") (quote "no")

      ifdefText : String
      ifdefText = ifdef "foo" (quote "yes") (quote "no")

      -- a macro body using $#, $* and $0
      withArgs : String
      withArgs = define "f" (argCount ++ " args of " ++ macroName ++ ": " ++ args)

      builtins : String
      builtins = eval "2 + 3 * 4" ++ " " ++ include "header.m4"
      """;

  private static String value(String name) {
    return Show.plain(Project.load(SRC, LIB).value("Main", name));
  }

  @Test
  void defineQuotesNameAndBody() {
    assertEquals("define(`greet', `Hello $1!')", value("greet"));
  }

  @Test
  void programJoinsStatementsPerLine() {
    assertEquals("define(`greet', `Hello $1!')dnl\ngreet(`world')", value("prog"));
  }

  @Test
  void conditionalsBuildIfelseAndIfdef() {
    assertEquals("ifelse(`x', `x', `yes', `no')", value("ifText"));
    assertEquals("ifdef(`foo', `yes', `no')", value("ifdefText"));
  }

  @Test
  void bodyPlaceholders() {
    assertEquals("define(`f', `$# args of $0: $*')", value("withArgs"));
  }

  @Test
  void evalAndIncludeAreQuoted() {
    assertEquals("eval(`2 + 3 * 4') include(`header.m4')", value("builtins"));
  }
}
