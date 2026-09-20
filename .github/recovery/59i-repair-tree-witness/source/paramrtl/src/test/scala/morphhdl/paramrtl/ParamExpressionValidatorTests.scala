package morphhdl.paramrtl

import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}
import morphhdl.paramrtl.IntExpr._
import morphhdl.paramrtl.ModuleItem.ContinuousAssign
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.RtlExpr.Ref
import morphhdl.paramrtl.Signedness.Unsigned
import org.scalatest.funsuite.AnyFunSuite

class ParamExpressionValidatorTests extends AnyFunSuite {
  test("validates a derived width and records deterministic topological local facts") {
    val normal = derivedWidthDesign(reverseLocalConstruction = false)
    val reversed = derivedWidthDesign(reverseLocalConstruction = true)

    val normalFacts = validatedFacts(normal)
    val reversedFacts = validatedFacts(reversed)

    assert(normalFacts.orderedLocalParameters.map(_.name) == Vector("TOTAL_WIDTH", "PADDED_WIDTH"))
    assert(reversedFacts.orderedLocalParameters.map(_.name) == Vector("TOTAL_WIDTH", "PADDED_WIDTH"))
    assert(normalFacts == reversedFacts)

    val total = normalFacts.localParameterFacts("TOTAL_WIDTH")
    assert(total.defaultValue == 32)
    assert(total.interval == interval(1, 128))

    val padded = normalFacts.localParameterFacts("PADDED_WIDTH")
    assert(padded.defaultValue == 35)
    assert(padded.interval == interval(4, 131))
  }

  test("rejects an unresolved local-parameter reference without cascading analysis errors") {
    val design = passthroughDesign(
      width = Literal(8),
      localParameters = Vector(
        IntegerLocalParameter("BROKEN", LocalParameterRef("MISSING"))
      )
    )

    val diagnostics = invalidDiagnostics(design)
    assert(diagnostics.codes == Vector("PRTL-UNRESOLVED-LOCAL-PARAMETER"))
    assert(
      diagnostics.values.head.pathString ==
        "modules/ParamExpressionTest/localParameters/BROKEN/value"
    )
  }

  test("rejects duplicate locals and every cross-kind declaration collision") {
    val design = passthroughDesign(
      width = ParameterRef("WIDTH"),
      parameters = Vector(boundedParameter("WIDTH", default = 8, minimum = 1, maximum = 16)),
      localParameters = Vector(
        IntegerLocalParameter("WIDTH", Literal(1)),
        IntegerLocalParameter("WIDTH", Literal(2)),
        IntegerLocalParameter("din", Literal(3))
      )
    )

    val diagnostics = invalidDiagnostics(design)
    assert(diagnostics.codes.count(_ == "PRTL-DUPLICATE-LOCAL-PARAMETER") == 1)
    assert(diagnostics.codes.count(_ == "PRTL-DUPLICATE-DECLARATION") == 2)
    assert(
      diagnostics.values.filter(_.code == "PRTL-DUPLICATE-DECLARATION").map(_.pathString) ==
        Vector(
          "modules/ParamExpressionTest/declarations/WIDTH",
          "modules/ParamExpressionTest/declarations/din"
        )
    )
  }

  test("reports self and mutual local-parameter cycles deterministically") {
    val design = passthroughDesign(
      width = Literal(8),
      localParameters = Vector(
        IntegerLocalParameter("SELF", LocalParameterRef("SELF")),
        IntegerLocalParameter("B", Add(LocalParameterRef("A"), Literal(1))),
        IntegerLocalParameter("A", LocalParameterRef("B"))
      )
    )

    val cycles = invalidDiagnostics(design).values.filter(_.code == "PRTL-LOCAL-PARAMETER-CYCLE")
    assert(
      cycles.map(_.pathString) == Vector(
        "modules/ParamExpressionTest/localParameters/A",
        "modules/ParamExpressionTest/localParameters/SELF"
      )
    )
    assert(
      cycles.map(_.message) == Vector(
        "Local-parameter dependency cycle members: A, B",
        "Local-parameter dependency cycle members: SELF"
      )
    )
  }

  test("reports non-alphabetical cycles as deterministic SCC membership") {
    def design(reverse: Boolean): Design = {
      val localParameters = Vector(
        IntegerLocalParameter("A", LocalParameterRef("C")),
        IntegerLocalParameter("B", LocalParameterRef("A")),
        IntegerLocalParameter("C", LocalParameterRef("B"))
      )
      passthroughDesign(
        width = Literal(8),
        localParameters = if (reverse) localParameters.reverse else localParameters
      )
    }

    val normal = invalidDiagnostics(design(reverse = false)).values
      .filter(_.code == "PRTL-LOCAL-PARAMETER-CYCLE")
    val reversed = invalidDiagnostics(design(reverse = true)).values
      .filter(_.code == "PRTL-LOCAL-PARAMETER-CYCLE")

    assert(normal == reversed)
    assert(normal.size == 1)
    assert(normal.head.pathString == "modules/ParamExpressionTest/localParameters/A")
    assert(normal.head.message == "Local-parameter dependency cycle members: A, B, C")
  }

  test("validates a long acyclic local chain without recursive or quadratic graph walks") {
    val localCount = 8000
    val names = (0 until localCount).map(index => f"LOCAL_$index%05d").toVector
    val localParameters = names.zipWithIndex.map { case (name, index) =>
      val value =
        if (index == 0) Literal(1)
        else Add(LocalParameterRef(names(index - 1)), Literal(1))
      IntegerLocalParameter(name, value)
    }

    val facts = validatedFacts(
      passthroughDesign(
        width = Literal(8),
        localParameters = localParameters.reverse
      )
    )

    assert(facts.orderedLocalParameters.map(_.name) == names)
    assert(facts.localParameterFacts(names.last) == IntExprFacts(localCount, interval(localCount, localCount)))
  }

  test("rejects a derived packed width that is nonpositive for legal overrides") {
    val width = LocalParameterRef("DERIVED_WIDTH")
    val design = passthroughDesign(
      width = width,
      parameters = Vector(boundedParameter("WIDTH", default = 4, minimum = 1, maximum = 4)),
      localParameters = Vector(
        IntegerLocalParameter("DERIVED_WIDTH", Subtract(ParameterRef("WIDTH"), Literal(4)))
      )
    )

    val diagnostics = invalidDiagnostics(design)
    val widthDiagnostics = diagnostics.values.filter(_.code == "PRTL-WIDTH-NOT-PROVEN-POSITIVE")
    assert(widthDiagnostics.size == 2)
    assert(widthDiagnostics.forall(_.message.contains("[-3, 0]")))
  }

  test("rejects divide and modulo when the legal denominator domain contains zero") {
    val design = passthroughDesign(
      width = Literal(8),
      parameters = Vector(boundedParameter("DENOMINATOR", default = 1, minimum = -1, maximum = 1)),
      localParameters = Vector(
        IntegerLocalParameter("QUOTIENT", Divide(Literal(10), ParameterRef("DENOMINATOR"))),
        IntegerLocalParameter("REMAINDER", Modulo(Literal(10), ParameterRef("DENOMINATOR")))
      )
    )

    val diagnostics = invalidDiagnostics(design).values.filter(_.code == "PRTL-DIVISOR-MAY-BE-ZERO")
    assert(diagnostics.size == 2)
    assert(
      diagnostics.map(_.pathString) == Vector(
        "modules/ParamExpressionTest/localParameters/QUOTIENT/value",
        "modules/ParamExpressionTest/localParameters/REMAINDER/value"
      )
    )
    assert(diagnostics.map(_.message).forall(_.contains("[-1, 1]")))
  }

  test("does not cascade expression diagnostics from an illegal parameter default") {
    val design = passthroughDesign(
      width = Literal(8),
      parameters = Vector(boundedParameter("DENOMINATOR", default = 0, minimum = 1, maximum = 4)),
      localParameters = Vector(
        IntegerLocalParameter("QUOTIENT", Divide(Literal(10), ParameterRef("DENOMINATOR")))
      )
    )

    val diagnostics = invalidDiagnostics(design)
    assert(diagnostics.codes == Vector("PRTL-DEFAULT-VIOLATES-CONSTRAINT"))
  }

  test("uses signed truncation-toward-zero divide and dividend-signed modulo semantics") {
    val design = passthroughDesign(
      width = Literal(8),
      parameters = Vector(
        boundedParameter("NUMERATOR", default = -7, minimum = -7, maximum = -5),
        boundedParameter("DENOMINATOR", default = 3, minimum = 2, maximum = 3)
      ),
      localParameters = Vector(
        IntegerLocalParameter(
          "QUOTIENT",
          Divide(ParameterRef("NUMERATOR"), ParameterRef("DENOMINATOR"))
        ),
        IntegerLocalParameter(
          "REMAINDER",
          Modulo(ParameterRef("NUMERATOR"), ParameterRef("DENOMINATOR"))
        )
      )
    )

    val facts = validatedFacts(design).localParameterFacts
    assert(facts("QUOTIENT") == IntExprFacts(-2, interval(-3, -1)))
    assert(facts("REMAINDER") == IntExprFacts(-1, interval(-2, 0)))
  }

  test("interval analysis soundly contains every value in small finite domains") {
    val expressions = Vector[IntExpr](
      Add(ParameterRef("LEFT"), ParameterRef("RIGHT")),
      Subtract(ParameterRef("LEFT"), ParameterRef("RIGHT")),
      Multiply(ParameterRef("LEFT"), ParameterRef("RIGHT")),
      Divide(ParameterRef("LEFT"), ParameterRef("POSITIVE")),
      Modulo(ParameterRef("LEFT"), ParameterRef("POSITIVE")),
      Negate(ParameterRef("LEFT"))
    )

    val domains = Vector(
      (-4 to 4, -3 to 3, 1 to 4),
      (-7 to -2, 2 to 5, 2 to 3),
      (0 to 6, -5 to -1, 1 to 5)
    )

    domains.foreach { case (leftDomain, rightDomain, positiveDomain) =>
      val parameters = Map(
        "LEFT" -> facts(leftDomain.start, leftDomain.end),
        "RIGHT" -> facts(rightDomain.start, rightDomain.end),
        "POSITIVE" -> facts(positiveDomain.start, positiveDomain.end)
      )

      expressions.foreach { expression =>
        val analyzed = IntExpressionAnalysis.analyze(expression, parameters, Map.empty) match {
          case Right(value) => value.interval
          case Left(error)  => fail(s"Unexpected analysis failure for $expression: $error")
        }
        for {
          left <- leftDomain
          right <- rightDomain
          positive <- positiveDomain
        } {
          val actual = evaluate(
            expression,
            Map(
              "LEFT" -> BigInt(left),
              "RIGHT" -> BigInt(right),
              "POSITIVE" -> BigInt(positive)
            )
          )
          assert(
            analyzed.contains(actual),
            s"$expression produced $actual outside $analyzed for LEFT=$left RIGHT=$right POSITIVE=$positive"
          )
        }
      }
    }
  }

  test("diagnostics are stable, sorted, and independent of declaration construction order") {
    def invalid(reverse: Boolean): Design = {
      val locals = Vector(
        IntegerLocalParameter("Z_LOCAL", LocalParameterRef("Z_MISSING")),
        IntegerLocalParameter("A_LOCAL", LocalParameterRef("A_MISSING"))
      )
      passthroughDesign(
        width = ParameterRef("MISSING_WIDTH"),
        localParameters = if (reverse) locals.reverse else locals
      )
    }

    val normal = invalidDiagnostics(invalid(reverse = false))
    val reversed = invalidDiagnostics(invalid(reverse = true))

    assert(normal.values == reversed.values)
    assert(
      normal.values == normal.values.sortBy(diagnostic => (diagnostic.pathString, diagnostic.code, diagnostic.message))
    )
    assert(
      normal.codes == Vector(
        "PRTL-UNRESOLVED-LOCAL-PARAMETER",
        "PRTL-UNRESOLVED-LOCAL-PARAMETER",
        "PRTL-UNRESOLVED-PARAMETER",
        "PRTL-UNRESOLVED-PARAMETER"
      )
    )
  }

  private def derivedWidthDesign(reverseLocalConstruction: Boolean): Design = {
    val total = IntegerLocalParameter(
      "TOTAL_WIDTH",
      Multiply(ParameterRef("LANES"), ParameterRef("DATA_WIDTH"))
    )
    val padded = IntegerLocalParameter(
      "PADDED_WIDTH",
      Add(LocalParameterRef("TOTAL_WIDTH"), Literal(3))
    )
    val localParameters = Vector(padded, total)

    passthroughDesign(
      width = LocalParameterRef("PADDED_WIDTH"),
      parameters = Vector(
        boundedParameter("LANES", default = 4, minimum = 1, maximum = 8),
        boundedParameter("DATA_WIDTH", default = 8, minimum = 1, maximum = 16)
      ),
      localParameters = if (reverseLocalConstruction) localParameters.reverse else localParameters
    )
  }

  private def passthroughDesign(
      width: IntExpr,
      parameters: Vector[IntegerParameter] = Vector.empty,
      localParameters: Vector[IntegerLocalParameter] = Vector.empty
  ): Design = {
    val packed = PackedBits(width, Unsigned)
    Design(
      top = "ParamExpressionTest",
      modules = Vector(
        ModuleDef(
          name = "ParamExpressionTest",
          parameters = parameters,
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

  private def boundedParameter(
      name: String,
      default: BigInt,
      minimum: BigInt,
      maximum: BigInt
  ): IntegerParameter =
    IntegerParameter(name, default, Vector(MinInclusive(minimum), MaxInclusive(maximum)))

  private def interval(lower: BigInt, upper: BigInt): IntInterval =
    IntInterval.bounded(lower, upper).get

  private def facts(lower: BigInt, upper: BigInt): IntExprFacts =
    IntExprFacts(lower, interval(lower, upper))

  private def evaluate(expression: IntExpr, values: Map[String, BigInt]): BigInt =
    expression match {
      case Literal(value)          => value
      case ParameterRef(name)      => values(name)
      case LocalParameterRef(name) => values(name)
      case GenerateIndexRef(name)  => values(name)
      case Negate(value)           => -evaluate(value, values)
      case Add(left, right)        => evaluate(left, values) + evaluate(right, values)
      case Subtract(left, right)   => evaluate(left, values) - evaluate(right, values)
      case Multiply(left, right)   => evaluate(left, values) * evaluate(right, values)
      case Divide(left, right)     => evaluate(left, values) / evaluate(right, values)
      case Modulo(left, right)     => evaluate(left, values) % evaluate(right, values)
    }

  private def validatedFacts(design: Design): ValidatedModuleFacts =
    ParamRtlValidator.validate(design) match {
      case Right(validated)  => validated.moduleFacts(design.top)
      case Left(diagnostics) => fail(diagnostics.values.mkString("\n"))
    }

  private def invalidDiagnostics(design: Design): DiagnosticSet =
    ParamRtlValidator.validate(design) match {
      case Left(diagnostics) => diagnostics
      case Right(_)          => fail("Expected ParamRTL validation to fail")
    }
}
