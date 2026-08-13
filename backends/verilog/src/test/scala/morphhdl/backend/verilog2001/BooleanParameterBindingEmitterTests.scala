package morphhdl.backend.verilog2001

import morphhdl.paramrtl.BoolExpr.{
  And,
  GreaterThan,
  Literal => BoolLiteral,
  ParameterRef => BoolParameterRef
}
import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}
import morphhdl.paramrtl.IntExpr.{Add, Literal, ParameterRef}
import morphhdl.paramrtl.ModuleItem.ModuleInstance
import morphhdl.paramrtl._
import org.scalatest.funsuite.AnyFunSuite

class BooleanParameterBindingEmitterTests extends AnyFunSuite {
  test("emits Boolean literals as canonical integer parameter associations") {
    val verilog = emit(parentWithBindings(Vector(BooleanParameterBinding("ENABLE", BoolLiteral(true)))))

    assert(verilog.contains(".ENABLE(1)"), verilog)
    assert(!verilog.contains(".ENABLE(1'b1)"), verilog)
  }

  test("emits forwarded and compound Boolean bindings as strict Verilog-2001 expressions") {
    val parent = parentWithBindings(
      Vector(
        BooleanParameterBinding(
          "ENABLE",
          And(BoolParameterRef("ALLOW"), GreaterThan(ParameterRef("LIMIT"), Literal(0)))
        )
      ),
      parentIntegerParameters = Vector(
        IntegerParameter("LIMIT", 1, Vector(MinInclusive(0), MaxInclusive(8)))
      ),
      parentBooleanParameters = Vector(BooleanParameter("ALLOW", default = true))
    )
    val verilog = emit(parent)

    assert(verilog.contains(".ENABLE((ALLOW == 1 && LIMIT > 0) ? 1 : 0)"), verilog)
  }

  test("sorts integer and Boolean child bindings together deterministically") {
    val child = bindingChild
    def design(integerFirst: Boolean): Design = {
      val integerBindings = Vector(ParameterBinding("ALPHA", Literal(3)))
      val booleanBindings = Vector(BooleanParameterBinding("ENABLE", BoolLiteral(false)))
      val instance =
        if (integerFirst)
          ModuleInstance(
            "child",
            child.name,
            parameterBindings = integerBindings,
            booleanParameterBindings = booleanBindings
          )
        else
          ModuleInstance(
            "child",
            child.name,
            booleanParameterBindings = booleanBindings,
            parameterBindings = integerBindings
          )
      val top = ModuleDef("SortedBindingParent", Vector.empty, Vector.empty, Vector(instance))
      Design(top.name, Vector(top, child))
    }

    val normal = emit(design(integerFirst = true))
    val reordered = emit(design(integerFirst = false))
    assert(normal == reordered)
    assert(normal.indexOf(".ALPHA(3)") < normal.indexOf(".ENABLE(0)"), normal)
  }

  test("checks portable integer range inside Boolean binding comparisons") {
    val maximum = BigInt(Int.MaxValue)
    val design = parentWithBindings(
      Vector(
        BooleanParameterBinding(
          "ENABLE",
          GreaterThan(Add(ParameterRef("LIMIT"), Literal(1)), Literal(0))
        )
      ),
      parentIntegerParameters = Vector(
        IntegerParameter("LIMIT", maximum, Vector(MinInclusive(maximum), MaxInclusive(maximum)))
      )
    )

    Verilog2001Emitter.emit(design) match {
      case Left(diagnostics) =>
        val failures = diagnostics.values.filter(_.code == "V2001-INTEGER-EXPRESSION-OUT-OF-RANGE")
        assert(failures.exists(_.path.contains("booleanParameterBindings")), failures.mkString("\n"))
      case Right(verilog) => fail(s"Expected portable-range diagnostic, emitted:\n$verilog")
    }
  }

  private def parentWithBindings(
      booleanBindings: Vector[BooleanParameterBinding],
      parentIntegerParameters: Vector[IntegerParameter] = Vector.empty,
      parentBooleanParameters: Vector[BooleanParameter] = Vector.empty
  ): Design = {
    val child = bindingChild
    val parent = ModuleDef(
      "BooleanBindingParent",
      parentIntegerParameters,
      Vector.empty,
      Vector(
        ModuleInstance(
          "child",
          child.name,
          booleanParameterBindings = booleanBindings
        )
      ),
      booleanParameters = parentBooleanParameters
    )
    Design(parent.name, Vector(parent, child))
  }

  private def bindingChild: ModuleDef =
    ModuleDef(
      "BooleanBindingChild",
      Vector(IntegerParameter("ALPHA", 1, Vector(MinInclusive(0), MaxInclusive(8)))),
      Vector.empty,
      Vector.empty,
      booleanParameters = Vector(BooleanParameter("ENABLE", default = false))
    )

  private def emit(design: Design): String =
    Verilog2001Emitter.emit(design) match {
      case Right(value)      => value
      case Left(diagnostics) => fail(diagnostics.values.mkString("\n"))
    }
}
