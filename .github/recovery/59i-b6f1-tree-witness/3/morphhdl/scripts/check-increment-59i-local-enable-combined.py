#!/usr/bin/env python3
"""Compare combined local-enable RTL to an independent concrete native oracle.

The six shapes cover generated child parameters, both MODE owner branches,
singleton bypass, odd tails, recursive Vec fields, widening, signed arithmetic,
runtime captures and saturation. Adapters only slice and concatenate wires.
The expected state comes from native RTL; Python supplies no datapath model.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
from pathlib import Path
import random
import re
import shutil
import subprocess

SCOPE = '59i-local-enable-combined-native-hardware'
PASS = '59I-LOCAL-ENABLE-COMBINED-PASS'
FAIL = '59I-LOCAL-ENABLE-COMBINED-MISMATCH'
KEYS = ('uw', 'sw', 'tw', 'inner', 'count', 'mode')
SHAPES = {(2, 3, 2, 1, 1, 0), (4, 2, 3, 3, 1, 1),
          (2, 4, 2, 1, 3, 0), (3, 2, 4, 3, 3, 1),
          (4, 3, 2, 3, 5, 0), (2, 4, 3, 1, 5, 1)}
PROFILES = {('packed', 'legacy', False), ('packed', 'casts', True),
            ('fields', 'declarations', False), ('fields', 'casts', True)}
IDENTIFIER = re.compile(r'[A-Za-z_][A-Za-z_0-9]*')


def require(ok, message):
    if not ok:
        raise RuntimeError(message)


def validate(value):
    require(set(value) == {'schema', 'scope', 'candidates', 'mutations', 'cases'} and
            type(value['schema']) is int and value['schema'] == 1 and value['scope'] == SCOPE,
            'wrong combined manifest schema/scope')
    candidates, mutants, cases = (value[k] for k in ('candidates', 'mutations', 'cases'))
    fields = {'layout', 'signed_mode', 'split', 'module', 'child_module', 'files'}
    require(all(set(c) == fields for c in candidates), 'wrong candidate metadata')
    require(len(candidates) == 4 and {(c['layout'], c['signed_mode'], c['split'])
            for c in candidates} == PROFILES, 'missing or duplicate emission profile')
    require(all(type(c['split']) is bool for c in candidates), 'invalid normal candidate flags')
    require(mutants == ['root-enable-polarity'], 'missing functional emitted-RTL mutation')
    require(all(set(c) == set(KEYS) | {'id', 'module', 'files'} and
            all(type(c[k]) is int for k in KEYS) for c in cases), 'wrong native metadata')
    require(len(cases) == 6 and {tuple(c[k] for k in KEYS) for c in cases} == SHAPES,
            'missing or duplicate combined native shape')
    require(len({c['id'] for c in cases}) == len(cases) and
            all(IDENTIFIER.fullmatch(c['id']) for c in cases), 'invalid case identity')
    entries = candidates + cases
    modules = [c['module'] for c in entries] + [c['child_module'] for c in candidates]
    require(len(set(modules)) == len(modules) and all(IDENTIFIER.fullmatch(m) for m in modules),
            'invalid or duplicate module identity')
    files = []
    for entry in entries:
        require(isinstance(entry['files'], list) and entry['files'] and
                all(isinstance(p, str) and p.endswith('.v') for p in entry['files']),
                'missing actual RTL file inventory')
        files.extend(entry['files'])
    require(len(set(files)) == len(files), 'overlapping RTL inventory')


def checked_file(root, relative):
    name = Path(relative)
    require(not name.is_absolute() and '..' not in name.parts, 'unsafe RTL path')
    file = root / name
    require(all(not root.joinpath(*name.parts[:n]).is_symlink()
                for n in range(1, len(name.parts) + 1)) and file.is_file(), 'missing/linked RTL')
    return file


def geometry(case):
    u, s, t, inner = (case[k] for k in KEYS[:4])
    return 2*u + 2*s + t*(inner+1), {
        'unsignedSum': [(0, u)], 'unsignedProduct': [(u, u)],
        'signedSum': [(2*u, s)], 'signedProduct': [(2*u+s, s)],
        'saturated': [(2*u+2*s, t)],
        'samples': [(2*u+2*s+t*(i+1), t) for i in range(inner)]}


def inputs(case):
    word, _ = geometry(case)
    return dict(clk=1, reset=1, enable=1, values=word*case['count'],
                biasA=case['uw'], biasB=case['uw'], offsetA=case['sw'], offsetB=case['sw'])


def outputs(case):
    u, s, t, inner, count, _ = (case[k] for k in KEYS)
    return dict(resultUS=u+2*count, resultUP=u*count, resultSS=s+2*count,
                resultSP=s*count, resultSat=t, resultSamples=t*inner)


def instances(case, candidates):
    lines = []
    word, fields = geometry(case)
    for prefix, item in [('g_', case)] + [(f'c{i}_', c) for i, c in enumerate(candidates)]:
        bindings = [f'.{p}({p})' for p in inputs(case) if p != 'values']
        for port, width in outputs(case).items():
            lines.append(f'wire [{width-1}:0] {prefix}{port};')
            bindings.append(f'.{port}({prefix}{port})')
        if item is case or item['layout'] == 'packed':
            bindings.append('.values(values)')
        else:
            for field, slots in fields.items():
                width = sum(w for _, w in slots)*case['count']
                pieces = [f'values[{lane*word+offset} +: {w}]'
                          for lane in reversed(range(case['count'])) for offset, w in reversed(slots)]
                expression = pieces[0] if len(pieces) == 1 else '{' + ', '.join(pieces) + '}'
                lines += [f'wire [{width-1}:0] {prefix}in_{field};',
                          f'assign {prefix}in_{field} = {expression};']
                bindings.append(f'.values_{field}({prefix}in_{field})')
        parameters = '' if item is case or item.get('concrete', False) else '#(' + ', '.join(
            f'.{name}({case[key]})' for name, key in zip(
                ('U_W', 'S_W', 'TAG_W', 'INNER', 'COUNT', 'MODE'), KEYS)) + ')'
        lines.append(f'{item["module"]} {parameters} {prefix}dut(' + ', '.join(bindings) + ');')
    return lines


def stimulus(case):
    generator = random.Random(0x59_1_E + sum((i+1)*case[k] for i, k in enumerate(KEYS)))
    widths = {k: v for k, v in inputs(case).items() if k not in ('clk', 'reset', 'enable')}
    for cycle in range(320):
        if cycle < 8:
            yield {k: ((1 << w)-1 if (cycle >> (i % 3)) & 1 else 0)
                   for i, (k, w) in enumerate(widths.items())}
        else:
            yield {k: generator.getrandbits(w) for k, w in widths.items()}


def bench(case, candidates):
    lines = ['`timescale 1ns/1ps', 'module tb;']
    lines += [f'reg [{w-1}:0] {p};' for p, w in inputs(case).items()]
    lines += ['integer sample; integer checks;'] + instances(case, candidates)
    lines += [f'reg [{w-1}:0] previous_{p};' for p, w in outputs(case).items()]
    lines += ['task compare; begin', 'checks = checks + 1;']
    for port in outputs(case):
        lines += [f"if ((^g_{port}) === 1'bx) begin",
                  f'$display("{FAIL} unknown-native sample=%0d port={port}", sample); $finish; end']
        for index in range(len(candidates)):
            lines += [f'if (g_{port} !== c{index}_{port}) begin',
                      f'$display("{FAIL} candidate={index} sample=%0d port={port} native=%h candidate=%h", '
                      f'sample, g_{port}, c{index}_{port}); $finish; end']
    lines += ['end endtask', 'task save_state; begin']
    lines += [f'previous_{p} = g_{p};' for p in outputs(case)]
    lines += ['end endtask', 'task held; begin']
    if case['count'] > 1:
        for port in outputs(case):
            lines += [f'if (previous_{port} !== g_{port}) begin',
                      f'$display("{FAIL} native-hold sample=%0d port={port}", sample); $finish; end']
    lines += ['end endtask', 'task reset_values; begin']
    if case['count'] > 1:
        for port, width in outputs(case).items():
            value = (1 << width)-1 if port in ('resultSS', 'resultSP') else (
                1 if port in ('resultUS', 'resultUP') else 0)
            lines += [f"if (g_{port} !== {width}'h{value:x}) begin",
                      f'$display("{FAIL} native-reset sample=%0d port={port}", sample); $finish; end']
    lines += ['compare; end endtask', 'task pulse_reset; begin',
              'clk=0; enable=0; reset=0; #2; save_state;', 'reset=1; #2;',
              'if (sample >= 0) begin compare; held; end', 'clk=1; #2;',
              'if (sample >= 0) begin compare; held; end',
              'clk=0; #2; enable=1; #2; clk=1; #2; reset_values; save_state;',
              'clk=0; #2; compare; held; reset=0; #2; compare; held;',
              'end endtask', 'initial begin', 'sample=-1; checks=0;']
    lines += [f'{p}=0;' for p in inputs(case)]
    lines.append('pulse_reset;')
    for cycle, values in enumerate(stimulus(case)):
        enabled = cycle < 64 or cycle % 9 not in (0, 1, 6)
        lines += [f'sample={cycle}; enable={int(enabled)};']
        lines += [f"{p}={inputs(case)[p]}'h{value:x};" for p, value in values.items()]
        lines += ['#2; compare; save_state;', 'clk=1; #2; compare;']
        if not enabled:
            lines.append('held;')
        lines += ['save_state;', 'clk=0; #2; compare; held;']
        if cycle in (103, 217):
            lines.append('pulse_reset;')
    lines += [f'$display("{PASS} checks=%0d", checks); $finish;', 'end', 'endmodule', '']
    return '\n'.join(lines)


def run(command, log):
    try:
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                text=True, timeout=180)
    except (OSError, subprocess.TimeoutExpired) as error:
        log.write_text(str(error) + '\n')
        raise RuntimeError('tool failed: ' + str(log)) from error
    log.write_text(result.stdout)
    require(result.returncode == 0, 'tool failed: ' + str(log))
    return result.stdout


def validate_signed_ports(rtl, profile):
    clean = re.sub(r'/\*.*?\*/|//[^\n]*', '', rtl, flags=re.S)
    for module in (profile['module'], profile['child_module']):
        headers = re.findall(r'(?ms)^module\s+' + re.escape(module) + r'\b(.*?)\);', clean)
        require(len(headers) == 1, 'missing unique module header for signed mode')
        for port in ('offsetA', 'offsetB', 'resultSS', 'resultSP'):
            declarations = re.findall(r'\b(?:input|output)\s+(?:wire|reg)\s+(signed\s+)?'
                                      r'(?:\[[^\]]+\]\s*)?' + port + r'\b', headers[0])
            require(len(declarations) == 1 and bool(declarations[0]) == (profile['signed_mode'] != 'legacy'),
                    'actual signed declaration mode differs: ' + module + '.' + port)


def yosys_quote(path):
    return '"' + str(path).replace('\\', '\\\\').replace('"', '\\"') + '"'


def specialize_and_mutate(root, directory, case, original):
    """Mutate one real synthesized root register; preserve its reset and type."""
    directory.mkdir(parents=True, exist_ok=True)
    source_json = directory / 'specialized.json'
    native_files = [checked_file(root, f) for f in original['files']]
    params = ' '.join(f'-chparam {name} {case[key]}' for name, key in zip(
        ('U_W', 'S_W', 'TAG_W', 'INNER', 'COUNT', 'MODE'), KEYS))
    script = directory / 'specialize.ys'
    script.write_text('read_verilog ' + ' '.join(map(yosys_quote, native_files)) + '\n' +
                      f'hierarchy -check -top {original["module"]} {params}\n' +
                      'proc\nflatten\nopt\n' + f'hierarchy -check -top {original["module"]}\n' +
                      'check -assert\nwrite_json ' + yosys_quote(source_json) + '\n')
    run(['yosys', '-Q', '-s', str(script)], directory / 'specialize.log')
    original_netlist = json.loads(source_json.read_text())
    netlist = copy.deepcopy(original_netlist)
    module = netlist['modules'][original['module']]
    result_bits = {b for b in module['ports']['resultUS']['bits'] if type(b) is int}
    choices = [(name, cell) for name, cell in module['cells'].items()
               if 'EN_POLARITY' in cell['parameters'] and 'EN' in cell['connections'] and
               cell['connections'].get('Q') and
               set(cell['connections']['Q']) <= result_bits]
    require(len(choices) == 1, 'cannot uniquely locate emitted root unsigned-result enable register')
    name, cell = choices[0]
    require(cell['type'] in ('$dffe', '$sdffe', '$sdffce', '$adffe'),
            'unexpected emitted root register type')
    before = cell['parameters']['EN_POLARITY']
    require(isinstance(before, str) and set(before) <= {'0', '1'} and int(before, 2) in (0, 1),
            'invalid emitted root enable polarity')
    after = ('0' * (len(before)-1)) + str(1-int(before, 2))
    cell['parameters']['EN_POLARITY'] = after
    mutant_json = directory / 'mutant.json'
    mutant_json.write_text(json.dumps(netlist, indent=2) + '\n')
    # Assert precisely one JSON scalar changed, including unchanged SRST/ARST
    # priority, reset values, register type, data and enable-net connections.
    restored = copy.deepcopy(netlist)
    restored['modules'][original['module']]['cells'][name]['parameters']['EN_POLARITY'] = before
    require(restored == original_netlist, 'mutation changed more than one enable polarity')
    profiles = []
    for tag, file in (('normalized', source_json), ('mutant', mutant_json)):
        rtl = directory / (tag + '.v')
        script = directory / (tag + '.ys')
        script.write_text('read_json ' + yosys_quote(file) + '\ncheck -assert\nwrite_verilog -noattr ' +
                          yosys_quote(rtl) + '\n')
        run(['yosys', '-Q', '-s', str(script)], directory / (tag + '-write.log'))
        run(['verilator', '--lint-only', '-Wno-fatal', '--language', '1364-2001',
             '--top-module', original['module'], str(rtl)], directory / (tag + '-lint.log'))
        profiles.append(dict(original, files=[str(rtl.relative_to(root))], concrete=True))
    return (*profiles, dict(kind='root-enable-polarity', cell=name, cell_type=cell['type'],
                           before=before, after=after,
                           source_rtl_sha256={str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest()
                                              for p in native_files},
                           original_json_sha256=hashlib.sha256(source_json.read_bytes()).hexdigest(),
                           mutant_json_sha256=hashlib.sha256(mutant_json.read_bytes()).hexdigest()))


def simulate(root, directory, case, candidates, mutant=False):
    directory.mkdir(parents=True, exist_ok=True)
    tb = directory / 'tb.v'
    tb.write_text(bench(case, candidates))
    files = [checked_file(root, f) for entry in [case] + candidates for f in entry['files']]
    executable = directory / 'tb.vvp'
    run(['iverilog', '-g2001', '-Wall', '-Wimplicit', '-s', 'tb', '-o', str(executable),
         *map(str, files), str(tb)], directory / 'compile.log')
    log = run(['vvp', str(executable)], directory / 'simulate.log')
    if mutant:
        require(FAIL in log and PASS not in log and 'candidate=0' in log,
                'mutated root register enable did not produce a native functional counterexample')
    else:
        require(log.count(PASS) == 1 and FAIL not in log, 'native combined RTL comparison failed')
    return dict(case=case['id'], comparisons=len(candidates), result='counterexample' if mutant else 'pass',
                rtl_sha256={str(f.relative_to(root)): hashlib.sha256(f.read_bytes()).hexdigest() for f in files},
                testbench_sha256=hashlib.sha256(tb.read_bytes()).hexdigest(), log=str(directory / 'simulate.log'))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--artifacts', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    root, out = args.artifacts.resolve(), args.output.resolve()
    # A failed rerun must never leave an earlier successful hardware receipt.
    (out / 'receipt.json').unlink(missing_ok=True)
    value = json.loads(checked_file(root, 'manifest.json').read_text())
    validate(value)
    for tool in ('iverilog', 'vvp', 'yosys', 'verilator'):
        require(shutil.which(tool), 'required simulator missing: ' + tool)
    require(root in out.parents, 'output must be a subdirectory of artifact root')
    out.mkdir(parents=True, exist_ok=True)
    for item in value['candidates']:
        rtl = '\n'.join(checked_file(root, f).read_text() for f in item['files'])
        for module in (item['module'], item['child_module']):
            require(len(re.findall(r'(?m)^module\s+' + re.escape(module) + r'\b', rtl)) == 1,
                    'missing/duplicate generated hierarchy module')
        require('.MODE((MODE+1))' in re.sub(r'\s+', '', rtl), 'child MODE expression binding lost')
        require('g_first_capture' in rtl and 'g_second_capture' in rtl, 'typed MODE owner missing')
        field_port = re.search(r'\binput\s+(?:wire|reg)\s+(?:signed\s+)?(?:\[[^\]]+\]\s*)?values_unsignedSum\b', rtl)
        require(bool(field_port) == (item['layout'] == 'fields'), 'layout declaration differs')
        validate_signed_ports(rtl, item)
        require(len(item['files']) >= 2 if item['split'] else len(item['files']) == 1,
                'actual split/consolidated artifact layout differs')
    results = [simulate(root, out / case['id'], case, value['candidates']) for case in value['cases']]
    anchor = next(c for c in value['cases'] if tuple(c[k] for k in KEYS) == (2, 4, 2, 1, 3, 0))
    original = next(c for c in value['candidates'] if (c['layout'], c['signed_mode'], c['split']) == ('packed', 'legacy', False))
    normalized, mutant, lineage = specialize_and_mutate(root, out / 'mutated-emitted-rtl', anchor, original)
    baseline = simulate(root, out / 'normalized-baseline', anchor, [normalized])
    mutants = [simulate(root, out / 'mutation-root-enable-polarity', anchor, [mutant], True)]
    receipt = dict(schema=1, scope=SCOPE, result='pass', native_cases=len(results),
                   candidate_comparisons=sum(r['comparisons'] for r in results), cycles_per_case=320,
                   formal='not-run', results=results, mutations=mutants,
                   normalized_baseline=baseline, mutation_lineage=lineage)
    (out / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(f'{PASS}: 24 native comparisons; one emitted root-enable mutant rejected')


if __name__ == '__main__':
    main()
