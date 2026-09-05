#!/usr/bin/env python3
"""HDL and formal qualification of concrete native operator replay, not COUNT publication."""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import itertools
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

WIDTHS = (1, 5, 8, 32)
COUNTS = (1, 2, 3, 5, 8, 9, 16, 17)
VECTOR_OUTPUTS = ('uAdd', 'uAnd', 'uOr', 'uXor', 'sAdd', 'sAnd', 'sOr', 'sXor', 'bAnd', 'bOr', 'bXor', 'uMin', 'uMax', 'sMin', 'sMax')
BOOL_OUTPUTS = ('qAnd', 'qOr', 'qXor')
OUTPUTS = VECTOR_OUTPUTS + BOOL_OUTPUTS
PASS = 'SAT proof finished - no model found: SUCCESS!'
COUNTEREXAMPLE = 'SAT proof finished - model found: FAIL!'
IDENTIFIER = re.compile(r'[A-Za-z_][A-Za-z0-9_]*')


def command(args: list[str], log: Path, timeout: int = 180) -> str:
    try:
        result = subprocess.run(args, text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=timeout, check=False)
    except (OSError, subprocess.TimeoutExpired) as error:
        log.write_text(str(error) + '\n')
        raise RuntimeError(f'tool did not complete; see {log}') from error
    log.write_text(result.stdout)
    if result.returncode:
        raise RuntimeError(f'tool exited {result.returncode}; see {log}')
    return result.stdout


def quoted(path: Path) -> str:
    return '"' + str(path).replace('\\', '\\\\').replace('"', '\\"') + '"'


def checked_rtl(root: Path, relative: str) -> Path:
    path = (root / relative).resolve()
    if root.resolve() not in path.parents or not path.is_file():
        raise RuntimeError('invalid or missing artifact path: ' + relative)
    return path


def bitwidth(name: str, width: int) -> int:
    return 1 if name in BOOL_OUTPUTS else width


def instances(case: dict, mutation: bool = False) -> list[str]:
    width = case['width']
    lines = []
    for prefix, role in (('g', 'reference'), ('c', 'replay')):
        lines += [f'wire [{bitwidth(name, width) - 1}:0] {prefix}_{name};' for name in OUTPUTS]
        bindings = ['.dataIn(dataIn)', '.boolIn(boolIn)'] + [f'.{name}({prefix}_{name})' for name in OUTPUTS]
        lines += [f"{case[role + '_module']} {prefix}({', '.join(bindings)});"]
    lines += [f"wire [{width - 1}:0] checked_sum = c_uAdd ^ {width}'h{int(mutation):x};"]
    return lines


def miter(case: dict, mutation: bool = False) -> str:
    width, count = case['width'], case['count']
    lines = [f'module miter(input wire [{width * count - 1}:0] dataIn,',
             f'input wire [{count - 1}:0] boolIn, output wire bad);']
    lines += instances(case, mutation)
    comparisons = [f'(|(g_{name} ^ {"checked_sum" if name == "uAdd" else "c_" + name}))' for name in OUTPUTS]
    lines += ['assign bad = ' + ' | '.join(comparisons) + ';', 'endmodule', '']
    return '\n'.join(lines)


def expected(words: tuple[int, ...], flags: int, width: int) -> dict[str, int]:
    mask = (1 << width) - 1
    conjunction, disjunction, parity = mask, 0, 0
    for word in words:
        if not 0 <= word <= mask:
            raise ValueError('input word out of range')
        conjunction &= word
        disjunction |= word
        parity ^= word
    result = {name: value for name, value in zip(
        VECTOR_OUTPUTS, (sum(words) & mask, conjunction, disjunction, parity,
                         sum(words) & mask, conjunction, disjunction, parity,
                         conjunction, disjunction, parity))}
    signed = tuple(word if word < (1 << (width - 1)) else word - (1 << width) for word in words)
    result.update(uMin=min(words), uMax=max(words), sMin=min(signed) & mask, sMax=max(signed) & mask)
    result.update(qAnd=int(flags == (1 << len(words)) - 1), qOr=int(flags != 0), qXor=bin(flags).count('1') % 2)
    return result


def testbench(case: dict, vectors: list[tuple[int, ...]]) -> str:
    width, count = case['width'], case['count']
    lines = ['`timescale 1ns/1ps', 'module tb;',
             f'reg [{width * count - 1}:0] dataIn;', f'reg [{count - 1}:0] boolIn;']
    lines += instances(case)
    lines += ['initial begin']
    for index, words in enumerate(vectors):
        flags = ((index * 0x9e3779b1) ^ (index >> 1)) & ((1 << count) - 1)
        if index == 0:
            flags = 0
        elif index == 1:
            flags = (1 << count) - 1
        packed = sum(word << (i * width) for i, word in enumerate(words))
        lines += [f"dataIn = {width * count}'h{packed:x}; boolIn = {count}'h{flags:x}; #1;"]
        for name, value in expected(words, flags, width).items():
            bits = bitwidth(name, width)
            for prefix in ('g', 'c'):
                lines += [f"if ({prefix}_{name} !== {bits}'h{value:x}) begin",
                          f'$display("59B-OPERATOR-MISMATCH {prefix}_{name} sample={index}"); $finish(1);', 'end']
    lines += ['$display("59B-OPERATOR-SIM-PASS"); $finish;', 'end', 'endmodule', '']
    return '\n'.join(lines)


def require_counterexample_vcd(path: Path) -> None:
    if not path.is_file():
        raise RuntimeError('formal mutation produced no counterexample trace')
    lines = path.read_text().splitlines()
    identifiers = []
    for line in lines:
        fields = line.split()
        if len(fields) >= 6 and fields[0] == '$var' and fields[4].lstrip('\\') == 'bad':
            identifiers.append(fields[3])
    if len(identifiers) != 1 or not any(line.strip() in ('1' + identifiers[0], 'b1 ' + identifiers[0]) for line in lines):
        raise RuntimeError('formal mutation trace does not exhibit bad=1')


def qualify(root: Path, duplicate: Path) -> None:
    root, duplicate = root.resolve(), duplicate.resolve()
    for tool in ('iverilog', 'vvp', 'verilator', 'yosys'):
        if shutil.which(tool) is None:
            raise RuntimeError('required tool missing: ' + tool)
    manifest = json.loads((root / 'manifest.json').read_text())
    if manifest.get('scope') != 'concrete-native-operator-replay' or manifest.get('parameterized_tree_formal') != 'not-run':
        raise RuntimeError('incorrect scope: concrete replay is not parameterized-stage proof')
    if (root / 'manifest.json').read_bytes() != (duplicate / 'manifest.json').read_bytes():
        raise RuntimeError('nondeterministic operator manifest')
    cases = manifest['configurations']
    shapes = [(case['width'], case['count']) for case in cases]
    if len(shapes) != 32 or set(shapes) != set(itertools.product(WIDTHS, COUNTS)):
        raise RuntimeError('incomplete or duplicated operator matrix')
    oracle_path = Path(__file__).with_name('check-increment-59b-native-oracle.py')
    spec = importlib.util.spec_from_file_location('native_stimulus', oracle_path)
    if spec is None or spec.loader is None:
        raise RuntimeError('missing independent native stimulus module')
    stimulus = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(stimulus)
    evidence = []
    for case in cases:
        width, count = case['width'], case['count']
        if case['replay_calls'] != len(OUTPUTS) * (count - 1):
            raise RuntimeError('incorrect recorded replay coverage')
        work = root / 'checks' / f'w{width}_n{count}'
        work.mkdir(parents=True, exist_ok=True)
        rtl_paths = []
        digests = {}
        for role in ('reference', 'replay'):
            module = case[role + '_module']
            if not IDENTIFIER.fullmatch(module):
                raise RuntimeError('invalid module identifier')
            rtl = checked_rtl(root, case[role + '_rtl'])
            other = checked_rtl(duplicate, case[role + '_rtl'])
            if rtl.read_bytes() != other.read_bytes():
                raise RuntimeError('nondeterministic ' + role + ' RTL')
            rtl_paths.append(rtl)
            digests[role] = hashlib.sha256(rtl.read_bytes()).hexdigest()
            command(['verilator', '--lint-only', '--language', '1364-2001', '--top-module', module, str(rtl)], work / (role + '-lint.log'))
            script = work / (role + '-synthesis.ys')
            script.write_text(f'read_verilog {quoted(rtl)}\nhierarchy -check -top {module}\nsynth -top {module}\ncheck -assert\nstat\n')
            command(['yosys', '-Q', '-T', '-s', str(script)], work / (role + '-synthesis.log'))
        vectors = stimulus.samples(width, count)
        bench = work / 'tb.v'
        bench.write_text(testbench(case, vectors))
        executable = work / 'tb.vvp'
        command(['iverilog', '-g2001', '-s', 'tb', '-o', str(executable), *map(str, rtl_paths), str(bench)], work / 'compile.log')
        output = command(['vvp', str(executable)], work / 'simulation.log')
        if '59B-OPERATOR-SIM-PASS' not in output or '59B-OPERATOR-MISMATCH' in output:
            raise RuntimeError('operator simulation did not pass')
        miter_path = work / 'miter.v'
        miter_path.write_text(miter(case))
        script = work / 'equivalence.ys'
        script.write_text('read_verilog ' + ' '.join(quoted(path) for path in (*rtl_paths, miter_path)) +
                          '\nprep -top miter -flatten\ncheck -assert\nsat -prove bad 0 -show-inputs -show-outputs -timeout 90\n')
        proof = command(['yosys', '-Q', '-T', '-s', str(script)], work / 'equivalence.log')
        if PASS not in proof or COUNTEREXAMPLE in proof:
            raise RuntimeError('operator proof missing definitive SUCCESS: ' + str(work))
        evidence.append(dict(width=width, count=count, outputs=len(OUTPUTS), samples=len(vectors), sha256=digests, formal='PASS'))

    # Mutate the observed replay sum only after all normal proofs pass. Require
    # an actual satisfying model and a VCD bad=1 witness, never timeout/errors.
    case = next(case for case in cases if (case['width'], case['count']) == (5, 5))
    work = root / 'checks' / 'mutation'
    work.mkdir(parents=True, exist_ok=True)
    miter_path = work / 'miter.v'
    miter_path.write_text(miter(case, mutation=True))
    rtl_paths = [checked_rtl(root, case[role + '_rtl']) for role in ('reference', 'replay')]
    trace = work / 'counterexample.vcd'
    script = work / 'mutation.ys'
    script.write_text('read_verilog ' + ' '.join(quoted(path) for path in (*rtl_paths, miter_path)) +
                      '\nprep -top miter -flatten\ncheck -assert\nsat -prove bad 0 -show-inputs -show-outputs -timeout 90 -dump_vcd ' + quoted(trace) + '\n')
    result = command(['yosys', '-Q', '-T', '-s', str(script)], work / 'mutation.log')
    if COUNTEREXAMPLE not in result or PASS in result:
        raise RuntimeError('formal mutation did not produce a definitive counterexample')
    require_counterexample_vcd(trace)
    (root / 'evidence.json').write_text(json.dumps(dict(
        scope='concrete-native-operator-replay', parameterized_tree_formal='not-run',
        configurations=evidence, mutation='counterexample-with-bad=1'), indent=2) + '\n')
    print('PASS: 32 concrete native-replay miters, 18 outputs each, independent simulation/lint/full synthesis, determinism and a real mutation counterexample')


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path)
    parser.add_argument('duplicate', type=Path)
    args = parser.parse_args()
    qualify(args.root, args.duplicate)


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, RuntimeError, KeyError) as error:
        print('FAIL: ' + str(error), file=sys.stderr)
        sys.exit(1)
