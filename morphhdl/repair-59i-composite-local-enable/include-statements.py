#!/usr/bin/env python3
"""Include exact native callback statements in composite freshness inventory.

The callback recorder already captures these statements and the closed-graph
validator admits only the reviewed register-enable When shape. This patch only
prevents that subsequently validated statement from being misclassified as an
unrecorded side effect.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeReplay.scala"


def require(condition: bool, detail: str) -> None:
    if not condition:
        raise RuntimeError(detail)


def main() -> None:
    text = SOURCE.read_text()
    before = "      val callbackSet = inventory(callback.declarations ++ callback.assignments)\n"
    after = "      val callbackSet = inventory(callback.declarations ++ callback.assignments ++ callback.statements)\n"
    require(text.count(before) == 1,
            "composite callback statement-inventory anchor changed: " + str(text.count(before)))
    SOURCE.write_text(text.replace(before, after, 1))
    print("59i composite callback statement inventory includes validated native statements")


if __name__ == "__main__":
    main()
