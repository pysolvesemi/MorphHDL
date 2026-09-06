#!/usr/bin/env python3
"""Close 60f qualification without editing the sealed independent RTL writers.

OUTPUT contains boundaries/ from SignednessBoundaryArtifactWriter and pure/ from
PureSIntCastArtifactWriter. The full run preserves the inherited 60d/60e gates and
adds the exact 60a mutation's solver witness/replay plus explicit memory-validity
proofs. The latter are bounded, supplementary checks, not replacements for the
inherited sequential equivalence induction gates.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import re
import shutil
import subprocess
from pathlib import Path

BASE = "feca6b9d599d97af92ed9f6a8bc871ef008c395e"
QUALIFIED_60F = "8ae431f54efcd7b88fb49243e5fe82a9dbcc4ccd"
# Complete reviewed follow-on production delta, frozen from WA-07a f6646f5.
# This does not expand 60f's historical qualification-only production scope.
WA07A_PRODUCTION_SHA256 = {
    "morphhdl-passes/src/main/scala/morphhdl/passes/api/PassContracts.scala":
        "1946882af38c058829564faa5d0f7967209e8efd1ab8cfe3d26060ec206a2cda",
    "morphhdl-passes/src/main/scala/morphhdl/passes/pipeline/WireAliasPassPipeline.scala":
        "e8ae9bdd4ae8bfb9ffd168a62a7a77578ae54b14cee3291b199d90899d1a4f1e",
    "morphhdl-passes/src/main/scala/morphhdl/passes/transform/ConstantOperandSimplificationPass.scala":
        "40a754b3b8029b9cbe047a92e35ef850f644f2b6a941f15cb69786c2b4b30b71",
}
# Exact, separately reviewed 59f production delta. Source selection never uses
# report counts, branch names, partial feature presence, or unchecked paths.
CALLBACK_59F_PRODUCTION_SHA256 = {
    "core/src/main/scala/spinal/core/BitVector.scala": "fc59c3f42ff9be5ea6ddfb4a5d7e32c59f57302ad8b806201285dc648425f9f6",
    "core/src/main/scala/spinal/core/ParameterizedWidth.scala": "f85ea514e70e7be6e46f566bb898bb40f8588d8b42ec68d9c6116a0122ffdce8",
    "morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala": "dd2bdf8629d08a0b25fb785e4828c9ae56ef0f5e7c4fc8efd4b086c61140d6a3",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionBackend.scala": "8745879bd3e435ed51c9809e6eb05f9c3d5ede6513fe47b84a1e4dc8bd895064",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCapture.scala": "b08a77c98c5b9fad4f5a44430b5c9a2edc6fa9055c763503b808c0c2688ccc70",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCaptureSchema.scala": "15fecc14650d5a0fb2f03450977cd91da432816bfa47ab1fc4003c573f557f1c",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCertifiedCallbackPolicy.scala": "db37d6540af715b6f535a78891439020fb7196d450dc403af3cde6a9fedc53fb",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionOperatorCertificate.scala": "5abc84b0aeaf26b21d6a21fc0a844f2e8b17833ec4da33cec84f8ce75b7f17d3",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionOperatorReplay.scala": "b401b6cece917c84e945efedaca22148ccc4a88bbe8df9ff7f4073fdf4ab35bf",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionScalarGraphReplay.scala": "a1de8e5058d02f66c801f70409f7e208daed3a2a1d1584491e3be837615352ed",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionStageReplay.scala": "43e5ee1294041a4bf7f46fc92ec56569d0bf20e8180c770749ae976060afcd10",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionValueEvidence.scala": "da743fb98428d5ffb6d2875096293daad1da99dfb4b796e7cc4c540d76d3f899"
}
INCREMENT_59D_BASE = "5a669d32095ee722c313bd069b771e7c350a1f81"
INCREMENT_59D_PRODUCTION_PATHS = frozenset({
    "core/src/main/scala/spinal/core/BaseType.scala",
    "core/src/main/scala/spinal/core/BitVector.scala",
    "core/src/main/scala/spinal/core/ElabInt.scala",
    "core/src/main/scala/spinal/core/ElaborationWidthAuthority.scala",
    "core/src/main/scala/spinal/core/Misc.scala",
    "core/src/main/scala/spinal/core/NativeWidthProvenance.scala",
    "core/src/main/scala/spinal/core/ParameterizedWidth.scala",
    "morphhdl/src/main/scala/morphhdl/MorphVerilog.scala",
    "morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedHighBit.scala",
    "morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedNativeResize.scala",
    "morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala",
    "morphhdl/src/main/scala/spinal/core/internals/MorphHdlSignednessAnalysis.scala",
    "morphhdl/src/main/scala/spinal/core/internals/NativePublicationScope.scala",
    "morphhdl/src/main/scala/spinal/core/internals/NativePublicationWidth.scala",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionBackend.scala",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionBridgeReplay.scala",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCallbackPolicy.scala",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionClosedGraph.scala",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionOperatorReplay.scala",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionStageReplay.scala",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionValueEvidence.scala",
})
INTEGRATION_59D59F_BASE = "c85659a20d428dd58cc6116c12c8b24418c37722"
INTEGRATION_59D59F_CONTRACT = "morphhdl/contracts/increment-59d-59f-integration-edits.json"
INTEGRATION_59D59F_SHA256 = "c4e314a6ca8d47ff50c04b1dcc6e7562f0195bdea1831bffbea6c425e9f2da9c"
INTEGRATION_59D59F_PATHS = frozenset({
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionOperatorCertificate.scala",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionScalarGraphReplay.scala",
})
WIDTHS = (1, 5, 8, 32)
MEMORY_STEPS = 8
SAT_PASS = "SAT proof finished - no model found: SUCCESS!"
SAT_FAIL = "SAT proof finished - model found: FAIL!"
BAD_RESULT = re.compile(r"\b(?:UNKNOWN|TIMEOUT|timed\s+out)\b|^\s*(?:ERROR|FATAL):", re.I | re.M)


def require(ok: bool, detail: str) -> None:
    if not ok:
        raise RuntimeError(detail)


def load(root: Path, suffix: str):
    path = root / ("morphhdl/scripts/check-increment-" + suffix + ".py")
    spec = importlib.util.spec_from_file_location(suffix.replace("-", "_"), path)
    require(spec is not None and spec.loader is not None, "cannot import " + str(path))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def classify_solver(returncode: int, output: str, expected: str) -> None:
    """A marker never overrides process failure, unknown results or tool errors."""
    require(expected in (SAT_PASS, SAT_FAIL), "unknown expected solver result")
    require(returncode == 0, "solver returned nonzero exit status")
    require(BAD_RESULT.search(output) is None, "solver reported an error, timeout or UNKNOWN")
    require(output.count(expected) == 1, "missing or duplicated expected solver result")
    opposite = SAT_FAIL if expected == SAT_PASS else SAT_PASS
    require(opposite not in output, "contradictory solver results")


def run(command: list[str], directory: Path, label: str, expected: str | None = None,
        timeout: int = 240) -> str:
    try:
        result = subprocess.run(command, cwd=directory, text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=timeout)
    except subprocess.TimeoutExpired as error:
        output = error.stdout or ""
        if isinstance(output, bytes):
            output = output.decode("utf-8", errors="replace")
        (directory / (label + ".log")).write_text(output + "\nTIMEOUT\n")
        (directory / (label + ".result.json")).write_text(json.dumps({
            "command": command, "status": "timeout", "timeout_seconds": timeout}, indent=2) + "\n")
        raise RuntimeError(label + " timed out; this is not a proof") from error
    (directory / (label + ".log")).write_text(result.stdout)
    (directory / (label + ".result.json")).write_text(json.dumps({
        "command": command, "returncode": result.returncode,
        "expected": expected}, indent=2) + "\n")
    require(result.returncode == 0, label + " failed:\n" + result.stdout[-16000:])
    require(BAD_RESULT.search(result.stdout) is None, label + " reported an error, timeout or UNKNOWN")
    if expected is not None:
        classify_solver(result.returncode, result.stdout, expected)
    return result.stdout


def self_test() -> None:
    # These are result-classification attack cases, not stand-ins for real tools.
    accepted = ((0, SAT_PASS, SAT_PASS), (0, SAT_FAIL, SAT_FAIL))
    rejected = (
        (1, SAT_FAIL, SAT_FAIL), (1, SAT_PASS, SAT_PASS),
        (0, "ERROR: Module Missing is not part of the design.\n" + SAT_FAIL, SAT_FAIL),
        (0, "ERROR: syntax error, unexpected TOK_ID\n" + SAT_PASS, SAT_PASS),
        (0, "UNKNOWN\n" + SAT_FAIL, SAT_FAIL),
        (0, "TIMEOUT\n" + SAT_PASS, SAT_PASS),
        (0, "solver timed out\n" + SAT_FAIL, SAT_FAIL),
        (0, "tool ran successfully without a proof marker", SAT_PASS),
        (0, SAT_PASS, SAT_FAIL), (0, SAT_FAIL, SAT_PASS),
        (0, SAT_PASS + "\n" + SAT_FAIL, SAT_PASS),
        (0, SAT_FAIL + "\n" + SAT_FAIL, SAT_FAIL),
    )
    for args in accepted:
        classify_solver(*args)
    for args in rejected:
        try:
            classify_solver(*args)
        except RuntimeError:
            continue
        raise RuntimeError("failed to reject invalid result: " + repr(args))
    print(f"60f result classification: {len(accepted)} positive and {len(rejected)} rejection controls PASS", flush=True)


def qualification_ancestry(root: Path) -> None:
    require(subprocess.run(["git", "merge-base", "--is-ancestor", BASE, QUALIFIED_60F],
                           cwd=root).returncode == 0,
            "60f baseline must be an ancestor of the qualified commit")
    require(subprocess.run(["git", "merge-base", "--is-ancestor", QUALIFIED_60F, "HEAD"],
                           cwd=root).returncode == 0,
            "qualified 60f must be an ancestor of HEAD")


def profile_features(profile: str) -> frozenset[str]:
    names = ("wa07a", "59d", "59f")
    profiles = {"60f-baseline": frozenset()}
    for mask in range(1, 8):
        selected = tuple(name for index, name in enumerate(names) if mask & (1 << index))
        profiles["60f-with-" + "-and-".join(selected)] = frozenset(selected)
    require(profile in profiles, "unknown validated source profile: " + profile)
    return profiles[profile]


def integration_59d59f(root: Path) -> dict[str, str]:
    """Prove only the two reviewed callback/width integrations against frozen 59f."""
    contract_path = root / INTEGRATION_59D59F_CONTRACT
    require(contract_path.is_file() and not contract_path.is_symlink(),
            "missing regular 59d/59f integration review")
    raw = contract_path.read_bytes()
    require(hashlib.sha256(raw).hexdigest() == INTEGRATION_59D59F_SHA256,
            "59d/59f reviewed integration manifest changed")
    reviewed = json.loads(raw)
    require(set(reviewed) == {"base", "files"} and reviewed["base"] == INTEGRATION_59D59F_BASE,
            "59d/59f integration restoration baseline/schema changed")
    entries = reviewed["files"]
    require([entry["path"] for entry in entries] == sorted(INTEGRATION_59D59F_PATHS),
            "59d/59f integration exceeds its two exact callback paths")
    hashes = {}
    for entry in entries:
        require(set(entry) == {"path", "before_sha256", "after_sha256", "edits"},
                "59d/59f integration file schema changed")
        path = entry["path"]
        require(entry["before_sha256"] == CALLBACK_59F_PRODUCTION_SHA256[path],
                "59d/59f integration changed the frozen 59f source hash")
        source_path = root / path
        require(source_path.is_file() and not source_path.is_symlink() and not source_path.stat().st_mode & 0o111,
                "reviewed production source must be a regular non-executable file: " + path)
        source = source_path.read_text()
        require(hashlib.sha256(source.encode()).hexdigest() == entry["after_sha256"],
                "59d/59f reviewed integration source changed: " + path)
        edits = entry["edits"]
        require(edits and all(set(edit) == {"id", "before", "after"} and
                             edit["id"] and edit["before"] and edit["after"] for edit in edits) and
                len({edit["id"] for edit in edits}) == len(edits),
                "59d/59f integration has malformed or duplicate reviewed spans")
        for edit in reversed(edits):
            require(source.count(edit["after"]) == 1,
                    "missing/duplicate reviewed 59d/59f integration span: " + path)
            source = source.replace(edit["after"], edit["before"], 1)
        require(hashlib.sha256(source.encode()).hexdigest() == entry["before_sha256"],
                "59d/59f restored integration source hash differs: " + path)
        historical = subprocess.check_output(["git", "show", INTEGRATION_59D59F_BASE + ":" + path],
                                             cwd=root, text=True)
        require(source == historical, "59d/59f restored integration source differs from frozen 59f: " + path)
        hashes[path] = entry["after_sha256"]
    return hashes


def production_profile(root: Path) -> str:
    """Select a complete reviewed source union before consulting any reports."""
    def git(*args: str) -> bytes:
        return subprocess.check_output(["git", *args], cwd=root)

    def production_paths(data: bytes) -> set[str]:
        return {path.decode("utf-8") for path in data.split(b"\0")
                if re.search(rb"(?:^|/)src/main/", path)}

    qualification_ancestry(root)
    historical = production_paths(git("diff", "--no-renames", "--name-only", "-z", BASE, QUALIFIED_60F))
    require(not historical, "qualified 60f must remain production-zero: " + str(sorted(historical)))
    untracked = production_paths(git("ls-files", "--others", "-z"))
    require(not untracked, "untracked production sources: " + str(sorted(untracked)))
    changed = production_paths(git("diff", "--no-renames", "--name-only", "-z", BASE))
    wa, widths, callbacks = (set(WA07A_PRODUCTION_SHA256), set(INCREMENT_59D_PRODUCTION_PATHS),
                             set(CALLBACK_59F_PRODUCTION_SHA256))
    require(wa and widths and callbacks and not wa & (widths | callbacks),
            "reviewed WA-07a production profile overlaps a width/callback profile or is empty")
    profiles = {}
    for mask in range(8):
        selected = tuple(name for index, name in enumerate(("wa07a", "59d", "59f"))
                         if mask & (1 << index))
        paths = (wa if mask & 1 else set()) | (widths if mask & 2 else set()) | (callbacks if mask & 4 else set())
        profiles[frozenset(paths)] = ("60f-with-" + "-and-".join(selected)) if selected else "60f-baseline"
    require(len(profiles) == 8, "reviewed production feature inventories do not identify eight exact unions")
    if changed & wa:
        require(changed & wa == wa,
                "incomplete reviewed WA-07a production delta: " + str(sorted(changed & wa)))
    require(frozenset(changed) in profiles,
            "59d reviewed production inventory differs from the exact current delta; "
            "unreviewed production delta: " + str(sorted(changed)))
    profile = profiles[frozenset(changed)]
    features = profile_features(profile)
    hashes = dict(WA07A_PRODUCTION_SHA256) if "wa07a" in features else {}
    if "59f" in features:
        hashes.update(CALLBACK_59F_PRODUCTION_SHA256)
    if "59d" in features:
        require(subprocess.run(["git", "merge-base", "--is-ancestor", INCREMENT_59D_BASE, "HEAD"],
                               cwd=root).returncode == 0, "59d review baseline must be an ancestor of HEAD")
        historical = production_paths(git("diff", "--no-renames", "--name-only", "-z", BASE, INCREMENT_59D_BASE))
        require(not historical, "59d review baseline must remain production-zero: " + str(sorted(historical)))
        reviewed = reviewed_59d(root)
        # The seven shared width/callback paths have separately reviewed merged
        # bytes in the exact 59d contract. Callback-only paths retain their frozen
        # 59f hashes; standalone 59f retains all twelve original source hashes.
        hashes.update((entry["path"], entry["sha256"]) for entry in reviewed["files"])
    if {"59d", "59f"}.issubset(features):
        require(INTEGRATION_59D59F_PATHS <= callbacks - widths,
                "59d/59f integration paths escaped the callback-only scope")
        hashes.update(integration_59d59f(root))
    require(set(hashes) == changed, "validated source hashes do not cover the exact production union")
    for path, digest in hashes.items():
        source = root / path
        diagnostic = ("59d reviewed production bytes changed: " if "59d" in features and path in widths else
                      "reviewed WA-07a production source hash differs: " if path in wa else
                      "reviewed production source hash differs: ")
        require(source.is_file(), diagnostic + path)
        require(not source.is_symlink() and not source.stat().st_mode & 0o111,
                "reviewed production source must be a regular non-executable file: " + path)
        require(hashlib.sha256(source.read_bytes()).hexdigest() == digest, diagnostic + path)
        stage = git("ls-files", "--stage", "--", path).decode("utf-8").split()
        require(len(stage) == 4 and stage[0] == "100644" and stage[2] == "0" and stage[3] == path,
                "reviewed production source is not uniquely tracked: " + path)
    return profile


def reviewed_59d(root: Path) -> dict:
    """Load the exact 59d contract without widening either follow-on's scope."""
    contract = root / "morphhdl/contracts/increment-59d-production-review.json"
    require(contract.is_file() and not contract.is_symlink(), "missing regular 59d production review")
    reviewed = json.loads(contract.read_text())
    require(set(reviewed) == {"base", "files", "checker_edits"} and
            reviewed["base"] == INCREMENT_59D_BASE, "59d production review baseline changed")
    require(all(set(entry) == {"path", "sha256"} and
                re.fullmatch(r"[0-9a-f]{64}", entry["sha256"]) for entry in reviewed["files"]),
            "59d production review has malformed file records")
    require([entry["path"] for entry in reviewed["files"]] == sorted(INCREMENT_59D_PRODUCTION_PATHS),
            "59d reviewed production inventory differs from the exact current delta")
    boundary_checker = "morphhdl/scripts/check-increment-60e-signedness-boundaries.py"
    expected_checker_edits = [(boundary_checker, "restore-exact-59d-width-seams")]
    if (root / "morphhdl/contracts/increment-59d-signed-width-edits.json").is_file():
        expected_checker_edits += [
            (boundary_checker, "restore-exact-59d-signed-width-authority"),
            (boundary_checker, "validate-exact-59d-signed-width-authority-60e"),
            ("morphhdl/scripts/check-increment-60d-pure-sint-casts.py",
             "validate-exact-59d-signed-width-authority-60d")]
    require(all(set(edit) == {"path", "id", "before", "after"}
                for edit in reviewed["checker_edits"]) and
            [(edit["path"], edit["id"]) for edit in reviewed["checker_edits"]] == expected_checker_edits,
            "59d checker restoration exceeds its exact inherited boundary seams")
    return reviewed


def source_scope(root: Path) -> None:
    # Preserve the inherited oracle/authority rejection diagnostics. The exact
    # production union is still mandatory after all historical source audits.
    qualification_ancestry(root)
    reviewed = reviewed_59d(root) if (root / "morphhdl/contracts/increment-59d-production-review.json").is_file() else None
    frozen = [
        "morphhdl/scripts/check-increment-60a-sint-baseline.py",
        "morphhdl/scripts/check-increment-60c-signed-declarations.py",
        "morphhdl/scripts/check-increment-60d-pure-sint-casts.py",
        "morphhdl/scripts/check-increment-60e-signedness-boundaries.py",
        "morphhdl/src/test/scala/nativeapplication/SIntSignedVerilogBaselineFixture.scala",
        "morphhdl/src/test/scala/nativeapplication/SIntSignedDeclarationsFixture.scala",
        "morphhdl/src/test/scala/nativeapplication/PureSIntCastFixture.scala",
        "morphhdl/src/test/scala/spinal/core/SignednessBoundaryFixture.scala",
    ]
    for path in frozen:
        old = subprocess.check_output(["git", "show", BASE + ":" + path], cwd=root)
        current = (root / path).read_bytes()
        if reviewed is not None:
            for edit in reversed([edit for edit in reviewed["checker_edits"] if edit["path"] == path]):
                before, after = edit["before"].encode(), edit["after"].encode()
                require(current.count(after) == 1, "missing/duplicate reviewed 59d checker restoration span")
                current = current.replace(after, before, 1)
        if path == "morphhdl/scripts/check-increment-60e-signedness-boundaries.py" and \
                (root / "morphhdl/scripts/check-increment-59f-source-scope.py").exists():
            current = load(root, "59f-source-scope").restore_59f_source(root, path, current.decode()).encode()
        require(current == old, "sealed writer/checker changed: " + path)
    for suffix in ("60c-signed-declarations", "60d-pure-sint-casts", "60e-signedness-boundaries"):
        load(root, suffix).source_scope(root)
    subprocess.run(["python3", "morphhdl/scripts/check-native-source-preservation.py"], cwd=root, check=True)
    profile = production_profile(root)
    print("60f historical qualification-only scope, " + profile +
          ", sealed writers/checkers and inherited native audits PASS", flush=True)


def inventory(root: Path, out: Path) -> dict[str, str]:
    boundary = load(root, "60e-signedness-boundaries")
    paths = ["boundaries/" + kind + "/" + file
             for kind in boundary.KINDS
             for file in ["candidate.v", *("fixed-" + boundary.key(p) + ".v" for p in boundary.tuples(kind))]]
    pure = [*(f"fixed-{w}.v" for w in WIDTHS), *(f"boundary-fixed-{w}.v" for w in WIDTHS),
            "disabled.v", "declarations.v", "pure-true.v", "boundaries.v", "baseline-clean.v",
            "declaration-fixture-clean.v", *(f"inherited/fixed-{w}.v" for w in WIDTHS),
            *("inherited/" + name + ".v" for name in ("functions-fixed", "functions", "disabled", "signed",
              "direct", "surfaces", "bundle-surfaces", "baseline-signed")),
            *("baseline/" + name + ".v" for name in ("sint_cast_heavy_fixed", "sint_cast_heavy_parameterized",
                                                       "sint_cast_heavy_nested"))]
    require(len(paths) == 70 and len(pure) == 29, "inherited artifact inventory changed")
    paths += ["pure/" + path for path in pure]
    manifest = {}
    for path in paths:
        require((out / path).is_file(), "missing independently generated artifact: " + path)
        manifest[path] = hashlib.sha256((out / path).read_bytes()).hexdigest()
    load(root, "60a-sint-baseline").verify_hashes(root, out / "pure/baseline")
    return manifest


def prefixed_modules(source: str, prefix: str) -> str:
    modules = re.findall(r"\bmodule\s+(\w+)", source)
    require(modules and len(modules) == len(set(modules)), "missing or duplicated generated module")
    mapping = {name: prefix + name for name in modules}
    return re.sub(r"\b(?:" + "|".join(map(re.escape, modules)) + r")\b",
                  lambda match: mapping[match[0]], source)


def invalidate_summaries(out: Path) -> None:
    # Even source/tool/inventory rejection must invalidate a prior success claim.
    for path in ("qualification-60f.json", "closure/memory-qualification.json",
                 "closure/baseline-mutation/qualification.json"):
        (out / path).unlink(missing_ok=True)


def sat_script(directory: Path, files: list[str], label: str, *, steps: int | None = None,
               counterexample: bool = False) -> str:
    options = f"-seq {steps} " if steps is not None else ""
    # No -set-init-zero: each DUT's untouched memory/register state is arbitrary.
    options += "-prove equal_result 1 -show-inputs -show-outputs"
    if counterexample:
        for extension in ("json", "vcd"):
            (directory / (label + "-witness." + extension)).unlink(missing_ok=True)
        options += f" -dump_json {label}-witness.json -dump_vcd {label}-witness.vcd"
    # Never opt_merge the two DUTs: matching uninitialized FF inputs must not
    # correlate their independent arbitrary initial Q values. Only local constant
    # folding and dead-cone removal are used before the SAT state model.
    commands = ["read_verilog " + " ".join(files), "hierarchy -check -top ClosureMiter",
                "proc", "flatten", "memory_map", "opt_expr", "opt_clean", "dffunmap", "opt_clean",
                "check -assert", "sat " + options]
    (directory / (label + ".ys")).write_text("\n".join(commands) + "\n")
    output = run(["yosys", "-s", label + ".ys"], directory, label,
                 SAT_FAIL if counterexample else SAT_PASS)
    if counterexample:
        for extension in ("json", "vcd"):
            witness = directory / (label + "-witness." + extension)
            require(witness.is_file() and witness.stat().st_size > 0, "solver did not save its witness: " + str(witness))
    return output


def baseline_mutation(root: Path, out: Path) -> None:
    (out / "closure/baseline-mutation/qualification.json").unlink(missing_ok=True)
    baseline = load(root, "60a-sint-baseline")
    source = out / "pure/baseline"
    baseline.verify_hashes(root, source)
    directory = out / "closure/baseline-mutation"
    directory.mkdir(parents=True, exist_ok=True)
    candidate = (source / "sint_cast_heavy_parameterized.v").read_text()
    mutant, count = re.subn(r"\bassign\s+negative_out\s*=\s*[^;]+;",
                            "assign negative_out = 8'h00;", candidate)
    require(count == 1, "exact 60a mutation must replace one negative_out assignment")
    # Only module identifiers in an isolated reference copy are prefixed. Neither
    # reference arithmetic nor candidate expressions are reconstructed here.
    (directory / "gold.v").write_text(prefixed_modules((source / "sint_cast_heavy_fixed.v").read_text(), "Gold_"))
    (directory / "candidate.v").write_text(candidate)
    (directory / "mutant.v").write_text(mutant)
    (directory / "external.v").write_text("""module SIntCastHeavyExternal #(parameter integer WIDTH=8)(
input wire [WIDTH-1:0] din, output wire [WIDTH-1:0] dout);
assign dout=din;
endmodule
""")
    # The cone is precisely the 60a mutation. Other outputs are open; constants on
    # unrelated inputs prune memory/division, never constrain arbitrary left.
    connections = (".left(left),.clk(1'b0),.enable(1'b0),.choose_left(1'b0),"
                   ".write_enable(1'b0),.address(2'd0),.right(8'd0),.third(8'd0),"
                   ".divisor(8'd1),.memory_write_data(8'd0)")
    (directory / "miter.v").write_text(f"""module ClosureMiter(input wire [7:0] left,
output wire equal_result, output wire [7:0] gold_negative, candidate_negative);
Gold_SIntCastHeavyBaseline gold({connections},.negative_out(gold_negative));
SIntCastHeavyBaseline #(.WIDTH(8)) candidate({connections},.negative_out(candidate_negative));
assign equal_result = gold_negative == candidate_negative;
endmodule
""")
    sat_script(directory, ["gold.v", "candidate.v", "external.v", "miter.v"], "positive-control")
    sat_script(directory, ["gold.v", "mutant.v", "external.v", "miter.v"], "negative-control", counterexample=True)
    wave = json.loads((directory / "negative-control-witness.json").read_text())
    signals = {entry["name"].lstrip("\\"): entry for entry in wave.get("signal", []) if isinstance(entry, dict)}
    require("left" in signals, "solver witness does not contain the arbitrary signed input")
    data = signals["left"].get("data", [])
    require(data and re.fullmatch(r"[01]{8}", str(data[0])) is not None,
            "solver witness must contain a fully defined 8-bit left value")
    value = int(data[0], 2)
    require(value != 0, "solver witness cannot expose the exact mutation at left=0")
    (directory / "replay.v").write_text(f"""module WitnessReplay;
reg [7:0] left;
wire equal_result;
wire [7:0] gold_negative, candidate_negative;
ClosureMiter dut(.left(left),.equal_result(equal_result),.gold_negative(gold_negative),
.candidate_negative(candidate_negative));
initial begin
left=8'h{value:02x}; #1;
if(equal_result !== 1'b0 || gold_negative !== 8'h{(-value) & 255:02x} || candidate_negative !== 8'h00) begin
$display("FAIL:60A_SOLVER_WITNESS_REPLAY"); $finish;
end
$display("SIXTY_A_SOLVER_WITNESS_REPLAY_OK"); $finish;
end
endmodule
""")
    run(["iverilog", "-g2001", "-s", "WitnessReplay", "-o", "replay.vvp", "gold.v", "mutant.v",
         "external.v", "miter.v", "replay.v"], directory, "replay-compile")
    replay = run(["vvp", "replay.vvp"], directory, "replay")
    require("SIXTY_A_SOLVER_WITNESS_REPLAY_OK" in replay and "FAIL:" not in replay,
            "independent simulator did not reproduce the solver counterexample")
    (directory / "qualification.json").write_text(json.dumps({
        "mutation": "assign negative_out = 8'h00;", "positive_control": "proved",
        "negative_control": "solver_counterexample", "witness_left": value,
        "icarus_replay": "passed"}, indent=2) + "\n")
    print("60f exact 60a mutation: positive proof, genuine SAT witness and Icarus replay PASS", flush=True)


def memory_validity(root: Path, out: Path) -> None:
    (out / "closure/memory-qualification.json").unlink(missing_ok=True)
    declaration = load(root, "60c-signed-declarations")
    candidate = (out / "pure/declaration-fixture-clean.v").read_text()
    for width in WIDTHS:
        directory = out / f"closure/memory-{width}"
        directory.mkdir(parents=True, exist_ok=True)
        native = (out / f"pure/inherited/fixed-{width}.v").read_text()
        physical = declaration.ports(native)
        inputs = [(bits, name) for direction, bits, name in physical if direction == "input"]
        require({name for _, name in inputs} == {"clk", "enable", "choose", "write", "address", "amount",
                                                    "a", "b", "raw", "wideIn"}, "memory fixture inputs changed")
        (directory / "gold.v").write_text(prefixed_modules(native, "Gold_"))
        (directory / "candidate.v").write_text(candidate)
        mutant, count = re.subn(r"\bassign\s+memOut\s*=\s*[^;]+;", "assign memOut = 0;", candidate)
        require(count == 1, "memory observability mutation must affect exactly one output")
        (directory / "mutant.v").write_text(mutant)
        ports = ",\n".join(f"input wire [{bits-1}:0] {name}" for bits, name in inputs)
        connections = ",".join(f".{name}({name})" for _, name in inputs)
        (directory / "miter.v").write_text(f"""module ClosureMiter({ports},
output wire equal_result, output reg memory_valid, output reg register_valid,
output wire [{width-1}:0] gold_memory, candidate_memory, gold_register, candidate_register);
// Only harness validity is initialized. DUT memory and data registers remain arbitrary.
reg [1:0] written;
initial begin written=2'b00; memory_valid=1'b0; register_valid=1'b0; end
always @(posedge clk) begin
  if(write) written[address] <= 1'b1;
  // readFirst: a simultaneous first write/read still returns uninitialized old data.
  if(enable) begin memory_valid <= written[address]; register_valid <= 1'b1; end
end
Gold_SignedDeclarations gold({connections},.memOut(gold_memory),.regOut(gold_register));
SignedDeclarations #(.WIDTH({width})) candidate({connections},.memOut(candidate_memory),.regOut(candidate_register));
assign equal_result = (!memory_valid || gold_memory == candidate_memory) &&
                      (!register_valid || gold_register == candidate_register);
endmodule
""")
        sat_script(directory, ["gold.v", "candidate.v", "miter.v"], "validity-proof", steps=MEMORY_STEPS)
        sat_script(directory, ["gold.v", "mutant.v", "miter.v"], "validity-observability",
                   steps=MEMORY_STEPS, counterexample=True)
        masked = (directory / "miter.v").read_text()
        original_comparison = ("assign equal_result = (!memory_valid || gold_memory == candidate_memory) &&\n"
                               "                      (!register_valid || gold_register == candidate_register);")
        require(masked.count(original_comparison) == 1, "memory validity comparison must be unique")
        for state in ("memory", "register"):
            # Separate controls prove that neither memory nor register initial
            # values were silently correlated by preprocessing. At step 1 there
            # has been no write or enabled read/update; equality is not promised.
            unmasked = masked.replace(original_comparison,
                                      f"assign equal_result = gold_{state} == candidate_{state};")
            name = f"uninitialized-{state}"
            (directory / (name + ".v")).write_text(unmasked)
            sat_script(directory, ["gold.v", "candidate.v", name + ".v"], name,
                       steps=1, counterexample=True)
        print(f"60f WIDTH={width}: {MEMORY_STEPS}-cycle validity proof, live mutation and independent memory/register initial-state controls PASS", flush=True)
    (out / "closure/memory-qualification.json").write_text(json.dumps({
        "widths": list(WIDTHS), "bounded_steps": MEMORY_STEPS,
        "dut_initialization": "independent arbitrary states; no zero initialization",
        "memory_comparison": "enabled synchronous read of previously written word; readFirst; validity holds when disabled",
        "register_comparison": "after first enabled update; validity holds when disabled",
        "input_constraints": "none", "observability_mutations": len(WIDTHS),
        "independent_initial_state_controls": {"memory": len(WIDTHS), "register": len(WIDTHS), "steps": 1},
        "unbounded_equivalence": "retained inherited 60c/60d induction gates"}, indent=2) + "\n")


def qualify(root: Path, out: Path, closure_only: bool = False) -> None:
    invalidate_summaries(out)
    for tool in ("yosys", "iverilog", "vvp", "verilator"):
        require(shutil.which(tool) is not None, "missing required tool: " + tool)
    manifest = inventory(root, out)
    if not closure_only:
        boundary = load(root, "60e-signedness-boundaries")
        boundary.qualify(root, out / "boundaries", tuple(boundary.KINDS), ("simulation", "formal", "tools"))
        boundary.mutations(out / "boundaries")
        load(root, "60d-pure-sint-casts").qualify(root, out / "pure")
    baseline_mutation(root, out)
    memory_validity(root, out)
    require(inventory(root, out) == manifest, "qualification mutated independently generated RTL")
    (out / "qualification-60f.json").write_text(json.dumps({
        "scope": "supplementary closure only" if closure_only else "full inherited and closure qualification",
        "independent_generated_files": len(manifest), "sha256": manifest,
        "boundary_equivalence_tuples": None if closure_only else 64,
        "arithmetic_domain": "same nonzero divisor mapping on both inherited proof legs",
        "exact_60a_mutation": "SAT counterexample, positive proof and Icarus witness replay",
        "memory_widths": list(WIDTHS), "memory_bounded_steps": MEMORY_STEPS,
        "independent_initial_state_counterexamples": 2 * len(WIDTHS),
        "default_signedness_cleanup": "unchanged; opt-in"}, indent=2) + "\n")
    print("60f equivalence, defined domains and solver-counterexample closure PASS", flush=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", nargs="?", type=Path)
    parser.add_argument("--source-only", action="store_true")
    parser.add_argument("--self-test", action="store_true", help="run result-classification controls without external tools")
    parser.add_argument("--closure-only", action="store_true", help="run supplementary proofs only; never claims full qualification")
    parser.add_argument("--skip-source", action="store_true", help="artifact stages after a separately completed source gate")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    output = args.output.resolve() if args.output else None
    if output is not None:
        invalidate_summaries(output)
    self_test()
    if args.self_test and not args.output and not args.source_only:
        return
    if not args.skip_source:
        source_scope(root)
    if not args.source_only:
        require(args.output is not None, "artifact output directory is required")
        qualify(root, output, args.closure_only)


if __name__ == "__main__":
    main()
