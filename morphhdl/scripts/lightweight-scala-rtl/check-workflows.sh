#!/usr/bin/env bash
# Validate the enrolled targeted/full qualification graph before dispatch.
set -euo pipefail
cd "$(dirname "$0")/../../.."
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
curl --fail --location --retry 5 --retry-all-errors \
  https://github.com/rhysd/actionlint/releases/download/v1.7.12/actionlint_1.7.12_linux_amd64.tar.gz \
  --output "$work/actionlint.tar.gz"
printf '%s  %s\n' \
  8aca8db96f1b94770f1b0d72b6dddcb1ebb8123cb3712530b08cc387b349a3d8 \
  "$work/actionlint.tar.gz" | sha256sum --check
tar -xzf "$work/actionlint.tar.gz" -C "$work" actionlint
"$work/actionlint" -version
# Shell/Python checks have separate gates; validate workflow syntax, job
# dependencies, expression contexts and matrices here.
"$work/actionlint" -shellcheck= -pyflakes= \
  .github/workflows/increment-53g-production-retirement.yml \
  .github/workflows/increment-54-typed-layering-ir.yml \
  .github/workflows/morphhdl-external-structural-process.yml \
  .github/workflows/morphhdl-typed-elaboration-values.yml \
  .github/workflows/morphhdl-external-symbolic-width.yml \
  .github/workflows/increment-59c-named-field-vectors.yml \
  .github/workflows/increment-59a-recursive-verilog-module.yml \
  .github/workflows/increment-59b-reduce-balanced-tree.yml \
  .github/workflows/increment-59f-callback-graphs.yml \
  .github/workflows/increment-59g-register-bridges.yml \
  .github/workflows/increment-59h-nested-owners.yml \
  .github/workflows/increment-60e-signedness-boundaries.yml \
  .github/workflows/increment-55-concrete-compatibility-audit.yml \
  .github/workflows/increment-56-native-typed-library-surface.yml \
  .github/workflows/morphhdl-native-source-guard.yml \
  .github/workflows/morphhdl-baseline.yml \
  .github/workflows/morphhdl-mill.yml
