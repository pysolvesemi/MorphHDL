#!/usr/bin/env python3
"""Exercise real synthesis preparation and reject corrupted state/interfaces/closure."""
from __future__ import annotations

import argparse
import copy
import importlib.util
import json
import re
import sys
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path)
    args = parser.parse_args()
    root = args.root.resolve()
    root.mkdir(parents=True, exist_ok=True)
    evidence = root / 'evidence.json'
    evidence.unlink(missing_ok=True)
    spec = importlib.util.spec_from_file_location('widening',
        Path(__file__).with_name('check-increment-59d-widening.py'))
    widening = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(widening)
    helper = widening.SYNTHESIS
    results = []

    def reject(name, action):
        try:
            action()
        except RuntimeError as error:
            results.append(dict(control=name, result='rejected', diagnostic=str(error)))
        else:
            raise AssertionError('corrupted synthesis preparation was accepted: ' + name)

    def qualify(name, rtl):
        work = root / name
        work.mkdir(exist_ok=True)
        source = work / (name + '.v')
        source.write_text(rtl)
        result = helper.qualify(name, [source], work, 'reference', widening.command, widening.H.quoted)
        results.append(dict(control=name, result='passed', synthesis=result))
        return work, result

    zero, zero_result = qualify('Zero',
        'module Zero(input [3:0] a, output [3:0] y); assign y=a; endmodule\n')
    assert zero_result['cells'] == 0 and zero_result['partitioned_native_cells'] == 0
    assert zero_result['mapped_modules'] == 1
    nested, nested_result = qualify('Nested', '''
module State(input clk, reset, enable, input [7:0] data,
             output reg [7:0] q=8'h96, output reg [7:0] free_q);
always @(posedge clk) begin
  if (enable) begin
    if (reset) q <= 8'h3c;
    else q <= data + 8'h17;
  end
  free_q <= data;
end
endmodule
module Nested(input clk, reset, enable, input [7:0] data,
              output [7:0] q, free_q);
State inner(clk, reset, enable, data, q, free_q);
endmodule
''')
    assert nested_result['preserved_known_init_bits'] == 8
    original = json.loads((nested / 'reference-flat.json').read_text())['modules']['Nested']
    prepared = json.loads((nested / 'reference-prepared-flat.json').read_text())['modules']['Nested']
    before_ports = json.loads((nested / 'reference-ports.json').read_text())['modules']['Nested']
    helper.check_interface(before_ports, original)
    helper.check_interface(original, prepared)

    changed = copy.deepcopy(prepared)
    initialized = next(w for w in changed['netnames'].values()
        if any(c in '01' for c in w.get('attributes', {}).get('init', '')))
    value = initialized['attributes']['init']
    index = next(i for i, c in enumerate(value) if c in '01')
    initialized['attributes']['init'] = value[:index] + str(1 - int(value[index])) + value[index + 1:]
    reject('changed-initial-bit', lambda: helper.check_initial_state(original, changed))
    dropped = copy.deepcopy(prepared)
    for wire in dropped['netnames'].values():
        wire.get('attributes', {}).pop('init', None)
    reject('dropped-initial-constraint', lambda: helper.check_initial_state(original, dropped))
    added = copy.deepcopy(prepared)
    assert not any(bit in helper.known_init(prepared) for bit in added['netnames']['free_q']['bits'])
    added['netnames']['free_q'].setdefault('attributes', {})['init'] = '10100110'
    reject('added-initial-constraint', lambda: helper.check_initial_state(original, added))

    extra_port = copy.deepcopy(prepared)
    extra_port['ports']['unused'] = dict(direction='input', bits=[prepared['ports']['data']['bits'][0]])
    reject('added-unused-port', lambda: helper.check_interface(original, extra_port))
    narrow_port = copy.deepcopy(prepared)
    narrow_port['ports']['q']['bits'].pop()
    reject('changed-port-width', lambda: helper.check_interface(original, narrow_port))
    reversed_port = copy.deepcopy(prepared)
    reversed_port['ports']['q']['direction'] = 'input'
    reject('changed-port-direction', lambda: helper.check_interface(original, reversed_port))

    report = json.loads((nested / 'reference-final-stat.json').read_text())
    primitives = set(re.findall(r'(?m)^\s+(\$_[A-Za-z0-9_]+_)\s+\(',
        (nested / 'reference-primitive-cells.log').read_text()))
    leaf = next(name for name, module in report['modules'].items()
        if module['num_cells_by_type'] and set(module['num_cells_by_type']) <= primitives)
    unknown = copy.deepcopy(report)
    cells = unknown['modules'][leaf]['num_cells_by_type']
    count = cells.pop(next(iter(cells)))
    cells['$_NOT_A_REAL_PRIMITIVE_'] = count
    reject('unknown-primitive', lambda: helper.check_mapped_hierarchy(unknown, 'Nested', primitives))
    unreachable = copy.deepcopy(report)
    unreachable['modules']['\\unused'] = json.loads(
        (zero / 'reference-final-stat.json').read_text())['modules']['\\Zero']
    reject('unreachable-module', lambda: helper.check_mapped_hierarchy(unreachable, 'Nested', primitives))
    cycle = copy.deepcopy(report)
    cells = cycle['modules']['\\Nested']['num_cells_by_type']
    count = cells.pop(next(iter(cells)))
    cells['Nested'] = count
    reject('cyclic-module', lambda: helper.check_mapped_hierarchy(cycle, 'Nested', primitives))
    for field in ('num_processes', 'num_memories', 'num_memory_bits'):
        corrupted = copy.deepcopy(report)
        corrupted['modules'][leaf][field] = 1
        reject('unmapped-' + field, lambda: helper.check_mapped_hierarchy(corrupted, 'Nested', primitives))
    inconsistent = copy.deepcopy(report)
    inconsistent['modules'][leaf]['num_cells'] += 1
    reject('inconsistent-cell-count', lambda: helper.check_mapped_hierarchy(inconsistent, 'Nested', primitives))

    boxed = root / 'Boxed'
    boxed.mkdir(exist_ok=True)
    source = boxed / 'Boxed.v'
    source.write_text('(* blackbox *) module Opaque(input a, output y); endmodule\n'
                      'module Boxed(input a, output y); Opaque inner(a,y); endmodule\n')
    reject('blackbox', lambda: helper.qualify('Boxed', [source], boxed,
        'reference', widening.command, widening.H.quoted))
    reject('failed-command', lambda: widening.command([sys.executable, '-c', 'raise SystemExit(7)'],
        root / 'failed-command.log'))
    failure = json.loads((root / 'failed-command.timing.json').read_text())
    assert failure['status'] == 'failed' and failure['returncode'] == 7
    reject('timed-out-command', lambda: widening.command(
        [sys.executable, '-c', 'import time; time.sleep(2)'], root / 'timed-out-command.log', timeout=0.1))
    assert json.loads((root / 'timed-out-command.timing.json').read_text())['status'] == 'timeout'
    evidence.write_text(json.dumps(dict(controls=results), indent=2) + '\n')
    print('PASS: real zero-cell and nested-state full synthesis; init, interface, closure and tool-failure controls')


if __name__ == '__main__':
    try:
        main()
    except (AssertionError, OSError, ValueError, RuntimeError, KeyError) as error:
        print('FAIL: ' + str(error), file=sys.stderr)
        sys.exit(1)
