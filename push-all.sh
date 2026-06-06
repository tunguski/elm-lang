#!/usr/bin/env bash
#
# push-all.sh — push every sibling project under projects/ first, then the root elm-lang repo.
#
# Order matters: elm-lang's CI clones projects/elm-rts and projects/elm-editor, so those must be
# pushed before the elm-lang push triggers a CI run. The root is pushed only if every project
# pushed successfully.
#
# Each repo uses its own configured `origin` + branch tracking, so a bare `git push` is enough.
#
set -uo pipefail

# Run from the repo root (this script's directory), regardless of where it's invoked from.
cd "$(dirname "${BASH_SOURCE[0]}")"

failed=()

# 1) The sibling project repos (each is its own git repository under projects/).
for d in projects/*/; do
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
