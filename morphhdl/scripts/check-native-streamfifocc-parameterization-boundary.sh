#!/usr/bin/env bash
set -euo pipefail

python3 morphhdl/scripts/check-native-source-preservation.py

grep -Fq 'object StreamFifoCC' frontend/src/main/scala/morphhdl/frontend/Library.scala
grep -Fq '): NativeStreamFifoCC[T]' frontend/src/main/scala/morphhdl/frontend/Library.scala
grep -Fq 'formalComponent.parameter' frontend/src/main/scala/morphhdl/frontend/Library.scala
grep -Fq 'Set("StreamFifo", "StreamFifoCC")' \
  morphplugin/src/main/scala/morphhdl/compiler/MorphHdlNativeIntShadowExpressionComponent.scala

if grep -R --include='*.scala' -nE \
  '(^|[[:space:]])class[[:space:]]+[A-Za-z0-9_]*StreamFifoCC|extends[[:space:]]+.*StreamFifoCC' \
  frontend/src/main morphruntime/src/main morphplugin/src/main morphhdl/src/main; then
  echo 'Increment 53e must not add a MorphHDL StreamFifoCC implementation or subclass' >&2
  exit 1
fi

if [[ -n "${GITHUB_BASE_REF:-}" ]]; then
  git fetch --no-tags origin \
    "${GITHUB_BASE_REF}:refs/remotes/origin/${GITHUB_BASE_REF}"
  changed="$(git diff --name-only "origin/${GITHUB_BASE_REF}...HEAD")"
  if grep -Eq '^(core|lib|idslplugin)/src/main/' <<<"${changed}"; then
    echo 'Increment 53e must not modify upstream-owned SpinalHDL production sources' >&2
    grep -E '^(core|lib|idslplugin)/src/main/' <<<"${changed}" >&2
    exit 1
  fi
fi
