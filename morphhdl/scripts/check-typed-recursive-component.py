#!/usr/bin/env python3
"""Validate native typed recursive interfaces against a flat adjacent-pair oracle.

The cycle oracle independently retains per-level state, odd tails, custom bridges,
reset priority and enable stalls. It does not inspect or reconstruct emitted RTL.
"""
import argparse
import hashlib
import json
from pathlib import Path
import random
import shutil
import subprocess

NAMES = dict(difference="TypedUnsignedTree", bits="TypedBitsTree", signed="TypedSignedTree",
             bundle="TypedBundleTree", bridge="TypedBridgedTree", custom_register="TypedBridgedTree",
             sync="TypedSyncTree")

COMMANDS = []

def run(args, log, cwd=None, env=None, timeout=180):
    with log.open('w') as stream:
        result = subprocess.run(list(map(str, args)), cwd=cwd, env=env, stdout=stream,
                                stderr=subprocess.STDOUT, timeout=timeout)
    COMMANDS.append(dict(command=list(map(str,args)), log=str(log), returncode=result.returncode))
    if result.returncode:
        raise RuntimeError(f'{args[0]} failed: {log}\n{log.read_text()[-5000:]}')


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def combine(kind, a, b):
    if kind == 'difference':
        return (a - b) & 255
    if kind == 'bits':
        return a | b
    if kind == 'signed':
        return a if (a ^ 128) >= (b ^ 128) else b
    if kind == 'bundle':
        return ((a + b) & 255) | (a & b & 256)
    return (a + b) & 255


class Model:
    """Cycle model of adjacent pairing with individual level register banks."""
    def __init__(self, kind, n, mask):
        self.kind, self.n, self.mask = kind, n, mask
        self.state = {}
        count = n
        level = 0
        while count > 1:
            count = (count + 1) // 2
            for slot in range((1 if kind == 'custom_register' and level == 1 else 0) + ((mask >> level) & 1)):
                self.state[level, slot] = [0] * count
            level += 1

    def evaluate(self, inputs, update=False, enable=True, reset=False, soft=False):
        values = inputs[:self.n]
        writes = {}
        level = 0
        while len(values) > 1:
            values = [combine(self.kind, values[i], values[i+1]) if i+1 < len(values)
                      else values[i] for i in range(0, len(values), 2)]
            if self.kind == 'bridge':
                values = [(v + level + 1) & 255 for v in values]
            slots = (1 if self.kind == 'custom_register' and level == 1 else 0) + ((self.mask >> level) & 1)
            for slot in range(slots):
                old = self.state[level, slot]
                writes[level, slot] = ([0] * len(values) if reset or (enable and soft)
                                      else values[:] if enable else old[:])
                values = old
            level += 1
        if update:
            self.state.update(writes)
            return self.evaluate(inputs)
        return values[0]


def testbench(kind, directory, capacity=8):
    width = 9 if kind == 'bundle' else 8
    lines = ['`timescale 1ns/1ps', 'module tb;', 'reg clk=0; always #5 clk=~clk;',
             'reg reset=0, softReset=0, enable=1;', f'reg [{capacity*width-1}:0] terms=0;']
    models = {}
    counts = range(1, capacity+1) if capacity <= 16 else sorted({capacity} | {n for n in (1,2,3,5,7,8,15,16,17,31,32,33,63,64) if n <= capacity})
    mask_limit = (1 << (capacity-1).bit_length()) - 1
    masks = range(mask_limit+1) if capacity <= 16 else sorted({mask_limit} | {m for m in (0,1,2,21,42,63) if m <= mask_limit})
    for n in counts:
        for mask in masks:
            ident = f'n{n}m{mask}'
            models[ident] = Model(kind, n, mask)
            lines.append(f'wire [{width-1}:0] {ident};')
            result = (f'.result_value({ident}[7:0]),.result_valid({ident}[8])'
                      if kind == 'bundle' else f'.result({ident})')
            lines.append(f'{NAMES[kind]} #(.COUNT({n}),.INITIAL_COUNT({n}),.PIPELINE_MASK({mask})) '
                         f'd_{ident}(.clk(clk),.reset(reset),.softReset(softReset),.enable(enable),'
                         f'.terms(terms[{n*width-1}:0]),{result});')
    lines += ['initial begin']
    rng = random.Random(937)
    assertions = 0
    for cycle in range(100):
        values = [rng.randrange(1 << width) for _ in range(capacity)]
        if cycle == 4:
            values = [(1 << width) - 1] * capacity  # overflow / signed-negative boundary
        if cycle == 5:
            values = [1 << (width-1)] * capacity
        reset = cycle in (0, 1, 36, 61)
        enable = cycle % 9 not in (3, 4, 5)
        soft = cycle in (18, 23, 44, 62)
        active = int(reset) if kind == 'sync' else int(not reset)
        packed = sum(v << (i * width) for i, v in enumerate(values))
        lines.append(f'@(negedge clk); reset={active}; enable={int(enable)}; softReset={int(soft)}; '
                     f'terms={capacity*width}\'h{packed:x};')
        # Async reset must take effect before the next clock, even while disabled.
        if reset and kind != 'sync':
            for model in models.values():
                model.state = {key: [0] * len(v) for key, v in model.state.items()}
            lines.append('#1;')
            for ident, model in models.items():
                lines.append(f'if ({ident} !== {width}\'d{model.evaluate(values)}) '
                             f'$fatal(1,"async {ident} cycle {cycle}");')
                assertions += 1
        lines.append('@(posedge clk); #1;')
        for ident, model in models.items():
            expected = model.evaluate(values, update=True, enable=enable, reset=reset, soft=soft)
            lines.append(f'if ({ident} !== {width}\'d{expected}) '
                         f'$fatal(1,"{ident} cycle {cycle}: got %d expected {expected}",{ident});')
            assertions += 1
    lines += ['$display("PASS balanced tree"); $finish; end', 'endmodule']
    path = directory / 'tb.v'
    path.write_text('\n'.join(lines) + '\n')
    return path, assertions



def prove_combinational(kind, rtl, directory, capacity):
    if kind == 'custom_register':
        return []  # This bridge inserts state even when PIPELINE_MASK is zero.
    counts = range(1, capacity+1) if capacity <= 8 else sorted({1,3,capacity-1,capacity})
    width = 9 if kind == 'bundle' else 8
    for n in counts:
        lines = [f'module oracle(input [{capacity*width-1}:0] terms, output ok);',
                 f'wire [{width-1}:0] actual;']
        result = ('.result_value(actual[7:0]),.result_valid(actual[8])' if kind == 'bundle'
                  else '.result(actual)')
        reset_inactive = 0 if kind == 'sync' else 1
        lines.append(f"{NAMES[kind]} #(.COUNT({n}),.INITIAL_COUNT({n}),.PIPELINE_MASK(0)) dut("
                     f".clk(1'b0),.reset(1'b{reset_inactive}),.softReset(1'b0),.enable(1'b1),"
                     f".terms(terms[{n*width-1}:0]),{result});")
        terms = []
        for i in range(n):
            name = f'input_{i}'
            lines.append(f'wire [{width-1}:0] {name} = terms[{i*width} +: {width}];')
            terms.append(name)
        level = 0
        while len(terms) > 1:
            following = []
            for i in range(0, len(terms), 2):
                a = terms[i]
                name = f'level_{level}_{i//2}'
                if i+1 == len(terms):
                    expression = a
                else:
                    b = terms[i+1]
                    if kind == 'signed':
                        expression = f'($signed({a}) > $signed({b})) ? {a} : {b}'
                    elif kind == 'bundle':
                        value = name + '_value'
                        lines.append(f'wire [7:0] {value} = {a}[7:0] + {b}[7:0];')
                        expression = '{' + f'({a}[8] & {b}[8]), {value}' + '}'
                    else:
                        op = '-' if kind == 'difference' else '|' if kind == 'bits' else '+'
                        expression = f'{a} {op} {b}'
                if kind == 'bridge':
                    expression = f'({expression}) + 8\'d{level+1}'
                lines.append(f'wire [{width-1}:0] {name} = {expression};')
                following.append(name)
            terms = following
            level += 1
        lines += [f'assign ok = actual == {terms[0]};', 'endmodule']
        oracle = directory / f'{kind}-oracle-{n}.v'
        oracle.write_text('\n'.join(lines) + '\n')
        run(['yosys', '-Q', '-T', '-p', f'read_verilog -formal -defer {rtl} {oracle}; hierarchy -check -top oracle; proc; flatten; opt; sat -verify -prove ok 1 -show terms'], directory / f'{kind}-formal-{n}.log')
    return list(counts)



def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--rtl',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    args.output.mkdir(parents=True,exist_ok=True)
    rows=[]
    for topology in ('ordinary','guarded'):
        for default in (1,5):
            for source,kind in [('uint','difference'),('bits','bits'),('signed','signed'),
                                ('bundle','bundle'),('bridge','bridge'),('register','custom_register'),('sync','sync')]:
                rtl=(args.rtl/topology/f'default-{default}'/source/'tree.v').resolve()
                out=(args.output/topology/f'default-{default}'/source).resolve();out.mkdir(parents=True,exist_ok=True)
                _,assertions=testbench(kind,out)
                run(['iverilog','-g2001','-s','tb','-o',out/'sim',rtl,out/'tb.v'],out/'compile.log')
                run(['vvp',out/'sim'],out/'simulation.log')
                if 'PASS balanced tree' not in (out/'simulation.log').read_text():
                    raise RuntimeError('simulation did not complete')
                run(['verilator','--lint-only','--language','1364-2001','-DSYNTHESIS','-Wno-fatal',
                     '--top-module',NAMES[kind],rtl],out/'lint.log')
                for mask in (0,7):
                    run(['yosys','-Q','-T','-p',f'read_verilog -defer {rtl}; chparam -set COUNT 8 -set INITIAL_COUNT 8 -set PIPELINE_MASK {mask} {NAMES[kind]}; hierarchy -check -top {NAMES[kind]}; proc; select -assert-none t:$dlatch; synth; check -assert'],out/f'synth-{mask}.log')
                proofs=prove_combinational(kind,rtl,out,8)
                rows.append(dict(topology=topology,default=default,kind=kind,assertions=assertions,formal_counts=proofs,
                                 rtl_sha256=sha(rtl),status='passed'))
                (args.output/'results.json').write_text(json.dumps(dict(complete=False,profiles=rows,commands=COMMANDS),indent=2)+'\n')
                print(topology,default,kind,assertions,'PASS',flush=True)
    (args.output/'results.json').write_text(json.dumps(dict(complete=True,profiles=rows,commands=COMMANDS,
        runner_sha256=sha(Path(__file__)),tools={n:shutil.which(n) for n in ['iverilog','verilator','yosys','vsim']}),indent=2)+'\n')

if __name__ == '__main__':
    main()
