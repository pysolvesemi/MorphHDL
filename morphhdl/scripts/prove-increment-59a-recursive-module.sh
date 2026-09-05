#!/usr/bin/env bash
# Qualification of the emitted candidate, never a separately authored RTL DUT.
set -euo pipefail
if [[ $# != 2 || $1 != --artifact-dir || ! -d $2 ]]; then
  printf '%s\n' 'usage: prove-increment-59a-recursive-module.sh --artifact-dir <directory>' >&2
  exit 2
fi
artifact_dir=$(cd "$2" && pwd)
candidate="$artifact_dir/bounded_recursive_power.v"
exponents=(0 1 2 3 4 5 6 7 8)
for tool in iverilog vvp verilator yosys python3 timeout; do command -v "$tool" >/dev/null; done
test -s "$candidate"
for n in "${exponents[@]}"; do
  test -s "$artifact_dir/bounded_recursive_power_concrete_n$n.v"
done
{
  yosys -V
  verilator --version
  iverilog -V 2>&1
} > "$artifact_dir/tool-versions.txt"

python3 - "$artifact_dir" <<'PY'
import pathlib
import re
import sys

root = pathlib.Path(sys.argv[1])
text = (root / 'bounded_recursive_power.v').read_text()
assert len(re.findall(r'(?m)^\s*module\s+BoundedRecursivePower\b', text)) == 1
assert len(re.findall(r'(?m)^\s*BoundedRecursivePower\s*#\s*\(', text)) == 1
for token in ('parameter integer N = 5', 'generate', 'g_base', 'g_step'):
    assert token in text, token
binding = re.compile(r'(?m)^(\s*\.N\s*\()(.*)(\)\s*,?\s*)$')
matches = list(binding.finditer(text))
assert len(matches) == 1, 'candidate must retain exactly one explicit N actual'
actual = re.sub(r'[\s()]', '', matches[0].group(2))
assert actual == 'N-1', actual
# A structural mutation, not just a flipped observation: bypass every recursive
# level after the first. It still parses, terminates and synthesizes, but N=5
# must disagree with the independently elaborated flat specialization.
mutated = binding.sub(lambda m: m.group(1) + '0' + m.group(3), text)
assert mutated != text
(root / 'bounded_recursive_power_mutated.v').write_text(mutated)

ports = ', '.join(f'output wire [7:0] y{n}' for n in range(9))
instances = '\n'.join(
    f'  BoundedRecursivePower #(.N({n})) power{n} (.x(x), .y(y{n}));'
    for n in range(9))
(root / 'tool_top.v').write_text(
    'module RecursivePowerToolTop(input wire [7:0] x, ' + ports + ');\n'
    + instances + '\nendmodule\n')
outputs = '\n'.join(f'  wire [7:0] y{n};' for n in range(9))
connections = ', '.join(f'.y{n}(y{n})' for n in range(9))
comparisons = ' ||\n          '.join(f'y{n} !== pow_mod(x, {n})' for n in range(9))
(root / 'simulation.v').write_text('''module RecursivePowerSimulation;
  reg [7:0] x;
  integer value;
''' + outputs + '''
  RecursivePowerToolTop dut(.x(x), ''' + connections + ''');
  function [7:0] pow_mod;
    input [7:0] base;
    input integer exponent;
    integer i;
    reg [7:0] accumulator;
    begin
      accumulator = 8'd1;
      for (i=0; i<exponent; i=i+1) accumulator = accumulator * base;
      pow_mod = accumulator;
    end
  endfunction
  initial begin
    for (value=0; value<256; value=value+1) begin
      x=value;
      #1;
      if (''' + comparisons + ''') begin
        $display("recursive power mismatch x=%0d",x);
        $finish(1);
      end
    end
    $display("bounded recursive power exhaustive simulation PASS");
    $finish(0);
  end
endmodule
''')
for n in range(9):
    (root / f'miter_n{n}.v').write_text(f'''module RecursivePowerMiterN{n}(
  input wire [7:0] x,
  output wire mismatch
);
  wire [7:0] candidate_y;
  wire [7:0] reference_y;
  BoundedRecursivePower #(.N({n})) candidate(.x(x), .y(candidate_y));
  BoundedRecursivePowerConcreteN{n} reference(.x(x), .y(reference_y));
  assign mismatch = candidate_y != reference_y;
endmodule
''')
PY

timeout 120 iverilog -g2001 -Wall -Wimplicit -s RecursivePowerSimulation \
  -o "$artifact_dir/recursive_power.vvp" \
  "$candidate" "$artifact_dir/tool_top.v" "$artifact_dir/simulation.v"
timeout 120 vvp "$artifact_dir/recursive_power.vvp" | tee "$artifact_dir/iverilog-simulation.log"
grep -Fq 'bounded recursive power exhaustive simulation PASS' "$artifact_dir/iverilog-simulation.log"

timeout 120 verilator --lint-only --language 1364-2001 -Wall -Wno-fatal \
  --top-module RecursivePowerToolTop "$candidate" "$artifact_dir/tool_top.v" \
  > "$artifact_dir/verilator-lint.log" 2>&1

timeout 180 yosys -Q -ql "$artifact_dir/yosys-synthesis.log" -p "
  read_verilog -defer -noautowire $candidate $artifact_dir/tool_top.v;
  hierarchy -simcheck -top RecursivePowerToolTop;
  synth -top RecursivePowerToolTop -flatten;
  check -assert;
  stat;
  write_json $artifact_dir/synthesized.json;
"
python3 - "$artifact_dir/synthesized.json" <<'PY'
import json
import pathlib
import sys
modules = json.loads(pathlib.Path(sys.argv[1]).read_text())['modules']
assert list(modules) == ['RecursivePowerToolTop'], modules.keys()
top = modules['RecursivePowerToolTop']
assert not top.get('attributes', {}).get('blackbox', '0').strip('0')
assert all(cell['type'].startswith('$_') for cell in top['cells'].values()), \
    'unresolved, recursive, parameterized or unmapped cells remain after synthesis'
print(f'Full synthesis PASS: one flattened module, {len(top["cells"])} mapped primitive cells')
PY

for n in "${exponents[@]}"; do
  timeout 180 yosys -Q -ql "$artifact_dir/yosys-formal-n$n.log" -p "
    read_verilog -defer -noautowire $candidate $artifact_dir/bounded_recursive_power_concrete_n$n.v $artifact_dir/miter_n$n.v;
    hierarchy -simcheck -top RecursivePowerMiterN$n;
    proc; flatten; opt; check -assert;
    sat -verify -prove mismatch 0 -set-def-inputs -show-inputs -show-outputs;
  "
  grep -Fq 'SAT proof finished - no model found: SUCCESS!' "$artifact_dir/yosys-formal-n$n.log"
  printf 'PASS: generated recursive candidate versus independent SpinalVerilog oracle N=%s\n' "$n"
done

# The mutated recursive actual must produce a genuine counterexample. Missing
# modules, malformed RTL, errors, UNKNOWN or a timeout are never accepted.
timeout 180 yosys -Q -ql "$artifact_dir/yosys-mutation-synthesis.log" -p "
  read_verilog -defer -noautowire $artifact_dir/bounded_recursive_power_mutated.v $artifact_dir/bounded_recursive_power_concrete_n5.v $artifact_dir/miter_n5.v;
  hierarchy -simcheck -top RecursivePowerMiterN5;
  synth -top RecursivePowerMiterN5 -flatten;
  check -assert;
"
set +e
timeout 180 yosys -Q -ql "$artifact_dir/yosys-mutation.log" -p "
  read_verilog -defer -noautowire $artifact_dir/bounded_recursive_power_mutated.v $artifact_dir/bounded_recursive_power_concrete_n5.v $artifact_dir/miter_n5.v;
  hierarchy -simcheck -top RecursivePowerMiterN5;
  proc; flatten; opt; check -assert;
  sat -verify -prove mismatch 0 -set-def-inputs -show-inputs -show-outputs -dump_json $artifact_dir/mutation-counterexample.json;
"
mutation_status=$?
set -e
test "$mutation_status" -eq 1
grep -Fq 'SAT proof finished - model found: FAIL!' "$artifact_dir/yosys-mutation.log"
test -s "$artifact_dir/mutation-counterexample.json"
printf '%s\n' 'Increment 59a recursive Verilog simulation, lint, full synthesis, nine formal proofs and recursive-binding mutation gates passed'
