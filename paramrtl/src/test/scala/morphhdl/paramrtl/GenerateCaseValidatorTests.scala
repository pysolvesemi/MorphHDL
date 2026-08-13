package morphhdl.paramrtl

import morphhdl.paramrtl.BoolExpr.{Literal => BoolLiteral, LocalParameterRef => BoolLocalParameterRef}
import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}
import morphhdl.paramrtl.IntExpr.{
  Divide,
  GenerateIndexRef,
  Literal,
  LocalParameterRef,
  ParameterRef,
  Select
}
import morphhdl.paramrtl.ModuleItem.{ContinuousAssign, GenerateCase, GenerateFor, GenerateIf, ModuleInstance}
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.RtlExpr.Ref
import morphhdl.paramrtl.Signedness.Unsigned
import org.scalatest.funsuite.AnyFunSuite

class GenerateCaseValidatorTests extends AnyFunSuite {
  test("accepts unique literal choices plus a mandatory default") {
    val top = caseModule(
      "CaseWire",
      selector = ParameterRef("MODE"),
      choices = Vector(choice(1, "g_one"), choice(-1, "g_minus_one")),
      default = block("g_default"),
      parameters = Vector(mode(default = 2, minimum = -1, maximum = 2))
    )

    assert(ParamRtlValidator.validate(Design(top.name, Vector(top))).isRight)
  }

  test("validates instances and discovers hierarchy dependencies through every branch") {
    val child = passthrough("Leaf")
    def leafBlock(label: String): GenerateBlock = GenerateBlock(
      label,
      Vector(
        ModuleInstance(
          "leaf",
          child.name,
          portConnections = Vector(
            PortConnection("din", Ref("din")),
            PortConnection("dout", Ref("dout"))
          )
        )
      )
    )
    val top = caseModule(
      "CaseHierarchy",
      ParameterRef("MODE"),
      Vector(
        GenerateCaseChoice(0, leafBlock("g_zero")),
        GenerateCaseChoice(1, leafBlock("g_one"))
      ),
      leafBlock("g_default"),
      parameters = Vector(mode(default = 0, minimum = 0, maximum = 2))
    )

    val validated = valid(Design(top.name, Vector(top, child)))
    assert(
      validated.moduleFacts(top.name).instanceFacts.keySet ==
        Set("g_zero.leaf", "g_one.leaf", "g_default.leaf")
    )
  }

  test("validates inactive choices and default even when the selector default picks a valid choice") {
    val invalidChoice = GenerateBlock(
      "g_invalid",
      Vector(
        ModuleInstance("missing", "UnknownModule"),
        ContinuousAssign(Ref("dout"), Ref("din"))
      )
    )
    val invalidDefault = GenerateBlock(
      "g_default",
      Vector(
        ModuleInstance("also_missing", "AlsoUnknown"),
        ContinuousAssign(Ref("dout"), Ref("din"))
      )
    )
    val top = caseModule(
      "InactiveCaseFailure",
      ParameterRef("MODE"),
      Vector(GenerateCaseChoice(0, block("g_selected")), GenerateCaseChoice(1, invalidChoice)),
      invalidDefault,
      parameters = Vector(mode(default = 0, minimum = 0, maximum = 1))
    )

    val diagnostics = invalid(Design(top.name, Vector(top)))
    assert(diagnostics.values.count(_.code == "PRTL-UNRESOLVED-INSTANCE-MODULE") == 2)
  }

  test("discovers module-instantiation cycles through an inactive choice") {
    val cycleA = ModuleDef("CycleA", Vector.empty, Vector.empty, Vector(ModuleInstance("b", "CycleB")))
    val cycleB = ModuleDef("CycleB", Vector.empty, Vector.empty, Vector(ModuleInstance("a", "CycleA")))
    val inactive = GenerateBlock(
      "g_cycle",
      Vector(
        ModuleInstance("entry", "CycleA"),
        ContinuousAssign(Ref("dout"), Ref("din"))
      )
    )
    val top = caseModule(
      "InactiveCaseCycle",
      Literal(0),
      Vector(GenerateCaseChoice(0, block("g_selected")), GenerateCaseChoice(1, inactive)),
      block("g_default")
    )

    assertCodes(
      Design(top.name, Vector(top, cycleA, cycleB)),
      "PRTL-MODULE-INSTANTIATION-CYCLE"
    )
  }

  test("accepts public, local, and Boolean-local select provenance in the selector") {
    val selector = Select(
      BoolLocalParameterRef("PICK_LOCAL"),
      LocalParameterRef("LOCAL_MODE"),
      ParameterRef("MODE")
    )
    val top = caseModule(
      "SelectorProvenance",
      selector,
      Vector(choice(1, "g_one"), choice(2, "g_two")),
      block("g_default"),
      parameters = Vector(mode(default = 1, minimum = 0, maximum = 2)),
      localParameters = Vector(IntegerLocalParameter("LOCAL_MODE", Literal(2))),
      booleanLocalParameters = Vector(BooleanLocalParameter("PICK_LOCAL", BoolLiteral(true)))
    )

    assert(ParamRtlValidator.validate(Design(top.name, Vector(top))).isRight)
  }

  test("rejects wrong-kind, unresolved, out-of-scope, and unsafe selector operands") {
    val wrongKind = caseModule(
      "WrongKindSelector",
      LocalParameterRef("BOOL_LOCAL"),
      Vector(choice(0, "g_zero")),
      block("g_default"),
      booleanLocalParameters = Vector(BooleanLocalParameter("BOOL_LOCAL", BoolLiteral(false)))
    )
    assertCodes(Design(wrongKind.name, Vector(wrongKind)), "PRTL-LOCAL-PARAMETER-KIND-MISMATCH")

    val missing = caseModule(
      "MissingSelector",
      ParameterRef("MISSING"),
      Vector(choice(0, "g_zero")),
      block("g_default")
    )
    assertCodes(Design(missing.name, Vector(missing)), "PRTL-UNRESOLVED-PARAMETER")

    val indexed = caseModule(
      "IndexedSelector",
      GenerateIndexRef("i"),
      Vector(choice(0, "g_zero")),
      block("g_default")
    )
    assertCodes(Design(indexed.name, Vector(indexed)), "PRTL-GENERATE-INDEX-OUT-OF-SCOPE")

    val unsafe = caseModule(
      "UnsafeSelector",
      Divide(Literal(8), ParameterRef("DIVISOR")),
      Vector(choice(0, "g_zero")),
      block("g_default"),
      parameters = Vector(mode("DIVISOR", default = 1, minimum = -1, maximum = 1))
    )
    assertCodes(Design(unsafe.name, Vector(unsafe)), "PRTL-DIVISOR-MAY-BE-ZERO")
  }

  test("rejects duplicate literals, duplicate labels, invalid labels, and empty choices") {
    val duplicateValues = caseModule(
      "DuplicateValues",
      Literal(0),
      Vector(choice(1, "g_first"), choice(1, "g_second")),
      block("g_default")
    )
    assertCodes(Design(duplicateValues.name, Vector(duplicateValues)), "PRTL-DUPLICATE-GENERATE-CASE-VALUE")

    val duplicateLabels = caseModule(
      "DuplicateLabels",
      Literal(0),
      Vector(choice(0, "g_shared"), choice(1, "g_shared")),
      block("g_default")
    )
    assertCodes(Design(duplicateLabels.name, Vector(duplicateLabels)), "PRTL-DUPLICATE-GENERATE-LABEL")

    val invalidLabel = caseModule(
      "InvalidLabel",
      Literal(0),
      Vector(choice(0, "bad-label")),
      block("g_default")
    )
    assertCodes(Design(invalidLabel.name, Vector(invalidLabel)), "PRTL-INVALID-IDENTIFIER")

    val empty = caseModule("EmptyChoices", Literal(0), Vector.empty, block("g_default"))
    assertCodes(Design(empty.name, Vector(empty)), "PRTL-GENERATE-CASE-NO-CHOICES")
  }

  test("requires unconditional plus every choice and default to drive each output exactly once") {
    val base = caseModule(
      "ExactCaseDrivers",
      Literal(0),
      Vector(choice(0, "g_zero"), choice(1, "g_one")),
      block("g_default")
    )
    val generate = base.items.head.asInstanceOf[GenerateCase]

    val missingChoice = base.copy(items = Vector(generate.copy(
      choices = Vector(GenerateCaseChoice(0, GenerateBlock("g_zero", Vector.empty)), choice(1, "g_one"))
    )))
    assertCodes(Design(base.name, Vector(missingChoice)), "PRTL-UNDRIVEN-OUTPUT")

    val missingDefault = base.copy(items = Vector(generate.copy(default = GenerateBlock("g_default", Vector.empty))))
    assertCodes(Design(base.name, Vector(missingDefault)), "PRTL-UNDRIVEN-OUTPUT")

    val doubled = block("g_zero").copy(body = block("g_zero").body ++ block("g_zero").body)
    val duplicateChoiceDriver = base.copy(items = Vector(generate.copy(
      choices = Vector(GenerateCaseChoice(0, doubled), choice(1, "g_one"))
    )))
    assertCodes(Design(base.name, Vector(duplicateChoiceDriver)), "PRTL-MULTIPLE-DRIVERS")

    val unconditional = base.copy(items = base.items :+ ContinuousAssign(Ref("dout"), Ref("din")))
    assertCodes(Design(base.name, Vector(unconditional)), "PRTL-MULTIPLE-DRIVERS")
  }

  test("allows the same instance name across branches but rejects duplicates within one branch") {
    val child = passthrough("SharedLeaf")
    def instanceBlock(label: String, duplicate: Boolean): GenerateBlock = {
      val instance = ModuleInstance(
        "leaf",
        child.name,
        portConnections = Vector(
          PortConnection("din", Ref("din")),
          PortConnection("dout", Ref("dout"))
        )
      )
      GenerateBlock(label, if (duplicate) Vector(instance, instance) else Vector(instance))
    }

    val validTop = caseModule(
      "SharedNames",
      Literal(0),
      Vector(GenerateCaseChoice(0, instanceBlock("g_zero", duplicate = false))),
      instanceBlock("g_default", duplicate = false)
    )
    assert(ParamRtlValidator.validate(Design(validTop.name, Vector(validTop, child))).isRight)

    val invalidTop = validTop.copy(items = Vector(
      GenerateCase(
        Literal(0),
        Vector(GenerateCaseChoice(0, instanceBlock("g_zero", duplicate = true))),
        instanceBlock("g_default", duplicate = false)
      )
    ))
    assertCodes(Design(invalidTop.name, Vector(invalidTop, child)), "PRTL-DUPLICATE-INSTANCE")
  }

  test("rejects a second case, an if sibling, and nested case/if/for combinations") {
    val first = GenerateCase(Literal(0), Vector(choice(0, "g_zero")), block("g_default"))
    val second = GenerateCase(Literal(1), Vector(choice(1, "g_one")), block("g_other_default"))
    val twoCases = caseModule("TwoCases", Literal(0), Vector(choice(0, "unused")), block("unused_default"))
      .copy(items = Vector(first, second))
    assertCodes(Design(twoCases.name, Vector(twoCases)), "PRTL-MULTIPLE-GENERATE-CASE-UNSUPPORTED")

    val siblingIf = GenerateIf(BoolLiteral(true), block("g_true"), block("g_false"))
    val mixed = twoCases.copy(name = "MixedConditional", items = Vector(first, siblingIf))
    assertCodes(Design(mixed.name, Vector(mixed)), "PRTL-MULTIPLE-CONDITIONAL-GENERATE-UNSUPPORTED")

    val caseInFor = twoCases.copy(
      name = "CaseInFor",
      items = Vector(GenerateFor("g_loop", "i", Literal(1), Vector(first)))
    )
    assertCodes(Design(caseInFor.name, Vector(caseInFor)), "PRTL-NESTED-GENERATE-UNSUPPORTED")

    val ifInCase = twoCases.copy(
      name = "IfInCase",
      items = Vector(first.copy(choices = Vector(
        GenerateCaseChoice(0, GenerateBlock("g_zero", Vector(siblingIf)))
      )))
    )
    assertCodes(Design(ifInCase.name, Vector(ifInCase)), "PRTL-NESTED-GENERATE-UNSUPPORTED")

    val forInCase = twoCases.copy(
      name = "ForInCase",
      items = Vector(first.copy(default = GenerateBlock(
        "g_default",
        Vector(GenerateFor("g_loop", "i", Literal(1), Vector.empty))
      )))
    )
    assertCodes(Design(forInCase.name, Vector(forInCase)), "PRTL-NESTED-GENERATE-UNSUPPORTED")
  }

  test("orders duplicate-case diagnostics independently of construction order") {
    def design(choices: Vector[GenerateCaseChoice]): Design = {
      val top = caseModule("StableDiagnostics", Literal(0), choices, block("g_default"))
      Design(top.name, Vector(top))
    }
    val choices = Vector(choice(2, "g_z"), choice(-1, "g_a"), choice(2, "g_b"))

    assert(invalid(design(choices)).values == invalid(design(choices.reverse)).values)
  }

  private def choice(value: BigInt, label: String): GenerateCaseChoice =
    GenerateCaseChoice(value, block(label))

  private def block(label: String): GenerateBlock =
    GenerateBlock(label, Vector(ContinuousAssign(Ref("dout"), Ref("din"))))

  private def mode(
      name: String = "MODE",
      default: BigInt,
      minimum: BigInt,
      maximum: BigInt
  ): IntegerParameter =
    IntegerParameter(name, default, Vector(MinInclusive(minimum), MaxInclusive(maximum)))

  private def passthrough(name: String): ModuleDef =
    caseModule(name, Literal(0), Vector.empty, block("unused")).copy(
      items = Vector(ContinuousAssign(Ref("dout"), Ref("din")))
    )

  private def caseModule(
      name: String,
      selector: IntExpr,
      choices: Vector[GenerateCaseChoice],
      default: GenerateBlock,
      parameters: Vector[IntegerParameter] = Vector.empty,
      localParameters: Vector[IntegerLocalParameter] = Vector.empty,
      booleanLocalParameters: Vector[BooleanLocalParameter] = Vector.empty
  ): ModuleDef = {
    val packed = PackedBits(Literal(8), Unsigned)
    ModuleDef(
      name,
      parameters,
      Vector(Port("din", Input, packed), Port("dout", Output, packed)),
      Vector(GenerateCase(selector, choices, default)),
      localParameters = localParameters,
      booleanLocalParameters = booleanLocalParameters
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
