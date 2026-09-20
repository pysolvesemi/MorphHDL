package spinal.core.internals

import java.util.regex.{Matcher, Pattern}

import scala.collection.mutable

import spinal.core._

/** Generic strict-Verilog-2001 publication for identity-retained finite folds.
  *
  * This pass knows only typed packed sources, result assignments and exact
  * counts. It has no knowledge of StreamFifo, storage names or user component
  * classes.
  */
private[internals] object ParameterizedVerilogFiniteFolds {
  private val PortableIdentifier = "[A-Za-z_][A-Za-z0-9_$]*".r

  def hasFolds(component: Component): Boolean =
    component != null && ElabFiniteRange.countOnesOf(component).nonEmpty

  def rewrite(
      component: Component,
      verilog: String,
      pc: PhaseContext
  ): String = {
    val folds = ElabFiniteRange.countOnesOf(component)
    if (folds.isEmpty) return verilog
    if (pc == null || pc.config == null)
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-FINITE-FOLD-PHASE-MISSING",
        "typed finite-fold publication requires an active phase context"
      )

    val graphAssignments = mutable.ArrayBuffer.empty[DataAssignmentStatement]
    component.dslBody.walkStatements {
      case value: DataAssignmentStatement => graphAssignments += value
      case _                              =>
    }

    folds.foldLeft(verilog) { case (current, fold) =>
      val exactTargetAssignments = graphAssignments.filter { assignment =>
        assignment.finalTarget eq fold.result
      }.toVector
      if (
        exactTargetAssignments.size != 1 ||
        (exactTargetAssignments.head ne fold.assignment)
      )
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-FINITE-FOLD-ANCHOR-CARDINALITY-MISMATCH",
          s"typed finite-fold result has ${exactTargetAssignments.size} native assignments; exactly its one retained anchor is required",
          fold.sourceLocation
        )
      if (fold.assignment.finalTarget ne fold.result)
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-FINITE-FOLD-TARGET-MISMATCH",
          "typed finite-fold anchor no longer targets its retained result",
          fold.sourceLocation
        )
      if (!isCanonicalZero(fold.assignment.source))
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-FINITE-FOLD-ANCHOR-NONZERO",
          "typed finite-fold anchor is no longer the retained canonical zero assignment",
          fold.sourceLocation
        )
      val retainedWidth = ParameterizedWidth.expressionOf(fold.result).getOrElse {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-FINITE-FOLD-RESULT-WIDTH-MISSING",
          "typed finite-fold result lost its exact width",
          fold.sourceLocation
        )
      }
      if (!ElabInt.equivalentExpression(retainedWidth, fold.resultWidth))
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-FINITE-FOLD-RESULT-WIDTH-MISMATCH",
          s"typed finite-fold result width '${retainedWidth.verilog}' differs from retained width '${fold.resultWidth.verilog}'",
          fold.sourceLocation
        )

      val source = requiredName(fold.source, "finite-fold source", fold.sourceLocation)
      val result = requiredName(fold.result, "finite-fold result", fold.sourceLocation)
      val declaration =
        ("(?m)^(\\s*)(wire|reg)(\\s+(?:signed\\s+)?(?:\\[[^\\]]+\\]\\s+)?)" +
          Pattern.quote(result) + "(\\s*;[^\\n]*)$").r
      val declarations = declaration.findAllMatchIn(current).toVector
      if (declarations.size != 1)
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-FINITE-FOLD-DECLARATION-NOT-FOUND",
          s"native Verilog contains ${declarations.size} declarations for typed finite-fold result '$result'",
          fold.sourceLocation
        )
      val declaredAs = declarations.head.group(2)

      val assignment =
        ("(?m)^(\\s*)assign\\s+" + Pattern.quote(result) +
          "\\s*=\\s*(?:(?:[0-9]+\\s*)?'[bBoOdDhH][0_]+|0)\\s*;\\s*$").r
      val assignments = assignment.findAllMatchIn(current).toVector
      if (assignments.size != 1)
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-FINITE-FOLD-ASSIGNMENT-NOT-FOUND",
          s"native Verilog contains ${assignments.size} continuous assignments for typed finite-fold result '$result'",
          fold.sourceLocation
        )

      val loop = uniqueLoopName(current, fold.ordinal)
      val indentation = assignments.head.group(1)
      val replacement = Vector(
        s"${indentation}integer $loop;",
        s"${indentation}always @(*) begin",
        s"${indentation}  $result = 0;",
        s"${indentation}  for ($loop = 0; $loop < ${fold.count.verilog}; $loop = $loop + 1) begin",
        s"${indentation}    $result = $result + $source[$loop];",
        s"${indentation}  end",
        s"${indentation}end"
      ).mkString("\n")
      val withProcess = assignment.replaceFirstIn(
        current,
        Matcher.quoteReplacement(replacement)
      )
      if (declaredAs == "reg") withProcess
      else {
        val matched = declaration.findFirstMatchIn(withProcess).getOrElse {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-FINITE-FOLD-DECLARATION-NOT-FOUND",
            s"typed finite-fold result declaration '$result' disappeared during rewrite",
            fold.sourceLocation
          )
        }
        val replacement =
          matched.group(1) + "reg" + matched.group(3) + result + matched.group(4)
        withProcess.substring(0, matched.start) + replacement +
          withProcess.substring(matched.end)
      }
    }
  }

  private def uniqueLoopName(verilog: String, ordinal: Long): String = {
    val base = s"morphhdl_finite_fold_index_$ordinal"
    var value = base
    var suffix = 1
    while (containsName(verilog, value)) {
      value = s"${base}_$suffix"
      suffix += 1
    }
    value
  }

  private def requiredName(
      value: Nameable,
      role: String,
      sourceLocation: Option[String]
  ): String = {
    val name = Option(value).flatMap(item => Option(item.getName())).getOrElse("")
    if (!PortableIdentifier.pattern.matcher(name).matches())
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-FINITE-FOLD-NAME-MISSING",
        s"$role has no portable emitted name",
        sourceLocation
      )
    name
  }

  private def containsName(value: String, name: String): Boolean =
    ("(?<![A-Za-z0-9_$])" + Pattern.quote(name) + "(?![A-Za-z0-9_$])").r
      .findFirstIn(value)
      .nonEmpty

  private def isCanonicalZero(value: Expression): Boolean = value match {
    case literal: UIntLiteral => literal.value == 0
    case literal: BitsLiteral => literal.value == 0
    case literal: SIntLiteral => literal.value == 0
    case literal: BoolLiteral => !literal.value
    case _                    => false
  }

  private def fail(
      code: String,
      detail: String,
      sourceLocation: Option[String] = None
  ): Nothing =
    ParameterizedVerilogException.fail(code, detail, sourceLocation)
}
