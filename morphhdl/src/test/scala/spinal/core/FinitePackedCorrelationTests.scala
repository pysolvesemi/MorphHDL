package spinal.core

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import morphhdl.{MorphAggregateOptions, MorphVerilog, MorphVerilogException}

class FinitePackedCorrelationTests extends AnyFunSuite {
  for(symbolic <- Seq(false, true); mutation <- Seq("offset", "width", "proof", "token", "record"))
  test(s"finite packed correlation ${if(symbolic) "symbolic " else ""}rejects replaced $mutation identity") {
    val error = intercept[MorphVerilogException] {
      MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=Files.createTempDirectory("finite-packed-negative-").toString),
        preserveConstantVecs=true, preserveConstantLoops=true)) { new Component {
        @dontName val count = ElabFormalParameter(ElabInt.literal(3), "COUNT", 1, 8)
        @dontName val width = if(symbolic) ElabFormalParameter(ElabInt.literal(8), "WIDTH", 1, 64)
          else ElabInt.literal(8)
        val source = in Bits((count*width) bits)
        val result = out Bits((count*width) bits)
        ElabFiniteRange.foreach(count, "copy") { i =>
          if(symbolic) i.packed(result,width,width bits) := i.packed(source,width,width bits)
          else i.packed(result,8,8 bits) := i.packed(source,8,8 bits)
        }
        val loop = ParameterizedStructure.regionsOf(this).collectFirst {
          case value: ParameterizedStructure.StructuralFor => value
        }.get
        val at = loop.body.slices.indexWhere(_.source eq source)
        val original = loop.body.slices(at)
        val changed = mutation match {
          case "offset" => original.copy(offset=original.offset.copy())
          case "width" => original.copy(width=original.width.copy())
          case "proof" => original.copy(finitePackedAccess=None)
          case "token" => original.copy(finiteIndexToken=None)
          case "record" => original
        }
        loop.body.slices = if(mutation=="record") loop.body.slices.patch(at, Vector.empty, 1)
          else loop.body.slices.updated(at, changed)
      }}
    }
    assert(error.getMessage.contains("SPINAL-ELAB-FINITE-PACKED-EVIDENCE-MISMATCH"), error.getMessage)
  }
}
