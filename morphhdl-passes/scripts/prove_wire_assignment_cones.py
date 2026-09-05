#!/usr/bin/env python3
"""Prove every scalar assertion with ABC PDR, preserving explicit clock edges.

``run_proof(directory, miter_top, scalar_miter_text, expected_property_count,
             timeout_seconds=600, *, expected_assumption_count=None)`` consumes
prepared ``reference.il`` and ``candidate.il``. The caller supplies the complete
scalar miter and separately proves its comparison region reachable. This backend
never invents a SymbiYosys status. ``validate_proof`` accepts the same arguments
and verifies retained command results, canonical scripts, hashes and exact
property coverage without rerunning tools.

Each assertion cone is extracted independently AFTER folding all assumptions
into the sequential graph. Byte-identical normalized AIGs may share one proof
within this invocation only. The proof rebuilds that cone, writes a matching
snapshot, and runs PDR in the SAME ABC process (rereading changes ABC ordering).
If normalization drops a constant output, the original single-output cone is
proved instead. Every subprocess shares one wall-clock budget for this binding.
"""
from __future__ import annotations

import hashlib
import json
import math
import re
import shutil
import subprocess
import time
from pathlib import Path
from typing import Any

BACKEND = "abc-pdr-output-cones"
DIRECTORY = "cone-proof"
IDENTIFIER = re.compile(r"[A-Za-z_][A-Za-z0-9_$]*")
SHA256 = re.compile(r"[0-9a-f]{64}")


class ConeProofError(RuntimeError):
    """Missing, unsuccessful or inconsistent proof evidence."""


def canonical_json(value: Any) -> str:
    return json.dumps(value, sort_keys=True, indent=2) + "\n"


def digest(path: Path) -> str:
    if not path.is_file() or path.is_symlink():
        raise ConeProofError(f"missing or nonregular proof artifact: {path}")
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _load(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, ValueError) as error:
        raise ConeProofError(f"malformed or missing evidence: {path}") from error


def _write(path: Path, value: Any) -> None:
    path.write_text(canonical_json(value), encoding="utf-8")


def _positive(value: int, label: str) -> None:
    if type(value) is not int or value < 1:
        raise ConeProofError(f"{label} must be a positive integer")


def _contract(miter_top: str, text: str, properties: int, timeout: int,
              assumptions: int | None) -> int:
    if not isinstance(miter_top, str) or not IDENTIFIER.fullmatch(miter_top):
        raise ConeProofError("invalid miter identifier")
    _positive(properties, "property count")
    _positive(timeout, "timeout")
    if not isinstance(text, str) or not text.strip():
        raise ConeProofError("empty scalar miter")
    # Generated source is checked independently from the synthesized AIG counts.
    clean = re.sub(r"/\*.*?\*/|//[^\n]*", "", text, flags=re.S)
    if len(re.findall(r"\bassert\s*\(", clean)) != properties:
        raise ConeProofError("scalar miter assertion count does not match contract")
    observed = len(re.findall(r"\bassume\s*\(", clean))
    if assumptions is None:
        assumptions = observed
    if type(assumptions) is not int or assumptions < 0 or assumptions != observed:
        raise ConeProofError("scalar miter assumption count does not match contract")
    return assumptions


def compile_script(miter_top: str) -> str:
    """Canonical conservative preparation; no single-clock abstraction."""
    return f"""read_rtlil ../reference.il
read_rtlil ../candidate.il
read_verilog -formal scalar-miter.v
setattr -set keep 1 t:$assert t:$assume t:$check
prep -top {miter_top} -flatten
memory_map
clk2fflogic
formalff -ff2anyinit
opt -full -keepdc
chformal -cover -remove
setundef -undriven -anyseq
techmap
opt -fast -keepdc
abc -g AND
opt_clean
formalff -anyinit2ff -fine
aigmap
check -assert
write_aiger -zinit -symbols full.aig
"""


def aiger_header(path: Path) -> dict[str, int]:
    """Read a strict AIGER 1.9 header, retaining bad/constraint counts."""
    digest(path)
    try:
        with path.open("rb") as handle:
            line = handle.readline(512).decode("ascii")
        parts = line.rstrip("\n").split(" ")
        if not line.endswith("\n") or parts[0] != "aig" or not 6 <= len(parts) <= 10:
            raise ValueError("invalid AIG header")
        if any(re.fullmatch(r"0|[1-9][0-9]*", item) is None for item in parts[1:]):
            raise ValueError("invalid AIG number")
        values = list(map(int, parts[1:])) + [0] * (10 - len(parts))
        result = dict(zip(("M", "I", "L", "O", "A", "B", "C", "J", "F"), values))
        if result["M"] != result["I"] + result["L"] + result["A"]:
            raise ValueError("inconsistent binary AIG node count")
        return result
    except (OSError, UnicodeError, ValueError) as error:
        raise ConeProofError(f"malformed AIGER header: {path}") from error


def _single(path: Path, allow_zero: bool = False) -> dict[str, int]:
    header = aiger_header(path)
    if (header["O"] not in ((0, 1) if allow_zero else (1,))
            or any(header[key] for key in ("B", "C", "J", "F"))):
        raise ConeProofError(f"cone is not an unconstrained single property: {path}")
    return header


def property_stem(index: int) -> str:
    return f"property-{index:04d}"


def cone_commands(index: int, normalize: bool, sequential: bool = True) -> str:
    commands = f"read_aiger full.aig; fold; strash; cone -s -O {index}"
    if sequential:
        commands += "; scleanup"
    if normalize:
        commands += "; trim" + ("; scorr" if sequential else "") + "; dc2"
    return commands


def isolation_script(index: int) -> str:
    return (cone_commands(index, False, False)
            + f"; write_aiger -u {property_stem(index)}-raw.aig\n")


def extraction_script(index: int, sequential: bool = True) -> str:
    stem = property_stem(index)
    return (cone_commands(index, False, sequential)
            + f"; write_aiger -u {stem}-original.aig; trim"
            + ("; scorr" if sequential else "") + "; dc2"
            + f"; write_aiger -u {stem}-normalized.aig\n")


def proof_script(index: int, normalize: bool, timeout: int, sequential: bool = True) -> str:
    return (cone_commands(index, normalize, sequential)
            + f"; write_aiger -u {property_stem(index)}-proven.aig"
            + f"; pdr -T {timeout} -v -d -I {property_stem(index)}-invariant.pla\n")


def _command(root: Path, name: str, argv: list[str], deadline: float) -> None:
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise ConeProofError("TIMEOUT: binding proof budget exhausted")
    log = root / f"{name}.log"
    started = time.monotonic()
    timed_out = False
    returncode = None
    try:
        with log.open("w", encoding="utf-8") as handle:
            completed = subprocess.run(argv, cwd=root, stdout=handle,
                                       stderr=subprocess.STDOUT, timeout=remaining,
                                       check=False)
        returncode = completed.returncode
    except subprocess.TimeoutExpired:
        timed_out = True
    except OSError as error:
        log.write_text(str(error) + "\n", encoding="utf-8")
    # Volatile execution records deliberately do not use a deterministic .json
    # suffix. Their log hashes and actual subprocess status remain mandatory.
    _write(root / f"{name}.execution", {
        "argv": argv, "returncode": returncode, "timed_out": timed_out,
        "elapsed_seconds": time.monotonic() - started, "log_sha256": digest(log),
    })
    _validate_command(root, name, argv)


def _validate_command(root: Path, name: str, argv: list[str]) -> str:
    result = _load(root / f"{name}.execution")
    if (not isinstance(result, dict) or set(result) != {
            "argv", "returncode", "timed_out", "elapsed_seconds", "log_sha256"}
            or result.get("argv") != argv
            or type(result.get("returncode")) is not int or result["returncode"] != 0
            or result.get("timed_out") is not False
            or type(result.get("elapsed_seconds")) not in (int, float)
            or not math.isfinite(result["elapsed_seconds"]) or result["elapsed_seconds"] < 0
            or result.get("log_sha256") != digest(root / f"{name}.log")):
        raise ConeProofError(f"failed, TIMEOUT or inconsistent command evidence: {name}")
    output = (root / f"{name}.log").read_text(encoding="utf-8", errors="replace")
    # ABC can return zero on a parser or command error, so returncode alone is
    # insufficient even for extraction stages.
    if re.search(r"(?im)^.*(?:\berror:|has failed\.|unknown command|cannot open|cannot find|wrong input file format)", output):
        raise ConeProofError(f"tool reported an error: {name}")
    return output


def validate_pdr_log(output: str) -> str:
    """Require ABC's actual proof and independently checked invariant reports."""
    proved = re.findall(r"(?m)^Property proved\.\s+Time\s*=\s*[0-9.]+\s+sec\s*$", output)
    invariant = re.findall(
        r"(?m)^Verification of invariant with [0-9]+ clauses was successful\.\s+Time\s*=\s*[0-9.]+\s+sec\s*$", output)
    failure = re.search(
        r"(?im)(?:\bUNDECIDED\b|\bUNKNOWN\b|\bTIMEOUT\b|property.*(?:failed|fails|disproved)|"
        r"output\s+\d+.*asserted|counterexample|time limit|verification of invariant.*(?:failed|unsuccessful))", output)
    if len(proved) != 1 or len(invariant) != 1 or failure:
        raise ConeProofError("ABC PDR did not publish one proved property with a verified invariant")
    return "PASS"


def _invariant_text(path: Path) -> str:
    digest(path)
    text = path.read_text(encoding="utf-8")
    # ABC emits a timestamp in a comment. Retain the raw file and its execution
    # hash, while comparing the exact noncomment PLA content across repetitions.
    canonical = "\n".join(line for line in text.splitlines() if not line.startswith("#")) + "\n"
    for pattern in (r"(?m)^\.i [0-9]+$", r"(?m)^\.o 1$", r"(?m)^\.p [0-9]+$", r"(?m)^\.e$"):
        if len(re.findall(pattern, canonical)) != 1:
            raise ConeProofError(f"missing or malformed retained invariant: {path}")
    return canonical


def _retain_invariant(root: Path, stem: str) -> None:
    raw = root / (stem + "-invariant.pla")
    canonical = root / (stem + "-invariant.txt")
    canonical.write_text(_invariant_text(raw), encoding="utf-8")
    _write(root / (stem + "-invariant.execution"), {
        "raw_sha256": digest(raw), "canonical_sha256": digest(canonical),
    })


def _validate_invariant(root: Path, stem: str) -> str:
    raw = root / (stem + "-invariant.pla")
    canonical = root / (stem + "-invariant.txt")
    expected = {"raw_sha256": digest(raw), "canonical_sha256": digest(canonical)}
    if (_load(root / (stem + "-invariant.execution")) != expected
            or canonical.read_text(encoding="utf-8") != _invariant_text(raw)):
        raise ConeProofError("retained invariant hash or canonical contents changed")
    return expected["canonical_sha256"]


def _text(root: Path, name: str, expected: str) -> None:
    path = root / name
    digest(path)
    if path.read_text(encoding="utf-8") != expected:
        raise ConeProofError(f"noncanonical proof source or command: {name}")


def _metadata(directory: Path, top: str, miter: str, count: int, timeout: int,
              assumptions: int) -> dict[str, Any]:
    root = directory / DIRECTORY
    _text(root, "scalar-miter.v", miter)
    _text(root, "compile.ys", compile_script(top))
    _validate_command(root, "compile", ["yosys", "-Q", "-T", "-s", "compile.ys"])
    full_header = aiger_header(root / "full.aig")
    if (full_header["O"] != 0 or full_header["B"] != count
            or full_header["C"] != assumptions or full_header["J"] or full_header["F"]):
        raise ConeProofError("full AIG assertion/assumption counts do not match the complete miter")
    artifacts = {name: digest(root / name) for name in ("scalar-miter.v", "compile.ys", "full.aig")}
    properties = []
    proofs = []
    # Digest indexing only locates candidates; actual byte equality is required
    # before any proof is reused, even in the hypothetical event of a collision.
    representatives: dict[str, tuple[int, bytes]] = {}
    for index in range(count):
        stem = property_stem(index)
        _text(root, stem + "-isolate.abc", isolation_script(index))
        _validate_command(root, stem + "-isolate", ["yosys-abc", "-f", stem + "-isolate.abc"])
        raw_header = _single(root / (stem + "-raw.aig"))
        sequential = raw_header["L"] > 0
        _text(root, stem + "-extract.abc", extraction_script(index, sequential))
        _validate_command(root, stem + "-extract", ["yosys-abc", "-f", stem + "-extract.abc"])
        _single(root / (stem + "-original.aig"))
        normalized = _single(root / (stem + "-normalized.aig"), allow_zero=True)
        use_normalized = normalized["O"] == 1
        selected = stem + ("-normalized.aig" if use_normalized else "-original.aig")
        formula = (root / selected).read_bytes()
        formula_sha = digest(root / selected)
        for suffix in ("-isolate.abc", "-raw.aig", "-extract.abc", "-original.aig", "-normalized.aig"):
            name = stem + suffix
            artifacts[name] = digest(root / name)
        if formula_sha in representatives:
            representative, previous = representatives[formula_sha]
            if previous != formula:
                raise ConeProofError("different formulas have the same hash")
        else:
            representative = index
            representatives[formula_sha] = (index, formula)
            _text(root, stem + "-prove.abc", proof_script(index, use_normalized, timeout, sequential))
            output = _validate_command(root, stem + "-prove", ["yosys-abc", "-f", stem + "-prove.abc"])
            _single(root / (stem + "-proven.aig"))
            if (root / (stem + "-proven.aig")).read_bytes() != formula:
                raise ConeProofError("proved snapshot differs from the extracted formula")
            validate_pdr_log(output)
            artifacts[stem + "-invariant.txt"] = _validate_invariant(root, stem)
            for suffix in ("-prove.abc", "-proven.aig"):
                artifacts[stem + suffix] = digest(root / (stem + suffix))
            proofs.append({"representative_index": index, "formula_sha256": formula_sha,
                           "status": "PASS", "verified_invariant": True})
        properties.append({"index": index, "formula_sha256": formula_sha,
                           "representative_index": representative,
                           "selected": selected, "normalization_kept_output": use_normalized})
    elapsed = sum(_load(root / (name + ".execution"))["elapsed_seconds"]
                  for name in ["compile"]
                  + [property_stem(i) + suffix for i in range(count) for suffix in ("-isolate", "-extract")]
                  + [property_stem(row["representative_index"]) + "-prove" for row in proofs])
    if elapsed > timeout:
        raise ConeProofError("retained commands exceed the shared binding timeout")
    return {"schema_version": 1, "backend": BACKEND, "status": "PASS",
            "miter_top": top, "property_count": count, "assumption_count": assumptions,
            "timeout_seconds": timeout, "full_aiger_header": full_header,
            "sources": {name: digest(directory / name) for name in ("reference.il", "candidate.il")},
            "artifacts": artifacts, "properties": properties, "unique_proofs": proofs,
            "proof_reuse_scope": "this_binding_this_invocation_only"}


def run_proof(directory: Path, miter_top: str, scalar_miter_text: str,
              expected_property_count: int, timeout_seconds: int = 600, *,
              expected_assumption_count: int | None = None) -> dict[str, Any]:
    """Execute all assertions, fail closed, and retain diagnostic failure evidence."""
    directory = Path(directory)
    assumptions = _contract(miter_top, scalar_miter_text, expected_property_count,
                            timeout_seconds, expected_assumption_count)
    root = directory / DIRECTORY
    if root.is_symlink():
        raise ConeProofError("proof directory cannot be a symlink")
    if root.exists():
        shutil.rmtree(root)
    root.mkdir(parents=True)
    deadline = time.monotonic() + timeout_seconds
    try:
        source_hashes = {name: digest(directory / name) for name in ("reference.il", "candidate.il")}
        (root / "scalar-miter.v").write_text(scalar_miter_text, encoding="utf-8")
        (root / "compile.ys").write_text(compile_script(miter_top), encoding="utf-8")
        _command(root, "compile", ["yosys", "-Q", "-T", "-s", "compile.ys"], deadline)
        header = aiger_header(root / "full.aig")
        if (header["O"] != 0 or header["B"] != expected_property_count
                or header["C"] != assumptions or header["J"] or header["F"]):
            raise ConeProofError("full AIG assertion/assumption counts do not match the complete miter")
        representatives: dict[str, bytes] = {}
        for index in range(expected_property_count):
            stem = property_stem(index)
            script = stem + "-isolate.abc"
            (root / script).write_text(isolation_script(index), encoding="utf-8")
            _command(root, stem + "-isolate", ["yosys-abc", "-f", script], deadline)
            sequential = _single(root / (stem + "-raw.aig"))["L"] > 0
            script = stem + "-extract.abc"
            (root / script).write_text(extraction_script(index, sequential), encoding="utf-8")
            _command(root, stem + "-extract", ["yosys-abc", "-f", script], deadline)
            _single(root / (stem + "-original.aig"))
            normalized = _single(root / (stem + "-normalized.aig"), allow_zero=True)
            use_normalized = normalized["O"] == 1
            formula_path = root / (stem + ("-normalized.aig" if use_normalized else "-original.aig"))
            formula = formula_path.read_bytes()
            sha = digest(formula_path)
            if sha in representatives:
                if representatives[sha] != formula:
                    raise ConeProofError("different formulas have the same hash")
                continue
            script = stem + "-prove.abc"
            (root / script).write_text(proof_script(index, use_normalized, timeout_seconds, sequential), encoding="utf-8")
            _command(root, stem + "-prove", ["yosys-abc", "-f", script], deadline)
            _single(root / (stem + "-proven.aig"))
            if (root / (stem + "-proven.aig")).read_bytes() != formula:
                raise ConeProofError("proved snapshot differs from the extracted formula")
            validate_pdr_log((root / (stem + "-prove.log")).read_text(encoding="utf-8"))
            _retain_invariant(root, stem)
            representatives[sha] = formula
        evidence = _metadata(directory, miter_top, scalar_miter_text, expected_property_count,
                             timeout_seconds, assumptions)
        if evidence["sources"] != source_hashes:
            raise ConeProofError("prepared sources changed during proof")
        if time.monotonic() > deadline:
            raise ConeProofError("TIMEOUT: binding proof budget exhausted")
        _write(root / "evidence.json", evidence)
        return evidence
    except (ConeProofError, OSError, ValueError) as error:
        (root / "evidence.json").unlink(missing_ok=True)
        _write(root / "failure.json", {"status": "FAIL", "backend": BACKEND, "reason": str(error)})
        if isinstance(error, ConeProofError):
            raise
        raise ConeProofError(str(error)) from error


def validate_proof(directory: Path, miter_top: str, scalar_miter_text: str,
                   expected_property_count: int, timeout_seconds: int = 600, *,
                   expected_assumption_count: int | None = None) -> dict[str, Any]:
    """Recompute the entire deterministic manifest and inspect actual tool results."""
    directory = Path(directory)
    assumptions = _contract(miter_top, scalar_miter_text, expected_property_count,
                            timeout_seconds, expected_assumption_count)
    root = directory / DIRECTORY
    if root.is_symlink() or (root / "failure.json").exists():
        raise ConeProofError("proof directory contains failed or unsafe evidence")
    try:
        recorded = _load(root / "evidence.json")
        expected = _metadata(directory, miter_top, scalar_miter_text, expected_property_count,
                             timeout_seconds, assumptions)
        if recorded != expected:
            raise ConeProofError("missing, reordered, omitted or inconsistent property evidence")
        return expected
    except (OSError, ValueError, TypeError) as error:
        raise ConeProofError(f"invalid cone proof evidence: {error}") from error
