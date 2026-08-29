#!/usr/bin/env python3
from pathlib import Path

registry = Path(
    "morphruntime/src/main/scala/spinal/core/"
    "ExternalNativeIntShadowRegistry.scala"
)
fallback = Path(
    "morphhdl/src/main/scala/spinal/core/internals/"
    "ExternalParameterizedVerilogNativeFallback.scala"
)

rv = registry.read_text()
if "def definitionExpressionRoot(" not in rv:
    marker = "  /** Execute one untouched constructor with an active shadow scope. */\n"
    method = '''  /**
    * Resolve the provenance root of one exact lowered native-Int expression.
    * The weak identity registry is authoritative; text and witness equality are
    * never accepted as provenance.
    */
  private[core] def definitionExpressionRoot(
      lowered: ElaborationIntegerExpression
  ): Option[ParameterizedStructure.StructuralPredicateRoot] = synchronized {
    if (lowered == null) return None
    reapDefinitionExpressionEvidence()
    definitionExpressionEvidence
      .get(new ExternalNativeIntExpressionIdentityRef(lowered, null))
      .map(_.root)
  }

'''
    if rv.count(marker) != 1:
        raise SystemExit("definition-expression root marker is ambiguous")
    rv = rv.replace(marker, method + marker, 1)
registry.write_text(rv)

value = fallback.read_text()

# Keep WidthRetained's existing public shape. Exact evaluator provenance remains
# in retainedOrigins, an IdentityHashMap populated only by retained(...).
value = value.replace(
'''  private final case class WidthRetained(
      render: String,
      default: BigInt,
      minimum: BigInt,
      maximum: BigInt,
      parameters: Vector[ElaborationIntegerParameter],
      origin: Option[ElaborationIntegerExpression] = None
  ) extends WidthExpr {
''',
'''  private final case class WidthRetained(
      render: String,
      default: BigInt,
      minimum: BigInt,
      maximum: BigInt,
      parameters: Vector[ElaborationIntegerParameter]
  ) extends WidthExpr {
''')
value = value.replace(
'''        val value = WidthRetained(
          expression.verilog,
          expression.default,
          expression.minimum,
          expression.maximum,
          expression.parameters.distinct.sortBy(_.name),
          origin = Some(expression)
        )
''',
'''        val value = WidthRetained(
          expression.verilog,
          expression.default,
          expression.minimum,
          expression.maximum,
          expression.parameters.distinct.sortBy(_.name)
        )
''')
value = value.replace(
'''        case retained: WidthRetained =>
          retained.origin.orElse(Option(retainedOrigins.get(retained))).flatMap(origin =>
            ExternalNativeIntShadowRegistry.evaluateDefinitionExpression(
              origin,
              root,
              value
            )
          )
''',
'''        case retained: WidthRetained =>
          Option(retainedOrigins.get(retained)).flatMap(origin =>
            ExternalNativeIntShadowRegistry.evaluateDefinitionExpression(
              origin,
              root,
              value
            )
          )
''')
value = value.replace(
'''        case retained: WidthRetained =>
          retained.origin.orElse(Option(retainedOrigins.get(retained)))
            .flatMap(ExternalNativeIntShadowRegistry.definitionExpressionRoot)
            .toSet
''',
'''        case retained: WidthRetained =>
          Option(retainedOrigins.get(retained))
            .flatMap(ExternalNativeIntShadowRegistry.definitionExpressionRoot)
            .toSet
''')

if "provenCompleteDomainEquivalent" not in value:
    marker = '''              val provenCapturedDomainEquivalent =
                isProvenCapturedDomainWidthEquivalence(
                  assignment,
                  targetWidth,
                  sourceWidth
                )
'''
    replacement = marker + '''              val provenCompleteDomainEquivalent =
                widthInference.provesEquivalentOnCompleteRetainedDomain(
                  targetWidth,
                  sourceWidth
                )
'''
    if value.count(marker) != 1:
        raise SystemExit("assignment complete-domain marker is ambiguous")
    value = value.replace(marker, replacement, 1)
    condition = '''                !provenAutoResize && !provenModularUpdate &&
                !provenCapturedDomainEquivalent
'''
    replacement = '''                !provenAutoResize && !provenModularUpdate &&
                !provenCapturedDomainEquivalent &&
                !provenCompleteDomainEquivalent
'''
    if value.count(condition) != 1:
        raise SystemExit("assignment complete-domain condition is ambiguous")
    value = value.replace(condition, replacement, 1)

if "def provesEquivalentOnCompleteRetainedDomain(" not in value:
    marker = '''      def ofBase(baseType: BaseType): WidthExpr = {
'''
    method = '''      /**
        * Establish algebraic equality over one complete compiler-retained
        * native-Int domain. This rule is independent of component, signal and
        * source-file names. Unsupported nodes, multiple roots, large domains,
        * schema conflicts and non-positive widths all fail closed.
        */
      def provesEquivalentOnCompleteRetainedDomain(
          left: WidthExpr,
          right: WidthExpr
      ): Boolean = {
        if (!left.isSymbolic || !right.isSymbolic || left.default != right.default)
          return false

        val schemas = (left.parameters ++ right.parameters).groupBy(_.name)
        if (schemas.exists { case (_, declarations) =>
              declarations.distinct.size != 1
            }) return false

        val roots = (provenanceRoots(left) ++ provenanceRoots(right)).toVector
        if (roots.size != 1) return false
        val root = roots.head
        val rootSchemas = root.parameters.map(parameter => parameter.name -> parameter).toMap
        if (schemas.exists { case (name, declarations) =>
              !rootSchemas.get(name).contains(declarations.head)
            }) return false

        val domainSize = root.maximum - root.minimum + 1
        if (
          domainSize < 1 ||
          domainSize >
            ExternalNativeIntShadowRegistry.MaximumStructuralPredicateDomainSize
        ) return false

        var current = root.minimum
        while (current <= root.maximum) {
          val leftValue = evaluate(left, root, current)
          val rightValue = evaluate(right, root, current)
          if (
            leftValue.isEmpty || rightValue.isEmpty ||
            leftValue != rightValue || leftValue.exists(_ < 1)
          ) return false
          current += 1
        }
        true
      }

      private def provenanceRoots(
          expression: WidthExpr
      ): Set[ParameterizedStructure.StructuralPredicateRoot] = expression match {
        case retained: WidthRetained =>
          Option(retainedOrigins.get(retained))
            .flatMap(ExternalNativeIntShadowRegistry.definitionExpressionRoot)
            .toSet
        case WidthBinary(_, left, right, _, _, _, _, _) =>
          provenanceRoots(left) ++ provenanceRoots(right)
        case WidthSelect(_, whenTrue, whenFalse, _, _, _) =>
          provenanceRoots(whenTrue) ++ provenanceRoots(whenFalse)
        case _ => Set.empty
      }

'''
    if value.count(marker) != 1:
        raise SystemExit("WidthInference complete-domain marker is ambiguous")
    value = value.replace(marker, method + marker, 1)

# A direct formal width may appear alongside retained derived widths. It is
# evaluable only when the shared root is exactly that formal schema.
needle = '''        case retained: WidthRetained =>
          Option(retainedOrigins.get(retained)).flatMap(origin =>
            ExternalNativeIntShadowRegistry.evaluateDefinitionExpression(
              origin,
              root,
              value
            )
          )
'''
if needle in value and "case WidthParameter(parameter)" not in value[value.index(needle):value.index(needle)+1000]:
    replacement = needle + '''        case WidthParameter(parameter)
            if root.parameters == Vector(parameter) &&
              root.default == parameter.default &&
              root.minimum == parameter.minimum &&
              root.maximum == parameter.maximum => Some(value)
'''
    value = value.replace(needle, replacement, 1)

fallback.write_text(value)
