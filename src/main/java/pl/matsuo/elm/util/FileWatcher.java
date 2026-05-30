package pl.matsuo.elm.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A tiny modification-time poller for {@code --watch}: re-run an action when any watched file changes. */
public final class FileWatcher {

  private FileWatcher() {}

  /** Last-modified timestamps (epoch millis) of the given files; missing files map to -1. */
  public static Map<Path, Long> snapshot(List<Path> files) {
    Map<Path, Long> stamps = new LinkedHashMap<>();
    for (Path f : files) {
      stamps.put(f, lastModified(f));
    }
    return stamps;
  }

  /**
   * Returns true if any file's modification time differs from {@code stamps}, updating {@code stamps}
   * to the current values. (So a caller can poll {@code changed(files, stamps)} in a loop.)
   */
  public static boolean changed(List<Path> files, Map<Path, Long> stamps) {
    boolean any = false;
    for (Path f : files) {
      long now = lastModified(f);
      Long prev = stamps.get(f);
      if (prev == null || prev != now) {
        any = true;
        stamps.put(f, now);
      }
    }
    return any;
  }

  private static long lastModified(Path f) {
    try {
      return Files.getLastModifiedTime(f).toMillis();
    } catch (Exception e) {
      return -1;
    }
  }

  /**
   * Runs {@code action} once, then re-runs it whenever any of {@code files} changes, polling every
   * {@code pollMillis}. Blocks until interrupted. Exceptions from {@code action} are reported and the
   * watch continues, so a transient error doesn't stop the loop.
   */
  public static void watch(List<Path> files, long pollMillis, Runnable action) throws InterruptedException {
    Map<Path, Long> stamps = snapshot(files);
    runSafely(action);
    System.out.println("Watching " + files.size() + " file(s) for changes… (Ctrl-C to stop)");
    while (true) {
      Thread.sleep(pollMillis);
      if (changed(files, stamps)) {
        runSafely(action);
      }
    }
  }

  private static void runSafely(Runnable action) {
    try {
      action.run();
    } catch (RuntimeException e) {
      System.out.println("Error: " + e.getMessage());
    }
  }
}
