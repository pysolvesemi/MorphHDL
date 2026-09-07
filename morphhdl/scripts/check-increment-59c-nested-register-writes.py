#!/usr/bin/env python3
"""Qualify standalone nested indexed registers against independent native RTL.

The proof relates every corresponding, otherwise arbitrary, FF bit at cycle 1
and proves that relation at cycle 2. Complete state-to-output inventories make
this a one-step inductive preservation proof, without reset or initial values.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import itertools
import json
import random
import re
import shutil
from pathlib import Path

_SPEC = importlib.util.spec_from_file_location(
    'named_fields', Path(__file__).with_name('check-increment-59c-named-field-vectors.py'))
H = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(H)
MODULE = 'NamedFieldNestedRegister'
SIM_PASS = '59C-NESTED-REGISTER-SIM-PASS'
SIM_FAIL = '59C-NESTED-REGISTER-MISMATCH'
MUTATIONS = ('disabled-write', 'wrong-axis')
RELATION = 'all corresponding FF bits equal; values otherwise unconstrained'


def stem(case):
    return f'register_w{case["width"]}_b{case["blue_width"]}_n{case["count"]}_i{case["inner"]}'


def schema(case):
    inputs = dict(clk=1, enable=1, outerIndex=64, innerIndex=64)
    inputs.update(('replacement_' + path, width) for path, width in H.fields(case))
    outputs = {'result_colors_' + path: width * case['count'] * case['inner']
               for path, width in H.fields(case)}
    return inputs, outputs


def mutated_input(name, mutation):
    if mutation is None:
        return name
    if mutation not in MUTATIONS:
        raise RuntimeError('unknown register mutation: ' + mutation)
    if mutation == 'disabled-write' and name == 'enable':
        # Inversion preserves all state bits for the same inventory gate. The
        # counterexample fixes enable=1, so the actual candidate write is off.
        return '~enable'
    if mutation == 'wrong-axis' and name in ('outerIndex', 'innerIndex'):
        return 'innerIndex' if name == 'outerIndex' else 'outerIndex'
    return name


def bindings(case, role, layout, prefix, mutation=None):
    inputs, _ = schema(case)
    ports = [f'.{name}({mutated_input(name, mutation) if role == "candidate" else name})'
             for name in inputs]
    declarations, assignments = [], []
    if role == 'reference':
        for outer in range(case['count']):
            for inner in range(case['inner']):
                index = outer * case['inner'] + inner
                for path, width in H.fields(case):
                    signal = H.slice_(prefix + '_result_colors_' + path, index * width, width)
                    ports.append(f'.result_{outer}_colors_{inner}_{path}({signal})')
    elif layout == 'named':
        ports += [f'.result_colors_{path}({prefix}_result_colors_{path})'
                  for path, _ in H.fields(case)]
    elif layout == 'legacy':
        total = H.pixel_bits(case) * case['count'] * case['inner']
        declarations.append(f'wire [{total - 1}:0] {prefix}_packed_result;')
        ports.append(f'.result({prefix}_packed_result)')
        offset = 0
        for index in range(case['count'] * case['inner']):
            for path, width in H.fields(case):
                assignments.append(f'assign {H.slice_(prefix + "_result_colors_" + path, index * width, width)} = '
                                   f'{H.slice_(prefix + "_packed_result", offset, width)};')
                offset += width
    else:
        raise RuntimeError('unknown layout: ' + layout)
    return ports, declarations, assignments


def instance(case, role, layout, prefix, mutation=None, declare_outputs=True):
    _, outputs = schema(case)
    ports, declarations, assignments = bindings(case, role, layout, prefix, mutation)
    lines = [f'wire [{width - 1}:0] {prefix}_{name};' for name, width in outputs.items()] if declare_outputs else []
    lines += declarations
    module = case['reference_module'] if role == 'reference' else MODULE
    parameters = '' if role == 'reference' else (
        f' #(.WIDTH({case["width"]}), .BLUE_WIDTH({case["blue_width"]}), '
        f'.COUNT({case["count"]}), .INNER({case["inner"]}))')
    return lines + [f'{module}{parameters} {prefix}(' + ',\n  '.join(ports) + ');'] + assignments


def miter(case, layout, mutation=None):
    inputs, outputs = schema(case)
    ports = [f'input wire [{width - 1}:0] {name}' for name, width in inputs.items()]
    ports += [f'output wire [{width - 1}:0] {role}_{name}'
              for role in ('g', 'c') for name, width in outputs.items()]
    lines = ['module miter(' + ',\n'.join(ports + ['output wire bad']) + ');']
    lines += instance(case, 'reference', layout, 'g', declare_outputs=False)
    lines += instance(case, 'candidate', layout, 'c', mutation, declare_outputs=False)
    lines += ['assign bad = ' + ' | '.join(f'(|(g_{name} ^ c_{name}))' for name in outputs) + ';', 'endmodule', '']
    return '\n'.join(lines)


def specialized(case, role, layout, mutation=None):
    inputs, outputs = schema(case)
    ports = [f'input wire [{width - 1}:0] {name}' for name, width in inputs.items()]
    ports += [f'output wire [{width - 1}:0] out_{name}' for name, width in outputs.items()]
    lines = ['module specialized(' + ',\n'.join(ports) + ');']
    lines += instance(case, role, layout, 'out', mutation, declare_outputs=False)
    return '\n'.join(lines + ['endmodule', ''])


def update_state(case, state, values):
    result = dict(state)
    if values['enable'] and values['outerIndex'] < case['count'] and values['innerIndex'] < case['inner']:
        index = values['outerIndex'] * case['inner'] + values['innerIndex']
        for path, width in H.fields(case):
            name, offset = 'result_colors_' + path, index * width
            mask = ((1 << width) - 1) << offset
            result[name] = (state[name] & ~mask) | (values['replacement_' + path] << offset)
    return result


def stimuli(case):
    inputs, _ = schema(case)
    rng = random.Random(590000 + case['width'] * 1000 + case['blue_width'] * 100 + case['count'] * 10 + case['inner'])
    result = []
    # Every uninitialized cell is loaded before checking the complete state.
    for outer in range(case['count']):
        for inner in range(case['inner']):
            values = {name: rng.getrandbits(width) for name, width in inputs.items()}
            values.update(clk=0, enable=1, outerIndex=outer, innerIndex=inner)
            result.append(values)
    for index in range(96):
        values = {name: rng.getrandbits(width) for name, width in inputs.items()}
        outer, inner = index % case['count'], (index // 3) % case['inner']
        values.update(clk=0, enable=int(index % 4 != 0))
        values['outerIndex'] = [outer, case['count'] - 1, case['count'], (1 << 32) | outer, (1 << 63) | outer, outer][index % 6]
        values['innerIndex'] = [inner, case['inner'] - 1, case['inner'], (1 << 32) | inner, (1 << 63) | inner, inner][(index // 6) % 6]
        result.append(values)
    return result


def testbench(case, layout):
    inputs, outputs = schema(case)
    lines = ['`timescale 1ns/1ps', 'module tb;']
    lines += [f'reg [{width - 1}:0] {name};' for name, width in inputs.items()]
    lines += instance(case, 'reference', layout, 'g') + instance(case, 'candidate', layout, 'c')
    lines += ['initial begin']
    state = dict.fromkeys(outputs, 0)
    warmup = case['count'] * case['inner']
    for index, values in enumerate(stimuli(case)):
        lines += [f"{name} = {inputs[name]}'h{value:x};" for name, value in values.items()] + ['#1;']
        if index >= warmup:
            for role in ('g', 'c'):
                for name, value in state.items():
                    lines += [f"if ({role}_{name} !== {outputs[name]}'h{value:x}) begin",
                              f'$display("{SIM_FAIL} before edge {role}_{name} sample={index}"); $finish(1); end']
        lines += ['clk=1; #1;']
        state = update_state(case, state, values)
        if index >= warmup - 1:
            for role in ('g', 'c'):
                for name, value in state.items():
                    lines += [f"if ({role}_{name} !== {outputs[name]}'h{value:x}) begin",
                              f'$display("{SIM_FAIL} after edge {role}_{name} sample={index}"); $finish(1); end']
        lines += ['clk=0; #1;']
    return '\n'.join(lines + [f'$display("{SIM_PASS}"); $finish;', 'end', 'endmodule', ''])


def check_ports(case, rtl, role, layout):
    module_name = case['reference_module'] if role == 'reference' else MODULE
    found = re.search(r'^module\s+' + re.escape(module_name) + r'\b[\s\S]*?^endmodule\b', rtl, re.M)
    if found is None or len(re.findall(r'^module\s+' + re.escape(module_name) + r'\b', rtl, re.M)) != 1:
        raise RuntimeError('missing or repeated register module')
    top = found.group(0)
    declarations = {}
    for match in re.finditer(r'^\s*(input|output)\s+(?:wire|reg)\s+(signed\s+)?(?:\[([^\]]+)\]\s+)?(\w+)\s*[,;]?\s*$', top, re.M):
        declarations[match.group(4)] = (match.group(1), bool(match.group(2)), match.group(3))
    inputs, outputs = schema(case)
    expected = dict(inputs)
    if role == 'reference':
        for outer in range(case['count']):
            for inner in range(case['inner']):
                expected.update((f'result_{outer}_colors_{inner}_{path}', width) for path, width in H.fields(case))
    elif layout == 'named':
        expected.update(outputs)
    else:
        expected['result'] = H.pixel_bits(case) * case['count'] * case['inner']
    if set(declarations) != set(expected):
        raise RuntimeError('register port inventory mismatch')
    for name, width in expected.items():
        direction, signed, bounds = declarations[name]
        if direction != ('input' if name in inputs else 'output'):
            raise RuntimeError('register port direction mismatch: ' + name)
        if role == 'candidate' and name.startswith('result'):
            if signed or bounds is None or not all(root in bounds for root in ('COUNT', 'INNER')):
                raise RuntimeError('lost unsigned independent register carrier dimensions: ' + name)
        elif role == 'reference':
            if bounds is not None and not re.fullmatch(r'\d+:0', bounds):
                raise RuntimeError('native reference port is not concrete: ' + name)
            actual = 1 if bounds is None else int(bounds.split(':')[0]) + 1
            if actual != width:
                raise RuntimeError('native reference width mismatch: ' + name)
    if role == 'candidate':
        for name, default in [('WIDTH', 5), ('BLUE_WIDTH', 3), ('COUNT', 1), ('INNER', 1)]:
            if not re.search(r'parameter\s+(?:integer\s+)?' + name + r'\s*=\s*' + str(default) + r'\b', top):
                raise RuntimeError('register singleton default lost: ' + name)


def inventory(case, document):
    """Reject hidden state and prove each complete state bit is directly visible."""
    module = document['modules']['specialized']
    _, outputs = schema(case)
    expected_bits = sum(outputs.values())
    clock = module['ports']['clk']['bits']
    if len(clock) != 1 or not isinstance(clock[0], int):
        raise RuntimeError('invalid register clock')
    q_bits = []
    dffs = 0
    for cell in module['cells'].values():
        kind = cell['type']
        if kind == '$dff':
            dffs += 1
            if cell['connections']['CLK'] != clock or int(cell['parameters']['CLK_POLARITY'], 2) != 1:
                raise RuntimeError('register clock or edge differs')
            q_bits += cell['connections']['Q']
        elif re.search(r'ff|latch|mem|fsm|\$sr\b', kind, re.I):
            raise RuntimeError('unsupported hidden, reset, or memory state: ' + kind)
    for net in module.get('netnames', {}).values():
        if 'init' in net.get('attributes', {}):
            raise RuntimeError('initialized register values are outside this proof')
    visible = []
    if {name for name, port in module['ports'].items() if port['direction'] == 'output'} != {'out_' + name for name in outputs}:
        raise RuntimeError('unexpected observable register ports')
    for name, width in outputs.items():
        bits = module['ports']['out_' + name]['bits']
        if len(bits) != width:
            raise RuntimeError('register output shape differs')
        visible += bits
    if not dffs or len(q_bits) != expected_bits or len(set(q_bits)) != expected_bits:
        raise RuntimeError('register state count or unique ownership differs')
    if any(not isinstance(bit, int) for bit in q_bits + visible):
        raise RuntimeError('constant or unknown register state bit')
    if len(visible) != expected_bits or len(set(visible)) != expected_bits or set(visible) != set(q_bits):
        raise RuntimeError('every unique FF bit must be directly and completely observable')
    return dict(ff_bits=expected_bits, ff_cells=dffs, directly_observable_ff_bits=len(visible),
                clock='clk', clock_edge='positive', reset_or_initialized_values_present=False)


def synthesis_and_inventory(case, work, rtl, role, layout, mutation=None):
    wrapper = work / (role + '-specialized.v')
    wrapper.write_text(specialized(case, role, layout, mutation))
    paths = [rtl, wrapper]
    H.command(['verilator', '--lint-only', '--language', '1364-2001', '--top-module', 'specialized', *map(str, paths)], work / (role + '-lint.log'))
    state = work / (role + '-state.json')
    script = work / (role + '-synthesis.ys')
    script.write_text('read_verilog ' + ' '.join(H.quoted(path) for path in paths) +
                      '\nprep -top specialized -flatten\ndffunmap\nmemory_map\nopt_clean\ncheck -assert\n' +
                      'write_json ' + H.quoted(state) + '\nsynth -top specialized\ncheck -assert\nstat\n')
    H.command(['yosys', '-Q', '-T', '-s', str(script)], work / (role + '-synthesis.log'))
    return inventory(case, json.loads(state.read_text()))


def proof_setup(paths):
    return 'read_verilog ' + ' '.join(H.quoted(path) for path in paths) + '\nprep -top miter -flatten\ndffunmap\nmemory_map\nopt_clean\ncheck -assert\n'


def qualify_case(case, root, replay):
    reference = H.checked_rtl(root, case['reference_rtl'])
    if reference.read_bytes() != H.checked_rtl(replay, case['reference_rtl']).read_bytes():
        raise RuntimeError('nondeterministic native register reference')
    check_ports(case, reference.read_text(), 'reference', 'named')
    records = []
    for layout in ('named', 'legacy'):
        relative = ('candidate' if layout == 'named' else 'legacy') + '/' + MODULE + '.v'
        candidate = H.checked_rtl(root, relative)
        candidate_bytes = candidate.read_bytes()
        if candidate_bytes != H.checked_rtl(replay, relative).read_bytes():
            raise RuntimeError('nondeterministic register candidate')
        check_ports(case, candidate.read_text(), 'candidate', layout)
        work = root / 'checks' / layout / stem(case)
        work.mkdir(parents=True, exist_ok=True)
        states = {role: synthesis_and_inventory(case, work, rtl, role, layout)
                  for role, rtl in [('candidate', candidate), ('reference', reference)]}
        bench, executable = work / 'tb.v', work / 'tb.vvp'
        bench.write_text(testbench(case, layout))
        H.command(['iverilog', '-g2001', '-s', 'tb', '-o', str(executable), str(reference), str(candidate), str(bench)], work / 'parse.log')
        simulation = H.command(['vvp', str(executable)], work / 'simulation.log')
        if SIM_PASS not in simulation or SIM_FAIL in simulation:
            raise RuntimeError('register simulation lacks definitive PASS')
        top, script = work / 'miter.v', work / 'proof.ys'
        top.write_text(miter(case, layout))
        script.write_text(proof_setup([reference, candidate, top]) +
                          'sat -seq 2 -set-at 1 bad 0 -prove-skip 1 -prove bad 0 -verify '
                          '-timeout 120 -show-inputs -show-outputs -show-regs\n')
        proof = H.command(['yosys', '-Q', '-T', '-s', str(script)], work / 'proof.log')
        if H.PASS not in proof or H.COUNTEREXAMPLE in proof:
            raise RuntimeError('register induction preservation lacks definitive PASS')
        if candidate.read_bytes() != candidate_bytes:
            raise RuntimeError('specialization changed shared register candidate')
        record = dict(case=stem(case), layout=layout, samples=96,
                      warmup_edges=case['count'] * case['inner'], proof='PASS',
                      initial_state_relation=RELATION, initialized_register_values_assumed=False,
                      state_inventory=states, candidate_sha256=hashlib.sha256(candidate_bytes).hexdigest(),
                      reference_sha256=hashlib.sha256(reference.read_bytes()).hexdigest())
        records.append(record)
        print(f'PASS {layout} {stem(case)}: strict tools, complete state inventory, induction preservation, simulation', flush=True)
    return records


def parse_vcd(path):
    """Read complete known signal snapshots, including carried-forward values."""
    declarations, state, snapshots = {}, {}, []
    in_header = True
    time = None
    for line in path.read_text().splitlines():
        line = line.strip()
        if in_header:
            match = re.fullmatch(r'\$var\s+\w+\s+(\d+)\s+(\S+)\s+(.*?)\s+\$end', line)
            if match:
                name = match.group(3).split()[0].lstrip('\\')
                declarations[match.group(2)] = (name, int(match.group(1)))
            if line == '$enddefinitions $end':
                in_header = False
            continue
        if line.startswith('#'):
            if time is not None:
                snapshots.append((time, dict(state)))
            time = int(line[1:])
        elif line.startswith('b'):
            bits, identifier = line[1:].split()
            if identifier in declarations:
                name, width = declarations[identifier]
                if len(bits) > width:
                    raise RuntimeError('oversized VCD vector')
                state[name] = None if re.search('[xz]', bits, re.I) else int(bits, 2)
        elif line and line[0] in '01xXzZ' and line[1:] in declarations:
            name, _ = declarations[line[1:]]
            state[name] = int(line[0]) if line[0] in '01' else None
    if time is not None:
        snapshots.append((time, dict(state)))
    if in_header or not snapshots:
        raise RuntimeError('missing counterexample VCD timeline')
    return snapshots


def validate_counterexample(case, mutation, path):
    inputs, outputs = schema(case)
    required = set(inputs) | {'bad'} | {role + '_' + name for role in ('g', 'c') for name in outputs}
    states = [values for _, values in parse_vcd(path)
              if required <= values.keys() and all(values[name] is not None for name in required)]
    # Yosys may retain a final unchanged VCD snapshot. Locate the actual equal
    # predecessor and divergent successor rather than trusting timestamp names.
    for before, after in zip(states, states[1:]):
        gold_before = {name: before['g_' + name] for name in outputs}
        gate_before = {name: before['c_' + name] for name in outputs}
        if before['bad'] != 0 or gold_before != gate_before or after['bad'] != 1:
            continue
        values = {name: before[name] for name in inputs}
        if (values['enable'], values['outerIndex'], values['innerIndex']) != (1, 0, 1):
            continue
        gold_expected = update_state(case, gold_before, values)
        changed = dict(values)
        if mutation == 'disabled-write':
            changed['enable'] ^= 1
        elif mutation == 'wrong-axis':
            changed['outerIndex'], changed['innerIndex'] = values['innerIndex'], values['outerIndex']
        else:
            raise RuntimeError('unknown trace mutation')
        gate_expected = update_state(case, gate_before, changed)
        gold_after = {name: after['g_' + name] for name in outputs}
        gate_after = {name: after['c_' + name] for name in outputs}
        if gold_after != gold_expected or gate_after != gate_expected:
            raise RuntimeError('SAT trace does not follow independent sequential update oracle')
        if gold_after == gate_after or gold_before == gold_after:
            raise RuntimeError('mutation trace lacks an actual incorrect state transition')
        if mutation == 'disabled-write' and gate_after != gate_before:
            raise RuntimeError('disabled-write counterexample did not hold candidate state')
        if mutation == 'wrong-axis' and gate_after == gate_before:
            raise RuntimeError('wrong-axis counterexample did not write the other cell')
        return dict(initial_states_equal=True, native_transition_checked=True,
                    mutant_transition_checked=True, divergent_next_states=True)
    raise RuntimeError('counterexample lacks a complete known equal-to-divergent state transition')


def qualify_mutations(case, root):
    candidate = H.checked_rtl(root, 'candidate/' + MODULE + '.v')
    reference = H.checked_rtl(root, case['reference_rtl'])
    records = []
    for mutation in MUTATIONS:
        work = root / 'checks' / 'mutations' / mutation
        work.mkdir(parents=True, exist_ok=True)
        state = synthesis_and_inventory(case, work, candidate, 'candidate', 'named', mutation)
        top, script, trace = work / 'miter.v', work / 'mutation.ys', work / 'counterexample.vcd'
        trace.unlink(missing_ok=True)
        top.write_text(miter(case, 'named', mutation))
        # The off-diagonal legal address distinguishes swapped axes. Starting
        # both red carriers at zero and writing one makes both traces concrete.
        controls = ('-set-at 1 enable 1 -set-at 1 outerIndex 0 -set-at 1 innerIndex 1 '
                    '-set-at 1 replacement_red 1 -set-at 1 g_result_colors_red 0 '
                    '-set-at 1 c_result_colors_red 0 ')
        script.write_text(proof_setup([reference, candidate, top]) +
                          'sat -seq 2 -set-at 1 bad 0 -prove-skip 1 -prove bad 0 ' + controls +
                          '-timeout 120 -show-inputs -show-outputs -show-regs -dump_vcd ' + H.quoted(trace) + '\n')
        proof = H.command(['yosys', '-Q', '-T', '-s', str(script)], work / 'mutation.log')
        if H.COUNTEREXAMPLE not in proof or H.PASS in proof or not trace.is_file():
            raise RuntimeError('register mutation lacks actual SAT counterexample: ' + mutation)
        checked = validate_counterexample(case, mutation, trace)
        records.append(dict(mutation=mutation, result='COUNTEREXAMPLE', state_inventory=state,
                            trace_sha256=hashlib.sha256(trace.read_bytes()).hexdigest(), trace_validation=checked))
        print('PASS mutation ' + mutation + ': actual SAT trace follows both independent state updates', flush=True)
    return records


def qualify(root, replay, only=None):
    root, replay = root.resolve(), replay.resolve()
    evidence_path = root / ('evidence.json' if only is None else 'focused-evidence.json')
    evidence_path.unlink(missing_ok=True)
    for tool in ('yosys', 'iverilog', 'vvp', 'verilator'):
        if shutil.which(tool) is None:
            raise RuntimeError('required tool missing: ' + tool)
    manifest = json.loads((root / 'manifest.json').read_text())
    if (root / 'manifest.json').read_bytes() != (replay / 'manifest.json').read_bytes():
        raise RuntimeError('nondeterministic register manifest')
    if manifest.get('scope') != 'named-field-nested-register-native-equivalence':
        raise RuntimeError('incorrect register qualification scope')
    if manifest.get('candidate_default') != dict(width=5, blue_width=3, count=1, inner=1):
        raise RuntimeError('register candidate default changed')
    if manifest.get('dimension_order') != 'outer-major-inner-minor-element-zero-low':
        raise RuntimeError('register dimension order changed')
    cases = manifest['configurations']
    expected = {(w, b, n, i) for (w, b), n, i in itertools.product(((1, 5), (5, 3)), (1, 3), (1, 3))}
    if len(cases) != 8 or {(c['width'], c['blue_width'], c['count'], c['inner']) for c in cases} != expected:
        raise RuntimeError('incomplete standalone nested register matrix')
    for case in cases:
        if case.get('kind') != 'nested-register' or not H.IDENTIFIER.fullmatch(case['reference_module']):
            raise RuntimeError('invalid native register case')
    selected = cases if only is None else [case for case in cases if stem(case) == only]
    if not selected:
        raise RuntimeError('unknown selected register case')
    evidence = []
    for case in selected:
        evidence.extend(qualify_case(case, root, replay))
    required = [(stem(case), layout) for case in selected for layout in ('named', 'legacy')]
    if [(record['case'], record['layout']) for record in evidence] != required or any(record['proof'] != 'PASS' for record in evidence):
        raise RuntimeError('incomplete standalone register proof ledger')
    mutations = [] if only is not None else qualify_mutations(
        next(case for case in cases if (case['width'], case['count'], case['inner']) == (5, 3, 3)), root)
    if only is None and [record['mutation'] for record in mutations] != list(MUTATIONS):
        raise RuntimeError('incomplete register mutation ledger')
    evidence_path.write_text(json.dumps(dict(scope=manifest['scope'], layout_specializations=len(evidence),
        initial_state_relation=RELATION, initialized_register_values_assumed=False,
        configurations=evidence, mutation_controls=mutations), indent=2, sort_keys=True) + '\n')
    print(f'PASS nested register qualification: {len(evidence)} layouts, {len(mutations)} actual counterexamples', flush=True)


def self_test():
    import copy
    import tempfile
    case = dict(kind='nested-register', width=5, blue_width=3, count=3, inner=3,
                reference_module='NativeExample')
    inputs, outputs = schema(case)
    state = dict.fromkeys(outputs, 0)
    values = dict.fromkeys(inputs, 0)
    values.update(enable=1, outerIndex=0, innerIndex=1, replacement_red=1)
    updated = update_state(case, state, values)
    assert updated['result_colors_red'] == 1 << 5
    for axis, depth in [('outerIndex', 3), ('innerIndex', 3)]:
        for index in [depth, (1 << 32) | 1, (1 << 63) | 1]:
            assert update_state(case, state, dict(values, **{axis: index})) == state
    assert update_state(case, updated, dict(values, enable=0)) == updated
    assert len(stimuli(case)) == 105
    for layout in ('named', 'legacy'):
        text = miter(case, layout)
        assert '-set-init' not in text
        assert '.outerIndex(outerIndex)' in text and '.innerIndex(innerIndex)' in text
        assert '.enable(~enable)' in miter(case, layout, 'disabled-write')
        assert '.outerIndex(innerIndex)' in miter(case, layout, 'wrong-axis')
        assert text.count('assign bad =') == 1
    ports = dict(clk=dict(direction='input', bits=[2]))
    bits, offset = [], 10
    for name, width in outputs.items():
        vector = list(range(offset, offset + width))
        ports['out_' + name] = dict(direction='output', bits=vector)
        bits += vector
        offset += width
    doc = dict(modules=dict(specialized=dict(ports=ports, netnames={}, cells=dict(regs=dict(
        type='$dff', parameters=dict(CLK_POLARITY='1'), connections=dict(CLK=[2], Q=bits))))))
    assert inventory(case, doc)['ff_bits'] == 126
    corruptions = []
    for kind in ('hidden', 'duplicate', 'invisible', 'clock', 'edge', 'reset', 'init'):
        altered = copy.deepcopy(doc)
        mod = altered['modules']['specialized']
        if kind == 'hidden':
            mod['cells']['regs']['connections']['Q'].append(999)
        elif kind == 'duplicate':
            mod['cells']['regs']['connections']['Q'][1] = bits[0]
        elif kind == 'invisible':
            mod['ports']['out_result_colors_red']['bits'][0] = 999
        elif kind == 'clock':
            mod['cells']['regs']['connections']['CLK'] = [999]
        elif kind == 'edge':
            mod['cells']['regs']['parameters']['CLK_POLARITY'] = '0'
        elif kind == 'reset':
            mod['cells']['regs']['type'] = '$adff'
        else:
            mod['netnames']['state'] = dict(attributes=dict(init='0'))
        corruptions.append(altered)
    for altered in corruptions:
        try:
            inventory(case, altered)
        except RuntimeError:
            pass
        else:
            raise AssertionError('incomplete state inventory accepted')
    # A synthetic trace tests parsing and the independent temporal oracle;
    # qualification separately requires real Yosys FAIL and generated VCDs.
    with tempfile.TemporaryDirectory() as directory:
        for mutation in MUTATIONS:
            gate_values = dict(values)
            if mutation == 'disabled-write':
                gate_values['enable'] = 0
            else:
                gate_values['outerIndex'], gate_values['innerIndex'] = 1, 0
            gate_after = update_state(case, state, gate_values)
            before = dict(values, bad=0)
            after = dict(values, bad=1)
            for name in outputs:
                before['g_' + name] = before['c_' + name] = state[name]
                after['g_' + name], after['c_' + name] = updated[name], gate_after[name]
            widths = dict(inputs, bad=1)
            widths.update((role + '_' + name, width) for role in ('g', 'c') for name, width in outputs.items())
            vcd = ['$scope module miter $end']
            identifiers = {name: 'v' + str(index) for index, name in enumerate(widths)}
            vcd += [f'$var wire {width} {identifiers[name]} {name} $end' for name, width in widths.items()]
            vcd += ['$upscope $end', '$enddefinitions $end']
            for time, snapshot in [(0, before), (10, after)]:
                vcd += ['#' + str(time)] + [f'b{value:b} {identifiers[name]}' for name, value in snapshot.items()]
            path = Path(directory) / 'trace.vcd'
            path.write_text('\n'.join(vcd) + '\n')
            assert validate_counterexample(case, mutation, path)['divergent_next_states']
            path.write_text('\n'.join(vcd).replace('b1 ' + identifiers['bad'], 'b0 ' + identifiers['bad']) + '\n')
            try:
                validate_counterexample(case, mutation, path)
            except RuntimeError:
                pass
            else:
                raise AssertionError('nondivergent trace accepted')
    print('PASS nested register checker self-test: bounds, state inventory attacks, and temporal trace oracle')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', nargs='?', type=Path)
    parser.add_argument('replay', nargs='?', type=Path)
    parser.add_argument('--only')
    parser.add_argument('--self-test', action='store_true')
    args = parser.parse_args()
    if args.self_test:
        self_test()
    else:
        if args.root is None or args.replay is None:
            parser.error('ROOT and REPLAY are required')
        qualify(args.root, args.replay, args.only)


if __name__ == '__main__':
    main()
