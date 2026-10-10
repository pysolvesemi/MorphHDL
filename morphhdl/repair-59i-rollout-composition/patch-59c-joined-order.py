#!/usr/bin/env python3
"""Order the immutable 59c review after the exact joined successor view.

Standalone 59c/59h/60g behavior remains unchanged. In the combined 59i profile,
the 59h->59g chain owns the fail-closed 59i/60g projection, so 59c must pass the
current bytes to that chain before applying its own historical spans.
"""
from pathlib import Path

CHECKER = Path("morphhdl/scripts/check-increment-59c-source-review.py")

OLD_RESTORE = '''def restore_source(root: Path, path: str, source: str) -> str:
    source = restore_rollout(root, path, source)
    """Leave unrelated historical hooks to their own exact source contracts."""
    nested = nested_source_review(root)
    if nested is not None:
        source = nested.restore_source(root, path, source)
    entries = load_contract(root)
'''

NEW_RESTORE = '''def restore_source(root: Path, path: str, source: str) -> str:
    """Leave unrelated historical hooks to their own exact source contracts."""
    nested = nested_source_review(root)
    register = None if nested is None else getattr(nested, "register_source_review", lambda _: None)(root)
    joined = None if register is None else getattr(register, "join_source_review", lambda _: None)(root)
    if joined is not None:
        source = nested.restore_source(root, path, source)
    else:
        source = restore_rollout(root, path, source)
        if nested is not None:
            source = nested.restore_source(root, path, source)
    entries = load_contract(root)
'''

OLD_SPANS = '''        current = restore_rollout(root, path, source.read_text()).encode()
        if nested is not None:
            current = nested.restore_source(root, path, current.decode()).encode()
        restore_reviewed(entry, baseline, current)
'''

NEW_SPANS = '''        current = source.read_text()
        register = None if nested is None else getattr(nested, "register_source_review", lambda _: None)(root)
        joined = None if register is None else getattr(register, "join_source_review", lambda _: None)(root)
        if joined is not None:
            current = nested.restore_source(root, path, current)
        else:
            current = restore_rollout(root, path, current)
            if nested is not None:
                current = nested.restore_source(root, path, current)
        restore_reviewed(entry, baseline, current.encode())
'''


def main() -> None:
    text = CHECKER.read_text()
    if (NEW_RESTORE in text and NEW_SPANS in text and
            OLD_RESTORE not in text and OLD_SPANS not in text):
        print("59c joined-source ordering already composed")
        return
    if text.count(OLD_RESTORE) != 1:
        raise RuntimeError("59c joined restore anchor changed")
    if text.count(OLD_SPANS) != 1:
        raise RuntimeError("59c joined span anchor changed")
    updated = text.replace(OLD_RESTORE, NEW_RESTORE, 1)
    updated = updated.replace(OLD_SPANS, NEW_SPANS, 1)
    compile(updated, str(CHECKER), "exec")
    CHECKER.write_text(updated)
    print("59c joined-source ordering composed")


if __name__ == "__main__":
    main()
