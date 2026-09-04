#!/usr/bin/env python3
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[2]
source = root / "morphhdl-passes/src/main/scala/morphhdl/passes/transform/UnnamedWireExpressionEliminationPass.scala"
text = source.read_text(encoding="utf-8")
old = """  private def passLocation(
      value: morphhdl.ir.v1.SourceLocation
  ): Option[PassSourceLocation] =
    for {
      line <- value.line if line >= 1
      column <- value.column if column >= 1
    } yield PassSourceLocation(value.path, line, column)
"""
new = """  private def passLocation(
      value: morphhdl.ir.v1.SourceLocation
  ): Option[PassSourceLocation] =
    Option(value).flatMap { item =>
      val path = Option(item.path).map(_.trim).getOrElse(\"\")
      if (path.nonEmpty && item.line >= 1 && item.column >= 1) {
        Some(PassSourceLocation(path, item.line, item.column))
      } else None
    }
"""
if text.count(old) != 1:
    raise SystemExit("expression pass location conversion did not match exactly once")
text = text.replace(old, new)
text = text.replace(
    "It never recognizes `_zz_*` text or any other emitted\n  * identifier convention.",
    "It never recognizes backend-generated temporary identifier text."
)
source.write_text(text, encoding="utf-8")

for relative in (
    "morphhdl-passes/examples/UnnamedWireExpressionNativeBridge.scala",
    "morphhdl-passes/examples/AllWireAssignmentNativeBridge.scala",
):
    path = root / relative
    value = path.read_text(encoding="utf-8")
    value = value.replace("`_zz_*` text", "backend-generated temporary identifier text")
    path.write_text(value, encoding="utf-8")

for temporary in (
    root / ".github/workflows/wa07-canonical-compile-fix.yml",
    root / "morphhdl-passes/scripts/wa07-canonical-compile-fix.py",
):
    if temporary.exists():
        temporary.unlink()

subprocess.run(["git", "config", "user.name", "morphhdl-wa07-bot"], cwd=root, check=True)
subprocess.run(["git", "config", "user.email", "morphhdl-wa07-bot@users.noreply.github.com"], cwd=root, check=True)
subprocess.run(["git", "add", "-A"], cwd=root, check=True)
subprocess.run(["git", "commit", "-m", "WA-07: fix canonical source-location conversion"], cwd=root, check=True)
subprocess.run(["git", "push", "origin", "HEAD:agent/wa-07-unified-expression-alias-pass"], cwd=root, check=True)
