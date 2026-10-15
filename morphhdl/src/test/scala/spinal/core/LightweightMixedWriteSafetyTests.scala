package spinal.core

import java.nio.file.Files
import morphhdl.{MorphAggregateOptions, MorphVerilog}
import org.scalatest.funsuite.AnyFunSuite

class LightweightMixedWriteSafetyTests extends AnyFunSuite {
  for (mutation <- Seq("missing-default", "partial", "competing", "feedback", "read-only",
      "changed-default", "changed-source", "changed-address"))
  test(s"scoped decoded row write rejects $mutation") {
    val error = intercept[Exception] {
      MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory = Files.createTempDirectory("mixed-write-negative-").toString),
        preserveConstantVecs = true, preserveConstantLoops = true)) {
        new Component {
          val rows = RtlParam.int("ROWS", 3, 1, 5)
          val columns = RtlParam.int("COLS", 3, 1, 4)
          val width = RtlParam.int("WIDTH", 8, 1, 64)
          val source = in(Vec(Vec(SInt(width bits), columns), rows))
          val replacement = in(Vec(SInt(width bits), columns))
          val row = in UInt(3 bits)
          val otherRow = in UInt(3 bits)
          val other = in SInt(width bits)
          val enable = in Bool()
          val result = out(Vec(Vec(SInt(width bits), columns), rows))
          if (mutation != "missing-default") result := source
          if (mutation == "competing") result(0)(0) := replacement(0)
          val count = if (mutation == "partial") columns - 1 else columns
          ElabFiniteRange.foreach(count, "update") { i =>
            if (mutation == "read-only") i(result.read(row)) := i(replacement)
            else if (mutation == "feedback") i(result(row)) := i(result(row))
            else when(enable) { i(result(row)) := i(replacement) }
          }
          val receipt = ParameterizedStructure.regionsOf(this).flatMap(_.blocks)
            .flatMap(_.vecIndices).flatMap(_.dynamicRowNormalization).head
          mutation match {
            case "changed-default" => receipt.default.assignments.head.source = other
            case "changed-source" => receipt.writes.head.source = other
            case "changed-address" =>
              val addressEdge = receipt.generated.find(a => a.source eq row).get
              addressEdge.source = otherRow
            case _ =>
          }
        }
      }
    }
    val expected = if (mutation == "partial") "SPINAL-ELAB-FINITE-RANGE-VEC-DEPTH-MISMATCH"
      else "SPINAL-ELAB-SCOPED-DYNAMIC-WRITE"
    assert(error.toString.contains(expected), error.toString)
  }
}
