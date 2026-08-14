package morphhdl.paramrtl

import morphhdl.paramrtl.BoolExpr.{Literal => BoolLiteral}
import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}
import morphhdl.paramrtl.IntExpr.{Add, CeilLog2, Literal, LocalParameterRef, ParameterRef, Select}
import morphhdl.paramrtl.ModuleItem.{ContinuousAssign, GenerateFor, ModuleInstance}
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.RtlExpr.Ref
import morphhdl.paramrtl.Signedness.Unsigned
import org.scalatest.funsuite.AnyFunSuite

class CeilLog2ExpressionTests extends AnyFunSuite {
  test("computes exact values at power-of-two boundaries") {
    Vector(
      BigInt(1) -> BigInt(0),
      BigInt(2) -> BigInt(1),
      BigInt(3) -> BigInt(2),
      BigInt(4) -> BigInt(2),
      BigInt(5) -> BigInt(3),
      BigInt(8) -> BigInt(3),
      BigInt(Int.MaxValue) -> BigInt(31)
    ).foreach { case (value, expected) =>
      assert(analyze(CeilLog2(Literal(value)), Map.empty) == Right(point(expected)))
    }
  }

  test("maps positive intervals monotonically and distinguishes address-width semantics") {
    val parameters = Map(
      "VALUE" -> IntExprFacts(5, IntInterval(Some(1), Some(BigInt(Int.MaxValue))))
    )

    assert(
      analyze(CeilLog2(ParameterRef("VALUE")), parameters) ==
        Right(IntExprFacts(3, IntInterval(Some(0), Some(31))))
    )
    assert(analyze(CeilLog2(Literal(1)), Map.empty) == Right(point(0)))
    assert(analyze(IntExpr.AddressWidth(Literal(1)), Map.empty) == Right(point(1)))
  }

  test("rejects nonpositive defaults domains and inactive unsafe branches") {
    assertCeilLog2Diagnostic(localDesign(CeilLog2(Literal(0))))
    assertCeilLog2Diagnostic(
      localDesign(
        CeilLog2(ParameterRef("VALUE")),
        Vector(boundedParameter("VALUE", default = 2, minimum = 0, maximum = 4))
      )
    )
    assertCeilLog2Diagnostic(
      localDesign(CeilLog2(Select(BoolLiteral(true), Literal(4), Literal(0))))
    )
  }

  test("discovers references and preserves CeilLog2 through substitution") {
    val expression = CeilLog2(Add(ParameterRef("PUBLIC"), LocalParameterRef("LOCAL")))

    assert(IntExpressionAnalysis.parameterReferences(expression) == Vector("PUBLIC"))
    assert(IntExpressionAnalysis.localParameterReferences(expression) == Vector("LOCAL"))
    assert(
      IntExpressionEquivalence.substitute(
        expression,
        Map("PUBLIC" -> Literal(3)),
        Map("LOCAL" -> Literal(2))
      ) == CeilLog2(Add(Literal(3), Literal(2)))
    )
    assert(IntExpressionEquivalence.equivalent(CeilLog2(Literal(4)), Literal(2)))
    assert(!IntExpressionEquivalence.equivalent(CeilLog2(Literal(0)), Literal(0)))
  }

  test("keeps zero legal for expressions locals and bindings while consumers enforce positivity") {
    val lanes = boundedParameter("LANES", default = 1, minimum = 1, maximum = 8)
    val selectedWidth = CeilLog2(ParameterRef("LANES"))
    assert(ParamRtlValidator.validate(localDesign(selectedWidth, Vector(lanes))).isRight)

    val child = ModuleDef(
      "CeilLog2Child",
      Vector(boundedParameter("SELECT_WIDTH", default = 0, minimum = 0, maximum = 3)),
      Vector.empty,
      Vector.empty
    )
    val parent = ModuleDef(
      "CeilLog2Parent",
      Vector(lanes),
      Vector.empty,
      Vector(
        ModuleInstance(
          "child_inst",
          child.name,
          parameterBindings = Vector(ParameterBinding("SELECT_WIDTH", selectedWidth))
        )
      )
    )
    assert(ParamRtlValidator.validate(Design(parent.name, Vector(parent, child))).isRight)

    val unsafeWidth = identityDesign(selectedWidth, Vector(lanes))
    val unsafeWidthDiagnostics = diagnostics(unsafeWidth)
    assert(unsafeWidthDiagnostics.codes.contains("PRTL-WIDTH-NOT-PROVEN-POSITIVE"))

    val safeLanes = boundedParameter("LANES", default = 2, minimum = 2, maximum = 8)
    assert(ParamRtlValidator.validate(identityDesign(selectedWidth, Vector(safeLanes))).isRight)

    val generate = ModuleDef(
      "CeilLog2GenerateCount",
      Vector(lanes),
      Vector.empty,
      Vector(GenerateFor("g_lane", "i", selectedWidth, Vector.empty))
    )
    assert(
      diagnostics(Design(generate.name, Vector(generate))).codes
        .contains("PRTL-GENERATE-COUNT-NOT-PROVEN-POSITIVE")
    )
  }

  test("analyzes deep and shared CeilLog2 operands without consuming the call stack") {
    var deep: IntExpr = ParameterRef("BASE")
    (1 to 1200).foreach { _ => deep = Add(deep, Literal(1)) }
    val expression = CeilLog2(Add(deep, deep))
    val parameters = Map("BASE" -> IntExprFacts(1, IntInterval(Some(1), Some(1))))

    assert(analyze(expression, parameters) == Right(point(12)))
    assert(IntExpressionAnalysis.parameterReferences(expression) == Vector("BASE"))
    assert(IntExpressionEquivalence.equivalent(expression, CeilLog2(Add(deep, deep))))
  }

  private def point(value: BigInt): IntExprFacts = IntExprFacts(value, IntInterval.point(value))

  private def analyze(
      expression: IntExpr,
      parameters: Map[String, IntExprFacts]
  ): Either[IntExpressionFailure, IntExprFacts] =
    IntExpressionAnalysis.analyze(expression, parameters, Map.empty)

  private def boundedParameter(
      name: String,
      default: BigInt,
      minimum: BigInt,
      maximum: BigInt
  ): IntegerParameter =
    IntegerParameter(name, default, Vector(MinInclusive(minimum), MaxInclusive(maximum)))

  private def localDesign(
      expression: IntExpr,
      parameters: Vector[IntegerParameter] = Vector.empty
  ): Design = {
    val module = ModuleDef(
      "CeilLog2Local",
      parameters,
      Vector.empty,
      Vector.empty,
      localParameters = Vector(IntegerLocalParameter("VALUE", expression))
    )
    Design(module.name, Vector(module))
  }

  private def identityDesign(
      width: IntExpr,
      parameters: Vector[IntegerParameter]
  ): Design = {
    val packed = PackedBits(width, Unsigned)
    val module = ModuleDef(
      "CeilLog2Width",
      parameters,
      Vector(Port("din", Input, packed), Port("dout", Output, packed)),
      Vector(ContinuousAssign(Ref("dout"), Ref("din")))
    )
    Design(module.name, Vector(module))
  }

  private def assertCeilLog2Diagnostic(design: Design): Unit = {
    val result = diagnostics(design)
    assert(
      result.codes.contains("PRTL-CEIL-LOG2-OPERAND-NOT-PROVEN-POSITIVE"),
      result.values.mkString("\n")
    )
  }

  private def diagnostics(design: Design): DiagnosticSet =
    ParamRtlValidator.validate(design) match {
      case Left(value)  => value
      case Right(value) => fail(s"Expected diagnostics, validated: $value")
    }
}
