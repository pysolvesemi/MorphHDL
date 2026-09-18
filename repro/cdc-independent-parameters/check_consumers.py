#!/usr/bin/env python3
"""Exercise the same native files under independent HDL overrides; no RTL rewriting."""
import argparse
import hashlib
import pathlib
import subprocess


def run(args, log):
    result = subprocess.run(args, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120)
    log.write_text(result.stdout)
    if result.returncode:
        raise RuntimeError(f'{args!r} returned {result.returncode}\n{result.stdout}')
    return result.stdout


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('out', type=pathlib.Path)
    parser.add_argument('repeat', type=pathlib.Path)
    parser.add_argument('results', type=pathlib.Path)
    args = parser.parse_args()
    args.results.mkdir(parents=True, exist_ok=True)
    files = sorted(p.relative_to(args.out) for p in args.out.rglob('*.v'))
    assert files and files == sorted(p.relative_to(args.repeat) for p in args.repeat.rglob('*.v'))
    for p in files:
        assert (args.out / p).read_bytes() == (args.repeat / p).read_bytes(), p
    child = args.results / 'external_storage.v'
    child.write_text('module ExternalRecordStorage #(parameter integer WIDTH=89)(input wire [WIDTH-1:0] din, output wire [WIDTH-1:0] dout); assign dout=din; endmodule\n')
    tuples = [(32,1),(1,1),(2048,4),(120,4),(32,4),(120,1),(29,4)]
    count = 0
    for mode in ('Ports', 'BlackBox', 'Value'):
        rtl = args.out / mode / ('CdcRecord' + mode + '.v')
        assert rtl.is_file(), rtl
        raw = rtl.read_text()
        assert 'parameter integer DATA_BITS' in raw and 'parameter integer LANES' in raw
        assert 'PROFILE' not in raw
        for data, lanes in tuples:
            width = data + lanes + 56
            tag = f'{mode}-{data}-{lanes}'
            match_port = ', .matchesWidth(matchesWidth)' if mode == 'Value' else ''
            child_check = 'if(dut.storage.WIDTH != W || $bits(dut.storage.din) != W || $bits(dut.storage.dout) != W) $fatal(1,"child geometry");' if mode == 'BlackBox' else ''
            value_check = f'if(matchesWidth !== {int(width == 89)}) $fatal(1,"typed value");' if mode == 'Value' else ''
            tb = args.results / (tag + '.sv')
            tb.write_text(f'''module tb;
localparam W={width}; reg [W-1:0] din; wire [W-1:0] dout; wire matchesWidth; integer i;
CdcRecord{mode} #(.DATA_BITS({data}), .LANES({lanes})) dut(.din(din), .dout(dout){match_port});
task check; begin #1; if(dout !== din) $fatal(1,"full bus mismatch"); {value_check} end endtask
initial begin
{child_check}
din=0; check; din={{W{{1'b1}}}}; check;
for(i=0;i<W;i=i+1) begin din=0; din[i]=1'b1; check; din=~din; check; end
$display("CDC_BUS_PASS {tag} W=%0d",W); $finish;
end
endmodule
''')
            exe = args.results / (tag + '.vvp')
            run(['iverilog','-g2012','-s','tb','-o',str(exe),str(rtl),str(child),str(tb)], args.results / (tag + '-compile.log'))
            result = run(['vvp',str(exe)], args.results / (tag + '-simulate.log'))
            assert 'CDC_BUS_PASS ' + tag in result
            print(result.strip())
            count += 1
            run(['yosys','-Q','-T','-p',f'read_verilog -D SYNTHESIS {rtl} {child}; chparam -set DATA_BITS {data} -set LANES {lanes} CdcRecord{mode}; hierarchy -check -top CdcRecord{mode}; proc; opt; check -assert'], args.results / (tag + '-yosys.log'))
        pre = args.results / (mode + '-synthesis.v')
        run(['iverilog','-g2012','-E','-DSYNTHESIS','-o',str(pre),str(rtl)],args.results / (mode + '-preprocess.log'))
        assert '$fatal' not in pre.read_text() and '$error' not in pre.read_text()
        print('NATIVE_RTL', mode, hashlib.sha256(rtl.read_bytes()).hexdigest())
    print(f'CDC_CONSUMERS_PASS simulations={count} synthesis={count} deterministic_files={len(files)}')

if __name__ == '__main__':
    main()
