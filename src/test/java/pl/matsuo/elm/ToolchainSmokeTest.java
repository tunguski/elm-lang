package pl.matsuo.elm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Verifies the Maven + JUnit 5 + Java 21 toolchain is wired up correctly. */
class ToolchainSmokeTest {

  @Test
  void junitRuns() {
    assertEquals(4, 2 + 2);
  }

  @Test
  void recordsAndSealedWork() {
    // Exercises a Java 21 feature we rely on heavily (records + pattern switch).
    Shape s = new Shape.Circle(2.0);
    double area =
        switch (s) {
          case Shape.Circle c -> Math.PI * c.radius() * c.radius();
          case Shape.Square sq -> sq.side() * sq.side();
        };
    assertEquals(Math.PI * 4.0, area, 1e-9);
  }

  sealed interface Shape permits Shape.Circle, Shape.Square {
    record Circle(double radius) implements Shape {}

    record Square(double side) implements Shape {}
  }
}
