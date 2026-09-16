#!/usr/bin/env python3
"""Execute the exact compact patch after the history-preserving restoration.

The full reviewed patch is retained verbatim in immutable ancestor TEMPLATE.
The restoration has exactly the already-qualified source tree. Only the
one-shot input parent is rebound; compiler edits, inventories and checks are
not altered, inferred or downloaded from a moving branch.
"""
import hashlib
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
QUALIFIED = "0a13d7fb01413d6d5f9f2bb8885f8abf109cb264"
RESTORED = "96fa69762c682204c0476ac272d350d9ca1a5190"
TEMPLATE = "6e632df8900ee7760a384c6a572825acfd012ac6"
PATH = "repro/cdc-independent-parameters/prepare_compact_timeout.py"
BLOB = "d8de2bf69e18ca295c877834c71f6abfa8882ba5"


def git(*args):
    return subprocess.check_output(["git", "--literal-pathspecs", *args], cwd=ROOT, timeout=120)


def main():
    if git("rev-parse", RESTORED + "^{tree}") != git("rev-parse", QUALIFIED + "^{tree}"):
        raise RuntimeError("restoration differs from the source/consumer-qualified tree")
    git("merge-base", "--is-ancestor", TEMPLATE, RESTORED)
    raw = git("show", TEMPLATE + ":" + PATH)
    actual = hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest()
    if actual != BLOB:
        raise RuntimeError("immutable reviewed compact patch differs")
    namespace = {"__name__": "pr189_pinned_compact_template", "__file__": str(ROOT / PATH)}
    exec(compile(raw, TEMPLATE + ":" + PATH, "exec"), namespace)
    if namespace["BASE"] != QUALIFIED:
        raise RuntimeError("template qualification anchor differs")
    namespace["BASE"] = RESTORED
    print("PR189_COMPACT_TEMPLATE", TEMPLATE, "RESTORED_PARENT", RESTORED, "QUALIFIED_TREE", QUALIFIED, flush=True)
    namespace["main"]()


if __name__ == "__main__":
    main()
