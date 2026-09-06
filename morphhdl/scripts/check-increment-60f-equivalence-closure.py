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
import tempfile
from pathlib import Path

BASE = "feca6b9d599d97af92ed9f6a8bc871ef008c395e"
COMPLETED_60F = "5a669d32095ee722c313bd069b771e7c350a1f81"
INHERITED_TRACKS = {"60c": "60c-signed-declarations", "60d": "60d-pure-sint-casts",
                    "60e": "60e-signedness-boundaries"}
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
    source_scope_self_test()


def qualification_interval(root: Path, baseline: str, completed: str) -> None:
    """Seal this completed increment's scope without freezing later work."""
    for older, newer in ((baseline, completed), (completed, "HEAD")):
        subprocess.run(["git", "merge-base", "--is-ancestor", older, newer], cwd=root, check=True)
    changed = subprocess.check_output(
        ["git", "diff", "--no-renames", "--name-only", baseline, completed], cwd=root, text=True).splitlines()
    production = sorted(path for path in changed if re.search(r"(?:^|/)src/main/", path))
    require(not production, "completed 60f interval changed production sources:\n" + "\n".join(production))


def source_scope_self_test() -> None:
    """Exercise the historical boundary and the real current native auditor."""
    audit_path = Path(__file__).with_name("check-native-source-preservation.py")
    spec = importlib.util.spec_from_file_location("closure_native_audit", audit_path)
    require(spec is not None and spec.loader is not None, "cannot load native audit controls")
    audit = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(audit)
    with tempfile.TemporaryDirectory(prefix="morphhdl-60f-scope-") as directory:
        root = Path(directory) / "repository"
        root.mkdir()

        def git(*args: str) -> str:
            return subprocess.check_output(["git", *args], cwd=root, text=True).strip()

        def commit(message: str) -> str:
            git("add", ".")
            git("-c", "core.hooksPath=/dev/null", "commit", "-qm", message)
            return git("rev-parse", "HEAD")

        git("init", "-q")
        git("config", "user.name", "MorphHDL scope control")
        git("config", "user.email", "scope@example.invalid")
        for index, source_root in enumerate(audit.EXPECTED_SOURCE_ROOTS):
            marker = root / source_root / "scala" / f"Marker{index}.scala"
            marker.parent.mkdir(parents=True)
            marker.write_text(f"object Marker{index}\n")
        upstream = commit("upstream")
        config = root / audit.DEFAULT_UPSTREAM_CONFIG
        config.parent.mkdir(parents=True, exist_ok=True)
        config.write_text("UPSTREAM_COMMIT=" + upstream + "\n")
        initial = "core/src/main/scala/InitialSupport.scala"
        (root / initial).write_text("object InitialSupport\n")
        baseline = commit("reviewed implementation before qualification")

        def reviewed_support(path: str) -> dict:
            return dict(path=path, baseline_path=None, change="added", classification="typed-support-file",
                        introduced_by=["source-scope control"], reason="explicitly reviewed native support", edits=[])

        policy = dict(schema_version=1, repository=audit.EXPECTED_REPOSITORY,
                      baseline_commit=upstream, files=[reviewed_support(initial)])
        policy_path = Path(directory) / "review.json"
        manifest_path = Path(directory) / "manifest.json"

        def approve_current() -> None:
            policy_path.write_text(json.dumps(policy))
            manifest_path.write_text(json.dumps(audit.generate_manifest_value(root, policy_path)))
            audit.validate_repository(root, manifest_path)

        approve_current()
        (root / "qualification.txt").write_text("tests and evidence only\n")
        completed = commit("completed qualification-only increment")
        qualification_interval(root, baseline, completed)
        audit.validate_repository(root, manifest_path)

        later = "core/src/main/scala/LaterSupport.scala"
        (root / later).write_text("object LaterSupport\n")
        current = commit("later implementation increment")
        qualification_interval(root, baseline, completed)
        try:
            audit.validate_repository(root, manifest_path)
        except audit.AuditError as error:
            require(error.code.endswith("UNAPPROVED-PATH"), "wrong unreviewed-source rejection: " + str(error))
        else:
            raise RuntimeError("later native production changes escaped the current approved-source audit")
        policy["files"].append(reviewed_support(later))
        approve_current()
        try:
            qualification_interval(root, baseline, current)
        except RuntimeError as error:
            require("changed production sources" in str(error), "wrong historical source rejection")
        else:
            raise RuntimeError("production changes were accepted inside a qualification-only interval")

        (root / later).write_text("object LaterSupport { val unreviewed = true }\n")
        try:
            audit.validate_repository(root, manifest_path)
        except audit.AuditError as error:
            require(error.code.endswith("DIRTY-WORKTREE"), "wrong dirty-source rejection: " + str(error))
        else:
            raise RuntimeError("uncommitted native production changes escaped the current source audit")
    print("60f historical scope and current approved native-source controls PASS", flush=True)


def source_scope(root: Path) -> None:
    qualification_interval(root, BASE, COMPLETED_60F)
    frozen = [
        "morphhdl/scripts/check-increment-60a-sint-baseline.py",
        "morphhdl/scripts/check-increment-60c-signed-declarations.py",
        "morphhdl/scripts/check-increment-60d-pure-sint-casts.py",
        "morphhdl/scripts/check-increment-60e-signedness-boundaries.py",
        "morphhdl/src/test/scala/nativeapplication/SIntSignedVerilogBaselineFixture.scala",
        "morphhdl/src/test/scala/nativeapplication/SIntSignedDeclarationsFixture.scala",
        "morphhdl/src/test/scala/nativeapplication/PureSIntCastFixture.scala",
        "morphhdl/src/test/scala/spinal/core/SignednessBoundaryFixture.scala",
        "morphhdl/src/main/scala/spinal/core/internals/MorphHdlSignednessAnalysis.scala",
        "morphhdl/src/main/scala/morphhdl/analysis/SignednessFacts.scala",
        "morphhdl/contracts/increment-60d-emitter-edits.json",
        "morphhdl/contracts/increment-60e-boundary-edits.json",
    ]
    for path in frozen:
        old = subprocess.check_output(["git", "show", BASE + ":" + path], cwd=root)
        require((root / path).read_bytes() == old, "sealed oracle/authority/contract changed: " + path)
    # Preserve the predecessor source gates' ongoing inference bans on current
    # policies; these are not historical diff-scope restrictions.
    for name in ("MorphHdlSignedWidth.scala", "MorphHdlSignedDeclarationPolicy.scala", "MorphHdlPureSIntCastPolicy.scala"):
        source = (root / "morphhdl/src/main/scala/spinal/core/internals" / name).read_text()
        for token in ("getName", "definitionName", "getScalaLocation", "ThreadLocal", "replaceAll", ".r\n"):
            require(token not in source, "signedness authority uses forbidden inference: " + token)
    # The inherited restoration checks describe the implementation that 60f
    # qualified. Re-run them on that exact completed tree; later increments
    # legitimately extend these same production files. Their current native
    # changes must still pass the complete path/blob/span audit below, and the
    # current semantic oracles above remain sealed to their original bytes.
    with tempfile.TemporaryDirectory(prefix="morphhdl-60f-history-") as directory:
        historical = Path(directory) / "completed"
        subprocess.run(["git", "worktree", "add", "--quiet", "--detach", str(historical), COMPLETED_60F],
                       cwd=root, check=True)
        try:
            for suffix in ("60c-signed-declarations", "60d-pure-sint-casts", "60e-signedness-boundaries"):
                load(historical, suffix).source_scope(historical)
        finally:
            subprocess.run(["git", "worktree", "remove", "--force", str(historical)], cwd=root, check=True)
    subprocess.run(["python3", "morphhdl/scripts/check-native-source-preservation.py"], cwd=root, check=True)
    print("60f completed qualification-only scope, sealed current oracles and current native audits PASS", flush=True)


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


def qualify_inherited(root: Path, out: Path, track: str) -> None:
    """Run a sealed predecessor's unchanged behavioral gates on current RTL."""
    require(track in INHERITED_TRACKS, "unknown inherited qualification track: " + track)
    inherited = load(root, INHERITED_TRACKS[track])
    if track == "60e":
        inherited.qualify(root, out, tuple(inherited.KINDS), ("simulation", "formal", "tools"))
        inherited.mutations(out)
    else:
        # Both 60c and 60d qualify() include their original mutation gates.
        inherited.qualify(root, out)
    print(track + " inherited behavioral qualification on current artifacts PASS", flush=True)


def qualify(root: Path, out: Path, closure_only: bool = False) -> None:
    invalidate_summaries(out)
    for tool in ("yosys", "iverilog", "vvp", "verilator"):
        require(shutil.which(tool) is not None, "missing required tool: " + tool)
    manifest = inventory(root, out)
    if not closure_only:
        qualify_inherited(root, out / "boundaries", "60e")
        qualify_inherited(root, out / "pure", "60d")
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
    parser.add_argument("--self-test", action="store_true", help="run solver-result and real Git/native-audit source-scope controls")
    parser.add_argument("--closure-only", action="store_true", help="run supplementary proofs only; never claims full qualification")
    parser.add_argument("--inherited-track", choices=tuple(INHERITED_TRACKS),
                        help="run one unchanged predecessor's full artifact qualification after the historical/current source gate")
    parser.add_argument("--skip-source", action="store_true", help="artifact stages after a separately completed source gate")
    args = parser.parse_args()
    require(not (args.inherited_track and args.closure_only), "inherited-track cannot select supplementary 60f closure")
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
        if args.inherited_track:
            qualify_inherited(root, output, args.inherited_track)
        else:
            qualify(root, output, args.closure_only)


if __name__ == "__main__":
    main()
