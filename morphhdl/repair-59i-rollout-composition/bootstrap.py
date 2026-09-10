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
        "    candidates = (changed(COMMON, FEATURE) | changed(COMMON, TARGET) |\n                  changed(COMMON, combined_base))\n    paths = {path for path in candidates if\n             \"/src/main/\" in \"/\" + path or \"/src/test/\" in \"/\" + path or\n             path.startswith(\"morphhdl/scripts/\") or\n             path.startswith(\"morphhdl/contracts/\")}\n    paths |= set(PATCHED)\n",
        "reviewed composition inventory filter",
    )
    text = replace_once(
        text,
        "        require(source.is_file() and not source.is_symlink() and\n                stat.S_ISREG(source.stat().st_mode) and not source.stat().st_mode & 0o111,\n                \"composition source must be a regular non-executable file: \" + path)\n",
        "        require(source.is_file() and not source.is_symlink() and\n                stat.S_ISREG(source.stat().st_mode),\n                \"composition source must be a regular file: \" + path)\n",
        "reviewed executable source allowance",
    )
    text = replace_once(
        text,
        "        require(mode == \"100644\" and stage_number == \"0\" and raw_path.decode() == path,\n                \"composition source index mode/stage changed: \" + path)\n",
        "        require(mode in (\"100644\", \"100755\") and stage_number == \"0\" and\n                raw_path.decode() == path,\n                \"composition source index mode/stage changed: \" + path)\n",
        "reviewed Git mode allowance",
    )
    compile(text, str(SOURCE), "exec")
    SOURCE.write_text(text)
    os.execv(sys.executable, [sys.executable, str(SOURCE)])


if __name__ == "__main__":
    main()
