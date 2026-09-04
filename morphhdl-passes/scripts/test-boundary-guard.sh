#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../.." && pwd)"
checker="${script_dir}/check-boundary.sh"
workflow="${repo_root}/.github/workflows/morphhdl-passes.yml"

tmp_dir="$(mktemp -d)"
trap 'rm -rf "${tmp_dir}"' EXIT

run_checker() {
  local head_ref="$1"
  local manifest="$2"
  MORPHDL_PASSES_REPO_ROOT="${repo_root}" \
  MORPHDL_PASSES_HEAD_REF="${head_ref}" \
  MORPHDL_PASSES_CHANGED_FILES_FILE="${manifest}" \
    "${checker}"
}

expect_success() {
  local description="$1"
  shift
  if ! "$@" >"${tmp_dir}/stdout" 2>"${tmp_dir}/stderr"; then
    printf 'expected success: %s\n' "${description}" >&2
    cat "${tmp_dir}/stdout" >&2 || true
    cat "${tmp_dir}/stderr" >&2 || true
    exit 1
  fi
}

expect_failure() {
  local description="$1"
  shift
  if "$@" >"${tmp_dir}/stdout" 2>"${tmp_dir}/stderr"; then
    printf 'expected failure: %s\n' "${description}" >&2
    cat "${tmp_dir}/stdout" >&2 || true
    exit 1
  fi
}

python3 - "${workflow}" <<'PY'
from pathlib import Path
import sys

workflow = Path(sys.argv[1]).read_text(encoding="utf-8")
try:
    push_scope = workflow.split("  push:\n", 1)[1].split("  workflow_dispatch:\n", 1)[0]
except IndexError as error:
    raise SystemExit("unable to locate the MorphHDL pass push trigger") from error

for required_path in ('"morphhdl-passes/**"', '".github/workflows/morphhdl-passes.yml"'):
    if required_path not in push_scope:
        raise SystemExit(f"missing required pass-workspace push path: {required_path}")
if '"morphhdl/**"' in push_scope:
    raise SystemExit("push trigger must not classify ordinary MorphHDL changes as pass-workspace changes")

required_step = """      - name: Resolve boundary source branch
        id: source
"""
if required_step not in workflow:
    raise SystemExit("workflow must resolve the merged PR source branch before enforcing the push boundary")
if "MORPHDL_PASSES_HEAD_REF: ${{ steps.source.outputs.head_ref }}" not in workflow:
    raise SystemExit("boundary enforcement must consume the resolved source branch")
PY

allowed_manifest="${tmp_dir}/allowed.txt"
printf '%s\n' \
  '.github/workflows/morphhdl-passes.yml' \
  'morphhdl-passes/build.sbt' \
  'morphhdl-passes/src/main/scala/morphhdl/passes/api/PassContracts.scala' \
  >"${allowed_manifest}"
expect_success \
  'WA increment changes remain inside the isolated workspace and its workflow' \
  run_checker agent/wa-01-isolated-pass-workspace "${allowed_manifest}"

root_manifest="${tmp_dir}/root.txt"
printf '%s\n' 'build.sbt' >"${root_manifest}"
expect_failure \
  'repository root build changes are rejected' \
  run_checker agent/wa-01-isolated-pass-workspace "${root_manifest}"

upstream_manifest="${tmp_dir}/upstream.txt"
printf '%s\n' 'core/src/main/scala/spinal/core/Phase.scala' >"${upstream_manifest}"
expect_failure \
  'upstream-owned SpinalHDL source changes are rejected' \
  run_checker agent/wa-01-isolated-pass-workspace "${upstream_manifest}"

wa08_manifest="${tmp_dir}/wa08.txt"
printf '%s\n' 'morphhdl/src/main/scala/morphhdl/MorphVerilog.scala' >"${wa08_manifest}"
if grep -Eq '^- \[[xX]\] \*\*WA-07[[:space:]]+—' \
    "${repo_root}/morphhdl-passes/morphhdl-ir-wire-assignment-passes-todo.md" && \
   grep -Eq '^- \[[xX]\] \*\*Increment 58[[:space:]]+—' \
    "${repo_root}/docs/morphhdl/parameterized-verilog-todo.md"; then
  expect_success \
    'WA-08 handoff is accepted after WA-07 and PV-58 are checked' \
    run_checker agent/wa-08-final-handoff "${wa08_manifest}"
else
  expect_failure \
    'WA-08 handoff remains blocked until WA-07 and PV-58 are checked' \
    run_checker agent/wa-08-final-handoff "${wa08_manifest}"
fi

tmp_repo="${tmp_dir}/dependency-repo"
mkdir -p \
  "${tmp_repo}/.github/workflows" \
  "${tmp_repo}/docs/morphhdl" \
  "${tmp_repo}/morphhdl-passes/scripts"
cp "${checker}" "${tmp_repo}/morphhdl-passes/scripts/check-boundary.sh"
cp "${workflow}" "${tmp_repo}/.github/workflows/morphhdl-passes.yml"
printf '%s\n' 'morphhdl/src/main/scala/morphhdl/MorphVerilog.scala' > "${tmp_repo}/changed.txt"

cat > "${tmp_repo}/morphhdl-passes/morphhdl-ir-wire-assignment-passes-todo.md" <<'ROADMAP_OPEN'
- [ ] **WA-07 — Unnamed continuous wire-expression elimination and common pass flag**
ROADMAP_OPEN
cat > "${tmp_repo}/docs/morphhdl/parameterized-verilog-todo.md" <<'PV58_ONLY'
- [x] **Increment 58 — Legacy adapter and shadow-path retirement**
PV58_ONLY
expect_failure \
  'WA-08 handoff requires completed WA-07 even when PV-58 is complete' \
  env \
    MORPHDL_PASSES_REPO_ROOT="${tmp_repo}" \
    MORPHDL_PASSES_HEAD_REF=agent/wa-08-final-handoff \
    MORPHDL_PASSES_CHANGED_FILES_FILE="${tmp_repo}/changed.txt" \
    "${tmp_repo}/morphhdl-passes/scripts/check-boundary.sh"

cat > "${tmp_repo}/morphhdl-passes/morphhdl-ir-wire-assignment-passes-todo.md" <<'ROADMAP_COMPLETE'
- [x] **WA-07 — Unnamed continuous wire-expression elimination and common pass flag**
ROADMAP_COMPLETE
cat > "${tmp_repo}/docs/morphhdl/parameterized-verilog-todo.md" <<'PV57A'
- [x] **Increment 57a — Typed native StreamFifoCC depth and CDC proof**
- [ ] **Increment 58 — Legacy adapter and shadow-path retirement**
PV57A
expect_failure \
  'WA-08 handoff is not enabled by PV-57a alone' \
  env \
    MORPHDL_PASSES_REPO_ROOT="${tmp_repo}" \
    MORPHDL_PASSES_HEAD_REF=agent/wa-08-final-handoff \
    MORPHDL_PASSES_CHANGED_FILES_FILE="${tmp_repo}/changed.txt" \
    "${tmp_repo}/morphhdl-passes/scripts/check-boundary.sh"

cat > "${tmp_repo}/docs/morphhdl/parameterized-verilog-todo.md" <<'PV58'
- [x] **Increment 57a — Typed native StreamFifoCC depth and CDC proof**
- [x] **Increment 58 — Legacy adapter and shadow-path retirement**
PV58
expect_success \
  'WA-08 handoff requires and accepts completed WA-07 and PV-58' \
  env \
    MORPHDL_PASSES_REPO_ROOT="${tmp_repo}" \
    MORPHDL_PASSES_HEAD_REF=agent/wa-08-final-handoff \
    MORPHDL_PASSES_CHANGED_FILES_FILE="${tmp_repo}/changed.txt" \
    "${tmp_repo}/morphhdl-passes/scripts/check-boundary.sh"

printf 'MorphHDL pass boundary self-tests passed.\n'
