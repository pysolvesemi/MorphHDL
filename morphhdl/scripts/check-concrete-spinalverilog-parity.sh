#!/usr/bin/env bash
set -euo pipefail

script_dir=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(CDPATH= cd -- "$script_dir/../.." && pwd)
base_config="$repo_root/morphhdl/upstream-base.conf"
fixture_asset="$repo_root/morphhdl/compatibility-assets/ConcreteSpinalVerilogParityFixture.scala"
fixture_relative="tester/src/test/scala/morphhdl/compatibility/ConcreteSpinalVerilogParityFixture.scala"
fixture_main="morphhdl.compatibility.ConcreteSpinalVerilogParityFixture"

scala_lane=""
current_ref="HEAD"
keep_temp=0
binary_linkage=1

usage() {
  cat <<'USAGE'
Usage: check-concrete-spinalverilog-parity.sh --scala VERSION [options]

Compile the same concrete SpinalHDL client against the selected upstream
baseline and the current MorphHDL revision, generate ordinary SpinalVerilog,
and byte-compare the complete output inventories. Public StreamFifoCC
typed-literal companion and Stream-helper entry points on the current runtime
are compared with legacy Int construction of the identical topology on the
upstream runtime. Both modes retain the public legacy Int constructor oracle.

Options:
  --scala VERSION       Scala lane to test (for example 2.12.18 or 2.13.12)
  --scala-lane VERSION  Alias for --scala
  --current-ref REF     Current MorphHDL commit to test (default: HEAD)
  --skip-binary-linkage Skip reuse of the baseline-compiled client binary
  --keep-temp           Retain temporary worktrees and outputs for diagnosis
  -h, --help            Show this help

SBT selection:
  Set SBT_LAUNCH_JAR to run `java -jar` with a specific launcher. Otherwise
  `sbt` must be available on PATH.
USAGE
}

fail() {
  printf 'concrete-spinalverilog-parity: %s\n' "$*" >&2
  exit 1
}

while (($#)); do
  case "$1" in
    --scala|--scala-lane)
      (($# >= 2)) || fail "$1 requires a version"
      scala_lane=$2
      shift 2
      ;;
    --current-ref)
      (($# >= 2)) || fail "$1 requires a git revision"
      current_ref=$2
      shift 2
      ;;
    --skip-binary-linkage)
      binary_linkage=0
      shift
      ;;
    --keep-temp)
      keep_temp=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      fail "unknown argument: $1"
      ;;
  esac
done

[[ -n "$scala_lane" ]] || fail "a Scala lane is required; pass --scala VERSION"
[[ "$scala_lane" =~ ^2\.(12|13)\.[0-9]+$ ]] || fail "unsupported Scala lane syntax: $scala_lane"
[[ -f "$base_config" ]] || fail "missing upstream base configuration: $base_config"
[[ -f "$fixture_asset" ]] || fail "missing compatibility fixture: $fixture_asset"

read_config_value() {
  local key=$1
  awk -F= -v wanted="$key" '
    $1 == wanted {
      value = substr($0, index($0, "=") + 1)
      sub(/^[[:space:]]+/, "", value)
      sub(/[[:space:]]+$/, "", value)
      print value
      found = 1
      exit
    }
    END { if (!found) exit 1 }
  ' "$base_config"
}

upstream_commit=$(read_config_value UPSTREAM_COMMIT) || fail "UPSTREAM_COMMIT is absent from $base_config"
[[ "$upstream_commit" =~ ^[0-9a-f]{40}$ ]] || fail "UPSTREAM_COMMIT is not a full lowercase SHA-1: $upstream_commit"
git -C "$repo_root" cat-file -e "$upstream_commit^{commit}" 2>/dev/null ||
  fail "selected upstream commit is unavailable locally: $upstream_commit"

current_commit=$(git -C "$repo_root" rev-parse --verify "$current_ref^{commit}") ||
  fail "current revision is not a commit: $current_ref"

lane_is_declared() {
  local tree=$1
  grep -Eq "\"${scala_lane//./\\.}\"" "$tree/project/version.conf"
}

if [[ -n "${SBT_LAUNCH_JAR:-}" ]]; then
  [[ -f "$SBT_LAUNCH_JAR" ]] || fail "SBT_LAUNCH_JAR does not exist: $SBT_LAUNCH_JAR"
  sbt_runner=(
    java
    -Dsbt.server.forcestart=true
    -Dsbt.supershell=false
    -Dsbt.log.noformat=true
    -jar "$SBT_LAUNCH_JAR"
  )
  sbt_options=()
elif command -v sbt >/dev/null 2>&1; then
  sbt_runner=(sbt)
  sbt_options=(-batch --error)
else
  fail "sbt is unavailable; install it or set SBT_LAUNCH_JAR"
fi

temp_parent=${TMPDIR:-/tmp}
temp_root=$(mktemp -d "$temp_parent/morphhdl-concrete-parity.XXXXXXXX")
baseline_tree="$temp_root/upstream"
current_tree="$temp_root/current"
baseline_output="$temp_root/output-upstream"
current_output="$temp_root/output-current"
binary_output="$temp_root/output-baseline-client-current-runtime"
binary_classes="$temp_root/baseline-client-classes"
baseline_added=0
current_added=0

cleanup() {
  local status=$?
  trap - EXIT INT TERM
  if ((keep_temp)); then
    printf 'concrete-spinalverilog-parity: retained diagnostics at %s\n' "$temp_root" >&2
    exit "$status"
  fi
  if ((current_added)); then
    git -C "$repo_root" worktree remove --force "$current_tree" >/dev/null 2>&1 || true
  fi
  if ((baseline_added)); then
    git -C "$repo_root" worktree remove --force "$baseline_tree" >/dev/null 2>&1 || true
  fi
  case "$temp_root" in
    "$temp_parent"/morphhdl-concrete-parity.*)
      rm -rf -- "$temp_root"
      ;;
    *)
      printf 'concrete-spinalverilog-parity: refused unsafe cleanup path: %s\n' "$temp_root" >&2
      ;;
  esac
  exit "$status"
}
trap cleanup EXIT INT TERM

git -C "$repo_root" worktree add --quiet --detach "$baseline_tree" "$upstream_commit"
baseline_added=1
git -C "$repo_root" worktree add --quiet --detach "$current_tree" "$current_commit"
current_added=1

lane_is_declared "$baseline_tree" || fail "Scala $scala_lane is not declared by the upstream baseline"
lane_is_declared "$current_tree" || fail "Scala $scala_lane is not declared by the current revision"

install_fixture() {
  local tree=$1
  local destination="$tree/$fixture_relative"
  mkdir -p -- "$(dirname -- "$destination")"
  cp -- "$fixture_asset" "$destination"
  cmp --silent "$fixture_asset" "$destination" || fail "fixture copy differs in $tree"
}

install_fixture "$baseline_tree"
install_fixture "$current_tree"
cmp --silent "$baseline_tree/$fixture_relative" "$current_tree/$fixture_relative" ||
  fail "baseline and current fixture sources are not byte-identical"

run_fixture() {
  local tree=$1
  local output=$2
  local label=$3
  local streamfifocc_mode=$4
  mkdir -p -- "$output"
  printf 'concrete-spinalverilog-parity: compiling and running %s on Scala %s\n' "$label" "$scala_lane"
  (
    cd "$tree"
    # Info.scala otherwise records the worktree commit in every generated RTL
    # header.  Stop its git discovery below this clean worktree so both builds
    # use the same deterministic "???" fallback.  The fixture also disables
    # the optional live-repository hash in SpinalConfig.
    GIT_CEILING_DIRECTORIES="$tree" COURSIER_PROGRESS=disable \
      "${sbt_runner[@]}" "${sbt_options[@]}" \
      "++$scala_lane" \
      "tester / Test / compile" \
      "tester / Test / runMain $fixture_main $output $streamfifocc_mode"
  )
}

run_fixture "$baseline_tree" "$baseline_output" "upstream $upstream_commit" legacy
run_fixture "$current_tree" "$current_output" "current $current_commit" typed

assert_expected_inventory() {
  local output=$1
  local expected
  for expected in \
    primitive-process \
    structure-hierarchy \
    memory \
    counter-stream-flow \
    counter-variants \
    stream-width-adapter \
    stream-fifo-depth-0 \
    stream-fifo-depth-1 \
    stream-fifo-depth-3 \
    stream-fifo-depth-5 \
    stream-fifo-depth-8 \
    stream-fifo-fmax-depth-5 \
    stream-fifo-async-ram-bypass-depth-5 \
    stream-fifo-async-vec-bypass-depth-5
  do
    [[ -d "$output/$expected" ]] || fail "missing generated fixture family: $expected"
    find "$output/$expected" -type f -name '*.v' -print -quit | grep -q . ||
      fail "fixture family generated no Verilog: $expected"
  done
  local depth
  local reset_mode
  local entry_mode
  # The entry mode names the switchable public companion/helper surface. Both
  # inventories also contain the same public legacy Int constructor oracle.
  for depth in 2 4 8 32; do
    for reset_mode in separate buffered; do
      for entry_mode in legacy typed; do
        expected="stream-fifocc-${entry_mode}-depth-${depth}-reset-${reset_mode}"
        [[ -d "$output/$expected" ]] || fail "missing generated fixture family: $expected"
        find "$output/$expected" -type f -name '*.v' -print -quit | grep -q . ||
          fail "fixture family generated no Verilog: $expected"
      done
    done
  done
}

write_inventory() {
  local output=$1
  local inventory=$2
  (
    cd "$output"
    find . -type f -print | LC_ALL=C sort | while IFS= read -r path; do
      sha256sum -- "$path"
    done
  ) > "$inventory"
}

compare_outputs() {
  local expected=$1
  local actual=$2
  local label=$3
  local expected_inventory="$temp_root/inventory-$(basename -- "$expected").sha256"
  local actual_inventory="$temp_root/inventory-$(basename -- "$actual").sha256"
  write_inventory "$expected" "$expected_inventory"
  write_inventory "$actual" "$actual_inventory"
  if ! diff -u -- "$expected_inventory" "$actual_inventory"; then
    printf 'concrete-spinalverilog-parity: generated inventory/content mismatch: %s\n' "$label" >&2
    diff -ru -- "$expected" "$actual" || true
    return 1
  fi
}

assert_expected_inventory "$baseline_output"
assert_expected_inventory "$current_output"
compare_outputs "$baseline_output" "$current_output" "upstream source versus current source" || exit 1

if ((binary_linkage)); then
  scala_binary=${scala_lane%.*}
  baseline_test_classes="$baseline_tree/tester/target/scala-$scala_binary/test-classes"
  baseline_package="$baseline_test_classes/morphhdl/compatibility"
  [[ -d "$baseline_package" ]] || fail "baseline fixture classes were not produced: $baseline_package"

  mkdir -p -- "$binary_classes/morphhdl/compatibility"
  shopt -s nullglob
  baseline_client_class_files=("$baseline_package"/ConcreteSpinalVerilogParityClient*.class)
  baseline_fixture_class_files=("$baseline_package"/ConcreteSpinalVerilogParityFixture*.class)
  shopt -u nullglob
  ((${#baseline_client_class_files[@]} > 0)) || fail "baseline client class family is empty"
  ((${#baseline_fixture_class_files[@]} > 0)) || fail "baseline fixture class family is empty"
  cp -- \
    "${baseline_client_class_files[@]}" \
    "${baseline_fixture_class_files[@]}" \
    "$binary_classes/morphhdl/compatibility/"

  current_classpath_file="$current_tree/tester/target/streams/test/dependencyClasspath/_global/streams/export"
  [[ -s "$current_classpath_file" ]] || fail "current tester dependency classpath was not exported by SBT"
  current_classpath=$(tr -d '\r\n' < "$current_classpath_file")
  [[ -n "$current_classpath" ]] || fail "current tester dependency classpath is empty"

  mkdir -p -- "$binary_output"
  printf 'concrete-spinalverilog-parity: running baseline-compiled client against current artifacts\n'
  java -cp "$binary_classes:$current_classpath" "$fixture_main" "$binary_output" typed
  assert_expected_inventory "$binary_output"
  compare_outputs "$baseline_output" "$binary_output" "baseline binary versus current runtime" || exit 1
fi

printf 'Concrete SpinalVerilog parity passed (Scala %s, upstream %s, current %s, binary-linkage=%s)\n' \
  "$scala_lane" "$upstream_commit" "$current_commit" "$binary_linkage"
