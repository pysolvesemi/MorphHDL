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

# Apply the source-level quoting correction before the target script is
# executed, then remove this transient helper so a successful publication
# cannot leave repair infrastructure behind.
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
