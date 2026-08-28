#!/usr/bin/env python3
"""Formal equivalence for Increment 53c native AXI4 factory offsets.

The script consumes one MorphHDL parameterized Verilog definition plus four
independently elaborated ordinary SpinalVerilog witnesses. Each DUT leg is
flattened and renamed in a separate Yosys process before entering a SymbiYosys
assertion miter. A deliberate output mutation must produce a counterexample.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Sequence

OFFSETS = (16, 64, 80, 112)
PARAMETERIZED_FILE = "native_axi4_parameterized.v"
CONCRETE_FILE = "native_axi4_concrete_top_offset_{offset}.v"
SOURCE_TOP = "NativeAxi4SlaveFactoryParameterizedTop"


@dataclass(frozen=True)
class Port:
    name: str
    width: int


SHARED_INPUTS = (
    Port("io_axi_aw_valid", 1),
    Port("io_axi_aw_payload_addr", 12),
    Port("io_axi_aw_payload_id", 2),
    Port("io_axi_aw_payload_region", 4),
    Port("io_axi_aw_payload_len", 8),
    Port("io_axi_aw_payload_size", 3),
    Port("io_axi_aw_payload_burst", 2),
    Port("io_axi_aw_payload_lock", 1),
    Port("io_axi_aw_payload_cache", 4),
    Port("io_axi_aw_payload_qos", 4),
    Port("io_axi_aw_payload_prot", 3),
    Port("io_axi_w_valid", 1),
    Port("io_axi_w_payload_data", 32),
    Port("io_axi_w_payload_strb", 4),
    Port("io_axi_w_payload_last", 1),
    Port("io_axi_b_ready", 1),
    Port("io_axi_ar_valid", 1),
    Port("io_axi_ar_payload_addr", 12),
    Port("io_axi_ar_payload_id", 2),
    Port("io_axi_ar_payload_region", 4),
    Port("io_axi_ar_payload_len", 8),
    Port("io_axi_ar_payload_size", 3),
    Port("io_axi_ar_payload_burst", 2),
    Port("io_axi_ar_payload_lock", 1),
    Port("io_axi_ar_payload_cache", 4),
    Port("io_axi_ar_payload_qos", 4),
    Port("io_axi_ar_payload_prot", 3),
    Port("io_axi_r_ready", 1),
    Port("clk", 1),
    Port("reset", 1),
)

COMPARED_OUTPUTS = (
    Port("io_axi_aw_ready", 1),
    Port("io_axi_w_ready", 1),
    Port("io_axi_b_valid", 1),
    Port("io_axi_b_payload_id", 2),
    Port("io_axi_b_payload_resp", 2),
    Port("io_axi_ar_ready", 1),
    Port("io_axi_r_valid", 1),
    Port("io_axi_r_payload_data", 32),
    Port("io_axi_r_payload_id", 2),
    Port("io_axi_r_payload_resp", 2),
    Port("io_axi_r_payload_last", 1),
    Port("io_observedBase", 32),
    Port("io_observedNext", 32),
    Port("io_observedFixed", 32),
)

ALWAYS_COMPARED = (
    "io_axi_aw_ready",
    "io_axi_w_ready",
    "io_axi_b_valid",
    "io_axi_ar_ready",
    "io_axi_r_valid",
    "io_observedBase",
    "io_observedNext",
    "io_observedFixed",
)

B_PAYLOAD_COMPARED = (
    "io_axi_b_payload_id",
    "io_axi_b_payload_resp",
)

R_PAYLOAD_COMPARED = (
    "io_axi_r_payload_data",
    "io_axi_r_payload_id",
    "io_axi_r_payload_resp",
    "io_axi_r_payload_last",
)

MODULE_DECLARATION = re.compile(
    r"(?m)^\s*module\s+([A-Za-z_][A-Za-z0-9_$]*)\b"
)


class ProofFailure(RuntimeError):
    pass


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--artifact-dir", required=True, type=Path)
    parser.add_argument("--workspace", required=True, type=Path)
    return parser.parse_args()


def safe_yosys_path(path: Path) -> str:
    absolute = str(path.resolve())
    if any(character.isspace() or character == '"' for character in absolute):
        raise ProofFailure(
            f"path is not safely representable in a Yosys script: {absolute}"
        )
    return absolute


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def write(path: Path, content: str) -> None:
    path.write_text(content, encoding="utf-8")


def run(
    command: Sequence[str],
    *,
    cwd: Path,
    log_path: Path | None = None,
) -> str:
    completed = subprocess.run(
        list(command),
        cwd=cwd,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        check=False,
    )
    output = completed.stdout
    if log_path is not None:
        write(log_path, output)
    if completed.returncode != 0:
        raise ProofFailure(
            f"command failed with exit code {completed.returncode}: "
            f"{' '.join(command)}\n{output}"
        )
    return output


def contains_address_literal(verilog: str, value: int) -> bool:
    decimal = re.search(
        rf"(?<![A-Za-z0-9_]){re.escape(str(value))}(?![A-Za-z0-9_])",
        verilog,
    )
    native_hex = f"12'h{value:03x}"
    return decimal is not None or native_hex in verilog.lower()


def validate_artifacts(artifact_dir: Path) -> tuple[Path, dict[int, Path]]:
    parameterized = artifact_dir / PARAMETERIZED_FILE
    concrete_by_offset = {
        offset: artifact_dir / CONCRETE_FILE.format(offset=offset)
        for offset in OFFSETS
    }

    required = (parameterized, *concrete_by_offset.values())
    for path in required:
        if not path.is_file() or path.stat().st_size == 0:
            raise ProofFailure(f"required generated RTL is missing or empty: {path}")

    candidate = read(parameterized)
    if "parameter integer TOP_OFFSET = 64" not in candidate:
        raise ProofFailure("parameterized top did not retain TOP_OFFSET")
    if "parameter integer OFFSET = 64" not in candidate:
        raise ProofFailure("parameterized child did not retain OFFSET")
    if ".OFFSET(TOP_OFFSET)" not in candidate:
        raise ProofFailure("parent-to-child OFFSET forwarding is missing")
    if not any(
        spelling in candidate
        for spelling in ("OFFSET + 4", "OFFSET+4", "(OFFSET + 4)")
    ):
        raise ProofFailure("derived OFFSET + 4 decode is missing")
    if not contains_address_literal(candidate, 128):
        raise ProofFailure("unrelated fixed address 0x080 was not preserved")
    if set(MODULE_DECLARATION.findall(candidate)) != {
        "NativeAxi4SlaveFactoryParameterizedTop",
        "NativeAxi4SlaveFactoryRegisterBlock",
    }:
        raise ProofFailure("unexpected parameterized module inventory")

    concrete_texts: list[str] = []
    for offset, path in concrete_by_offset.items():
        concrete = read(path)
        concrete_texts.append(concrete)
        if "parameter integer TOP_OFFSET" in concrete:
            raise ProofFailure(f"concrete offset {offset} retained TOP_OFFSET")
        if "parameter integer OFFSET" in concrete:
            raise ProofFailure(f"concrete offset {offset} retained OFFSET")
        if ".OFFSET(" in concrete:
            raise ProofFailure(f"concrete offset {offset} retained forwarding")
        for expected in (offset, offset + 4, 128):
            if not contains_address_literal(concrete, expected):
                raise ProofFailure(
                    f"concrete offset {offset} is missing address {expected}"
                )

    if len(set(concrete_texts)) != len(OFFSETS):
        raise ProofFailure(
            "ordinary SpinalVerilog witnesses were not independently specialized"
        )

    return parameterized, concrete_by_offset


def candidate_top(offset: int) -> str:
    return f"MorphNativeAxi4FactoryCandidateOffset{offset}"


def concrete_top(offset: int) -> str:
    return f"ConcreteNativeAxi4FactoryReferenceOffset{offset}"


def miter_top(offset: int) -> str:
    return f"NativeAxi4FactoryFormalMiterOffset{offset}"


def prepare_dut(
    *,
    source: Path,
    output: Path,
    script: Path,
    top: str,
    renamed_top: str,
    workspace: Path,
    offset: int | None,
) -> None:
    commands = [f"read_verilog -defer {safe_yosys_path(source)}"]
    if offset is not None:
        commands.append(f"chparam -set TOP_OFFSET {offset} {top}")
    commands.extend(
        (
            f"hierarchy -check -top {top}",
            "flatten",
            "proc",
            "async2sync",
            "opt_clean",
            "check -assert",
            f"rename -top {renamed_top}",
            f"write_rtlil {safe_yosys_path(output)}",
        )
    )
    write(script, "\n".join(commands) + "\n")
    run(
        ("yosys", "-q", "-s", script.name),
        cwd=workspace,
        log_path=workspace / f"{script.stem}.log",
    )
    if not output.is_file() or output.stat().st_size == 0:
        raise ProofFailure(f"Yosys did not publish prepared RTLIL: {output}")


def verilog_range(width: int) -> str:
    return "" if width == 1 else f"[{width - 1}:0] "


def wire_name(prefix: str, port: str) -> str:
    return f"{prefix}_{port}"


def module_connections(prefix: str) -> str:
    connections: list[tuple[str, str]] = [
        (port.name, port.name) for port in SHARED_INPUTS
    ]
    connections.extend(
        (port.name, wire_name(prefix, port.name)) for port in COMPARED_OUTPUTS
    )
    return ",\n".join(
        f"    .{port}({signal})" for port, signal in connections
    )


def assertion(name: str, indent: str = "      ") -> str:
    return (
        f"{indent}assert({wire_name('concrete', name)} == "
        f"{wire_name('morph', name)});"
    )


def equivalence_miter(offset: int, *, mutate_observed_fixed: bool) -> str:
    input_declarations = ",\n".join(
        f"  input wire {verilog_range(port.width)}{port.name}"
        for port in SHARED_INPUTS
    )
    output_declarations = "\n".join(
        declaration
        for port in COMPARED_OUTPUTS
        for declaration in (
            f"  wire {verilog_range(port.width)}"
            f"{wire_name('concrete', port.name)};",
            f"  wire {verilog_range(port.width)}"
            f"{wire_name('morph', port.name)};",
        )
    )
    observed_fixed_expression = wire_name("morph", "io_observedFixed")
    if mutate_observed_fixed:
        observed_fixed_expression = f"(~ {observed_fixed_expression})"

    always_assertions: list[str] = []
    for name in ALWAYS_COMPARED:
        morph_signal = wire_name("morph", name)
        if name == "io_observedFixed":
            morph_signal = "morph_observed_fixed_compared"
        always_assertions.append(
            f"      assert({wire_name('concrete', name)} == {morph_signal});"
        )

    b_payload_assertions = "\n".join(
        assertion(name, "        ") for name in B_PAYLOAD_COMPARED
    )
    r_payload_assertions = "\n".join(
        assertion(name, "        ") for name in R_PAYLOAD_COMPARED
    )

    return f"""module {miter_top(offset)} (
{input_declarations}
);
{output_declarations}
  wire [31:0] morph_observed_fixed_compared;

  assign morph_observed_fixed_compared = {observed_fixed_expression};

  {concrete_top(offset)} concrete_dut (
{module_connections('concrete')}
  );

  {candidate_top(offset)} morph_dut (
{module_connections('morph')}
  );

  always @* begin
    if ($initstate)
      assume(reset);
    if (!$initstate) begin
{chr(10).join(always_assertions)}
      if ({wire_name('concrete', 'io_axi_b_valid')} && {wire_name('morph', 'io_axi_b_valid')}) begin
{b_payload_assertions}
      end
      if ({wire_name('concrete', 'io_axi_r_valid')} && {wire_name('morph', 'io_axi_r_valid')}) begin
{r_payload_assertions}
      end
    end
  end
endmodule
"""


def sby_config(
    *,
    candidate: Path,
    concrete: Path,
    miter: Path,
    top: str,
    mutation: bool,
) -> str:
    if mutation:
        options = "mode bmc\ndepth 4\nexpect fail\nmulticlock off\ntimeout 120"
        engine = "smtbmc yices"
    else:
        options = "mode prove\nexpect pass\nmulticlock off\ntimeout 900"
        engine = "abc pdr"

    return f"""[options]
{options}

[engines]
{engine}

[script]
read_rtlil {candidate.name}
read_rtlil {concrete.name}
read_verilog -formal {miter.name}
hierarchy -check -top {top}
prep -top {top}
memory_map
setundef -undriven -anyseq
opt_clean
check -assert

[files]
{candidate.resolve()}
{concrete.resolve()}
{miter.resolve()}
"""


def regular_files(directory: Path) -> Iterable[Path]:
    return (path for path in directory.rglob("*") if path.is_file())


def run_sby(
    *,
    workspace: Path,
    config: Path,
    expected_status: str,
    require_counterexample: bool,
) -> None:
    output = run(
        ("sby", "-f", config.name),
        cwd=workspace,
        log_path=workspace / f"{config.stem}.driver.log",
    )
    work_directory = workspace / config.stem
    status_file = work_directory / "status"
    if not status_file.is_file():
        raise ProofFailure(
            f"SymbiYosys published no status for {config.name}\n{output}"
        )
    status_lines = [
        line.strip() for line in read(status_file).splitlines() if line.strip()
    ]
    if len(status_lines) != 1:
        raise ProofFailure(
            f"ambiguous SymbiYosys status for {config.name}: {status_lines}"
        )
    tokens = status_lines[0].split()
    if not tokens or any(not token.isdigit() for token in tokens[1:]):
        raise ProofFailure(
            f"malformed SymbiYosys status for {config.name}: {status_lines[0]}"
        )
    if tokens[0] != expected_status:
        raise ProofFailure(
            f"expected {expected_status} for {config.name}, got {tokens[0]}\n{output}"
        )

    if require_counterexample:
        files = list(regular_files(work_directory))
        traces = [
            path for path in files if path.suffix == ".vcd" and path.stat().st_size
        ]
        if not traces:
            raise ProofFailure("mutation FAIL published no VCD counterexample")
        engine_logs = "\n".join(
            read(path)
            for path in files
            if path.suffix in {".txt", ".log"}
        )
        if "Assert failed in" not in engine_logs:
            raise ProofFailure(
                "mutation FAIL was not caused by an assertion counterexample"
            )


def main() -> int:
    args = parse_args()
    artifact_dir = args.artifact_dir.resolve()
    workspace = args.workspace.resolve()
    workspace.mkdir(parents=True, exist_ok=True)

    parameterized, concrete_by_offset = validate_artifacts(artifact_dir)
    prepared: dict[int, tuple[Path, Path]] = {}

    for offset in OFFSETS:
        candidate = workspace / f"morph_candidate_offset_{offset}.il"
        prepare_dut(
            source=parameterized,
            output=candidate,
            script=workspace / f"prepare_morph_offset_{offset}.ys",
            top=SOURCE_TOP,
            renamed_top=candidate_top(offset),
            workspace=workspace,
            offset=offset,
        )

        concrete = workspace / f"concrete_reference_offset_{offset}.il"
        prepare_dut(
            source=concrete_by_offset[offset],
            output=concrete,
            script=workspace / f"prepare_concrete_offset_{offset}.ys",
            top=SOURCE_TOP,
            renamed_top=concrete_top(offset),
            workspace=workspace,
            offset=None,
        )
        prepared[offset] = candidate, concrete

    for offset in OFFSETS:
        candidate, concrete = prepared[offset]
        miter = workspace / f"native_axi4_equivalence_offset_{offset}.v"
        write(miter, equivalence_miter(offset, mutate_observed_fixed=False))
        config = workspace / f"native_axi4_equivalence_offset_{offset}.sby"
        write(
            config,
            sby_config(
                candidate=candidate,
                concrete=concrete,
                miter=miter,
                top=miter_top(offset),
                mutation=False,
            ),
        )
        run_sby(
            workspace=workspace,
            config=config,
            expected_status="PASS",
            require_counterexample=False,
        )
        print(f"formal PASS: native AXI4 offset {offset}")

    mutation_offset = 64
    candidate, concrete = prepared[mutation_offset]
    mutation_miter = workspace / "native_axi4_equivalence_offset_64_mutation.v"
    write(
        mutation_miter,
        equivalence_miter(mutation_offset, mutate_observed_fixed=True),
    )
    mutation_config = (
        workspace / "native_axi4_equivalence_offset_64_mutation.sby"
    )
    write(
        mutation_config,
        sby_config(
            candidate=candidate,
            concrete=concrete,
            miter=mutation_miter,
            top=miter_top(mutation_offset),
            mutation=True,
        ),
    )
    run_sby(
        workspace=workspace,
        config=mutation_config,
        expected_status="FAIL",
        require_counterexample=True,
    )
    print("mutation control FAIL with counterexample: native AXI4 offset 64")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ProofFailure as error:
        print(f"formal proof failure: {error}", file=sys.stderr)
        raise SystemExit(1)
