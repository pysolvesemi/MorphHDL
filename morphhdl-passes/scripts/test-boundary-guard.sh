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
def validate_boundary_route(text):
    direct = "MORPHDL_PASSES_HEAD_REF: ${{ steps.source.outputs.head_ref }}"
    if "        id: audit\n" not in text:
        if direct not in text:
            raise SystemExit("boundary enforcement must consume the resolved source branch")
        return
    def step(name):
        marker = "      - name: " + name + "\n"
        if text.count(marker) != 1:
            raise SystemExit("missing or ambiguous boundary step: " + name)
        return text.split(marker, 1)[1].split("\n      - ", 1)[0]
    audit = step("Authenticate the Increment 61 integration and resolve historical static audit sources")
    enforce = step("Enforce isolated pass paths")
    # Verify the actual resolver and enforcement blocks, not matching strings
    # elsewhere in the workflow or a decorative source-variable occurrence.
    required = (
        "SOURCE_HEAD_REF: ${{ steps.source.outputs.head_ref }}",
        'audit_root="$GITHUB_WORKSPACE"',
        'audit_head_ref="$SOURCE_HEAD_REF"',
        'audit_base_sha="$SOURCE_BASE_SHA"',
        '\"$audit_root\" \"$audit_head_ref\" \"$audit_base_sha\" >> \"$GITHUB_OUTPUT\"',
        "working-directory: ${{ steps.audit.outputs.root }}",
        "MORPHDL_PASSES_BASE_SHA: ${{ steps.audit.outputs.base_sha }}",
        "MORPHDL_PASSES_HEAD_REF: ${{ steps.audit.outputs.head_ref }}",
    )
    if not all(fragment in audit for fragment in required[:5]) or not all(fragment in enforce for fragment in required[5:]):
        raise SystemExit("authenticated boundary route must preserve source input and audited outputs")
    marker = 'if [[ "$SOURCE_HEAD_REF" == agent/cdc-independent-parameter-consumers ]]; then'
    if audit.count(marker) != 1:
        raise SystemExit("missing exact PR189 source-authentication route")
    route = audit.split(marker, 1)[1].split('          elif ', 1)[0]
    checks = (
        "python3 morphhdl/scripts/check-increment-62-wa08-source-overlay.py",
        "python3 morphhdl/scripts/check-increment-61-source-review.py",
        "python3 morphhdl/scripts/check-cdc-successor-source.py --self-test",
        "python3 morphhdl/scripts/check-native-source-preservation.py",
        "python3 morphhdl-passes/scripts/validate_wire_assignment_equivalence.py --self-test",
        'git diff --exit-code "$integrated" HEAD -- morphhdl-passes',
        'git diff --exit-code "$audit_source" "$integrated" -- morphhdl-passes',
        "bash morphhdl-passes/scripts/test-boundary-guard.sh",
    )
    replay = route.find('git worktree add --detach "$audit_root" "$audit_source"')
    if replay < 0 or any(route.find(check) < 0 or route.find(check) > replay for check in checks):
        raise SystemExit("current source checks must all precede frozen pass replay")
    return required, checks

route_contract = validate_boundary_route(workflow)
if route_contract:
    fragments = route_contract[0] + route_contract[1]
    rejected = 0
    for fragment in fragments:
        mutated = workflow.replace(fragment, "REMOVED_BOUNDARY_ROUTE_EDGE", 1)
        try:
            validate_boundary_route(mutated)
        except SystemExit:
            rejected += 1
        else:
            raise SystemExit("boundary route mutation was accepted: " + fragment)
    if rejected != 16:
        raise SystemExit("boundary route mutation inventory changed")
    print("BOUNDARY_ROUTE_MUTATIONS_PASS controls=16")
def _validate_increment61_route(text):
    # The Increment 61 adapter forwards the resolved current branch unchanged
    # unless it has authenticated that exact historical publication profile.
    # Follow the complete source -> audit -> enforcement chain, not a token
    # elsewhere in the workflow which could hide a disconnected input.
    try:
        adapter = text.split("        id: audit\n", 1)[1].split(
            "      - name: Enforce isolated pass paths\n", 1)[0]
        enforcement = text.split("      - name: Enforce isolated pass paths\n", 1)[1].split(
            "      - name: Test the boundary guard\n", 1)[0]
    except IndexError as error:
        raise ValueError("missing authenticated boundary source routing") from error
    required_adapter = (
        "SOURCE_HEAD_REF: ${{ steps.source.outputs.head_ref }}",
        'audit_root="$GITHUB_WORKSPACE"',
        'audit_head_ref="$SOURCE_HEAD_REF"',
        'audit_base_sha="$SOURCE_BASE_SHA"',
        'if [[ "$SOURCE_HEAD_REF" == agent/increment-61-one-file-per-component ]]; then',
        "python3 morphhdl/scripts/check-increment-61-source-review.py\n",
        'git merge-base --is-ancestor "$integrated" HEAD',
        'git worktree add --detach "$audit_root" "$audit_source"',
        '"$audit_root" "$audit_head_ref" "$audit_base_sha" >> "$GITHUB_OUTPUT"',
    )
    required_enforcement = (
        "working-directory: ${{ steps.audit.outputs.root }}",
        "MORPHDL_PASSES_BASE_SHA: ${{ steps.audit.outputs.base_sha }}",
        "MORPHDL_PASSES_HEAD_REF: ${{ steps.audit.outputs.head_ref }}",
        "run: bash morphhdl-passes/scripts/check-boundary.sh",
    )
    for section, tokens in ((adapter, required_adapter), (enforcement, required_enforcement)):
        for token in tokens:
            if section.count(token) != 1:
                raise ValueError("missing/duplicate boundary routing: " + token)
    if adapter.index(required_adapter[5]) >= adapter.index(required_adapter[7]):
        raise ValueError("historical boundary projection precedes current source authentication")

def validate_source_routing(text):
    cdc = '          if [[ "$SOURCE_HEAD_REF" == agent/cdc-independent-parameter-consumers ]]; then\n'
    legacy = '          elif [[ "$SOURCE_HEAD_REF" == agent/increment-61-one-file-per-component ]]; then\n'
    if text.count(cdc) != 1 or text.count(legacy) != 1:
        raise ValueError("missing authenticated integration or Increment61 route")
    before, rest = text.split(cdc, 1)
    _, after = rest.split(legacy, 1)
    _validate_increment61_route(before + legacy.replace('elif', 'if', 1) + after)

try:
    validate_source_routing(workflow)
except ValueError as error:
    raise SystemExit(str(error)) from error

# Prove the replacement gate still rejects a broken input, output, working
# directory, bypassed checker, or unverified historical branch selection.
routing_mutations = (
    ("SOURCE_HEAD_REF: ${{ steps.source.outputs.head_ref }}", "SOURCE_HEAD_REF: unrelated"),
    ('audit_head_ref="$SOURCE_HEAD_REF"', 'audit_head_ref="unrelated"'),
    ("MORPHDL_PASSES_HEAD_REF: ${{ steps.audit.outputs.head_ref }}", "MORPHDL_PASSES_HEAD_REF: unrelated"),
    ("MORPHDL_PASSES_BASE_SHA: ${{ steps.audit.outputs.base_sha }}", "MORPHDL_PASSES_BASE_SHA: unrelated"),
    ("working-directory: ${{ steps.audit.outputs.root }}", "working-directory: /unrelated"),
    ("run: bash morphhdl-passes/scripts/check-boundary.sh", "run: true"),
    ('if [[ "$SOURCE_HEAD_REF" == agent/increment-61-one-file-per-component ]]; then', 'if true; then'),
)
for before, after in routing_mutations:
    if before not in workflow:
        raise SystemExit("routing mutation did not alter the actual workflow: " + before)
    try:
        validate_source_routing(workflow.replace(before, after, 1))
    except ValueError:
        continue
    raise SystemExit("boundary routing mutation was accepted: " + before)
print("Boundary source routing: resolved branch chain and 7 rejection controls PASS")
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
if [[ -f "${repo_root}/morphhdl/contracts/increment-62-wa08-source-overlay.json" ]]; then
  expect_success \
    'exact reviewed shared build inputs are accepted through the WA-08 overlay' \
    run_checker agent/wa-01-isolated-pass-workspace "${root_manifest}"
else
  expect_failure \
    'repository root build changes are rejected without a reviewed overlay' \
    run_checker agent/wa-01-isolated-pass-workspace "${root_manifest}"
fi

upstream_manifest="${tmp_dir}/upstream.txt"
printf '%s\n' 'core/src/main/scala/spinal/core/Phase.scala' >"${upstream_manifest}"
expect_failure \
  'upstream-owned SpinalHDL source changes are rejected' \
  run_checker agent/wa-01-isolated-pass-workspace "${upstream_manifest}"

wa09_manifest="${tmp_dir}/wa09.txt"
printf '%s\n' \
  '.github/workflows/increment-60f-equivalence-closure.yml' \
  '.github/workflows/increment-60g-default-signed-verilog.yml' \
  'core/src/main/scala/spinal/core/internals/ComponentEmitterVerilog.scala' \
  'core/src/main/scala/spinal/core/internals/VerilogEmitterExpressionInlining.scala' \
  'core/src/test/scala/spinal/core/internals/VerilogEmitterExpressionInliningTests.scala' \
  'morphhdl/contracts/increment-62-wa08-source-overlay.json' \
  'morphhdl/contracts/increment-55-native-change-review.json' \
  'morphhdl/contracts/native-source-preservation.json' \
  'morphhdl/scripts/check-increment-62-wa08-source-overlay.py' \
  'morphhdl/scripts/check-increment-60f-equivalence-closure.py' \
  'morphhdl/scripts/test-increment-60f-inherited-source-scope.py' \
  'morphhdl/scripts/test-increment-59h-inherited-source-scope.py' \
  'morphhdl/scripts/test-increment-59c-inherited-source-scope.py' \
  'morphhdl/scripts/test-increment-59f-source-scope.py' \
  'morphhdl/scripts/check-increment-60g-source-scope.py' \
  'morphhdl/src/main/scala/morphhdl/MorphWireAssignmentPasses.scala' \
  'morphhdl/src/main/scala/morphhdl/examples/WireAssignmentProductionBridge.scala' \
  'morphhdl/src/test/scala/morphhdl/MorphCanonicalIrHandoffTests.scala' \
  >"${wa09_manifest}"
if [[ -f "${repo_root}/morphhdl/contracts/increment-62-wa08-source-overlay.json" ]]; then
  expect_success \
    'WA-09 exact cross-workspace sources are admitted through the verified overlay' \
    run_checker agent/wa-09-named-expression-name-preference "${wa09_manifest}"
  expect_failure \
    'WA-09 cross-workspace sources are not admitted on an unrelated branch' \
    run_checker agent/wa-01-isolated-pass-workspace "${wa09_manifest}"
fi

wa09_unreviewed_manifest="${tmp_dir}/wa09-unreviewed.txt"
printf '%s\n' 'core/src/main/scala/spinal/core/Phase.scala' >"${wa09_unreviewed_manifest}"
expect_failure \
  'WA-09 does not authorize an unenumerated upstream source' \
  run_checker agent/wa-09-named-expression-name-preference "${wa09_unreviewed_manifest}"

wa10_manifest="${tmp_dir}/wa10.txt"
printf '%s\n' \
  'core/src/main/scala/spinal/core/internals/ComponentEmitterVerilog.scala' \
  'morphhdl/src/test/scala/nativeapplication/GenerateTimingExpressionExample.scala' \
  'morphhdl/contracts/wa10-source-scope.json' \
  >"${wa10_manifest}"
if [[ -f "${repo_root}/morphhdl/contracts/wa10-source-scope.json" ]]; then
  expect_success \
    'WA-10 exact general-expression scope is admitted after its predecessor and source seal' \
    run_checker agent/wa-10-general-expression-inlining "${wa10_manifest}"
  expect_failure \
    'WA-10 cross-workspace scope is not admitted on an unrelated branch' \
    run_checker agent/wa-01-isolated-pass-workspace "${wa10_manifest}"
fi
expect_failure \
  'WA-10 branch spelling does not authorize an unenumerated upstream source' \
  run_checker agent/wa-10-general-expression-inlining "${wa09_unreviewed_manifest}"

wa10_log_manifest="${tmp_dir}/wa10-log-repair.txt"
printf '%s\n' \
  'morphhdl/src/test/scala/morphhdl/NativeWireCompatibility.scala' \
  'morphhdl/src/test/scala/morphhdl/GenericExpressionAndStreamTests.scala' \
  >"${wa10_log_manifest}"
expect_success \
  'PR-186 exact diagnostic-capture repair paths require the verified source seal' \
  run_checker agent/wa-10-inherited-audit-timeout "${wa10_log_manifest}"
for unrelated_branch in agent/wa-10-general-expression-inlining agent/wa-10-inherited-audit-timeout-other agent/wa-11-symbolic-boolean-width-normalization; do
  expect_failure \
    'diagnostic-capture repair does not extend other branch authorizations' \
    run_checker "${unrelated_branch}" "${wa10_log_manifest}"
done
printf '%s\n' 'morphhdl/src/test/scala/morphhdl/UnreviewedProof.scala' >"${tmp_dir}/wa10-log-unreviewed.txt"
expect_failure \
  'PR-186 repair branch does not authorize an unenumerated test source' \
  run_checker agent/wa-10-inherited-audit-timeout "${tmp_dir}/wa10-log-unreviewed.txt"

wa11_manifest="${tmp_dir}/wa11.txt"
printf '%s\n' \
  '.github/workflows/wa11-symbolic-boolean-width.yml' \
  'core/src/main/scala/spinal/core/ElabInt.scala' \
  'frontend/src/main/scala/morphhdl/frontend/HdlBool.scala' \
  'frontend/src/main/scala/morphhdl/frontend/StructuralExpressionBridge.scala' \
  'frontend/src/main/scala/spinal/core/ExternalAnalyzedFrontendPermitIssuer.scala' \
  'frontend/src/test/scala/morphhdl/frontend/AnalyzedFrontendBooleanTests.scala' \
  'morphhdl/contracts/increment-62-wa08-source-overlay.json' \
  'morphhdl/contracts/increment-55-native-change-review.json' \
  'morphhdl/contracts/native-source-preservation.json' \
  'morphhdl/scripts/check-increment-62-wa08-source-overlay.py' \
  'morphhdl/scripts/check-increment-60f-artifacts.py' \
  'morphhdl/scripts/check-wa10-source-scope.py' \
  'morphhdl/src/test/scala/nativeapplication/BooleanWidthNormalizationArtifactWriter.scala' \
  'morphhdl/src/test/scala/nativeapplication/ReproduceBooleanWidth.scala' \
  'morphhdl/src/test/scala/spinal/core/BooleanWidthNormalizationTests.scala' \
  >"${wa11_manifest}"
if [[ -f "${repo_root}/morphhdl/contracts/increment-62-wa08-source-overlay.json" ]]; then
  expect_success \
    'WA-11 exact typed-support and frontend sources require the verified overlay' \
    run_checker agent/wa-11-symbolic-boolean-width-normalization "${wa11_manifest}"
  for unrelated_branch in agent/wa-01-isolated-pass-workspace agent/wa-09-named-expression-name-preference agent/wa-10-general-expression-inlining; do
    expect_failure \
      'WA-11 sources do not extend an unrelated branch authorization' \
      run_checker "${unrelated_branch}" "${wa11_manifest}"
  done
fi
for unreviewed_path in \
  'core/src/main/scala/spinal/core/Bits.scala' \
  'frontend/src/main/scala/morphhdl/frontend/HdlInt.scala' \
  'frontend/src/main/scala/morphhdl/frontend/Unexpected.scala'; do
  printf '%s\n' "${unreviewed_path}" >"${tmp_dir}/wa11-unreviewed.txt"
  expect_failure \
    'WA-11 does not authorize unenumerated core or frontend source' \
    run_checker agent/wa-11-symbolic-boolean-width-normalization "${tmp_dir}/wa11-unreviewed.txt"
done

wa08_manifest="${tmp_dir}/wa08.txt"
printf '%s\n' 'morphhdl/src/main/scala/morphhdl/MorphVerilog.scala' >"${wa08_manifest}"
if grep -Eq '^- \[[xX]\] \*\*WA-07[[:space:]]+—' \
    "${repo_root}/morphhdl-passes/morphhdl-ir-wire-assignment-passes-todo.md" && \
   grep -Eq '^- \[[xX]\] \*\*WA-07a[[:space:]]+—' \
    "${repo_root}/morphhdl-passes/morphhdl-ir-wire-assignment-passes-todo.md" && \
   grep -Eq '^- \[[xX]\] \*\*WA-07b[[:space:]]+—' \
    "${repo_root}/morphhdl-passes/morphhdl-ir-wire-assignment-passes-todo.md" && \
   grep -Eq '^- \[[xX]\] \*\*Increment 58[[:space:]]+—' \
    "${repo_root}/docs/morphhdl/parameterized-verilog-todo.md"; then
  expect_success \
    'WA-08 handoff is accepted after WA-07, WA-07a, WA-07b and PV-58 are checked' \
    run_checker agent/wa-08-final-handoff "${wa08_manifest}"
else
  expect_failure \
    'WA-08 handoff remains blocked until WA-07, WA-07a, WA-07b and PV-58 are checked' \
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
- [x] **WA-07a — Constants**
- [x] **WA-07b — Boolean ternaries**
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
- [x] **WA-07a — Constants**
- [x] **WA-07b — Boolean ternaries**
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
  'WA-08 handoff requires and accepts completed WA-07, WA-07a, WA-07b and PV-58' \
  env \
    MORPHDL_PASSES_REPO_ROOT="${tmp_repo}" \
    MORPHDL_PASSES_HEAD_REF=agent/wa-08-final-handoff \
    MORPHDL_PASSES_CHANGED_FILES_FILE="${tmp_repo}/changed.txt" \
    "${tmp_repo}/morphhdl-passes/scripts/check-boundary.sh"

# The newly inserted dependency is independent of both completed predecessors.
sed -i 's/\[x\] \*\*WA-07a/[ ] **WA-07a/' "${tmp_repo}/morphhdl-passes/morphhdl-ir-wire-assignment-passes-todo.md"
expect_failure \
  'WA-08 handoff rejects an unchecked WA-07a even with WA-07 and PV-58 complete' \
  env \
    MORPHDL_PASSES_REPO_ROOT="${tmp_repo}" \
    MORPHDL_PASSES_HEAD_REF=agent/wa-08-final-handoff \
    MORPHDL_PASSES_CHANGED_FILES_FILE="${tmp_repo}/changed.txt" \
    "${tmp_repo}/morphhdl-passes/scripts/check-boundary.sh"

# WA-07b independently blocks handoff even after all earlier dependencies pass.
sed -i 's/\[ \] \*\*WA-07a/[x] **WA-07a/' "${tmp_repo}/morphhdl-passes/morphhdl-ir-wire-assignment-passes-todo.md"
sed -i 's/\[x\] \*\*WA-07b/[ ] **WA-07b/' "${tmp_repo}/morphhdl-passes/morphhdl-ir-wire-assignment-passes-todo.md"
expect_failure \
  'WA-08 handoff rejects an unchecked WA-07b even with every earlier dependency complete' \
  env \
    MORPHDL_PASSES_REPO_ROOT="${tmp_repo}" \
    MORPHDL_PASSES_HEAD_REF=agent/wa-08-final-handoff \
    MORPHDL_PASSES_CHANGED_FILES_FILE="${tmp_repo}/changed.txt" \
    "${tmp_repo}/morphhdl-passes/scripts/check-boundary.sh"
sed -i '/\*\*WA-07b/d' "${tmp_repo}/morphhdl-passes/morphhdl-ir-wire-assignment-passes-todo.md"
expect_failure \
  'deleting the WA-07b entry cannot unblock WA-08' \
  env \
    MORPHDL_PASSES_REPO_ROOT="${tmp_repo}" \
    MORPHDL_PASSES_HEAD_REF=agent/wa-08-final-handoff \
    MORPHDL_PASSES_CHANGED_FILES_FILE="${tmp_repo}/changed.txt" \
    "${tmp_repo}/morphhdl-passes/scripts/check-boundary.sh"

cat > "${tmp_repo}/morphhdl-passes/morphhdl-ir-wire-assignment-passes-todo.md" <<'WA11_PREDECESSORS'
- [x] **WA-08 — Handoff**
- [x] **WA-09 — Named expressions**
WA11_PREDECESSORS
cat > "${tmp_repo}/docs/morphhdl/parameterized-verilog-todo.md" <<'WA11_PV62'
- [x] **Increment 62 — Handoff**
WA11_PV62
printf '%s\n' 'frontend/src/main/scala/morphhdl/frontend/HdlBool.scala' > "${tmp_repo}/changed.txt"
expect_failure \
  'WA-11 requires the exact overlay even when completed predecessors are present' \
  env \
    MORPHDL_PASSES_REPO_ROOT="${tmp_repo}" \
    MORPHDL_PASSES_HEAD_REF=agent/wa-11-symbolic-boolean-width-normalization \
    MORPHDL_PASSES_CHANGED_FILES_FILE="${tmp_repo}/changed.txt" \
    "${tmp_repo}/morphhdl-passes/scripts/check-boundary.sh"

printf '%s\n' '- [x] **Increment 63 — Named expressions**' >> "${tmp_repo}/docs/morphhdl/parameterized-verilog-todo.md"
expect_failure \
  'PR-186 diagnostic repair requires its source checks even with completed predecessors' \
  env \
    MORPHDL_PASSES_REPO_ROOT="${tmp_repo}" \
    MORPHDL_PASSES_HEAD_REF=agent/wa-10-inherited-audit-timeout \
    MORPHDL_PASSES_CHANGED_FILES_FILE="${wa10_log_manifest}" \
    "${tmp_repo}/morphhdl-passes/scripts/check-boundary.sh"

# PR190 is a bounded lane/when successor, never a generic waiver for WA names.
if [[ -f "${repo_root}/morphhdl/scripts/check-sequential-wire-source-review.py" ]]; then
  sequential_manifest="${tmp_dir}/sequential.txt"
  python3 "${repo_root}/morphhdl/scripts/check-sequential-wire-source-review.py" --print-paths > "${sequential_manifest}"
  expect_success 'exact sequential successor paths require the verified union' \
    run_checker agent/wa-sequential-wire-consumers "${sequential_manifest}"
  expect_failure 'similar branch spelling cannot authorize cross-workspace source' \
    run_checker agent/wa-sequential-wire-consumers-unreviewed "${sequential_manifest}"
  printf '%s\n' 'core/src/main/scala/spinal/core/Unreviewed.scala' >> "${sequential_manifest}"
  expect_failure 'sequential branch still rejects unreviewed source' \
    run_checker agent/wa-sequential-wire-consumers "${sequential_manifest}"
fi

printf 'MorphHDL pass boundary self-tests passed.\n'

# The CDC-WIRE-01 branch alone never authorizes native changes. Its exact
# current-source inventory and byte seal must pass before admitting paths.
if [[ -f "${repo_root}/morphhdl/scripts/check-cdc-wire-source-review.py" ]]; then
  cdc_wire_manifest="${tmp_dir}/cdc-wire.txt"
  printf '%s\n' \
    'core/src/main/scala/spinal/core/ParameterizedExpressionCarrier.scala' \
    'morphhdl/scripts/check-cdc-wire-source-review.py' >"${cdc_wire_manifest}"
  expect_success 'CDC-WIRE authenticated successor paths' \
    run_checker agent/wa-cdc-wire-01-fixed-point "${cdc_wire_manifest}"
  expect_failure 'CDC-WIRE lookalike branch cannot authorize native paths' \
    run_checker agent/wa-cdc-wire-01-fixed-point-unreviewed "${cdc_wire_manifest}"
  printf '%s\n' 'core/src/main/scala/spinal/core/UnreviewedCdcWire.scala' >>"${cdc_wire_manifest}"
  expect_failure 'CDC-WIRE extra native path stays outside the inventory' \
    run_checker agent/wa-cdc-wire-01-fixed-point "${cdc_wire_manifest}"
fi
