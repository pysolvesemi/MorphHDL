#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
path = ROOT / "morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala"
value = path.read_text()
start = value.find("      def provesEquivalentAcrossCompleteDomain(\n")
end = value.find("\n      def ofBase(baseType: BaseType): WidthExpr = {", start)
if start < 0 or end < 0:
    raise SystemExit("generic complete-domain proof method boundaries not found")

method = '''      def provesEquivalentAcrossCompleteDomain(
          left: WidthExpr,
          right: WidthExpr
      ): Boolean = {
        if (
          !left.isSymbolic || !right.isSymbolic ||
          left.default != right.default ||
          left.parameters.distinct.sortBy(_.name) !=
            right.parameters.distinct.sortBy(_.name)
        ) return false

        def exactRootOf(
            expression: WidthExpr
        ): Option[ParameterizedStructure.StructuralPredicateRoot] = {
          val roots = new IdentityHashMap[
            ParameterizedStructure.StructuralPredicateRoot,
            java.lang.Boolean
          ]()
          var complete = true

          def collect(current: WidthExpr): Unit = current match {
            case retained: WidthRetained =>
              Option(retainedOrigins.get(retained))
                .flatMap(ExternalNativeIntShadowRegistry.definitionExpressionRootOf) match {
                case Some(root) => roots.put(root, java.lang.Boolean.TRUE)
                case None       => complete = false
              }
            case _: WidthLiteral =>
            case _: WidthParameter =>
              // Never recover a native-expression root from a rendered formal
              // name.  A bare parameter needs its own identity-backed origin.
              complete = false
            case binary: WidthBinary =>
              collect(binary.left)
              collect(binary.right)
            case _: WidthSelect =>
              // The select condition currently has no retained evaluator.
              complete = false
          }

          collect(expression)
          if (!complete || roots.size != 1) None
          else Some(roots.keySet().iterator().next())
        }

        val roots = for {
          leftRoot <- exactRootOf(left)
          rightRoot <- exactRootOf(right)
        } yield leftRoot -> rightRoot
        if (roots.isEmpty) return false

        val (leftRoot, rightRoot) = roots.get
        val leftSchema = leftRoot.parameters.distinct.sortBy(_.name)
        val rightSchema = rightRoot.parameters.distinct.sortBy(_.name)
        if (
          leftSchema != rightSchema ||
          left.parameters.distinct.sortBy(_.name) != leftSchema ||
          leftRoot.minimum != rightRoot.minimum ||
          leftRoot.maximum != rightRoot.maximum ||
          leftRoot.default != rightRoot.default
        ) return false

        val domainSize = leftRoot.maximum - leftRoot.minimum + 1
        if (
          domainSize < 1 ||
          domainSize > ExternalNativeIntShadowRegistry.MaximumStructuralPredicateDomainSize
        ) return false

        var value = leftRoot.minimum
        while (value <= leftRoot.maximum) {
          val leftValue = evaluate(left, leftRoot, value)
          val rightValue = evaluate(right, rightRoot, value)
          if (
            leftValue.isEmpty || rightValue.isEmpty ||
            leftValue != rightValue || leftValue.exists(_ < 1)
          ) return false
          value += 1
        }
        true
      }
'''

value = value[:start] + method + value[end:]
path.write_text(value)
