package pl.matsuo.elm.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tests the modification-time change detection behind `--watch`. */
class FileWatcherTest {

  @Test
  void detectsModificationsAndIsStableWhenUnchanged() throws Exception {
    Path f = Files.createTempFile("watch-", ".elm");
    Files.writeString(f, "main = 1\n");
    List<Path> files = List.of(f);
    Map<Path, Long> stamps = FileWatcher.snapshot(files);

    assertFalse(FileWatcher.changed(files, stamps), "no change right after snapshot");

    // Bump the modification time and confirm it's detected, then settles.
    Files.setLastModifiedTime(f, FileTime.fromMillis(Files.getLastModifiedTime(f).toMillis() + 5000));
    assertTrue(FileWatcher.changed(files, stamps), "modification detected");
    assertFalse(FileWatcher.changed(files, stamps), "no further change after observing it");
  }
}
