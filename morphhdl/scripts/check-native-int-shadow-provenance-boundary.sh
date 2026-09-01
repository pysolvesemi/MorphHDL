#!/usr/bin/env bash
set -euo pipefail

# Stable compatibility entry point. Increment 53g changed this boundary from
# preserving native-Int shadow provenance to proving its production absence.
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
exec python3 "$root/morphhdl/scripts/check-production-retirement.py" \
  --repo-root "$root" "$@"
