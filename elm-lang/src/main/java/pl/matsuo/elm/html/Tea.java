package pl.matsuo.elm.html;

import java.util.ArrayList;
import java.util.List;
import pl.matsuo.elm.error.ElmRuntimeError;
import pl.matsuo.elm.interp.Apply;
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

  private Tea(String kind, ElmRecord def, Object model) {
    this.kind = kind;
    this.def = def;
    this.model = model;
  }

  public static Tea start(Object program) {
    if (!(program instanceof ElmData d) || !(d.arg(0) instanceof ElmRecord def)) {
      throw new ElmRuntimeError("Not a Browser program: " + program);
    }
    return switch (d.ctor()) {
      case "$Sandbox" -> new Tea("sandbox", def, def.get("init"));
      case "$Element", "$Document" -> {
        Object pair = Apply.apply(def.get("init"), ElmUnit.INSTANCE);
        ElmTuple t = (ElmTuple) pair;
        Tea tea = new Tea(d.ctor().equals("$Document") ? "document" : "element", def, t.get(0));
        tea.runCmd(t.get(1));
        yield tea;
      }
      default -> throw new ElmRuntimeError("Unsupported program: " + d.ctor());
    };
  }

  public void setSeed(long seed) {
    this.seed = seed;
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
        Object value = runTask(d.arg(0));
        send(Apply.apply(d.arg(1), value));
      }
      default -> {}
    }
  }

  private Object runTask(Object task) {
    if (task instanceof ElmData d && d.ctor().equals("$Task_Const")) {
      return d.arg(0);
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
      case "$Gen_AndThen" -> runGen(Apply.apply(g.arg(0), runGen(g.arg(1))));
      default -> throw new ElmRuntimeError("Unsupported generator: " + g.ctor());
    };
  }
}
