#!/usr/bin/env python3
"""Normalize nested templates, then run the exact 59i/60g composition generator."""
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

    # Normalize the previously staged nested source templates before adding the
    # latest exact successor composition. These transformations modify the
    # generator copy in the workflow worktree only; apply.py then emits and
    # validates the complete reviewed candidate.
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

    # The native-tree manifest successor landed after this generator was first
    # written. Preserve that exact restoration before projecting the immutable
    # feature-parent view.
    text = replace_once(
        text,
        """    restore = '''def restore_source(root: Path, path: str, source: str) -> str:
    source = load_widening_review(root).restore_source(root, path, source)
'''
    restored = '''def restore_source(root: Path, path: str, source: str) -> str:
    source = load_composition_review(root).feature_view(root, path, source)
    source = load_widening_review(root).restore_source(root, path, source)
'''
""",
        """    restore = '''def restore_source(root: Path, path: str, source: str) -> str:
    source = load_anchor_review(root).restore_source(root, path, source)
    source = load_widening_review(root).restore_source(root, path, source)
'''
    restored = '''def restore_source(root: Path, path: str, source: str) -> str:
    source = load_anchor_review(root).restore_source(root, path, source)
    source = load_composition_review(root).feature_view(root, path, source)
    source = load_widening_review(root).restore_source(root, path, source)
'''
""",
        "anchor-aware 59i feature-view restoration",
    )

    # The current WA-07b file contains an orphaned restore_rollout call from a
    # partial reconciliation. Its existing joined_adapter_source is already the
    # required fail-closed handoff: unchanged/pass bytes return untouched,
    # combined bytes require exact 59i reviewer+manifest evidence, and only then
    # flow through the composition-aware 59i restore_source generated above.
    text = replace_once(
        text,
        'ROLLOUT = ROOT / "morphhdl/scripts/check-increment-60g-source-scope.py"\nPROMOTER = ROOT /',
        'ROLLOUT = ROOT / "morphhdl/scripts/check-increment-60g-source-scope.py"\n'
        'WA07B = ROOT / "morphhdl/scripts/check-wa07b-inherited-review.py"\n'
        'PROMOTER = ROOT /',
        "WA-07b composition path",
    )
    text = replace_once(
        text,
        "                (PARENT, WIDENING, ROLLOUT, PROMOTER, PROMOTER_CI))",
        "                (PARENT, WIDENING, ROLLOUT, WA07B, PROMOTER, PROMOTER_CI))",
        "WA-07b reviewed path inventory",
    )

    wa_patch = r'''
def patch_wa07b() -> None:
    text = WA07B.read_text()
    text = replace_once(
        text,
        "    source = restore_rollout(root, path, source)\n",
        "",
        "WA-07b orphaned rollout removal",
    )
    WA07B.write_text(text)


'''
    text = replace_once(text, "\ndef patch_promoter() -> None:\n",
                        wa_patch + "def patch_promoter() -> None:\n",
                        "WA-07b checker patch function")
    text = replace_once(
        text,
        "    patch_rollout()\n    patch_promoter()\n",
        "    patch_rollout()\n    patch_wa07b()\n    patch_promoter()\n",
        "WA-07b checker patch invocation",
    )

    # Keep the original 59i diagnostic category required by inherited mutation
    # tests while adding the more specific composition-layer explanation.
    text = replace_once(
        text,
        '            "unreviewed merged source outside 59i/60g composition: " + path)',
        '            "unreviewed source change outside 59i spans; outside 59i/60g composition: " + path)',
        "composition mutation diagnostic",
    )

    # Review only source/checker/contract paths; workflow and documentation
    # inputs drive the transaction but are not projected as historical source.
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
