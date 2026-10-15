#!/usr/bin/env python3
"""Apply the exact 59h joined-source ordering repair."""
from pathlib import Path

CHECKER = Path("morphhdl/scripts/check-increment-59h-source-review.py")

OLD_RESTORE = '''def restore_source(root: Path, path: str, source: str) -> str:
    source = restore_rollout(root, path, source)
    """Leave unrelated historical hooks to their own exact source contracts."""
    register = register_source_review(root)
    if register is not None:
        source = register.restore_source(root, path, source)
    entries = load_contract(root)
'''

NEW_RESTORE = '''def restore_source(root: Path, path: str, source: str) -> str:
    """Leave unrelated historical hooks to their own exact source contracts."""
    register = register_source_review(root)
    joined = None if register is None else getattr(register, "join_source_review", lambda _: None)(root)
    if joined is not None:
        source = register.restore_source(root, path, source)
    else:
        source = restore_rollout(root, path, source)
        if register is not None:
            source = register.restore_source(root, path, source)
    entries = load_contract(root)
'''

OLD_SPANS = '''        current = restore_rollout(root, path, source.read_text()).encode()
        if register is not None:
            current = register.restore_source(root, path, current.decode()).encode()
        restore_reviewed(entry, baseline, current)
'''

NEW_SPANS = '''        current = source.read_text()
        joined = None if register is None else getattr(register, "join_source_review", lambda _: None)(root)
        if joined is not None:
            current = register.restore_source(root, path, current)
        else:
            current = restore_rollout(root, path, current)
            if register is not None:
                current = register.restore_source(root, path, current)
        restore_reviewed(entry, baseline, current.encode())
'''


def main() -> None:
    text = CHECKER.read_text()
    if NEW_RESTORE in text and NEW_SPANS in text:
        if OLD_RESTORE in text or OLD_SPANS in text:
            raise RuntimeError("59h checker contains a partial ordering repair")
        print("59h joined-source ordering already repaired")
        return
    if text.count(OLD_RESTORE) != 1 or text.count(OLD_SPANS) != 1:
        raise RuntimeError("59h joined-source ordering anchors changed")
    CHECKER.write_text(
        text.replace(OLD_RESTORE, NEW_RESTORE, 1)
            .replace(OLD_SPANS, NEW_SPANS, 1)
    )
    print("59h joined-source ordering repaired")


if __name__ == "__main__":
    main()
