package pl.matsuo.elm.codegen.js;

import static java.util.stream.Collectors.toCollection;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import pl.matsuo.elm.interp.Prelude;
import pl.matsuo.elm.types.Signatures;

/**
 * Every pure elm/core builtin the interpreter binds (List/Dict/Set/Array/String/Char/Tuple/Maybe/
 * Result/Bitwise) must also be registered in the JS runtime kernel. Otherwise an app that type-checks
 * and runs interpreted throws {@code Unbound: <name>} and blanks the page the moment it is compiled
 * to JS — exactly the class of bug reported for {@code Set.intersect}. (Basics is excluded: the
 * interpreter lowers it to operators rather than named builtins; effect modules — Task, Time, Http,
 * Random, … — are driven by the TEA runtime, not the {@code $rt} registry.)
 */
class CoreBuiltinParityTest {

  private static final Set<String> PURE_MODULES =
      Set.of(
          "List", "Dict", "Set", "Array", "String", "Char", "Tuple", "Maybe", "Result", "Bitwise");

  @Test
  void jsKernelBindsEveryPureInterpreterBuiltin() throws IOException {
    Set<String> interp =
        Prelude.builtins().keySet().stream()
            .filter(CoreBuiltinParityTest::isPureBuiltin)
            .collect(toCollection(TreeSet::new));
    Set<String> js = jsRegisteredBuiltins();
    Set<String> missing = new TreeSet<>(interp);
    missing.removeAll(js);
    assertTrue(
        missing.isEmpty(),
        "these pure-stdlib builtins run in the interpreter but are unbound in the JS backend "
            + "(they would crash at runtime when compiled to JS): " + missing);
  }

  @Test
  void effectManagerPrimitivesAreBoundAndTypedEverywhere() throws IOException {
    // Process.spawn/kill and Platform.sendToApp/sendToSelf can't be called from ordinary code (they
    // need a Router only effect managers receive), so assert by name that they exist in the
    // interpreter, the type-checker and the JS runtime — present for spec conformance on every path.
    Set<String> js = jsRegisteredBuiltins();
    for (String name :
        new String[] {"Process.spawn", "Process.kill", "Platform.sendToApp", "Platform.sendToSelf"}) {
      assertTrue(Prelude.builtins().containsKey(name), name + " must be bound in the interpreter");
      assertTrue(Signatures.globals().containsKey(name), name + " must have a type scheme");
      assertTrue(js.contains(name), name + " must be registered in the JS runtime");
    }
  }

  @Test
  void smallStdlibAdditionsAreBoundAndTyped() throws IOException {
    // Effect/opaque-value functions added for elm-package conformance: assert by name across the
    // interpreter, the type-checker and (where they have a JS runtime) the JS backend.
    for (String n :
        new String[] {
          "Time.getZoneName",
          "Browser.Navigation.reload",
          "Browser.Navigation.reloadAndSkipCache",
          "File.lastModified",
          "WebGL.preserveDrawingBuffer",
          "WebGL.Texture.defaultOptions"
        }) {
      assertTrue(Prelude.builtins().containsKey(n), n + " must be bound in the interpreter");
      assertTrue(Signatures.globals().containsKey(n), n + " must have a type scheme");
    }
    // WebGL has no JS code-gen; the rest must be registered in the JS runtime.
    String dom;
    try (InputStream in = CoreBuiltinParityTest.class.getResourceAsStream("/elm/js/dom.js")) {
      dom = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    for (String n :
        new String[] {
          "Time.getZoneName",
          "Browser.Navigation.reload",
          "Browser.Navigation.reloadAndSkipCache",
          "File.lastModified"
        }) {
      assertTrue(dom.contains("'" + n + "'"), n + " must be registered in the JS runtime");
    }
  }

  private static boolean isPureBuiltin(String name) {
    int dot = name.indexOf('.');
    return dot > 0 && PURE_MODULES.contains(name.substring(0, dot));
  }

  /** Every {@code 'Module.fn'} the JS kernel/runtime quotes (its registration keys). */
  private static Set<String> jsRegisteredBuiltins() throws IOException {
    Set<String> names = new TreeSet<>();
    Pattern p = Pattern.compile("['\"]([A-Z][A-Za-z]*\\.[A-Za-z0-9_]+)['\"]");
    for (String resource : new String[] {"/elm/js/kernel.js", "/elm/js/dom.js"}) {
      try (InputStream in = CoreBuiltinParityTest.class.getResourceAsStream(resource)) {
        Matcher m = p.matcher(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        while (m.find()) {
          names.add(m.group(1));
        }
      }
    }
    return names;
  }
}
