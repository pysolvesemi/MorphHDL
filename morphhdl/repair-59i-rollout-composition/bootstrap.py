#!/usr/bin/env python3
"""Normalize nested template quoting, then run the exact composition generator."""
from __future__ import annotations

import os
import sys
from pathlib import Path

SOURCE = Path(__file__).resolve().parent / "apply.py"


def replace_once(text: str, before: str, after: str, label: str) -> str:
    count = text.count(before)
    if count != 1:
        raise RuntimeError(label + " anchor count changed: " + str(count))
    return text.replace(before, after, 1)


def main() -> None:
    text = SOURCE.read_text()
    text = replace_once(
        text,
        "    old_restore = '''    restore = '''def restore_source",
        "    old_restore = \"\"\"    restore = '''def restore_source",
        "old nested-template opening quote",
    )
    text = replace_once(
        text,
        "        \"    paths = load_local_enable_review(root).inherited_inventory(root, paths, BASE)\\\\n\" + inventory, 1)\n'''\n    new_restore = '''    restore = '''def restore_source",
        "        \"    paths = load_local_enable_review(root).inherited_inventory(root, paths, BASE)\\\\n\" + inventory, 1)\n\"\"\"\n    new_restore = \"\"\"    restore = '''def restore_source",
        "old close and new nested-template opening quote",
    )
    text = replace_once(
        text,
        "    require(text.count(inventory) == 1, \"59i composition inventory anchor changed\")\n'''\n    text = replace_once(text, old_restore, new_restore,",
        "    require(text.count(inventory) == 1, \"59i composition inventory anchor changed\")\n\"\"\"\n    text = replace_once(text, old_restore, new_restore,",
        "new nested-template closing quote",
    )
    text = replace_once(
        text,
        "    paths = changed(COMMON, FEATURE) | changed(COMMON, TARGET) | changed(COMMON, combined_base)\n    paths |= set(PATCHED)\n",
        "    candidates = (changed(COMMON, FEATURE) | changed(COMMON, TARGET) |\n                  changed(COMMON, combined_base))\n    reviewed_prefixes = (\n        \"core/src/main/\", \"lib/src/main/\", \"idslplugin/src/main/\",\n        \"sim/src/main/\", \"morphhdl/src/main/\", \"morphhdl/src/test/\",\n        \"morphhdl/scripts/\", \"morphhdl/contracts/\",\n        \"morphhdl-passes/src/main/\", \"morphhdl-passes/src/test/\",\n    )\n    paths = {path for path in candidates if path.startswith(reviewed_prefixes)}\n    paths |= set(PATCHED)\n",
        "reviewed composition inventory filter",
    )
    compile(text, str(SOURCE), "exec")
    SOURCE.write_text(text)
    os.execv(sys.executable, [sys.executable, str(SOURCE)])


if __name__ == "__main__":
    main()
