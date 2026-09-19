#!/usr/bin/env python3
"""Simulate emitted parameterized local-enable hardware against native Spinal.

The only adapters are port wiring. Python generates deterministic stimulus; it
does not emulate reductions, registers, enables, resets, or an expected datapath.
Both split and consolidated candidates run against the same independent native
reference. Semantic candidate mutations must produce a functional mismatch.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import itertools
import json
import random
import re
import shutil
import subprocess
from pathlib import Path

SCOPE = "59i-local-enable-native-hardware"
PASS = "59I-LOCAL-ENABLE-HARDWARE-PASS"
FAIL = "59I-LOCAL-ENABLE-HARDWARE-MISMATCH"
SAT_PASS = "SAT proof finished - no model found: SUCCESS!"
SAT_FAIL = "SAT proof finished - model found: FAIL!"
SHAPES = {(u, s, b, n) for (u, s, b), n in itertools.product(
    ((3, 4, 2), (5, 7, 3), (8, 3, 6)), (1, 2, 3, 5))}
PROFILES = {f"{kind}_{polarity}_{edge}" for kind, polarity, edge in itertools.product(
    ("sync", "async"), ("high", "low"), ("rising", "falling"))}
IDENTIFIER = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(message)


def checked_file(root: Path, name: str) -> Path:
    path = (root / name).resolve()
    require(root in path.parents and path.is_file(), f"invalid artifact: {name}")
    return path


def validate(manifest: dict) -> None:
    require(set(manifest) == {"schema", "scope", "candidates", "mutations", "cases"}
            and manifest["schema"] == 1 and manifest["scope"] == SCOPE, "wrong manifest schema/scope")
    candidates, cases, mutations = (manifest[k] for k in ("candidates", "cases", "mutations"))
    candidate_keys = {"profile", "split", "module", "file"}
    require(all(set(c) == candidate_keys for c in candidates), "wrong candidate fields")
    require(len(candidates) == 16 and {(c["profile"], c["split"]) for c in candidates} ==
            set(itertools.product(PROFILES, (False, True))), "incomplete emission/profile matrix")
    require(all(type(c["split"]) is bool for c in candidates), "non-Boolean split mode")
    require(mutations == ["enable-identity", "reset-value"],
            "missing semantic enable/reset mutation")
    case_keys = {"id", "profile", "asynchronous", "reset_low", "falling", "enable_low",
                 "uw", "sw", "bw", "count", "module", "file"}
    require(all(set(c) == case_keys for c in cases), "wrong native case fields")
    require(len(cases) == 96 and {(c["profile"], tuple(c[k] for k in ("uw", "sw", "bw", "count")))
            for c in cases} == set(itertools.product(PROFILES, SHAPES)), "incomplete native shape matrix")
    for case in cases:
        require(all(type(case[k]) is int for k in ("uw", "sw", "bw", "count")), "noninteger geometry")
        for key, expected in (("asynchronous", case["profile"].startswith("async_")),
                              ("reset_low", "_low_" in case["profile"]),
                              ("falling", case["profile"].endswith("_falling")),
                              ("enable_low", case["profile"].endswith("_falling"))):
            require(type(case[key]) is bool and case[key] == expected, f"wrong {key} profile")
    for key in ("module", "file"):
        entries = candidates + cases
        require(len({c[key] for c in entries}) == len(entries), f"reused {key}")
    require(all(IDENTIFIER.fullmatch(c["module"]) for c in candidates + cases),
            "invalid module identity")
    require(len({c["id"] for c in cases}) == len(cases), "reused case ID")


def ports(case: dict) -> dict[str, int]:
    return {"unsigned": case["uw"], "signed": case["sw"], "bitsValue": case["bw"], "valid": 1}


def instances(case: dict, candidates: list[dict]) -> list[str]:
    lines = []
    for index, entry in enumerate([case] + candidates):
        prefix = "g" if index == 0 else f"c{index - 1}"
        bindings = [f".{name}({name})" for name in ("clk", "reset", "enable", "values")]
        for field, width in ports(case).items():
            lines.append(f"wire [{width - 1}:0] {prefix}_{field};")
            bindings.append(f".result_{field}({prefix}_{field})")
        params = "" if index == 0 or entry.get("concrete", False) else " #(" + ", ".join(
            f".{name.upper()}({case[name]})" for name in ("uw", "sw", "bw", "count")) + ")"
        lines.append(f"{entry['module']}{params} {prefix}_dut(" + ", ".join(bindings) + ");")
    return lines


def miter(case: dict, candidates: list[dict], bit: tuple[str, int] | None = None) -> str:
    width = sum(ports(case).values()) * case["count"]
    lines = ["module miter(input wire clk, reset, enable,",
             f"input wire [{width - 1}:0] values, output wire bad);"]
    lines += instances(case, candidates)
    if bit is None:
        comparisons = [f"(|(g_{field} ^ c{index}_{field}))"
                       for index in range(len(candidates)) for field in ports(case)]
    else:
        field, index = bit
        require(len(candidates) == 1 and field in ports(case) and 0 <= index < ports(case)[field],
                "invalid native/candidate output-bit obligation")
        comparisons = [f"(g_{field}[{index}] ^ c0_{field}[{index}])"]
    lines += ["assign bad = " + " | ".join(comparisons) + ";", "endmodule", ""]
    return "\n".join(lines)


def stimulus(case: dict) -> list[int]:
    """First leaf is least significant, and each lane occupies a whole record."""
    fields = tuple(ports(case).values())
    stride = sum(fields)
    rng = random.Random(0x59_10_CA_FE + case["count"] * 131 + stride)
    result = []
    # Alternating original/current values expose the two-register identity bug.
    # One active lane isolates that distinction from sum/XOR cancellation.
    directed = ((0, 0, 0, 0), (1, 0, 0, 0), (0, 0, 0, 1),
                (1 << (fields[0] - 1), 1 << (fields[1] - 1), 1, 0),
                ((1 << fields[0]) - 1, (1 << fields[1]) - 1, (1 << fields[2]) - 1, 1),
                (2, 1, 2, 0), (3, 2, 1, 1))
    for iteration in range(384):
        packed = 0
        for lane in range(case["count"]):
            if iteration < 56:
                values = directed[iteration % len(directed)] if lane == 0 else (0, 0, 0, 0)
            elif iteration < 112:
                values = directed[(iteration + lane) % len(directed)]
            else:
                values = tuple(rng.getrandbits(width) for width in fields)
            offset = lane * stride
            for width, value in zip(fields, values):
                packed |= value << offset
                offset += width
        result.append(packed)
    return result


def bench(case: dict, candidates: list[dict]) -> str:
    width = sum(ports(case).values()) * case["count"]
    active_clock, idle_clock = int(not case["falling"]), int(case["falling"])
    active_reset, idle_reset = int(not case["reset_low"]), int(case["reset_low"])
    active_enable, idle_enable = int(not case["enable_low"]), int(case["enable_low"])
    lines = ["`timescale 1ns/1ps", "module tb;", "reg clk, reset, enable;",
             f"reg [{width - 1}:0] values;", "integer sample; integer checks;"]
    lines += instances(case, candidates)
    for field, bits in ports(case).items():
        lines.append(f"reg [{bits - 1}:0] previous_{field};")
    lines += ["task compare; begin", "checks = checks + 1;"]
    for field in ports(case):
        lines += [f"if ((^g_{field}) === 1'bx) begin",
                  f'$display("{FAIL} unknown-native sample=%0d field={field}", sample); $finish; end']
    for index in range(len(candidates)):
        for field in ports(case):
            lines += [f"if (g_{field} !== c{index}_{field}) begin",
                      f'$display("{FAIL} sample=%0d candidate={index} field={field} native=%h candidate=%h", '
                      f"sample, g_{field}, c{index}_{field}); $finish; end"]
    lines += ["end endtask", "task save_state; begin"]
    for field in ports(case):
        lines.append(f"previous_{field} = g_{field};")
    lines += ["end endtask", "task held; begin"]
    if case["count"] > 1:
        for field in ports(case):
            lines += [f"if (g_{field} !== previous_{field}) begin",
                      f'$display("{FAIL} native-hold sample=%0d field={field}", sample); $finish; end']
    lines += ["end endtask", "task reset_values; begin"]
    if case["count"] > 1:
        reset_values = {"unsigned": 3, "signed": (1 << case["sw"]) - 2, "bitsValue": 1, "valid": 0}
        for field, value in reset_values.items():
            bits = ports(case)[field]
            lines += [f"if (g_{field} !== {bits}'h{value:x}) begin",
                      f'$display("{FAIL} native-reset sample=%0d field={field}", sample); $finish; end']
    lines += ["compare; end endtask", "task pulse_reset; begin",
              f"clk = 1'b{idle_clock}; enable = 1'b{idle_enable}; reset = 1'b{idle_reset}; #2; save_state;",
              f"reset = 1'b{active_reset}; #2;"]
    lines += ["reset_values;"] if case["asynchronous"] else ["if (sample >= 0) begin compare; held; end"]
    lines += [f"clk = 1'b{active_clock}; #2;"]
    # Native Spinal's SYNC reset is inside its domain clock-enable guard;
    # ASYNC assertion resets even while CE is disabled. Establish initial
    # state with an enabled reset, and explicitly exercise both priorities.
    lines += ["reset_values;"] if case["asynchronous"] else ["if (sample >= 0) begin compare; held; end"]
    lines += [f"clk = 1'b{idle_clock}; #2; enable = 1'b{active_enable}; #2;",
              f"clk = 1'b{active_clock}; #2; reset_values; save_state;",
              f"clk = 1'b{idle_clock}; #2; compare; held;",
              f"reset = 1'b{idle_reset}; #2; compare; held; end endtask",
              "initial begin", "sample = -1; checks = 0; values = 0;",
              f"clk = 1'b{idle_clock}; reset = 1'b{idle_reset}; enable = 1'b{idle_enable};",
              "pulse_reset;"]
    for index, value in enumerate(stimulus(case)):
        # First 56 cycles run uninterrupted for deterministic identity mutation.
        enabled = index < 56 or index % 11 not in (0, 1, 5, 9)
        lines += [f"sample = {index}; values = {width}'h{value:x}; enable = 1'b{active_enable if enabled else idle_enable};",
                  "#2; compare; save_state;", f"clk = 1'b{active_clock}; #2; compare;"]
        if not enabled:
            lines.append("held;")
        lines += ["save_state;", f"clk = 1'b{idle_clock}; #2; compare; held;"]
        if index in (97, 251):
            lines.append("pulse_reset;")
    lines += [f'$display("{PASS} checks=%0d", checks); $finish;', "end", "endmodule", ""]
    return "\n".join(lines)


def run(args: list[str], log: Path) -> str:
    try:
        result = subprocess.run(args, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                check=False, timeout=180)
    except (OSError, subprocess.TimeoutExpired) as error:
        log.write_text(str(error) + "\n")
        raise RuntimeError(f"tool did not complete: {log}") from error
    log.write_text(result.stdout)
    require(result.returncode == 0, f"tool exited {result.returncode}: {log}")
    return result.stdout


def quoted(path: Path) -> str:
    return '"' + str(path).replace("\\", "\\\\").replace('"', '\\"') + '"'


def actual_rtl_mutations(root: Path, case: dict, candidate: dict) -> tuple[dict, list[dict]]:
    """Change one connection in the actual candidate's specialized RTL graph.

    Signal names are never mutation anchors. The anchors are the complete
    output-register identities, register widths, arithmetic result, original
    reset value and actual D/enable cone. Missing or ambiguous anchors fail.
    The untouched normalized counterpart is returned for positive native
    verification before either mutation is allowed to count as evidence.
    """
    require(case["profile"] == candidate["profile"] == "sync_high_rising" and
            tuple(case[k] for k in ("uw", "sw", "bw", "count")) == (5, 7, 3, 2),
            "mutation anchors require the selected two-leaf synchronous profile")
    work = root / "actual-rtl-mutations"
    work.mkdir(parents=True, exist_ok=True)
    source = checked_file(root, candidate["file"])
    width = sum(ports(case).values()) * case["count"]
    wrapper = work / "specialized.v"
    lines = ["module mutation_source(input wire clk, reset, enable,",
             f"input wire [{width - 1}:0] values,",
             ",\n".join(f"output wire [{bits - 1}:0] result_{field}" for field, bits in ports(case).items()) + ");"]
    params = ", ".join(f".{name.upper()}({case[name]})" for name in ("uw", "sw", "bw", "count"))
    bindings = [f".{name}({name})" for name in ("clk", "reset", "enable", "values")]
    bindings += [f".result_{field}(result_{field})" for field in ports(case)]
    lines += [f"{candidate['module']} #({params}) dut(" + ", ".join(bindings) + ");", "endmodule", ""]
    wrapper.write_text("\n".join(lines))
    original = work / "original.json"
    extraction = work / "extract.ys"
    extraction.write_text("read_verilog " + quoted(source) + " " + quoted(wrapper) +
        "\nhierarchy -check -top mutation_source\nproc\nflatten\nhierarchy -check -top mutation_source\n"
        "opt_expr\nopt_clean\ncheck -assert\nwrite_json " +
        quoted(original) + "\n")
    run(["yosys", "-Q", "-T", "-s", str(extraction)], work / "extract.log")
    document = json.loads(original.read_text())
    require(set(document["modules"]) == {"mutation_source"}, "mutation graph was not fully specialized/flattened")
    module = document["modules"]["mutation_source"]
    cells = module["cells"]
    drivers = {}
    for name, cell in cells.items():
        for port, direction in cell["port_directions"].items():
            if direction == "output":
                for bit in cell["connections"][port]:
                    if isinstance(bit, int):
                        require(bit not in drivers, "ambiguous actual RTL bit driver")
                        drivers[bit] = name

    def cone(bits: list) -> set[str]:
        result = set()
        def visit(bit):
            if bit not in drivers or drivers[bit] in result:
                return
            name = drivers[bit]
            cell = cells[name]
            result.add(name)
            if cell["type"] == "$dff":
                return
            for port, direction in cell["port_directions"].items():
                if direction == "input":
                    for child in cell["connections"][port]:
                        visit(child)
        for bit in bits:
            visit(bit)
        return result

    def only(values: list, label: str):
        require(len(values) == 1, "missing or ambiguous emitted RTL mutation anchor: " + label)
        return values[0]

    def output_register(field: str) -> str:
        return only([name for name, cell in cells.items() if cell["type"] == "$dff" and
                     cell["connections"]["Q"] == module["ports"]["result_" + field]["bits"]], field + " output register")

    unsigned_last = output_register("unsigned")
    unsigned_first = only([name for name, cell in cells.items() if cell["type"] == "$dff" and
                           len(cell["connections"]["Q"]) == case["uw"] and name != unsigned_last],
                          "unsigned first register")
    first_cone = cone(cells[unsigned_first]["connections"]["D"])
    add = only([name for name in first_cone if cells[name]["type"] == "$add" and
                len(cells[name]["connections"]["Y"]) == case["uw"]], "original unsigned sum")
    original_msb = cells[add]["connections"]["Y"][-1]
    current_msb = cells[unsigned_first]["connections"]["Q"][-1]
    require(isinstance(original_msb, int) and isinstance(current_msb, int) and original_msb != current_msb,
            "original and current controls must be distinct actual signal bits")
    unsigned_cone = cone(cells[unsigned_last]["connections"]["D"])
    enable_anchor = only([(name, port, index) for name in unsigned_cone
                          if cells[name]["type"] == "$xor" and len(cells[name]["connections"]["Y"]) == 1
                          for port in ("A", "B") for index, bit in enumerate(cells[name]["connections"][port])
                          if bit == original_msb], "original unsigned MSB in second-register enable XOR")
    signed_last = output_register("signed")
    signed_cone = cone(cells[signed_last]["connections"]["D"])
    reset_bits = [str(((1 << case["sw"]) - 2) >> i & 1) for i in range(case["sw"])]
    reset_anchor = only([(name, "B", 0) for name in signed_cone if cells[name]["type"] == "$mux" and
                         cells[name]["connections"]["S"] == module["ports"]["reset"]["bits"] and
                         cells[name]["connections"]["B"] == reset_bits], "signed second-register reset -2")

    def emit(doc: dict, role: str) -> dict:
        path = work / (role + ".json")
        path.write_text(json.dumps(doc, sort_keys=True) + "\n")
        name = "LocalEnableActualRtl_" + role.replace("-", "_")
        rtl = work / (name + ".v")
        script = work / (role + ".ys")
        script.write_text("read_json " + quoted(path) + "\nrename mutation_source " + name +
                          "\ncheck -assert\nwrite_verilog -noattr " + quoted(rtl) + "\n")
        run(["yosys", "-Q", "-T", "-s", str(script)], work / (role + ".log"))
        return {"module": name, "file": str(rtl.relative_to(root)), "concrete": True,
                "netlist_sha256": hashlib.sha256(path.read_bytes()).hexdigest()}

    baseline = emit(document, "unmutated")
    mutants = []
    for label, anchor, replacement in (("enable-identity", enable_anchor, current_msb),
                                        ("reset-value", reset_anchor, "1")):
        changed = copy.deepcopy(document)
        name, port, index = anchor
        before = cells[name]["connections"][port][index]
        require(before != replacement, "mutation must change an actual connection")
        changed["modules"]["mutation_source"]["cells"][name]["connections"][port][index] = replacement
        # Check the entire graph differs only at this one authorized bit binding.
        restored = copy.deepcopy(changed)
        restored["modules"]["mutation_source"]["cells"][name]["connections"][port][index] = before
        require(restored == document and changed != document, "mutation modified more than one connection")
        mutant = emit(changed, label)
        mutant.update(mutation=label, witness={"cell": name, "port": port, "index": index,
            "before": before, "after": replacement, "changed_connections": 1,
            "emitted_source": str(source.relative_to(root)),
            "emitted_source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
            "original_netlist_sha256": baseline["netlist_sha256"],
            "mutated_netlist_sha256": mutant["netlist_sha256"]})
        require(mutant["netlist_sha256"] != baseline["netlist_sha256"], "mutation netlist hash unchanged")
        mutants.append(mutant)
    return baseline, mutants


def formal_setup(paths: list[Path]) -> str:
    # async2sync is an active-edge model only. The unmodified emitted RTL also
    # runs strict lint/synthesis and native comparisons between clock edges.
    # Merge only combinational cells. A full opt/opt_merge could unify the two
    # DUTs' independent arbitrary initial register values. Each output-bit
    # obligation retains its entire reachable state and all unconstrained inputs.
    # Map vector cells to bits before the final dead-cone pass: otherwise one
    # live DFF/mux/add output bit keeps the whole word and its upstream logic.
    # techmap preserves each state bit; both merge selections exclude every FF.
    return ("read_verilog " + " ".join(quoted(path) for path in paths) +
            "\nhierarchy -check -top miter\nproc\nflatten\nopt_expr\nopt_clean\n"
            "async2sync\ndffunmap\n"
            "opt_merge -keepdc t:$add t:$mux t:$logic_not t:$xor t:$or t:$and "
            "t:$reduce_or t:$logic_and t:$logic_or\nopt_clean -purge\n"
            "techmap\nopt_expr -keepdc\nopt_clean -purge\n"
            "opt_merge -keepdc t:$_NOT_ t:$_AND_ t:$_OR_ t:$_XOR_ t:$_XNOR_ "
            "t:$_MUX_ t:$_NAND_ t:$_NOR_\nopt_clean -purge\ncheck -assert\nstat\n")


def bounded_bit_equivalence(root: Path, output: Path, case: dict,
                            candidates: list[dict], command: str) -> list[dict]:
    """Prove the original output conjunction without correlating DUT state.

    COUNT=3's three-DUT monolithic 18-step query timed out with 449487 SAT
    variables. Decomposing its conjunction by candidate and output bit permits
    dead-cone removal before unrolling. COUNT=5's first bit query still retained
    267607 variables; bit-level normalization permits pruning sibling bits of
    vector cells as well.
    Every bit still uses the full 18 steps, independent unknown initial state,
    native enabled reset at step 1, and arbitrary data/reset/enable thereafter.
    No internal equalities are assumed.
    """
    for obsolete in ("formal.log", "equivalence.ys", "formal-partitions.json"):
        (output / obsolete).unlink(missing_ok=True)
    results = []
    for candidate_index, candidate in enumerate(candidates):
        for field, width in ports(case).items():
            for bit in range(width):
                work = output / "formal-bits" / f"c{candidate_index}-{field}-{bit}"
                work.mkdir(parents=True, exist_ok=True)
                top = work / "miter.v"
                top.write_text(miter(case, [candidate], (field, bit)))
                sources = [checked_file(root, entry["file"]) for entry in (case, candidate)] + [top]
                proof = work / "equivalence.ys"
                proof.write_text(formal_setup(sources) + command + "-verify\n")
                log = run(["yosys", "-Q", "-T", "-s", str(proof)], work / "formal.log")
                require(SAT_PASS in log and SAT_FAIL not in log,
                        f"bounded native output-bit equivalence failed: {work}")
                results.append({"candidate": candidate["module"], "field": field, "bit": bit,
                                "result": "pass-18-steps", "log": str(work / "formal.log"),
                                "script": str(proof), "miter": str(top)})
    expected = {(c["module"], field, bit) for c in candidates
                for field, width in ports(case).items() for bit in range(width)}
    require(len(results) == len(expected) and
            {(r["candidate"], r["field"], r["bit"]) for r in results} == expected,
            "incomplete bounded output-bit proof conjunction")
    (output / "formal-partitions.json").write_text(json.dumps(results, indent=2) + "\n")
    return results


def require_counterexample(trace: Path) -> None:
    require(trace.is_file(), "formal mutation lacks a counterexample VCD")
    lines = trace.read_text().splitlines()
    identifiers = [words[3] for line in lines if len(words := line.split()) >= 6
                   and words[0] == "$var" and words[4].lstrip("\\") == "bad"]
    require(len(identifiers) == 1 and any(line.strip() in
            ("1" + identifiers[0], "b1 " + identifiers[0]) for line in lines),
            "formal mutation trace does not show bad=1")


def strict_tools_and_formal(root: Path, output: Path, case: dict, candidates: list[dict],
                            mutation: bool = False) -> dict:
    output.mkdir(parents=True, exist_ok=True)
    top = output / "miter.v"
    top.write_text(miter(case, candidates))
    sources = [checked_file(root, entry["file"]) for entry in [case] + candidates] + [top]
    run(["verilator", "--lint-only", "--language", "1364-2001", "--top-module", "miter",
         *map(str, sources)], output / "lint.log")
    synthesis = output / "synthesis.ys"
    synthesis.write_text("read_verilog " + " ".join(quoted(path) for path in sources) +
                         "\nhierarchy -check -top miter\nsynth -top miter\ncheck -assert\nstat\n")
    run(["yosys", "-Q", "-T", "-s", str(synthesis)], output / "synthesis.log")
    result = {"lint": "pass", "synthesis": "pass", "formal": "not-selected"}
    # All width/count/profile combinations are simulated and tool-checked.
    # Formal selects one unequal-width shape across every count and profile;
    # reset at step 1 establishes all initialized state without assigning any
    # DUT registers. CE/reset/data are unconstrained after that reset edge.
    selected = tuple(case[k] for k in ("uw", "sw", "bw")) == (5, 7, 3)
    if selected or mutation:
        trace = output / "counterexample.vcd"
        trace.unlink(missing_ok=True)
        proof = output / "equivalence.ys"
        command = (f"sat -seq 18 -set-def-inputs -set-at 1 reset {int(not case['reset_low'])} "
                   f"-set-at 1 enable {int(not case['enable_low'])} "
                   "-prove bad 0 -prove-skip 1 -timeout 120 ")
        if mutation:
            command += "-show-inputs -show-outputs -dump_vcd " + quoted(trace)
            proof.write_text(formal_setup(sources) + command + "\n")
            log = run(["yosys", "-Q", "-T", "-s", str(proof)], output / "formal.log")
            require(SAT_FAIL in log and SAT_PASS not in log,
                    f"semantic mutant produced no formal mismatch: {output}")
            require_counterexample(trace)
            result["formal"] = "counterexample"
        else:
            result["formal_partitions"] = bounded_bit_equivalence(root, output, case, candidates, command)
            result["formal"] = "pass-18-steps"
    return result


def simulate(root: Path, output: Path, case: dict, candidates: list[dict], mutation: bool) -> dict:
    output.mkdir(parents=True, exist_ok=True)
    testbench = output / "tb.v"
    testbench.write_text(bench(case, candidates))
    sources = [checked_file(root, entry["file"]) for entry in [case] + candidates]
    executable = output / "tb.vvp"
    run(["iverilog", "-g2001", "-Wall", "-Wimplicit", "-s", "tb", "-o", str(executable),
         *map(str, sources), str(testbench)], output / "compile.log")
    log = run(["vvp", str(executable)], output / "simulate.log")
    if mutation:
        require(FAIL in log and PASS not in log and "candidate=0" in log,
                f"semantic mutation was not rejected by candidate/native comparison: {output}")
    else:
        require(log.count(PASS) == 1 and FAIL not in log, f"native equivalence failed: {output}")
    return {"id": case["id"], "candidates": [c["module"] for c in candidates],
            "result": "counterexample" if mutation else "pass", "log": str(output / "simulate.log"),
            "rtl_sha256": {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest()
                           for path in sources},
            "testbench_sha256": hashlib.sha256(testbench.read_bytes()).hexdigest()}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifacts", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    root, output = args.artifacts.resolve(), args.output.resolve()
    # Never preserve a prior successful receipt if a rerun fails midway.
    (output / "receipt.json").unlink(missing_ok=True)
    manifest = json.loads((root / "manifest.json").read_text())
    validate(manifest)
    for tool in ("iverilog", "vvp", "verilator", "yosys"):
        require(shutil.which(tool) is not None, f"required simulator missing: {tool}")
    output.mkdir(parents=True, exist_ok=True)
    results = []
    for case in manifest["cases"]:
        candidates = [c for c in manifest["candidates"] if c["profile"] == case["profile"]]
        work = output / case["id"]
        result = simulate(root, work, case, candidates, False)
        result.update(strict_tools_and_formal(root, work, case, candidates))
        results.append(result)
    anchor = next(c for c in manifest["cases"] if c["profile"] == "sync_high_rising"
                  and tuple(c[k] for k in ("uw", "sw", "bw", "count")) == (5, 7, 3, 2))
    candidate = next(c for c in manifest["candidates"] if c["profile"] == anchor["profile"] and not c["split"])
    baseline, mutant_entries = actual_rtl_mutations(root, anchor, candidate)
    baseline_work = output / "normalized-unmutated"
    normalized = simulate(root, baseline_work, anchor, [baseline], False)
    normalized.update(strict_tools_and_formal(root, baseline_work, anchor, [baseline]))
    mutations = []
    for mutant in mutant_entries:
        work = output / ("mutation-" + mutant["mutation"])
        result = simulate(root, work, anchor, [mutant], True)
        result.update(strict_tools_and_formal(root, work, anchor, [mutant], mutation=True))
        result["mutation_witness"] = mutant["witness"]
        mutations.append(result)
    receipt = {"schema": 1, "scope": SCOPE, "result": "pass", "native_cases": len(results),
               "candidate_comparisons": 2 * len(results), "cycles_per_case": 384,
               "formal": {"kind": "bounded-active-edge-equivalence", "steps": 18,
                          "cases": sum(r["formal"] == "pass-18-steps" for r in results),
                          "decomposition": "all candidate/output-bit obligations; no state merging or cut assumptions",
                          "bit_obligations": sum(len(r.get("formal_partitions", [])) for r in results),
                          "initial_state": "unconstrained; enabled native reset at step 1",
                          "after_reset": "unconstrained input, reset, and global enable",
                          "async": "async2sync edge model; raw RTL asynchronous timing covered by simulation",
                          "unbounded_induction": "not-run"},
               "results": results, "normalized_unmutated": normalized, "mutations": mutations}
    (output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(f"{PASS}: {2 * len(results)} native comparisons; {len(mutations)} semantic mutations rejected")


if __name__ == "__main__":
    main()
