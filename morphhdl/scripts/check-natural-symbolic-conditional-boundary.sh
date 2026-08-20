#!/usr/bin/env bash
set -euo pipefail
root=$(git rev-parse --show-toplevel)
cd "${root}"
for path in "$@"; do
  case "${path}" in
    core/src/main/scala/*|lib/src/main/scala/*|idslplugin/src/main/scala/*)
      echo "Increment 48 may not modify upstream-owned native source: ${path}" >&2
      exit 1
      ;;
  esac
done
if grep -RInE 'implicit[[:space:]]+(def|val|object).*HdlBool.*Boolean|implicit[[:space:]]+conversion.*HdlBool' frontend/src/main/scala morphplugin/src/main/scala; then
  echo 'Implicit HdlBool-to-Boolean witness conversion is forbidden' >&2
  exit 1
fi
