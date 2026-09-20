package repro

import spinal.core._
import morphhdl.MorphVerilog
import morphhdl.frontend.HdlInt

object IndependentParameterRepro extends App {
  require(args.length == 1, "Expected output directory")

  class RecordWidth(dataBits: ElabInt, generationBits: ElabInt) extends Component {
    setDefinitionName("RecordWidth")
    val record = in Bits((dataBits + generationBits) bits)
    val observed = out Bool()
    observed := record.orR
  }

  val config = SpinalConfig(
    targetDirectory = args(0),
    oneFilePerComponent = false,
    headerWithDate = false,
    headerWithRepoHash = true
  )
  config.netlistFileName = "RecordWidth.v"

  val report = MorphVerilog(config) {
    new RecordWidth(
      HdlInt.param("DATA_BITS", default = 32, min = 1, max = 2048).asElabInt,
      HdlInt.param("GENERATION_BITS", default = 32, min = 2, max = 64).asElabInt
    )
  }
  println(report.generatedSourcesPaths.mkString("\n"))
}
