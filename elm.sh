#!/usr/bin/env bash
#
# elm.sh — a thin wrapper around the elm-lang CLI (pl.matsuo.elm.Main).
#
# Provides the same commands as the built application (run/js/eval/check/repl/lsp/format/
# project/bench/site/…) without typing the full `java -cp …` invocation. Before running it
# checks that Main.class is compiled — if not, it compiles the project first — and caches the
# dependency classpath so subsequent calls start fast (no Maven per invocation).
#
# Usage:  ./elm.sh <command> [args...]      e.g.  ./elm.sh eval "List.range 1 5"
#
set -euo pipefail

# Locate the Maven project (the dir containing pom.xml — this script's own directory).
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "$SCRIPT_DIR/pom.xml" ]; then
  PROJ="$SCRIPT_DIR"
else
  echo "elm.sh: cannot find the Maven project (pom.xml)" >&2
  exit 1
fi
cd "$PROJ"

MVNW="./mvnw"
[ -x "$MVNW" ] || MVNW="sh ./mvnw"
MAIN_CLASS="target/classes/pl/matsuo/elm/Main.class"
CP_FILE="target/elm-classpath.txt"

# Recompile if the entry point is missing OR any source is newer than it. Gating on Main.class alone
# meant an edit to the Java sources ran against stale classes, and a partial/interrupted build (Main
# compiled but a later class — e.g. an inner class like Solver$Unsolvable — missing) surfaced as a
# baffling NoClassDefFoundError at startup. The `find … -newer … -quit` stops at the first newer file,
# so this stays a fast millisecond check, not a Maven launch, when everything is up to date.
NEEDS_BUILD=
if [ ! -f "$MAIN_CLASS" ]; then
  NEEDS_BUILD="Main.class missing"
elif [ -n "$(find src/main/java -name '*.java' -newer "$MAIN_CLASS" -print -quit 2>/dev/null)" ]; then
  NEEDS_BUILD="sources changed"
fi
if [ -n "$NEEDS_BUILD" ]; then
  echo "elm.sh: $NEEDS_BUILD — compiling…" >&2
  $MVNW -q -o compile 2>/dev/null || $MVNW -q compile
fi

# Cache the dependency classpath (rebuild it if the build file is newer).
if [ ! -f "$CP_FILE" ] || [ "pom.xml" -nt "$CP_FILE" ]; then
  $MVNW -q -o dependency:build-classpath -Dmdep.outputFile="$CP_FILE" >/dev/null 2>&1 \
    || $MVNW -q dependency:build-classpath -Dmdep.outputFile="$CP_FILE" >/dev/null
fi

# Windows (Git Bash/MSYS) uses ';' as the classpath separator; Unix uses ':'.
SEP=":"
case "$(uname -s 2>/dev/null || echo)" in
  MINGW* | MSYS* | CYGWIN*) SEP=";" ;;
esac

# Silence GraalVM/Truffle's startup notices (native-access + sun.misc.Unsafe deprecation) so script
# and server output isn't buried in JVM warnings. Real errors still go to stderr. Extra flags can be
# appended via ELM_JAVA_OPTS.
JAVA_OPTS="--enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow ${ELM_JAVA_OPTS:-}"

exec java $JAVA_OPTS -cp "target/classes${SEP}$(cat "$CP_FILE")" pl.matsuo.elm.Main "$@"
