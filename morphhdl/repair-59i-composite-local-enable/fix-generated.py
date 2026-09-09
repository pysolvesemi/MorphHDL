#!/usr/bin/env python3
"""Harden the staged local-enable prototype before compilation.

This small follow-on remains separate from apply.py so the original source-bound
prototype is reviewable. It removes false-negative test paths, narrows the
bit-access source before exact identity lookup, preserves width-polymorphic
bridge behavior, and ensures only conditional data assignments contribute When
control ownership.
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


def replace_region(text: str, start: str, end: str, replacement: str, label: str) -> str:
    require(text.count(start) == 1, f"{label} start count changed: {text.count(start)}")
    require(text.count(end) == 1, f"{label} end count changed: {text.count(end)}")
    left = text.index(start)
    right = text.index(end, left)
    require(right > left, f"{label} anchors reversed")
    return text[:left] + replacement + text[right + len(end):]


def patch_bridge_source_type() -> None:
    text = BRIDGE.read_text()
    before = '''          val index = if (access.source eq driver) inputIndex else admitted(access.source)
'''
    after = '''          val index =
            if (access.source eq driver) inputIndex
            else access.source match {
              case source: BaseType => admitted(source)
              case _ => -1
            }
'''
    text = replace_once(text, before, after, "bit-access source narrowing")

    # Width-polymorphic rows have the same bridge behavior even when a control
    # leaf widens between rows. Fixed-bit bounds and high-bit semantics are
    # revalidated against each replacement width during replay; behavior
    # equality therefore compares only control slot/type structure here.
    strict_widths = '''        controls.zip(other.controls).forall { case (a, b) =>
          (a.owner eq b.owner) && (a.kind eq b.kind) &&
            ElaborationWidthAuthority.equivalent(a.width, b.width)
        } && minimumInitializerWidth == other.minimumInitializerWidth &&
'''
    polymorphic_widths = '''        controls.zip(other.controls).forall { case (a, b) =>
          (a.owner eq b.owner) && (a.kind eq b.kind)
        } && minimumInitializerWidth == other.minimumInitializerWidth &&
'''
    text = replace_once(text, strict_widths, polymorphic_widths,
                        "width-polymorphic control behavior")
    BRIDGE.write_text(text)


def patch_walker() -> None:
    text = COMPOSITE.read_text()
    control = '''            mark(leaf).foreach { assignment =>
              condition(assignment)
              walkControl(assignment.source)
            }
'''
    control_fixed = '''            mark(leaf).foreach { assignment =>
              assignment match {
                case _: DataAssignmentStatement => condition(assignment)
                case _ =>
              }
              walkControl(assignment.source)
            }
'''
    data = '''            mark(leaf).foreach { assignment =>
              condition(assignment)
              walkData(assignment.source)
            }
'''
    data_fixed = '''            mark(leaf).foreach { assignment =>
              assignment match {
                case _: DataAssignmentStatement => condition(assignment)
                case _ =>
              }
              walkData(assignment.source)
            }
'''
    text = replace_once(text, control, control_fixed, "control assignment scope")
    text = replace_once(text, data, data_fixed, "data assignment scope")
    COMPOSITE.write_text(text)


def patch_test() -> None:
    text = TEST.read_text()
    text = replace_once(text,
        "final case class BalancedLocalEnableRecord(uw: HdlInt, sw: HdlInt, bw: HdlInt) extends Bundle {\n",
        "final class BalancedLocalEnableRecord(uw: HdlInt, sw: HdlInt, bw: HdlInt) extends Bundle {\n",
        "non-Product HdlInt fixture")
    require(text.count("Vec(BalancedLocalEnableRecord(") == 3,
            "unexpected Vec record-construction inventory")
    text = text.replace("Vec(BalancedLocalEnableRecord(",
                        "Vec(new BalancedLocalEnableRecord(")
    text = replace_once(text, "out(BalancedLocalEnableRecord(",
                        "out(new BalancedLocalEnableRecord(",
                        "record output construction")

    text = replace_once(text, "private object BalancedLocalEnableRecord {\n",
        "private object BalancedLocalEnableOps {\n", "helper object name")
    require(text.count("BalancedLocalEnableRecord.combine") == 3,
            "unexpected combine call inventory")
    require(text.count("BalancedLocalEnableRecord.register") == 3,
            "unexpected register call inventory")
    text = text.replace("BalancedLocalEnableRecord.combine", "BalancedLocalEnableOps.combine")
    text = text.replace("BalancedLocalEnableRecord.register", "BalancedLocalEnableOps.register")

    first_start = '''  test("same-composite cross-field controls replay with field-local data and exact latency") {
'''
    first_end = '''  test("public parameterized publication retains cross-field controls in two reset profiles") {
'''
    first = '''  test("same-composite cross-field controls replay with field-local data and exact latency") {
    var stageCounts = Vector.empty[Int]
    var allLocal = false
    var latencies = Vector.empty[Int]
    var registerCounts = Vector.empty[Int]
    SpinalConfig(targetDirectory = Files.createTempDirectory("balanced-local-enable-certificate-").toString,
      headerWithDate = false, headerWithRepoHash = false).generateVerilog(new Component {
      val width = HdlInt.param("WIDTH", 5, 3, 16)
      val values = Vec(new BalancedLocalEnableRecord(width, width, width), HdlInt.param("COUNT", 1, 1, 5))
      values.vec.foreach(_.flatten.foreach {
        case value: UInt => value := 0
        case value: SInt => value := 0
        case value: Bits => value := 0
        case value: Bool => value := False
      })
      val certificate = TypedBalancedReductionCompositeReplay.capture(values,
        (a: BalancedLocalEnableRecord, b: BalancedLocalEnableRecord) =>
          BalancedLocalEnableOps.combine(a, b),
        (value: BalancedLocalEnableRecord, _: Int) => BalancedLocalEnableOps.register(value),
        native[BalancedLocalEnableRecord])
      for (count <- 1 to 5) certificate.replay(values.vec.take(count).toVector)
      stageCounts = certificate.stages.map(_.registerCountPerRow)
      allLocal = certificate.stages.forall(_.bridges.head.hasLocalEnables)
      latencies = (1 to 5).map(certificate.latencyFor).toVector
      registerCounts = certificate.captured.rows.map(_.bridge.declarations.count(_.isReg))
      certificate.requireFreshness()
    })
    assert(stageCounts == Vector(1, 1, 1))
    assert(allLocal)
    assert(latencies == (1 to 5).map(count => (BigInt(count) - 1).bitLength).toVector)
    assert(registerCounts.forall(_ == 4))
  }

  test("public parameterized publication retains cross-field controls in two reset profiles") {
'''
    text = replace_region(text, first_start, first_end, first, "pre-phase freshness test")

    public_config = '''      val config = SpinalConfig(targetDirectory = directory.toString, headerWithDate = false,
        headerWithRepoHash = false, bitVectorWidthMax = 4096)
'''
    supported_public_config = '''      val config = SpinalConfig(targetDirectory = directory.toString,
        bitVectorWidthMax = 4096)
'''
    text = replace_once(text, public_config, supported_public_config,
                        "direct-emitter supported config")

    old_negative = '''          (value: BalancedLocalEnableRecord, _: Int) => {
            val result = BalancedLocalEnableOps.register(value)
            result.valid := RegNextWhen(value.valid, foreign) init False
            result
          }, native[BalancedLocalEnableRecord])
'''
    new_negative = '''          (value: BalancedLocalEnableRecord, _: Int) => {
            val result = cloneOf(value)
            result.unsigned := RegNextWhen(value.unsigned, foreign) init U(3)
            result.signed := RegNextWhen(value.signed, value.bitsValue(0)) init S(-2)
            result.bitsValue := RegNextWhen(value.bitsValue, value.signed.msb) init B(1)
            result.valid := RegNextWhen(value.valid, value.unsigned(0)) init False
            result
          }, native[BalancedLocalEnableRecord])
'''
    text = replace_once(text, old_negative, new_negative, "single-driver external control")
    TEST.write_text(text)


def main() -> None:
    require(BRIDGE.is_file() and COMPOSITE.is_file() and TEST.is_file(),
            "apply.py must run first")
    patch_bridge_source_type()
    patch_walker()
    patch_test()
    print("59i local-enable generated source hardening applied")


if __name__ == "__main__":
    main()
