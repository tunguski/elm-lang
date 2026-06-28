#!/usr/bin/env bash
#
# push-all.sh — push every sibling project under projects/ first, then the root elm-lang repo.
#
# Order matters (sequential mode):
#   * elm-editor is pushed FIRST: the other projects (the CSS theme builder, the Vega editor) vendor
#     its shell modules from a fresh clone at build time, and elm-lang's gallery CI clones it to build
#     the editor page — so it must be up to date before anything that depends on it builds.
#   * The remaining projects (elm-rts, …) follow.
#   * The root elm-lang repo is pushed LAST, and only if every project pushed cleanly, so we never
#     trigger elm-lang CI (which clones the projects) before the projects are up to date.
#
# Each repo uses its own configured `origin` + branch tracking, so a bare `git push` is enough.
#
# Flags:
#   --parallel   Push every repo (all projects AND the root) concurrently. Each repo's output is
#                captured to its own buffer and printed as one labelled block once it finishes, so the
#                logs never interleave on the terminal. This trades away the ordering guarantee above
#                (dependent CIs may briefly clone a not-yet-updated dependency) for speed — use it when
#                that ordering doesn't matter. Other arguments are forwarded to `git push`
#                (e.g. push-all.sh --parallel --force-with-lease).
#
set -uo pipefail

# Run from the repo root (this script's directory), regardless of where it's invoked from.
cd "$(dirname "${BASH_SOURCE[0]}")"

# Parse our own flags out of the argument list; everything else is forwarded to `git push`.
parallel=0
args=()
for a in "$@"; do
  case "$a" in
    --parallel) parallel=1 ;;
    *) args+=("$a") ;;
  esac
done
set -- ${args[@]+"${args[@]}"}

failed=()

# The sibling project repos (each is its own git repository under projects/), elm-editor first since
# the others vendor/clone its shell.
editor="projects/elm-editor/"
projects=("$editor")
for d in projects/*/; do
  [ "$d" = "$editor" ] || projects+=("$d")
done

if [ "$parallel" -eq 1 ]; then
  # Push every repo at once — all projects and the root ("."), no ordering. Each job's stdout+stderr
  # is redirected to its own file; we collect them in launch order after every job finishes, so each
  # repo's output prints as one clean block instead of interleaving.
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  pids=(); labels=(); logs=()
  i=0
  for d in "${projects[@]}" "."; do
    [ -d "$d/.git" ] || continue
    label="$d"; [ "$d" = "." ] && label="elm-lang (root)"
    log="$tmp/job_$i.log"
    git -C "$d" push "$@" >"$log" 2>&1 &
    pids+=("$!"); labels+=("$label"); logs+=("$log"); i=$((i + 1))
  done
  for j in "${!pids[@]}"; do
    echo "== pushing ${labels[$j]}"
    wait "${pids[$j]}" && ok=1 || ok=0
    cat "${logs[$j]}"
    if [ "$ok" -eq 0 ]; then
      echo "   !! push failed for ${labels[$j]}" >&2
      failed+=("${labels[$j]}")
    fi
  done
  if [ ${#failed[@]} -ne 0 ]; then
    echo >&2
    echo "Done with errors: ${#failed[@]} push(es) failed: ${failed[*]}" >&2
    exit 1
  fi
  exit 0
fi

# --- sequential (default): projects first (elm-editor leading), then the root if all succeeded ------

for d in "${projects[@]}"; do
  [ -d "$d/.git" ] || continue
  echo "== pushing $d"
  if ! git -C "$d" push "$@"; then
    echo "   !! push failed for $d" >&2
    failed+=("$d")
  fi
done

# The root elm-lang repo — only if every project pushed cleanly, so we never trigger elm-lang CI
# (which clones the projects) before the projects are up to date.
if [ ${#failed[@]} -ne 0 ]; then
  echo >&2
  echo "Aborting: ${#failed[@]} project push(es) failed: ${failed[*]}" >&2
  echo "Root elm-lang repo NOT pushed (its CI clones the projects)." >&2
  exit 1
fi

echo "== pushing elm-lang (root)"
git push "$@"
