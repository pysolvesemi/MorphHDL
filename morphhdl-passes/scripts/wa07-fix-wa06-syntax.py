#!/usr/bin/env python3
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[2]
path = root / "morphhdl-passes/scripts/check-wa06-pipeline.py"
text = path.read_text(encoding="utf-8")
old = '    pv_roadmap = "- [x] **Increment 58 — Retirement**\n"\n'
if old not in text:
    # The prior patch accidentally embedded a literal newline inside the string.
    old = '    pv_roadmap = "- [x] **Increment 58 — Retirement**\n"\n'.replace('\\n', '\n')
new = '    pv_roadmap = "- [x] **Increment 58 — Retirement**\\n"\n'
if text.count(old) != 1:
    raise SystemExit(f"WA-06 malformed pv_roadmap string matched {text.count(old)} times")
path.write_text(text.replace(old, new), encoding="utf-8")

for temporary in (
    root / ".github/workflows/wa07-fix-wa06-syntax.yml",
    root / "morphhdl-passes/scripts/wa07-fix-wa06-syntax.py",
):
    if temporary.exists():
        temporary.unlink()

subprocess.run(["python3", "-m", "py_compile", str(path)], cwd=root, check=True)
subprocess.run(["git", "config", "user.name", "morphhdl-wa07-bot"], cwd=root, check=True)
subprocess.run(["git", "config", "user.email", "morphhdl-wa07-bot@users.noreply.github.com"], cwd=root, check=True)
subprocess.run(["git", "add", "-A"], cwd=root, check=True)
subprocess.run(["git", "commit", "-m", "WA-07: repair inherited WA-06 guard syntax"], cwd=root, check=True)
subprocess.run(["git", "push", "origin", "HEAD:agent/wa-07-unified-expression-alias-pass"], cwd=root, check=True)
