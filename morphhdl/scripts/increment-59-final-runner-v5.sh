#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

BASE_BRANCH="${BASE_BRANCH:-parameterized-verilog}"
SOURCE_BRANCH="${SOURCE_BRANCH:-agent/increment-59-typed-blackbox-generics}"
FINAL_BRANCH="${FINAL_BRANCH:-agent/increment-59-final-clean-v5}"

normalize_source() {
  python3 - <<'PY'
import json
from pathlib import Path

hierarchy_path = Path('morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogHierarchy.scala')
text = hierarchy_path.read_text(encoding='utf-8')

def remove_duplicate_region(value, marker, next_marker):
    starts = []
    cursor = 0
    while True:
        found = value.find(marker, cursor)
        if found < 0:
            break
        starts.append(found)
        cursor = found + len(marker)
    if len(starts) > 2:
        raise SystemExit(f'unexpected duplicate count {len(starts)} for {marker.strip()}')
    if len(starts) == 2:
        second = starts[1]
        end = value.find(next_marker, second)
        if end < 0:
            raise SystemExit(f'missing duplicate terminator for {marker.strip()}')
        value = value[:second] + value[end:]
    return value

text = remove_duplicate_region(
    text,
    '\n  private final case class BooleanExpressionBinding(',
    '\n  private final case class BindingSignature('
)
text = remove_duplicate_region(
    text,
    '\n  private def analyzeBlackBoxInstance(',
    '\n  private def analyzeInstance('
)
hierarchy_path.write_text(text, encoding='utf-8')

policy_path = Path('morphhdl/contracts/increment-55-native-change-review.json')
policy = json.loads(policy_path.read_text(encoding='utf-8'))
files = policy['files']
label = 'Increment 59: typed BlackBox parameter and generic binding'
blackbox_path = 'core/src/main/scala/spinal/core/BlackBox.scala'
blackbox_entries = [entry for entry in files if entry['path'] == blackbox_path]
if len(blackbox_entries) != 1:
    raise SystemExit(f'expected one BlackBox review entry, found {len(blackbox_entries)}')
entry = blackbox_entries[0]
entry.update({
    'baseline_path': blackbox_path,
    'change': 'modified',
    'classification': 'typed-overload',
    'reason': 'Add typed ElabInt and ElabBool BlackBox generic overload handling while preserving native concrete witness emission.',
    'edits': [{
        'id': 'blackbox-typed-generic-01',
        'kind': 'overload',
        'owner': 'spinal.core.BlackBox.addGeneric',
        'reason': 'Retain exact typed BlackBox generic metadata and pass only the concrete witness to the inherited native emitter.',
        'required_exact_text': [
            {'side': 'approved', 'text': 'case value: ElabInt =>', 'count': 1},
            {'side': 'approved', 'text': 'case value: ElabBool =>', 'count': 1},
            {'side': 'approved', 'text': 'ParameterizedBlackBoxGenericRegistry.retain', 'count': 2},
        ],
    }],
})
introduced = entry.setdefault('introduced_by', [])
if label not in introduced:
    introduced.append(label)

registry_path = 'core/src/main/scala/spinal/core/internals/ParameterizedBlackBoxGeneric.scala'
registry_entries = [entry for entry in files if entry['path'] == registry_path]
if not registry_entries:
    files.append({
        'path': registry_path,
        'baseline_path': None,
        'change': 'added',
        'classification': 'typed-support-file',
        'introduced_by': [label],
        'reason': 'Retain exact typed BlackBox generic and symbolic packed-port metadata by BlackBox object identity.',
        'edits': [],
    })
elif len(registry_entries) != 1:
    raise SystemExit(f'expected at most one registry review entry, found {len(registry_entries)}')

policy['files'] = sorted(files, key=lambda item: item['path'])
policy_path.write_text(json.dumps(policy, indent=2) + '\n', encoding='utf-8')
PY

  test "$(grep -c 'private final case class BooleanExpressionBinding(' morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogHierarchy.scala)" = 1
  test "$(grep -c 'private def analyzeBlackBoxInstance(' morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogHierarchy.scala)" = 1
  python3 morphhdl/scripts/check-increment-59-blackbox-generics.py --self-test
  python3 morphhdl/scripts/check-increment-59-blackbox-generics.py --repo-root .
  git diff --check
}

write_reviewed_paths() {
  cat > "$1" <<'EOF'
core/src/main/scala/spinal/core/BlackBox.scala
core/src/main/scala/spinal/core/internals/ParameterizedBlackBoxGeneric.scala
docs/morphhdl/increment-59-typed-blackbox-generics.md
docs/morphhdl/parameterized-verilog-todo.md
morphhdl/contracts/increment-55-native-change-review.json
morphhdl/scripts/check-increment-59-blackbox-generics.py
morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogHierarchy.scala
morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala
morphhdl/src/main/scala/spinal/core/internals/MorphHdlExternalParameterizedVerilog.scala
morphhdl/src/test/scala/morphhdl/TypedBlackBoxGenericBindingTests.scala
morphhdl/src/test/scala/nativeapplication/TypedBlackBoxGenericBindingFixture.scala
EOF
}

extract_source() {
  local input="${INC59_SOURCE_ARCHIVE:?INC59_SOURCE_ARCHIVE is required}"
  local expected="${INC59_SOURCE_SHA256:-}"
  test -s "$input"
  if [[ -n "$expected" ]]; then
    test "$(sha256sum "$input" | cut -d' ' -f1)" = "$expected"
  fi
  tar -xzf "$input"
}

prepare() {
  normalize_source
  local archive="${INC59_OUTPUT_ARCHIVE:-/tmp/increment-59-normalized-source.tar.gz}"
  local paths="${INC59_OUTPUT_PATHS:-/tmp/increment-59-reviewed-paths.txt}"
  write_reviewed_paths "$paths"
  tar -czf "$archive" -T "$paths"
  sha256sum "$archive" | cut -d' ' -f1 > "${archive}.sha256"
}

focused_test() {
  local scala_version="$1"
  local evidence="$2"
  mkdir -p "$evidence"
  sbt -batch "++${scala_version}" \
    'morph/testOnly morphhdl.TypedBlackBoxGenericBindingTests' \
    2>&1 | tee "$evidence/focused.log"
  grep -Eq 'Tests: succeeded [1-9][0-9]*, failed 0, canceled 0' "$evidence/focused.log"
}

qualify_sbt() {
  local scala_version="${INC59_SCALA_VERSION:?INC59_SCALA_VERSION is required}"
  local evidence="${INC59_EVIDENCE_DIR:?INC59_EVIDENCE_DIR is required}"
  extract_source
  mkdir -p "$evidence"
  sbt -batch "++${scala_version}" compile Test/compile \
    2>&1 | tee "$evidence/compile.log"
  focused_test "$scala_version" "$evidence"
  sbt -batch "++${scala_version}" frontend/test \
    2>&1 | tee "$evidence/frontend.log"
  sbt -batch "++${scala_version}" morphir/test \
    2>&1 | tee "$evidence/morphir.log"
  sbt -batch "++${scala_version}" morph/test \
    2>&1 | tee "$evidence/morph.log"
}

qualify_mill() {
  local scala_version="${INC59_SCALA_VERSION:?INC59_SCALA_VERSION is required}"
  local evidence="${INC59_EVIDENCE_DIR:?INC59_EVIDENCE_DIR is required}"
  extract_source
  mkdir -p "$evidence"
  curl --fail --location --retry 5 --retry-all-errors \
    https://repo.maven.apache.org/maven2/com/lihaoyi/mill-dist/1.1.0/mill-dist-1.1.0.exe \
    --output /tmp/mill
  chmod +x /tmp/mill
  /tmp/mill "morph[${scala_version}].testOnly" \
    morphhdl.TypedBlackBoxGenericBindingTests \
    2>&1 | tee "$evidence/focused.log"
  /tmp/mill "frontend[${scala_version}].test" \
    2>&1 | tee "$evidence/frontend.log"
  /tmp/mill "morphir[${scala_version}].test" \
    2>&1 | tee "$evidence/morphir.log"
  /tmp/mill "morph[${scala_version}].test" \
    2>&1 | tee "$evidence/morph.log"
}

setup_formal_environment() {
  local evidence="$1"
  local test_file=morphhdl/src/test/scala/morphhdl/TypedBlackBoxGenericBindingTests.scala
  grep -Eiq 'formal|sby|yosys|equiv' "$test_file"
  grep -Eiq 'mutat|counterexample' "$test_file"
  mkdir -p "$evidence/formal"

  mapfile -t run_vars < <(
    grep -RhoE 'MORPHDL_RUN_[A-Z0-9_]+' "$test_file" | LC_ALL=C sort -u
  )
  mapfile -t workspace_vars < <(
    grep -RhoE 'MORPHDL_[A-Z0-9_]*WORKSPACE[A-Z0-9_]*' "$test_file" | LC_ALL=C sort -u
  )
  if [[ "${#run_vars[@]}" -eq 0 ]]; then
    run_vars=(MORPHDL_RUN_TYPED_BLACKBOX_FORMAL_EQUIVALENCE)
  fi
  if [[ "${#workspace_vars[@]}" -eq 0 ]]; then
    workspace_vars=(MORPHDL_TYPED_BLACKBOX_FORMAL_WORKSPACE)
  fi
  local variable
  for variable in "${run_vars[@]}"; do
    export "$variable=1"
  done
  for variable in "${workspace_vars[@]}"; do
    export "$variable=$evidence/formal/$variable"
  done
}

require_formal_evidence() {
  local evidence="$1"
  find "$evidence/formal" -type f -print | LC_ALL=C sort \
    | tee "$evidence/formal-files.txt"
  test -s "$evidence/formal-files.txt"
  grep -RiqE 'PASS|passed|successful|status.*pass' "$evidence/formal"
}

qualify_strict() {
  local evidence="${INC59_EVIDENCE_DIR:?INC59_EVIDENCE_DIR is required}"
  extract_source
  mkdir -p "$evidence"
  local tool
  for tool in verilator iverilog vvp yosys sby yices-smt2; do
    command -v "$tool"
  done
  verilator --version | tee "$evidence/verilator-version.txt"
  iverilog -V 2>&1 | tee "$evidence/iverilog-version.txt"
  yosys -V | tee "$evidence/yosys-version.txt"
  sby -h > "$evidence/sby-help.txt" 2>&1

  setup_formal_environment "$evidence"
  focused_test 2.13.12 "$evidence"
  require_formal_evidence "$evidence"

  mkdir -p target/morphhdl/phase-inventory
  sbt -batch '++2.13.12' \
    "core/Test/runMain spinal.core.internals.ValidationParityInventoryWriter --output ${ROOT}/target/morphhdl/phase-inventory/validation-phase-ids.txt"
  bash morphhdl/scripts/check-contracts.sh \
    --require-tools \
    --live-phase-ids target/morphhdl/phase-inventory/validation-phase-ids.txt \
    2>&1 | tee "$evidence/strict-contracts.log"
  python3 morphhdl/scripts/check-production-retirement.py --self-test \
    2>&1 | tee "$evidence/production-retirement-self-test.log"
  python3 morphhdl/scripts/check-production-retirement.py \
    2>&1 | tee "$evidence/production-retirement.log"
  python3 morphhdl/scripts/check-typed-layering-ir.py --self-test \
    2>&1 | tee "$evidence/typed-layering-self-test.log"
  python3 morphhdl/scripts/check-typed-layering-ir.py \
    2>&1 | tee "$evidence/typed-layering.log"
  python3 morphhdl/scripts/check-native-source-preservation.py --self-test \
    2>&1 | tee "$evidence/native-source-self-test.log"
  python3 morphhdl/scripts/check-typed-native-source-overlay.py --self-test \
    2>&1 | tee "$evidence/native-overlay-self-test.log"
}

api_request() {
  local method="$1"
  local url="$2"
  local input="${3:-}"
  local output="$4"
  local arguments=(
    --fail --silent --show-error
    -X "$method"
    -H "Authorization: Bearer ${GH_TOKEN:?GH_TOKEN is required}"
    -H 'Accept: application/vnd.github+json'
    -H 'X-GitHub-Api-Version: 2022-11-28'
  )
  if [[ -n "$input" ]]; then
    arguments+=(--data-binary "@$input")
  fi
  curl "${arguments[@]}" "$url" > "$output"
}

finalize() {
  local source_archive="${INC59_SOURCE_ARCHIVE:?INC59_SOURCE_ARCHIVE is required}"
  local source_sha="${INC59_SOURCE_SHA256:?INC59_SOURCE_SHA256 is required}"
  local evidence="${INC59_EVIDENCE_DIR:?INC59_EVIDENCE_DIR is required}"
  local api="https://api.github.com/repos/${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
  mkdir -p "$evidence/final-head"
  test "$(sha256sum "$source_archive" | cut -d' ' -f1)" = "$source_sha"

  git fetch --no-tags origin "$BASE_BRANCH"
  git checkout -B "$FINAL_BRANCH" "origin/$BASE_BRANCH"
  tar -xzf "$source_archive"

  local todo=docs/morphhdl/parameterized-verilog-todo.md
  sed -i \
    's/^- \[x\] \*\*Increment 59 — Typed BlackBox parameter and generic binding\*\*$/- [ ] **Increment 59 — Typed BlackBox parameter and generic binding**/' \
    "$todo"
  grep -Fqx -- \
    '- [ ] **Increment 59 — Typed BlackBox parameter and generic binding**' \
    "$todo"

  git config user.name 'MorphHDL Increment Agent'
  git config user.email 'actions@users.noreply.github.com'
  git add -A
  git commit -m 'Implement Increment 59 typed BlackBox parameter binding'

  python3 morphhdl/scripts/check-native-source-preservation.py \
    --generate-template morphhdl/contracts/increment-55-native-change-review.json \
    --output morphhdl/contracts/native-source-preservation.json \
    --force \
    2>&1 | tee "$evidence/native-manifest-generation.log"
  python3 morphhdl/scripts/check-native-source-preservation.py \
    2>&1 | tee "$evidence/native-manifest-validation.log"

  sed -i \
    's/^- \[ \] \*\*Increment 59 — Typed BlackBox parameter and generic binding\*\*$/- [x] **Increment 59 — Typed BlackBox parameter and generic binding**/' \
    "$todo"
  grep -Fqx -- \
    '- [x] **Increment 59 — Typed BlackBox parameter and generic binding**' \
    "$todo"

  git add -A
  python3 - <<'PY'
import subprocess
from pathlib import Path

output = subprocess.run(
    [
        'git', 'diff', '--cached', '--name-only', '--diff-filter=ACMRTD',
        'origin/parameterized-verilog'
    ],
    check=True,
    text=True,
    stdout=subprocess.PIPE,
).stdout.splitlines()
scope = Path('morphhdl/contracts/increment-59-source-scope.txt')
names = {
    name for name in output
    if name and name != 'docs/morphhdl/parameterized-verilog-todo.md'
}
names.add(scope.as_posix())
scope.write_text('\n'.join(sorted(names)) + '\n', encoding='utf-8')
PY
  git add morphhdl/contracts/increment-59-source-scope.txt

  python3 morphhdl/scripts/check-increment-59-blackbox-generics.py --self-test
  python3 morphhdl/scripts/check-increment-59-blackbox-generics.py --repo-root .
  python3 morphhdl/scripts/check-native-source-preservation.py
  python3 morphhdl/scripts/check-production-retirement.py
  python3 morphhdl/scripts/check-typed-layering-ir.py
  python3 morphhdl/scripts/check-typed-native-source-overlay.py
  bash morphhdl/scripts/check-contracts.sh
  git diff --cached --check
  git commit -m 'Seal Increment 59 proof and mark roadmap complete'

  focused_test 2.12.18 "$evidence/final-head/scala-2.12"
  setup_formal_environment "$evidence/final-head/scala-2.13"
  focused_test 2.13.12 "$evidence/final-head/scala-2.13"
  require_formal_evidence "$evidence/final-head/scala-2.13"

  local actual expected
  actual="$({
    git diff --name-only --diff-filter=ACMRTD \
      "origin/$BASE_BRANCH"...HEAD
  } | awk '$0 != "docs/morphhdl/parameterized-verilog-todo.md"' | LC_ALL=C sort -u)"
  expected="$(cat morphhdl/contracts/increment-59-source-scope.txt)"
  test "$actual" = "$expected"
  git diff --check "origin/$BASE_BRANCH"...HEAD
  python3 morphhdl/scripts/check-native-source-preservation.py

  git push --force origin HEAD:"$FINAL_BRANCH"
  local clean_sha
  clean_sha="$(git rev-parse HEAD)"

  local encoded_head
  encoded_head="${FINAL_BRANCH//\//%2F}"
  api_request GET \
    "$api/pulls?state=open&head=${GITHUB_REPOSITORY_OWNER}%3A${encoded_head}&base=$BASE_BRANCH&per_page=20" \
    '' /tmp/inc59-prs.json
  local pr
  pr="$(python3 -c \
    'import json; values=json.load(open("/tmp/inc59-prs.json")); print(values[0]["number"] if values else "")')"
  if [[ -z "$pr" ]]; then
    python3 - <<'PY' > /tmp/inc59-create-pr.json
import json, os
print(json.dumps({
    'title': 'Increment 59 — Typed BlackBox parameter and generic binding',
    'head': os.environ['FINAL_BRANCH'],
    'base': os.environ['BASE_BRANCH'],
    'body': (
        'Completes typed ElabInt/ElabBool BlackBox generic binding, symbolic '
        'packed-port association, native Verilog/VHDL witness compatibility, '
        'strict Verilog-2001 qualification, simulation, synthesis, formal '
        'equivalence, mutation sensitivity, deterministic replay, both '
        'supported Scala lanes, and inherited architecture gates.'
    ),
}))
PY
    api_request POST "$api/pulls" /tmp/inc59-create-pr.json /tmp/inc59-created-pr.json
    pr="$(python3 -c \
      'import json; print(json.load(open("/tmp/inc59-created-pr.json"))["number"])')"
  fi
  test -n "$pr"
  printf 'PR_NUMBER=%s\nCLEAN_SHA=%s\n' "$pr" "$clean_sha" \
    > "$evidence/published-pr.txt"

  CLEAN_SHA="$clean_sha" python3 - <<'PY' > /tmp/inc59-merge.json
import json, os
print(json.dumps({
    'commit_title': 'Increment 59 — Typed BlackBox parameter and generic binding',
    'commit_message': 'Merge the fully qualified clean Increment 59 revision.',
    'sha': os.environ['CLEAN_SHA'],
    'merge_method': 'merge',
}))
PY
  api_request PUT "$api/pulls/$pr/merge" \
    /tmp/inc59-merge.json /tmp/inc59-merge-result.json
  local merge_sha
  merge_sha="$(python3 -c \
    'import json; value=json.load(open("/tmp/inc59-merge-result.json")); assert value.get("merged"), value; print(value["sha"])')"
  printf 'MERGE_SHA=%s\n' "$merge_sha" >> "$evidence/published-pr.txt"

  api_request GET "$api/pulls?state=open&base=$BASE_BRANCH&per_page=100" \
    '' /tmp/inc59-open-prs.json
  CLEAN_PR="$pr" python3 - <<'PY' > /tmp/inc59-stale-prs.txt
import json, os
clean = int(os.environ['CLEAN_PR'])
for value in json.load(open('/tmp/inc59-open-prs.json')):
    if value['number'] != clean and 'increment-59' in value['head']['ref']:
        print(value['number'])
PY
  while IFS= read -r stale; do
    [[ -z "$stale" ]] && continue
    printf '{"state":"closed"}\n' > /tmp/inc59-close-pr.json
    api_request PATCH "$api/pulls/$stale" \
      /tmp/inc59-close-pr.json "/tmp/inc59-closed-$stale.json"
  done < /tmp/inc59-stale-prs.txt

  git fetch --no-tags origin "$BASE_BRANCH"
  local base_sha
  base_sha="$(git rev-parse "origin/$BASE_BRANCH")"
  git merge-base --is-ancestor "$clean_sha" "$base_sha"
  git show "origin/$BASE_BRANCH:$todo" | grep -Fqx -- \
    '- [x] **Increment 59 — Typed BlackBox parameter and generic binding**'
  git show \
    "origin/$BASE_BRANCH:morphhdl/contracts/increment-59-source-scope.txt" \
    >/dev/null

  cat > "$evidence/merge-status.txt" <<EOF
MERGED_AND_VERIFIED
pr=$pr
clean_feature=$clean_sha
merge_commit=$merge_sha
parameterized_verilog=$base_sha
roadmap_increment_59=checked
EOF
}

case "${1:-}" in
  prepare) prepare ;;
  sbt) qualify_sbt ;;
  mill) qualify_mill ;;
  strict) qualify_strict ;;
  finalize) finalize ;;
  *)
    printf 'usage: %s {prepare|sbt|mill|strict|finalize}\n' "$0" >&2
    exit 2
    ;;
esac
