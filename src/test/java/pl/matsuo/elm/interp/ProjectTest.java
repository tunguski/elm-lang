package pl.matsuo.elm.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ProjectTest {

  @Test
  void crossModuleQualifiedAndExposed() {
    String helpers =
        """
        module Helpers exposing (double, triple)
        double n = n * 2
        triple n = n * 3
        """;
    String main =
        """
        module Main exposing (main)
        import Helpers exposing (double)
        main = double 21 + Helpers.triple 10
        """;
    Project p = Project.load(helpers, main);
    assertEquals(72L, p.value("Main", "main"));
    assertEquals(72L, p.main());
  }

  @Test
  void openExposingAndCrossModuleTypes() {
    String shapes =
        """
        module Shapes exposing (..)
        type Shape = Circle Float | Square Float
        area s =
            case s of
                Circle r -> pi * r * r
                Square side -> side * side
        """;
    String main =
        """
        module Main exposing (main)
        import Shapes exposing (..)
        main = area (Square 5.0) + area (Circle 1.0)
        """;
    Project p = Project.load(shapes, main);
    double expected = 25.0 + Math.PI;
    assertEquals(expected, (Double) p.value("Main", "main"), 1e-9);
  }

  @Test
  void mutualReferenceAcrossModulesAndLazyValues() {
    String constants =
        """
        module Constants exposing (answer)
        answer = base + 2
        base = 40
        """;
    String main =
        """
        module Main exposing (main)
        import Constants
        main = Constants.answer
        """;
    Project p = Project.load(main, constants); // note: Main listed before Constants
    assertEquals(42L, p.value("Main", "main"));
  }
}
