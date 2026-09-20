package repro

import spinal.core._
import morphhdl.MorphVerilog
import morphhdl.frontend.HdlInt

/** Three independent roots, with the exact application-style width expression.
  * This fixture is regression input, not generated RTL or a compiler repair.
  */
object IndependentRecordRepro extends App {
  require(args.length == 1, "Expected output directory")

  class IndependentRecord(dataBits: ElabInt, generationBits: ElabInt, lanes: ElabInt)
      extends Component {
    setDefinitionName("IndependentRecord")
    val width = dataBits + 7 + generationBits + lanes + 16 + 1
    assert(width.minimum == BigInt(28), "Expected minimum record width 28")
    assert(width.maximum == BigInt(2152), "Expected maximum record width 2152")
    val record = in Bits(width bits)
    val observed = out Bool()
    observed := record.orR
  }

  val config = SpinalConfig(
    targetDirectory = args(0),
    oneFilePerComponent = false,
    headerWithDate = false,
    headerWithRepoHash = true
  )
  config.netlistFileName = "IndependentRecord.v"
  val report = MorphVerilog(config) {
    new IndependentRecord(
      HdlInt.param("DATA_BITS", default = 32, min = 1, max = 2048).asElabInt,
      HdlInt.param("GENERATION_BITS", default = 32, min = 2, max = 64).asElabInt,
      HdlInt.param("LANES", default = 4, min = 1, max = 16).asElabInt
    )
  }
  println(report.generatedSourcesPaths.mkString("\n"))
}
