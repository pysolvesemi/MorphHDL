#!/usr/bin/env python3
"""Strict-tool and independent-reference qualification of pure-SInt cast cleanup."""
from __future__ import annotations

import argparse
import importlib.util
import json
import re
import shutil
import subprocess
from pathlib import Path

BASE = "75e581592334e2e596f6e1043beb9596cc20a99b"
QUALIFIED_60D = "6c2d0027c36076942c03bd2a4f6d4df1b7934962"
QUALIFIED_60E = "dc8cab41cf3fd41b026ba7359f30cb596b14d015"
WIDTHS = (1, 5, 8, 32)
TOP = "PureSIntCasts"


def require(condition: bool, detail: str) -> None:
    if not condition:
        raise RuntimeError(detail)


def load(path: Path, name: str):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def restore_declaration_only_emitter(root: Path, source: str) -> str:
    """Undo only the four reviewed 60d helper edits, not arbitrary printer code."""
    boundary_checker = root / "morphhdl/scripts/check-increment-60e-signedness-boundaries.py"
    if boundary_checker.is_file():
        boundary = load(boundary_checker, "boundary_scope")
        source = boundary.restore_60d_source(root,
            "core/src/main/scala/spinal/core/internals/ComponentEmitterVerilog.scala", source)
    contract = json.loads((root / "morphhdl/contracts/increment-60d-emitter-edits.json").read_text())
    require(contract["base"] == BASE and len(contract["edits"]) == 4, "unexpected emitter edit contract")
    for edit in reversed(contract["edits"]):
        require(source.count(edit["after"]) == 1, "missing/duplicate reviewed native printer span")
        source = source.replace(edit["after"], edit["before"], 1)
    return source


def restore_rollout(root: Path, path: str, source: str) -> str:
    helper = root / "morphhdl/scripts/check-increment-60g-source-scope.py"
    if not helper.is_file():
        return source
    spec = importlib.util.spec_from_file_location("rollout_scope", helper)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module.restore_60g_source(root, path, source)


def source_scope(root: Path) -> None:
    def git(*args: str) -> str:
        return subprocess.check_output(["git", *args], cwd=root, text=True)
    subprocess.run(["git", "merge-base", "--is-ancestor", BASE, "HEAD"], cwd=root, check=True)
    expected = {"core/src/main/scala/spinal/core/internals/VerilogBase.scala",
                "core/src/main/scala/spinal/core/internals/ComponentEmitterVerilog.scala"}
    native = set(git("diff", "--name-only", BASE, "HEAD", "--", "core/src/main", "lib/src/main",
                     "idslplugin/src/main", "sim/src/main").splitlines())
    if native != expected:
        # Inherited gates admit later native work only through the canonical
        # reviewed-span inventory. Both qualified emitter hooks remain frozen.
        subprocess.run(["git", "merge-base", "--is-ancestor", QUALIFIED_60D, "HEAD"],
                       cwd=root, check=True)
        historical = set(git("diff", "--name-only", BASE, QUALIFIED_60D, "--", "core/src/main",
                             "lib/src/main", "idslplugin/src/main", "sim/src/main").splitlines())
        require(historical == expected, "qualified 60d native delta changed: " + str(historical))
        qualified = QUALIFIED_60D
        if subprocess.run(["git", "merge-base", "--is-ancestor", QUALIFIED_60E, "HEAD"],
                          cwd=root, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0:
            qualified = QUALIFIED_60E
        require(not git("diff", "--name-only", qualified, "HEAD", "--", *sorted(expected)).strip(),
                "native signed declaration/cast hooks changed after their frozen qualification")
        subprocess.run(["python3", "morphhdl/scripts/check-native-source-preservation.py"],
                       cwd=root, check=True)
    emitter = "core/src/main/scala/spinal/core/internals/ComponentEmitterVerilog.scala"
    require(restore_declaration_only_emitter(root, (root / emitter).read_text()) == git("show", BASE + ":" + emitter),
            "wrapper planning, declaration emission or another native printer changed")
    for file in ("MorphHdlPureSIntCastPolicy.scala", "MorphHdlSignedDeclarationPolicy.scala"):
        source = (root / "morphhdl/src/main/scala/spinal/core/internals" / file).read_text()
        for token in ("getName", "definitionName", "getScalaLocation", "ThreadLocal", "replaceAll", ".r\n"):
            require(token not in source, "cast authority uses forbidden inference: " + token)
    for path in ("morphhdl/src/test/scala/nativeapplication/SIntSignedVerilogBaselineFixture.scala",
                 "morphhdl/src/main/scala/spinal/core/internals/MorphHdlSignednessAnalysis.scala",
                 "morphhdl/src/main/scala/morphhdl/analysis/SignednessFacts.scala"):
        require(git("show", BASE + ":" + path) == restore_rollout(root, path, (root / path).read_text()), "sealed oracle/authority changed: " + path)
    print("60d exact native hook, unchanged wrapper plan and independent oracle scope PASS")


def ports(rtl: str) -> list[tuple[str, int, str]]:
    pattern = re.compile(r"^\s*(input|output)\s+(?:wire|reg)\s*(?:signed\s+)?(?:\[(\d+):0\])?\s+(\w+)\s*[,]?\s*$", re.M)
    result = [(d, int(w) + 1 if w else 1, n) for d, w, n in pattern.findall(rtl)]
    require(result and len({n for _, _, n in result}) == len(result), "invalid/duplicate generated interface")
    return result


def interface(out: Path, width: int) -> list[tuple[str, int, str]]:
    values = ports((out / f"fixed-{width}.v").read_text())
    expected = {"clk", "enable", "amount", "a", "b", "c", "divisor", "sum", "difference", "quotient",
                "remainder", "negative", "shiftConstant", "shiftVariable", "nestedShift", "nestedDivision",
                "nestedRemainder", "product", "nestedProduct", "negatedProduct", "nestedTriple", "less",
                "lessEqual", "greater", "greaterEqual", "nestedLess", "registered", "registeredProduct"}
    require({n for _, _, n in values} == expected, "pure fixture interface changed")
    return values


def domain_wrapper(out: Path, width: int) -> str:
    """Same external nonzero domain on both sides; the DUT itself is unchanged.

    Mapping only zero to one surjectively covers every nonzero bit-vector,
    including the minimum negative divisor. This avoids interpreting an
    undefined divide/modulo by zero as part of an equivalence obligation.
    """
    values = interface(out, width)
    decl = ",\n".join(f"{d} wire [{bits-1}:0] {name}" for d, bits, name in values)
    connections = ",".join(f".{name}({'nonzero_divisor' if name == 'divisor' else name})" for _, _, name in values)
    return f"""module NonzeroDivisorDomain({decl});
wire [{width-1}:0] nonzero_divisor;
assign nonzero_divisor = (divisor == {width}'d0) ? {width}'d1 : divisor;
{TOP} dut({connections});
endmodule
"""


def equivalent_nonzero(out: Path, width: int, run) -> None:
    wrapper = f"domain-{width}.v"
    (out / wrapper).write_text(domain_wrapper(out, width))
    # Native casts and native signed references may lower to different redundant
    # sign-extension widths. Standard Yosys wreduce canonicalizes both designs;
    # opt_merge deduplicates only identical cells, not assumed matching names.
    commands = []
    for file, role in ((f"fixed-{width}.v", "gold"), ("pure-true.v", "gate")):
        commands.append(f"read_verilog {file} {wrapper}")
        if role == "gate":
            commands.append(f"chparam -set WIDTH {width} {TOP}")
        commands += ["hierarchy -check -top NonzeroDivisorDomain", "proc", "flatten", "wreduce", "opt",
                     # Never pair combinational wrappers by generated names.
                     "rename -hide w:* t:$*dff* %x:+[Q] %d",
                     f"rename NonzeroDivisorDomain {role}", f"design -stash {role}"]
    commands += ["design -copy-from gold -as gold gold", "design -copy-from gate -as gate gate",
                 "equiv_make gold gate equiv", "hierarchy -check -top equiv", "opt_merge", "equiv_simple",
                 "equiv_induct -undef -seq 4", "equiv_status -assert"]
    label = f"pure-nonzero-equivalence-{width}"
    (out / (label + ".ys")).write_text("\n".join(commands) + "\n")
    result = run(["yosys", "-s", label + ".ys"], out, label)
    require("Equivalence successfully proven" in result, "missing positive equivalence result")


def simulation(out: Path, width: int, run) -> None:
    values = interface(out, width)
    original = (out / f"fixed-{width}.v").read_text()
    reference, count = re.subn(r"\bmodule\s+PureSIntCasts\b", "module GoldPureSInt", original)
    require(count == 1, "reference module was not unique")
    (out / f"gold-{width}.v").write_text(reference)
    declarations = [f"reg [{bits-1}:0] {name};" for direction, bits, name in values if direction == "input"]
    declarations += [f"wire [{bits-1}:0] g_{name}, c_{name};" for direction, bits, name in values if direction == "output"]
    def instance(module: str, name: str, prefix: str) -> str:
        associations = ",".join(f".{n}({n if d == 'input' else prefix + n})" for d, _, n in values)
        return f"{module} {name}({associations});"
    comparisons = "\n".join(f'if (g_{n} !== c_{n}) begin $display("FAIL:{n} at %d", i); $finish; end'
                            for d, _, n in values if d == "output")
    assignments = "\n".join(f"{n}=0;" for d, _, n in values if d == "input")
    # Native reference + a separate modular arithmetic oracle for selected hard
    # cases. Arithmetic is truncated at explicit register widths in the test.
    source = f"""module PureTb;
{chr(10).join(declarations)}
{instance('GoldPureSInt', 'gold', 'g_')}
{instance(f'{TOP} #(.WIDTH({width}))', 'candidate', 'c_')}
integer i, j, seed;
reg signed [{width-1}:0] wrap_sum, wrap_negative;
reg signed [{2*width-1}:0] expected_product;
reg signed [{width-1}:0] edges [0:4];
task compare; begin
{comparisons}
wrap_sum = a + b;
wrap_negative = -a;
expected_product = wrap_sum * $signed(c);
if (c_nestedProduct !== expected_product || c_negative !== wrap_negative) begin
  $display("FAIL:independent wrap-width oracle"); $finish;
end
end endtask
initial begin
{assignments}
seed=12345; i=0; divisor=1; enable=1;
edges[0]=({width}'d1 << {width-1}); edges[1]=-1; edges[2]=0;
edges[3]=1; edges[4]=({width}'d1 << {width-1})-1;
#2; clk=1; #1; clk=0; #1; compare;
for(i=0; i<600; i=i+1) begin
  if(i<125) begin a=edges[i%5]; b=edges[(i/5)%5]; c=edges[(i/25)%5]; end
  else begin a=$random(seed); b=$random(seed); c=$random(seed); end
  divisor=(i%2 == 0) ? edges[i%5] : $random(seed);
  if(divisor==0) divisor=1;
  amount=i%8; enable=(i%4!=0);
  #2; compare; clk=1; #1; compare; clk=0; #1;
end
$display("PURE_SINT_OK"); $finish;
end
endmodule
"""
    (out / f"tb-{width}.v").write_text(source)
    run(["iverilog", "-g2001", "-s", "PureTb", "-o", f"sim-{width}.vvp", f"gold-{width}.v",
         "pure-true.v", f"tb-{width}.v"], out, f"pure-compile-{width}")
    result = run(["vvp", f"sim-{width}.vvp"], out, f"pure-simulation-{width}")
    require("PURE_SINT_OK" in result and "FAIL:" not in result, "simulation/reference mismatch")


def mutations(out: Path, run) -> None:
    text = (out / "pure-true.v").read_text()
    for label, output, replacement, outbits in (
            ("negative", "negative", "0", 8),
            ("division", "quotient", "0", 8),
            # Widening the sum at its consumer is NOT legal cast elimination.
            ("context-width", "nestedProduct", "((a + b) * c)", 16)):
        candidate, count = re.subn(r"\bassign\s+" + output + r"\s*=\s*[^;]+;",
                                  f"assign {output} = {replacement};", text)
        require(count == 1, "mutation must modify exactly one output assignment")
        (out / f"mutant-{label}.v").write_text(candidate)
        (out / f"mutation-{label}.v").write_text(f"""module MutationMiter(
input wire [7:0] a, b, c, divisor, output wire equal_result);
wire [7:0] nonzero_divisor = divisor == 0 ? 8'd1 : divisor;
wire [{outbits-1}:0] g, m;
GoldPureSInt gold(.a(a),.b(b),.c(c),.divisor(nonzero_divisor),.amount(3'd0),.clk(1'b0),.enable(1'b0),.{output}(g));
{TOP} #(.WIDTH(8)) mutant(.a(a),.b(b),.c(c),.divisor(nonzero_divisor),.amount(3'd0),.clk(1'b0),.enable(1'b0),.{output}(m));
assign equal_result = g == m;
endmodule
""")
        result = run(["yosys", "-p", f"read_verilog gold-8.v mutant-{label}.v mutation-{label}.v; "
                      "hierarchy -check -top MutationMiter; proc; flatten; opt; "
                      "sat -prove equal_result 1 -show-inputs -show-outputs"], out, f"mutation-{label}-counterexample")
        require("SAT proof finished - model found: FAIL!" in result,
                label + " mutation did not produce a genuine solver counterexample")


def qualify(root: Path, out: Path) -> None:
    previous = load(root / "morphhdl/scripts/check-increment-60c-signed-declarations.py", "declarations")
    run = previous.run
    for tool in ("yosys", "iverilog", "verilator", "vvp"):
        require(shutil.which(tool) is not None, "missing required tool: " + tool)
    pure = (out / "pure-true.v").read_text()
    require("$signed(" not in pure and "wire signed [WIDTH-1:0]" in pure, "pure fixture not cast free/signed")
    for file in ("pure-true.v", "boundaries.v", "baseline-clean.v", "declaration-fixture-clean.v"):
        require(re.search(r"\$signed\(\s*\$signed\(", (out / file).read_text()) is None, "nested signed cast in " + file)
    require((out / "disabled.v").read_text().count("$signed(") ==
            (out / "declarations.v").read_text().count("$signed("), "declaration-only cast contract changed")
    for width in WIDTHS:
        simulation(out, width, run)
        equivalent_nonzero(out, width, run)
        for file, top in (("pure-true.v", TOP), ("boundaries.v", "SIntCastBoundaries")):
            label = f"{top}-{width}"
            run(["iverilog", "-g2001", "-s", top, f"-P{top}.WIDTH={width}", "-tnull", file], out, label + "-parse")
            run(["verilator", "--lint-only", "--language", "1364-2001", "-Wno-fatal", "--top-module", top,
                 f"-GWIDTH={width}", file], out, label + "-lint")
            run(["yosys", "-p", f"read_verilog {file}; chparam -set WIDTH {width} {top}; "
                 f"hierarchy -check -top {top}; synth -top {top}; check -assert"], out, label + "-synth")
        previous.equivalence(out, f"boundary-fixed-{width}.v", "boundaries.v", "SIntCastBoundaries",
                             f"boundary-equivalence-{width}", width)
    mutations(out, run)
    # Freshly elaborated 60c independent native references and memory validity.
    inherited = out / "declaration-clean"
    inherited.mkdir(exist_ok=True)
    shutil.copyfile(out / "declaration-fixture-clean.v", inherited / "signed.v")
    for width in WIDTHS:
        shutil.copyfile(out / "inherited" / f"fixed-{width}.v", inherited / f"fixed-{width}.v")
        previous.simulation(inherited, width)
        previous.equivalence(inherited, f"fixed-{width}.v", "signed.v", previous.TOP,
                             f"clean-declaration-equivalence-{width}", width)
    # Full sealed 60a fixture includes memory, real boundaries and a BlackBox.
    baseline = load(root / "morphhdl/scripts/check-increment-60a-sint-baseline.py", "baseline")
    base = out / "baseline"
    baseline.qualify(root, base)
    shutil.copyfile(out / "baseline-clean.v", base / "clean.v")
    baseline.simulation(base, "clean.v", "clean")
    previous.equivalence(base, "sint_cast_heavy_fixed.v", "clean.v", baseline.TOP,
                         "full-baseline-clean-equivalence", external="external.v")
    print("60d pure-SInt strict tools, WIDTH 1/5/8/32 equivalence, boundaries and three real mutations PASS")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", nargs="?", type=Path)
    parser.add_argument("--source-only", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    source_scope(root)
    if not args.source_only:
        require(args.output is not None, "output directory required")
        qualify(root, args.output.resolve())


if __name__ == "__main__":
    main()
