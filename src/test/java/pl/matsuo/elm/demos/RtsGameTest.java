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
 * The multi-file RTS example (projects/elm-rts): the real-time strategy game's pure model/logic runs
 * correctly under the interpreter — setup → start, training and building, worker gathering, the
 * algorithmic AI growing its economy — and the backend handler still answers requests. This both
 * documents the game and guards the example against bit-rot as the language evolves.
 *
 * <p>The game lives in its own repo (github.com/tunguski/elm-rts), checked out under projects/elm-rts;
 * the whole suite skips when that sibling isn't present.
 */
class RtsGameTest {

  private static final java.nio.file.Path RTS = java.nio.file.Path.of("projects/elm-rts");
  private static final boolean AVAILABLE =
      java.nio.file.Files.exists(RTS.resolve("src/RTS/Model.elm"));

  private static String src(String rel) {
    try {
      return java.nio.file.Files.readString(RTS.resolve(rel));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  // The whole frontend module graph (Main pulls in View/Chart/Game/Ai/Logic/Map/Rating/Rng/Model).
  private static final Project FRONTEND =
      AVAILABLE
          ? Project.load(
              src("src/RTS/Model.elm"),
              src("src/RTS/Rng.elm"),
              src("src/RTS/Map.elm"),
              src("src/RTS/Logic.elm"),
              src("src/RTS/Ai.elm"),
              src("src/RTS/Rating.elm"),
              src("src/RTS/Game.elm"),
              src("src/RTS/Chart.elm"),
              src("src/RTS/View.elm"),
              src("src/RTS/Main.elm"))
          : null;

  private static final Project BACKEND =
      AVAILABLE
          ? Project.load(
              src("src/RTS/Model.elm"),
              src("backend/RTS/Backend.elm"),
              Resources.read("/elm/lib/Server.elm"))
          : null;

  @org.junit.jupiter.api.BeforeEach
  void requireProject() {
    org.junit.jupiter.api.Assumptions.assumeTrue(
        AVAILABLE, "projects/elm-rts not present (separate repo github.com/tunguski/elm-rts)");
  }

  // --- helpers ----------------------------------------------------------------------------------

  private ElmRecord init() {
    return (ElmRecord) FRONTEND.value("RTS.Game", "init");
  }

  private ElmRecord update(Object msg, ElmRecord model) {
    return (ElmRecord) Apply.applyAll(FRONTEND.value("RTS.Game", "update"), msg, model);
  }

  private ElmData msg(String ctor, Object... args) {
    return new ElmData(ctor, args);
  }

  /** A started match with the given opponent count (and the default Medium map). */
  private ElmRecord started(long opponents) {
    ElmRecord configured = update(msg("SetOpponents", opponents), init());
    return update(msg("StartGame"), configured);
  }

  private static long asLong(Object o) {
    return ((Number) o).longValue();
  }

  private static String ctor(Object o) {
    return ((ElmData) o).ctor();
  }

  private static List<Object> list(Object elmList) {
    return ((ElmList) elmList).toJava();
  }

  private ElmRecord player(ElmRecord model, long id) {
    for (Object p : list(model.get("players"))) {
      ElmRecord rec = (ElmRecord) p;
      if (asLong(rec.get("id")) == id) {
        return rec;
      }
    }
    return null;
  }

  private long gold(ElmRecord model, long id) {
    return asLong(player(model, id).get("gold"));
  }

  private int countUnits(ElmRecord model, long owner, String kind) {
    int n = 0;
    for (Object u : list(model.get("units"))) {
      ElmRecord rec = (ElmRecord) u;
      if (asLong(rec.get("owner")) == owner && ctor(rec.get("kind")).equals(kind)) {
        n++;
      }
    }
    return n;
  }

  private int countBuildings(ElmRecord model, long owner, String kind) {
    int n = 0;
    for (Object b : list(model.get("buildings"))) {
      ElmRecord rec = (ElmRecord) b;
      if (asLong(rec.get("owner")) == owner && ctor(rec.get("kind")).equals(kind)) {
        n++;
      }
    }
    return n;
  }

  private ElmRecord humanBase(ElmRecord model) {
    for (Object b : list(model.get("buildings"))) {
      ElmRecord rec = (ElmRecord) b;
      if (asLong(rec.get("owner")) == 0 && ctor(rec.get("kind")).equals("Base")) {
        return rec;
      }
    }
    return null;
  }

  private long[] firstTerrain(ElmRecord model, String terrain) {
    for (Object t : list(model.get("map"))) {
      ElmRecord rec = (ElmRecord) t;
      if (ctor(rec.get("terrain")).equals(terrain)) {
        return new long[] {asLong(rec.get("x")), asLong(rec.get("y"))};
      }
    }
    return null;
  }

  private long firstHumanWorkerId(ElmRecord model) {
    for (Object u : list(model.get("units"))) {
      ElmRecord rec = (ElmRecord) u;
      if (asLong(rec.get("owner")) == 0 && ctor(rec.get("kind")).equals("Worker")) {
        return asLong(rec.get("id"));
      }
    }
    return -1;
  }

  // --- setup & start ----------------------------------------------------------------------------

  @Test
  void opensOnTheSetupScreen() {
    ElmRecord m = init();
    assertEquals("SetupScreen", ctor(m.get("screen")));
    assertEquals(1L, asLong(m.get("opponents")), "one opponent by default");
  }

  @Test
  void startingAMatchPlacesBasesWorkersAndGold() {
    ElmRecord m = started(1);
    assertEquals("GameScreen", ctor(m.get("screen")));
    assertEquals(2, list(m.get("players")).size(), "human + one AI");
    assertEquals(2, countBaseTotal(m), "one base per player");
    assertEquals(2, countUnits(m, 0, "Worker"), "human starts with two workers");
    assertEquals(150L, gold(m, 0), "human starts with 150 gold");
  }

  private int countBaseTotal(ElmRecord model) {
    int n = 0;
    for (Object b : list(model.get("buildings"))) {
      if (ctor(((ElmRecord) b).get("kind")).equals("Base")) {
        n++;
      }
    }
    return n;
  }

  @Test
  void tickAdvancesTheClock() {
    ElmRecord m = update(msg("Tick"), started(1));
    assertEquals(1L, asLong(m.get("tick")));
  }

  // --- economy ----------------------------------------------------------------------------------

  @Test
  void trainingAWorkerCostsGoldAndAddsAUnit() {
    ElmRecord m = update(msg("TrainWorker"), started(0));
    assertEquals(3, countUnits(m, 0, "Worker"), "a worker was added");
    assertEquals(100L, gold(m, 0), "50 gold spent");
  }

  @Test
  void buildingABarracksRequiresPlacingItOnAClearTile() {
    ElmRecord m = started(0);
    ElmRecord base = humanBase(m);
    long bx = asLong(base.get("x"));
    long by = asLong(base.get("y"));

    ElmRecord placing = update(msg("StartBarracks"), m);
    ElmRecord built = update(msg("ClickTile", bx, by + 2), placing);

    assertEquals(1, countBuildings(built, 0, "Barracks"), "barracks placed");
    assertEquals(30L, gold(built, 0), "120 gold spent (150 - 120)");
  }

  @Test
  void soldiersNeedABarracksFirst() {
    ElmRecord m = update(msg("TrainSoldier"), started(0));
    assertEquals(0, countUnits(m, 0, "Soldier"), "no barracks yet → no soldier");
  }

  @Test
  void aWorkerSentToAGoldMineRaisesGold() {
    ElmRecord m = started(0);
    long[] mine = firstTerrain(m, "GoldMine");
    long workerId = firstHumanWorkerId(m);

    m = update(msg("SelectUnit", workerId), m);
    m = update(msg("ClickTile", mine[0], mine[1]), m);
    for (int i = 0; i < 250; i++) {
      m = update(msg("Tick"), m);
    }
    assertTrue(gold(m, 0) > 150L, "a worker on the gold mine increased gold (was " + gold(m, 0) + ")");
  }

  // --- AI ---------------------------------------------------------------------------------------

  @Test
  void theAiGrowsItsEconomyOverTime() {
    ElmRecord m = started(1);
    for (int i = 0; i < 320; i++) {
      m = update(msg("Tick"), m);
    }
    int aiUnits = list(m.get("units")).size() == 0 ? 0 : countOwnedUnits(m, 1);
    int aiBuildings = countOwnedBuildings(m, 1);
    assertTrue(
        aiUnits > 2 || aiBuildings > 1,
        "the AI should train extra units and/or build (units=" + aiUnits + ", buildings=" + aiBuildings + ")");
  }

  private int countOwnedUnits(ElmRecord model, long owner) {
    int n = 0;
    for (Object u : list(model.get("units"))) {
      if (asLong(((ElmRecord) u).get("owner")) == owner) {
        n++;
      }
    }
    return n;
  }

  private int countOwnedBuildings(ElmRecord model, long owner) {
    int n = 0;
    for (Object b : list(model.get("buildings"))) {
      if (asLong(((ElmRecord) b).get("owner")) == owner) {
        n++;
      }
    }
    return n;
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
    ElmTuple afterSave = onRequest("POST", "/api/save", "{\"gold\":42}", "");
    assertEquals("{\"gold\":42}", afterSave.get(0), "save updates the in-memory state");
    ElmTuple afterLoad = onRequest("GET", "/api/load", "", "{\"gold\":42}");
    assertEquals("{\"gold\":42}", ((ElmRecord) afterLoad.get(1)).get("body"));
    assertEquals("null", ((ElmRecord) onRequest("GET", "/api/load", "", "").get(1)).get("body"));
    assertEquals("pong", ((ElmRecord) onRequest("GET", "/ping", "", "").get(1)).get("body"));
  }

  // --- backend stateless routes -----------------------------------------------------------------

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
    assertTrue(((String) home.get("body")).contains("Elm RTS"), "landing page");

    ElmRecord map = request("GET", "/api/map");
    assertEquals("application/json", map.get("contentType"));
    String json = (String) map.get("body");
    assertTrue(json.contains("\"sizes\""), json);
    assertTrue(json.contains("\"worker\":50"), json);
    assertTrue(json.contains("\"farm\":90"), json);

    assertEquals(404L, asLong(request("GET", "/nope").get("status")));
  }
}
