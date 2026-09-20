#!/usr/bin/env python3
"""Focused independent HDL proof for the 59i composite-widening publisher."""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import itertools
import json
import math
import random
import re
import shutil
import subprocess
from pathlib import Path

SCOPE = "59i-composite-widening-publication-proof"
FIELDS = ("unsignedSum", "unsignedProduct", "signedSum", "signedProduct")
DEFAULTS = {
    ("singleton", 5, 5, 5, 5, 1),
    ("alternate", 3, 4, 5, 2, 5),
}
PROFILES = set(itertools.product(("packed", "fields"),
    ("legacy", "declarations", "casts"), DEFAULTS))
SHAPES = ((1, 1, 1, 1), (3, 5, 4, 2), (5, 3, 7, 4),
          (8, 4, 1, 6), (16, 7, 5, 3))
COUNTS = (1, 2, 3, 5)
CASES = {(us, up, ss, sp, count) for us, up, ss, sp in SHAPES for count in COUNTS}
PASS = "59I-COMPOSITE-WIDENING-PASS"
FAIL = "59I-COMPOSITE-WIDENING-MISMATCH"


def require(condition: bool, detail: str) -> None:
    if not condition:
        raise RuntimeError(detail)


def command(args: list[str], log: Path, timeout: int = 240) -> str:
    with log.open("w") as output:
        result = subprocess.run(args, text=True, stdout=output,
            stderr=subprocess.STDOUT, timeout=timeout, check=False)
    if result.returncode:
        raise RuntimeError(f"tool exited {result.returncode}; see {log}")
    return log.read_text()


def checked(root: Path, relative: str) -> Path:
    path = (root / relative).resolve()
    require(path.is_relative_to(root.resolve()) and path.is_file() and not path.is_symlink(),
            "invalid RTL path: " + relative)
    return path


def case_shape(case: dict) -> tuple[int, ...]:
    return tuple(case[key] for key in ("us", "up", "ss", "sp", "count"))


def profile_shape(profile: dict) -> tuple:
    defaults = (profile["defaults"], profile["default_us"], profile["default_up"],
                profile["default_ss"], profile["default_sp"], profile["default_count"])
    return profile["layout"], profile["signed_mode"], defaults


def expected_widths(case: dict) -> dict[str, int]:
    count = case["count"]
    growth = (count - 1).bit_length()
    return {
        "unsignedSum": case["us"] + growth,
        "unsignedProduct": case["up"] * count,
        "signedSum": case["ss"] + growth,
        "signedProduct": case["sp"] * count,
    }


def input_widths(case: dict) -> dict[str, int]:
    return {"unsignedSum": case["us"], "unsignedProduct": case["up"],
            "signedSum": case["ss"], "signedProduct": case["sp"]}


def validate(manifest: dict) -> None:
    require(set(manifest) == {"schema", "scope", "candidates", "cases"} and
            manifest["schema"] == 1 and manifest["scope"] == SCOPE,
            "invalid widening proof manifest schema")
    profiles = manifest["candidates"]
    require(len(profiles) == len(PROFILES) and {profile_shape(value) for value in profiles} == PROFILES,
            "missing or duplicate layout/signed/default profile")
    cases = manifest["cases"]
    require(len(cases) == len(CASES) and {case_shape(value) for value in cases} == CASES,
            "missing or duplicate widening specialization")
    require(len({value["module"] for value in profiles + cases}) == len(profiles) + len(cases),
            "module identity reused")
    require(len({value["file"] for value in profiles + cases}) == len(profiles) + len(cases),
            "RTL file reused")
    for case in cases:
        require(case["outputs"] == expected_widths(case), "independent native output width differs")


def field_adapter(case: dict, prefix: str) -> tuple[list[str], list[str]]:
    widths = input_widths(case)
    lane_width = sum(widths.values())
    offset = 0
    lines, bindings = [], []
    for field in FIELDS:
        width = widths[field]
        name = prefix + "values_" + field
        pieces = [f"values[{lane * lane_width + offset} +: {width}]"
                  for lane in reversed(range(case["count"]))]
        rhs = pieces[0] if len(pieces) == 1 else "{" + ", ".join(pieces) + "}"
        lines += [f"wire [{width * case['count'] - 1}:0] {name};", f"assign {name} = {rhs};"]
        bindings.append(f".values_{field}({name})")
        offset += width
    return lines, bindings


def instance_lines(case: dict, candidates: list[dict]) -> list[str]:
    widths = expected_widths(case)
    lines = []
    for index, candidate in enumerate([None] + candidates):
        prefix = "g" if candidate is None else f"c{index - 1}"
        lines += [f"wire [{width - 1}:0] {prefix}_result_{name};"
                  for name, width in widths.items()]
        if candidate is None:
            module = case["module"]
            parameters = ""
            bindings = [".values(values)"]
        else:
            module = candidate["module"]
            parameters = (f" #(.UNSIGNED_SUM_WIDTH({case['us']}),"
                f".UNSIGNED_PRODUCT_WIDTH({case['up']}),"
                f".SIGNED_SUM_WIDTH({case['ss']}),"
                f".SIGNED_PRODUCT_WIDTH({case['sp']}),.COUNT({case['count']}))")
            if candidate["layout"] == "fields":
                wiring, bindings = field_adapter(case, prefix + "_")
                lines += wiring
            else:
                bindings = [".values(values)"]
        bindings += [f".result_{name}({prefix}_result_{name})" for name in FIELDS]
        lines.append(f"{module}{parameters} {prefix}_dut(" + ", ".join(bindings) + ");")
    return lines


def miter(case: dict, candidates: list[dict]) -> str:
    packed = sum(input_widths(case).values()) * case["count"]
    lines = [f"module miter(input wire [{packed - 1}:0] values, output wire bad);"]
    lines += instance_lines(case, candidates)
    terms = [f"(|(g_result_{field} ^ c{index}_result_{field}))"
             for index in range(len(candidates)) for field in FIELDS]
    lines += ["assign bad = " + " | ".join(terms) + ";", "endmodule", ""]
    return "\n".join(lines)


def signed(value: int, width: int) -> int:
    return value - (1 << width) if value & (1 << (width - 1)) else value


def decode(case: dict, packed: int) -> list[dict[str, int]]:
    widths = input_widths(case)
    result = []
    for _ in range(case["count"]):
        lane = {}
        for field in FIELDS:
            width = widths[field]
            lane[field] = packed & ((1 << width) - 1)
            packed >>= width
        result.append(lane)
    return result


def expected(case: dict, packed: int) -> dict[str, int]:
    rows = decode(case, packed)
    widths = expected_widths(case)
    values = {
        "unsignedSum": sum(row["unsignedSum"] for row in rows),
        "unsignedProduct": math.prod(row["unsignedProduct"] for row in rows),
        "signedSum": sum(signed(row["signedSum"], case["ss"]) for row in rows),
        "signedProduct": math.prod(signed(row["signedProduct"], case["sp"]) for row in rows),
    }
    return {name: value & ((1 << widths[name]) - 1) for name, value in values.items()}


def samples(case: dict) -> list[int]:
    widths = input_widths(case)
    lane_width = sum(widths.values())
    result = [0, (1 << (lane_width * case["count"])) - 1]
    offset = 0
    for field in FIELDS:
        width = widths[field]
        values = {1, (1 << width) - 1, 1 << (width - 1)}
        if width > 1:
            values.add((1 << (width - 1)) - 1)
        for lane in range(case["count"]):
            for value in values:
                result.append(value << (lane * lane_width + offset))
        offset += width
    rng = random.Random(59001 + sum(case_shape(case)))
    result += [rng.getrandbits(lane_width * case["count"]) for _ in range(96)]
    return list(dict.fromkeys(result))


def testbench(case: dict, candidates: list[dict]) -> tuple[str, int]:
    packed = sum(input_widths(case).values()) * case["count"]
    widths = expected_widths(case)
    vectors = samples(case)
    lines = ["`timescale 1ns/1ps", "module tb;", f"reg [{packed - 1}:0] values;"]
    lines += instance_lines(case, candidates)
    lines.append("initial begin")
    for tick, value in enumerate(vectors):
        exp = expected(case, value)
        lines += [f"values = {packed}'h{value:x}; #1;"]
        for prefix in ["g"] + [f"c{i}" for i in range(len(candidates))]:
            for field in FIELDS:
                lines += [f"if ({prefix}_result_{field} !== {widths[field]}'h{exp[field]:x}) begin",
                          f"$display(\"{FAIL} {prefix} {field} tick={tick}\"); $finish(1);", "end"]
    lines += [f"$display(\"{PASS}\"); $finish;", "end", "endmodule", ""]
    return "\n".join(lines), len(vectors)


def candidate_contract(text: str, profile: dict) -> None:
    require(len(re.findall(r"(?m)^module\s+" + re.escape(profile["module"]) + r"\b", text)) == 1,
            "candidate module identity missing")
    for name, value in (("UNSIGNED_SUM_WIDTH", profile["default_us"]),
                        ("UNSIGNED_PRODUCT_WIDTH", profile["default_up"]),
                        ("SIGNED_SUM_WIDTH", profile["default_ss"]),
                        ("SIGNED_PRODUCT_WIDTH", profile["default_sp"]),
                        ("COUNT", profile["default_count"])):
        require(re.search(r"parameter\s+(?:integer\s+)?" + name + r"\s*=\s*" + str(value) + r"\b", text) is not None,
                "candidate lost parameter/default " + name)
    require("genvar" in text and "begin : tail" in text and "partial_pair" in text,
            "candidate lost generic pair/tail publication")


def qualify(root: Path, duplicate: Path, only: str | None) -> None:
    root, duplicate = root.resolve(), duplicate.resolve()
    for tool in ("iverilog", "vvp", "verilator", "yosys"):
        require(shutil.which(tool) is not None, "missing tool: " + tool)
    manifest = json.loads((root / "manifest.json").read_text())
    validate(manifest)
    require((root / "manifest.json").read_bytes() == (duplicate / "manifest.json").read_bytes(),
            "nondeterministic manifest")
    candidates = manifest["candidates"]
    candidate_files = [checked(root, value["file"]) for value in candidates]
    for profile, path in zip(candidates, candidate_files):
        candidate_contract(path.read_text(), profile)
        require(path.read_bytes() == checked(duplicate, profile["file"]).read_bytes(),
                "nondeterministic candidate RTL")
    selected = 0
    evidence = []
    for case in manifest["cases"]:
        if only is not None and case["id"] != only:
            continue
        selected += 1
        reference = checked(root, case["file"])
        require(reference.read_bytes() == checked(duplicate, case["file"]).read_bytes(),
                "nondeterministic native reference")
        work = root / "checks" / case["id"]
        work.mkdir(parents=True, exist_ok=True)
        top = work / "miter.v"
        top.write_text(miter(case, candidates))
        sources = [reference] + candidate_files + [top]
        lint = command(["verilator", "--lint-only", "--language", "1364-2001",
                        "--top-module", "miter", *map(str, sources)], work / "lint.log")
        require("%Warning-WIDTH" not in lint, "Verilator reported a width adapter mismatch")
        script = work / "synthesis.ys"
        script.write_text("read_verilog " + " ".join(str(path) for path in sources) +
            "\nprep -top miter -flatten\ncheck -assert\nsynth -top miter\ncheck -assert\nstat\n")
        command(["yosys", "-Q", "-T", "-s", str(script)], work / "synthesis.log")
        bench = work / "tb.v"
        bench_text, cycles = testbench(case, candidates)
        bench.write_text(bench_text)
        executable = work / "tb.vvp"
        command(["iverilog", "-g2001", "-s", "tb", "-o", str(executable),
                 *map(str, [reference] + candidate_files + [bench])], work / "compile.log")
        simulation = command(["vvp", str(executable)], work / "simulation.log")
        require(PASS in simulation and FAIL not in simulation, "independent software/native simulation failed")
        proof = work / "proof.ys"
        proof.write_text("read_verilog " + " ".join(str(path) for path in sources) +
            "\nprep -top miter -flatten\ncheck -assert\nsat -prove bad 0 -verify -timeout 120\n")
        result = command(["yosys", "-Q", "-T", "-s", str(proof)], work / "proof.log")
        require("SUCCESS" in result and "FAIL" not in result, "combinational SAT proof did not pass")
        evidence.append({"case": case["id"], "profiles": len(candidates), "vectors": cycles,
                         "outputs": case["outputs"], "proof": "PASS"})
        print("PASS:", case["id"], len(candidates), "profiles", cycles, "vectors", flush=True)
    require(selected == 1 if only is not None else selected == len(CASES), "unknown or incomplete selected case")
    if only is None:
        (root / "evidence.json").write_text(json.dumps({"scope": SCOPE,
            "complete_59i_join": False, "cases": evidence,
            "manifest_sha256": hashlib.sha256((root / "manifest.json").read_bytes()).hexdigest()}, indent=2) + "\n")
    else:
        print("Focused proof only; full matrix and mutation controls remain required.", flush=True)


def self_test() -> None:
    for values in CASES:
        case = dict(zip(("us", "up", "ss", "sp", "count"), values))
        case["id"] = "x"
        widths = expected_widths(case)
        require(widths["unsignedSum"] == case["us"] + (case["count"] - 1).bit_length(), "sum width model")
        require(widths["signedProduct"] == case["sp"] * case["count"], "product width model")
        for sample in samples(case)[:24]:
            value = expected(case, sample)
            require(all(0 <= value[field] < 1 << widths[field] for field in FIELDS), "software result width")
    print("59i composite widening proof self-test PASS:", len(CASES), "cases", len(PROFILES), "profiles")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", nargs="?", type=Path)
    parser.add_argument("duplicate", nargs="?", type=Path)
    parser.add_argument("--case")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
    else:
        require(args.root is not None and args.duplicate is not None, "two artifact roots are required")
        qualify(args.root, args.duplicate, args.case)


if __name__ == "__main__":
    main()
