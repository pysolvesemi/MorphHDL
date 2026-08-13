package morphhdl.paramrtl

import morphhdl.paramrtl.BoolExpr.{
  And,
  GreaterThanOrEqual,
  GreaterThan,
  Literal => BoolLiteral,
  LocalParameterRef => BoolLocalParameterRef,
  Or,
  ParameterRef => BoolParameterRef
}
import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}
import morphhdl.paramrtl.IntExpr.{Divide, Literal, LocalParameterRef, ParameterRef, Select}
import morphhdl.paramrtl.ModuleItem.{GenerateIf, ModuleInstance}
import morphhdl.paramrtl.PortDirection.Input
import morphhdl.paramrtl.RtlExpr.Ref
import org.scalatest.funsuite.AnyFunSuite

class BooleanLocalParameterTests extends AnyFunSuite {
  test("orders integer and Boolean locals in one dependency-first graph") {
    val top = ModuleDef(
      "MixedLocalOrder",
      Vector(bounded("WIDTH", 8, 1, 32)),
      Vector.empty,
      Vector.empty,
      localParameters = Vector(
        IntegerLocalParameter("A_SELECTED", Select(BoolLocalParameterRef("Y_READY"), Literal(9), Literal(3))),
        IntegerLocalParameter("Z_WIDTH", ParameterRef("WIDTH"))
      ),
      booleanParameters = Vector(BooleanParameter("ENABLE", default = true)),
      booleanLocalParameters = Vector(
        BooleanLocalParameter(
          "B_FINAL",
          And(BoolLocalParameterRef("Y_READY"), GreaterThan(LocalParameterRef("A_SELECTED"), Literal(0)))
        ),
        BooleanLocalParameter(
          "Y_READY",
          And(BoolParameterRef("ENABLE"), GreaterThan(LocalParameterRef("Z_WIDTH"), Literal(0)))
        )
      )
    )

    validated(Design(top.name, Vector(top))) { design =>
      val facts = design.moduleFacts(top.name)
      assert(
        facts.orderedLocalParameterDeclarations.map(_.name) ==
          Vector("Z_WIDTH", "Y_READY", "A_SELECTED", "B_FINAL")
      )
      assert(facts.orderedLocalParameters.map(_.name) == Vector("Z_WIDTH", "A_SELECTED"))
      assert(facts.orderedBooleanLocalParameters.map(_.name) == Vector("Y_READY", "B_FINAL"))
      assert(facts.booleanLocalParameterFacts("B_FINAL").defaultValue)
      assert(facts.localParameterFacts("A_SELECTED").defaultValue == 9)
    }
  }

  test("rejects cycles crossing integer and Boolean local kinds") {
    val top = ModuleDef(
      "MixedLocalCycle",
      Vector.empty,
      Vector.empty,
      Vector.empty,
      localParameters = Vector(
        IntegerLocalParameter("COUNT", Select(BoolLocalParameterRef("ACTIVE"), Literal(2), Literal(1)))
      ),
      booleanLocalParameters = Vector(
        BooleanLocalParameter("ACTIVE", GreaterThan(LocalParameterRef("COUNT"), Literal(0)))
      )
    )

    assertCodes(Design(top.name, Vector(top)), "PRTL-LOCAL-PARAMETER-CYCLE")
  }

  test("rejects duplicate cross-kind local declarations and typed wrong-kind references") {
    val top = ModuleDef(
      "WrongKindLocals",
      Vector.empty,
      Vector.empty,
      Vector.empty,
      localParameters = Vector(
        IntegerLocalParameter("SHARED", Literal(1)),
        IntegerLocalParameter("INT_ONLY", Literal(2)),
        IntegerLocalParameter("BAD_INT_REF", Select(BoolLocalParameterRef("BOOL_ONLY"), Literal(1), Literal(0)))
      ),
      booleanLocalParameters = Vector(
        BooleanLocalParameter("SHARED", BoolLiteral(true)),
        BooleanLocalParameter("BOOL_ONLY", BoolLiteral(false)),
        BooleanLocalParameter("BAD_BOOL_REF", BoolLocalParameterRef("INT_ONLY"))
      )
    )

    assertCodes(
      Design(top.name, Vector(top)),
      "PRTL-DUPLICATE-DECLARATION",
      "PRTL-LOCAL-PARAMETER-KIND-MISMATCH"
    )
  }

  test("rejects unresolved Boolean-local identities without treating them as public parameters") {
    val top = ModuleDef(
      "MissingBooleanLocal",
      Vector.empty,
      Vector.empty,
      Vector.empty,
      booleanLocalParameters = Vector(
        BooleanLocalParameter("VALUE", BoolLocalParameterRef("MISSING"))
      )
    )

    val diagnostics = invalid(Design(top.name, Vector(top)))
    assert(diagnostics.codes.contains("PRTL-UNRESOLVED-BOOLEAN-LOCAL-PARAMETER"))
    assert(!diagnostics.codes.contains("PRTL-UNRESOLVED-BOOLEAN-PARAMETER"))
  }

  test("validates inactive Boolean-local operands eagerly") {
    val top = ModuleDef(
      "UnsafeBooleanLocal",
      Vector(bounded("DIVISOR", 1, -1, 1)),
      Vector.empty,
      Vector.empty,
      booleanLocalParameters = Vector(
        BooleanLocalParameter(
          "SAFE_BY_DEFAULT",
          Or(
            BoolLiteral(true),
            GreaterThan(Divide(Literal(8), ParameterRef("DIVISOR")), Literal(0))
          )
        )
      )
    )

    assertCodes(Design(top.name, Vector(top)), "PRTL-DIVISOR-MAY-BE-ZERO")
  }

  test("Boolean locals drive child bindings and instantiate dependent child locals") {
    val child = ModuleDef(
      "BooleanLocalChild",
      Vector.empty,
      Vector.empty,
      Vector.empty,
      localParameters = Vector(
        IntegerLocalParameter("SELECTED", Select(BoolLocalParameterRef("ROUTE"), Literal(8), Literal(16)))
      ),
      booleanParameters = Vector(BooleanParameter("ENABLE", default = true)),
      booleanLocalParameters = Vector(
        BooleanLocalParameter("ROUTE", BoolParameterRef("ENABLE"))
      )
    )
    val parent = ModuleDef(
      "BooleanLocalParent",
      Vector.empty,
      Vector.empty,
      Vector(
        ModuleInstance(
          "child",
          child.name,
          booleanParameterBindings = Vector(
            BooleanParameterBinding("ENABLE", BoolLocalParameterRef("FORWARD"))
          )
        )
      ),
      booleanLocalParameters = Vector(BooleanLocalParameter("FORWARD", BoolLiteral(false)))
    )

    validated(Design(parent.name, Vector(parent, child))) { design =>
      val childFacts = design.moduleFacts(parent.name).instanceFacts("child")
      assert(!childFacts.booleanParameters("ENABLE").default)
      assert(!childFacts.booleanLocalParameterFacts("ROUTE").defaultValue)
      assert(childFacts.localParameterFacts("SELECTED").defaultValue == 16)
    }
  }

  test("Boolean locals are accepted by GenerateIf and IntExpr Select") {
    val top = ModuleDef(
      "BooleanLocalConsumers",
      Vector.empty,
      Vector.empty,
      Vector(
        GenerateIf(
          BoolLocalParameterRef("ACTIVE"),
          GenerateBlock("g_active", Vector.empty),
          GenerateBlock("g_inactive", Vector.empty)
        )
      ),
      localParameters = Vector(
        IntegerLocalParameter("SELECTED", Select(BoolLocalParameterRef("ACTIVE"), Literal(4), Literal(2)))
      ),
      booleanLocalParameters = Vector(BooleanLocalParameter("ACTIVE", BoolLiteral(true)))
    )

    validated(Design(top.name, Vector(top))) { design =>
      assert(design.moduleFacts(top.name).localParameterFacts("SELECTED").defaultValue == 4)
    }
  }

  test("a false child Boolean binding fixes a Boolean-local selected port width") {
    val child = booleanLocalWidthChild(
      "FalseBoundBooleanLocalWidthChild",
      BoolParameterRef("ENABLE")
    )
    val parent = booleanLocalWidthParent(
      "FalseBoundBooleanLocalWidthParent",
      child,
      binding = false,
      connectedWidth = 2
    )

    validated(Design(parent.name, Vector(parent, child))) { design =>
      val facts = design.moduleFacts(parent.name).instanceFacts("child")
      assert(!facts.booleanLocalParameterFacts("ACTIVE").defaultValue)
      assert(facts.instantiatedPortTypes("din").width == Literal(2))
    }
  }

  test("a true child Boolean binding fixes a predicate Boolean-local selected port width") {
    val child = booleanLocalWidthChild(
      "TrueBoundBooleanLocalWidthChild",
      And(
        BoolParameterRef("ENABLE"),
        GreaterThanOrEqual(Literal(2), Literal(1))
      )
    )
    val parent = booleanLocalWidthParent(
      "TrueBoundBooleanLocalWidthParent",
      child,
      binding = true,
      connectedWidth = 4
    )

    validated(Design(parent.name, Vector(parent, child))) { design =>
      val facts = design.moduleFacts(parent.name).instanceFacts("child")
      assert(facts.booleanLocalParameterFacts("ACTIVE").defaultValue)
      assert(facts.instantiatedPortTypes("din").width == Literal(4))
    }
  }

  test("a fixed Boolean-local selected port width still rejects a mismatched parent width") {
    val child = booleanLocalWidthChild(
      "MismatchedBooleanLocalWidthChild",
      BoolParameterRef("ENABLE")
    )
    val parent = booleanLocalWidthParent(
      "MismatchedBooleanLocalWidthParent",
      child,
      binding = false,
      connectedWidth = 4
    )

    assertCodes(
      Design(parent.name, Vector(parent, child)),
      "PRTL-INSTANCE-PORT-TYPE-MISMATCH"
    )
  }

  private def booleanLocalWidthChild(name: String, active: BoolExpr): ModuleDef =
    ModuleDef(
      name,
      Vector.empty,
      Vector(
        Port(
          "din",
          Input,
          PackedBits(Select(BoolLocalParameterRef("ACTIVE"), Literal(4), Literal(2)), Signedness.Unsigned)
        )
      ),
      Vector.empty,
      booleanParameters = Vector(BooleanParameter("ENABLE", default = true)),
      booleanLocalParameters = Vector(BooleanLocalParameter("ACTIVE", active))
    )

  private def booleanLocalWidthParent(
      name: String,
      child: ModuleDef,
      binding: Boolean,
      connectedWidth: BigInt
  ): ModuleDef =
    ModuleDef(
      name,
      Vector.empty,
      Vector(Port("din", Input, PackedBits(Literal(connectedWidth), Signedness.Unsigned))),
      Vector(
        ModuleInstance(
          "child",
          child.name,
          portConnections = Vector(PortConnection("din", Ref("din"))),
          booleanParameterBindings = Vector(
            BooleanParameterBinding("ENABLE", BoolLiteral(binding))
          )
        )
      )
    )

  private def bounded(name: String, default: BigInt, minimum: BigInt, maximum: BigInt): IntegerParameter =
    IntegerParameter(name, default, Vector(MinInclusive(minimum), MaxInclusive(maximum)))

  private def invalid(design: Design): DiagnosticSet =
    ParamRtlValidator.validate(design) match {
      case Left(value)      => value
      case Right(validated) => fail(s"Expected invalid design, validated ${validated.value.top}")
    }

  private def assertCodes(design: Design, expected: String*): Unit = {
    val codes = invalid(design).codes
    expected.foreach(code => assert(codes.contains(code), s"Expected $code in ${codes.mkString(", ")}"))
  }

  private def validated(design: Design)(check: ValidatedDesign => Unit): Unit =
    ParamRtlValidator.validate(design) match {
      case Left(diagnostics) => fail(diagnostics.values.mkString("\n"))
      case Right(value)      => check(value)
    }
}
