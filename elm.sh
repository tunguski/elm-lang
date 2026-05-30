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

# Recompile if the entry point hasn't been built yet.
if [ ! -f "$MAIN_CLASS" ]; then
  echo "elm.sh: Main.class missing — compiling…" >&2
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

exec java -cp "target/classes${SEP}$(cat "$CP_FILE")" pl.matsuo.elm.Main "$@"
