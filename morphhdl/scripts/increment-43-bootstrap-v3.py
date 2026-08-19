#!/usr/bin/env python3
from pathlib import Path

source = Path("morphhdl/scripts/increment-43-bootstrap.py")
text = source.read_text(encoding="utf-8")

old_tree = 'source_tree = run("git", "rev-parse", "HEAD^{{tree}}", capture=True)'
new_tree = 'source_tree = run("git", "rev-parse", "HEAD^{tree}", capture=True)'
if text.count(old_tree) != 1:
    raise SystemExit(f"expected one tree-resolution command, found {text.count(old_tree)}")
text = text.replace(old_tree, new_tree, 1)

workflow_start = 'write(\n    ".github/workflows/morphhdl-external-memory.yml",'
cleanup_start = '# Remove the one-time bootstrap from the final publication delta.'
if text.count(workflow_start) != 1 or text.count(cleanup_start) != 1:
    raise SystemExit("expected one permanent-workflow block and one cleanup block")
start = text.index(workflow_start)
end = text.index(cleanup_start, start)
text = text[:start] + text[end:]

workflow_cleanup = '    ".github/workflows/increment-43-bootstrap.yml",\n'
if text.count(workflow_cleanup) != 1:
    raise SystemExit("expected one bootstrap-workflow cleanup entry")
text = text.replace(workflow_cleanup, "", 1)

source.write_text(text, encoding="utf-8")
Path(__file__).unlink()
namespace = {"__name__": "__main__", "__file__": str(source)}
exec(compile(source.read_text(encoding="utf-8"), str(source), "exec"), namespace)
