#!/usr/bin/env python3
"""Qualify native fixed-width callback graphs, exact captures and pairing order."""
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


def load(name: str, filename: str):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    if spec is None or spec.loader is None:
        raise RuntimeError('missing qualification helper: ' + filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


H = load('callback_graph_tools', 'check-increment-59b-operator-replay.py')
STIMULUS = load('callback_graph_stimulus', 'check-increment-59b-native-oracle.py')
OUTPUTS = ('composed', 'subtraction', 'helper', 'selected', 'conditioned',
           'sliced', 'part', 'biased', 'alternate', 'saturated', 'signedComposed', 'signedSub',
           'signedBiased', 'signedSelect', 'bitsComposed', 'boolComposed')
SCOPE = 'parameterized-native-safe-callback-graphs'
INPUTS = ['dataIn', 'bias', 'otherBias', 'signedIn', 'signedBias', 'bitsIn', 'boolIn']


def bitwidth(name: str, width: int) -> int:
    return 1 if name == 'boolComposed' else width


def tree(words: tuple[int, ...], combine) -> int:
    """Independent exact adjacent pairing with an untouched odd tail."""
    level = list(words)
    while len(level) > 1:
        following = [combine(level[i], level[i + 1]) for i in range(0, len(level) - 1, 2)]
        if len(level) % 2:
            following.append(level[-1])
        level = following
    return level[0]


def expected(words: tuple[int, ...], width: int, bias: int, other: int) -> dict[str, int]:
    mask = (1 << width) - 1
    callbacks = {
        'composed': lambda a, b: ((a + b) & mask) ^ a,
        'subtraction': lambda a, b: (a - b) & mask,
        'helper': lambda a, b: ((a + b) & mask) ^ a,
        'selected': lambda a, b: a if a & 1 else b,
        'conditioned': max,
        'sliced': lambda a, b: b ^ a,
        'part': lambda a, b: (a & 1) ^ b,
        'biased': lambda a, b: (a + b + bias) & mask,
        'alternate': lambda a, b: ((a ^ b) + other) & mask,
        'saturated': lambda a, b: min(a + b, mask),
    }
    return {name: tree(words, callback) for name, callback in callbacks.items()}


def bindings(prefix: str) -> str:
    return ', '.join([f'.{name}({name})' for name in INPUTS] +
                     [f'.{name}({prefix}_{name})' for name in OUTPUTS])


def instances(case: dict) -> list[str]:
    lines = []
    for prefix, role in (('g', 'reference'), ('c', 'candidate')):
        lines += [f'wire [{bitwidth(name, case["width"]) - 1}:0] {prefix}_{name};' for name in OUTPUTS]
        parameters = '' if role == 'reference' else f' #(.WIDTH({case["width"]}), .COUNT({case["count"]}))'
        lines += [f'{case[role + "_module"]}{parameters} {prefix}({bindings(prefix)});']
    return lines


def miter(case: dict) -> str:
    width, count = case['width'], case['count']
    return '\n'.join([
        f'module miter(input wire [{width * count - 1}:0] dataIn,',
        f'input wire [{width * count - 1}:0] signedIn, bitsIn,',
        f'input wire [{count - 1}:0] boolIn,',
        f'input wire [{width - 1}:0] bias, otherBias, signedBias, output wire bad);',
        *instances(case),
        'assign bad = ' + ' | '.join(f'(|(g_{name} ^ c_{name}))' for name in OUTPUTS) + ';',
        'endmodule', ''])


def specialized_top(case: dict) -> str:
    width, count = case['width'], case['count']
    return '\n'.join([
        f'module specialized(input wire [{width * count - 1}:0] dataIn,',
        f'input wire [{width * count - 1}:0] signedIn, bitsIn,',
        f'input wire [{count - 1}:0] boolIn,',
        f'input wire [{width - 1}:0] bias, otherBias, signedBias,',
        ',\n'.join(f'output wire [{bitwidth(name, width) - 1}:0] c_{name}' for name in OUTPUTS) + ');',
        f'{case["candidate_module"]} #(.WIDTH({width}), .COUNT({count})) dut({bindings("c")});',
        'endmodule', ''])


def testbench(case: dict, samples: list[tuple[int, ...]]) -> str:
    width, count = case['width'], case['count']
    mask = (1 << width) - 1
    lines = ['`timescale 1ns/1ps', 'module tb;', f'reg [{width * count - 1}:0] dataIn, signedIn, bitsIn;',
             f'reg [{count - 1}:0] boolIn;',
             f'reg [{width - 1}:0] bias, otherBias, signedBias;', *instances(case), 'initial begin']
    for index, words in enumerate(samples):
        packed = sum(word << (i * width) for i, word in enumerate(words))
        signed_words = tuple((word ^ (1 << (width - 1)) ^ index) & mask for word in reversed(words))
        bits_words = tuple((~word ^ ((index + i) * 0x9e3779b1)) & mask for i, word in enumerate(words))
        signed_packed = sum(word << (i * width) for i, word in enumerate(signed_words))
        bits_packed = sum(word << (i * width) for i, word in enumerate(bits_words))
        flags = ((index * 0x9e3779b1) ^ (index >> 1)) & ((1 << count) - 1)
        if index < 2:
            flags = 0 if index == 0 else (1 << count) - 1
        # Repeat each independent Vec stimulus with opposing capture values. This
        # covers runtime variability at fixed element values and singleton bypass.
        for capture_index, bias in enumerate((0, mask, (index * 0x9e3779b1) & mask)):
            other = (bias ^ mask ^ index) & mask
            signed_bias = (other ^ (1 << (width - 1)) ^ (index * 7)) & mask
            lines += [f"dataIn={width * count}'h{packed:x}; signedIn={width * count}'h{signed_packed:x}; bitsIn={width * count}'h{bits_packed:x}; boolIn={count}'h{flags:x};",
                      f"bias={width}'h{bias:x}; otherBias={width}'h{other:x}; signedBias={width}'h{signed_bias:x}; #1;"]
            expected_values = expected(words, width, bias, other)
            signed_results = expected(signed_words, width, signed_bias, 0)
            def signed(word: int) -> int:
                return word if word < (1 << (width - 1)) else word - (1 << width)
            expected_values.update(signedComposed=signed_results['composed'], signedSub=signed_results['subtraction'],
                signedBiased=signed_results['biased'], signedSelect=tree(signed_words, lambda a, b: a if signed(a) > signed(b) else b),
                bitsComposed=tree(bits_words, lambda a, b: (a ^ b) & a),
                boolComposed=tree(tuple((flags >> i) & 1 for i in range(count)), lambda a, b: (a ^ b) | a))
            for name, value in expected_values.items():
                for prefix in ('g', 'c'):
                    lines += [f"if ({prefix}_{name} !== {bitwidth(name, width)}'h{value:x}) begin",
                              f'$display("59F-CALLBACK-MISMATCH {prefix}_{name} sample={index} capture={capture_index}"); $finish(1);',
                              'end']
    return '\n'.join(lines + ['$display("59F-CALLBACK-SIM-PASS"); $finish;', 'end', 'endmodule', ''])


def setup(paths: list[Path]) -> str:
    return 'read_verilog ' + ' '.join(H.quoted(path) for path in paths) + '\nprep -top miter -flatten\ncheck -assert\n'


def candidate_contract(rtl: str, profile: dict) -> None:
    module = profile['module']
    if len(re.findall(r'^module\s+' + re.escape(module) + r'\b', rtl, re.MULTILINE)) != 1:
        raise RuntimeError('one candidate per declared profile is required')
    for parameter, field in (('WIDTH', 'width'), ('COUNT', 'count')):
        if not re.search(r'parameter\s+(?:integer\s+)?' + parameter + r'\s*=\s*' + str(profile[field]) + r'\b', rtl):
            raise RuntimeError('missing declared parameter default: ' + parameter)
    for ordinal in range(1, len(OUTPUTS) + 1):
        for level in range(5):
            if f'morphhdl_balanced_{ordinal}_active_{level}' not in rtl:
                raise RuntimeError('default specialization discarded a potentially active callback stage')
    if 'genvar' not in rtl or 'begin : tail' not in rtl or '+:' not in rtl:
        raise RuntimeError('native pairing loops, odd tail or packed input slices missing')


def mutate_pair_order(rtl: str) -> str:
    pattern = lambda side: re.compile(r'(?m)^(\s*assign\s+\w*morphhdl_balanced_2_l0_pair_' + side + r'\w*\s*=\s*)([^;]+);')
    left, right = pattern('left').search(rtl), pattern('right').search(rtl)
    if left is None or right is None:
        raise RuntimeError('subtraction pair anchors missing for order mutation')
    replacements = [(left.start(2), left.end(2), right.group(2)),
                    (right.start(2), right.end(2), left.group(2))]
    for start, end, replacement in sorted(replacements, reverse=True):
        rtl = rtl[:start] + replacement + rtl[end:]
    return rtl


def mutate_dropped_operation(rtl: str) -> str:
    # Remove an actual native XOR node from the first eligible callback
    # assignment. This is an RTL mutation control, never a production recognizer.
    pattern = re.compile(r'(?m)^(\s*assign\s+[^;=]+\s*=\s*)([^;]*\^[^;]*);')
    for match in pattern.finditer(rtl):
        expression = match.group(2).strip()
        # Strip only parentheses that enclose the complete native expression.
        while expression.startswith('(') and expression.endswith(')'):
            depth, closes_at_end = 0, True
            for index, character in enumerate(expression):
                depth += (character == '(') - (character == ')')
                if depth == 0 and index != len(expression) - 1:
                    closes_at_end = False
                    break
            if depth != 0 or not closes_at_end:
                break
            expression = expression[1:-1].strip()
        depth = 0
        for index, character in enumerate(expression):
            depth += (character == '(') - (character == ')')
            if character == '^' and depth == 0:
                left, right = expression[:index].strip(), expression[index + 1:].strip()
                if left and right:
                    return rtl[:match.start(2)] + '(' + left + ')' + rtl[match.end(2):]
    raise RuntimeError('no actual binary native XOR node found for dropped-operation mutation')


def mutate_capture_binding(rtl: str) -> str:
    # Only actual assignment RHS reads are mutated; ports remain separate and
    # the independently unconstrained reference still reads the original bias.
    pattern = re.compile(r'(?m)^(\s*assign\s+[^;=]+\s*=\s*)([^;]*\bbias\b[^;]*);')
    matches = list(pattern.finditer(rtl))
    if not matches:
        raise RuntimeError('no runtime bias capture binding retained for mutation')
    return pattern.sub(lambda match: match.group(1) + re.sub(r'\bbias\b', 'otherBias', match.group(2)) + ';', rtl)


def qualify(root: Path, duplicate: Path, only_case: str | None = None) -> None:
    root, duplicate = root.resolve(), duplicate.resolve()
    for tool in ('iverilog', 'vvp', 'verilator', 'yosys'):
        if shutil.which(tool) is None:
            raise RuntimeError('required tool missing: ' + tool)
    manifest_path = root / 'manifest.json'
    manifest = json.loads(manifest_path.read_text())
    if manifest.get('scope') != SCOPE or manifest.get('outputs') != list(OUTPUTS):
        raise RuntimeError('incorrect callback graph evidence scope or output inventory')
    if manifest.get('independent_inputs') != INPUTS:
        raise RuntimeError('all typed scalar Vecs and runtime captures must remain independent')
    if manifest_path.read_bytes() != (duplicate / 'manifest.json').read_bytes():
        raise RuntimeError('nondeterministic callback graph manifest')
    shapes = [(case['width'], case['count']) for case in manifest['configurations']]
    if len(shapes) != 32 or set(shapes) != set(itertools.product(H.WIDTHS, H.COUNTS)):
        raise RuntimeError('incomplete or duplicated width/count matrix')
    profiles = manifest['profiles']
    if [(profile['profile'], profile['width'], profile['count']) for profile in profiles] != [
            ('singleton', 5, 1), ('alternate', 8, 3)]:
        raise RuntimeError('singleton and alternate candidate defaults must both be qualified')
    if len({profile['rtl'] for profile in profiles}) != len(profiles):
        raise RuntimeError('each declared profile must retain its own sole candidate artifact')
    evidence, matched = [], False
    for profile in profiles:
        candidate = H.checked_rtl(root, profile['rtl'])
        if candidate.read_bytes() != H.checked_rtl(duplicate, profile['rtl']).read_bytes():
            raise RuntimeError('nondeterministic candidate: ' + profile['profile'])
        candidate_contract(candidate.read_text(), profile)
        for original in manifest['configurations']:
            case = dict(original, candidate_module=profile['module'], candidate_rtl=profile['rtl'])
            width, count = case['width'], case['count']
            label = f'{profile["profile"]}_w{width}_n{count}'
            if only_case is not None and only_case != label:
                continue
            matched = True
            if case['scalar_result_width'] != width or case['bool_result_width'] != 1 or case['result_kinds'] != ['UInt', 'SInt', 'Bits', 'Bool']:
                raise RuntimeError('native width/type mismatch may not be hidden by miter padding')
            if not all(H.IDENTIFIER.fullmatch(case[role + '_module']) for role in ('candidate', 'reference')):
                raise RuntimeError('invalid native module identifier')
            work = root / 'checks' / label
            work.mkdir(parents=True, exist_ok=True)
            reference = H.checked_rtl(root, case['reference_rtl'])
            if reference.read_bytes() != H.checked_rtl(duplicate, case['reference_rtl']).read_bytes():
                raise RuntimeError('nondeterministic independent native reference: ' + label)
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
            if '59F-CALLBACK-SIM-PASS' not in simulation or '59F-CALLBACK-MISMATCH' in simulation:
                raise RuntimeError('independent native/integer simulation failed: ' + label)
            top = work / 'miter.v'
            top.write_text(miter(case))
            script = work / 'equivalence.ys'
            script.write_text(setup([reference, candidate, top]) +
                              'sat -prove bad 0 -verify -timeout 90\n')
            proof = H.command(['yosys', '-Q', '-T', '-s', str(script)], work / 'equivalence.log')
            if H.PASS not in proof or H.COUNTEREXAMPLE in proof:
                raise RuntimeError('specialization proof lacks definitive SUCCESS: ' + label)
            evidence.append(dict(profile=profile['profile'], width=width, count=count,
                outputs=len(OUTPUTS), simulation_vectors=len(samples) * 3,
                native_operator_uses=count - 1, equivalence='PASS',
                sha256={role: hashlib.sha256(path.read_bytes()).hexdigest()
                        for role, path in (('reference', reference), ('candidate', candidate))}))
            print('PASS:', label, 'independent native equivalence, simulation, strict tools and exact capture inputs', flush=True)
    if not matched:
        raise RuntimeError('unknown focused callback graph case')
    if only_case is not None:
        print('PASS: focused development case; no complete qualification evidence emitted', flush=True)
        return
    profile = profiles[0]
    candidate = H.checked_rtl(root, profile['rtl'])
    case = dict(next(case for case in manifest['configurations'] if (case['width'], case['count']) == (5, 5)),
                candidate_module=profile['module'], candidate_rtl=profile['rtl'])
    reference = H.checked_rtl(root, case['reference_rtl'])
    mutations = [('reordered-subtraction', mutate_pair_order), ('dropped-callback-operation', mutate_dropped_operation),
                 ('changed-capture-binding', mutate_capture_binding)]
    for label, mutate in mutations:
        work = root / 'checks' / label
        work.mkdir(parents=True, exist_ok=True)
        mutated = work / 'candidate-mutated.v'
        changed = mutate(candidate.read_text())
        if changed == candidate.read_text():
            raise RuntimeError('mutation made no actual candidate change: ' + label)
        mutated.write_text(changed)
        top = work / 'miter.v'
        top.write_text(miter(case))
        trace = work / 'counterexample.vcd'
        script = work / 'mutation.ys'
        script.write_text(setup([reference, mutated, top]) +
                          'sat -prove bad 0 -show-inputs -show-outputs -timeout 90 -dump_vcd ' + H.quoted(trace) + '\n')
        proof = H.command(['yosys', '-Q', '-T', '-s', str(script)], work / 'mutation.log')
        if H.COUNTEREXAMPLE not in proof or H.PASS in proof:
            raise RuntimeError('mutation lacks a genuine counterexample: ' + label)
        H.require_counterexample_vcd(trace)
        print('PASS: genuine bad=1 candidate counterexample:', label, flush=True)
    (root / 'evidence.json').write_text(json.dumps(dict(scope=SCOPE,
        finite_matrix_note='Finite specialization evidence is not universal parameter quantification.',
        formal_inputs='independent UInt/SInt/Bits/Bool packed elements and three independently unconstrained runtime captures',
        profiles=profiles, configurations=evidence,
        mutation_controls=[label for label, _ in mutations]), indent=2) + '\n')
    print('PASS: 64 callback graph specializations and three genuine candidate mutation controls', flush=True)


def self_test() -> None:
    assert tree((9, 4, 2), lambda a, b: a - b) == 3
    assert tree((9, 4, 2, 1, 3), lambda a, b: a - b) == 1
    assert expected((3,), 5, 31, 7) == {name: 3 for name in OUTPUTS[:10]}
    result = expected((1, 2, 3, 4, 5), 5, 7, 9)
    assert result['biased'] == (15 + 4 * 7) & 31
    assert result['saturated'] == 15
    assert expected((31, 31), 5, 0, 0)['saturated'] == 31
    sample = 'assign morphhdl_balanced_2_l0_pair_left = left;\nassign morphhdl_balanced_2_l0_pair_right = right;\n'
    assert '= right;' in mutate_pair_order(sample).splitlines()[0]
    assert '= otherBias;' in mutate_capture_binding('assign capture = bias;\n')
    assert mutate_dropped_operation('assign result = ((left + right) ^ left);\n') == 'assign result = ((left + right));\n'
    try:
        mutate_pair_order('module no_anchors; endmodule')
    except RuntimeError:
        pass
    else:
        raise RuntimeError('missing mutation anchor was accepted')
    print('PASS: independent pairing/stimulus models and mutation guards')


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path, nargs='?')
    parser.add_argument('duplicate', type=Path, nargs='?')
    parser.add_argument('--case', dest='only_case', help='Focused case such as singleton_w5_n3; no full evidence')
    parser.add_argument('--self-test', action='store_true')
    args = parser.parse_args()
    if args.self_test:
        self_test()
    elif args.root is None or args.duplicate is None:
        parser.error('provide two independently generated artifact directories')
    else:
        qualify(args.root, args.duplicate, args.only_case)


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, RuntimeError, KeyError) as error:
        print('FAIL: ' + str(error), file=sys.stderr)
        sys.exit(1)
