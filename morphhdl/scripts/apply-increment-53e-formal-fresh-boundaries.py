#!/usr/bin/env python3
from pathlib import Path

path = Path(
    "morphhdl/src/test/scala/morphhdl/"
    "NativeStreamFifoCCFormalEquivalenceTests.scala"
)
value = path.read_text()
old = '''  private def generate(directory: Path): Generated = {
    val depth = HdlInt.param(
      "DEPTH",
      default = BigInt(8),
      min = BigInt(4),
      max = BigInt(16)
    )
    val candidateByResetMode = ResetModes.map { buffered =>
'''
new = '''  private def generate(directory: Path): Generated = {
    val candidateByResetMode = ResetModes.map { buffered =>
      val depth = HdlInt.param(
        "DEPTH",
        default = BigInt(8),
        min = BigInt(4),
        max = BigInt(16)
      )
'''
if old not in value and new not in value:
    raise SystemExit("fresh formal boundary marker not found")
path.write_text(value.replace(old, new, 1))
