#!/usr/bin/env python3
"""Qualify declaration-only signed publication against independent native RTL."""
from __future__ import annotations

import argparse
import importlib.util
import re
import shutil
import subprocess
from pathlib import Path

BASE = "d0c2d65ed301a7895218a2fe225b2faf4a4bbfe0"
QUALIFIED_60C = "75e581592334e2e596f6e1043beb9596cc20a99b"
TOP = "SignedDeclarations"
WIDTHS = (1, 5, 8, 32)


def require(ok: bool, message: str) -> None:
    if not ok:
        raise RuntimeError(message)


def run(command: list[str], cwd: Path, label: str) -> str:
    result = subprocess.run(command, cwd=cwd, text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, timeout=240)
    (cwd / (label + ".log")).write_text(result.stdout)
    require(result.returncode == 0, label + " failed:\n" + result.stdout[-15000:])
    return result.stdout


def source_scope(root: Path) -> None:
    def git(*args: str) -> str:
        return subprocess.check_output(["git", *args], cwd=root, text=True)
    subprocess.run(["git", "merge-base", "--is-ancestor", BASE, "HEAD"], cwd=root, check=True)
    roots = ("core/src/main", "lib/src/main", "idslplugin/src/main", "sim/src/main")
    native = set(git("diff", "--name-only", BASE, "HEAD", "--", *roots).splitlines())
    expected = {"core/src/main/scala/spinal/core/internals/VerilogBase.scala",
                "core/src/main/scala/spinal/core/internals/ComponentEmitterVerilog.scala"}
    if native != expected:
        # Later increments may add independently reviewed native entry points.
        # Freeze the actual 60c hooks instead of treating every subsequent
        # native edit as if it were part of the declaration-only increment.
        subprocess.run(["git", "merge-base", "--is-ancestor", QUALIFIED_60C, "HEAD"],
                       cwd=root, check=True)
        historical = set(git("diff", "--name-only", BASE, QUALIFIED_60C,
                             "--", *roots).splitlines())
        require(historical == expected, "qualified 60c native delta changed: " + str(historical))
        require(not git("diff", "--name-only", QUALIFIED_60C, "HEAD", "--", *sorted(expected)).strip(),
                "native 60c declaration hooks changed after their frozen qualification")
        # Never accept an arbitrary extra native path merely because the two
        # frozen hooks are intact. The canonical guard checks every reviewed
        # production root, byte span, blob, root tree and dirty native file.
        subprocess.run(["python3", "morphhdl/scripts/check-native-source-preservation.py"],
                       cwd=root, check=True)
    emitter = "core/src/main/scala/spinal/core/internals/ComponentEmitterVerilog.scala"
    old = git("show", BASE + ":" + emitter)
    new = (root / emitter).read_text()
    marker = "  def emitReference("
    require(old[old.index(marker):] == new[new.index(marker):], "native expression/cast printers changed")
    fallback = "morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala"
    old = git("show", BASE + ":" + fallback)
    needle = r"\s*(\[[^\]]+\])?\s*([A-Za-z_][A-Za-z0-9_$]*)"
    replacement = r"\s*((?:signed\s+)?\[[^\]]+\])?\s*([A-Za-z_][A-Za-z0-9_$]*)"
    require(old.count(needle) == 2 and old.replace(needle, replacement) == (root / fallback).read_text(),
            "fallback change exceeds preserving the graph-owned declaration section")
    for name in ("MorphHdlSignedDeclarationPolicy.scala",):
        source = (root / "morphhdl/src/main/scala/spinal/core/internals" / name).read_text()
        for token in ("getName", "definitionName", "getScalaLocation", "ThreadLocal", "replaceAll", ".r\n"):
            require(token not in source, "signedness authority uses forbidden inference: " + token)
    baseline = "morphhdl/src/test/scala/nativeapplication/SIntSignedVerilogBaselineFixture.scala"
    require(git("hash-object", baseline).strip() == "84ed2baf743d2c47f07b6e76ddc9843fbb5fe910",
            "independent 60a fixture changed")
    print("60c source scope, unchanged cast printers and immutable oracle PASS")


def equivalence(out: Path, gold: str, gate: str, top: str, label: str,
                width: int | None = None, external: str = "") -> None:
    commands = []
    for name, role in ((gold, "gold"), (gate, "gate")):
        commands.append(f"read_verilog {name} {external}")
        if role == "gate" and width is not None:
            commands.append(f"chparam -set WIDTH {width} {top}")
        commands += [f"hierarchy -check -top {top}", "proc", "flatten", "memory_map", "opt",
                     # Only ports and actual sequential Q nets are correspondence points.
                     # Added wrappers can reuse a former temporary's generated name.
                     # Hide ALL combinational internals, not just failed matches.
                     "rename -hide w:* t:$*dff* %x:+[Q] %d",
                     f"rename {top} {role}", f"design -stash {role}"]
    commands += ["design -copy-from gold -as gold gold", "design -copy-from gate -as gate gate",
                 "equiv_make gold gate equiv", "hierarchy -check -top equiv", "equiv_simple",
                 "equiv_induct -undef -seq 4", "equiv_status -assert"]
    (out / (label + ".ys")).write_text("\n".join(commands) + "\n")
    result = run(["yosys", "-s", label + ".ys"], out, label)
    require("Equivalence successfully proven" in result, "missing equivalence proof: " + label)


def ports(rtl: str) -> list[tuple[str, int, str]]:
    pattern = re.compile(r"^\s*(input|output)\s+(?:wire|reg)\s*(?:\[(\d+):0\])?\s+(\w+)\s*[,]?\s*$", re.M)
    result = [(d, int(w) + 1 if w else 1, name) for d, w, name in pattern.findall(rtl)]
    require(len(result) == 24, "unexpected native test interface: " + str(result))
    return result


def simulation(out: Path, width: int) -> None:
    original = (out / f"fixed-{width}.v").read_text()
    reference, count = re.subn(r"\bmodule\s+SignedDeclarations\b", "module GoldDeclarations", original)
    require(count == 1, "expected one independently generated reference module")
    (out / f"gold-{width}.v").write_text(reference)
    interface = ports(original)
    inputs = [(bits, name) for d, bits, name in interface if d == "input"]
    outputs = [(bits, name) for d, bits, name in interface if d == "output"]
    declarations = [f"reg [{bits-1}:0] {name};" for bits, name in inputs]
    declarations += [f"wire [{bits-1}:0] g_{name}, c_{name};" for bits, name in outputs]
    def instance(module: str, instance_name: str, prefix: str) -> str:
        associations = [f".{name}({name if d == 'input' else prefix + name})" for d, _, name in interface]
        return module + " " + instance_name + "(" + ",".join(associations) + ");"
    compare = "\n".join(f'if (g_{name} !== c_{name}) begin $display("FAIL:{name}"); $finish; end'
                        for _, name in outputs)
    init = "\n".join(f"{name}=0;" for _, name in inputs)
    source = f'''module SignedTb;
{chr(10).join(declarations)}
{instance('GoldDeclarations', 'gold', 'g_')}
{instance(f'SignedDeclarations #(.WIDTH({width}))', 'candidate', 'c_')}
integer i;
task compare; begin
{compare}
end endtask
initial begin
{init}
enable=1; write=1; a=-1;
// Initialize both memory words identically, then read valid data.
address=0; #2; clk=1; #1; clk=0;
address=1; #2; clk=1; #1; clk=0; write=0;
#2; clk=1; #1; clk=0; #1; compare;
for(i=0; i<100; i=i+1) begin
  case(i%5)
    0: a=({width}'d1 << {width-1});
    1: a=-1;
    2: a=0;
    3: a=1;
    4: a=({width}'d1 << {width-1})-1;
  endcase
  b=$random; raw=$random; wideIn=$random;
  amount=i%4; choose=i%2; address=i%2; write=(i%3==0); enable=(i%4!=0);
  #2; compare; clk=1; #1; compare; clk=0; #1;
end
$display("SIGNED_DECLARATIONS_OK"); $finish;
end
endmodule
'''
    (out / f"tb-{width}.v").write_text(source)
    run(["iverilog", "-g2001", "-s", "SignedTb", "-o", f"sim-{width}.vvp",
         f"gold-{width}.v", "signed.v", f"tb-{width}.v"], out, f"compile-{width}")
    result = run(["vvp", f"sim-{width}.vvp"], out, f"simulate-{width}")
    require("SIGNED_DECLARATIONS_OK" in result and "FAIL:" not in result,
            "candidate differs from independent native reference at WIDTH=" + str(width))


def mutation(out: Path) -> None:
    candidate, count = re.subn(r"\bassign\s+negative\s*=\s*[^;]+;", "assign negative = 0;",
                               (out / "signed.v").read_text())
    require(count == 1, "negative-result mutation did not change exactly one assignment")
    (out / "mutant.v").write_text(candidate)
    connections = ".clk(1'b0), .enable(1'b0), .choose(1'b0), .write(1'b0), .address(1'b0), .amount(2'b0), .a(a), .b(8'd0), .raw(8'd0), .wideIn(9'd0)"
    (out / "mutation-miter.v").write_text(f'''module MutationMiter(input wire [7:0] a, output wire equal_result);
wire [7:0] g, c;
GoldDeclarations gold({connections}, .negative(g));
SignedDeclarations #(.WIDTH(8)) candidate({connections}, .negative(c));
assign equal_result = g == c;
endmodule
''')
    result = run(["yosys", "-p", "read_verilog gold-8.v mutant.v mutation-miter.v; hierarchy -check -top MutationMiter; proc; flatten; opt; memory_map; opt; sat -prove equal_result 1 -show-inputs -show-outputs"], out, "negative-mutation-counterexample")
    require("SAT proof finished - model found: FAIL!" in result,
            "mutation did not produce a genuine solver counterexample")


def qualify(root: Path, out: Path) -> None:
    for tool in ("iverilog", "vvp", "verilator", "yosys"):
        require(shutil.which(tool) is not None, "missing required tool: " + tool)
    for width in WIDTHS:
        simulation(out, width)
        run(["verilator", "--lint-only", "--language", "1364-2001", "-Wno-fatal",
             "--top-module", TOP, f"-GWIDTH={width}", "signed.v"], out, f"lint-{width}")
        run(["yosys", "-p", f"read_verilog signed.v; chparam -set WIDTH {width} {TOP}; hierarchy -check -top {TOP}; synth -top {TOP}; check -assert"], out, f"synth-{width}")
        equivalence(out, f"fixed-{width}.v", "signed.v", TOP, f"equivalence-{width}", width)
    for file, top in (("direct.v", "SignedDirect"), ("surfaces.v", "SignedSurfaces"),
                      ("bundle-surfaces.v", "SignedBundleSurfaces"),
                      ("functions.v", "SignedFunctions")):
        for width in WIDTHS:
            label = f"{top}-{width}"
            run(["iverilog", "-g2001", "-s", top, f"-P{top}.WIDTH={width}", "-tnull", file], out, label + "-parse")
            run(["verilator", "--lint-only", "--language", "1364-2001", "-Wno-fatal", "--top-module", top,
                 f"-GWIDTH={width}", file], out, label + "-lint")
            run(["yosys", "-p", f"read_verilog {file}; chparam -set WIDTH {width} {top}; hierarchy -check -top {top}; synth -top {top}; check -assert"], out, label + "-synth")
    equivalence(out, "functions-fixed.v", "functions.v", "SignedFunctions",
                "function-result-equivalence")
    mutation(out)
    # Reuse the sealed fixture and baseline checker without modifying either.
    spec = importlib.util.spec_from_file_location("baseline", root / "morphhdl/scripts/check-increment-60a-sint-baseline.py")
    baseline = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(baseline)
    base = out / "baseline"
    baseline.qualify(root, base)
    shutil.copyfile(out / "baseline-signed.v", base / "signed-candidate.v")
    baseline.simulation(base, "signed-candidate.v", "signed-candidate")
    run(["verilator", "--lint-only", "--language", "1364-2001", "-Wno-fatal", "--top-module", baseline.TOP,
         "signed-candidate.v", "external.v"], base, "signed-candidate-lint")
    run(["yosys", "-p", f"read_verilog signed-candidate.v external.v; hierarchy -check -top {baseline.TOP}; synth -top {baseline.TOP}; check -assert"], base, "signed-candidate-synth")
    equivalence(base, "sint_cast_heavy_fixed.v", "signed-candidate.v", baseline.TOP,
                "signed-candidate-equivalence", external="external.v")
    print("60c: strict parsing, signed declarations, independent matrix equivalence and live solver mutation PASS")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", nargs="?", type=Path)
    parser.add_argument("--source-only", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    source_scope(root)
    if not args.source_only:
        require(args.output is not None, "an artifact output directory is required")
        qualify(root, args.output.resolve())


if __name__ == "__main__":
    main()
