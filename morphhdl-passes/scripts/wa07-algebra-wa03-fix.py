#!/usr/bin/env python3
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[2]

algebra = root / "morphhdl-passes/src/test/scala/morphhdl/passes/transform/UnnamedWireExpressionAlgebraSpec.scala"
text = algebra.read_text(encoding="utf-8")
old = '''  private final case class ExpressionCase(
      label: String,
      aliasType: PackedType,
      expression: Fixture => RtlExpr,
      expectedRoot: String,
      expectedSources: Set[SymbolId]
  )

  private final case class Fixture(
      moduleId: ModuleId,
      scopeId: ScopeId,
      sourceA: SymbolId,
      sourceB: SymbolId,
      condition: SymbolId,
      alias: SymbolId,
      sink: SymbolId
  ) {
'''
new = '''  private final class ExpressionCase(
      val label: String,
      val aliasType: PackedType,
      val expression: Fixture => RtlExpr,
      val expectedRoot: String,
      val expectedSources: Set[SymbolId]
  )

  private final class Fixture(
      val moduleId: ModuleId,
      val scopeId: ScopeId,
      val sourceA: SymbolId,
      val sourceB: SymbolId,
      val condition: SymbolId,
      val alias: SymbolId,
      val sink: SymbolId
  ) {
'''
if text.count(old) != 1:
    raise SystemExit("algebra helper class block did not match exactly once")
text = text.replace(old, new)
text = text.replace("    ExpressionCase(\n", "    new ExpressionCase(\n")
text = text.replace("    val fixture = Fixture(\n", "    val fixture = new Fixture(\n")
algebra.write_text(text, encoding="utf-8")

wa03 = root / "morphhdl-passes/scripts/check-wa03-gates.py"
text = wa03.read_text(encoding="utf-8")
old = '    expected_items = {"WA-04", "WA-05", "WA-06"}\n'
new = '    expected_items = {"WA-04", "WA-05", "WA-06", "WA-07"}\n'
if text.count(old) != 1:
    raise SystemExit("WA-03 expected activation set did not match exactly once")
text = text.replace(old, new)
old = '''                {"activation_item": "WA-06"},
            ],
'''
new = '''                {"activation_item": "WA-06"},
                {"activation_item": "WA-07"},
                {"activation_item": "WA-07"},
            ],
'''
if text.count(old) != 1:
    raise SystemExit("WA-03 self-test slot block did not match exactly once")
wa03.write_text(text.replace(old, new), encoding="utf-8")

for temporary in (
    root / ".github/workflows/wa07-algebra-wa03-fix.yml",
    root / "morphhdl-passes/scripts/wa07-algebra-wa03-fix.py",
    root / ".github/workflows/wa07-wa03-slot-patch.yml",
    root / "morphhdl-passes/scripts/wa07-wa03-slot-patch.py",
):
    if temporary.exists():
        temporary.unlink()

subprocess.run(["git", "config", "user.name", "morphhdl-wa07-bot"], cwd=root, check=True)
subprocess.run(["git", "config", "user.email", "morphhdl-wa07-bot@users.noreply.github.com"], cwd=root, check=True)
subprocess.run(["git", "add", "-A"], cwd=root, check=True)
subprocess.run(["git", "commit", "-m", "WA-07: close expression algebra and formal-slot guards"], cwd=root, check=True)
subprocess.run(["git", "push", "origin", "HEAD:agent/wa-07-unified-expression-alias-pass"], cwd=root, check=True)
