#!/usr/bin/env bash
# Keep 59d's strict lint within an upstream-supported signed multiplier width.
set -euo pipefail
if [ "$#" -ne 2 ]; then
  echo "usage: $0 INSTALL_PREFIX EVIDENCE_DIRECTORY" >&2
  exit 2
fi
prefix=$(realpath -m "$1")
evidence=$(realpath -m "$2")
version=5.020
commit=5c5314b39cd888f427807d626e1502cbf222c292
checksum=6c0e50774abe23c8a40b30745195ba16d91f3963894cf4944f2c7c19396606b7
url="https://codeload.github.com/verilator/verilator/tar.gz/$commit"
mkdir -p "$prefix" "$evidence"
for tool in autoconf g++ flex bison make perl help2man python3 curl tar sha256sum; do
  if ! command -v "$tool" > /dev/null; then
    echo "Missing Verilator build prerequisite: $tool (install autoconf g++ flex bison make perl help2man python3 curl)" >&2
    exit 1
  fi
done
build=$(mktemp -d "${TMPDIR:-/tmp}/increment-59d-verilator.XXXXXXXX")
cleanup() {
  status=$?
  if [ "$status" -ne 0 ]; then
    for log in configure build install wide-multiply fatal-width-warning; do
      if [ -f "$evidence/$log.log" ]; then
        echo "Verilator bootstrap diagnostic: $log" >&2
        tail -n 50 "$evidence/$log.log" >&2
      fi
    done
  fi
  rm -rf -- "$build"
  exit "$status"
}
trap cleanup EXIT
started=$(date +%s)
archive="$evidence/verilator-$version.tar.gz"
curl --fail --location --retry 5 --retry-all-errors "$url" -o "$archive"
printf '%s  %s\n' "$checksum" "$archive" | sha256sum --check
tar -xzf "$archive" -C "$build" --strip-components=1
unset VERILATOR_ROOT VERILATOR_BIN
(
  cd "$build"
  autoconf
  ./configure --prefix="$prefix"
) > "$evidence/configure.log" 2>&1
make -C "$build" -j2 > "$evidence/build.log" 2>&1
make -C "$build" install > "$evidence/install.log" 2>&1
# An upstream make-install prefix is selected by the installed wrapper and
# its compiled data path. VERILATOR_ROOT is for the separate run-in-place mode.
"$prefix/bin/verilator" --version > "$evidence/version.txt"
cat > "$evidence/wide-multiply.v" <<'VERILOG'
module wide_multiply(input wire signed [511:0] a, input wire signed [31:0] b,
                     output wire signed [543:0] product);
  assign product = a * b;
endmodule
VERILOG
"$prefix/bin/verilator" --lint-only --language 1364-2001 --top-module wide_multiply \
  "$evidence/wide-multiply.v" > "$evidence/wide-multiply.log" 2>&1
cat > "$evidence/fatal-width-warning.v" <<'VERILOG'
module fatal_width_warning(input wire [7:0] value, output wire [6:0] narrowed);
  assign narrowed = value;
endmodule
VERILOG
if "$prefix/bin/verilator" --lint-only --language 1364-2001 --top-module fatal_width_warning \
    "$evidence/fatal-width-warning.v" > "$evidence/fatal-width-warning.log" 2>&1; then
  echo 'Strict Verilator lint incorrectly accepted a width mismatch' >&2
  exit 1
fi
python3 - "$evidence" "$prefix" "$version" "$commit" "$checksum" "$url" "$started" <<'PY'
import hashlib, json, re, sys, time
from pathlib import Path
root, prefix = map(Path, sys.argv[1:3])
version, commit, checksum, url, started = sys.argv[3:]
actual = (root / 'version.txt').read_text().strip()
assert re.match(r'Verilator ' + re.escape(version) + r'\b', actual), actual
negative = (root / 'fatal-width-warning.log').read_text()
assert '%Warning-WIDTHTRUNC:' in negative and '%Error: Exiting due to' in negative, negative
record = dict(version=actual, upstream_commit=commit, source_url=url,
              source_sha256=checksum, build_jobs=2, elapsed_seconds=time.time()-int(started),
              binary_sha256=hashlib.sha256((prefix / 'bin/verilator_bin').read_bytes()).hexdigest(),
              wide_signed_544_bit_lint='PASS', width_warning_fatal='PASS',
              source_modifications='none', configure_arguments=['--prefix=' + str(prefix)])
(root / 'provenance.json').write_text(json.dumps(record, indent=2) + '\n')
print(json.dumps(record, indent=2))
PY
