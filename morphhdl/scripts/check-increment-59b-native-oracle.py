#!/usr/bin/env python3
"""Native oracle qualification only; no typed-candidate formal claim."""
from __future__ import annotations
import argparse
import hashlib
import itertools
import json
import random
import shutil
import subprocess
import sys
from pathlib import Path

WIDTHS = (1, 5, 8, 32)
COUNTS = (1, 2, 3, 5, 8, 9, 16, 17)
OUTPUTS = ('sumResult', 'orResult', 'xorResult', 'minResult', 'maxResult',
           'signedMinResult', 'signedMaxResult', 'growingResult')


def expected(words, width):
    if width < 1 or not words or any(v < 0 or v >= 1 << width for v in words):
        raise ValueError('nonempty in-range unsigned words required')
    mask = (1 << width) - 1
    signed = [v if v < (1 << (width - 1)) else v - (1 << width) for v in words]
    ored = xored = 0
    for value in words:
        ored |= value
        xored ^= value
    return dict(zip(OUTPUTS, (sum(words) & mask, ored, xored, min(words), max(words),
                             min(signed) & mask, max(signed) & mask, sum(words))))


def samples(width, count):
    if width * count <= 8:
        return list(itertools.product(range(1 << width), repeat=count))
    mask, sign = (1 << width) - 1, 1 << (width - 1)
    values = [tuple([v] * count) for v in (0, 1, mask, sign, sign - 1)]
    values += [tuple((i + k) & mask for i in range(count)) for k in (0, 1, sign)]
    values += [tuple(mask if (i + p) % 2 else 0 for i in range(count)) for p in (0, 1)]
    for index in range(count):
        for value in (1, mask, sign):
            values.append(tuple(value if i == index else 0 for i in range(count)))
    rng = random.Random((width << 16) | count)
    values += [tuple(rng.getrandbits(width) for _ in range(count)) for _ in range(128)]
    return list(dict.fromkeys(values))


def literal(width, value):
    if width < 1 or not 0 <= value < (1 << width):
        raise ValueError('invalid Verilog literal')
    return f"{width}'h{value:x}"


def testbench(width, count, module, mutate=False):
    levels = (count - 1).bit_length()
    widths = {key: width + (levels if key == 'growingResult' else 0) for key in OUTPUTS}
    lines = ['`timescale 1ns/1ps', 'module tb;', 'reg clk = 0; reg reset = 1;',
             f'reg [{width * count - 1}:0] dataIn = 0;']
    lines += [f'wire [{bits - 1}:0] {key};' for key, bits in widths.items()]
    lines += [f'wire [{width - 1}:0] pipelineResult, checkedSum;',
              f'assign checkedSum = sumResult ^ {literal(width, int(mutate))};']
    ports = ['.clk(clk)', '.reset(reset)', '.dataIn(dataIn)']
    ports += [f'.{key}({key})' for key in (*OUTPUTS, 'pipelineResult')]
    lines += [f"{module} dut({', '.join(ports)});", 'initial begin',
              '#5; clk = 1; #5; clk = 0; reset = 0;']
    history = []
    for index, words in enumerate(samples(width, count)):
        results = expected(words, width)
        packed = sum(value << (i * width) for i, value in enumerate(words))
        lines += [f'dataIn = {literal(width * count, packed)}; #1;']
        for key in OUTPUTS:
            observed = 'checkedSum' if key == 'sumResult' else key
            lines += [f'if ({observed} !== {literal(widths[key], results[key])}) begin',
                      f'$display("MORPHHDL-59B-MISMATCH {key} W={width} N={count} sample={index}"); $finish(1);', 'end']
        history.append(results['sumResult'])
        target = results['sumResult'] if levels == 0 else (history[index - levels + 1] if index >= levels - 1 else 0)
        lines += ['#4; clk = 1; #1;', f'if (pipelineResult !== {literal(width, target)}) begin',
                  f'$display("MORPHHDL-59B-MISMATCH pipelineResult W={width} N={count} sample={index}"); $finish(1);',
                  'end', '#4; clk = 0;']
    lines += ['$display("MORPHHDL-59B-NATIVE-ORACLE-PASS"); $finish;', 'end', 'endmodule', '']
    return '\n'.join(lines)


def run(command, log):
    try:
        result = subprocess.run(command, text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=180, check=False)
    except (OSError, subprocess.TimeoutExpired) as error:
        log.write_text(str(error) + '\n')
        raise RuntimeError(f'tool did not complete; see {log}') from error
    log.write_text(result.stdout)
    if result.returncode:
        raise RuntimeError(f'tool failed ({result.returncode}); see {log}')
    return result.stdout


def qualify(root, replay):
    for tool in ('iverilog', 'vvp', 'verilator', 'yosys'):
        if shutil.which(tool) is None:
            raise RuntimeError(f'required tool missing: {tool}')
    manifest = json.loads((root / 'manifest.json').read_text())
    if manifest.get('status') != 'native-oracle-only':
        raise RuntimeError('incorrect evidence status')
    cases = manifest['configurations']
    shapes = [(c['width'], c['count']) for c in cases]
    if len(shapes) != len(set(shapes)) or set(shapes) != set(itertools.product(WIDTHS, COUNTS)):
        raise RuntimeError('incomplete or duplicated matrix')
    if (root / 'manifest.json').read_bytes() != (replay / 'manifest.json').read_bytes():
        raise RuntimeError('nondeterministic manifest')
    evidence = []
    for case in cases:
        width, count, module = case['width'], case['count'], case['module']
        rtl, other = (root / case['rtl']).resolve(), (replay / case['rtl']).resolve()
        if root.resolve() not in rtl.parents or replay.resolve() not in other.parents:
            raise RuntimeError('RTL path escapes artifact root')
        if rtl.read_bytes() != other.read_bytes():
            raise RuntimeError(f'nondeterministic RTL: {module}')
        work = root / 'checks' / module
        work.mkdir(parents=True, exist_ok=True)
        bench, executable = work / 'tb.v', work / 'sim.vvp'
        bench.write_text(testbench(width, count, module))
        run(['iverilog', '-g2001', '-s', 'tb', '-o', str(executable), str(rtl), str(bench)], work / 'iverilog.log')
        output = run(['vvp', str(executable)], work / 'simulation.log')
        if 'MORPHHDL-59B-NATIVE-ORACLE-PASS' not in output or 'MORPHHDL-59B-MISMATCH' in output:
            raise RuntimeError(f'simulation did not pass: {module}')
        run(['verilator', '--lint-only', '--language', '1364-2001', '--top-module', module, str(rtl)], work / 'verilator.log')
        script = work / 'synthesis.ys'
        quoted = str(rtl).replace('\\', '\\\\').replace('"', '\\"')
        script.write_text(f'read_verilog "{quoted}"\nhierarchy -check -top {module}\nsynth -top {module}\ncheck -assert\nstat\n')
        run(['yosys', '-Q', '-T', '-s', str(script)], work / 'yosys.log')
        evidence.append(dict(width=width, count=count, samples=len(samples(width, count)),
                             sha256=hashlib.sha256(rtl.read_bytes()).hexdigest()))
    case = next(c for c in cases if (c['width'], c['count']) == (5, 5))
    work = root / 'checks' / 'mutation'
    work.mkdir(parents=True, exist_ok=True)
    bench, executable = work / 'tb.v', work / 'sim.vvp'
    bench.write_text(testbench(5, 5, case['module'], mutate=True))
    run(['iverilog', '-g2001', '-s', 'tb', '-o', str(executable), str(root / case['rtl']), str(bench)], work / 'iverilog.log')
    output = run(['vvp', str(executable)], work / 'simulation.log')
    if 'MORPHHDL-59B-MISMATCH sumResult' not in output or 'MORPHHDL-59B-NATIVE-ORACLE-PASS' in output:
        raise RuntimeError('simulation mutation did not produce the required output mismatch')
    record = dict(status='native-oracle-only', typed_candidate_formal='not-run',
                  configurations=evidence, simulation_mutation='detected')
    (root / 'evidence.json').write_text(json.dumps(record, indent=2) + '\n')
    print(f'PASS: {len(evidence)} native shapes; typed-candidate formal remains pending')


def self_test():
    assert expected((31, 1, 16), 5) == dict(zip(OUTPUTS, (16, 31, 14, 1, 31, 16, 1, 48)))
    assert expected((1,), 1)['signedMinResult'] == 1
    assert len(samples(1, 8)) == 256
    for width, count in itertools.product(WIDTHS, COUNTS):
        vectors = samples(width, count)
        assert vectors and vectors == samples(width, count)
        for words in vectors:
            assert len(words) == count
            assert expected(words, width)['growingResult'] < (1 << (width + (count - 1).bit_length()))
        assert testbench(width, count, 'Oracle').count('MORPHHDL-59B-MISMATCH pipelineResult') == len(vectors)
    assert testbench(5, 5, 'Oracle', True) != testbench(5, 5, 'Oracle')
    print('PASS: oracle model, matrix, deterministic stimuli, widths and testbench self-tests')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', nargs='?', type=Path)
    parser.add_argument('replay', nargs='?', type=Path)
    parser.add_argument('--self-test', action='store_true')
    args = parser.parse_args()
    if args.self_test:
        self_test()
    elif args.root is not None and args.replay is not None:
        qualify(args.root, args.replay)
    else:
        parser.error('provide both artifact directories or --self-test')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, RuntimeError, KeyError) as error:
        print(f'FAIL: {error}', file=sys.stderr)
        sys.exit(1)
