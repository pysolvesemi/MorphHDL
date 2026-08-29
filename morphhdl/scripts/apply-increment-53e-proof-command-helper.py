#!/usr/bin/env python3
from pathlib import Path

fixtures = Path(
    "morphhdl/src/test/scala/morphhdl/NativeStreamFifoCCProofFixtures.scala"
)
value = fixtures.read_text()
old_definition = "  def run(directory: Path, command: Seq[String]): (Int, String) = {\n"
new_definition = "  def runCommand(directory: Path, command: Seq[String]): (Int, String) = {\n"
if value.count(old_definition) != 1:
    raise SystemExit(
        f"proof command helper definition count={value.count(old_definition)}"
    )
value = value.replace(old_definition, new_definition, 1)
old_require = "    val (code, output) = run(directory, command)\n"
new_require = "    val (code, output) = runCommand(directory, command)\n"
if value.count(old_require) != 1:
    raise SystemExit(
        f"proof requireTool invocation count={value.count(old_require)}"
    )
fixtures.write_text(value.replace(old_require, new_require, 1))

for relative in (
    "NativeStreamFifoCCImplementationProofTests.scala",
    "NativeStreamFifoCCFormalEquivalenceTests.scala",
):
    path = Path("morphhdl/src/test/scala/morphhdl") / relative
    value = path.read_text()
    count = value.count("run(")
    if count == 0:
        raise SystemExit(f"{relative}: no process-helper calls found")
    value = value.replace("run(", "runCommand(")
    if "run(" in value:
        raise SystemExit(f"{relative}: stale process-helper call remains")
    path.write_text(value)
