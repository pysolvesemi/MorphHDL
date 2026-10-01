#!/usr/bin/env python3
"""Run the rollout generator with the published 59h repair as input.

The historical bootstrap still carries the recipe that originally constructed
the 59h ordering repair. The branch now contains that exact reviewed repair.
This wrapper removes only the dead construction template before parsing the
bootstrap, while retaining the 59h checker in the composition hash inventory.
All other guarded generator anchors and mutation controls remain unchanged.
"""
from pathlib import Path

BOOTSTRAP = Path(__file__).resolve().with_name("bootstrap.py")


def main() -> None:
    source = BOOTSTRAP.read_text()

    start_marker = "    # 59h is an older owner audit."
    insertion_marker = (
        '    text = replace_once(text, "\\ndef patch_promoter() -> None:\\n",'
    )
    end_marker = '                        "composed checker patch functions")\n'
    start = source.find(start_marker)
    insertion = source.find(insertion_marker, start)
    end_start = source.find(end_marker, insertion)
    if start < 0 or insertion < 0 or end_start < 0:
        raise RuntimeError("59h construction-template boundaries changed")
    end = end_start + len(end_marker)
    replacement = r'''    # The exact 59h repair is already committed. Keep its path in PATCHED,
    # but do not construct or apply the repair a second time.
    text = replace_once(text, "\ndef patch_promoter() -> None:\n",
                        wa_patch + "def patch_promoter() -> None:\n",
                        "composed checker patch functions")
'''
    source = source[:start] + replacement + source[end:]

    old = (
        '        "    patch_rollout()\\n    patch_wa07b()\\n'
        '    patch_59h()\\n    patch_promoter()\\n",\n'
    )
    new = (
        '        "    patch_rollout()\\n    patch_wa07b()\\n'
        '    patch_promoter()\\n",\n'
    )
    if source.count(old) != 1:
        raise RuntimeError("59h generator invocation anchor changed")
    source = source.replace(old, new, 1)

    scope = {
        "__name__": "__main__",
        "__file__": str(BOOTSTRAP),
        "__package__": None,
    }
    exec(compile(source, str(BOOTSTRAP), "exec"), scope)


if __name__ == "__main__":
    main()
