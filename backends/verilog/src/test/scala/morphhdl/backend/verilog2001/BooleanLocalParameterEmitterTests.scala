package morphhdl.backend.verilog2001

import morphhdl.paramrtl.BoolExpr.{
  And,
  GreaterThanOrEqual,
  Literal => BoolLiteral,
  LocalParameterRef => BoolLocalParameterRef,
  ParameterRef => BoolParameterRef
}
import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}
import morphhdl.paramrtl.IntExpr.{Add, Literal, LocalParameterRef, ParameterRef, Select}
import morphhdl.paramrtl.ModuleItem.{GenerateIf, ModuleInstance}
import morphhdl.paramrtl._
import org.scalatest.funsuite.AnyFunSuite

class BooleanLocalParameterEmitterTests extends AnyFunSuite {
  test("emits literal and compound Boolean locals as strict integer localparams") {
    val top = ModuleDef(
      "BooleanLocalEmission",
      Vector(
        bounded("LIMIT", 8, 1, 32),
        bounded("WIDTH", 8, 1, 32)
      ),
      Vector.empty,
      Vector.empty,
      localParameters = Vector(
        IntegerLocalParameter("EFFECTIVE_WIDTH", Add(ParameterRef("WIDTH"), Literal(1)))
      ),
      booleanParameters = Vector(BooleanParameter("ENABLE", default = true)),
      booleanLocalParameters = Vector(
        BooleanLocalParameter("ALWAYS", BoolLiteral(true)),
        BooleanLocalParameter(
          "ROUTE_HIGH",
          And(
            BoolParameterRef("ENABLE"),
            GreaterThanOrEqual(LocalParameterRef("EFFECTIVE_WIDTH"), ParameterRef("LIMIT"))
          )
        )
      )
    )

    val verilog = emit(Design(top.name, Vector(top)))
    assert(verilog.contains("localparam integer ALWAYS = 1;"), verilog)
    assert(
      verilog.contains(
        "localparam integer ROUTE_HIGH = (ENABLE == 1 && EFFECTIVE_WIDTH >= LIMIT) ? 1 : 0;"
      ),
      verilog
    )
    assert(!verilog.contains("localparam bit"), verilog)
  }

  test("emits mixed locals in unified dependency order regardless of source order") {
    def design(reverse: Boolean): Design = {
      val integers = Vector(
        IntegerLocalParameter("A_SELECTED", Select(BoolLocalParameterRef("Y_READY"), Literal(9), Literal(3))),
        IntegerLocalParameter("Z_BASE", Literal(1))
      )
      val booleans = Vector(
        BooleanLocalParameter("B_FINAL", GreaterThanOrEqual(LocalParameterRef("A_SELECTED"), Literal(1))),
        BooleanLocalParameter("Y_READY", GreaterThanOrEqual(LocalParameterRef("Z_BASE"), Literal(1)))
      )
      val top = ModuleDef(
        "UnifiedLocalOrder",
        Vector.empty,
        Vector.empty,
        Vector.empty,
        localParameters = if (reverse) integers.reverse else integers,
        booleanLocalParameters = if (reverse) booleans.reverse else booleans
      )
      Design(top.name, Vector(top))
    }

    val normal = emit(design(reverse = false))
    val reversed = emit(design(reverse = true))
    assert(normal == reversed)
    val names = Vector("Z_BASE", "Y_READY", "A_SELECTED", "B_FINAL")
    assert(names.map(name => normal.indexOf(s"localparam integer $name")).sliding(2).forall {
      case Seq(left, right) => left >= 0 && left < right
      case _                => true
    }, normal)
  }

  test("renders Boolean-local references in GenerateIf and child bindings as integer predicates") {
    val child = ModuleDef(
      "BooleanLocalEmissionChild",
      Vector.empty,
      Vector.empty,
      Vector.empty,
      booleanParameters = Vector(BooleanParameter("SELECT", default = false))
    )
    val instance = ModuleInstance(
      "child",
      child.name,
      booleanParameterBindings = Vector(
        BooleanParameterBinding("SELECT", BoolLocalParameterRef("ROUTE"))
      )
    )
    val top = ModuleDef(
      "BooleanLocalUse",
      Vector.empty,
      Vector.empty,
      Vector(
        GenerateIf(
          BoolLocalParameterRef("ROUTE"),
          GenerateBlock("g_route", Vector(instance)),
          GenerateBlock("g_no_route", Vector.empty)
        )
      ),
      booleanLocalParameters = Vector(BooleanLocalParameter("ROUTE", BoolLiteral(true)))
    )

    val verilog = emit(Design(top.name, Vector(top, child)))
    assert(verilog.contains("if (ROUTE == 1) begin : g_route"), verilog)
    assert(verilog.contains(".SELECT((ROUTE == 1) ? 1 : 0)"), verilog)
  }

  test("checks signed 32-bit range inside Boolean-local comparison operands") {
    val maximum = BigInt(Int.MaxValue)
    val top = ModuleDef(
      "BooleanLocalRange",
      Vector(bounded("LIMIT", maximum, maximum, maximum)),
      Vector.empty,
      Vector.empty,
      booleanLocalParameters = Vector(
        BooleanLocalParameter(
          "OVERFLOW",
          GreaterThanOrEqual(Add(ParameterRef("LIMIT"), Literal(1)), Literal(0))
        )
      )
    )

    assertRangePath(Design(top.name, Vector(top)), "booleanLocalParameters")
  }

  test("checks signed 32-bit range in an IntExpr Select controlled by a Boolean local") {
    val maximum = BigInt(Int.MaxValue)
    val top = ModuleDef(
      "BooleanLocalSelectedRange",
      Vector(bounded("LIMIT", maximum, maximum, maximum)),
      Vector.empty,
      Vector.empty,
      localParameters = Vector(
        IntegerLocalParameter(
          "SELECTED",
          Select(
            BoolLocalParameterRef("ACTIVE"),
            Add(ParameterRef("LIMIT"), Literal(1)),
            Literal(0)
          )
        )
      ),
      booleanLocalParameters = Vector(BooleanLocalParameter("ACTIVE", BoolLiteral(false)))
    )

    assertRangePath(Design(top.name, Vector(top)), "whenTrue")
  }

  test("rejects reserved Boolean-local identifiers in the target profile") {
    val top = ModuleDef(
      "BooleanLocalReserved",
      Vector.empty,
      Vector.empty,
      Vector.empty,
      booleanLocalParameters = Vector(BooleanLocalParameter("always", BoolLiteral(true)))
    )

    Verilog2001Emitter.emit(Design(top.name, Vector(top))) match {
      case Left(diagnostics) =>
        assert(diagnostics.codes.contains("V2001-RESERVED-IDENTIFIER"), diagnostics.values.mkString("\n"))
      case Right(verilog) => fail(s"Expected target diagnostic, emitted:\n$verilog")
    }
  }

  private def bounded(name: String, default: BigInt, minimum: BigInt, maximum: BigInt): IntegerParameter =
    IntegerParameter(name, default, Vector(MinInclusive(minimum), MaxInclusive(maximum)))

  private def assertRangePath(design: Design, expectedPathPart: String): Unit =
    Verilog2001Emitter.emit(design) match {
      case Left(diagnostics) =>
        val failures = diagnostics.values.filter(_.code == "V2001-INTEGER-EXPRESSION-OUT-OF-RANGE")
        assert(failures.exists(_.path.contains(expectedPathPart)), failures.mkString("\n"))
      case Right(verilog) => fail(s"Expected portable-range diagnostic, emitted:\n$verilog")
    }

  private def emit(design: Design): String =
    Verilog2001Emitter.emit(design) match {
      case Right(value)      => value
      case Left(diagnostics) => fail(diagnostics.values.mkString("\n"))
    }
}
