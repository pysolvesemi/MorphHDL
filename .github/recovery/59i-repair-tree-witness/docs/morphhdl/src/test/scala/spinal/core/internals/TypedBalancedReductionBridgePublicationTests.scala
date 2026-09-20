package spinal.core.internals

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import org.scalatest.funsuite.AnyFunSuite
import morphhdl.MorphVerilog
import morphhdl.frontend.HdlInt
import spinal.core._
import spinal.lib._

/** Scalar kinds whose initializers cannot be represented by an unsigned-only
  * bridge shortcut. The public native callbacks must survive publication.
  */
final class BalancedBridgeScalarKindsHardware(width: HdlInt, count: HdlInt) extends Component {
  setDefinitionName("BalancedBridgeScalarKinds")
  val clk = in(Bool()).setName("clk")
  val reset = in(Bool()).setName("reset")
  val signedIn = in(Vec(SInt(width bits), count)).setName("signedIn")
  val bitsIn = in(Vec(Bits(width bits), count)).setName("bitsIn")
  val boolIn = in(Vec(Bool(), count)).setName("boolIn")
  val signedResult = out(SInt(width bits)).setName("signedResult")
  val bitsResult = out(Bits(width bits)).setName("bitsResult")
  val boolResult = out(Bool()).setName("boolResult")
  val domain = new ClockingArea(ClockDomain(clock = clk, reset = reset,
      config = ClockDomainConfig(resetKind = SYNC))) {
    signedResult := signedIn.reduceBalancedTree((a: SInt, b: SInt) => a + b,
      (value: SInt, _: Int) => RegNextWhen(value, !value.msb) init S(-1))
    bitsResult := bitsIn.reduceBalancedTree((a: Bits, b: Bits) => a ^ b,
      (value: Bits, _: Int) => RegNextWhen(value, value(0)) init B(1))
    boolResult := boolIn.reduceBalancedTree((a: Bool, b: Bool) => a ^ b,
      (value: Bool, _: Int) => RegNextWhen(value, !value) init True)
  }
}

class TypedBalancedReductionBridgePublicationTests extends AnyFunSuite {
  private def read(path: Path): String = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  test("singleton-default ordinary native bridges retain WIDTH COUNT and every possible level") {
    val directory = Files.createTempDirectory("balanced-bridge-publication-")
    val clock = TypedBalancedReductionBridgeArtifactWriter.clocks.head
    val path = TypedBalancedReductionBridgeArtifactWriter.candidate(directory,
      "BalancedBridgePublication", clock)
    val rtl = read(path)
    assert(rtl.linesIterator.count(_.trim.startsWith("module BalancedBridgePublication")) == 1, rtl)
    assert(rtl.contains("parameter") && rtl.contains("WIDTH") && rtl.contains("COUNT"), rtl)
    TypedBalancedReductionBridgeArtifactWriter.outputs.foreach(output => assert(rtl.contains(output), rtl))
    for (tree <- 1 to 11; level <- 0 until 5)
      assert(rtl.contains(s"morphhdl_balanced_${tree}_active_$level"), rtl)
    assert(rtl.contains("generate") && rtl.contains("begin : tail"), rtl)
  }

  test("nonzero signed Bits and Bool initializers and exact local predicates publish through the public helper") {
    val directory = Files.createTempDirectory("balanced-bridge-scalar-kinds-")
    val config = TypedBalancedReductionBridgeArtifactWriter.config(directory,
      "BalancedBridgeScalarKinds.v")
    MorphVerilog(config) {
      new BalancedBridgeScalarKindsHardware(HdlInt.param("WIDTH", 5, 1, 32),
        HdlInt.param("COUNT", 1, 1, 17))
    }
    val rtl = read(directory.resolve("BalancedBridgeScalarKinds.v"))
    assert(rtl.contains("signedResult") && rtl.contains("bitsResult") && rtl.contains("boolResult"), rtl)
    assert(rtl.contains("always @(posedge clk)"), rtl)
    assert(rtl.contains("morphhdl_balanced_3_active_4"), rtl)
  }

  test("ordinary bridge publication preserves asynchronous low reset and falling clock with low active enable") {
    val directory = Files.createTempDirectory("balanced-bridge-falling-async-")
    val clock = TypedBalancedReductionBridgeArtifactWriter.ClockProfile(
      rising = false, asynchronous = true, resetHigh = false, enablePolarity = "LOW")
    val rtl = read(TypedBalancedReductionBridgeArtifactWriter.candidate(directory,
      "BalancedBridgeFalling", clock, defaultWidth = 8, defaultCount = 3))
    assert(rtl.contains("negedge clk or negedge reset"), rtl)
    assert(rtl.contains("negedge clk"), rtl)
    assert(rtl.contains("enable") && rtl.contains("localEnableResult"), rtl)
  }
}
