#!/usr/bin/env bash
set -eEuo pipefail

repo_root="$(git rev-parse --show-toplevel)"
cd "${repo_root}"
artifact_root="${repo_root}/morphhdl-passes/build/wa10"
generate=true
case "${1:-}" in
  '') ;;
  --artifacts)
    test "$#" -eq 2 || { echo 'usage: run-wa10-regression.sh [--artifacts DIRECTORY]' >&2; exit 2; }
    artifact_root="$(realpath "$2")"
    generate=false
    ;;
  *) echo 'usage: run-wa10-regression.sh [--artifacts DIRECTORY]' >&2; exit 2 ;;
esac
mkdir -p "${artifact_root}"

if "${generate}"; then
  "${SBT:-sbt}" -batch "++${SCALA_VERSION:-2.12.18}" \
    "morph / Test / runMain TimingExpressionArtifactWriter ${artifact_root}/timing" \
    "morph / Test / runMain morphhdl.examples.GeneralExpressionInliningArtifactWriter ${artifact_root}/general" \
    2>&1 | tee "${artifact_root}/generation.log"
fi

python3 morphhdl-passes/scripts/validate-wa10-artifacts.py "${artifact_root}"
