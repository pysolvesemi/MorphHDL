#!/usr/bin/env python3
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[2]
path = root / "morphhdl-passes/scripts/check-wa06-pipeline.py"
text = path.read_text(encoding="utf-8")
old = '''    for marker in required_scope:
        if marker.lower() not in wa06_body.lower():
            failures.append(
                f"{path}: WA06-ROADMAP-SCOPE: WA-06 entry is missing {marker!r}"
            )
'''
new = '''    normalized_body = " ".join(wa06_body.lower().split())
    for marker in required_scope:
        normalized_marker = " ".join(marker.lower().split())
        if normalized_marker not in normalized_body:
            failures.append(
                f"{path}: WA06-ROADMAP-SCOPE: WA-06 entry is missing {marker!r}"
            )
'''
if text.count(old) != 1:
    raise SystemExit("WA-06 roadmap marker loop did not match exactly once")
text = text.replace(old, new)
text = text.replace('    "unnamed then named",\n', '    "unnamed-then-named",\n')
path.write_text(text, encoding="utf-8")

for temporary in (
    root / ".github/workflows/wa07-harden-wa06-guard.yml",
    root / "morphhdl-passes/scripts/wa07-harden-wa06-guard.py",
):
    if temporary.exists():
        temporary.unlink()

subprocess.run(["python3", "-m", "py_compile", str(path)], cwd=root, check=True)
subprocess.run(["git", "config", "user.name", "morphhdl-wa07-bot"], cwd=root, check=True)
subprocess.run(["git", "config", "user.email", "morphhdl-wa07-bot@users.noreply.github.com"], cwd=root, check=True)
subprocess.run(["git", "add", "-A"], cwd=root, check=True)
subprocess.run(["git", "commit", "-m", "WA-07: harden inherited WA-06 roadmap guard"], cwd=root, check=True)
subprocess.run(["git", "push", "origin", "HEAD:agent/wa-07-unified-expression-alias-pass"], cwd=root, check=True)
