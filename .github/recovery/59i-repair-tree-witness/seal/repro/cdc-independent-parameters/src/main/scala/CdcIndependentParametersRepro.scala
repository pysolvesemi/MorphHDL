import spinal.core._
import morphhdl.MorphVerilog
import morphhdl.frontend.HdlInt

/** Compiler boundary probe, not production CDC or a substitute Dan IP. */
object CdcIndependentParametersRepro extends App {
  require(args.length == 1, "Expected output directory")
  class Record(dataBits: ElabInt, lanes: ElabInt, mode: String) extends Component {
    setDefinitionName("IndependentRecordProbe")
    val recordBits: ElabInt = dataBits + 7 + 32 + lanes + 16 + 1
    val din = in Bits(recordBits bits)
    val dout = out Bits(recordBits bits)
    mode match {
      case "ports" => dout := din
      case "blackbox" =>
        val child = new BlackBox {
          setBlackBoxName("ExternalRecordStorage")
          addGeneric("WIDTH", recordBits)
          val io = new Bundle {
            val din = in Bits(recordBits bits)
            val dout = out Bits(recordBits bits)
          }
          noIoPrefix()
        }
        child.io.din := din
        dout := child.io.dout
      case "value" =>
        val widthValue = ElabValue.uintLike(recordBits, U(0, 12 bits), "record_width")
        // Forces the typed elaboration-constant adapter independently of BlackBox.
        val matchesWidth = out Bool()
        matchesWidth := widthValue === 89
        dout := din
    }
  }
  class Deadline(timeout: ElabInt) extends Component {
    setDefinitionName("IndependentDeadlineProbe")
    require(timeout >= 32 && timeout <= (1 << 24))
    val din = in Bool()
    val dout = out Bool()
    dout := din
  }
  val expected = Seq(
    "ports" -> "",
    "blackbox" -> "SPINAL-PARAMETERIZED-VERILOG-BLACKBOX-INTEGER-GENERIC-DOMAIN-INVALID",
    "value" -> "SPINAL-PARAMETERIZED-VERILOG-VALUE-EXACT-DOMAIN-REQUIRED",
    "timeout" -> "SPINAL-ELAB-DOMAIN-EVIDENCE-MISSING"
  )
  expected.foreach { case (mode, diagnostic) =>
    val config = SpinalConfig(targetDirectory = args(0) + "/" + mode,
      oneFilePerComponent = false, headerWithDate = false, headerWithRepoHash = true)
    try {
      MorphVerilog(config) {
        if (mode == "timeout") new Deadline(
          HdlInt.param("CLOCK_TIMEOUT", default = 1024, min = 32, max = 1 << 24).asElabInt)
        else new Record(
          HdlInt.param("DATA_BITS", default = 32, min = 1, max = 2048).asElabInt,
          HdlInt.param("LANES", default = 1, min = 1, max = 4).asElabInt, mode)
      }
      require(diagnostic.isEmpty, s"$mode no longer reproduces; update the probe expectations")
      println(s"PROBE $mode: GENERATION_SUCCEEDED")
    } catch {
      case error: morphhdl.MorphVerilogException =>
        require(diagnostic.nonEmpty && error.getMessage.contains(diagnostic),
          s"Unexpected failure in $mode: ${error.getMessage}")
        println(s"PROBE $mode: REPRODUCED $diagnostic")
    }
  }
}
