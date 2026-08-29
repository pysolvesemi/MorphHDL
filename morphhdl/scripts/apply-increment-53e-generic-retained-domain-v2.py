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
    * Return the provenance root of one exact lowered native-Int expression.
    * Object identity is the only lookup key; rendered text and equal witnesses
    * are deliberately insufficient. Generic downstream analyses use this to
    * validate algebraically different widths over the complete retained domain.
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
        raise SystemExit("generic root-query insertion marker is ambiguous")
    rv = rv.replace(marker, method + marker, 1)
    registry.write_text(rv)

value = fallback.read_text()

if "provenCompleteDomainEquivalent" not in value:
    old = '''              val provenCapturedDomainEquivalent =
                isProvenCapturedDomainWidthEquivalence(
                  assignment,
                  targetWidth,
                  sourceWidth
                )
'''
    new = old + '''              val provenCompleteDomainEquivalent =
                widthInference.provesEquivalentOnCompleteRetainedDomain(
                  targetWidth,
                  sourceWidth
                )
'''
    if value.count(old) != 1:
        raise SystemExit("generic complete-domain assignment marker is ambiguous")
    value = value.replace(old, new, 1)

    old = '''                !provenAutoResize && !provenModularUpdate &&
                !provenCapturedDomainEquivalent
'''
    new = '''                !provenAutoResize && !provenModularUpdate &&
                !provenCapturedDomainEquivalent &&
                !provenCompleteDomainEquivalent
'''
    if value.count(old) != 1:
        raise SystemExit("generic complete-domain rejection marker is ambiguous")
    value = value.replace(old, new, 1)

if "def provesEquivalentOnCompleteRetainedDomain(" not in value:
    marker = '''      def ofBase(baseType: BaseType): WidthExpr = {
'''
    method = '''      /**
        * Prove two independently retained width expressions equal over their
        * complete compiler-proven native-Int domain. This is deliberately
        * component-agnostic: it follows exact expression provenance, requires
        * one shared root identity, exhaustively evaluates the bounded domain,
        * and fails closed for unsupported expression nodes.
        */
      def provesEquivalentOnCompleteRetainedDomain(
          left: WidthExpr,
          right: WidthExpr
      ): Boolean = {
        if (!left.isSymbolic || !right.isSymbolic) return false
        if (left.default != right.default) return false

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

        var value = root.minimum
        while (value <= root.maximum) {
          val leftValue = evaluate(left, root, value)
          val rightValue = evaluate(right, root, value)
          if (
            leftValue.isEmpty || rightValue.isEmpty ||
            leftValue != rightValue || leftValue.exists(_ < 1)
          ) return false
          value += 1
        }
        true
      }

      private def provenanceRoots(
          expression: WidthExpr
      ): Set[ParameterizedStructure.StructuralPredicateRoot] = expression match {
        case retained: WidthRetained =>
          retained.origin.orElse(Option(retainedOrigins.get(retained)))
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
        raise SystemExit("generic WidthInference insertion marker is ambiguous")
    value = value.replace(marker, method + marker, 1)

old = '''        case retained: WidthRetained =>
          Option(retainedOrigins.get(retained)).flatMap(origin =>
            ExternalNativeIntShadowRegistry.evaluateDefinitionExpression(
              origin,
              root,
              value
            )
          )
'''
new = '''        case retained: WidthRetained =>
          retained.origin.orElse(Option(retainedOrigins.get(retained))).flatMap(origin =>
            ExternalNativeIntShadowRegistry.evaluateDefinitionExpression(
              origin,
              root,
              value
            )
          )
        case WidthParameter(parameter)
            if root.parameters == Vector(parameter) &&
              root.default == parameter.default &&
              root.minimum == parameter.minimum &&
              root.maximum == parameter.maximum => Some(value)
'''
if old in value:
    value = value.replace(old, new, 1)

old = '''        val value = WidthRetained(
          expression.verilog,
          expression.default,
          expression.minimum,
          expression.maximum,
          expression.parameters.distinct.sortBy(_.name)
        )
'''
new = '''        val value = WidthRetained(
          expression.verilog,
          expression.default,
          expression.minimum,
          expression.maximum,
          expression.parameters.distinct.sortBy(_.name),
          origin = Some(expression)
        )
'''
if old in value:
    value = value.replace(old, new, 1)

old = '''  private final case class WidthRetained(
      render: String,
      default: BigInt,
      minimum: BigInt,
      maximum: BigInt,
      parameters: Vector[ElaborationIntegerParameter]
  ) extends WidthExpr {
'''
new = '''  private final case class WidthRetained(
      render: String,
      default: BigInt,
      minimum: BigInt,
      maximum: BigInt,
      parameters: Vector[ElaborationIntegerParameter],
      origin: Option[ElaborationIntegerExpression] = None
  ) extends WidthExpr {
'''
if old in value:
    value = value.replace(old, new, 1)

fallback.write_text(value)
