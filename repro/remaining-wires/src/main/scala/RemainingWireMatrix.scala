import java.nio.file.{Files, Paths}
import spinal.core._
import morphhdl.{MorphVerilog, MorphWireAssignmentPasses}
import morphhdl.frontend.HdlBool

/** Independent of application RTL. Both clocks deliberately use one predicate. */
class SequentialConsumerStress extends Component {
  val io = new Bundle {
    val clkA, clkB, rstA, rstB, enableA, enableB = in Bool()
    val block, forceRun, priority, clear, allowB = in Bool()
    val value, extra = in UInt(18 bits)
    val signedValue = in SInt(18 bits)
    val countA, priorA, totalA, sumTruncA = out UInt(13 bits)
    val countB, totalB = out UInt(12 bits)
    val sameEdgeA = out Bool()
    val signedA = out SInt(8 bits)
    val signedWideA = out SInt(20 bits)
  }
  @dontName val shared = !io.block || io.forceRun
  val sourceWord = io.value + io.extra
  val domainA = ClockDomain(io.clkA, io.rstA, clockEnable = io.enableA,
    config = ClockDomainConfig(clockEdge = RISING, resetKind = ASYNC,
      resetActiveLevel = HIGH, clockEnableActiveLevel = HIGH))
  val domainB = ClockDomain(io.clkB, io.rstB, clockEnable = io.enableB,
    config = ClockDomainConfig(clockEdge = FALLING, resetKind = SYNC,
      resetActiveLevel = LOW, clockEnableActiveLevel = LOW))
  val a = new ClockingArea(domainA) {
    val count = Reg(UInt(13 bits)) init(3)
    val prior = Reg(UInt(13 bits)) init(7)
    val total = Reg(UInt(13 bits)) init(0)
    val sumTrunc = Reg(UInt(13 bits)) init(0)
    val sameEdge = Reg(Bool()) init(False)
    val signedSmall = Reg(SInt(8 bits)) init(-2)
    val signedWide = Reg(SInt(20 bits)) init(0)
    @dontName val preEdge = count === 3
    @dontName val direct = UInt(18 bits)
    direct := sourceWord
    @dontName val signedAlias = SInt(18 bits)
    signedAlias := io.signedValue
    sameEdge := preEdge
    signedSmall := signedAlias.resize(8)
    signedWide := signedAlias.resize(20)
    // This real arithmetic node MUST still get a legal Verilog select base.
    sumTrunc := (io.value + io.extra).resize(13)
    when(shared) {
      count := count + 1
      total := direct.resized
    } otherwise {
      count := 0
    }
    when(preEdge) { prior := count } elsewhen(io.clear) { prior := 0 }
    when(io.priority) {
      count := 9
      total := io.value.resized
    }
    when(shared) { when(io.clear) { total := direct.resized } }
  }
  val b = new ClockingArea(domainB) {
    val count = Reg(UInt(12 bits)) init(5)
    val total = Reg(UInt(12 bits)) init(0)
    when(shared) {
      count := count + 2
      total := sourceWord.resized
    } otherwise { count := 0 }
    when(!io.allowB) { count := 1 }
  }
  io.countA := a.count
  io.priorA := a.prior
  io.totalA := a.total
  io.sumTruncA := a.sumTrunc
  io.sameEdgeA := a.sameEdge
  io.signedA := a.signedSmall
  io.signedWideA := a.signedWide
  io.countB := b.count
  io.totalB := b.total
}

object GenerateRemainingWireMatrix extends App {
  require(args.length == 1, "Supply an output directory")
  val root = Paths.get(args(0)).toAbsolutePath
  def config(directory: String, mode: String, reset: ResetKind = ASYNC): SpinalConfig = {
    val base = SpinalConfig(targetDirectory = root.resolve(directory).toString,
      oneFilePerComponent = true, headerWithDate = false, headerWithRepoHash = true,
      defaultConfigForClockDomains = ClockDomainConfig(resetKind = reset))
    mode match {
      case "enabled" => MorphWireAssignmentPasses(base, enabled = true)
      case "disabled" => MorphWireAssignmentPasses(base, enabled = false)
      case "default" | "repeat" => base
    }
  }
  for ((name, reset) <- Vector("async" -> ASYNC, "sync" -> SYNC, "boot" -> BOOT);
       mode <- Vector("default", "repeat", "enabled", "disabled")) {
    MorphVerilog(config(s"$name-$mode", mode, reset)) {
      new RemainingWireRepro(
        HdlBool.param("PPC4", default = false).asElabBool.toElabInt * 3 + 1)
    }
  }
  for (mode <- Vector("default", "repeat", "disabled")) {
    MorphVerilog(config(s"stress-$mode", mode)) {
      val dut = new SequentialConsumerStress
      dut.setDefinitionName(if (mode == "disabled") "StressReference" else "StressOptimized")
      dut
    }
  }
  val traced = MorphWireAssignmentPasses(config("traced", "default"))
  spinal.core.internals.RemainingWireTrace.install(traced, root.resolve("trace"))
  MorphVerilog(traced) {
    new RemainingWireRepro(
      HdlBool.param("PPC4", default = false).asElabBool.toElabInt * 3 + 1)
  }
  println("REMAINING-WIRES: matrix generation complete")
}
