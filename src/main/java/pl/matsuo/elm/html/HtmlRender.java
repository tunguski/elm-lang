package pl.matsuo.elm.html;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import pl.matsuo.elm.error.ElmRuntimeError;
import pl.matsuo.elm.runtime.ElmData;
import pl.matsuo.elm.runtime.ElmList;

/** Renders a virtual-DOM value (see {@link VDom}) to an HTML/SVG string. Event handlers are
 * ignored; attributes and inline styles are emitted deterministically. */
public final class HtmlRender {

  private static final Set<String> VOID =
      Set.of("br", "hr", "img", "input", "meta", "link", "area", "base", "col", "source");

  private HtmlRender() {}

  public static String render(Object node) {
    StringBuilder sb = new StringBuilder();
    renderInto(sb, node);
    return sb.toString();
  }

  private static void renderInto(StringBuilder sb, Object node) {
    if (!(node instanceof ElmData d)) {
      throw new ElmRuntimeError("Not an Html value: " + node);
    }
    switch (d.ctor()) {
      case VDom.TEXT -> sb.append(escapeText((String) d.arg(0)));
      case VDom.NODE -> renderElement(sb, d);
      default -> throw new ElmRuntimeError("Not an Html node: " + d.ctor());
    }
  }

  private static void renderElement(StringBuilder sb, ElmData node) {
    String tag = (String) node.arg(0);
    sb.append('<').append(tag);

    Map<String, String> attrs = new LinkedHashMap<>();
    Map<String, String> styles = new LinkedHashMap<>();
    for (Object a : ((ElmList) node.arg(1)).toJava()) {
      ElmData attr = (ElmData) a;
      switch (attr.ctor()) {
        case VDom.ATT, VDom.PROP -> {
          String name = (String) attr.arg(0);
          Object value = attr.arg(1);
          if (value instanceof Boolean b) {
            if (b) {
              attrs.put(name, name);
            }
          } else {
            attrs.put(name, String.valueOf(value));
          }
        }
        case VDom.STYLE -> styles.put((String) attr.arg(0), String.valueOf(attr.arg(1)));
        case VDom.ON -> {
          // events are not part of static rendering
        }
        default -> throw new ElmRuntimeError("Unknown attribute: " + attr.ctor());
      }
    }
    attrs.forEach((k, v) -> sb.append(' ').append(k).append("=\"").append(escapeAttr(v)).append('"'));
    if (!styles.isEmpty()) {
      StringBuilder style = new StringBuilder();
      styles.forEach((k, v) -> style.append(k).append(':').append(v).append(';'));
      sb.append(" style=\"").append(escapeAttr(style.toString())).append('"');
    }

    if (VOID.contains(tag)) {
      sb.append('>');
      return;
    }
    sb.append('>');
    for (Object child : ((ElmList) node.arg(2)).toJava()) {
      renderInto(sb, child);
    }
    sb.append("</").append(tag).append('>');
  }

  private static String escapeText(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  private static String escapeAttr(String s) {
    return s.replace("&", "&amp;").replace("\"", "&quot;");
  }
}
