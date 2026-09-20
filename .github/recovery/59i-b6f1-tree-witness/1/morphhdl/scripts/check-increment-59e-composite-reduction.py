#!/usr/bin/env python3
"""Qualify reused composite candidates against independently elaborated native RTL.

All adapters contain wiring only. Port geometry is checked from the elaborated
HDL before any comparison. A focused --case run never produces full evidence.
"""
from __future__ import annotations

import argparse
import ast
import hashlib
import importlib.util
import itertools
import json
import random
import re
import shutil
import sys
from pathlib import Path


def load_tools():
    path = Path(__file__).with_name('check-increment-59b-operator-replay.py')
    spec = importlib.util.spec_from_file_location('composite_tools', path)
    if spec is None or spec.loader is None:
        raise RuntimeError('missing strict HDL qualification helpers')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


H = load_tools()
WIDTHS = (1, 5, 8, 32)
COUNTS = (1, 2, 3, 5, 8, 9, 16, 17)
WIDTH_ROOTS = ('R_W', 'G_W', 'B_W', 'KEY_W', 'TAG_W', 'COORD_W', 'C_W', 'U_W', 'S_W', 'BITS_W')
SCOPE = 'parameterized-native-composite-balanced-reduction'
INDUCTION_PASS = 'Induction step proven: SUCCESS!'
SIM_PASS = '59E-COMPOSITE-SIM-PASS'
SIM_FAIL = '59E-COMPOSITE-MISMATCH'
CONTROL_INPUTS = {'clk', 'reset', 'enable'}


def ports(case: dict, direction: str) -> list[dict]:
    return case[direction]


def portwidth(case: dict, direction: str, name: str) -> int:
    return next(port['width'] for port in ports(case, direction) if port['name'] == name)


def sequential(case: dict) -> bool:
    return any(port.get('group') == 'pipelineResult' for port in ports(case, 'outputs'))


def parameters(case: dict) -> str:
    return ' #(' + ', '.join(f'.{name}({value})' for name, value in sorted(case['parameters'].items())) + ')'


def declaration(port: dict, direction: str, prefix: str = '') -> str:
    return f'{direction} [{port["width"] - 1}:0] {prefix}{port["name"]}'


def instance(case: dict, role: str, prefix: str) -> str:
    bindings = [f'.{port["name"]}({port["name"]})' for port in ports(case, 'inputs')]
    bindings += [f'.{port["name"]}({prefix}{port["name"]})' for port in ports(case, 'outputs')]
    overrides = parameters(case) if role == 'candidate' else ''
    return f'{case[role + "_module"]}{overrides} {role}(' + ', '.join(bindings) + ');'


def instances(case: dict) -> list[str]:
    lines = []
    for role, prefix in (('reference', 'g_'), ('candidate', 'c_')):
        lines += [declaration(port, 'wire', prefix) + ';' for port in ports(case, 'outputs')]
        lines += [instance(case, role, prefix)]
    return lines


def miter(case: dict) -> str:
    lines = ['module miter(' + ',\n'.join(
        [declaration(port, 'input wire') for port in ports(case, 'inputs')] + ['output wire bad']) + ');']
    lines += instances(case)
    lines += ['assign bad = ' + ' | '.join(f'(|(g_{p["name"]} ^ c_{p["name"]}))'
                                         for p in ports(case, 'outputs')) + ';', 'endmodule', '']
    return '\n'.join(lines)


def specialized(case: dict) -> str:
    lines = ['module specialized(' + ',\n'.join(
        [declaration(port, 'input wire') for port in ports(case, 'inputs')] +
        [declaration(port, 'output wire', 'c_') for port in ports(case, 'outputs')]) + ');',
        instance(case, 'candidate', 'c_'), 'endmodule', '']
    return '\n'.join(lines)


def stimulus(case: dict) -> list[dict[str, int]]:
    """Exercise independent words, fields, indices, extrema and deterministic data."""
    payloads = [port for port in ports(case, 'inputs') if port['name'] not in CONTROL_INPUTS]
    masks = {port['name']: (1 << port['width']) - 1 for port in payloads}
    result = [{name: 0 for name in masks}, dict(masks)]
    total_bits = sum(port['width'] for port in payloads)
    if total_bits <= 8:
        for vector in range(1 << total_bits):
            offset, sample = 0, {}
            for port in payloads:
                name = port['name']
                sample[name] = (vector >> offset) & masks[name]
                offset += port['width']
            result.append(sample)
    # Vary one port at a time; independent SAT inputs cover every bit pattern.
    for port in payloads:
        name, width = port['name'], port['width']
        anchors = {0, width - 1, width // 2}
        anchors.update(range(0, width, max(1, width // 32)))
        for bit in sorted(anchors):
            for complement in (False, True):
                sample = {other: 0 for other in masks}
                sample[name] = (1 << bit) ^ (masks[name] if complement else 0)
                result.append(sample)
        for salt in (0, 1):
            sample = {other: 0 for other in masks}
            sample[name] = sum(1 << bit for bit in range(width) if (bit + salt) % 2)
            result.append(sample)
    seed = int.from_bytes(hashlib.sha256(case['label'].encode()).digest()[:8], 'little')
    rng = random.Random(seed)
    for _ in range(160):
        result.append({p['name']: rng.getrandbits(p['width']) for p in payloads})
    return result


def unpack_record(value: int, shape: list[dict]) -> dict[str, int]:
    return {leaf['path']: (value >> leaf['offset']) & ((1 << leaf['width']) - 1) for leaf in shape}


def pack_record(record: dict[str, int], shape: list[dict]) -> int:
    return sum((record[leaf['path']] & ((1 << leaf['width']) - 1)) << leaf['offset'] for leaf in shape)


def signed(value: int, width: int) -> int:
    return value if value < 1 << (width - 1) else value - (1 << width)


def balanced(values: list[dict], combine) -> dict:
    # Native adjacent pairing and unchanged odd tail are required for the
    # intentionally order-sensitive complex and nested cross-field callbacks.
    while len(values) > 1:
        values = [combine(values[i], values[i + 1]) if i + 1 < len(values) else values[i]
                  for i in range(0, len(values), 2)]
    return values[0]


def expected(case: dict, sample: dict[str, int]) -> dict[str, int]:
    inputs = case['input_leaf_shapes']
    shapes = dict(case['output_leaf_shapes'])
    shapes.update(case.get('recursive_result_shapes', {}))
    records = {}
    for name, shape in inputs.items():
        width = sum(leaf['width'] for leaf in shape)
        records[name] = [unpack_record(sample[name] >> (index * width), shape)
                         for index in range(case['count'])]
    if case.get('profile') == 'nested_counts':
        if len(records) != 1:
            raise RuntimeError('nested-count profile requires one independent composite Vec')
        source = next(iter(records.values()))
        # The whole packed native record is selected; every inner lane remains
        # attached to the same key/tag even when independent dimensions change.
        selected = min(source, key=lambda record: record['key'])
        shape = next(iter(inputs.values()))
        return {'countedResult': pack_record(selected, shape)}
    result = {}
    for output, operation in (('rgbMin', min), ('rgbMax', max)):
        result[output] = pack_record({field: operation(record[field] for record in records['rgbIn'])
                                     for field in ('red', 'green', 'blue')}, shapes[output])
    # Python min is stable, so equal keys keep the earliest whole record.
    result['selected'] = pack_record(min(records['recordIn'], key=lambda record: record['key']), shapes['selected'])
    mask = (1 << case['parameters']['C_W']) - 1
    result['complexResult'] = pack_record(balanced(records['complexIn'], lambda a, b: {
        'real': (a['real'] + b['imag']) & mask, 'imag': (a['imag'] - b['real']) & mask}), shapes['complexResult'])
    nested_shape = {leaf['path']: leaf for leaf in inputs['nestedIn']}

    def nested(a, b):
        r = {'tag': a['tag'] ^ b['tag']}
        for prefix, choose, valid in (('payload', max, lambda x, y: x | y),
                                      ('lanes_0', min, lambda x, y: x & y),
                                      ('lanes_1', max, lambda x, y: x | y)):
            u, s, bits, q = (prefix + '_' + name for name in ('unsigned', 'signed', 'bitsValue', 'valid'))
            width = nested_shape[s]['width']
            r[u] = (a[u] + b[u]) & ((1 << nested_shape[u]['width']) - 1)
            r[s] = choose(signed(a[s], width), signed(b[s], width)) & ((1 << width) - 1)
            other = {'payload': 'lanes_1_bitsValue', 'lanes_0': bits, 'lanes_1': 'payload_bitsValue'}[prefix]
            r[bits] = a[other] ^ b[bits]
            r[q] = valid(a[q], b[q])
        r['grid_0_0'] = a['grid_0_0'] ^ b['grid_0_0']
        r['grid_0_1'] = a['grid_0_1'] | b['grid_0_1']
        r['grid_1_0'] = a['grid_1_0'] & b['grid_1_0']
        r['grid_1_1'] = a['grid_1_1'] ^ b['grid_1_1']
        return r

    result['nestedResult'] = pack_record(balanced(records['nestedIn'], nested), shapes['nestedResult'])
    return result


def testbench(case: dict) -> tuple[str, int]:
    lines = ['`timescale 1ns/1ps', 'module tb;']
    lines += [declaration(port, 'reg') + ';' for port in ports(case, 'inputs')]
    for name in sorted(CONTROL_INPUTS - {port['name'] for port in ports(case, 'inputs')}):
        lines += ['reg ' + name + ';']
    lines += instances(case)
    lines += ['initial begin', 'clk=0; reset=1; enable=1;']
    lines += [f'{p["name"]}=0;' for p in ports(case, 'inputs') if p['name'] not in CONTROL_INPUTS]
    lines += ['#2; clk=1; #1; clk=0; #1;']

    delay = (case['count'] - 1).bit_length()
    history = [0] * delay

    def compare(tick: int, phase: str, values: dict[str, int]) -> None:
        for port in ports(case, 'outputs'):
            name = port['name']
            lines.extend([f'if (g_{name} !== c_{name}) begin',
                          f'$display("{SIM_FAIL} {name} {phase} tick={tick}"); $finish(1);', 'end'])
            for prefix in ('g_', 'c_'):
                lines.extend([f"if ({prefix}{name} !== {port['width']}'h{((values[port['group']] >> port['offset']) & ((1 << port['width']) - 1)):x}) begin",
                              f'$display("{SIM_FAIL} {prefix}{name} arithmetic-model {phase} tick={tick}"); $finish(1);', 'end'])

    samples = stimulus(case)
    for index, sample in enumerate(samples):
        reset = int(index in (17, 18, 55, 56, 111, 112))
        enable = int(index % 5 != 0 and not 30 <= index < 37 and index not in (17, 55, 111))
        lines += [f'reset={reset}; enable={enable};']
        for name, value in sample.items():
            lines += [f"{name}={portwidth(case, 'inputs', name)}'h{value:x};"]
        values = expected(case, sample)
        if sequential(case):
            values['pipelineResult'] = history[-1] if delay else values['selected']
        lines += ['#2;']
        compare(index, 'before-edge', values)
        lines += ['clk=1; #1;']
        if sequential(case) and delay and enable:
            history = [0] * delay if reset else [values['selected']] + history[:-1]
        if sequential(case):
            values['pipelineResult'] = history[-1] if delay else values['selected']
        compare(index, 'after-edge', values)
        lines += ['clk=0; #1;']
    lines += [f'$display("{SIM_PASS}"); $finish;', 'end', 'endmodule', '']
    return '\n'.join(lines), len(samples)


def setup(paths: list[Path]) -> str:
    # Normalize the two native register/enable forms before SAT. Full Yosys
    # optimization proves and shares identical cones, avoiding exponential
    # induction over separately represented but equivalent pipeline states.
    # Unmap any remaining enabled/reset registers for the SAT cell importer.
    return ('read_verilog ' + ' '.join(H.quoted(path) for path in paths) +
            '\nprep -top miter -flatten\ndffunmap\nopt -full\ndffunmap\ncheck -assert\n')


def evaluate_geometry(expression: str, parameters: dict[str, int]) -> int:
    """Specialize the actual retained candidate shape without guessing witnesses."""
    def value(node):
        if isinstance(node, ast.Constant) and isinstance(node.value, int) and not isinstance(node.value, bool):
            return node.value
        if isinstance(node, ast.Name) and node.id in parameters:
            return parameters[node.id]
        if isinstance(node, ast.BinOp):
            left, right = value(node.left), value(node.right)
            if isinstance(node.op, ast.Add):
                return left + right
            if isinstance(node.op, ast.Sub):
                return left - right
            if isinstance(node.op, ast.Mult):
                return left * right
        raise RuntimeError('unsupported exact candidate geometry expression: ' + expression)

    try:
        result = value(ast.parse(expression, mode='eval').body)
    except SyntaxError as error:
        raise RuntimeError('invalid candidate geometry expression: ' + expression) from error
    if result < 1:
        raise RuntimeError('candidate geometry is nonpositive under declared specialization')
    return result


def specialize_shape(node: dict, parameters: dict[str, int]) -> list[dict]:
    result = []
    offset = 0

    def walk(current, path):
        nonlocal offset
        kind = current['kind']
        if kind in ('UInt', 'SInt', 'Bits', 'Bool'):
            width = evaluate_geometry(current['width'], parameters)
            if kind == 'Bool' and width != 1:
                raise RuntimeError('candidate Bool leaf lost its exact one-bit shape')
            result.append(dict(path='_'.join(path), offset=offset, width=width, kind=kind))
            offset += width
        elif kind == 'Bundle':
            names = [field['name'] for field in current['fields']]
            if len(names) != len(set(names)):
                raise RuntimeError('duplicate actual candidate Bundle fields')
            for field in current['fields']:
                walk(field['node'], path + [field['name']])
        elif kind == 'Vec':
            for index in range(evaluate_geometry(current['count'], parameters)):
                walk(current['element'], path + [str(index)])
        else:
            raise RuntimeError('unsupported actual candidate Data shape: ' + kind)

    walk(node, [])
    return result


def assert_native_candidate_shapes(manifest: dict, case: dict) -> None:
    descriptors = manifest['candidate_native_shapes'][case['candidate_module']]
    fields = ('path', 'offset', 'width', 'kind')
    logical_outputs = dict(case['output_leaf_shapes'])
    logical_outputs.update(case.get('recursive_result_shapes', {}))
    if set(descriptors['outputs']) != set(logical_outputs):
        raise RuntimeError('actual candidate output Data groups differ from the native oracle')
    for group, expected in logical_outputs.items():
        actual = specialize_shape(descriptors['outputs'][group], case['parameters'])
        normalized = [{key: leaf[key] for key in fields} for leaf in expected]
        if actual != normalized:
            raise RuntimeError('actual candidate native output shape mismatch: ' + case['label'] + '/' + group +
                               ': ' + str(actual) + ' != ' + str(normalized))
    if set(descriptors['inputs']) != set(case['input_leaf_shapes']):
        raise RuntimeError('actual candidate input Vecs differ from the native oracle')
    for name, shape in case['input_leaf_shapes'].items():
        width = sum(leaf['width'] for leaf in shape)
        expected = [dict(path=str(index) + '_' + leaf['path'], offset=index * width + leaf['offset'],
                         width=leaf['width'], kind=leaf['kind'])
                    for index in range(case['count']) for leaf in shape]
        actual = specialize_shape(descriptors['inputs'][name], case['parameters'])
        if actual != expected:
            raise RuntimeError('actual candidate native input shape mismatch: ' + case['label'] + '/' + name)


def validate_manifest(manifest: dict) -> list[dict]:
    if manifest.get('scope') != SCOPE:
        raise RuntimeError('incorrect composite qualification scope')
    if manifest.get('candidate_default', {}).get('COUNT') != 1:
        raise RuntimeError('candidate must elaborate once with singleton COUNT=1 default')
    if manifest.get('width_roots') != list(WIDTH_ROOTS):
        raise RuntimeError('all ten independent field-width roots are required')
    if any(manifest['candidate_default'].get(root) != 5 for root in WIDTH_ROOTS):
        raise RuntimeError('field-width roots must retain the declared candidate default')
    cases = manifest['configurations']
    if not cases or len({c['label'] for c in cases}) != len(cases):
        raise RuntimeError('empty or duplicate case labels')
    base = [case for case in cases if case.get('profile', 'base') == 'base']
    if len(base) != 32 or {(c['width'], c['count']) for c in base} != set(itertools.product(WIDTHS, COUNTS)):
        raise RuntimeError('all 32 WIDTH/COUNT baseline specializations are required')
    if any(any(case['parameters'].get(root) != case['width'] for root in WIDTH_ROOTS) for case in base):
        raise RuntimeError('baseline WIDTH labels must bind every independent root to that actual width')
    independent = [case for case in cases if case.get('profile') == 'independent']
    expected_independent = {(tuple(widths), count) for widths in (range(1, 11), range(10, 0, -1)) for count in (3, 5, 9, 17)}
    observed_independent = {(tuple(case['parameters'].get(root) for root in WIDTH_ROOTS), case['count']) for case in independent}
    if len(independent) != 8 or observed_independent != expected_independent:
        raise RuntimeError('all eight independent field-width override cases are required')
    counted = [case for case in cases if case.get('profile') == 'nested_counts']
    counted_shapes = {((inner, rows, columns), count) for inner, rows, columns in ((1, 2, 3), (2, 3, 1), (3, 1, 2)) for count in COUNTS}
    counted_shapes.add(((1, 1, 1), 1))
    actual_shapes = {((case['parameters']['INNER'], case['parameters']['GRID_R'], case['parameters']['GRID_C']), case['count']) for case in counted}
    if len(counted) != 25 or actual_shapes != counted_shapes:
        raise RuntimeError('all 25 independent nested-dimension specializations are required')
    if len(cases) != 65:
        raise RuntimeError('unexpected or incomplete composite qualification matrix')
    if len({(case['candidate_rtl'], case['candidate_module']) for case in counted}) != 1:
        raise RuntimeError('all nested-count cases must reuse one singleton-default candidate')
    topologies = {}
    for case in cases:
        topologies.setdefault(case['candidate_module'], set()).add(case['candidate_rtl'])
    if any(len(paths) != 1 for paths in topologies.values()):
        raise RuntimeError('each static topology must reuse one candidate artifact')
    if len({(case['candidate_rtl'], case['candidate_module']) for case in base + independent}) != 1:
        raise RuntimeError('base and independent field-width cases must reuse the same candidate')
    for case in cases:
        if not H.IDENTIFIER.fullmatch(case['label']):
            raise RuntimeError('invalid case label')
        for role in ('reference', 'candidate'):
            if not H.IDENTIFIER.fullmatch(case[role + '_module']):
                raise RuntimeError('invalid module identifier')
        for name, value in case['parameters'].items():
            if not H.IDENTIFIER.fullmatch(name) or not isinstance(value, int) or isinstance(value, bool) or value < 1:
                raise RuntimeError('invalid positive finite parameter binding')
        defaults = manifest['candidate_defaults'][case['candidate_module']]
        if set(defaults) != set(case['parameters']) or defaults.get('COUNT') != 1:
            raise RuntimeError('case parameters differ from the declared static topology')
        if case['parameters'].get('COUNT') != case['count']:
            raise RuntimeError('COUNT override must match oracle geometry')
        all_names = []
        for direction in ('inputs', 'outputs'):
            entries = ports(case, direction)
            if not entries:
                raise RuntimeError('missing exact port geometry')
            for port in entries:
                if not H.IDENTIFIER.fullmatch(port['name']) or not isinstance(port['width'], int) or port['width'] < 1:
                    raise RuntimeError('invalid or zero-width port')
                all_names.append(port['name'])
        if len(all_names) != len(set(all_names)):
            raise RuntimeError('duplicate input/output port names')
        controls = CONTROL_INPUTS & {p['name'] for p in ports(case, 'inputs')}
        if (controls and controls != CONTROL_INPUTS) or (sequential(case) and controls != CONTROL_INPUTS):
            raise RuntimeError('incomplete native synchronous-reset clock-enable controls')
        if any(portwidth(case, 'inputs', name) != 1 for name in controls):
            raise RuntimeError('control ports must be one bit')
        for direction in ('input', 'output'):
            shape_map = case[direction + '_leaf_shapes']
            actual_ports = ports(case, direction + 's')
            required = ({p['name'] for p in actual_ports} - CONTROL_INPUTS if direction == 'input'
                        else {p['group'] for p in actual_ports})
            if set(shape_map) != required:
                raise RuntimeError('native recursive leaf shapes do not cover exact ports')
            for name, shape in shape_map.items():
                offset, seen = 0, set()
                for leaf in shape:
                    if leaf['path'] in seen or leaf['offset'] != offset or leaf['width'] < 1:
                        raise RuntimeError('duplicate, overlapping, gapped or invalid recursive leaf')
                    if leaf['kind'] not in ('UInt', 'SInt', 'Bits', 'Bool') or (leaf['kind'] == 'Bool' and leaf['width'] != 1):
                        raise RuntimeError('unsupported native recursive leaf type')
                    seen.add(leaf['path'])
                    offset += leaf['width']
                if direction == 'input':
                    if offset * case['count'] != portwidth(case, 'inputs', name):
                        raise RuntimeError('packed input lacks exact recursive element geometry')
                else:
                    observed = [{key: port[key] for key in ('path', 'offset', 'width', 'kind', 'name')}
                                for port in actual_ports if port['group'] == name]
                    expected_leaves = [{key: leaf[key] for key in ('path', 'offset', 'width', 'kind', 'name')} for leaf in shape]
                    if observed != expected_leaves:
                        raise RuntimeError('physical output leaves do not match native result paths, types and widths')
        if case.get('profile') == 'nested_counts':
            source_shape = case['input_leaf_shapes']['countedIn']
            result_shape = case['recursive_result_shapes']['countedResult']
            fields = ('path', 'offset', 'width', 'kind')
            if [{key: leaf[key] for key in fields} for leaf in source_shape] != [{key: leaf[key] for key in fields} for leaf in result_shape]:
                raise RuntimeError('nested whole-record selection changed recursive native result shape')
            if sum(leaf['width'] for leaf in result_shape) != sum(port['width'] for port in case['outputs']):
                raise RuntimeError('nested packed outputs hide a recursive result shape mismatch')
        assert_native_candidate_shapes(manifest, case)
    return cases


def assert_geometry(case: dict, rtl: Path, role: str, work: Path) -> None:
    """Read tool-elaborated module ports before a wrapper can hide a width error."""
    module = case[role + '_module']
    script = work / (role + '-geometry.ys')
    geometry = work / (role + '-geometry.json')
    overrides = ''
    if role == 'candidate':
        overrides = 'chparam ' + ' '.join(f'-set {name} {value}' for name, value in sorted(case['parameters'].items())) + ' ' + module + '\n'
    script.write_text('read_verilog ' + H.quoted(rtl) + '\n' + overrides +
                      f'hierarchy -check -top {module}\nproc\nwrite_json ' + H.quoted(geometry) + '\n')
    H.command(['yosys', '-Q', '-T', '-s', str(script)], work / (role + '-geometry.log'))
    observed = json.loads(geometry.read_text())['modules'][module]['ports']
    expected = {p['name']: (direction[:-1], p['width'])
                for direction in ('inputs', 'outputs') for p in ports(case, direction)}
    actual = {name: (port['direction'], len(port['bits'])) for name, port in observed.items()}
    if actual != expected:
        raise RuntimeError(f'exact {role} port geometry mismatch for {case["label"]}: {actual} != {expected}')


def require_pass(proof: str, marker: str, label: str) -> None:
    if marker not in proof or H.COUNTEREXAMPLE in proof:
        raise RuntimeError('proof lacks definitive SUCCESS: ' + label)


def qualify(root: Path, duplicate: Path, only_case: str | None) -> None:
    root, duplicate = root.resolve(), duplicate.resolve()
    evidence_path = root / 'evidence.json'
    if evidence_path.exists():
        evidence_path.unlink()
    for tool in ('iverilog', 'vvp', 'verilator', 'yosys'):
        if shutil.which(tool) is None:
            raise RuntimeError('required tool missing: ' + tool)
    manifest_path = root / 'manifest.json'
    manifest = json.loads(manifest_path.read_text())
    cases = validate_manifest(manifest)
    if manifest_path.read_bytes() != (duplicate / 'manifest.json').read_bytes():
        raise RuntimeError('nondeterministic composite manifest')
    candidates = {}
    for case in cases:
        module = case['candidate_module']
        if module in candidates:
            continue
        candidate = H.checked_rtl(root, case['candidate_rtl'])
        if candidate.read_bytes() != H.checked_rtl(duplicate, case['candidate_rtl']).read_bytes():
            raise RuntimeError('nondeterministic reused composite candidate: ' + module)
        text = candidate.read_text()
        if len(re.findall(r'^module\s+' + re.escape(module) + r'\b', text, re.MULTILINE)) != 1:
            raise RuntimeError('one parameterized module per static topology is required')
        if not re.search(r'parameter\s+(?:integer\s+)?COUNT\s*=\s*1\b', text):
            raise RuntimeError('COUNT=1 default was lost in published RTL')
        for parameter, default in manifest['candidate_defaults'][module].items():
            if not re.search(r'parameter\s+(?:integer\s+)?' + re.escape(parameter) + r'\s*=\s*' + str(default) + r'\b', text):
                raise RuntimeError('declared parameter default was lost in ' + module + ': ' + parameter)
        if 'genvar' not in text or 'begin : tail' not in text:
            raise RuntimeError('larger overrides must retain pair loops and odd-tail structure')
        candidates[module] = candidate
    selected = cases if only_case is None else [case for case in cases if case['label'] == only_case]
    if not selected:
        raise RuntimeError('unknown focused composite case')
    evidence = []
    for case in selected:
        label = case['label']
        candidate = candidates[case['candidate_module']]
        work = root / 'checks' / label
        work.mkdir(parents=True, exist_ok=True)
        reference = H.checked_rtl(root, case['reference_rtl'])
        if reference.read_bytes() != H.checked_rtl(duplicate, case['reference_rtl']).read_bytes():
            raise RuntimeError('nondeterministic independent reference: ' + label)
        for role, rtl in (('reference', reference), ('candidate', candidate)):
            assert_geometry(case, rtl, role, work)
        top = work / 'specialized.v'
        top.write_text(specialized(case))
        for role, module, paths in (('reference', case['reference_module'], [reference]),
                                    ('candidate', 'specialized', [candidate, top])):
            H.command(['verilator', '--lint-only', '--language', '1364-2001', '--top-module', module,
                       *map(str, paths)], work / (role + '-lint.log'))
            script = work / (role + '-synthesis.ys')
            script.write_text('read_verilog ' + ' '.join(H.quoted(path) for path in paths) +
                              f'\nhierarchy -check -top {module}\nsynth -top {module}\ncheck -assert\nstat\n')
            H.command(['yosys', '-Q', '-T', '-s', str(script)], work / (role + '-synthesis.log'))
        bench = work / 'tb.v'
        bench_text, cycles = testbench(case)
        bench.write_text(bench_text)
        executable = work / 'tb.vvp'
        H.command(['iverilog', '-g2001', '-s', 'tb', '-o', str(executable), str(reference), str(candidate), str(bench)], work / 'compile.log')
        simulation = H.command(['vvp', str(executable)], work / 'simulation.log')
        if SIM_PASS not in simulation or SIM_FAIL in simulation:
            raise RuntimeError('native composite simulation mismatch: ' + label)
        miter_path = work / 'miter.v'
        miter_path.write_text(miter(case))
        if sequential(case):
            script = work / 'reset-entry.ys'
            script.write_text(setup([reference, candidate, miter_path]) +
                              'sat -seq 2 -set-at 1 reset 1 -set-at 1 enable 1 -prove bad 0 -prove-skip 1 -verify -timeout 120\n')
            require_pass(H.command(['yosys', '-Q', '-T', '-s', str(script)], work / 'reset-entry.log'), H.PASS, label + ' reset entry')
            script = work / 'induction.ys'
            script.write_text(setup([reference, candidate, miter_path]) +
                              'sat -seq 1 -tempinduct -set-init-zero -prove bad 0 -verify -maxsteps 24 -timeout 120\n')
            require_pass(H.command(['yosys', '-Q', '-T', '-s', str(script)], work / 'induction.log'), INDUCTION_PASS, label + ' induction')
        else:
            script = work / 'equivalence.ys'
            script.write_text(setup([reference, candidate, miter_path]) + 'sat -prove bad 0 -verify -timeout 120\n')
            require_pass(H.command(['yosys', '-Q', '-T', '-s', str(script)], work / 'equivalence.log'), H.PASS, label + ' combinational equivalence')
        evidence.append(dict(label=label, parameters=case['parameters'], cycles=cycles,
                             sha256={role: hashlib.sha256(path.read_bytes()).hexdigest() for role, path in
                                     (('reference', reference), ('candidate', candidate))},
                             exact_geometry='PASS', strict_verilog_2001='PASS', synthesis='PASS',
                             reset_entry='PASS' if sequential(case) else 'not-applicable',
                             temporal_induction='PASS' if sequential(case) else 'not-applicable',
                             combinational_equivalence='not-applicable' if sequential(case) else 'PASS'))
        print('PASS:', label, 'independent native composite behavior, exact geometry, strict tools and induction', flush=True)
    if only_case is not None:
        print('PASS: focused case only; no full qualification evidence emitted', flush=True)
        return
    main_case = next(case for case in cases if case.get('profile') == 'base')
    mutations = qualify_mutations(root, cases, candidates[main_case['candidate_module']])
    evidence_path.write_text(json.dumps(dict(scope=SCOPE, candidate_default=manifest['candidate_default'],
        proof_scope='finite declared parameter matrix; arbitrary independent payloads and temporal induction',
        configurations=evidence, mutations=mutations), indent=2) + '\n')
    print(f'PASS: {len(evidence)} composite specializations and {len(mutations)} genuine candidate mutation counterexamples', flush=True)


def rewrite_candidate_output(text: str, output: str, expression) -> str:
    # Change the actual emitted candidate, never the miter's observation or an
    # independent oracle. Keep its original RHS in an equally wide local wire.
    assignment = re.compile(r'(?m)^(\s*assign\s+' + re.escape(output) + r'\s*=\s*)([^;]+);')
    matches = list(assignment.finditer(text))
    declaration = re.search(r'(?m)^\s*output\s+wire\s+(?:signed\s+)?(\[[^\n;]+?\])\s+' +
                            re.escape(output) + r'\s*[,;)]', text)
    if len(matches) != 1 or declaration is None:
        raise RuntimeError('one exact candidate output anchor is required for mutation: ' + output)
    match = matches[0]
    wire = 'morphhdl_59e_mutation_' + output
    replacement = ('  wire ' + declaration.group(1) + ' ' + wire + ';\n' +
                   '  assign ' + wire + ' = ' + match.group(2) + ';\n' +
                   '  assign ' + output + ' = ' + expression(wire) + ';')
    mutated = text[:match.start()] + replacement + text[match.end():]
    if mutated == text:
        raise RuntimeError('actual candidate mutation made no change')
    return mutated


def candidate_mutations(text: str) -> list[tuple[str, str]]:
    def assignment(output):
        pattern = re.compile(r'(?m)^(\s*assign\s+' + re.escape(output) + r'\s*=\s*)([^;]+);')
        matches = list(pattern.finditer(text))
        if len(matches) != 1:
            raise RuntimeError('exact candidate leaf assignment required: ' + output)
        return matches[0]

    red, green = assignment('rgbMin_red'), assignment('rgbMin_green')
    rgb = text
    for match, rhs in sorted(((red, green.group(2)), (green, red.group(2))), key=lambda pair: pair[0].start(), reverse=True):
        rgb = rgb[:match.start()] + match.group(1) + rhs + ';' + rgb[match.end():]
    tag = rewrite_candidate_output(text, 'selected_tag', lambda w: w + " ^ 1'b1")
    cross_field = rewrite_candidate_output(text, 'nestedResult_payload_bitsValue', lambda _:
        'nestedResult_lanes[(U_W+S_W+BITS_W)-1:(U_W+S_W)]')
    return [('rgb-leaf-swap', rgb), ('selected-record-tag-corruption', tag),
            ('nested-cross-field-wiring', cross_field)]


def qualify_mutations(root: Path, cases: list[dict], candidate: Path) -> list[dict]:
    case = next(c for c in cases if c.get('profile', 'base') == 'base' and (c['width'], c['count']) == (5, 5))
    jobs = [(case, label, text) for label, text in candidate_mutations(candidate.read_text())]
    nested = next(c for c in cases if c.get('profile') == 'nested_counts' and c['parameters']['INNER'] == 3 and c['count'] == 5)
    nested_candidate = H.checked_rtl(root, nested['candidate_rtl'])
    # This output is published through guarded native leaf slice assignments.
    # Corrupt the first element's UInt source with the next element's UInt,
    # preserving the exact slice width and every generate guard.
    text = nested_candidate.read_text()
    anchor = re.compile(r'(?m)^(\s*assign\s+countedResult_samples\[\(0\)\s*\+:\s*U_W\]\s*=\s*)([^;]+);')
    matches = list(anchor.finditer(text))
    if len(matches) != 1:
        raise RuntimeError('one exact nested element output slice is required for mutation')
    match = matches[0]
    mutated = (text[:match.start()] + match.group(1) +
               'countedResult_samples[(U_W+S_W+BITS_W+1) +: U_W];' + text[match.end():])
    jobs.append((nested, 'nested-element-offset', mutated))
    result = []
    for case, label, text in jobs:
        reference = H.checked_rtl(root, case['reference_rtl'])
        work = root / 'checks' / label
        work.mkdir(parents=True, exist_ok=True)
        mutated = work / 'candidate-mutated.v'
        mutated.write_text(text)
        bench, executable = work / 'tb.v', work / 'tb.vvp'
        bench.write_text(testbench(case)[0])
        # A parser error is never accepted as mutation detection.
        H.command(['iverilog', '-g2001', '-s', 'tb', '-o', str(executable), str(reference), str(mutated), str(bench)],
                  work / 'compile.log')
        simulation = H.command(['vvp', str(executable)], work / 'simulation.log')
        if SIM_FAIL not in simulation or SIM_PASS in simulation:
            raise RuntimeError('actual candidate mutation was not detected by simulation: ' + label)
        top = work / 'miter.v'
        top.write_text(miter(case))
        trace = work / 'counterexample.vcd'
        script = work / 'mutation.ys'
        temporal = '-seq 8 -set-init-zero ' if sequential(case) else ''
        script.write_text(setup([reference, mutated, top]) +
                          'sat ' + temporal + '-prove bad 0 -show-inputs -show-outputs -timeout 120 -dump_vcd ' + H.quoted(trace) + '\n')
        proof = H.command(['yosys', '-Q', '-T', '-s', str(script)], work / 'mutation.log')
        if H.COUNTEREXAMPLE not in proof or H.PASS in proof:
            raise RuntimeError('actual candidate mutation lacks a genuine satisfying counterexample: ' + label)
        H.require_counterexample_vcd(trace)
        result.append(dict(label=label, simulation='MISMATCH', formal='COUNTEREXAMPLE', trace_bad=1,
                           candidate_sha256=hashlib.sha256(mutated.read_bytes()).hexdigest()))
        print('PASS:', label, 'actual candidate mutation detected in simulation and SAT bad=1 witness', flush=True)
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path)
    parser.add_argument('duplicate', type=Path)
    parser.add_argument('--case', dest='only_case', help='Focused development case; emits no full evidence')
    args = parser.parse_args()
    qualify(args.root, args.duplicate, args.only_case)


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, RuntimeError, KeyError, StopIteration) as error:
        print('FAIL: ' + str(error), file=sys.stderr)
        sys.exit(1)
