package pl.matsuo.elm.codegen.js;

import pl.matsuo.elm.util.Resources;

/**
 * The JavaScript kernel runtime prepended to compiled output. Mirrors the interpreter's value model
 * and prelude: Int/Float are JS numbers, Bool is boolean, String is string, Char is {@code
 * {$:'Char',c}}, lists are nil/cons cells, tuples are {@code {$:'#',vs}}, records are plain objects
 * and custom-type values are {@code {$:ctor,_:[args]}}. Functions are native curried arrow
 * functions, so application is {@code f(x)(y)}.
 *
 * <p>The actual JavaScript lives in bundled resource files ({@code /elm/js/kernel.js} and {@code
 * /elm/js/dom.js}) and is loaded at runtime, rather than embedded in this source.
 */
public final class JsRuntime {

  private JsRuntime() {}

  /** The pure kernel: value constructors, equality/show, Basics/List/String/... builtins. */
  public static final String SOURCE = Resources.read("/elm/js/kernel.js");

  /** The browser/TEA runtime: virtual-DOM diff, effects (Random/Http/Time/File), WebGL, Set/Dict. */
  public static final String DOM = Resources.read("/elm/js/dom.js");
}
