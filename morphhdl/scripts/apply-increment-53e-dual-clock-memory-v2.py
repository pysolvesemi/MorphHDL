#!/usr/bin/env python3
from pathlib import Path
import runpy

source_path = Path("morphhdl/scripts/apply-increment-53e-dual-clock-memory.py")
source = source_path.read_text(encoding="utf-8")

assignment_start = source.index('    old_complete = """')
new_assignment_start = source.index('    new_complete = """', assignment_start)
marker_code = '''    complete_start = text.index(
        "  private def independentDontCareProcessesAreComplete("
    )
    complete_end = text.index(
        "  private def alwaysBlocks(",
        complete_start
    )
'''
source = source[:assignment_start] + marker_code + source[new_assignment_start:]

call_start = source.index(
    "    text = replace_once(\n        text,\n        old_complete,",
    assignment_start,
)
call_end = source.index("\n\n    text = text.replace(", call_start)
replacement = '''    text = (
        text[:complete_start]
        + new_complete
        + "\\n"
        + text[complete_end:]
    )'''
source = source[:call_start] + replacement + source[call_end:]

temporary = Path("/tmp/apply-increment-53e-dual-clock-memory.py")
temporary.write_text(source, encoding="utf-8")
runpy.run_path(str(temporary), run_name="__main__")
