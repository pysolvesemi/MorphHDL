#!/usr/bin/env python3
"""Tool-backed positive/negative controls for non-vacuous clocked equivalence."""
from __future__ import annotations

from pathlib import Path
from unittest.mock import patch

import validate_wire_assignment_equivalence as gate


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    manifest_path = root / "morphhdl-passes/tests/formal/wire_assignment_ir/manifest.json"
    manifest = gate.validate_manifest(root, manifest_path, gate.load_json(manifest_path))
    case = next(item for item in manifest["cases"] if item["clock"] is not None)
    binding = gate.parameter_bindings(case["domains"])[0]
    output = root / "morphhdl-passes/build/wa07a-clock-model"
    gate.clean_output_directory(output)

    def prove(directory: Path):
        return gate.run_formal_binding(
            case["reference"], case["candidate"], case["reference_top"], case["candidate_top"],
            case["inputs"], case["outputs"], binding, case["clock"], case["reset"],
            "abc pdr", 120, directory,
        )

    positive = prove(output / "correct-clock-model")
    assert positive["status"] == "PASS" and positive["comparison_reachable"] is True
    functional_mutation = gate.run_mutation_control(case, output / "functional-mutation")
    assert functional_mutation["status"] == "EXPECTED_FAIL"

    configuration = gate.sby_configuration

    def unsafe_configuration(*args, **kwargs):
        value = configuration(*args, **kwargs)
        assert "multiclock on" in value
        return value.replace("multiclock on", "multiclock off")

    negative = output / "incorrect-clock-model"
    with patch.object(gate, "sby_configuration", side_effect=unsafe_configuration):
        try:
            prove(negative)
        except gate.ValidationError:
            status_file = negative / "reachability/status"
            assert status_file.is_file(), "clock mutation failed before the reachability solver ran"
            assert status_file.read_text().split()[0] == "FAIL", status_file.read_text()
            assert not (negative / "proof.sby").exists(), "unsafe equivalence ran before reachability was proven"
        else:
            raise AssertionError("single-clock abstraction incorrectly passed an explicit-edge reset model")

    gate.write_json(output / "clock-model-evidence.json", {
        "status": "PASS", "binding": binding, "correct_clock_model": positive,
        "functional_mutation": functional_mutation,
        "incorrect_clock_model": "REJECTED_UNREACHABLE",
        "comparison_reachability_is_required": True,
    })
    print("WA07A_CLOCK_MODEL_PASS: reachable comparison, functional mutation and unsafe-clock rejection")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
