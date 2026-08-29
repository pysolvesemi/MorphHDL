#!/usr/bin/env python3
from pathlib import Path

path = Path(
    "morphhdl/src/main/scala/spinal/core/internals/"
    "ExternalParameterizedVerilogNativeFallback.scala"
)
value = path.read_text()

rewrite_after_resize = '''    val rewrittenAutoResizes = rewriteMaterializedAutoResizeAssignments(
      component,
      rewrittenDeclarations
    )
    val rewrittenCounterBoundaries = rewriteSymbolicCounterBoundaryComparisons(
      rewrittenAutoResizes,
      analysis.symbolicCounterBoundaryWidths
    )
'''
rewrite_direct = '''    val rewrittenCounterBoundaries = rewriteSymbolicCounterBoundaryComparisons(
      rewrittenDeclarations,
      analysis.symbolicCounterBoundaryWidths
    )
'''
rewrite_after_resize_new = '''    val rewrittenAutoResizes = rewriteMaterializedAutoResizeAssignments(
      component,
      rewrittenDeclarations
    )
    val rewrittenInitializers = rewriteSymbolicZeroAssignments(
      rewrittenAutoResizes,
      analysis.symbolicZeroInitializers
    )
    val rewrittenCounterBoundaries = rewriteSymbolicCounterBoundaryComparisons(
      rewrittenInitializers,
      analysis.symbolicCounterBoundaryWidths
    )
'''
rewrite_direct_new = '''    val rewrittenInitializers = rewriteSymbolicZeroAssignments(
      rewrittenDeclarations,
      analysis.symbolicZeroInitializers
    )
    val rewrittenCounterBoundaries = rewriteSymbolicCounterBoundaryComparisons(
      rewrittenInitializers,
      analysis.symbolicCounterBoundaryWidths
    )
'''
if value.count(rewrite_after_resize) == 1:
    value = value.replace(rewrite_after_resize, rewrite_after_resize_new, 1)
elif value.count(rewrite_direct) == 1:
    value = value.replace(rewrite_direct, rewrite_direct_new, 1)
else:
    raise SystemExit(
        "symbolic zero initializer rewrite marker count="
        f"afterResize:{value.count(rewrite_after_resize)},"
        f"direct:{value.count(rewrite_direct)}"
    )

method_marker = '''  /**
    * Replace only the concrete witness assignment of compiler-created UInt
'''
if value.count(method_marker) != 1:
    raise SystemExit(
        f"symbolic zero initializer method marker count={value.count(method_marker)}"
    )
method = '''  /**
    * Rewrite zero assignments of graph-proven symbolic-width registers to a
    * portable parameter-sized replication. The emitted signal name comes from
    * the exact BaseType identity after normal Spinal naming; neither component
    * names nor equal concrete widths are discovery keys.
    */
  private def rewriteSymbolicZeroAssignments(
      verilog: String,
      initializers: Vector[(String, WidthExpr)]
  ): String = {
    if (initializers.isEmpty) return verilog

    val literalSyntax = "[0-9]+'[sS]?[bBoOdDhH][0-9a-fA-F_xXzZ_]+"
    val literalParser =
      "(?i)^([0-9]+)'([s]?)([bodh])([0-9a-f_xz_]+)$".r

    def literalValue(value: String): Option[BigInt] = value match {
      case literalParser(_, _, radix, digits)
          if !digits.exists(character =>
            character == 'x' || character == 'X' ||
              character == 'z' || character == 'Z') =>
        val base = radix.toLowerCase match {
          case "b" => 2
          case "o" => 8
          case "d" => 10
          case "h" => 16
        }
        Some(BigInt(digits.replace("_", ""), base))
      case _ => None
    }

    val grouped = initializers.groupBy(_._1).toVector.sortBy(_._1)
    grouped.collectFirst {
      case (name, values) if values.map(_._2).distinct.size != 1 => name
    }.foreach { name =>
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-ZERO-INITIALIZER-WIDTH-CONFLICT",
        s"symbolic register '$name' has conflicting retained initializer widths"
      )
    }

    var lines = verilog.split("\\n", -1).toVector
    grouped.foreach { case (name, values) =>
      val width = values.head._2
      val repeatCount =
        if (width.precedence >= 100) width.render else s"(${width.render})"
      val replacement = s"{$repeatCount{1'b0}}"
      val pattern = (
        """^(\s*""" + Pattern.quote(name) +
          """\s*(?:<=|=)\s*)(""" + literalSyntax + """)(\s*;.*)$"""
      ).r
      var count = 0
      lines = lines.map { line =>
        line match {
          case pattern(prefix, literal, suffix)
              if literalValue(literal).contains(BigInt(0)) =>
            count += 1
            prefix + replacement + suffix
          case _ => line
        }
      }
      if (count == 0) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-ZERO-INITIALIZER-NOT-FOUND",
          s"symbolic register '$name' has a graph-proven zero initializer but no unique portable emitted zero assignment"
        )
      }
    }
    lines.mkString("\\n")
  }

'''
value = value.replace(method_marker, method + method_marker, 1)

field_old = '''    private val assignments = ArrayBuffer.empty[DataAssignmentStatement]
    private val treeStatements = ArrayBuffer.empty[TreeStatement]
'''
field_new = '''    private val assignments = ArrayBuffer.empty[DataAssignmentStatement]
    private val initializerAssignments = ArrayBuffer.empty[AssignmentStatement]
    private val treeStatements = ArrayBuffer.empty[TreeStatement]
'''
if value.count(field_old) != 1:
    raise SystemExit(
        f"symbolic zero initializer field marker count={value.count(field_old)}"
    )
value = value.replace(field_old, field_new, 1)

walk_old = '''    component.dslBody.walkLeafStatements {
      case assignment: DataAssignmentStatement => assignments += assignment
      case _                                   =>
    }
'''
walk_new = '''    component.dslBody.walkLeafStatements {
      case assignment: DataAssignmentStatement => assignments += assignment
      case assignment: InitAssignmentStatement =>
        initializerAssignments += assignment
      case assignment: InitialAssignmentStatement =>
        initializerAssignments += assignment
      case _ =>
    }
    declarations.distinct.foreach { declaration =>
      declaration.foreachStatements {
        case assignment: InitAssignmentStatement =>
          initializerAssignments += assignment
        case assignment: InitialAssignmentStatement =>
          initializerAssignments += assignment
        case _ =>
      }
    }
'''
if value.count(walk_old) != 1:
    raise SystemExit(
        f"symbolic zero initializer walk marker count={value.count(walk_old)}"
    )
value = value.replace(walk_old, walk_new, 1)

lazy_marker = '''    lazy val symbolicCounterBoundaryWidths
        : Vector[(String, ElaborationIntegerExpression)] =
'''
if value.count(lazy_marker) != 1:
    raise SystemExit(
        f"symbolic zero initializer lazy marker count={value.count(lazy_marker)}"
    )
lazy_code = '''    private def isZeroInitializer(expression: Expression): Boolean =
      expression match {
        case literal: Literal => literal.getValue() == BigInt(0)
        case resize: Resize   => isZeroInitializer(resize.input)
        case cast: CastBitVectorToBitVector =>
          isZeroInitializer(cast.input)
        case _ => false
      }

    lazy val symbolicZeroInitializers: Vector[(String, WidthExpr)] =
      initializerAssignments.distinct.toVector.flatMap { assignment =>
        assignment.finalTarget match {
          case target: BitVector
              if target.isReg && (assignment.target eq target) &&
                isZeroInitializer(assignment.source) =>
            val width = widthInference.ofBase(target)
            if (width.isSymbolic) {
              val name = Option(target.getName()).filter(_.nonEmpty).getOrElse {
                fail(
                  "SPINAL-PARAMETERIZED-VERILOG-ZERO-INITIALIZER-NAME-MISSING",
                  "one symbolic-width register with a zero initializer has no final emitted name",
                  ParameterizedWidth.sourceLocationOf(target)
                )
              }
              Some(name -> width)
            } else None
          case _ => None
        }
      }.distinct

'''
value = value.replace(lazy_marker, lazy_code + lazy_marker, 1)

path.write_text(value)
