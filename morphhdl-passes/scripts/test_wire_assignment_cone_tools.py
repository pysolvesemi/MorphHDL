#!/usr/bin/env python3
"""Real-tool controls for cone proof semantics; mocked results never qualify."""
from __future__ import annotations

import json
from pathlib import Path
import re
from unittest.mock import patch

import prove_wire_assignment_cones as cones


TOP = "ConeControl"
PHASE = """
  reg [1:0] phase = 0;
  always @($global_clock) if (phase != 2) phase <= phase + 1'b1;
  always @* begin
    if (phase == 0) begin assume(!clk); assume(reset); end
    if (phase == 1) begin assume(clk); assume(reset); end
  end
"""


def miter(body: str, *, clocked: bool = True) -> str:
    return (f"module {TOP}(input clk, input reset, input data);\n"
            + (PHASE if clocked else "") + body + "\nendmodule\n")


def prepare(directory: Path) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    # Standalone controls use the exact production compiler/solver path. The
    # imported modules are intentionally empty: all control logic is the miter.
    for name in ("reference", "candidate"):
        (directory / f"{name}.il").write_text(
            f"autoidx 1\nmodule \\{name}Unused\nend\n", encoding="utf-8")


def prove(directory: Path, source: str, count: int = 1, *, assumptions: int | None = None) -> dict:
    prepare(directory)
    result = cones.run_proof(directory, TOP, source, count, 60,
                             expected_assumption_count=assumptions)
    assert result["status"] == "PASS", result
    validated = cones.validate_proof(directory, TOP, source, count, 60,
                                     expected_assumption_count=assumptions)
    assert validated["status"] == "PASS", validated
    return result


def require_counterexample(directory: Path, source: str, count: int = 1) -> dict:
    prepare(directory)
    try:
        cones.run_proof(directory, TOP, source, count, 60)
    except cones.ConeProofError as error:
        matches = []
        for logfile in sorted((directory / "cone-proof").rglob("*.log")):
            text = logfile.read_text(encoding="utf-8", errors="replace")
            found = re.findall(r"Output\s+(\d+)\s+of miter.*?was asserted in frame\s+(\d+)", text)
            matches.extend({"log": str(logfile.relative_to(directory)),
                            "output": int(output), "frame": int(frame)}
                           for output, frame in found)
        if not matches:
            compile_log = directory / "cone-proof" / "compile.log"
            tail = ("\n".join(compile_log.read_text(encoding="utf-8", errors="replace").splitlines()[-30:])
                    if compile_log.is_file() else "<compile log was not created>")
            raise AssertionError(
                f"control failed without an actual solver counterexample: {error}\n"
                f"Retained compiler log: {compile_log}\n{tail}"
            ) from error
        return {"status": "EXPECTED_FAIL", "counterexamples": matches}
    raise AssertionError("reachable counterexample was incorrectly proved safe")


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    output = root / "morphhdl-passes/build/wa07a-cone-tools"
    if output.exists():
        import shutil
        shutil.rmtree(output)
    output.mkdir(parents=True)
    evidence = {"status": "PASS", "controls": {}}
    controls = evidence["controls"]

    # A real reset edge initializes q. Holding the clock high afterward must
    # prevent incrementing, even though the global formal time keeps advancing.
    clock_sensitive = miter("""
  reg [1:0] q;
  always @(posedge clk) if (reset) q <= 0; else q <= q + 1'b1;
  always @* if (phase == 2) begin
    assume(clk); assume(!reset); assert(q == 0);
  end
""")
    controls["explicit_clock"] = prove(output / "explicit-clock", clock_sensitive)

    # Different data inputs keep these two state cones distinct before
    # canonicalization. Both still require a temporal PDR proof. GIA snapshots
    # must permit exact-byte proof reuse and remain identical across runs.
    isomorphic = miter("""
  reg [1:0] a;
  reg [1:0] b;
  always @(posedge clk) begin
    if (reset) begin a <= 0; b <= 0; end
    else begin a <= a + data; b <= b + other; end
  end
  always @* if (phase == 2) begin
    assume(clk); assume(!reset);
    assert(a == 0); assert(b == 0);
  end
""").replace("input data)", "input data, input other)")
    reused = prove(output / "isomorphic-snapshots", isomorphic, 2)
    assert [row["representative_index"] for row in reused["properties"]] == [0, 0], reused
    assert len(reused["unique_proofs"]) == 1, reused
    assert reused["unique_proofs"][0]["proof_method"] == "abc-pdr", reused
    assert reused["unique_proofs"][0]["verified_invariant"] is True, reused
    repeated = prove(output / "isomorphic-snapshots-repeat", isomorphic, 2)
    assert repeated == reused, "canonical snapshot evidence changed across identical runs"
    controls["isomorphic_snapshot_reuse"] = reused
    controls["isomorphic_snapshot_repeat"] = repeated

    original_compile = cones.compile_script

    # Preserve every formal statement even when its condition is repeated.
    # Yosys 0.41 emits $check cells and copies their attributes when lowering
    # them; omitting keep on that representation lets opt_merge lose coverage.
    duplicate_formal = miter("""
  always @* begin assume(data); assume(data); assert(data); assert(data); end
""", clocked=False)
    controls["duplicate_formal_properties"] = prove(
        output / "duplicate-formal-properties", duplicate_formal, 2, assumptions=2)
    # Pinned ABC PDR reports UNDECIDED for an already constant-false output.
    # Require the separate exact structural certificate, and ensure constant
    # true still reaches the solver and produces a real counterexample.
    controls["constant_false_output"] = prove(
        output / "constant-false-output", miter("always @* assert(1);", clocked=False))
    constant_proof = controls["constant_false_output"]["unique_proofs"]
    assert len(constant_proof) == 1
    assert constant_proof[0]["proof_method"] == "constant-false", constant_proof
    assert constant_proof[0]["verified_invariant"] is False
    controls["constant_true_output"] = require_counterexample(
        output / "constant-true-output", miter("always @* assert(0);", clocked=False))

    def missing_formal_keep(top: str) -> str:
        script = original_compile(top)
        keep = "setattr -set keep 1 t:$assert t:$assume t:$check\n"
        assert script.count(keep) == 1
        return script.replace(keep, "")

    loss_directory = output / "missing-formal-keep"
    prepare(loss_directory)
    with patch.object(cones, "compile_script", side_effect=missing_formal_keep):
        try:
            cones.run_proof(loss_directory, TOP, duplicate_formal, 2, 60,
                            expected_assumption_count=2)
        except cones.ConeProofError as error:
            assert str(error) == "full AIG assertion/assumption counts do not match the complete miter", error
            header = cones.aiger_header(loss_directory / "cone-proof" / "full.aig")
            assert header["B"] == 1 and header["C"] == 1, header
            controls["missing_formal_keep"] = {
                "status": "REJECTED_PROPERTY_LOSS", "full_aiger_header": header}
        else:
            raise AssertionError("merged formal properties incorrectly passed exact coverage")

    def wrong_clock(top: str) -> str:
        script = original_compile(top)
        assert script.count("clk2fflogic") == 1
        # Discard the DUT's explicit edge before normal preparation. Keep
        # clk2fflogic itself: Yosys 0.41 also uses it to lower $check cells,
        # and a frontend error is not the required solver counterexample.
        return script.replace("clk2fflogic", "formalff -clk2ff\nclk2fflogic")

    with patch.object(cones, "compile_script", side_effect=wrong_clock):
        controls["incorrect_clock_lowering"] = require_counterexample(
            output / "incorrect-clock-lowering", clock_sensitive)

    # The phase-0 assumption must constrain history when checking phase 2.
    # Merely masking the current cycle's bad output is not enough.
    temporal = miter("""
  reg remembered;
  always @($global_clock) if (phase == 0) remembered <= data;
  always @* begin
    if (phase == 0) assume(data);
    if (phase == 2) begin assume(!data); assert(remembered); end
  end
""")
    controls["temporal_assumption"] = prove(output / "temporal-assumption", temporal)
    controls["temporal_mutation"] = require_counterexample(
        output / "temporal-mutation", temporal.replace("assert(remembered)", "assert(!remembered)"))

    # Identical transition functions do not imply equal independent initial
    # state. The reset edge deliberately does not initialize this data state.
    independent_init = miter("""
  reg a;
  reg b;
  always @(posedge clk) if (!reset && data) begin a <= data; b <= data; end
  always @* if (phase == 2) assert(a == b);
""")
    controls["independent_initial_state"] = require_counterexample(
        output / "independent-initial-state", independent_init)

    # This is the negation of the unchanged comparison-reachability cover.
    # A counterexample proves that deasserted reset at phase 2 is reachable.
    reachable = miter("  always @* if (phase == 2) assert(reset);\n")
    controls["comparison_reachable"] = require_counterexample(
        output / "comparison-reachable", reachable)
    contradictory = reachable.replace("assume(!clk);", "assume(!clk); assume(clk);")
    try:
        require_counterexample(output / "contradictory-clock-assumption", contradictory)
    except AssertionError as error:
        assert str(error) == "reachable counterexample was incorrectly proved safe", error
        controls["contradictory_clock_assumption"] = {"status": "REJECTED_UNREACHABLE"}
    else:
        raise AssertionError("contradictory clock assumptions did not trip the reachability requirement")

    # The complete bad-property list matters: proving the first property must
    # not hide a later failing bit or property.
    two_properties = miter("""
  always @* begin assert(data == data); assert(data); end
""", clocked=False)
    controls["later_property_mutation"] = require_counterexample(
        output / "later-property-mutation", two_properties, 2)

    (output / "cone-tool-evidence.json").write_text(
        json.dumps(evidence, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print("WA07A_CONE_TOOLS_PASS: clock, temporal constraints, independent init, reachability, all properties")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
