#!/usr/bin/env python3
"""Qualify named field and legacy Vec layouts against ordinary native leaves.

The adapter is specified from Scala fixture field declarations, independently
of emitted candidate expressions. Each RTL candidate is generated once with a
singleton default, then specialized by the tools. All independent leaf inputs
remain free in formal proofs and receive different deterministic simulation
stimulus. A parser error, timeout, missing tool or UNKNOWN is always failure.
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
import itertools
import json
import random
import re
import shutil
import subprocess
import sys
from pathlib import Path

WIDTHS = (1, 5, 8, 32)
COUNTS = (1, 2, 3, 5, 8, 9, 16, 17)
PASS = 'SAT proof finished - no model found: SUCCESS!'
COUNTEREXAMPLE = 'SAT proof finished - model found: FAIL!'
INDUCTIVE_PASS = 'Induction step proven: SUCCESS!'
IDENTIFIER = re.compile(r'[A-Za-z_][A-Za-z0-9_$]*\Z')
SIM_PASS = '59C-NAMED-FIELD-SIM-PASS'
SIM_FAIL = '59C-NAMED-FIELD-MISMATCH'


def fields(case):
    return [('red', case['width']), ('green', case['width']),
            ('blue', case['blue_width']), ('metadata_valid', 1)]


def pixel_bits(case):
    return 2 * case['width'] + case['blue_width'] + 1


def vec_fields(case):
    count, inner = case['count'], case['inner']
    if case['kind'] != 'nested':
        return [(path, width, count) for path, width in fields(case)]
    return [('tag', 3, count)] + [
            ('colors_' + path, width, count * inner) for path, width in fields(case)]


def roots(case):
    return {
        'access': (['pixels', 'alternate'], ['result', 'alternateResult', 'restored']),
        'nested': (['pixels'], ['result', 'restored']),
        'storage': (['pixels', 'alternate', 'directPixels'], ['result', 'alternateResult', 'directResult']),
        'streams': (['source_payload', 'flowSource_payload'], ['sink_payload', 'flowSink_payload']),
        'mixed': (['source_payload'], ['sink_payload']),
    }[case['kind']]


def schema(case):
    """Canonical ports, direction and bit width, independently from candidate RTL."""
    inputs, outputs = {}, {}
    input_roots, output_roots = roots(case)
    for names, root_names in ((inputs, input_roots), (outputs, output_roots)):
        for root in root_names:
            for path, width, size in vec_fields(case):
                names[root + '_' + path] = width * size
    if case['kind'] in ('access', 'nested'):
        for root in ('first', 'selected'):
            outputs.update((root + '_' + path, width) for path, width in fields(case))
    if case['kind'] == 'access':
        inputs.update(index=64, writeEnable=1, staticEnable=1,
                      staticGreen=case['width'], staticBlue=case['blue_width'])
        inputs.update(('replacement_' + path, width) for path, width in fields(case))
        outputs.update(coupled=case['width'], signedLess=1,
                       packedBits=pixel_bits(case) * case['count'])
    elif case['kind'] == 'nested':
        inputs.update(outerIndex=5, innerIndex=2)
        outputs['selectedTag'] = 3
        outputs['packedBits'] = (3 + case['inner'] * pixel_bits(case)) * case['count']
    elif case['kind'] == 'storage':
        inputs.update(clk=1, enable=1)
    elif case['kind'] == 'streams':
        inputs.update(source_valid=1, sink_ready=1, flowSource_valid=1)
        outputs.update(source_ready=1, sink_valid=1, flowSink_valid=1)
    else:
        inputs.update(source_valid=case['count'], sink_ready=case['count'])
        outputs.update(source_ready=case['count'], sink_valid=case['count'])
    return inputs, outputs


def slice_(signal, start, width):
    return f'{signal}[{start} +: {width}]'


def canonical_packed_bits(case, root):
    """LSB-first ordinary asBits offsets from declared Bundle and Vec ordering."""
    result = []
    for outer in range(case['count']):
        if case['kind'] == 'nested':
            result.append((f'{root}_tag', outer * 3, 3))
            for inner in range(case['inner']):
                index = outer * case['inner'] + inner
                result.extend((f'{root}_colors_{path}', index * width, width)
                              for path, width in fields(case))
        else:
            result.extend((f'{root}_{path}', outer * width, width)
                          for path, width in fields(case))
    return result


def concatenate(expressions):
    return '{' + ', '.join(reversed(expressions)) + '}'


def mutated_input(case, name, mutation):
    """Alter the candidate's actual input wiring; never alter the comparison."""
    if not mutation:
        return name
    width, count = case['width'], case['count']
    if mutation == 'field-swap' and name in ('pixels_red', 'pixels_green'):
        return 'pixels_green' if name == 'pixels_red' else 'pixels_red'
    if mutation == 'cross-vec-binding' and name == 'pixels_red':
        return 'alternate_red'
    if name == 'pixels_red' and mutation in ('reversed-elements', 'wrong-offset'):
        order = list(reversed(range(count))) if mutation == 'reversed-elements' else [
            (index + 1) % count for index in range(count)]
        return concatenate([slice_(name, index * width, width) for index in order])
    return name


def bindings(case, role, layout, prefix, mutation=None):
    inputs, outputs = schema(case)
    ports, declarations, assignments = [], [], []
    if case['kind'] == 'mixed':
        if layout != 'named':
            raise RuntimeError('mixed-direction Vec has no legacy single-carrier representation')
        if role == 'candidate':
            ports += [f'.{name}({name})' for name in inputs]
            ports += [f'.{name}({prefix}_{name})' for name in outputs]
        else:
            for index in range(case['count']):
                for path, width in fields(case):
                    ports += [f'.source_{index}_payload_{path}({slice_("source_payload_" + path, index * width, width)})',
                              f'.sink_{index}_payload_{path}({slice_(prefix + "_sink_payload_" + path, index * width, width)})']
                ports += [f'.source_{index}_valid({slice_("source_valid", index, 1)})',
                          f'.sink_{index}_ready({slice_("sink_ready", index, 1)})',
                          f'.source_{index}_ready({slice_(prefix + "_source_ready", index, 1)})',
                          f'.sink_{index}_valid({slice_(prefix + "_sink_valid", index, 1)})']
        return ports, declarations, assignments
    vector_inputs, vector_outputs = roots(case)
    vector_names = set()
    for direction, root_names in (('input', vector_inputs), ('output', vector_outputs)):
        for root in root_names:
            for path, width, count in vec_fields(case):
                vector_names.add(root + '_' + path)
            if role == 'candidate' and layout == 'named':
                for path, width, count in vec_fields(case):
                    name = root + '_' + path
                    signal = mutated_input(case, name, mutation) if direction == 'input' else prefix + '_' + name
                    ports.append(f'.{name}({signal})')
            elif role == 'candidate':
                pieces = canonical_packed_bits(case, root)
                total = sum(width for _, _, width in pieces)
                if direction == 'input':
                    # Legacy mutations are not used: positive comparison retains
                    # exactly the pre-59c packed public layout.
                    expr = concatenate([slice_(name, low, width) for name, low, width in pieces])
                    ports.append(f'.{root}({expr})')
                else:
                    signal = prefix + '_packed_' + root
                    declarations.append(f'wire [{total - 1}:0] {signal};')
                    ports.append(f'.{root}({signal})')
                    low = 0
                    for name, start, width in pieces:
                        assignments.append(f'assign {slice_(prefix + "_" + name, start, width)} = {slice_(signal, low, width)};')
                        low += width
            else:
                for outer in range(case['count']):
                    if case['kind'] == 'nested':
                        ports.append(f'.{root}_{outer}_tag({slice_(("" if direction == "input" else prefix + "_") + root + "_tag", outer * 3, 3)})')
                        for inner in range(case['inner']):
                            index = outer * case['inner'] + inner
                            for path, width in fields(case):
                                signal = ('' if direction == 'input' else prefix + '_') + root + '_colors_' + path
                                ports.append(f'.{root}_{outer}_colors_{inner}_{path}({slice_(signal, index * width, width)})')
                    else:
                        for path, width in fields(case):
                            signal = ('' if direction == 'input' else prefix + '_') + root + '_' + path
                            ports.append(f'.{root}_{outer}_{path}({slice_(signal, outer * width, width)})')
    for name in inputs:
        if name not in vector_names:
            ports.append(f'.{name}({name})')
    for name in outputs:
        if name not in vector_names:
            ports.append(f'.{name}({prefix}_{name})')
    return ports, declarations, assignments


def candidate_module(case):
    return 'NamedFieldVec' + ('MixedDirections' if case['kind'] == 'mixed' else case['kind'].title())


def instance(case, role, layout, prefix, mutation=None):
    _, outputs = schema(case)
    ports, declarations, assignments = bindings(case, role, layout, prefix, mutation)
    lines = [f'wire [{width - 1}:0] {prefix}_{name};' for name, width in outputs.items()]
    lines += declarations
    module = case['reference_module'] if role == 'reference' else candidate_module(case)
    parameters = ''
    if role == 'candidate':
        parameters = f' #(.WIDTH({case["width"]}), .BLUE_WIDTH({case["blue_width"]}), .COUNT({case["count"]})'
        if case['kind'] == 'nested':
            parameters += f', .INNER({case["inner"]})'
        parameters += ')'
    lines += [f'{module}{parameters} {prefix}(' + ',\n  '.join(ports) + ');']
    return lines + assignments


def miter(case, layout, mutation=None):
    inputs, outputs = schema(case)
    lines = ['module miter(' + ',\n'.join(f'input wire [{width - 1}:0] {name}' for name, width in inputs.items()) + ',\noutput wire bad);']
    lines += instance(case, 'reference', layout, 'g')
    lines += instance(case, 'candidate', layout, 'c', mutation)
    lines += ['assign bad = ' + ' | '.join(f'(|(g_{name} ^ c_{name}))' for name in outputs) + ';', 'endmodule', '']
    return '\n'.join(lines)


def specialize(case, layout):
    inputs, outputs = schema(case)
    lines = ['module specialized(' + ',\n'.join(
        [f'input wire [{width - 1}:0] {name}' for name, width in inputs.items()] +
        [f'output wire [{width - 1}:0] out_{name}' for name, width in outputs.items()]) + ');']
    lines += instance(case, 'candidate', layout, 'c')
    lines += [f'assign out_{name} = c_{name};' for name in outputs] + ['endmodule', '']
    return '\n'.join(lines)


def word(value, index, width):
    return (value >> (index * width)) & ((1 << width) - 1)


def expected(case, values):
    result = {}
    count, inner = case['count'], case['inner']
    if case['kind'] == 'access':
        selected = min(values['index'], count - 1)
        for path, width in fields(case):
            source = values['pixels_' + path]
            updated = source
            if values['staticEnable'] and path in ('green', 'blue'):
                static = values['staticGreen' if path == 'green' else 'staticBlue']
                updated = (updated & ~((1 << width) - 1)) | static
            if values['writeEnable'] and values['index'] < count:
                offset = values['index'] * width
                updated = (updated & ~(((1 << width) - 1) << offset)) | (values['replacement_' + path] << offset)
            result['result_' + path] = updated
            result['alternateResult_' + path] = values['alternate_' + path]
            result['restored_' + path] = source
            result['first_' + path] = word(updated, 0, width)
            result['selected_' + path] = word(updated, selected, width)
        result['coupled'] = result['selected_red'] ^ word(values['alternate_green'], selected, case['width'])
        width = case['blue_width']
        signed = lambda value: value - (1 << width) if value & (1 << (width - 1)) else value
        result['signedLess'] = int(signed(result['selected_blue']) < signed(values['replacement_blue']))
        packed, offset = 0, 0
        for name, start, width in canonical_packed_bits(case, 'pixels'):
            packed |= ((values[name] >> start) & ((1 << width) - 1)) << offset
            offset += width
        result['packedBits'] = packed
    elif case['kind'] == 'nested':
        outer = min(values['outerIndex'], count - 1)
        chosen = outer * inner + min(values['innerIndex'], inner - 1)
        for path, width, size in vec_fields(case):
            result['result_' + path] = values['pixels_' + path]
            result['restored_' + path] = values['pixels_' + path]
        for path, width in fields(case):
            source = values['pixels_colors_' + path]
            result['first_' + path] = word(source, 0, width)
            result['selected_' + path] = word(source, chosen, width)
        result['selectedTag'] = word(values['pixels_tag'], outer, 3)
        packed, offset = 0, 0
        for name, start, width in canonical_packed_bits(case, 'pixels'):
            packed |= ((values[name] >> start) & ((1 << width) - 1)) << offset
            offset += width
        result['packedBits'] = packed
    elif case['kind'] == 'storage':
        for path, width in fields(case):
            result['result_' + path] = values['pixels_' + path]
            result['alternateResult_' + path] = values['alternate_' + path]
            result['directResult_' + path] = values['directPixels_' + path]
    elif case['kind'] == 'streams':
        for path, width in fields(case):
            result['sink_payload_' + path] = values['source_payload_' + path]
            result['flowSink_payload_' + path] = values['flowSource_payload_' + path]
        result.update(source_ready=values['sink_ready'], sink_valid=values['source_valid'],
                      flowSink_valid=values['flowSource_valid'])
    else:
        for path, width in fields(case):
            result['sink_payload_' + path] = values['source_payload_' + path]
        result.update(source_ready=values['sink_ready'], sink_valid=values['source_valid'])
    assert set(result) == set(schema(case)[1])
    return result


def samples(case):
    inputs, _ = schema(case)
    seed = case['width'] * 1000000 + case['blue_width'] * 10000 + case['count'] * 10 + case['inner']
    rng = random.Random(seed)
    stimuli = []
    for index in range(96):
        values = {name: rng.getrandbits(width) for name, width in inputs.items()}
        if case['kind'] == 'access':
            legal = index % case['count']
            values['index'] = [0, case['count'] - 1, case['count'], 31, legal,
                               (1 << 32) | legal, (1 << 63) | legal][index % 7]
            values['writeEnable'] = index % 2
        elif case['kind'] == 'nested':
            values['outerIndex'] = [0, case['count'] - 1, case['count'], 31, index % case['count']][index % 5]
            values['innerIndex'] = [0, case['inner'] - 1, case['inner'], 3][index % 4]
        elif case['kind'] == 'storage':
            values['clk'] = 0
            values['enable'] = 1 if index == 0 else int(index % 5 != 0 and not 30 <= index < 38)
        stimuli.append(values)
    return stimuli


def testbench(case, layout):
    inputs, outputs = schema(case)
    lines = ['`timescale 1ns/1ps', 'module tb;']
    lines += [f'reg [{width - 1}:0] {name};' for name, width in inputs.items()]
    lines += instance(case, 'reference', layout, 'g') + instance(case, 'candidate', layout, 'c')
    lines += ['initial begin']
    previous = None
    for index, values in enumerate(samples(case)):
        lines += [f"{name} = {inputs[name]}'h{value:x};" for name, value in values.items()] + ['#1;']
        if case['kind'] == 'storage':
            if previous is not None:
                for root in ('result', 'directResult'):
                    for path, width in fields(case):
                        name = root + '_' + path
                        for role in ('g', 'c'):
                            lines += [f"if ({role}_{name} !== {outputs[name]}'h{previous[name]:x}) begin",
                                      f'$display("{SIM_FAIL} {role}_{name} before-edge sample={index}"); $finish(1); end']
            lines += ['clk=1; #1;']
        observed = expected(case, values)
        if case['kind'] == 'storage' and not values['enable']:
            assert previous is not None, 'first simulation edge must load the uninitialized direct register'
            for path, width in fields(case):
                observed['directResult_' + path] = previous['directResult_' + path]
        for name, value in observed.items():
            for role in ('g', 'c'):
                lines += [f"if ({role}_{name} !== {outputs[name]}'h{value:x}) begin",
                          f'$display("{SIM_FAIL} {role}_{name} sample={index}"); $finish(1); end']
        if case['kind'] == 'storage':
            lines += ['clk=0; #1;']
            previous = observed
    lines += [f'$display("{SIM_PASS}"); $finish;', 'end', 'endmodule', '']
    return '\n'.join(lines)


def command(argv, log, timeout=180):
    try:
        process = subprocess.run(argv, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                 text=True, timeout=timeout, check=False)
    except (OSError, subprocess.TimeoutExpired) as error:
        log.write_text(str(error) + '\n')
        raise RuntimeError(f'tool did not complete; see {log}') from error
    log.write_text(process.stdout)
    if process.returncode:
        raise RuntimeError(f'tool failed ({process.returncode}); see {log}')
    return process.stdout


def checked_rtl(root, relative):
    result = (root / relative).resolve()
    if root.resolve() not in result.parents or not result.is_file():
        raise RuntimeError('missing or invalid RTL artifact: ' + relative)
    return result


def quoted(path):
    return '"' + str(path).replace('\\', '\\\\').replace('"', '\\"') + '"'


def check_module_ports(case, native):
    """Independently check exact native leaf names and widths before adapting."""
    # Native output ranges are constants. No resizing is allowed in adapters.
    module = re.search(r'^module\s+' + re.escape(case['reference_module']) + r'\b[\s\S]*?^endmodule\b', native, re.M)
    if module is None:
        raise RuntimeError('native top module is missing')
    native = module.group(0)
    declarations = {}
    pattern = re.compile(r'^\s*(input|output)\s+(?:wire|reg)\s+(?:signed\s+)?(?:\[(\d+):0\]\s+)?(\w+)\s*[,;]?\s*$', re.M)
    for match in pattern.finditer(native):
        declarations[match.group(3)] = (match.group(1), int(match.group(2)) + 1 if match.group(2) else 1)
    expected_ports = {}
    inputs, outputs = schema(case)
    ports, _, _ = bindings(case, 'reference', 'named', 'g')
    for port in ports:
        name, signal = re.fullmatch(r'\.(\w+)\((.*)\)', port).groups()
        direction = 'output' if signal.startswith('g_') else 'input'
        if '+:' in signal:
            width = int(re.search(r'\+: (\d+)\]', signal).group(1))
        else:
            width = (outputs if direction == 'output' else inputs)[signal[2:] if direction == 'output' else signal]
        expected_ports[name] = (direction, width)
    if declarations != expected_ports:
        missing = set(expected_ports) - set(declarations)
        extra = set(declarations) - set(expected_ports)
        wrong = {key: (expected_ports[key], declarations[key]) for key in expected_ports.keys() & declarations.keys() if expected_ports[key] != declarations[key]}
        raise RuntimeError(f'native leaf shape mismatch: missing={missing}, extra={extra}, wrong={wrong}')


def check_candidate(case, text, layout):
    module = candidate_module(case)
    if len(re.findall(r'^module\s+' + module + r'\b', text, re.M)) != 1:
        raise RuntimeError('expected one candidate definition per declared topology')
    top = re.search(r'^module\s+' + re.escape(module) + r'\b[\s\S]*?^endmodule\b', text, re.M)
    if top is None:
        raise RuntimeError('candidate top module is missing')
    # Child modules carry their own defaults. They must never satisfy the
    # singleton-default or port contract of the independently overridden top.
    text = top.group(0)
    for name, value in [('WIDTH', 5), ('BLUE_WIDTH', 3), ('COUNT', 1)] + ([('INNER', 1)] if case['kind'] == 'nested' else []):
        if not re.search(r'parameter\s+(?:integer\s+)?' + name + r'\s*=\s*' + str(value) + r'\b', text):
            raise RuntimeError(f'candidate lost singleton default or symbolic root {name}')
    if layout == 'named':
        declarations = {}
        for direction, root_names in zip(('input', 'output'), roots(case)):
            for root in root_names:
                for path, width, size in vec_fields(case):
                    declarations[root + '_' + path] = (direction, path)
        if case['kind'] == 'mixed':
            declarations.update(source_valid=('input', 'valid'), sink_ready=('input', 'ready'),
                                source_ready=('output', 'ready'), sink_valid=('output', 'valid'))
        for name, (direction, path) in declarations.items():
            # A carrier containing signed elements is still unsigned. Checking
            # this across every direction/root closes a gap bitwise proof alone
            # cannot detect at an external module interface.
            match = re.search(r'^\s*' + direction + r'\s+(?:wire|reg)\s+\[([^\]]+)\]\s+' + re.escape(name) + r'\s*[,;]?\s*$', text, re.M)
            if match is None or 'COUNT' not in match.group(1):
                raise RuntimeError('missing unsigned symbolic named field carrier: ' + name)
            if case['kind'] == 'nested' and path != 'tag' and 'INNER' not in match.group(1):
                raise RuntimeError('lost independent nested count: ' + name)
        if re.search(r'^\s*(?:input|output)\b[^\n]*\bpixels_\d+_', text, re.M):
            raise RuntimeError('parameter-varying numbered candidate ports')
    else:
        for direction, root_names in zip(('input', 'output'), roots(case)):
            for root in root_names:
                match = re.search(r'^\s*' + direction + r'\s+(?:wire|reg)\s+\[([^\]]+)\]\s+' + re.escape(root) + r'\s*[,;]?\s*$', text, re.M)
                if match is None or 'COUNT' not in match.group(1):
                    raise RuntimeError('legacy packed interface lost its unsigned symbolic carrier: ' + root)
                if case['kind'] == 'nested' and 'INNER' not in match.group(1):
                    raise RuntimeError('legacy packed interface lost the nested dimension: ' + root)


def proof_setup(paths):
    return 'read_verilog ' + ' '.join(quoted(path) for path in paths) + '\nprep -top miter -flatten\ndffunmap\nmemory_map\nopt_clean\ncheck -assert\n'


def qualify_case(case, root, replay, layouts):
    """Run unchanged strict checks in this case's independent artifact paths."""
    evidence = []
    label = stem(case)
    reference = checked_rtl(root, case['reference_rtl'])
    if reference.read_bytes() != checked_rtl(replay, case['reference_rtl']).read_bytes():
        raise RuntimeError('nondeterministic independent native reference: ' + label)
    check_module_ports(case, reference.read_text())
    for layout in layouts:
        if case['kind'] == 'mixed' and layout == 'legacy':
            continue
        candidate_relative = ('candidate' if layout == 'named' else 'legacy') + '/' + case['kind'] + '/' + candidate_module(case) + '.v'
        candidate = checked_rtl(root, candidate_relative)
        candidate_bytes = candidate.read_bytes()
        if candidate_bytes != checked_rtl(replay, candidate_relative).read_bytes():
            raise RuntimeError('nondeterministic candidate: ' + candidate_relative)
        check_candidate(case, candidate.read_text(), layout)
        work = root / 'checks' / layout / label
        work.mkdir(parents=True, exist_ok=True)
        special = work / 'specialized.v'
        special.write_text(specialize(case, layout))
        for role, module, paths in (
                ('candidate', 'specialized', [candidate, special]),
                ('reference', case['reference_module'], [reference])):
            command(['verilator', '--lint-only', '--language', '1364-2001', '--top-module', module, *map(str, paths)], work / (role + '-lint.log'))
            synthesis = work / (role + '-synthesis.ys')
            synthesis.write_text('read_verilog ' + ' '.join(quoted(path) for path in paths) +
                                 f'\nhierarchy -check -top {module}\nsynth -top {module}\ncheck -assert\nstat\n')
            command(['yosys', '-Q', '-T', '-s', str(synthesis)], work / (role + '-synthesis.log'))
        bench, executable = work / 'tb.v', work / 'tb.vvp'
        bench.write_text(testbench(case, layout))
        command(['iverilog', '-g2001', '-s', 'tb', '-o', str(executable), str(reference), str(candidate), str(bench)], work / 'parse.log')
        simulation = command(['vvp', str(executable)], work / 'simulation.log')
        if SIM_PASS not in simulation or SIM_FAIL in simulation:
            raise RuntimeError('simulation did not produce definitive PASS: ' + label)
        top, script = work / 'miter.v', work / 'proof.ys'
        top.write_text(miter(case, layout))
        temporal = '-seq 2 -set-at 1 enable 1 -prove-skip 1 ' if case['kind'] == 'storage' else ''
        script.write_text(proof_setup([reference, candidate, top]) +
                          f'sat {temporal}-prove bad 0 -verify -timeout 120 -show-inputs -show-outputs\n')
        proof = command(['yosys', '-Q', '-T', '-s', str(script)], work / 'proof.log')
        if PASS not in proof or COUNTEREXAMPLE in proof:
            raise RuntimeError('formal did not produce definitive PASS: ' + label)
        if case['kind'] == 'storage':
            # The previous proof shows convergence after one load from
            # arbitrary independent register states. Induction additionally
            # establishes unbounded equivalence from a shared initial state.
            induction = work / 'induction.ys'
            induction.write_text(proof_setup([reference, candidate, top]) +
                                 'sat -seq 1 -tempinduct -set-init-zero -prove bad 0 -verify -maxsteps 8 -timeout 120\n')
            inductive = command(['yosys', '-Q', '-T', '-s', str(induction)], work / 'induction.log')
            if INDUCTIVE_PASS not in inductive or COUNTEREXAMPLE in inductive:
                raise RuntimeError('register/hierarchy proof lacks definitive induction SUCCESS: ' + label)
        if candidate_bytes != candidate.read_bytes():
            raise RuntimeError('tool specialization rewrote the shared candidate')
        evidence.append(dict(case=label, layout=layout, samples=len(samples(case)), proof='PASS',
                             candidate_sha256=hashlib.sha256(candidate_bytes).hexdigest(),
                             reference_sha256=hashlib.sha256(reference.read_bytes()).hexdigest()))
    return evidence


def ordered_results(function, items, jobs):
    """Bound active workers, surface failures promptly, yield in input order."""
    if not 1 <= jobs <= 8:
        raise RuntimeError('jobs must be between 1 and 8')
    if jobs == 1:
        yield from map(function, items)
        return
    workers = ThreadPoolExecutor(max_workers=jobs)
    try:
        futures = {workers.submit(function, item): index for index, item in enumerate(items)}
        ready, next_index = {}, 0
        for future in as_completed(futures):
            ready[futures[future]] = future.result()
            while next_index in ready:
                yield ready.pop(next_index)
                next_index += 1
        if next_index != len(futures):
            raise RuntimeError('parallel scheduler lost a case result')
    finally:
        # A failed worker retains its strict error. Pending tasks are cancelled;
        # already running tools finish under their unchanged per-tool timeouts.
        workers.shutdown(wait=True, cancel_futures=True)


def check_case_evidence(case, layouts, records):
    required = [(stem(case), layout) for layout in layouts
                if not (case['kind'] == 'mixed' and layout == 'legacy')]
    if not isinstance(records, list) or any(not isinstance(record, dict) for record in records):
        raise RuntimeError('missing case evidence: ' + stem(case))
    actual = [(record.get('case'), record.get('layout')) for record in records]
    if actual != required or any(record.get('proof') != 'PASS' for record in records):
        raise RuntimeError('incomplete or reordered case evidence: ' + stem(case))


def qualify(root, replay, only=None, layout_filter=None, jobs=1):
    root, replay = root.resolve(), replay.resolve()
    evidence_path = root / ('evidence.json' if only is None and layout_filter is None else 'focused-evidence.json')
    # A failed rerun must never leave a prior success record available to CI.
    evidence_path.unlink(missing_ok=True)
    if not 1 <= jobs <= 8:
        raise RuntimeError('jobs must be between 1 and 8')
    for tool in ('yosys', 'iverilog', 'vvp', 'verilator'):
        if shutil.which(tool) is None:
            raise RuntimeError('required tool missing: ' + tool)
    manifest_path = root / 'manifest.json'
    manifest = json.loads(manifest_path.read_text())
    if manifest.get('scope') != 'named-field-vec-native-equivalence':
        raise RuntimeError('incorrect qualification scope')
    if manifest.get('candidate_default') != dict(width=5, blue_width=3, count=1, inner=1):
        raise RuntimeError('candidate default contract changed')
    if manifest.get('dimension_order') != 'outer-major-inner-minor-element-zero-low':
        raise RuntimeError('dimension ordering contract changed')
    if manifest_path.read_bytes() != (replay / 'manifest.json').read_bytes():
        raise RuntimeError('nondeterministic manifest')
    cases = manifest['configurations']
    access = [case for case in cases if case['kind'] == 'access']
    nested = [case for case in cases if case['kind'] == 'nested']
    if len(access) != 32 or {(c['width'], c['blue_width'], c['count'], c['inner']) for c in access} != {
            (width, 7 if width == 32 else width + 1, count, 1) for width, count in itertools.product(WIDTHS, COUNTS)}:
        raise RuntimeError('incomplete common WIDTH/COUNT matrix')
    if len(nested) != 36 or {(c['width'], c['blue_width'], c['count'], c['inner']) for c in nested} != {
            (w, b, n, i) for (w, b), n, i in itertools.product(((1, 5), (5, 3), (8, 1), (32, 7)), (1, 3, 5), (1, 2, 3))}:
        raise RuntimeError('incomplete independent nested dimension matrix')
    for kind in ('storage', 'streams', 'mixed'):
        structural = [case for case in cases if case['kind'] == kind]
        if len(structural) != 16 or {(c['width'], c['blue_width'], c['count'], c['inner']) for c in structural} != {
                (w, b, n, 1) for (w, b), n in itertools.product(((1, 5), (5, 3), (8, 1), (32, 7)), (1, 3, 5, 17))}:
            raise RuntimeError('incomplete hierarchy/storage or Stream/Flow matrix: ' + kind)
    if len(cases) != 116:
        raise RuntimeError('unexpected extra or missing topology cases')
    selected = cases if only is None else [case for case in cases if stem(case) == only or case['kind'] == only]
    if not selected:
        raise RuntimeError('unknown selected case')
    evidence = []
    layouts = ('named', 'legacy') if layout_filter is None else (layout_filter,)
    def run_case(case):
        records = qualify_case(case, root, replay, layouts)
        check_case_evidence(case, layouts, records)
        return records

    # Tool outputs belong to checks/<layout>/<case>; shared RTL is read only.
    # Results and the final ledger retain manifest order at every worker count.
    for records in ordered_results(run_case, selected, jobs):
        evidence.extend(records)
        for record in records:
            print(f'PASS {record["layout"]} {record["case"]}: strict tools, native equivalence, independent simulation', flush=True)
    mutations = []
    if only is None and layout_filter != 'legacy':
        case = next(c for c in access if c['width'] == 5 and c['count'] == 3)
        candidate = checked_rtl(root, 'candidate/access/NamedFieldVecAccess.v')
        reference = checked_rtl(root, case['reference_rtl'])
        for mutation in ('field-swap', 'reversed-elements', 'wrong-offset', 'cross-vec-binding'):
            work = root / 'checks' / 'mutations' / mutation
            work.mkdir(parents=True, exist_ok=True)
            top, script, trace = work / 'miter.v', work / 'mutation.ys', work / 'counterexample.vcd'
            top.write_text(miter(case, 'named', mutation))
            script.write_text(proof_setup([reference, candidate, top]) +
                              f'sat -prove bad 0 -timeout 120 -show-inputs -show-outputs -dump_vcd {quoted(trace)}\n')
            proof = command(['yosys', '-Q', '-T', '-s', str(script)], work / 'mutation.log')
            if COUNTEREXAMPLE not in proof or PASS in proof or not trace.is_file() or trace.stat().st_size == 0:
                raise RuntimeError('mutation lacks an actual counterexample trace: ' + mutation)
            mutations.append(dict(mutation=mutation, location='candidate-input-wiring', status='counterexample',
                                  trace=str(trace.relative_to(root))))
        case = next(c for c in cases if c['kind'] == 'storage' and c['width'] == 5 and c['count'] == 3)
        candidate = checked_rtl(root, 'candidate/storage/NamedFieldVecStorage.v')
        reference = checked_rtl(root, case['reference_rtl'])
        work = root / 'checks' / 'mutations' / 'parent-child-binding'
        work.mkdir(parents=True, exist_ok=True)
        text = candidate.read_text()
        connections = list(re.finditer(r'\.pixels_red\s*\(\s*([^()]+?)\s*\)', text))
        if len(connections) != 3 or len({connection.group(1) for connection in connections}) != 3:
            raise RuntimeError('expected three independent named child bindings for hierarchy mutation')
        changed = text[:connections[0].start(1)] + connections[1].group(1) + text[connections[0].end(1):]
        mutant = work / 'mutated-candidate.v'
        mutant.write_text(changed)
        top, script, trace = work / 'miter.v', work / 'mutation.ys', work / 'counterexample.vcd'
        top.write_text(miter(case, 'named'))
        script.write_text(proof_setup([reference, mutant, top]) +
                          f'sat -seq 2 -set-at 1 enable 1 -prove-skip 1 -prove bad 0 -timeout 120 -show-inputs -show-outputs -dump_vcd {quoted(trace)}\n')
        proof = command(['yosys', '-Q', '-T', '-s', str(script)], work / 'mutation.log')
        if COUNTEREXAMPLE not in proof or PASS in proof or not trace.is_file() or trace.stat().st_size == 0:
            raise RuntimeError('parent/child mutation lacks an actual counterexample trace')
        mutations.append(dict(mutation='parent-child-binding', location='generated-candidate-child-connection',
                              status='counterexample', trace=str(trace.relative_to(root))))
    status = 'focused-qualification' if only or layout_filter else 'finite-matrix-qualified'
    if not evidence:
        raise RuntimeError('no supported specialization was selected; mixed directions require the named layout')
    record = dict(status=status, universal_parameter_proof=False, cases=evidence, mutations=mutations)
    evidence_path.write_text(json.dumps(record, indent=2) + '\n')
    print(f'PASS: {len(evidence)} layout specializations, {len(mutations)} genuine input-wiring counterexamples')


def stem(case):
    return f'{case["kind"]}_w{case["width"]}_b{case["blue_width"]}_n{case["count"]}_i{case["inner"]}'


def scheduler_self_test():
    from threading import Event, Lock

    lock, second_done = Lock(), Event()
    seen, completed = [], []
    active, peak = 0, 0

    def out_of_order(value):
        nonlocal active, peak
        with lock:
            seen.append(value)
            active += 1
            peak = max(peak, active)
        if value == 0:
            if not second_done.wait(timeout=5):
                raise AssertionError('parallel worker did not start')
        with lock:
            completed.append(value)
            active -= 1
        if value == 1:
            second_done.set()
        return value * 3

    assert list(ordered_results(out_of_order, range(6), 2)) == [value * 3 for value in range(6)]
    assert sorted(seen) == list(range(6)) and peak == 2
    assert completed.index(1) < completed.index(0), 'test must finish out of order'
    assert list(ordered_results(lambda value: value, range(6), 1)) == list(range(6))
    for jobs in (0, -1, 9):
        try:
            list(ordered_results(lambda value: value, [0], jobs))
        except RuntimeError:
            pass
        else:
            raise AssertionError('scheduler accepted invalid worker bound')

    def broken_worker(value):
        if value == 1:
            raise RuntimeError('genuine worker exception')
        return value

    try:
        list(ordered_results(broken_worker, range(6), 2))
    except RuntimeError as error:
        assert str(error) == 'genuine worker exception'
    else:
        raise AssertionError('scheduler swallowed a worker exception')

    case = dict(kind='access', width=5, blue_width=3, count=3, inner=1)
    records = [dict(case=stem(case), layout=layout, proof='PASS') for layout in ('named', 'legacy')]
    check_case_evidence(case, ('named', 'legacy'), records)
    for broken in (None, [], records[:1], records + records[:1], list(reversed(records)),
                   [dict(records[0], proof='UNKNOWN'), records[1]]):
        try:
            check_case_evidence(case, ('named', 'legacy'), broken)
        except RuntimeError:
            pass
        else:
            raise AssertionError('scheduler accepted missing, duplicate, unordered or failed evidence')

    from tempfile import TemporaryDirectory
    with TemporaryDirectory(prefix='59c-stale-evidence-') as temporary:
        root = Path(temporary)
        for only, filename in ((None, 'evidence.json'), ('access', 'focused-evidence.json')):
            stale = root / filename
            stale.write_text('{"status":"stale-success"}')
            try:
                qualify(root, root, only=only, jobs=0)
            except RuntimeError:
                pass
            else:
                raise AssertionError('invalid worker bound did not fail')
            assert not stale.exists(), 'failed rerun retained stale success evidence'


def self_test():
    scheduler_self_test()
    for kind, width, blue, count, inner in [('access', 5, 3, 3, 1), ('nested', 5, 3, 3, 2), ('nested', 1, 5, 1, 3),
                                           ('storage', 5, 3, 3, 1), ('streams', 8, 1, 3, 1), ('mixed', 5, 3, 3, 1)]:
        case = dict(kind=kind, width=width, blue_width=blue, count=count, inner=inner, reference_module='Reference')
        inputs, outputs = schema(case)
        for values in samples(case):
            assert all(0 <= value < 1 << inputs[name] for name, value in values.items())
            assert all(0 <= value < 1 << outputs[name] for name, value in expected(case, values).items())
        pieces = canonical_packed_bits(case, 'pixels')
        assert sum(width for _, _, width in pieces) == count * (3 + inner * pixel_bits(case) if kind == 'nested' else pixel_bits(case))
        for layout in ('named', 'legacy'):
            if kind == 'mixed' and layout == 'legacy':
                continue
            assert miter(case, layout).count('assign bad =') == 1
            assert SIM_PASS in testbench(case, layout)
            assert 'module specialized(' in specialize(case, layout)
        if kind == 'access':
            high = dict(samples(case)[0])
            high.update(index=(1 << 32), writeEnable=1, staticEnable=0,
                        pixels_red=0, replacement_red=1)
            actual = expected(case, high)
            wrapped = expected(case, dict(high, index=0))
            assert actual['result_red'] == 0 and wrapped['result_red'] == 1
            assert any(value['index'] & (1 << 63) for value in samples(case))
            high.update(index=(1 << 63), writeEnable=0,
                        pixels_red=1 << ((count - 1) * width))
            assert expected(case, high)['selected_red'] == 1
            assert expected(case, dict(high, index=0))['selected_red'] == 0
            for mutation in ('field-swap', 'reversed-elements', 'wrong-offset', 'cross-vec-binding'):
                assert miter(case, 'named', mutation) != miter(case, 'named')
                assert miter(case, 'named', mutation).split('assign bad =')[1] == miter(case, 'named').split('assign bad =')[1]
    contract_case = dict(kind='storage', width=5, blue_width=3, count=3, inner=1,
                         reference_module='Reference')
    defaults = 'parameter integer WIDTH = 5,\nparameter integer BLUE_WIDTH = 3,\nparameter integer COUNT = 1\n'
    declarations = []
    for direction, root_names in zip(('input', 'output'), roots(contract_case)):
        for root in root_names:
            for path, _ in fields(contract_case):
                declarations.append(f'{direction} wire [(COUNT * WIDTH)-1:0] {root}_{path};')
    top = 'module NamedFieldVecStorage #(\n' + defaults + ')();\n' + '\n'.join(declarations) + '\nendmodule\n'
    child = 'module Child #(\n' + defaults + ')();\nendmodule\n'
    check_candidate(contract_case, top + child, 'named')
    for broken in (top.replace('WIDTH = 5', 'WIDTH = 99') + child,
                   top.replace('output wire [(COUNT * WIDTH)-1:0] result_blue;',
                               'output wire signed [(COUNT * WIDTH)-1:0] result_blue;') + child):
        try:
            check_candidate(contract_case, broken, 'named')
        except RuntimeError:
            pass
        else:
            raise AssertionError('candidate ABI checker accepted wrong top defaults or a signed output carrier')
    print('PASS: independent native mapping, dimensions, stimulus, semantic model, mutation and bounded scheduler self-tests')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', nargs='?', type=Path)
    parser.add_argument('replay', nargs='?', type=Path)
    parser.add_argument('--only', help='case stem or topology name; focused evidence cannot claim full matrix')
    parser.add_argument('--layout', choices=('named', 'legacy'))
    parser.add_argument('--jobs', type=int, default=1,
                        help='bounded independent case workers, 1 through 8 (default: 1)')
    parser.add_argument('--self-test', action='store_true')
    args = parser.parse_args()
    if args.self_test:
        self_test()
    elif args.root is not None and args.replay is not None:
        qualify(args.root, args.replay, args.only, args.layout, args.jobs)
    else:
        parser.error('provide root and replay artifact directories, or --self-test')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, RuntimeError, KeyError, AssertionError) as error:
        print(f'FAIL: {error}', file=sys.stderr)
        sys.exit(1)
