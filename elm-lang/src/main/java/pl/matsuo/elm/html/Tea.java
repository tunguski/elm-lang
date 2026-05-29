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
 * {@code document}). Holds the current model, lets tests dispatch messages and inspect the rendered
 * view. Commands produced by {@code element}/{@code document} updates are collected (not executed).
 */
public final class Tea {

  private final String kind;
  private final ElmRecord def;
  private Object model;
  private final List<Object> commands = new ArrayList<>();

  private Tea(String kind, ElmRecord def, Object model) {
    this.kind = kind;
    this.def = def;
    this.model = model;
  }

  /** Starts a program value produced by {@code Browser.sandbox}/{@code element}/{@code document}. */
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
        tea.commands.add(t.get(1));
        yield tea;
      }
      default -> throw new ElmRuntimeError("Unsupported program: " + d.ctor());
    };
  }

  public Object model() {
    return model;
  }

  public List<Object> commands() {
    return commands;
  }

  /** The raw Html value of the current view (for {@code document}, the body list wrapped in a div). */
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

  /** Dispatches a message through {@code update}, updating the model (and collecting any command). */
  public void send(Object msg) {
    Object result = Apply.applyAll(def.get("update"), msg, model);
    if (kind.equals("sandbox")) {
      model = result;
    } else {
      ElmTuple t = (ElmTuple) result;
      model = t.get(0);
      commands.add(t.get(1));
    }
  }
}
