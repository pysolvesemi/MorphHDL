#!/usr/bin/env python3
"""Bind exact native callback statements through capture and publication.

The native callback recorder already captures register-enable When statements.
Composite certification must count those statements as callback-owned effects,
and the publication backend must recursively inventory the exact assignments
inside each retained When tree before creating its closed-graph observation.
No missing driver is inferred by target or name; only statements already present
inside the exact captured structural block enter the observation.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
COMPOSITE = ROOT / "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeReplay.scala"
BACKEND = ROOT / "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionBackend.scala"


def require(condition: bool, detail: str) -> None:
    if not condition:
        raise RuntimeError(detail)


def replace_once(text: str, before: str, after: str, detail: str) -> str:
    require(text.count(before) == 1, detail + ": " + str(text.count(before)))
    return text.replace(before, after, 1)


def main() -> None:
    composite = COMPOSITE.read_text()
    composite = replace_once(
        composite,
        "      val callbackSet = inventory(callback.declarations ++ callback.assignments)\n",
        "      val callbackSet = inventory(callback.declarations ++ callback.assignments ++ callback.statements)\n",
        "composite callback statement-inventory anchor changed",
    )
    COMPOSITE.write_text(composite)

    backend = BACKEND.read_text()
    before = '''      val assignments = block.statements.collect { case a: AssignmentStatement => a }
      val observation = TypedBalancedReductionClosedGraph.observe(UnvalidatedBalancedCallback(
        0, Vector(left) ++ right.toVector ++ schema.hardwareInputs, result, block.declarations, assignments))
'''
    after = '''      val callbackStatements = ArrayBuffer.empty[Statement]
      block.statements.foreach {
        case statement: LeafStatement => callbackStatements += statement
        case statement: TreeStatement =>
          callbackStatements += statement
          statement.walkStatements(callbackStatements += _)
      }
      val assignments = callbackStatements.collect { case a: AssignmentStatement => a }.toVector
      val observation = TypedBalancedReductionClosedGraph.observe(UnvalidatedBalancedCallback(
        0, Vector(left) ++ right.toVector ++ schema.hardwareInputs, result, block.declarations,
        assignments, callbackStatements.toVector))
'''
    backend = replace_once(
        backend, before, after,
        "composite publication statement-observation anchor changed",
    )
    BACKEND.write_text(backend)
    print("59i composite callback and publication observations bind exact native statement trees")


if __name__ == "__main__":
    main()
