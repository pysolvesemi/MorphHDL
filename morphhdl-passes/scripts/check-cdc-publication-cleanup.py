#!/usr/bin/env python3
"""Port-driven oracle and on/off proof of one parameterized artifact per mode.

Does not edit generated HDL. Yosys renames modules in its own native design IR.
Shape/identity acceptance belongs to the native regression suite, not this test.
"""
import argparse
from pathlib import Path
import subprocess
import json

DEPTHS = (2, 3, 4, 8, 16)

def run(argv, log):
    p = subprocess.run(argv, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=180)
    log.write_text(p.stdout)
    if p.returncode:
        raise RuntimeError(f"{argv[0]} failed ({p.returncode}): {log}\n{p.stdout[-5000:]}")
    return p.stdout

def rejected(argv, log, marker):
    p = subprocess.run(argv, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=180)
    log.write_text(p.stdout)
    assert p.returncode != 0 and marker in p.stdout, f'mutation was not rejected by the intended check: {log}'

FILL = r'''module OracleBench;
  parameter integer P=3;
  localparam integer W=P+2;
  localparam [17:0] CAP=(18'd1 << P)+18'd1;
  reg bypass;
  reg [W-1:0] upper,lower;
  wire [W-1:0] writeFill,readFill;
  wire [W-1:0] oracleWrite = bypass ? {W{1'b0}} : ((upper > CAP) ? CAP : upper);
  wire [W-1:0] oracleRead = (bypass || (lower > CAP)) ? {W{1'b0}} : lower;
  RecursiveFillCleanupRepro #(.FIFO_LOG_DEPTH(P)) dut(
    .bypass(bypass),.upper(upper),.lower(lower),.writeFill(writeFill),.readFill(readFill));
  integer i,j,k,seed,checks;
  reg [W-1:0] values[0:5];
  task check;
    begin
      #1;
      if (writeFill !== oracleWrite || readFill !== oracleRead)
        $fatal(1,"occupancy P=%0d bypass=%b upper=%b lower=%b got=%b,%b expected=%b,%b",
          P,bypass,upper,lower,writeFill,readFill,oracleWrite,oracleRead);
      $display("TRACE %b %b %b %b %b",bypass,upper,lower,writeFill,readFill);
      checks=checks+1;
    end
  endtask
  task controls;
    begin bypass=0;check; bypass=1;check; bypass=1'bx;check; bypass=1'bz;check;end
  endtask
  initial begin
    seed=13579; checks=0;
    values[0]=0;values[1]=1;values[2]=CAP-1;values[3]=CAP;values[4]=CAP+1;values[5]={W{1'b1}};
    for(i=0;i<6;i=i+1) for(j=0;j<6;j=j+1) begin upper=values[i];lower=values[j];controls;end
    for(i=0;i<1000;i=i+1) begin upper=$random(seed);lower=$random(seed);controls;end
    for(i=0;i<W;i=i+1) begin
      upper=CAP;lower=CAP;upper[i]=1'bx;controls;
      upper=CAP;lower=CAP;lower[i]=1'bz;controls;
      upper=0;lower=0;upper[i]=1'bz;lower[i]=1'bx;controls;
    end
    upper={W{1'bx}};lower={W{1'bz}};controls;
    $display("PASS occupancy P=%0d checks=%0d",P,checks);$finish;
  end
endmodule
'''

NAMING = r'''module OracleBench;
  parameter integer P=3;
  localparam integer W=P+2;
  reg [W-1:0] a,b;
  wire [17:0] wide,namedWide,zzNamedWide;
  wire [W-1:0] roundTrip;
  // Modular subtraction is completed at W bits BEFORE widening.
  wire [W-1:0] modularDifference=a-b;
  wire [17:0] oracleWide={{(18-W){1'b0}},modularDifference};
  ResizeTemporaryNamingRepro #(.FIFO_LOG_DEPTH(P)) dut(
    .a(a),.b(b),.wide(wide),.namedWide(namedWide),.zzNamedWide(zzNamedWide),.roundTrip(roundTrip));
  integer i,j,seed,checks;
  reg [W-1:0] values[0:4];
  task check;
    begin
      #1;
      if(wide !== oracleWide || namedWide !== oracleWide || zzNamedWide !== oracleWide || roundTrip !== modularDifference)
        $fatal(1,"resize P=%0d a=%b b=%b got=%b,%b,%b,%b expected=%b,%b",
          P,a,b,wide,namedWide,zzNamedWide,roundTrip,oracleWide,modularDifference);
      $display("TRACE %b %b %b %b %b %b",a,b,wide,namedWide,zzNamedWide,roundTrip);
      checks=checks+1;
    end
  endtask
  initial begin
    seed=24680;checks=0;
    values[0]=0;values[1]=1;values[2]={W{1'b1}};values[3]={1'b1,{(W-1){1'b0}}};values[4]=values[3]-1;
    for(i=0;i<5;i=i+1) for(j=0;j<5;j=j+1) begin a=values[i];b=values[j];check;end
    for(i=0;i<1000;i=i+1) begin a=$random(seed);b=$random(seed);check;end
    for(i=0;i<W;i=i+1) begin
      a=0;b=1;a[i]=1'bx;check;
      a=0;b=1;b[i]=1'bz;check;
      a={W{1'b1}};b=0;a[i]=1'bz;b[i]=1'bx;check;
    end
    $display("PASS naming P=%0d checks=%0d",P,checks);$finish;
  end
endmodule
'''

NAMING_PROTECTED = NAMING.replace('wire [17:0] wide,namedWide,zzNamedWide;',
    'wire [17:0] wide,namedWide,zzNamedWide,userPrefixWide,collisionWide;').replace(
    '.roundTrip(roundTrip));', '.roundTrip(roundTrip),.userPrefixWide(userPrefixWide),.collisionWide(collisionWide));').replace(
    'if(wide !== oracleWide', 'if(userPrefixWide !== oracleWide || collisionWide !== oracleWide || wide !== oracleWide')

FILL_ORACLE = r'''module RecursiveFillCleanupRepro #(parameter integer FIFO_LOG_DEPTH=3)(
  input wire bypass,
  input wire [FIFO_LOG_DEPTH+1:0] upper,lower,
  output wire [FIFO_LOG_DEPTH+1:0] writeFill,readFill);
  wire [17:0] capacity=(18'd1 << FIFO_LOG_DEPTH)+18'd1;
  assign writeFill=bypass ? 0 : ((upper > capacity) ? capacity : upper);
  assign readFill=(bypass || (lower > capacity)) ? 0 : lower;
endmodule
'''
NAMING_ORACLE = r'''module ResizeTemporaryNamingRepro #(parameter integer FIFO_LOG_DEPTH=3)(
  input wire [FIFO_LOG_DEPTH+1:0] a,b,
  output wire [17:0] wide,namedWide,zzNamedWide,userPrefixWide,collisionWide,
  output wire [FIFO_LOG_DEPTH+1:0] roundTrip);
  wire [FIFO_LOG_DEPTH+1:0] difference=a-b;
  assign wide={{(16-FIFO_LOG_DEPTH){1'b0}},difference};
  assign namedWide=wide;
  assign zzNamedWide=wide;
  assign userPrefixWide=wide;
  assign collisionWide=wide;
  assign roundTrip=difference;
endmodule
'''

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('artifact_directory', type=Path)
    args=p.parse_args(); root=args.artifact_directory.resolve()
    proof=root/'oracle-proofs';proof.mkdir(exist_ok=True)
    matrix=[]
    for prefix,top,tb,oracle in [('fill','RecursiveFillCleanupRepro',FILL,FILL_ORACLE),('naming','ResizeTemporaryNamingRepro',NAMING_PROTECTED,NAMING_ORACLE),('hierarchy','ResizeNamingHierarchy',NAMING_PROTECTED.replace('ResizeTemporaryNamingRepro','ResizeNamingHierarchy').replace('.FIFO_LOG_DEPTH(P)', '.COUNT_BITS(P+2)'),NAMING_ORACLE.replace('ResizeTemporaryNamingRepro','ResizeNamingHierarchy').replace('FIFO_LOG_DEPTH=3','COUNT_BITS=5').replace('FIFO_LOG_DEPTH+1','COUNT_BITS-1').replace('16-FIFO_LOG_DEPTH','18-COUNT_BITS'))]:
        oracle_path=proof/(prefix+'-oracle.v');oracle_path.write_text(oracle)
        bench=proof/(prefix+'-tb.v');bench.write_text(tb)
        for depth in DEPTHS:
            parameter = 'COUNT_BITS' if prefix == 'hierarchy' else 'FIFO_LOG_DEPTH'
            parameter_value = depth + 2 if prefix == 'hierarchy' else depth
            traces=[]
            for mode in ('on','off'):
                rtl=root/(prefix+'-'+mode)/(top+'.v')
                assert rtl.is_file(),rtl
                key=f'{prefix}-{mode}-p{depth}'
                binary=proof/(key+'.vvp')
                run(['iverilog','-Wall','-g2012','-s','OracleBench','-P',f'OracleBench.P={depth}','-o',str(binary),*[str(p) for p in sorted(rtl.parent.glob('*.v'))],str(bench)],proof/(key+'-compile.log'))
                traces.append(run(['vvp',str(binary)],proof/(key+'-oracle.log')))
            assert traces[0]==traces[1],f'on/off four-state port observations differ: {prefix} P={depth}'
            script=proof/f'{prefix}-p{depth}.ys'
            def load(mode,name):
                rtl=root/(prefix+'-'+mode)/(top+'.v')
                files=' '.join('\"'+str(p)+'\"' for p in sorted(rtl.parent.glob('*.v')))
                return f'read_verilog {files}\nchparam -set {parameter} {parameter_value} {top}\nhierarchy -top {top}\nproc\nflatten\nopt\ncheck -assert\nrename {top} {name}\ndesign -stash {name}\n'
            finish='design -copy-from gold -as gold gold\ndesign -copy-from gate -as gate gate\nequiv_make gold gate equiv\nhierarchy -top equiv\nequiv_simple\nequiv_status -assert\n'
            script.write_text(load('off','gold')+load('on','gate')+finish)
            run(['yosys','-Q','-s',str(script)],proof/f'{prefix}-p{depth}-equivalence.log')
            for mode in ('on','off'):
                oracle_script=proof/f'{prefix}-{mode}-p{depth}-oracle.ys'
                oracle_script.write_text(f'read_verilog "{oracle_path}"\nchparam -set {parameter} {parameter_value} {top}\nhierarchy -top {top}\nproc\nflatten\nopt\ncheck -assert\nrename {top} gold\ndesign -stash gold\n'+load(mode,'gate')+finish)
                run(['yosys','-Q','-s',str(oracle_script)],proof/f'{prefix}-{mode}-p{depth}-oracle-formal.log')
            matrix.append({'fixture':prefix,'depth':depth,'four_state':'passed','equivalence':'passed','oracle_formal':'passed'})
            print(f'PASS {prefix} depth={depth}: independent oracle, exact four-state on/off observations, Yosys on/off and oracle equivalence')
    # Deliberately wrong independent models validate that neither simulator nor
    # formal checker can pass by merely comparing two equally wrong artifacts.
    # Only these handwritten oracle copies change; generated HDL is untouched.
    mutations=[]
    for prefix,top,tb,oracle in [
        ('fill','RecursiveFillCleanupRepro',FILL,
         FILL_ORACLE.replace('assign writeFill=bypass ?', 'assign writeFill=!bypass ?')),
        ('naming','ResizeTemporaryNamingRepro',NAMING_PROTECTED,
         NAMING_ORACLE.replace("assign wide={{(16-FIFO_LOG_DEPTH){1'b0}},difference};", 'assign wide=a-b;'))]:
        bad=proof/(prefix+'-wrong-oracle.v');bad.write_text(oracle)
        bench=proof/(prefix+'-tb.v')
        binary=proof/(prefix+'-mutation.vvp')
        run(['iverilog','-g2012','-s','OracleBench','-P','OracleBench.P=3','-o',str(binary),str(bad),str(bench)],proof/(prefix+'-mutation-compile.log'))
        rejected(['vvp',str(binary)],proof/(prefix+'-mutation-simulation.log'),'FATAL:')
        rtl=root/(prefix+'-on')/(top+'.v')
        script=proof/(prefix+'-mutation.ys')
        script.write_text(f'read_verilog "{bad}"\nchparam -set FIFO_LOG_DEPTH 3 {top}\nhierarchy -top {top}\nproc\nopt\nrename {top} gold\ndesign -stash gold\n'
            +f'read_verilog "{rtl}"\nchparam -set FIFO_LOG_DEPTH 3 {top}\nhierarchy -top {top}\nproc\nopt\nrename {top} gate\ndesign -stash gate\n'+finish)
        rejected(['yosys','-Q','-s',str(script)],proof/(prefix+'-mutation-formal.log'),'unproven $equiv')
        mutations.append({'fixture':prefix,'four_state':'rejected','formal':'rejected'})
    (root/'publication-qualification.json').write_text(json.dumps({'matrix':matrix,'mutations':mutations},indent=2)+'\n')

if __name__=='__main__': main()
