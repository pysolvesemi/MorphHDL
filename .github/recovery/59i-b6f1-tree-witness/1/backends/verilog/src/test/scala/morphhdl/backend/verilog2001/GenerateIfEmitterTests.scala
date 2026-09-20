package morphhdl.backend.verilog2001

import morphhdl.paramrtl.BoolExpr.{
  And,
  Equal,
  GreaterThan,
  GreaterThanOrEqual,
  LessThan,
  LessThanOrEqual,
  Literal => BoolLiteral,
  Not,
  NotEqual,
  Or,
  ParameterRef => BoolParameterRef
}
import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}
import morphhdl.paramrtl.IntExpr.{
  Add,
  Literal,
  LocalParameterRef,
  Multiply,
  Negate,
  Subtract,
  ParameterRef => IntParameterRef
}
import morphhdl.paramrtl.ModuleItem.{ContinuousAssign, GenerateIf}
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.RtlExpr.Ref
import morphhdl.paramrtl.Signedness.Unsigned
import morphhdl.paramrtl._
import org.scalatest.funsuite.AnyFunSuite

class GenerateIfEmitterTests extends AnyFunSuite {
  test("emits typed Boolean parameters as strict Verilog-2001 integer 1/0") {
    val verilog = emit(design())
    val expected =
      """module ConditionalWire #(
        |  parameter integer BYPASS = 0,
        |  parameter integer ENABLE = 1
        |) (
        |  output wire [7:0] aux,
        |  input  wire [7:0] din,
        |  output wire [7:0] dout
        |);
        |
        |  generate
        |    if (ENABLE == 1 && !(BYPASS == 1)) begin : g_enabled
        |      assign aux = din;
        |      assign dout = din;
        |    end else begin : g_disabled
        |      assign aux = din;
        |      assign dout = din;
        |    end
        |  endgenerate
        |
        |endmodule
        |""".stripMargin

    assert(verilog == expected)
  }

  test("renders Boolean precedence and literals exactly") {
    val condition = Or(
      Not(And(BoolParameterRef("ENABLE"), BoolParameterRef("BYPASS"))),
      BoolLiteral(false)
    )
    val verilog = emit(design(condition = condition))

    assert(verilog.contains("if (!(ENABLE == 1 && BYPASS == 1) || 1'b0) begin : g_enabled"))
  }

  test("sorts branch items deterministically without changing branch semantics") {
    val normal = emit(design(reverseBranchItems = false))
    val reversed = emit(design(reverseBranchItems = true))

    assert(normal == reversed)
    assert(normal.indexOf("assign aux = din;") < normal.indexOf("assign dout = din;"))
  }

  test("renders all six integer comparison operators exactly") {
    val width = IntParameterRef("WIDTH")
    val limit = LocalParameterRef("LIMIT")
    val conditions = Vector(
      LessThan(Add(width, Literal(1)), limit) -> "WIDTH + 1 < LIMIT",
      LessThanOrEqual(width, limit) -> "WIDTH <= LIMIT",
      GreaterThan(limit, width) -> "LIMIT > WIDTH",
      GreaterThanOrEqual(width, Literal(1)) -> "WIDTH >= 1",
      Equal(width, Literal(8)) -> "WIDTH == 8",
      NotEqual(limit, width) -> "LIMIT != WIDTH"
    )

    conditions.foreach { case (condition, rendered) =>
      assert(generateIfLine(emit(comparisonDesign(condition))) == s"if ($rendered) begin : g_enabled")
    }
  }

  test("preserves comparison, negation, and logical precedence exactly") {
    val width = IntParameterRef("WIDTH")
    val limit = LocalParameterRef("LIMIT")
    val condition = Or(
      And(LessThan(width, limit), Not(Equal(width, Literal(8)))),
      NotEqual(limit, width)
    )

    assert(
      generateIfLine(emit(comparisonDesign(condition))) ==
        "if (WIDTH < LIMIT && !(WIDTH == 8) || LIMIT != WIDTH) begin : g_enabled"
    )
  }

  test("preserves arithmetic operand grouping around comparisons") {
    val width = IntParameterRef("WIDTH")
    val limit = LocalParameterRef("LIMIT")
    val conditions = Vector(
      LessThan(Add(width, Literal(1)), limit) -> "WIDTH + 1 < LIMIT",
      LessThan(width, Add(limit, Literal(1))) -> "WIDTH < LIMIT + 1",
      LessThan(Multiply(Add(width, Literal(1)), Literal(2)), limit) ->
        "(WIDTH + 1) * 2 < LIMIT",
      Equal(Subtract(width, Add(limit, Literal(1))), Literal(0)) ->
        "WIDTH - (LIMIT + 1) == 0",
      Equal(Subtract(width, Literal(1)), limit) ->
        "WIDTH - 1 == LIMIT",
      Equal(Add(width, Subtract(limit, Literal(1))), limit) ->
        "WIDTH + (LIMIT - 1) == LIMIT",
      GreaterThan(width, Negate(Add(limit, Literal(1)))) ->
        "WIDTH > -(LIMIT + 1)"
    )

    conditions.foreach { case (condition, rendered) =>
      assert(generateIfLine(emit(comparisonDesign(condition))) == s"if ($rendered) begin : g_enabled")
    }
  }

  test("checks both comparison operands against portable Verilog integer range") {
    val maximum = BigInt(Int.MaxValue)
    val condition = LessThan(
      Add(IntParameterRef("LEFT"), Literal(1)),
      Add(IntParameterRef("RIGHT"), Literal(1))
    )
    val top = comparisonDesign(condition).modules.head.copy(
      parameters = Vector(
        IntegerParameter("LEFT", maximum, Vector(MinInclusive(maximum), MaxInclusive(maximum))),
        IntegerParameter("RIGHT", maximum, Vector(MinInclusive(maximum), MaxInclusive(maximum)))
      ),
      localParameters = Vector.empty
    )
    val invalid = Design(top.name, Vector(top))

    Verilog2001Emitter.emit(invalid) match {
      case Left(diagnostics) =>
        val failures = diagnostics.values.filter(_.code == "V2001-INTEGER-EXPRESSION-OUT-OF-RANGE")
        assert(failures.exists(_.path.last == "left"), failures.mkString("\n"))
        assert(failures.exists(_.path.last == "right"), failures.mkString("\n"))
      case Right(verilog) => fail(s"Expected target range diagnostics, emitted:\n$verilog")
    }
  }

  test("rejects reserved Boolean parameter and branch identifiers before emission") {
    val reservedParameter = design().copy(modules = design().modules.map(_.copy(
      booleanParameters = Vector(BooleanParameter("wire", default = true)),
      items = conditionalItems(BoolParameterRef("wire"), reverse = false)
    )))
    assertDiagnostic(reservedParameter, "V2001-RESERVED-IDENTIFIER")

    val top = design().modules.head
    val generate = top.items.head.asInstanceOf[GenerateIf]
    val reservedLabel = design().copy(modules = Vector(top.copy(items = Vector(
      generate.copy(whenTrue = generate.whenTrue.copy(label = "generate"))
    ))))
    assertDiagnostic(reservedLabel, "V2001-RESERVED-IDENTIFIER")
  }

  private def design(
      condition: BoolExpr = And(BoolParameterRef("ENABLE"), Not(BoolParameterRef("BYPASS"))),
      reverseBranchItems: Boolean = false
  ): Design = {
    val packed = PackedBits(Literal(8), Unsigned)
    val top = ModuleDef(
      name = "ConditionalWire",
      parameters = Vector.empty,
      ports = Vector(
        Port("aux", Output, packed),
        Port("din", Input, packed),
        Port("dout", Output, packed)
      ),
      items = conditionalItems(condition, reverseBranchItems),
      booleanParameters = Vector(
        BooleanParameter("ENABLE", default = true),
        BooleanParameter("BYPASS", default = false)
      )
    )
    Design(top.name, Vector(top))
  }

  private def conditionalItems(condition: BoolExpr, reverse: Boolean): Vector[ModuleItem] = {
    val assignments = Vector(
      ContinuousAssign(Ref("dout"), Ref("din")),
      ContinuousAssign(Ref("aux"), Ref("din"))
    )
    val body = if (reverse) assignments.reverse else assignments
    Vector(
      GenerateIf(
        condition,
        GenerateBlock("g_enabled", body),
        GenerateBlock("g_disabled", body.reverse)
      )
    )
  }

  private def comparisonDesign(condition: BoolExpr): Design = {
    val base = design(condition = condition).modules.head
    val width = IntegerParameter(
      "WIDTH",
      default = 8,
      constraints = Vector(MinInclusive(1), MaxInclusive(32))
    )
    val top = base.copy(
      parameters = Vector(width),
      localParameters = Vector(
        IntegerLocalParameter("LIMIT", Add(IntParameterRef("WIDTH"), Literal(2)))
      ),
      booleanParameters = Vector.empty
    )
    Design(top.name, Vector(top))
  }

  private def generateIfLine(verilog: String): String =
    verilog.split("\\n").iterator.map(_.trim).find(_.startsWith("if (")).getOrElse(fail(verilog))

  private def emit(design: Design): String = Verilog2001Emitter.emit(design) match {
    case Right(value)      => value
    case Left(diagnostics) => fail(diagnostics.values.mkString("\n"))
  }

  private def assertDiagnostic(design: Design, code: String): Unit = Verilog2001Emitter.emit(design) match {
    case Left(diagnostics) => assert(diagnostics.codes.contains(code), diagnostics.values.mkString("\n"))
    case Right(verilog)    => fail(s"Expected $code, emitted:\n$verilog")
  }
}
