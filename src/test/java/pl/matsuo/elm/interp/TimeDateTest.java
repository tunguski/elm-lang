package pl.matsuo.elm.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Time date breakdown: toYear/toMonth/toDay/toWeekday from a Posix instant in UTC. */
class TimeDateTest {

  private static String eval(String expr) {
    return Show.plain(Interpreter.eval(expr));
  }

  // 2021-06-15T12:00:00Z = 1623758400000 ms (a Tuesday).
  private static final String POSIX = "(Time.millisToPosix 1623758400000)";

  @Test
  void breaksDownADateInUtc() {
    assertEquals("2021", eval("Time.toYear Time.utc " + POSIX));
    assertEquals("Jun", eval("Time.toMonth Time.utc " + POSIX));
    assertEquals("15", eval("Time.toDay Time.utc " + POSIX));
    assertEquals("Tue", eval("Time.toWeekday Time.utc " + POSIX));
  }

  @Test
  void appliesTheZoneOffset() {
    // A -720-minute (UTC-12) zone shifts 2021-06-15T12:00Z back to 00:00 the same day.
    assertEquals("0", eval("Time.toHour (Time.customZone -720 []) " + POSIX));
    assertEquals("15", eval("Time.toDay (Time.customZone -720 []) " + POSIX));
    // A +720-minute (UTC+12) zone crosses into the next day.
    assertEquals("16", eval("Time.toDay (Time.customZone 720 []) " + POSIX));
  }
}
