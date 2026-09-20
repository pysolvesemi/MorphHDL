#!/usr/bin/env python3
"""Qualify one published WIDTH/COUNT tree against separately native references."""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import itertools
import json
import re
import shutil
import sys
from pathlib import Path


def load(name: str, file: str):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(file))
    if spec is None or spec.loader is None:
        raise RuntimeError('missing qualification helper: ' + file)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


H = load('publication_tools', 'check-increment-59b-operator-replay.py')
STIMULUS = load('publication_stimulus', 'check-increment-59b-native-oracle.py')
OUTPUTS = ('uAdd', 'sAdd', 'bXor', 'qAnd', 'rAdd')
INDUCTIVE_PASS = 'Induction step proven: SUCCESS!'


def bindings(case: dict, role: str, prefix: str) -> str:
    ports = ['.unsignedIn(dataIn)', '.signedIn(signedDataIn)', '.bitsIn(bitsDataIn)']
    ports += ['.boolIn(boolIn)', '.clk(clk)', '.reset(reset)', '.enable(enable)']
    ports += [f'.{name}({prefix}_{name})' for name in OUTPUTS]
    return ', '.join(ports)


def instances(case: dict) -> list[str]:
    lines = []
    for prefix, role in (('g', 'reference'), ('c', 'candidate')):
        lines += [f'wire [{H.bitwidth(name, case["width"])-1}:0] {prefix}_{name};' for name in OUTPUTS]
        parameters = '' if role == 'reference' else f' #(.WIDTH({case["width"]}), .COUNT({case["count"]}))'
        lines += [f'{case[role + "_module"]}{parameters} {prefix}({bindings(case, role, prefix)});']
    return lines


def miter(case: dict) -> str:
    lines = [f'module miter(input wire clk, reset, enable, input wire [{case["width"]*case["count"]-1}:0] dataIn,',
             f'input wire [{case["width"]*case["count"]-1}:0] signedDataIn, bitsDataIn,',
             f'input wire [{case["count"]-1}:0] boolIn, output wire bad);']
    lines += instances(case)
    lines += ['assign bad = ' + ' | '.join(f'(|(g_{name} ^ c_{name}))' for name in OUTPUTS) + ';',
              'endmodule', '']
    return '\n'.join(lines)


def testbench(case: dict, samples: list[tuple[int, ...]]) -> str:
    width, count = case['width'], case['count']
    delay = (count - 1).bit_length()
    lines = ['`timescale 1ns/1ps', 'module tb;', 'reg clk, reset, enable;',
             f'reg [{width*count-1}:0] dataIn, signedDataIn, bitsDataIn;', f'reg [{count-1}:0] boolIn;']
    lines += instances(case) + ['initial begin',
        'clk=0; reset=1; enable=1; dataIn=0; signedDataIn=0; bitsDataIn=0; boolIn=0; #2; clk=1; #1; clk=0; #1;']
    history = [0 for _ in range(delay)]

    def compare(expected: dict, phase: str, tick: int) -> None:
        for name in OUTPUTS:
            bits = H.bitwidth(name, width)
            for prefix in ('g', 'c'):
                lines.extend([f"if ({prefix}_{name} !== {bits}'h{expected[name]:x}) begin",
                    f'$display("59B-PUBLICATION-MISMATCH {prefix}_{name} {phase} tick={tick}"); $finish(1);', 'end'])

    for index in range(max(160, len(samples)) + delay):
        words = samples[index % len(samples)]
        flags = ((index * 0x9e3779b1) ^ (index >> 1)) & ((1 << count) - 1)
        if index < 2:
            flags = 0 if index == 0 else (1 << count) - 1
        packed = sum(word << (i * width) for i, word in enumerate(words))
        # Distinct scalar Vec inputs expose accidental binding across trees.
        mask = (1 << width) - 1
        signed_words = tuple((word ^ (1 << (width-1)) ^ index) & mask for word in reversed(words))
        bits_words = tuple((~word ^ ((i+index)*0x9e3779b1)) & mask for i, word in enumerate(words))
        signed_packed = sum(word << (i * width) for i, word in enumerate(signed_words))
        bits_packed = sum(word << (i * width) for i, word in enumerate(bits_words))
        expected = H.expected(words, flags, width)
        expected['sAdd'] = H.expected(signed_words, flags, width)['sAdd']
        expected['bXor'] = H.expected(bits_words, flags, width)['bXor']
        reset = int(index in (17, 18, 55, 56, 111, 112))
        enable = int(index % 5 != 0 and not 30 <= index < 37 and index not in (17, 55, 111))
        lines += [f"reset={reset}; enable={enable}; dataIn={width*count}'h{packed:x}; signedDataIn={width*count}'h{signed_packed:x}; bitsDataIn={width*count}'h{bits_packed:x}; boolIn={count}'h{flags:x}; #2;"]
        expected['rAdd'] = expected['uAdd'] if not delay else history[-1]
        compare(expected, 'before-edge', index)
        lines += ['clk=1; #1;']
        if delay and enable:
            history = [0 for _ in range(delay)] if reset else [expected['uAdd']] + history[:-1]
        expected['rAdd'] = expected['uAdd'] if not delay else history[-1]
        compare(expected, 'after-edge', index)
        lines += ['clk=0; #1;']
    lines += ['$display("59B-PUBLICATION-SIM-PASS"); $finish;', 'end', 'endmodule', '']
    return '\n'.join(lines)


def specialized_top(case: dict) -> str:
    width, count = case['width'], case['count']
    lines = [f'module specialized(input wire clk, reset, enable, input wire [{width*count-1}:0] dataIn,',
             f'input wire [{width*count-1}:0] signedDataIn, bitsDataIn,', f'input wire [{count-1}:0] boolIn,']
    lines += [',\n'.join(f'output wire [{H.bitwidth(name, width)-1}:0] c_{name}' for name in OUTPUTS) + ');']
    lines += [f'{case["candidate_module"]} #(.WIDTH({width}), .COUNT({count})) dut({bindings(case, "candidate", "c")});',
              'endmodule', '']
    return '\n'.join(lines)


def setup(paths: list[Path]) -> str:
    return 'read_verilog ' + ' '.join(H.quoted(path) for path in paths) + '\nprep -top miter -flatten\ndffunmap\ncheck -assert\n'


def candidate_contract(text: str) -> None:
    if len(re.findall(r'^module\s+BalancedPublication\b', text, re.MULTILINE)) != 1:
        raise RuntimeError('one parameterized candidate module is required')
    if not re.search(r'parameter\s+(?:integer\s+)?WIDTH\s*=\s*5\b', text) or not re.search(
            r'parameter\s+(?:integer\s+)?COUNT\s*=\s*1\b', text):
        raise RuntimeError('candidate must retain independent WIDTH=5 and singleton COUNT=1 defaults')
    for tree in range(1, 6):
        for level in range(5):
            if f'morphhdl_balanced_{tree}_active_{level}' not in text:
                raise RuntimeError('default singleton incorrectly discarded higher balanced stages')
    if 'genvar' not in text or 'begin : tail' not in text or '+:' not in text:
        raise RuntimeError('missing structural pair loop, odd tail, or packed native input slices')


def mutate_pair_operand(text: str) -> str:
    # Alter an actual generated pair connection, without an operator-specific
    # handwritten RTL implementation or a mutation only in the observation.
    left = re.search(r'(?m)^\s*assign\s+(\w*morphhdl_balanced_1_l0_pair_left\w*)\s*=\s*([^;]+);', text)
    right = re.search(r'(?m)^(\s*assign\s+\w*morphhdl_balanced_1_l0_pair_right\w*\s*=\s*)[^;]+;', text)
    if left is None or right is None:
        raise RuntimeError('native pair operand anchors were not retained for mutation')
    result = text[:right.start()] + right.group(1) + left.group(2) + ';' + text[right.end():]
    if result == text:
        raise RuntimeError('candidate pair-operand mutation made no change')
    return result


def mutate_vec_binding(text: str) -> str:
    pattern = re.compile(r'(?m)^(\s*assign\s+morphhdl_balanced_2_input\s*=\s*)signedIn\s*;')
    if len(pattern.findall(text)) != 1:
        raise RuntimeError('one exact signed Vec source binding is required for mutation')
    return pattern.sub(lambda match: match.group(1) + 'unsignedIn;', text)


def qualify(root: Path, duplicate: Path, only_case: str | None = None) -> None:
    root, duplicate = root.resolve(), duplicate.resolve()
    for tool in ('iverilog', 'vvp', 'verilator', 'yosys'):
        if shutil.which(tool) is None:
            raise RuntimeError('required tool missing: ' + tool)
    manifest_path = root / 'manifest.json'
    manifest = json.loads(manifest_path.read_text())
    if manifest.get('scope') != 'parameterized-native-balanced-publication':
        raise RuntimeError('incorrect publication evidence scope')
    if manifest.get('independent_inputs') != ['unsignedIn', 'signedIn', 'bitsIn', 'boolIn']:
        raise RuntimeError('unsigned, signed, bits and boolean Vec inputs must be independent')
    if manifest.get('outputs') != list(OUTPUTS) or manifest.get('candidate_default') != dict(width=5, count=1):
        raise RuntimeError('incorrect public helper output or elaboration default contract')
    if manifest_path.read_bytes() != (duplicate / 'manifest.json').read_bytes():
        raise RuntimeError('nondeterministic publication manifest')
    cases = manifest['configurations']
    shapes = [(case['width'], case['count']) for case in cases]
    if len(shapes) != 32 or set(shapes) != set(itertools.product(H.WIDTHS, H.COUNTS)):
        raise RuntimeError('incomplete or duplicated parameter specialization matrix')
    if len({(case['candidate_rtl'], case['candidate_module']) for case in cases}) != 1:
        raise RuntimeError('every specialization must use the same candidate artifact')
    candidate = H.checked_rtl(root, cases[0]['candidate_rtl'])
    candidate_contract(candidate.read_text())
    if candidate.read_bytes() != H.checked_rtl(duplicate, cases[0]['candidate_rtl']).read_bytes():
        raise RuntimeError('nondeterministic parameterized candidate RTL')
    selected = cases if only_case is None else [case for case in cases if f'w{case["width"]}_n{case["count"]}' == only_case]
    if not selected:
        raise RuntimeError('unknown focused publication case')
    evidence = []
    for case in selected:
        width, count = case['width'], case['count']
        label = f'w{width}_n{count}'
        work = root / 'checks' / label
        work.mkdir(parents=True, exist_ok=True)
        for role in ('reference', 'candidate'):
            if not H.IDENTIFIER.fullmatch(case[role + '_module']):
                raise RuntimeError('invalid module identifier')
        reference = H.checked_rtl(root, case['reference_rtl'])
        if reference.read_bytes() != H.checked_rtl(duplicate, case['reference_rtl']).read_bytes():
            raise RuntimeError('nondeterministic independent reference: ' + label)
        specialized = work / 'specialized.v'
        specialized.write_text(specialized_top(case))
        for role, module, paths in (
                ('reference', case['reference_module'], [reference]),
                ('candidate', 'specialized', [candidate, specialized])):
            H.command(['verilator', '--lint-only', '--language', '1364-2001', '--top-module', module,
                       *map(str, paths)], work / (role + '-lint.log'))
            script = work / (role + '-synthesis.ys')
            script.write_text('read_verilog ' + ' '.join(H.quoted(path) for path in paths) +
                              f'\nhierarchy -check -top {module}\nsynth -top {module}\ncheck -assert\nstat\n')
            H.command(['yosys', '-Q', '-T', '-s', str(script)], work / (role + '-synthesis.log'))
        samples = STIMULUS.samples(width, count)
        bench = work / 'tb.v'
        bench.write_text(testbench(case, samples))
        executable = work / 'tb.vvp'
        H.command(['iverilog', '-g2001', '-s', 'tb', '-o', str(executable), str(reference), str(candidate), str(bench)], work / 'compile.log')
        simulation = H.command(['vvp', str(executable)], work / 'simulation.log')
        if '59B-PUBLICATION-SIM-PASS' not in simulation or '59B-PUBLICATION-MISMATCH' in simulation:
            raise RuntimeError('independent specialization simulation failed: ' + label)
        top = work / 'miter.v'
        top.write_text(miter(case))
        script = work / 'reset-entry.ys'
        script.write_text(setup([reference, candidate, top]) +
                          'sat -seq 2 -set-at 1 reset 1 -set-at 1 enable 1 -prove bad 0 -prove-skip 1 -verify -timeout 90\n')
        proof = H.command(['yosys', '-Q', '-T', '-s', str(script)], work / 'reset-entry.log')
        if H.PASS not in proof or H.COUNTEREXAMPLE in proof:
            raise RuntimeError('specialization reset-entry proof lacks definitive SUCCESS: ' + label)
        script = work / 'induction.ys'
        script.write_text(setup([reference, candidate, top]) +
                          'sat -seq 1 -tempinduct -set-init-zero -prove bad 0 -verify -maxsteps 24 -timeout 90\n')
        proof = H.command(['yosys', '-Q', '-T', '-s', str(script)], work / 'induction.log')
        if INDUCTIVE_PASS not in proof:
            raise RuntimeError('unbounded specialization induction lacks definitive SUCCESS: ' + label)
        evidence.append(dict(width=width, count=count, outputs=len(OUTPUTS), samples=len(samples), cycles=max(160, len(samples))+(count-1).bit_length(),
                             sha256={role: hashlib.sha256(path.read_bytes()).hexdigest()
                                     for role, path in (('reference', reference), ('candidate', candidate))}, latency=(count-1).bit_length(), reset_entry='PASS', induction='PASS'))
        print('PASS:', label, 'same parameterized artifact, independent simulation, strict tools and equivalence', flush=True)
    if only_case is not None:
        print('PASS: focused case only; complete publication qualification and mutation remain required', flush=True)
        return

    case = next(case for case in cases if (case['width'], case['count']) == (5, 5))
    reference = H.checked_rtl(root, case['reference_rtl'])
    mutations = [('pair-operand-mutation', mutate_pair_operand), ('cross-vec-binding-mutation', mutate_vec_binding)]
    for label, mutate in mutations:
        work = root / 'checks' / label
        work.mkdir(parents=True, exist_ok=True)
        mutated = work / 'candidate-mutated.v'
        mutated.write_text(mutate(candidate.read_text()))
        top = work / 'miter.v'
        top.write_text(miter(case))
        trace = work / 'counterexample.vcd'
        script = work / 'mutation.ys'
        script.write_text(setup([reference, mutated, top]) +
                          'sat -seq 8 -set-init-zero -prove bad 0 -show-inputs -show-outputs -timeout 90 -dump_vcd ' + H.quoted(trace) + '\n')
        proof = H.command(['yosys', '-Q', '-T', '-s', str(script)], work / 'mutation.log')
        if H.COUNTEREXAMPLE not in proof or H.PASS in proof:
            raise RuntimeError('mutated published RTL failed to produce a genuine counterexample: ' + label)
        H.require_counterexample_vcd(trace)
    (root / 'evidence.json').write_text(json.dumps(dict(
        scope='parameterized-native-balanced-publication', candidate_default=dict(width=5, count=1),
        formal_inputs='independent unsigned, signed, bits and boolean packed vectors',
        reset_model='native CE gates synchronous active-high reset', configurations=evidence,
        mutation='published pair operand and cross-Vec source binding replaced; both counterexamples bad=1',
        mutation_controls=[label for label, _ in mutations]), indent=2) + '\n')
    print('PASS: 32 parameterized specializations, independent native references, strict tools, simulation, determinism and genuine candidate mutation', flush=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path)
    parser.add_argument('duplicate', type=Path)
    parser.add_argument('--case', dest='only_case', help='Focused development check, e.g. w5_n3; emits no full qualification evidence')
    args = parser.parse_args()
    qualify(args.root, args.duplicate, args.only_case)


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, RuntimeError, KeyError) as error:
        print('FAIL: ' + str(error), file=sys.stderr)
        sys.exit(1)
