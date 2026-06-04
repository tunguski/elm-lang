package pl.matsuo.elm.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import pl.matsuo.elm.html.Tea;
import pl.matsuo.elm.interp.Interpreter;
import pl.matsuo.elm.runtime.ElmData;
import pl.matsuo.elm.runtime.ElmList;
import pl.matsuo.elm.runtime.ElmRecord;

/**
 * Drives the File examples headlessly. Browser file selection / drag-drop can't happen without a
 * browser, so the {@link Tea} driver supplies stub files (the same approach as stubbed HTTP), and
 * messages are dispatched as a real browser would after a pick/drop.
 */
class FilesExamplesTest {

  private static String source(String slug) {
    try (InputStream in = FilesExamplesTest.class.getResourceAsStream("/elm/examples/" + slug + ".elm")) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /** A stub File value: $File[name, mime, dataUrl]. */
  private static ElmData file(String name, String url) {
    return new ElmData("$File", new Object[] {name, "image/png", url});
  }

  @Test
  void upload() {
    Interpreter interp = Interpreter.load(source("upload"));
    Tea app = Tea.start(interp.value("main"));
    assertTrue(app.html().contains("type=\"file\""), app.html());
    assertTrue(app.html().contains("multiple"), app.html());

    // As the browser would after a file input "change": deliver the selected files.
    ElmList files = ElmList.fromJava(List.of(file("a.txt", "data:a"), file("b.txt", "data:b")));
    app.send(new ElmData("GotFiles", new Object[] {files}));
    assertEquals(2, ((ElmList) app.model()).toJava().size());
  }

  @Test
  void dragAndDrop() {
    Interpreter interp = Interpreter.load(source("drag-and-drop"));
    Tea app = Tea.start(interp.value("main"));
    assertTrue(app.html().contains("6px dashed #ccc"), app.html()); // not hovering

    app.send(interp.evalExpr("DragEnter"));
    assertTrue(app.html().contains("6px dashed purple"), app.html()); // hovering

    // Click "Upload Images" -> Select.files command -> driver supplies stub files -> GotFiles.
    app.provideFiles(List.of(file("x.png", "data:x"), file("y.png", "data:y")));
    app.send(interp.evalExpr("Pick"));
    ElmRecord model = (ElmRecord) app.model();
    assertEquals(2, ((ElmList) model.get("files")).toJava().size());
    assertEquals(false, model.get("hover"));
  }

  @Test
  void imagePreviews() {
    Interpreter interp = Interpreter.load(source("image-previews"));
    Tea app = Tea.start(interp.value("main"));
    app.provideFiles(List.of(file("a.png", "data:img-a"), file("b.png", "data:img-b")));

    // Pick -> Select.files -> GotFiles -> Task.sequence (List.map File.toUrl files) -> GotPreviews.
    app.send(interp.evalExpr("Pick"));
    String html = app.html();
    assertTrue(html.contains("url('data:img-a')"), html);
    assertTrue(html.contains("url('data:img-b')"), html);
  }
}
