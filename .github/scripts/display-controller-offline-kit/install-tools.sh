#!/usr/bin/env bash
set -euo pipefail
kit=$(cd "$(dirname "$0")" && pwd)
test "$(id -u)" -eq 0 || { echo 'install-tools.sh must run as root' >&2; exit 2; }
cp -a "$kit/tools-rootfs/." /
hash -r
for command in iverilog vvp verilator yosys; do command -v "$command" >/dev/null; done
