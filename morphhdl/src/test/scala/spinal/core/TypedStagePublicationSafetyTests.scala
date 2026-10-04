package spinal.core
import java.nio.file.Files
import morphhdl.MorphVerilog
import morphhdl.frontend.HdlInt
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.internals._

class TypedStagePublicationSafetyTests extends AnyFunSuite {
  for(mode <- Seq("bridge-source","competing-writer","range","constant-index","power-shift")) {
    test(s"typed prefix publication rejects changed exact provenance mode=$mode") {
      val dir=Files.createTempDirectory("typed-stage-provenance-")
      val config=SpinalConfig(targetDirectory=dir.toString,headerWithDate=false,
        flags=scala.collection.mutable.HashSet[Any](VerilogAggregateOptions(preserveConstantVecs=true,
          preserveConstantLoops=true,vecLayout=VerilogAggregateOptions.UnpackedArray)))
      config.phasesInserters += { phases =>
        val at=phases.indexWhere(_.isInstanceOf[PhaseVerilog]);require(at>=0)
        phases.insert(at,new PhaseMisc { override def impl(pc:PhaseContext):Unit={
          val component=pc.topLevel
          val selection=ParameterizedStructure.regionsOf(component).flatMap(_.blocks).flatMap(_.vecIndices)
            .find(_.coverageBridges.nonEmpty).get
          val bridge=selection.coverageBridges.head
          mode match {
            case "bridge-source" => bridge.assignment.source=bridge.value
            case "competing-writer" => component.dslBody.append(DataAssignmentStatement(bridge.value,bridge.alias))
            case "range" => selection.unitRange=None
            case "constant-index" => TypedVecStaticSelect.entries(component).head.assignment.source=UIntLiteral(0,8)
            case "power-shift" => TypedLoopPowerShift.entries(component).head.assignment.source=UIntLiteral(0,8)
          }
        }})
      }
      val error=intercept[Exception] {
        MorphVerilog(config)(new Component {
          @dontName private val width=HdlInt.param("WIDTH",8,1,64).asElabInt
          val gray=in Bits(width bits);val binary=out UInt(width bits)
          binary:=spinal.lib.fromGray(gray)
        })
      }
      val text=Iterator.iterate[Throwable](error)(_.getCause).takeWhile(_!=null).map(_.getMessage).mkString("\n")
      assert(text.contains("VEC-WRITER-MISMATCH") || text.contains("VEC-RANGE-MISMATCH") ||
        text.contains("STRUCTURAL-DOMAIN-MISMATCH") || text.contains("constant Vec selector lost") ||
        text.contains("finite power shift lost"),text)
    }
  }
}
