#!/usr/bin/env python3
"""Source-bound finite qualification of composite runtime captures, not full 59i.

Adapters contain only wiring. Separate concrete native Scala callbacks and a
software model provide references. No skipped or timed-out tool run is a pass.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import copy
import hashlib
import importlib.util
import itertools
import json
import random
import re
import shutil
from pathlib import Path

SPEC = importlib.util.spec_from_file_location('capture_tools',
    Path(__file__).with_name('check-increment-59b-operator-replay.py'))
assert SPEC is not None and SPEC.loader is not None
H = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(H)
SCOPE = '59i-composite-runtime-captures'
KEYS = ('width', 'tag', 'coord', 'count', 'mode')
PROFILES = set(itertools.product(('packed', 'fields'), ('legacy', 'declarations', 'casts')))
SHAPES = {(w, w, w, n, m) for w, n, m in itertools.product(
    (1, 5, 8, 32), (1, 2, 3, 5, 8, 9, 16, 17), (0, 1))} | {
    (w, t, c, n, m) for (w, t, c), n, m in itertools.product(
        ((1, 3, 7), (5, 1, 8), (8, 32, 1), (32, 5, 7)), (1, 3, 5, 17), (0, 1))}
CONTROLS = ('capture-swap', 'frozen-mask', 'frozen-bias', 'offset-bit-loss', 'frozen-selector')
PASS = 'COMPOSITE-CAPTURE-SIM-PASS'
FAIL = 'COMPOSITE-CAPTURE-MISMATCH'


def require(value: bool, detail: str) -> None:
    if not value:
        raise RuntimeError(detail)


def validate(manifest: dict) -> None:
    require(set(manifest) == {'schema', 'scope', 'candidates', 'cases'} and
        type(manifest['schema']) is int and manifest['schema'] == 1 and manifest['scope'] == SCOPE,
        'invalid capture scope/schema')
    profiles, cases = manifest['candidates'], manifest['cases']
    require(len(profiles) == len(PROFILES) and
        all(set(p) == {'layout', 'signed', 'module', 'file'} for p in profiles) and
        {(p['layout'], p['signed']) for p in profiles} == PROFILES, 'missing/duplicate capture profile')
    require(len(cases) == len(SHAPES) and all(set(c) == set(KEYS) | {'id', 'module', 'file'} for c in cases)
        and all(type(c[k]) is int for c in cases for k in KEYS) and
        {tuple(c[k] for k in KEYS) for c in cases} == SHAPES, 'missing/duplicate capture case')
    items = profiles + cases
    for key in ('module', 'file'):
        require(len({i[key] for i in items}) == len(items), 'overlapping capture artifact identity')
    require(all(H.IDENTIFIER.fullmatch(i['module']) for i in items), 'invalid module')
    require(len({c['id'] for c in cases}) == len(cases) and
        all(H.IDENTIFIER.fullmatch(c['id']) for c in cases), 'invalid case identity')


def fields(case: dict) -> dict:
    return {'records': (('key', case['width']), ('tag', case['tag']),
            ('x', case['coord']), ('y', case['coord'])),
        'signedRecords': (('real', case['width']), ('imag', case['width']))}


def inputs(case: dict) -> dict:
    return {**{p: sum(w for _, w in leaves) * case['count'] for p, leaves in fields(case).items()},
        'biasA': case['coord'], 'biasB': case['coord'], 'mask': case['tag'],
        'choose': 1, 'offset': case['width']}


def outputs(case: dict) -> dict:
    return {**{'result_' + p: w for p, w in fields(case)['records']},
        **{'signedResult_' + p: w for p, w in fields(case)['signedRecords']}}


def field_wires(case: dict, prefix: str) -> tuple[list, list]:
    lines, bindings = [], []
    for port, leaves in fields(case).items():
        stride, offset = sum(w for _, w in leaves), 0
        for leaf, width in leaves:
            name = prefix + port + '_' + leaf
            parts = [f'{port}[{lane * stride + offset} +: {width}]' for lane in reversed(range(case['count']))]
            rhs = parts[0] if len(parts) == 1 else '{' + ', '.join(parts) + '}'
            lines += [f'wire [{width * case["count"] - 1}:0] {name};', f'assign {name} = {rhs};']
            bindings += [f'.{port}_{leaf}({name})']
            offset += width
    return lines, bindings


def instances(case: dict, candidates: list) -> list:
    lines = []
    for i, candidate in enumerate([None] + candidates):
        prefix = 'g_' if candidate is None else f'c{i - 1}_'
        lines += [f'wire [{w - 1}:0] {prefix}{p};' for p, w in outputs(case).items()]
        bindings = [f'.{p}({p})' for p in inputs(case) if p not in fields(case)]
        if candidate is not None and candidate['layout'] == 'fields':
            wiring, data_bindings = field_wires(case, prefix)
            lines += wiring
            bindings += data_bindings
        else:
            bindings += [f'.{p}({p})' for p in fields(case)]
        bindings += [f'.{p}({prefix}{p})' for p in outputs(case)]
        if candidate is None:
            module, params = case['module'], ''
        else:
            module = candidate['module']
            params = ' #(' + ', '.join(f'.{k.upper()}({case[v]})' for k, v in
                [('WIDTH','width'),('TAG','tag'),('COORD','coord'),('COUNT','count'),('MODE','mode')]) + ')'
        lines += [f'{module}{params} {prefix}dut(' + ', '.join(bindings) + ');']
    return lines


def miter(case: dict, candidates: list) -> str:
    lines = ['module miter(' + ', '.join([f'input wire [{w - 1}:0] {p}' for p, w in inputs(case).items()] +
        ['output wire bad']) + ');'] + instances(case, candidates)
    lines += ['assign bad = ' + ' | '.join(f'(|(g_{p} ^ c{i}_{p}))'
        for i in range(len(candidates)) for p in outputs(case)) + ';', 'endmodule', '']
    return '\n'.join(lines)


def synthesis_top(case: dict, candidates: list) -> str:
    # Retain every actual result. Synthesizing only a proved-zero bad output
    # could let optimization erase the datapaths before technology mapping.
    names = [prefix + p for prefix in ['g_'] + [f'c{i}_' for i in range(len(candidates))]
        for p in outputs(case)]
    width = sum(outputs(case).values()) * (len(candidates) + 1)
    lines = ['module synthesis(' + ', '.join([f'input wire [{w - 1}:0] {p}' for p, w in inputs(case).items()] +
        [f'output wire [{width - 1}:0] all_results']) + ');'] + instances(case, candidates)
    return '\n'.join(lines + ['assign all_results = {' + ', '.join(names) + '};', 'endmodule', ''])


def decode(value: int, leaves: tuple, count: int) -> list:
    rows = []
    for _ in range(count):
        row = []
        for _, width in leaves:
            row.append(value & ((1 << width) - 1))
            value >>= width
        rows.append(tuple(row))
    return rows


def reference(case: dict, data: dict) -> dict:
    f = fields(case)
    rows = decode(data['records'], f['records'], case['count'])
    signed = decode(data['signedRecords'], f['signedRecords'], case['count'])
    coord_mask, sign_mask = (1 << case['coord']) - 1, (1 << case['width']) - 1
    def integer(value):
        return value - (1 << case['width']) if value & (1 << (case['width'] - 1)) else value
    def combine(a, b):
        if case['mode'] == 0:
            a, b = b, a
        minimum = bool(data['choose']) == bool(case['mode'])
        return ((min if minimum else max)(a[0], b[0]), a[1] ^ b[1] ^ data['mask'],
            ((a[2] + b[3]) & coord_mask) ^ data['biasA'], ((a[3] - b[2]) & coord_mask) ^ data['biasB'])
    def combine_signed(a, b):
        if case['mode'] == 0:
            a, b = b, a
        return ((integer(a[0]) + integer(b[1]) + integer(data['offset'])) & sign_mask,
            (integer(a[1]) - integer(b[0])) & sign_mask)
    def reduce(values, operation):
        # Explicit pairing retains order for subtraction and cross-field graphs.
        # Odd tails and singleton rows bypass the operator entirely.
        while len(values) > 1:
            values = [operation(values[i], values[i + 1]) if i + 1 < len(values) else values[i]
                for i in range(0, len(values), 2)]
        return values[0]
    return {**{'result_' + p: v for (p, _), v in zip(f['records'], reduce(rows, combine))},
        **{'signedResult_' + p: v for (p, _), v in zip(f['signedRecords'], reduce(signed, combine_signed))}}


def samples(case: dict) -> list:
    widths = inputs(case)
    values = [{p: 0 for p in widths}, {p: (1 << w) - 1 for p, w in widths.items()}]
    for port, width in widths.items():
        for bit in range(width):
            values.append({p: 1 << bit if p == port else 0 for p in widths})
    rng = random.Random(5959 + sum(case[k] for k in KEYS))
    values += [{p: rng.getrandbits(w) for p, w in widths.items()} for _ in range(96)]
    # Exercise capture changes while ALL lane values stay fixed.
    fixed = {p: rng.getrandbits(w) for p, w in widths.items()}
    for choose in (0, 1):
        for port in ('biasA', 'biasB', 'mask', 'offset'):
            for value in (0, 1, (1 << widths[port]) - 1):
                values.append({**fixed, 'choose': choose, port: value})
    return values


def testbench(case: dict, candidates: list) -> tuple[str, int]:
    vectors = samples(case)
    lines = ['`timescale 1ns/1ps', 'module tb;']
    lines += [f'reg [{w - 1}:0] {p};' for p, w in inputs(case).items()]
    lines += instances(case, candidates) + ['initial begin']
    for index, data in enumerate(vectors):
        lines += [f"{p} = {inputs(case)[p]}'h{v:x};" for p, v in data.items()] + ['#1;']
        for prefix in ['g_'] + [f'c{i}_' for i in range(len(candidates))]:
            for p, value in reference(case, data).items():
                lines += [f"if ({prefix}{p} !== {outputs(case)[p]}'h{value:x}) begin",
                    f'$display("{FAIL} {prefix}{p} vector={index}"); $finish(1); end']
    lines += [f'$display("{PASS}"); $finish;', 'end', 'endmodule', '']
    return '\n'.join(lines), len(vectors)


def rewrite_rhs(rtl: str, mapping: dict[str, str]) -> str:
    matched = 0
    identifier = re.compile(r'\b(' + '|'.join(re.escape(k) for k in mapping) + r')\b')
    def assignment(match):
        nonlocal matched
        rhs, count = identifier.subn(lambda token: mapping[token.group()], match.group(2))
        matched += count
        return match.group(1) + rhs + ';'
    changed = re.sub(r'(?m)^(\s*assign\s+[^;=]+?=)([^;]+);', assignment, rtl)
    require(matched > 0 and changed != rtl, 'missing actual RTL capture mutation anchor')
    return changed


def mutate(rtl: str, control: str) -> str:
    if control == 'capture-swap':
        return rewrite_rhs(rtl, {'biasA': 'biasB', 'biasB': 'biasA'})
    if control == 'frozen-mask':
        return rewrite_rhs(rtl, {'mask': "{TAG{1'b0}}"})
    if control == 'frozen-bias':
        return rewrite_rhs(rtl, {'biasA': "{COORD{1'b0}}"})
    if control == 'frozen-selector':
        return rewrite_rhs(rtl, {'choose': "1'b0"})
    if control == 'offset-bit-loss':
        return rewrite_rhs(rtl, {'offset': "(offset & {1'b0, {(WIDTH-1){1'b1}}})"})
    raise RuntimeError('unknown capture mutation')


def setup(paths: list, top: str = "miter") -> str:
    return 'read_verilog ' + ' '.join(H.quoted(p) for p in paths) + f'\nprep -top {top} -flatten\ncheck -assert\n'


def qualify(root: Path, duplicate: Path, focused: str | None = None, jobs: int = 2) -> None:
    root, duplicate = root.resolve(), duplicate.resolve()
    report_path = root / 'evidence.json'
    report_path.unlink(missing_ok=True)
    require(root != duplicate and 1 <= jobs <= 8, 'invalid independent roots or worker count')
    for tool in ('yosys', 'verilator', 'iverilog', 'vvp'):
        require(shutil.which(tool) is not None, 'required tool missing: ' + tool)
    manifest = json.loads((root / 'manifest.json').read_text())
    validate(manifest)
    require((root / 'manifest.json').read_bytes() == (duplicate / 'manifest.json').read_bytes(), 'A/B manifest changed')
    candidates, cases = manifest['candidates'], manifest['cases']
    canonical = set()
    hashes = {}
    for item in candidates + cases:
        a, b = H.checked_rtl(root, item['file']), H.checked_rtl(duplicate, item['file'])
        require(a != b and not a.samefile(b) and a not in canonical and b not in canonical,
            'aliased A/B or reference/candidate artifact')
        canonical.update((a, b))
        require(a.read_bytes() == b.read_bytes(), 'A/B original RTL changed: ' + item['file'])
        hashes[item['file']] = hashlib.sha256(a.read_bytes()).hexdigest()
    candidate_files = [H.checked_rtl(root, p['file']) for p in candidates]
    def check(case):
        work = root / 'checks' / case['id']
        work.mkdir(parents=True, exist_ok=True)
        sources = [H.checked_rtl(root, case['file'])] + candidate_files
        top = work / 'miter.v'; top.write_text(miter(case, candidates))
        H.command(['verilator', '--lint-only', '--language', '1364-2001', '--top-module', 'miter',
            *map(str, sources + [top])], work / 'lint.log')
        script = work / 'proof.ys'
        script.write_text(setup(sources + [top]) + 'sat -prove bad 0 -verify -timeout 90\n')
        result = H.command(['yosys', '-Q', '-T', '-s', str(script)], work / 'proof.log')
        require(H.PASS in result and H.COUNTEREXAMPLE not in result, 'capture proof failed: ' + case['id'])
        synthesis = work / 'synthesis.v'; synthesis.write_text(synthesis_top(case, candidates))
        script = work / 'synthesis.ys'
        script.write_text(setup(sources + [synthesis], 'synthesis') + 'synth -top synthesis\ncheck -assert\nstat\n')
        H.command(['yosys', '-Q', '-T', '-s', str(script)], work / 'synthesis.log')
        bench = work / 'tb.v'; text, count = testbench(case, candidates); bench.write_text(text)
        binary = work / 'tb.vvp'
        H.command(['iverilog', '-g2001', '-s', 'tb', '-o', str(binary), *map(str, sources + [bench])], work / 'compile.log')
        result = H.command(['vvp', str(binary)], work / 'simulation.log')
        require(PASS in result and FAIL not in result, 'capture simulation failed: ' + case['id'])
        print('PASS capture:', case['id'], 'profiles:', len(candidates), 'vectors:', count, flush=True)
        return {'case': case['id'], 'profiles': len(candidates), 'vectors': count, 'formal': 'PASS'}
    selected = [c for c in cases if focused is None or c['id'] == focused]
    require(selected, 'unknown focused case')
    with concurrent.futures.ThreadPoolExecutor(max_workers=jobs) as pool:
        evidence = list(pool.map(check, selected))
    if focused is not None:
        print('Focused diagnosis only; no complete evidence record.')
        return
    case = next(c for c in cases if tuple(c[k] for k in KEYS) == (5, 5, 5, 2, 0))
    candidate = next(c for c in candidates if (c['layout'], c['signed']) == ('fields', 'legacy'))
    mutation_hashes = {}
    for control in CONTROLS:
        work = root / 'checks' / control; work.mkdir(parents=True, exist_ok=True)
        actual = H.checked_rtl(root, candidate['file'])
        changed = work / 'mutated.v'; changed.write_text(mutate(actual.read_text(), control))
        top = work / 'miter.v'; top.write_text(miter(case, [candidate]))
        trace = work / 'counterexample.vcd'
        script = work / 'mutation.ys'
        script.write_text(setup([H.checked_rtl(root, case['file']), changed, top]) +
            'sat -prove bad 0 -show-inputs -show-outputs -timeout 90 -dump_vcd ' + H.quoted(trace) + '\n')
        result = H.command(['yosys', '-Q', '-T', '-s', str(script)], work / 'mutation.log')
        require(H.COUNTEREXAMPLE in result and H.PASS not in result, 'capture mutation has no counterexample: ' + control)
        H.require_counterexample_vcd(trace)
        mutation_hashes[control] = hashlib.sha256(changed.read_bytes()).hexdigest()
    report = {'scope': SCOPE, 'complete_59i_join': False, 'cases': evidence, 'mutations': list(CONTROLS),
        'mutated_sha256': mutation_hashes, 'sha256': hashes,
        'checker_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        'manifest_sha256': hashlib.sha256((root / 'manifest.json').read_bytes()).hexdigest()}
    report_path.write_text(json.dumps(report, indent=2) + '\n')


def self_test() -> None:
    candidates = [dict(layout=l, signed=s, module='C_'+l+'_'+s, file='C_'+l+'_'+s+'.v') for l, s in sorted(PROFILES)]
    cases = [dict(zip(KEYS, shape), id='s'+str(i), module='R'+str(i), file='R'+str(i)+'.v') for i, shape in enumerate(sorted(SHAPES))]
    manifest = dict(schema=1, scope=SCOPE, candidates=candidates, cases=cases)
    validate(manifest)
    rejected = 0
    for collection in ('candidates', 'cases'):
        for index in range(len(manifest[collection])):
            changed = copy.deepcopy(manifest); del changed[collection][index]
            try: validate(changed)
            except RuntimeError: rejected += 1
            else: raise AssertionError('missing matrix item accepted')
    for control in CONTROLS:
        try: mutate('module absent; endmodule', control)
        except RuntimeError: rejected += 1
        else: raise AssertionError('missing real RTL mutation anchor accepted')
    case = cases[0]
    for data in samples(case):
        expect = reference(case, data)
        require(all(0 <= expect[p] < 1 << w for p, w in outputs(case).items()), 'software reference overflow')
    for c in cases:
        lines, bindings = field_wires(c, 'check_')
        require(len(lines) == 12 and len(bindings) == 6 and all(l.startswith(('wire ', 'assign ')) for l in lines),
            'adapter contains non-wiring or incomplete fields')
    print(f'Capture checker self-test PASS: {len(cases)} cases, {len(candidates)} profiles, {rejected} rejection controls; no HDL execution')


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', nargs='?', type=Path)
    parser.add_argument('duplicate', nargs='?', type=Path)
    parser.add_argument('--case')
    parser.add_argument('--jobs', type=int, default=2)
    parser.add_argument('--self-test', action='store_true')
    args = parser.parse_args()
    if args.self_test: self_test()
    else:
        require(args.root is not None and args.duplicate is not None, 'independent A/B roots required')
        qualify(args.root, args.duplicate, args.case, args.jobs)


if __name__ == '__main__':
    main()
