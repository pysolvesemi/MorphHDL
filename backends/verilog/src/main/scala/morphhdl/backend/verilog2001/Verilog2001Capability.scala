package morphhdl.backend.verilog2001

import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}
import morphhdl.paramrtl.IntExpr.{
  Add,
  Divide,
  Literal,
  LocalParameterRef,
  Modulo,
  Multiply,
  Negate,
  ParameterRef,
  Subtract
}
import morphhdl.paramrtl._

object Verilog2001Capability {
  private val MinimumInteger = BigInt(Int.MinValue)
  private val MaximumInteger = BigInt(Int.MaxValue)

  private val ReservedWords = Set(
    "always",
    "and",
    "assign",
    "automatic",
    "begin",
    "buf",
    "bufif0",
    "bufif1",
    "case",
    "casex",
    "casez",
    "cell",
    "cmos",
    "config",
    "deassign",
    "default",
    "defparam",
    "design",
    "disable",
    "edge",
    "else",
    "end",
    "endcase",
    "endconfig",
    "endfunction",
    "endgenerate",
    "endmodule",
    "endprimitive",
    "endspecify",
    "endtable",
    "endtask",
    "event",
    "for",
    "force",
    "forever",
    "fork",
    "function",
    "generate",
    "genvar",
    "highz0",
    "highz1",
    "if",
    "ifnone",
    "incdir",
    "include",
    "initial",
    "inout",
    "input",
    "instance",
    "integer",
    "join",
    "large",
    "liblist",
    "library",
    "localparam",
    "macromodule",
    "medium",
    "module",
    "nand",
    "negedge",
    "nmos",
    "nor",
    "noshowcancelled",
    "notif0",
    "notif1",
    "or",
    "output",
    "parameter",
    "pmos",
    "posedge",
    "primitive",
    "pull0",
    "pull1",
    "pulldown",
    "pullup",
    "pulsestyle_ondetect",
    "pulsestyle_onevent",
    "rcmos",
    "real",
    "realtime",
    "reg",
    "release",
    "repeat",
    "rnmos",
    "rpmos",
    "rtran",
    "rtranif0",
    "rtranif1",
    "scalared",
    "showcancelled",
    "signed",
    "small",
    "specify",
    "specparam",
    "strong0",
    "strong1",
    "supply0",
    "supply1",
    "table",
    "task",
    "time",
    "tran",
    "tranif0",
    "tranif1",
    "tri",
    "tri0",
    "tri1",
    "triand",
    "trior",
    "trireg",
    "unsigned",
    "use",
    "vectored",
    "wait",
    "wand",
    "weak0",
    "weak1",
    "while",
    "wire",
    "wor",
    "xnor",
    "xor"
  )

  def verify(design: ValidatedDesign): Either[DiagnosticSet, ValidatedDesign] = {
    val diagnostics = Vector.newBuilder[Diagnostic]

    design.value.modules.sortBy(_.name).foreach { module =>
      val modulePath = Vector("modules", module.name)
      checkName(module.name, modulePath :+ "name", diagnostics)

      val parameters = module.parameters.sortBy(_.name)
      parameters.foreach { parameter =>
        val path = modulePath :+ "parameters" :+ parameter.name
        checkName(parameter.name, path :+ "name", diagnostics)
        checkInteger(parameter.default, path :+ "default", diagnostics)
        parameter.constraints.zipWithIndex.foreach {
          case (MinInclusive(value), index) =>
            checkInteger(value, path :+ "constraints" :+ index.toString, diagnostics)
          case (MaxInclusive(value), index) =>
            checkInteger(value, path :+ "constraints" :+ index.toString, diagnostics)
        }
        checkIntegerDomain(parameter, path :+ "constraints", diagnostics)
      }

      val facts = design.moduleFacts(module.name)

      module.localParameters.sortBy(_.name).foreach { localParameter =>
        val path = modulePath :+ "localParameters" :+ localParameter.name
        checkName(localParameter.name, path :+ "name", diagnostics)
        checkExpression(
          localParameter.value,
          facts.parameterFacts,
          facts.localParameterFacts,
          path :+ "value",
          diagnostics
        )
      }

      module.ports.sortBy(_.name).foreach { port =>
        val path = modulePath :+ "ports" :+ port.name
        checkName(port.name, path :+ "name", diagnostics)
        checkExpression(
          port.dataType.width,
          facts.parameterFacts,
          facts.localParameterFacts,
          path :+ "width",
          diagnostics
        )
      }
    }

    val result = DiagnosticSet.from(diagnostics.result())
    if (result.isEmpty) Right(design) else Left(result)
  }

  private def checkName(
      name: String,
      path: Vector[String],
      diagnostics: scala.collection.mutable.Builder[Diagnostic, Vector[Diagnostic]]
  ): Unit =
    if (ReservedWords.contains(name)) {
      diagnostics += Diagnostic(
        "V2001-RESERVED-IDENTIFIER",
        path,
        s"Identifier '$name' is reserved by IEEE 1364-2001"
      )
    }

  private def checkInteger(
      value: BigInt,
      path: Vector[String],
      diagnostics: scala.collection.mutable.Builder[Diagnostic, Vector[Diagnostic]]
  ): Unit =
    if (value < MinimumInteger || value > MaximumInteger) {
      diagnostics += Diagnostic(
        "V2001-INTEGER-OUT-OF-RANGE",
        path,
        s"Integer $value is outside the portable signed 32-bit Verilog integer range"
      )
    }

  private def checkIntegerDomain(
      parameter: IntegerParameter,
      path: Vector[String],
      diagnostics: scala.collection.mutable.Builder[Diagnostic, Vector[Diagnostic]]
  ): Unit = {
    val minimums = parameter.constraints.collect { case MinInclusive(value) => value }
    val maximums = parameter.constraints.collect { case MaxInclusive(value) => value }
    val lower = if (minimums.isEmpty) None else Some(minimums.max)
    val upper = if (maximums.isEmpty) None else Some(maximums.min)

    if (!lower.exists(_ >= MinimumInteger) || !upper.exists(_ <= MaximumInteger)) {
      diagnostics += Diagnostic(
        "V2001-INTEGER-DOMAIN-OUT-OF-RANGE",
        path,
        s"Legal domain of parameter '${parameter.name}' must be bounded within the signed 32-bit Verilog integer range"
      )
    }
  }

  private def checkExpression(
      expression: IntExpr,
      parameters: Map[String, IntExprFacts],
      localParameters: Map[String, IntExprFacts],
      path: Vector[String],
      diagnostics: scala.collection.mutable.Builder[Diagnostic, Vector[Diagnostic]]
  ): Unit = {
    IntExpressionAnalysis.analyze(expression, parameters, localParameters).toOption.foreach { facts =>
      val interval = facts.interval
      if (
        interval.lower.isEmpty ||
        interval.upper.isEmpty ||
        interval.lower.exists(_ < MinimumInteger) ||
        interval.upper.exists(_ > MaximumInteger)
      ) {
        val renderedDomain = (interval.lower, interval.upper) match {
          case (Some(lower), Some(upper)) => s"[$lower, $upper]"
          case _                          => "unbounded"
        }
        diagnostics += Diagnostic(
          "V2001-INTEGER-EXPRESSION-OUT-OF-RANGE",
          path,
          s"Integer expression domain $renderedDomain is outside the portable signed 32-bit Verilog integer range"
        )
      }
    }

    expression match {
      case Literal(_) | ParameterRef(_) | LocalParameterRef(_) =>
      case Negate(value) =>
        checkExpression(value, parameters, localParameters, path :+ "operand", diagnostics)
      case Add(left, right) =>
        checkExpression(left, parameters, localParameters, path :+ "left", diagnostics)
        checkExpression(right, parameters, localParameters, path :+ "right", diagnostics)
      case Subtract(left, right) =>
        checkExpression(left, parameters, localParameters, path :+ "left", diagnostics)
        checkExpression(right, parameters, localParameters, path :+ "right", diagnostics)
      case Multiply(left, right) =>
        checkExpression(left, parameters, localParameters, path :+ "left", diagnostics)
        checkExpression(right, parameters, localParameters, path :+ "right", diagnostics)
      case Divide(left, right) =>
        checkExpression(left, parameters, localParameters, path :+ "left", diagnostics)
        checkExpression(right, parameters, localParameters, path :+ "right", diagnostics)
      case Modulo(left, right) =>
        checkExpression(left, parameters, localParameters, path :+ "left", diagnostics)
        checkExpression(right, parameters, localParameters, path :+ "right", diagnostics)
    }
  }
}
