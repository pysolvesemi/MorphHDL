#!/usr/bin/env python3
"""Qualify actual parameterized bridges against independent native sequential RTL."""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import importlib.util
import itertools
import json
import os
from pathlib import Path
import random
import re
import shutil
import sys


def load_helpers():
    spec = importlib.util.spec_from_file_location('bridge_tools', Path(__file__).with_name('check-increment-59b-operator-replay.py'))
    if spec is None or spec.loader is None:
        raise RuntimeError('missing strict HDL tool helpers')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


H = load_helpers()
SCOPE = 'parameterized-register-bridges'
OUTPUTS = ('identityResult', 'aliasResult', 'regResult', 'zeroResult', 'initResult',
           'levelResult', 'localEnableResult', 'localDisableResult',
           'signedResult', 'bitsResult', 'boolResult')
WIDTHS, COUNTS = (5, 8), (1, 2, 3, 5, 9)
CLOCK_FIELDS = ('reset_kind', 'reset_active_level', 'clock_edge', 'enable_active_level')
CLOCK_MATRIX = set(itertools.product(('SYNC', 'ASYNC'), ('HIGH', 'LOW'), ('RISING', 'FALLING'), ('HIGH', 'LOW', 'NONE')))
INPUTS = ('clk', 'reset', 'enable', 'dataIn', 'signedIn', 'bitsIn', 'boolIn')
INDUCTIVE_PASS = 'Induction step proven: SUCCESS!'
CYCLES = 128


def active(case: dict, field: str) -> int:
    return int(case[field].upper() == 'HIGH')


def edge(case: dict) -> str:
    return 'posedge' if case['clock_edge'].upper() == 'RISING' else 'negedge'


def condition(case: dict, signal: str) -> str:
    field = 'reset_active_level' if signal == 'reset' else 'enable_active_level'
    return "1'b1" if case[field] == 'NONE' else signal if active(case, field) else '!' + signal


def stages(name: str, level: int) -> int:
    if name in ('identityResult', 'aliasResult'):
        return 0
    if name == 'levelResult':
        return 0 if level == 0 else 2
    return 1


def initializer(name: str) -> int | None:
    return None if name == 'regResult' else 0 if name == 'zeroResult' else -1 if name == 'signedResult' else 1


def bitwidth(name: str, width: int) -> int:
    return 1 if name == 'boolResult' else width


def local_enabled(name: str, value: int, width: int) -> bool:
    if name in ('localEnableResult', 'localDisableResult', 'signedResult'):
        return bool(value & (1 << (width - 1))) == (name == 'localEnableResult')
    if name == 'bitsResult':
        return bool(value & 1)
    if name == 'boolResult':
        return not bool(value)
    return True


def latency(name: str, count: int) -> int:
    return sum(stages(name, level) for level in range((count - 1).bit_length()))


class TreeModel:
    """Independent cycle model: adjacent sums, odd tail, then each native bridge.

    None means unconstrained/uninitialized state, never an implicit zero. All
    next-state inputs use old state; local enables inspect each stage input.
    """
    def __init__(self, width: int, count: int, name: str):
        self.width, self.count, self.name = width, count, name
        self.mask = (1 << width) - 1
        self.state: dict[tuple[int, int, int], int | None] = {}
        level, size = 0, count
        while size > 1:
            size = (size + 1) // 2
            for node in range(size):
                for stage in range(stages(name, level)):
                    self.state[level, node, stage] = None
            level += 1

    def evaluate(self, words: tuple[int, ...]) -> tuple[int | None, dict]:
        if len(words) != self.count or any(not 0 <= word <= self.mask for word in words):
            raise ValueError('input shape or value outside declared width')
        values: list[int | None] = list(words)
        incoming, level = {}, 0
        while len(values) > 1:
            following = []
            for offset in range(0, len(values), 2):
                value = values[offset]
                if offset + 1 < len(values):
                    right = values[offset + 1]
                    value = None if value is None or right is None else ((value ^ right) if self.name in ('bitsResult', 'boolResult') else (value + right)) & self.mask
                for stage in range(stages(self.name, level)):
                    key = level, offset // 2, stage
                    incoming[key] = value
                    value = self.state[key]
                following.append(value)
            values, level = following, level + 1
        return values[0], incoming

    def reset(self) -> None:
        value = initializer(self.name)
        if value is not None:
            self.state = dict.fromkeys(self.state, value & self.mask)

    def tick(self, words: tuple[int, ...], enabled: bool, reset: bool, asynchronous: bool) -> None:
        _, incoming = self.evaluate(words)
        if reset and (asynchronous or enabled) and initializer(self.name) is not None:
            self.reset()
            return
        if not enabled:
            return
        next_state = self.state.copy()
        for key, value in incoming.items():
            if self.name in ('localEnableResult', 'localDisableResult', 'signedResult', 'bitsResult', 'boolResult'):
                if value is None:
                    # A four-state guard can hold unknown state. For the legal
                    # post-reset contract every input here is already defined.
                    continue
                if not local_enabled(self.name, value, self.width):
                    continue
            next_state[key] = value
        self.state = next_state


def bindings(prefix: str) -> str:
    return ', '.join([f'.{name}({name})' for name in INPUTS] +
                     [f'.{name}({prefix}_{name})' for name in OUTPUTS])


def instances(case: dict) -> list[str]:
    lines = []
    for prefix, role in (('g', 'reference'), ('c', 'candidate')):
        lines += [f'wire [{bitwidth(name, case["width"]) - 1}:0] {prefix}_{name};' for name in OUTPUTS]
        params = '' if role == 'reference' else f' #(.WIDTH({case["width"]}), .COUNT({case["count"]}))'
        lines += [f'{case[role + "_module"]}{params} {prefix}({bindings(prefix)});']
    return lines


def miter(case: dict, added_stage: bool = False) -> str:
    width, count = case['width'], case['count']
    levels = (count - 1).bit_length()
    lines = [f'module miter(input wire clk, reset, enable, input wire [{width * count - 1}:0] dataIn, signedIn, bitsIn,',
             f'input wire [{count - 1}:0] boolIn,',
             'output wire bad, output wire initialized_valid, output wire noinit_valid);', *instances(case)]
    if levels:
        # These initializers apply ONLY to checker-owned validity monitors.
        # Neither design's state is initialized or forced equal by the proof.
        lines += ['reg entered_reset = 1\'b0;', f'reg [{levels - 1}:0] observed_edges = {levels}\'h0;',
                  f'always @({edge(case)} clk) begin',
                  f'  if ({condition(case, "reset")} && {condition(case, "enable")}) entered_reset <= 1\'b1;',
                  f'  if ({condition(case, "enable")}) observed_edges <= ' +
                  ("1'b1;" if levels == 1 else f"{{observed_edges[{levels - 2}:0], 1'b1}};"),
                  'end', 'assign initialized_valid = entered_reset;',
                  f'assign noinit_valid = observed_edges[{levels - 1}];']
    else:
        lines += ["assign initialized_valid = 1'b1;", "assign noinit_valid = 1'b1;"]
    if added_stage:
        sensitivity = edge(case) + ' clk'
        if case['reset_kind'] == 'ASYNC':
            sensitivity += ' or ' + ('posedge' if active(case, 'reset_active_level') else 'negedge') + ' reset'
        lines += [f'reg [{width - 1}:0] added_result;', f'always @({sensitivity}) begin']
        if case['reset_kind'] == 'ASYNC':
            lines += [f'if ({condition(case, "reset")}) added_result <= {width}\'d1;',
                      f'else if ({condition(case, "enable")}) added_result <= c_initResult;']
        else:
            lines += [f'if ({condition(case, "enable")}) begin',
                      f'if ({condition(case, "reset")}) added_result <= {width}\'d1;',
                      'else added_result <= c_initResult;', 'end']
        lines += ['end']
    comparisons = []
    for name in OUTPUTS:
        valid = 'noinit_valid' if name == 'regResult' else 'initialized_valid'
        if not latency(name, count):
            valid = "1'b1"
        observed = 'added_result' if added_stage and name == 'initResult' else 'c_' + name
        lines.append(f'wire bad_{name} = ({valid} && (|(g_{name} ^ {observed})));')
        comparisons.append('bad_' + name)
    return '\n'.join(lines + ['assign bad = ' + ' | '.join(comparisons) + ';', 'endmodule', ''])


def specialized_top(case: dict) -> str:
    width, count = case['width'], case['count']
    return '\n'.join([
        f'module specialized(input wire clk, reset, enable, input wire [{width * count - 1}:0] dataIn, signedIn, bitsIn,',
        f'input wire [{count - 1}:0] boolIn,',
        ',\n'.join(f'output wire [{bitwidth(name, width) - 1}:0] c_{name}' for name in OUTPUTS) + ');',
        f'{case["candidate_module"]} #(.WIDTH({width}), .COUNT({count})) dut({bindings("c")});',
        'endmodule', ''])


def simulation_bench(case: dict) -> tuple[str, dict]:
    width, count = case['width'], case['count']
    mask = (1 << width) - 1
    rng = random.Random(5907000 + width * 100 + count)
    models = {name: TreeModel(bitwidth(name, width), count, name) for name in OUTPUTS}
    counters = dict(comparisons=0, invalid_observations=0, asynchronous_reset_observations=0,
                    disabled_reset_edges=0, in_flight_resets=0, enabled_edges=0)
    idle = int(edge(case) == 'negedge')
    reset_active, enable_active = active(case, 'reset_active_level'), active(case, 'enable_active_level')
    asynchronous = case['reset_kind'] == 'ASYNC'
    lines = ['`timescale 1ns/1ps', 'module tb;', 'reg clk, reset, enable;',
             f'reg [{width * count - 1}:0] dataIn, signedIn, bitsIn;', f'reg [{count - 1}:0] boolIn;', *instances(case), 'initial begin',
             f'clk={idle}; reset={1 - reset_active}; enable={1 - enable_active}; dataIn=0; signedIn=0; bitsIn=0; boolIn=0; #1;']
    # Check the absence of initialization directly before any reset/clock edge.
    if count > 1:
        for prefix in ('g', 'c'):
            lines += [f"if ((^{prefix}_regResult) !== 1'bx) begin",
                      '$display("59G-BRIDGE-MISMATCH uninitialized state was silently initialized"); $finish(1);', 'end']

    def compare(inputs: dict, tick: int, phase: str) -> None:
        for name, model in models.items():
            expected, _ = model.evaluate(inputs.get(name, inputs['data']))
            bits = bitwidth(name, width)
            if expected is None:
                counters['invalid_observations'] += 1
                continue
            for prefix in ('g', 'c'):
                counters['comparisons'] += 1
                lines.extend([f"if ({prefix}_{name} !== {bits}'h{expected:x}) begin",
                              f'$display("59G-BRIDGE-MISMATCH tick={tick} phase={phase} {prefix}_{name} got=%h expected=%h", {prefix}_{name}, {bits}\'h{expected:x}); $finish(1);', 'end'])

    for tick in range(CYCLES):
        words = tuple(rng.randrange(mask + 1) for _ in range(count))
        if tick % 9 == 0:
            words = (mask,) * count
        elif tick % 9 == 1:
            words = (0,) * count
        elif tick % 9 == 2:
            words = tuple((1 << (width - 1)) if i == (tick // 9) % count else 0 for i in range(count))
        signed_words = tuple((word ^ (1 << (width - 1)) ^ tick) & mask for word in reversed(words))
        bits_words = tuple((~word ^ ((tick + i) * 0x9e3779b1)) & mask for i, word in enumerate(words))
        flags = ((tick * 0x9e3779b1) ^ (tick >> 1)) & ((1 << count) - 1)
        boolean_words = tuple((flags >> i) & 1 for i in range(count))
        inputs = dict(data=words, signedResult=signed_words, bitsResult=bits_words, boolResult=boolean_words)
        reset = tick in (0, 1, 23, 24, 57, 58, 95, 96)
        enabled = tick not in (0, 23, 57, 95) and tick % 6 != 5 and not 38 <= tick < 45
        if case['enable_active_level'] == 'NONE':
            enabled = True
        counters['disabled_reset_edges'] += int(reset and not enabled)
        counters['in_flight_resets'] += int(reset and tick > 2)
        counters['enabled_edges'] += int(enabled)
        packed = sum(word << (i * width) for i, word in enumerate(words))
        signed_packed = sum(word << (i * width) for i, word in enumerate(signed_words))
        bits_packed = sum(word << (i * width) for i, word in enumerate(bits_words))
        lines += [f'reset={reset_active if reset else 1 - reset_active}; enable={enable_active if enabled else 1 - enable_active};',
                  f"dataIn={width * count}'h{packed:x}; signedIn={width * count}'h{signed_packed:x}; bitsIn={width * count}'h{bits_packed:x}; boolIn={count}'h{flags:x}; #2;"]
        if reset and asynchronous:
            for model in models.values():
                model.reset()
            counters['asynchronous_reset_observations'] += 1
        compare(inputs, tick, 'before-edge')
        lines += [f'clk={1 - idle}; #1;']
        for name, model in models.items():
            model.tick(inputs.get(name, inputs['data']), enabled, reset, asynchronous)
        compare(inputs, tick, 'active-edge')
        lines += [f'clk={idle}; #1;']
        compare(inputs, tick, 'inactive-edge')
    return '\n'.join(lines + ['$display("59G-BRIDGE-SIM-PASS"); $finish;', 'end', 'endmodule', '']), counters


def setup(paths: list[Path]) -> str:
    # async2sync is confined to the formal edge model. The original RTL is also
    # linted/synthesized and simulated with reset transitions between edges.
    return ('read_verilog ' + ' '.join(H.quoted(path) for path in paths) +
            '\nhierarchy -check -top miter\nproc\nflatten\nopt_expr\nopt_clean\n'
            'async2sync\ndffunmap\ncheck -assert\n')


def register_correspondence(module: dict, names=OUTPUTS) -> tuple[list[dict], dict]:
    """Suggest a state invariant by walking actual RTL cones from public outputs.

    This graph walk is only a lemma generator. Its results are additional proof
    obligations, not substitutions, initial conditions, assumptions or a proof.
    Every original cell and connection remains untouched in the strengthened
    design, including independent unconstrained registers on both sides.
    """
    cells, drivers = module['cells'], {}
    for name, cell in cells.items():
        for port, direction in cell['port_directions'].items():
            if direction == 'output':
                for index, bit in enumerate(cell['connections'][port]):
                    if isinstance(bit, int):
                        if bit in drivers:
                            raise RuntimeError('ambiguous state proof driver')
                        drivers[bit] = name, port, index
    visited, correspondences, owners = set(), set(), {}

    def pair(left, right, owner):
        if left == right:
            return
        if left not in drivers or right not in drivers:
            raise RuntimeError(f'cannot match native/candidate state cone at {owner}: {left}, {right}')
        gl, gp, gi = drivers[left]
        cr, cp, ci = drivers[right]
        g, c = cells[gl], cells[cr]
        if (gp, gi, g['type'], g['parameters'], g['port_directions']) != (cp, ci, c['type'], c['parameters'], c['port_directions']):
            raise RuntimeError(f'cannot match actual state cone at {owner}: {gl} ({g["type"]}), {cr} ({c["type"]})')
        if g['type'] == '$dff':
            correspondences.add((gl, cr))
            owners.setdefault(gl, set()).add(owner)
        key = gl, cr, owner
        if key in visited:
            return
        visited.add(key)
        for port, direction in g['port_directions'].items():
            if direction == 'input':
                a, b = g['connections'][port], c['connections'][port]
                if len(a) != len(b):
                    raise RuntimeError('state cone input width mismatch')
                for left_bit, right_bit in zip(a, b):
                    pair(left_bit, right_bit, owner)

    for name in names:
        gold = module['netnames']['g_' + name]['bits']
        candidate = module['netnames']['c_' + name]['bits']
        if len(gold) != len(candidate):
            raise RuntimeError('proof output width mismatch')
        for left, right in zip(gold, candidate):
            pair(left, right, name)

    dependencies = {}

    def preceding(bits, visited_cells):
        result = set()
        for bit in bits:
            if bit not in drivers:
                continue
            name = drivers[bit][0]
            if cells[name]['type'] == '$dff':
                result.add(name)
            elif name not in visited_cells:
                visited_cells.add(name)
                for port, direction in cells[name]['port_directions'].items():
                    if direction == 'input':
                        result.update(preceding(cells[name]['connections'][port], visited_cells))
        return result

    def depth(name, stack=frozenset()):
        if name in dependencies:
            return dependencies[name]
        if name in stack:
            raise RuntimeError('non-feedforward register dependency in native oracle')
        previous = preceding(cells[name]['connections']['D'], set()) - {name}
        value = 1 + max((depth(other, stack | {name}) for other in previous), default=0)
        dependencies[name] = value
        return value

    result = []
    for gold, candidate in sorted(correspondences):
        if not gold.startswith('$flatten\\g.') or not candidate.startswith('$flatten\\c.'):
            raise RuntimeError('state correspondence escaped independent DUT scopes')
        result.append(dict(reference=gold, candidate=candidate,
                           bits=len(cells[gold]['connections']['Q']), depth=depth(gold),
                           uninitialized='regResult' in owners[gold], outputs=sorted(owners[gold])))
    return result, drivers


def strengthen_state_invariant(work: Path, design: str, count: int) -> tuple[str, int]:
    """Prove all validity-gated intermediate state equalities alongside bad=0."""
    original = work / 'state-original.json'
    script = work / 'state-extraction.ys'
    script.write_text(design + 'write_json ' + H.quoted(original) + '\n')
    H.command(['yosys', '-Q', '-T', '-s', str(script)], work / 'state-extraction.log')
    document = json.loads(original.read_text())
    module = document['modules']['miter']
    pairs, _ = register_correspondence(module)
    if bool(pairs) != (count > 1):
        raise RuntimeError('missing or unexpected native bridge registers')
    # No optimization that merges independent state precedes this inspection.
    initialized_bits = {bit for net in module['netnames'].values()
                        if 'init' in net.get('attributes', {}) for bit in net['bits']}
    for name, cell in module['cells'].items():
        if name.startswith(('$flatten\\g.', '$flatten\\c.')) and cell['type'] == '$dff':
            if initialized_bits.intersection(cell['connections']['Q']):
                raise RuntimeError('unexpected DUT initial-state constraint')
    next_bit = 1 + max(bit for net in module['netnames'].values()
                       for bit in net['bits'] if isinstance(bit, int))
    serial = 0

    def expression(kind, a, b=None, width=1):
        nonlocal next_bit, serial
        result = list(range(next_bit, next_bit + width))
        next_bit += width
        connections = dict(A=a, Y=result)
        parameters = dict(A_SIGNED=0, A_WIDTH=len(a), Y_WIDTH=width)
        directions = dict(A='input', Y='output')
        if b is not None:
            connections['B'] = b
            parameters.update(B_SIGNED=0, B_WIDTH=len(b))
            directions['B'] = 'input'
        module['cells'][f'$59g_proved_invariant${serial}'] = dict(hide_name=1, type=kind,
            parameters=parameters, attributes={}, port_directions=directions, connections=connections)
        serial += 1
        return result

    properties = {name: module['netnames']['bad_' + name]['bits'].copy() for name in OUTPUTS}
    for relation in pairs:
        reference = module['cells'][relation['reference']]['connections']['Q']
        candidate = module['cells'][relation['candidate']]['connections']['Q']
        mismatch = expression('$reduce_or', expression('$xor', reference, candidate, relation['bits']))
        if relation['uninitialized']:
            fill = module['netnames']['observed_edges']['bits']
            if not 1 <= relation['depth'] <= len(fill):
                raise RuntimeError('uninitialized state depth exceeds actual bridge fill monitor')
            valid = [fill[relation['depth'] - 1]]
        else:
            valid = module['netnames']['initialized_valid']['bits']
        checked_relation = expression('$and', mismatch, valid)
        for name in relation['outputs']:
            properties[name].extend(checked_relation)
    bad = []
    for name, terms in properties.items():
        property_bits = expression('$reduce_or', terms)
        module['netnames']['proof_' + name] = dict(hide_name=0, bits=property_bits, attributes={})
        bad.extend(property_bits)
    strengthened = expression('$reduce_or', bad)
    module['ports']['bad']['bits'] = strengthened
    module['netnames']['bad']['bits'] = strengthened
    target = work / 'state-strengthened.json'
    target.write_text(json.dumps(document) + '\n')
    (work / 'state-correspondence.json').write_text(json.dumps(dict(
        contract='Every state relation is proved with base and induction, never assumed.',
        registers=pairs, original_sha256=hashlib.sha256(original.read_bytes()).hexdigest(),
        strengthened_sha256=hashlib.sha256(target.read_bytes()).hexdigest()), indent=2) + '\n')
    return 'read_json ' + H.quoted(target) + '\nhierarchy -check -top miter\ncheck -assert\n', len(pairs)


def prove_independent_outputs(work: Path, strengthened: str) -> None:
    """Conjunction decomposition, with each cone closed over its own state.

    Removing unused output flags only enables ordinary cone-of-influence
    pruning. No signal is cut and no DUT state is assigned a value or unified.
    Each property includes every register relation reachable in that output's
    native/candidate cones. Every property has its own initial-state base case
    and temporal induction step; proving all eleven proves their conjunction.
    """
    for name in OUTPUTS:
        work_property = work / 'properties' / name
        work_property.mkdir(parents=True, exist_ok=True)
        script = work_property / 'induction.ys'
        script.write_text(strengthened + 'delete -output miter/o:*\nexpose miter/w:proof_' + name +
            '\nopt_clean -purge\nopt_merge -keepdc t:$add t:$mux t:$logic_not t:$xor t:$or t:$and t:$reduce_or t:$logic_and\n'
            'check -assert\nsat -seq 1 -tempinduct -prove proof_' + name + ' 0 -verify -maxsteps 24 -timeout 60\n')
        definite_proof(H.command(['yosys', '-Q', '-T', '-s', str(script)],
                                work_property / 'induction.log', timeout=300), induction=True)
    (work / 'induction.log').write_text('PASS: all eleven output cones proved with native state base cases and temporal induction.\n')


def definite_proof(output: str, induction: bool = False) -> None:
    expected = INDUCTIVE_PASS if induction else H.PASS
    if expected not in output or H.COUNTEREXAMPLE in output:
        raise RuntimeError('missing definitive ' + ('temporal induction' if induction else 'SAT') + ' success')


def definite_counterexample(output: str, trace: Path) -> None:
    if H.COUNTEREXAMPLE not in output or H.PASS in output or INDUCTIVE_PASS in output:
        raise RuntimeError('mutation did not produce a genuine functional counterexample')
    H.require_counterexample_vcd(trace)


def candidate_contract(rtl: str, profile: dict) -> None:
    for parameter, field in (('WIDTH', 'width'), ('COUNT', 'count')):
        if not re.search(r'parameter\s+(?:integer\s+)?' + parameter + r'\s*=\s*' + str(profile[field]) + r'\b', rtl):
            raise RuntimeError('candidate lost symbolic parameter/default: ' + parameter)
    if 'genvar' not in rtl or 'begin : tail' not in rtl:
        raise RuntimeError('candidate lost native pairing/odd-tail generation')
    if len(re.findall(r'^module\s+' + re.escape(profile['module']) + r'\b', rtl, re.MULTILINE)) != 1:
        raise RuntimeError('candidate must contain its declared single parameterized top')


def run_case(root: Path, duplicate: Path, case: dict) -> dict:
    width, count = case['width'], case['count']
    label = f'{case["profile"]}_{case["clock_profile"]}_w{width}_n{count}'
    work = root / 'checks' / label
    work.mkdir(parents=True, exist_ok=True)
    paths, digests = [], {}
    for role in ('reference', 'candidate'):
        if not H.IDENTIFIER.fullmatch(case[role + '_module']):
            raise RuntimeError('invalid module identifier')
        path = H.checked_rtl(root, case[role + '_rtl'])
        if path.read_bytes() != H.checked_rtl(duplicate, case[role + '_rtl']).read_bytes():
            raise RuntimeError('nondeterministic ' + role + ': ' + label)
        paths.append(path)
        digests[role] = hashlib.sha256(path.read_bytes()).hexdigest()
    specialized = work / 'specialized.v'
    specialized.write_text(specialized_top(case))
    for role, module, sources in (('reference', case['reference_module'], [paths[0]]),
                                  ('candidate', 'specialized', [paths[1], specialized])):
        H.command(['verilator', '--lint-only', '--language', '1364-2001', '--top-module', module,
                   *map(str, sources)], work / (role + '-lint.log'))
        script = work / (role + '-synthesis.ys')
        script.write_text('read_verilog ' + ' '.join(H.quoted(path) for path in sources) +
                          f'\nhierarchy -check -top {module}\nsynth -top {module}\ncheck -assert\nstat\n')
        H.command(['yosys', '-Q', '-T', '-s', str(script)], work / (role + '-synthesis.log'))
    bench = work / 'tb.v'
    source, observations = simulation_bench(case)
    bench.write_text(source)
    executable = work / 'tb.vvp'
    H.command(['iverilog', '-g2001', '-s', 'tb', '-o', str(executable), *map(str, paths), str(bench)], work / 'compile.log')
    output = H.command(['vvp', str(executable)], work / 'simulation.log')
    if '59G-BRIDGE-SIM-PASS' not in output or '59G-BRIDGE-MISMATCH' in output:
        raise RuntimeError('native/candidate/independent integer simulation failed: ' + label)
    top = work / 'miter.v'
    top.write_text(miter(case))
    design = setup(paths + [top])
    strengthened, state_relations = strengthen_state_invariant(work, design, count)
    script = work / 'reset-entry.ys'
    script.write_text(design + f'sat -seq 2 -set-at 1 reset {active(case, "reset_active_level")} '
                      f'-set-at 1 enable {active(case, "enable_active_level")} '
                      '-prove bad 0 -prove-skip 1 -verify -timeout 60\n')
    definite_proof(H.command(['yosys', '-Q', '-T', '-s', str(script)], work / 'reset-entry.log'))
    prove_independent_outputs(work, strengthened)
    result = dict(profile=case['profile'], clock_profile=case['clock_profile'], width=width, count=count,
                  cycles=CYCLES, observations=observations, sha256=digests, reset_entry='PASS', induction='PASS',
                  proved_state_relations=state_relations,
                  latency={name: latency(name, count) for name in OUTPUTS if name not in ('localEnableResult', 'localDisableResult', 'signedResult', 'bitsResult', 'boolResult')})
    print('PASS:', label, 'independent sequential simulation, strict tools, reset entry and induction', flush=True)
    return result


# Mutations are qualification-only transformations, never production recognizers.
# They refuse missing/ambiguous anchors so a parser miss cannot look like a pass.
def mutate_initializer(rtl: str) -> str:
    fixed = re.compile(r"(<=\s*)(\d+)'([hdb])0*1(\s*;)")
    symbolic = re.compile(r"(<=\s*\{\{\(WIDTH - 1\)\{1'b0\}\},\s*)1'b1(\};)")
    changed, fixed_count = fixed.subn(lambda match: match.group(1) + match.group(2) + "'" + match.group(3) + '0' + match.group(4), rtl)
    changed, symbolic_count = symbolic.subn(r"\g<1>1'b0\g<2>", changed)
    if fixed_count + symbolic_count == 0:
        raise RuntimeError('missing nonzero native reset initializer mutation anchor')
    return changed


def mutate_removed_stage(rtl: str) -> str:
    # A native bridge register's data assignment is bypassed at every read. Its
    # own declaration/process stay intact, so this removes latency rather than
    # manufacturing a parse failure or a combinational arithmetic mutation.
    pattern = re.compile(r'(?m)^\s*([A-Za-z_][A-Za-z0-9_]*)\s*<=\s*([A-Za-z_][A-Za-z0-9_]*)\s*;')
    candidates = [match for match in pattern.finditer(rtl)
                  if 'morphhdl_balanced_5_' in match.group(1) and 'l0_' in match.group(1)]
    if not candidates:
        raise RuntimeError('missing initialized bridge register removal anchor')
    match = candidates[0]
    register, incoming = match.group(1), match.group(2)
    token = re.compile(r'\b' + re.escape(register) + r'\b')
    changed, replacements = [], 0
    for line in rtl.splitlines(keepends=True):
        if re.match(r'\s*(?:reg|wire|input|output)\b', line) or re.match(r'\s*' + re.escape(register) + r'\s*<=', line):
            changed.append(line)
        else:
            new, number = token.subn(incoming, line)
            changed.append(new)
            replacements += number
    if replacements == 0:
        raise RuntimeError('removed-stage mutation changed no register read')
    return ''.join(changed)


def mutate_precedence(rtl: str, case: dict) -> str:
    # For native SYNC semantics the domain CE gates reset. Making CE active
    # during reset makes reset dominate CE while leaving ordinary data enables
    # unchanged. Directed/formal disabled reset cycles distinguish this policy.
    if case['reset_kind'] != 'SYNC':
        raise RuntimeError('precedence mutation requires a synchronous profile')
    enable = condition(case, 'enable')
    pattern = re.compile(r'if\s*\(\s*' + re.escape(enable) + r'\s*\)')
    replacement = f'if (({enable}) || ({condition(case, "reset")}))'
    changed, count = pattern.subn(replacement, rtl)
    if count == 0:
        raise RuntimeError('missing native clock-enable precedence mutation anchor')
    return changed


def run_mutations(root: Path, cases: list[dict]) -> list[str]:
    case = next(case for case in cases if case['profile'] == 'singleton' and case['width'] == 5 and case['count'] == 5
                and tuple(case[field] for field in CLOCK_FIELDS) == ('SYNC', 'HIGH', 'RISING', 'HIGH'))
    reference = H.checked_rtl(root, case['reference_rtl'])
    candidate = H.checked_rtl(root, case['candidate_rtl'])
    mutations = [('added-stage', None), ('removed-stage', mutate_removed_stage),
                 ('wrong-initializer', mutate_initializer), ('reset-enable-precedence', lambda rtl: mutate_precedence(rtl, case))]
    for label, mutate in mutations:
        work = root / 'checks' / ('mutation-' + label)
        work.mkdir(parents=True, exist_ok=True)
        source = candidate
        if mutate is not None:
            changed = mutate(candidate.read_text())
            if changed == candidate.read_text():
                raise RuntimeError('mutation did not modify actual candidate RTL: ' + label)
            source = work / 'candidate-mutated.v'
            source.write_text(changed)
        top = work / 'miter.v'
        top.write_text(miter(case, added_stage=label == 'added-stage'))
        trace = work / 'counterexample.vcd'
        trace.unlink(missing_ok=True)
        script = work / 'mutation.ys'
        script.write_text(setup([reference, source, top]) +
                          f'sat -seq 12 -set-at 1 reset {active(case, "reset_active_level")} '
                          f'-set-at 1 enable {active(case, "enable_active_level")} '
                          '-prove bad 0 -show-inputs -show-outputs -timeout 60 -dump_vcd ' + H.quoted(trace) + '\n')
        result = H.command(['yosys', '-Q', '-T', '-s', str(script)], work / 'mutation.log')
        definite_counterexample(result, trace)
        print('PASS: genuine sequential bad=1 counterexample:', label, flush=True)
    return [name for name, _ in mutations]


def qualify(root: Path, duplicate: Path, only_case: str | None, jobs: int) -> None:
    root, duplicate = root.resolve(), duplicate.resolve()
    if only_case is None:
        if root == duplicate:
            raise RuntimeError('full evidence requires two independently generated artifact directories')
        (root / 'evidence.json').unlink(missing_ok=True)
    for tool in ('iverilog', 'vvp', 'verilator', 'yosys'):
        if shutil.which(tool) is None:
            raise RuntimeError('required tool missing: ' + tool)
    manifest = json.loads((root / 'manifest.json').read_text())
    if manifest.get('scope') != SCOPE or manifest.get('outputs') != list(OUTPUTS):
        raise RuntimeError('incorrect scope or bridge output inventory')
    if manifest.get('independent_inputs') != list(INPUTS):
        raise RuntimeError('clock/reset/enable/data inputs must remain independent')
    if (root / 'manifest.json').read_bytes() != (duplicate / 'manifest.json').read_bytes():
        raise RuntimeError('nondeterministic manifest')
    configurations, profiles = manifest['configurations'], manifest['profiles']
    for item in configurations + profiles:
        for field in CLOCK_FIELDS:
            item[field] = item[field].upper()
    shapes = [(*(case[field] for field in CLOCK_FIELDS), case['width'], case['count']) for case in configurations]
    expected = set((*clock, width, count) for clock in CLOCK_MATRIX if clock[-1] != 'NONE' for width in WIDTHS for count in COUNTS)
    expected.update((*clock, 5, 3) for clock in CLOCK_MATRIX if clock[-1] == 'NONE')
    expected.update(('SYNC', 'HIGH', 'RISING', 'HIGH', width, count) for width in H.WIDTHS for count in H.COUNTS)
    if len(shapes) != len(expected) or set(shapes) != expected:
        raise RuntimeError('incomplete or duplicated 24-clock/width/count native matrix')
    profile_shapes = [(*(profile[field] for field in CLOCK_FIELDS), profile['profile'], profile['width'], profile['count'])
                      for profile in profiles]
    expected_profiles = set((*clock, 'singleton', 5, 1) for clock in CLOCK_MATRIX)
    expected_profiles.add(('SYNC', 'HIGH', 'RISING', 'HIGH', 'alternate', 8, 3))
    if len(profile_shapes) != len(expected_profiles) or set(profile_shapes) != expected_profiles:
        raise RuntimeError('all clock profiles require singleton defaults and c0 requires alternate defaults')
    if len({profile['rtl'] for profile in profiles}) != len(profiles):
        raise RuntimeError('distinct profile candidates must remain separate artifacts')
    cases = []
    for profile in profiles:
        path = H.checked_rtl(root, profile['rtl'])
        candidate_contract(path.read_text(), profile)
        for original in configurations:
            if original['clock_profile'] != profile['clock_profile']:
                continue
            if any(original[field] != profile[field] for field in CLOCK_FIELDS):
                raise RuntimeError('candidate/reference clock metadata disagree')
            cases.append(dict(original, profile=profile['profile'], candidate_module=profile['module'], candidate_rtl=profile['rtl']))
    if len(cases) != 222:
        raise RuntimeError('incomplete native/candidate specialization matrix')
    selected = [case for case in cases if only_case is None or only_case ==
                f'{case["profile"]}_{case["clock_profile"]}_w{case["width"]}_n{case["count"]}']
    if not selected:
        raise RuntimeError('unknown focused case; use PROFILE_CLOCK_wWIDTH_nCOUNT from manifest')
    with ThreadPoolExecutor(max_workers=jobs) as pool:
        evidence = list(pool.map(lambda case: run_case(root, duplicate, case), selected))
    if only_case:
        print('PASS: focused development case only; complete evidence not emitted', flush=True)
        return
    mutations = run_mutations(root, cases)
    (root / 'evidence.json').write_text(json.dumps(dict(scope=SCOPE,
        finite_matrix_note='Finite specializations do not universally quantify WIDTH or COUNT.',
        uninitialized_contract='DUT register state remains unconstrained; regResult valid after ceil(log2 COUNT) enabled edges. COUNT=1 is always valid.',
        initialization_contract='Resettable observations valid after a native enabled reset edge. Only checker validity registers have explicit initial state.',
        temporal_contract='Unbounded induction with arbitrary subsequent reset, clock enable and inputs; no set-init-zero, zinit or assume-equal DUT state.',
        induction_contract='Validity-gated actual intermediate register equality is included in bad and proved, never assumed. Native and candidate state remain separate.',
        asynchronous_contract='Native simulation checks assertion between edges and both clock transitions; sequential formal uses Yosys async2sync edge semantics.',
        local_enable_contract='UInt MSB/complemented-MSB, SInt complemented-MSB, Bits bit-zero and complemented-Bool guards inspect each native stage input; their stall latency is data dependent.',
        configurations=evidence, mutation_controls=mutations), indent=2) + '\n')
    print('PASS: 222 parameterized bridge specializations, 24 clock/enable profiles and four functional mutations', flush=True)


def self_test() -> None:
    assert latency('regResult', 1) == 0 and latency('regResult', 5) == 3
    assert latency('levelResult', 5) == 4 and latency('aliasResult', 9) == 0
    direct = TreeModel(5, 5, 'identityResult')
    assert direct.evaluate((1, 2, 3, 4, 5))[0] == 15
    register = TreeModel(5, 5, 'regResult')
    words = (1, 2, 3, 4, 5)
    assert register.evaluate(words)[0] is None
    register.reset()
    assert register.evaluate(words)[0] is None
    for _ in range(2):
        register.tick(words, True, False, False)
        assert register.evaluate(words)[0] is None
    register.tick(words, False, False, False)
    assert register.evaluate(words)[0] is None
    register.tick(words, True, False, False)
    assert register.evaluate(words)[0] == 15
    initialized = TreeModel(5, 3, 'initResult')
    initialized.tick((1, 2, 3), False, True, False)
    assert initialized.evaluate((1, 2, 3))[0] is None
    initialized.tick((1, 2, 3), True, True, False)
    assert initialized.evaluate((1, 2, 3))[0] == 1
    initialized.tick((1, 2, 3), True, False, False)
    assert initialized.evaluate((1, 2, 3))[0] == 2
    initialized.tick((1, 2, 3), True, False, False)
    assert initialized.evaluate((1, 2, 3))[0] == 6
    initialized.tick((1, 2, 3), False, True, True)
    assert initialized.evaluate((1, 2, 3))[0] == 1
    for name, enabled_words, stalled_words in (('localEnableResult', (8, 8), (1, 2)),
                                               ('localDisableResult', (1, 2), (8, 8))):
        model = TreeModel(5, 2, name)
        model.reset()
        model.tick(enabled_words, True, False, False)
        captured = sum(enabled_words) & 31
        assert model.evaluate(enabled_words)[0] == captured
        model.tick(stalled_words, True, False, False)
        assert model.evaluate(stalled_words)[0] == captured
    signed = TreeModel(5, 2, 'signedResult')
    signed.reset()
    assert signed.evaluate((0, 0))[0] == 31
    signed.tick((31, 1), True, False, False)
    assert signed.evaluate((0, 0))[0] == 0
    signed.tick((15, 1), True, False, False)
    assert signed.evaluate((0, 0))[0] == 0
    bits = TreeModel(5, 2, 'bitsResult')
    bits.reset()
    bits.tick((2, 5), True, False, False)
    assert bits.evaluate((0, 0))[0] == 7
    bits.tick((2, 4), True, False, False)
    assert bits.evaluate((0, 0))[0] == 7
    boolean = TreeModel(1, 2, 'boolResult')
    boolean.reset()
    boolean.tick((0, 1), True, False, False)
    assert boolean.evaluate((0, 0))[0] == 1
    boolean.tick((1, 1), True, False, False)
    assert boolean.evaluate((0, 0))[0] == 0
    sample = "reg [4:0] morphhdl_balanced_5_l0_register;\nassign result = morphhdl_balanced_5_l0_register;\n  morphhdl_balanced_5_l0_register <= incoming;\n  morphhdl_balanced_5_l0_register <= 5'h01;\n"
    assert 'assign result = incoming;' in mutate_removed_stage(sample)
    assert "<= 5'h0;" in mutate_initializer(sample)
    assert "<= {{(WIDTH - 1){1'b0}}, 1'b0};" in mutate_initializer("result <= {{(WIDTH - 1){1'b0}}, 1'b1};")
    case = dict(reset_kind='SYNC', reset_active_level='HIGH', enable_active_level='HIGH')
    assert 'if ((enable) || (reset))' in mutate_precedence('if(enable) begin\n', case)
    for mutation in (mutate_initializer, mutate_removed_stage, lambda text: mutate_precedence(text, case)):
        try:
            mutation('module absent; endmodule')
        except RuntimeError:
            pass
        else:
            raise RuntimeError('missing mutation anchor incorrectly accepted')
    for text in ('timeout', H.PASS, H.COUNTEREXAMPLE + '\n' + H.PASS):
        try:
            definite_counterexample(text, Path('/nonexistent-counterexample.vcd'))
        except RuntimeError:
            pass
        else:
            raise RuntimeError('non-counterexample incorrectly accepted')
    # The waveform gate itself must reject a syntactically valid bad=0 trace.
    import tempfile
    with tempfile.TemporaryDirectory(prefix='59g-mutation-self-test-') as temporary:
        trace = Path(temporary) / 'counterexample.vcd'
        header = '$var wire 1 v0 bad $end\n$enddefinitions $end\n#1\n'
        trace.write_text(header + 'b0 v0\n')
        try:
            definite_counterexample(H.COUNTEREXAMPLE, trace)
        except RuntimeError:
            pass
        else:
            raise RuntimeError('bad=0 waveform incorrectly accepted')
        trace.write_text(header + 'b1 v0\n')
        definite_counterexample(H.COUNTEREXAMPLE, trace)
    print('PASS: independent sequential/validity model, local guards, mutation anchors and definitive counterexample gates')


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path, nargs='?')
    parser.add_argument('duplicate', type=Path, nargs='?')
    parser.add_argument('--case', dest='only_case', help='Focused PROFILE_CLOCK_wWIDTH_nCOUNT; does not emit full evidence')
    parser.add_argument('--jobs', type=int, default=min(4, os.cpu_count() or 1))
    parser.add_argument('--self-test', action='store_true')
    args = parser.parse_args()
    if args.self_test:
        self_test()
    elif args.root is None or args.duplicate is None:
        parser.error('provide two independently generated artifact directories')
    elif args.jobs < 1:
        parser.error('--jobs must be positive')
    else:
        qualify(args.root, args.duplicate, args.only_case, args.jobs)


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, RuntimeError, KeyError) as error:
        print('FAIL: ' + str(error), file=sys.stderr)
        sys.exit(1)
