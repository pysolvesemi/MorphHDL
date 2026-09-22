#!/usr/bin/env python3
"""Validate emitted lane/condition RTL, not a compiler or HDL postprocessor.

Both input directories must contain all 18 artifacts from the production
LaneInliningRegressionWriter. The baseline is independently built from the
reported compiler revision with only the four standalone test sources added.
Only explicitly labelled negative controls alter copied generated RTL.
A tool error or timeout is never accepted as a mutation counterexample.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
import time
from pathlib import Path

CASES = {
    "lane": ("LaneExpressionExample", "LaneExpressionExample_tb",
             "PASS: 2833 independent lane vectors; PPC4=0/1; all lanes; modular boundaries; X/Z"),
    "conditions": ("LaneConditionCoverageExample", "tb_lane_conditions",
                   "PASS topology=conditions checks=6412 PPC4=0,1"),
    "receivers": ("LaneReceiverCoverageExample", "tb_lane_receivers",
                  "PASS topology=receivers checks=10510 PPC4=0,1"),
}
MODES = ("default", "enabled", "disabled")
ROUNDS = ("first", "repeat")
SUCCESS = "SAT proof finished - no model found: SUCCESS!"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(message)


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def artifact(root: Path, topology: str, mode: str = "enabled", round_: str = "first") -> Path:
    return root / round_ / mode / topology / "design.v"


def inventory(root: Path) -> dict[str, str]:
    expected = {f"{r}/{m}/{t}/design.v" for r in ROUNDS for m in MODES for t in CASES}
    actual = {p.relative_to(root).as_posix() for p in root.rglob("*.v")}
    require(actual == expected, f"incomplete or unexpected RTL inventory: {root}: {actual ^ expected}")
    return {p: digest(root / p) for p in sorted(expected)}


def canonical_disabled_resizes(text: str, *, candidate: bool, topology: str,
                               roles: tuple[tuple[str, str, str], ...]) -> str:
    """Canonicalize only authenticated retained symbolic output resizes.

    CDC-WIRE-01 deliberately stopped assigning ``morphhdl_resize*`` weak names
    to unnamed native resize nodes.  Disabled artifacts therefore keep the same
    declarations and assignments under ordinary backend names.  Match each node
    by its exact driver and output receiver, then require byte identity after
    replacing only its identifier.  This is a validator comparison; emitted RTL
    is never rewritten.
    """
    identifier = r"[A-Za-z_][A-Za-z0-9_$]*"
    names: list[str] = []
    canonical = text
    for source, output, placeholder in roles:
        matches = re.findall(
            rf"(?m)^\s*assign\s+({identifier})\s*=\s*{source}\[", text)
        require(len(matches) == 1,
                f"disabled {topology} {source} resize carrier inventory changed")
        name = matches[0]
        require(re.search(rf"(?m)^\s*assign\s+{output}\s*=\s*{re.escape(name)}\s*;\s*$", text)
                is not None, f"disabled {topology} {output} resize receiver changed")
        names.append(name)
        canonical = re.sub(rf"\b{re.escape(name)}\b", placeholder, canonical)
    require(len(set(names)) == len(names), f"disabled {topology} resize carriers alias")
    if candidate:
        require(all(not name.startswith("morphhdl_resize") for name in names),
                "candidate retained compiler-injected resize naming")
        require(all(name.startswith("_zz_") for name in names),
                "candidate resize carrier did not use ordinary generated naming")
    else:
        require(all(name.startswith("morphhdl_resize") for name in names),
                "baseline no longer demonstrates compiler-injected resize naming")
    return canonical


def canonical_disabled_lane(text: str, *, candidate: bool) -> str:
    return canonical_disabled_resizes(
        text, candidate=candidate, topology="lane",
        roles=(("laneDe", "io_de", "__LANE_DE_RESIZE__"),
               ("laneFrameEnd", "io_frameEnd", "__LANE_FRAME_END_RESIZE__")))


def canonical_disabled_receivers(text: str, *, candidate: bool) -> str:
    return canonical_disabled_resizes(
        text, candidate=candidate, topology="receivers",
        roles=(("lanes", "io_de", "__RECEIVERS_DE_RESIZE__"),))


def structural(before: Path, after: Path) -> None:
    for topology in CASES:
        for mode in MODES:
            for root in (before, after):
                require(artifact(root, topology, mode).read_bytes() ==
                        artifact(root, topology, mode, "repeat").read_bytes(),
                        f"nondeterministic {root} {mode} {topology}")
            if mode != "disabled":
                require(artifact(after, topology, mode).read_bytes() ==
                        artifact(after, topology).read_bytes(), f"default/enabled mismatch: {topology}")
        if topology in ("lane", "receivers"):
            old_disabled = artifact(before, topology, "disabled").read_text()
            new_disabled = artifact(after, topology, "disabled").read_text()
            canonicalize = (canonical_disabled_lane if topology == "lane"
                            else canonical_disabled_receivers)
            require(canonicalize(old_disabled, candidate=False) ==
                    canonicalize(new_disabled, candidate=True),
                    f"disabled {topology} RTL changed beyond ordinary resize carrier names")
        else:
            require(artifact(before, topology, "disabled").read_bytes() ==
                    artifact(after, topology, "disabled").read_bytes(),
                    f"disabled RTL changed: {topology}")
    old = artifact(before, "lane").read_text()
    new = artifact(after, "lane").read_text()
    require("assign _zz_laneDe = {3'd0, laneX_0};" in old and
            "assign _zz_laneFrameEnd" in old and "assign when_" in old,
            "baseline must demonstrate BOTH reported defects")
    require("_zz_laneDe" not in new and "_zz_laneFrameEnd" not in new and
            "when_" not in new, "eligible lane/condition carriers survive in final RTL")
    require(old.count("always @") == new.count("always @"), "lane process count changed")
    for n in range(4):
        require(f"laneDe[{n}] = ((io_running && ({{3'd0, laneX_{n}}} < io_hActive)) && "
                f"({{4'd0, laneY_{n}}} < io_vActive));" in new, f"laneDe {n} not inlined exactly")
        require(f"laneFrameEnd[{n}] = ((io_running && ({{3'd0, laneX_{n}}} == "
                f"(io_hActive - 16'h0001))) && ({{4'd0, laneY_{n}}} == "
                f"(io_vActive - 16'h0001)));" in new, f"frameEnd {n} lost its modular boundary")
        require(new.count(f"if((laneX_{n} == (io_hTotal - 13'h0001)))") == 2,
                f"split X/Y predicate {n} not substituted everywhere")
        require(f"if((laneY_{n} == (io_vTotal - 12'h001)))" in new,
                f"nested Y predicate {n} not substituted")
    conditions = artifact(after, "conditions").read_text()
    declarations = [line for line in conditions.splitlines()
                    if re.search(r"\bwire\b", line) and "when_" in line and "_zz_" not in line]
    require(len(declarations) == 2 and all("(* keep *)" in line for line in declarations),
            "protected condition inventory changed or unprotected conditions survived")
    require("if(when_example_l42)" in conditions and
            "assign io_booleanRhs = (io_y == (io_vTotal - 12'h001));" in conditions,
            "lookalike protection or mixed Boolean RHS handling regressed")
    receivers = artifact(after, "receivers").read_text()
    require("_zz_io_whole" not in receivers and "_zz_lanes" not in receivers,
            "whole-Bool/disjoint-bit optimization mismatch")
    require(all(name in receivers for name in ("_zz_overlapping", "_zz_dynamicallySelected", "_zz_keptLane")),
            "overlap/dynamic/protected boundaries were not retained")


def comparison_script(baseline: Path, candidate: Path, top: str, binding: int,
                      counterexample: Path | None = None) -> str:
    # Lower each independent procedural design BEFORE constructing/flattening
    # the miter. Flattening unlowered processes can discard their driven values
    # and make the comparison vacuous. Stashes also isolate parameter caches.
    def prepare(path: Path, renamed: str, stash: str) -> str:
        return f"""read_verilog {json.dumps(str(path))}
chparam -set PPC4 {binding} {top}
hierarchy -check -top {top}
proc
opt_clean
rename {top} {renamed}
design -stash {stash}
"""
    sat_options = "-verify" if counterexample is None else "-dump_json " + json.dumps(str(counterexample))
    return prepare(baseline, "Reference", "baseline") + prepare(candidate, "Implementation", "candidate") + f"""design -reset
design -copy-from baseline -as Reference Reference
design -copy-from candidate -as Implementation Implementation
miter -equiv -make_assert -flatten Reference Implementation compare
hierarchy -check -top compare
prep -top compare
flatten
opt_clean
sat {sat_options} -prove-asserts -show-inputs -show-outputs
"""


class Validation:
    def __init__(self, args: argparse.Namespace):
        self.args = args
        self.output = args.output.resolve()
        self.output.mkdir(parents=True, exist_ok=True)
        self.commands: list[dict] = []

    def run(self, command: list[str | Path], label: str, *, mutation: bool = False,
            formal_counterexample: bool = False) -> str:
        command = list(map(str, command))
        path = self.output / f"{label}.log"
        started = time.monotonic()
        record = {"label": label, "command": command, "expected_simulation_counterexample": mutation,
                  "expected_formal_counterexample": formal_counterexample}
        print(label, flush=True)
        with path.open("w") as stream:
            stream.write(json.dumps(command) + "\n")
            stream.flush()
            try:
                result = subprocess.run(command, cwd=self.args.repo, stdout=stream,
                                        stderr=subprocess.STDOUT, timeout=self.args.timeout, check=False)
                record["exit_code"] = result.returncode
            except subprocess.TimeoutExpired:
                record["timed_out"] = True
                raise RuntimeError(f"{label}: tool timeout, NOT a semantic counterexample")
            finally:
                stream.flush()
                record.update(seconds=round(time.monotonic() - started, 3), log_sha256=digest(path))
                self.commands.append(record)
                (self.output / "commands.json").write_text(json.dumps(self.commands, indent=2) + "\n")
        text = path.read_text()
        if formal_counterexample:
            require(result.returncode == 0 and "SAT proof finished - model found: FAIL!" in text,
                    f"{label}: formal control did not produce a semantic counterexample")
        elif mutation:
            require(result.returncode != 0 and "FAIL" in text and
                    "FATAL:" in text and "watchdog" not in text and "simulation timeout" not in text,
                    f"{label}: mutation was not rejected by the semantic oracle")
        else:
            require(result.returncode == 0, f"{label}: exit {result.returncode}; see {path}")
        return text

    def simulate(self, source: Path, topology: str, label: str, *, mutation: bool = False) -> None:
        _, tb, marker = CASES[topology]
        image = self.output / (label + ".vvp")
        self.run([self.args.iverilog, "-g2012", "-s", tb, "-o", image, source,
                  self.args.repo / f"morphhdl/src/test/resources/lane-expression/{tb}.sv"], label + "-compile")
        result = self.run([self.args.vvp, image], label, mutation=mutation)
        if not mutation:
            require(marker in result.splitlines(), f"{label}: exact simulation completion marker missing")

    def yosys(self, commands: str, label: str, *, formal_counterexample: bool = False) -> str:
        path = self.output / (label + ".ys")
        path.write_text(commands)
        return self.run([self.args.yosys, "-s", path], label, formal_counterexample=formal_counterexample)

    def qualify(self, before: Path, after: Path) -> None:
        for topology, (top, _, _) in CASES.items():
            # Same source bytes are elaborated with both parameter bindings.
            for mode in MODES:
                source = artifact(after, topology, mode)
                for binding in (0, 1):
                    label = f"{topology}-{mode}-{binding}"
                    self.run([self.args.iverilog, "-g2001", "-s", top, "-P", f"{top}.PPC4={binding}",
                              "-o", self.output / (label + ".vvp"), source], "compile-" + label)
                    self.run([self.args.verilator, "--lint-only", "--language", "1364-2001",
                              "--top-module", top, f"-GPPC4={binding}", source], "lint-" + label)
                self.simulate(source, topology, f"sim-{topology}-{mode}")
            self.simulate(artifact(before, topology), topology, f"sim-baseline-{topology}")
            for binding in (0, 1):
                label = f"{topology}-{binding}"
                baseline, candidate = artifact(before, topology), artifact(after, topology)
                c = json.dumps(str(candidate))
                proof = self.yosys(comparison_script(baseline, candidate, top, binding),
                                   "equivalence-" + label)
                require(SUCCESS in proof, f"{label}: missing SAT completion")
                netlist = self.output / ("synthesis-" + label + ".json")
                self.yosys(f'''read_verilog {c}
chparam -set PPC4 {binding} {top}
synth -top {top}
check -assert
write_json {json.dumps(str(netlist))}
''', "synthesis-" + label)
                ports = json.loads(netlist.read_text())["modules"][top]["ports"]
                names = ("io_de", "io_frameEnd") if topology == "lane" else (
                    ("io_witnessIn", "io_witnessOut") if topology == "conditions" else ("io_de",))
                for name in names:
                    require(len(ports[name]["bits"]) == 1 + binding * 3,
                            f"{top}/{name}: wrong elaborated width at PPC4={binding}")

    def mutations(self, before: Path, after: Path) -> None:
        mutants = (
            ("lane", "extension", "{3'd0, laneX_0}", "{3'd1, laneX_0}"),
            ("lane", "active-subtraction", "(io_hActive - 16'h0001)", "(io_hActive - 16'h0002)"),
            ("lane", "coordinate-subtraction", "(io_hTotal - 13'h0001)", "(io_hTotal - 13'h0002)"),
            ("conditions", "priority", "if((io_x == 13'h0))", "if((io_x == 13'h0001))"),
            ("conditions", "four-state-equality", "if((io_y == (io_vTotal - 12'h001)))",
             "if((io_y === (io_vTotal - 12'h001)))"),
        )
        for topology, label, old, new in mutants:
            source = artifact(after, topology).read_text()
            require(old in source and old != new, "mutation site missing: " + label)
            mutated = source.replace(old, new, 1)
            path = self.output / ("mutant-" + label + ".v")
            path.write_text(mutated)
            self.simulate(path, topology, "mutation-" + label, mutation=True)
            # == and === agree in the two-state SAT model; that control is
            # deliberately simulation-only. All other controls must also break
            # the same formal comparison used for the positive proof.
            if label != "four-state-equality":
                top = CASES[topology][0]
                model = self.output / ("formal-mutation-" + label + ".json")
                model.unlink(missing_ok=True)
                self.yosys(comparison_script(artifact(before, topology), path, top, 1, model),
                           "formal-mutation-" + label, formal_counterexample=True)
                require(model.is_file(), "formal counterexample model missing: " + label)
                signals = json.loads(model.read_text()).get("signal", [])
                require(any(signal.get("name") == "trigger" and
                            signal.get("wave", "").startswith("1") for signal in signals),
                        "counterexample does not assert the comparison mismatch: " + label)



def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--before", required=True, type=Path)
    parser.add_argument("--after", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--compare-scala", type=Path)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--iverilog", default="iverilog")
    parser.add_argument("--vvp", default="vvp")
    parser.add_argument("--yosys", default="yosys")
    parser.add_argument("--verilator", default="verilator")
    parser.add_argument("--timeout", type=int, default=180)
    args = parser.parse_args()
    args.repo, args.before, args.after = args.repo.resolve(), args.before.resolve(), args.after.resolve()
    validation = Validation(args)
    # Invalidate the previous receipt before any input/profile preflight can fail.
    summary = validation.output / "summary.json"
    summary.unlink(missing_ok=True)
    (validation.output / "commands.json").unlink(missing_ok=True)
    require(args.timeout > 0, "timeout must be positive")
    inventories = {"baseline": inventory(args.before), "candidate": inventory(args.after)}
    structural(args.before, args.after)
    if args.compare_scala:
        require(inventories["candidate"] == inventory(args.compare_scala.resolve()),
                "cross-Scala emitted artifact mismatch")
    validation.qualify(args.before, args.after)
    validation.mutations(args.before, args.after)
    summary.write_text(json.dumps({"status": "passed", "scope": "emitted RTL validation only",
        "formal_model": "two-state combinational SAT; X/Z semantics checked by independent simulation",
        "artifacts": inventories, "cross_scala_compared": bool(args.compare_scala),
        "simulations": 12, "strict_compiles": 18, "lint_runs": 18, "equivalence_proofs": 6,
        "synthesis_and_width_checks": 6, "semantic_mutations_rejected": 5, "formal_mutations_rejected": 4,
        "command_count": len(validation.commands)}, indent=2) + "\n")
    print("LANE_WHEN_VALIDATION_PASS artifacts=18 proofs=6 simulations=12 mutations=5 formal_mutations=4")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, ValueError, KeyError) as error:
        print(f"LANE_WHEN_VALIDATION_FAIL: {error}", file=sys.stderr)
        sys.exit(1)
