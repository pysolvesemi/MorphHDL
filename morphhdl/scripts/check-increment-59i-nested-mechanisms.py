#!/usr/bin/env python3
"""Finite native/software/RTL qualification of the nested six-mechanism join.

Only wiring, fixed slices and concatenations occur in layout adapters. Numeric
expectations and enabled-register state are independently modelled below. A
focused run never issues full-matrix evidence. Hardware failures and absent
mutation anchors are failures, not exclusions from the frozen inventory.
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
from collections import Counter
from pathlib import Path

SPEC = importlib.util.spec_from_file_location('nested_mechanism_tools',
    Path(__file__).with_name('check-increment-59b-operator-replay.py'))
assert SPEC and SPEC.loader
H = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(H)
SCOPE = '59i-nested-widening-capture-register-hierarchy'
SAT_SCOPE = '59i-nested-saturation-widening'
PROFILES = set(itertools.product(('packed', 'fields'), ('legacy', 'declarations', 'casts')))
KEYS = ('uw', 'sw', 'tw', 'inner', 'count', 'mode')
SHAPES = {(*shape, mode) for shape, mode in itertools.product(
    ((1, 1, 1, 1, 1), (3, 2, 4, 1, 1), (1, 4, 2, 3, 2),
     (4, 1, 3, 1, 3), (2, 3, 1, 2, 4), (3, 2, 4, 3, 5),
     (4, 4, 4, 3, 5), (2, 2, 2, 2, 5)), (0, 1))}
MUTATIONS = ('capture-misbinding', 'child-mode-misbinding', 'field-misbinding',
             'inner-index-misbinding', 'odd-tail-loss', 'carry-loss', 'sign-loss',
             'bypass-latency', 'ignored-enable', 'reset-overrides-enable')
PASS = '59I-NESTED-MECHANISMS-PASS'
FAIL = '59I-NESTED-MECHANISMS-FAIL'
INDUCTION_PASS = 'Induction step proven: SUCCESS!'


def require(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(message)


def shape(case: dict) -> tuple:
    return tuple(case[k] for k in KEYS)


def validate(manifest: dict, saturation: bool = False) -> None:
    require(set(manifest) == {'schema', 'scope', 'candidates', 'cases'} and
            type(manifest['schema']) is int and manifest['schema'] == 1 and
            manifest['scope'] == (SAT_SCOPE if saturation else SCOPE), 'wrong scope/schema')
    profiles, cases = manifest['candidates'], manifest['cases']
    require(all(set(p) == {'layout','signed_mode','module','child_module','file'} for p in profiles),
            'unknown/missing candidate metadata')
    require(all(set(c) == set(KEYS) | {'id','module','file'} for c in cases),
            'unknown/missing case metadata')
    require(len(profiles) == len(PROFILES) and
            {(p['layout'], p['signed_mode']) for p in profiles} == PROFILES, 'missing/duplicate profile')
    require(len(cases) == len(SHAPES) and {shape(c) for c in cases} == SHAPES and
            all(type(c[k]) is int for c in cases for k in KEYS), 'missing/duplicate/invalid shape')
    all_items = profiles + cases
    for key in ('file', 'module'):
        require(len({v[key] for v in all_items}) == len(all_items), 'overlapping artifact identity')
    modules = [i['module'] for i in all_items] + [i['child_module'] for i in profiles]
    require(len(set(modules)) == len(modules) and all(H.IDENTIFIER.fullmatch(m) for m in modules),
            'invalid or overlapping module identity')
    require(len({c['id'] for c in cases}) == len(cases) and
            all(H.IDENTIFIER.fullmatch(c['id']) for c in cases), 'invalid case identity')


def validate_signed_ports(rtl: str, profile: dict) -> None:
    """Check the actual requested mode at each generated hierarchy boundary.

    Internal field carriers and real signedness boundaries can retain native
    qualifiers/casts even in legacy mode; only these typed ports establish the
    expected declaration mode. Never infer it from a module/profile name.
    """
    rtl = re.sub(r'/\*.*?\*/|//[^\n]*', '', rtl, flags=re.S)
    signed_ports = {'offsetA', 'offsetB', 'resultSS', 'resultSP'}
    ports = dict.fromkeys(('clk', 'reset', 'enable', 'biasA', 'biasB', 'offsetA', 'offsetB'), 'input')
    ports.update(dict.fromkeys(('resultUS', 'resultUP', 'resultSS', 'resultSP',
                               'resultSat', 'resultSamples'), 'output'))
    for module in (profile['module'], profile['child_module']):
        modules = list(re.finditer(r'(?ms)^module\s+' + re.escape(module) +
                                  r'\b(.*?)\);.*?^endmodule\b', rtl))
        require(len(modules) == 1, 'missing/duplicate signed-mode module: ' + module)
        header = modules[0].group(1)
        declarations = list(re.finditer(r'(?m)^\s*(input|output)\s+(?:wire|reg)\s+'
            r'(?:(signed)\s+)?(?:\[[^\]\n]+\]\s*)?([A-Za-z_][A-Za-z_0-9]*)\s*,?\s*$', header))
        for port, direction in ports.items():
            found = [m for m in declarations if m[3] == port]
            require(len(found) == 1 and found[0][1] == direction,
                    'missing/duplicate typed port declaration: ' + module + '.' + port)
            expected = port in signed_ports and profile['signed_mode'] != 'legacy'
            require(bool(found[0][2]) == expected,
                    'wrong ' + profile['signed_mode'] + ' port signedness: ' + module + '.' + port)


def validate_signed_casts(rtl_profiles: list[tuple[dict, str]]) -> None:
    counts = {(p['layout'],p['signed_mode']):len(re.findall(r'\$signed\s*\(',
        re.sub(r'/\*.*?\*/|//[^\n]*', '', rtl, flags=re.S))) for p,rtl in rtl_profiles}
    require(set(counts) == PROFILES, 'incomplete actual signed-cast profile inventory')
    for layout in ('packed','fields'):
        legacy, declarations, casts = (counts[layout,mode] for mode in ('legacy','declarations','casts'))
        require(legacy == declarations and declarations > casts,
                'native casts/declaration-only/minimal-cast profile contract failed: ' + layout)


def geometry(case: dict) -> tuple[int, dict[str, list[tuple[int, int]]]]:
    u, s, t, inner, _, _ = shape(case)
    fields = {'unsignedSum': [(0, u)], 'unsignedProduct': [(u, u)],
              'signedSum': [(2*u, s)], 'signedProduct': [(2*u+s, s)],
              'saturated': [(2*u+2*s, t)],
              'samples': [(2*u+2*s+t*(j+1), t) for j in range(inner)]}
    return 2*u + 2*s + t*(inner+1), fields


def input_widths(case: dict) -> dict[str, int]:
    word, _ = geometry(case)
    return dict(clk=1, reset=1, enable=1, values=word*case['count'],
                biasA=case['uw'], biasB=case['uw'], offsetA=case['sw'], offsetB=case['sw'])


def outputs(case: dict) -> dict[str, int]:
    u, s, t, inner, n, _ = shape(case)
    return dict(resultUS=u+2*n, resultUP=u*n, resultSS=s+2*n,
                resultSP=s*n, resultSat=t, resultSamples=t*inner)


def concat(parts: list[str]) -> str:
    return parts[0] if len(parts) == 1 else '{' + ', '.join(parts) + '}'


def instances(case: dict, profiles: list[dict]) -> list[str]:
    lines = []
    word, fields = geometry(case)
    common = [f'.{p}({p})' for p in input_widths(case) if p != 'values']
    for prefix, module, profile in [('g_', case['module'], None)] + [
            (f'c{i}_', p['module'], p) for i, p in enumerate(profiles)]:
        bindings = list(common)
        for port, width in outputs(case).items():
            lines.append(f'wire [{width-1}:0] {prefix}{port};')
            bindings.append(f'.{port}({prefix}{port})')
        if profile is None or profile['layout'] == 'packed':
            bindings.append('.values(values)')
        else:
            for name, slots in fields.items():
                width = sum(w for _, w in slots) * case['count']
                expression = concat([f'values[{lane*word+offset} +: {w}]'
                    for lane in reversed(range(case['count'])) for offset, w in reversed(slots)])
                lines += [f'wire [{width-1}:0] {prefix}in_{name};',
                          f'assign {prefix}in_{name} = {expression};']
                bindings.append(f'.values_{name}({prefix}in_{name})')
        params = '' if profile is None else '#(' + ', '.join(
            f'.{name}({case[key]})' for name, key in zip(
                ('U_W', 'S_W', 'TAG_W', 'INNER', 'COUNT', 'MODE'), KEYS)) + ')'
        lines.append(f'{module} {params} {prefix}dut(' + ', '.join(bindings) + ');')
    return lines


def miter(case: dict, profiles: list[dict]) -> str:
    lines = ['module miter(' + ', '.join([f'input wire [{w-1}:0] {p}'
        for p, w in input_widths(case).items()] + ['output wire bad']) + ');']
    lines += instances(case, profiles)
    lines += ['assign bad = ' + ' | '.join(f'(|(g_{p} ^ c{i}_{p}))'
        for i in range(len(profiles)) for p in outputs(case)) + ';', 'endmodule', '']
    return '\n'.join(lines)


def signed(value: int, width: int) -> int:
    return value - (1 << width) if value & (1 << (width-1)) else value


def decode(case: dict, packed: int) -> list[tuple[int, ...]]:
    word, fields = geometry(case)
    result = []
    for lane in range(case['count']):
        bits = packed >> (word*lane)
        row = []
        for name, slots in fields.items():
            value = sum(((bits >> offset) & ((1 << width)-1)) << (j*width)
                        for j, (offset, width) in enumerate(slots))
            row.append(signed(value, case['sw']) if name in ('signedSum', 'signedProduct') else value)
        result.append(tuple(row))
    return result


class Pipeline:
    """Native pair topology with independent integers and old-state sampling.

    Current-cycle captures feed EVERY active operator level, so a delayed flat
    sum with only the current/initial bias would be an invalid oracle here.
    """
    def __init__(self, case: dict, saturation: bool):
        self.case, self.saturation = case, saturation
        self.initialized = case['count'] == 1
        n = case['count']
        self.state = []
        while n > 1:
            n = (n+1)//2
            self.state.append([(0,)*6 for _ in range(n)])

    def operation(self, a: tuple, b: tuple, bias: int, offset: int) -> tuple:
        sat = min(a[4] + b[4], (1 << self.case['tw'])-1) if self.saturation else a[4] ^ b[4]
        return (a[0] + bias + b[0], a[1]*b[1], a[2] + offset + b[2], a[3]*b[3], sat, a[5])

    def step(self, sample: dict, reset: bool, enable: bool) -> tuple:
        rows = decode(self.case, sample['values'])
        if not self.state:
            return rows[0]
        require(self.initialized or (reset and enable), 'observation before enabled reset')
        if enable:
            if reset:
                self.state = [[(0,)*6 for _ in row] for row in self.state]
                self.initialized = True
            else:
                suffix = 'B' if self.case['mode'] else 'A'
                bias = sample['bias'+suffix]
                offset = signed(sample['offset'+suffix], self.case['sw'])
                before = [rows] + self.state[:-1]
                self.state = [[self.operation(row[j], row[j+1], bias, offset)
                    if j+1 < len(row) else row[j] for j in range(0, len(row), 2)] for row in before]
        return self.state[-1][0]


def stimulus(case: dict) -> list[tuple[dict, bool, bool]]:
    widths = {p: w for p, w in input_widths(case).items() if p not in ('clk', 'reset', 'enable')}
    zero = {p: 0 for p in widths}
    full = {p: (1 << w)-1 for p, w in widths.items()}
    depth = (case['count']-1).bit_length()
    trace = [(zero, True, True)] + [(full, False, True)]*(depth+1)
    trace += [(full, True, False), (zero, False, False), (zero, True, True)]
    rng = random.Random(590910 + sum(shape(case)))
    vectors = [zero, full]
    # Each input bit independently moves, covering unequal fields and all inner
    # and outer Vec indices, carry inputs and signed extrema.
    for port, width in widths.items():
        for bit in range(width):
            vectors.append({p: (1 << bit) if p == port else 0 for p in widths})
    vectors += [{p: rng.getrandbits(w) for p, w in widths.items()} for _ in range(96)]
    trace += [(v, cycle % 47 == 19, cycle % 7 not in (0, 1)) for cycle, v in enumerate(vectors)]
    return trace


def bench(case: dict, profiles: list[dict], saturation: bool) -> tuple[str, int]:
    lines = ['`timescale 1ns/1ps', 'module tb;']
    lines += [f'reg [{w-1}:0] {p};' for p, w in input_widths(case).items()]
    lines += instances(case, profiles)
    lines += ['initial begin', 'clk = 0; reset = 1; enable = 1;']
    pipeline = Pipeline(case, saturation)

    def compare(row: tuple, cycle: int, phase: str) -> None:
        for prefix in ['g_'] + [f'c{i}_' for i in range(len(profiles))]:
            for (port, width), value in zip(outputs(case).items(), row):
                answer = value & ((1 << width)-1)
                lines.extend([f"if ({prefix}{port} !== {width}'h{answer:x}) begin",
                    f'$display("{FAIL} {prefix}{port} cycle={cycle} {phase}"); $finish(1);', 'end'])

    trace = stimulus(case)
    for cycle, (sample, reset, enable) in enumerate(trace):
        lines += [f'clk = 0; reset = {int(reset)}; enable = {int(enable)};']
        lines += [f"{p} = {input_widths(case)[p]}'h{v:x};" for p, v in sample.items()]
        lines.append('#1;')
        if cycle:
            compare(pipeline.state[-1][0] if pipeline.state else decode(case, sample['values'])[0],
                    cycle, 'before-edge')
        row = pipeline.step(sample, reset, enable)
        lines.append('clk = 1; #1;')
        compare(row, cycle, 'after-edge')
    lines += [f'$display("{PASS}"); $finish;', 'end', 'endmodule', '']
    return '\n'.join(lines), len(trace)


def setup(sources: list[Path], zero: bool = False) -> str:
    text = 'read_verilog ' + ' '.join(H.quoted(f) for f in sources)
    text += '\nprep -top miter -flatten\ndffunmap\ncheck -assert\n'
    if zero:
        text += 'zinit -all\nopt -full -keepdc\ndffunmap\ncheck -assert\n'
    return text


def qualify_case(root: Path, profiles: list[dict], case: dict, saturation: bool) -> dict:
    work = root/'checks'/case['id']
    work.mkdir(parents=True, exist_ok=True)
    sources = [H.checked_rtl(root, case['file'])] + [H.checked_rtl(root, p['file']) for p in profiles]
    top = work/'miter.v'; top.write_text(miter(case, profiles))
    H.command(['verilator', '--lint-only', '--language', '1364-2001', '--top-module', 'miter',
               *map(str, sources+[top])], work/'lint.log')
    script = work/'synthesis.ys'; script.write_text(setup(sources+[top])+'synth -top miter\ncheck -assert\nstat\n')
    H.command(['yosys', '-Q', '-T', '-s', str(script)], work/'synthesis.log')
    text, cycles = bench(case, profiles, saturation)
    test = work/'tb.v'; test.write_text(text)
    executable = work/'tb.vvp'
    H.command(['iverilog', '-g2001', '-s', 'tb', '-o', str(executable), *map(str, sources+[test])], work/'compile.log')
    result = H.command(['vvp', str(executable)], work/'simulation.log')
    require(PASS in result and FAIL not in result, 'native/software/RTL comparison failed')
    script = work/'reset-entry.ys'
    script.write_text(setup(sources+[top])+ 'sat -seq 2 -set-at 1 reset 1 -set-at 1 enable 1 '
                      '-prove bad 0 -prove-skip 1 -verify -timeout 90\n')
    result = H.command(['yosys', '-Q', '-T', '-s', str(script)], work/'reset-entry.log')
    require(H.PASS in result and H.COUNTEREXAMPLE not in result, 'arbitrary-state reset-entry proof failed')
    script = work/'induction.ys'
    script.write_text(setup(sources+[top], True) +
        'sat -seq 1 -tempinduct -set-init-zero -prove bad 0 -verify -maxsteps 24 -timeout 90\n')
    result = H.command(['yosys', '-Q', '-T', '-s', str(script)], work/'induction.log')
    require(INDUCTION_PASS in result, 'unbounded sequential equivalence proof failed')
    print('PASS:', case['id'], 'profiles=', len(profiles), flush=True)
    return dict(case=case['id'], profiles=len(profiles), simulation_cycles=cycles,
                reset_entry='PASS', induction='PASS')


def child_body(rtl: str, module: str) -> tuple[str, str, str]:
    matches = list(re.finditer(r'(?ms)^module\s+' + re.escape(module) + r'\b.*?\);(.*?)^endmodule\b', rtl))
    require(len(matches) == 1, 'missing or duplicated actual child module')
    match = matches[0]
    return rtl[:match.start(1)], match.group(1), rtl[match.end(1):]


def input_alias(body: str, layout: str, control: str) -> str:
    """Change reads of actual emitted child inputs; preserve the real datapath."""
    packed = layout == 'packed'
    target = 'values' if packed else 'values_samples'
    if control == 'field-misbinding' and not packed:
        require(all(re.search(r'\bvalues_'+n+r'\b', body) for n in ('unsignedSum', 'unsignedProduct')),
                'missing field mutation anchors')
        return re.sub(r'\bvalues_(unsignedSum|unsignedProduct)\b',
            lambda m: 'values_' + ('unsignedProduct' if m[1] == 'unsignedSum' else 'unsignedSum'), body)
    if control == 'odd-tail-loss' and not packed:
        # Every numeric field uses the same final outer lane, with no aggregate
        # assumptions hidden in the field-preserving layout.
        prefix = []
        for name, width in (('unsignedSum','U_W'),('unsignedProduct','U_W'),
                           ('signedSum','S_W'),('signedProduct','S_W'),('saturated','TAG_W')):
            source = 'values_'+name
            require(re.search(r'\b'+source+r'\b', body) is not None, 'missing tail field anchor')
            body = re.sub(r'\b'+source+r'\b', 'nm_'+source, body)
            prefix += [f'wire [COUNT*{width}-1:0] nm_{source};',
                f'assign nm_{source} = {source} & ~({{{width}{{1\'b1}}}} << ((COUNT-1)*{width}));']
        return '\n'.join(prefix)+'\n'+body
    require(re.search(r'\b'+target+r'\b', body) is not None, 'missing nested input mutation anchor')
    body = re.sub(r'\b'+target+r'\b', 'nm_values', body)
    word = '(2*U_W+2*S_W+TAG_W*(INNER+1))' if packed else '(TAG_W*INNER)'
    pre = [f'wire [COUNT*{word}-1:0] nm_values;', 'genvar nm_lane, nm_inner;',
           'generate for(nm_lane=0; nm_lane<COUNT; nm_lane=nm_lane+1) begin : nm_outer']
    if control == 'field-misbinding':
        pre += [f'assign nm_values[nm_lane*{word} +: U_W] = values[nm_lane*{word}+U_W +: U_W];',
                f'assign nm_values[nm_lane*{word}+U_W +: U_W] = values[nm_lane*{word} +: U_W];',
                f'assign nm_values[nm_lane*{word}+2*U_W +: {word}-2*U_W] = values[nm_lane*{word}+2*U_W +: {word}-2*U_W];']
    elif control == 'odd-tail-loss':
        pre += [f'if(nm_lane == COUNT-1) assign nm_values[nm_lane*{word} +: {word}] = {{{word}{{1\'b0}}}};',
                f'else assign nm_values[nm_lane*{word} +: {word}] = values[nm_lane*{word} +: {word}];']
    else:
        require(control == 'inner-index-misbinding', 'unknown nested alias mutation')
        offset = '(2*U_W+2*S_W+TAG_W)' if packed else '0'
        if packed:
            pre += [f'assign nm_values[nm_lane*{word} +: {offset}] = values[nm_lane*{word} +: {offset}];']
        pre += ['for(nm_inner=0; nm_inner<INNER; nm_inner=nm_inner+1) begin : nm_inner_reverse',
            f'assign nm_values[nm_lane*{word}+{offset}+nm_inner*TAG_W +: TAG_W] = '
            f'{target}[nm_lane*{word}+{offset}+(INNER-1-nm_inner)*TAG_W +: TAG_W];', 'end']
    return '\n'.join(pre+['end endgenerate'])+'\n'+body


def mutate(rtl: str, profile: dict, control: str) -> str:
    if control == 'child-mode-misbinding':
        changed, count = re.subn(r'\.MODE\s*\(\s*\(\s*MODE\s*\+\s*1\s*\)\s*\)',
                                '.MODE((2 - MODE))', rtl)
        require(count == 1 and changed != rtl, 'missing exact child MODE+1 binding')
        return changed
    prefix, body, suffix = child_body(rtl, profile['child_module'])
    original = body
    if control == 'capture-misbinding':
        require(re.search(r'\bbiasA\b', body) is not None and re.search(r'\bbiasB\b', body) is not None,
                'missing capture input anchors')
        body = re.sub(r'\b(biasA|biasB)\b', lambda m: 'biasB' if m[1] == 'biasA' else 'biasA', body)
    elif control in ('field-misbinding', 'inner-index-misbinding', 'odd-tail-loss'):
        body = input_alias(body, profile['layout'], control)
    elif control in ('ignored-enable', 'reset-overrides-enable'):
        body, count = re.subn(r'\bif\s*\(\s*enable\s*\)',
            "if(1'b1)" if control == 'ignored-enable' else 'if(enable || reset)', body)
        require(count > 0, 'missing native register enable anchor')
    elif control == 'bypass-latency':
        # Whole typed Vec transport publishes one guarded slice per retained
        # inner entry in each MODE branch. Preserve those guards and exact
        # bit positions while bypassing the native register chain on every
        # actual emitted sample assignment.
        pattern = re.compile(r'(?m)^([ \t]*(?:assign[ \t]+)?resultSamples\[\s*\(\s*'
            r'(0|TAG_W|[12]\s*\*\s*TAG_W)\s*\)\s*\+:\s*TAG_W\s*\][ \t]*=(?!=))\s*'
            r'morphhdl_balanced_(\d+)_result_leaf_([567]);')
        anchors = list(pattern.finditer(body))
        offsets = {'0':'5',('1*TAG_W' if profile['layout'] == 'packed' else 'TAG_W'):'6','2*TAG_W':'7'}
        branches = {m[3] for m in anchors}
        require(len(anchors) == 6 and len(branches) == 2 and
                all(Counter(re.sub(r'\s+','',m[2]) for m in anchors if m[3] == branch) ==
                    Counter(offsets.keys()) for branch in branches) and
                all(offsets[re.sub(r'\s+','',m[2])] == m[4] for m in anchors),
                'missing exact recursive output slice anchors: bypass-latency')
        def direct_sample(match: re.Match) -> str:
            offset = match[2]
            value = (f'values[(2*U_W+2*S_W+TAG_W)+({offset}) +: TAG_W]'
                     if profile['layout'] == 'packed' else f'values_samples[({offset}) +: TAG_W]')
            return match[1]+' '+value+';'
        body = pattern.sub(direct_sample,body)
    else:
        output = {'carry-loss':'resultUS','sign-loss':'resultSP'}[control]
        pattern = re.compile(r'(?m)^([ \t]*(?:assign[ \t]+)?'+output+r'[ \t]*=(?!=))([^;\n]+);')
        bit = 'U_W' if control == 'carry-loss' else '(S_W*COUNT-1)'
        width = '(U_W+2*COUNT)' if control == 'carry-loss' else '(S_W*COUNT)'
        # Sized replication avoids unsized signed-mask context effects.
        mask = "~({{("+width+"-1){1'b0}},1'b1} << "+bit+")"
        body, count = pattern.subn(lambda m: m[1]+' ('+m[2].strip()+') & '+mask+';', body)
        require(count > 0, 'missing emitted output assignment anchor: '+control)
    require(body != original, 'actual child RTL mutation did not change source')
    return prefix+body+suffix


def mutations(root: Path, manifest: dict, saturation: bool) -> list[dict]:
    case = next(c for c in manifest['cases'] if shape(c) == (3,2,4,3,5,0))
    result = []
    # Every layout and signedness mode must reject each actual emitted mutation.
    for profile in manifest['candidates']:
        for control in MUTATIONS:
            work = root/'checks'/('mutation-'+profile['layout']+'-'+profile['signed_mode']+'-'+control)
            work.mkdir(parents=True, exist_ok=True)
            original = H.checked_rtl(root, profile['file'])
            changed = work/'mutated.v'; changed.write_text(mutate(original.read_text(), profile, control))
            sources = [H.checked_rtl(root, case['file']), changed]
            top = work/'miter.v'; top.write_text(miter(case, [profile]))
            text, _ = bench(case, [profile], saturation)
            tb = work/'tb.v'; tb.write_text(text)
            executable = work/'tb.vvp'
            H.command(['iverilog','-g2001','-s','tb','-o',str(executable),*map(str,sources+[tb])],work/'compile.log')
            simulation = H.command(['vvp',str(executable)],work/'simulation.log')
            require(FAIL in simulation and PASS not in simulation, 'mutation escaped simulation: '+control)
            trace = work/'counterexample.vcd'
            script = work/'mutation.ys'
            script.write_text(setup(sources+[top], True)+
                'sat -seq 7 -set-init-zero -prove bad 0 -show-inputs -show-outputs -timeout 90 -dump_vcd '+H.quoted(trace)+'\n')
            output = H.command(['yosys','-Q','-T','-s',str(script)],work/'formal.log')
            require(H.COUNTEREXAMPLE in output and H.PASS not in output,
                    'mutation did not produce a real formal counterexample: '+control)
            H.require_counterexample_vcd(trace)
            result.append(dict(profile=profile['module'], control=control,
                               simulation='REJECTED', formal='COUNTEREXAMPLE'))
    return result


def qualify(root: Path, duplicate: Path, focus: str | None, jobs: int, saturation: bool) -> None:
    root, duplicate = root.resolve(), duplicate.resolve()
    evidence = root/'evidence.json'; evidence.unlink(missing_ok=True)
    require(root != duplicate, 'A/B require independent artifact directories')
    manifest = json.loads((root/'manifest.json').read_text()); validate(manifest, saturation)
    require((root/'manifest.json').read_bytes() == (duplicate/'manifest.json').read_bytes(), 'manifest nondeterminism')
    seen = set()
    for item in manifest['candidates']+manifest['cases']:
        a, b = H.checked_rtl(root,item['file']), H.checked_rtl(duplicate,item['file'])
        require(a not in seen and b not in seen and not a.samefile(b), 'reused A/B generated RTL')
        seen.update((a,b))
        require(a.read_bytes() == b.read_bytes(), 'actual RTL nondeterminism: '+item['file'])
    for profile in manifest['candidates']:
        validate_signed_ports(H.checked_rtl(root,profile['file']).read_text(), profile)
    validate_signed_casts([(p,H.checked_rtl(root,p['file']).read_text()) for p in manifest['candidates']])
    for tool in ('yosys','iverilog','vvp','verilator'):
        require(shutil.which(tool) is not None, 'missing required tool: '+tool)
    cases = [c for c in manifest['cases'] if focus is None or c['id'] == focus]
    require(cases, 'unknown focused case')
    with concurrent.futures.ThreadPoolExecutor(max_workers=jobs) as pool:
        reports = list(pool.map(lambda c: qualify_case(root,manifest['candidates'],c,saturation), cases))
    if focus:
        print('Focused diagnosis only; no full-matrix evidence produced.')
        return
    controls = mutations(root,manifest,saturation)
    evidence.write_text(json.dumps(dict(scope=manifest['scope'], complete_59i_join=False,
        cases=reports, mutations=controls,
        checker_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        manifest_sha256=hashlib.sha256((root/'manifest.json').read_bytes()).hexdigest(),
        rtl_sha256={i['file']:hashlib.sha256(H.checked_rtl(root,i['file']).read_bytes()).hexdigest()
                    for i in manifest['candidates']+manifest['cases']}),indent=2)+'\n')


def self_test(saturation: bool = False) -> None:
    profiles = [dict(layout=l,signed_mode=s,module=f'C{i}',child_module=f'Child{i}',file=f'C{i}.v')
                for i,(l,s) in enumerate(sorted(PROFILES))]
    cases = [dict(zip(KEYS,values),id=f'case{i}',module=f'R{i}',file=f'R{i}.v')
             for i,values in enumerate(sorted(SHAPES))]
    manifest = dict(schema=1,scope=SAT_SCOPE if saturation else SCOPE,candidates=profiles,cases=cases)
    validate(manifest,saturation)
    negatives = 0
    for collection in ('candidates','cases'):
        for i in range(len(manifest[collection])):
            changed = copy.deepcopy(manifest); del changed[collection][i]
            try: validate(changed,saturation)
            except RuntimeError: negatives += 1
            else: raise AssertionError('missing frozen matrix entry accepted')
        changed = copy.deepcopy(manifest);changed[collection].append(copy.deepcopy(changed[collection][0]))
        try: validate(changed,saturation)
        except RuntimeError: negatives += 1
        else: raise AssertionError('duplicate matrix entry accepted')
    for profile in profiles:
        qualifier = 'signed ' if profile['signed_mode'] != 'legacy' else ''
        declarations = [f'input wire {qualifier if name in ("offsetA", "offsetB") else ""}[7:0] {name}'
            for name in ('clk', 'reset', 'enable', 'biasA', 'biasB', 'offsetA', 'offsetB')]
        declarations += [f'output wire {qualifier if name in ("resultSS", "resultSP") else ""}[7:0] {name}'
            for name in ('resultUS', 'resultUP', 'resultSS', 'resultSP', 'resultSat', 'resultSamples')]
        modules = [f'module {name} #(parameter WIDTH = 8) (\n' + ',\n'.join(declarations) +
            '\n);\nwire signed [7:0] native_carrier;\nendmodule\n'
            for name in (profile['module'], profile['child_module'])]
        original = ''.join(modules)
        validate_signed_ports(original,profile)
        # Wrong qualifiers on either side of the real hierarchy must fail,
        # including every signed scalar and representative UInt/Bits outputs.
        for index in (0,1):
            for port in ('offsetA', 'offsetB', 'resultSS', 'resultSP', 'resultUS', 'resultSamples'):
                pattern = r'(?m)^((?:input|output) wire )(signed )?(\[7:0\] '+port+r'\b)'
                changed = modules.copy()
                changed[index], count = re.subn(pattern,
                    lambda m: m[1]+('' if m[2] else 'signed ')+m[3], changed[index])
                require(count == 1, 'signed-port self-test anchor missing')
                try: validate_signed_ports(''.join(changed),profile)
                except RuntimeError: negatives += 1
                else: raise AssertionError('wrong emitted signed qualifier accepted')
            try: validate_signed_ports(modules[index],profile)
            except RuntimeError: negatives += 1
            else: raise AssertionError('missing emitted hierarchy module accepted')
    cast_rtl = [(p,'$signed(value)\n' * (1 if p['signed_mode'] == 'casts' else 3)) for p in profiles]
    validate_signed_casts(cast_rtl)
    for index, (profile, _) in enumerate(cast_rtl):
        changed = cast_rtl.copy()
        changed[index] = profile, '$signed(value)\n' * (3 if profile['signed_mode'] == 'casts' else 0)
        try: validate_signed_casts(changed)
        except RuntimeError: negatives += 1
        else: raise AssertionError('wrong actual cast cleanup mode accepted')
    sample_assignments = [f'  resultSamples[({offset}) +: TAG_W] = morphhdl_balanced_{branch}_result_leaf_{leaf};'
        for branch in (1,2) for offset,leaf in (('0',5),('TAG_W',6),('2 * TAG_W',7))]
    def sample_module(assignments: list[str]) -> str:
        return 'module '+profiles[0]['child_module']+' ();\nif (1 < INNER) begin\n'+\
            '\n'.join(assignments)+'\nend\nendmodule\n'
    changed = mutate(sample_module(sample_assignments),profiles[0],'bypass-latency')
    require('if (1 < INNER) begin' in changed and changed.count('values_samples[') == 6,
            'latency mutation changed guards or missed actual sample slices')
    bad_samples = [sample_assignments[:i]+sample_assignments[i+1:] for i in range(6)]
    bad_samples += [sample_assignments+[sample_assignments[0]],
        [s.replace('result_leaf_7','result_leaf_6') for s in sample_assignments]]
    for assignments in bad_samples:
        try: mutate(sample_module(assignments),profiles[0],'bypass-latency')
        except RuntimeError: negatives += 1
        else: raise AssertionError('incomplete or misbound latency slice anchors accepted')
    for case in cases:
        word, fields = geometry(case)
        bits = [o+bit for slots in fields.values() for o,w in slots for bit in range(w)]
        require(sorted(bits) == list(range(word)), 'input geometry gap/overlap')
        for lane in range(case['count']):
            for bit in range(word):
                decoded = decode(case,(1 << bit) << (word*lane))
                require(all(all(x == 0 for x in row) for i,row in enumerate(decoded) if i != lane),
                        'outer Vec index decode leaked')
        require('?' not in '\n'.join(instances(case,profiles)), 'layout adapter has arithmetic selection')
    case = dict(zip(KEYS,(3,2,4,3,5,0)))
    pipe = Pipeline(case,saturation)
    zero = {p:0 for p in input_widths(case) if p not in ('clk','reset','enable')}
    pipe.step(zero,True,True)
    ones = dict(zero,values=(1 << (geometry(case)[0]*case['count']))-1,biasA=3,offsetA=1)
    for _ in range(3): answer=pipe.step(ones,False,True)
    require(answer[0] == 5*7+4*3 and answer[1] == 7**5 and answer[2] == -5+4,
            'odd-tail widening/capture software model failed')
    require(answer[3] == -1 and answer[4] == 15, 'signed product/saturation model failed')
    require(pipe.step(zero,True,False) == answer, 'stalled reset must retain state')
    require(pipe.step(zero,True,True) == (0,)*6, 'enabled reset did not initialize every leaf')
    dynamic = Pipeline(case,saturation)
    dynamic.step(zero,True,True)
    row_ones = sum(1 << (geometry(case)[0]*lane) for lane in range(case['count']))
    for bias in (1,2,3):
        actual = dynamic.step(dict(zero,values=row_ones,biasA=bias),False,True)
    # Five inputs plus two first-level bias=1 uses, one next-level bias=2
    # use, and one final-level bias=3 use: 5 + 2 + 2 + 3 = 12.
    require(actual[0] == 12, 'captures were flattened or sampled at the wrong register level')
    singleton = dict(case,count=1)
    require(Pipeline(singleton,saturation).step(ones,True,False) == decode(singleton,ones['values'])[0],
            'COUNT=1 gained a bridge')
    for control in MUTATIONS:
        try: mutate('module missing; endmodule',profiles[0],control)
        except RuntimeError: negatives += 1
        else: raise AssertionError('absent real RTL mutation anchor accepted')
    print(f'Nested-mechanism checker self-test PASS: {len(cases)} cases, {len(profiles)} profiles, '
          f'{negatives} negative controls; no HDL run')


def main(saturation: bool = False) -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root',nargs='?',type=Path);parser.add_argument('duplicate',nargs='?',type=Path)
    parser.add_argument('--case');parser.add_argument('--jobs',type=int,default=1)
    parser.add_argument('--self-test',action='store_true')
    args = parser.parse_args()
    if args.self_test: self_test(saturation)
    else:
        require(args.root is not None and args.duplicate is not None and 1 <= args.jobs <= 4,
                'two artifact directories and jobs 1..4 required')
        qualify(args.root,args.duplicate,args.case,args.jobs,saturation)


if __name__ == '__main__':
    main()
