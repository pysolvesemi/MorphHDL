#!/usr/bin/env python3
"""WA-03 fail-closed equivalence, tool, domain, mutation and determinism gate."""

from __future__ import annotations

import argparse
import hashlib
import itertools
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from concurrent.futures import FIRST_COMPLETED, ThreadPoolExecutor, wait
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Iterable, Mapping, Sequence, TypeVar


IDENTIFIER = re.compile(r"^[A-Za-z_][A-Za-z0-9_$]*$")
ROADMAP_ITEM = re.compile(r"^- \[(?P<checked>[ xX])\] \*\*(?P<id>WA-[0-9]+[a-z]?)\s+—", re.MULTILINE)
SHA256 = re.compile(r"^[0-9a-f]{64}$")
DETERMINISTIC_SUFFIXES = {".json", ".sby", ".v", ".ys", ".args"}
PASS_MARKER = "WA03_SIM_PASS"
FAIL_MARKER = "WA03_SIM_FAIL"
ItemT = TypeVar("ItemT")
ResultT = TypeVar("ResultT")


class ValidationError(RuntimeError):
    """One stable validation failure that must stop the gate."""


@dataclass(frozen=True)
class Toolchain:
    versions: Mapping[str, str]


@dataclass(frozen=True)
class CommandResult:
    returncode: int
    output: str


@dataclass(frozen=True)
class ProofStatus:
    status: str
    status_file: Path
    work_directory: Path


def canonical_json(value: Any) -> str:
    return json.dumps(value, indent=2, sort_keys=True, separators=(",", ": ")) + "\n"


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    return sha256_bytes(path.read_bytes())


def safe_relative_path(root: Path, value: str, label: str) -> Path:
    if not isinstance(value, str) or not value.strip():
        raise ValidationError(f"{label} must be a non-empty relative path")
    candidate = Path(value)
    if candidate.is_absolute() or ".." in candidate.parts:
        raise ValidationError(f"{label} must stay inside its declared root: {value!r}")
    resolved = (root / candidate).resolve()
    try:
        resolved.relative_to(root.resolve())
    except ValueError as error:
        raise ValidationError(f"{label} escapes its declared root: {value!r}") from error
    return resolved


def require_identifier(value: Any, label: str) -> str:
    if not isinstance(value, str) or IDENTIFIER.fullmatch(value) is None:
        raise ValidationError(f"{label} is not a strict Verilog identifier: {value!r}")
    return value


def require_positive_integer(value: Any, label: str) -> int:
    if not isinstance(value, int) or isinstance(value, bool) or value < 1:
        raise ValidationError(f"{label} must be a positive integer, observed {value!r}")
    return value


def run_bounded_ordered(
    items: Sequence[ItemT], jobs: int, task: Callable[[ItemT], ResultT]
) -> list[ResultT]:
    """Run independent proofs with bounded workers, returning manifest order.

    At most ``jobs`` tasks are submitted at once. Completed failures are checked
    before collecting successes or submitting replacements, so an earlier slow
    binding cannot hide a later failure behind manifest-order result collection.
    A task exception propagates after already-running tasks have been drained;
    it is never converted to a successful result.
    Each task owns its binding-specific directory and uses subprocess cwd,
    never a process-wide chdir. One worker is the serial reference behavior.
    """
    require_positive_integer(jobs, "formal jobs")
    if jobs == 1:
        return [task(item) for item in items]
    with ThreadPoolExecutor(max_workers=jobs) as executor:
        pending = {}
        results = {}
        next_index = 0
        try:
            while next_index < len(items) or pending:
                while next_index < len(items) and len(pending) < jobs:
                    # Recheck between submissions: a completed failure must be
                    # observed before filling a free worker with another proof.
                    if any(future.done() for future in pending):
                        break
                    future = executor.submit(task, items[next_index])
                    pending[future] = next_index
                    next_index += 1
                wait(pending, return_when=FIRST_COMPLETED)
                ready = sorted(
                    (future for future in pending if future.done()),
                    key=pending.__getitem__,
                )
                for future in ready:
                    if future.exception() is not None:
                        future.result()  # Re-raise the original worker failure.
                for future in ready:
                    results[pending.pop(future)] = future.result()
            return [results[index] for index in range(len(items))]
        finally:
            # Running subprocess owners finish normally before executor exit.
            # Work that has not started is cancelled; no replacement is queued.
            for future in pending:
                future.cancel()


def select_proof_bindings(
    bindings: Sequence[dict[str, int]], shard_index: int = 0, shard_count: int = 1
) -> tuple[dict[str, int], ...]:
    """Partition the unchanged admitted domain; a partition is never a full proof.

    Striding balances widths/depths without sampling, changing a parameter bound,
    or making two workers own the same binding. The aggregation gate must check
    the exact disjoint union before publishing complete-domain success.
    """
    require_positive_integer(shard_count, "formal shard count")
    if (not isinstance(shard_index, int) or isinstance(shard_index, bool)
            or not 0 <= shard_index < shard_count):
        raise ValidationError("formal shard index must be in [0, shard count)")
    if not bindings or shard_count > len(bindings):
        raise ValidationError("formal shards must be non-empty")
    return tuple(bindings[shard_index::shard_count])


def proof_source_identity(repo_root: Path, manifest_path: Path, registry_path: Path) -> dict[str, str]:
    """Bind retained proof artifacts to an exact checkout and reviewed inputs."""
    completed = subprocess.run(("git", "rev-parse", "HEAD"), cwd=repo_root,
                               check=True, capture_output=True, text=True)
    commit = completed.stdout.strip()
    if re.fullmatch(r"[0-9a-f]{40}", commit) is None:
        raise ValidationError("proof source has no exact Git commit identity")
    return {"source_commit": commit, "manifest_sha256": sha256_file(manifest_path),
            "signature_registry_sha256": sha256_file(registry_path)}


def load_json(path: Path) -> Any:
    try:
        with path.open(encoding="utf-8") as handle:
            return json.load(handle)
    except (OSError, json.JSONDecodeError) as error:
        raise ValidationError(f"unable to load JSON {path}: {error}") from error


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(canonical_json(value), encoding="utf-8")


def validate_domain_map(value: Any, label: str) -> dict[str, tuple[int, ...]]:
    if not isinstance(value, dict) or not value:
        raise ValidationError(f"{label} must contain at least one parameter domain")
    result: dict[str, tuple[int, ...]] = {}
    for name in sorted(value):
        require_identifier(name, f"{label} parameter")
        raw_values = value[name]
        if not isinstance(raw_values, list) or not raw_values:
            raise ValidationError(f"{label}.{name} must be a non-empty admitted-value list")
        if any(not isinstance(item, int) or isinstance(item, bool) for item in raw_values):
            raise ValidationError(f"{label}.{name} contains a non-integer value")
        if len(set(raw_values)) != len(raw_values):
            raise ValidationError(f"{label}.{name} contains duplicate admitted values")
        if raw_values != sorted(raw_values):
            raise ValidationError(f"{label}.{name} must be in deterministic ascending order")
        result[name] = tuple(raw_values)
    return result


def parameter_bindings(domains: Mapping[str, Sequence[int]]) -> tuple[dict[str, int], ...]:
    names = tuple(sorted(domains))
    products = itertools.product(*(domains[name] for name in names))
    return tuple(dict(zip(names, values)) for values in products)


def binding_key(binding: Mapping[str, int]) -> str:
    return "__".join(f"{name}-{binding[name]}" for name in sorted(binding))


def validate_binding(
    binding: Any,
    domains: Mapping[str, Sequence[int]],
    label: str,
) -> dict[str, int]:
    if not isinstance(binding, dict) or set(binding) != set(domains):
        raise ValidationError(
            f"{label} must bind exactly {sorted(domains)}, observed {sorted(binding) if isinstance(binding, dict) else binding!r}"
        )
    result: dict[str, int] = {}
    for name in sorted(domains):
        value = binding[name]
        if not isinstance(value, int) or isinstance(value, bool):
            raise ValidationError(f"{label}.{name} must be an integer")
        if value not in domains[name]:
            raise ValidationError(
                f"{label}.{name}={value} is outside admitted values {list(domains[name])}"
            )
        result[name] = value
    return result


def referenced_width_parameter(width: Any, label: str) -> str | None:
    if isinstance(width, int) and not isinstance(width, bool):
        if width < 1:
            raise ValidationError(f"{label} literal width must be positive")
        return None
    if not isinstance(width, dict) or set(width) != {"parameter"}:
        raise ValidationError(
            f"{label} width must be a positive integer or {{'parameter': NAME}}"
        )
    return require_identifier(width["parameter"], f"{label} width parameter")


def resolve_width(width: Any, binding: Mapping[str, int], label: str) -> int:
    parameter = referenced_width_parameter(width, label)
    if parameter is None:
        return int(width)
    if parameter not in binding:
        raise ValidationError(f"{label} width parameter {parameter!r} is unbound")
    return require_positive_integer(binding[parameter], f"{label} resolved width")


def validate_ports(
    inputs: Any,
    outputs: Any,
    domains: Mapping[str, Sequence[int]],
    label: str,
) -> tuple[tuple[dict[str, Any], ...], tuple[dict[str, Any], ...]]:
    if not isinstance(inputs, list) or not inputs:
        raise ValidationError(f"{label}.inputs must be a non-empty list")
    if not isinstance(outputs, list) or not outputs:
        raise ValidationError(f"{label}.outputs must be a non-empty list")

    names: set[str] = set()
    validated_inputs: list[dict[str, Any]] = []
    validated_outputs: list[dict[str, Any]] = []
    for direction, raw_ports, destination in (
        ("input", inputs, validated_inputs),
        ("output", outputs, validated_outputs),
    ):
        for index, raw_port in enumerate(raw_ports):
            port_label = f"{label}.{direction}s[{index}]"
            if not isinstance(raw_port, dict):
                raise ValidationError(f"{port_label} must be an object")
            allowed = {"name", "width"} if direction == "input" else {"name", "width", "compare_when"}
            unknown = set(raw_port) - allowed
            if unknown:
                raise ValidationError(f"{port_label} has unknown keys {sorted(unknown)}")
            name = require_identifier(raw_port.get("name"), f"{port_label}.name")
            if name in names:
                raise ValidationError(f"{label} repeats port name {name!r}")
            names.add(name)
            if "width" not in raw_port:
                raise ValidationError(f"{port_label}.width is required")
            parameter = referenced_width_parameter(raw_port["width"], f"{port_label}.width")
            if parameter is not None and parameter not in domains:
                raise ValidationError(
                    f"{port_label}.width references undeclared domain {parameter!r}"
                )
            normalized = {"name": name, "width": raw_port["width"]}
            if direction == "output":
                compare_when = raw_port.get("compare_when", [])
                if not isinstance(compare_when, list) or any(
                    not isinstance(item, str) for item in compare_when
                ):
                    raise ValidationError(f"{port_label}.compare_when must be a string list")
                normalized["compare_when"] = tuple(compare_when)
            destination.append(normalized)

    output_names = {port["name"] for port in validated_outputs}
    for port in validated_outputs:
        for condition in port["compare_when"]:
            if condition not in output_names:
                raise ValidationError(
                    f"{label} output {port['name']!r} compares under unknown output {condition!r}"
                )
    return tuple(validated_inputs), tuple(validated_outputs)


def validate_case(
    fixture_directory: Path,
    raw_case: Any,
    index: int,
) -> dict[str, Any]:
    label = f"cases[{index}]"
    if not isinstance(raw_case, dict):
        raise ValidationError(f"{label} must be an object")
    case_id = raw_case.get("id")
    if not isinstance(case_id, str) or not case_id or re.fullmatch(r"[a-z0-9][a-z0-9-]*", case_id) is None:
        raise ValidationError(f"{label}.id must be a stable lowercase identifier")

    domains = validate_domain_map(raw_case.get("parameter_domains"), f"{label}.parameter_domains")
    inputs, outputs = validate_ports(
        raw_case.get("inputs"), raw_case.get("outputs"), domains, label
    )
    reference = safe_relative_path(fixture_directory, raw_case.get("reference"), f"{label}.reference")
    candidate = safe_relative_path(fixture_directory, raw_case.get("candidate"), f"{label}.candidate")
    testbench = safe_relative_path(fixture_directory, raw_case.get("testbench"), f"{label}.testbench")
    for path in (reference, candidate, testbench):
        if not path.is_file():
            raise ValidationError(f"{label} fixture is missing: {path}")

    simulations = raw_case.get("representative_simulations")
    if not isinstance(simulations, list) or not simulations:
        raise ValidationError(f"{label}.representative_simulations must not be empty")
    validated_simulations = tuple(
        validate_binding(value, domains, f"{label}.representative_simulations[{position}]")
        for position, value in enumerate(simulations)
    )
    if len({binding_key(value) for value in validated_simulations}) != len(validated_simulations):
        raise ValidationError(f"{label}.representative_simulations contains duplicates")

    clock = raw_case.get("clock")
    reset = raw_case.get("reset")
    input_names = {port["name"] for port in inputs}
    if clock is not None and clock not in input_names:
        raise ValidationError(f"{label}.clock must identify an input port")
    if reset is not None and reset not in input_names:
        raise ValidationError(f"{label}.reset must identify an input port")
    if (clock is None) != (reset is None):
        raise ValidationError(f"{label} must declare both clock and reset or neither")

    formal = raw_case.get("formal")
    if not isinstance(formal, dict):
        raise ValidationError(f"{label}.formal must be an object")
    if formal.get("mode") != "prove":
        raise ValidationError(f"{label}.formal.mode must be 'prove'")
    engine = formal.get("engine")
    if not isinstance(engine, str) or not engine.strip():
        raise ValidationError(f"{label}.formal.engine must be non-empty")
    timeout_seconds = require_positive_integer(
        formal.get("timeout_seconds"), f"{label}.formal.timeout_seconds"
    )

    return {
        "id": case_id,
        "reference": reference,
        "candidate": candidate,
        "reference_top": require_identifier(raw_case.get("reference_top"), f"{label}.reference_top"),
        "candidate_top": require_identifier(raw_case.get("candidate_top"), f"{label}.candidate_top"),
        "testbench": testbench,
        "testbench_top": require_identifier(raw_case.get("testbench_top"), f"{label}.testbench_top"),
        "domains": domains,
        "simulations": validated_simulations,
        "inputs": inputs,
        "outputs": outputs,
        "clock": clock,
        "reset": reset,
        "engine": engine,
        "timeout_seconds": timeout_seconds,
    }


def validate_shared_witness(
    repo_root: Path,
    pass_root: Path,
    raw: Any,
) -> dict[str, Any]:
    label = "shared_witness"
    if not isinstance(raw, dict):
        raise ValidationError(f"{label} must be an object")
    domains = validate_domain_map(raw.get("parameter_domains"), f"{label}.parameter_domains")
    inputs, outputs = validate_ports(raw.get("inputs"), raw.get("outputs"), domains, label)
    source_fixture = safe_relative_path(pass_root, raw.get("source_fixture"), f"{label}.source_fixture")
    testbench = safe_relative_path(pass_root, raw.get("testbench"), f"{label}.testbench")
    for path in (source_fixture, testbench):
        if not path.is_file():
            raise ValidationError(f"{label} fixture is missing: {path}")

    simulations = raw.get("representative_simulations")
    if not isinstance(simulations, list) or not simulations:
        raise ValidationError(f"{label}.representative_simulations must not be empty")
    validated_simulations = tuple(
        validate_binding(value, domains, f"{label}.representative_simulations[{position}]")
        for position, value in enumerate(simulations)
    )

    future_outputs = raw.get("future_pass_outputs")
    if not isinstance(future_outputs, list) or not future_outputs:
        raise ValidationError(f"{label}.future_pass_outputs must not be empty")
    normalized_outputs: list[dict[str, str]] = []
    pass_ids: set[str] = set()
    for index, value in enumerate(future_outputs):
        item_label = f"{label}.future_pass_outputs[{index}]"
        if not isinstance(value, dict) or set(value) != {
            "pass_id",
            "activation_item",
            "candidate",
        }:
            raise ValidationError(f"{item_label} has an invalid contract")
        pass_id = value["pass_id"]
        if not isinstance(pass_id, str) or not pass_id.strip() or pass_id in pass_ids:
            raise ValidationError(f"{item_label}.pass_id must be unique and non-empty")
        pass_ids.add(pass_id)
        activation = value["activation_item"]
        if not isinstance(activation, str) or re.fullmatch(r"WA-[0-9]+[a-z]?", activation) is None:
            raise ValidationError(f"{item_label}.activation_item must be a WA roadmap id")
        candidate = safe_relative_path(repo_root, value["candidate"], f"{item_label}.candidate")
        normalized_outputs.append(
            {"pass_id": pass_id, "activation_item": activation, "candidate": str(candidate)}
        )

    clock = raw.get("clock")
    reset = raw.get("reset")
    input_names = {port["name"] for port in inputs}
    if clock not in input_names or reset not in input_names:
        raise ValidationError(f"{label} clock and reset must identify input ports")

    generated_file_name = raw.get("generated_file_name")
    if not isinstance(generated_file_name, str) or Path(generated_file_name).name != generated_file_name:
        raise ValidationError(f"{label}.generated_file_name must be one file name")
    common_capture = raw.get("common_reference_capture")
    if not isinstance(common_capture, str):
        raise ValidationError(f"{label}.common_reference_capture must be a relative path")
    if safe_relative_path(Path("/tmp/wa03-contract-root"), common_capture, f"{label}.common_reference_capture").name != "reference.v":
        raise ValidationError(f"{label}.common_reference_capture must name reference.v")

    return {
        "source_fixture": source_fixture,
        "generated_file_name": generated_file_name,
        "reference_top": require_identifier(raw.get("reference_top"), f"{label}.reference_top"),
        "testbench": testbench,
        "testbench_top": require_identifier(raw.get("testbench_top"), f"{label}.testbench_top"),
        "domains": domains,
        "simulations": validated_simulations,
        "inputs": inputs,
        "outputs": outputs,
        "clock": clock,
        "reset": reset,
        "common_capture": common_capture,
        "future_outputs": tuple(normalized_outputs),
    }


def validate_manifest(repo_root: Path, manifest_path: Path, raw: Any) -> dict[str, Any]:
    if not isinstance(raw, dict) or raw.get("schema_version") != 1:
        raise ValidationError("manifest schema_version must be 1")
    if raw.get("suite_id") != "wire-assignment-ir":
        raise ValidationError("manifest suite_id must be 'wire-assignment-ir'")
    raw_cases = raw.get("cases")
    if not isinstance(raw_cases, list) or len(raw_cases) < 2:
        raise ValidationError("manifest requires combinational and sequential generic cases")
    fixture_directory = manifest_path.parent.resolve()
    cases = tuple(
        validate_case(fixture_directory, raw_case, index)
        for index, raw_case in enumerate(raw_cases)
    )
    case_ids = [value["id"] for value in cases]
    if len(set(case_ids)) != len(case_ids):
        raise ValidationError("manifest case ids must be unique")
    if not any(value["clock"] is None for value in cases):
        raise ValidationError("manifest requires a generic combinational case")
    if not any(value["clock"] is not None for value in cases):
        raise ValidationError("manifest requires a generic sequential case")

    pass_root = repo_root / "morphhdl-passes"
    shared = validate_shared_witness(repo_root, pass_root, raw.get("shared_witness"))
    return {"cases": cases, "shared": shared}


def verify_signature_registry(repo_root: Path, registry_path: Path) -> dict[str, str]:
    raw = load_json(registry_path)
    if not isinstance(raw, dict) or raw.get("schema_version") != 1:
        raise ValidationError("formal-model signature registry schema_version must be 1")
    files = raw.get("files")
    if not isinstance(files, dict) or not files:
        raise ValidationError("formal-model signature registry must contain files")
    normalized: dict[str, str] = {}
    for relative in sorted(files):
        expected = files[relative]
        if not isinstance(expected, str) or SHA256.fullmatch(expected) is None:
            raise ValidationError(f"invalid expected SHA-256 for {relative!r}")
        path = safe_relative_path(repo_root, relative, "signature registry path")
        if not path.is_file():
            raise ValidationError(f"signature registry file is missing: {relative}")
        actual = sha256_file(path)
        if actual != expected:
            raise ValidationError(
                f"formal-model signature mismatch for {relative}: expected {expected}, observed {actual}"
            )
        normalized[relative] = expected
    return normalized


def run_command(
    command: Sequence[str],
    cwd: Path,
    log_path: Path,
    timeout_seconds: int = 300,
) -> CommandResult:
    log_path.parent.mkdir(parents=True, exist_ok=True)
    environment = os.environ.copy()
    environment.update(
        {
            "LC_ALL": "C",
            "LANG": "C",
            "TZ": "UTC",
            "SOURCE_DATE_EPOCH": "0",
        }
    )
    try:
        completed = subprocess.run(
            list(command),
            cwd=str(cwd),
            env=environment,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout_seconds,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        raise ValidationError(f"command failed to execute: {' '.join(command)}: {error}") from error
    output = completed.stdout
    log_path.write_text(
        "$ " + " ".join(command) + "\n" + output,
        encoding="utf-8",
    )
    if completed.returncode != 0:
        raise ValidationError(
            f"command returned {completed.returncode}: {' '.join(command)}\n{output}"
        )
    return CommandResult(completed.returncode, output)


def require_toolchain(work_directory: Path) -> Toolchain:
    commands = {
        "iverilog": ("iverilog", "-V"),
        "verilator": ("verilator", "--version"),
        "yosys": ("yosys", "-V"),
        "sby": ("sby", "-h"),
        "yices-smt2": ("yices-smt2", "--version"),
    }
    for executable in ("iverilog", "vvp", "verilator", "yosys", "sby", "yices-smt2"):
        if shutil.which(executable) is None:
            raise ValidationError(f"required WA-03 tool is unavailable: {executable}")

    versions: dict[str, str] = {}
    version_directory = work_directory / "tool-versions"
    for name, command in commands.items():
        result = run_command(command, work_directory, version_directory / f"{name}.log", 60)
        lines = [line.strip() for line in result.output.splitlines() if line.strip()]
        if not lines:
            raise ValidationError(f"required WA-03 tool returned no version/help text: {name}")
        versions[name] = lines[0]
    return Toolchain(versions)


def copy_text(source: Path, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    try:
        text = source.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError) as error:
        raise ValidationError(f"unable to read UTF-8 fixture {source}: {error}") from error
    destination.write_text(text, encoding="utf-8")


def write_args(path: Path, command: Sequence[str]) -> None:
    path.write_text("\n".join(command) + "\n", encoding="utf-8")


def strict_design_checks(
    source: Path,
    top: str,
    output_directory: Path,
    stem: str,
) -> dict[str, str]:
    directory = output_directory / f"strict-{stem}"
    directory.mkdir(parents=True, exist_ok=True)
    local_source = directory / "design.v"
    copy_text(source, local_source)

    compile_output = directory / "compile.vvp"
    compile_command = (
        "iverilog",
        "-g2001",
        "-s",
        top,
        "-o",
        compile_output.name,
        local_source.name,
    )
    write_args(directory / "compile.args", compile_command)
    run_command(compile_command, directory, directory / "compile.log")

    lint_command = (
        "verilator",
        "--lint-only",
        "--language",
        "1364-2001",
        "-Wno-fatal",
        "-Wno-DECLFILENAME",
        "--top-module",
        top,
        local_source.name,
    )
    write_args(directory / "lint.args", lint_command)
    run_command(lint_command, directory, directory / "lint.log")

    synthesis_script = directory / "synthesis.ys"
    synthesis_script.write_text(
        "\n".join(
            (
                f"read_verilog -defer {local_source.name}",
                f"hierarchy -check -top {top}",
                "proc",
                "opt_clean",
                "memory_dff",
                "memory_collect",
                "opt_clean",
                "check -assert",
                f"synth -top {top}",
                "check -assert",
                "stat",
                "",
            )
        ),
        encoding="utf-8",
    )
    run_command(
        ("yosys", "-q", "-l", "synthesis.log", "-s", synthesis_script.name),
        directory,
        directory / "synthesis-command.log",
        600,
    )
    return {"compile": "PASS", "lint": "PASS", "synthesis": "PASS"}


def simulation(
    sources: Sequence[Path],
    testbench: Path,
    testbench_top: str,
    binding: Mapping[str, int],
    output_directory: Path,
) -> dict[str, Any]:
    directory = output_directory / binding_key(binding)
    directory.mkdir(parents=True, exist_ok=True)
    local_sources: list[Path] = []
    for index, source in enumerate(sources):
        destination = directory / f"source-{index}.v"
        copy_text(source, destination)
        local_sources.append(destination)
    local_testbench = directory / "testbench.v"
    copy_text(testbench, local_testbench)

    executable = directory / "simulation.vvp"
    command: list[str] = [
        "iverilog",
        "-g2001",
        "-s",
        testbench_top,
        "-o",
        executable.name,
    ]
    command.extend(
        f"-P{testbench_top}.{name}={binding[name]}" for name in sorted(binding)
    )
    command.extend(path.name for path in local_sources)
    command.append(local_testbench.name)
    write_args(directory / "compile.args", command)
    run_command(command, directory, directory / "compile.log")
    result = run_command(("vvp", executable.name), directory, directory / "run.log")
    pass_count = result.output.count(PASS_MARKER)
    if FAIL_MARKER in result.output or pass_count != 1:
        raise ValidationError(
            f"simulation did not publish exactly one success marker for {binding_key(binding)}:\n{result.output}"
        )
    return {"binding": dict(sorted(binding.items())), "status": "PASS"}


def parameter_commands(binding: Mapping[str, int], top: str) -> tuple[str, ...]:
    return tuple(f"chparam -set {name} {binding[name]} {top}" for name in sorted(binding))


def prepare_leg(
    source: Path,
    source_top: str,
    prepared_top: str,
    binding: Mapping[str, int],
    directory: Path,
    stem: str,
) -> Path:
    local_source = directory / f"{stem}.v"
    copy_text(source, local_source)
    output = directory / f"{stem}.il"
    script = directory / f"prepare-{stem}.ys"
    lines = [f"read_verilog -defer {local_source.name}"]
    lines.extend(parameter_commands(binding, source_top))
    lines.extend(
        (
            f"hierarchy -check -top {source_top}",
            "flatten",
            "proc",
            "opt_clean",
            "memory_dff",
            "memory_collect",
            "opt_clean",
            "check -assert",
            f"rename -top {prepared_top}",
            f"write_rtlil {output.name}",
            "",
        )
    )
    script.write_text("\n".join(lines), encoding="utf-8")
    run_command(
        ("yosys", "-q", "-l", f"prepare-{stem}.log", "-s", script.name),
        directory,
        directory / f"prepare-{stem}-command.log",
        600,
    )
    if not output.is_file() or output.stat().st_size == 0:
        raise ValidationError(f"Yosys did not publish prepared {stem} RTLIL")
    write_json(directory / f"prepare-{stem}-evidence.json", {
        "schema_version": 1,
        "source_top": source_top,
        "prepared_top": prepared_top,
        "binding": dict(sorted(binding.items())),
        "source_sha256": sha256_file(local_source),
        "script_sha256": sha256_file(script),
        "rtlil_sha256": sha256_file(output),
    })
    return output


def verilog_declaration(direction: str, name: str, width: int) -> str:
    prefix = "  wire" if direction == "wire" else f"  {direction} wire"
    if width == 1:
        return f"{prefix} {name};"
    return f"{prefix} [{width - 1}:0] {name};"


def generated_miter(
    inputs: Sequence[Mapping[str, Any]],
    outputs: Sequence[Mapping[str, Any]],
    binding: Mapping[str, int],
    reference_top: str,
    candidate_top: str,
    miter_top: str,
    clock: str | None,
    reset: str | None,
    mutate_first_output: bool,
) -> str:
    if (clock is None) != (reset is None):
        raise ValidationError("formal miter requires clock and reset together")

    input_declarations = [
        verilog_declaration(
            "input",
            str(port["name"]),
            resolve_width(port["width"], binding, f"input {port['name']}"),
        )
        for port in inputs
    ]
    wire_declarations: list[str] = []
    for port in outputs:
        width = resolve_width(port["width"], binding, f"output {port['name']}")
        wire_declarations.append(
            verilog_declaration("wire", f"reference_{port['name']}", width)
        )
        wire_declarations.append(
            verilog_declaration("wire", f"candidate_{port['name']}", width)
        )

    def instance(top: str, name: str, prefix: str) -> str:
        connections = []
        for port in inputs:
            connections.append(f"    .{port['name']}({port['name']})")
        for port in outputs:
            connections.append(f"    .{port['name']}({prefix}_{port['name']})")
        return f"  {top} {name} (\n" + ",\n".join(connections) + "\n  );"

    assertions: list[str] = []
    for index, port in enumerate(outputs):
        candidate_expression = f"candidate_{port['name']}"
        if mutate_first_output and index == 0:
            candidate_expression = f"({candidate_expression} ^ 1'b1)"
        assertion = f"assert(reference_{port['name']} == {candidate_expression});"
        conditions = tuple(port.get("compare_when", ()))
        if conditions:
            guard_terms = [
                f"reference_{condition} && candidate_{condition}"
                for condition in conditions
            ]
            assertion = f"if ({' && '.join(guard_terms)}) {assertion}"
        assertions.append("      " + assertion)

    body: list[str] = []
    if reset is None:
        body.append("  always @* begin")
        body.append("    cover(1'b1);")
        body.extend(line.replace("      ", "    ", 1) for line in assertions)
        body.append("  end")
    else:
        body.extend(
            (
                "  reg [1:0] wa03_reset_phase;",
                "  initial wa03_reset_phase = 2'd0;",
                "",
                "  always @($global_clock) begin",
                "    if (wa03_reset_phase != 2'd2)",
                "      wa03_reset_phase <= wa03_reset_phase + 1'b1;",
                "  end",
                "",
                "  always @* begin",
                "    if (wa03_reset_phase == 2'd0) begin",
                f"      assume(!{clock});",
                f"      assume({reset});",
                "    end",
                "    if (wa03_reset_phase == 2'd1) begin",
                f"      assume({clock});",
                f"      assume({reset});",
                "    end",
                "    if (wa03_reset_phase == 2'd2) begin",
                f"      cover(!{reset});",
            )
        )
        body.extend(assertions)
        body.extend(("    end", "  end"))

    ports = [str(port["name"]) for port in inputs]
    return (
        f"module {miter_top} (\n"
        + ",\n".join(f"  {name}" for name in ports)
        + "\n);\n"
        + "\n".join(input_declarations)
        + "\n"
        + "\n".join(wire_declarations)
        + "\n\n"
        + instance(reference_top, "reference_dut", "reference")
        + "\n\n"
        + instance(candidate_top, "candidate_dut", "candidate")
        + "\n\n"
        + "\n".join(body)
        + "\nendmodule\n"
    )

def sby_configuration(
    miter_top: str,
    expected: str,
    mode: str,
    engine: str,
    timeout_seconds: int,
    depth: int | None = None,
) -> str:
    # The reset sequencer uses $global_clock while the DUT observes explicit
    # clock edges. Single-clock abstraction can make low/high reset assumptions
    # contradictory. Preserve edges in prove, cover AND mutation configurations.
    depth_line = f"depth {depth}\n" if depth is not None else ""
    return f"""[options]
mode {mode}
{depth_line}expect {expected.lower()}
multiclock on
timeout {timeout_seconds}

[engines]
{engine}

[script]
read_rtlil reference.il
read_rtlil candidate.il
read_verilog -formal miter.v
hierarchy -check -top {miter_top}
prep -top {miter_top}
memory_map
setundef -undriven -anyseq
opt_clean
check -assert

[files]
reference.il
candidate.il
miter.v
"""


def read_sby_status(directory: Path, stem: str, expected: str) -> ProofStatus:
    work = directory / stem
    status_file = work / "status"
    if not status_file.is_file():
        raise ValidationError(f"formal run published no status file: {status_file}")
    lines = [line.strip() for line in status_file.read_text(encoding="utf-8").splitlines() if line.strip()]
    if len(lines) != 1:
        raise ValidationError(f"formal status is ambiguous in {status_file}: {lines}")
    tokens = lines[0].split()
    if not tokens or any(re.fullmatch(r"[0-9]+", token) is None for token in tokens[1:]):
        raise ValidationError(f"formal status is malformed in {status_file}: {lines[0]!r}")
    observed = tokens[0]
    if observed != expected:
        raise ValidationError(
            f"formal proof expected {expected}, observed {observed} in {status_file}"
        )
    return ProofStatus(observed, status_file, work)


def prove_comparison_reachable(directory: Path, miter_top: str) -> None:
    """Require a concrete trace reaching the enabled comparison region.

    In clocked miters this also reaches deasserted reset after an actual reset
    edge. This is mandatory for every binding, not just a representative case.
    An unsatisfiable model or missing trace must never publish equivalence PASS.
    """
    (directory / "reachability.sby").write_text(
        sby_configuration(miter_top, expected="PASS", mode="cover",
                          engine="smtbmc yices", timeout_seconds=120, depth=4),
        encoding="utf-8",
    )
    run_command(("sby", "-f", "-d", "reachability", "reachability.sby"), directory,
                directory / "reachability-command.log", 240)
    status = read_sby_status(directory, "reachability", "PASS")
    traces = [path for path in status.work_directory.rglob("*.vcd")
              if path.is_file() and path.stat().st_size > 0]
    if not traces:
        raise ValidationError("comparison reachability passed without a retained cover trace")
    write_json(directory / "reachability-evidence.json", {
        "status": "PASS", "comparison_region_reached": True,
        "cover_trace_count": len(traces),
    })


def run_formal_binding(
    reference: Path,
    candidate: Path,
    reference_top: str,
    candidate_top: str,
    inputs: Sequence[Mapping[str, Any]],
    outputs: Sequence[Mapping[str, Any]],
    binding: Mapping[str, int],
    clock: str | None,
    reset: str | None,
    engine: str,
    timeout_seconds: int,
    directory: Path,
) -> dict[str, Any]:
    directory.mkdir(parents=True, exist_ok=True)
    prepared_reference = "Wa03ReferencePrepared"
    prepared_candidate = "Wa03CandidatePrepared"
    miter_top = "Wa03EquivalenceMiter"
    prepare_leg(reference, reference_top, prepared_reference, binding, directory, "reference")
    prepare_leg(candidate, candidate_top, prepared_candidate, binding, directory, "candidate")
    miter = generated_miter(
        inputs,
        outputs,
        binding,
        prepared_reference,
        prepared_candidate,
        miter_top,
        clock,
        reset,
        mutate_first_output=False,
    )
    (directory / "miter.v").write_text(miter, encoding="utf-8")
    prove_comparison_reachable(directory, miter_top)
    (directory / "proof.sby").write_text(
        sby_configuration(
            miter_top,
            expected="PASS",
            mode="prove",
            engine=engine,
            timeout_seconds=timeout_seconds,
        ),
        encoding="utf-8",
    )
    run_command(
        ("sby", "-f", "proof.sby"),
        directory,
        directory / "proof-command.log",
        timeout_seconds + 120,
    )
    read_sby_status(directory, "proof", "PASS")
    return {"binding": dict(sorted(binding.items())), "status": "PASS",
            "comparison_reachable": True}

def run_mutation_control(
    case: Mapping[str, Any], output_directory: Path
) -> dict[str, Any]:
    binding = parameter_bindings(case["domains"])[0]
    directory = output_directory / "mutation-control"
    directory.mkdir(parents=True, exist_ok=True)
    prepared_reference = "Wa03MutationReferencePrepared"
    prepared_candidate = "Wa03MutationCandidatePrepared"
    miter_top = "Wa03MutationMiter"
    prepare_leg(
        case["reference"],
        case["reference_top"],
        prepared_reference,
        binding,
        directory,
        "reference",
    )
    prepare_leg(
        case["candidate"],
        case["candidate_top"],
        prepared_candidate,
        binding,
        directory,
        "candidate",
    )
    (directory / "miter.v").write_text(
        generated_miter(
            case["inputs"],
            case["outputs"],
            binding,
            prepared_reference,
            prepared_candidate,
            miter_top,
            case["clock"],
            case["reset"],
            mutate_first_output=True,
        ),
        encoding="utf-8",
    )
    (directory / "proof.sby").write_text(
        sby_configuration(
            miter_top,
            expected="FAIL",
            mode="bmc",
            engine="smtbmc yices",
            timeout_seconds=120,
            depth=3,
        ),
        encoding="utf-8",
    )
    run_command(
        ("sby", "-f", "proof.sby"),
        directory,
        directory / "proof-command.log",
        240,
    )
    status = read_sby_status(directory, "proof", "FAIL")
    traces = [
        path
        for path in status.work_directory.rglob("*.vcd")
        if path.is_file() and path.stat().st_size > 0
    ]
    if not traces:
        raise ValidationError(
            "intentional mutation failed without a retained counterexample VCD"
        )
    return {
        "binding": dict(sorted(binding.items())),
        "status": "EXPECTED_FAIL",
        "counterexample_count": len(traces),
    }

def run_generic_case(
    case: Mapping[str, Any], output_root: Path, formal_jobs: int = 1
) -> dict[str, Any]:
    case_root = output_root / "generic" / case["id"]
    case_root.mkdir(parents=True, exist_ok=True)
    domains = case["domains"]
    all_bindings = parameter_bindings(domains)
    domain_audit = {
        "domains": {name: list(domains[name]) for name in sorted(domains)},
        "binding_count": len(all_bindings),
        "binding_digest": sha256_bytes(
            canonical_json([dict(sorted(value.items())) for value in all_bindings]).encode("utf-8")
        ),
    }
    write_json(case_root / "domain-audit.json", domain_audit)

    strict = {
        "reference": strict_design_checks(
            case["reference"], case["reference_top"], case_root, "reference"
        ),
        "candidate": strict_design_checks(
            case["candidate"], case["candidate_top"], case_root, "candidate"
        ),
    }
    simulations = [
        simulation(
            (case["reference"], case["candidate"]),
            case["testbench"],
            case["testbench_top"],
            binding,
            case_root / "simulation",
        )
        for binding in case["simulations"]
    ]

    def prove(binding: Mapping[str, int]) -> dict[str, Any]:
        proof = run_formal_binding(
            case["reference"],
            case["candidate"],
            case["reference_top"],
            case["candidate_top"],
            case["inputs"],
            case["outputs"],
            binding,
            case["clock"],
            case["reset"],
            case["engine"],
            case["timeout_seconds"],
            case_root / "formal" / binding_key(binding),
        )
        print(f"Proved {case['id']}: {binding_key(binding)} PASS", flush=True)
        return proof

    proofs = run_bounded_ordered(all_bindings, formal_jobs, prove)
    evidence = {
        "id": case["id"],
        "strict": strict,
        "simulations": simulations,
        "formal": {
            "complete_domain": True,
            "binding_count": len(proofs),
            "proofs": proofs,
        },
    }
    write_json(case_root / "case-evidence.json", evidence)
    return evidence


def roadmap_completion(roadmap: Path) -> dict[str, bool]:
    if not roadmap.is_file():
        raise ValidationError(f"wire-assignment roadmap is missing: {roadmap}")
    text = roadmap.read_text(encoding="utf-8")
    values: dict[str, bool] = {}
    for match in ROADMAP_ITEM.finditer(text):
        item = match.group("id")
        if item in values:
            raise ValidationError(f"wire-assignment roadmap repeats item {item}")
        values[item] = match.group("checked").lower() == "x"
    if not values:
        raise ValidationError("wire-assignment roadmap contains no WA checklist items")
    return values


def plan_shared_slots(
    shared: Mapping[str, Any],
    completion: Mapping[str, bool],
    prove_pending: Sequence[str] = (),
) -> tuple[dict[str, Any], ...]:
    """Add explicit pending proofs without changing completion or dropping gates."""
    requested = set(prove_pending)
    if len(requested) != len(prove_pending):
        raise ValidationError("--prove-pending contains duplicate roadmap items")
    slots = shared["future_outputs"]
    activations = {slot["activation_item"] for slot in slots}
    unknown = requested - set(completion)
    if unknown:
        raise ValidationError(f"unknown pending roadmap items: {sorted(unknown)}")
    missing_slots = requested - activations
    if missing_slots:
        raise ValidationError(f"pending roadmap items have no formal slots: {sorted(missing_slots)}")
    result: list[dict[str, Any]] = []
    directory_names: set[str] = set()
    for slot in slots:
        activation = slot["activation_item"]
        if activation not in completion:
            raise ValidationError(f"future pass slot references unknown roadmap item {activation}")
        directory_name = re.sub(r"[^A-Za-z0-9._-]", "_", slot["pass_id"])
        if directory_name in {"", ".", ".."} or directory_name in directory_names:
            raise ValidationError(f"formal pass directories are not unique: {slot['pass_id']}")
        directory_names.add(directory_name)
        required = bool(completion[activation] or activation in requested)
        candidate = Path(slot["candidate"])
        if required and not candidate.is_file():
            raise ValidationError(
                f"activated pass {slot['pass_id']} published no candidate output at {candidate}"
            )
        if not required and candidate.exists():
            raise ValidationError(
                f"inactive future pass slot unexpectedly published an output: {candidate}; "
                f"use --prove-pending {activation} to require its complete proof before completion"
            )
        result.append({
            **slot,
            "required": required,
            "roadmap_completed": bool(completion[activation]),
            "directory_name": directory_name,
        })
    return tuple(result)


def audit_shared_domain(shared: Mapping[str, Any]) -> dict[str, Any]:
    bindings = parameter_bindings(shared["domains"])
    return {
        "domains": {name: list(shared["domains"][name]) for name in sorted(shared["domains"])},
        "binding_count": len(bindings),
        "binding_digest": sha256_bytes(
            canonical_json([dict(sorted(value.items())) for value in bindings]).encode("utf-8")
        ),
        "complete_cartesian_product": True,
    }


def run_shared_witness(
    repo_root: Path,
    shared: Mapping[str, Any],
    witness_path: Path,
    output_root: Path,
    formal_jobs: int = 1,
    prove_pending: Sequence[str] = (),
    shard_index: int = 0,
    shard_count: int = 1,
) -> dict[str, Any]:
    all_bindings = parameter_bindings(shared["domains"])
    selected_bindings = select_proof_bindings(all_bindings, shard_index, shard_count)
    shard = {"index": shard_index, "count": shard_count,
             "domain_binding_count": len(all_bindings),
             "domain_sha256": sha256_bytes(canonical_json(all_bindings).encode("utf-8"))}
    completion = roadmap_completion(
        repo_root / "morphhdl-passes" / "morphhdl-ir-wire-assignment-passes-todo.md"
    )
    planned_slots = plan_shared_slots(shared, completion, prove_pending)
    if not witness_path.is_file():
        raise ValidationError(f"common pre-pass witness was not generated: {witness_path}")
    if witness_path.name != shared["generated_file_name"]:
        raise ValidationError(
            f"common pre-pass witness file name must be {shared['generated_file_name']!r}, observed {witness_path.name!r}"
        )
    witness_root = output_root / "shared-witness"
    capture = safe_relative_path(witness_root, shared["common_capture"], "common reference capture")
    copy_text(witness_path, capture)
    capture_sha = sha256_file(capture)
    write_json(
        witness_root / "capture.json",
        {
            "captured_before_passes": True,
            "file_name": capture.name,
            "sha256": capture_sha,
            "single_common_reference": True,
        },
    )
    domain_audit = audit_shared_domain(shared)
    write_json(witness_root / "domain-audit.json", domain_audit)
    write_json(witness_root / "proof-plan.json", {
        "requested_pending_items": sorted(prove_pending),
        "required_binding_count_per_slot": domain_audit["binding_count"],
        "formal_shard": shard,
        "slots": [
            {key: slot[key] for key in ("pass_id", "activation_item", "required", "roadmap_completed")}
            for slot in planned_slots
        ],
    })

    strict = strict_design_checks(
        capture,
        shared["reference_top"],
        witness_root,
        "common-reference",
    )
    simulations = [
        simulation(
            (capture,),
            shared["testbench"],
            shared["testbench_top"],
            binding,
            witness_root / "simulation",
        )
        for binding in shared["simulations"]
    ]

    future_evidence: list[dict[str, Any]] = []
    for slot in planned_slots:
        activation = slot["activation_item"]
        if not slot["required"]:
            future_evidence.append(
                {
                    "pass_id": slot["pass_id"],
                    "activation_item": activation,
                    "status": "INACTIVE_UNTIL_ROADMAP_COMPLETION",
                    "common_reference_sha256": capture_sha,
                    "required_binding_count": domain_audit["binding_count"],
                }
            )
            continue

        candidate = Path(slot["candidate"])
        candidate_sha = sha256_file(candidate)
        pass_directory = witness_root / "future-pass-formal" / slot["directory_name"]
        mutation_case = dict(shared, reference=capture, candidate=candidate,
                             candidate_top=shared["reference_top"])
        mutation = run_mutation_control(mutation_case, pass_directory)
        print(
            f"Proving {slot['pass_id']}: shard {shard_index + 1}/{shard_count}, "
            f"{len(selected_bindings)}/{len(all_bindings)} bindings, {formal_jobs} workers",
            flush=True,
        )

        def prove(binding: Mapping[str, int]) -> dict[str, Any]:
            proof = run_formal_binding(
                capture,
                candidate,
                shared["reference_top"],
                shared["reference_top"],
                shared["inputs"],
                shared["outputs"],
                binding,
                shared["clock"],
                shared["reset"],
                "abc pdr",
                600,
                pass_directory / binding_key(binding),
            )

            write_json(pass_directory / binding_key(binding) / "binding-evidence.json", proof)
            print(
                f"Proved {slot['pass_id']}: {binding_key(binding)} PASS "
                f"(shard {shard_index + 1}/{shard_count})",
                flush=True,
            )
            return proof

        proofs = run_bounded_ordered(selected_bindings, formal_jobs, prove)
        if len(proofs) != len(selected_bindings) or any(
            proof.get("status") != "PASS" or proof.get("binding") != binding
            or proof.get("comparison_reachable") is not True
            for proof, binding in zip(proofs, selected_bindings)
        ):
            raise ValidationError(f"incomplete or misordered formal evidence for {slot['pass_id']}")
        if sha256_file(capture) != capture_sha or sha256_file(candidate) != candidate_sha:
            raise ValidationError(f"formal input changed during proof of {slot['pass_id']}")
        slot_evidence = {
            "pass_id": slot["pass_id"],
            "activation_item": activation,
            "status": "PASS" if shard_count == 1 else "SHARD_PASS",
            "formal_shard": shard,
            "common_reference_sha256": capture_sha,
            "candidate_sha256": candidate_sha,
            "mutation_control": mutation,
            "binding_count": len(proofs),
            "required_binding_count": len(all_bindings),
            "complete_domain": shard_count == 1,
            "proofs": proofs,
        }
        write_json(pass_directory / "pass-evidence.json", slot_evidence)
        future_evidence.append(slot_evidence)
        print(f"Proved {slot['pass_id']}: {len(proofs)} / {len(all_bindings)} PASS "
              f"(shard {shard_index + 1}/{shard_count})", flush=True)

    evidence = {
        "capture_sha256": capture_sha,
        "formal_shard": shard,
        "domain_audit": domain_audit,
        "strict": strict,
        "simulations": simulations,
        "future_pass_slots": future_evidence,
    }
    write_json(witness_root / "witness-evidence.json", evidence)
    return evidence


def execute_suite(
    repo_root: Path,
    manifest: Mapping[str, Any],
    witness_path: Path,
    output_root: Path,
    toolchain: Toolchain,
    formal_jobs: int = 1,
    prove_pending: Sequence[str] = (),
    shard_index: int = 0,
    shard_count: int = 1,
) -> dict[str, Any]:
    output_root.mkdir(parents=True, exist_ok=True)
    generic = [run_generic_case(case, output_root, formal_jobs) for case in manifest["cases"]]
    mutation = run_mutation_control(manifest["cases"][0], output_root)
    sequential_case = next(case for case in manifest["cases"] if case["clock"] is not None)
    sequential_mutation = run_mutation_control(sequential_case, output_root / "sequential-control")
    shared = run_shared_witness(
        repo_root,
        manifest["shared"],
        witness_path,
        output_root,
        formal_jobs,
        prove_pending,
        shard_index,
        shard_count,
    )
    evidence = {
        "schema_version": 1,
        "suite_id": "wire-assignment-ir",
        "tool_versions": dict(sorted(toolchain.versions.items())),
        "generic_cases": generic,
        "mutation_control": mutation,
        "sequential_mutation_control": sequential_mutation,
        "shared_witness": shared,
        "status": "PASS" if shard_count == 1 else "SHARD_PASS",
    }
    write_json(output_root / "suite-evidence.json", evidence)
    return evidence


def deterministic_artifact_signatures(root: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    for path in sorted(value for value in root.rglob("*") if value.is_file()):
        relative_path = path.relative_to(root)
        if any(part in relative_path.parts[:-1] for part in ("proof", "reachability", "obj_dir")):
            continue
        if path.suffix not in DETERMINISTIC_SUFFIXES:
            continue
        relative = relative_path.as_posix()
        result[relative] = sha256_file(path)
    return result


def compare_deterministic_runs(first: Path, second: Path, output: Path) -> dict[str, Any]:
    first_signatures = deterministic_artifact_signatures(first)
    second_signatures = deterministic_artifact_signatures(second)
    if first_signatures != second_signatures:
        first_keys = set(first_signatures)
        second_keys = set(second_signatures)
        missing = sorted(first_keys - second_keys)
        extra = sorted(second_keys - first_keys)
        changed = sorted(
            key
            for key in first_keys & second_keys
            if first_signatures[key] != second_signatures[key]
        )
        raise ValidationError(
            "repeated WA-03 run was not deterministic: "
            f"missing={missing}, extra={extra}, changed={changed}"
        )
    digest = sha256_bytes(canonical_json(first_signatures).encode("utf-8"))
    evidence = {
        "status": "PASS",
        "artifact_count": len(first_signatures),
        "artifact_set_digest": digest,
        "runs_identical": True,
    }
    write_json(output, evidence)
    return evidence


def clean_output_directory(path: Path) -> None:
    if path.exists():
        if path.is_symlink() or not path.is_dir():
            raise ValidationError(f"WA-03 output path is not a normal directory: {path}")
        shutil.rmtree(path)
    path.mkdir(parents=True)


def self_test(repo_root: Path, manifest_path: Path, registry_path: Path) -> None:
    raw = load_json(manifest_path)
    manifest = validate_manifest(repo_root, manifest_path, raw)
    if len(parameter_bindings(manifest["cases"][0]["domains"])) != 4:
        raise AssertionError("combinational complete-domain expansion changed")
    if len(parameter_bindings(manifest["cases"][1]["domains"])) != 3:
        raise AssertionError("sequential complete-domain expansion changed")
    shared_audit = audit_shared_domain(manifest["shared"])
    if shared_audit["binding_count"] != 512:
        raise AssertionError(
            "shared witness complete admitted domain must contain 512 bindings"
        )

    first_case = manifest["cases"][0]
    binding = parameter_bindings(first_case["domains"])[0]
    positive = generated_miter(
        first_case["inputs"],
        first_case["outputs"],
        binding,
        "ReferencePrepared",
        "CandidatePrepared",
        "PositiveMiter",
        None,
        None,
        mutate_first_output=False,
    )
    mutation = generated_miter(
        first_case["inputs"],
        first_case["outputs"],
        binding,
        "ReferencePrepared",
        "CandidatePrepared",
        "MutationMiter",
        None,
        None,
        mutate_first_output=True,
    )
    if "candidate_sink);" not in positive or "candidate_sink ^ 1'b1" not in mutation:
        raise AssertionError(
            "formal mutation control no longer changes the compared behavior"
        )

    sequential_case = manifest["cases"][1]
    sequential_binding = parameter_bindings(sequential_case["domains"])[0]
    sequential = generated_miter(
        sequential_case["inputs"],
        sequential_case["outputs"],
        sequential_binding,
        "SequentialReferencePrepared",
        "SequentialCandidatePrepared",
        "SequentialMiter",
        sequential_case["clock"],
        sequential_case["reset"],
        mutate_first_output=False,
    )
    reset_markers = (
        "always @($global_clock)",
        "initial wa03_reset_phase = 2'd0;",
        "if (wa03_reset_phase == 2'd0) begin",
        "assume(!clk);",
        "if (wa03_reset_phase == 2'd1) begin",
        "assume(clk);",
        "assume(reset);",
        "if (wa03_reset_phase == 2'd2) begin",
    )
    if any(marker not in sequential for marker in reset_markers):
        raise AssertionError(
            "sequential miter no longer forces a reset-active clock edge"
        )

    with tempfile.TemporaryDirectory(prefix="wa03-signature-self-test-") as directory:
        temporary_root = Path(directory)
        tracked = temporary_root / "tracked.txt"
        tracked.write_text("stable\n", encoding="utf-8")
        registry = temporary_root / "registry.json"
        write_json(
            registry,
            {
                "schema_version": 1,
                "files": {"tracked.txt": sha256_file(tracked)},
            },
        )
        verify_signature_registry(temporary_root, registry)
        tracked.write_text("mutated\n", encoding="utf-8")
        try:
            verify_signature_registry(temporary_root, registry)
        except ValidationError:
            pass
        else:
            raise AssertionError(
                "formal-model signature mutation was not detected"
            )

    verified = verify_signature_registry(repo_root, registry_path)
    if not verified:
        raise AssertionError("formal-model signature registry unexpectedly empty")
    print("WA-03 equivalence validator self-tests passed.")

def parse_args(argv: Iterable[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", type=Path)
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--signature-registry", type=Path)
    parser.add_argument("--shared-witness", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--check-determinism", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--formal-jobs", type=int, default=1,
                        help="maximum independent binding proofs; default is serial")
    parser.add_argument("--formal-shard-index", type=int, default=0,
                        help="zero-based proof partition; requires aggregate coverage validation")
    parser.add_argument("--formal-shard-count", type=int, default=1,
                        help="number of non-empty partitions; default proves the complete domain")
    parser.add_argument("--prove-pending", action="append", default=[], metavar="WA-ID",
                        help="also require every formal slot for this unchecked increment; never edits the roadmap")
    return parser.parse_args(list(argv))


def main(argv: Iterable[str] = sys.argv[1:]) -> int:
    args = parse_args(argv)
    script = Path(__file__).resolve()
    repo_root = (args.repo_root or script.parents[2]).resolve()
    manifest_path = (
        args.manifest
        or repo_root
        / "morphhdl-passes"
        / "tests"
        / "formal"
        / "wire_assignment_ir"
        / "manifest.json"
    ).resolve()
    registry_path = (
        args.signature_registry
        or repo_root
        / "morphhdl-passes"
        / "tests"
        / "formal_model"
        / "wire_assignment_ir"
        / "expected-signatures.json"
    ).resolve()

    try:
        require_positive_integer(args.formal_jobs, "--formal-jobs")
        if args.self_test:
            self_test(repo_root, manifest_path, registry_path)
            return 0

        if args.shared_witness is None:
            raise ValidationError("--shared-witness is required for the WA-03 proof gate")
        witness = args.shared_witness.resolve()
        output = (
            args.output
            or repo_root
            / "morphhdl-passes"
            / "build"
            / "formal"
            / "wire_assignment_ir"
        ).resolve()
        if output == repo_root or repo_root not in output.parents:
            raise ValidationError("WA-03 output must stay inside the repository workspace")
        # A failed preflight must not leave an earlier run's success marker.
        (output / "gate-status.json").unlink(missing_ok=True)

        raw_manifest = load_json(manifest_path)
        manifest = validate_manifest(repo_root, manifest_path, raw_manifest)
        verify_signature_registry(repo_root, registry_path)
        select_proof_bindings(parameter_bindings(manifest["shared"]["domains"]),
                              args.formal_shard_index, args.formal_shard_count)
        source_identity = proof_source_identity(repo_root, manifest_path, registry_path)
        completion = roadmap_completion(
            repo_root / "morphhdl-passes" / "morphhdl-ir-wire-assignment-passes-todo.md"
        )
        # Preflight every required candidate before spending time on earlier groups.
        plan_shared_slots(manifest["shared"], completion, args.prove_pending)
        clean_output_directory(output)
        toolchain = require_toolchain(output)
        execute_suite(repo_root, manifest, witness, output / "run-a", toolchain,
                      args.formal_jobs, args.prove_pending,
                      args.formal_shard_index, args.formal_shard_count)
        if args.check_determinism:
            execute_suite(repo_root, manifest, witness, output / "run-b", toolchain,
                          args.formal_jobs, args.prove_pending,
                          args.formal_shard_index, args.formal_shard_count)
            compare_deterministic_runs(
                output / "run-a",
                output / "run-b",
                output / "determinism.json",
            )
        else:
            write_json(
                output / "determinism.json",
                {"status": "NOT_REQUESTED", "runs_identical": False},
            )
        write_json(
            output / "gate-status.json",
            {
                "status": "PASS" if args.formal_shard_count == 1 else "SHARD_PASS",
                **source_identity,
                "formal_shard": {"index": args.formal_shard_index, "count": args.formal_shard_count},
                "determinism_checked": bool(args.check_determinism),
                "common_reference_sha256": sha256_file(
                    output
                    / "run-a"
                    / "shared-witness"
                    / manifest["shared"]["common_capture"]
                ),
            },
        )
        label = "gate" if args.formal_shard_count == 1 else "shard (NOT complete-domain qualification)"
        print(f"WA-03 equivalence, safety and determinism {label} passed: {output}")
        return 0
    except ValidationError as error:
        print(f"WA-03 gate failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
