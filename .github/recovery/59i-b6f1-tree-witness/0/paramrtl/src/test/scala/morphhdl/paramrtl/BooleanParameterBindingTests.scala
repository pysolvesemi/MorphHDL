package morphhdl.paramrtl

import morphhdl.paramrtl.BoolExpr.{
  And,
  GreaterThan,
  Literal => BoolLiteral,
  Or,
  ParameterRef => BoolParameterRef
}
import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}
import morphhdl.paramrtl.IntExpr.{Add, Divide, Literal, LocalParameterRef, ParameterRef}
import morphhdl.paramrtl.ModuleItem.{ContinuousAssign, ModuleInstance}
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.RtlExpr.Ref
import morphhdl.paramrtl.Signedness.Unsigned
import org.scalatest.funsuite.AnyFunSuite

class BooleanParameterBindingTests extends AnyFunSuite {
  test("Boolean child binding overrides its default and instantiates conditional locals") {
    val child = conditionalChild("BoundBooleanChild", default = true)
    val packed = PackedBits(LocalParameterRef("PARENT_WIDTH"), Unsigned)
    val parent = ModuleDef(
      "BoundBooleanParent",
      Vector.empty,
      Vector(Port("din", Input, packed), Port("dout", Output, packed)),
      Vector(
        ModuleInstance(
          "child",
          child.name,
          portConnections = passthroughConnections,
          booleanParameterBindings = Vector(
            BooleanParameterBinding("ENABLE", BoolParameterRef("ENABLE"))
          )
        )
      ),
      localParameters = Vector(
        IntegerLocalParameter(
          "PARENT_WIDTH",
          IntExpr.Select(BoolParameterRef("ENABLE"), Literal(8), Literal(16))
        )
      ),
      booleanParameters = Vector(BooleanParameter("ENABLE", default = false))
    )

    ParamRtlValidator.validate(Design(parent.name, Vector(parent, child))) match {
      case Left(diagnostics) => fail(diagnostics.values.mkString("\n"))
      case Right(validated) =>
        val instanceFacts = validated.moduleFacts(parent.name).instanceFacts("child")
        assert(!instanceFacts.booleanParameters("ENABLE").default)
        assert(instanceFacts.localParameterFacts("CHILD_WIDTH").defaultValue == 16)
        assert(instanceFacts.instantiatedPortTypes.contains("din"))
    }
  }

  test("Boolean child binding may compare parent integer parameters and locals") {
    val child = conditionalChild("ComparedBooleanChild", default = false)
    val bindingExpression = And(
      BoolParameterRef("ALLOW"),
      GreaterThan(LocalParameterRef("LIMIT"), ParameterRef("THRESHOLD"))
    )
    val packed = PackedBits(LocalParameterRef("PARENT_WIDTH"), Unsigned)
    val parent = ModuleDef(
      "ComparedBooleanParent",
      Vector(
        IntegerParameter("BASE", 4, Vector(MinInclusive(4), MaxInclusive(4))),
        IntegerParameter("THRESHOLD", 4, Vector(MinInclusive(4), MaxInclusive(4)))
      ),
      Vector(Port("din", Input, packed), Port("dout", Output, packed)),
      Vector(
        ModuleInstance(
          "child",
          child.name,
          portConnections = passthroughConnections,
          booleanParameterBindings = Vector(
            BooleanParameterBinding(
              "ENABLE",
              bindingExpression
            )
          )
        )
      ),
      localParameters = Vector(
        IntegerLocalParameter("LIMIT", Add(ParameterRef("BASE"), Literal(1))),
        IntegerLocalParameter(
          "PARENT_WIDTH",
          IntExpr.Select(bindingExpression, Literal(8), Literal(16))
        )
      ),
      booleanParameters = Vector(BooleanParameter("ALLOW", default = true))
    )

    ParamRtlValidator.validate(Design(parent.name, Vector(parent, child))) match {
      case Left(diagnostics) => fail(diagnostics.values.mkString("\n"))
      case Right(validated) =>
        val facts = validated.moduleFacts(parent.name).instanceFacts("child")
        assert(facts.booleanParameters("ENABLE").default)
        assert(facts.localParameterFacts("CHILD_WIDTH").defaultValue == 8)
    }
  }

  test("Boolean child binding validates inactive integer comparisons eagerly") {
    val child = emptyBooleanChild("EagerBooleanChild")
    val parent = ModuleDef(
      "EagerBooleanParent",
      Vector(IntegerParameter("DIVISOR", 1, Vector(MinInclusive(-1), MaxInclusive(1)))),
      Vector.empty,
      Vector(
        ModuleInstance(
          "child",
          child.name,
          booleanParameterBindings = Vector(
            BooleanParameterBinding(
              "ENABLE",
              Or(
                BoolLiteral(true),
                GreaterThan(Divide(Literal(8), ParameterRef("DIVISOR")), Literal(0))
              )
            )
          )
        )
      )
    )

    assertCodes(Design(parent.name, Vector(parent, child)), "PRTL-DIVISOR-MAY-BE-ZERO")
  }

  test("Boolean child binding reports Boolean integer and local namespace failures") {
    val child = emptyBooleanChild("UnresolvedBooleanChild")
    val parent = ModuleDef(
      "UnresolvedBooleanParent",
      Vector.empty,
      Vector.empty,
      Vector(
        ModuleInstance(
          "child",
          child.name,
          booleanParameterBindings = Vector(
            BooleanParameterBinding(
              "ENABLE",
              And(
                BoolParameterRef("MISSING_BOOL"),
                And(
                  GreaterThan(ParameterRef("MISSING_INT"), Literal(0)),
                  GreaterThan(LocalParameterRef("MISSING_LOCAL"), Literal(0))
                )
              )
            )
          )
        )
      )
    )

    assertCodes(
      Design(parent.name, Vector(parent, child)),
      "PRTL-UNRESOLVED-BOOLEAN-PARAMETER",
      "PRTL-UNRESOLVED-PARAMETER",
      "PRTL-UNRESOLVED-LOCAL-PARAMETER"
    )
  }

  test("Boolean child bindings reject duplicates unknown names and cross-kind targets") {
    val booleanChild = emptyBooleanChild("BooleanKindChild")
    val integerChild = ModuleDef(
      "IntegerKindChild",
      Vector(IntegerParameter("WIDTH", 1, Vector(MinInclusive(1), MaxInclusive(1)))),
      Vector.empty,
      Vector.empty
    )
    val parent = ModuleDef(
      "InvalidBindingParent",
      Vector.empty,
      Vector.empty,
      Vector(
        ModuleInstance(
          "bool_child",
          booleanChild.name,
          parameterBindings = Vector(ParameterBinding("ENABLE", Literal(1))),
          booleanParameterBindings = Vector(
            BooleanParameterBinding("ENABLE", BoolLiteral(true)),
            BooleanParameterBinding("ENABLE", BoolLiteral(false)),
            BooleanParameterBinding("UNKNOWN", BoolLiteral(true))
          )
        ),
        ModuleInstance(
          "int_child",
          integerChild.name,
          booleanParameterBindings = Vector(BooleanParameterBinding("WIDTH", BoolLiteral(true)))
        )
      )
    )

    assertCodes(
      Design(parent.name, Vector(parent, booleanChild, integerChild)),
      "PRTL-DUPLICATE-BOOLEAN-PARAMETER-BINDING",
      "PRTL-DUPLICATE-INSTANCE-PARAMETER-BINDING",
      "PRTL-INSTANCE-PARAMETER-KIND-MISMATCH",
      "PRTL-UNRESOLVED-INSTANCE-BOOLEAN-PARAMETER"
    )
  }

  test("parent and child opposite-kind names remain scoped during Boolean binding evaluation") {
    val child = ModuleDef(
      "OppositeKindChild",
      Vector(IntegerParameter("ENABLE", 3, Vector(MinInclusive(3), MaxInclusive(3)))),
      Vector.empty,
      Vector.empty,
      booleanParameters = Vector(BooleanParameter("LIMIT", default = false))
    )
    val parent = ModuleDef(
      "OppositeKindParent",
      Vector(IntegerParameter("LIMIT", 4, Vector(MinInclusive(4), MaxInclusive(4)))),
      Vector.empty,
      Vector(
        ModuleInstance(
          "child",
          child.name,
          booleanParameterBindings = Vector(
            BooleanParameterBinding(
              "LIMIT",
              And(
                BoolParameterRef("ENABLE"),
                GreaterThan(ParameterRef("LIMIT"), Literal(0))
              )
            )
          )
        )
      ),
      booleanParameters = Vector(BooleanParameter("ENABLE", default = true))
    )

    ParamRtlValidator.validate(Design(parent.name, Vector(parent, child))) match {
      case Left(diagnostics) => fail(diagnostics.values.mkString("\n"))
      case Right(validated) =>
        val facts = validated.moduleFacts(parent.name).instanceFacts("child")
        assert(facts.booleanParameters("LIMIT").default)
        assert(facts.parameterFacts("ENABLE").defaultValue == 3)
    }
  }

  test("two-level Boolean forwarding remains well typed in each instance namespace") {
    val leaf = conditionalChild("ForwardedBooleanLeaf", default = false)
    val packed = PackedBits(LocalParameterRef("WIDTH"), Unsigned)
    val middle = ModuleDef(
      "ForwardedBooleanMiddle",
      Vector.empty,
      Vector(Port("din", Input, packed), Port("dout", Output, packed)),
      Vector(
        ModuleInstance(
          "leaf",
          leaf.name,
          portConnections = passthroughConnections,
          booleanParameterBindings = Vector(
            BooleanParameterBinding("ENABLE", BoolParameterRef("ENABLE"))
          )
        )
      ),
      localParameters = Vector(
        IntegerLocalParameter(
          "WIDTH",
          IntExpr.Select(BoolParameterRef("ENABLE"), Literal(8), Literal(16))
        )
      ),
      booleanParameters = Vector(BooleanParameter("ENABLE", default = true))
    )
    val top = ModuleDef(
      "ForwardedBooleanTop",
      Vector.empty,
      Vector(
        Port("din", Input, PackedBits(Literal(8), Unsigned)),
        Port("dout", Output, PackedBits(Literal(8), Unsigned))
      ),
      Vector(
        ModuleInstance(
          "middle",
          middle.name,
          portConnections = passthroughConnections,
          booleanParameterBindings = Vector(BooleanParameterBinding("ENABLE", BoolLiteral(true)))
        )
      )
    )

    assert(ParamRtlValidator.validate(Design(top.name, Vector(top, middle, leaf))).isRight)
  }

  private def passthroughConnections =
    Vector(PortConnection("din", Ref("din")), PortConnection("dout", Ref("dout")))

  private def conditionalChild(name: String, default: Boolean): ModuleDef = {
    val width = IntegerLocalParameter(
      "CHILD_WIDTH",
      IntExpr.Select(BoolParameterRef("ENABLE"), Literal(8), Literal(16))
    )
    val packed = PackedBits(LocalParameterRef(width.name), Unsigned)
    ModuleDef(
      name,
      Vector.empty,
      Vector(Port("din", Input, packed), Port("dout", Output, packed)),
      Vector(ContinuousAssign(Ref("dout"), Ref("din"))),
      localParameters = Vector(width),
      booleanParameters = Vector(BooleanParameter("ENABLE", default))
    )
  }

  private def emptyBooleanChild(name: String): ModuleDef =
    ModuleDef(
      name,
      Vector.empty,
      Vector.empty,
      Vector.empty,
      booleanParameters = Vector(BooleanParameter("ENABLE", default = false))
    )

  private def assertCodes(design: Design, expected: String*): Unit =
    ParamRtlValidator.validate(design) match {
      case Left(diagnostics) =>
        expected.foreach(code => assert(diagnostics.codes.contains(code), diagnostics.values.mkString("\n")))
      case Right(_) => fail(s"Expected diagnostics: ${expected.mkString(", ")}")
    }
}
