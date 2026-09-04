#!/usr/bin/env python3
from pathlib import Path
import re
import subprocess

root = Path(__file__).resolve().parents[2]
pass_root = root / "morphhdl-passes"

for path in sorted(pass_root.rglob("*.scala")):
    text = path.read_text(encoding="utf-8")
    original = text
    text = re.sub(
        r"WireAliasPassConfiguration\(\s*eliminateUnnamedAliases\s*=\s*true\s*,\s*eliminateNamedAliases\s*=\s*true\s*\)",
        "WireAliasPassConfiguration.selectedForTesting(\n"
        "        morphhdl.passes.api.PassId.UnnamedWireAliasElimination,\n"
        "        morphhdl.passes.api.PassId.NamedWireAliasElimination\n"
        "      )",
        text,
    )
    text = re.sub(
        r"WireAliasPassConfiguration\(\s*eliminateUnnamedAliases\s*=\s*true\s*\)",
        "WireAliasPassConfiguration.selectedForTesting(\n"
        "      morphhdl.passes.api.PassId.UnnamedWireAliasElimination\n"
        "    )",
        text,
    )
    text = re.sub(
        r"WireAliasPassConfiguration\(\s*eliminateNamedAliases\s*=\s*true\s*\)",
        "WireAliasPassConfiguration.selectedForTesting(\n"
        "      morphhdl.passes.api.PassId.NamedWireAliasElimination\n"
        "    )",
        text,
    )
    text = text.replace(
        "if (!configuration.eliminateUnnamedAliases)",
        "if (!configuration.isEnabled(passId))",
    )
    text = text.replace(
        "if (!configuration.eliminateNamedAliases)",
        "if (!configuration.isEnabled(passId))",
    )
    text = text.replace(
        'references.foreach(_.id.value should include("wa07-inline"))',
        'references.foreach(value => value.id.value should include("wa07-inline"))',
    )
    if text != original:
        path.write_text(text, encoding="utf-8")

api = pass_root / "src/main/scala/morphhdl/passes/api/PassContracts.scala"
text = api.read_text(encoding="utf-8")
text = re.sub(
    r"\nprivate\[morphhdl\] sealed trait UnnamedSelectionCompatibility[\s\S]*?"
    r"private\[morphhdl\] object NamedSelectionCompatibility \{\n"
    r"  implicit object Enabled extends NamedSelectionCompatibility\n"
    r"\}\n",
    "\n",
    text,
    count=1,
)
text = re.sub(
    r"\n  // Internal read-only compatibility for the already reviewed direct passes\.\n"
    r"  private\[morphhdl\] def eliminateUnnamedAliases: Boolean =\n"
    r"    isEnabled\(PassId\.UnnamedWireAliasElimination\)\n"
    r"  private\[morphhdl\] def eliminateNamedAliases: Boolean =\n"
    r"    isEnabled\(PassId\.NamedWireAliasElimination\)\n",
    "\n",
    text,
    count=1,
)
text = re.sub(
    r"\n  private\[morphhdl\] def apply\(\n"
    r"      eliminateUnnamedAliases: Boolean[\s\S]*?"
    r"    \)\n\n"
    r"  /\*\*\n    \* Internal-only selection",
    "\n  /**\n    * Internal-only selection",
    text,
    count=1,
)
text = text.replace(
    "    val requested = passes.toVector.filter(_ != null)\n",
    "    val requested = passes.toVector\n",
)
api.write_text(text, encoding="utf-8")

# The public API must contain no legacy per-pass Boolean parameter or getter.
remaining = []
for path in sorted(pass_root.rglob("*.scala")):
    text = path.read_text(encoding="utf-8")
    for token in ("eliminateUnnamedAliases", "eliminateNamedAliases"):
        if token in text:
            remaining.append(f"{path.relative_to(root)}: {token}")
if remaining:
    raise SystemExit("legacy per-pass flags remain:\n  " + "\n  ".join(remaining))

# Remove this one-shot workflow and script from the final branch.
for temporary in (
    root / ".github/workflows/wa07-single-flag-cleanup.yml",
    root / "morphhdl-passes/scripts/wa07-single-flag-cleanup.py",
):
    if temporary.exists():
        temporary.unlink()

subprocess.run(["git", "config", "user.name", "morphhdl-wa07-bot"], cwd=root, check=True)
subprocess.run(["git", "config", "user.email", "morphhdl-wa07-bot@users.noreply.github.com"], cwd=root, check=True)
subprocess.run(["git", "add", "-A"], cwd=root, check=True)
subprocess.run(
    ["git", "commit", "-m", "WA-07: expose only one all-or-none pass flag"],
    cwd=root,
    check=True,
)
subprocess.run(["git", "push", "origin", "HEAD:agent/wa-07-unified-expression-alias-pass"], cwd=root, check=True)
