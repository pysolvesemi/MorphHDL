package morphhdl.examples

import java.nio.file.{Files, Paths}
import morphhdl.{MorphVerilog, MorphWireAssignmentPasses}
import morphhdl.frontend.HdlInt
import spinal.core._

class RecordLink(dataBits: ElabInt, generationBits: ElabInt) extends Component {
  val totalBits: ElabInt = dataBits + generationBits
  val dataIn = in Bits(totalBits bits)
  val dataOut = out Bits(totalBits bits)
  dataOut := dataIn
}

/** Runnable unchanged roadmap example; no optional wire pipeline is needed. */
object DerivedLocalParameterArtifactWriter {
  def main(args: Array[String]): Unit = {
    require(args.length == 1, "provide an artifact directory")
    val directory = Paths.get(args(0)).toAbsolutePath.normalize()
    Files.createDirectories(directory)
    MorphVerilog(MorphWireAssignmentPasses(SpinalConfig(
      targetDirectory = directory.toString, oneFilePerComponent = true,
      headerWithDate = false), enabled = false)) {
      new RecordLink(HdlInt.param("DATA_BITS", 32, 1, 2048).asElabInt,
        HdlInt.param("GENERATION_BITS", 8, 2, 64).asElabInt)
    }
  }
}
