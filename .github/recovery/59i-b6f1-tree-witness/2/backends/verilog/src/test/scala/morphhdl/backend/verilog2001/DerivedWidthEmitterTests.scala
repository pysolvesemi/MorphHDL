package morphhdl.backend.verilog2001

import morphhdl.paramrtl.IntExpr.{Add, Divide, Literal, LocalParameterRef, Modulo, Multiply, Negate, Subtract}
import morphhdl.paramrtl.ModuleItem.ContinuousAssign
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.RtlExpr.Ref
import morphhdl.paramrtl.Signedness.Unsigned
import morphhdl.paramrtl._
import org.scalatest.funsuite.AnyFunSuite

class DerivedWidthEmitterTests extends AnyFunSuite {
  test("emits the DerivedWidth contract byte for byte") {
    assert(emit(DerivedWidthFixture.design()) == DerivedWidthFixture.expected)
  }

  test("orders dependent local parameters deterministically") {
    val normal = emit(DerivedWidthFixture.design())
    val reversed = emit(DerivedWidthFixture.design(reverseConstructionOrder = true))

    assert(normal == reversed)
    assert(normal.indexOf("localparam integer TOTAL_WIDTH") < normal.indexOf("localparam integer PADDED_WIDTH"))
  }

  test("keeps parameter expressions symbolic instead of folding their defaults") {
    val verilog = emit(DerivedWidthFixture.design())

    assert(verilog.contains("localparam integer TOTAL_WIDTH = LANES * DATA_WIDTH;"))
    assert(verilog.contains("localparam integer PADDED_WIDTH = TOTAL_WIDTH + 3;"))
    assert(!verilog.contains("localparam integer TOTAL_WIDTH = 32;"))
    assert(!verilog.contains("localparam integer PADDED_WIDTH = 35;"))
  }

  test("renders precedence and association without changing the expression tree") {
    val verilog = emit(expressionDesign())

    assert(verilog.contains("localparam integer ADD_MUL = 1 + 2 * 3;"))
    assert(verilog.contains("localparam integer MUL_ADD = (1 + 2) * 3;"))
    assert(verilog.contains("localparam integer RIGHT_SUB = 20 - (7 - 2);"))
    assert(verilog.contains("localparam integer NEGATED_ADD = -(2 + 3);"))
    assert(verilog.contains("localparam integer DIV_MUL = 24 / (2 * 3);"))
    assert(verilog.contains("localparam integer MOD_DIV = 20 % (7 / 2);"))
    assert(verilog.contains("localparam integer DIV_ADD = (20 + 4) / 3;"))
    assert(verilog.contains("localparam integer NEGATIVE_LITERAL = -(-1);"))
    assert(verilog.contains("localparam integer SUB_NEGATIVE = 5 - (-1);"))
    assert(!verilog.contains("= --1;"))
  }

  test("rejects a reserved local-parameter identifier") {
    val invalid = simpleDesign(
      Vector(IntegerLocalParameter("wire", Literal(1))),
      widthName = "wire"
    )

    assertDiagnostic(invalid, "V2001-RESERVED-IDENTIFIER")
  }

  test("rejects an out-of-range intermediate expression even when the final value fits") {
    val product = Multiply(Literal(50000), Literal(50000))
    val invalid = simpleDesign(
      Vector(
        IntegerLocalParameter("INTERMEDIATE", Subtract(product, product)),
        IntegerLocalParameter("WIDTH", Literal(1))
      ),
      widthName = "WIDTH"
    )

    assertDiagnostic(invalid, "V2001-INTEGER-EXPRESSION-OUT-OF-RANGE")
  }

  private def expressionDesign(): Design =
    simpleDesign(
      Vector(
        IntegerLocalParameter("WIDTH", Literal(1)),
        IntegerLocalParameter("ADD_MUL", Add(Literal(1), Multiply(Literal(2), Literal(3)))),
        IntegerLocalParameter("MUL_ADD", Multiply(Add(Literal(1), Literal(2)), Literal(3))),
        IntegerLocalParameter("RIGHT_SUB", Subtract(Literal(20), Subtract(Literal(7), Literal(2)))),
        IntegerLocalParameter("NEGATED_ADD", Negate(Add(Literal(2), Literal(3)))),
        IntegerLocalParameter("DIV_MUL", Divide(Literal(24), Multiply(Literal(2), Literal(3)))),
        IntegerLocalParameter("MOD_DIV", Modulo(Literal(20), Divide(Literal(7), Literal(2)))),
        IntegerLocalParameter("DIV_ADD", Divide(Add(Literal(20), Literal(4)), Literal(3))),
        IntegerLocalParameter("NEGATIVE_LITERAL", Negate(Literal(-1))),
        IntegerLocalParameter("SUB_NEGATIVE", Subtract(Literal(5), Literal(-1)))
      ),
      widthName = "WIDTH"
    )

  private def simpleDesign(
      localParameters: Vector[IntegerLocalParameter],
      widthName: String
  ): Design = {
    val packed = PackedBits(LocalParameterRef(widthName), Unsigned)
    Design(
      top = "ExpressionRender",
      modules = Vector(
        ModuleDef(
          name = "ExpressionRender",
          parameters = Vector.empty,
          ports = Vector(
            Port("din", Input, packed),
            Port("dout", Output, packed)
          ),
          items = Vector(ContinuousAssign(Ref("dout"), Ref("din"))),
          localParameters = localParameters
        )
      )
    )
  }

  private def emit(design: Design): String =
    Verilog2001Emitter.emit(design) match {
      case Right(value)      => value
      case Left(diagnostics) => fail(diagnostics.values.mkString("\n"))
    }

  private def assertDiagnostic(design: Design, code: String): Unit =
    Verilog2001Emitter.emit(design) match {
      case Left(diagnostics) => assert(diagnostics.codes.contains(code), diagnostics.values.mkString("\n"))
      case Right(verilog)    => fail(s"Expected diagnostic $code, emitted:\n$verilog")
    }
}
