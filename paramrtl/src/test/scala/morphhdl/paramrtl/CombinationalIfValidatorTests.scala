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
  ModuleInstance
}
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.RtlExpr.Ref
import morphhdl.paramrtl.Signedness.{Signed, Unsigned}
import org.scalatest.funsuite.AnyFunSuite

class CombinationalIfValidatorTests extends AnyFunSuite {
  test("accepts one complete ref-only runtime combinational if") {
    assert(ParamRtlValidator.validate(muxDesign()).isRight)
  }

  test("accepts exactly equivalent parameterized packed types") {
    val width = IntegerParameter("WIDTH", 8, Vector(MinInclusive(1), MaxInclusive(32)))
    val packed = PackedBits(ParameterRef("WIDTH"), Unsigned)
    val top = muxModule("ParameterizedMux", packed = packed).copy(parameters = Vector(width))

    assert(ParamRtlValidator.validate(Design(top.name, Vector(top))).isRight)
  }

  test("requires a resolved unsigned one-bit input condition") {
    val missing = muxModule("MissingCondition", condition = "missing")
    assertCodes(Design(missing.name, Vector(missing)), "PRTL-UNRESOLVED-RTL-REFERENCE")

    val outputCondition = muxModule("OutputCondition", condition = "result")
    assertCodes(Design(outputCondition.name, Vector(outputCondition)), "PRTL-COMBINATIONAL-CONDITION-NOT-INPUT")

    val wideCondition = muxModule("WideCondition", condition = "data_true")
    assertCodes(Design(wideCondition.name, Vector(wideCondition)), "PRTL-COMBINATIONAL-CONDITION-TYPE-MISMATCH")

    val signedSelect = muxModule("SignedCondition").copy(ports = muxModule("SignedCondition").ports.map {
      case port if port.name == "select" => port.copy(dataType = PackedBits(Literal(1), Signed))
      case port                           => port
    })
    assertCodes(Design(signedSelect.name, Vector(signedSelect)), "PRTL-COMBINATIONAL-CONDITION-TYPE-MISMATCH")
  }

  test("requires both branches to be nonempty and assign the same target set") {
    val emptyFalse = muxModule("EmptyFalse", whenFalse = Vector.empty)
    assertCodes(
      Design(emptyFalse.name, Vector(emptyFalse)),
      "PRTL-EMPTY-COMBINATIONAL-BRANCH",
      "PRTL-COMBINATIONAL-BRANCH-TARGET-MISMATCH",
      "PRTL-UNDRIVEN-OUTPUT"
    )

    val packed = PackedBits(Literal(8), Unsigned)
    val mismatch = muxModule(
      "LatchGap",
      whenTrue = Vector(assign("result", "data_true"), assign("aux", "data_true")),
      whenFalse = Vector(assign("result", "data_false"))
    ).copy(ports = muxModule("LatchGap").ports :+ Port("aux", Output, packed))
    assertCodes(
      Design(mismatch.name, Vector(mismatch)),
      "PRTL-COMBINATIONAL-BRANCH-TARGET-MISMATCH",
      "PRTL-UNDRIVEN-OUTPUT"
    )
  }

  test("rejects duplicate target assignments within either branch") {
    val duplicate = muxModule(
      "DuplicateTarget",
      whenTrue = Vector(assign("result", "data_true"), assign("result", "data_false"))
    )
    assertCodes(
      Design(duplicate.name, Vector(duplicate)),
      "PRTL-DUPLICATE-PROCEDURAL-TARGET",
      "PRTL-MULTIPLE-DRIVERS"
    )
  }

  test("allows only output targets and input values, with no output reads") {
    val inputTarget = muxModule(
      "InputTarget",
      whenTrue = Vector(assign("data_true", "data_false")),
      whenFalse = Vector(assign("data_true", "data_false"))
    )
    assertCodes(Design(inputTarget.name, Vector(inputTarget)), "PRTL-ILLEGAL-INPUT-DRIVER")

    val outputRead = muxModule(
      "OutputRead",
      whenTrue = Vector(assign("result", "result"))
    )
    assertCodes(Design(outputRead.name, Vector(outputRead)), "PRTL-PROCEDURAL-OUTPUT-READ-UNSUPPORTED")
  }

  test("requires exact packed width and signedness equivalence") {
    val wideInput = muxModule("WideValue").copy(ports = muxModule("WideValue").ports.map {
      case port if port.name == "data_true" => port.copy(dataType = PackedBits(Literal(9), Unsigned))
      case port                              => port
    })
    assertCodes(Design(wideInput.name, Vector(wideInput)), "PRTL-PROCEDURAL-TYPE-MISMATCH")

    val signedInput = muxModule("SignedValue").copy(ports = muxModule("SignedValue").ports.map {
      case port if port.name == "data_false" => port.copy(dataType = PackedBits(Literal(8), Signed))
      case port                               => port
    })
    assertCodes(Design(signedInput.name, Vector(signedInput)), "PRTL-PROCEDURAL-TYPE-MISMATCH")
  }

  test("requires the process to drive every module output") {
    val packed = PackedBits(Literal(8), Unsigned)
    val top = muxModule("UndrivenAux").copy(ports = muxModule("UndrivenAux").ports :+ Port("aux", Output, packed))

    assertCodes(Design(top.name, Vector(top)), "PRTL-UNDRIVEN-OUTPUT")
  }

  test("rejects process mixing with continuous assignments and instances") {
    val continuous = muxModule("MixedContinuous").copy(
      items = muxModule("MixedContinuous").items :+ ContinuousAssign(Ref("result"), Ref("data_true"))
    )
    assertCodes(
      Design(continuous.name, Vector(continuous)),
      "PRTL-COMBINATIONAL-PROCESS-MIXED-DRIVERS-UNSUPPORTED",
      "PRTL-MULTIPLE-DRIVERS"
    )

    val instance = muxModule("MixedInstance").copy(
      items = muxModule("MixedInstance").items :+ ModuleInstance("helper", "EmptyHelper")
    )
    val helper = ModuleDef("EmptyHelper", Vector.empty, Vector.empty, Vector.empty)
    assertCodes(
      Design(instance.name, Vector(instance, helper)),
      "PRTL-COMBINATIONAL-PROCESS-MIXED-DRIVERS-UNSUPPORTED"
    )
  }

  test("rejects process mixing with parameter-controlled generate constructs") {
    val generated = GenerateIf(
      BoolLiteral(true),
      GenerateBlock("g_true", Vector(ContinuousAssign(Ref("result"), Ref("data_true")))),
      GenerateBlock("g_false", Vector(ContinuousAssign(Ref("result"), Ref("data_false"))))
    )
    val top = muxModule("MixedGenerate").copy(items = muxModule("MixedGenerate").items :+ generated)

    assertCodes(
      Design(top.name, Vector(top)),
      "PRTL-COMBINATIONAL-PROCESS-WITH-GENERATE-UNSUPPORTED"
    )

    val generatedFor = GenerateFor("g_loop", "i", Literal(1), Vector.empty)
    val withFor = muxModule("MixedGenerateFor").copy(
      items = muxModule("MixedGenerateFor").items :+ generatedFor
    )
    assertCodes(
      Design(withFor.name, Vector(withFor)),
      "PRTL-COMBINATIONAL-PROCESS-WITH-GENERATE-UNSUPPORTED"
    )

    val generatedCase = GenerateCase(
      Literal(0),
      Vector(GenerateCaseChoice(
        0,
        GenerateBlock("g_zero", Vector(ContinuousAssign(Ref("result"), Ref("data_true"))))
      )),
      GenerateBlock("g_default", Vector(ContinuousAssign(Ref("result"), Ref("data_false"))))
    )
    val withCase = muxModule("MixedGenerateCase").copy(
      items = muxModule("MixedGenerateCase").items :+ generatedCase
    )
    assertCodes(
      Design(withCase.name, Vector(withCase)),
      "PRTL-COMBINATIONAL-PROCESS-WITH-GENERATE-UNSUPPORTED"
    )
  }

  test("rejects runtime processes nested inside generate regions") {
    val process = muxModule("NestedTemplate").items.head
    val inFor = muxModule("ProcessInFor").copy(
      items = Vector(GenerateFor("g_loop", "i", Literal(1), Vector(process)))
    )
    assertCodes(Design(inFor.name, Vector(inFor)), "PRTL-PROCESS-IN-GENERATE-UNSUPPORTED")

    val inIf = muxModule("ProcessInIf").copy(items = Vector(
      GenerateIf(
        BoolLiteral(true),
        GenerateBlock("g_true", Vector(process)),
        GenerateBlock("g_false", Vector(ContinuousAssign(Ref("result"), Ref("data_false"))))
      )
    ))
    assertCodes(Design(inIf.name, Vector(inIf)), "PRTL-PROCESS-IN-GENERATE-UNSUPPORTED")

    val inCase = muxModule("ProcessInCase").copy(items = Vector(
      GenerateCase(
        Literal(0),
        Vector(GenerateCaseChoice(0, GenerateBlock("g_zero", Vector(process)))),
        GenerateBlock("g_default", Vector(ContinuousAssign(Ref("result"), Ref("data_false"))))
      )
    ))
    assertCodes(Design(inCase.name, Vector(inCase)), "PRTL-PROCESS-IN-GENERATE-UNSUPPORTED")
  }

  test("rejects multiple processes, duplicate labels, invalid labels, and declaration collisions") {
    val first = muxModule("TwoProcesses").items.head.asInstanceOf[CombinationalIf]
    val second = first.copy(label = "p_second")
    val multiple = muxModule("TwoProcesses").copy(items = Vector(first, second))
    assertCodes(Design(multiple.name, Vector(multiple)), "PRTL-MULTIPLE-COMBINATIONAL-PROCESSES-UNSUPPORTED")

    val duplicate = multiple.copy(items = Vector(first, first))
    assertCodes(Design(duplicate.name, Vector(duplicate)), "PRTL-DUPLICATE-COMBINATIONAL-PROCESS-LABEL")

    val invalid = muxModule("InvalidLabel", label = "bad-label")
    assertCodes(Design(invalid.name, Vector(invalid)), "PRTL-INVALID-IDENTIFIER")

    val collision = muxModule("LabelCollision", label = "select")
    assertCodes(Design(collision.name, Vector(collision)), "PRTL-DUPLICATE-DECLARATION")
  }

  test("orders process diagnostics independently of construction order") {
    def invalidOrder(reverse: Boolean): Vector[Diagnostic] = {
      val assignments = Vector(assign("result", "result"), assign("result", "missing"))
      val ordered = if (reverse) assignments.reverse else assignments
      val top = muxModule("StableProcessDiagnostics", whenTrue = ordered)
      invalid(Design(top.name, Vector(top))).values
    }

    assert(invalidOrder(reverse = false) == invalidOrder(reverse = true))
  }

  test("orders mixed item diagnostics independently of module construction order") {
    val process = muxModule("StableMixedDiagnostics").items.head
    val assignments = Vector(
      ContinuousAssign(Ref("result"), Ref("data_true")),
      ContinuousAssign(Ref("result"), Ref("data_false"))
    )
    def diagnostics(items: Vector[ModuleItem]): Vector[Diagnostic] = {
      val top = muxModule("StableMixedDiagnostics").copy(items = items)
      invalid(Design(top.name, Vector(top))).values
    }

    assert(diagnostics(process +: assignments) == diagnostics(process +: assignments.reverse))
  }

  private def muxDesign(): Design = {
    val top = muxModule("RuntimeMux")
    Design(top.name, Vector(top))
  }

  private def muxModule(
      name: String,
      label: String = "p_runtime_mux",
      condition: String = "select",
      whenTrue: Vector[ProceduralAssign] = Vector(assign("result", "data_true")),
      whenFalse: Vector[ProceduralAssign] = Vector(assign("result", "data_false")),
      packed: PackedBits = PackedBits(Literal(8), Unsigned)
  ): ModuleDef = ModuleDef(
    name,
    Vector.empty,
    Vector(
      Port("data_false", Input, packed),
      Port("data_true", Input, packed),
      Port("result", Output, packed),
      Port("select", Input, PackedBits(Literal(1), Unsigned))
    ),
    Vector(CombinationalIf(label, Ref(condition), whenTrue, whenFalse))
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
