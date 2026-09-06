#!/usr/bin/env python3
"""Independent native-reference qualification of signedness boundaries.

Each candidate is emitted once. A separate ordinary SpinalHDL elaboration is
used for every concrete tuple. Vec reference ports are joined only in the test
wrapper; candidate RTL and reference arithmetic are never regenerated or edited
by the proof. The shared Vec harness maps all addresses onto the complete legal
index domain, without claiming equivalence for native out-of-range Vec behavior.
"""
from __future__ import annotations

import argparse
import importlib.util
import itertools
import json
import re
import shutil
import subprocess
from pathlib import Path

BASE = "6c2d0027c36076942c03bd2a4f6d4df1b7934962"
QUALIFIED_59B = "b0a4388e3babbc01500a620eefe6c0965e9e6343"
QUALIFIED_60E = "dc8cab41cf3fd41b026ba7359f30cb596b14d015"
WIDTHS = (1, 5, 8, 32)
DEPTHS = (1, 3, 5, 8)
KINDS = {
    "scalars": "SignedBoundaryScalars",
    "bundles": "SignedBoundaryBundles",
    "vectors": "SignedBoundaryVectors",
    "vec-hierarchy": "SignedBoundaryVecHierarchy",
    "hierarchy": "SignedBoundaryHierarchy",
    "channels": "SignedBoundaryChannels",
}
EXTERNAL = """// Test-owned implementation; external ports deliberately remain unsigned.
module SignedBoundaryExternal #(
  parameter LABEL="boundary", parameter integer WIDTH=8,
  parameter integer COUNT=2, parameter integer DOUBLE_WIDTH=16,
  parameter integer ENABLED=1
)(input wire [WIDTH-1:0] din, output wire [WIDTH-1:0] dout);
assign dout = ENABLED ? din : ~din;
endmodule
"""


def require(ok: bool, detail: str) -> None:
    if not ok:
        raise RuntimeError(detail)


def run(command: list[str], cwd: Path, label: str, timeout: int = 240) -> str:
    result = subprocess.run(command, cwd=cwd, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True, timeout=timeout)
    (cwd / (label + ".log")).write_text(result.stdout)
    require(result.returncode == 0, label + " failed:\n" + result.stdout[-16000:])
    return result.stdout


def restore_59d_signed_width_authority(root: Path, path: str, source: str) -> str:
    """Undo only the reviewed width-owner adapter; the complete old authority is frozen."""
    authority = "morphhdl/src/main/scala/spinal/core/internals/MorphHdlSignednessAnalysis.scala"
    contract_path = root / "morphhdl/contracts/increment-59d-signed-width-edits.json"
    if path != authority or not contract_path.is_file():
        return source
    contract = json.loads(contract_path.read_text())
    require(set(contract) == {"base", "edits"} and
            contract["base"] == "5a669d32095ee722c313bd069b771e7c350a1f81",
            "59d signed-width restoration baseline changed")
    require([entry["id"] for entry in contract["edits"]] ==
            ["signed-width-fresh-owner", "signed-width-validated-token-owner",
             "signed-width-authority-route", "signed-width-token-construction-owner",
             "signed-width-capture-owner"] and
            all(set(entry) == {"path", "id", "before", "after"} and
                entry["path"] == authority for entry in contract["edits"]),
            "59d signed-width restoration exceeds its five exact authority seams")
    for edit in reversed(contract["edits"]):
        require(source.count(edit["after"]) == 1,
                "missing/duplicate 59d signed-width span in " + path)
        source = source.replace(edit["after"], edit["before"], 1)
    return source


def restore_60d_source(root: Path, path: str, source: str) -> str:
    """Undo only exact reviewed 60e spans; inherited 60c/60d guards still run."""
    # 59d publishes exact native widths, msb and resize operations through
    # separately reviewed seams. Undo those first, including seams inside a 60e resize
    # call. The complete restored source is still compared with its historical
    # baseline by every inherited scope guard; no unrelated edit is admitted.
    width_contract = root / "morphhdl/contracts/increment-59d-width-publication-edits.json"
    fallback = "morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala"
    if width_contract.is_file() and path == fallback:
        width = json.loads(width_contract.read_text())
        require(set(width) == {"base", "edits"} and
                width["base"] == "d3a0f112ce3cab9f074e5a7cbbc165c9878ff40a",
                "59d width publication restoration baseline changed")
        require([entry["id"] for entry in width["edits"]] ==
                ["native-high-bit-publication", "native-resize-publication",
                 "native-resize-single-owner", "native-resize-target-width",
                 "native-resize-domain-proof", "native-resize-result-width",
                 "native-high-bit-domain-proof", "width-complete-domain-relation",
                 "width-complete-domain-expression", "width-multi-root-projection-route",
                 "width-multi-root-projection-origins", "width-multi-root-owner-evaluation",
                 "native-resize-normalized-assignment-proof",
                 "native-publication-validation-session",
                 "native-retained-modular-uint-result",
                 "native-resize-published-width-validation",
                 "native-resize-declaration-width-matcher"] and
                all(set(entry) == {"path", "id", "before", "after"} and
                    entry["path"] == fallback for entry in width["edits"]),
                "59d width publication restoration exceeds its seventeen fallback seams")
        for edit in reversed(width["edits"]):
            require(source.count(edit["after"]) == 1, "missing/duplicate 59d span in " + path)
            source = source.replace(edit["after"], edit["before"], 1)
    descendant = root / "morphhdl/scripts/check-increment-59f-source-scope.py"
    if descendant.exists():
        spec = importlib.util.spec_from_file_location("publisher_59f_scope", descendant)
        require(spec is not None and spec.loader is not None, "cannot import reviewed 59f publisher scope")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        source = module.restore_59f_source(root, path, source)
    contract = json.loads((root / "morphhdl/contracts/increment-60e-boundary-edits.json").read_text())
    require(contract["base"] == BASE, "60e restoration baseline changed")
    edits = [entry for entry in contract["edits"] if entry["path"] == path]
    require(edits, "no reviewed 60e edits for " + path)
    for edit in reversed(edits):
        require(source.count(edit["after"]) == 1, "missing/duplicate 60e span in " + path)
        source = source.replace(edit["after"], edit["before"], 1)
    return source


def source_scope(root: Path) -> None:
    def git(*args: str) -> str:
        return subprocess.check_output(["git", *args], cwd=root, text=True)
    subprocess.run(["git", "merge-base", "--is-ancestor", BASE, "HEAD"], cwd=root, check=True)
    has_59b = subprocess.run(["git", "merge-base", "--is-ancestor", QUALIFIED_59B, "HEAD"],
                            cwd=root, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0
    contract = json.loads((root / "morphhdl/contracts/increment-60e-boundary-edits.json").read_text())
    for path in sorted({entry["path"] for entry in contract["edits"]}):
        # The independently qualified reduction publisher changes this same
        # file. Undo only exact 60e spans, then freeze the complete 59b source;
        # all other 60e paths must still reproduce the original 60d baseline.
        baseline = (QUALIFIED_59B if has_59b and path ==
                    "morphhdl/src/main/scala/spinal/core/internals/ParameterizedVerilogVecs.scala" else BASE)
        require(restore_60d_source(root, path, (root / path).read_text()) == git("show", baseline + ":" + path),
                "unreviewed source change outside 60e spans: " + path)
    native = set(git("diff", "--name-only", BASE, "--", "core/src/main", "lib/src/main",
                     "idslplugin/src/main", "sim/src/main").splitlines())
    expected = {"core/src/main/scala/spinal/core/internals/VerilogBase.scala",
                "core/src/main/scala/spinal/core/internals/ComponentEmitterVerilog.scala"}
    if native != expected:
        # Extra native entry points require both qualified histories and the
        # canonical complete-path/blob/span audit. The exact restoration above
        # continues to reject every unreviewed change to the two native hooks.
        require(has_59b, "60e native hooks exceed the two printers")
        subprocess.run(["git", "merge-base", "--is-ancestor", QUALIFIED_60E, "HEAD"],
                       cwd=root, check=True)
        historical = set(git("diff", "--name-only", BASE, QUALIFIED_60E, "--", "core/src/main",
                             "lib/src/main", "idslplugin/src/main", "sim/src/main").splitlines())
        require(historical == expected, "qualified 60e native delta changed: " + str(historical))
        subprocess.run(["python3", "morphhdl/scripts/check-native-source-preservation.py"],
                       cwd=root, check=True)
    for name in ("MorphHdlSignednessAnalysis.scala",):
        path = "morphhdl/src/main/scala/spinal/core/internals/" + name
        restored = restore_59d_signed_width_authority(root, path, (root / path).read_text())
        require(git("show", BASE + ":" + path) == restored, "independent type authority changed")
    for path in ("morphhdl/src/main/scala/morphhdl/analysis/SignednessFacts.scala",
                 "morphhdl/src/test/scala/nativeapplication/SIntSignedVerilogBaselineFixture.scala"):
        require(git("show", BASE + ":" + path) == (root / path).read_text(), "sealed baseline changed: " + path)
    for name in ("MorphHdlSignedWidth.scala", "MorphHdlSignedDeclarationPolicy.scala", "MorphHdlPureSIntCastPolicy.scala"):
        source = (root / "morphhdl/src/main/scala/spinal/core/internals" / name).read_text()
        for token in ("getName", "definitionName", "getScalaLocation", "ThreadLocal", "replaceAll", ".r\n"):
            require(token not in source, "signedness authority uses forbidden inference: " + token)
    print("60e exact reviewed hooks, sealed independent authority and generic boundaries PASS", flush=True)


def tuples(kind: str):
    if kind == "scalars":
        return (dict(WIDTH=w, TARGET=t) for w, t in itertools.product(WIDTHS, WIDTHS))
    if kind in ("vectors", "vec-hierarchy"):
        return (dict(WIDTH=w, DEPTH=d) for w, d in itertools.product(WIDTHS, DEPTHS))
    if kind == "hierarchy":
        return (dict(WIDTH=w, ENABLED=e) for w, e in itertools.product(WIDTHS, (0, 1)))
    return (dict(WIDTH=w) for w in WIDTHS)


def key(parameters: dict[str, int]) -> str:
    return "-".join(str(value) for value in parameters.values())


def ports(text: str) -> list[tuple[str, int, str]]:
    header = text.split("\n);", 1)[0]
    pattern = r"^\s*(input|output)\s+(?:wire|reg)\s*(?:signed\s+)?(?:\[(\d+):0\])?\s+(\w+)\s*[,]?\s*$"
    found = [(direction, int(width) + 1 if width else 1, name)
             for direction, width, name in re.findall(pattern, header, re.M)]
    require(found and len({name for _, _, name in found}) == len(found), "invalid independent native interface")
    return found


def wrapper(out: Path, kind: str, parameters: dict[str, int], role: str):
    reference = out / ("fixed-" + key(parameters) + ".v")
    physical = ports(reference.read_text())
    logical = list(physical)
    if kind in ("vectors", "vec-hierarchy"):
        width, depth = parameters["WIDTH"], parameters["DEPTH"]
        logical = [port for port in physical if not re.fullmatch(r"packed(?:In|Out)_\d+", port[2])]
        for group, direction in (("packedIn", "input"), ("packedOut", "output")):
            members = [(d, b, n) for d, b, n in physical if n.startswith(group + "_")]
            require(members == [(direction, width, f"{group}_{i}") for i in range(depth)],
                    "independent native Vec layout changed")
            logical.append((direction, width * depth, group))
    associations = []
    for direction, bits, name in (physical if role == "gold" else logical):
        vector = re.fullmatch(r"(packedIn|packedOut)_(\d+)", name) if role == "gold" else None
        signal = (f"{vector[1]}[{int(vector[2]) * parameters['WIDTH']} +: {parameters['WIDTH']}]"
                  if vector else ("legal_index" if name == "index" else name))
        associations.append(f".{name}({signal})")
    declaration = ",\n".join(f"{d} wire [{b-1}:0] {n}" for d, b, n in logical)
    overrides = "" if role == "gold" else "#(" + ",".join(f".{p}({v})" for p, v in parameters.items()) + ")"
    index = (f"wire [2:0] legal_index = (index < 4'd{parameters['DEPTH']}) ? index : 3'd0;"
             if "DEPTH" in parameters else "")
    name = f"{role}-{key(parameters)}-wrapper.v"
    (out / name).write_text(f"module Root({declaration});\n{index}\n"
                           f"{KINDS[kind]} {overrides} dut({','.join(associations)});\nendmodule\n")
    return name, logical


def formal(out: Path, kind: str, parameters: dict[str, int]) -> None:
    label = "equivalence-" + key(parameters)
    commands = []
    for role, file in (("gold", "fixed-" + key(parameters) + ".v"), ("gate", "candidate.v")):
        wrap, _ = wrapper(out, kind, parameters, role)
        commands += [f"read_verilog {file} external.v {wrap}", "hierarchy -check -top Root", "proc", "flatten",
                     "memory_map", "async2sync", "wreduce", "opt",
                     # No coincident combinational names are assumed to correspond.
                     "rename -hide w:* t:$*dff* %x:+[Q] %d", f"rename Root {role}", f"design -stash {role}"]
    commands += ["design -copy-from gold -as gold gold", "design -copy-from gate -as gate gate",
                 "equiv_make gold gate equiv", "hierarchy -check -top equiv", "opt_merge", "equiv_simple",
                 "equiv_induct -undef -seq 4", "equiv_status -assert"]
    (out / (label + ".ys")).write_text("\n".join(commands) + "\n")
    text = run(["yosys", "-s", label + ".ys"], out, label)
    require("Equivalence successfully proven" in text, "missing positive solver evidence: " + label)


def simulation(out: Path, kind: str, parameters: dict[str, int]) -> None:
    label = "simulation-" + key(parameters)
    # Prefix every reference module/instantiation, including canonical children,
    # in the isolated test copy. External blackbox code is shared unchanged.
    original = (out / ("fixed-" + key(parameters) + ".v")).read_text()
    modules = re.findall(r"\bmodule\s+(\w+)", original)
    require(modules and len(set(modules)) == len(modules), "duplicate native modules")
    mapping = {name: "Gold_" + name for name in modules}
    renamed = re.sub(r"\b(?:" + "|".join(map(re.escape, modules)) + r")\b",
                     lambda match: mapping[match[0]], original)
    gold_file = "sim-gold-" + key(parameters) + ".v"
    (out / gold_file).write_text(renamed)
    gold_wrap, logical = wrapper(out, kind, parameters, "gold")
    gate_wrap, _ = wrapper(out, kind, parameters, "gate")
    gold_source = (out / gold_wrap).read_text().replace("module Root(", "module GoldRoot(").replace(KINDS[kind], "Gold_" + KINDS[kind])
    gate_source = (out / gate_wrap).read_text().replace("module Root(", "module GateRoot(")
    decl = [f"reg [{bits-1}:0] {name};" for d, bits, name in logical if d == "input"]
    decl += [f"wire [{bits-1}:0] g_{name}, c_{name};" for d, bits, name in logical if d == "output"]
    def instance(module, prefix):
        return module + " " + prefix + "(" + ",".join(f".{n}({n if d == 'input' else prefix + n})" for d, _, n in logical) + ");"
    checks = "\n".join(f'if(g_{n} !== c_{n}) begin $display("FAIL:{kind}:{n} cycle %d", i); $finish; end'
                       for d, _, n in logical if d == "output")
    init = "\n".join(f"{name}=0;" for d, _, name in logical if d == "input")
    random = []
    for d, bits, name in logical:
        if d != "input" or name in ("clk", "reset"):
            continue
        # The first patterns include minimum negative, -1, zero, one and maximum
        # positive at each port's exact width. Fill all words of wide aggregates.
        words = (bits + 31) // 32
        value = "{" + ",".join("$random(seed)" for _ in range(words)) + "}"
        random.append(f"case(i%8) 0:{name}=({bits}'d1 << {bits-1}); 1:{name}=-1; "
                      f"2:{name}=0; 3:{name}=1; 4:{name}=({bits}'d1 << {bits-1})-1; "
                      f"default:{name}={value}; endcase")
    clocks = "#2; compare; clk=1; #1; compare; clk=0; #1;" if kind == "channels" else "#2; compare;"
    reset = "reset=1; #2; clk=1; #1; clk=0; #1; reset=0; #1; compare;" if kind == "channels" else "#2; compare;"
    repeated_reset = "if (i%71 == 0) begin reset=1; #1; reset=0; end" if kind == "channels" else ""
    source = f"""{gold_source}
{gate_source}
module BoundaryTb;
{chr(10).join(decl)}
{instance('GoldRoot', 'g_')}
{instance('GateRoot', 'c_')}
integer i, seed;
task compare; begin
{checks}
end endtask
initial begin
{init}
i=0; seed=19491;
{reset}
for (i=0; i<400; i=i+1) begin
{chr(10).join(random)}
{repeated_reset}
{clocks}
end
$display("SIGNED_BOUNDARY_OK"); $finish;
end
endmodule
"""
    tb = label + ".v"
    (out / tb).write_text(source)
    run(["iverilog", "-g2001", "-s", "BoundaryTb", "-o", label + ".vvp", gold_file, "candidate.v", "external.v", tb],
        out, label + "-compile")
    text = run(["vvp", label + ".vvp"], out, label)
    require("SIGNED_BOUNDARY_OK" in text and "FAIL:" not in text, label + " did not match independent native RTL")


def tools(out: Path, kind: str, parameters: dict[str, int]) -> None:
    wrap, _ = wrapper(out, kind, parameters, "gate")
    label = "tools-" + key(parameters)
    run(["iverilog", "-g2001", "-s", "Root", "-tnull", "candidate.v", "external.v", wrap], out, label + "-parse")
    run(["verilator", "--lint-only", "--language", "1364-2001", "-Wno-fatal", "--top-module", "Root",
         "candidate.v", "external.v", wrap], out, label + "-lint")
    run(["yosys", "-p", f"read_verilog candidate.v external.v {wrap}; hierarchy -check -top Root; synth -top Root; check -assert"],
        out, label + "-synthesis")


def text_contract(out: Path, kind: str) -> None:
    text = (out / "candidate.v").read_text()
    require("wire signed [WIDTH-1:0]" in text or "wire       signed [WIDTH-1:0]" in text,
            "no signed scalar declaration in " + kind)
    require(re.search(r"\$signed\(\s*\$signed\(", text) is None, "nested redundant signed cast")
    require(not re.search(r"\b(?:logic|always_comb|typedef)\b", text), "SystemVerilog-only publication")
    if kind == "scalars":
        require("$signed(" not in text, "materialized scalar boundaries still carry redundant casts")
        require("function signed [(WIDTH + 1)-1:0]" in text, "constant function range remains a witness")
        require("[TARGET-1:0] _zz_resizedProduct" in text, "nested resize width boundary disappeared")
        require("8'shff" in text and "5'sh1d" in text, "literal interpretation not explicit")
        require("wire       [WIDTH-1:0] _zz_logicalUInt;" in text, "unsigned shift transport changed")
    if kind in ("vectors", "vec-hierarchy"):
        require("input wire [(WIDTH * DEPTH)-1:0] packedIn" in text, "Vec packed input is not unsigned")
        require("output wire [(WIDTH * DEPTH)-1:0] packedOut" in text, "Vec packed output is not unsigned")
        require(not re.search(r"signed\s+\[\(WIDTH \* DEPTH\)", text), "whole SInt Vec was incorrectly signed")
    if kind == "vectors":
        require("wire signed [(WIDTH)-1:0] updated_0" in text, "constant leaf reconstruction missing")
        require("$signed(updated[" in text, "dynamic read lost signed slice boundary")
    if kind == "vec-hierarchy":
        require(len(re.findall(r"\bmodule SignedBoundaryVecChild\b", text)) == 1, "Vec child deduplication changed")
    if kind == "hierarchy":
        require(len(re.findall(r"\bmodule SignedBoundaryChild\b", text)) == 1, "canonical child deduplication changed")
        require("module SignedBoundaryExternal" not in text, "external BlackBox definition was rewritten")
        for generic in ("LABEL", "WIDTH", "COUNT", "DOUBLE_WIDTH", "ENABLED"):
            require(re.search(r"\." + generic + r"\s*\(", text) is not None, "typed BlackBox generic missing: " + generic)



def mutations(out: Path) -> None:
    """Each live boundary corruption must produce a real SAT counterexample."""
    cases = (
        ("sign-extension", "scalars", dict(WIDTH=5, TARGET=8),
         "assign _zz_resized = {{((TARGET > WIDTH) ? (TARGET - WIDTH) : 0){a[WIDTH-1]}},",
         "assign _zz_resized = {{((TARGET > WIDTH) ? (TARGET - WIDTH) : 0){1'b0}},"),
        ("negative-literal", "scalars", dict(WIDTH=32, TARGET=8),
         "assign negativeLiteral = 8'shff;", "assign negativeLiteral = 8'hff;"),
        ("unsigned-consumer", "scalars", dict(WIDTH=5, TARGET=8),
         "wire       [WIDTH-1:0] _zz_logicalUInt;", "wire signed [WIDTH-1:0] _zz_logicalUInt;"),
        ("vec-leaf", "vectors", dict(WIDTH=5, DEPTH=3),
         "wire signed [(WIDTH)-1:0] updated_0;", "wire [(WIDTH)-1:0] updated_0;"),
        ("external-boundary", "hierarchy", dict(WIDTH=5, ENABLED=1),
         "wire       signed [WIDTH-1:0] externalOut;", "wire [WIDTH-1:0] externalOut;"),
    )
    qualified = []
    for label, kind, parameters, before, after in cases:
        directory = out / kind
        text = (directory / "candidate.v").read_text()
        require(text.count(before) == 1, label + " mutation must affect exactly one boundary")
        file = "mutant-" + label + ".v"
        (directory / file).write_text(text.replace(before, after, 1))
        commands = []
        for role, source in (("gold", "fixed-" + key(parameters) + ".v"), ("gate", file)):
            wrap, _ = wrapper(directory, kind, parameters, role)
            commands += [f"read_verilog {source} external.v {wrap}", "hierarchy -check -top Root",
                         "proc", "flatten", "opt", f"rename Root {role}", f"design -stash {role}"]
        commands += ["design -copy-from gold -as gold gold", "design -copy-from gate -as gate gate",
                     "miter -equiv -flatten gold gate MutationMiter", "hierarchy -check -top MutationMiter",
                     "proc", "flatten", "opt", "sat -prove trigger 0 -show-inputs -show-outputs"]
        script = "mutation-" + label + ".ys"
        (directory / script).write_text("\n".join(commands) + "\n")
        result = run(["yosys", "-s", script], directory, "mutation-" + label + "-counterexample")
        require("SAT proof finished - model found: FAIL!" in result,
                label + " did not produce a genuine boundary counterexample")
        qualified.append(label)
        print(label + ": genuine SAT counterexample PASS", flush=True)
    (out / "mutations.json").write_text(json.dumps({"counterexamples": qualified}, indent=2) + "\n")


def qualify(root: Path, out: Path, selected: tuple[str, ...], stages: tuple[str, ...]) -> None:
    count = 0
    for kind in selected:
        directory = out / kind
        (directory / "external.v").write_text(EXTERNAL)
        text_contract(directory, kind)
        for parameters in tuples(kind):
            for stage in stages:
                {"formal": formal, "simulation": simulation, "tools": tools}[stage](directory, kind, parameters)
            count += 1
        print(kind + ": " + ", ".join(stages) + " PASS", flush=True)
    (out / "qualification.json").write_text(json.dumps({"tuples": count, "kinds": list(selected), "stages": list(stages),
        "widths": list(WIDTHS), "vec_depths": list(DEPTHS), "vec_index_contract": "all legal indices on both legs"}, indent=2) + "\n")
    print(f"60e {count} independent native-reference tuples PASS", flush=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path, nargs="?")
    parser.add_argument("--source-only", action="store_true")
    parser.add_argument("--mutations-only", action="store_true")
    parser.add_argument("--skip-source", action="store_true", help="run artifact stages separately after the mandatory source gate")
    parser.add_argument("--kind", choices=tuple(KINDS), action="append")
    parser.add_argument("--stage", choices=("formal", "simulation", "tools"), action="append")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    if not args.skip_source:
        source_scope(root)
    if not args.source_only:
        require(args.output is not None, "artifact directory is required")
        if not args.mutations_only:
            qualify(root, args.output.resolve(), tuple(args.kind or KINDS), tuple(args.stage or ("simulation", "formal", "tools")))
        if args.mutations_only or (not args.kind and not args.stage):
            mutations(args.output.resolve())


if __name__ == "__main__":
    main()
