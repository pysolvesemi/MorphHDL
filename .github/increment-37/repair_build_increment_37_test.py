#!/usr/bin/env python3
from pathlib import Path
import subprocess

SCRIPT_PATH = ".github/increment-37/repair_build_increment_37_test.py"


def fail(message: str) -> None:
    raise SystemExit(message)


def previous_repair_source() -> str:
    for ref in ("HEAD^", "HEAD~2", "HEAD~3"):
        result = subprocess.run(
            ["git", "show", f"{ref}:{SCRIPT_PATH}"],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        if (
            result.returncode == 0
            and "PAYLOAD =" in result.stdout
            and "TARGET.write_bytes" in result.stdout
        ):
            return result.stdout
    fail("Unable to recover the reviewed Increment 37 regression generator")


# Reuse the reviewed focused regression generator from the parent controller
# commit, then install only the final, narrowly scoped corrections below.
reviewed = previous_repair_source()
namespace = {
    "__name__": "__main__",
    "__file__": SCRIPT_PATH,
}
exec(compile(reviewed, SCRIPT_PATH, "exec"), namespace)


# The controller's normal inline patch fixes the primary pointer names. Append a
# post-generation correction so every FIFO address pipeline and the non-power-
# of-two occupancy counter also retain DEPTH-derived widths.
apply_path = Path(".github/increment-37/apply_increment_37.py")
apply_text = apply_path.read_text()
geometry_marker = "# INCREMENT37_FINAL_GEOMETRY_REWRITE"
if geometry_marker not in apply_text:
    apply_text += r"""

# INCREMENT37_FINAL_GEOMETRY_REWRITE
backend_file = ROOT / "core/src/main/scala/spinal/core/internals/ParameterizedVerilogMemories.scala"
backend_source = backend_file.read_text()
geometry_start_marker = '      val compactName = lower.replace("_", "")\n'
geometry_end_marker = '      if (pointerContext) {\n        line = replaceSized'
geometry_start = backend_source.find(geometry_start_marker)
geometry_end = backend_source.find(geometry_end_marker, geometry_start)
if geometry_start < 0 or geometry_end < 0:
    fail("Increment 37 generated FIFO-geometry anchors were not found")
geometry_replacement = (
    r'''      val compactName = lower.replace("_", "")
      val pointer'''
    + r'''Context =
        compactName.contains("pushptr") || compactName.contains("popptr") ||
          compactName.contains("ptrpush") || compactName.contains("ptrpop") ||
          compactName.contains("poponio") ||
          compactName.contains("addressgen") ||
          compactName.contains("popreg") ||
          compactName.contains("readportcmdpayload") ||
          compactName.contains("toflowfirepayload") ||
          compactName.contains("readarbitrationpayload") ||
          (lower.contains("address") &&
            (lower.contains("ram") || lower.contains("memory")))
      val occupancy'''
    + r'''Context =
        lower.contains("occupancy") || lower.contains("availability") ||
          lower.contains("push_ready")
      val occupancyCounterContext = compactName.contains("notpow2counter")
      val memoryArray = lower.contains("[0:")
      val packed = packedRange.findFirstIn(original)
      val oneBitPacked = packed.exists(_.replace(" ", "") == "[0:0]")

      var line = original
      if (isDeclaration(line) && !memoryArray && packed.nonEmpty) {
        if (pointerContext) {
          line = packedRange.replaceFirstIn(
            line,
            java.util.regex.Matcher.quoteReplacement(s"[$pointerWidth-1:0]")
          )
        } else if (
          occupancyContext || (occupancyCounterContext && !oneBitPacked)
        ) {
          line = packedRange.replaceFirstIn(
            line,
            java.util.regex.Matcher.quoteReplacement(s"[$occupancyWidth-1:0]")
          )
        }
      }
'''
)
backend_source = (
    backend_source[:geometry_start]
    + geometry_replacement
    + backend_source[geometry_end:]
)
backend_file.write_text(backend_source)

doc_file = ROOT / "docs/morphhdl/increment-37-parameterized-streamfifo-depth.md"
doc_source = doc_file.read_text()
doc_old = '''`StreamFifo`, including fill-to-full, backpressure stability, FIFO ordering,
drain-to-empty and flush recovery. Every override is also synthesized by Yosys.
'''
doc_new = '''`StreamFifo`, including its public capacity of exactly `DEPTH`, fill-to-full,
backpressure stability, FIFO ordering, drain-to-empty and flush recovery. Every
override is also synthesized by Yosys.
'''
if doc_old in doc_source:
    doc_source = doc_source.replace(doc_old, doc_new, 1)
elif doc_new not in doc_source:
    fail("Increment 37 documentation capacity anchor was not found")
doc_file.write_text(doc_source)
"""
    apply_path.write_text(apply_text)


# Patch the focused source after its reviewed payload is decoded. The public
# StreamFifo contract stores exactly DEPTH transactions; the synchronous read
# stage is internal and must not be counted as an extra advertised slot.
build_path = Path(".github/increment-37/build_increment_37_test.py")
build_text = build_path.read_text()
test_marker = "# INCREMENT37_FINAL_TEST_FIX"
if test_marker not in build_text:
    build_text += r"""

# INCREMENT37_FINAL_TEST_FIX
source = TARGET.read_text()
capacity_new = "localparam integer CAPACITY = DEPTH_VALUE;"
capacity_variants = (
    "localparam integer CAPACITY = (DEPTH_VALUE == 1) ? 1 : DEPTH_VALUE + 1;",
    "localparam integer CAPACITY = DEPTH_VALUE + 1;",
)
for capacity_old in capacity_variants:
    if capacity_old in source:
        source = source.replace(capacity_old, capacity_new, 1)
        break
else:
    if capacity_new not in source:
        raise SystemExit("Increment 37 FIFO capacity anchor was not found")

static_old = '        assert(concrete.contains(s"[0:${depth - 1}]"))'
static_new = '        if (depth > 1) assert(concrete.contains(s"[0:${depth - 1}]"))'
if static_old in source:
    source = source.replace(static_old, static_new, 1)
elif static_new not in source:
    raise SystemExit("Increment 37 depth-one concrete-memory assertion anchor was not found")

structural_anchor = '      assert(parameterized.contains("DEPTH - 1"))\n'
structural_assertions = structural_anchor + '''      val fifoGeometryLines = parameterized.split("\\n").toVector
      assert(
        fifoGeometryLines.exists { line =>
          line.contains("logic_ptr_notPow2_counter") &&
          (line.contains("clog2((DEPTH + 1), 1)") ||
            line.contains("clog2(DEPTH + 1, 1)"))
        }
      )
      assert(
        fifoGeometryLines.exists { line =>
          (line.contains("logic_pop_addressGen_payload") ||
            line.contains("logic_pop_sync_popReg")) &&
          line.contains("clog2(DEPTH, 1)")
        }
      )
'''
if "val fifoGeometryLines" not in source:
    if structural_anchor not in source:
        raise SystemExit("Increment 37 structural assertion anchor was not found")
    source = source.replace(structural_anchor, structural_assertions, 1)

TARGET.write_text(source)
"""
    build_path.write_text(build_text)

print(
    "Installed final Increment 37 symbolic geometry and exact-capacity repairs"
)
