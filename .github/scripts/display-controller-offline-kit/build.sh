#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd "$(dirname "$0")" && pwd)
pinned=/tmp/morphhdl-pinned
kit=/tmp/offline-kit

rm -rf "$pinned" "$kit" /tmp/offline-smoke
git cat-file -e "${PINNED_MORPHDL_COMMIT}^{commit}"
git worktree add --detach "$pinned" "$PINNED_MORPHDL_COMMIT"
git -C "$pinned" submodule sync --recursive
git -C "$pinned" submodule update --init --recursive
test "$(git -C "$pinned" rev-parse HEAD)" = "$PINNED_MORPHDL_COMMIT"

missing=0
for command in sbt jar java javac iverilog vvp verilator yosys file ldd; do
  command -v "$command" >/dev/null 2>&1 || missing=1
done
if [[ "$missing" -ne 0 ]]; then
  apt-get update
  DEBIAN_FRONTEND=noninteractive apt-get install -y default-jdk iverilog verilator yosys file
fi

cd "$pinned"
sbt -batch -no-colors "++${SCALA_VERSION}" \
  "idslplugin / Compile / packageBin" \
  "morph / Compile / compile" \
  "lib / Compile / compile"
for project in idslplugin morph lib; do
  sbt -batch -no-colors "++${SCALA_VERSION}" \
    "show ${project} / Compile / fullClasspath" \
    > "/tmp/${project}-full-classpath.log"
done

mkdir -p "$kit/metadata"
python3 "$script_dir/package_classpath.py" \
  --pinned-root "$pinned" \
  --output "$kit" \
  /tmp/idslplugin-full-classpath.log \
  /tmp/morph-full-classpath.log \
  /tmp/lib-full-classpath.log

install -m 0755 "$script_dir/compile-scala.sh" "$kit/compile-scala.sh"
install -m 0755 "$script_dir/run-main.sh" "$kit/run-main.sh"
install -m 0755 "$script_dir/install-tools.sh" "$kit/install-tools.sh"
bash "$script_dir/package_tools.sh" "$kit"

printf '%s\n' "$PINNED_MORPHDL_COMMIT" > "$kit/metadata/morphhdl-commit.txt"
printf '%s\n' "$SCALA_VERSION" > "$kit/metadata/scala-version.txt"
java -version 2> "$kit/metadata/java-version.txt"
iverilog -V > "$kit/metadata/iverilog-version.txt" 2>&1
verilator --version > "$kit/metadata/verilator-version.txt"
yosys -V > "$kit/metadata/yosys-version.txt"

bash "$script_dir/self-test.sh" "$kit"
find "$kit" -type f ! -path '*/tools-rootfs/*' -print0 \
  | sort -z | xargs -0 sha256sum > "$kit/metadata/toolkit-files.sha256"
tar -C "$kit" -czf /tmp/display-controller-offline-kit.tar.gz .
sha256sum /tmp/display-controller-offline-kit.tar.gz \
  > /tmp/display-controller-offline-kit.tar.gz.sha256
