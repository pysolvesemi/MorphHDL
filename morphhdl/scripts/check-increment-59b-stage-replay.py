#!/usr/bin/env python3
"""Qualify concrete native whole-stage replay, not parameterized COUNT publication."""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import itertools
import json
import random
import re
import shutil
import sys
from pathlib import Path


def load_helpers():
    path = Path(__file__).with_name('check-increment-59b-operator-replay.py')
    spec = importlib.util.spec_from_file_location('operator_checks', path)
    if spec is None or spec.loader is None:
        raise RuntimeError('missing qualified operator-check helpers')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


H = load_helpers()
MODES = (0, 1, 2)
INDUCTIVE_PASS = 'Induction step proven: SUCCESS!'


def latency(count: int, mode: int) -> int:
    levels = (count - 1).bit_length()
    return 0 if mode == 0 else levels if mode == 1 else 2 * max(0, levels - 1)


def instances(case: dict, mutation: bool = False) -> list[str]:
    width = case['width']
    lines = []
    for prefix, role in (('g', 'reference'), ('c', 'replay')):
        lines += [f'wire [{H.bitwidth(name, width)-1}:0] {prefix}_{name};' for name in H.OUTPUTS]
        bindings = ['.clk(clk)', '.reset(reset)', '.enable(enable)', '.dataIn(dataIn)', '.boolIn(boolIn)']
        bindings += [f'.{name}({prefix}_{name})' for name in H.OUTPUTS]
        lines += [f"{case[role + '_module']} {prefix}({', '.join(bindings)});"]
    if mutation:
        lines += [f'reg [{width-1}:0] checked_sum;',
                  'always @(posedge clk) begin',
                  '  if (reset) checked_sum <= 0;',
                  '  else if (enable) checked_sum <= c_uAdd;', 'end']
    else:
        lines += [f'wire [{width-1}:0] checked_sum = c_uAdd;']
    return lines


def miter(case: dict, mutation: bool = False) -> str:
    width, count = case['width'], case['count']
    lines = [f'module miter(input wire clk, reset, enable, input wire [{width*count-1}:0] dataIn,',
             f'input wire [{count-1}:0] boolIn, output wire bad);']
    lines += instances(case, mutation)
    lines += ['assign bad = ' + ' | '.join(
        f'(|(g_{name} ^ {"checked_sum" if name == "uAdd" else "c_"+name}))'
        for name in H.OUTPUTS) + ';', 'endmodule', '']
    return '\n'.join(lines)


def simulation_bench(case: dict) -> str:
    width, count, delay = case['width'], case['count'], case['latency']
    mask, flagmask = (1 << width) - 1, (1 << count) - 1
    rng = random.Random(590000 + width * 1000 + count)
    lines = ['`timescale 1ns/1ps', 'module tb;', 'reg clk, reset, enable;',
             f'reg [{width*count-1}:0] dataIn;', f'reg [{count-1}:0] boolIn;']
    lines += instances(case)
    lines += ['initial begin', 'clk=0; reset=1; enable=0; dataIn=0; boolIn=0; #2; clk=1; #1; clk=0; #1;']
    zero = dict.fromkeys(H.OUTPUTS, 0)
    history = [zero.copy() for _ in range(delay)]

    def compare(expected: dict[str, int], phase: str, tick: int) -> None:
        for name, value in expected.items():
            bits = H.bitwidth(name, width)
            for role in ('g', 'c'):
                lines.extend([f"if ({role}_{name} !== {bits}'h{value:x}) begin",
                              f'$display("59B-STAGE-MISMATCH {phase} tick={tick} {role}_{name}"); $finish(1);', 'end'])

    for tick in range(160):
        words = tuple(rng.randrange(mask + 1) for _ in range(count))
        flags = rng.randrange(flagmask + 1)
        if tick % 11 == 0:
            words, flags = (mask,) * count, flagmask
        elif tick % 11 == 1:
            words, flags = (0,) * count, 0
        elif tick % 11 == 2:
            index = (tick // 11) % count
            words = tuple(mask if i == index else 0 for i in range(count))
            flags = 1 << index
        reset = int(tick in (17, 55, 111))
        enable = int(tick % 5 != 0 and not 30 <= tick < 37 and not reset)
        value = H.expected(words, flags, width)
        packed = sum(word << (i * width) for i, word in enumerate(words))
        lines += [f"reset={reset}; enable={enable}; dataIn={width*count}'h{packed:x}; boolIn={count}'h{flags:x}; #2;"]
        compare(value if not delay else history[-1], 'before-edge', tick)
        lines += ['clk=1; #1;']
        if delay:
            if reset:
                history = [zero.copy() for _ in range(delay)]
            elif enable:
                history = [value] + history[:-1]
        compare(value if not delay else history[-1], 'after-edge', tick)
        lines += ['clk=0; #1;']
    lines += ['$display("59B-STAGE-SIM-PASS"); $finish;', 'end', 'endmodule', '']
    return '\n'.join(lines)


def design_script(paths: list[Path]) -> str:
    return ('read_verilog ' + ' '.join(H.quoted(path) for path in paths) +
            '\nprep -top miter -flatten\ndffunmap\ncheck -assert\n')


def qualify(root: Path, duplicate: Path) -> None:
    root, duplicate = root.resolve(), duplicate.resolve()
    for tool in ('iverilog', 'vvp', 'verilator', 'yosys'):
        if shutil.which(tool) is None:
            raise RuntimeError('required tool missing: ' + tool)
    manifest = json.loads((root / 'manifest.json').read_text())
    if manifest.get('scope') != 'concrete-native-stage-replay' or manifest.get('parameterized_tree_formal') != 'not-run':
        raise RuntimeError('incorrect stage-proof scope')
    if (root / 'manifest.json').read_bytes() != (duplicate / 'manifest.json').read_bytes():
        raise RuntimeError('nondeterministic stage manifest')
    cases = manifest['configurations']
    shapes = [(case['width'], case['count'], case['mode']) for case in cases]
    if len(shapes) != 96 or set(shapes) != set(itertools.product(H.WIDTHS, H.COUNTS, MODES)):
        raise RuntimeError('incomplete or duplicated stage matrix')
    results = []
    for case in cases:
        width, count, mode = case['width'], case['count'], case['mode']
        if case['latency'] != latency(count, mode):
            raise RuntimeError('incorrect native stage latency')
        label = f'w{width}_n{count}_m{mode}'
        work = root / 'checks' / label
        work.mkdir(parents=True, exist_ok=True)
        paths, digests = [], {}
        for role in ('reference', 'replay'):
            module = case[role + '_module']
            if not H.IDENTIFIER.fullmatch(module):
                raise RuntimeError('invalid module identifier')
            rtl = H.checked_rtl(root, case[role + '_rtl'])
            other = H.checked_rtl(duplicate, case[role + '_rtl'])
            if rtl.read_bytes() != other.read_bytes():
                raise RuntimeError('nondeterministic ' + label + ' ' + role)
            paths.append(rtl)
            digests[role] = hashlib.sha256(rtl.read_bytes()).hexdigest()
            H.command(['verilator', '--lint-only', '--language', '1364-2001', '--top-module', module, str(rtl)], work / (role + '-lint.log'))
            script = work / (role + '-synthesis.ys')
            script.write_text(f'read_verilog {H.quoted(rtl)}\nhierarchy -check -top {module}\nsynth -top {module}\ncheck -assert\nstat\n')
            H.command(['yosys', '-Q', '-T', '-s', str(script)], work / (role + '-synthesis.log'))
        bench = work / 'tb.v'
        bench.write_text(simulation_bench(case))
        executable = work / 'tb.vvp'
        H.command(['iverilog', '-g2001', '-s', 'tb', '-o', str(executable), *map(str, paths), str(bench)], work / 'compile.log')
        output = H.command(['vvp', str(executable)], work / 'simulation.log')
        if '59B-STAGE-SIM-PASS' not in output or '59B-STAGE-MISMATCH' in output:
            raise RuntimeError('independent pipeline simulation failed: ' + label)
        top = work / 'miter.v'
        top.write_text(miter(case))
        setup = design_script(paths + [top])
        # A reset edge establishes the all-zero sequential state even when
        # enable is low. No initial-state correlation is required for this
        # reset-entry check. Subsequent induction starts in that reset state.
        script = work / 'reset-entry.ys'
        script.write_text(setup + 'sat -seq 2 -set-at 1 reset 1 -set-at 1 enable 0 -prove bad 0 -prove-skip 1 -verify -timeout 60\n')
        entry = H.command(['yosys', '-Q', '-T', '-s', str(script)], work / 'reset-entry.log')
        if H.PASS not in entry:
            raise RuntimeError('reset-entry proof missing definitive SUCCESS: ' + label)
        script = work / 'induction.ys'
        script.write_text(setup + 'sat -seq 1 -tempinduct -set-init-zero -prove bad 0 -verify -maxsteps 24 -timeout 60\n')
        proof = H.command(['yosys', '-Q', '-T', '-s', str(script)], work / 'induction.log')
        if INDUCTIVE_PASS not in proof:
            raise RuntimeError('unbounded induction missing definitive SUCCESS: ' + label)
        results.append(dict(width=width, count=count, mode=mode, latency=case['latency'],
                            outputs=14, cycles=160, sha256=digests, reset_entry='PASS', induction='PASS'))
        print('PASS:', label, 'independent simulation, strict tools, reset entry and temporal induction', flush=True)

    case = next(case for case in cases if (case['width'], case['count'], case['mode']) == (5, 5, 1))
    work = root / 'checks' / 'latency-mutation'
    work.mkdir(parents=True, exist_ok=True)
    top = work / 'miter.v'
    top.write_text(miter(case, mutation=True))
    paths = [H.checked_rtl(root, case[role + '_rtl']) for role in ('reference', 'replay')]
    trace = work / 'counterexample.vcd'
    script = work / 'mutation.ys'
    script.write_text(design_script(paths + [top]) +
                      'sat -seq 8 -set-init-zero -prove bad 0 -show-inputs -show-outputs -timeout 60 -dump_vcd ' + H.quoted(trace) + '\n')
    proof = H.command(['yosys', '-Q', '-T', '-s', str(script)], work / 'mutation.log')
    if H.COUNTEREXAMPLE not in proof or H.PASS in proof:
        raise RuntimeError('extra-latency mutation did not produce a real counterexample')
    H.require_counterexample_vcd(trace)
    (root / 'evidence.json').write_text(json.dumps(dict(scope='concrete-native-stage-replay',
        parameterized_tree_formal='not-run', reset_model='synchronous active-high reset dominates enable',
        configurations=results, mutation='extra enabled cycle, counterexample bad=1'), indent=2) + '\n')
    print('PASS: all 96 native stage shapes, 14 outputs, reset-entry proofs, unbounded induction and latency mutation', flush=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path)
    parser.add_argument('duplicate', type=Path)
    args = parser.parse_args()
    qualify(args.root, args.duplicate)


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, RuntimeError, KeyError) as error:
        print('FAIL: ' + str(error), file=sys.stderr)
        sys.exit(1)
