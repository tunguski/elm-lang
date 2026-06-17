package pl.matsuo.elm.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Completeness checks for the kernel-backed elm-explorations/linear-algebra API (Math.Vector*,
 * Math.Matrix4). Without these, a function the package exposes can be simply absent from {@link
 * Signatures} — not a backend mismatch (which {@code JsKernelSignatureCoverageTest} and {@code
 * BuiltinSignatureCoverageTest} catch) but a hole in the declared API itself. That is how
 * {@code Math.Matrix4.makeScale} went missing: its {@code makeScale3} sibling was declared, the
 * Vec3 form was not, and nothing noticed until an app crashed.
 *
 * <p>Rather than hand-transcribe the whole external API (error-prone), the symmetry checks encode
 * the package's naming families — each {@code make<T>}/{@code make<T>3} pair is all-or-nothing, and
 * the component accessors must cover every component of a vector's dimension — plus a curated core
 * list as a regression guard.
 */
class LinearAlgebraApiCoverageTest {

  private static Set<String> sig() {
    return Signatures.globals().keySet();
  }

  @Test
  void matrix4MakeFamiliesAreSymmetric() {
    // The makeScale bug exactly: makeScale3 present, the Vec3-form makeScale missing.
    assertPair("Math.Matrix4.makeScale", "Math.Matrix4.makeScale3");
    assertPair("Math.Matrix4.makeTranslate", "Math.Matrix4.makeTranslate3");
  }

  @Test
  void vectorComponentAccessorsCoverTheWholeDimension() {
    // A Vec3 that exposes getX but not getZ (or setX but not setZ) is a hole.
    assertAccessorFamily("Math.Vector2", "get", List.of("X", "Y"));
    assertAccessorFamily("Math.Vector3", "get", List.of("X", "Y", "Z"));
    assertAccessorFamily("Math.Vector3", "set", List.of("X", "Y", "Z"));
    assertAccessorFamily("Math.Vector4", "get", List.of("X", "Y", "Z", "W"));
    assertAccessorFamily("Math.Vector4", "set", List.of("X", "Y", "Z", "W"));
  }

  @Test
  void curatedCoreApiIsDeclared() {
    // The core functions apps actually depend on; a regression guard so none is silently dropped
    // from the type-checker.
    List<String> core =
        List.of(
            "Math.Vector3.vec3",
            "Math.Vector3.add",
            "Math.Vector3.sub",
            "Math.Vector3.scale",
            "Math.Vector3.dot",
            "Math.Vector3.cross",
            "Math.Vector3.normalize",
            "Math.Vector3.length",
            "Math.Matrix4.identity",
            "Math.Matrix4.mul",
            "Math.Matrix4.transform",
            "Math.Matrix4.makeRotate",
            "Math.Matrix4.makePerspective",
            "Math.Matrix4.makeLookAt",
            "Math.Matrix4.rotate");
    for (String name : core) {
      assertTrue(sig().contains(name), "core linear-algebra function missing a type scheme: " + name);
    }
  }

  /** Both members of a {@code make<T>}/{@code make<T>3} family must be declared, or neither. */
  private static void assertPair(String a, String b) {
    assertEquals(
        sig().contains(a),
        sig().contains(b),
        "make-family must be all-or-nothing (one is declared, the other isn't): " + a + " / " + b);
  }

  /** If any accessor of a vector's component family is declared, all components of its dimension
   *  must be (so a partial family — getX without getZ — is flagged). */
  private static void assertAccessorFamily(String module, String verb, List<String> components) {
    boolean any = components.stream().anyMatch(c -> sig().contains(module + "." + verb + c));
    if (!any) {
      return; // the whole module/family is unsupported — that's allowed, just not a partial one
    }
    for (String c : components) {
      assertTrue(
          sig().contains(module + "." + verb + c),
          "incomplete accessor family: " + module + "." + verb + c + " is missing");
    }
  }
}
