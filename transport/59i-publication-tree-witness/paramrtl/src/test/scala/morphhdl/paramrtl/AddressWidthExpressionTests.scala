package morphhdl.paramrtl

import morphhdl.paramrtl.BoolExpr.{
  And => BoolAnd,
  LessThan => BoolLessThan,
  Literal => BoolLiteral,
  LocalParameterRef => BoolLocalParameterRef,
  ParameterRef => BoolParameterRef
}
import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}
import morphhdl.paramrtl.IntExpr.{
  Add,
  AddressWidth,
  Divide,
  Literal,
  LocalParameterRef,
  ParameterRef,
  Select
}
import morphhdl.paramrtl.ModuleItem.{ContinuousAssign, ModuleInstance}
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.RtlExpr.Ref
import morphhdl.paramrtl.Signedness.Unsigned
import org.scalatest.funsuite.AnyFunSuite

class AddressWidthExpressionTests extends AnyFunSuite {
  test("computes exact address widths at every portable boundary") {
    Vector(
      BigInt(1) -> BigInt(1),
      BigInt(2) -> BigInt(1),
      BigInt(3) -> BigInt(2),
      BigInt(5) -> BigInt(3),
      (BigInt(1) << 30) -> BigInt(30),
      ((BigInt(1) << 30) + 1) -> BigInt(31)
    ).foreach { case (value, expected) =>
      assert(analyze(AddressWidth(Literal(value))) == Right(point(expected)))
    }
  }

  test("maps a positive operand interval monotonically") {
    val depth = IntegerParameter(
      "DEPTH",
      5,
      Vector(MinInclusive(1), MaxInclusive((BigInt(1) << 30) + 1))
    )
    val parameters = Map("DEPTH" -> IntExpressionAnalysis.parameterFacts(depth).get)

    assert(
      IntExpressionAnalysis.analyze(AddressWidth(ParameterRef("DEPTH")), parameters, Map.empty) ==
        Right(IntExprFacts(3, IntInterval(Some(1), Some(31))))
    )
  }

  test("rejects nonpositive defaults and every domain that can reach zero") {
    assertAddressWidthDiagnostic(identityDesign(AddressWidth(Literal(0))))
    assertAddressWidthDiagnostic(
      identityDesign(
        AddressWidth(ParameterRef("VALUE")),
        Vector(IntegerParameter("VALUE", 0, Vector(MinInclusive(0), MaxInclusive(4))))
      )
    )
    assertAddressWidthDiagnostic(
      identityDesign(
        AddressWidth(ParameterRef("VALUE")),
        Vector(IntegerParameter("VALUE", 2, Vector(MinInclusive(0), MaxInclusive(4))))
      )
    )
  }

  test("keeps unresolved and inactive unsafe operand failures ahead of the domain diagnostic") {
    ParamRtlValidator.validate(identityDesign(AddressWidth(ParameterRef("MISSING")))) match {
      case Left(diagnostics) =>
        val unresolved = diagnostics.values.find(_.code == "PRTL-UNRESOLVED-PARAMETER").get
        assert(unresolved.path.last == "operand")
        assert(!diagnostics.codes.contains("PRTL-ADDRESS-WIDTH-OPERAND-NOT-PROVEN-POSITIVE"))
      case Right(_) => fail("Expected unresolved operand failure")
    }

    val inactiveUnsafe = AddressWidth(
      Select(
        BoolLiteral(false),
        Divide(Literal(1), Literal(0)),
        Literal(3)
      )
    )
    ParamRtlValidator.validate(identityDesign(inactiveUnsafe)) match {
      case Left(diagnostics) =>
        assert(diagnostics.codes.contains("PRTL-DIVISOR-MAY-BE-ZERO"), diagnostics.values.mkString("\n"))
        assert(!diagnostics.codes.contains("PRTL-ADDRESS-WIDTH-OPERAND-NOT-PROVEN-POSITIVE"))
      case Right(_) => fail("Expected inactive unsafe operand failure")
    }
  }

  test("preserves Boolean reference and comparison failure priority") {
    val missingKinds = AddressWidth(
      Select(
        BoolAnd(BoolLocalParameterRef("MISSING_LOCAL"), BoolParameterRef("MISSING_PUBLIC")),
        Literal(5),
        Literal(3)
      )
    )
    assert(analyze(missingKinds) == Left(IntExpressionFailure.UnresolvedBooleanParameter("MISSING_PUBLIC")))

    val leftFailure = AddressWidth(
      Select(
        BoolLessThan(
          Divide(Literal(1), Literal(0)),
          AddressWidth(Literal(0))
        ),
        Literal(5),
        Literal(3)
      )
    )
    analyze(leftFailure) match {
      case Left(IntExpressionFailure.DivisorMayBeZero("/", _)) =>
      case other => fail(s"Expected left comparison failure, found $other")
    }

    val falseAndUnsafe = AddressWidth(
      Select(
        BoolAnd(
          BoolLiteral(false),
          BoolLessThan(Divide(Literal(1), Literal(0)), Literal(2))
        ),
        Literal(5),
        Literal(3)
      )
    )
    analyze(falseAndUnsafe) match {
      case Left(IntExpressionFailure.DivisorMayBeZero("/", _)) =>
      case other => fail(s"Expected non-short-circuited comparison failure, found $other")
    }
  }

  test("discovers references and orders local address-width dependencies") {
    val selected = AddressWidth(
      Select(
        BoolParameterRef("ENABLE"),
        ParameterRef("DEPTH"),
        LocalParameterRef("RAW_DEPTH")
      )
    )
    assert(IntExpressionAnalysis.parameterReferences(selected) == Vector("DEPTH"))
    assert(IntExpressionAnalysis.localParameterReferences(selected) == Vector("RAW_DEPTH"))
    assert(IntExpressionAnalysis.booleanParameterReferences(selected) == Vector("ENABLE"))

    val packed = PackedBits(LocalParameterRef("ADDRESS_WIDTH"), Unsigned)
    val module = ModuleDef(
      "LocalAddressWidth",
      Vector(IntegerParameter("DEPTH", 5, Vector(MinInclusive(1), MaxInclusive(5)))),
      Vector(Port("din", Input, packed), Port("dout", Output, packed)),
      Vector(ContinuousAssign(Ref("dout"), Ref("din"))),
      localParameters = Vector(
        IntegerLocalParameter("ADDRESS_WIDTH", AddressWidth(LocalParameterRef("RAW_DEPTH"))),
        IntegerLocalParameter("RAW_DEPTH", ParameterRef("DEPTH"))
      )
    )
    val validated = ParamRtlValidator.validate(Design(module.name, Vector(module))) match {
      case Right(value)      => value
      case Left(diagnostics) => fail(diagnostics.values.mkString("\n"))
    }
    assert(
      validated.moduleFacts(module.name).orderedLocalParameters.map(_.name) ==
        Vector("RAW_DEPTH", "ADDRESS_WIDTH")
    )
  }

  test("substitution and equivalence retain address-width correlation") {
    val original = AddressWidth(Add(ParameterRef("DEPTH"), LocalParameterRef("OFFSET")))
    assert(
      IntExpressionEquivalence.substitute(
        original,
        Map("DEPTH" -> Literal(5)),
        Map("OFFSET" -> Literal(0))
      ) == AddressWidth(Add(Literal(5), Literal(0)))
    )
    assert(
      IntExpressionEquivalence.equivalent(
        AddressWidth(Add(ParameterRef("DEPTH"), Literal(0))),
        AddressWidth(ParameterRef("DEPTH"))
      )
    )
    assert(IntExpressionEquivalence.equivalent(AddressWidth(Literal(5)), Literal(3)))
    assert(!IntExpressionEquivalence.equivalent(AddressWidth(ParameterRef("DEPTH")), ParameterRef("DEPTH")))
  }

  test("normalizes deep equivalent sums without recursive descent") {
    var expanded: IntExpr = ParameterRef("DEPTH")
    (1 to 900).foreach { _ =>
      expanded = Add(expanded, Literal(1))
    }

    assert(
      IntExpressionEquivalence.equivalent(
        expanded,
        Add(ParameterRef("DEPTH"), Literal(900))
      )
    )
  }

  test("compares equivalent shared Boolean DAGs once per identity pair") {
    var leftCondition: BoolExpr = BoolParameterRef("ENABLE")
    var rightCondition: BoolExpr = BoolParameterRef("ENABLE")
    (1 to 64).foreach { _ =>
      leftCondition = BoolAnd(leftCondition, leftCondition)
      rightCondition = BoolAnd(rightCondition, rightCondition)
    }

    assert(
      IntExpressionEquivalence.equivalent(
        Select(leftCondition, ParameterRef("DEPTH"), Literal(1)),
        Select(rightCondition, ParameterRef("DEPTH"), Literal(1))
      )
    )
  }

  test("counts and normalizes distinct alternating integer and Boolean trees iteratively") {
    def alternating(depth: Int): IntExpr = {
      var result: IntExpr = Literal(1)
      (1 to depth).foreach { _ =>
        result = Select(BoolLessThan(result, Literal(2)), Literal(5), Literal(3))
      }
      result
    }

    assert(
      IntExpressionEquivalence.equivalent(
        Add(alternating(180), Literal(0)),
        alternating(180)
      )
    )
  }

  test("preserves address-width expressions through parameter binding and hierarchy") {
    val childWidth = ParameterRef("ADDRESS_WIDTH")
    val childBits = PackedBits(childWidth, Unsigned)
    val child = ModuleDef(
      "AddressWidthChild",
      Vector(IntegerParameter("ADDRESS_WIDTH", 3, Vector(MinInclusive(1), MaxInclusive(31)))),
      Vector(Port("din", Input, childBits), Port("dout", Output, childBits)),
      Vector(ContinuousAssign(Ref("dout"), Ref("din")))
    )
    val parentWidth = AddressWidth(Add(ParameterRef("PARENT_DEPTH"), Literal(0)))
    val parentBits = PackedBits(parentWidth, Unsigned)
    val parent = ModuleDef(
      "AddressWidthParent",
      Vector(IntegerParameter("PARENT_DEPTH", 5, Vector(MinInclusive(1), MaxInclusive(5)))),
      Vector(Port("din", Input, parentBits), Port("dout", Output, parentBits)),
      Vector(
        ModuleInstance(
          "child",
          child.name,
          parameterBindings = Vector(
            ParameterBinding("ADDRESS_WIDTH", AddressWidth(ParameterRef("PARENT_DEPTH")))
          ),
          portConnections = Vector(
            PortConnection("din", Ref("din")),
            PortConnection("dout", Ref("dout"))
          )
        )
      )
    )

    ParamRtlValidator.validate(Design(parent.name, Vector(parent, child))) match {
      case Right(_) =>
      case Left(diagnostics) => fail(diagnostics.values.mkString("\n"))
    }
  }

  private def point(value: BigInt): IntExprFacts =
    IntExprFacts(value, IntInterval(Some(value), Some(value)))

  private def analyze(expression: IntExpr): Either[IntExpressionFailure, IntExprFacts] =
    IntExpressionAnalysis.analyze(expression, Map.empty, Map.empty)

  private def identityDesign(
      width: IntExpr,
      parameters: Vector[IntegerParameter] = Vector.empty
  ): Design = {
    val packed = PackedBits(width, Unsigned)
    val module = ModuleDef(
      "AddressWidthProbe",
      parameters,
      Vector(Port("din", Input, packed), Port("dout", Output, packed)),
      Vector(ContinuousAssign(Ref("dout"), Ref("din")))
    )
    Design(module.name, Vector(module))
  }

  private def assertAddressWidthDiagnostic(design: Design): Unit =
    ParamRtlValidator.validate(design) match {
      case Left(diagnostics) =>
        val matches = diagnostics.values.filter(_.code == "PRTL-ADDRESS-WIDTH-OPERAND-NOT-PROVEN-POSITIVE")
        assert(matches.size == 2, diagnostics.values.mkString("\n"))
        assert(matches.forall(_.path.last == "width"), diagnostics.values.mkString("\n"))
      case Right(_) => fail("Expected address-width operand diagnostic")
    }
}
