package morphhdl.paramrtl

import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}
import morphhdl.paramrtl.IntExpr.{Add, AddressWidth, Literal, ParameterRef}
import morphhdl.paramrtl.ModuleItem.{ContinuousAssign, GenerateFor, SynchronousCounter}
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.RtlExpr.Ref
import morphhdl.paramrtl.Signedness.{Signed, Unsigned}
import org.scalatest.funsuite.AnyFunSuite

class SynchronousCounterValidatorTests extends AnyFunSuite {
  test("accepts bounded parameterized counters including LIMIT equal to one") {
    assert(ParamRtlValidator.validate(counterDesign()).isRight)
    assert(ParamRtlValidator.validate(counterDesign(
      parameters = Vector(IntegerParameter("LIMIT", 1, Vector(MinInclusive(1), MaxInclusive(1))))
    )).isRight)
  }

  test("requires a direct positive finitely bounded public limit parameter") {
    assertCodes(
      counterDesign(limit = Add(ParameterRef("LIMIT"), Literal(0))),
      "PRTL-SYNCHRONOUS-COUNTER-LIMIT-NOT-DIRECT-PUBLIC-PARAMETER"
    )
    assertCodes(
      counterDesign(parameters = Vector(IntegerParameter("LIMIT", 5, Vector(MinInclusive(1))))),
      "PRTL-SYNCHRONOUS-COUNTER-LIMIT-NOT-FINITELY-BOUNDED"
    )
    assertCodes(
      counterDesign(parameters = Vector(IntegerParameter("LIMIT", 0, Vector(MinInclusive(0), MaxInclusive(8))))),
      "PRTL-SYNCHRONOUS-COUNTER-LIMIT-NOT-PROVEN-POSITIVE"
    )
    assertCodes(
      counterDesign(limit = ParameterRef("MISSING")),
      "PRTL-UNRESOLVED-PARAMETER",
      "PRTL-SYNCHRONOUS-COUNTER-LIMIT-NOT-DIRECT-PUBLIC-PARAMETER"
    )
  }

  test("requires exact distinct unsigned one-bit control inputs") {
    assertCodes(counterDesign(clock = "missing"), "PRTL-UNRESOLVED-RTL-REFERENCE")
    assertCodes(
      rewritePort(counterDesign(), "enable", direction = Some(Output)),
      "PRTL-SYNCHRONOUS-COUNTER-ENABLE-NOT-INPUT"
    )
    assertCodes(
      rewritePort(counterDesign(), "reset", dataType = Some(PackedBits(Literal(2), Unsigned))),
      "PRTL-SYNCHRONOUS-COUNTER-RESET-TYPE-MISMATCH"
    )
    assertCodes(
      rewritePort(counterDesign(), "clk", dataType = Some(PackedBits(Literal(1), Signed))),
      "PRTL-SYNCHRONOUS-COUNTER-CLOCK-TYPE-MISMATCH"
    )
    assertCodes(
      counterDesign(enable = "reset"),
      "PRTL-SYNCHRONOUS-COUNTER-ROLE-ALIAS"
    )
    assertCodes(
      counterDesign(count = "enable"),
      "PRTL-SYNCHRONOUS-COUNTER-COUNT-NOT-OUTPUT",
      "PRTL-SYNCHRONOUS-COUNTER-ROLE-ALIAS"
    )
  }

  test("requires count to be the sole unsigned AddressWidth(limit) output") {
    assertCodes(
      counterDesign(countType = PackedBits(Literal(3), Unsigned)),
      "PRTL-SYNCHRONOUS-COUNTER-COUNT-TYPE-MISMATCH"
    )
    assertCodes(
      counterDesign(countType = PackedBits(AddressWidth(ParameterRef("LIMIT")), Signed)),
      "PRTL-SYNCHRONOUS-COUNTER-COUNT-TYPE-MISMATCH"
    )
    assertCodes(
      counterDesign(countType = PackedBits(AddressWidth(Add(ParameterRef("LIMIT"), Literal(1))), Unsigned)),
      "PRTL-SYNCHRONOUS-COUNTER-COUNT-TYPE-MISMATCH"
    )

    val base = counterDesign().modules.head
    val extra = base.copy(ports = base.ports :+ Port("other", Output, PackedBits(Literal(1), Unsigned)))
    assertCodes(
      Design(extra.name, Vector(extra)),
      "PRTL-SYNCHRONOUS-COUNTER-OUTPUT-SHAPE-UNSUPPORTED",
      "PRTL-UNDRIVEN-OUTPUT"
    )
  }

  test("rejects sibling items multiple counters and counters nested in generate") {
    val base = counterDesign().modules.head
    val counter = base.items.head.asInstanceOf[SynchronousCounter]
    val sibling = base.copy(items = base.items :+ ContinuousAssign(Ref("count"), Ref("enable")))
    assertCodes(
      Design(sibling.name, Vector(sibling)),
      "PRTL-SYNCHRONOUS-COUNTER-MIXED-ITEMS-UNSUPPORTED",
      "PRTL-MULTIPLE-DRIVERS"
    )

    val multiple = base.copy(items = Vector(counter, counter.copy(label = "p_other")))
    assertCodes(
      Design(multiple.name, Vector(multiple)),
      "PRTL-MULTIPLE-SYNCHRONOUS-COUNTERS-UNSUPPORTED",
      "PRTL-MULTIPLE-DRIVERS"
    )

    val nested = base.copy(items = Vector(GenerateFor("g_counter", "i", Literal(1), Vector(counter))))
    assertCodes(
      Design(nested.name, Vector(nested)),
      "PRTL-PROCESS-IN-GENERATE-UNSUPPORTED",
      "PRTL-UNDRIVEN-OUTPUT"
    )
  }

  test("validates the counter process identifier and declaration namespace") {
    assertCodes(counterDesign(label = "bad-label"), "PRTL-INVALID-IDENTIFIER")
    assertCodes(counterDesign(label = "count"), "PRTL-DUPLICATE-DECLARATION")
  }

  private def rewritePort(
      design: Design,
      name: String,
      direction: Option[PortDirection] = None,
      dataType: Option[PackedBits] = None
  ): Design = {
    val module = design.modules.head
    Design(module.name, Vector(module.copy(ports = module.ports.map { port =>
      if (port.name == name)
        port.copy(
          direction = direction.getOrElse(port.direction),
          dataType = dataType.getOrElse(port.dataType)
        )
      else port
    })))
  }

  private def counterDesign(
      label: String = "p_counter",
      clock: String = "clk",
      reset: String = "reset",
      enable: String = "enable",
      count: String = "count",
      limit: IntExpr = ParameterRef("LIMIT"),
      countType: PackedBits = PackedBits(AddressWidth(ParameterRef("LIMIT")), Unsigned),
      parameters: Vector[IntegerParameter] = Vector(
        IntegerParameter("LIMIT", 5, Vector(MinInclusive(1), MaxInclusive(8)))
      )
  ): Design = {
    val module = ModuleDef(
      "ParameterizedCounter",
      parameters,
      Vector(
        Port("clk", Input, PackedBits(Literal(1), Unsigned)),
        Port("reset", Input, PackedBits(Literal(1), Unsigned)),
        Port("enable", Input, PackedBits(Literal(1), Unsigned)),
        Port("count", Output, countType)
      ),
      Vector(SynchronousCounter(
        label,
        Ref(clock),
        Ref(reset),
        Ref(enable),
        Ref(count),
        limit
      ))
    )
    Design(module.name, Vector(module))
  }

  private def assertCodes(design: Design, expected: String*): Unit =
    ParamRtlValidator.validate(design) match {
      case Left(diagnostics) =>
        expected.foreach(code => assert(diagnostics.codes.contains(code), diagnostics.values.mkString("\n")))
      case Right(_) => fail(s"Expected ${expected.mkString(", ")}")
    }
}
