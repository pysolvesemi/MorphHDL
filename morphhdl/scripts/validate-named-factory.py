#!/usr/bin/env python3
"""Directed native AXI transactions, differential against the literal factory.

Parsing is confined to testbench port wiring; compiler publication never uses it.
"""
import argparse
from pathlib import Path
import re
import subprocess


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("directory", type=Path)
    parser.add_argument("--base", type=int, required=True)
    parser.add_argument("--lite", action="store_true")
    parser.add_argument("--symbolic", action="store_true")
    args = parser.parse_args()
    directory = args.directory.resolve()
    def ports(path):
        header = path.read_text().split(");", 1)[0]
        return [(d, " ".join(w.split()), n) for d, w, n in re.findall(
            r"^\s*(input|output)\s+(?:wire|reg)\s*(\[[^\]]+\])?\s*(\w+)\s*[,]?\s*$", header, re.M)]
    pins = ports(directory / "NamedFactory.v")
    assert pins and pins == ports(directory / "LiteralFactory.v"), "factory port contract changed"
    inputs = [(w, n) for d, w, n in pins if d == "input"]
    outputs = [(w, n) for d, w, n in pins if d == "output"]
    decls = [f"reg {w} {n}=0;" for w, n in inputs]
    decls += [f"wire {w} actual_{n},gold_{n};" for w, n in outputs]
    def bindings(prefix):
        return ",".join(f".{n}({n if d == 'input' else prefix+n})" for d, w, n in pins)
    override = f"#(.BASE_WORD({args.base // 4}))" if args.symbolic else ""
    checks = "\n".join(f'if(actual_{n} !== gold_{n}) $fatal(1,"DIFFERENTIAL {n}");' for w,n in outputs)
    aw_extra = "" if args.lite else "bus_aw_payload_id=id; bus_aw_payload_len=beats-1; bus_aw_payload_size=2; bus_aw_payload_burst=1;"
    ar_extra = "" if args.lite else "bus_ar_payload_id=id; bus_ar_payload_len=beats-1; bus_ar_payload_size=2; bus_ar_payload_burst=1;"
    last = "" if args.lite else "bus_w_payload_last=(beat==beats-1);"
    bid = "" if args.lite else 'if(actual_bus_b_payload_id !== id[1:0]) $fatal(1,"B_ID");'
    rid = "" if args.lite else 'if(actual_bus_r_payload_id !== id[1:0] || actual_bus_r_payload_last !== (beat==beats-1)) $fatal(1,"R_ID_LAST");'
    burst = "" if args.lite else f"""
write_tx({args.base},32'h76543210,15,2,2,0,2);
read_tx({args.base},32'h76543210,2,2);
if(actual_writeEvents !== 2 || actual_readEvents !== 2) $fatal(1,"BURST_EVENTS");
"""
    tb = f"""`timescale 1ns/1ps
module tb;
{chr(10).join(decls)}
NamedFactory {override} candidate({bindings('actual_')});
LiteralFactory reference({bindings('gold_')});
always #5 clk=~clk;
initial begin #200000; $fatal(1,"TRANSACTION_TIMEOUT"); end
task check_outputs; begin {checks} end endtask
task step; begin @(posedge clk); #1; check_outputs; end endtask
task automatic handshake;
input integer channel;
integer accepted,cycles;
begin
accepted=0; cycles=0;
while(!accepted && cycles<200) begin
@(negedge clk);
case(channel)
0: accepted=actual_bus_aw_ready;
1: accepted=actual_bus_w_ready;
2: accepted=actual_bus_ar_ready;
3: accepted=actual_bus_b_valid;
4: accepted=actual_bus_r_valid;
endcase
@(posedge clk); #1; check_outputs; cycles=cycles+1;
end
if(!accepted) $fatal(1,"CHANNEL_TIMEOUT %0d",channel);
end endtask

task automatic write_tx;
input integer address; input [31:0] data; input [3:0] strobes;
input integer id,awdelay,wdelay,beats;
integer beat,cycles;
begin
bus_b_ready=0; bus_aw_payload_addr=address; {aw_extra}
fork
begin repeat(awdelay) step; bus_aw_valid=1; handshake(0); bus_aw_valid=0; end
begin repeat(wdelay) step;
for(beat=0;beat<beats;beat=beat+1) begin
bus_w_payload_data=data+beat; bus_w_payload_strb=strobes; {last}
bus_w_valid=1; handshake(1); bus_w_valid=0;
end end
join
cycles=0; while(!actual_bus_b_valid && cycles<100) begin step; cycles=cycles+1; end
if(!actual_bus_b_valid) $fatal(1,"MISSING_B");
repeat(3) begin step; if(!actual_bus_b_valid) $fatal(1,"B_STALL"); end
if(actual_bus_b_payload_resp !== 0) $fatal(1,"B_RESP"); {bid}
bus_b_ready=1; handshake(3); bus_b_ready=0;
end endtask

task automatic read_tx;
input integer address; input [31:0] expected; input integer id,beats;
integer beat,cycles;
begin
bus_r_ready=0; bus_ar_payload_addr=address; {ar_extra}
bus_ar_valid=1; handshake(2); bus_ar_valid=0;
for(beat=0;beat<beats;beat=beat+1) begin
cycles=0; while(!actual_bus_r_valid && cycles<100) begin step; cycles=cycles+1; end
if(!actual_bus_r_valid) $fatal(1,"MISSING_R");
repeat(3) begin step; if(!actual_bus_r_valid) $fatal(1,"R_STALL"); end
if(actual_bus_r_payload_data !== (beat==0 ? expected : 32'd0)) $fatal(1,"READ_DATA");
if(actual_bus_r_payload_resp !== 0) $fatal(1,"R_RESP"); {rid}
bus_r_ready=1; handshake(4); bus_r_ready=0;
end
end endtask

initial begin
reset=1; repeat(3) step; reset=0; repeat(2) step;
if(actual_observed !== 0 || actual_observedHigh !== 0 || actual_readEvents !== 0 || actual_writeEvents !== 0) $fatal(1,"RESET");
write_tx({args.base},32'h11223344,15,1,3,0,1);
write_tx({args.base+1},32'haabbccdd,5,2,0,3,1);
if(actual_observed !== 32'h11bb33dd) $fatal(1,"BYTE_STROBES_MASKING");
read_tx({args.base},32'h11bb33dd,3,1);
write_tx(252,32'hfeedbeef,15,2,1,0,1);
read_tx(252,32'hfeedbeef,1,1);
write_tx({args.base+4},0,15,1,0,2,1);
read_tx({args.base+4},0,2,1);
if(actual_writeEvents !== 1 || actual_readEvents !== 1) $fatal(1,"EVENTS");
write_tx(128,32'hffffffff,15,0,0,1,1); read_tx(128,0,0,1);
if(actual_observed !== 32'h11bb33dd || actual_observedHigh !== 32'hfeedbeef) $fatal(1,"NO_MATCH");
write_tx(32'hxxxxxxxx,32'hffffffff,15,1,0,1,1);
read_tx(32'hzzzzzzzz,0,2,1);
if(actual_observed !== 32'h11bb33dd || actual_observedHigh !== 32'hfeedbeef || actual_writeEvents !== 1 || actual_readEvents !== 1) $fatal(1,"XZ_NO_MATCH");
{burst}
reset=1; repeat(3) step; reset=0; repeat(2) step;
if(actual_observed !== 0 || actual_observedHigh !== 0 || actual_readEvents !== 0 || actual_writeEvents !== 0) $fatal(1,"MIDSTREAM_RESET");
$display("NAMED_FACTORY_PROTOCOL_PASS"); $finish;
end
endmodule
"""
    (directory / "protocol.v").write_text(tb)
    subprocess.run(["iverilog", "-g2001", "-s", "tb", "-o", "protocol.vvp", "NamedFactory.v", "LiteralFactory.v", "protocol.v"], cwd=directory, check=True)
    subprocess.run(["vvp", "protocol.vvp"], cwd=directory, check=True, timeout=30)


if __name__ == "__main__":
    main()
