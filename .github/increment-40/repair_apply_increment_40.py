#!/usr/bin/env python3
from pathlib import Path

path = Path('.github/increment-40/apply_increment_40.py')
text = path.read_text(encoding='utf-8')

workflow_write = '    write(".github/workflows/morphhdl-external-symbolic-width.yml", FOCUSED_WORKFLOW)\n'
if text.count(workflow_write) != 1:
    raise SystemExit('focused-workflow write anchor was not found exactly once')
text = text.replace(workflow_write, '', 1)

for workflow_path in (
    '        ".github/workflows/agent-increment-40-inventory.yml",\n',
    '        ".github/workflows/agent-increment-40-controller.yml",\n',
):
    if text.count(workflow_path) != 1:
        raise SystemExit(f'workflow cleanup anchor was not found exactly once: {workflow_path.strip()}')
    text = text.replace(workflow_path, '', 1)

path.write_text(text, encoding='utf-8')
print('Separated Increment 40 code migration from workflow-file publication')
