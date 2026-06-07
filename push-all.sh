#!/usr/bin/env bash
#
# push-all.sh — push every sibling project under projects/ first, then the root elm-lang repo.
#
# Order matters:
#   * elm-editor is pushed FIRST: the other projects (the CSS theme builder, the Vega editor) vendor
#     its shell modules from a fresh clone at build time, and elm-lang's gallery CI clones it to build
#     the editor page — so it must be up to date before anything that depends on it builds.
#   * The remaining projects (elm-rts, …) follow.
#   * The root elm-lang repo is pushed LAST, and only if every project pushed cleanly, so we never
#     trigger elm-lang CI (which clones the projects) before the projects are up to date.
#
# Each repo uses its own configured `origin` + branch tracking, so a bare `git push` is enough.
#
set -uo pipefail

# Run from the repo root (this script's directory), regardless of where it's invoked from.
cd "$(dirname "${BASH_SOURCE[0]}")"

failed=()

# 1) The sibling project repos (each is its own git repository under projects/), elm-editor first
#    since the others vendor/clone its shell.
projects=("projects/elm-editor/")
for d in projects/*/; do
  [ "$d" = "projects/elm-editor/" ] || projects+=("$d")
done

for d in "${projects[@]}"; do
  [ -d "$d/.git" ] || continue
  echo "== pushing $d"
  if ! git -C "$d" push "$@"; then
    echo "   !! push failed for $d" >&2
    failed+=("$d")
  fi
done

# 2) The root elm-lang repo — only if every project pushed cleanly, so we never trigger elm-lang
#    CI (which clones the projects) before the projects are up to date.
if [ ${#failed[@]} -ne 0 ]; then
  echo >&2
  echo "Aborting: ${#failed[@]} project push(es) failed: ${failed[*]}" >&2
  echo "Root elm-lang repo NOT pushed (its CI clones the projects)." >&2
  exit 1
fi

echo "== pushing elm-lang (root)"
git push "$@"
