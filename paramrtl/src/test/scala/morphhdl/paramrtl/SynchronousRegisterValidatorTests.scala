package morphhdl.paramrtl

import morphhdl.paramrtl.BoolExpr.{Literal => BoolLiteral}
import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}
import morphhdl.paramrtl.IntExpr.{Literal, ParameterRef}
import morphhdl.paramrtl.ModuleItem.{
  CombinationalIf,
  ContinuousAssign,
  GenerateCase,
  GenerateFor,
  GenerateIf,
  ModuleInstance,
  SynchronousRegister
}
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.RtlExpr.Ref
import morphhdl.paramrtl.Signedness.{Signed, Unsigned}
import org.scalatest.funsuite.AnyFunSuite

class SynchronousRegisterValidatorTests extends AnyFunSuite {
  test("accepts one complete rising-edge synchronous-reset register") {
    assert(ParamRtlValidator.validate(registerDesign()).isRight)
  }

  test("accepts exactly equivalent parameterized packed data and output types") {
    val width = IntegerParameter("WIDTH", 8, Vector(MinInclusive(1), MaxInclusive(32)))
    val packed = PackedBits(ParameterRef("WIDTH"), Unsigned)
    val top = registerModule("ParameterizedRegister", packed = packed).copy(parameters = Vector(width))

    assert(ParamRtlValidator.validate(Design(top.name, Vector(top))).isRight)
  }

  test("allows unrelated extra inputs outside the three bounded process roles") {
    val top = registerModule("RegisterWithUnusedInput").copy(
      ports = registerModule("RegisterWithUnusedInput").ports :+
        Port("unused", Input, PackedBits(Literal(3), Unsigned))
    )

    assert(ParamRtlValidator.validate(Design(top.name, Vector(top))).isRight)
  }

  test("requires resolved distinct unsigned one-bit input clock and reset controls") {
    val missingClock = registerModule("MissingClock", clock = "missing")
    assertCodes(Design(missingClock.name, Vector(missingClock)), "PRTL-UNRESOLVED-RTL-REFERENCE")

    val missingReset = registerModule("MissingReset", reset = "missing")
    assertCodes(Design(missingReset.name, Vector(missingReset)), "PRTL-UNRESOLVED-RTL-REFERENCE")

    val outputClock = registerModule("OutputClock", clock = "data_out")
    assertCodes(
      Design(outputClock.name, Vector(outputClock)),
      "PRTL-SYNCHRONOUS-CLOCK-NOT-INPUT",
      "PRTL-SYNCHRONOUS-CLOCK-TYPE-MISMATCH"
    )

    val wideReset = registerModule("WideReset", reset = "data_in")
    assertCodes(Design(wideReset.name, Vector(wideReset)), "PRTL-SYNCHRONOUS-RESET-TYPE-MISMATCH")

    val signedClock = registerModule("SignedClock").copy(
      ports = registerModule("SignedClock").ports.map {
        case port if port.name == "clk" => port.copy(dataType = PackedBits(Literal(1), Signed))
        case port                        => port
      }
    )
    assertCodes(Design(signedClock.name, Vector(signedClock)), "PRTL-SYNCHRONOUS-CLOCK-TYPE-MISMATCH")

    val aliasedControls = registerModule("AliasedControls", reset = "clk")
    assertCodes(Design(aliasedControls.name, Vector(aliasedControls)), "PRTL-SYNCHRONOUS-REGISTER-ROLE-ALIAS")
  }

  test("keeps clock reset and data input roles distinct") {
    val bit = PackedBits(Literal(1), Unsigned)
    val aliasedData = registerModule("AliasedData", data = "clk", packed = bit)

    assertCodes(Design(aliasedData.name, Vector(aliasedData)), "PRTL-SYNCHRONOUS-REGISTER-ROLE-ALIAS")
  }

  test("requires an output target and input data value") {
    val inputTarget = registerModule("InputTarget", target = "data_in")
    assertCodes(
      Design(inputTarget.name, Vector(inputTarget)),
      "PRTL-ILLEGAL-INPUT-DRIVER",
      "PRTL-SYNCHRONOUS-REGISTER-OUTPUT-SHAPE-UNSUPPORTED",
      "PRTL-UNDRIVEN-OUTPUT"
    )

    val outputRead = registerModule("OutputRead", data = "data_out")
    assertCodes(Design(outputRead.name, Vector(outputRead)), "PRTL-SYNCHRONOUS-DATA-NOT-INPUT")

    val missingData = registerModule("MissingData", data = "missing")
    assertCodes(Design(missingData.name, Vector(missingData)), "PRTL-UNRESOLVED-RTL-REFERENCE")
  }

  test("requires exact packed width and signedness agreement") {
    val wideInput = registerModule("WideData").copy(
      ports = registerModule("WideData").ports.map {
        case port if port.name == "data_in" => port.copy(dataType = PackedBits(Literal(9), Unsigned))
        case port                            => port
      }
    )
    assertCodes(Design(wideInput.name, Vector(wideInput)), "PRTL-SYNCHRONOUS-DATA-TYPE-MISMATCH")

    val signedInput = registerModule("SignedData").copy(
      ports = registerModule("SignedData").ports.map {
        case port if port.name == "data_in" => port.copy(dataType = PackedBits(Literal(8), Signed))
        case port                            => port
      }
    )
    assertCodes(Design(signedInput.name, Vector(signedInput)), "PRTL-SYNCHRONOUS-DATA-TYPE-MISMATCH")
  }

  test("requires the registered target to be the sole module output") {
    val packed = PackedBits(Literal(8), Unsigned)
    val top = registerModule("ExtraOutput").copy(
      ports = registerModule("ExtraOutput").ports :+ Port("aux", Output, packed)
    )

    assertCodes(
      Design(top.name, Vector(top)),
      "PRTL-SYNCHRONOUS-REGISTER-OUTPUT-SHAPE-UNSUPPORTED",
      "PRTL-UNDRIVEN-OUTPUT"
    )
  }

  test("rejects continuous combinational hierarchy and generate siblings") {
    val base = registerModule("MixedItems")
    val process = base.items.head
    val combinational = CombinationalIf(
      "p_mux",
      Ref("reset"),
      Vector(assign("data_out", "data_in")),
      Vector(assign("data_out", "data_in"))
    )
    val generateIf = GenerateIf(
      BoolLiteral(true),
      GenerateBlock("g_true", Vector(ContinuousAssign(Ref("data_out"), Ref("data_in")))),
      GenerateBlock("g_false", Vector(ContinuousAssign(Ref("data_out"), Ref("data_in"))))
    )
    val generateCase = GenerateCase(
      Literal(0),
      Vector(GenerateCaseChoice(0, GenerateBlock("g_zero", Vector.empty))),
      GenerateBlock("g_default", Vector.empty)
    )
    val conflicts = Vector[ModuleItem](
      ContinuousAssign(Ref("data_out"), Ref("data_in")),
      combinational,
      ModuleInstance("helper", "EmptyHelper"),
      GenerateFor("g_loop", "i", Literal(1), Vector.empty),
      generateIf,
      generateCase
    )
    val helper = ModuleDef("EmptyHelper", Vector.empty, Vector.empty, Vector.empty)

    conflicts.foreach { conflict =>
      val top = base.copy(items = Vector(process, conflict))
      assertCodes(
        Design(top.name, Vector(top, helper)),
        "PRTL-SYNCHRONOUS-REGISTER-MIXED-ITEMS-UNSUPPORTED"
      )
    }
  }

  test("rejects synchronous registers nested in every generate region") {
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

  test("rejects multiple processes with either shared or distinct control ports") {
    val base = registerModule("TwoRegisters")
    val first = base.items.head.asInstanceOf[SynchronousRegister]
    val sameControls = first.copy(label = "p_second")
    val multiple = base.copy(items = Vector(first, sameControls))
    assertCodes(
      Design(multiple.name, Vector(multiple)),
      "PRTL-MULTIPLE-SYNCHRONOUS-REGISTERS-UNSUPPORTED",
      "PRTL-MULTIPLE-DRIVERS"
    )

    val controls = PackedBits(Literal(1), Unsigned)
    val distinctControls = base.copy(
      ports = base.ports ++ Vector(Port("clk2", Input, controls), Port("reset2", Input, controls)),
      items = Vector(first, first.copy(label = "p_second", clock = Ref("clk2"), reset = Ref("reset2")))
    )
    assertCodes(
      Design(distinctControls.name, Vector(distinctControls)),
      "PRTL-MULTIPLE-SYNCHRONOUS-REGISTERS-UNSUPPORTED"
    )
  }

  test("rejects duplicate invalid and declaration-colliding process labels") {
    val base = registerModule("LabelChecks")
    val first = base.items.head.asInstanceOf[SynchronousRegister]
    val duplicate = base.copy(items = Vector(first, first))
    assertCodes(
      Design(duplicate.name, Vector(duplicate)),
      "PRTL-DUPLICATE-SYNCHRONOUS-REGISTER-LABEL"
    )

    val invalid = registerModule("InvalidLabel", label = "bad-label")
    assertCodes(Design(invalid.name, Vector(invalid)), "PRTL-INVALID-IDENTIFIER")

    val collision = registerModule("LabelCollision", label = "clk")
    assertCodes(Design(collision.name, Vector(collision)), "PRTL-DUPLICATE-DECLARATION")
  }

  test("orders mixed-item diagnostics independently of construction order") {
    val base = registerModule("StableRegisterDiagnostics")
    val process = base.items.head
    val conflicts = Vector[ModuleItem](
      ContinuousAssign(Ref("data_out"), Ref("data_in")),
      CombinationalIf(
        "p_mux",
        Ref("reset"),
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
    val top = registerModule("SyncRegister")
    Design(top.name, Vector(top))
  }

  private def registerModule(
      name: String,
      label: String = "p_register",
      clock: String = "clk",
      reset: String = "reset",
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
      Port("reset", Input, PackedBits(Literal(1), Unsigned))
    ),
    Vector(SynchronousRegister(label, Ref(clock), Ref(reset), assign(target, data)))
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
