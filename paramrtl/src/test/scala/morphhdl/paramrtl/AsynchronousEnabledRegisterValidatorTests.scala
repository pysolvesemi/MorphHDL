package morphhdl.paramrtl

import morphhdl.paramrtl.BoolExpr.{Literal => BoolLiteral}
import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}
import morphhdl.paramrtl.IntExpr.{Literal, ParameterRef}
import morphhdl.paramrtl.ModuleItem.{
  AsynchronousEnabledRegister,
  AsynchronousRegister,
  CombinationalIf,
  ContinuousAssign,
  GenerateCase,
  GenerateFor,
  GenerateIf,
  ModuleInstance,
  SynchronousEnabledRegister,
  SynchronousRegister
}
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.RtlExpr.Ref
import morphhdl.paramrtl.Signedness.{Signed, Unsigned}
import org.scalatest.funsuite.AnyFunSuite

class AsynchronousEnabledRegisterValidatorTests extends AnyFunSuite {
  test("accepts one rising-edge active-high asynchronous-reset enabled register") {
    assert(ParamRtlValidator.validate(registerDesign()).isRight)
  }

  test("accepts exact parameterized packed types and unrelated extra inputs") {
    val width = IntegerParameter("WIDTH", 8, Vector(MinInclusive(1), MaxInclusive(32)))
    val packed = PackedBits(ParameterRef("WIDTH"), Unsigned)
    val base = registerModule("ParameterizedAsyncEnabledRegister", packed = packed)
    val top = base.copy(
      parameters = Vector(width),
      ports = base.ports :+ Port("unused", Input, PackedBits(Literal(3), Unsigned))
    )

    assert(ParamRtlValidator.validate(Design(top.name, Vector(top))).isRight)
  }

  test("requires resolved unsigned one-bit input clock reset and enable controls") {
    Vector(
      registerModule("MissingClock", clock = "missing"),
      registerModule("MissingReset", reset = "missing"),
      registerModule("MissingEnable", enable = "missing")
    ).foreach { top =>
      assertCodes(Design(top.name, Vector(top)), "PRTL-UNRESOLVED-RTL-REFERENCE")
    }

    val outputClock = registerModule("OutputClock", clock = "data_out")
    assertCodes(
      Design(outputClock.name, Vector(outputClock)),
      "PRTL-ASYNCHRONOUS-ENABLED-CLOCK-NOT-INPUT",
      "PRTL-ASYNCHRONOUS-ENABLED-CLOCK-TYPE-MISMATCH"
    )
    val wideReset = registerModule("WideReset", reset = "data_in")
    assertCodes(
      Design(wideReset.name, Vector(wideReset)),
      "PRTL-ASYNCHRONOUS-ENABLED-RESET-TYPE-MISMATCH"
    )
    val wideEnable = registerModule("WideEnable", enable = "data_in")
    assertCodes(
      Design(wideEnable.name, Vector(wideEnable)),
      "PRTL-ASYNCHRONOUS-ENABLED-ENABLE-TYPE-MISMATCH"
    )

    val signedBase = registerModule("SignedEnable")
    val signedEnable = signedBase.copy(ports = signedBase.ports.map {
      case port if port.name == "enable" => port.copy(dataType = PackedBits(Literal(1), Signed))
      case port                           => port
    })
    assertCodes(
      Design(signedEnable.name, Vector(signedEnable)),
      "PRTL-ASYNCHRONOUS-ENABLED-ENABLE-TYPE-MISMATCH"
    )
  }

  test("keeps clock reset enable and data input roles distinct") {
    val aliasedControls = registerModule("AliasedControls", enable = "reset")
    assertCodes(
      Design(aliasedControls.name, Vector(aliasedControls)),
      "PRTL-ASYNCHRONOUS-ENABLED-REGISTER-ROLE-ALIAS"
    )

    val aliasedData = registerModule(
      "AliasedData",
      data = "enable",
      packed = PackedBits(Literal(1), Unsigned)
    )
    assertCodes(
      Design(aliasedData.name, Vector(aliasedData)),
      "PRTL-ASYNCHRONOUS-ENABLED-REGISTER-ROLE-ALIAS"
    )
  }

  test("requires an output target and a direct input data value") {
    val inputTarget = registerModule("InputTarget", target = "data_in")
    assertCodes(
      Design(inputTarget.name, Vector(inputTarget)),
      "PRTL-ILLEGAL-INPUT-DRIVER",
      "PRTL-ASYNCHRONOUS-ENABLED-REGISTER-OUTPUT-SHAPE-UNSUPPORTED",
      "PRTL-UNDRIVEN-OUTPUT"
    )

    val outputRead = registerModule("OutputRead", data = "data_out")
    assertCodes(
      Design(outputRead.name, Vector(outputRead)),
      "PRTL-ASYNCHRONOUS-ENABLED-DATA-NOT-INPUT"
    )
  }

  test("requires exact packed width and signedness agreement") {
    val wideBase = registerModule("WideData")
    val wideInput = wideBase.copy(ports = wideBase.ports.map {
      case port if port.name == "data_in" => port.copy(dataType = PackedBits(Literal(9), Unsigned))
      case port                            => port
    })
    assertCodes(
      Design(wideInput.name, Vector(wideInput)),
      "PRTL-ASYNCHRONOUS-ENABLED-DATA-TYPE-MISMATCH"
    )

    val signedBase = registerModule("SignedData")
    val signedInput = signedBase.copy(ports = signedBase.ports.map {
      case port if port.name == "data_in" => port.copy(dataType = PackedBits(Literal(8), Signed))
      case port                            => port
    })
    assertCodes(
      Design(signedInput.name, Vector(signedInput)),
      "PRTL-ASYNCHRONOUS-ENABLED-DATA-TYPE-MISMATCH"
    )
  }

  test("requires the registered target to be the sole module output") {
    val base = registerModule("ExtraOutput")
    val top = base.copy(ports = base.ports :+ Port("aux", Output, PackedBits(Literal(8), Unsigned)))

    assertCodes(
      Design(top.name, Vector(top)),
      "PRTL-ASYNCHRONOUS-ENABLED-REGISTER-OUTPUT-SHAPE-UNSUPPORTED",
      "PRTL-UNDRIVEN-OUTPUT"
    )
  }

  test("rejects every sibling item including every older runtime process kind") {
    val base = registerModule("MixedItems")
    val process = base.items.head
    val assignment = assign("data_out", "data_in")
    val conflicts = Vector[ModuleItem](
      ContinuousAssign(Ref("data_out"), Ref("data_in")),
      CombinationalIf("p_mux", Ref("enable"), Vector(assignment), Vector(assignment)),
      ModuleInstance("helper", "EmptyHelper"),
      GenerateFor("g_loop", "i", Literal(1), Vector.empty),
      GenerateIf(
        BoolLiteral(true),
        GenerateBlock("g_true", Vector.empty),
        GenerateBlock("g_false", Vector.empty)
      ),
      GenerateCase(
        Literal(0),
        Vector(GenerateCaseChoice(0, GenerateBlock("g_zero", Vector.empty))),
        GenerateBlock("g_default", Vector.empty)
      ),
      SynchronousRegister("p_sync", Ref("clk"), Ref("reset"), assignment),
      AsynchronousRegister("p_async", Ref("clk"), Ref("reset"), assignment),
      SynchronousEnabledRegister("p_sync_enable", Ref("clk"), Ref("reset"), Ref("enable"), assignment)
    )
    val helper = ModuleDef("EmptyHelper", Vector.empty, Vector.empty, Vector.empty)

    conflicts.foreach { conflict =>
      val top = base.copy(items = Vector(process, conflict))
      assertCodes(
        Design(top.name, Vector(top, helper)),
        "PRTL-ASYNCHRONOUS-ENABLED-REGISTER-MIXED-ITEMS-UNSUPPORTED"
      )
    }
  }

  test("rejects asynchronous enabled registers nested in every generate region") {
    val process = registerModule("NestedTemplate").items.head
    val inFor = registerModule("RegisterInFor").copy(
      items = Vector(GenerateFor("g_loop", "i", Literal(1), Vector(process)))
    )
    assertCodes(Design(inFor.name, Vector(inFor)), "PRTL-PROCESS-IN-GENERATE-UNSUPPORTED")

    val inIf = registerModule("RegisterInIf").copy(items = Vector(
      GenerateIf(
        BoolLiteral(true),
        GenerateBlock("g_true", Vector(process)),
        GenerateBlock("g_false", Vector(ContinuousAssign(Ref("data_out"), Ref("data_in"))))
      )
    ))
    assertCodes(Design(inIf.name, Vector(inIf)), "PRTL-PROCESS-IN-GENERATE-UNSUPPORTED")

    val inCase = registerModule("RegisterInCase").copy(items = Vector(
      GenerateCase(
        Literal(0),
        Vector(GenerateCaseChoice(0, GenerateBlock("g_zero", Vector(process)))),
        GenerateBlock("g_default", Vector(ContinuousAssign(Ref("data_out"), Ref("data_in"))))
      )
    ))
    assertCodes(Design(inCase.name, Vector(inCase)), "PRTL-PROCESS-IN-GENERATE-UNSUPPORTED")
  }

  test("rejects multiple processes and process label failures deterministically") {
    val base = registerModule("TwoRegisters")
    val first = base.items.head.asInstanceOf[AsynchronousEnabledRegister]
    val multiple = base.copy(items = Vector(first, first.copy(label = "p_second")))
    assertCodes(
      Design(multiple.name, Vector(multiple)),
      "PRTL-MULTIPLE-ASYNCHRONOUS-ENABLED-REGISTERS-UNSUPPORTED",
      "PRTL-MULTIPLE-DRIVERS"
    )

    val duplicate = base.copy(items = Vector(first, first))
    assertCodes(
      Design(duplicate.name, Vector(duplicate)),
      "PRTL-DUPLICATE-ASYNCHRONOUS-ENABLED-REGISTER-LABEL"
    )
    val invalid = registerModule("InvalidLabel", label = "bad-label")
    assertCodes(Design(invalid.name, Vector(invalid)), "PRTL-INVALID-IDENTIFIER")
    val collision = registerModule("LabelCollision", label = "enable")
    assertCodes(Design(collision.name, Vector(collision)), "PRTL-DUPLICATE-DECLARATION")
  }

  test("orders mixed-item diagnostics independently of construction order") {
    val base = registerModule("StableAsyncEnabledDiagnostics")
    val process = base.items.head
    val conflicts = Vector[ModuleItem](
      ContinuousAssign(Ref("data_out"), Ref("data_in")),
      CombinationalIf(
        "p_mux",
        Ref("enable"),
        Vector(assign("data_out", "data_in")),
        Vector(assign("data_out", "data_in"))
      )
    )
    def diagnostics(items: Vector[ModuleItem]): Vector[Diagnostic] = {
      val top = base.copy(items = items)
      invalid(Design(top.name, Vector(top))).values
    }

    assert(diagnostics(process +: conflicts) == diagnostics(process +: conflicts.reverse))
  }

  private def registerDesign(): Design = {
    val top = registerModule("AsyncEnabledRegister")
    Design(top.name, Vector(top))
  }

  private def registerModule(
      name: String,
      label: String = "p_async_enabled_register",
      clock: String = "clk",
      reset: String = "reset",
      enable: String = "enable",
      target: String = "data_out",
      data: String = "data_in",
      packed: PackedBits = PackedBits(Literal(8), Unsigned)
  ): ModuleDef = ModuleDef(
    name,
    Vector.empty,
    Vector(
      Port("clk", Input, PackedBits(Literal(1), Unsigned)),
      Port("data_in", Input, packed),
      Port("data_out", Output, packed),
      Port("enable", Input, PackedBits(Literal(1), Unsigned)),
      Port("reset", Input, PackedBits(Literal(1), Unsigned))
    ),
    Vector(AsynchronousEnabledRegister(
      label,
      Ref(clock),
      Ref(reset),
      Ref(enable),
      assign(target, data)
    ))
  )

  private def assign(target: String, value: String): ProceduralAssign =
    ProceduralAssign(Ref(target), Ref(value))

  private def invalid(design: Design): DiagnosticSet = ParamRtlValidator.validate(design) match {
    case Left(value) => value
    case Right(_)    => fail("Expected validation to fail")
  }

  private def assertCodes(design: Design, expected: String*): Unit = {
    val diagnostics = invalid(design)
    expected.foreach(code => assert(diagnostics.codes.contains(code), diagnostics.values.mkString("\n")))
  }
}
