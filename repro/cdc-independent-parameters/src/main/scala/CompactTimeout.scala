package spinal.core

import morphhdl.MorphVerilog
import morphhdl.frontend.HdlInt

/** Actual native sequential logic: CLOCK_TIMEOUT supplies the reload value.
  * The 25-bit carrier covers the full declared maximum, not only the default.
  * Preloading near expiration makes endpoint tests bounded even at 2^24.
  */
class CompactDeadline(timeout: ElabInt, minimumTimeout: ElabInt) extends Component {
  setDefinitionName("CompactDeadline")
  require(timeout >= 32 && timeout <= (1 << 24))
  require(timeout >= minimumTimeout, "CLOCK_TIMEOUT must be >= MIN_TIMEOUT")
  assert(timeout.expression.exactDomain.isEmpty, "compact declaration must not fabricate an exact table")
  assert(timeout.minimum == 32 && timeout.maximum == (BigInt(1) << 24))
  assert((timeout + 1).log2Up.minimum == 6 && (timeout + 1).log2Up.maximum == 25)

  val reload = in Bool()
  val preload = in Bool()
  val enable = in Bool()
  val preloadValue = in UInt(25 bits)
  val remaining = out UInt(25 bits)
  val busy = out Bool()
  val expired = out Bool()

  val reloadValue = ElabValue.uintLike(timeout, U(0, 25 bits), "timeout_reload_value")
  val counter = Reg(UInt(25 bits)) init(0)
  val expiration = Reg(Bool()) init(False)
  expiration := False
  when(reload) {
    counter := reloadValue
  } elsewhen(preload) {
    counter := preloadValue
  } elsewhen(enable && counter =/= 0) {
    counter := counter - 1
    when(counter === 1) { expiration := True }
  }
  remaining := counter
  busy := counter =/= 0
  expired := expiration
}

/** Separate geometry fixture exercises a genuine compact-derived packed width. */
class CompactDeadlineGeometry(timeout: ElabInt) extends Component {
  setDefinitionName("CompactDeadlineGeometry")
  val width = (timeout + 1).log2Up
  val din = in Bits(width bits)
  val dout = out Bits(width bits)
  dout := din
}

object CompactTimeoutGenerate extends App {
  require(args.length == 1, "Expected output directory")
  def timeout: ElabInt =
    HdlInt.param("CLOCK_TIMEOUT", default = 1024, min = 32, max = BigInt(1) << 24).asElabInt
  def config(name: String): SpinalConfig = SpinalConfig(
    targetDirectory = args(0) + "/" + name,
    oneFilePerComponent = false,
    headerWithDate = false,
    headerWithRepoHash = true
  )
  MorphVerilog(config("timer")) {
    new CompactDeadline(timeout,
      HdlInt.param("MIN_TIMEOUT", default = 32, min = 16, max = 64).asElabInt)
  }
  MorphVerilog(config("geometry")) { new CompactDeadlineGeometry(timeout) }
  println("COMPACT_TIMEOUT_GENERATION_PASS")
}

object CompactTimeoutSafety extends App {
  require(args.length == 1, "Expected output directory")
  var controls = 0
  def timeout: ElabInt =
    HdlInt.param("CLOCK_TIMEOUT", default = 1024, min = 32, max = BigInt(1) << 24).asElabInt
  def reject(label: String, code: String)(body: => Unit): Unit = {
    var caught = false
    try {
      MorphVerilog(SpinalConfig(targetDirectory = args(0) + "/" + label,
        headerWithDate = false, headerWithRepoHash = true)) {
        new Component { body }
      }
    } catch {
      case error: Exception if Option(error.getMessage).exists(_.contains(code)) =>
        caught = true
        controls += 1
        println("COMPACT_REJECTION_PASS " + label + " " + code)
    }
    assert(caught, label + " did not fail with " + code)
  }
  reject("copied-carrier", "COMPACT-COPIED-CARRIER") {
    val value = timeout
    ElaborationWidthAuthority.requireAuthoritative(value.expression.copy(),
      "copied compact carrier", "COMPACT-COPIED-CARRIER")
  }
  reject("changed-summary", "COMPACT-CHANGED-SUMMARY") {
    val value = timeout
    ElaborationWidthAuthority.requireAuthoritative(value.expression.copy(maximum = 2048),
      "changed compact summary", "COMPACT-CHANGED-SUMMARY")
  }
  reject("narrow-uint", "SPINAL-PARAMETERIZED-VERILOG-VALUE-WIDTH-INSUFFICIENT") {
    ElabValue.uintLike(timeout, U(0, 24 bits), "too_narrow")
  }
  reject("structural-count", "SPINAL-ELAB-INT-DOMAIN-NOT-CONSTANT") {
    timeout.slices
  }
  reject("branch-declaration", "SPINAL-ELAB-DOMAIN-COMPACT-SCOPE-UNSUPPORTED") {
    val root = ElaborationIntegerParameterRoot.fresh("OTHER")
    ElaborationDomainContext.withAdmitted(root, Set(BigInt(1)), None) { timeout }
  }
  reject("same-name-independent", "SPINAL-ELAB-INT-INDEPENDENT-ROOTS-UNSUPPORTED") {
    val first = timeout
    val second = timeout
    first + second
  }
  reject("same-name-conflicting-schema", "SPINAL-ELAB-INT-PARAMETER-SCHEMA-CONFLICT") {
    val first = timeout
    val second = HdlInt.param("CLOCK_TIMEOUT", default = 1024, min = 64, max = BigInt(1) << 24).asElabInt
    first + second
  }
  reject("checked-overflow", "SPINAL-ELAB-DOMAIN-EVIDENCE-RESULT-OUT-OF-RANGE") {
    timeout * 1000
  }
  reject("arbitrary-enumeration", "SPINAL-ELAB-DOMAIN-PRODUCT-CORRELATION-UNSUPPORTED") {
    ElaborationProductDomain.provesRelation(timeout.expression, ElabInt.literal(0).expression)(_ > _)
  }
  // This is separate from the nine original authority controls: accepting
  // compact integer values must not raise the physical bit-vector width cap.
  var physicalWidthRejected = false
  try {
    MorphVerilog(SpinalConfig(targetDirectory = args(0) + "/oversized-packed-width",
      headerWithDate = false, headerWithRepoHash = true)) {
      new Component {
        val bound = timeout
        val din = in Bits(bound bits)
        val dout = out Bits(bound bits)
        dout := din
      }
    }
  } catch {
    case error: Exception if Option(error.getMessage).exists(
      _.contains("SPINAL-PARAMETERIZED-VERILOG-EXPRESSION-DOMAIN-TOO-LARGE")) =>
      physicalWidthRejected = true
  }
  assert(physicalWidthRejected, "compact integer admission bypassed the physical packed-width cap")
  println("COMPACT_PHYSICAL_WIDTH_REJECTION_PASS")
  assert(controls == 9, "compact rejection inventory changed")
  println("COMPACT_TIMEOUT_SAFETY_PASS controls=" + controls)
}
