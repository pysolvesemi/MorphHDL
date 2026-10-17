#!/usr/bin/env python3
"""Qualify one untouched recursively emitted artifact with parameter overrides."""
import argparse,hashlib,json,random,shutil,subprocess,time,os,signal
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--rtl',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();rtl=a.rtl.resolve();out=a.output.resolve();out.mkdir(parents=True,exist_ok=True);rows=[]
def receipt(complete=False):
 (out/'results.json').write_text(json.dumps(dict(complete=complete,profile_count=45,invalid_override_count=9,runner_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),rtl=str(rtl),sha256=hashlib.sha256(rtl.read_bytes()).hexdigest(),runs=rows,tools={t:shutil.which(t) for t in ['iverilog','vvp','verilator','yosys','yosys-smtbmc','z3','vsim','vlog']}),indent=2)+'\n')
def run(label,command,timeout=180,passing=True):
 start=time.monotonic()
 with (out/(label+'.log')).open('w') as log:
  process=subprocess.Popen(list(map(str,command)),cwd=out,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
  try:code=process.wait(timeout=timeout)
  except subprocess.TimeoutExpired:
   os.killpg(process.pid,signal.SIGKILL);process.wait();code=124
 rows.append(dict(name=label,command=list(map(str,command)),code=code,seconds=time.monotonic()-start,expected_success=passing))
 receipt()
 if passing and code:raise RuntimeError(f'{label} failed: {code}')
 return code
for n in [1,2,3,5,7,8,15,16,17]:
 for tw,sw in [(1,1),(3,2),(3,8),(8,8),(32,36)]:
  label=f'c{n}-t{tw}-s{sw}';bits=n*tw;rng=random.Random(431+n*101+tw*3+sw);mask=(1<<sw)-1
  vectors=[0,(1<<bits)-1]+[1<<i for i in range(bits)]+[rng.getrandbits(bits) for _ in range(50)]
  stimulus=[]
  for data in vectors:
   expected=sum((data>>(i*tw))&((1<<tw)-1) for i in range(n))&mask
   stimulus.append(f"terms={bits}'h{data:x}; #1; if(sum !== {sw}'h{expected:x}) $fatal(1,\"sum mismatch\");")
  # Compare four-state behavior against independently authored flat arithmetic.
  # A singleton is a conversion, not addition to an artificial zero term.
  unknown=[]
  for pos in [0,bits-1]:
   for digit in 'xz':unknown.append(f"terms=0; terms[{pos}]=1'b{digit}; #1; if(sum !== reference(terms)) $fatal(1,\"four-state mismatch\");")
  bench=f'''module tb;
reg [{bits-1}:0] terms; wire [{sw-1}:0] sum;
RecursiveUnsignedSum #(.COUNT({n}), .TERM_WIDTH({tw}), .SUM_WIDTH({sw})) dut(.terms(terms),.sum(sum));
function [{sw-1}:0] reference;
 input [{bits-1}:0] data; integer i; reg [{sw-1}:0] term;
 begin
  reference=0;
  for(i=0;i<{n};i=i+1) begin
   term=data[i*{tw} +: {tw}];
   if ({n} == 1) reference=term; else reference=reference+term;
  end
 end
endfunction
initial begin
{chr(10).join(stimulus+unknown)}
$display("PASS {label}"); $finish;
end
endmodule
'''
  tb=out/(label+'.v');tb.write_text(bench)
  run(label+'-compile',['iverilog','-g2001','-s','tb','-o',label+'.sim',rtl,tb]);run(label+'-simulate',['vvp',label+'.sim'])
  if f'PASS {label}' not in (out/(label+'-simulate.log')).read_text(): raise RuntimeError('simulation did not complete: '+label)
  run(label+'-lint',['verilator','--lint-only','--language','1364-2001','--top-module','RecursiveUnsignedSum',f'-GCOUNT={n}',f'-GTERM_WIDTH={tw}',f'-GSUM_WIDTH={sw}',rtl])
  run(label+'-synth',['yosys','-Q','-p',f'read_verilog -defer {rtl}; hierarchy -check -top RecursiveUnsignedSum -chparam COUNT {n} -chparam TERM_WIDTH {tw} -chparam SUM_WIDTH {sw}; proc; select -assert-none t:$dlatch; flatten; synth -top RecursiveUnsignedSum; check -assert; stat'])
  ref=out/(label+'-formal.v');ref.write_text(f'''module miter(input [{bits-1}:0] terms, output mismatch);
wire [{sw-1}:0] sum; reg [{sw-1}:0] flat; reg [{sw-1}:0] term; integer i;
RecursiveUnsignedSum #(.COUNT({n}),.TERM_WIDTH({tw}),.SUM_WIDTH({sw})) dut(.terms(terms),.sum(sum));
always @* begin flat=0; term=0; for(i=0;i<{n};i=i+1) begin term=terms[i*{tw} +: {tw}]; flat=flat+term; end end
assign mismatch = sum != flat;
always @* assert(!mismatch);
endmodule
''')
  run(label+'-formal-model',['yosys','-Q','-p',f'read_verilog -formal -defer {rtl} {ref}; prep -top miter -flatten; write_smt2 -wires {out}/{label}.smt2'])
  run(label+'-formal',['yosys-smtbmc','-s','z3','-t','1','--presat',out/(label+'.smt2')])
# Domain guards must reject invalid overrides, without relying on an elaboration
# timeout. COUNT <= 1 selects the finite base branch even for invalid counts.
for parameter, value, count, term, width in [
 ('COUNT',0,0,3,8),('COUNT',-1,-1,3,8),('COUNT',18,18,3,8),
 ('TERM_WIDTH',0,3,0,8),('TERM_WIDTH',-1,3,-1,8),('TERM_WIDTH',33,3,33,8),
 ('SUM_WIDTH',0,3,3,0),('SUM_WIDTH',-1,3,3,-1),('SUM_WIDTH',37,3,3,37)]:
 label=f'invalid-{parameter}-{value}'
 # Invalid packed widths are rejected by strict lint before simulation.
 # Icarus can attempt enormous allocations for a negative indexed part width.
 if parameter != 'COUNT' and value <= 0:
  code=run(label+'-lint',['verilator','--lint-only','--language','1364-2001','--top-module','RecursiveUnsignedSum',f'-GCOUNT={count}',f'-GTERM_WIDTH={term}',f'-GSUM_WIDTH={width}',rtl],passing=False)
  log=(out/(label+'-lint.log')).read_text()
  if code in (0,124) or not any(x in log for x in ['Width of :+ or :- is < 0','ASCRANGE','LITENDIAN','Little bit endian']):
   raise RuntimeError('invalid width was not rejected by strict lint: '+label)
  continue
 bench=out/(label+'.v')
 bench.write_text(f"""module tb;
 RecursiveUnsignedSum #(.COUNT({count}),.TERM_WIDTH({term}),.SUM_WIDTH({width})) dut();
 initial begin #1; $display("INVALID_OVERRIDE_ACCEPTED"); $finish; end
 endmodule
 """)
 code=run(label+'-compile',['iverilog','-g2001','-s','tb','-o',label+'.sim',rtl,bench],passing=False)
 if code == 124: raise RuntimeError('invalid override compilation timed out')
 if code == 0:
  code=run(label+'-simulate',['vvp',label+'.sim'],passing=False)
  log=(out/(label+'-simulate.log')).read_text()
  if code == 124 or 'INVALID_OVERRIDE_ACCEPTED' in log or f'{parameter} must be in' not in log:
   raise RuntimeError('invalid override did not hit its domain guard: '+label)
 else:
  log=(out/(label+'-compile.log')).read_text()
  if not any(message in log for message in ['Indexed part widths must be constant and greater than zero','Concatenation repeat may not be negative','part select']):
   raise RuntimeError('unexpected invalid geometry compile failure: '+label)
receipt(complete=True)
print('All recursive sum artifact checks passed')
