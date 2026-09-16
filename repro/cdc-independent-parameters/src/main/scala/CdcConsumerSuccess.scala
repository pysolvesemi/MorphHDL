package spinal.core

import morphhdl.MorphVerilog
import morphhdl.frontend.HdlInt

/** Success-only compiler fixtures. ExternalRecordStorage is test-only, not Dan IP. */
object CdcConsumerSuccess extends App {
  require(args.length == 1, "Expected output directory")
  def parameter(name: String, default: Int, min: Int, max: Int): ElabInt =
    HdlInt.param(name, default = default, min = min, max = max).asElabInt
  class Record(dataBits: ElabInt, lanes: ElabInt, mode: String) extends Component {
    setDefinitionName("CdcRecord" + mode)
    val recordBits: ElabInt = dataBits + 7 + 32 + lanes + 16 + 1
    val din = in Bits(recordBits bits)
    val dout = out Bits(recordBits bits)
    if (mode == "BlackBox") {
      val storage = new BlackBox {
        setBlackBoxName("ExternalRecordStorage")
        addGeneric("WIDTH", recordBits)
        val io = new Bundle {
          val din = in Bits(recordBits bits)
          val dout = out Bits(recordBits bits)
        }
        noIoPrefix()
      }
      storage.setName("storage")
      storage.io.din := din
      dout := storage.io.dout
    } else {
      dout := din
      if (mode == "Value") {
        val widthValue = ElabValue.uintLike(recordBits, U(0, 12 bits), "record_width")
        val matchesWidth = out Bool()
        matchesWidth := widthValue === 89
      }
    }
  }
  for (mode <- Vector("Ports", "BlackBox", "Value")) {
    MorphVerilog(SpinalConfig(targetDirectory = args(0) + "/" + mode,
      oneFilePerComponent = false, headerWithDate = false, headerWithRepoHash = true)) {
      new Record(parameter("DATA_BITS", 32, 1, 2048), parameter("LANES", 1, 1, 4), mode)
    }
    println("CDC_CONSUMER_GENERATED " + mode)
  }
}

/** Construction-side negative controls; publication ownership is checked separately. */
object CdcConsumerSafety extends App {
  require(args.length == 1, "Expected output directory")
  def p(name: String = "DATA_BITS", max: Int = 2048): ElabInt =
    HdlInt.param(name, default = 32, min = 1, max = max).asElabInt
  def l(): ElabInt = HdlInt.param("LANES", default = 1, min = 1, max = 4).asElabInt
  def reject(name: String, code: String)(body: => Component): Unit = {
    var rejected = false
    try {
      MorphVerilog(SpinalConfig(targetDirectory = args(0) + "/" + name,
        headerWithDate = false, headerWithRepoHash = false))(body)
    } catch {
      case e: morphhdl.MorphVerilogException =>
        require(e.getMessage.contains(code), name + " unexpected diagnostic: " + e.getMessage)
        rejected = true
      case e: ParameterizedVerilogException =>
        require(e.getMessage.contains(code), name + " unexpected diagnostic: " + e.getMessage)
        rejected = true
    }
    require(rejected, "accepted invalid fixture " + name)
    println("CDC_CONSUMER_REJECTED " + name + " " + code)
  }
  reject("undersized", "SPINAL-PARAMETERIZED-VERILOG-VALUE-WIDTH-INSUFFICIENT") {
    val width = p() + 7 + 32 + l() + 16 + 1
    new Component { val result = out UInt(11 bits); result := ElabValue.uintLike(width, U(0, 11 bits), "bad") }
  }
  reject("copied-value", "SPINAL-PARAMETERIZED-VERILOG-VALUE-EXACT-DOMAIN-REQUIRED") {
    val width = p() + l() + 56
    val copied = ElabInt.fromExpression(width.expression.copy())
    new Component { val result = out UInt(12 bits); result := ElabValue.uintLike(copied, U(0, 12 bits), "bad") }
  }
  reject("copied-generic", "SPINAL-PARAMETERIZED-VERILOG-BLACKBOX-INTEGER-GENERIC-DOMAIN-INVALID") {
    val width = p() + l() + 56
    val copied = ElabInt.fromExpression(width.expression.copy())
    new Component {
      val child = new BlackBox { setBlackBoxName("ExternalRecordStorage"); addGeneric("WIDTH", copied) }
    }
  }
  reject("conflicting-roots", "SPINAL-ELAB-INT-INDEPENDENT-ROOTS-UNSUPPORTED") {
    val width = p() + p()
    new Component { val result = out UInt(13 bits); result := ElabValue.uintLike(width, U(0, 13 bits), "bad") }
  }
  reject("escaped-projection", "SPINAL-ELAB-DOMAIN-EVIDENCE-SCOPE-MISMATCH") {
    val data = p()
    val lanes = l()
    val width = ElaborationDomainContext.withAdmitted(data.expression.exactDomain.get.root, Set(BigInt(32)), None) {
      data + lanes + 56
    }
    new Component { val result = out UInt(12 bits); result := ElabValue.uintLike(width, U(0, 12 bits), "bad") }
  }
}
