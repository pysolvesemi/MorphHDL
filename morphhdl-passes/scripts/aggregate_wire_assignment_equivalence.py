#!/usr/bin/env python3
"""Fail-closed aggregation of disjoint full-domain proof shards.

A SHARD_PASS is deliberately not qualification. Only this gate may combine
shards into PASS, after checking both repeated runs, exact source/input hashes,
all admitted bindings for every required pass, solver status files, reachable
comparison traces, functional mutation traces, and deterministic artifacts.
No proof property, assumption, clock model or parameter domain is weakened.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path
from typing import Any, Mapping, Sequence

import validate_wire_assignment_equivalence as gate


def require(condition: bool, message: str) -> None:
    if not condition:
        raise gate.ValidationError(message)


def read_text(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as error:
        raise gate.ValidationError(f"missing or unreadable evidence {path}: {error}") from error


def nonempty_traces(work: Path) -> int:
    return sum(1 for path in work.rglob("*.vcd")
               if path.is_file() and not path.is_symlink() and path.stat().st_size > 0)


def check_binding_list(proofs: Any, expected: Sequence[Mapping[str, int]], label: str) -> None:
    require(isinstance(proofs, list) and len(proofs) == len(expected),
            f"{label}: incomplete binding coverage")
    for proof, binding in zip(proofs, expected):
        require(isinstance(proof, dict) and proof.get("binding") == binding
                and proof.get("status") == "PASS"
                and proof.get("comparison_reachable") is True,
                f"{label}: missing, duplicated, reordered or unproven binding {binding}")


def check_legality(evidence: Mapping[str, Any], case: Mapping[str, Any], *, generic: bool = False) -> None:
    expected = {"compile": "PASS", "lint": "PASS", "synthesis": "PASS"}
    required = {"reference": expected, "candidate": expected} if generic else expected
    require(evidence.get("strict") == required, "missing compile, lint or synthesis evidence")
    expected_simulations = [{"binding": dict(sorted(binding.items())), "status": "PASS"}
                            for binding in case["simulations"]]
    require(evidence.get("simulations") == expected_simulations,
            "missing, reordered or failed representative simulations")


def preparation_script(source_top: str, prepared_top: str,
                       binding: Mapping[str, int], stem: str) -> str:
    """The canonical preparation whose output is consumed by SBY."""
    return "\n".join((
        f"read_verilog -defer {stem}.v", *gate.parameter_commands(binding, source_top),
        f"hierarchy -check -top {source_top}", "flatten", "proc", "opt_clean",
        "memory_dff", "memory_collect", "opt_clean", "check -assert",
        f"rename -top {prepared_top}", f"write_rtlil {stem}.il", "",
    ))


def check_prepared_leg(directory: Path, case: Mapping[str, Any], binding: Mapping[str, int],
                       stem: str, digest: str, prepared_top: str) -> None:
    source = directory / f"{stem}.v"
    require(source.is_file() and gate.sha256_file(source) == digest,
            f"{directory}: stale or changed {stem} RTL")
    source_top = case.get(f"{stem}_top", case["reference_top"])
    script = directory / f"prepare-{stem}.ys"
    require(read_text(script) == preparation_script(source_top, prepared_top, binding, stem),
            f"{directory}: changed {stem} preparation or parameter binding")
    rtlil = directory / f"{stem}.il"
    require(rtlil.is_file() and rtlil.stat().st_size > 0,
            f"{directory}: missing prepared {stem} RTLIL")
    expected = {"schema_version": 1, "source_top": source_top,
                "prepared_top": prepared_top, "binding": dict(sorted(binding.items())),
                "source_sha256": digest, "script_sha256": gate.sha256_file(script),
                "rtlil_sha256": gate.sha256_file(rtlil)}
    require(gate.load_json(directory / f"prepare-{stem}-evidence.json") == expected,
            f"{directory}: stale or changed prepared {stem} RTLIL provenance")


def configuration_sections(configuration: str) -> dict[str, list[str]]:
    sections: dict[str, list[str]] = {}
    current = None
    for line in configuration.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("[") and line.endswith("]"):
            require(line not in sections, "duplicate solver configuration section")
            current = sections.setdefault(line, [])
        else:
            require(current is not None, "solver configuration data precedes its section")
            current.append(line)
    return sections


def check_solver_inputs(directory: Path, work: Path, configuration: str) -> None:
    """Bind retained status to SBY's copied inputs, not just neighbouring files.

    Preparation fingerprints are recorded by the trusted workflow immediately
    after Yosys completes. This checks artifact integrity; it is not a solver
    replay or an attestation against deliberately fabricated evidence.
    """
    expected = configuration_sections(configuration)
    actual = configuration_sections(read_text(work / "config.sby"))
    require(actual.keys() == expected.keys() and all(
                actual[key] == value for key, value in expected.items() if key != "[files]"),
            f"{work}: retained solver configuration changed")
    for name in ("reference.il", "candidate.il", "miter.v"):
        copied = work / "src" / name
        require(copied.is_file() and gate.sha256_file(copied) == gate.sha256_file(directory / name),
                f"{work}: stale or changed solver input {name}")


def check_tool_proof(directory: Path, case: Mapping[str, Any], binding: Mapping[str, int],
                     reference_sha: str, candidate_sha: str, *, mutation: bool = False) -> None:
    """Read the actual solver records, rather than accepting an aggregate count."""
    prefix = "Wa03Mutation" if mutation else "Wa03"
    for stem, digest in (("reference", reference_sha), ("candidate", candidate_sha)):
        check_prepared_leg(directory, case, binding, stem, digest,
                           prefix + stem.title() + "Prepared")
    top = "Wa03MutationMiter" if mutation else "Wa03EquivalenceMiter"
    expected_miter = gate.generated_miter(
        case["inputs"], case["outputs"], binding, prefix + "ReferencePrepared",
        prefix + "CandidatePrepared", top, case["clock"], case["reset"], mutation)
    require(read_text(directory / "miter.v") == expected_miter,
            f"{directory}: proof miter or assumptions changed")
    configuration = gate.sby_configuration(
        top, "FAIL" if mutation else "PASS", "bmc" if mutation else "prove",
        "smtbmc yices" if mutation else case.get("engine", "abc pdr"),
        120 if mutation else case.get("timeout_seconds", 600),
        depth=3 if mutation else None)
    require(read_text(directory / "proof.sby") == configuration,
            f"{directory}: solver configuration or clock model changed")
    status = gate.read_sby_status(directory, "proof", "FAIL" if mutation else "PASS")
    check_solver_inputs(directory, status.work_directory, configuration)
    if mutation:
        require(nonempty_traces(status.work_directory) > 0,
                f"{directory}: mutation has no retained counterexample")
        return
    cover = gate.sby_configuration(top, "PASS", "cover", "smtbmc yices", 120, depth=4)
    require(read_text(directory / "reachability.sby") == cover,
            f"{directory}: comparison reachability configuration changed")
    reachability = gate.read_sby_status(directory, "reachability", "PASS")
    check_solver_inputs(directory, reachability.work_directory, cover)
    require(nonempty_traces(reachability.work_directory) > 0,
            f"{directory}: comparison reachability has no retained trace")
    evidence = gate.load_json(directory / "reachability-evidence.json")
    require(evidence.get("status") == "PASS" and evidence.get("comparison_region_reached") is True,
            f"{directory}: comparison region was not reached")


def check_mutation(directory: Path, evidence: Mapping[str, Any], case: Mapping[str, Any],
                   reference_sha: str, candidate_sha: str) -> None:
    binding = gate.parameter_bindings(case["domains"])[0]
    require(evidence.get("status") == "EXPECTED_FAIL" and evidence.get("binding") == binding,
            f"{directory}: functional mutation evidence missing")
    check_tool_proof(directory / "mutation-control", case, binding,
                     reference_sha, candidate_sha, mutation=True)


def native_input_identity(root: Path, manifest_path: Path, registry_path: Path,
                          shared: Mapping[str, Any], slots: Sequence[Mapping[str, Any]],
                          witness: Path) -> dict[str, Any]:
    require(witness.is_file() and witness.name == shared["generated_file_name"],
            "common native pre-pass reference is missing")
    paths = [witness] + [Path(slot["candidate"]) for slot in slots if slot["required"]]
    return {**gate.proof_source_identity(root, manifest_path, registry_path),
            "files": {path.relative_to(root).as_posix(): gate.sha256_file(path) for path in paths}}


def aggregate(root: Path, shard_root: Path, output: Path, shard_count: int,
              manifest: Mapping[str, Any], slots: Sequence[Mapping[str, Any]],
              native_identity: Mapping[str, Any], witness: Path) -> dict[str, Any]:
    require(output != root and root in output.parents
            and output != shard_root and output not in shard_root.parents
            and shard_root not in output.parents,
            "aggregation output must be a separate directory inside the repository")
    gate.clean_output_directory(output)
    require(shard_count > 1, "aggregation requires more than one non-empty proof shard")
    shared = manifest["shared"]
    all_bindings = gate.parameter_bindings(shared["domains"])
    gate.select_proof_bindings(all_bindings, 0, shard_count)
    required_slots = [slot for slot in slots if slot["required"]]
    require(required_slots and all(slot["required"] for slot in slots),
            "qualification requires every declared pass slot; activate pending increments explicitly")
    expected_ids = [slot["pass_id"] for slot in required_slots]
    source = {key: native_identity[key] for key in (
        "source_commit", "manifest_sha256", "signature_registry_sha256")}
    reference_sha = gate.sha256_file(witness)
    candidates = {slot["pass_id"]: gate.sha256_file(Path(slot["candidate"])) for slot in required_slots}
    folders = sorted(path for path in shard_root.iterdir() if path.is_dir())
    require(len(folders) == shard_count, "missing or extra formal shard artifacts")
    seen: set[int] = set()
    coverage = {(run, pass_id): [] for run in ("run-a", "run-b") for pass_id in expected_ids}
    tool_versions = None
    deterministic_sets = []
    for folder in folders:
        require(not folder.is_symlink(), f"{folder}: symlinked shard artifacts are not accepted")
        require(not any(path.is_symlink() for path in folder.rglob("*")),
                f"{folder}: symlinked evidence cannot establish independent repeated runs")
        summary = gate.load_json(folder / "gate-status.json")
        require(summary.get("status") == "SHARD_PASS" and summary.get("determinism_checked") is True,
                f"{folder}: incomplete shard or repeated proof")
        require(all(summary.get(key) == value for key, value in source.items()),
                f"{folder}: stale source, manifest or signature registry")
        require(summary.get("common_reference_sha256") == reference_sha,
                f"{folder}: shard used a different pre-pass reference")
        shard = summary.get("formal_shard", {})
        index = shard.get("index")
        require(shard.get("count") == shard_count, f"{folder}: inconsistent shard count")
        selected = gate.select_proof_bindings(all_bindings, index, shard_count)
        require(index not in seen, f"{folder}: duplicate shard index {index}")
        seen.add(index)
        formal_shard = {"index": index, "count": shard_count,
                        "domain_binding_count": len(all_bindings),
                        "domain_sha256": gate.sha256_bytes(gate.canonical_json(all_bindings).encode("utf-8"))}
        for run_name in ("run-a", "run-b"):
            run = folder / run_name
            suite = gate.load_json(run / "suite-evidence.json")
            require(suite.get("status") == "SHARD_PASS", f"{run}: incomplete suite")
            if tool_versions is None:
                tool_versions = suite.get("tool_versions")
                require(isinstance(tool_versions, dict) and bool(tool_versions), f"{run}: missing tool versions")
            require(suite.get("tool_versions") == tool_versions, f"{run}: tool versions differ across shards")
            witness_root = run / "shared-witness"
            captured = gate.safe_relative_path(witness_root, shared["common_capture"], "common capture")
            require(gate.sha256_file(captured) == reference_sha, f"{run}: changed common reference")
            evidence = gate.load_json(witness_root / "witness-evidence.json")
            require(suite.get("shared_witness") == evidence, f"{run}: conflicting shared evidence")
            check_legality(evidence, shared)
            require(evidence.get("formal_shard") == formal_shard
                    and evidence.get("domain_audit") == gate.audit_shared_domain(shared),
                    f"{run}: admitted parameter domain or shard changed")
            pass_evidence = evidence.get("future_pass_slots", [])
            require([item.get("pass_id") for item in pass_evidence] == expected_ids,
                    f"{run}: missing, extra or reordered pass slots")
            for slot, item in zip(required_slots, pass_evidence):
                pass_id = slot["pass_id"]
                directory = witness_root / "future-pass-formal" / slot["directory_name"]
                require(gate.load_json(directory / "pass-evidence.json") == item,
                        f"{directory}: conflicting pass evidence")
                require(item.get("status") == "SHARD_PASS" and item.get("complete_domain") is False
                        and item.get("formal_shard") == formal_shard
                        and item.get("activation_item") == slot["activation_item"]
                        and item.get("binding_count") == len(selected)
                        and item.get("required_binding_count") == len(all_bindings)
                        and item.get("common_reference_sha256") == reference_sha
                        and item.get("candidate_sha256") == candidates[pass_id],
                        f"{directory}: incomplete or inconsistent proof metadata")
                check_binding_list(item.get("proofs"), selected, str(directory))
                for proof, binding in zip(item["proofs"], selected):
                    binding_directory = directory / gate.binding_key(binding)
                    require(gate.load_json(binding_directory / "binding-evidence.json") == proof,
                            f"{binding_directory}: missing or inconsistent binding ledger")
                    check_tool_proof(binding_directory, shared, binding, reference_sha, candidates[pass_id])
                    coverage[run_name, pass_id].append(gate.binding_key(binding))
                check_mutation(directory, item.get("mutation_control", {}), shared,
                               reference_sha, candidates[pass_id])
            generic = suite.get("generic_cases", [])
            require([item.get("id") for item in generic] == [case["id"] for case in manifest["cases"]],
                    f"{run}: missing generic proof cases")
            for case, item in zip(manifest["cases"], generic):
                require(gate.load_json(run / "generic" / case["id"] / "case-evidence.json") == item,
                        f"{run}: conflicting generic case evidence")
                check_legality(item, case, generic=True)
                bindings = gate.parameter_bindings(case["domains"])
                formal = item.get("formal", {})
                require(formal.get("complete_domain") is True and formal.get("binding_count") == len(bindings),
                        f"{run}: incomplete generic domain")
                check_binding_list(formal.get("proofs"), bindings, case["id"])
                for binding in bindings:
                    check_tool_proof(run / "generic" / case["id"] / "formal" / gate.binding_key(binding),
                                     case, binding, gate.sha256_file(case["reference"]),
                                     gate.sha256_file(case["candidate"]))
            for directory, key, case in (
                (run, "mutation_control", manifest["cases"][0]),
                (run / "sequential-control", "sequential_mutation_control",
                 next(case for case in manifest["cases"] if case["clock"] is not None)),
            ):
                check_mutation(directory, suite.get(key, {}), case,
                               gate.sha256_file(case["reference"]), gate.sha256_file(case["candidate"]))
        stored = gate.load_json(folder / "determinism.json")
        recomputed = gate.compare_deterministic_runs(folder / "run-a", folder / "run-b",
                                                    output / f"shard-{index}-determinism.json")
        require(stored == recomputed and stored.get("runs_identical") is True,
                f"{folder}: repeated-proof artifact signatures changed")
        deterministic_sets.append({"index": index, **stored})
        print(f"Validated formal shard {index + 1}/{shard_count}", flush=True)
    require(seen == set(range(shard_count)), "missing shard indices")
    expected_keys = sorted(gate.binding_key(binding) for binding in all_bindings)
    for key, observed in coverage.items():
        require(sorted(observed) == expected_keys and len(set(observed)) == len(all_bindings),
                f"{key}: full domain is not an exact disjoint union")
    result = {"schema_version": 1, "status": "PASS", **source,
              "shard_count": shard_count, "complete_domain": True, "repeated_runs": 2,
              "binding_count_per_pass": len(all_bindings), "pass_count": len(required_slots),
              "equivalence_proof_count": 2 * len(required_slots) * len(all_bindings),
              "comparison_reachability_proof_count": 2 * len(required_slots) * len(all_bindings),
              "common_reference_sha256": reference_sha, "candidate_sha256": candidates,
              "tool_versions": tool_versions, "exact_disjoint_coverage": True,
              "all_mutations_detected": True, "determinism_checked": True,
              "deterministic_shards": sorted(deterministic_sets, key=lambda item: item["index"])}
    gate.write_json(output / "gate-status.json", result)
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--shards", type=Path)
    parser.add_argument("--shard-count", type=int, default=16)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--shared-witness", type=Path)
    parser.add_argument("--native-inputs", type=Path)
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument("--capture-native-inputs", action="store_true")
    modes.add_argument("--verify-native-inputs", action="store_true")
    parser.add_argument("--prove-pending", action="append", default=[])
    args = parser.parse_args()
    try:
        root = args.repo_root.resolve()
        if args.output is not None and not args.capture_native_inputs and not args.verify_native_inputs:
            stale_status = args.output.resolve() / "gate-status.json"
            require(stale_status.parent != root and root in stale_status.parent.parents,
                    "aggregation output must be inside the repository")
            stale_status.unlink(missing_ok=True)
        manifest_path = root / "morphhdl-passes/tests/formal/wire_assignment_ir/manifest.json"
        registry = root / "morphhdl-passes/tests/formal_model/wire_assignment_ir/expected-signatures.json"
        manifest = gate.validate_manifest(root, manifest_path, gate.load_json(manifest_path))
        gate.verify_signature_registry(root, registry)
        completion = gate.roadmap_completion(root / "morphhdl-passes/morphhdl-ir-wire-assignment-passes-todo.md")
        slots = gate.plan_shared_slots(manifest["shared"], completion, args.prove_pending)
        witness = (args.shared_witness or root / "morphhdl-passes/build/formal/wire_assignment_ir/generated/parameterized_stream_fifo.v").resolve()
        native_path = (args.native_inputs or root / "morphhdl-passes/build/native-proof-inputs.json").resolve()
        identity = native_input_identity(root, manifest_path, registry, manifest["shared"], slots, witness)
        if args.capture_native_inputs:
            gate.write_json(native_path, identity)
            print("Native proof inputs bound to exact source and file hashes")
            return 0
        require(gate.load_json(native_path) == identity, "native proof artifacts belong to different source or inputs")
        if args.verify_native_inputs:
            print("Exact-source native proof input hashes verified")
            return 0
        require(args.shards is not None and args.output is not None, "--shards and --output are required")
        result = aggregate(root, args.shards.resolve(), args.output.resolve(), args.shard_count,
                           manifest, slots, identity, witness)
        print(f"WA07A_FULL_DOMAIN_PASS: {result['pass_count']} passes, "
              f"{result['binding_count_per_pass']} bindings each, both repeated runs")
        return 0
    except (gate.ValidationError, OSError, KeyError, TypeError, ValueError) as error:
        print(f"WA-07a formal aggregation failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
