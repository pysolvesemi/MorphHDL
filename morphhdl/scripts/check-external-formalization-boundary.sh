#!/usr/bin/env bash
set -euo pipefail

# Stable compatibility entry point. Typed ElabFormalComponent bindings remain;
# the native-Int constructor formalization path must now be absent.
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
exec python3 "$root/morphhdl/scripts/check-production-retirement.py" \
  --repo-root "$root" "$@"
