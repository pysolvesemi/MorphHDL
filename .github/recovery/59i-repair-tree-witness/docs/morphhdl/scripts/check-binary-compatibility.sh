#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat >&2 <<'EOF'
Usage:
  check-binary-compatibility.sh --scala-version <2.12.18|2.13.12> [options]
  check-binary-compatibility.sh --self-test [--python-command <command>]

Build options:
  --baseline-ref <ref>        Baseline commit/tag (default: UPSTREAM_COMMIT)
  --baseline-manifest <path>  Upstream baseline properties file
  --current-root <path>       Current MorphHDL worktree (default: repository root)
  --sbt-command <command>     SBT executable/wrapper (default: MORPHDL_SBT_COMMAND or sbt)
  --sbt-arg <argument>        Extra launcher argument; repeatable
  --python-command <command>  Python executable (default: MORPHDL_PYTHON_COMMAND or python3)
  --keep-temp                 Preserve and report the detached baseline worktree
  --self-test                 Run the isolated JVM checker tests without building

MORPHDL_SBT_ARGS controls launcher arguments and defaults to "-batch".  Set it
to an empty string for a wrapper which does not accept launcher arguments.
EOF
}

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
default_repo_root="$(git -C "$script_dir" rev-parse --show-toplevel)"

scala_version=""
baseline_ref=""
baseline_manifest=""
current_root="$default_repo_root"
sbt_command="${MORPHDL_SBT_COMMAND:-sbt}"
python_command="${MORPHDL_PYTHON_COMMAND:-python3}"
sbt_args_text="${MORPHDL_SBT_ARGS--batch}"
sbt_args=()
extra_sbt_args=()
keep_temp=0
self_test=0

if [[ -n "$sbt_args_text" ]]; then
  # Launcher arguments are deliberately whitespace-only: use repeated
  # --sbt-arg options when an individual argument itself contains whitespace.
  read -r -a sbt_args <<<"$sbt_args_text"
fi

while (( $# > 0 )); do
  case "$1" in
    --scala-version)
      if (( $# < 2 )); then
        usage
        exit 2
      fi
      scala_version="$2"
      shift 2
      ;;
    --baseline-ref)
      if (( $# < 2 )); then
        usage
        exit 2
      fi
      baseline_ref="$2"
      shift 2
      ;;
    --baseline-manifest)
      if (( $# < 2 )); then
        usage
        exit 2
      fi
      baseline_manifest="$2"
      shift 2
      ;;
    --current-root)
      if (( $# < 2 )); then
        usage
        exit 2
      fi
      current_root="$2"
      shift 2
      ;;
    --sbt-command)
      if (( $# < 2 )); then
        usage
        exit 2
      fi
      sbt_command="$2"
      shift 2
      ;;
    --sbt-arg)
      if (( $# < 2 )); then
        usage
        exit 2
      fi
      extra_sbt_args+=("$2")
      shift 2
      ;;
    --python-command)
      if (( $# < 2 )); then
        usage
        exit 2
      fi
      python_command="$2"
      shift 2
      ;;
    --keep-temp)
      keep_temp=1
      shift
      ;;
    --self-test)
      self_test=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "BINARY_COMPAT_USAGE_ERROR: unknown argument: $1" >&2
      usage
      exit 2
      ;;
  esac
done

checker="$script_dir/check-jvm-binary-compatibility.py"
source_fixture="$default_repo_root/morphhdl/compatibility-assets/LegacySourceCompatibilityFixture.scala"
if (( self_test == 1 )); then
  if [[ -n "$scala_version" || -n "$baseline_ref" || -n "$baseline_manifest" ]]; then
    echo "BINARY_COMPAT_USAGE_ERROR: --self-test cannot be combined with build selection" >&2
    exit 2
  fi
  exec "$python_command" "$checker" --self-test
fi

case "$scala_version" in
  2.12.18|2.13.12)
    ;;
  *)
    echo "BINARY_COMPAT_UNSUPPORTED_SCALA: expected 2.12.18 or 2.13.12, got '$scala_version'" >&2
    exit 2
    ;;
esac

if [[ ! -f "$checker" ]]; then
  echo "BINARY_COMPAT_CHECKER_MISSING: $checker" >&2
  exit 2
fi
if [[ ! -f "$source_fixture" ]]; then
  echo "BINARY_COMPAT_SOURCE_FIXTURE_MISSING: $source_fixture" >&2
  exit 2
fi

current_root="$(cd "$current_root" && pwd)"
if ! git -C "$current_root" rev-parse --show-toplevel >/dev/null 2>&1; then
  echo "BINARY_COMPAT_NOT_GIT_WORKTREE: $current_root" >&2
  exit 2
fi

if [[ -z "$baseline_manifest" ]]; then
  baseline_manifest="$current_root/morphhdl/upstream-base.conf"
elif [[ "$baseline_manifest" != /* ]]; then
  baseline_manifest="$current_root/$baseline_manifest"
fi

read_manifest_value() {
  local key="$1"
  awk -F= -v key="$key" '
    /^[[:space:]]*#/ { next }
    {
      candidate = $1
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", candidate)
    }
    candidate == key {
      sub(/^[^=]*=/, "")
      gsub(/^[[:space:]]+|[[:space:]]+$/, "")
      print
      found = 1
      exit
    }
    END { if (!found) exit 1 }
  ' "$baseline_manifest"
}

if [[ -z "$baseline_ref" ]]; then
  if [[ ! -f "$baseline_manifest" ]]; then
    echo "BINARY_COMPAT_BASELINE_MANIFEST_MISSING: $baseline_manifest" >&2
    exit 2
  fi
  if ! baseline_ref="$(read_manifest_value UPSTREAM_COMMIT)"; then
    echo "BINARY_COMPAT_BASELINE_REF_MISSING: UPSTREAM_COMMIT is absent from $baseline_manifest" >&2
    exit 2
  fi
fi

if ! baseline_commit="$(git -C "$current_root" rev-parse --verify "${baseline_ref}^{commit}" 2>/dev/null)"; then
  echo "BINARY_COMPAT_BASELINE_UNAVAILABLE: cannot resolve '$baseline_ref' locally" >&2
  exit 2
fi

resolve_command() {
  local candidate="$1"
  if [[ "$candidate" == */* ]]; then
    if [[ "$candidate" != /* ]]; then
      candidate="$(cd "$(dirname "$candidate")" && pwd)/$(basename "$candidate")"
    fi
    if [[ ! -x "$candidate" ]]; then
      return 1
    fi
    printf '%s\n' "$candidate"
    return 0
  fi
  command -v "$candidate"
}

if ! sbt_command="$(resolve_command "$sbt_command")"; then
  echo "BINARY_COMPAT_SBT_UNAVAILABLE: cannot execute '$sbt_command'" >&2
  exit 2
fi
if ! python_command="$(resolve_command "$python_command")"; then
  echo "BINARY_COMPAT_PYTHON_UNAVAILABLE: cannot execute '$python_command'" >&2
  exit 2
fi

temp_root="$(mktemp -d "${TMPDIR:-/tmp}/morphhdl-binary-compat.XXXXXX")"
baseline_root="$temp_root/baseline"
source_fixture_copy="$temp_root/LegacySourceCompatibilityFixture.scala"
baseline_registered=0
cp -- "$source_fixture" "$source_fixture_copy"

cleanup() {
  local status=$?
  if (( keep_temp == 1 )); then
    echo "BINARY_COMPAT_TEMP_PRESERVED: $temp_root" >&2
    return "$status"
  fi
  if (( baseline_registered == 1 )); then
    git -C "$current_root" worktree remove --force "$baseline_root" >/dev/null 2>&1 || true
  fi
  rm -rf -- "$temp_root"
  return "$status"
}
trap cleanup EXIT

echo "BINARY_COMPAT_BASELINE: $baseline_commit"
echo "BINARY_COMPAT_SCALA: $scala_version"
git -C "$current_root" worktree add --quiet --detach "$baseline_root" "$baseline_commit"
baseline_registered=1

run_sbt_build() {
  local root="$1"
  local role="$2"
  echo "BINARY_COMPAT_BUILD: role=$role root=$root"
  (
    cd "$root"
    "$sbt_command" "${sbt_args[@]}" "${extra_sbt_args[@]}" \
      "++$scala_version" \
      "idslplugin / Compile / packageBin" \
      "core / Compile / packageBin" \
      "lib / Compile / packageBin"
  )
}

run_sbt_build "$baseline_root" baseline
run_sbt_build "$current_root" current

run_source_compatibility_compile() {
  local root="$1"
  local role="$2"
  local output_root="$temp_root/source-compatibility-$role"
  mkdir -p "$output_root/classes"
  echo "BINARY_COMPAT_SOURCE_COMPILE: role=$role fixture=$source_fixture_copy"
  (
    cd "$root"
    "$sbt_command" "${sbt_args[@]}" "${extra_sbt_args[@]}" \
      "++$scala_version" \
      "set tester / Compile / unmanagedSources := Seq(file(\"$source_fixture_copy\"))" \
      "set tester / Compile / classDirectory := file(\"$output_root/classes\")" \
      "set tester / Compile / compileAnalysisFile := file(\"$output_root/analysis.zip\")" \
      "tester / Compile / compile" \
      "tester / Compile / runMain morphhdl.compatibility.LegacySourceCompatibilityFixture"
  )
}

run_source_compatibility_compile "$baseline_root" baseline
run_source_compatibility_compile "$current_root" current

scala_binary="${scala_version%.*}"

artifact_path() {
  local root="$1"
  local module="$2"
  local artifact_dir="$root/$module/target/scala-$scala_binary"
  local candidates=()
  if [[ -d "$artifact_dir" ]]; then
    mapfile -t candidates < <(
      find "$artifact_dir" -maxdepth 1 -type f -name '*.jar' \
        ! -name '*-tests.jar' \
        ! -name '*-sources.jar' \
        ! -name '*-javadoc.jar' \
        -print | sort
    )
  fi
  if (( ${#candidates[@]} != 1 )); then
    echo "BINARY_COMPAT_ARTIFACT_AMBIGUOUS: role_root=$root module=$module candidates=${#candidates[@]}" >&2
    return 1
  fi
  printf '%s\n' "${candidates[0]}"
}

compatibility_status=0
for module in idslplugin core lib; do
  baseline_artifact="$(artifact_path "$baseline_root" "$module")"
  current_artifact="$(artifact_path "$current_root" "$module")"
  echo "BINARY_COMPAT_COMPARE: module=$module"
  if ! "$python_command" "$checker" \
    --label "$module-$scala_version" \
    --baseline "$baseline_artifact" \
    --current "$current_artifact"; then
    compatibility_status=1
  fi
done

if (( compatibility_status != 0 )); then
  echo "BINARY_COMPAT_FAILED: scala=$scala_version" >&2
  exit 1
fi

echo "BINARY_COMPAT_OK: scala=$scala_version baseline=$baseline_commit"
