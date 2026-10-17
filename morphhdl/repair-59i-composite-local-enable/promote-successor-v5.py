#!/usr/bin/env python3
"""Corrected entry point for the exact 59i local-enable successor publisher.

It reuses the reviewed v4 implementation, but binds the literal Scala source
marker (the runtime diagnostic prefix is added by fail()) and emits a distinct
v5 successor contract so a failed/stale v4 preparation cannot be mistaken for
qualification.
"""
from __future__ import annotations

import importlib.util
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = Path(__file__).resolve().parent / "promote-successor-v4.py"
spec = importlib.util.spec_from_file_location("increment_59i_local_enable_v4", SOURCE)
if spec is None or spec.loader is None:
    raise RuntimeError("cannot load local-enable v4 publisher")
review = importlib.util.module_from_spec(spec)
spec.loader.exec_module(review)

review.MARKERS = {
    review.PRODUCTION[0]: (
        "controlValues: Vector[BaseType]",
        "CONTROL-BINDING",
    ),
    review.PRODUCTION[1]: (
        "hasLocalEnables",
        "BRIDGE-CONTROL-REGISTER",
        "callback.assignments ++ callback.statements",
    ),
}
review.CONTRACT = ROOT / "morphhdl/contracts/increment-59i-local-enable-successor-v5.json"
review.CHECKER = ROOT / "morphhdl/scripts/check-increment-59i-local-enable-successor-v5.py"
review.DOC = ROOT / "docs/morphhdl/increment-59i-composite-local-enable-successor-v5.md"
review.CHECKER_TEMPLATE = (review.CHECKER_TEMPLATE
    .replace("successor-v4.json", "successor-v5.json")
    .replace("local-enable v4", "local-enable v5")
    .replace("local-enable-v4", "local-enable-v5")
    .replace("successor review PASS", "successor review PASS"))


def main() -> None:
    review.require(review.output("git", "status", "--porcelain") == "",
                   "publisher requires a clean checkout")
    review.require(not review.CONTRACT.exists() and not review.CHECKER.exists() and
                   not review.DOC.exists(),
                   "v5 successor review already exists; run its checker instead")
    base = review.prepare_implementation()
    review.write_review(base)
    print("59i local-enable v5 successor prepared from " + base, flush=True)


if __name__ == "__main__":
    main()
