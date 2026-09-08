#!/usr/bin/env python3
"""Strict qualification of 59i's first scoped, fixed-shape composite join.

One independent native reference is compared with all layout, signedness and
alternate-default candidates. Adapters only permute wires. This is deliberately
not the complete 59i pairwise/end-to-end closure certificate.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import importlib.util
import itertools
import json
import random
import re
import shutil
from pathlib import Path


def load_helpers():
    spec = importlib.util.spec_from_file_location('combined_hdl_tools',
        Path(__file__).with_name('check-increment-59b-operator-replay.py'))
    if spec is None or spec.loader is None:
        raise RuntimeError('strict inherited tool helpers are missing')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


H = load_helpers()
SCOPE = '59i-scoped-fixed-shape-record-join'
WIDTHS = (1, 5, 8, 32)
COUNTS = (1, 2, 3, 5, 8, 9, 16, 17)
PROFILES = set(itertools.product(('packed', 'fields'), ('legacy', 'declarations', 'casts'), (1, 5)))
UNEQUAL = ((1, 3, 7), (5, 1, 8), (8, 32, 1), (32, 5, 7))
SHAPES = {(w, w, w, n, m) for w, n, m in itertools.product(WIDTHS, COUNTS, (0, 1))} | {
    (w, t, c, n, m) for (w, t, c), n, m in itertools.product(UNEQUAL, (1, 3, 5, 17), (0, 1))}
INDUCTION_PASS = 'Induction step proven: SUCCESS!'
SIM_PASS = '59I-SCOPED-RECORD-SIM-PASS'
SIM_FAIL = '59I-SCOPED-RECORD-MISMATCH'
MUTATIONS = ('field-misbinding', 'signed-key-misbinding', 'wrong-branch',
             'ignored-enable', 'wrong-reset-value', 'reset-overrides-enable', 'bypass-latency')
SEQUENTIAL_MUTATIONS = frozenset(('ignored-enable', 'wrong-reset-value',
                                 'reset-overrides-enable', 'bypass-latency'))


def require(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(message)


def shape(case: dict) -> tuple[int, ...]:
    return tuple(case[k] for k in ('width', 'tag_width', 'coord_width', 'count', 'mode'))


def profile(candidate: dict) -> tuple:
    return candidate['layout'], candidate['signed_mode'], candidate['default_count']


def validate_manifest(manifest: dict) -> None:
    require(set(manifest) == {'schema', 'scope', 'candidates', 'cases'} and
            manifest['schema'] == 1 and manifest['scope'] == SCOPE, 'invalid 59i slice scope/schema')
    candidates, cases = manifest['candidates'], manifest['cases']
    require(isinstance(candidates, list) and isinstance(cases, list), 'matrix entries must be lists')
    require(all(isinstance(c, dict) and set(c) ==
                {'layout', 'signed_mode', 'default_count', 'module', 'file'} for c in candidates),
            'invalid candidate schema')
    require(all(isinstance(c, dict) and set(c) ==
                {'id', 'width', 'tag_width', 'coord_width', 'count', 'mode', 'module', 'file'} for c in cases),
            'invalid reference schema')
    require(all(type(c['default_count']) is int for c in candidates), 'non-integer candidate default')
    require(all(isinstance(c['id'], str) and H.IDENTIFIER.fullmatch(c['id']) for c in cases),
            'invalid case identity')
    require(len(candidates) == len(PROFILES) and {profile(c) for c in candidates} == PROFILES,
            'missing/duplicated 59i layout/signedness/default profile')
    require(len(cases) == len(SHAPES) and {shape(c) for c in cases} == SHAPES,
            'missing/duplicated 59i WIDTH/COUNT/independent-field specialization')
    require(all(type(c[k]) is int for c in cases
                for k in ('width', 'tag_width', 'coord_width', 'count', 'mode')),
            'non-integer shape')
    for collection in (candidates, cases):
        require(len({c['file'] for c in collection}) == len(collection), 'reused RTL artifact')
        require(len({c['module'] for c in collection}) == len(collection), 'reused module identity')
        require(all(H.IDENTIFIER.fullmatch(c['module']) for c in collection), 'invalid module identity')
    require(len({c['id'] for c in cases}) == len(cases), 'reused case identity')
    require(not {c['module'] for c in candidates} & {c['module'] for c in cases},
            'candidate and native module identities overlap')
    require(not {c['file'] for c in candidates} & {c['file'] for c in cases},
            'candidate and native artifact identities overlap')


def fields(case: dict) -> dict[str, tuple[tuple[str, int], ...]]:
    w, t, c, _, _ = shape(case)
    return {'records': (('key', w), ('tag', t), ('x', c), ('y', c)),
            'signedRecords': (('real', w), ('imag', w))}


def outputs(case: dict) -> dict[str, int]:
    f = fields(case)
    return {**{p + '_' + n: w for p in ('selected', 'delayed') for n, w in f['records']},
            **{'signedSelected_' + n: w for n, w in f['signedRecords']}}


def input_widths(case: dict) -> dict[str, int]:
    return {'clk': 1, 'reset': 1, 'enable': 1,
            **{p: sum(w for _, w in leaves) * case['count'] for p, leaves in fields(case).items()}}


def field_wiring(case: dict, prefix: str) -> tuple[list[str], list[str]]:
    """The sole layout adapter: declaration, concatenation and constant slices.

    No condition, arithmetic datapath, callback, register or behavioral model
    appears here. Index arithmetic below only computes elaborated wire offsets.
    """
    lines, bindings = [], []
    for port, leaves in fields(case).items():
        stride, offset = sum(w for _, w in leaves), 0
        for name, width in leaves:
            target = prefix + port + '_' + name
            pieces = [f'{port}[{lane * stride + offset} +: {width}]'
                      for lane in reversed(range(case['count']))]
            rhs = pieces[0] if len(pieces) == 1 else '{' + ', '.join(pieces) + '}'
            lines += [f'wire [{width * case["count"] - 1}:0] {target};', f'assign {target} = {rhs};']
            bindings += [f'.{port}_{name}({target})']
            offset += width
    return lines, bindings


def instances(case: dict, candidates: list[dict]) -> list[str]:
    lines = []
    for i, candidate in enumerate([None] + candidates):
        prefix = 'g_' if candidate is None else f'c{i - 1}_'
        lines += [f'wire [{width - 1}:0] {prefix}{name};' for name, width in outputs(case).items()]
        bindings = [f'.{p}({p})' for p in ('clk', 'reset', 'enable')]
        if candidate is not None and candidate['layout'] == 'fields':
            wiring, data_bindings = field_wiring(case, prefix)
            lines += wiring
            bindings += data_bindings
        else:
            bindings += [f'.{p}({p})' for p in fields(case)]
        bindings += [f'.{p}({prefix}{p})' for p in outputs(case)]
        if candidate is None:
            module, params = case['module'], ''
        else:
            module = candidate['module']
            w, t, c, n, m = shape(case)
            params = f' #(.WIDTH({w}), .TAG_WIDTH({t}), .COORD_WIDTH({c}), .COUNT({n}), .MODE({m}))'
        lines += [f'{module}{params} {prefix}dut(' + ', '.join(bindings) + ');']
    return lines


def miter(case: dict, candidates: list[dict], sequential: bool) -> str:
    lines = ['module miter(' + ',\n'.join(
        [f'input wire [{w - 1}:0] {p}' for p, w in input_widths(case).items()] + ['output wire bad']) + ');']
    lines += instances(case, candidates)
    checked = [p for p in outputs(case) if sequential or not p.startswith('delayed_')]
    lines += ['assign bad = ' + ' | '.join(f'(|(g_{p} ^ c{i}_{p}))'
        for i in range(len(candidates)) for p in checked) + ';', 'endmodule', '']
    return '\n'.join(lines)


def decode(packed: int, leaves: tuple, count: int) -> list[tuple[int, ...]]:
    result = []
    for _ in range(count):
        row = []
        for _, width in leaves:
            row.append(packed & ((1 << width) - 1))
            packed >>= width
        result.append(tuple(row))
    return result


def select(rows: list[tuple], maximum: bool, signed_width: int | None = None) -> tuple:
    def key(row):
        value = row[0]
        if signed_width is not None and value & (1 << (signed_width - 1)):
            value -= 1 << signed_width
        return value
    # Stable min/max of a list deliberately does not reconstruct the RTL tree.
    return (max if maximum else min)(rows, key=key)


class Pipeline:
    """Independent enabled-register state model, including native odd tails."""
    def __init__(self, count: int):
        require(type(count) is int and count >= 1, 'pipeline requires a positive COUNT')
        self.count = count
        self.initialized = count == 1
        self.state = []
        while count > 1:
            count = (count + 1) // 2
            self.state.append([(0, 0, 0, 0)] * count)

    def step(self, rows: list[tuple], reset: bool, enable: bool, maximum: bool) -> tuple:
        if self.count == 1:
            return rows[0]
        # This fixture uses native SYNC reset and a clock-domain enable:
        # if (enable) { if (reset) ... else ... }. A stalled reset is NOT an
        # initialization event. Other clock profiles need separate contracts.
        require(self.initialized or (reset and enable),
                'registered state cannot be observed before an enabled reset')
        if enable:
            if reset:
                self.state = [[(0, 0, 0, 0)] * len(level) for level in self.state]
                self.initialized = True
            else:
                # Every level samples the OLD preceding level at this edge.
                before = [rows] + self.state[:-1]
                self.state = [[select(values[i:i + 2], maximum) for i in range(0, len(values), 2)]
                              for values in before]
        return self.state[-1][0]


def samples(case: dict) -> list[dict]:
    n = case['count']
    data_widths = {p: sum(w for _, w in leaves) * n for p, leaves in fields(case).items()}
    values = [{p: 0 for p in data_widths}, {p: (1 << w) - 1 for p, w in data_widths.items()}]
    # Independent one-lane/one-field patterns, including sign extrema and tags.
    for port, leaves in fields(case).items():
        stride, offset = sum(w for _, w in leaves), 0
        for _, width in leaves:
            for lane in range(n):
                for value in (1, 1 << (width - 1), (1 << width) - 1):
                    values.append({p: (value << (lane * stride + offset)) if p == port else 0
                                   for p in data_widths})
            offset += width
    rng = random.Random(5909 + sum(shape(case)))
    values += [{p: rng.getrandbits(w) for p, w in data_widths.items()} for _ in range(128)]
    return values


def stimulus(case: dict) -> list[tuple[dict, bool, bool]]:
    widths = {p: w for p, w in input_widths(case).items() if p in fields(case)}
    zero = {p: 0 for p in widths}
    nonzero = {p: (1 << w) - 1 for p, w in widths.items()}
    depth = (case['count'] - 1).bit_length()
    # Establish real native state with an ENABLED reset. Fill every stage with
    # nonzero data before asserting a stalled reset, so wrong precedence
    # cannot hide behind a zero-valued pipeline. Then exercise enabled reset.
    trace = [(zero, True, True)]
    trace += [(nonzero, False, True)] * depth
    trace += [(nonzero, True, False), (nonzero, False, False), (zero, True, True)]
    trace += [(sample, cycle % 53 == 17, cycle % 7 not in (0, 1, 5))
              for cycle, sample in enumerate(samples(case))]
    return trace


def testbench(case: dict, candidates: list[dict]) -> tuple[str, int]:
    lines = ['`timescale 1ns/1ps', 'module tb;']
    lines += [f'reg [{w - 1}:0] {p};' for p, w in input_widths(case).items()]
    lines += instances(case, candidates)
    lines += ['initial begin', 'clk = 0; reset = 1; enable = 1;']
    state = Pipeline(case['count'])
    vectors = stimulus(case)
    f = fields(case)

    def compare(expected: dict, phase: str, cycle: int) -> None:
        for prefix in ['g_'] + [f'c{i}_' for i in range(len(candidates))]:
            for name, value in expected.items():
                width = outputs(case)[name]
                lines.extend([f"if ({prefix}{name} !== {width}'h{value:x}) begin",
                    f'$display("{SIM_FAIL} {prefix}{name} {phase} cycle={cycle}"); $finish(1);', 'end'])

    for cycle, (sample, reset, enable) in enumerate(vectors):
        rows = decode(sample['records'], f['records'], case['count'])
        signed_rows = decode(sample['signedRecords'], f['signedRecords'], case['count'])
        selected = select(rows, bool(case['mode']))
        signed = select(signed_rows, bool(case['mode']), case['width'])
        expected = {**{'selected_' + p: v for (p, _), v in zip(f['records'], selected)},
                    **{'signedSelected_' + p: v for (p, _), v in zip(f['signedRecords'], signed)}}
        lines += [f'clk = 0; reset = {int(reset)}; enable = {int(enable)};']
        lines += [f"{p} = {input_widths(case)[p]}'h{v:x};" for p, v in sample.items()]
        lines += ['#1;']
        if cycle:
            prior = rows[0] if case['count'] == 1 else state.state[-1][0]
            compare(dict(expected, **{'delayed_' + p: v for (p, _), v in zip(f['records'], prior)}),
                    'before-edge', cycle)
        delayed = state.step(rows, reset, enable, bool(case['mode']))
        lines += ['clk = 1; #1;']
        compare(dict(expected, **{'delayed_' + p: v for (p, _), v in zip(f['records'], delayed)}),
                'after-edge', cycle)
    lines += [f'$display("{SIM_PASS}"); $finish;', 'end', 'endmodule', '']
    return '\n'.join(lines), len(vectors)


def setup(paths: list[Path], zero_init: bool = False) -> str:
    result = 'read_verilog ' + ' '.join(H.quoted(p) for p in paths)
    result += '\nprep -top miter -flatten\ndffunmap\ncheck -assert\n'
    if zero_init:
        # Same existing 59h zero-state preparation. NOT used for reset entry.
        result += 'zinit -all\nopt -full -keepdc\ndffunmap\ncheck -assert\n'
    return result


def swap_inputs(rtl: str, first: str, second: str) -> str:
    require(re.search(r'\b' + first + r'\b', rtl) is not None and
            re.search(r'\b' + second + r'\b', rtl) is not None, 'missing mutation input anchors')
    return re.sub(r'\b(' + first + '|' + second + r')\b',
                  lambda m: second if m.group() == first else first, rtl)


def mutate(rtl: str, control: str) -> str:
    if control == 'field-misbinding':
        return swap_inputs(rtl, 'records_x', 'records_y')
    if control == 'signed-key-misbinding':
        return swap_inputs(rtl, 'signedRecords_real', 'signedRecords_imag')
    if control == 'wrong-branch':
        changed, count = re.subn(r'\bcase\s*\(\s*MODE\s*\)', 'case (1 - MODE)', rtl, count=1)
    elif control == 'ignored-enable':
        changed, count = re.subn(r'\bif\s*\(\s*enable\s*\)', "if (1'b1)", rtl)
    elif control == 'reset-overrides-enable':
        # On this active-high SYNC profile this changes only the reset/enable
        # priority; it does not remove ordinary enable stalls.
        changed, count = re.subn(r'\bif\s*\(\s*enable\s*\)', 'if(enable || reset)', rtl)
    elif control == 'bypass-latency':
        pattern = re.compile(r'(?m)^(\s*assign\s+delayed_(key|tag|x|y)\s*=)[^;]+;')
        require({m.group(2) for m in pattern.finditer(rtl)} == {'key', 'tag', 'x', 'y'},
                'missing actual-RTL mutation anchor: ' + control)
        changed, count = pattern.subn(lambda m: m.group(1) + ' selected_' + m.group(2) + ';', rtl)
    elif control == 'wrong-reset-value':
        changed, count = re.subn(r"<=\s*\{WIDTH\{1'b0\}\}\s*;", "<= {WIDTH{1'b1}};", rtl)
    else:
        raise RuntimeError('unknown actual-RTL mutation')
    require(count > 0 and changed != rtl, 'missing actual-RTL mutation anchor: ' + control)
    return changed


def qualify(root: Path, duplicate: Path, only: str | None = None) -> None:
    root, duplicate = root.resolve(), duplicate.resolve()
    # A failed rerun or focused diagnosis must not retain an earlier full-slice
    # certificate. Remove it before every fallible validation/tool operation.
    (root / 'evidence.json').unlink(missing_ok=True)
    require(root != duplicate, 'A/B generation requires two distinct artifact directories')
    for tool in ('iverilog', 'vvp', 'verilator', 'yosys'):
        require(shutil.which(tool) is not None, 'required tool is missing: ' + tool)
    manifest = json.loads((root / 'manifest.json').read_text())
    validate_manifest(manifest)
    require((root / 'manifest.json').read_bytes() == (duplicate / 'manifest.json').read_bytes(),
            'nondeterministic combined manifest')
    candidates, cases = manifest['candidates'], manifest['cases']
    original_paths = set()
    for item in candidates + cases:
        first = H.checked_rtl(root, item['file'])
        second = H.checked_rtl(duplicate, item['file'])
        require(first not in original_paths, 'reused canonical RTL artifact: ' + item['file'])
        original_paths.add(first)
        require(not first.samefile(second), 'A/B generation reused the same RTL file: ' + item['file'])
        require(first.read_bytes() == second.read_bytes(),
                'nondeterministic actual generated RTL: ' + item['file'])
    candidate_files = [H.checked_rtl(root, c['file']) for c in candidates]
    evidence = []
    for case in cases:
        if only is not None and only != case['id']:
            continue
        work = root / 'checks' / case['id']
        work.mkdir(parents=True, exist_ok=True)
        reference = H.checked_rtl(root, case['file'])
        sources = [reference] + candidate_files
        top = work / 'miter.v'
        top.write_text(miter(case, candidates, True))
        H.command(['verilator', '--lint-only', '--language', '1364-2001', '--top-module', 'miter',
                   *map(str, sources + [top])], work / 'lint.log')
        script = work / 'synthesis.ys'
        script.write_text(setup(sources + [top]) + 'synth -top miter\ncheck -assert\nstat\n')
        H.command(['yosys', '-Q', '-T', '-s', str(script)], work / 'synthesis.log')
        bench = work / 'tb.v'
        bench_text, cycles = testbench(case, candidates)
        bench.write_text(bench_text)
        executable = work / 'tb.vvp'
        H.command(['iverilog', '-g2001', '-s', 'tb', '-o', str(executable), *map(str, sources + [bench])], work / 'compile.log')
        result = H.command(['vvp', str(executable)], work / 'simulation.log')
        require(SIM_PASS in result and SIM_FAIL not in result, 'native/software simulation failed: ' + case['id'])
        combinational = work / 'combinational.v'
        combinational.write_text(miter(case, candidates, False))
        script = work / 'combinational.ys'
        script.write_text(setup(sources + [combinational]) + 'sat -prove bad 0 -verify -timeout 90\n')
        result = H.command(['yosys', '-Q', '-T', '-s', str(script)], work / 'combinational.log')
        require(H.PASS in result and H.COUNTEREXAMPLE not in result, 'combinational proof did not pass')
        script = work / 'reset-entry.ys'
        script.write_text(setup(sources + [top]) +
            'sat -seq 2 -set-at 1 reset 1 -set-at 1 enable 1 -prove bad 0 -prove-skip 1 -verify -timeout 90\n')
        result = H.command(['yosys', '-Q', '-T', '-s', str(script)], work / 'reset-entry.log')
        require(H.PASS in result and H.COUNTEREXAMPLE not in result, 'arbitrary-state reset-entry proof did not pass')
        script = work / 'induction.ys'
        script.write_text(setup(sources + [top], True) +
            'sat -seq 1 -tempinduct -set-init-zero -prove bad 0 -verify -maxsteps 24 -timeout 90\n')
        result = H.command(['yosys', '-Q', '-T', '-s', str(script)], work / 'induction.log')
        require(INDUCTION_PASS in result, 'unbounded induction did not pass')
        evidence.append({'case': case['id'], 'profiles': len(candidates), 'cycles': cycles,
            'combinational': 'PASS', 'reset_entry': 'PASS', 'induction': 'PASS'})
        print('PASS:', case['id'], 'all', len(candidates), 'layouts/signedness/defaults', flush=True)
    require(evidence, 'unknown focused case')
    if only is not None:
        print('Focused development case only; no full-slice evidence produced.')
        return
    # Falsify real generated candidate RTL, not a behavioral stand-in or timeout.
    case = next(c for c in cases if shape(c) == (5, 5, 5, 5, 0))
    selected = next(i for i, c in enumerate(candidates) if profile(c) == ('fields', 'legacy', 1))
    controls = MUTATIONS
    for control in controls:
        work = root / 'checks' / control
        work.mkdir(parents=True, exist_ok=True)
        actual = candidate_files[selected]
        mutated = work / 'mutated.v'
        mutated.write_text(mutate(actual.read_text(), control))
        files = [H.checked_rtl(root, case['file'])] + [mutated if i == selected else f for i, f in enumerate(candidate_files)]
        top = work / 'miter.v'
        sequential = control in SEQUENTIAL_MUTATIONS
        top.write_text(miter(case, candidates, sequential))
        trace = work / 'counterexample.vcd'
        script = work / 'mutation.ys'
        script.write_text(setup(files + [top], sequential) + 'sat ' +
            ('-seq 6 -set-init-zero ' if sequential else '') +
            '-prove bad 0 -show-inputs -show-outputs -timeout 90 -dump_vcd ' + H.quoted(trace) + '\n')
        result = H.command(['yosys', '-Q', '-T', '-s', str(script)], work / 'mutation.log')
        require(H.COUNTEREXAMPLE in result and H.PASS not in result, 'mutation did not yield a real counterexample: ' + control)
        H.require_counterexample_vcd(trace)
    report = {'scope': SCOPE, 'complete_59i_join': False, 'cases': evidence, 'mutations': list(controls),
              'checker_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              'manifest_sha256': hashlib.sha256((root / 'manifest.json').read_bytes()).hexdigest(),
              'sha256': {item['file']: hashlib.sha256(H.checked_rtl(root, item['file']).read_bytes()).hexdigest()
                         for item in candidates + cases}}
    (root / 'evidence.json').write_text(json.dumps(report, indent=2) + '\n')


def self_test() -> None:
    candidates = [{'layout': l, 'signed_mode': s, 'default_count': d,
                   'module': f'C_{l}_{s}_{d}', 'file': f'C_{l}_{s}_{d}.v'} for l, s, d in sorted(PROFILES)]
    cases = [dict(zip(('width', 'tag_width', 'coord_width', 'count', 'mode'), values),
                  id=f's{i}', module=f'R{i}', file=f'R{i}.v') for i, values in enumerate(sorted(SHAPES))]
    manifest = {'schema': 1, 'scope': SCOPE, 'candidates': candidates, 'cases': cases}
    validate_manifest(manifest)
    negative = 0
    for collection in ('candidates', 'cases'):
        for i in range(len(manifest[collection])):
            changed = copy.deepcopy(manifest)
            del changed[collection][i]
            try:
                validate_manifest(changed)
            except RuntimeError:
                negative += 1
            else:
                raise AssertionError('missing matrix entry accepted')
        changed = copy.deepcopy(manifest)
        changed[collection].append(copy.deepcopy(changed[collection][0]))
        try:
            validate_manifest(changed)
        except RuntimeError:
            negative += 1
        else:
            raise AssertionError('duplicate matrix entry accepted')
    for case in cases:
        lines, bindings = field_wiring(case, 'test_')
        require(len(lines) == 12 and len(bindings) == 6, 'missing field wiring')
        require(all(line.startswith(('wire ', 'assign ')) for line in lines), 'adapter contains logic')
        # Decode the same explicit bit patterns independently of the adapter's offsets.
        f = fields(case)
        for port, leaves in f.items():
            stride = sum(w for _, w in leaves)
            for lane in range(case['count']):
                offset = 0
                for index, (_, width) in enumerate(leaves):
                    packed = ((1 << width) - 1) << (lane * stride + offset)
                    rows = decode(packed, leaves, case['count'])
                    require(rows[lane][index] == (1 << width) - 1 and
                            sum(sum(row) for row in rows) == (1 << width) - 1, 'field/index decode error')
                    offset += width
    # Singleton has no added stage, even while reset is asserted or enable is low.
    singleton = Pipeline(1)
    require(singleton.step([(2, 3, 4, 5)], True, False, False) == (2, 3, 4, 5), 'singleton latency changed')
    odd = Pipeline(5)
    rows = [(7, 1, 0, 0), (6, 2, 0, 0), (5, 3, 0, 0), (4, 4, 0, 0), (1, 5, 0, 0)]
    odd.step(rows, True, True, False)
    for _ in range(3):
        value = odd.step(rows, False, True, False)
    require(value == (1, 5, 0, 0), 'odd tail or latency model failed')
    require(odd.step([(0, 0, 0, 0)] * 5, False, False, False) == value, 'stall model failed')
    require(odd.step(rows, True, False, False) == value, 'stalled reset must preserve native state')
    require(odd.step(rows, True, True, False) == (0, 0, 0, 0), 'enabled reset model failed')
    require(select([(3, 1), (3, 2)], False) == (3, 1), 'left tie selection failed')
    require(select([(7, 1), (0, 2)], False, 3) == (7, 1), 'signed comparison failed')
    for control in MUTATIONS:
        try:
            mutate('module absent; endmodule', control)
        except RuntimeError:
            negative += 1
        else:
            raise AssertionError('missing mutation anchor was accepted')
    print(f'59i checker self-test PASS: {len(cases)} cases, {len(candidates)} profiles, {negative} negative controls; HDL not run')


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', nargs='?', type=Path)
    parser.add_argument('duplicate', nargs='?', type=Path)
    parser.add_argument('--case')
    parser.add_argument('--self-test', action='store_true')
    args = parser.parse_args()
    if args.self_test:
        self_test()
    else:
        require(args.root is not None and args.duplicate is not None, 'two generated artifact directories are required')
        qualify(args.root, args.duplicate, args.case)


if __name__ == '__main__':
    main()
