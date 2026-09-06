#!/usr/bin/env python3
"""Qualify generic scalar widths against independent, unchanged native elaboration."""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import itertools
import json
import math
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


H = load('widening_tools', 'check-increment-59b-operator-replay.py')
STIMULUS = load('widening_stimulus', 'check-increment-59b-native-oracle.py')
OUTPUTS = ('uSum', 'sSum', 'uProduct', 'sProduct', 'uMin', 'uMax', 'sMin', 'sMax',
           'uResize', 'sResize', 'uSymbolicResize', 'sSymbolicResize', 'rSum', 'rSignedSum')
INDUCTIVE_PASS = 'Induction step proven: SUCCESS!'


def expected_width(name: str, width: int, count: int) -> int:
    if name in ('uProduct', 'sProduct'):
        return width * count
    if name in ('uSum', 'sSum', 'rSum', 'rSignedSum'):
        return width + (count - 1).bit_length()
    if name in ('uResize', 'sResize'):
        return width if count == 1 else 5
    if name in ('uSymbolicResize', 'sSymbolicResize'):
        return width + 1
    return width


def bitwidth(case: dict, name: str) -> int:
    return case['native_outputs'][name]['width']


def check_native_shape(case: dict) -> None:
    width, count = case['width'], case['count']
    if set(case['native_outputs']) != set(OUTPUTS):
        raise RuntimeError('native oracle output set differs')
    for name in OUTPUTS:
        expected = dict(width=expected_width(name, width, count),
                        kind='SInt' if name.startswith('s') or name == 'rSignedSum' else 'UInt')
        if case['native_outputs'][name] != expected:
            raise RuntimeError(f'native width/kind contract differs: {name}, {case}')
    # Check the native per-node evidence independently of the candidate. Widths
    # remain lane-specific; a tail does not get padded to its neighbouring group.
    lanes = [width] * count
    stages = []
    level = 0
    while len(lanes) > 1:
        lanes = [sum(lanes[index:index + 2]) for index in range(0, len(lanes), 2)]
        stages.extend(dict(level=level, width=lane) for lane in lanes)
        level += 1
    if case['native_product_stages'] != stages:
        raise RuntimeError('native product tail/stage width evidence differs')


def bindings(prefix: str) -> str:
    inputs = ('.unsignedIn(unsignedIn)', '.signedIn(signedIn)', '.clk(clk)',
              '.reset(reset)', '.enable(enable)')
    return ', '.join((*inputs, *(f'.{name}({prefix}_{name})' for name in OUTPUTS)))


def instances(case: dict) -> list[str]:
    lines = []
    for prefix, role in (('g', 'reference'), ('c', 'candidate')):
        lines += [f'wire [{bitwidth(case, name)-1}:0] {prefix}_{name};' for name in OUTPUTS]
        parameters = '' if role == 'reference' else f' #(.WIDTH({case["width"]}), .COUNT({case["count"]}))'
        lines += [f'{case[role + "_module"]}{parameters} {prefix}({bindings(prefix)});']
    return lines


def miter(case: dict) -> str:
    lines = [f'module miter(input wire clk, reset, enable,',
             f'input wire [{case["width"]*case["count"]-1}:0] unsignedIn, signedIn, output wire bad);']
    lines += instances(case)
    lines += ['assign bad = ' + ' | '.join(f'(|(g_{name} ^ c_{name}))' for name in OUTPUTS) + ';',
              'endmodule', '']
    return '\n'.join(lines)


def specialized_top(case: dict) -> str:
    lines = ['module specialized(input wire clk, reset, enable,',
             f'input wire [{case["width"]*case["count"]-1}:0] unsignedIn, signedIn,']
    lines += [',\n'.join(f'output wire [{bitwidth(case, name)-1}:0] c_{name}' for name in OUTPUTS) + ');']
    lines += [f'{case["candidate_module"]} #(.WIDTH({case["width"]}), .COUNT({case["count"]})) dut({bindings("c")});',
              'endmodule', '']
    return '\n'.join(lines)


def as_signed(word: int, width: int) -> int:
    return word if word < 1 << (width - 1) else word - (1 << width)


def arithmetic(case: dict, unsigned: tuple[int, ...], signed: tuple[int, ...]) -> dict[str, int]:
    signed_values = tuple(as_signed(word, case['width']) for word in signed)
    values = dict(uSum=sum(unsigned), sSum=sum(signed_values),
                  uProduct=math.prod(unsigned), sProduct=math.prod(signed_values),
                  uMin=min(unsigned), uMax=max(unsigned), sMin=min(signed_values), sMax=max(signed_values),
                  uResize=sum(unsigned), sResize=sum(signed_values),
                  uSymbolicResize=sum(unsigned), sSymbolicResize=sum(signed_values))
    return {name: value & ((1 << bitwidth(case, name)) - 1) for name, value in values.items()}


def testbench(case: dict) -> tuple[str, int]:
    width, count = case['width'], case['count']
    packed_width = width * count
    delay = (count - 1).bit_length()
    mask = (1 << width) - 1
    vectors = STIMULUS.samples(width, count)
    corners = [tuple([value] * count) for value in (0, 1, mask, 1 << (width - 1), (1 << (width - 1)) - 1)]
    vectors = corners + vectors
    cycles = max(160, len(vectors)) + delay
    lines = ['`timescale 1ns/1ps', 'module tb;', 'reg clk, reset, enable;',
             f'reg [{packed_width-1}:0] unsignedIn, signedIn;']
    lines += instances(case) + ['initial begin',
        'clk=0; reset=1; enable=1; unsignedIn=0; signedIn=0; #2; clk=1; #1; clk=0; #1;']
    histories = {name: [0] * delay for name in ('rSum', 'rSignedSum')}

    def compare(expected: dict, phase: str, tick: int) -> None:
        for name in OUTPUTS:
            for prefix in ('g', 'c'):
                lines.extend([f"if ({prefix}_{name} !== {bitwidth(case,name)}'h{expected[name]:x}) begin",
                    f'$display("59D-WIDENING-MISMATCH {prefix}_{name} {phase} tick={tick}"); $finish(1);', 'end'])

    for index in range(cycles):
        unsigned = vectors[index % len(vectors)]
        # Different vectors, independent lanes, all signed extremes, and odd
        # tails exercise both native extension and possible cross-Vec misbinding.
        signed = corners[(index + 2) % len(corners)] if index < len(corners) else tuple(
            (word ^ (1 << (width - 1)) ^ (index + 3 * lane)) & mask
            for lane, word in enumerate(reversed(vectors[(index + 7) % len(vectors)])))
        packed_u = sum(word << (i * width) for i, word in enumerate(unsigned))
        packed_s = sum(word << (i * width) for i, word in enumerate(signed))
        expected = arithmetic(case, unsigned, signed)
        reset = int(index in (17, 18, 55, 56, 111, 112))
        enable = int(index % 5 != 0 and not 30 <= index < 37 and index not in (17, 55, 111))
        lines += [f"reset={reset}; enable={enable}; unsignedIn={packed_width}'h{packed_u:x}; signedIn={packed_width}'h{packed_s:x}; #2;"]
        for name, source in (('rSum', 'uSum'), ('rSignedSum', 'sSum')):
            expected[name] = histories[name][-1] if delay else expected[source]
        compare(expected, 'before-edge', index)
        lines += ['clk=1; #1;']
        for name, source in (('rSum', 'uSum'), ('rSignedSum', 'sSum')):
            if delay and enable:
                histories[name] = [0] * delay if reset else [expected[source]] + histories[name][:-1]
            expected[name] = histories[name][-1] if delay else expected[source]
        compare(expected, 'after-edge', index)
        lines += ['clk=0; #1;']
    lines += ['$display("59D-WIDENING-SIM-PASS"); $finish;', 'end', 'endmodule', '']
    return '\n'.join(lines), cycles


def setup(paths: list[Path]) -> str:
    return ('read_verilog ' + ' '.join(H.quoted(path) for path in paths) +
            '\nprep -top miter -flatten\ndffunmap\nopt -full\ncheck -assert\n')


def candidate_contract(text: str, profile: dict) -> None:
    if len(re.findall(r'^module\s+BalancedWidening\b', text, re.MULTILINE)) != 1:
        raise RuntimeError('one parameterized candidate per static profile is required')
    for parameter, value in (('WIDTH', profile['default_width']), ('COUNT', profile['default_count'])):
        if not re.search(r'parameter\s+(?:integer\s+)?' + parameter + r'\s*=\s*' + str(value) + r'\b', text):
            raise RuntimeError('candidate lost its declared independent defaults')
    if 'genvar' not in text or 'begin : tail' not in text:
        raise RuntimeError('singleton/default publication discarded generic native topology')


def check_specialized_ports(case: dict, netlist: dict, role: str) -> None:
    modules = netlist['modules']
    module_name = case[role + '_module']
    if role == 'candidate':
        cells = modules['specialized']['cells']
        matches = [cell for cell in cells.values() if cell['type'] in modules and
                   modules[cell['type']].get('attributes', {}).get('hdlname') == module_name]
        if len(matches) != 1:
            # Yosys versions differ in hdlname attributes; the wrapper contains
            # precisely the native instance, and its actual specialized ports
            # are what matter, never the hand-sized wrapper ports.
            matches = [cell for cell in cells.values() if cell['type'] in modules]
        if len(matches) != 1:
            raise RuntimeError('cannot identify the actual specialized native candidate module')
        module_name = matches[0]['type']
    ports = modules[module_name]['ports']
    for name in OUTPUTS:
        if ports[name]['direction'] != 'output' or len(ports[name]['bits']) != bitwidth(case, name):
            raise RuntimeError(f'{role} actual native port width mismatch for {name}; a miter may not resize it')
    for name in ('unsignedIn', 'signedIn'):
        if ports[name]['direction'] != 'input' or len(ports[name]['bits']) != case['width'] * case['count']:
            raise RuntimeError(f'{role} input geometry mismatch: {name}')


def mutate_output(text: str, output: str, rhs_suffix: str) -> str:
    pattern = re.compile(r'(?m)^(\s*assign\s+' + re.escape(output) + r'\s*=\s*)([^;]+);')
    matches = list(pattern.finditer(text))
    if len(matches) != 1:
        raise RuntimeError('one actual native output driver is required for mutation: ' + output)
    return pattern.sub(lambda match: match.group(1) + '(' + match.group(2) + ') ' + rhs_suffix + ';', text)


def mutate_tail_extension(text: str) -> str:
    # COUNT=5 carries the fifth W-bit signed leaf through levels zero and one,
    # then combines it with a (W+2)-bit sum. Mutate that *actual native operand's*
    # sign replication to zeros, preserving the original low bits and widths.
    # In particular, this is not a changed miter observation or an input value.
    right = 'morphhdl_balanced_2_l2_partial_pair_right'
    replication = re.compile(r'(\{\{[^{}]*\{)(' + re.escape(right) + r'\[[^\]]+\])(\}\},)')
    context = re.compile(r'(?m)^(\s*assign\s+\w+\s*=\s*)\$signed\(' + re.escape(right) + r'\)(\s*;)')
    high_bit = re.compile(r'(?m)^(\s*assign\s+\w*morphhdl_high_bit\w*\s*=\s*)' +
                          re.escape(right) + r'\[[^\n;]*\](\s*;)')
    repeated = list(replication.finditer(text))
    contextual = list(context.finditer(text))
    expanded = list(high_bit.finditer(text))
    if len(repeated) + len(contextual) + len(expanded) != 1:
        raise RuntimeError('one native narrow signed-tail extension is required for mutation')
    if repeated:
        return replication.sub(lambda match: match.group(1) + "1'b0" + match.group(3), text)
    if expanded:
        # Native SInt.expand concatenates its separately retained high-bit
        # extraction with the unchanged original low bits.
        return high_bit.sub(lambda match: match.group(1) + "1'b0" + match.group(2), text)
    # Generic native resize retains its target-sized assignment and requests
    # native signed context. Changing just that context performs zero extension.
    return context.sub(lambda match: match.group(1) + '$unsigned(' + right + ')' + match.group(2), text)


def mutation_specs() -> list[tuple[str, tuple[int, int], object]]:
    return [
        ('dropped-carry-bit', (5, 5), lambda text: mutate_output(text, 'uSum', '& ((1 << WIDTH) - 1)')),
        ('dropped-sign-bit', (5, 5), lambda text: mutate_output(text, 'sSum', '& ((1 << (WIDTH + 2)) - 1)')),
        ('default-frozen-result-width', (8, 5), lambda text: mutate_output(text, 'uProduct', '& ((1 << 5) - 1)')),
        ('incorrect-tail-extension', (5, 5), mutate_tail_extension),
    ]


def qualify(root: Path, duplicate: Path, only_case: str | None = None) -> None:
    root, duplicate = root.resolve(), duplicate.resolve()
    # A failed or focused rerun cannot leave a previous full PASS artifact behind.
    (root / 'evidence.json').unlink(missing_ok=True)
    for tool in ('iverilog', 'vvp', 'verilator', 'yosys'):
        if shutil.which(tool) is None:
            raise RuntimeError('required tool missing: ' + tool)
    manifest_path = root / 'manifest.json'
    manifest = json.loads(manifest_path.read_text())
    if manifest.get('scope') != 'parameterized-native-balanced-widening':
        raise RuntimeError('incorrect widening evidence scope')
    if manifest.get('independent_inputs') != ['unsignedIn', 'signedIn']:
        raise RuntimeError('unsigned and signed vectors must have independently driven ports')
    if manifest_path.read_bytes() != (duplicate / 'manifest.json').read_bytes():
        raise RuntimeError('nondeterministic native evidence manifest')
    profiles = manifest['profiles']
    if [(p['name'], p['default_width'], p['default_count']) for p in profiles] != [
            ('singleton', 5, 1), ('alternate', 8, 5)]:
        raise RuntimeError('both singleton and alternate candidate defaults are mandatory')
    if len({p['candidate_rtl'] for p in profiles}) != len(profiles):
        raise RuntimeError('static profiles must have distinct candidates')
    cases = manifest['configurations']
    if len(cases) != 32 or {(c['width'], c['count']) for c in cases} != set(itertools.product(H.WIDTHS, H.COUNTS)):
        raise RuntimeError('incomplete or duplicate native specialization matrix')
    for case in cases:
        check_native_shape(case)
        reference = H.checked_rtl(root, case['reference_rtl'])
        if reference.read_bytes() != H.checked_rtl(duplicate, case['reference_rtl']).read_bytes():
            raise RuntimeError('nondeterministic independently elaborated native reference')
    mutations = mutation_specs()
    if only_case is None:
        original = H.checked_rtl(root, profiles[0]['candidate_rtl']).read_text()
        for label, _, mutate in mutations:
            if mutate(original) == original:
                raise RuntimeError('actual candidate mutation anchor made no change: ' + label)
    evidence = []
    selected = 0
    for profile in profiles:
        candidate = H.checked_rtl(root, profile['candidate_rtl'])
        candidate_contract(candidate.read_text(), profile)
        if candidate.read_bytes() != H.checked_rtl(duplicate, profile['candidate_rtl']).read_bytes():
            raise RuntimeError('nondeterministic generic candidate RTL')
        for native_case in cases:
            case = dict(native_case, **profile)
            width, count = case['width'], case['count']
            label = f'{profile["name"]}_w{width}_n{count}'
            if only_case is not None and label != only_case:
                continue
            selected += 1
            work = root / 'checks' / label
            work.mkdir(parents=True, exist_ok=True)
            for role in ('reference', 'candidate'):
                if not H.IDENTIFIER.fullmatch(case[role + '_module']):
                    raise RuntimeError('invalid native module identifier')
            reference = H.checked_rtl(root, case['reference_rtl'])
            specialized = work / 'specialized.v'
            specialized.write_text(specialized_top(case))
            for role, module, paths in (
                    ('reference', case['reference_module'], [reference]),
                    ('candidate', 'specialized', [candidate, specialized])):
                H.command(['verilator', '--lint-only', '--language', '1364-2001', '--top-module', module,
                           *map(str, paths)], work / (role + '-lint.log'), timeout=300)
                script = work / (role + '-synthesis.ys')
                port_evidence = work / (role + '-ports.json')
                script.write_text('read_verilog ' + ' '.join(H.quoted(path) for path in paths) +
                    f'\nhierarchy -check -top {module}\nproc\nwrite_json {H.quoted(port_evidence)}\n' +
                    f'synth -top {module}\ncheck -assert\nstat\n')
                H.command(['yosys', '-Q', '-T', '-s', str(script)], work / (role + '-synthesis.log'), timeout=600)
                check_specialized_ports(case, json.loads(port_evidence.read_text()), role)
            bench = work / 'tb.v'
            bench_text, cycles = testbench(case)
            bench.write_text(bench_text)
            executable = work / 'tb.vvp'
            H.command(['iverilog', '-g2001', '-s', 'tb', '-o', str(executable), str(reference), str(candidate), str(bench)], work / 'compile.log')
            simulation = H.command(['vvp', str(executable)], work / 'simulation.log')
            if '59D-WIDENING-SIM-PASS' not in simulation or '59D-WIDENING-MISMATCH' in simulation:
                raise RuntimeError('independent native specialization arithmetic/latency simulation failed: ' + label)
            top = work / 'miter.v'
            top.write_text(miter(case))
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
                raise RuntimeError('unbounded induction lacks definitive SUCCESS: ' + label)
            evidence.append(dict(profile=profile['name'], width=width, count=count,
                native_outputs=case['native_outputs'], native_product_stages=case['native_product_stages'],
                cycles=cycles, latency=(count-1).bit_length(), reset_entry='PASS', induction='PASS',
                sha256={role: hashlib.sha256(path.read_bytes()).hexdigest()
                        for role, path in (('reference', reference), ('candidate', candidate))}))
            print('PASS:', label, 'exact native widths, independent arithmetic/latency, strict tools and equivalence', flush=True)
    if not selected:
        raise RuntimeError('unknown focused widening case')
    if only_case is not None:
        print('PASS: focused case only; full matrix and mutation gates remain required', flush=True)
        return

    profile = profiles[0]
    candidate = H.checked_rtl(root, profile['candidate_rtl'])
    for label, shape, mutate in mutations:
        case = dict(next(c for c in cases if (c['width'], c['count']) == shape), **profile)
        reference = H.checked_rtl(root, case['reference_rtl'])
        work = root / 'checks' / label
        work.mkdir(parents=True, exist_ok=True)
        mutated = work / 'candidate-mutated.v'
        original_text = candidate.read_text()
        mutation_text = mutate(original_text)
        if mutation_text == original_text:
            raise RuntimeError('mutation did not change the actual candidate RTL: ' + label)
        mutated.write_text(mutation_text)
        top = work / 'miter.v'
        top.write_text(miter(case))
        trace = work / 'counterexample.vcd'
        script = work / 'mutation.ys'
        script.write_text(setup([reference, mutated, top]) +
            'sat -seq 8 -set-init-zero -prove bad 0 -show-inputs -show-outputs -timeout 90 -dump_vcd ' + H.quoted(trace) + '\n')
        proof = H.command(['yosys', '-Q', '-T', '-s', str(script)], work / 'mutation.log')
        if H.COUNTEREXAMPLE not in proof or H.PASS in proof:
            raise RuntimeError('actual candidate mutation lacks a genuine counterexample: ' + label)
        H.require_counterexample_vcd(trace)
    (root / 'evidence.json').write_text(json.dumps(dict(
        scope='parameterized-native-balanced-widening', profiles=profiles,
        formal_inputs='independent unsigned and signed vectors, independent lanes',
        formal_scope='64 finite specializations; symbolic-domain validity is a separate typed IR gate',
        reset_model='native clock enable gates synchronous active-high reset',
        configurations=evidence, mutation_controls=[label for label, _, _ in mutations],
        mutation='actual candidate RTL modified; each proof yields a VCD bad=1 counterexample'), indent=2) + '\n')
    print('PASS: 64 widening specializations, exact native widths/kinds, full strict tool matrix and four genuine mutations', flush=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path)
    parser.add_argument('duplicate', type=Path)
    parser.add_argument('--case', dest='only_case', help='Development case, e.g. singleton_w5_n5; emits no complete evidence')
    args = parser.parse_args()
    qualify(args.root, args.duplicate, args.only_case)


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, RuntimeError, KeyError) as error:
        print('FAIL: ' + str(error), file=sys.stderr)
        sys.exit(1)
