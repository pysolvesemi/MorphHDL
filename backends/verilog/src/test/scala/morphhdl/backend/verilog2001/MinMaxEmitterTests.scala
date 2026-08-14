package morphhdl.backend.verilog2001

import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}
import morphhdl.paramrtl.BoolExpr.{ParameterRef => BoolParameterRef}
import morphhdl.paramrtl.IntExpr.{Add, AddressWidth, Literal, Max, Min, Negate, ParameterRef, Select}
import morphhdl.paramrtl.ModuleItem.ContinuousAssign
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.RtlExpr.Ref
import morphhdl.paramrtl.Signedness.Unsigned
import morphhdl.paramrtl._
import org.scalatest.funsuite.AnyFunSuite

class MinMaxEmitterTests extends AnyFunSuite {
  test("lowers Min and Max with canonical strict Verilog-2001 ternaries") {
    val parameters = Vector(
      boundedParameter("LEFT", 8, 1, 32),
      boundedParameter("RIGHT", 4, 1, 32)
    )
    val minimum = emit(localDesign(Min(ParameterRef("LEFT"), Literal(5)), parameters))
    val maximum = emit(localDesign(Max(ParameterRef("RIGHT"), Literal(5)), parameters))

    assert(minimum.contains("localparam integer SELECTED = (LEFT < 5) ? LEFT : 5;"))
    assert(maximum.contains("localparam integer SELECTED = (RIGHT > 5) ? RIGHT : 5;"))
    assert(!minimum.contains("$clog2"))
    assert(!maximum.contains("$clog2"))
  }

  test("parenthesizes nested extrema and surrounding arithmetic deterministically") {
    val expression = Add(
      Min(
        Add(ParameterRef("LEFT"), Literal(1)),
        Max(ParameterRef("RIGHT"), Literal(4))
      ),
      Literal(2)
    )
    val verilog = emit(
      localDesign(
        expression,
        Vector(
          boundedParameter("LEFT", 8, 1, 16),
          boundedParameter("RIGHT", 4, 1, 16)
        )
      )
    )

    assert(
      verilog.contains(
        "localparam integer SELECTED = ((LEFT + 1 < ((RIGHT > 4) ? RIGHT : 4)) ? LEFT + 1 : ((RIGHT > 4) ? RIGHT : 4)) + 2;"
      )
    )
  }

  test("renders an admitted skinny extremum expression without recursive stack growth") {
    var skinny: IntExpr = ParameterRef("BASE")
    (1 to 900).foreach { _ => skinny = Add(skinny, Literal(1)) }
    val verilog = emit(
      localDesign(
        Min(skinny, Literal(1024)),
        Vector(boundedParameter("BASE", 1, 1, 1))
      )
    )

    assert(verilog.contains("localparam integer SELECTED = (BASE + 1 + 1"))
    assert(verilog.contains(" < 1024) ? BASE + 1 + 1"))
  }

  test("admits exactly 4096 lowering nodes and rejects the first larger boundary") {
    val below = Min(addChain(1021), Negate(Literal(1)))
    val atLimit = Min(addChain(1022), Negate(Literal(1)))
    val above = Min(addChain(1023), Negate(Literal(1)))
    val limit = Verilog2001IntExpressionLowering.MaximumExpansionNodes

    assert(Verilog2001IntExpressionLowering.expansionWithin(below, limit))
    assert(Verilog2001IntExpressionLowering.expansionWithin(atLimit, limit))
    assert(!Verilog2001IntExpressionLowering.expansionWithin(above, limit))
    assert(emit(localDesign(atLimit, Vector(boundedParameter("BASE", 1, 1, 1)))).nonEmpty)

    val design = localDesign(above, Vector(boundedParameter("BASE", 1, 1, 1)))
    val first = diagnostics(design)
    val second = diagnostics(design)
    assert(first == second)
    assert(first.codes == Vector("V2001-MIN-MAX-EXPANSION-TOO-LARGE"))
  }

  test("rejects a small shared DAG before its duplicated text can grow exponentially") {
    var shared: IntExpr = ParameterRef("BASE")
    (1 to 6).foreach { index =>
      shared = if ((index & 1) == 0) Max(shared, shared) else Min(shared, shared)
    }
    val design = localDesign(shared, Vector(boundedParameter("BASE", 8, 1, 16)))

    assert(diagnostics(design).codes.contains("V2001-MIN-MAX-EXPANSION-TOO-LARGE"))
  }

  test("counts every repeated address-width syntax node inside shared extrema") {
    var mixed: IntExpr = AddressWidth(ParameterRef("DEPTH"))
    (1 to 3).foreach { _ => mixed = Min(mixed, mixed) }
    val limit = Verilog2001IntExpressionLowering.MaximumExpansionNodes
    val design = localDesign(
      mixed,
      Vector(boundedParameter("DEPTH", 5, 1, Int.MaxValue))
    )

    assert(!Verilog2001IntExpressionLowering.expansionWithin(mixed, limit))
    assert(diagnostics(design).codes == Vector("V2001-MIN-MAX-EXPANSION-TOO-LARGE"))
  }

  test("counts emitted Boolean parameter comparisons before Min duplication") {
    val selected = Select(BoolParameterRef("FLAG"), Literal(1), Literal(2))
    val mixed = Min(addChain(1021), selected)
    val limit = Verilog2001IntExpressionLowering.MaximumExpansionNodes
    val design = localDesign(
      mixed,
      Vector(boundedParameter("BASE", 1, 1, 1)),
      booleanParameters = Vector(BooleanParameter("FLAG", default = true))
    )

    assert(!Verilog2001IntExpressionLowering.expansionWithin(mixed, limit))
    assert(diagnostics(design).codes == Vector("V2001-MIN-MAX-EXPANSION-TOO-LARGE"))
  }

  test("counts unary-minus syntax for negative literals before Min duplication") {
    var negativeChain: IntExpr = ParameterRef("BASE")
    (1 to 500).foreach { _ => negativeChain = Add(negativeChain, Literal(-1)) }
    val mixed = Min(negativeChain, negativeChain)
    val limit = Verilog2001IntExpressionLowering.MaximumExpansionNodes
    val design = localDesign(
      mixed,
      Vector(boundedParameter("BASE", 1000, 1000, 1000))
    )

    assert(!Verilog2001IntExpressionLowering.expansionWithin(mixed, limit))
    assert(diagnostics(design).codes == Vector("V2001-MIN-MAX-EXPANSION-TOO-LARGE"))
  }

  private def addChain(depth: Int): IntExpr = {
    var expression: IntExpr = ParameterRef("BASE")
    (1 to depth).foreach { _ => expression = Add(expression, Literal(1)) }
    expression
  }

  private def localDesign(
      expression: IntExpr,
      integerParameters: Vector[IntegerParameter],
      booleanParameters: Vector[BooleanParameter] = Vector.empty
  ): Design = {
    val packed = PackedBits(Literal(8), Unsigned)
    val top = ModuleDef(
      "MinMaxLocal",
      integerParameters,
      Vector(Port("din", Input, packed), Port("dout", Output, packed)),
      Vector(ContinuousAssign(Ref("dout"), Ref("din"))),
      localParameters = Vector(IntegerLocalParameter("SELECTED", expression)),
      booleanParameters = booleanParameters
    )
    Design(top.name, Vector(top))
  }

  private def boundedParameter(
      name: String,
      default: BigInt,
      minimum: BigInt,
      maximum: BigInt
  ): IntegerParameter =
    IntegerParameter(name, default, Vector(MinInclusive(minimum), MaxInclusive(maximum)))

  private def emit(design: Design): String =
    Verilog2001Emitter.emit(design) match {
      case Right(value)      => value
      case Left(failures)    => fail(failures.values.mkString("\n"))
    }

  private def diagnostics(design: Design): DiagnosticSet =
    Verilog2001Emitter.emit(design) match {
      case Left(failures) => failures
      case Right(verilog) => fail(s"Expected diagnostic, emitted:\n$verilog")
    }
}
