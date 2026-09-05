#!/usr/bin/env python3
"""Real-tool control for the shard aggregator on a tiny sequential fixture.

This deliberately isolated two-binding fixture is NOT shared-FIFO qualification.
It checks that real solver evidence, not only mocked metadata, can be aggregated
and that a solver-status mutation cannot be hidden by a passing summary.
"""
from pathlib import Path
import subprocess

import aggregate_wire_assignment_equivalence as aggregate
import validate_wire_assignment_equivalence as gate


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    manifest_path = root / "morphhdl-passes/tests/formal/wire_assignment_ir/manifest.json"
    original = gate.validate_manifest(root, manifest_path, gate.load_json(manifest_path))
    case = next(case for case in original["cases"] if case["clock"] is not None)
    work = root / "morphhdl-passes/build/wa07a-shard-tool-control"
    gate.clean_output_directory(work)
    witness = work / "parameterized_stream_fifo.v"
    gate.copy_text(case["reference"], witness)
    candidate = work / "identity-candidate.v"
    gate.copy_text(case["reference"], candidate)
    shared = dict(original["shared"], domains={"WIDTH": (1, 3)},
                  reference_top=case["reference_top"], inputs=case["inputs"], outputs=case["outputs"],
                  simulations=(), future_outputs=({"pass_id": "aggregation-control-only",
                      "activation_item": "WA-07a", "candidate": str(candidate)},))
    # The helper consumes already validated objects. This control substitutes a
    # separate, explicit tiny fixture; it never rewrites the real manifest.
    manifest = dict(original, shared=shared, cases=tuple(
        dict(item, domains={"WIDTH": (1,)}, simulations=()) for item in original["cases"]))
    toolchain = gate.require_toolchain(work)
    identity = {"source_commit": subprocess.check_output(("git", "rev-parse", "HEAD"),
                    cwd=root, text=True).strip(),
                "manifest_sha256": gate.sha256_bytes(b"isolated sequential shard tool control: WIDTH=1,3"),
                "signature_registry_sha256": gate.sha256_file(root /
                    "morphhdl-passes/tests/formal_model/wire_assignment_ir/expected-signatures.json")}
    for index in range(2):
        folder = work / "shards" / f"shard-{index}"
        for run_name in ("run-a", "run-b"):
            gate.execute_suite(root, manifest, witness, folder / run_name, toolchain,
                               1, ["WA-07a"], index, 2)
        gate.compare_deterministic_runs(folder / "run-a", folder / "run-b", folder / "determinism.json")
        gate.write_json(folder / "gate-status.json", {"status": "SHARD_PASS", **identity,
            "formal_shard": {"index": index, "count": 2}, "determinism_checked": True,
            "common_reference_sha256": gate.sha256_file(witness)})
    completion = gate.roadmap_completion(root / "morphhdl-passes/morphhdl-ir-wire-assignment-passes-todo.md")
    slots = gate.plan_shared_slots(shared, completion, ["WA-07a"])
    result = aggregate.aggregate(root, work / "shards", work / "positive", 2,
                                 manifest, slots, identity, witness)
    assert result["equivalence_proof_count"] == 4 and result["complete_domain"] is True
    changed = work / "shards/shard-1/run-b/shared-witness/future-pass-formal/aggregation-control-only/WIDTH-3/proof/status"
    before = changed.read_bytes()
    changed.write_text("UNKNOWN 0 1\n")
    try:
        aggregate.aggregate(root, work / "shards", work / "negative", 2,
                            manifest, slots, identity, witness)
    except gate.ValidationError as error:
        assert "expected PASS, observed UNKNOWN" in str(error), str(error)
        assert not (work / "negative/gate-status.json").exists()
    else:
        raise AssertionError("a failed proof was hidden by a successful shard summary")
    finally:
        changed.write_bytes(before)
    gate.write_json(work / "tool-control-evidence.json", {
        "status": "PASS", "scope": "isolated sequential aggregation control; NOT shared-FIFO qualification",
        "positive_equivalence_proofs": 4, "solver_status_mutation": "REJECTED",
        "both_repeated_runs_checked": True, "tool_versions": dict(toolchain.versions)})
    print("WA07A_SHARD_TOOL_CONTROL_PASS: real repeated proofs accepted, hidden solver failure rejected")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
