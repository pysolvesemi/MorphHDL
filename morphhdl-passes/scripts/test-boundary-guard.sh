#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../.." && pwd)"
checker="${script_dir}/check-boundary.sh"

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

wa07_manifest="${tmp_dir}/wa07.txt"
printf '%s\n' 'morphhdl/src/main/scala/morphhdl/MorphVerilog.scala' >"${wa07_manifest}"
expect_failure \
  'WA-07 handoff is rejected while WA-06 and PV-48 are unchecked' \
  run_checker agent/wa-07-final-handoff "${wa07_manifest}"

printf 'MorphHDL pass boundary self-tests passed.\n'
