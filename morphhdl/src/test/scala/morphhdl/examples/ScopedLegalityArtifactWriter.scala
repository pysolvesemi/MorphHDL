package morphhdl.examples

import java.nio.file.{Files, Paths}
import morphhdl.{MorphVerilog, MorphWireAssignmentPasses}
import morphhdl.frontend.HdlInt
import spinal.core._

class OptionalPipeline(width: ElabInt, usePipeline: ElabInt) extends Component {
  val dataIn = in Bits(width bits)
  val dataOut = out Bits(width bits)
  if (usePipeline == 1) {
    require(width >= 8, "Pipeline mode requires WIDTH >= 8")
    dataOut := RegNext(dataIn)
  } else {
    dataOut := dataIn
  }
}

/** Runnable roadmap fixture, with normal typed compiler plugins. */
object ScopedLegalityArtifactWriter {
  def main(args: Array[String]): Unit = {
    require(args.length == 1, "provide an artifact directory")
    val directory = Paths.get(args(0)).toAbsolutePath.normalize()
    Files.createDirectories(directory)
    MorphVerilog(MorphWireAssignmentPasses(SpinalConfig(
      targetDirectory = directory.toString, oneFilePerComponent = true,
      headerWithDate = false), enabled = false)) {
      new OptionalPipeline(HdlInt.param("WIDTH", 16, 1, 64).asElabInt,
        HdlInt.param("USE_PIPELINE", 0, 0, 1).asElabInt)
    }
  }
}
