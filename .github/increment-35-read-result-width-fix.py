from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file_path = Path(path)
    text = file_path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(
            f"expected exactly one replacement in {path}, found {count}: {old!r}"
        )
    file_path.write_text(text.replace(old, new, 1), encoding="utf-8")


source = "core/src/main/scala/spinal/core/internals/ParameterizedVerilogMemories.scala"
replace_once(
    source,
    "    lines = rewriteMemoryDeclaration(lines, plan, helperName)\n",
    "    lines = rewriteMemoryDeclaration(lines, plan, helperName)\n"
    "    lines = rewriteReadTargetDeclaration(lines, plan, helperName)\n",
)

read_target_rewrite = r'''  private def rewriteReadTargetDeclaration(
    lines: Vector[String],
    plan: MemoryPlan,
    helperName: String
  ): Vector[String] = {
    if (plan.metadata.elementWidth.parameters.isEmpty) return lines

    val regDeclaration = "^\\s*(?:output\\s+)?reg\\b".r
    val candidates = lines.zipWithIndex.collect {
      case (line, index)
          if regDeclaration.findFirstIn(line).nonEmpty &&
            containsIdentifier(line, plan.readTarget) =>
        index
    }
    if (candidates.size != 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-READ-TARGET-DECLARATION-NOT-FOUND",
        s"normal Verilog emission contains ${candidates.size} register declarations matching synchronous read result '${plan.readTarget}'",
        plan.sourceLocation
      )
    }

    val index = candidates.head
    val line = lines(index)
    val nameMatch = identifierPattern(plan.readTarget).findFirstMatchIn(line).get
    var prefix = line.substring(0, nameMatch.start)
    val suffix = line.substring(nameMatch.end)
    val packed = "\\[[^\\]]+\\]\\s*$".r
    val range = s"[${render(plan.metadata.elementWidth, helperName)}-1:0]"
    packed.findFirstMatchIn(prefix) match {
      case Some(value) =>
        prefix = prefix.substring(0, value.start) + range + " "
      case None =>
        prefix = prefix + range + " "
    }
    lines.updated(index, prefix + plan.readTarget + suffix)
  }

'''
replace_once(
    source,
    "  private def rewriteMemoryDeclaration(\n",
    read_target_rewrite + "  private def rewriteMemoryDeclaration(\n",
)

tests = "morphhdl/src/test/scala/morphhdl/NativeSymbolicMemoryTests.scala"
replace_once(
    tests,
    '      assert(verilog.contains("reg [WIDTH-1:0] memory [0:DEPTH-1];"))\n',
    '      assert(verilog.contains("reg [WIDTH-1:0] memory [0:DEPTH-1];"))\n'
    '      assert(\n'
    '        """(?m)^\\s*reg\\s+\\[WIDTH-1:0\\]\\s+memory_spinal_port0\\s*;\\s*$""".r\n'
    '          .findFirstIn(verilog)\n'
    '          .nonEmpty\n'
    '      )\n',
)

scalar_test = r'''  test("symbolic read-result storage is widened from a scalar concrete witness") {
    withTemporaryDirectory { directory =>
      val width = HdlInt.param("WIDTH", default = 1, min = 1, max = 8)
      val depth = HdlInt.param("DEPTH", default = 5, min = 1, max = 8)
      val verilog = emitMorph(
        directory,
        "native_single_port_scalar_witness.v",
        new NativeSinglePortMemory(width, depth)
      )

      assert(verilog.contains("reg [WIDTH-1:0] memory [0:DEPTH-1];"))
      assert(
        """(?m)^\s*reg\s+\[WIDTH-1:0\]\s+memory_spinal_port0\s*;\s*$""".r
          .findFirstIn(verilog)
          .nonEmpty
      )
      assert(
        """(?m)^\s*reg\s+memory_spinal_port0\s*;\s*$""".r
          .findFirstIn(verilog)
          .isEmpty
      )
    }
  }

'''
replace_once(
    tests,
    '  test("independent read and write addresses emit the existing simple-dual-port policy") {\n',
    scalar_test
    + '  test("independent read and write addresses emit the existing simple-dual-port policy") {\n',
)

documentation = "docs/morphhdl/increment-35-native-symbolic-memories.md"
replace_once(
    documentation,
    "MorphHDL retains the normal emitter's names and wiring, rewrites the memory\n"
    "array to symbolic geometry, and replaces only its native clocked memory block\n"
    "with the reviewed guarded process. The address ABI uses the collision-safe\n",
    "MorphHDL retains the normal emitter's names and wiring, rewrites both the memory\n"
    "array and its synchronous read-result register to the retained symbolic element\n"
    "width, rewrites the array depth to the symbolic word count, and replaces only its\n"
    "native clocked memory block with the reviewed guarded process. The address ABI\n"
    "uses the collision-safe\n",
)
replace_once(
    documentation,
    "memory-policy validation and mask rejection on Scala 2.12 and 2.13.\n",
    "memory-policy validation, scalar-witness read-result widening and mask rejection\n"
    "on Scala 2.12 and 2.13.\n",
)
