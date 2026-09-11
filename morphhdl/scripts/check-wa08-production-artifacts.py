#!/usr/bin/env python3
"""Qualify the actual public production-flag artifacts and a live counterexample."""
import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path


EXPECTED_GENERATED_ARTIFACTS = frozenset(
    ["fifo-" + mode + "/generated.v" for mode in (
        "enabled", "disabled", "plain", "legacy", "historical-all", "canonical-six")]
    + ["generic-" + mode + "/generated.v" for mode in (
        "reference", "enabled", "default", "disabled", "plain")]
    + [kind + "-" + mode + "/generated.v"
       for kind in ("named", "unrelated")
       for mode in ("default", "enabled", "disabled")]
    + ["named-debug-" + mode + "/generated.v" for mode in ("enabled", "disabled")]
    + ["named-opaque-" + mode + "/generated.v"
       for mode in ("default", "enabled", "disabled")]
    + ["nested-" + kind + "-" + mode + "/generated.v"
       for kind in ("fixed", "parameterized", "overflow")
       for mode in ("default", "enabled", "disabled")]
)


def qualify_ordinary_named(output, first):
    """Exercise ordinary source names through MorphVerilog, without fixture tags."""
    historical_aliases = ("bitCloneAlias", "flagAlias", "signedCloneAlias",
                          "unsignedCloneAlias")
    successor_aliases = ("bitAlias", "removableAlias", "p")
    expected_aliases = historical_aliases + successor_aliases
    expected_expressions = ("bitSource", "flagSource", "signedSource", "unsignedSource",
                            "softResetSource", "timingSource", "fixedSource",
                            "substantiallyLongerMeaningfulSource", "q")
    lexical_tie_names = ("aaa", "bbb")
    protected_aliases = ("keptAlias", "guardedAlias", "sampledAlias", "conditionalAlias",
                         "toChildAlias", "fromChildAlias", "softResetAlias", "timingAlias",
                         "rootProceduralAlias", "extraordinarilyLongProtectedName")
    ineligible_direct_port_aliases = ("signedAlias", "unsignedAlias")

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
        for name in expected_expressions:
            assert declares_wire(reference, name), (kind, "missing disabled expression wire", name)
            assert re.search(r"(?<![A-Za-z0-9_$])" + re.escape(name) +
                             r"(?![A-Za-z0-9_$])", enabled) is None, (
                kind, "eligible named/generated expression wire survived", name)
        for name in lexical_tie_names:
            assert declares_wire(reference, name), (kind, "missing lexical-tie wire", name)
            assert re.search(r"(?<![A-Za-z0-9_$])" + re.escape(name) +
                             r"(?![A-Za-z0-9_$])", enabled) is None, (
                kind, "equal-length lexical-tie wire survived", name)
        for name in protected_aliases + ineligible_direct_port_aliases:
            assert declares_wire(reference, name) and declares_wire(enabled, name), (
                kind, "protected/ineligible alias identity changed", name)
        generated_names = re.findall(
            r"(?m)^\s*wire\s+(?:\[[^\n]*?\]\s+)?(_zz(?:_[0-9]+)?)\s*;", reference)
        assert len(generated_names) == 1, (kind, "generated fixture identity changed", generated_names)
        generated_assignment = re.search(
            r"assign (_zz(?:_[0-9]+)?) = \(choose \^ softResetIn\);", reference)
        assert generated_assignment, (kind, "generated provenance fixture changed")
        generated_name = generated_assignment.group(1)
        assert generated_names == [generated_name], (kind, generated_names)
        assert re.search(r"(?<![A-Za-z0-9_$])_zz(?:_[0-9]+)?(?![A-Za-z0-9_$])", enabled) is None, (
            kind, "generated or genuinely unnamed temporary survived")
        assert "assign clonedResult = bitCloneAlias;" in reference, kind
        assert "assign clonedResult = (a ^ b);" in enabled, kind
        assert "assign " + generated_name + " = (choose ^ softResetIn);" in reference, kind
        assert "assign abc = " + generated_name + ";" in reference, kind
        assert "assign abc = (choose ^ softResetIn);" in enabled, kind
        assert "assign unnamedTemporaryResult = (choose != softResetIn);" in reference, kind
        assert "assign unnamedTemporaryResult = (choose != softResetIn);" in enabled, kind
        assert "assign q = substantiallyLongerMeaningfulSource;" in reference, kind
        assert "assign shortestNameResult = q;" in reference, kind
        assert "assign shortestNameResult = (choose && softResetIn);" in enabled, kind
        assert "assign bbb = (choose == softResetIn);" in reference, kind
        assert "assign aaa = bbb;" in reference, kind
        assert "assign lexicalTieResult = aaa;" in reference, kind
        assert "assign lexicalTieResult = (choose == softResetIn);" in enabled, kind
        assert "assign p = extraordinarilyLongProtectedName;" in reference, kind
        assert "assign protectedPreferenceResult = p;" in reference, kind
        assert "assign protectedPreferenceResult = extraordinarilyLongProtectedName;" in enabled, kind

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
                   ("abc", "1"), ("unnamedTemporaryResult", "1"),
                   ("shortestNameResult", "1"), ("lexicalTieResult", "1"),
                   ("protectedPreferenceResult", "1"),
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
        assert candidate.count("assign clonedResult = (a ^ b);") == 1
        (proof / "candidate.v").write_text(candidate.replace(
            "assign clonedResult = (a ^ b);", "assign clonedResult = (~ (a ^ b));", 1))
        failed = run("mutation", ["yosys", "-Q", "-p", proof_command(8)], success=False)
        assert "proof did fail" in failed or "model found: FAIL" in failed
        (proof / "candidate.v").write_text(candidate)
    return {"ordinary_untagged_aliases_removed_per_fixture": len(historical_aliases),
            "all_direct_aliases_removed_per_fixture": len(expected_aliases),
            "named_or_generated_expressions_removed_per_fixture": len(expected_expressions) + 1,
            "genuinely_unnamed_expression_temporaries_not_emitted_per_fixture": 1,
            "protected_aliases_retained_per_fixture": len(protected_aliases),
            "ineligible_direct_port_aliases_retained_per_fixture": len(ineligible_direct_port_aliases),
            "final_named_expression_inlined_to_output": True,
            "short_meaningful_chain_fully_inlined": True,
            "equal_length_lexical_tie_is_deterministic": True,
            "equal_length_lexical_chain_names_removed_per_fixture": len(lexical_tie_names),
            "meaningful_name_beats_generated_spelling": True,
            "meaningful_name_beats_genuinely_unnamed_temporary": True,
            "output_port_identity_overrides_name_length": True,
            "protected_long_name_overrides_shorter_alias": True,
            "component_rename_invariant": True,
            "assignment_debug_metadata_preserved": True,
            "opaque_metadata_retained_without_source_tags": True,
            "public_default_matches_enabled": True, "explicit_opt_out_retains_aliases": True,
            "parameter_width_bindings": list(range(1, 17)),
            "four_state_cases": total_four_state, "sequential_equivalence_cases": total_equivalence,
            "functional_mutations_rejected": 2}


def qualify_nested_unsigned_sums(output, first):
    """Prove the public six-pass output for the exact nested unsigned-add form."""
    fixed = {mode: (first / ("nested-fixed-" + mode) / "generated.v").read_text()
             for mode in ("default", "enabled", "disabled")}
    parameterized = {
        mode: (first / ("nested-parameterized-" + mode) / "generated.v").read_text()
        for mode in ("default", "enabled", "disabled")
    }
    overflow = {mode: (first / ("nested-overflow-" + mode) / "generated.v").read_text()
                for mode in ("default", "enabled", "disabled")}
    assert fixed["default"] == fixed["enabled"], "fixed default differs from explicit enable"
    assert parameterized["default"] == parameterized["enabled"], (
        "parameterized default differs from explicit enable")
    assert overflow["default"] == overflow["enabled"], (
        "overflow default differs from explicit enable")
    assert fixed["enabled"] != fixed["disabled"], "fixed wrapper fixture did not transform"
    assert parameterized["enabled"] != parameterized["disabled"], (
        "parameterized wrapper fixture did not transform")
    assert overflow["enabled"] != overflow["disabled"], (
        "overflow wrapper fixture did not transform")

    def internal_wires(source, prefix):
        return set(re.findall(
            r"(?m)^\s*wire\s+\[17:0\]\s+(" + re.escape(prefix) + r"(?:_[0-9]+)?)\s*;",
            source))

    fixed_wrappers = {"_zz_hTotal"} | {"_zz_hTotal_" + str(index) for index in range(1, 6)}
    assert internal_wires(fixed["disabled"], "_zz_hTotal") == fixed_wrappers
    assert not internal_wires(fixed["enabled"], "_zz_hTotal")
    fixed_direct = ("assign hTotal = ((({2'd0, hActive} + {2'd0, hFrontPorch}) + "
                    "{2'd0, hSyncWidth}) + {2'd0, hBackPorch});")
    assert fixed_direct in fixed["enabled"]
    assert "assign hTotal = (_zz_hTotal + _zz_hTotal_5);" in fixed["disabled"]

    parameter_add_wrappers = {"_zz_hTotal", "_zz_hTotal_1"}
    parameter_resize_carriers = {"morphhdl_resize"} | {
        "morphhdl_resize_" + str(index) for index in range(1, 4)
    }
    assert internal_wires(parameterized["disabled"], "_zz_hTotal") == parameter_add_wrappers
    assert not internal_wires(parameterized["enabled"], "_zz_hTotal")
    for source in parameterized.values():
        assert internal_wires(source, "morphhdl_resize") == parameter_resize_carriers
    parameter_direct = ("assign hTotal = (((morphhdl_resize + morphhdl_resize_1) + "
                        "morphhdl_resize_2) + morphhdl_resize_3);")
    assert parameter_direct in parameterized["enabled"]
    assert "assign hTotal = (_zz_hTotal + morphhdl_resize_3);" in parameterized["disabled"]
    assert "parameter integer WIDTH = 16" in parameterized["enabled"]

    overflow_wrappers = {"_zz_overflowTotal", "_zz_overflowTotal_1"}
    assert internal_wires(overflow["disabled"], "_zz_overflowTotal") == overflow_wrappers
    assert not internal_wires(overflow["enabled"], "_zz_overflowTotal")
    overflow_direct = "assign overflowTotal = (((a + b) + c) + d);"
    assert overflow_direct in overflow["enabled"]
    assert "assign overflowTotal = (_zz_overflowTotal + d);" in overflow["disabled"]

    proof = output / "nested-sum-proof"
    proof.mkdir(exist_ok=True)

    def run(name, command, cwd=proof, success=True):
        result = subprocess.run(command, cwd=cwd, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, text=True, timeout=600)
        (proof / (name + ".log")).write_text(result.stdout)
        assert (result.returncode == 0) == success, (name, result.stdout[-5000:])
        return result.stdout

    def prefixed(source, prefix):
        names = re.findall(r"(?m)^module\s+(\w+)", source)
        assert names, "nested-sum fixture emitted no modules"
        return re.sub(r"\b(?:" + "|".join(map(re.escape, names)) + r")\b",
                      lambda match: prefix + match.group(0), source)

    total_four_state = 0
    total_equivalence = 0
    for kind, source, module in (
            ("fixed", fixed, "FixedNestedUnsignedExtendedSum"),
            ("parameterized", parameterized, "ParameterizedNestedUnsignedExtendedSum")):
        case = proof / kind
        case.mkdir(exist_ok=True)
        reference_top, candidate_top = "Reference_" + module, "Candidate_" + module
        reference = prefixed(source["disabled"], "Reference_")
        candidate = prefixed(source["enabled"], "Candidate_")
        (case / "reference.v").write_text(reference)
        (case / "candidate.v").write_text(candidate)

        for file, top in (("reference.v", reference_top), ("candidate.v", candidate_top)):
            run(kind + "-" + top + "-lint",
                ["verilator", "--lint-only", "--language", "1364-2001", "-Wno-fatal",
                 "--top-module", top, file], cwd=case)
            run(kind + "-" + top + "-synthesis",
                ["yosys", "-Q", "-p", "read_verilog " + file +
                 "; hierarchy -check -top " + top + "; synth -top " + top +
                 "; check -assert"], cwd=case)

        if kind == "fixed":
            pair = (
                "wire [17:0] expected, actual; wire [3:0] witnessExpected, witnessActual;\n"
                + reference_top + " #(.WITNESS_WIDTH(4)) reference("
                ".hActive(hActive), .hFrontPorch(hFrontPorch), .hSyncWidth(hSyncWidth), "
                ".hBackPorch(hBackPorch), .hTotal(expected), "
                ".parameterWitnessIn(parameterWitnessIn), "
                ".parameterWitnessOut(witnessExpected));\n"
                + candidate_top + " #(.WITNESS_WIDTH(4)) candidate("
                ".hActive(hActive), .hFrontPorch(hFrontPorch), .hSyncWidth(hSyncWidth), "
                ".hBackPorch(hBackPorch), .hTotal(actual), "
                ".parameterWitnessIn(parameterWitnessIn), "
                ".parameterWitnessOut(witnessActual));\n")
            ports = ("input wire [15:0] hActive,hFrontPorch,hSyncWidth,hBackPorch, "
                     "input wire [3:0] parameterWitnessIn")
            (case / "miter.v").write_text(
                "module Miter(" + ports + ", output wire ok);\n" + pair
                + "assign ok = (expected == actual) && "
                  "(witnessExpected == witnessActual);\nendmodule\n")
            (case / "tb.v").write_text(
                "module Tb; reg [15:0] hActive,hFrontPorch,hSyncWidth,hBackPorch; "
                "reg [3:0] parameterWitnessIn; integer i,j,k,l;\n" + pair
                + "function value; input integer x; begin case(x) "
                  "0:value=1'b0; 1:value=1'b1; 2:value=1'bx; 3:value=1'bz; "
                  "endcase end endfunction\n"
                + "initial begin parameterWitnessIn=4'b10xz; "
                  "for(i=0;i<4;i=i+1) for(j=0;j<4;j=j+1) "
                  "for(k=0;k<4;k=k+1) for(l=0;l<4;l=l+1) begin "
                  "hActive={16{value(i)}}; hFrontPorch={16{value(j)}}; "
                  "hSyncWidth={16{value(k)}}; hBackPorch={16{value(l)}}; #1; "
                  "if(expected !== actual || witnessExpected !== witnessActual) begin "
                  "$display(\"WA09 FIXED FOUR STATE FAIL\"); $finish(1); end end "
                  "$display(\"WA09 FIXED FOUR STATE PASS cases=256\"); $finish; end\nendmodule\n")
            run("fixed-compile", ["iverilog", "-g2001", "-s", "Tb", "-o",
                                  "simulation.vvp", "reference.v", "candidate.v", "tb.v"], cwd=case)
            simulation = run("fixed-four-state", ["vvp", "simulation.vvp"], cwd=case)
            assert "WA09 FIXED FOUR STATE PASS cases=256" in simulation and "FAIL" not in simulation
            total_four_state += 256
            proof_command = ("read_verilog reference.v candidate.v miter.v; prep -top Miter; "
                             "flatten; opt; sat -verify -prove ok 1 -show-inputs")
            run("fixed-equivalence", ["yosys", "-Q", "-p", proof_command], cwd=case)
            total_equivalence += 1

            assert candidate.count(fixed_direct) == 1
            mutated_direct = fixed_direct.replace(" + ", " - ", 1)
            mutated = candidate.replace(fixed_direct, mutated_direct, 1)
            assert mutated != candidate and mutated.count(mutated_direct) == 1
            (case / "candidate.v").write_text(mutated)
            failure = run("fixed-mutation", ["yosys", "-Q", "-p", proof_command],
                          cwd=case, success=False)
            assert "proof did fail" in failure or "model found: FAIL" in failure
            (case / "candidate.v").write_text(candidate)
        else:
            pair = (
                "wire [17:0] expected, actual;\n" + reference_top
                + " #(.WIDTH(WIDTH)) reference(.hActive(hActive), "
                  ".hFrontPorch(hFrontPorch), .hSyncWidth(hSyncWidth), "
                  ".hBackPorch(hBackPorch), .hTotal(expected));\n"
                + candidate_top + " #(.WIDTH(WIDTH)) candidate(.hActive(hActive), "
                  ".hFrontPorch(hFrontPorch), .hSyncWidth(hSyncWidth), "
                  ".hBackPorch(hBackPorch), .hTotal(actual));\n")
            ports = "input wire [WIDTH-1:0] hActive,hFrontPorch,hSyncWidth,hBackPorch"
            (case / "miter.v").write_text(
                "module Miter #(parameter integer WIDTH=16)(" + ports
                + ", output wire ok);\n" + pair
                + "assign ok = (expected == actual);\nendmodule\n")
            (case / "tb.v").write_text(
                "module Tb; parameter integer WIDTH=16; "
                "reg [WIDTH-1:0] hActive,hFrontPorch,hSyncWidth,hBackPorch; "
                "integer i,j,k,l;\n" + pair
                + "function value; input integer x; begin case(x) "
                  "0:value=1'b0; 1:value=1'b1; 2:value=1'bx; 3:value=1'bz; "
                  "endcase end endfunction\n"
                + "initial begin for(i=0;i<4;i=i+1) for(j=0;j<4;j=j+1) "
                  "for(k=0;k<4;k=k+1) for(l=0;l<4;l=l+1) begin "
                  "hActive={WIDTH{value(i)}}; hFrontPorch={WIDTH{value(j)}}; "
                  "hSyncWidth={WIDTH{value(k)}}; hBackPorch={WIDTH{value(l)}}; #1; "
                  "if(expected !== actual) begin "
                  "$display(\"WA09 PARAMETER FOUR STATE FAIL WIDTH=%0d\", WIDTH); "
                  "$finish(1); end end "
                  "$display(\"WA09 PARAMETER FOUR STATE PASS WIDTH=%0d cases=256\", WIDTH); "
                  "$finish; end\nendmodule\n")
            for width in range(1, 17):
                run("parameterized-compile-width-" + str(width),
                    ["iverilog", "-g2001", "-s", "Tb", "-PTb.WIDTH=" + str(width),
                     "-o", "simulation.vvp", "reference.v", "candidate.v", "tb.v"], cwd=case)
                simulation = run("parameterized-four-state-width-" + str(width),
                                 ["vvp", "simulation.vvp"], cwd=case)
                assert ("WA09 PARAMETER FOUR STATE PASS WIDTH=" + str(width)
                        + " cases=256") in simulation and "FAIL" not in simulation
                total_four_state += 256
                command = ("read_verilog reference.v candidate.v miter.v; chparam -set WIDTH "
                           + str(width) + " Miter; prep -top Miter; flatten; opt; "
                           "sat -verify -prove ok 1 -show-inputs")
                run("parameterized-equivalence-width-" + str(width),
                    ["yosys", "-Q", "-p", command], cwd=case)
                total_equivalence += 1

    overflow_case = proof / "overflow"
    overflow_case.mkdir(exist_ok=True)
    overflow_module = "FixedNestedUnsignedOverflowSum"
    overflow_reference_top = "Reference_" + overflow_module
    overflow_candidate_top = "Candidate_" + overflow_module
    overflow_reference = prefixed(overflow["disabled"], "Reference_")
    overflow_candidate = prefixed(overflow["enabled"], "Candidate_")
    (overflow_case / "reference.v").write_text(overflow_reference)
    (overflow_case / "candidate.v").write_text(overflow_candidate)
    for file, top in (("reference.v", overflow_reference_top),
                      ("candidate.v", overflow_candidate_top)):
        run("overflow-" + top + "-lint",
            ["verilator", "--lint-only", "--language", "1364-2001", "-Wno-fatal",
             "--top-module", top, file], cwd=overflow_case)
        run("overflow-" + top + "-synthesis",
            ["yosys", "-Q", "-p", "read_verilog " + file
             + "; hierarchy -check -top " + top + "; synth -top " + top
             + "; check -assert"], cwd=overflow_case)
    overflow_pair = (
        "wire [17:0] expected, actual; wire [3:0] witnessExpected, witnessActual;\n"
        + overflow_reference_top + " #(.WITNESS_WIDTH(4)) reference("
          ".a(a), .b(b), .c(c), .d(d), .overflowTotal(expected), "
          ".parameterWitnessIn(parameterWitnessIn), "
          ".parameterWitnessOut(witnessExpected));\n"
        + overflow_candidate_top + " #(.WITNESS_WIDTH(4)) candidate("
          ".a(a), .b(b), .c(c), .d(d), .overflowTotal(actual), "
          ".parameterWitnessIn(parameterWitnessIn), "
          ".parameterWitnessOut(witnessActual));\n")
    (overflow_case / "miter.v").write_text(
        "module Miter(input wire [17:0] a,b,c,d, input wire [3:0] parameterWitnessIn, "
        "output wire ok);\n" + overflow_pair
        + "assign ok = (expected == actual) && (witnessExpected == witnessActual);\n"
          "endmodule\n")
    (overflow_case / "tb.v").write_text(
        "module Tb; reg [17:0] a,b,c,d; reg [3:0] parameterWitnessIn; "
        "integer i,j,k,l;\n" + overflow_pair
        + "function value; input integer x; begin case(x) "
          "0:value=1'b0; 1:value=1'b1; 2:value=1'bx; 3:value=1'bz; "
          "endcase end endfunction\n"
        + "initial begin parameterWitnessIn=4'b10xz; "
          "a=18'h3ffff; b=18'h3ffff; c=18'h3ffff; d=18'h3ffff; #1; "
          "if(expected !== actual || actual !== 18'h3fffc) begin "
          "$display(\"WA09 OVERFLOW MAX FAIL expected=%h actual=%h\", expected, actual); "
          "$finish(1); end "
          "for(i=0;i<4;i=i+1) for(j=0;j<4;j=j+1) "
          "for(k=0;k<4;k=k+1) for(l=0;l<4;l=l+1) begin "
          "a={18{value(i)}}; b={18{value(j)}}; c={18{value(k)}}; d={18{value(l)}}; #1; "
          "if(expected !== actual || witnessExpected !== witnessActual) begin "
          "$display(\"WA09 OVERFLOW FOUR STATE FAIL\"); $finish(1); end end "
          "$display(\"WA09 OVERFLOW PASS max_cases=1 four_state_cases=256\"); "
          "$finish; end\nendmodule\n")
    run("overflow-compile", ["iverilog", "-g2001", "-s", "Tb", "-o", "simulation.vvp",
                             "reference.v", "candidate.v", "tb.v"], cwd=overflow_case)
    overflow_simulation = run("overflow-simulation", ["vvp", "simulation.vvp"],
                              cwd=overflow_case)
    assert ("WA09 OVERFLOW PASS max_cases=1 four_state_cases=256" in overflow_simulation
            and "FAIL" not in overflow_simulation)
    total_four_state += 256
    overflow_proof = ("read_verilog reference.v candidate.v miter.v; prep -top Miter; "
                      "flatten; opt; sat -verify -prove ok 1 -show-inputs")
    run("overflow-equivalence", ["yosys", "-Q", "-p", overflow_proof], cwd=overflow_case)
    total_equivalence += 1

    return {"fixed_disabled_wrapper_wires": len(fixed_wrappers),
            "fixed_enabled_wrapper_wires": 0,
            "parameterized_disabled_add_wrapper_wires": len(parameter_add_wrappers),
            "parameterized_enabled_add_wrapper_wires": 0,
            "parameterized_resize_carriers_retained": len(parameter_resize_carriers),
            "overflow_disabled_add_wrapper_wires": len(overflow_wrappers),
            "overflow_enabled_add_wrapper_wires": 0,
            "overflow_max_value_cases": 1,
            "parameter_width_bindings": list(range(1, 17)),
            "default_matches_enabled": True,
            "explicit_opt_out_retains_legacy_wrappers": True,
            "four_state_cases": total_four_state,
            "formal_equivalence_cases": total_equivalence,
            "functional_mutations_rejected": 1}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    output = args.output.resolve()
    first, repeat = output / "first", output / "repeat"
    sources = sorted(first.glob("*/generated.v"))
    actual_artifacts = frozenset(file.relative_to(first).as_posix() for file in sources)
    assert actual_artifacts == EXPECTED_GENERATED_ARTIFACTS, (
        "production artifact inventory mismatch",
        sorted(EXPECTED_GENERATED_ARTIFACTS - actual_artifacts),
        sorted(actual_artifacts - EXPECTED_GENERATED_ARTIFACTS))
    for file in sources:
        assert file.read_bytes() == (repeat / file.relative_to(first)).read_bytes(), file
    for kind, baseline in (("fifo", "legacy"), ("generic", "plain")):
        assert (first / (kind + "-" + baseline + "/generated.v")).read_bytes() == (
            first / (kind + "-disabled/generated.v")).read_bytes(), kind
    for kind, default in (("fifo", "plain"), ("generic", "default")):
        assert (first / (kind + "-" + default + "/generated.v")).read_bytes() == (
            first / (kind + "-enabled/generated.v")).read_bytes(), kind
    # Exact symbolic RTL identity connects the real public API to the independently
    # generated production candidate proved over all 512 bindings by the pass CI.
    fifo = first / "fifo-enabled/generated.v"
    assert fifo.read_bytes() == (first / "fifo-canonical-six/generated.v").read_bytes()
    fifo_six_report = json.loads((first / "fifo-canonical-six/report.json").read_text())
    assert fifo_six_report["executed_passes"] == [
        "wire-alias-unnamed", "wire-alias-named", "wire-expression-unnamed",
        "wire-expression-named", "constant-operand-simplification",
        "boolean-ternary-simplification"
    ]
    assert fifo_six_report["common_flag_enabled"] is True
    assert fifo_six_report["executed_before_name_allocation"] is True
    assert fifo_six_report["actual_rhs_capture_writeback"] is True
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
    nested_qualification = qualify_nested_unsigned_sums(output, first)
    assert named_qualification["four_state_cases"] == 2048
    assert named_qualification["sequential_equivalence_cases"] == 32
    assert named_qualification["functional_mutations_rejected"] == 2
    assert nested_qualification["four_state_cases"] == 4608
    assert nested_qualification["formal_equivalence_cases"] == 18
    assert nested_qualification["functional_mutations_rejected"] == 1
    total_four_state = 16 + named_qualification["four_state_cases"] + \
        nested_qualification["four_state_cases"]
    total_equivalence = 1 + named_qualification["sequential_equivalence_cases"] + \
        nested_qualification["formal_equivalence_cases"]
    total_mutations = 1 + named_qualification["functional_mutations_rejected"] + \
        nested_qualification["functional_mutations_rejected"]
    report = {"deterministic_files": len(EXPECTED_GENERATED_ARTIFACTS),
              "default_on_matches_enabled": True,
              "explicit_opt_out_byte_identity": True,
              "fifo_matches_full_domain_proof_candidate": True, "four_state_cases": 16,
              "formal_mutation_rejected": True,
              "total_four_state_cases": total_four_state,
              "total_formal_equivalence_cases": total_equivalence,
              "total_functional_mutations_rejected": total_mutations,
              "fifo_sha256": hashlib.sha256(fifo.read_bytes()).hexdigest(),
              "ordinary_named_aliases": named_qualification,
              "nested_unsigned_extended_sum": nested_qualification}
    (output / "qualification.json").write_text(json.dumps(report, indent=2) + "\n")
    print("WA08_PRODUCTION_QUALIFICATION_PASS", json.dumps(report, sort_keys=True))


if __name__ == "__main__":
    main()
