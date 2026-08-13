package morphhdl.paramrtl

import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}
import morphhdl.paramrtl.BoolExpr.{Equal, GreaterThan, LessThan, Literal => BoolLiteral, ParameterRef => BoolParameterRef}
import morphhdl.paramrtl.IntExpr.{Add, Divide, GenerateIndexRef, Literal, LocalParameterRef, Multiply, ParameterRef, Select}
import morphhdl.paramrtl.ModuleItem.{ContinuousAssign, GenerateFor, ModuleInstance}
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.RtlExpr.{IndexedPartSelect, Ref}
import morphhdl.paramrtl.Signedness.Unsigned
import org.scalatest.funsuite.AnyFunSuite

class ParamRtlValidatorTests extends AnyFunSuite {
  test("validates a constrained symbolic packed width") {
    assert(ParamRtlValidator.validate(parameterizedWire()).isRight)
  }

  test("does not use a positive default as proof that a symbolic width is positive") {
    val design = parameterizedWire(
      parameter = IntegerParameter("WIDTH", 8)
    )

    assertCodes(design, "PRTL-WIDTH-NOT-PROVEN-POSITIVE")
  }

  test("rejects duplicate declarations deterministically") {
    val design = parameterizedWire().copy(
      modules = Vector(
        parameterizedWire().modules.head.copy(
          parameters = Vector(
            IntegerParameter("WIDTH", 8, Vector(MinInclusive(1))),
            IntegerParameter("WIDTH", 13, Vector(MinInclusive(1)))
          )
        )
      )
    )

    assertCodes(design, "PRTL-DUPLICATE-PARAMETER")
  }

  test("rejects unresolved parameter and RTL references") {
    val base = parameterizedWire().modules.head
    val design = Design(
      top = base.name,
      modules = Vector(
        base.copy(
          ports = base.ports.map(_.copy(dataType = PackedBits(ParameterRef("MISSING"), Unsigned))),
          items = Vector(ContinuousAssign(Ref("dout"), Ref("missing_signal")))
        )
      )
    )

    assertCodes(
      design,
      "PRTL-UNRESOLVED-PARAMETER",
      "PRTL-UNRESOLVED-RTL-REFERENCE"
    )
  }

  test("rejects illegal input drivers and missing output drivers") {
    val base = parameterizedWire().modules.head
    val design = Design(
      top = base.name,
      modules = Vector(base.copy(items = Vector(ContinuousAssign(Ref("din"), Ref("dout")))))
    )

    assertCodes(design, "PRTL-ILLEGAL-INPUT-DRIVER", "PRTL-UNDRIVEN-OUTPUT")
  }

  test("rejects structurally different assignment types") {
    val base = parameterizedWire().modules.head
    val design = Design(
      top = base.name,
      modules = Vector(
        base.copy(
          ports = Vector(
            Port("din", Input, PackedBits(Literal(8), Unsigned)),
            Port("dout", Output, PackedBits(ParameterRef("WIDTH"), Unsigned))
          )
        )
      )
    )

    assertCodes(design, "PRTL-TYPE-MISMATCH")
  }

  test("rejects inconsistent constraints and illegal defaults") {
    val design = parameterizedWire(
      parameter = IntegerParameter(
        "WIDTH",
        8,
        Vector(MinInclusive(10), MaxInclusive(4))
      )
    )

    assertCodes(
      design,
      "PRTL-DEFAULT-VIOLATES-CONSTRAINT",
      "PRTL-INCONSISTENT-CONSTRAINTS"
    )
  }

  test("rejects multiple drivers") {
    val base = parameterizedWire().modules.head
    val assignment = ContinuousAssign(Ref("dout"), Ref("din"))
    val design = Design(
      top = base.name,
      modules = Vector(base.copy(items = Vector(assignment, assignment)))
    )

    assertCodes(design, "PRTL-MULTIPLE-DRIVERS")
  }

  test("select uses the exact Boolean default witness and hulls both branch domains") {
    val integerParameters = Vector(
      IntegerParameter("SELECTOR", 8, Vector(MinInclusive(0), MaxInclusive(16))),
      IntegerParameter("WIDE", 12, Vector(MinInclusive(8), MaxInclusive(16))),
      IntegerParameter("NARROW", 3, Vector(MinInclusive(2), MaxInclusive(4)))
    )
    val integerFacts = integerParameters.flatMap { parameter =>
      IntExpressionAnalysis.parameterFacts(parameter).map(parameter.name -> _)
    }.toMap
    val expression = Select(
      GreaterThan(ParameterRef("SELECTOR"), Literal(4)),
      ParameterRef("WIDE"),
      ParameterRef("NARROW")
    )

    val analyzed = IntExpressionAnalysis.analyze(
      expression,
      integerFacts,
      Map.empty,
      Map.empty,
      Map.empty
    )

    assert(analyzed == Right(IntExprFacts(12, IntInterval(Some(2), Some(16)))))
  }

  test("select validates an inactive branch before choosing its default witness") {
    val divisor = IntegerParameter("DIVISOR", 1, Vector(MinInclusive(-1), MaxInclusive(1)))
    val divisorFacts = IntExpressionAnalysis.parameterFacts(divisor).map("DIVISOR" -> _).toSeq.toMap
    val expression = Select(
      BoolLiteral(true),
      Literal(8),
      Divide(Literal(8), ParameterRef("DIVISOR"))
    )

    assert(
      IntExpressionAnalysis.analyze(expression, divisorFacts, Map.empty, Map.empty, Map.empty) match {
        case Left(IntExpressionFailure.DivisorMayBeZero("/", _)) => true
        case _                                                     => false
      }
    )
  }

  test("select reports references from its condition and both value branches") {
    val selected = Add(
      Select(
        BoolParameterRef("MISSING_BOOL"),
        ParameterRef("MISSING_TRUE"),
        LocalParameterRef("MISSING_FALSE")
      ),
      Literal(1)
    )
    val packed = PackedBits(selected, Unsigned)
    val top = ModuleDef(
      "MissingSelectReferences",
      Vector.empty,
      Vector(Port("din", Input, packed), Port("dout", Output, packed)),
      Vector(ContinuousAssign(Ref("dout"), Ref("din")))
    )

    assertCodes(
      Design(top.name, Vector(top)),
      "PRTL-UNRESOLVED-BOOLEAN-PARAMETER",
      "PRTL-UNRESOLVED-PARAMETER",
      "PRTL-UNRESOLVED-LOCAL-PARAMETER"
    )
  }

  test("select rejects unsafe division in its Boolean condition") {
    val divisor = IntegerParameter("DIVISOR", 1, Vector(MinInclusive(-1), MaxInclusive(1)))
    val width = Select(
      LessThan(Divide(Literal(8), ParameterRef("DIVISOR")), Literal(10)),
      Literal(8),
      Literal(4)
    )
    val base = parameterizedWire(divisor).modules.head
    val top = base.copy(
      ports = base.ports.map(_.copy(dataType = PackedBits(width, Unsigned)))
    )

    assertCodes(Design(top.name, Vector(top)), "PRTL-DIVISOR-MAY-BE-ZERO")
  }

  test("select condition and branch dependencies order local parameters before consumers") {
    val selected = IntegerLocalParameter(
      "SELECTED",
      Select(
        GreaterThan(LocalParameterRef("BASE"), Literal(4)),
        LocalParameterRef("BASE"),
        LocalParameterRef("FALLBACK")
      )
    )
    val locals = Vector(selected, IntegerLocalParameter("FALLBACK", Literal(3)), IntegerLocalParameter("BASE", Literal(8)))
    val packed = PackedBits(LocalParameterRef("SELECTED"), Unsigned)
    val top = ModuleDef(
      "OrderedSelectLocals",
      Vector.empty,
      Vector(Port("din", Input, packed), Port("dout", Output, packed)),
      Vector(ContinuousAssign(Ref("dout"), Ref("din"))),
      localParameters = locals
    )

    val validated = ParamRtlValidator.validate(Design(top.name, Vector(top))) match {
      case Right(value)      => value
      case Left(diagnostics) => fail(diagnostics.values.mkString("\n"))
    }
    val ordered = validated.moduleFacts(top.name).orderedLocalParameters.map(_.name)
    assert(ordered.indexOf("BASE") < ordered.indexOf("SELECTED"))
    assert(ordered.indexOf("FALLBACK") < ordered.indexOf("SELECTED"))
  }

  test("select requires every possible width branch to remain positive") {
    val width = Select(BoolParameterRef("WIDE"), Literal(8), Literal(0))
    val packed = PackedBits(width, Unsigned)
    val top = ModuleDef(
      "UnsafeSelectedWidth",
      Vector.empty,
      Vector(Port("din", Input, packed), Port("dout", Output, packed)),
      Vector(ContinuousAssign(Ref("dout"), Ref("din"))),
      booleanParameters = Vector(BooleanParameter("WIDE", default = true))
    )

    assertCodes(Design(top.name, Vector(top)), "PRTL-WIDTH-NOT-PROVEN-POSITIVE")
  }

  test("select requires every binding branch to fit the child parameter domain") {
    val childPacked = PackedBits(ParameterRef("WIDTH"), Unsigned)
    val child = ModuleDef(
      "SelectedChild",
      Vector(IntegerParameter("WIDTH", 8, Vector(MinInclusive(1), MaxInclusive(16)))),
      Vector(Port("din", Input, childPacked), Port("dout", Output, childPacked)),
      Vector(ContinuousAssign(Ref("dout"), Ref("din")))
    )
    val parentPacked = PackedBits(Literal(8), Unsigned)
    val parent = ModuleDef(
      "SelectedParent",
      Vector.empty,
      Vector(Port("din", Input, parentPacked), Port("dout", Output, parentPacked)),
      Vector(
        ModuleInstance(
          "child",
          child.name,
          parameterBindings = Vector(
            ParameterBinding("WIDTH", Select(BoolParameterRef("USE_SAFE"), Literal(8), Literal(32)))
          ),
          portConnections = Vector(PortConnection("din", Ref("din")), PortConnection("dout", Ref("dout")))
        )
      ),
      booleanParameters = Vector(BooleanParameter("USE_SAFE", default = true))
    )

    assertCodes(
      Design(parent.name, Vector(parent, child)),
      "PRTL-PARAMETER-BINDING-DOMAIN-NOT-PROVEN"
    )
  }

  test("select substitution and equivalence visit its condition and both branches") {
    val original = Select(
      Equal(ParameterRef("PUBLIC"), LocalParameterRef("LOCAL")),
      Add(ParameterRef("PUBLIC"), Literal(1)),
      LocalParameterRef("LOCAL")
    )
    val substituted = IntExpressionEquivalence.substitute(
      original,
      Map("PUBLIC" -> Literal(8)),
      Map("LOCAL" -> Literal(8))
    )
    val expected = Select(
      Equal(Literal(8), Literal(8)),
      Add(Literal(8), Literal(1)),
      Literal(8)
    )

    assert(substituted == expected)
    assert(
      IntExpressionEquivalence.equivalent(
        Select(BoolParameterRef("ENABLE"), Add(ParameterRef("P"), Literal(1)), Literal(4)),
        Select(BoolParameterRef("ENABLE"), Add(Literal(1), ParameterRef("P")), Literal(4))
      )
    )
  }

  test("Boolean reference discovery reaches a select nested inside a comparison") {
    val expression = Equal(
      Select(BoolParameterRef("MISSING"), Literal(8), Literal(4)),
      Literal(8)
    )

    assert(
      BoolExpressionAnalysis.evaluateDefault(expression, Map.empty, Map.empty, Map.empty) ==
        Left(BoolExpressionFailure.UnresolvedParameter("MISSING"))
    )
  }

  test("generate-index detection reaches a selection condition") {
    val childPacked = PackedBits(Literal(8), Unsigned)
    val child = ModuleDef(
      "SelectedLane",
      Vector.empty,
      Vector(Port("din", Input, childPacked), Port("dout", Output, childPacked)),
      Vector(ContinuousAssign(Ref("dout"), Ref("din")))
    )
    val parentPacked = PackedBits(Literal(16), Unsigned)
    val selectedWidth = Select(
      Equal(GenerateIndexRef("i"), Literal(0)),
      Literal(8),
      Literal(8)
    )
    def slice(name: String): IndexedPartSelect =
      IndexedPartSelect(
        Ref(name),
        Multiply(GenerateIndexRef("i"), selectedWidth),
        selectedWidth
      )
    val parent = ModuleDef(
      "SelectedLaneArray",
      Vector.empty,
      Vector(Port("din", Input, parentPacked), Port("dout", Output, parentPacked)),
      Vector(
        GenerateFor(
          "g_lane",
          "i",
          Literal(2),
          Vector(
            ModuleInstance(
              "lane",
              child.name,
              portConnections = Vector(
                PortConnection("din", slice("din")),
                PortConnection("dout", slice("dout"))
              )
            )
          )
        )
      )
    )

    ParamRtlValidator.validate(Design(parent.name, Vector(parent, child))) match {
      case Left(diagnostics) =>
        assert(diagnostics.codes.contains("PRTL-GENERATE-SLICE-WIDTH-VARIES"), diagnostics.values.mkString("\n"))
        assert(!diagnostics.codes.contains("PRTL-GENERATE-INDEX-OUT-OF-SCOPE"), diagnostics.values.mkString("\n"))
      case Right(_) => fail("Expected generate-index-dependent selected width to fail")
    }
  }

  test("child selected widths are fixed to the child Boolean default during instantiation") {
    val childWidth = IntegerLocalParameter(
      "CHILD_WIDTH",
      Select(BoolParameterRef("WIDE"), Literal(8), Literal(16))
    )
    val childPacked = PackedBits(LocalParameterRef(childWidth.name), Unsigned)
    val child = ModuleDef(
      "DefaultSelectedChild",
      Vector.empty,
      Vector(Port("din", Input, childPacked), Port("dout", Output, childPacked)),
      Vector(ContinuousAssign(Ref("dout"), Ref("din"))),
      localParameters = Vector(childWidth),
      booleanParameters = Vector(BooleanParameter("WIDE", default = true))
    )
    val parentPacked = PackedBits(Literal(8), Unsigned)
    val parent = ModuleDef(
      "DefaultSelectedParent",
      Vector.empty,
      Vector(Port("din", Input, parentPacked), Port("dout", Output, parentPacked)),
      Vector(
        ModuleInstance(
          "child",
          child.name,
          portConnections = Vector(PortConnection("din", Ref("din")), PortConnection("dout", Ref("dout")))
        )
      )
    )

    assert(ParamRtlValidator.validate(Design(parent.name, Vector(parent, child))).isRight)
  }

  test("same-named parent and child Boolean parameters keep independent instance scopes") {
    val selectedWidth = Select(BoolParameterRef("WIDE"), Literal(8), Literal(16))
    val child = ModuleDef(
      "IndependentBooleanChild",
      Vector.empty,
      Vector(
        Port("din", Input, PackedBits(LocalParameterRef("CHILD_WIDTH"), Unsigned)),
        Port("dout", Output, PackedBits(LocalParameterRef("CHILD_WIDTH"), Unsigned))
      ),
      Vector(ContinuousAssign(Ref("dout"), Ref("din"))),
      localParameters = Vector(IntegerLocalParameter("CHILD_WIDTH", selectedWidth)),
      booleanParameters = Vector(BooleanParameter("WIDE", default = true))
    )
    val parent = ModuleDef(
      "IndependentBooleanParent",
      Vector.empty,
      Vector(
        Port("din", Input, PackedBits(LocalParameterRef("PARENT_WIDTH"), Unsigned)),
        Port("dout", Output, PackedBits(LocalParameterRef("PARENT_WIDTH"), Unsigned))
      ),
      Vector(
        ModuleInstance(
          "child",
          child.name,
          portConnections = Vector(PortConnection("din", Ref("din")), PortConnection("dout", Ref("dout")))
        )
      ),
      localParameters = Vector(IntegerLocalParameter("PARENT_WIDTH", selectedWidth)),
      booleanParameters = Vector(BooleanParameter("WIDE", default = false))
    )

    assertCodes(
      Design(parent.name, Vector(parent, child)),
      "PRTL-INSTANCE-PORT-TYPE-MISMATCH"
    )
  }

  private def parameterizedWire(
      parameter: IntegerParameter = IntegerParameter("WIDTH", 8, Vector(MinInclusive(1)))
  ): Design = {
    val width = ParameterRef(parameter.name)
    val packed = PackedBits(width, Unsigned)
    Design(
      top = "ParameterizedWire",
      modules = Vector(
        ModuleDef(
          name = "ParameterizedWire",
          parameters = Vector(parameter),
          ports = Vector(
            Port("din", Input, packed),
            Port("dout", Output, packed)
          ),
          items = Vector(ContinuousAssign(Ref("dout"), Ref("din")))
        )
      )
    )
  }

  private def assertCodes(design: Design, expected: String*): Unit =
    ParamRtlValidator.validate(design) match {
      case Left(diagnostics) =>
        expected.foreach(code => assert(diagnostics.codes.contains(code), diagnostics.values.mkString("\n")))
        assert(diagnostics.values == diagnostics.values.sortBy(d => (d.pathString, d.code, d.message)))
      case Right(_) => fail(s"Expected diagnostics ${expected.mkString(", ")}, but validation passed")
    }
}
