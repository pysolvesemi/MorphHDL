#!/usr/bin/env bash
set -euo pipefail

# Stable compatibility entry point for the retired nested shadow-replay check.
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
exec python3 "$root/morphhdl/scripts/check-production-retirement.py" \
  --repo-root "$root" "$@"
