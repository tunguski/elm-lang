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
