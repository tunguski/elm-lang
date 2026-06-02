package pl.matsuo.elm.html;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import pl.matsuo.elm.error.ElmRuntimeError;
import pl.matsuo.elm.interp.Apply;
import pl.matsuo.elm.json.DecoderRunner;
import pl.matsuo.elm.json.JsonParse;
import pl.matsuo.elm.runtime.ElmData;
import pl.matsuo.elm.runtime.ElmList;
import pl.matsuo.elm.runtime.ElmRecord;
import pl.matsuo.elm.runtime.ElmTuple;
import pl.matsuo.elm.runtime.ElmUnit;

/**
 * A headless driver for The Elm Architecture programs ({@code Browser.sandbox}/{@code element}/
 * {@code document}). Holds the current model, dispatches messages through {@code update}, and
 * interprets the commands ({@code Random}, {@code Task}) and time subscriptions an {@code element}
 * produces — deterministically, so examples can be tested without a browser.
 */
public final class Tea {

  private final String kind;
  private final ElmRecord def;
  private Object model;
  private long seed = 0x2545F4914F6CDD1DL; // deterministic random state
  private Map<String, String> httpResponses = Map.of();
  private List<Object> selectableFiles = new ArrayList<>();
  private boolean allowNetwork = false; // when true, an uncanned Http.get performs a real request

  private Tea(String kind, ElmRecord def, Object model) {
    this.kind = kind;
    this.def = def;
    this.model = model;
  }

  public static Tea start(Object program) {
    return start(program, Map.of());
  }

  /** Starts a program, supplying canned HTTP responses keyed by URL (consulted by Http.get). */
  public static Tea start(Object program, Map<String, String> httpResponses) {
    return start(program, httpResponses, false);
  }

  /**
   * Starts a program with <b>real effects</b> enabled: an {@code Http.get} with no canned response
   * performs an actual network request, and {@code Random} is seeded non-deterministically. Used by
   * {@code elm run}/{@code project run} so effectful programs work outside the browser; tests use
   * the offline {@link #start(Object)} to stay deterministic.
   */
  public static Tea startLive(Object program) {
    return start(program, Map.of(), true);
  }

  /** Starts a program, supplying canned HTTP responses and choosing whether real network is allowed. */
  public static Tea start(Object program, Map<String, String> httpResponses, boolean allowNetwork) {
    if (!(program instanceof ElmData d) || !(d.arg(0) instanceof ElmRecord def)) {
      throw new ElmRuntimeError("Not a Browser program: " + program);
    }
    Tea tea;
    Object pendingCmd = null;
    if (d.ctor().equals("$Sandbox")) {
      tea = new Tea("sandbox", def, def.get("init"));
    } else if (d.ctor().equals("$Application")) {
      // init : flags -> Url -> Key -> ( model, Cmd msg ); view returns a Document, so render like one.
      Object init1 = Apply.apply(def.get("init"), ElmUnit.INSTANCE);
      Object init2 = Apply.apply(init1, defaultUrl());
      ElmTuple t = (ElmTuple) Apply.apply(init2, ElmUnit.INSTANCE); // Key is opaque
      tea = new Tea("document", def, t.get(0));
      pendingCmd = t.get(1);
    } else {
      ElmTuple t = (ElmTuple) Apply.apply(def.get("init"), ElmUnit.INSTANCE);
      tea = new Tea(d.ctor().equals("$Document") ? "document" : "element", def, t.get(0));
      pendingCmd = t.get(1);
    }
    tea.httpResponses = httpResponses;
    tea.allowNetwork = allowNetwork;
    if (allowNetwork) {
      tea.seed = System.nanoTime() * 2862933555777941757L + 3037000493L;
    }
    if (pendingCmd != null) {
      tea.runCmd(pendingCmd);
    }
    return tea;
  }

  public void setSeed(long seed) {
    this.seed = seed;
  }

  /** Supplies the files that a subsequent {@code File.Select} command will "select" (headless). */
  public void provideFiles(List<Object> files) {
    this.selectableFiles = files;
  }

  public Object model() {
    return model;
  }

  public Object view() {
    Object v = Apply.apply(def.get("view"), model);
    if (kind.equals("document") && v instanceof ElmRecord doc) {
      return new ElmData("$Node", new Object[] {"body", ElmList.NIL, doc.get("body")});
    }
    return v;
  }

  public String html() {
    return HtmlRender.render(view());
  }

  /** Dispatches a message, updating the model and running any resulting command. */
  public void send(Object msg) {
    if (kind.equals("sandbox")) {
      model = Apply.applyAll(def.get("update"), msg, model);
      return;
    }
    ElmTuple result = (ElmTuple) Apply.applyAll(def.get("update"), msg, model);
    model = result.get(0);
    runCmd(result.get(1));
  }

  /** Fires every {@code Time.every} subscription once with the given posix time (ms). */
  public void tick(long millis) {
    if (def.has("subscriptions")) {
      fireSubs(Apply.apply(def.get("subscriptions"), model), millis);
    }
  }

  private void fireSubs(Object sub, long millis) {
    if (!(sub instanceof ElmData d)) {
      return;
    }
    switch (d.ctor()) {
      case "$SubBatch" -> {
        for (Object s : ((ElmList) d.arg(0)).toJava()) {
          fireSubs(s, millis);
        }
      }
      case "$Sub_Every" -> send(Apply.apply(d.arg(1), new ElmData("$Posix", new Object[] {millis})));
      default -> {}
    }
  }

  // --- command interpretation -------------------------------------------

  private void runCmd(Object cmd) {
    if (!(cmd instanceof ElmData d)) {
      return;
    }
    switch (d.ctor()) {
      case "$CmdNone" -> {}
      case "$CmdBatch" -> {
        for (Object c : ((ElmList) d.arg(0)).toJava()) {
          runCmd(c);
        }
      }
      case "$Cmd_Random" -> {
        Object value = runGen(d.arg(0));
        send(Apply.apply(d.arg(1), value));
      }
      case "$Cmd_Task" -> {
        // Task.perform can't fail; if a (mapped/chained) task does, deliver nothing.
        try {
          send(Apply.apply(d.arg(1), runTask(d.arg(0))));
        } catch (TaskFail ignored) {
          // no message on failure
        }
      }
      case "$Cmd_TaskAttempt" -> {
        try {
          send(Apply.apply(d.arg(1), ok(runTask(d.arg(0)))));
        } catch (TaskFail f) {
          send(Apply.apply(d.arg(1), err(f.error)));
        }
      }
      case "$Cmd_Http" -> runHttp((String) d.arg(0), (ElmData) d.arg(1));
      case "$Cmd_SelectFile" -> {
        if (!selectableFiles.isEmpty()) {
          send(Apply.apply(d.arg(0), selectableFiles.get(0)));
        }
      }
      case "$Cmd_SelectFiles" -> {
        if (!selectableFiles.isEmpty()) {
          ElmList rest = ElmList.fromJava(selectableFiles.subList(1, selectableFiles.size()));
          send(Apply.applyAll(d.arg(0), selectableFiles.get(0), rest));
        }
      }
      default -> {}
    }
  }

  private void runHttp(String url, ElmData expect) {
    Object toMsg = expect.arg(0);
    String body = httpResponses.get(url);
    if (body == null) {
      if (allowNetwork) {
        // Perform a real request (elm run / project run); a non-200 or failure becomes an Http.Error.
        body = fetch(url, toMsg);
        if (body == null) {
          return; // fetch() already delivered the error message
        }
      } else {
        // Offline (tests): deliver a network error (matched by `Err _`).
        send(Apply.apply(toMsg, err(new ElmData("NetworkError", new Object[0]))));
        return;
      }
    }
    switch (expect.ctor()) {
      case "$Expect_String" -> send(Apply.apply(toMsg, ok(body)));
      case "$Expect_Whatever" -> send(Apply.apply(toMsg, ok(ElmUnit.INSTANCE)));
      case "$Expect_Json" -> {
        ElmData decoded;
        try {
          decoded = DecoderRunner.run(expect.arg(1), JsonParse.parse(body));
        } catch (RuntimeException ex) {
          send(Apply.apply(toMsg, err(new ElmData("BadBody", new Object[] {ex.getMessage()}))));
          return;
        }
        if (decoded.ctor().equals("Ok")) {
          send(Apply.apply(toMsg, ok(decoded.arg(0))));
        } else {
          send(Apply.apply(toMsg, err(new ElmData("BadBody", new Object[] {decoded.arg(0)}))));
        }
      }
      default -> {}
    }
  }

  /**
   * Performs a real HTTP GET. Returns the body on 200, or delivers the matching {@code Http.Error}
   * message ({@code BadStatus}/{@code NetworkError}) and returns null.
   */
  private String fetch(String url, Object toMsg) {
    try {
      var client = java.net.http.HttpClient.newBuilder()
          .connectTimeout(java.time.Duration.ofSeconds(10)).build();
      var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
          .timeout(java.time.Duration.ofSeconds(15)).GET().build();
      var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 200) {
        return response.body();
      }
      send(Apply.apply(toMsg, err(new ElmData("BadStatus", new Object[] {(long) response.statusCode()}))));
      return null;
    } catch (Exception e) {
      send(Apply.apply(toMsg, err(new ElmData("NetworkError", new Object[0]))));
      return null;
    }
  }

  private static ElmData ok(Object v) {
    return new ElmData("Ok", new Object[] {v});
  }

  /** The initial Url handed to a {@code Browser.application}'s init in a headless run
   *  ({@code https://localhost/}). */
  private static ElmRecord defaultUrl() {
    Map<String, Object> url = new java.util.LinkedHashMap<>();
    url.put("protocol", new ElmData("Https", new Object[] {}));
    url.put("host", "localhost");
    url.put("port_", new ElmData("Nothing", new Object[] {}));
    url.put("path", "/");
    url.put("query", new ElmData("Nothing", new Object[] {}));
    url.put("fragment", new ElmData("Nothing", new Object[] {}));
    return new ElmRecord(url);
  }

  private static ElmData err(Object e) {
    return new ElmData("Err", new Object[] {e});
  }

  /** A task that failed, carrying its error value (caught by onError/mapError/attempt). */
  private static final class TaskFail extends RuntimeException {
    final Object error;

    TaskFail(Object error) {
      super(null, null, false, false);
      this.error = error;
    }
  }

  private Object runTask(Object task) {
    if (task instanceof ElmData d) {
      switch (d.ctor()) {
        case "$Task_Const" -> {
          return d.arg(0);
        }
        case "$Task_Fail" -> throw new TaskFail(d.arg(0));
        case "$Task_Map" -> {
          return Apply.apply(d.arg(0), runTask(d.arg(1)));
        }
        case "$Task_AndThen" -> {
          return runTask(Apply.apply(d.arg(0), runTask(d.arg(1))));
        }
        case "$Task_MapError" -> {
          try {
            return runTask(d.arg(1));
          } catch (TaskFail f) {
            throw new TaskFail(Apply.apply(d.arg(0), f.error));
          }
        }
        case "$Task_OnError" -> {
          try {
            return runTask(d.arg(1));
          } catch (TaskFail f) {
            return runTask(Apply.apply(d.arg(0), f.error));
          }
        }
        case "$Task_Seq" -> {
          List<Object> out = new ArrayList<>();
          for (Object t : ((ElmList) d.arg(0)).toJava()) {
            out.add(runTask(t));
          }
          return ElmList.fromJava(out);
        }
        default -> {}
      }
    }
    throw new ElmRuntimeError("Unsupported task: " + task);
  }

  // --- random generators -------------------------------------------------

  private long nextSeed() {
    // xorshift64*
    seed ^= seed >>> 12;
    seed ^= seed << 25;
    seed ^= seed >>> 27;
    return seed * 0x2545F4914F6CDD1DL;
  }

  private double nextDouble() {
    return (nextSeed() >>> 11) * (1.0 / (1L << 53));
  }

  private Object runGen(Object gen) {
    ElmData g = (ElmData) gen;
    return switch (g.ctor()) {
      case "$Gen_Int" -> {
        long lo = (Long) g.arg(0);
        long hi = (Long) g.arg(1);
        yield lo + Math.floorMod(nextSeed(), hi - lo + 1);
      }
      case "$Gen_Float" -> {
        double lo = ((Number) g.arg(0)).doubleValue();
        double hi = ((Number) g.arg(1)).doubleValue();
        yield lo + nextDouble() * (hi - lo);
      }
      case "$Gen_Uniform" -> {
        List<Object> all = new ArrayList<>();
        all.add(g.arg(0));
        all.addAll(((ElmList) g.arg(1)).toJava());
        yield all.get((int) Math.floorMod(nextSeed(), all.size()));
      }
      case "$Gen_List" -> {
        long n = (Long) g.arg(0);
        List<Object> out = new ArrayList<>();
        for (long i = 0; i < n; i++) {
          out.add(runGen(g.arg(1)));
        }
        yield ElmList.fromJava(out);
      }
      case "$Gen_Pair" -> new ElmTuple(new Object[] {runGen(g.arg(0)), runGen(g.arg(1))});
      case "$Gen_Const" -> g.arg(0);
      case "$Gen_Map" -> Apply.apply(g.arg(0), runGen(g.arg(1)));
      case "$Gen_Map2" -> Apply.applyAll(g.arg(0), runGen(g.arg(1)), runGen(g.arg(2)));
      case "$Gen_Map3" ->
          Apply.applyAll(g.arg(0), runGen(g.arg(1)), runGen(g.arg(2)), runGen(g.arg(3)));
      case "$Gen_Map4" ->
          Apply.applyAll(g.arg(0), runGen(g.arg(1)), runGen(g.arg(2)), runGen(g.arg(3)), runGen(g.arg(4)));
      case "$Gen_Map5" ->
          Apply.applyAll(g.arg(0), runGen(g.arg(1)), runGen(g.arg(2)), runGen(g.arg(3)), runGen(g.arg(4)), runGen(g.arg(5)));
      case "$Gen_Weighted" -> {
        List<Object> all = new ArrayList<>();
        all.add(g.arg(0));
        all.addAll(((ElmList) g.arg(1)).toJava());
        double total = 0;
        for (Object t : all) {
          total += Math.abs(((Number) ((ElmTuple) t).get(0)).doubleValue());
        }
        double r = nextDouble() * total;
        Object chosen = ((ElmTuple) all.get(all.size() - 1)).get(1);
        for (Object t : all) {
          r -= Math.abs(((Number) ((ElmTuple) t).get(0)).doubleValue());
          if (r <= 0) {
            chosen = ((ElmTuple) t).get(1);
            break;
          }
        }
        yield chosen;
      }
      case "$Gen_AndThen" -> runGen(Apply.apply(g.arg(0), runGen(g.arg(1))));
      default -> throw new ElmRuntimeError("Unsupported generator: " + g.ctor());
    };
  }
}
