#!/usr/bin/env python3
from pathlib import Path
import argparse
import re
import subprocess
import sys


def run(command, cwd, expect_success=True):
    completed = subprocess.run(
        command,
        cwd=str(cwd),
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )
    rendered = " ".join(str(item) for item in command)
    print(f"$ {rendered}")
    print(completed.stdout)
    if expect_success and completed.returncode != 0:
        raise RuntimeError(
            f"command failed with exit code {completed.returncode}: {rendered}"
        )
    return completed


def parse_manifest(workspace):
    candidates = {}
    concrete = {}
    for line in (workspace / "manifest.txt").read_text().splitlines():
        fields = line.split("|")
        if fields[0] == "candidate" and len(fields) == 4:
            _, mode, path, top = fields
            candidates[mode] = (Path(path), top)
        elif fields[0] == "concrete" and len(fields) == 5:
            _, mode, depth, path, top = fields
            concrete[(mode, int(depth))] = (Path(path), top)
        else:
            raise RuntimeError(f"unsupported manifest row: {line}")
    expected_modes = {"plain", "buffered"}
    if set(candidates) != expected_modes:
        raise RuntimeError(f"candidate modes were {sorted(candidates)}")
    expected_concrete = {
        (mode, depth) for mode in expected_modes for depth in (4, 8, 16)
    }
    if set(concrete) != expected_concrete:
        raise RuntimeError(
            f"concrete witness inventory was {sorted(concrete)}"
        )
    return candidates, concrete


def module_block(verilog, top):
    pattern = re.compile(
        rf"(?ms)^\s*module\s+{re.escape(top)}\b(.*?)^\s*endmodule\b"
    )
    matches = pattern.findall(verilog)
    if len(matches) != 1:
        raise RuntimeError(
            f"expected one module block for {top}, found {len(matches)}"
        )
    return matches[0]


def parse_ports(verilog_path, top):
    block = module_block(verilog_path.read_text(), top)
    declaration = re.compile(
        r"(?m)^\s*(input|output)\s+(?:wire|reg|logic)?\s*"
        r"(\[[^\]]+\])?\s*([A-Za-z_][A-Za-z0-9_$]*)\s*[,;]"
    )
    ports = []
    for direction, packed, name in declaration.findall(block):
        ports.append((direction, packed.strip(), name))
    if not ports:
        raise RuntimeError(f"no ports parsed from {top}")
    names = [name for _, _, name in ports]
    if len(names) != len(set(names)):
        raise RuntimeError(f"duplicate port declarations in {top}")
    return ports


def choose(ports, direction, required, alternatives=()):
    matches = []
    for port_direction, _, name in ports:
        lower = name.lower()
        if port_direction != direction:
            continue
        if all(token in lower for token in required) and (
            not alternatives or any(token in lower for token in alternatives)
        ):
            matches.append(name)
    if len(matches) != 1:
        raise RuntimeError(
            f"port role direction={direction} required={required} "
            f"alternatives={alternatives} matched {matches}"
        )
    return matches[0]


def simulation_testbench(verilog_path, top, depth, output):
    ports = parse_ports(verilog_path, top)
    push_clock = choose(ports, "input", ("push",), ("clk", "clock"))
    pop_clock = choose(ports, "input", ("pop",), ("clk", "clock"))
    push_reset = choose(ports, "input", ("push", "reset"))
    pop_reset = choose(ports, "input", ("pop", "reset"))
    push_valid = choose(ports, "input", ("push", "valid"))
    push_payload = choose(ports, "input", ("push", "payload"))
    push_ready = choose(ports, "output", ("push", "ready"))
    pop_valid = choose(ports, "output", ("pop", "valid"))
    pop_payload = choose(ports, "output", ("pop", "payload"))
    pop_ready = choose(ports, "input", ("pop", "ready"))

    packed_by_name = {name: packed for _, packed, name in ports}
    declarations = []
    initials = []
    connections = []
    driven = {
        push_clock,
        pop_clock,
        push_reset,
        pop_reset,
        push_valid,
        push_payload,
        pop_ready,
    }
    for direction, packed, name in ports:
        net = "reg" if direction == "input" else "wire"
        range_text = f" {packed}" if packed else ""
        declarations.append(f"  {net}{range_text} {name};")
        connections.append(f"    .{name}({name})")
        if direction == "input" and name not in {push_clock, pop_clock}:
            initials.append(f"    {name} = 0;")

    push_reset_asserted = "0" if push_reset.lower().endswith("n") else "1"
    push_reset_released = "1" if push_reset.lower().endswith("n") else "0"
    pop_reset_asserted = "0" if pop_reset.lower().endswith("n") else "1"
    pop_reset_released = "1" if pop_reset.lower().endswith("n") else "0"
    total = depth * 2 + 3
    connection_text = ",\n".join(connections)
    declaration_text = "\n".join(declarations)
    initial_text = "\n".join(initials)

    output.write_text(
        f"""`timescale 1ns/1ps
module tb;
{declaration_text}
  integer sent;
  integer received;
  integer cycles;
  integer errors;
  localparam integer TOTAL = {total};

  {top} #(.DEPTH({depth})) dut (
{connection_text}
  );

  initial begin
    {push_clock} = 0;
    forever #5 {push_clock} = ~{push_clock};
  end

  initial begin
    {pop_clock} = 0;
    forever #7 {pop_clock} = ~{pop_clock};
  end

  initial begin
{initial_text}
    {push_reset} = {push_reset_asserted};
    {pop_reset} = {pop_reset_asserted};
    sent = 0;
    received = 0;
    cycles = 0;
    errors = 0;
    repeat (5) @(posedge {push_clock});
    {push_reset} = {push_reset_released};
    repeat (3) @(posedge {pop_clock});
    {pop_reset} = {pop_reset_released};
  end

  always @(posedge {push_clock}) begin
    if ({push_reset} == {push_reset_asserted}) begin
      {push_valid} <= 0;
      {push_payload} <= 0;
      sent <= 0;
    end else begin
      if ({push_valid} && {push_ready}) begin
        sent <= sent + 1;
        if (sent + 1 >= TOTAL)
          {push_valid} <= 0;
        else
          {push_payload} <= sent + 2;
      end else if (!{push_valid} && sent < TOTAL) begin
        {push_valid} <= 1;
        {push_payload} <= sent + 1;
      end
    end
  end

  always @(posedge {pop_clock}) begin
    if ({pop_reset} == {pop_reset_asserted}) begin
      {pop_ready} <= 0;
      received <= 0;
    end else begin
      {pop_ready} <= (cycles[2:0] != 3'b011);
      if ({pop_valid} && {pop_ready}) begin
        if ({pop_payload} !== ((received + 1) & 8'hff)) begin
          $display("ORDER ERROR received=%0d payload=%0h", received, {pop_payload});
          errors <= errors + 1;
        end
        received <= received + 1;
      end
    end
  end

  always @(posedge {pop_clock}) begin
    cycles <= cycles + 1;
    if (received == TOTAL) begin
      if (errors != 0) $fatal(1, "StreamFifoCC ordering errors=%0d", errors);
      $display("PASS depth={depth} received=%0d", received);
      $finish;
    end
    if (cycles > 4000) $fatal(1, "StreamFifoCC timeout sent=%0d received=%0d", sent, received);
  end
endmodule
"""
    )


def prepare_candidate(workspace, path, top, mode, depth):
    out = workspace / f"candidate-{mode}-{depth}.il"
    script = workspace / f"prepare-candidate-{mode}-{depth}.ys"
    script.write_text(
        f"""read_verilog -defer {path}
chparam -set DEPTH {depth} {top}
hierarchy -check -top {top}
flatten
proc
opt_clean
memory_dff
memory_map
opt_clean
check -assert
rename -top candidate_{mode}_{depth}
write_rtlil {out}
"""
    )
    run(["yosys", "-Q", "-s", str(script)], workspace)
    return out, f"candidate_{mode}_{depth}"


def prepare_concrete(workspace, path, top, mode, depth):
    out = workspace / f"concrete-{mode}-{depth}.il"
    script = workspace / f"prepare-concrete-{mode}-{depth}.ys"
    script.write_text(
        f"""read_verilog -defer {path}
hierarchy -check -top {top}
flatten
proc
opt_clean
memory_dff
memory_map
opt_clean
check -assert
rename -top concrete_{mode}_{depth}
write_rtlil {out}
"""
    )
    run(["yosys", "-Q", "-s", str(script)], workspace)
    return out, f"concrete_{mode}_{depth}"


def prove_pair(workspace, candidate, candidate_top, concrete, concrete_top, label, expect_pass):
    script = workspace / f"equivalence-{label}.ys"
    script.write_text(
        f"""read_rtlil {concrete}
read_rtlil {candidate}
equiv_make -inames {concrete_top} {candidate_top} equivalence_{label}
hierarchy -check -top equivalence_{label}
proc
opt_clean
select -assert-min 1 t:$equiv
equiv_simple -seq 8
equiv_induct -undef -seq 24
equiv_status -assert
"""
    )
    completed = run(
        ["yosys", "-Q", "-s", str(script)],
        workspace,
        expect_success=False,
    )
    if expect_pass and completed.returncode != 0:
        raise RuntimeError(f"positive equivalence failed for {label}")
    if not expect_pass and completed.returncode == 0:
        raise RuntimeError(
            f"negative-control equivalence unexpectedly passed for {label}"
        )
    return completed


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("workspace", type=Path)
    arguments = parser.parse_args()
    workspace = arguments.workspace.resolve()
    candidates, concrete = parse_manifest(workspace)

    for tool in ("iverilog", "vvp", "yosys"):
        run([tool, "-V" if tool != "vvp" else "-V"], workspace)

    prepared_candidates = {}
    prepared_concrete = {}
    for mode in ("plain", "buffered"):
        candidate_path, candidate_top = candidates[mode]
        for depth in (4, 8, 16):
            # Strict IEEE-1364 compilation of the specialized parameterized DUT.
            lint_output = workspace / f"lint-{mode}-{depth}.out"
            run(
                [
                    "iverilog",
                    "-g2005",
                    "-s",
                    candidate_top,
                    f"-P{candidate_top}.DEPTH={depth}",
                    "-o",
                    str(lint_output),
                    str(candidate_path),
                ],
                workspace,
            )

            synthesis = workspace / f"synthesis-{mode}-{depth}.ys"
            synthesis.write_text(
                f"""read_verilog -defer {candidate_path}
chparam -set DEPTH {depth} {candidate_top}
hierarchy -check -top {candidate_top}
synth -top {candidate_top}
check -assert
stat
"""
            )
            run(["yosys", "-Q", "-s", str(synthesis)], workspace)

            testbench = workspace / f"tb-{mode}-{depth}.v"
            simulation_testbench(
                candidate_path,
                candidate_top,
                depth,
                testbench,
            )
            executable = workspace / f"simulation-{mode}-{depth}.out"
            run(
                [
                    "iverilog",
                    "-g2005",
                    "-s",
                    "tb",
                    "-o",
                    str(executable),
                    str(candidate_path),
                    str(testbench),
                ],
                workspace,
            )
            run(["vvp", str(executable)], workspace)

            prepared_candidates[(mode, depth)] = prepare_candidate(
                workspace,
                candidate_path,
                candidate_top,
                mode,
                depth,
            )
            concrete_path, concrete_top = concrete[(mode, depth)]
            prepared_concrete[(mode, depth)] = prepare_concrete(
                workspace,
                concrete_path,
                concrete_top,
                mode,
                depth,
            )
            candidate_il, prepared_candidate_top = prepared_candidates[(mode, depth)]
            concrete_il, prepared_concrete_top = prepared_concrete[(mode, depth)]
            prove_pair(
                workspace,
                candidate_il,
                prepared_candidate_top,
                concrete_il,
                prepared_concrete_top,
                f"{mode}_{depth}",
                expect_pass=True,
            )

    # The wrong-depth reference is a non-vacuity control. It must leave at least
    # one unproven equivalence cell and make equiv_status -assert fail.
    candidate_il, candidate_top = prepared_candidates[("plain", 4)]
    wrong_il, wrong_top = prepared_concrete[("plain", 8)]
    mutation = prove_pair(
        workspace,
        candidate_il,
        candidate_top,
        wrong_il,
        wrong_top,
        "wrong_depth_control",
        expect_pass=False,
    )
    (workspace / "negative-control.log").write_text(mutation.stdout)
    (workspace / "PROOF_PASS").write_text("all six positive proofs passed; wrong-depth control failed\n")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"Increment 53e proof failure: {error}", file=sys.stderr)
        raise
