#!/usr/bin/env python3
from pathlib import Path

path = Path(
    "morphhdl/scripts/"
    "apply-increment-53e-generic-target-sizing-and-slices.py"
)
value = path.read_text()
old = '''pipeline_old = \'\'\'    val rewrittenInitializers = rewriteSymbolicZeroAssignments(
      rewrittenDeclarations,
      analysis.symbolicZeroInitializers
    )
    val rewrittenCounterBoundaries = rewriteSymbolicCounterBoundaryComparisons(
      rewrittenInitializers,
      analysis.symbolicCounterBoundaryWidths
    )
\'\'\'
'''
new = '''pipeline_old = \'\'\'    val rewrittenAutoResizes = rewriteMaterializedAutoResizeAssignments(
      component,
      rewrittenDeclarations
    )
    val rewrittenInitializers = rewriteSymbolicZeroAssignments(
      rewrittenAutoResizes,
      analysis.symbolicZeroInitializers
    )
    val rewrittenCounterBoundaries = rewriteSymbolicCounterBoundaryComparisons(
      rewrittenInitializers,
      analysis.symbolicCounterBoundaryWidths
    )
\'\'\'
'''
count = value.count(old)
if count != 1:
    raise SystemExit(
        f"generic target-sizing patch-order marker count={count}"
    )
path.write_text(value.replace(old, new, 1))

# Apply source-level quoting corrections before the target script executes.
# The target script itself contains a Python triple-quoted Scala source body;
# newline escapes therefore need two source backslashes so execution writes one
# legal Scala escape instead of a physical newline inside a string literal.
regex_fixer = Path(
    "morphhdl/scripts/apply-increment-53e-generic-regex-literals.py"
)
namespace = {"__name__": "__main__"}
exec(
    compile(regex_fixer.read_text(), str(regex_fixer), "exec"),
    namespace,
    namespace,
)
regex_fixer.unlink()

value = path.read_text()
newline_replacements = (
    (
        r'verilog.split("\n", -1)',
        r'verilog.split("\\n", -1)',
        2,
        "generated Scala split newline",
    ),
    (
        r'lines.mkString("\n")',
        r'lines.mkString("\\n")',
        2,
        "generated Scala join newline",
    ),
)
for old_text, new_text, expected, label in newline_replacements:
    count = value.count(old_text)
    if count != expected:
        raise SystemExit(f"{label}: expected {expected} matches, found {count}")
    value = value.replace(old_text, new_text)
path.write_text(value)
