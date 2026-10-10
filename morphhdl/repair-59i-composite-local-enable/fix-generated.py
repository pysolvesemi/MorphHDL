#!/usr/bin/env python3
"""Harden the staged local-enable prototype before compilation.

This follow-on remains separate from apply.py so the original source-bound
prototype is reviewable. It narrows bit-access sources before identity lookup,
preserves width-polymorphic bridge behavior, excludes initializer statements
from conditional-control discovery, and replaces the initial smoke fixture with
capture-free native callbacks and exact negative controls.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BRIDGE = ROOT / "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionBridgeReplay.scala"
COMPOSITE = ROOT / "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeReplay.scala"
TEST = ROOT / "morphhdl/src/test/scala/spinal/core/internals/TypedBalancedReductionCompositeLocalEnableTests.scala"


def require(condition: bool, detail: str) -> None:
    if not condition:
        raise RuntimeError(detail)


def replace_once(text: str, before: str, after: str, label: str) -> str:
    require(text.count(before) == 1, f"{label} anchor count changed: {text.count(before)}")
    return text.replace(before, after, 1)


def patch_bridge() -> None:
    text = BRIDGE.read_text()
    text = replace_once(text,
        '''          val index = if (access.source eq driver) inputIndex else admitted(access.source)
''',
        '''          val index =
            if (access.source eq driver) inputIndex
            else access.source match {
              case source: BaseType => admitted(source)
              case _ => -1
            }
''', "bit-access source narrowing")

    # A bridge can retain identical slot/type behavior while a widening row has
    # a different symbolic width. Every replacement width and fixed-bit bound is
    # still revalidated at replay; behavior equality must not freeze the row.
    text = replace_once(text,
        '''        controls.zip(other.controls).forall { case (a, b) =>
          (a.owner eq b.owner) && (a.kind eq b.kind) &&
            ElaborationWidthAuthority.equivalent(a.width, b.width)
        } && minimumInitializerWidth == other.minimumInitializerWidth &&
''',
        '''        controls.zip(other.controls).forall { case (a, b) =>
          (a.owner eq b.owner) && (a.kind eq b.kind)
        } && minimumInitializerWidth == other.minimumInitializerWidth &&
''', "width-polymorphic control behavior")
    BRIDGE.write_text(text)


def patch_composite() -> None:
    text = COMPOSITE.read_text()
    text = replace_once(text,
        '''            mark(leaf).foreach { assignment =>
              condition(assignment)
              walkControl(assignment.source)
            }
''',
        '''            mark(leaf).foreach { assignment =>
              assignment match {
                case _: DataAssignmentStatement => condition(assignment)
                case _ =>
              }
              walkControl(assignment.source)
            }
''', "control assignment scope")
    text = replace_once(text,
        '''            mark(leaf).foreach { assignment =>
              condition(assignment)
              walkData(assignment.source)
            }
''',
        '''            mark(leaf).foreach { assignment =>
              assignment match {
                case _: DataAssignmentStatement => condition(assignment)
                case _ =>
              }
              walkData(assignment.source)
            }
''', "data assignment scope")
    COMPOSITE.write_text(text)


def write_test() -> None:
    require(TEST.is_file(), "apply.py must create the local-enable fixture first")
    TEST.write_text(r'''package spinal.core.internals

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import morphhdl.MorphVerilog
import morphhdl.frontend.HdlInt
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._

final case class BalancedLocalEnableRecord(uw: HdlInt, sw: HdlInt, bw: HdlInt) extends Bundle {
  val unsigned = UInt(uw bits)
  val signed = SInt(sw bits)
  val bitsValue = Bits(bw bits)
  val valid = Bool()
}

private final class BalancedLocalEnablePublic(width: HdlInt, count: HdlInt,
    moduleName: String, asynchronousLow: Boolean) extends Component {
  setDefinitionName(moduleName)
  val clk = in(Bool()).setName("clk")
  val reset = in(Bool()).setName("reset")
  val values = in(Vec(BalancedLocalEnableRecord(width, width, width), count)).setName("values")
  val result = out(BalancedLocalEnableRecord(width, width, width)).setName("result")
  val domain = ClockDomain(clock = clk, reset = reset, config = ClockDomainConfig(
    clockEdge = if (asynchronousLow) FALLING else RISING,
    resetKind = if (asynchronousLow) ASYNC else SYNC,
    resetActiveLevel = if (asynchronousLow) LOW else HIGH))
  val area = new ClockingArea(domain) {
    val reduced = values.reduceBalancedTree(
      (a: BalancedLocalEnableRecord, b: BalancedLocalEnableRecord) => {
        val r = cloneOf(a)
        r.unsigned := a.unsigned + b.unsigned
        r.signed := a.signed + b.signed
        r.bitsValue := a.bitsValue ^ b.bitsValue
        r.valid := a.valid | b.valid
        r
      },
      (value: BalancedLocalEnableRecord, _: Int) => {
        val r = cloneOf(value)
        r.unsigned := RegNextWhen(value.unsigned, value.valid) init U(3)
        r.signed := RegNextWhen(value.signed, value.bitsValue(0)) init S(-2)
        r.bitsValue := RegNextWhen(value.bitsValue, value.signed.msb) init B(1)
        r.valid := RegNextWhen(value.valid, value.unsigned(0) ^ value.bitsValue.msb) init True
        r
      })
    result := reduced
  }
}

class TypedBalancedReductionCompositeLocalEnableTests extends AnyFunSuite {
  private def native[T <: Data]: ElabBalancedReduction.Native[T] =
    (values, operation, bridge) => new TraversableOnceAnyPimped[T](values).reduceBalancedTree(operation, bridge)

  private def details(error: Throwable): String =
    if (error == null) "" else Option(error.getMessage).getOrElse("") + "\n" + details(error.getCause)

  test("same-composite cross-field controls replay with field-local data and exact latency") {
    val width = HdlInt.param("WIDTH", 5, 3, 16)
    val count = HdlInt.param("COUNT", 1, 1, 5)
    var stageCounts = Vector.empty[Int]
    var allLocal = false
    var latencies = Vector.empty[Int]
    var registerCounts = Vector.empty[Int]
    SpinalConfig(targetDirectory = Files.createTempDirectory("balanced-local-enable-certificate-").toString,
      headerWithDate = false, headerWithRepoHash = false).generateVerilog(new Component {
      val values = Vec(BalancedLocalEnableRecord(width, width, width), count)
      values.vec.foreach(_.flatten.foreach {
        case value: UInt => value := 0
        case value: SInt => value := 0
        case value: Bits => value := 0
        case value: Bool => value := False
      })
      val certificate = TypedBalancedReductionCompositeReplay.capture(values,
        (a: BalancedLocalEnableRecord, b: BalancedLocalEnableRecord) => {
          val r = cloneOf(a)
          r.unsigned := a.unsigned + b.unsigned
          r.signed := a.signed + b.signed
          r.bitsValue := a.bitsValue ^ b.bitsValue
          r.valid := a.valid | b.valid
          r
        },
        (value: BalancedLocalEnableRecord, _: Int) => {
          val r = cloneOf(value)
          r.unsigned := RegNextWhen(value.unsigned, value.valid) init U(3)
          r.signed := RegNextWhen(value.signed, value.bitsValue(0)) init S(-2)
          r.bitsValue := RegNextWhen(value.bitsValue, value.signed.msb) init B(1)
          r.valid := RegNextWhen(value.valid, value.unsigned(0) ^ value.bitsValue.msb) init True
          r
        }, native[BalancedLocalEnableRecord])
      for (activeCount <- 1 to 5) certificate.replay(values.vec.take(activeCount).toVector)
      stageCounts = certificate.stages.map(_.registerCountPerRow)
      allLocal = certificate.stages.forall(_.bridges.head.hasLocalEnables)
      latencies = (1 to 5).map(certificate.latencyFor).toVector
      registerCounts = certificate.captured.rows.map(_.bridge.declarations.count(_.isReg))
      certificate.requireFreshness()
    })
    assert(stageCounts == Vector(1, 1, 1))
    assert(allLocal)
    assert(latencies == (1 to 5).map(value => (BigInt(value) - 1).bitLength).toVector)
    assert(registerCounts.forall(_ == 4))
  }

  test("public parameterized publication retains cross-field controls in two reset profiles") {
    for ((name, asynchronousLow) <- Vector("sync_high" -> false, "async_low" -> true)) {
      val directory = Files.createTempDirectory("balanced-local-enable-public-" + name + "-")
      val file = "BalancedLocalEnable_" + name + ".v"
      val config = SpinalConfig(targetDirectory = directory.toString, bitVectorWidthMax = 4096)
      config.netlistFileName = file
      MorphVerilog(config) {
        new BalancedLocalEnablePublic(HdlInt.param("WIDTH", 5, 3, 16),
          HdlInt.param("COUNT", 1, 1, 5), "BalancedLocalEnable_" + name, asynchronousLow)
      }
      val path = directory.resolve(file)
      assert(Files.isRegularFile(path))
      val rtl = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
      assert(rtl.contains("parameter") && rtl.contains("COUNT") && rtl.contains("WIDTH"), rtl)
      assert(rtl.contains("generate") && rtl.contains("always"), rtl)
    }
  }

  test("external and registered peer controls remain rejected") {
    val width = HdlInt.param("WIDTH", 5, 3, 16)
    val count = HdlInt.param("COUNT", 1, 1, 3)
    val external = intercept[Exception] {
      SpinalConfig(targetDirectory = Files.createTempDirectory("balanced-local-enable-external-").toString,
        headerWithDate = false, headerWithRepoHash = false).generateVerilog(new Component {
        val values = Vec(BalancedLocalEnableRecord(width, width, width), count)
        values.vec.foreach(_.flatten.foreach {
          case value: UInt => value := 0
          case value: SInt => value := 0
          case value: Bits => value := 0
          case value: Bool => value := False
        })
        val foreign = Bool(); foreign := False
        TypedBalancedReductionCompositeReplay.capture(values,
          (a: BalancedLocalEnableRecord, b: BalancedLocalEnableRecord) => {
            val r = cloneOf(a)
            r.unsigned := a.unsigned + b.unsigned
            r.signed := a.signed + b.signed
            r.bitsValue := a.bitsValue ^ b.bitsValue
            r.valid := a.valid | b.valid
            r
          },
          (value: BalancedLocalEnableRecord, _: Int) => {
            val r = cloneOf(value)
            r.unsigned := RegNextWhen(value.unsigned, foreign) init U(3)
            r.signed := RegNextWhen(value.signed, value.bitsValue(0)) init S(-2)
            r.bitsValue := RegNextWhen(value.bitsValue, value.signed.msb) init B(1)
            r.valid := RegNextWhen(value.valid, value.unsigned(0)) init False
            r
          }, native[BalancedLocalEnableRecord])
      })
    }
    assert(details(external).contains("BRIDGE") ||
      details(external).contains("GRAPH-EXTERNAL-READ"), details(external))

    val registered = intercept[Exception] {
      SpinalConfig(targetDirectory = Files.createTempDirectory("balanced-local-enable-peer-").toString,
        headerWithDate = false, headerWithRepoHash = false).generateVerilog(new Component {
        val values = Vec(BalancedLocalEnableRecord(width, width, width), count)
        values.vec.foreach(_.flatten.foreach {
          case value: UInt => value := 0
          case value: SInt => value := 0
          case value: Bits => value := 0
          case value: Bool => value := False
        })
        TypedBalancedReductionCompositeReplay.capture(values,
          (a: BalancedLocalEnableRecord, b: BalancedLocalEnableRecord) => {
            val r = cloneOf(a)
            r.unsigned := a.unsigned + b.unsigned
            r.signed := a.signed + b.signed
            r.bitsValue := a.bitsValue ^ b.bitsValue
            r.valid := a.valid | b.valid
            r
          },
          (value: BalancedLocalEnableRecord, _: Int) => {
            val r = cloneOf(value)
            val peer = RegNext(value.valid) init False
            r.unsigned := RegNextWhen(value.unsigned, peer) init U(3)
            r.signed := RegNextWhen(value.signed, value.bitsValue(0)) init S(-2)
            r.bitsValue := RegNextWhen(value.bitsValue, value.signed.msb) init B(1)
            r.valid := RegNextWhen(value.valid, value.unsigned(0)) init False
            r
          }, native[BalancedLocalEnableRecord])
      })
    }
    assert(details(registered).contains("BRIDGE-CONTROL-REGISTER"), details(registered))
  }
}
''')


def main() -> None:
    require(BRIDGE.is_file() and COMPOSITE.is_file() and TEST.is_file(),
            "apply.py must run first")
    patch_bridge()
    patch_composite()
    write_test()
    print("59i local-enable generated source hardening applied")


if __name__ == "__main__":
    main()
