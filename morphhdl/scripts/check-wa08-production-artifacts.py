#!/usr/bin/env python3
"""Qualify the actual public production-flag artifacts and a live counterexample."""
import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path


def qualify_ordinary_named(output, first):
    """Exercise ordinary source names through MorphVerilog, without fixture tags."""
    expected_aliases = ("bitCloneAlias", "flagAlias", "signedCloneAlias", "unsignedCloneAlias")
    protected_aliases = ("keptAlias", "guardedAlias", "sampledAlias", "conditionalAlias",
                         "toChildAlias", "fromChildAlias", "softResetAlias", "removableAlias", "timingAlias",
                         "rootProceduralAlias", "bitAlias", "signedAlias", "unsignedAlias")

    def declares_wire(source, name):
        return re.search(r"(?m)^\s*(?:\(\*.*?\*\)\s*)*wire\s+(?:signed\s+)?(?:\[[^\n]*?\]\s+)?" +
                         re.escape(name) + r"\s*;", source) is not None

    def prefixed(source, prefix):
        names = re.findall(r"(?m)^module\s+(\w+)", source)
        assert names, "public named fixture emitted no modules"
        return re.sub(r"\b(?:" + "|".join(map(re.escape, names)) + r")\b",
                      lambda match: prefix + match.group(0), source)

    def normalize_component(source):
        return source.replace("ProductionNamedAliases", "NamedTopology").replace(
            "UnrelatedPacketRouter", "NamedTopology")

    for mode in ("default", "enabled", "disabled"):
        assert normalize_component((first / ("named-" + mode) / "generated.v").read_text()) == (
            normalize_component((first / ("unrelated-" + mode) / "generated.v").read_text())), mode

    debug_enabled = (first / "named-debug-enabled/generated.v").read_text()
    debug_disabled = (first / "named-debug-disabled/generated.v").read_text()
    for name in ("debugAlias", "debugSource"):
        assert declares_wire(debug_disabled, name) and declares_wire(debug_enabled, name), (
            "source assignment debug metadata was not preserved", name)
    for source in (debug_disabled, debug_enabled):
        assert "WireAssignmentProductionArtifactWriter.scala" in source, (
            "debug fixture must contain actual generated source-location comments")
    assert debug_enabled == debug_disabled, "debug aliases and assignment metadata changed"
    opaque = [(first / ("named-opaque-" + mode) / "generated.v").read_text()
              for mode in ("default", "enabled", "disabled")]
    assert opaque[0] == opaque[1] == opaque[2], "opaque metadata identity changed"
    assert declares_wire(opaque[0], "opaqueAlias"), "unknown metadata must fail closed"
    for name, top in (("named-debug-enabled", "ProductionDebugAliases"),
                      ("named-opaque-default", "ProductionOpaqueAliases")):
        result = subprocess.run(["iverilog", "-g2001", "-s", top, "-t", "null",
                                 str(first / name / "generated.v")],
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=600)
        assert result.returncode == 0, (name, result.stdout)

    total_four_state = 0
    total_equivalence = 0
    for kind, module in (("named", "ProductionNamedAliases"),
                         ("unrelated", "UnrelatedPacketRouter")):
        reference = (first / (kind + "-disabled") / "generated.v").read_text()
        enabled = (first / (kind + "-enabled") / "generated.v").read_text()
        default = (first / (kind + "-default") / "generated.v").read_text()
        assert enabled == default, (kind, "default does not match explicit enable")
        assert enabled != reference, (kind, "public production path removed no named alias")
        assert "parameter integer WIDTH" in enabled, kind
        for name in expected_aliases:
            assert declares_wire(reference, name), (kind, "missing disabled alias", name)
            assert re.search(r"\b" + re.escape(name) + r"\b", enabled) is None, (
                kind, "ordinary untagged alias survived", name)
        for name in protected_aliases + ("bitSource", "flagSource", "signedSource", "unsignedSource"):
            assert declares_wire(reference, name) and declares_wire(enabled, name), (
                kind, "protected alias or surviving source name changed", name)
        assert "assign clonedResult = bitCloneAlias;" in reference, kind
        assert "assign clonedResult = bitSource;" in enabled, kind

        proof = output / (kind + "-proof")
        proof.mkdir(exist_ok=True)

        def run(name, command, success=True):
            result = subprocess.run(command, cwd=proof, stdout=subprocess.PIPE,
                                    stderr=subprocess.STDOUT, text=True, timeout=600)
            (proof / (name + ".log")).write_text(result.stdout)
            assert (result.returncode == 0) == success, (kind, name, result.stdout[-5000:])
            return result.stdout

        reference_top, candidate_top = "Reference_" + module, "Candidate_" + module
        (proof / "reference.v").write_text(prefixed(reference, "Reference_"))
        candidate = prefixed(enabled, "Candidate_")
        (proof / "candidate.v").write_text(candidate)
        outputs = (("bitResult", "WIDTH"), ("clonedResult", "WIDTH"), ("keptResult", "WIDTH"),
                   ("guardedResult", "WIDTH"), ("registeredResult", "WIDTH"),
                   ("branchResult", "WIDTH"), ("softRegisterResult", "WIDTH"),
                   ("flagResult", "1"), ("removableResult", "1"),
                   ("timingResult", "1"), ("timingRegisterResult", "1"), ("rootBranchResult", "1"),
                   ("signedResult", "8"), ("unsignedResult", "8"),
                   ("signedCloneResult", "8"), ("unsignedCloneResult", "8"),
                   ("hierarchyResult", "8"), ("hierarchyInputResult", "8"))
        inputs = ".a(a), .b(b), .fixedIn(fixedIn), .signedIn(signedIn), " + (
            ".unsignedIn(unsignedIn), .choose(choose), .softResetIn(softResetIn), .clk(clk), .reset(reset)")
        declarations = "\n".join("wire [%s-1:0] %s_%s;" % (width, prefix, port)
                                 for prefix in ("expected", "actual") for port, width in outputs)
        connections = lambda prefix: ", ".join(".%s(%s_%s)" % (port, prefix, port)
                                                for port, _ in outputs)
        vector = lambda prefix: "{" + ", ".join(prefix + "_" + port for port, _ in outputs) + "}"
        pair = declarations + "\n" + (
            reference_top + " #(.WIDTH(WIDTH)) reference(" + inputs + ", " + connections("expected") + ");\n" +
            candidate_top + " #(.WIDTH(WIDTH)) candidate(" + inputs + ", " + connections("actual") + ");\n")
        (proof / "miter.v").write_text(
            "module Miter #(parameter integer WIDTH=8)(input wire clk,reset,choose,softResetIn,\n"
            "input wire [WIDTH-1:0] a,b, input wire [7:0] fixedIn,signedIn,unsignedIn, output wire ok);\n" +
            pair + "assign ok = (" + vector("expected") + " == " + vector("actual") + ");\nendmodule\n")
        (proof / "tb.v").write_text(
            "module Tb; parameter integer WIDTH=8; reg clk,reset,choose,softResetIn;\n"
            "reg [WIDTH-1:0] a,b; reg [7:0] fixedIn,signedIn,unsignedIn; integer i,j,k;\n" + pair +
            "function value; input integer x; begin case(x)\n"
            "0:value=1'b0; 1:value=1'b1; 2:value=1'bx; 3:value=1'bz; endcase end endfunction\n"
            "task compare; begin if(" + vector("expected") + " !== " + vector("actual") + ") begin\n"
            "$display(\"WA08 NAMED FOUR STATE FAIL WIDTH=%0d i=%0d j=%0d k=%0d\", WIDTH,i,j,k);\n"
            "$finish(1); end end endtask\n"
            "initial begin clk=0;reset=1;choose=0;softResetIn=0;a=0;b=0;fixedIn=0;signedIn=0;unsignedIn=0;\n"
            "#1;clk=1;#1;clk=0;reset=0;\n"
            "for(i=0;i<4;i=i+1) for(j=0;j<4;j=j+1) for(k=0;k<4;k=k+1) begin\n"
            "a={WIDTH{value(i)}};b={WIDTH{value(j)}};choose=value(k);softResetIn=value(j);fixedIn={8{value(k)}};\n"
            "signedIn={8{value(i)}};unsignedIn={8{value(j)}};\n"
            "#1;compare;clk=1;#1;compare;clk=0; end\n"
            "$display(\"WA08 NAMED FOUR STATE PASS cases=64\");$finish;end endmodule\n")
        for file, top in (("reference.v", reference_top), ("candidate.v", candidate_top)):
            run(top + "-lint", ["verilator", "--lint-only", "--language", "1364-2001",
                                "-Wno-fatal", "--top-module", top, file])
            run(top + "-synthesis", ["yosys", "-Q", "-p", "read_verilog " + file +
                "; hierarchy -check -top " + top + "; synth -top " + top + "; check -assert"])

        def proof_command(width):
            return ("read_verilog reference.v candidate.v miter.v; chparam -set WIDTH " + str(width) +
                    " Miter; prep -top Miter; flatten; opt; "
                    "sat -seq 4 -set-init-zero -verify -prove ok 1 -show-inputs")

        for width in range(1, 17):
            run("compile-width-" + str(width), ["iverilog", "-g2001", "-s", "Tb", "-PTb.WIDTH=" + str(width),
                                               "-o", "simulation.vvp", "reference.v", "candidate.v", "tb.v"])
            simulation = run("four-state-width-" + str(width), ["vvp", "simulation.vvp"])
            assert "WA08 NAMED FOUR STATE PASS cases=64" in simulation and "FAIL" not in simulation
            total_four_state += 64
            run("equivalence-width-" + str(width), ["yosys", "-Q", "-p", proof_command(width)])
            total_equivalence += 1
        assert "assign clonedResult = bitSource;" in candidate
        (proof / "candidate.v").write_text(candidate.replace(
            "assign clonedResult = bitSource;", "assign clonedResult = ~bitSource;", 1))
        failed = run("mutation", ["yosys", "-Q", "-p", proof_command(8)], success=False)
        assert "proof did fail" in failed or "model found: FAIL" in failed
        (proof / "candidate.v").write_text(candidate)
    return {"ordinary_untagged_aliases_removed_per_fixture": len(expected_aliases),
            "protected_aliases_retained_per_fixture": len(protected_aliases),
            "surviving_source_names_preserved": True, "component_rename_invariant": True,
            "assignment_debug_metadata_preserved": True,
            "opaque_metadata_retained_without_source_tags": True,
            "public_default_matches_enabled": True, "explicit_opt_out_retains_aliases": True,
            "parameter_width_bindings": list(range(1, 17)),
            "four_state_cases": total_four_state, "sequential_equivalence_cases": total_equivalence,
            "functional_mutations_rejected": 2}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    output = args.output.resolve()
    first, repeat = output / "first", output / "repeat"
    sources = sorted(first.glob("*/generated.v"))
    assert len(sources) == 21, sources
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
    named_qualification = qualify_ordinary_named(output, first)
    report = {"deterministic_files": 21, "default_on_matches_enabled": True,
              "explicit_opt_out_byte_identity": True,
              "fifo_matches_full_domain_proof_candidate": True, "four_state_cases": 16,
              "formal_mutation_rejected": True,
              "fifo_sha256": hashlib.sha256(fifo.read_bytes()).hexdigest(),
              "ordinary_named_aliases": named_qualification}
    (output / "qualification.json").write_text(json.dumps(report, indent=2) + "\n")
    print("WA08_PRODUCTION_QUALIFICATION_PASS", json.dumps(report, sort_keys=True))


if __name__ == "__main__":
    main()
