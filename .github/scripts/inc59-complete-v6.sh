#!/usr/bin/env bash
set -euo pipefail

git config --global --add safe.directory "$GITHUB_WORKSPACE"
git config user.name "MorphHDL Increment 59 automation"
git config user.email "actions@users.noreply.github.com"
git fetch --no-tags origin parameterized-verilog
if ! git merge-base --is-ancestor origin/parameterized-verilog HEAD; then
  git merge --no-edit origin/parameterized-verilog
fi
base="$(git rev-parse origin/parameterized-verilog)"

python3 .github/scripts/inc59-complete-v6.py
python3 -m py_compile \
  morphhdl/scripts/check-increment-59-blackbox-generics.py \
  morphhdl/scripts/generate-increment-59-blackbox-stubs.py \
  morphhdl/scripts/check-native-source-preservation.py \
  morphhdl/scripts/check-production-retirement.py \
  morphhdl/scripts/check-typed-layering-ir.py
python3 morphhdl/scripts/check-increment-59-blackbox-generics.py --self-test
python3 morphhdl/scripts/check-increment-59-blackbox-generics.py --repo-root .
python3 morphhdl/scripts/check-native-source-preservation.py --self-test
python3 morphhdl/scripts/check-native-source-preservation.py
python3 morphhdl/scripts/check-production-retirement.py
python3 morphhdl/scripts/check-typed-layering-ir.py
git diff --check

git add -A
if ! git diff --cached --quiet; then
  git commit -m "Repair and seal Increment 59 implementation"
fi

for scala in 2.12.18 2.13.12; do
  bash morphhdl/scripts/check-increment-59-final-gates.sh scala "$scala"
  bash morphhdl/scripts/check-increment-59-final-gates.sh strict "$scala"
  bash morphhdl/scripts/check-increment-59-final-gates.sh formal "$scala"
  bash morphhdl/scripts/check-increment-59-final-gates.sh determinism "$scala"
done

for temporary in \
  .github/workflows/increment-59-bootstrap-v6.yml \
  .github/scripts/inc59-complete-v6.py \
  .github/scripts/inc59-complete-v6.sh; do
  if [[ -e "$temporary" ]]; then
    git rm -f "$temporary"
  fi
done
git add -A
git commit -m "Seal Increment 59 verification scope"

scope=morphhdl/contracts/increment-59-source-scope.txt
actual="$(git diff --no-renames --name-only --diff-filter=ACMRTD \
  "$base"...HEAD \
  | awk '$0 != "docs/morphhdl/parameterized-verilog-todo.md"' \
  | LC_ALL=C sort -u)"
expected="$(cat "$scope")"
if [[ "$actual" != "$expected" ]]; then
  printf '%s\n' 'Final Increment 59 source scope mismatch' >&2
  printf '%s\n' '--- expected ---' "$expected" >&2
  printf '%s\n' '--- actual ---' "$actual" >&2
  exit 1
fi

python3 - <<'PY'
from pathlib import Path

path = Path('docs/morphhdl/parameterized-verilog-todo.md')
text = path.read_text(encoding='utf-8')
unchecked = '- [ ] **Increment 59 — Typed BlackBox parameter and generic binding**'
checked = '- [x] **Increment 59 — Typed BlackBox parameter and generic binding**'
if text.count(checked) == 1 and text.count(unchecked) == 0:
    raise SystemExit(0)
if text.count(unchecked) != 1 or text.count(checked) != 0:
    raise SystemExit('roadmap does not contain exactly one unchecked Increment 59 entry')
path.write_text(text.replace(unchecked, checked, 1), encoding='utf-8')
PY
if ! git diff --quiet -- docs/morphhdl/parameterized-verilog-todo.md; then
  git add docs/morphhdl/parameterized-verilog-todo.md
  git commit -m "Mark Increment 59 complete"
  test "$(git diff-tree --no-commit-id --name-only -r HEAD)" = \
    "docs/morphhdl/parameterized-verilog-todo.md"
fi

git diff --check "$base"...HEAD
git push origin HEAD:agent/inc59-final
