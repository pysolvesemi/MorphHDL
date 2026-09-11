#!/usr/bin/env python3
"""Qualify the actual public production-flag artifacts and a live counterexample."""
import argparse
import hashlib
import json
import subprocess
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    output = args.output.resolve()
    first, repeat = output / "first", output / "repeat"
    sources = sorted(first.glob("*/generated.v"))
    assert len(sources) == 10, sources
    for file in sources:
        assert file.read_bytes() == (repeat / file.relative_to(first)).read_bytes(), file
    for kind, baseline in (("fifo", "legacy"), ("generic", "plain")):
        assert (first / (kind + "-" + baseline + "/generated.v")).read_bytes() == (
            first / (kind + "-disabled/generated.v")).read_bytes(), kind
    for kind, default in (("fifo", "plain"), ("generic", "default")):
        assert (first / (kind + "-" + default + "/generated.v")).read_bytes() == (
            first / (kind + "-enabled/generated.v")).read_bytes(), kind
    # Exact symbolic RTL identity connects the real public API to the independently
    # generated five-pass candidate proved over all 512 bindings by the pass CI.
    fifo = first / "fifo-enabled/generated.v"
    assert fifo.read_bytes() == (first / "fifo-historical-all/generated.v").read_bytes()
    assert "parameter integer WIDTH" in fifo.read_text()
    assert "parameter integer DEPTH" in fifo.read_text()
    reference = (first / "generic-reference/generated.v").read_text()
    enabled = (first / "generic-enabled/generated.v").read_text()
    assert reference != enabled, "generic production run must actually transform expressions"
    proof = output / "proof"
    proof.mkdir(exist_ok=True)

    def run(name, command, success=True):
        result = subprocess.run(command, cwd=proof, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, text=True, timeout=600)
        (proof / (name + ".log")).write_text(result.stdout)
        assert (result.returncode == 0) == success, (name, result.stdout[-5000:])
        return result.stdout

    def renamed(source, name):
        assert source.count("module ProductionExpressions (") == 1
        return source.replace("module ProductionExpressions (", "module " + name + " (", 1)

    (proof / "reference.v").write_text(renamed(reference, "Reference"))
    (proof / "candidate.v").write_text(renamed(enabled, "Candidate"))
    connections = lambda prefix: ", ".join(".y%d(%s[%d])" % (i, prefix, i) for i in range(8))
    pair = ("wire [7:0] expected, actual;\n" +
        "Reference reference(.a(a), .b(b), " + connections("expected") + ");\n" +
        "Candidate candidate(.a(a), .b(b), " + connections("actual") + ");\n")
    (proof / "miter.v").write_text(
        "module Miter(input wire a, b, output wire ok);\n" + pair +
        "assign ok = (expected == actual);\nendmodule\n")
    (proof / "tb.v").write_text("module Tb; reg a,b; integer i,j;\n" + pair +
        "function value; input integer x; begin case(x)\n"
        "0:value=1'b0; 1:value=1'b1; 2:value=1'bx; 3:value=1'bz; endcase end endfunction\n"
        "initial begin for(i=0;i<4;i=i+1) for(j=0;j<4;j=j+1) begin\n"
        "a=value(i); b=value(j); #1; if(expected !== actual) begin\n"
        "$display(\"WA08 FOUR STATE FAIL\"); $finish(1); end end\n"
        "$display(\"WA08 FOUR STATE PASS cases=16\"); $finish; end endmodule\n")
    run("compile", ["iverilog", "-g2001", "-s", "Tb", "-o", "simulation.vvp",
                    "reference.v", "candidate.v", "tb.v"])
    simulation = run("four-state", ["vvp", "simulation.vvp"])
    assert "WA08 FOUR STATE PASS cases=16" in simulation and "FAIL" not in simulation
    for file, top in (("reference.v", "Reference"), ("candidate.v", "Candidate")):
        run(top + "-lint", ["verilator", "--lint-only", "--language", "1364-2001",
                            "-Wno-fatal", "--top-module", top, file])
        run(top + "-synthesis", ["yosys", "-Q", "-p", "read_verilog " + file +
            "; hierarchy -check -top " + top + "; synth -top " + top + "; check -assert"])
    proof_command = ("read_verilog reference.v candidate.v miter.v; prep -top Miter; "
                     "flatten; opt; sat -verify -prove ok 1 -show-inputs")
    run("equivalence", ["yosys", "-Q", "-p", proof_command])
    mutation = renamed(enabled, "Candidate")
    assert "assign y1 = 1'b0;" in mutation
    (proof / "candidate.v").write_text(mutation.replace("assign y1 = 1'b0;", "assign y1 = 1'b1;", 1))
    failed = run("mutation", ["yosys", "-Q", "-p", proof_command], success=False)
    assert "proof did fail" in failed or "model found: FAIL" in failed
    (proof / "candidate.v").write_text(mutation)
    report = {"deterministic_files": 10, "default_on_matches_enabled": True,
              "explicit_opt_out_byte_identity": True,
              "fifo_matches_full_domain_proof_candidate": True, "four_state_cases": 16,
              "formal_mutation_rejected": True,
              "fifo_sha256": hashlib.sha256(fifo.read_bytes()).hexdigest()}
    (output / "qualification.json").write_text(json.dumps(report, indent=2) + "\n")
    print("WA08_PRODUCTION_QUALIFICATION_PASS", json.dumps(report, sort_keys=True))


if __name__ == "__main__":
    main()
