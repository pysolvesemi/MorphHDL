package morphhdl.backend.verilog2001

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
import morphhdl.paramrtl.IntExpr.{Add, Literal, LocalParameterRef, Select}
import morphhdl.paramrtl.ModuleItem.ContinuousAssign
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.RtlExpr.Ref
import morphhdl.paramrtl.Signedness.Unsigned
import morphhdl.paramrtl._
import org.scalatest.funsuite.AnyFunSuite

class BooleanLocalParameterEmitterTests extends AnyFunSuite {
  test("emits mixed locals in dependency-first order with strict Boolean legalization") {
    val module = passthrough(
      "MixedBooleanLocalEmission",
      LocalParameterRef("WIDTH"),
      localParameters = Vector(
        IntegerLocalParameter("WIDTH", Select(BoolLocalParameterRef("ROUTE"), Literal(8), Literal(16))),
        IntegerLocalParameter("LIMIT", Add(Literal(3), Literal(1)))
      ),
      booleanLocalParameters = Vector(
        BooleanLocalParameter("ALWAYS", BoolLiteral(true)),
        BooleanLocalParameter("READY", GreaterThan(LocalParameterRef("LIMIT"), Literal(0))),
        BooleanLocalParameter("ROUTE", And(BoolParameterRef("ENABLE"), BoolLocalParameterRef("READY")))
      ),
      booleanParameters = Vector(BooleanParameter("ENABLE", default = true))
    )

    val verilog = emit(module)
    val declarations = verilog.split("\n").toVector.filter(_.contains("localparam integer"))
    assert(
      declarations == Vector(
        "  localparam integer ALWAYS = 1;",
        "  localparam integer LIMIT = 3 + 1;",
        "  localparam integer READY = (LIMIT > 0) ? 1 : 0;",
        "  localparam integer ROUTE = (ENABLE == 1 && READY == 1) ? 1 : 0;",
        "  localparam integer WIDTH = (ROUTE == 1) ? 8 : 16;"
      ),
      verilog
    )
    assert(verilog.contains("input  wire [WIDTH-1:0] din"), verilog)
  }

  test("preserves Boolean precedence around local references") {
    val module = passthrough(
      "BooleanLocalPrecedence",
      Literal(8),
      booleanLocalParameters = Vector(
        BooleanLocalParameter("A", BoolLiteral(true)),
        BooleanLocalParameter("B", BoolLiteral(false)),
        BooleanLocalParameter(
          "RESULT",
          And(Not(BoolLocalParameterRef("A")), Or(BoolLocalParameterRef("A"), BoolLocalParameterRef("B")))
        )
      )
    )

    val verilog = emit(module)
    assert(verilog.contains("localparam integer RESULT = (!(A == 1) && (A == 1 || B == 1)) ? 1 : 0;"), verilog)
  }

  test("checks portable range throughout Boolean-local integer comparisons") {
    val maximum = BigInt(Int.MaxValue)
    val module = passthrough(
      "BooleanLocalRange",
      Literal(8),
      localParameters = Vector(IntegerLocalParameter("LIMIT", Add(IntExpr.ParameterRef("MAX"), Literal(1)))),
      booleanLocalParameters = Vector(
        BooleanLocalParameter("READY", GreaterThan(LocalParameterRef("LIMIT"), Literal(0)))
      ),
      integerParameters = Vector(
        IntegerParameter("MAX", maximum, Vector(MinInclusive(maximum), MaxInclusive(maximum)))
      )
    )

    Verilog2001Emitter.emit(Design(module.name, Vector(module))) match {
      case Left(diagnostics) =>
        assert(diagnostics.codes.contains("V2001-INTEGER-EXPRESSION-OUT-OF-RANGE"), diagnostics.values.mkString("\n"))
      case Right(verilog) => fail(s"Expected range diagnostic, emitted:\n$verilog")
    }
  }

  private def passthrough(
      name: String,
      width: IntExpr,
      integerParameters: Vector[IntegerParameter] = Vector.empty,
      localParameters: Vector[IntegerLocalParameter] = Vector.empty,
      booleanLocalParameters: Vector[BooleanLocalParameter] = Vector.empty,
      booleanParameters: Vector[BooleanParameter] = Vector.empty
  ): ModuleDef = {
    val packed = PackedBits(width, Unsigned)
    ModuleDef(
      name,
      integerParameters,
      Vector(Port("din", Input, packed), Port("dout", Output, packed)),
      Vector(ContinuousAssign(Ref("dout"), Ref("din"))),
      localParameters = localParameters,
      booleanParameters = booleanParameters,
      booleanLocalParameters = booleanLocalParameters
    )
  }

  private def emit(module: ModuleDef): String =
    Verilog2001Emitter.emit(Design(module.name, Vector(module))) match {
      case Right(value)      => value
      case Left(diagnostics) => fail(diagnostics.values.mkString("\n"))
    }
}
