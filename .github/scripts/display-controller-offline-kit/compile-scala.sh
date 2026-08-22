#!/usr/bin/env bash
set -euo pipefail
kit=$(cd "$(dirname "$0")" && pwd)
source_root=${1:?usage: compile-scala.sh SOURCE_ROOT OUTPUT_CLASSES}
output_classes=${2:?usage: compile-scala.sh SOURCE_ROOT OUTPUT_CLASSES}
rm -rf "$output_classes"
mkdir -p "$output_classes"
mapfile -t rel_cp < "$kit/classpath.txt"
cp=''
for rel in "${rel_cp[@]}"; do
  if [[ -z "$cp" ]]; then cp="$kit/$rel"; else cp="$cp:$kit/$rel"; fi
done
find "$source_root" -type f -name '*.scala' -print0 | sort -z > "$output_classes/sources.list0"
python3 - "$output_classes/sources.list0" "$output_classes/sources.args" <<'PY'
import pathlib, sys
raw = pathlib.Path(sys.argv[1]).read_bytes().split(b'\0')
paths = [item.decode() for item in raw if item]
if not paths:
    raise SystemExit('No Scala sources found')
pathlib.Path(sys.argv[2]).write_text('\n'.join(paths) + '\n')
PY
java -Dscala.usejavacp=true -cp "$cp" scala.tools.nsc.Main \
  -usejavacp \
  -unchecked \
  -target:jvm-1.8 \
  -language:reflectiveCalls \
  -Xplugin:"$kit/idslplugin.jar" \
  -classpath "$cp" \
  -d "$output_classes" \
  @"$output_classes/sources.args"
