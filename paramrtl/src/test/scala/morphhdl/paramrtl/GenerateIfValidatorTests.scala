package morphhdl.paramrtl

import morphhdl.paramrtl.BoolExpr.{And, Literal => BoolLiteral, Not, Or, ParameterRef => BoolParameterRef}
import morphhdl.paramrtl.IntExpr.{Literal, ParameterRef}
import morphhdl.paramrtl.ModuleItem.{ContinuousAssign, GenerateFor, GenerateIf, ModuleInstance}
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.RtlExpr.Ref
import morphhdl.paramrtl.Signedness.Unsigned
import org.scalatest.funsuite.AnyFunSuite

class GenerateIfValidatorTests extends AnyFunSuite {
  test("validates both mutually exclusive assignment branches as one driver") {
    val validated = valid(conditionalAssignDesign())
    assert(validated.moduleFacts("ConditionalWire").instanceFacts.isEmpty)
  }

  test("validates instances and hierarchy in both branches") {
    val child = passthrough("Leaf")
    def instance(name: String): ModuleInstance = ModuleInstance(
      name,
      "Leaf",
      portConnections = Vector(
        PortConnection("din", Ref("din")),
        PortConnection("dout", Ref("dout"))
      )
    )
    val top = module(
      name = "ConditionalHierarchy",
      items = Vector(
        GenerateIf(
          BoolParameterRef("ENABLE"),
          GenerateBlock("g_enabled", Vector(instance("enabled_leaf"))),
          GenerateBlock("g_disabled", Vector(instance("disabled_leaf")))
        )
      ),
      booleanParameters = Vector(BooleanParameter("ENABLE", default = true))
    )
    val validated = valid(Design(top.name, Vector(top, child)))

    assert(
      validated.moduleFacts(top.name).instanceFacts.keySet ==
        Set("g_enabled.enabled_leaf", "g_disabled.disabled_leaf")
    )
  }

  test("validates an inactive branch even when the Boolean default selects true") {
    val generate = conditionalItems(BoolParameterRef("ENABLE")).head.asInstanceOf[GenerateIf]
    val inactiveBody = Vector(
      ModuleInstance("missing", "UnknownModule"),
      ContinuousAssign(Ref("dout"), Ref("din"))
    )
    val top = module(
      name = "InactiveBranchFailure",
      items = Vector(generate.copy(whenFalse = GenerateBlock("g_disabled", inactiveBody))),
      booleanParameters = Vector(BooleanParameter("ENABLE", default = true))
    )

    assertCodes(Design(top.name, Vector(top)), "PRTL-UNRESOLVED-INSTANCE-MODULE")
  }

  test("discovers dependency cycles through an inactive branch") {
    val generate = conditionalItems(BoolParameterRef("ENABLE")).head.asInstanceOf[GenerateIf]
    val inactiveBody = Vector(
      ModuleInstance("cycle_entry", "CycleA"),
      ContinuousAssign(Ref("dout"), Ref("din"))
    )
    val top = module(
      name = "InactiveBranchCycle",
      items = Vector(generate.copy(whenFalse = GenerateBlock("g_disabled", inactiveBody))),
      booleanParameters = Vector(BooleanParameter("ENABLE", default = true))
    )
    val cycleA = ModuleDef("CycleA", Vector.empty, Vector.empty, Vector(ModuleInstance("b", "CycleB")))
    val cycleB = ModuleDef("CycleB", Vector.empty, Vector.empty, Vector(ModuleInstance("a", "CycleA")))

    assertCodes(
      Design(top.name, Vector(top, cycleA, cycleB)),
      "PRTL-MODULE-INSTANTIATION-CYCLE"
    )
  }

  test("requires a typed Boolean parameter and rejects integer aliases") {
    val integerAlias = module(
      name = "IntegerAlias",
      items = conditionalItems(BoolParameterRef("ENABLE")),
      parameters = Vector(
        IntegerParameter(
          "ENABLE",
          default = 1,
          constraints = Vector(IntConstraint.MinInclusive(0), IntConstraint.MaxInclusive(1))
        )
      )
    )
    assertCodes(Design(integerAlias.name, Vector(integerAlias)), "PRTL-UNRESOLVED-BOOLEAN-PARAMETER")

    val missing = module(name = "Missing", items = conditionalItems(BoolParameterRef("UNKNOWN")))
    assertCodes(Design(missing.name, Vector(missing)), "PRTL-UNRESOLVED-BOOLEAN-PARAMETER")
  }

  test("validates Boolean parameter names, duplicates, and integer-name collisions") {
    val duplicate = module(
      name = "DuplicateBoolean",
      items = conditionalItems(BoolParameterRef("ENABLE")),
      booleanParameters = Vector(
        BooleanParameter("ENABLE", default = true),
        BooleanParameter("ENABLE", default = false)
      )
    )
    assertCodes(Design(duplicate.name, Vector(duplicate)), "PRTL-DUPLICATE-BOOLEAN-PARAMETER")

    val collision = module(
      name = "BooleanIntegerCollision",
      items = conditionalItems(BoolParameterRef("ENABLE")),
      parameters = Vector(IntegerParameter("ENABLE", default = 1)),
      booleanParameters = Vector(BooleanParameter("ENABLE", default = true))
    )
    assertCodes(Design(collision.name, Vector(collision)), "PRTL-DUPLICATE-DECLARATION")

    val invalidName = module(
      name = "InvalidBooleanName",
      items = conditionalItems(BoolParameterRef("bad-name")),
      booleanParameters = Vector(BooleanParameter("bad-name", default = true))
    )
    assertCodes(Design(invalidName.name, Vector(invalidName)), "PRTL-INVALID-IDENTIFIER")
  }

  test("validates every operand reference even when a literal determines the result") {
    val conditions = Vector(
      And(BoolLiteral(false), BoolParameterRef("MISSING")),
      Or(BoolLiteral(true), BoolParameterRef("MISSING"))
    )
    conditions.foreach { condition =>
      val top = module(name = "NoShortCircuit", items = conditionalItems(condition))
      assertCodes(Design(top.name, Vector(top)), "PRTL-UNRESOLVED-BOOLEAN-PARAMETER")
    }
  }

  test("requires each legal branch to drive every output exactly once") {
    val base = conditionalAssignDesign().modules.head
    val generate = base.items.head.asInstanceOf[GenerateIf]
    val missingFalse = base.copy(items = Vector(generate.copy(whenFalse = GenerateBlock("g_disabled", Vector.empty))))
    assertCodes(Design(base.name, Vector(missingFalse)), "PRTL-UNDRIVEN-OUTPUT")

    val twice = generate.whenTrue.copy(body = generate.whenTrue.body ++ generate.whenTrue.body)
    val duplicateTrue = base.copy(items = Vector(generate.copy(whenTrue = twice)))
    assertCodes(Design(base.name, Vector(duplicateTrue)), "PRTL-MULTIPLE-DRIVERS")

    val unconditional = base.copy(items = base.items :+ ContinuousAssign(Ref("dout"), Ref("din")))
    assertCodes(Design(base.name, Vector(unconditional)), "PRTL-MULTIPLE-DRIVERS")
  }

  test("rejects multiple top-level generate-if constructs") {
    val first = conditionalItems(BoolLiteral(true)).head.asInstanceOf[GenerateIf]
    val second = first.copy(
      whenTrue = first.whenTrue.copy(label = "g_second_true"),
      whenFalse = first.whenFalse.copy(label = "g_second_false")
    )
    val top = module(name = "TwoIfs", items = Vector(first, second))

    assertCodes(Design(top.name, Vector(top)), "PRTL-MULTIPLE-GENERATE-IF-UNSUPPORTED")
  }

  test("rejects nested if/for combinations and unsupported generate-for bodies") {
    val innerIf = conditionalItems(BoolLiteral(true)).head
    val ifInFor = module(
      name = "IfInFor",
      items = Vector(GenerateFor("g_loop", "i", Literal(1), Vector(innerIf)))
    )
    assertCodes(Design(ifInFor.name, Vector(ifInFor)), "PRTL-NESTED-GENERATE-UNSUPPORTED")

    val forInIf = module(
      name = "ForInIf",
      items = Vector(
        GenerateIf(
          BoolLiteral(true),
          GenerateBlock("g_true", Vector(GenerateFor("g_loop", "i", Literal(1), Vector.empty))),
          GenerateBlock("g_false", Vector(ContinuousAssign(Ref("dout"), Ref("din"))))
        )
      )
    )
    assertCodes(Design(forInIf.name, Vector(forInIf)), "PRTL-NESTED-GENERATE-UNSUPPORTED")
  }

  test("validates branch labels, duplicate labels, and declaration collisions") {
    val base = conditionalAssignDesign().modules.head
    val generate = base.items.head.asInstanceOf[GenerateIf]
    val duplicateLabels = base.copy(
      items = Vector(generate.copy(whenFalse = generate.whenFalse.copy(label = "g_enabled")))
    )
    assertCodes(Design(base.name, Vector(duplicateLabels)), "PRTL-DUPLICATE-GENERATE-LABEL")

    val invalidLabel = base.copy(items = Vector(generate.copy(whenTrue = generate.whenTrue.copy(label = "bad-label"))))
    assertCodes(Design(base.name, Vector(invalidLabel)), "PRTL-INVALID-IDENTIFIER")

    val collision = base.copy(items = Vector(generate.copy(whenTrue = generate.whenTrue.copy(label = "din"))))
    assertCodes(Design(base.name, Vector(collision)), "PRTL-DUPLICATE-DECLARATION")
  }

  test("rejects Boolean names in public integer-width expressions") {
    val top = module(
      name = "ConditionalPort",
      items = conditionalItems(BoolParameterRef("ENABLE")),
      booleanParameters = Vector(BooleanParameter("ENABLE", default = true)),
      width = ParameterRef("ENABLE")
    )
    assertCodes(
      Design(top.name, Vector(top)),
      "PRTL-UNRESOLVED-PARAMETER",
      "PRTL-PUBLIC-PORT-CONDITIONALITY-UNSUPPORTED"
    )
  }

  private def conditionalAssignDesign(): Design = {
    val top = module(
      name = "ConditionalWire",
      items = conditionalItems(
        Or(BoolParameterRef("ENABLE"), Not(BoolParameterRef("BYPASS")))
      ),
      booleanParameters = Vector(
        BooleanParameter("ENABLE", default = true),
        BooleanParameter("BYPASS", default = false)
      )
    )
    Design(top.name, Vector(top))
  }

  private def conditionalItems(condition: BoolExpr): Vector[ModuleItem] = Vector(
    GenerateIf(
      condition,
      GenerateBlock("g_enabled", Vector(ContinuousAssign(Ref("dout"), Ref("din")))),
      GenerateBlock("g_disabled", Vector(ContinuousAssign(Ref("dout"), Ref("din"))))
    )
  )

  private def passthrough(name: String): ModuleDef =
    module(name, Vector(ContinuousAssign(Ref("dout"), Ref("din"))))

  private def module(
      name: String,
      items: Vector[ModuleItem],
      parameters: Vector[IntegerParameter] = Vector.empty,
      booleanParameters: Vector[BooleanParameter] = Vector.empty,
      width: IntExpr = Literal(8)
  ): ModuleDef = {
    val packed = PackedBits(width, Unsigned)
    ModuleDef(
      name,
      parameters,
      Vector(Port("din", Input, packed), Port("dout", Output, packed)),
      items,
      booleanParameters = booleanParameters
    )
  }

  private def valid(design: Design): ValidatedDesign = ParamRtlValidator.validate(design) match {
    case Right(value)      => value
    case Left(diagnostics) => fail(diagnostics.values.mkString("\n"))
  }

  private def invalid(design: Design): DiagnosticSet = ParamRtlValidator.validate(design) match {
    case Left(value) => value
    case Right(_)    => fail("Expected validation to fail")
  }

  private def assertCodes(design: Design, expected: String*): Unit = {
    val diagnostics = invalid(design)
    expected.foreach(code => assert(diagnostics.codes.contains(code), diagnostics.values.mkString("\n")))
  }
}
