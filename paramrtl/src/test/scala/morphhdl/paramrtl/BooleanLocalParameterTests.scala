package morphhdl.paramrtl

import morphhdl.paramrtl.BoolExpr.{
  And,
  GreaterThan,
  Literal => BoolLiteral,
  LocalParameterRef => BoolLocalParameterRef,
  Not,
  Or,
  ParameterRef => BoolParameterRef
}
import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}
import morphhdl.paramrtl.IntExpr.{Divide, Literal, LocalParameterRef, Select}
import morphhdl.paramrtl.ModuleItem.{ContinuousAssign, ModuleInstance}
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.RtlExpr.Ref
import morphhdl.paramrtl.Signedness.Unsigned
import org.scalatest.funsuite.AnyFunSuite

class BooleanLocalParameterTests extends AnyFunSuite {
  test("preserves old positional ModuleDef and expression-analysis call shapes") {
    val module = ModuleDef(
      "OldPositionalShape",
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector(BooleanParameter("ENABLE", default = true))
    )
    assert(module.booleanLocalParameters.isEmpty)

    val booleanResult = BoolExpressionAnalysis.evaluateDefault(
      BoolParameterRef("ENABLE"),
      Map("ENABLE" -> BooleanParameter("ENABLE", default = true)),
      Map.empty,
      Map.empty,
      Map.empty
    )
    assert(booleanResult == Right(true))

    val integerResult = IntExpressionAnalysis.analyze(
      Select(BoolParameterRef("ENABLE"), Literal(8), Literal(4)),
      Map.empty,
      Map.empty,
      Map("ENABLE" -> BooleanParameter("ENABLE", default = true)),
      Map.empty
    )
    assert(integerResult.map(_.defaultValue) == Right(BigInt(8)))
  }

  test("orders mixed local declarations dependency first and computes exact defaults") {
    val module = passthrough(
      "MixedLocalOrder",
      LocalParameterRef("WIDTH"),
      localParameters = Vector(
        IntegerLocalParameter("ALPHA", Literal(2)),
        IntegerLocalParameter(
          "WIDTH",
          Select(BoolLocalParameterRef("ROUTE"), Literal(8), Literal(16))
        )
      ),
      booleanLocalParameters = Vector(
        BooleanLocalParameter("ROUTE", BoolLocalParameterRef("READY")),
        BooleanLocalParameter("READY", BoolParameterRef("ENABLE")),
        BooleanLocalParameter("ZED", BoolLiteral(true))
      ),
      booleanParameters = Vector(BooleanParameter("ENABLE", default = false))
    )

    val facts = valid(module).moduleFacts(module.name)
    assert(
      facts.orderedLocalDeclarations.map(_.name) == Vector("ALPHA", "READY", "ROUTE", "WIDTH", "ZED")
    )
    assert(
      facts.booleanLocalParameterFacts == Map("READY" -> false, "ROUTE" -> false, "ZED" -> true)
    )
    assert(facts.localParameterFacts("WIDTH").defaultValue == 16)
  }

  test("supports Boolean locals that compare integer locals") {
    val module = passthrough(
      "BooleanUsesIntegerLocal",
      Literal(8),
      localParameters = Vector(IntegerLocalParameter("LIMIT", Literal(7))),
      booleanLocalParameters = Vector(
        BooleanLocalParameter("LARGE", GreaterThan(LocalParameterRef("LIMIT"), Literal(4)))
      )
    )

    val facts = valid(module).moduleFacts(module.name)
    assert(facts.booleanLocalParameterFacts("LARGE"))
    assert(facts.orderedLocalDeclarations.map(_.name) == Vector("LIMIT", "LARGE"))
  }

  test("rejects cross-kind local dependency cycles deterministically") {
    val module = passthrough(
      "CrossKindLocalCycle",
      Literal(8),
      localParameters = Vector(
        IntegerLocalParameter("WIDTH", Select(BoolLocalParameterRef("READY"), Literal(8), Literal(4)))
      ),
      booleanLocalParameters = Vector(
        BooleanLocalParameter("READY", GreaterThan(LocalParameterRef("WIDTH"), Literal(0)))
      )
    )

    assertCodes(module, "PRTL-LOCAL-PARAMETER-CYCLE")
  }

  test("orders multiple mixed local cycle diagnostics by declaration name") {
    val module = passthrough(
      "MultipleMixedLocalCycles",
      Literal(8),
      localParameters = Vector(
        IntegerLocalParameter("ALPHA", Select(BoolLocalParameterRef("BETA"), Literal(8), Literal(4))),
        IntegerLocalParameter("YELLOW", Select(BoolLocalParameterRef("ZED"), Literal(8), Literal(4)))
      ),
      booleanLocalParameters = Vector(
        BooleanLocalParameter("BETA", GreaterThan(LocalParameterRef("ALPHA"), Literal(0))),
        BooleanLocalParameter("ZED", GreaterThan(LocalParameterRef("YELLOW"), Literal(0)))
      )
    )

    ParamRtlValidator.validate(Design(module.name, Vector(module))) match {
      case Left(diagnostics) =>
        val cycles = diagnostics.values.filter(_.code == "PRTL-LOCAL-PARAMETER-CYCLE")
        assert(cycles.size == 2, cycles.mkString("\n"))
        assert(cycles.head.message.contains("integer ALPHA, Boolean BETA"), cycles.mkString("\n"))
        assert(cycles(1).message.contains("integer YELLOW, Boolean ZED"), cycles.mkString("\n"))
      case Right(_) => fail("Expected two mixed local cycles")
    }
  }

  test("preserves legacy integer-only cycle diagnostic text") {
    val module = passthrough(
      "LegacyIntegerCycleText",
      Literal(8),
      localParameters = Vector(
        IntegerLocalParameter("A", LocalParameterRef("B")),
        IntegerLocalParameter("B", LocalParameterRef("A"))
      )
    )

    ParamRtlValidator.validate(Design(module.name, Vector(module))) match {
      case Left(diagnostics) =>
        val cycles = diagnostics.values.filter(_.code == "PRTL-LOCAL-PARAMETER-CYCLE")
        assert(cycles.map(_.message) == Vector("Local-parameter dependency cycle members: A, B"))
      case Right(_) => fail("Expected an integer-only local cycle")
    }
  }

  test("reports Boolean-local unresolved and wrong-kind references") {
    val module = passthrough(
      "InvalidBooleanLocalRefs",
      Literal(8),
      localParameters = Vector(IntegerLocalParameter("INTEGER_ONLY", Literal(1))),
      booleanLocalParameters = Vector(
        BooleanLocalParameter("BAD_KIND", BoolLocalParameterRef("INTEGER_ONLY")),
        BooleanLocalParameter("MISSING", BoolLocalParameterRef("UNKNOWN"))
      )
    )

    assertCodes(
      module,
      "PRTL-LOCAL-PARAMETER-KIND-MISMATCH",
      "PRTL-UNRESOLVED-BOOLEAN-LOCAL-PARAMETER"
    )
  }

  test("rejects integer use of a Boolean local parameter") {
    val module = passthrough(
      "IntegerUsesBooleanWrongKind",
      Literal(8),
      localParameters = Vector(IntegerLocalParameter("WIDTH", LocalParameterRef("READY"))),
      booleanLocalParameters = Vector(BooleanLocalParameter("READY", BoolLiteral(true)))
    )

    assertCodes(module, "PRTL-LOCAL-PARAMETER-KIND-MISMATCH")
  }

  test("validates unsafe inactive Boolean-local subtrees eagerly") {
    val module = passthrough(
      "EagerBooleanLocal",
      Literal(8),
      localParameters = Vector(IntegerLocalParameter("ZERO", Literal(0))),
      booleanLocalParameters = Vector(
        BooleanLocalParameter(
          "READY",
          Or(
            BoolLiteral(true),
            GreaterThan(Divide(Literal(1), LocalParameterRef("ZERO")), Literal(0))
          )
        )
      )
    )

    assertCodes(module, "PRTL-DIVISOR-MAY-BE-ZERO")
  }

  test("recomputes child Boolean locals in the bound parent instance context") {
    val child = passthrough(
      "InstanceBooleanLocalChild",
      LocalParameterRef("CHILD_WIDTH"),
      localParameters = Vector(
        IntegerLocalParameter(
          "CHILD_WIDTH",
          Select(BoolLocalParameterRef("ROUTE"), Literal(8), Literal(16))
        )
      ),
      booleanLocalParameters = Vector(
        BooleanLocalParameter("ROUTE", And(BoolParameterRef("ENABLE"), BoolLocalParameterRef("READY"))),
        BooleanLocalParameter("READY", BoolLiteral(true))
      ),
      booleanParameters = Vector(BooleanParameter("ENABLE", default = true))
    )
    val packed = PackedBits(Literal(16), Unsigned)
    val parent = ModuleDef(
      "InstanceBooleanLocalParent",
      Vector.empty,
      Vector(Port("din", Input, packed), Port("dout", Output, packed)),
      Vector(
        ModuleInstance(
          "child",
          child.name,
          portConnections = Vector(PortConnection("din", Ref("din")), PortConnection("dout", Ref("dout"))),
          booleanParameterBindings = Vector(BooleanParameterBinding("ENABLE", BoolLiteral(false)))
        )
      )
    )

    val facts = valid(Design(parent.name, Vector(parent, child))).moduleFacts(parent.name).instanceFacts("child")
    assert(facts.booleanLocalParameterFacts == Map("READY" -> true, "ROUTE" -> false))
    assert(facts.localParameterFacts("CHILD_WIDTH").defaultValue == 16)
    assert(
      facts.instantiatedPortTypes("din").width ==
        Select(And(BoolLiteral(false), BoolLiteral(true)), Literal(8), Literal(16)),
      facts.instantiatedPortTypes("din").width.toString
    )
  }

  test("does not collapse a parent-dependent Boolean-local child port to its default width") {
    val child = passthrough(
      "VariableInstanceBooleanLocalChild",
      LocalParameterRef("CHILD_WIDTH"),
      localParameters = Vector(
        IntegerLocalParameter(
          "CHILD_WIDTH",
          Select(BoolLocalParameterRef("ROUTE"), Literal(8), Literal(16))
        )
      ),
      booleanLocalParameters = Vector(
        BooleanLocalParameter("ROUTE", BoolParameterRef("ENABLE"))
      ),
      booleanParameters = Vector(BooleanParameter("ENABLE", default = false))
    )
    val packed = PackedBits(Literal(16), Unsigned)
    val parent = ModuleDef(
      "VariableInstanceBooleanLocalParent",
      Vector.empty,
      Vector(Port("din", Input, packed), Port("dout", Output, packed)),
      Vector(
        ModuleInstance(
          "child",
          child.name,
          portConnections = Vector(PortConnection("din", Ref("din")), PortConnection("dout", Ref("dout"))),
          booleanParameterBindings = Vector(
            BooleanParameterBinding("ENABLE", BoolParameterRef("ALLOW"))
          )
        )
      ),
      booleanParameters = Vector(BooleanParameter("ALLOW", default = false))
    )

    ParamRtlValidator.validate(Design(parent.name, Vector(parent, child))) match {
      case Left(diagnostics) =>
        assert(diagnostics.codes.count(_ == "PRTL-INSTANCE-PORT-TYPE-MISMATCH") == 2)
      case Right(_) => fail("Expected symbolic child width to reject fixed parent port widths")
    }
  }

  test("keeps closedness analysis stack-safe for a deeply expanded mixed local DAG") {
    val depth = 5000
    def localName(index: Int): String = f"L$index%05d"
    val integerLocals = Vector.tabulate(depth) { index =>
      val value =
        if (index == 0) Literal(1)
        else IntExpr.Add(LocalParameterRef(localName(index - 1)), Literal(1))
      IntegerLocalParameter(localName(index), value)
    }
    val finalWidth = LocalParameterRef(localName(depth - 1))
    val child = passthrough(
      "DeepMixedLocalChild",
      Select(BoolLocalParameterRef("READY"), finalWidth, Literal(1)),
      localParameters = integerLocals,
      booleanLocalParameters = Vector(
        BooleanLocalParameter("READY", GreaterThan(finalWidth, Literal(0)))
      )
    )
    val packed = PackedBits(Literal(depth), Unsigned)
    val parent = ModuleDef(
      "DeepMixedLocalParent",
      Vector.empty,
      Vector(Port("din", Input, packed), Port("dout", Output, packed)),
      Vector(
        ModuleInstance(
          "child",
          child.name,
          portConnections = Vector(PortConnection("din", Ref("din")), PortConnection("dout", Ref("dout")))
        )
      )
    )

    val facts = valid(Design(parent.name, Vector(parent, child)))
      .moduleFacts(parent.name)
      .instanceFacts("child")
    assert(facts.localParameterFacts(localName(depth - 1)).defaultValue == depth)
    assert(facts.booleanLocalParameterFacts("READY"))
  }

  test("rejects duplicate and public or cross-kind Boolean local declarations") {
    val module = passthrough(
      "DuplicateBooleanLocals",
      Literal(8),
      localParameters = Vector(IntegerLocalParameter("SHARED", Literal(8))),
      booleanLocalParameters = Vector(
        BooleanLocalParameter("SHARED", BoolLiteral(true)),
        BooleanLocalParameter("FLAG", BoolLiteral(true)),
        BooleanLocalParameter("FLAG", BoolLiteral(false)),
        BooleanLocalParameter("ENABLE", BoolLiteral(true))
      ),
      booleanParameters = Vector(BooleanParameter("ENABLE", default = true))
    )

    assertCodes(
      module,
      "PRTL-DUPLICATE-BOOLEAN-LOCAL-PARAMETER",
      "PRTL-DUPLICATE-DECLARATION"
    )
  }

  private def passthrough(
      name: String,
      width: IntExpr,
      localParameters: Vector[IntegerLocalParameter] = Vector.empty,
      booleanLocalParameters: Vector[BooleanLocalParameter] = Vector.empty,
      booleanParameters: Vector[BooleanParameter] = Vector.empty
  ): ModuleDef = {
    val packed = PackedBits(width, Unsigned)
    ModuleDef(
      name,
      Vector.empty,
      Vector(Port("din", Input, packed), Port("dout", Output, packed)),
      Vector(ContinuousAssign(Ref("dout"), Ref("din"))),
      localParameters = localParameters,
      booleanParameters = booleanParameters,
      booleanLocalParameters = booleanLocalParameters
    )
  }

  private def valid(module: ModuleDef): ValidatedDesign = valid(Design(module.name, Vector(module)))

  private def valid(design: Design): ValidatedDesign = ParamRtlValidator.validate(design) match {
    case Right(value)      => value
    case Left(diagnostics) => fail(diagnostics.values.mkString("\n"))
  }

  private def assertCodes(module: ModuleDef, expected: String*): Unit =
    ParamRtlValidator.validate(Design(module.name, Vector(module))) match {
      case Left(diagnostics) => expected.foreach(code => assert(diagnostics.codes.contains(code), diagnostics.values.mkString("\n")))
      case Right(_)          => fail(s"Expected diagnostics ${expected.mkString(", ")}")
    }
}
