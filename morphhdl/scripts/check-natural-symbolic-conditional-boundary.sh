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
python3 <<'PY'
from pathlib import Path
import re
pattern = re.compile(
    r'implicit\s+def\s+[A-Za-z_][A-Za-z0-9_]*\s*\([^)]*:\s*HdlBool\b[^)]*\)'
    r'\s*(?:\([^)]*\)\s*)*:\s*Boolean\b',
    re.S,
)
for root in (Path('frontend/src/main/scala'), Path('morphplugin/src/main/scala')):
    for path in root.rglob('*.scala'):
        if pattern.search(path.read_text()):
            raise SystemExit(f'Implicit HdlBool-to-Boolean witness conversion is forbidden: {path}')
PY
