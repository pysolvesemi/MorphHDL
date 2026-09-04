#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
usage: prove-increment-59a-recursive-module.sh --artifact-dir <directory>
EOF
}

artifact_dir=''
while [[ $# -gt 0 ]]; do
  case "$1" in
    --artifact-dir)
      artifact_dir=${2:-}
      shift 2
      ;;
    *)
      usage >&2
      exit 2
      ;;
  esac
done

if [[ -z "$artifact_dir" ]]; then
  usage >&2
  exit 2
fi
artifact_dir=$(cd "$artifact_dir" && pwd)
candidate="$artifact_dir/bounded_recursive_power.v"
exponents=(0 1 2 3 5 8)

test -s "$candidate"
for exponent in "${exponents[@]}"; do
  test -s "$artifact_dir/bounded_recursive_power_concrete_n${exponent}.v"
done

for tool in iverilog vvp verilator yosys python3; do
  command -v "$tool" >/dev/null
done

python3 - "$candidate" <<'PY'
import pathlib
import re
import sys

path = pathlib.Path(sys.argv[1])
text = path.read_text(encoding="utf-8")
if len(re.findall(r"(?m)^\s*module\s+BoundedRecursivePower\b", text)) != 1:
    raise SystemExit("expected exactly one BoundedRecursivePower definition")
for token in (
    "parameter integer N = 5",
    "generate",
    "g_base",
    "g_step",
):
    if token not in text:
        raise SystemExit(f"missing recursive module token: {token}")
normalized = re.sub(r"\s+", "", text)
normalized = normalized.replace("(", "").replace(")", "")
if ".NN-1" not in normalized:
    raise SystemExit("missing exact decreasing .N(N - 1) recursive binding")
if len(re.findall(r"(?m)^\s*BoundedRecursivePower\s*#\s*\(", text)) != 1:
    raise SystemExit("expected exactly one recursive self-reference in the source module")
PY

work=$(mktemp -d /tmp/morphhdl-increment-59a.XXXXXX)
cleanup() {
  status=$?
  rm -rf -- "$work"
  return "$status"
}
trap cleanup EXIT

cat > "$work/tool_top.v" <<'VERILOG'
module RecursivePowerToolTop (
  input  wire [7:0] x,
  output wire [7:0] y0,
  output wire [7:0] y1,
  output wire [7:0] y2,
  output wire [7:0] y3,
  output wire [7:0] y5,
  output wire [7:0] y8
);
  BoundedRecursivePower #(.N(0)) power0 (.x(x), .y(y0));
  BoundedRecursivePower #(.N(1)) power1 (.x(x), .y(y1));
  BoundedRecursivePower #(.N(2)) power2 (.x(x), .y(y2));
  BoundedRecursivePower #(.N(3)) power3 (.x(x), .y(y3));
  BoundedRecursivePower #(.N(5)) power5 (.x(x), .y(y5));
  BoundedRecursivePower #(.N(8)) power8 (.x(x), .y(y8));
endmodule
VERILOG

cat > "$work/simulation.v" <<'VERILOG'
module RecursivePowerSimulation;
  reg  [7:0] x;
  wire [7:0] y0;
  wire [7:0] y1;
  wire [7:0] y2;
  wire [7:0] y3;
  wire [7:0] y5;
  wire [7:0] y8;
  integer value;

  RecursivePowerToolTop dut (
    .x(x),
    .y0(y0),
    .y1(y1),
    .y2(y2),
    .y3(y3),
    .y5(y5),
    .y8(y8)
  );

  function [7:0] pow_mod;
    input [7:0] base;
    input integer exponent;
    integer index;
    reg [7:0] accumulator;
    begin
      accumulator = 8'h01;
      for (index = 0; index < exponent; index = index + 1)
        accumulator = accumulator * base;
      pow_mod = accumulator;
    end
  endfunction

  initial begin
    x = 8'h00;
    #1;
    for (value = 0; value < 256; value = value + 1) begin
      x = value;
      #1;
      if (y0 !== pow_mod(x, 0) ||
          y1 !== pow_mod(x, 1) ||
          y2 !== pow_mod(x, 2) ||
          y3 !== pow_mod(x, 3) ||
          y5 !== pow_mod(x, 5) ||
          y8 !== pow_mod(x, 8)) begin
        $display("recursive power mismatch x=%0d y0=%0d y1=%0d y2=%0d y3=%0d y5=%0d y8=%0d",
          x, y0, y1, y2, y3, y5, y8);
        $finish(1);
      end
    end
    $display("bounded recursive power exhaustive simulation PASS");
    $finish(0);
  end
endmodule
VERILOG

iverilog -g2001 -Wall -Wimplicit \
  -s RecursivePowerSimulation \
  -o "$work/recursive_power.vvp" \
  "$candidate" "$work/tool_top.v" "$work/simulation.v"
vvp "$work/recursive_power.vvp" | tee "$artifact_dir/iverilog-simulation.log"
grep -Fq 'bounded recursive power exhaustive simulation PASS' \
  "$artifact_dir/iverilog-simulation.log"

verilator --lint-only --language 1364-2001 -Wall -Wno-fatal \
  --top-module RecursivePowerToolTop \
  "$candidate" "$work/tool_top.v" \
  >"$artifact_dir/verilator-lint.log" 2>&1

yosys -ql "$artifact_dir/yosys-synthesis.log" -p "
  read_verilog $candidate $work/tool_top.v;
  hierarchy -check -top RecursivePowerToolTop;
  proc;
  opt;
  flatten;
  opt;
  check;
  stat;
"

for exponent in "${exponents[@]}"; do
  oracle="$artifact_dir/bounded_recursive_power_concrete_n${exponent}.v"
  miter="$work/miter_n${exponent}.v"
  top="RecursivePowerMiterN${exponent}"
  cat > "$miter" <<VERILOG
module $top (
  input wire [7:0] x,
  output wire mismatch
);
  wire [7:0] candidate_y;
  wire [7:0] reference_y;
  BoundedRecursivePower #(.N($exponent)) candidate (
    .x(x),
    .y(candidate_y)
  );
  BoundedRecursivePowerConcreteN$exponent reference (
    .x(x),
    .y(reference_y)
  );
  assign mismatch = candidate_y != reference_y;
endmodule
VERILOG

  yosys -ql "$artifact_dir/yosys-formal-n${exponent}.log" -p "
    read_verilog $candidate $oracle $miter;
    hierarchy -check -top $top;
    proc;
    opt;
    flatten;
    opt;
    sat -verify -prove mismatch 0 -set-def-inputs -show x,candidate_y,reference_y;
  "
done

mutation_miter="$work/mutation_miter.v"
cat > "$mutation_miter" <<'VERILOG'
module RecursivePowerMutationMiter (
  input wire [7:0] x,
  output wire mismatch
);
  wire [7:0] candidate_y;
  wire [7:0] mutated_y;
  wire [7:0] reference_y;
  BoundedRecursivePower #(.N(5)) candidate (
    .x(x),
    .y(candidate_y)
  );
  BoundedRecursivePowerConcreteN5 reference (
    .x(x),
    .y(reference_y)
  );
  assign mutated_y = candidate_y ^ 8'h01;
  assign mismatch = mutated_y != reference_y;
endmodule
VERILOG

set +e
yosys -ql "$artifact_dir/yosys-mutation.log" -p "
  read_verilog $candidate \
    $artifact_dir/bounded_recursive_power_concrete_n5.v \
    $mutation_miter;
  hierarchy -check -top RecursivePowerMutationMiter;
  proc;
  opt;
  flatten;
  opt;
  sat -verify -prove mismatch 0 -set-def-inputs -show x,candidate_y,mutated_y,reference_y;
"
mutation_status=$?
set -e
if [[ $mutation_status -eq 0 ]]; then
  printf '%s\n' 'mutation proof unexpectedly passed' >&2
  exit 1
fi
grep -Eqi 'model found.*FAIL|proof finished.*FAIL|Called with -verify and proof did fail' \
  "$artifact_dir/yosys-mutation.log"

printf '%s\n' 'Increment 59a recursive Verilog simulation, lint, synthesis, formal and mutation gates passed'
