#!/usr/bin/env python3
"""Qualify the bounded scoped nested-Vec result slice, not all Increment 59i.

Candidate/reference boundaries contain only declarations, fixed slices and
concatenation. Arithmetic/selection stays in independent native Scala bodies.
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

SPEC = importlib.util.spec_from_file_location('nested_result_tools',
    Path(__file__).with_name('check-increment-59b-operator-replay.py'))
assert SPEC is not None and SPEC.loader is not None
H = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(H)
SCOPE = '59i-scoped-nested-Vec-results'
PROFILES = {('packed', 'legacy', 1), ('fields', 'legacy', 1),
            ('packed', 'casts', 5), ('fields', 'casts', 5)}
KEYS = ('uw', 'sw', 'tw', 'inner', 'count', 'mode')
SHAPES = {(w, w, w, 2, n, m) for w, n, m in itertools.product(
    (1, 5, 8, 32), (1, 2, 3, 5, 8, 9, 16, 17), (0, 1))} | {
    (u, s, t, i, n, m) for (u, s, t), i, n, m in itertools.product(
        ((1, 3, 7), (5, 1, 8), (8, 32, 1), (32, 5, 7)), (1, 3), (1, 5), (0, 1))}
CONTROLS = ('wrong-branch', 'field-swap', 'inner-index-swap', 'signed-bit-loss')


def require(value: bool, detail: str) -> None:
    if not value:
        raise RuntimeError(detail)


def shape(case: dict) -> tuple:
    return tuple(case[key] for key in KEYS)


def validate(manifest: dict) -> None:
    require(set(manifest) == {'schema', 'scope', 'candidates', 'cases'} and
            type(manifest['schema']) is int and manifest['schema'] == 1 and manifest['scope'] == SCOPE,
            'wrong nested-result scope/schema')
    profiles = manifest['candidates']
    cases = manifest['cases']
    require(len(profiles) == len(PROFILES) and
            {(p['layout'], p['signed_mode'], p['default_count']) for p in profiles} == PROFILES and
            all(type(p['default_count']) is int for p in profiles), 'missing/duplicate profile')
    require(len(cases) == len(SHAPES) and {shape(c) for c in cases} == SHAPES and
            all(type(c[k]) is int for c in cases for k in KEYS), 'missing/duplicate shape')
    items = profiles + cases
    for key in ('module', 'file'):
        require(len({i[key] for i in items}) == len(items), 'overlapping artifact identity')
    require(all(H.IDENTIFIER.fullmatch(i['module']) for i in items), 'invalid module identity')
    require(len({c['id'] for c in cases}) == len(cases) and
            all(H.IDENTIFIER.fullmatch(c['id']) for c in cases), 'invalid case identity')


def geometry(case: dict) -> tuple[int, dict[str, list[tuple[int, int]]]]:
    """Logical native leaf order; field dimensions remain separate from paths."""
    u, s, t, inner, _, _ = shape(case)
    fields = {'key': [(0, u)], 'tag': [(u, t)],
              'samples_unsigned': [], 'samples_signed': [], 'samples_bitsValue': [], 'samples_valid': []}
    offset = u + t
    for _ in range(inner):
        for name, width in (('unsigned', u), ('signed', s), ('bitsValue', u), ('valid', 1)):
            fields['samples_' + name].append((offset, width))
            offset += width
    fields['grid'] = [(offset, 1)]
    return offset + 1, fields


def concat(pieces: list[str]) -> str:
    return pieces[0] if len(pieces) == 1 else '{' + ', '.join(pieces) + '}'


def instances(case: dict, profiles: list[dict]) -> list[str]:
    word, fields = geometry(case)
    lines = [f'wire [{word-1}:0] golden;',
             f'{case["module"]} reference(.records(records), .selected(golden));']
    u, s, t, inner, n, mode = shape(case)
    for i, p in enumerate(profiles):
        pre = f'c{i}_'
        lines.append(f'wire [{word-1}:0] {pre}selected;')
        bindings = []
        if p['layout'] == 'packed':
            bindings.append('.records(records)')
            outwidths = {'key': u, 'tag': t, 'samples': inner * (2*u+s+1), 'grid_0_0': 1}
            for name, width in outwidths.items():
                lines.append(f'wire [{width-1}:0] {pre}{name};')
                bindings.append(f'.selected_{name}({pre}{name})')
            packed = concat([pre + name for name in ('grid_0_0', 'samples', 'tag', 'key')])
        else:
            for name, slots in fields.items():
                pieces = [f'records[{lane*word+offset} +: {width}]'
                          for lane in reversed(range(n)) for offset, width in reversed(slots)]
                total = sum(w for _, w in slots)*n
                lines += [f'wire [{total-1}:0] {pre}in_{name};',
                          f'assign {pre}in_{name} = {concat(pieces)};']
                bindings.append(f'.records_{name}({pre}in_{name})')
            for name, slots in fields.items():
                # The fixed 1x1 result grid stays an ordinary native Bool port.
                target = 'grid_0_0' if name == 'grid' else name
                total = sum(w for _, w in slots)
                lines.append(f'wire [{total-1}:0] {pre}{name};')
                bindings.append(f'.selected_{target}({pre}{name})')
            pieces = [pre + 'grid']
            for lane in reversed(range(inner)):
                for name, width in (('valid', 1), ('bitsValue', u), ('signed', s), ('unsigned', u)):
                    pieces.append(f'{pre}samples_{name}[{lane*width} +: {width}]')
            packed = concat(pieces + [pre + 'tag', pre + 'key'])
        lines.append(f'assign {pre}selected = {packed};')
        params = f'#(.U_W({u}), .S_W({s}), .TAG_W({t}), .INNER({inner}), .COUNT({n}), .MODE({mode}))'
        lines.append(f'{p["module"]} {params} {pre}dut(' + ', '.join(bindings) + ');')
    return lines


def miter(case: dict, profiles: list[dict]) -> str:
    word, _ = geometry(case)
    lines = [f'module miter(input wire [{word*case["count"]-1}:0] records, output wire bad);']
    lines += instances(case, profiles)
    lines += ['assign bad = ' + ' | '.join(f'(|(golden ^ c{i}_selected))' for i in range(len(profiles))) + ';',
              'endmodule', '']
    return '\n'.join(lines)


def samples(case: dict) -> list[int]:
    word, fields = geometry(case)
    n = case['count']
    rng = random.Random(59091 + sum(shape(case)))
    result = [0, (1 << (word*n))-1]
    for lane in range(n):
        for slots in fields.values():
            for offset, width in slots:
                for bits in (1, 1 << (width-1), (1 << width)-1):
                    result.append(bits << (lane*word+offset))
    # Equal-key, independently tagged/filled records exercise stable whole-record ties.
    for key in (0, (1 << case['uw'])-1):
        result.append(sum(((rng.getrandbits(word) >> case['uw'] << case['uw']) | key) << (lane*word)
                          for lane in range(n)))
    result += [rng.getrandbits(word*n) for _ in range(96)]
    return result


def expected(case: dict, value: int) -> int:
    word, _ = geometry(case)
    rows = [(value >> (i*word)) & ((1 << word)-1) for i in range(case['count'])]
    # Flat stable selection is independent of the candidate's balanced tree.
    choose = max if case['mode'] else min
    return choose(rows, key=lambda row: row & ((1 << case['uw'])-1))


def bench(case: dict, profiles: list[dict]) -> tuple[str, int]:
    word, _ = geometry(case)
    values = samples(case)
    lines = ['module tb;', f'reg [{word*case["count"]-1}:0] records;'] + instances(case, profiles)
    lines.append('initial begin')
    for sample, value in enumerate(values):
        lines.append(f"records = {word*case['count']}'h{value:x}; #1;")
        answer = expected(case, value)
        for name in ['golden'] + [f'c{i}_selected' for i in range(len(profiles))]:
            lines += [f"if ({name} !== {word}'h{answer:x}) begin",
                      f'$display("59I-NESTED-FAIL {sample} {name}"); $finish(1); end']
    lines += ['$display("59I-NESTED-PASS"); $finish;', 'end', 'endmodule', '']
    return '\n'.join(lines), len(values)


def mutate(text: str, control: str) -> str:
    if control == 'wrong-branch':
        result, count = re.subn(r'if\s*\(\(\(MODE\) > \(0\)\)\)', 'if (((MODE) == (0)))', text, count=1)
        require(count == 1, 'missing branch mutation anchor')
    elif control == 'field-swap':
        names = ('records_samples_unsigned', 'records_samples_bitsValue')
        require(all(re.search(r'\b' + n + r'\b', text) for n in names), 'missing field mutation anchor')
        result = re.sub(r'\b(' + '|'.join(names) + r')\b',
            lambda m: names[1] if m.group() == names[0] else names[0], text)
    else:
        field = 'unsigned' if control == 'inner-index-swap' else 'signed'
        require(control in ('inner-index-swap', 'signed-bit-loss'), 'unknown mutation')
        pattern = re.compile(r'(?m)^(\s*selected_samples_' + field + r'\[[^;]+\]\s*=\s*)(morphhdl_balanced_\d+_result_leaf_\d+)(;)')
        matches = list(pattern.finditer(text))
        require(len(matches) == 6, 'missing exact two-owner/three-lane mutation anchors')
        changes = []
        if control == 'inner-index-swap':
            require(matches[0].group(2) != matches[1].group(2), 'duplicate lane anchor')
            changes = [(matches[0], matches[1].group(2)), (matches[1], matches[0].group(2))]
        else:
            changes = [(matches[0], matches[0].group(2) + " & {1'b0, {(S_W-1){1'b1}}}")]
        result = text
        for match, replacement in reversed(changes):
            result = result[:match.start(2)] + replacement + result[match.end(2):]
    require(result != text, 'mutation made no actual-RTL change')
    return result


def setup(sources: list[Path]) -> str:
    return 'read_verilog ' + ' '.join(H.quoted(f) for f in sources) + '\nprep -top miter -flatten\ncheck -assert\n'


def qualify_case(root: Path, profiles: list[dict], case: dict) -> dict:
    work = root / 'checks' / case['id']
    work.mkdir(parents=True, exist_ok=True)
    sources = [H.checked_rtl(root, case['file'])] + [H.checked_rtl(root, p['file']) for p in profiles]
    top = work/'miter.v'; top.write_text(miter(case, profiles))
    H.command(['verilator', '--lint-only', '--language', '1364-2001', '--top-module', 'miter',
               *map(str, sources+[top])], work/'lint.log')
    script = work/'synthesis.ys'; script.write_text(setup(sources+[top])+'synth -top miter\ncheck -assert\nstat\n')
    H.command(['yosys', '-Q', '-T', '-s', str(script)], work/'synthesis.log')
    tb, cycles = bench(case, profiles)
    benchpath = work/'tb.v'; benchpath.write_text(tb)
    exe = work/'tb.vvp'
    H.command(['iverilog', '-g2001', '-s', 'tb', '-o', str(exe), *map(str, sources+[benchpath])], work/'compile.log')
    output = H.command(['vvp', str(exe)], work/'simulation.log')
    require('59I-NESTED-PASS' in output and '59I-NESTED-FAIL' not in output, 'native/software comparison failed')
    script = work/'proof.ys'; script.write_text(setup(sources+[top])+'sat -prove bad 0 -verify -timeout 90\n')
    output = H.command(['yosys', '-Q', '-T', '-s', str(script)], work/'proof.log')
    require(H.PASS in output and H.COUNTEREXAMPLE not in output, 'non-passing proof')
    print('PASS', case['id'], 'profiles=',len(profiles),flush=True)
    return {'case': case['id'], 'profiles': len(profiles), 'simulation_vectors': cycles, 'equivalence': 'PASS'}


def qualify(root: Path, duplicate: Path, focus: str | None, jobs: int) -> None:
    root, duplicate = root.resolve(), duplicate.resolve()
    evidence = root/'evidence.json'
    evidence.unlink(missing_ok=True)
    require(root != duplicate, 'A/B must be independent output directories')
    manifest = json.loads((root/'manifest.json').read_text())
    validate(manifest)
    require((root/'manifest.json').read_bytes() == (duplicate/'manifest.json').read_bytes(), 'manifest nondeterminism')
    seen = set()
    for item in manifest['candidates'] + manifest['cases']:
        a, b = H.checked_rtl(root, item['file']), H.checked_rtl(duplicate, item['file'])
        require(a != b and not a.samefile(b), 'reused A/B RTL')
        require(a not in seen and b not in seen, 'reused canonical RTL path')
        seen.update((a, b))
        require(a.read_bytes() == b.read_bytes(), 'actual RTL nondeterminism: ' + item['file'])
    for tool in ('yosys', 'iverilog', 'vvp', 'verilator'):
        require(shutil.which(tool) is not None, 'missing required HDL tool: '+tool)
    cases = [c for c in manifest['cases'] if focus is None or c['id'] == focus]
    require(cases, 'unknown focused case')
    with concurrent.futures.ThreadPoolExecutor(max_workers=jobs) as pool:
        reports = list(pool.map(lambda c: qualify_case(root, manifest['candidates'], c), cases))
    if focus:
        print('Focused diagnosis only; no full-slice certificate produced.')
        return
    case = next(c for c in cases if shape(c) == (5,5,5,2,5,1))
    profiles = manifest['candidates']
    for control in CONTROLS:
        work = root/'checks'/control; work.mkdir(parents=True,exist_ok=True)
        sources = [H.checked_rtl(root,case['file'])]
        for p in profiles:
            source = H.checked_rtl(root,p['file'])
            if (p['layout'],p['signed_mode'],p['default_count']) == ('fields','legacy',1):
                changed = work/'mutated.v'; changed.write_text(mutate(source.read_text(),control)); sources.append(changed)
            else:
                sources.append(source)
        top = work/'miter.v'; top.write_text(miter(case,profiles)); sources.append(top)
        vcd = work/'counterexample.vcd'
        script = work/'mutation.ys'; script.write_text(setup(sources)+
            'sat -prove bad 0 -show-inputs -show-outputs -timeout 90 -dump_vcd '+H.quoted(vcd)+'\n')
        output = H.command(['yosys','-Q','-T','-s',str(script)],work/'mutation.log')
        require(H.COUNTEREXAMPLE in output and H.PASS not in output, 'no real mutation counterexample: '+control)
        H.require_counterexample_vcd(vcd)
    evidence.write_text(json.dumps({'scope':SCOPE,'complete_59i_join':False,'cases':reports,
        'mutations':list(CONTROLS),'checker_sha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        'manifest_sha256':hashlib.sha256((root/'manifest.json').read_bytes()).hexdigest(),
        'rtl_sha256':{i['file']:hashlib.sha256(H.checked_rtl(root,i['file']).read_bytes()).hexdigest()
                     for i in profiles+cases}},indent=2)+'\n')


def self_test() -> None:
    profiles = [dict(layout=l,signed_mode=s,default_count=d,module=f'C{i}',file=f'C{i}.v')
                for i,(l,s,d) in enumerate(sorted(PROFILES))]
    cases = [dict(zip(KEYS,v),id=f'x{i}',module=f'R{i}',file=f'R{i}.v') for i,v in enumerate(sorted(SHAPES))]
    manifest = {'schema':1,'scope':SCOPE,'candidates':profiles,'cases':cases}; validate(manifest)
    negatives = 0
    for group in ('candidates','cases'):
        for i in range(len(manifest[group])):
            changed = copy.deepcopy(manifest); del changed[group][i]
            try: validate(changed)
            except RuntimeError: negatives += 1
            else: raise AssertionError('missing matrix entry accepted')
        changed = copy.deepcopy(manifest);changed[group][1]=copy.deepcopy(changed[group][0])
        try: validate(changed)
        except RuntimeError: negatives += 1
        else: raise AssertionError('duplicate accepted')
    for c in cases:
        word, fields = geometry(c)
        offsets = [offset+bit for slots in fields.values() for offset,width in slots for bit in range(width)]
        require(sorted(offsets) == list(range(word)), 'layout has gap/overlap')
        # Stable selection must retain the earlier record, including all lane metadata.
        value = (1 << c['uw']) | 1
        words = [value, value | (1 << (word-1))] + [value]*(c['count']-2)
        words = words[:c['count']]
        packed = sum(v << (i*word) for i,v in enumerate(words))
        require(expected(c,packed) == value, 'unstable whole-record tie')
        for p in profiles:
            require('?' not in '\n'.join(instances(c,[p])), 'layout adapter contains a selector')
    for control in CONTROLS:
        try: mutate('module missing; endmodule',control)
        except RuntimeError: negatives += 1
        else: raise AssertionError('missing RTL anchor accepted')
    print(f'Nested-result checker self-test PASS: {len(cases)} cases, {len(profiles)} profiles, {negatives} negative controls; no HDL run')


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root',nargs='?',type=Path);parser.add_argument('duplicate',nargs='?',type=Path)
    parser.add_argument('--case');parser.add_argument('--jobs',type=int,default=1);parser.add_argument('--self-test',action='store_true')
    args = parser.parse_args()
    if args.self_test:self_test()
    else:
        require(args.root is not None and args.duplicate is not None and 1 <= args.jobs <= 4,'two directories and jobs 1..4 required')
        qualify(args.root,args.duplicate,args.case,args.jobs)

if __name__ == '__main__':main()
