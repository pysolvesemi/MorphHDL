#!/usr/bin/env python3
from pathlib import Path

path = Path(
    "morphhdl/src/main/scala/spinal/core/internals/"
    "ExternalParameterizedVerilogNativeFallback.scala"
)
value = path.read_text()

call_old = '''      validateAssignments()
      validateProcesses()
'''
call_new = '''      validateAssignments()
      debugExpressionPublication()
      validateProcesses()
'''
if value.count(call_old) != 1:
    raise SystemExit(
        f"expression diagnostic call marker count={value.count(call_old)}"
    )
value = value.replace(call_old, call_new, 1)

method_marker = '''    private def parameterSourceLocation(
        parameter: ElaborationIntegerParameter
    ): Option[String] =
'''
if value.count(method_marker) != 1:
    raise SystemExit(
        f"expression diagnostic method marker count={value.count(method_marker)}"
    )
method = '''    /** Temporary exact-identity diagnostic for generic symbolic publication. */
    private def debugExpressionPublication(): Unit = {
      if (!sys.env.get("MORPHDL_INCREMENT_53E_EXPRESSION_DEBUG").contains("1")) return

      def nameOf(expression: Expression): String = expression match {
        case value: BaseType =>
          Option(value.getName()).filter(_.nonEmpty).getOrElse("<unnamed>")
        case _ => "<expression>"
      }

      def ownerOf(expression: Expression): String = expression match {
        case value: BaseType if value.component != null =>
          Option(value.component.definitionName).filter(_.nonEmpty).getOrElse("<unnamed-component>")
        case _ => "<no-owner>"
      }

      def widthOf(expression: Expression): String = expression match {
        case value: Expression with WidthProvider =>
          val width = widthInference.ofExpression(value)
          s"${width.render}|default=${width.default}|domain=[${width.minimum},${width.maximum}]|symbolic=${width.isSymbolic}"
        case _ => "<no-width>"
      }

      def dump(root: Expression): Unit = {
        val visited = new IdentityHashMap[Expression, java.lang.Boolean]()
        def visit(expression: Expression, depth: Int): Unit = {
          if (expression == null || visited.put(expression, java.lang.Boolean.TRUE) != null) return
          val indent = "  " * depth
          System.err.println(
            s"MORPHDL-EXPR-NODE $indent" +
              s"id=${System.identityHashCode(expression)} " +
              s"class=${expression.getClass.getName} op=${expression.opName} " +
              s"name=${nameOf(expression)} owner=${ownerOf(expression)} " +
              s"width=${widthOf(expression)}"
          )
          expression.foreachExpression(child => visit(child, depth + 1))
        }
        visit(root, 0)
      }

      System.err.println(
        s"MORPHDL-EXPR-COMPONENT name=${component.definitionName} " +
          s"parameters=${parameters.map(_.name).mkString(",")}"
      )
      symbolicDeclarationWidths.foreach { case (name, width) =>
        System.err.println(
          s"MORPHDL-EXPR-DECL name=$name width=${width.render} " +
            s"default=${width.default} domain=[${width.minimum},${width.maximum}]"
        )
      }
      assignments.foreach { assignment =>
        val target = assignment.finalTarget
        val targetName = Option(target.getName()).filter(_.nonEmpty).getOrElse("<unnamed>")
        val targetWidth = target match {
          case value: BitVector => widthInference.ofBase(value).render
          case _                => target.getBitsWidth.toString
        }
        val sourceWidth = assignment.source match {
          case value: Expression with WidthProvider =>
            widthInference.ofExpression(value).render
          case _ => "<no-width>"
        }
        System.err.println(
          s"MORPHDL-EXPR-ASSIGN id=${System.identityHashCode(assignment)} " +
            s"target=$targetName targetClass=${assignment.target.getClass.getName} " +
            s"finalClass=${target.getClass.getName} targetEqFinal=${assignment.target eq target} " +
            s"targetWidth=$targetWidth sourceClass=${assignment.source.getClass.getName} " +
            s"sourceOp=${assignment.source.opName} sourceWidth=$sourceWidth " +
            s"hierarchy=${isHierarchyBoundary(assignment)}"
        )
        dump(assignment.source)
      }
    }

'''
value = value.replace(method_marker, method + method_marker, 1)
path.write_text(value)
