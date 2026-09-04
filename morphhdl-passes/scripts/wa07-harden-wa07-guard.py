#!/usr/bin/env python3
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[2]
path = root / "morphhdl-passes/scripts/check-wa07-expression-pass.py"
text = path.read_text(encoding="utf-8")
old = '''    for marker in required_scope:
        if marker.lower() not in wa07_body.lower():
            failures.append(
                f"{path}: WA07-ROADMAP-SCOPE: WA-07 entry is missing {marker!r}"
            )
'''
new = '''    normalized_body = " ".join(wa07_body.lower().split())
    for marker in required_scope:
        normalized_marker = " ".join(marker.lower().split())
        if normalized_marker not in normalized_body:
            failures.append(
                f"{path}: WA07-ROADMAP-SCOPE: WA-07 entry is missing {marker!r}"
            )
'''
if text.count(old) != 1:
    raise SystemExit("WA-07 roadmap marker loop did not match exactly once")
path.write_text(text.replace(old, new), encoding="utf-8")

for temporary in (
    root / ".github/workflows/wa07-harden-wa07-guard.yml",
    root / "morphhdl-passes/scripts/wa07-harden-wa07-guard.py",
):
    if temporary.exists():
        temporary.unlink()

subprocess.run(["python3", "-m", "py_compile", str(path)], cwd=root, check=True)
subprocess.run(["git", "config", "user.name", "morphhdl-wa07-bot"], cwd=root, check=True)
subprocess.run(["git", "config", "user.email", "morphhdl-wa07-bot@users.noreply.github.com"], cwd=root, check=True)
subprocess.run(["git", "add", "-A"], cwd=root, check=True)
subprocess.run(["git", "commit", "-m", "WA-07: harden expression-roadmap guard"], cwd=root, check=True)
subprocess.run(["git", "push", "origin", "HEAD:agent/wa-07-unified-expression-alias-pass"], cwd=root, check=True)
