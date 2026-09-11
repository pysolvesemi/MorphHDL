#!/usr/bin/env python3
"""Run the rollout generator with the already-published 59h repair as input.

The parent bootstrap still describes how to construct the 59h repair for an
older checkout. The branch now contains that exact reviewed repair, so this
wrapper keeps the 59h checker in the composition hash inventory but suppresses
only the redundant second application. All other guarded generator anchors and
mutations remain unchanged.
"""
from pathlib import Path

BOOTSTRAP = Path(__file__).resolve().with_name("bootstrap.py")


def main() -> None:
    source = BOOTSTRAP.read_text()
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
