#!/usr/bin/env python3
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[2]
path = root / "morphhdl-passes/scripts/check-wa03-gates.py"
text = path.read_text(encoding="utf-8")
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
    raise SystemExit("WA-03 self-test slots did not match exactly once")
path.write_text(text.replace(old, new), encoding="utf-8")

for temporary in (
    root / ".github/workflows/wa07-wa03-slot-patch.yml",
    root / "morphhdl-passes/scripts/wa07-wa03-slot-patch.py",
):
    if temporary.exists():
        temporary.unlink()

subprocess.run(["git", "config", "user.name", "morphhdl-wa07-bot"], cwd=root, check=True)
subprocess.run(["git", "config", "user.email", "morphhdl-wa07-bot@users.noreply.github.com"], cwd=root, check=True)
subprocess.run(["git", "add", "-A"], cwd=root, check=True)
subprocess.run(["git", "commit", "-m", "WA-07: extend the common formal slot guard"], cwd=root, check=True)
subprocess.run(["git", "push", "origin", "HEAD:agent/wa-07-unified-expression-alias-pass"], cwd=root, check=True)
