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

is_wa07=false
case "${head_ref}" in
  agent/wa-07-*|wa-07-*) is_wa07=true ;;
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

wa07_dependencies_satisfied() {
  [[ -f "${pv_roadmap}" ]] || return 1
  grep -Eq '^- \[x\] \*\*WA-06[[:space:]]+—' "${roadmap}" && \
    grep -Eq '^- \[x\] \*\*Increment 58[[:space:]]+—' "${pv_roadmap}"
}

allowed_path() {
  local path="$1"
  case "${path}" in
    morphhdl-passes/*|"${workflow}")
      return 0
      ;;
    morphhdl/*)
      if [[ "${is_wa07}" == true ]] && wa07_dependencies_satisfied; then
        return 0
      fi
      return 1
      ;;
    *)
      return 1
      ;;
  esac
}

mapfile -t changed_files < <(collect_changed_files | sed '/^[[:space:]]*$/d' | LC_ALL=C sort -u)

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
  if [[ "${is_wa07}" == true ]]; then
    printf 'WA-07 MorphHDL-owned handoff paths are allowed only after WA-06 and PV-58 are checked on the target branch.\n' >&2
  else
    printf 'Allowed paths are morphhdl-passes/** and %s. MorphHDL-owned handoff paths are reserved for an eligible agent/wa-07-* branch.\n' "${workflow}" >&2
  fi
  exit 1
fi

printf 'MorphHDL pass boundary accepted %d changed path(s).\n' "${#changed_files[@]}"
