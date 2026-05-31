package pl.matsuo.elm.bundle;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * Builds a standalone artifact from an Elm script or server: a self-contained executable JAR (the
 * whole runtime classpath merged into one jar with the Elm source embedded and {@link Standalone} as
 * the entry point), and — when a GraalVM {@code native-image} is available — a true native binary.
 *
 * <p>This is only meaningful when running on a JDK with the build toolchain present; it is refused
 * inside an already-compiled {@code elm} native binary (which has neither a classpath to bundle nor
 * the ability to drive {@code native-image}).
 */
public final class Bundler {

  /** The launcher class used as the bundled jar's {@code Main-Class}. */
  public static final String LAUNCHER = "pl.matsuo.elm.bundle.Standalone";

  private Bundler() {}

  /** True when the current process is itself a GraalVM-compiled native image. */
  public static boolean runningAsNativeImage() {
    return System.getProperty("org.graalvm.nativeimage.imagecode") != null;
  }

  /** Locates a GraalVM {@code native-image} launcher via GRAALVM_HOME, JAVA_HOME, then PATH. */
  public static Optional<Path> findNativeImage() {
    String exe = System.getProperty("os.name", "").toLowerCase().contains("win")
        ? "native-image.cmd"
        : "native-image";
    for (String home : new String[] {System.getenv("GRAALVM_HOME"), System.getProperty("java.home")}) {
      if (home != null && !home.isBlank()) {
        Path candidate = Path.of(home, "bin", exe);
        if (Files.isRegularFile(candidate)) {
          return Optional.of(candidate);
        }
      }
    }
    String path = System.getenv("PATH");
    if (path != null) {
      for (String dir : path.split(File.pathSeparator)) {
        Path candidate = Path.of(dir, exe);
        if (Files.isRegularFile(candidate)) {
          return Optional.of(candidate);
        }
      }
    }
    return Optional.empty();
  }

  /** Builds the executable JAR using the current process's classpath. */
  public static Path buildJar(String source, String mode, int port, Path outJar) throws IOException {
    List<String> classpath =
        new ArrayList<>(List.of(System.getProperty("java.class.path").split(File.pathSeparator)));
    return buildJar(classpath, source, mode, port, outJar);
  }

  /**
   * Merges every classpath entry into a single executable jar, embeds the Elm {@code source}, the run
   * {@code mode} ("script"/"server") and {@code port}, and sets {@link #LAUNCHER} as the entry point.
   */
  public static Path buildJar(
      List<String> classpath, String source, String mode, int port, Path outJar)
      throws IOException {
    Manifest mf = new Manifest();
    mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    mf.getMainAttributes().put(Attributes.Name.MAIN_CLASS, LAUNCHER);
    // Truffle (the interpreter runtime) ships as a multi-release jar and refuses to start unless the
    // bundled manifest keeps `Multi-Release: true`. Propagate it from any source jar that declares it.
    if (anyMultiRelease(classpath)) {
      mf.getMainAttributes().put(new Attributes.Name("Multi-Release"), "true");
    }
    Files.createDirectories(outJar.toAbsolutePath().getParent());
    Set<String> seen = new HashSet<>();
    seen.add("META-INF/MANIFEST.MF");
    try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(outJar), mf)) {
      writeEntry(jos, seen, "META-INF/elm/app.elm", source.getBytes(StandardCharsets.UTF_8));
      writeEntry(jos, seen, "META-INF/elm/mode", mode.getBytes(StandardCharsets.UTF_8));
      writeEntry(
          jos, seen, "META-INF/elm/port", Integer.toString(port).getBytes(StandardCharsets.UTF_8));
      for (String cp : classpath) {
        Path entry = Path.of(cp);
        if (Files.isDirectory(entry)) {
          mergeDir(jos, seen, entry);
        } else if (Files.isRegularFile(entry)) {
          mergeJar(jos, seen, entry);
        }
      }
    }
    return outJar;
  }

  /** Builds a tiny jar holding only the embedded app resources, for use on a native-image classpath
   * alongside the real (modular) dependency jars — a flattened uber jar can't be native-imaged
   * because Truffle's polyglot module must stay on the module path with its descriptor intact. */
  public static Path buildEmbedJar(String source, String mode, int port, Path outJar)
      throws IOException {
    Files.createDirectories(outJar.toAbsolutePath().getParent());
    Set<String> seen = new HashSet<>();
    try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(outJar))) {
      writeEntry(jos, seen, "META-INF/elm/app.elm", source.getBytes(StandardCharsets.UTF_8));
      writeEntry(jos, seen, "META-INF/elm/mode", mode.getBytes(StandardCharsets.UTF_8));
      writeEntry(
          jos, seen, "META-INF/elm/port", Integer.toString(port).getBytes(StandardCharsets.UTF_8));
    }
    return outJar;
  }

  /** True when the classpath is a single jar (e.g. the shaded `elm.jar`) — native-image needs the
   * individual modular dependency jars, so a single uber jar can't be compiled natively. */
  public static boolean classpathIsSingleJar(List<String> classpath) {
    return classpath.size() == 1 && classpath.get(0).endsWith(".jar");
  }

  /**
   * Compiles a native binary by running {@code native-image} against the live classpath (the real,
   * still-modular dependency jars) plus the small {@code embedJar}, with {@link #LAUNCHER} as the
   * entry point. Returns the process exit code.
   */
  public static int compileNative(
      Path nativeImage, List<String> classpath, Path embedJar, Path outBinary)
      throws IOException, InterruptedException {
    String cp =
        embedJar.toAbsolutePath()
            + File.pathSeparator
            + String.join(File.pathSeparator, classpath);
    List<String> cmd =
        List.of(
            nativeImage.toString(),
            "--no-fallback",
            "-O2",
            "-H:+ReportExceptionStackTraces",
            "-cp",
            cp,
            LAUNCHER,
            outBinary.toAbsolutePath().toString());
    Process p = new ProcessBuilder(cmd).inheritIO().start();
    return p.waitFor();
  }

  /** The current process's classpath entries. */
  public static List<String> currentClasspath() {
    return new ArrayList<>(List.of(System.getProperty("java.class.path").split(File.pathSeparator)));
  }

  /** True if any jar on the classpath declares {@code Multi-Release: true} in its manifest. */
  private static boolean anyMultiRelease(List<String> classpath) throws IOException {
    for (String cp : classpath) {
      Path entry = Path.of(cp);
      if (Files.isRegularFile(entry)) {
        try (JarInputStream jis = new JarInputStream(Files.newInputStream(entry))) {
          Manifest m = jis.getManifest();
          if (m != null
              && "true".equalsIgnoreCase(
                  String.valueOf(m.getMainAttributes().getValue("Multi-Release")))) {
            return true;
          }
        }
      }
    }
    return false;
  }

  // --- jar assembly helpers ---------------------------------------------------------------------

  private static void writeEntry(JarOutputStream jos, Set<String> seen, String name, byte[] data)
      throws IOException {
    if (!seen.add(name)) {
      return;
    }
    jos.putNextEntry(new JarEntry(name));
    jos.write(data);
    jos.closeEntry();
  }

  private static void mergeDir(JarOutputStream jos, Set<String> seen, Path root) throws IOException {
    try (var paths = Files.walk(root)) {
      for (Path p : paths.filter(Files::isRegularFile).toList()) {
        String name = root.relativize(p).toString().replace('\\', '/');
        if (skip(name)) {
          continue;
        }
        writeEntry(jos, seen, name, Files.readAllBytes(p));
      }
    }
  }

  private static void mergeJar(JarOutputStream jos, Set<String> seen, Path jar) throws IOException {
    try (JarInputStream jis = new JarInputStream(Files.newInputStream(jar))) {
      JarEntry e;
      while ((e = jis.getNextJarEntry()) != null) {
        if (e.isDirectory() || skip(e.getName())) {
          continue;
        }
        writeEntry(jos, seen, e.getName(), jis.readAllBytes());
      }
    }
  }

  /** Skips manifests, module descriptors and jar signatures, which don't merge into a fat jar. */
  private static boolean skip(String name) {
    String upper = name.toUpperCase();
    return name.equals("module-info.class")
        || name.endsWith("/module-info.class")
        || upper.equals("META-INF/MANIFEST.MF")
        || upper.endsWith(".SF")
        || upper.endsWith(".RSA")
        || upper.endsWith(".DSA")
        || (upper.startsWith("META-INF/") && upper.endsWith(".EC"));
  }
}
