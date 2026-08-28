#!/usr/bin/env python3
from pathlib import Path

TODO = Path("docs/morphhdl/parameterized-verilog-todo.md")
TESTS = Path("morphhdl/src/test/scala/morphhdl/SpinalEnumLocalParameterTests.scala")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"{label} anchor is missing")
    return text.replace(old, new, 1)


todo = TODO.read_text(encoding="utf-8")
todo = replace_once(
    todo,
    """- Increment 53c depends only on the merged Increment 53b. Increment 54 requires
  both Increment 53a and Increment 53c.
- Increments 54 through 58 then form a strict sequential closure chain after
  Increments 53a and 53c.
""",
    """- Increment 53b.1 is a corrective enum-naming closure and depends only on the
  merged Increment 53b. Increment 54 requires both Increment 53a and Increment
  53b.1.
- Increments 54 through 58 then form a strict sequential closure chain after
  Increments 53a and 53b.1.
""",
    "Increment 53b.1 dependency graph",
)
todo = todo.replace(
    "Increment 53c — SCREAMING_SNAKE_CASE SpinalEnum localparam names",
    "Increment 53b.1 — SCREAMING_SNAKE_CASE SpinalEnum localparam names",
)
todo = todo.replace(
    "  **Dependencies:** Increments 53a, 53b and 53c implemented and merged.\n",
    "  **Dependencies:** Increments 53a, 53b and 53b.1 implemented and merged.\n",
)
TODO.write_text(todo, encoding="utf-8")

tests = TESTS.read_text(encoding="utf-8")
tests = tests.replace("Inc53c", "Inc53b1")
tests = tests.replace("INC53C", "INC53B1")
TESTS.write_text(tests, encoding="utf-8")

print("Renamed enum naming follow-up from Increment 53c to Increment 53b.1")
