#!/usr/bin/env bash
set -euo pipefail

fail() {
  printf 'MorphHDL pass boundary violation: %s\n' "$*" >&2
  exit 1
}

repo_root="${MORPHDL_PASSES_REPO_ROOT:-}"
if [[ -z "${repo_root}" ]]; then
  repo_root="$(git rev-parse --show-toplevel 2>/dev/null)" || \
    fail "unable to locate the repository root"
fi
cd "${repo_root}"

roadmap="morphhdl-passes/morphhdl-ir-wire-assignment-passes-todo.md"
pv_roadmap="docs/morphhdl/parameterized-verilog-todo.md"
workflow=".github/workflows/morphhdl-passes.yml"

[[ -f "${roadmap}" ]] || fail "missing ${roadmap}"

head_ref="${MORPHDL_PASSES_HEAD_REF:-${GITHUB_HEAD_REF:-${GITHUB_REF_NAME:-}}}"
if [[ -z "${head_ref}" ]]; then
  head_ref="$(git branch --show-current 2>/dev/null || true)"
fi

is_wa08=false
is_wa09=false
case "${head_ref}" in
  agent/wa-08-*|wa-08-*) is_wa08=true ;;
  agent/wa-09-*|wa-09-*) is_wa09=true ;;
esac

collect_changed_files() {
  if [[ -n "${MORPHDL_PASSES_CHANGED_FILES_FILE:-}" ]]; then
    [[ -f "${MORPHDL_PASSES_CHANGED_FILES_FILE}" ]] || \
      fail "changed-files manifest does not exist: ${MORPHDL_PASSES_CHANGED_FILES_FILE}"
    cat "${MORPHDL_PASSES_CHANGED_FILES_FILE}"
    return
  fi

  local base_sha="${MORPHDL_PASSES_BASE_SHA:-}"
  if [[ -n "${base_sha}" ]] && git cat-file -e "${base_sha}^{commit}" 2>/dev/null; then
    git diff --name-only --diff-filter=ACMRTUXB "${base_sha}...HEAD"
    return
  fi

  local before_sha="${GITHUB_EVENT_BEFORE:-}"
  if [[ -n "${before_sha}" ]] && \
     [[ ! "${before_sha}" =~ ^0+$ ]] && \
     git cat-file -e "${before_sha}^{commit}" 2>/dev/null; then
    git diff --name-only --diff-filter=ACMRTUXB "${before_sha}..HEAD"
    return
  fi

  local base_ref="${MORPHDL_PASSES_BASE_REF:-origin/parameterized-verilog}"
  if git rev-parse --verify "${base_ref}^{commit}" >/dev/null 2>&1; then
    local merge_base
    merge_base="$(git merge-base "${base_ref}" HEAD)"
    git diff --name-only --diff-filter=ACMRTUXB "${merge_base}...HEAD"
    return
  fi

  fail "unable to determine the change set; provide MORPHDL_PASSES_BASE_SHA or MORPHDL_PASSES_CHANGED_FILES_FILE"
}

wa08_dependencies_satisfied() {
  [[ -f "${pv_roadmap}" ]] || return 1
  grep -Eq '^- \[x\] \*\*WA-07[[:space:]]+—' "${roadmap}" && \
    grep -Eq '^- \[x\] \*\*WA-07a[[:space:]]+—' "${roadmap}" && \
    grep -Eq '^- \[x\] \*\*WA-07b[[:space:]]+—' "${roadmap}" && \
    grep -Eq '^- \[x\] \*\*Increment 58[[:space:]]+—' "${pv_roadmap}"
}

wa09_dependencies_satisfied() {
  [[ -f "${pv_roadmap}" ]] || return 1
  grep -Eq '^- \[x\] \*\*WA-08[[:space:]]+—' "${roadmap}" && \
    grep -Eq '^- \[x\] \*\*Increment 62[[:space:]]+—' "${pv_roadmap}"
}

# WA-09 is the one reviewed successor that must coordinate the isolated pass
# workspace with MorphHDL's native writeback and the upstream Verilog emitter.
# Admit only its enumerated cross-workspace sources, only on its branch family,
# and only after the exact outer source overlay has authenticated the full delta.
wa09_cross_workspace_path() {
  local path="$1"
  case "${path}" in
    .github/workflows/increment-60f-equivalence-closure.yml|\
    core/src/main/scala/spinal/core/internals/ComponentEmitterVerilog.scala|\
    core/src/main/scala/spinal/core/internals/VerilogEmitterExpressionInlining.scala|\
    core/src/test/scala/spinal/core/internals/VerilogEmitterExpressionInliningTests.scala|\
    morphhdl/contracts/increment-62-wa08-source-overlay.json|\
    morphhdl/contracts/increment-55-native-change-review.json|\
    morphhdl/contracts/native-source-preservation.json|\
    morphhdl/scripts/check-increment-62-wa08-source-overlay.py|\
    morphhdl/scripts/check-increment-60f-artifacts.py|\
    morphhdl/scripts/check-increment-60f-equivalence-closure.py|\
    morphhdl/scripts/test-increment-60f-inherited-source-scope.py|\
    morphhdl/scripts/check-increment-60g-source-scope.py|\
    morphhdl/scripts/check-wa07b-inherited-review.py|\
    morphhdl/scripts/check-wa08-production-artifacts.py|\
    morphhdl/scripts/test-wa07b-inherited-review.py|\
    morphhdl/src/main/scala/morphhdl/MorphWireAssignmentPasses.scala|\
    morphhdl/src/main/scala/morphhdl/examples/WireAssignmentProductionBridge.scala|\
    morphhdl/src/test/scala/morphhdl/MorphCanonicalIrHandoffTests.scala|\
    morphhdl/src/test/scala/nativeapplication/NestedUnsignedExtendedSumProductionArtifactWriter.scala|\
    morphhdl/src/test/scala/nativeapplication/WireAssignmentProductionArtifactWriter.scala|\
    morphhdl/src/test/scala/spinal/core/MorphVerilogExpressionInliningTests.scala)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

allowed_path() {
  local path="$1"
  case "${path}" in
    morphhdl-passes/*|"${workflow}")
      return 0
      ;;
    morphhdl/*|morphir/*)
      if [[ "${is_wa08}" == true ]] && wa08_dependencies_satisfied; then
        return 0
      fi
      if [[ "${is_wa09}" == true ]] && \
         [[ "${wa08_overlay_verified:-false}" == true ]] && \
         wa09_dependencies_satisfied && wa09_cross_workspace_path "${path}"; then
        return 0
      fi
      return 1
      ;;
    core/*|.github/workflows/increment-60f-equivalence-closure.yml)
      if [[ "${is_wa09}" == true ]] && \
         [[ "${wa08_overlay_verified:-false}" == true ]] && \
         wa09_dependencies_satisfied && wa09_cross_workspace_path "${path}"; then
        return 0
      fi
      return 1
      ;;
    build.sbt|build.mill|.github/workflows/increment-62-wa08-source-overlay.yml|docs/morphhdl/parameterized-verilog-todo.md)
      # Production build inputs and the compatibility gate are accepted only
      # through the exact reviewed overlay, independent of branch spelling.
      [[ "${wa08_overlay_verified:-false}" == true ]]
      return
      ;;
    *)
      return 1
      ;;
  esac
}

mapfile -t changed_files < <(collect_changed_files | sed '/^[[:space:]]*$/d' | LC_ALL=C sort -u)

wa08_overlay_verified=false
overlay="morphhdl/scripts/check-increment-62-wa08-source-overlay.py"
contract="morphhdl/contracts/increment-62-wa08-source-overlay.json"
if [[ -e "${overlay}" || -e "${contract}" ]]; then
  python3 "${overlay}"
  wa08_overlay_verified=true
fi

if [[ ${#changed_files[@]} -eq 0 ]]; then
  printf 'MorphHDL pass boundary: no changed files detected.\n'
  exit 0
fi

violations=()
for path in "${changed_files[@]}"; do
  if ! allowed_path "${path}"; then
    violations+=("${path}")
  fi
done

if [[ ${#violations[@]} -ne 0 ]]; then
  printf 'MorphHDL pass boundary rejected the following path(s):\n' >&2
  printf '  - %s\n' "${violations[@]}" >&2
  if [[ "${is_wa08}" == true ]]; then
    printf 'WA-08 MorphHDL and canonical-IR handoff paths are allowed only after WA-07, WA-07a, WA-07b and PV-58 are checked on the target branch.\n' >&2
  elif [[ "${is_wa09}" == true ]]; then
    printf 'WA-09 cross-workspace paths require completed WA-08/PV-62 dependencies, an exact source overlay, and the enumerated successor inventory.\n' >&2
  else
    printf 'Allowed paths are morphhdl-passes/** and %s. Cross-workspace paths require an eligible WA-08 or WA-09 branch and its exact authorization.\n' "${workflow}" >&2
  fi
  exit 1
fi

printf 'MorphHDL pass boundary accepted %d changed path(s).\n' "${#changed_files[@]}"
