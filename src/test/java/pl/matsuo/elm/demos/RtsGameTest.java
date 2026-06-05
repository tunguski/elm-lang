package pl.matsuo.elm.demos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pl.matsuo.elm.interp.Apply;
import pl.matsuo.elm.interp.Project;
import pl.matsuo.elm.runtime.ElmData;
import pl.matsuo.elm.runtime.ElmList;
import pl.matsuo.elm.runtime.ElmRecord;
import pl.matsuo.elm.runtime.ElmTuple;
import pl.matsuo.elm.util.Resources;

/**
 * The multi-file RTS example (src/main/elm/rts): the frontend's pure model/logic runs correctly under
 * the interpreter (build/train/gather/fog), and the backend handler answers requests. This both
 * documents the game and guards the example against bit-rot as the language evolves.
 */
class RtsGameTest {

  // The example lives under src/main/elm/rts (outside the resources root), so read it from disk.
  private static String src(String name) {
    try {
      return java.nio.file.Files.readString(java.nio.file.Path.of("src/main/elm/rts", name));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static final Project FRONTEND =
      Project.load(src("Model.elm"), src("Logic.elm"), src("View.elm"), src("Main.elm"));

  private static final Project BACKEND =
      Project.load(src("Model.elm"), src("Backend.elm"), Resources.read("/elm/lib/Server.elm"));

  private ElmRecord init() {
    return (ElmRecord) FRONTEND.value("RTS.Logic", "init");
  }

  private ElmRecord update(Object msg, ElmRecord model) {
    return (ElmRecord) Apply.applyAll(FRONTEND.value("RTS.Logic", "update"), msg, model);
  }

  private static long asLong(Object o) {
    return ((Number) o).longValue();
  }

  private static int listSize(Object list) {
    return ((ElmList) list).toJava().size();
  }

  @Test
  void startsWithABaseAWorkerAndGold() {
    ElmRecord m = init();
    assertEquals(150L, asLong(m.get("gold")));
    assertEquals(1, listSize(m.get("units")), "one starting worker");
    assertEquals(1, listSize(m.get("buildings")), "one starting base");
  }

  @Test
  void tickAdvancesTheClock() {
    ElmRecord m = update(new ElmData("Tick", new Object[0]), init());
    assertEquals(1L, asLong(m.get("tick")));
  }

  @Test
  void trainingAWorkerCostsGoldAndAddsAUnit() {
    ElmRecord m = update(new ElmData("TrainWorker", new Object[0]), init());
    assertEquals(2, listSize(m.get("units")), "a worker was added");
    assertEquals(100L, asLong(m.get("gold")), "50 gold spent");
  }

  @Test
  void buildingABarracksRequiresPlacingItOnAClearTile() {
    // Enter placing mode, then click a clear grass tile near the base.
    ElmRecord placing = update(new ElmData("StartBarracks", new Object[0]), init());
    ElmRecord built = update(new ElmData("ClickTile", new Object[] {1L, 4L}), placing);
    assertEquals(2, listSize(built.get("buildings")), "barracks placed");
    assertEquals(30L, asLong(built.get("gold")), "120 gold spent (150 - 120)");
  }

  @Test
  void workerMovesToAGoldMineGathersAndFogIsCleared() {
    ElmRecord m = init();
    int visibleStart = countVisible(m);
    // The starting worker (id 1) is pre-selected; send it to the gold mine at (14, 2).
    m = update(new ElmData("ClickTile", new Object[] {14L, 2L}), m);
    for (int i = 0; i < 200; i++) {
      m = update(new ElmData("Tick", new Object[0]), m);
    }
    assertTrue(asLong(m.get("gold")) > 150L, "worker on the gold mine increased gold");
    assertTrue(countVisible(m) > visibleStart, "moving across the map cleared more fog");
  }

  @Test
  void workerPathfindsAroundObstaclesToAFarResource() {
    // The gold mine at (16, 9) sits past the water lake (x 8-10, y 4-7) and scattered rock; a worker
    // must route *around* them. After enough ticks it gathers and deposits, so gold rises.
    ElmRecord m = init();
    m = update(new ElmData("ClickTile", new Object[] {16L, 9L}), m);
    for (int i = 0; i < 400; i++) {
      m = update(new ElmData("Tick", new Object[0]), m);
    }
    assertTrue(asLong(m.get("gold")) > 150L, "worker reached the far mine around the obstacles");
  }

  @Test
  void runningOutOfTimeWithoutExploringLoses() {
    ElmRecord m = init();
    assertEquals("Playing", ((ElmData) m.get("status")).ctor());
    // Never move the worker out to explore; once the tick limit passes, the game is lost and frozen.
    for (int i = 0; i < 620; i++) {
      m = update(new ElmData("Tick", new Object[0]), m);
    }
    assertEquals("Lost", ((ElmData) m.get("status")).ctor());
    long lostTick = asLong(m.get("tick"));
    // Further ticks are ignored once the game is over (the clock froze).
    m = update(new ElmData("Tick", new Object[0]), m);
    assertEquals(lostTick, asLong(m.get("tick")), "the game freezes after it ends");
  }

  // --- backend save/load (stateful server) ------------------------------------------------------

  private ElmTuple onRequest(String method, String path, String body, String state) {
    Map<String, Object> fields = new java.util.LinkedHashMap<>();
    fields.put("method", method);
    fields.put("path", path);
    fields.put("query", ElmList.fromJava(List.of()));
    fields.put("body", body);
    Object onReq = ((ElmRecord) BACKEND.value("RTS.Backend", "main")).get("onRequest");
    return (ElmTuple) Apply.applyAll(onReq, new ElmRecord(fields), state);
  }

  @Test
  void backendSavesAndLoadsGameState() {
    // Saving stores the posted body as the new server state.
    ElmTuple afterSave = onRequest("POST", "/api/save", "{\"gold\":42}", "");
    assertEquals("{\"gold\":42}", afterSave.get(0), "save updates the in-memory state");
    // Loading from that state returns it as the response body.
    ElmTuple afterLoad = onRequest("GET", "/api/load", "", "{\"gold\":42}");
    assertEquals("{\"gold\":42}", ((ElmRecord) afterLoad.get(1)).get("body"));
    // Loading with nothing saved yet yields null.
    assertEquals("null", ((ElmRecord) onRequest("GET", "/api/load", "", "").get(1)).get("body"));
    // Other routes still work through the shared handler.
    assertEquals("pong", ((ElmRecord) onRequest("GET", "/ping", "", "").get(1)).get("body"));
  }

  private int countVisible(ElmRecord model) {
    int n = 0;
    for (Object tile : ((ElmList) model.get("map")).toJava()) {
      if (Boolean.TRUE.equals(((ElmRecord) tile).get("visible"))) {
        n++;
      }
    }
    return n;
  }

  // --- backend ----------------------------------------------------------------------------------

  private ElmRecord request(String method, String path) {
    Map<String, Object> fields = new java.util.LinkedHashMap<>();
    fields.put("method", method);
    fields.put("path", path);
    fields.put("query", ElmList.fromJava(List.of()));
    fields.put("body", "");
    ElmRecord req = new ElmRecord(fields);
    return (ElmRecord) Apply.apply(BACKEND.value("RTS.Backend", "handle"), req);
  }

  @Test
  void backendServesPingPageAndMapJson() {
    assertEquals("pong", request("GET", "/ping").get("body"));

    ElmRecord home = request("GET", "/");
    assertEquals(200L, asLong(home.get("status")));
    assertTrue(((String) home.get("body")).contains("RTS Mini"), "landing page");

    ElmRecord map = request("GET", "/api/map");
    assertEquals("application/json", map.get("contentType"));
    String json = (String) map.get("body");
    assertTrue(json.contains("\"width\":20"), json);
    assertTrue(json.contains("\"worker\":50"), json);
    assertTrue(json.contains("gold"), json);

    assertEquals(404L, asLong(request("GET", "/nope").get("status")));
  }
}
