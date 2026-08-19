#!/usr/bin/env python3
from pathlib import Path

source = Path("morphhdl/scripts/increment-43-bootstrap.py")
text = source.read_text(encoding="utf-8")
old = 'source_tree = run("git", "rev-parse", "HEAD^{{tree}}", capture=True)'
new = 'source_tree = run("git", "rev-parse", "HEAD^{tree}", capture=True)'
if text.count(old) != 1:
    raise SystemExit(f"expected one tree-resolution command, found {text.count(old)}")
source.write_text(text.replace(old, new, 1), encoding="utf-8")
Path(__file__).unlink()
namespace = {"__name__": "__main__", "__file__": str(source)}
exec(compile(source.read_text(encoding="utf-8"), str(source), "exec"), namespace)
