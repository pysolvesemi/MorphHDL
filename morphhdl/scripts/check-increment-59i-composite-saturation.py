#!/usr/bin/env python3
"""Native-reference HDL qualification for the focused 59i saturation mechanism.

The reference is ordinary SpinalVerilog output from the existing native callback.
Adapters only reorder/slice wires; this checker contains no saturation datapath.
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import difflib
import hashlib
import itertools
import json
from pathlib import Path
import random
import re
import shutil
import subprocess

SCOPE = "59i-composite-saturation-hardware"
SHAPES = {(1, 1), (1, 16), (32, 1), (32, 16), (5, 3), (3, 5), (5, 5)}
COUNTS = {1, 2, 3, 5, 8, 9, 16, 17}
CASES = {(w, t, n) for (w, t), n in itertools.product(SHAPES, COUNTS)}
FIELDS = ("value", "tag", "valid")
PASS = "59I-SATURATION-NATIVE-PASS"
FAIL = "59I-SATURATION-NATIVE-MISMATCH"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(message)


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(args: list[str], log: Path, timeout: int = 240) -> str:
    with log.open("w") as output:
        completed = subprocess.run(args, stdout=output, stderr=subprocess.STDOUT,
            timeout=timeout, check=False, text=True)
    require(completed.returncode == 0, f"tool exit {completed.returncode}: {log}")
    return log.read_text()


def checked(root: Path, relative: str) -> Path:
    path = (root / relative).resolve()
    require(path.is_relative_to(root) and path.is_file() and not path.is_symlink(),
        "invalid artifact path: " + relative)
    return path


def widths(case: dict) -> dict[str, int]:
    return {"value": case["width"], "tag": case["tag_width"], "valid": 1}


def validate(root: Path) -> dict:
    manifest = json.loads((root / "manifest.json").read_text())
    require(set(manifest) == {"schema", "scope", "candidates", "cases"} and
        manifest["schema"] == 1 and manifest["scope"] == SCOPE, "invalid manifest")
    candidates, cases = manifest["candidates"], manifest["cases"]
    require(len(candidates) == 2 and {p["layout"] for p in candidates} == {"packed", "fields"},
        "missing/duplicate saturation layout")
    require(len(cases) == len(CASES) and
        {(c["width"], c["tag_width"], c["count"]) for c in cases} == CASES,
        "missing/duplicate concrete native specialization")
    require(len({x["module"] for x in candidates + cases}) == len(candidates + cases),
        "reused module identity")
    for artifact in candidates + cases:
        rtl = checked(root, artifact["file"]).read_text()
        require(len(re.findall(r"(?m)^module\s+" + re.escape(artifact["module"]) + r"\b", rtl)) == 1,
            "incorrect module identity: " + artifact["file"])
    for candidate in candidates:
        rtl = checked(root, candidate["file"]).read_text()
        for name, default in (("WIDTH", 5), ("TAG_WIDTH", 3), ("COUNT", 5)):
            require(re.search(r"parameter\s+(?:integer\s+)?" + name + r"\s*=\s*" + str(default) + r"\b", rtl),
                "lost independent parameter: " + name)
    for case in cases:
        rtl = checked(root, case["file"]).read_text()
        require(not re.search(r"\bparameter\b", rtl), "reference is not concrete")
        for i in range(case["count"]):
            for field in FIELDS:
                require(re.search(r"\bvalues_" + str(i) + "_" + field + r"\b", rtl),
                    f"reference lost native indexed field port {i}/{field}")
    return manifest


def instances(case: dict, candidates: list[dict]) -> list[str]:
    sizes = widths(case)
    stride = sum(sizes.values())
    lines = []
    for number, artifact in enumerate([case] + candidates):
        prefix = "native" if number == 0 else f"candidate{number-1}"
        for field, size in sizes.items():
            lines.append(f"wire [{size-1}:0] {prefix}_{field};")
        bindings = []
        offset = 0
        if number == 0:
            for field, size in sizes.items():
                for lane in range(case["count"]):
                    bindings.append(f".values_{lane}_{field}(values[{lane*stride+offset} +: {size}])")
                offset += size
        elif artifact["layout"] == "packed":
            bindings.append(".values(values)")
        else:
            for field, size in sizes.items():
                pieces = [f"values[{lane*stride+offset} +: {size}]"
                    for lane in reversed(range(case["count"]))]
                expression = pieces[0] if len(pieces) == 1 else "{" + ", ".join(pieces) + "}"
                name = prefix + "_values_" + field
                lines += [f"wire [{size*case['count']-1}:0] {name};", f"assign {name} = {expression};"]
                bindings.append(f".values_{field}({name})")
                offset += size
        bindings += [f".result_{field}({prefix}_{field})" for field in FIELDS]
        params = "" if number == 0 else (f" #(.WIDTH({case['width']}),"
            f".TAG_WIDTH({case['tag_width']}),.COUNT({case['count']}))")
        lines.append(f"{artifact['module']}{params} {prefix}_dut(" + ", ".join(bindings) + ");")
    return lines


def miter(case: dict, candidates: list[dict]) -> str:
    packed = sum(widths(case).values()) * case["count"]
    lines = [f"module miter(input wire [{packed-1}:0] values, output wire bad);"]
    lines += instances(case, candidates)
    terms = [f"(|(native_{field} ^ candidate{i}_{field}))"
        for i in range(len(candidates)) for field in FIELDS]
    return "\n".join(lines + ["assign bad = " + " | ".join(terms) + ";", "endmodule", ""])


def samples(case: dict) -> list[int]:
    sizes = widths(case)
    stride, count = sum(sizes.values()), case["count"]
    packed = stride * count
    # Boundary-driving inputs; expected outputs always come from native RTL.
    values = [0, (1 << packed) - 1]
    offset = 0
    for field, size in sizes.items():
        extrema = {1, (1 << size) - 1, 1 << (size - 1)}
        if size > 1:
            extrema.add((1 << (size - 1)) - 1)
        for lane in range(count):
            for value in extrema:
                values.append(value << (lane * stride + offset))
        for value in extrema:
            values.append(sum(value << (lane * stride + offset) for lane in range(count)))
        offset += size
    # One low-bit impulse in every position covers all fields and odd tail lanes.
    values += [1 << bit for bit in range(packed)]
    rng = random.Random(59009 + case["width"] * 1000 + case["tag_width"] * 100 + count)
    values += [rng.getrandbits(packed) for _ in range(128)]
    return list(dict.fromkeys(values))


def testbench(case: dict, candidates: list[dict]) -> tuple[str, int]:
    packed = sum(widths(case).values()) * case["count"]
    vectors = samples(case)
    lines = ["`timescale 1ns/1ps", "module tb;", f"reg [{packed-1}:0] values;"]
    lines += instances(case, candidates)
    lines.append("initial begin")
    for tick, value in enumerate(vectors):
        lines.append(f"values = {packed}'h{value:x}; #1;")
        for index in range(len(candidates)):
            for field in FIELDS:
                lines += [f"if (candidate{index}_{field} !== native_{field}) begin",
                    f'$display("{FAIL} candidate={index} field={field} vector={tick}"); $finish;', "end"]
    lines += [f'$display("{PASS}"); $finish;', "end", "endmodule", ""]
    return "\n".join(lines), len(vectors)


def proof(sources: list[Path], work: Path, mutation: bool = False) -> str:
    script = work / "proof.ys"
    script.write_text("read_verilog " + " ".join(map(str, sources)) +
        "\nprep -top miter -flatten\ncheck -assert\n" +
        "sat -prove bad 0 " + ("" if mutation else "-verify ") +
        "-show-inputs -show-outputs -dump_json " + str(work / "counterexample.json") +
        " -timeout 120\n")
    text = run(["yosys", "-Q", "-T", "-s", str(script)], work / "proof.log", 180)
    if mutation:
        require("model found: FAIL" in text and "no model found: SUCCESS" not in text,
            "generated-RTL mutation was not disproved: " + str(work))
        require((work / "counterexample.json").is_file(), "mutation missing actual SAT counterexample")
    else:
        require("no model found: SUCCESS" in text and "model found: FAIL" not in text,
            "native saturation equivalence unproved: " + str(work))
    return text


def qualify_case(root: Path, case: dict, candidates: list[dict]) -> dict:
    work = root / "checks" / case["id"]
    work.mkdir(parents=True, exist_ok=True)
    rtl = [checked(root, x["file"]) for x in [case] + candidates]
    top = work / "miter.v"
    top.write_text(miter(case, candidates))
    sources = rtl + [top]
    lint = run(["verilator", "--lint-only", "--language", "1364-2001", "--top-module", "miter",
        *map(str, sources)], work / "lint.log")
    require("%Warning-WIDTH" not in lint, "adapter width warning")
    synthesis = work / "synthesis.ys"
    synthesis.write_text("read_verilog " + " ".join(map(str, sources)) +
        "\nprep -top miter -flatten\ncheck -assert\nsynth -top miter\ncheck -assert\nstat\n")
    run(["yosys", "-Q", "-T", "-s", str(synthesis)], work / "synthesis.log")
    bench, vectors = testbench(case, candidates)
    (work / "tb.v").write_text(bench)
    run(["iverilog", "-g2001", "-s", "tb", "-o", str(work / "tb.vvp"),
        *map(str, rtl), str(work / "tb.v")], work / "compile.log")
    simulation = run(["vvp", str(work / "tb.vvp")], work / "simulation.log")
    require(PASS in simulation and FAIL not in simulation, "native RTL simulation mismatch")
    proof(sources, work)
    result = {"case": case["id"], "layouts": [p["layout"] for p in candidates], "vectors": vectors,
        "strict_v2001": "PASS", "lint": "PASS", "synthesis": "PASS", "simulation": "PASS", "sat": "PASS"}
    (work / "evidence.json").write_text(json.dumps(result, indent=2) + "\n")
    print("PASS", case["id"], vectors, "vectors; both layouts", flush=True)
    return result


def mutate(rtl: str, kind: str) -> tuple[str, str, str]:
    if kind == "tail":
        match = re.search(r"(?m)^(\s*assign\s+\w*l0_tail_result_leaf_0\s*=\s*)([^;]+);", rtl)
        require(match is not None, "generated tail assignment not found")
        before = match.group(0)
        after = match.group(1) + "{WIDTH{1'b0}};"
    else:
        matches = [m for m in re.finditer(r"(?m)^\s*assign\s+\w*l0_pair_result_leaf_0(?:_\d+)?\s*=\s*[^;]+;", rtl)
            if "?" in m.group(0)]
        require(len(matches) == 1, "expected one generated first-level native saturation ternary")
        match = matches[0]
        before = match.group(0)
        if kind == "carry":
            require("(|" in before, "native carry reduction not found")
            after = before.replace("(|", "(~|", 1)
        elif kind == "fill":
            fill = re.search(r"\?\s*(\w+)\s*:\s*\w+", before)
            require(fill is not None, "native saturation fill operand not found")
            assignments = list(re.finditer(r"(?m)^(\s*assign\s+" + re.escape(fill.group(1)) +
                r"\s*=\s*)([^;]+);", rtl[:match.start()]))
            require(assignments, "native all-ones fill assignment not found")
            match = assignments[-1]
            before = match.group(0)
            require("1'b1" in before, "saturation fill must actually be emitted all-ones geometry")
            after = before.replace("1'b1", "1'b0")
        elif kind == "slice":
            result = re.search(r"\?\s*\w+\s*:\s*(\w+)", before)
            require(result is not None, "native saturation result operand not found")
            assignments = list(re.finditer(r"(?m)^\s*assign\s+" + re.escape(result.group(1)) +
                r"\s*=\s*[^;]+;", rtl[:match.start()]))
            require(assignments, "native saturation result selection assignment not found")
            match = assignments[-1]
            before = match.group(0)
            selection = list(re.finditer(r"(\w+)\[([^\]]+)\]", before))[-1]
            bounds = selection.group(2).split(":")
            require(len(bounds) == 2, "native fixed result selection not found")
            changed = f"({bounds[0].strip()}) + 1 : ({bounds[1].strip()}) + 1"
            after = before[:selection.start(2)] + changed + before[selection.end(2):]
        else:
            raise RuntimeError("unknown RTL mutation " + kind)
    require(before != after, "no-op mutation")
    return rtl[:match.start()] + after + rtl[match.end():], before, after


def qualify_mutations(root: Path, manifest: dict) -> list[dict]:
    case = next(c for c in manifest["cases"] if c["id"] == "w5_t3_n5")
    evidence = []
    for candidate in manifest["candidates"]:
        original = checked(root, candidate["file"])
        for kind in ("slice", "fill", "carry", "tail"):
            work = root / "mutations" / candidate["layout"] / kind
            work.mkdir(parents=True, exist_ok=True)
            mutated, before, after = mutate(original.read_text(), kind)
            modified = work / "candidate.v"
            modified.write_text(mutated)
            (work / "mutation.diff").write_text("".join(difflib.unified_diff(
                original.read_text().splitlines(True), mutated.splitlines(True),
                fromfile=str(original), tofile=str(modified))))
            top = work / "miter.v"
            top.write_text(miter(case, [candidate]))
            rtl = [checked(root, case["file"]), modified]
            bench, _ = testbench(case, [candidate])
            (work / "tb.v").write_text(bench)
            run(["iverilog", "-g2001", "-s", "tb", "-o", str(work / "tb.vvp"),
                *map(str, rtl), str(work / "tb.v")], work / "compile.log")
            simulation = run(["vvp", str(work / "tb.vvp")], work / "simulation.log")
            require(FAIL in simulation and PASS not in simulation,
                "actual generated RTL mutation survived directed simulation")
            proof(rtl + [top], work, mutation=True)
            record = {"layout": candidate["layout"], "kind": kind, "case": case["id"],
                "original_sha256": digest(original), "mutated_sha256": digest(modified),
                "before": before, "after": after, "simulation": "MISMATCH", "sat": "COUNTEREXAMPLE"}
            (work / "evidence.json").write_text(json.dumps(record, indent=2) + "\n")
            evidence.append(record)
            print("REJECTED actual RTL mutation", candidate["layout"], kind, flush=True)
    return evidence


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    parser.add_argument("duplicate", type=Path)
    parser.add_argument("--jobs", type=int, default=2)
    parser.add_argument("--case")
    args = parser.parse_args()
    root, duplicate = args.root.resolve(), args.duplicate.resolve()
    require(1 <= args.jobs <= 4, "jobs must be between 1 and 4")
    for tool in ("iverilog", "vvp", "verilator", "yosys"):
        require(shutil.which(tool), "missing hardware tool " + tool)
    manifest = validate(root)
    validate(duplicate)
    require((root / "manifest.json").read_bytes() == (duplicate / "manifest.json").read_bytes(),
        "manifest differs between deterministic emissions")
    for artifact in manifest["candidates"] + manifest["cases"]:
        require(checked(root, artifact["file"]).read_bytes() == checked(duplicate, artifact["file"]).read_bytes(),
            "nondeterministic emitted RTL: " + artifact["file"])
    cases = [c for c in manifest["cases"] if args.case is None or c["id"] == args.case]
    require(len(cases) == (len(CASES) if args.case is None else 1), "missing selected case")
    evidence = []
    with ThreadPoolExecutor(max_workers=args.jobs) as pool:
        futures = [pool.submit(qualify_case, root, c, manifest["candidates"]) for c in cases]
        for future in as_completed(futures):
            evidence.append(future.result())
    mutations = qualify_mutations(root, manifest) if args.case is None else []
    result = {"scope": SCOPE, "complete_59i": False, "focused_only": args.case is not None,
        "determinism": "PASS", "cases": sorted(evidence, key=lambda x: x["case"]),
        "generated_rtl_mutations": mutations, "source_files": {
            x["file"]: digest(checked(root, x["file"])) for x in manifest["candidates"] + manifest["cases"]},
        "manifest_sha256": digest(root / "manifest.json")}
    output = root / ("evidence.json" if args.case is None else "focused-" + args.case + ".json")
    output.write_text(json.dumps(result, indent=2) + "\n")


if __name__ == "__main__":
    main()
