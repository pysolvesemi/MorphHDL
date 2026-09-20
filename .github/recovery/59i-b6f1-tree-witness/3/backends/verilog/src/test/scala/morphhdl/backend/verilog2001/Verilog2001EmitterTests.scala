package morphhdl.backend.verilog2001

import morphhdl.paramrtl.BoolExpr.{GreaterThan, ParameterRef => BoolParameterRef}
import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}
import morphhdl.paramrtl.IntExpr.{Add, GenerateIndexRef, Literal, Multiply, ParameterRef, Select, Subtract}
import morphhdl.paramrtl.ModuleItem.{ContinuousAssign, GenerateFor, GenerateIf}
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.RtlExpr.{IndexedPartSelect, Ref}
import morphhdl.paramrtl.Signedness.Unsigned
import morphhdl.paramrtl._
import org.scalatest.funsuite.AnyFunSuite

class Verilog2001EmitterTests extends AnyFunSuite {
  test("emits the ParameterizedWire contract byte for byte") {
    assertEmission(ParameterizedWireFixture.design(), ParameterizedWireFixture.expected)
  }

  test("emission is deterministic and independent of construction order") {
    val normal = emit(ParameterizedWireFixture.design())
    val reversed = emit(ParameterizedWireFixture.design(reverseConstructionOrder = true))

    assert(normal == emit(ParameterizedWireFixture.design()))
    assert(normal == reversed)
    assert(normal.endsWith("\n"))
    assert(!normal.endsWith("\n\n"))
  }

  test("does not expose a validation bypass") {
    val invalid = ParameterizedWireFixture
      .design()
      .copy(
        modules = Vector(
          ParameterizedWireFixture
            .design()
            .modules
            .head
            .copy(
              parameters = Vector(IntegerParameter("WIDTH", 8))
            )
        )
      )

    assertDiagnostic(invalid, "PRTL-WIDTH-NOT-PROVEN-POSITIVE")
  }

  test("rejects Verilog-2001 reserved identifiers") {
    val packed = PackedBits(ParameterRef("WIDTH"), Unsigned)
    val design = Design(
      top = "module",
      modules = Vector(
        ModuleDef(
          name = "module",
          parameters = Vector(IntegerParameter("WIDTH", 8, Vector(MinInclusive(1)))),
          ports = Vector(Port("din", Input, packed), Port("dout", Output, packed)),
          items = Vector(ContinuousAssign(Ref("dout"), Ref("din")))
        )
      )
    )

    assertDiagnostic(design, "V2001-RESERVED-IDENTIFIER")
  }

  test("rejects parameter integers that Verilog-2001 cannot represent portably") {
    val invalid = ParameterizedWireFixture
      .design()
      .copy(
        modules = Vector(
          ParameterizedWireFixture
            .design()
            .modules
            .head
            .copy(
              parameters = Vector(
                IntegerParameter(
                  "WIDTH",
                  BigInt(Int.MaxValue) + 1,
                  Vector(MinInclusive(1))
                )
              )
            )
        )
      )

    assertDiagnostic(invalid, "V2001-INTEGER-OUT-OF-RANGE")
  }

  test("rejects an integer parameter whose legal domain is not target bounded") {
    val invalid = ParameterizedWireFixture
      .design()
      .copy(
        modules = Vector(
          ParameterizedWireFixture
            .design()
            .modules
            .head
            .copy(
              parameters = Vector(IntegerParameter("WIDTH", 8, Vector(MinInclusive(1))))
            )
        )
      )

    assertDiagnostic(invalid, "V2001-INTEGER-DOMAIN-OUT-OF-RANGE")
  }

  test("preserves one-bit packed-vector intent") {
    val packed = PackedBits(IntExpr.Literal(1), Unsigned)
    val design = Design(
      top = "OneBitPacked",
      modules = Vector(
        ModuleDef(
          name = "OneBitPacked",
          parameters = Vector.empty,
          ports = Vector(Port("din", Input, packed), Port("dout", Output, packed)),
          items = Vector(ContinuousAssign(Ref("dout"), Ref("din")))
        )
      )
    )

    assert(emit(design).contains("wire [0:0] din"))
    assert(emit(design).contains("wire [0:0] dout"))
  }

  test("rejects syntax-injection identifiers before emission") {
    val invalidName = "bad); endmodule"
    val invalid = ParameterizedWireFixture
      .design()
      .copy(
        top = invalidName,
        modules = Vector(ParameterizedWireFixture.design().modules.head.copy(name = invalidName))
      )

    assertDiagnostic(invalid, "PRTL-INVALID-IDENTIFIER")
  }

  test("rejects duplicate logical module definitions") {
    val module = ParameterizedWireFixture.design().modules.head
    val invalid = ParameterizedWireFixture.design().copy(modules = Vector(module, module))

    assertDiagnostic(invalid, "PRTL-DUPLICATE-MODULE")
  }

  test("rejects an unresolved top module") {
    val invalid = ParameterizedWireFixture.design().copy(top = "MissingTop")

    assertDiagnostic(invalid, "PRTL-UNRESOLVED-TOP")
  }

  test("emits bounded integer selection as a strict Verilog-2001 ternary") {
    val verilog = emit(
      selectedLocalDesign(
        Select(BoolParameterRef("WIDE"), ParameterRef("WIDE_WIDTH"), ParameterRef("NARROW_WIDTH")),
        integerParameters = Vector(
          boundedParameter("WIDE_WIDTH", 16, 1, 32),
          boundedParameter("NARROW_WIDTH", 8, 1, 32)
        )
      )
    )

    assert(
      verilog.contains(
        "localparam integer SELECTED = (WIDE == 1) ? WIDE_WIDTH : NARROW_WIDTH;"
      )
    )
  }

  test("parenthesizes selection around arithmetic and nested value branches deterministically") {
    val selected = Select(
      BoolParameterRef("WIDE"),
      Select(BoolParameterRef("INNER_TRUE"), Add(Literal(1), Literal(2)), Literal(4)),
      Select(BoolParameterRef("INNER_FALSE"), Literal(5), Subtract(Literal(8), Literal(2)))
    )
    val expression = Multiply(Add(selected, Literal(1)), Literal(2))
    val verilog = emit(
      selectedLocalDesign(
        expression,
        booleanParameters = Vector(
          BooleanParameter("WIDE", default = true),
          BooleanParameter("INNER_TRUE", default = false),
          BooleanParameter("INNER_FALSE", default = true)
        )
      )
    )

    assert(
      verilog.contains(
        "localparam integer SELECTED = (((WIDE == 1) ? ((INNER_TRUE == 1) ? 1 + 2 : 4) : ((INNER_FALSE == 1) ? 5 : 8 - 2)) + 1) * 2;"
      )
    )
  }

  test("parenthesizes the whole selection when it is a comparison operand") {
    val packed = PackedBits(Literal(8), Unsigned)
    val condition = GreaterThan(
      Select(BoolParameterRef("WIDE"), Literal(8), Literal(2)),
      Literal(4)
    )
    val assignment = ContinuousAssign(Ref("dout"), Ref("din"))
    val top = ModuleDef(
      "ComparedSelection",
      Vector.empty,
      Vector(Port("din", Input, packed), Port("dout", Output, packed)),
      Vector(
        GenerateIf(
          condition,
          GenerateBlock("g_true", Vector(assignment)),
          GenerateBlock("g_false", Vector(assignment))
        )
      ),
      booleanParameters = Vector(BooleanParameter("WIDE", default = true))
    )

    assert(
      emit(Design(top.name, Vector(top))).contains(
        "if (((WIDE == 1) ? 8 : 2) > 4) begin : g_true"
      )
    )
  }

  test("checks both selected value branches against the portable integer range") {
    val invalidValue = BigInt(Int.MaxValue) + 1
    val design = selectedLocalDesign(
      Select(BoolParameterRef("WIDE"), Literal(8), Literal(invalidValue))
    )

    Verilog2001Emitter.emit(design) match {
      case Left(diagnostics) =>
        val failures = diagnostics.values.filter(_.code == "V2001-INTEGER-EXPRESSION-OUT-OF-RANGE")
        assert(failures.exists(_.path.last == "whenFalse"), failures.mkString("\n"))
      case Right(verilog) => fail(s"Expected selected-branch range diagnostics, emitted:\n$verilog")
    }
  }

  test("checks integer operands in a selection condition against the portable range") {
    val maximum = BigInt(Int.MaxValue)
    val condition = GreaterThan(Add(ParameterRef("LIMIT"), Literal(1)), Literal(0))
    val design = selectedLocalDesign(
      Select(condition, Literal(8), Literal(4)),
      integerParameters = Vector(boundedParameter("LIMIT", maximum, maximum, maximum))
    )

    Verilog2001Emitter.emit(design) match {
      case Left(diagnostics) =>
        val failures = diagnostics.values.filter(_.code == "V2001-INTEGER-EXPRESSION-OUT-OF-RANGE")
        assert(failures.exists(_.path.contains("condition")), failures.mkString("\n"))
      case Right(verilog) => fail(s"Expected selected-condition range diagnostics, emitted:\n$verilog")
    }
  }

  test("parenthesizes a selected generate count as a relational operand") {
    val packed = PackedBits(Literal(8), Unsigned)
    val top = ModuleDef(
      "SelectedGenerateCount",
      Vector.empty,
      Vector(Port("din", Input, packed), Port("dout", Output, packed)),
      Vector(
        ContinuousAssign(Ref("dout"), Ref("din")),
        GenerateFor(
          "g_selected",
          "i",
          Select(BoolParameterRef("WIDE"), Literal(2), Literal(3)),
          Vector.empty
        )
      ),
      booleanParameters = Vector(BooleanParameter("WIDE", default = true))
    )

    assert(
      emit(Design(top.name, Vector(top))).contains(
        "for (i = 0; i < ((WIDE == 1) ? 2 : 3); i = i + 1) begin : g_selected"
      )
    )
  }

  test("parenthesizes selected indexed-part-select operands") {
    val selectedWidth = Select(BoolParameterRef("WIDE"), Literal(8), Literal(8))
    val canonicalOffset = Multiply(GenerateIndexRef("i"), selectedWidth)
    val selectedOffset = Select(
      BoolParameterRef("OFFSET_HIGH"),
      canonicalOffset,
      canonicalOffset
    )
    val child = ModuleDef(
      "SelectedSliceChild",
      Vector.empty,
      Vector(
        Port("din", Input, PackedBits(Literal(8), Unsigned)),
        Port("dout", Output, PackedBits(Literal(8), Unsigned))
      ),
      Vector(ContinuousAssign(Ref("dout"), Ref("din")))
    )
    def slice(name: String): IndexedPartSelect = IndexedPartSelect(Ref(name), selectedOffset, selectedWidth)
    val packed = PackedBits(Multiply(Literal(1), selectedWidth), Unsigned)
    val parent = ModuleDef(
      "SelectedSliceParent",
      Vector.empty,
      Vector(Port("din", Input, packed), Port("dout", Output, packed)),
      Vector(
        GenerateFor(
          "g_slice",
          "i",
          Literal(1),
          Vector(
            ModuleItem.ModuleInstance(
              "child",
              child.name,
              portConnections = Vector(
                PortConnection("din", slice("din")),
                PortConnection("dout", slice("dout"))
              )
            )
          )
        )
      ),
      booleanParameters = Vector(
        BooleanParameter("OFFSET_HIGH", default = false),
        BooleanParameter("WIDE", default = true)
      )
    )

    val verilog = emit(Design(parent.name, Vector(parent, child)))
    assert(
      verilog.contains(
        "din[((OFFSET_HIGH == 1) ? i * ((WIDE == 1) ? 8 : 8) : i * ((WIDE == 1) ? 8 : 8)) +: ((WIDE == 1) ? 8 : 8)]"
      )
    )
  }

  private def selectedLocalDesign(
      expression: IntExpr,
      integerParameters: Vector[IntegerParameter] = Vector.empty,
      booleanParameters: Vector[BooleanParameter] = Vector(BooleanParameter("WIDE", default = true))
  ): Design = {
    val packed = PackedBits(Literal(8), Unsigned)
    val top = ModuleDef(
      "SelectedLocal",
      integerParameters,
      Vector(Port("din", Input, packed), Port("dout", Output, packed)),
      Vector(ContinuousAssign(Ref("dout"), Ref("din"))),
      localParameters = Vector(IntegerLocalParameter("SELECTED", expression)),
      booleanParameters = booleanParameters
    )
    Design(top.name, Vector(top))
  }

  private def boundedParameter(name: String, default: BigInt, minimum: BigInt, maximum: BigInt): IntegerParameter =
    IntegerParameter(name, default, Vector(MinInclusive(minimum), MaxInclusive(maximum)))

  private def emit(design: Design): String =
    Verilog2001Emitter.emit(design) match {
      case Right(value)      => value
      case Left(diagnostics) => fail(diagnostics.values.mkString("\n"))
    }

  private def assertEmission(design: Design, expected: String): Unit =
    assert(emit(design) == expected)

  private def assertDiagnostic(design: Design, code: String): Unit =
    Verilog2001Emitter.emit(design) match {
      case Left(diagnostics) => assert(diagnostics.codes.contains(code), diagnostics.values.mkString("\n"))
      case Right(verilog)    => fail(s"Expected diagnostic $code, emitted:\n$verilog")
    }
}
