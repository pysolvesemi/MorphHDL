#!/usr/bin/env python3
from pathlib import Path

repo = Path(".")
runtime = repo / "morphruntime/src/main/scala/spinal/core/ExternalNativeIntShadowRegistry.scala"
fallback = repo / "morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala"


def replace_method(source: str, signature: str, replacement: str) -> str:
    start = source.find(signature)
    if start < 0:
        return source
    brace = source.find("{", start)
    if brace < 0:
        raise SystemExit(f"no opening brace for {signature}")
    depth = 0
    index = brace
    in_string = False
    in_triple = False
    escaped = False
    while index < len(source):
        if source.startswith('"""', index) and not escaped:
            in_triple = not in_triple
            index += 3
            continue
        current = source[index]
        if not in_triple:
            if current == '"' and not escaped:
                in_string = not in_string
            if not in_string:
                if current == "{":
                    depth += 1
                elif current == "}":
                    depth -= 1
                    if depth == 0:
                        return source[:start] + replacement + source[index + 1 :]
            escaped = current == "\\" and not escaped
            if current != "\\":
                escaped = False
        index += 1
    raise SystemExit(f"unterminated method {signature}")


value = runtime.read_text()
if "private def definitionExpressionEvidenceOf(" not in value:
    marker = """  /**
    * Evaluate one exact lowered expression against its compiler-retained native
"""
    if value.count(marker) != 1:
        raise SystemExit(
            f"definition-expression evidence marker count={value.count(marker)}"
        )
    helper = """  /**
    * Resolve retained evaluator provenance for one lowered expression.
    *
    * Exact identity is preferred. Some MorphHDL metadata paths preserve an
    * immutable case-class copy of the same lowered expression; for those paths
    * accept only one unambiguous structurally equivalent provenance record.
    * This fallback never uses component names, signal names or concrete-width
    * coincidence, and conflicting candidates fail closed.
    */
  private def definitionExpressionEvidenceOf(
      lowered: ElaborationIntegerExpression
  ): Option[DefinitionExpressionEvidence] = {
    val exact = definitionExpressionEvidence.get(
      new ExternalNativeIntExpressionIdentityRef(lowered, null)
    )
    exact.orElse {
      val candidates = definitionExpressionEvidence.iterator.flatMap {
        case (reference, evidence) =>
          Option(reference.get())
            .filter(candidate => equivalentExpression(candidate, lowered))
            .map(_ => evidence)
      }.toVector
      candidates.headOption.filter { first =>
        candidates.forall(candidate =>
          (candidate.root eq first.root) &&
            candidate.expression == first.expression
        )
      }
    }
  }

"""
    value = value.replace(marker, helper + marker, 1)

exact_lookup = """    definitionExpressionEvidence
      .get(new ExternalNativeIntExpressionIdentityRef(lowered, null))
      .filter(_.root eq root)
"""
if exact_lookup in value:
    value = value.replace(
        exact_lookup,
        """    definitionExpressionEvidenceOf(lowered)
      .filter(_.root eq root)
""",
        1,
    )

if "def evaluateDefinitionExpressionAtParameters(" not in value:
    marker = """  /** Execute one untouched constructor with an active shadow scope. */
"""
    if value.count(marker) != 1:
        raise SystemExit(
            f"parameter evaluator insertion marker count={value.count(marker)}"
        )
    method = """  /**
    * Evaluate one exact compiler-retained native-Int definition expression
    * under a complete named formal-parameter assignment.
    *
    * This is identity/provenance backed. Rendered Verilog text, equal concrete
    * witnesses and component names are never discovery keys. Unsupported or
    * ambiguous roots fail closed.
    */
  private[core] def evaluateDefinitionExpressionAtParameters(
      lowered: ElaborationIntegerExpression,
      values: Map[String, BigInt]
  ): Option[BigInt] = synchronized {
    if (lowered == null || values == null) return None
    reapDefinitionExpressionEvidence()
    definitionExpressionEvidenceOf(lowered).flatMap { evidence =>
      val root = evidence.root
      val declarations = root.parameters.distinct
      if (declarations.size != 1) None
      else {
        val declaration = declarations.head
        val rootIsCanonicalFormal =
          root.default == declaration.default &&
            root.minimum == declaration.minimum &&
            root.maximum == declaration.maximum
        if (!rootIsCanonicalFormal) None
        else {
          values
            .get(declaration.name)
            .filter(value =>
              value >= declaration.minimum && value <= declaration.maximum
            )
            .flatMap(value =>
              ExternalNativeIntRelativeExpression.evaluate(
                evidence.expression,
                value
              )
            )
            .filter(result =>
              result >= lowered.minimum && result <= lowered.maximum
            )
        }
      }
    }
  }

"""
    value = value.replace(marker, method + marker, 1)
else:
    old_lookup = """    definitionExpressionEvidence
      .get(new ExternalNativeIntExpressionIdentityRef(lowered, null))
      .flatMap { evidence =>
"""
    if old_lookup in value:
        value = value.replace(
            old_lookup,
            """    definitionExpressionEvidenceOf(lowered)
      .flatMap { evidence =>
""",
            1,
        )
runtime.write_text(value)

value = fallback.read_text()
if "isProvenCompleteDomainWidthEquivalence(" not in value:
    marker = """              val provenCapturedDomainEquivalent =
                isProvenCapturedDomainWidthEquivalence(
                  assignment,
                  targetWidth,
                  sourceWidth
                )
"""
    if value.count(marker) != 1:
        raise SystemExit(
            f"complete-domain validation marker count={value.count(marker)}"
        )
    value = value.replace(
        marker,
        marker
        + """              val provenCompleteDomainEquivalent =
                isProvenCompleteDomainWidthEquivalence(
                  targetWidth,
                  sourceWidth
                )
""",
        1,
    )
    condition = """                !provenCapturedDomainEquivalent
"""
    if value.count(condition) < 1:
        raise SystemExit("complete-domain condition marker missing")
    value = value.replace(
        condition,
        """                !provenCapturedDomainEquivalent &&
                !provenCompleteDomainEquivalent
""",
        1,
    )

method = """    /**
      * Prove two symbolic packed widths equal over the complete Cartesian
      * product of every bounded formal domain they reference.
      *
      * This rule is generic to SpinalHDL expression graphs. It does not inspect
      * component classes, source paths, emitted signal names or library APIs.
      */
    private def isProvenCompleteDomainWidthEquivalence(
        left: WidthExpr,
        right: WidthExpr
    ): Boolean = {
      if (!left.isSymbolic || !right.isSymbolic) return false
      val leftDeclarations = left.parameters.groupBy(_.name)
      val rightDeclarations = right.parameters.groupBy(_.name)
      if (leftDeclarations.keySet != rightDeclarations.keySet) return false
      val all = (left.parameters ++ right.parameters).distinct.sortBy(_.name)
      ExternalParameterizedDomainEquivalence.provePositiveEquality(
        all,
        left.default,
        right.default
      )(
        values => widthInference.evaluateAtParameters(left, values),
        values => widthInference.evaluateAtParameters(right, values)
      )
    }
"""
signature = "    private def isProvenCompleteDomainWidthEquivalence("
if signature in value:
    value = replace_method(value, signature, method)
else:
    marker = """    /**
      * Distinct symbolic width expressions may be equal only inside the exact
"""
    if value.count(marker) != 1:
        raise SystemExit(
            f"complete-domain method marker count={value.count(marker)}"
        )
    value = value.replace(marker, method + "\n" + marker, 1)

if "def evaluateAtParameters(" not in value:
    marker = """      def ofBase(baseType: BaseType): WidthExpr = {
"""
    if value.count(marker) != 1:
        raise SystemExit(
            f"generic evaluator marker count={value.count(marker)}"
        )
    evaluator = """      /**
        * Evaluate a retained width using only its WidthExpr tree and exact
        * native-Int provenance. Rendered-expression parsing is prohibited.
        */
      def evaluateAtParameters(
          expression: WidthExpr,
          values: Map[String, BigInt]
      ): Option[BigInt] = {
        def checked(value: BigInt, node: WidthExpr): Option[BigInt] =
          if (value >= node.minimum && value <= node.maximum) Some(value)
          else None

        expression match {
          case literal: WidthLiteral => Some(literal.value)
          case parameter: WidthParameter =>
            values
              .get(parameter.value.name)
              .filter(value =>
                value >= parameter.value.minimum &&
                  value <= parameter.value.maximum
              )
          case retained: WidthRetained =>
            Option(retainedOrigins.get(retained)).flatMap { origin =>
              val nativeValue =
                ExternalNativeIntShadowRegistry
                  .evaluateDefinitionExpressionAtParameters(origin, values)
              val directFormalValue = {
                val declarations = origin.parameters.distinct
                if (declarations.size != 1) None
                else {
                  val declaration = declarations.head
                  val isDirectFormal =
                    origin.verilog == declaration.name &&
                      origin.default == declaration.default &&
                      origin.minimum == declaration.minimum &&
                      origin.maximum == declaration.maximum
                  if (!isDirectFormal) None
                  else values.get(declaration.name)
                }
              }
              nativeValue.orElse(directFormalValue)
                .flatMap(value => checked(value, retained))
            }
          case binary: WidthBinary =>
            for {
              left <- evaluateAtParameters(binary.left, values)
              right <- evaluateAtParameters(binary.right, values)
              result <- binary.operator match {
                case "+" => Some(left + right)
                case "-" => Some(left - right)
                case "*" => Some(left * right)
                case _   => None
              }
              bounded <- checked(result, binary)
            } yield bounded
          case select: WidthSelect =>
            if (select.whenTrue == select.whenFalse)
              evaluateAtParameters(select.whenTrue, values)
                .flatMap(value => checked(value, select))
            else None
        }
      }

"""
    value = value.replace(marker, evaluator + marker, 1)
fallback.write_text(value)
