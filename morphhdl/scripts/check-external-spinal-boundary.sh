#!/usr/bin/env bash

set -euo pipefail

baseline_commit="8c4241396cd718a36227dcd89a2e6a29d9077f11"
scala_version="${1:-2.12.18}"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(git -C "$script_dir" rev-parse --show-toplevel)"
workspace="$(mktemp -d "${TMPDIR:-/tmp}/morphhdl-external-boundary.XXXXXX")"
baseline_root="$workspace/baseline"

cleanup() {
  git -C "$repo_root" worktree remove --force "$baseline_root" >/dev/null 2>&1 || true
  rm -rf "$workspace"
}
trap cleanup EXIT

if ! git -C "$repo_root" cat-file -e "${baseline_commit}^{commit}"; then
  echo "Recorded Increment 0 baseline is missing from local history: $baseline_commit" >&2
  exit 1
fi

case "$scala_version" in
  2.12.18|2.13.12) ;;
  *)
    echo "Unsupported Scala version for external-boundary proof: $scala_version" >&2
    exit 1
    ;;
esac

git -C "$repo_root" worktree add --detach "$baseline_root" "$baseline_commit" >/dev/null
mkdir -p "$baseline_root/core/src/test/scala/morphhdl/integration"
mkdir -p "$baseline_root/core/src/test/scala/morphhdl/runtime"
cp \
  "$repo_root/morphhdl/src/main/scala/morphhdl/integration/ExternalSpinalVerilog.scala" \
  "$baseline_root/core/src/test/scala/morphhdl/integration/ExternalSpinalVerilog.scala"
cp \
  "$repo_root/morphhdl/src/test/scala/morphhdl/integration/ExternalSpinalVerilogBaselineTests.scala" \
  "$baseline_root/core/src/test/scala/morphhdl/integration/ExternalSpinalVerilogBaselineTests.scala"
cp \
  "$repo_root/morphruntime/src/main/scala/morphhdl/runtime/ParameterizedVerilogMode.scala" \
  "$baseline_root/core/src/test/scala/morphhdl/runtime/ParameterizedVerilogMode.scala"

(
  cd "$baseline_root"
  sbt -batch "++${scala_version}" \
    "core/testOnly morphhdl.integration.ExternalSpinalVerilogBaselineTests"
)
