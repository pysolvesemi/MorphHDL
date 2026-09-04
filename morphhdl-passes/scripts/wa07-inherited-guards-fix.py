#!/usr/bin/env python3
from pathlib import Path
import re
import subprocess

root = Path(__file__).resolve().parents[2]


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one exact replacement, found {count}")
    path.write_text(text.replace(old, new), encoding="utf-8")

# The self-reference is intentionally represented by the stable, stronger cycle
# rejection in the public deterministic report.
spec = root / "morphhdl-passes/src/test/scala/morphhdl/passes/transform/UnnamedWireExpressionEliminationPassSpec.scala"
replace_once(
    spec,
    "      UnnamedWireExpressionSafetyReason.SourceSelfReference\n",
    "      UnnamedWireExpressionSafetyReason.CombinationalCycle\n",
    "self-reference diagnostic assertion",
)

# Reserve production handoff paths for WA-08 only after WA-07 and PV-58.
boundary = root / "morphhdl-passes/scripts/check-boundary.sh"
replace_once(
    boundary,
    "grep -Eq '^- \\[x\\] \\*\\*WA-06[[:space:]]+—' \"${roadmap}\"",
    "grep -Eq '^- \\[x\\] \\*\\*WA-07[[:space:]]+—' \"${roadmap}\"",
    "WA-08 dependency boundary",
)
boundary_test = root / "morphhdl-passes/scripts/test-boundary-guard.sh"
text = boundary_test.read_text(encoding="utf-8")
text = text.replace(
    "'WA-08 handoff is accepted after WA-06 and PV-58 are checked'",
    "'WA-08 handoff is accepted after WA-07 and PV-58 are checked'",
)
boundary_test.write_text(text, encoding="utf-8")

# Historical direct-pass guards now check the package-private proof selector,
# while the product API exposes only WireAliasPassConfiguration(enabled = ...).
wa04 = root / "morphhdl-passes/scripts/check-wa04-pass.py"
text = wa04.read_text(encoding="utf-8")
text = text.replace(
    '"configuration.eliminateUnnamedAliases",',
    '"configuration.isEnabled(passId)",',
)
text = text.replace(
    '"WireAliasPassConfiguration(eliminateUnnamedAliases = true)",',
    '"WireAliasPassConfiguration.selectedForTesting",',
)
wa04.write_text(text, encoding="utf-8")

wa05 = root / "morphhdl-passes/scripts/check-wa05-pass.py"
text = wa05.read_text(encoding="utf-8")
text = text.replace(
    '"configuration.eliminateNamedAliases",',
    '"configuration.isEnabled(passId)",',
)
text = text.replace(
    '"WireAliasPassConfiguration(eliminateNamedAliases = true)",',
    '"WireAliasPassConfiguration.selectedForTesting",',
)
text = text.replace(
    '"directNamedAlias(directUnnamedAlias(popPayloadSource))",',
    '"directNamedAlias(directUnnamedAlias(expressionUnnamedAlias(popPayloadSource)))",',
)
wa05.write_text(text, encoding="utf-8")

wa06 = root / "morphhdl-passes/scripts/check-wa06-pipeline.py"
text = wa06.read_text(encoding="utf-8")
text = text.replace(
    '    "eliminateUnnamedAliases = true",\n    "eliminateNamedAliases = true",\n',
    '    "WireAliasPassConfiguration.selectedForTesting",\n'
    '    "PassId.UnnamedWireAliasElimination",\n'
    '    "PassId.NamedWireAliasElimination",\n',
)
text = text.replace('    "run-wa06-regression.sh",\n', '    "run-wa07-regression.sh",\n')
text = text.replace('    "either pass independently",\n', '    "historical direct-alias stages",\n')

old_roadmap = re.compile(
    r"def roadmap_failures\(path: Path, text: str, pv_text: str\) -> list\[str\]:[\s\S]*?\n    return failures\n\n\ndef manifest_failures",
)
new_roadmap = '''def roadmap_failures(path: Path, text: str, pv_text: str) -> list[str]:
    try:
        entries = roadmap_entries(text)
    except AssertionError as error:
        return [f"{path}: WA06-ROADMAP: {error}"]

    failures: list[str] = []
    for item in ("WA-05", "WA-06", "WA-07", "WA-08"):
        if item not in entries:
            failures.append(f"{path}: WA06-ROADMAP: missing {item}")
    if failures:
        return failures

    wa05_checked, wa05_body = entries["WA-05"]
    wa06_checked, wa06_body = entries["WA-06"]
    wa07_checked, wa07_body = entries["WA-07"]
    wa08_checked, wa08_body = entries["WA-08"]
    if not wa05_checked or "**Status:** `COMPLETED`" not in wa05_body:
        failures.append(f"{path}: WA06-DEPENDENCY: WA-05 must remain completed")
    if PV58.search(pv_text) is None:
        failures.append(f"{path}: WA06-PV58: Increment 58 must remain completed")
    if not wa06_checked or "**Status:** `COMPLETED`" not in wa06_body:
        failures.append(f"{path}: WA06-STATUS: WA-06 must remain completed")
    if not wa07_checked or "**Status:** `COMPLETED`" not in wa07_body:
        failures.append(f"{path}: WA06-SUCCESSOR: completed WA-07 must retain WA-06")
    if wa08_checked or "**Status:** `READY`" not in wa08_body:
        failures.append(f"{path}: WA06-NEXT-STATUS: WA-08 must remain open and READY")

    required_scope = (
        "optional MorphHDL-IR pipeline entrypoint",
        "historical",
        "unnamed-then-named",
        "alias chains",
        "fanout",
        "without parsing emitted Verilog",
        "deterministic reports",
        "idempotent IR",
        "byte-identical repeated emission",
        "strict Verilog-2001 legality",
        "synthesis",
        "formal equivalence",
        "common pre-pass StreamFifo reference",
    )
    for marker in required_scope:
        if marker.lower() not in wa06_body.lower():
            failures.append(
                f"{path}: WA06-ROADMAP-SCOPE: WA-06 entry is missing {marker!r}"
            )
    return failures


def manifest_failures'''
text, count = old_roadmap.subn(new_roadmap, text, count=1)
if count != 1:
    raise SystemExit("WA-06 roadmap guard replacement did not match")

old_self_test = re.compile(
    r"    roadmap = \"\"\"- \[x\] \*\*WA-05 — Named\*\*[\s\S]*?"
    r"    pv_roadmap = \"- \[x\] \*\*Increment 58 — Retirement\*\*\\n\"",
)
new_self_test = '''    roadmap = """- [x] **WA-05 — Named**

  **Status:** `COMPLETED`.

- [x] **WA-06 — Ordered**

  **Status:** `COMPLETED`.
  optional MorphHDL-IR pipeline entrypoint; historical direct stages use the
  unnamed-then-named order; alias chains and fanout; without parsing emitted
  Verilog; deterministic reports; idempotent IR; byte-identical repeated
  emission; strict Verilog-2001 legality; synthesis; formal equivalence;
  common pre-pass StreamFifo reference.

- [x] **WA-07 — Expressions**

  **Status:** `COMPLETED`.

- [ ] **WA-08 — Handoff**

  **Status:** `READY`.
"""
    pv_roadmap = "- [x] **Increment 58 — Retirement**\\n"'''
text, count = old_self_test.subn(new_self_test, text, count=1)
if count != 1:
    raise SystemExit("WA-06 self-test roadmap replacement did not match")
text = text.replace(
    'roadmap.replace("**Status:** `READY`.", "**Status:** `BLOCKED` by WA-06.")',
    'roadmap.replace("**Status:** `READY`.", "**Status:** `BLOCKED` by WA-07.")',
)
text = text.replace(
    '"blocked WA-07 after completed dependencies was not rejected"',
    '"blocked WA-08 after completed WA-07 was not rejected"',
)
wa06.write_text(text, encoding="utf-8")

for temporary in (
    root / ".github/workflows/wa07-inherited-guards-fix.yml",
    root / "morphhdl-passes/scripts/wa07-inherited-guards-fix.py",
):
    if temporary.exists():
        temporary.unlink()

subprocess.run(["git", "config", "user.name", "morphhdl-wa07-bot"], cwd=root, check=True)
subprocess.run(["git", "config", "user.email", "morphhdl-wa07-bot@users.noreply.github.com"], cwd=root, check=True)
subprocess.run(["git", "add", "-A"], cwd=root, check=True)
subprocess.run(["git", "commit", "-m", "WA-07: update inherited direct-pass guards for one flag"], cwd=root, check=True)
subprocess.run(["git", "push", "origin", "HEAD:agent/wa-07-unified-expression-alias-pass"], cwd=root, check=True)
