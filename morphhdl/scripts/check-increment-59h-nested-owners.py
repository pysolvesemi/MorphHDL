#!/usr/bin/env python3
"""Qualify balanced reductions in nested typed branches, loops and hierarchy."""
from __future__ import annotations

import argparse
import functools
import hashlib
import importlib.util
import itertools
import json
import operator
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


H = load('nested_owner_tools', 'check-increment-59b-operator-replay.py')
STIMULUS = load('nested_owner_stimulus', 'check-increment-59b-native-oracle.py')
SCOPE = 'balanced-nested-typed-owners'
MODES = (0, 1, 2)
WIDTHS = (1, 5, 8, 32)
COUNTS = (1, 2, 3, 5, 8, 9, 16, 17)
ORIGINAL_POINTS = ((1, 1, 1), (5, 1, 3), (5, 2, 2), (8, 3, 3), (5, 5, 2), (8, 9, 3), (1, 5, 2))
POINTS = tuple(dict.fromkeys(ORIGINAL_POINTS + tuple(
    (width, count, 3) for width in WIDTHS for count in COUNTS)))
PROFILES = ('conditional', 'loop', 'hierarchy', 'registered-loop', 'hierarchy-loop')
LOOP_PROFILES = ('loop', 'registered-loop', 'hierarchy-loop')
INDUCTIVE_PASS = 'Induction step proven: SUCCESS!'


def shape(case: dict) -> tuple[str, int, int, int, int]:
    return (case['profile'], case['width'], case['count'], case['rows'], case['mode'])


def required_shapes() -> set[tuple[str, int, int, int, int]]:
    return {(profile, width, count, rows if profile in LOOP_PROFILES else 1, mode)
            for profile in PROFILES for width, count, rows in POINTS for mode in MODES}


def validate_matrix(cases: list[dict]) -> None:
    required = required_shapes()
    if len(cases) != len(required) or {shape(case) for case in cases} != required:
        raise RuntimeError('incomplete or duplicated nested branch/loop/hierarchy specialization matrix')


def ports(case: dict) -> tuple[dict[str, int], dict[str, int]]:
    width, count, rows = case['width'], case['count'], case['rows']
    if case['profile'] == 'hierarchy':
        return ({'leftWords': width * count, 'rightWords': width * count},
                {'leftResult': width, 'rightResult': width})
    inputs = {'words': width * count}
    if case['profile'] in LOOP_PROFILES:
        inputs['biases'] = width * rows
    if case['profile'] == 'registered-loop':
        inputs.update(clk=1, reset=1, enable=1)
    return inputs, {'result': width * rows}


def data_ports(case: dict) -> dict[str, int]:
    return {name: bits for name, bits in ports(case)[0].items() if name not in ('clk', 'reset', 'enable')}


def bindings(case: dict, prefix: str) -> str:
    inputs, outputs = ports(case)
    return ', '.join([f'.{name}({name})' for name in inputs] +
                     [f'.{name}({prefix}_{name})' for name in outputs])


def parameters(case: dict) -> str:
    names = ('WIDTH', 'COUNT', 'MODE') + (('ROWS',) if case['profile'] in LOOP_PROFILES else ())
    return ' #(' + ', '.join(f'.{name}({case[name.lower()]})' for name in names) + ')'


def instances(case: dict) -> list[str]:
    _, outputs = ports(case)
    lines = []
    for prefix, role in (('g', 'reference'), ('c', 'candidate')):
        lines += [f'wire [{bits - 1}:0] {prefix}_{name};' for name, bits in outputs.items()]
        parameterization = parameters(case) if role == 'candidate' else ''
        lines += [f'{case[role + "_module"]}{parameterization} {prefix}({bindings(case, prefix)});']
    return lines


def miter(case: dict) -> str:
    inputs, outputs = ports(case)
    declarations = [f'input wire [{bits - 1}:0] {name}' for name, bits in inputs.items()]
    return '\n'.join(['module miter(' + ', '.join(declarations + ['output wire bad']) + ');',
        *instances(case),
        'assign bad = ' + ' | '.join(f'(|(g_{name} ^ c_{name}))' for name in outputs) + ';',
        'endmodule', ''])


def specialized_top(case: dict) -> str:
    inputs, outputs = ports(case)
    declarations = [f'input wire [{bits - 1}:0] {name}' for name, bits in inputs.items()]
    declarations += [f'output wire [{bits - 1}:0] c_{name}' for name, bits in outputs.items()]
    return '\n'.join(['module specialized(' + ', '.join(declarations) + ');',
        f'{case["candidate_module"]}{parameters(case)} dut({bindings(case, "c")});',
        'endmodule', ''])


def reduced(words: tuple[int, ...], width: int, mode: int, profile: str) -> int:
    if mode == 0:
        return sum(words) & ((1 << width) - 1)
    operation = operator.xor if mode == 1 or profile in ('loop', 'registered-loop') else operator.or_
    return functools.reduce(operation, words)


def expected(case: dict, values: dict[str, int]) -> dict[str, int]:
    width, count = case['width'], case['count']
    mask = (1 << width) - 1
    def row(packed: int, index: int) -> int:
        bias = ((values['biases'] >> (index * width)) & mask) if case['profile'] in LOOP_PROFILES else 0
        words = tuple(((packed >> (lane * width)) & mask) ^ bias for lane in range(count))
        return reduced(words, width, case['mode'], case['profile'])
    if case['profile'] == 'hierarchy':
        return {side + 'Result': row(values[side + 'Words'], 0) for side in ('left', 'right')}
    return {'result': sum(row(values['words'], index) << (index * width) for index in range(case['rows']))}


def samples(case: dict) -> list[dict[str, int]]:
    width, count = case['width'], case['count']
    inputs = data_ports(case)
    total = sum(inputs.values())
    # Exhaust the whole independent input domain for the smallest shapes.
    if total <= 8:
        return [dict(zip(inputs, values)) for values in itertools.product(
            *(range(1 << bits) for bits in inputs.values()))]
    mask = (1 << width) - 1
    values = []
    for index, words in enumerate(STIMULUS.samples(width, count)):
        packed = sum(word << (lane * width) for lane, word in enumerate(words))
        if case['profile'] == 'hierarchy':
            other = tuple((words[(lane + 1) % count] ^ ((index + 1) * (lane + 3) * 6)) & mask
                          for lane in range(count))
            values += [dict(leftWords=packed,
                            rightWords=sum(word << (lane * width) for lane, word in enumerate(other)))]
        elif case['profile'] in LOOP_PROFILES:
            # Shared data and every row's bias remain separately varying inputs.
            # Distinct row transformations expose stale finite indices.
            values += [dict(words=packed, biases=sum(
                (((index + 1) * (row + 3) ^ (index >> (row + 1))) & mask) << (row * width)
                for row in range(case['rows'])))]
        else:
            values += [dict(words=packed)]
    # Probe every physical input lane independently, including every row and
    # both child instances, to expose stale indices and cross-instance reads.
    for name, bits in inputs.items():
        for lane in range(bits // width):
            for value in (1, mask, 1 << (width - 1)):
                values += [{key: value << (lane * width) if key == name else 0 for key in inputs}]
    return [dict(items) for items in dict.fromkeys(tuple(value.items()) for value in values)]


def testbench(case: dict, vectors: list[dict[str, int]]) -> str:
    inputs, outputs = ports(case)
    lines = ['`timescale 1ns/1ps', 'module tb;']
    lines += [f'reg [{bits - 1}:0] {name};' for name, bits in inputs.items()]
    lines += instances(case) + ['initial begin']
    if case['profile'] == 'registered-loop':
        delay = (case['count'] - 1).bit_length()
        lines += ['clk=0; reset=1; enable=1; words=0; biases=0; #2; clk=1; #1; clk=0; #1;']
        history = [0] * delay
        def compare(value: int, phase: str, tick: int) -> None:
            for prefix in ('g', 'c'):
                lines.extend([f"if ({prefix}_result !== {outputs['result']}'h{value:x}) begin",
                    f'$display("59H-NESTED-MISMATCH {prefix}_result {phase} tick={tick}"); $finish(1);', 'end'])
        for tick in range(max(160, len(vectors)) + delay):
            values = vectors[tick % len(vectors)]
            value = expected(case, values)['result']
            reset = int(tick in (17, 18, 55, 56, 111, 112))
            enable = int(tick % 5 != 0 and not 30 <= tick < 37 and tick not in (17, 55, 111))
            lines += [f'reset={reset}; enable={enable};']
            lines += [f"{name}={inputs[name]}'h{data:x};" for name, data in values.items()] + ['#2;']
            compare(history[-1] if delay else value, 'before-edge', tick)
            lines += ['clk=1; #1;']
            if delay and enable:
                history = [0] * delay if reset else [value] + history[:-1]
            compare(history[-1] if delay else value, 'after-edge', tick)
            lines += ['clk=0; #1;']
        return '\n'.join(lines + ['$display("59H-NESTED-SIM-PASS"); $finish;', 'end', 'endmodule', ''])
    for index, values in enumerate(vectors):
        lines += [f"{name} = {inputs[name]}'h{value:x};" for name, value in values.items()] + ['#1;']
        for name, value in expected(case, values).items():
            for prefix in ('g', 'c'):
                lines += [f"if ({prefix}_{name} !== {outputs[name]}'h{value:x}) begin",
                    f'$display("59H-NESTED-MISMATCH {prefix}_{name} sample={index}"); $finish(1);', 'end']
    return '\n'.join(lines + ['$display("59H-NESTED-SIM-PASS"); $finish;', 'end', 'endmodule', ''])


def candidate_contract(rtl: str, case: dict) -> None:
    modules = re.findall(r'^module\s+(\w+)\b', rtl, re.MULTILINE)
    if modules.count(case['candidate_module']) != 1 or len(modules) != len(set(modules)):
        raise RuntimeError('missing or duplicated canonical candidate definition')
    expected_modules = 2 if case['profile'] in ('hierarchy', 'hierarchy-loop') else 1
    if len(modules) != expected_modules:
        raise RuntimeError('one definition per logical component is required')
    if expected_modules == 2 and set(modules) != {case['candidate_module'], 'BalancedNestedFormalChild'}:
        raise RuntimeError('hierarchy must retain one shared canonical formal-bound child definition')
    if expected_modules == 2:
        expected_bindings = 2 if case['profile'] == 'hierarchy' else 1
        if len(re.findall(r'\.MODE\s*\(\s*\(\s*MODE\s*\+\s*1\s*\)\s*\)', rtl)) != expected_bindings:
            raise RuntimeError('each child instance must retain its exact MODE+1 formal parameter binding')
    defaults = (('WIDTH', 5), ('COUNT', 1), ('MODE', 0))
    if case['profile'] in LOOP_PROFILES:
        defaults += (('ROWS', 1),)
    for parameter, value in defaults:
        if not re.search(r'parameter\s+(?:integer\s+)?' + parameter + r'\s*=\s*' + str(value) + r'\b', rtl):
            raise RuntimeError('lost singleton-default parameter: ' + parameter)
    if 'genvar' not in rtl or 'begin : tail' not in rtl or '+:' not in rtl:
        raise RuntimeError('nested native pair loops, odd tails or packed slices missing')
    labels = {
        'loop': ('g_row', 'g_row_xor', 'g_row_add'),
        'registered-loop': ('g_registered_row', 'g_row_xor', 'g_row_add'),
        'hierarchy': ('g_if_formal_mode_add_l1_true', 'g_if_formal_count_l1_true', 'g_if_formal_mode_xor_l1_true'),
        'hierarchy-loop': ('g_child_row', 'g_if_formal_mode_add_l1_true', 'g_if_formal_mode_xor_l1_true'),
    }.get(case['profile'], ('g_add', 'g_xor', 'g_or'))
    if any(not re.search(r'\bbegin\s*:\s*' + label + r'(?:_[0-9]+)*\b', rtl) for label in labels):
        raise RuntimeError('typed structural branch or owner label missing')


def mutate_wrong_branch(rtl: str) -> str:
    # Change an actual generate-case selector, leaving every branch body intact.
    pattern = re.compile(r'\bcase\s*\(\s*MODE\s*\)')
    changed, count = pattern.subn('case ((MODE + 1) % 3)', rtl, count=1)
    if count != 1:
        raise RuntimeError('typed MODE case selector missing for branch mutation')
    return changed


def mutate_stale_index(rtl: str) -> str:
    # Resolve the declared finite-loop index, then change only input reads.
    # Names are qualification anchors, never a production ownership recognizer.
    indices = re.findall(r'\bfor\s*\(\s*(\w+)\s*=.*?begin\s*:\s*g_row(?:_[0-9]+)*\b', rtl)
    if len(indices) != 1:
        raise RuntimeError('finite g_row loop index missing or ambiguous')
    index = indices[0]
    pattern = re.compile(r'(?m)^(\s*assign\s+[^;=]+\s*=\s*)([^;]*\bbiases\s*\[[^;]*);')
    changed_count = 0
    def replace(match: re.Match) -> str:
        nonlocal changed_count
        rhs, count = re.subn(r'\b' + re.escape(index) + r'\b', '0', match.group(2))
        changed_count += count
        return match.group(1) + rhs + ';'
    changed = pattern.sub(replace, rtl)
    if not changed_count:
        raise RuntimeError('no bound row index in actual packed input reads')
    return changed


def mutate_cross_instance(rtl: str) -> str:
    pattern = re.compile(r'(?m)^(\s*assign\s+rightResult\s*=\s*)([^;]+);')
    changed, count = pattern.subn(lambda match: match.group(1) + 'leftResult;', rtl, count=1)
    if count != 1 or changed == rtl:
        raise RuntimeError('right child result anchor missing for instance mutation')
    return changed


def setup(paths: list[Path]) -> str:
    return 'read_verilog ' + ' '.join(H.quoted(path) for path in paths) + '\nprep -top miter -flatten\ndffunmap\ncheck -assert\n'


def qualify(root: Path, duplicate: Path, only_case: str | None = None) -> None:
    root, duplicate = root.resolve(), duplicate.resolve()
    for tool in ('iverilog', 'vvp', 'verilator', 'yosys'):
        if shutil.which(tool) is None:
            raise RuntimeError('required tool missing: ' + tool)
    manifest_path = root / 'manifest.json'
    manifest = json.loads(manifest_path.read_text())
    if manifest.get('scope') != SCOPE or manifest.get('candidate_default') != dict(width=5, count=1, rows=1, mode=0):
        raise RuntimeError('incorrect nested-owner evidence scope or singleton defaults')
    if manifest_path.read_bytes() != (duplicate / 'manifest.json').read_bytes():
        raise RuntimeError('nondeterministic nested-owner manifest')
    cases = manifest['configurations']
    validate_matrix(cases)
    for profile in PROFILES:
        artifacts = {(case['candidate_module'], case['candidate_rtl']) for case in cases if case['profile'] == profile}
        if len(artifacts) != 1:
            raise RuntimeError('each profile must use one sole parameterized candidate artifact')
    validated = set()
    for case in cases:
        for role in ('candidate', 'reference'):
            if not H.IDENTIFIER.fullmatch(case[role + '_module']):
                raise RuntimeError('invalid module identifier')
            relative = case[role + '_rtl']
            if relative in validated:
                continue
            artifact = H.checked_rtl(root, relative)
            if artifact.read_bytes() != H.checked_rtl(duplicate, relative).read_bytes():
                raise RuntimeError('nondeterministic ' + role + ' RTL: ' + relative)
            if role == 'candidate':
                candidate_contract(artifact.read_text(), case)
            validated.add(relative)
    evidence = []
    for case in cases:
        profile, width, count, rows, mode = shape(case)
        label = f'{profile}_w{width}_n{count}_r{rows}_m{mode}'
        if only_case is not None and only_case != label:
            continue
        work = root / 'checks' / label
        work.mkdir(parents=True, exist_ok=True)
        candidate, reference = (H.checked_rtl(root, case[role + '_rtl']) for role in ('candidate', 'reference'))
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
        vectors = samples(case)
        bench = work / 'tb.v'
        bench.write_text(testbench(case, vectors))
        executable = work / 'tb.vvp'
        H.command(['iverilog', '-g2001', '-s', 'tb', '-o', str(executable), str(reference), str(candidate), str(bench)], work / 'compile.log')
        simulation = H.command(['vvp', str(executable)], work / 'simulation.log')
        if '59H-NESTED-SIM-PASS' not in simulation or '59H-NESTED-MISMATCH' in simulation:
            raise RuntimeError('independent native/integer simulation failed: ' + label)
        top = work / 'miter.v'
        top.write_text(miter(case))
        if profile == 'registered-loop':
            script = work / 'reset-entry.ys'
            script.write_text(setup([reference, candidate, top]) +
                'sat -seq 2 -set-at 1 reset 1 -set-at 1 enable 1 -prove bad 0 -prove-skip 1 -verify -timeout 90\n')
            proof = H.command(['yosys', '-Q', '-T', '-s', str(script)], work / 'reset-entry.log')
            if H.PASS not in proof or H.COUNTEREXAMPLE in proof:
                raise RuntimeError('reset-entry proof lacks definitive SUCCESS: ' + label)
            script = work / 'induction.ys'
            script.write_text(setup([reference, candidate, top]) +
                'sat -seq 1 -tempinduct -set-init-zero -prove bad 0 -verify -maxsteps 24 -timeout 90\n')
            proof = H.command(['yosys', '-Q', '-T', '-s', str(script)], work / 'induction.log')
            if INDUCTIVE_PASS not in proof:
                raise RuntimeError('unbounded sequential induction lacks definitive SUCCESS: ' + label)
        else:
            script = work / 'equivalence.ys'
            script.write_text(setup([reference, candidate, top]) + 'sat -prove bad 0 -verify -timeout 90\n')
            proof = H.command(['yosys', '-Q', '-T', '-s', str(script)], work / 'equivalence.log')
            if H.PASS not in proof or H.COUNTEREXAMPLE in proof:
                raise RuntimeError('specialization proof lacks definitive SUCCESS: ' + label)
        evidence.append(dict(profile=profile, width=width, count=count, rows=rows, mode=mode,
            simulation_vectors=len(vectors), equivalence='PASS',
            sequential=(dict(latency=(count - 1).bit_length(), reset_entry='PASS', induction='PASS',
                             simulation_cycles=max(160, len(vectors)) + (count - 1).bit_length())
                        if profile == 'registered-loop' else None),
            sha256={role: hashlib.sha256(path.read_bytes()).hexdigest()
                    for role, path in (('reference', reference), ('candidate', candidate))}))
        print('PASS:', label, 'independent native/integer simulation, strict tools and formal equivalence', flush=True)
    if not evidence:
        raise RuntimeError('unknown focused nested-owner case')
    if only_case is not None:
        print('PASS: focused development case; no complete qualification evidence emitted', flush=True)
        return
    mutations = [('wrong-branch-binding', 'conditional', 1, mutate_wrong_branch),
                 ('stale-loop-index', 'loop', 2, mutate_stale_index),
                 ('cross-instance-result', 'hierarchy', 1, mutate_cross_instance)]
    for label, profile, rows, mutate in mutations:
        case = next(case for case in cases if shape(case) == (profile, 5, 5, rows, 0))
        candidate, reference = (H.checked_rtl(root, case[role + '_rtl']) for role in ('candidate', 'reference'))
        work = root / 'checks' / label
        work.mkdir(parents=True, exist_ok=True)
        changed = mutate(candidate.read_text())
        if changed == candidate.read_text():
            raise RuntimeError('mutation made no actual candidate change: ' + label)
        mutated = work / 'candidate-mutated.v'
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
        formal_inputs='Every packed Vec element, row bias, ordinary child-instance input and sequential control independently unconstrained.',
        candidate_default=manifest['candidate_default'], configurations=evidence,
        mutation_controls=[label for label, _, _, _ in mutations]), indent=2) + '\n')
    print(f'PASS: {len(evidence)} nested-owner specializations and three genuine candidate mutation controls', flush=True)


def self_test() -> None:
    required = required_shapes()
    assert len(required) == 516, 'roadmap and original row witnesses must both remain present'
    # Independently pin the roadmap minimum so narrowing the generator and
    # checker together cannot silently turn the old 105-case subset green.
    for profile in PROFILES:
        for mode in (0, 1, 2):
            actual = {(width, count) for p, width, count, _, m in required
                      if p == profile and m == mode}
            assert actual == set(itertools.product((1, 5, 8, 32), (1, 2, 3, 5, 8, 9, 16, 17)))
    keys = ('profile', 'width', 'count', 'rows', 'mode')
    complete = [dict(zip(keys, item)) for item in sorted(required)]
    validate_matrix(complete)
    old = [case for case in complete if shape(case) in {
        (profile, width, count, rows if profile in LOOP_PROFILES else 1, mode)
        for profile in PROFILES for width, count, rows in ORIGINAL_POINTS for mode in MODES}]
    assert len(old) == 105
    # Every single omitted specialization, the old matrix, a duplicate and
    # a count outside the declared finite domain must be rejected.
    negatives = [complete[:index] + complete[index + 1:] for index in range(len(complete))]
    negatives += [old, complete + [complete[0]],
                  [dict(complete[0], count=18)] + complete[1:]]
    for incomplete in negatives:
        try:
            validate_matrix(incomplete)
        except RuntimeError:
            pass
        else:
            raise RuntimeError('incomplete or corrupted roadmap matrix accepted')
    case = dict(profile='loop', width=5, count=3, rows=2, mode=0)
    assert expected(case, {'words': 1 | (2 << 5) | (3 << 10), 'biases': 0 | (4 << 5)}) == {'result': 6 | (18 << 5)}
    hierarchy = dict(profile='hierarchy', width=5, count=2, rows=1, mode=2)
    assert expected(hierarchy, {'leftWords': 1 | (2 << 5), 'rightWords': 4 | (8 << 5)}) == dict(leftResult=3, rightResult=12)
    for profile in PROFILES:
        for mode in MODES:
            assert reduced((23,), 5, mode, profile) == 23
    tiny = dict(profile='hierarchy', width=1, count=2, rows=1, mode=0)
    assert len(samples(tiny)) == 16
    assert samples(case) == samples(case)
    assert mutate_wrong_branch('case (MODE)\n0: begin end\nendcase') != 'case (MODE)\n0: begin end\nendcase'
    loop = 'for (row_index_1_1 = 0; row_index_1_1 < ROWS; row_index_1_1 = row_index_1_1 + 1) begin : g_row_1_1\nassign local = words[((lane * COUNT) * WIDTH) +: (COUNT * WIDTH)] ^ biases[row_index_1_1 * WIDTH +: WIDTH];\nassign result[row_index_1_1 * WIDTH +: WIDTH] = local;\nend\n'
    mutated = mutate_stale_index(loop)
    assert 'biases[0 * WIDTH' in mutated and 'result[row_index_1_1 * WIDTH' in mutated and 'words[((lane * COUNT)' in mutated
    assert mutate_cross_instance('assign rightResult = right_result;\n') == 'assign rightResult = leftResult;\n'
    for mutation in (mutate_wrong_branch, mutate_stale_index, mutate_cross_instance):
        try:
            mutation('module no_anchors; endmodule')
        except RuntimeError:
            pass
        else:
            raise RuntimeError('missing mutation anchor accepted')
    print('PASS: 516 required specializations, 519 matrix rejection controls, independent models, stimuli and mutation guards')


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path, nargs='?')
    parser.add_argument('duplicate', type=Path, nargs='?')
    parser.add_argument('--case', dest='only_case', help='Focused case, e.g. loop_w5_n3_r3_m0; no full evidence')
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
