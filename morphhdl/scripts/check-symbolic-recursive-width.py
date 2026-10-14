#!/usr/bin/env python3
"""Independent adjacent-pair oracle for COUNT/DATA_WIDTH recursive publication."""
import argparse
import hashlib
import json
from pathlib import Path
import random
import shutil
import subprocess

COUNTS = (1, 2, 3, 5, 7, 8, 15, 16)
WIDTHS = (1, 8, 32, 36, 64)
COMMANDS = []


def run(command, log):
    with log.open('w') as stream:
        result = subprocess.run(list(map(str, command)), stdout=stream,
                                stderr=subprocess.STDOUT, timeout=180)
    COMMANDS.append(dict(command=list(map(str, command)), log=str(log), returncode=result.returncode))
    if result.returncode:
        raise RuntimeError(f'{log}:\n{log.read_text()[-6000:]}')


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


class Model:
    def __init__(self, count, width, mask, difference):
        self.count, self.width, self.mask, self.difference = count, width, mask, difference
        self.state = {}
        n, level = count, 0
        while n > 1:
            n = (n+1)//2
            if mask & (1 << level):
                self.state[level] = [0]*n
            level += 1

    def value(self, words, update=False, reset=False, enable=True):
        values = words[:self.count]
        writes = {}
        level = 0
        while len(values) > 1:
            values = [(values[i] - values[i+1] if self.difference else values[i] + values[i+1]) &
                      ((1 << self.width)-1) if i+1 < len(values) else values[i]
                      for i in range(0, len(values), 2)]
            if level in self.state:
                old = self.state[level]
                writes[level] = [0]*len(values) if reset else values[:] if enable else old[:]
                values = old
            level += 1
        if update:
            self.state.update(writes)
            return self.value(words)
        return values[0]


def reference(lines, count, width, difference, prefix, source):
    values = []
    for i in range(count):
        name = f'{prefix}_in{i}'
        lines.append(f'wire [{width-1}:0] {name} = {source}[{i*width} +: {width}];')
        values.append(name)
    level = 0
    while len(values) > 1:
        following = []
        for i in range(0, len(values), 2):
            name = f'{prefix}_l{level}_{i//2}'
            expr = values[i] if i+1 == len(values) else f'{values[i]} {"-" if difference else "+"} {values[i+1]}'
            lines.append(f'wire [{width-1}:0] {name} = {expr};')
            following.append(name)
        values = following
        level += 1
    return values[0]


def instance(name, n, w, mask, masked, result, terms, ident, clock="clk", reset="reset", enable="enable"):
    params = f'.COUNT({n}),.INITIAL_COUNT({n}),.DATA_WIDTH({w})'
    if masked:
        params += f',.PIPELINE_MASK({mask})'
    return (f'{name} #({params}) {ident}(.clk({clock}),.reset({reset}),.enable({enable}),'
            f'.terms({terms}),.result({result}));')


def simulate(rtl, out, name, masked, difference):
    lines = ['`timescale 1ns/1ps', 'module tb;', 'reg clk=0; always #5 clk=~clk;',
             'reg reset=0,enable=1;']
    for w in WIDTHS:
        lines.append(f'reg [{16*w-1}:0] data{w}=0;')
    models = {}
    refs = []
    for n in COUNTS:
        for w in WIDTHS:
            for mask in ((0, 1, 5, 15) if masked else (0,)):
                key = f'n{n}w{w}m{mask}'
                models[key] = Model(n, w, mask, difference)
                lines.append(f'wire [{w-1}:0] {key};')
                lines.append(instance(name,n,w,mask,masked,key,f'data{w}[{n*w-1}:0]',f'd_{key}'))
                if mask == 0:
                    expected = reference(lines,n,w,difference,f'ref_{key}',f'data{w}')
                    refs.append(f'if({key} !== {expected}) $fatal(1,"four-state {key}");')
    lines.append('initial begin')
    rng = random.Random(48050)
    assertions = 0
    for cycle in range(80):
        reset = cycle in (0,1,31,54)
        enable = cycle % 9 not in (3,4,5)
        lines.append(f'@(negedge clk); reset={int(not reset)}; enable={int(enable)};')
        words = {}
        for w in WIDTHS:
            values = [rng.getrandbits(w) for _ in range(16)]
            if cycle == 2:
                values = [0]*16
            elif cycle == 3:
                values = [(1 << w)-1]*16
            elif cycle == 4:
                values = [1 << (w-1)]*16
            elif 5 <= cycle < 21:
                values = [int(i == cycle-5) for i in range(16)]
            words[w] = values
            packed = sum(v << (i*w) for i,v in enumerate(values))
            lines.append(f'data{w}={16*w}\'h{packed:x};')
        if reset:
            for model in models.values():
                model.state = {key:[0]*len(values) for key,values in model.state.items()}
            lines.append('#1;')
            for key,model in models.items():
                expected = model.value(words[model.width])
                lines.append(f'if({key} !== {model.width}\'d{expected}) $fatal(1,"async {key} cycle {cycle}");')
                assertions += 1
        lines.append('@(posedge clk); #1;')
        for key,model in models.items():
            expected = model.value(words[model.width],True,reset,enable)
            lines.append(f'if({key} !== {model.width}\'d{expected}) $fatal(1,"{key} cycle {cycle}");')
            assertions += 1
    for value in ("'bx", "'bz"):
        lines += ['@(negedge clk);'] + [f'data{w}={value};' for w in WIDTHS] + ['#1;'] + refs
        assertions += len(refs)
    lines += ['$display("PASS symbolic recursive widths"); $finish; end', 'endmodule']
    tb = out/'tb.v'
    tb.write_text('\n'.join(lines)+'\n')
    run(['iverilog','-g2001','-s','tb','-o',out/'sim',rtl,tb],out/'compile.log')
    run(['vvp',out/'sim'],out/'simulation.log')
    if 'PASS symbolic recursive widths' not in (out/'simulation.log').read_text():
        raise RuntimeError('simulation did not finish')
    return assertions


def prove(rtl, out, name, masked, difference):
    profiles = []
    for n in (1,3,8,16):
        for w in (1,8,36,64):
            lines = [f'module oracle(input [{n*w-1}:0] terms, output ok);',f'wire [{w-1}:0] actual;']
            lines.append(instance(name,n,w,0,masked,'actual','terms','dut',"1'b0","1'b1","1'b1"))
            expected = reference(lines,n,w,difference,'ref','terms')
            lines += [f'assign ok = actual == {expected};','endmodule']
            oracle = out/f'oracle-{n}-{w}.v'
            oracle.write_text('\n'.join(lines)+'\n')
            run(['yosys','-Q','-T','-p',f'read_verilog -formal -defer {rtl} {oracle}; hierarchy -check -top oracle; proc; flatten; opt; sat -verify -prove ok 1 -show terms'],out/f'formal-{n}-{w}.log')
            profiles.append([n,w])
    return profiles


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--rtl',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    args.output.mkdir(parents=True,exist_ok=True)
    rows=[]
    for default in (1,8):
        for masked in (False,True):
            for difference in (False,True):
                relative=Path(f'default-{default}/masked-{str(masked).lower()}/difference-{str(difference).lower()}')
                rtl=(args.rtl/relative/'tree.v').resolve()
                out=(args.output/relative).resolve(); out.mkdir(parents=True,exist_ok=True)
                name='WidthDifferenceTree' if difference else 'WidthSumTree'
                assertions=simulate(rtl,out,name,masked,difference)
                run(['verilator','--lint-only','--language','1364-2001','-DSYNTHESIS','-Wno-fatal','--top-module',name,rtl],out/'lint.log')
                for n,w in ((1,1),(3,8),(8,36),(16,64)):
                    for mask in ((0,15) if masked else (0,)):
                        params=f'-set COUNT {n} -set INITIAL_COUNT {n} -set DATA_WIDTH {w}'
                        if masked: params+=f' -set PIPELINE_MASK {mask}'
                        run(['yosys','-Q','-T','-p',f'read_verilog -defer {rtl}; chparam {params} {name}; hierarchy -check -top {name}; proc; select -assert-none t:$dlatch; synth; check -assert'],out/f'synth-{n}-{w}-{mask}.log')
                proofs=prove(rtl,out,name,masked,difference)
                rows.append(dict(default=default,masked=masked,difference=difference,assertions=assertions,
                                 formal_profiles=proofs,rtl_sha256=sha(rtl),status='passed'))
                (args.output/'results.json').write_text(json.dumps(dict(complete=False,profiles=rows,commands=COMMANDS),indent=2)+'\n')
                print(relative,assertions,'PASS',flush=True)
    (args.output/'results.json').write_text(json.dumps(dict(complete=True,profiles=rows,commands=COMMANDS,
        runner_sha256=sha(Path(__file__)),tools={name:shutil.which(name) for name in ('iverilog','verilator','yosys','vsim')}),indent=2)+'\n')


if __name__ == '__main__':
    main()
