package pl.matsuo.elm.html;

/**
 * Constructor tag names for the virtual DOM. Html/Svg values flow through the normal value system
 * as {@link pl.matsuo.elm.runtime.ElmData}, so all three backends can produce and inspect them.
 */
public final class VDom {

  private VDom() {}

  public static final String TEXT = "$Text"; // [String]
  public static final String NODE = "$Node"; // [tag, List attribute, List html]
  public static final String ATT = "$Att"; // [name, stringValue]
  public static final String PROP = "$Prop"; // [name, value] (boolean/other property)
  public static final String STYLE = "$Style"; // [key, value]
  public static final String ON = "$On"; // [eventName, handler]
}
