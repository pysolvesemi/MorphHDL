#!/usr/bin/env bash
set -euo pipefail
kit=$(cd "$(dirname "$0")" && pwd)
classes=${1:?usage: run-main.sh CLASSES MAIN_CLASS [ARGS...]}
main=${2:?usage: run-main.sh CLASSES MAIN_CLASS [ARGS...]}
shift 2
mapfile -t rel_cp < "$kit/classpath.txt"
cp="$classes"
for rel in "${rel_cp[@]}"; do cp="$cp:$kit/$rel"; done
exec java -cp "$cp" "$main" "$@"
