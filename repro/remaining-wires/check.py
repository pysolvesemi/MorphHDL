#!/usr/bin/env python3
"""Check untouched emitted DUTs; generated files here are testbenches/tool scripts only."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys


def run(argv: list[str], log: Path, expect_failure: bool = False) -> None:
    completed = subprocess.run(argv, text=True, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, timeout=180)
    log.write_text('$ ' + ' '.join(argv) + '\n' + completed.stdout)
    if (completed.returncode == 0) == expect_failure:
        raise AssertionError(f"Unexpected exit {completed.returncode}: {' '.join(argv)}\n{completed.stdout}")


def timing_bench(reset_kind: str) -> str:
    has_reset = reset_kind != 'boot'
    reset_port = ', .reset(reset)' if has_reset else ''
    reset_model = 'if (reset) clear_model; else' if has_reset else ''
    start = ('reset=1; #2; clear_model; check; step; reset=0;' if reset_kind == 'async' else
             'reset=1; step; reset=0;' if reset_kind == 'sync' else '#2; check;')
    async_pulse = ('if (i % 137 == 0) begin reset=1; #1; clear_model; check; reset=0; end'
                   if reset_kind == 'async' else '')
    sync_drive = 'reset=(i % 197 == 0);' if reset_kind == 'sync' else ''
    return r'''`timescale 1ns/1ps
module tb;
  parameter integer PPC4=0;
  reg clk=0, reset=0;
  reg load=0, ready=0, legal=0, rasterRun=0, cfgValid=0, running=0, accept=0;
  reg [15:0] hA=0,hF=0,hS=0,hB=0,vA=0,vF=0,vS=0,vB=0;
  wire [12:0] hTotal,x;
  wire [11:0] vTotal;
  wire controlError,invalidEvent,runningOut;
  wire [(PPC4*3+1)-1:0] lanes;
  reg [12:0] rh=0,rx=0;
  reg [11:0] rv=0;
  reg rerr=0,rinv=0,rrun=0;
  integer i,cycles=0,ht,vt;
  reg [31:0] random_state=32'hbc519347;
  RemainingWireRepro #(.PPC4(PPC4)) dut(
    .clk(clk)@@RESET_PORT@@,
    .io_load(load),.io_ready(ready),.io_legal(legal),.io_rasterRun(rasterRun),
    .io_cfgValid(cfgValid),.io_running(running),.io_accept(accept),
    .io_hActive(hA),.io_hFront(hF),.io_hSync(hS),.io_hBack(hB),
    .io_vActive(vA),.io_vFront(vF),.io_vSync(vS),.io_vBack(vB),
    .io_hTotal(hTotal),.io_vTotal(vTotal),.io_x(x),.io_controlError(controlError),
    .io_invalidEvent(invalidEvent),.io_runningOut(runningOut),.io_lanes(lanes));
  function [31:0] next_random;
    input [31:0] seed;
    reg [31:0] r;
    begin r=seed ^ (seed << 13); r=r ^ (r >> 17); next_random=r ^ (r << 5); end
  endfunction
  task clear_model;
    begin rh=0;rv=0;rx=0;rerr=0;rinv=0;rrun=0; end
  endtask
  task check;
    begin
      if ({hTotal,vTotal,x,controlError,invalidEvent,runningOut} !== {rh,rv,rx,rerr,rinv,rrun}) begin
        $display("MISMATCH cycle=%0d PPC4=%0d actual=%h expected=%h",cycles,PPC4,
          {hTotal,vTotal,x,controlError,invalidEvent,runningOut},{rh,rv,rx,rerr,rinv,rrun});
        $fatal(1,"sequential scoreboard");
      end
      if (lanes !== {(PPC4*3+1){1'b0}}) $fatal(1,"parameterized lanes changed");
    end
  endtask
  task step;
    begin
      clk=0; #2;
      @@RESET_MODEL@@ begin
        rerr=0;rinv=0;
        if (load) begin
`ifdef MUTATE_PRIORITY
          if (!legal) rinv=1;
          else if (!ready) rerr=1;
`else
          if (!ready) rerr=1;
          else if (!legal) rinv=1;
`endif
          else begin
            ht={16'd0,hA}+{16'd0,hF}+{16'd0,hS}+{16'd0,hB};
            vt={16'd0,vA}+{16'd0,vF}+{16'd0,vS}+{16'd0,vB};
            rh=ht[12:0];rv=vt[11:0];
          end
        end
        if (!rasterRun || !cfgValid) begin rrun=0;rx=0; end
        else begin
          rrun=1;
          if (!running || accept) rx=0;
`ifdef MUTATE_COUNTER
          else rx=rx+2;
`else
          else rx=rx+1;
`endif
        end
      end
      clk=1; #2; cycles=cycles+1; check; clk=0; #2;
    end
  endtask
  initial begin
    @@START@@
    // Complete truth table, with maximum totals and priority collisions.
    hA=16'hffff;hF=16'hffff;hS=16'hffff;hB=16'hffff;
    vA=16'hffff;vF=16'hffff;vS=16'hffff;vB=16'hffff;
    for(i=0;i<128;i=i+1) begin
      {load,ready,legal,rasterRun,cfgValid,running,accept}=i[6:0]; step;
    end
    // Counter wrap and consecutive updates; no reset for more than 8192 edges.
    load=1;ready=1;legal=1;rasterRun=1;cfgValid=1;running=1;accept=0;
    for(i=0;i<9000;i=i+1) begin hA=i;vA=i;step;end
    for(i=0;i<4096;i=i+1) begin
      random_state=next_random(random_state);
      {load,ready,legal,rasterRun,cfgValid,running,accept}=random_state[6:0];
      random_state=next_random(random_state);hA=random_state[15:0];hF=random_state[31:16];
      random_state=next_random(random_state);hS=random_state[15:0];hB=random_state[31:16];
      random_state=next_random(random_state);vA=random_state[15:0];vF=random_state[31:16];
      random_state=next_random(random_state);vS=random_state[15:0];vB=random_state[31:16];
      @@ASYNC_PULSE@@
      @@SYNC_DRIVE@@
      step;
    end
    reset=0;
    // Four-state condition use: an unknown if condition is not true.
    load=1;ready=1'bx;legal=1;rasterRun=1;cfgValid=1;running=1;accept=0;step;
    ready=1;legal=1'bx;step;
    legal=1;rasterRun=1'bx;step;
    rasterRun=1;cfgValid=1'bx;accept=1'bx;step;
    cfgValid=1;hA=16'hxxxx;vB=16'hxxxx;step;
    $display("PASS timing reset=@@KIND@@ PPC4=%0d cycles=%0d",PPC4,cycles);
    $finish;
  end
endmodule
'''.replace('@@RESET_PORT@@', reset_port).replace('@@RESET_MODEL@@', reset_model).replace(
        '@@START@@', start).replace('@@ASYNC_PULSE@@', async_pulse).replace(
        '@@SYNC_DRIVE@@', sync_drive).replace('@@KIND@@', reset_kind)


def stress_bench() -> str:
    inputs = [('clkA', 1), ('clkB', 1), ('rstA', 1), ('rstB', 1), ('enableA', 1), ('enableB', 1),
              ('block', 1), ('forceRun', 1), ('priority', 1), ('clear', 1), ('allowB', 1),
              ('value', 18), ('extra', 18), ('signedValue', 18)]
    outputs = [('countA', 13), ('priorA', 13), ('totalA', 13), ('sumTruncA', 13),
               ('countB', 12), ('totalB', 12), ('sameEdgeA', 1), ('signedA', 8), ('signedWideA', 20)]
    lines = ['`timescale 1ns/1ps', 'module stress_tb;']
    for name, width in inputs:
        lines.append(f'reg [{width-1}:0] {name}=0;')
    for name, width in outputs:
        lines.append(f'wire [{width-1}:0] ref_{name}, opt_{name};')
    for module, prefix in [('StressReference', 'ref'), ('StressOptimized', 'opt')]:
        ports = [f'.io_{name}({name})' for name, _ in inputs]
        ports += [f'.io_{name}({prefix}_{name})' for name, _ in outputs]
        lines.append(f'{module} {prefix}_dut({",".join(ports)});')
    ref = '{' + ','.join('ref_' + n for n, _ in outputs) + '}'
    opt = '{' + ','.join('opt_' + n for n, _ in outputs) + '}'
    lines += ['integer i, samples=0;', "reg [31:0] rng=32'hcd497ec1;", 'task check; begin',
              'samples=samples+1;', f'if ({ref} !== {opt}) $fatal(1,"stress mismatch sample=%0d", samples);',
              'end endtask', 'initial begin',
              'clkA=0;clkB=1;rstA=0;rstB=1;#2;rstA=1;rstB=0;#2;check;',
              'clkA=1;clkB=0;#2;check;',
              f'if (^{ref} === 1\'bx) $fatal(1,"reference not initialized by both resets");',
              'clkA=0;clkB=1;rstA=0;rstB=1;']
    lines += ['for(i=0;i<16384;i=i+1) begin',
              'rng=rng^(rng<<13);rng=rng^(rng>>17);rng=rng^(rng<<5);',
              '{enableA,enableB,block,forceRun,priority,clear,allowB}=rng[6:0];',
              'value=rng[17:0];extra={rng[7:0],rng[31:22]};signedValue=rng[31:14];',
              'rstA=(i%43==0);rstB=(i%61!=0);#2;check;',
              'clkA=1;#2;check;', 'clkB=0;#2;check;', 'clkA=0;clkB=1;#2;check;',
              'end', '$display("PASS stress: 2 clock domains, 16384 cycles, samples=%0d",samples);',
              '$finish;', 'end', 'endmodule']
    return '\n'.join(lines) + '\n'


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument('directory', type=Path, help='Untouched GenerateRemainingWireMatrix output')
    args = parser.parse_args()
    root = args.directory.resolve()
    logs = root / 'checks'
    logs.mkdir(exist_ok=True)
    for tool in ('iverilog', 'vvp', 'yosys'):
        if not shutil.which(tool):
            raise RuntimeError(f'Required verification tool is missing: {tool}')
    duties = sorted(root.glob('*/*.v'))
    before = {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in duties}
    receipts: list[str] = []
    for reset in ('async', 'sync', 'boot'):
        versions = {mode: (root / f'{reset}-{mode}' / 'RemainingWireRepro.v')
                    for mode in ('default', 'repeat', 'enabled', 'disabled')}
        default = versions['default'].read_text()
        assert versions['default'].read_bytes() == versions['repeat'].read_bytes(), 'Non-deterministic generation'
        assert versions['default'].read_bytes() == versions['enabled'].read_bytes(), 'Default is not standard enabled flow'
        assert not re.search(r'\bwhen_\w+|\b_zz_timing_[hv]Total\w*', default), default
        assert 'timing_hTotal <= timing_proposedHTotal[12:0];' in default, default
        assert 'timing_vTotal <= timing_proposedVTotal[11:0];' in default, default
        assert 'parameter integer PPC4 = 0' in default
        assert len(re.findall(r'^\s*reg\s', default, flags=re.M)) == 6, default
        disabled = versions['disabled'].read_text()
        assert len(re.findall(r'^\s*assign when_\w+\s*=', disabled, flags=re.M)) == 4, disabled
        # Disabling the production pipeline restores Spinal's earlier native
        # cleanup too: that path already slices named totals directly. Its
        # historical distinction is retained conditions + arithmetic carriers,
        # not the enabled-pipeline's former two direct-total aliases.
        for axis, high in (('h', 12), ('v', 11)):
            assert f'timing_{axis}Total <= timing_proposed{axis.upper()}Total[{high}:0];' in disabled, disabled
            assert re.search(rf'assign _zz_timing_proposed{axis.upper()}Total\w*\s*=', disabled), disabled
        bench = logs / f'tb-{reset}.v'
        bench.write_text(timing_bench(reset))
        for mode, dut in versions.items():
            for ppc in (0, 1):
                label = f'timing-{reset}-{mode}-ppc{ppc}'
                exe = logs / (label + '.vvp')
                run(['iverilog', '-g2001', '-s', 'tb', f'-Ptb.PPC4={ppc}', '-o', str(exe), str(bench), str(dut)], logs / (label + '-compile.log'))
                run(['vvp', str(exe)], logs / (label + '-simulation.log'))
                receipts.append(label)
        for ppc in (0, 1):
            script = logs / f'equiv-{reset}-ppc{ppc}.ys'
            cmds = []
            for mode, name in [('disabled', 'gold'), ('default', 'gate')]:
                cmds += [f'read_verilog {versions[mode]}',
                         f'hierarchy -top RemainingWireRepro -chparam PPC4 {ppc}',
                         'proc', 'opt', 'check -assert', 'async2sync', 'opt',
                         f'rename RemainingWireRepro {name}', f'design -stash {name}']
            cmds += ['design -copy-from gold -as gold gold', 'design -copy-from gate -as gate gate',
                     'equiv_make gold gate equiv', 'hierarchy -top equiv', 'opt_clean',
                     'equiv_simple', 'equiv_induct -undef -seq 6', 'equiv_status -assert']
            script.write_text('\n'.join(cmds) + '\n')
            run(['yosys', '-Q', '-s', str(script)], script.with_suffix('.log'))
            receipts.append(f'equivalence-{reset}-ppc{ppc}')
            for mode in ('default', 'disabled'):
                synthesis = logs / f'synth-{reset}-{mode}-ppc{ppc}.ys'
                synthesis.write_text(f'read_verilog {versions[mode]}\nhierarchy -top RemainingWireRepro -chparam PPC4 {ppc}\nsynth -top RemainingWireRepro\ncheck -assert\n')
                run(['yosys', '-Q', '-s', str(synthesis)], synthesis.with_suffix('.log'))
                receipts.append(f'synthesis-{reset}-{mode}-ppc{ppc}')
    assert (root / 'async-default/RemainingWireRepro.v').read_bytes() == (root / 'traced/RemainingWireRepro.v').read_bytes(), 'Trace changed the emitted DUT'
    assert (root / 'stress-default/StressOptimized.v').read_bytes() == (root / 'stress-repeat/StressOptimized.v').read_bytes()
    stress_dut = (root / 'stress-default/StressOptimized.v').read_text()
    assert 'sourceWord' in stress_dut
    assert re.search(r'assign _zz_\w+\s*=.*io_value.*\+.*io_extra', stress_dut), 'Arithmetic select base must survive'
    bench = logs / 'stress_tb.v'
    bench.write_text(stress_bench())
    exe = logs / 'stress.vvp'
    run(['iverilog', '-g2001', '-s', 'stress_tb', '-o', str(exe), str(bench),
         str(root / 'stress-default/StressOptimized.v'), str(root / 'stress-disabled/StressReference.v')], logs / 'stress-compile.log')
    run(['vvp', str(exe)], logs / 'stress-simulation.log')
    receipts.append('two-clock-multiple-use-signed-priority-enable-miter')
    # Mutate only the independent EXPECTED model, never the emitted Verilog.
    for mutation in ('MUTATE_PRIORITY', 'MUTATE_COUNTER'):
        exe = logs / (mutation + '.vvp')
        run(['iverilog', '-g2001', '-s', 'tb', '-D' + mutation, '-o', str(exe),
             str(logs / 'tb-async.v'), str(root / 'async-default/RemainingWireRepro.v')], logs / (mutation + '-compile.log'))
        run(['vvp', str(exe)], logs / (mutation + '-detected.log'), expect_failure=True)
        receipts.append('detected-' + mutation)
    after = {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in duties}
    assert before == after, 'Verification modified emitted Verilog'
    (logs / 'receipt.json').write_text(json.dumps({'passed': receipts, 'unmodified_dut_sha256': after}, indent=2) + '\n')
    print(f'PASS: {len(receipts)} simulation, equivalence, synthesis and mutation checks; deterministic output and untouched DUT digests')


if __name__ == '__main__':
    main()
