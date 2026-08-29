#!/usr/bin/env python3
from pathlib import Path


def replace_once(value: str, old: str, new: str, label: str) -> str:
    count = value.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    return value.replace(old, new, 1)

registry = Path(
    "morphruntime/src/main/scala/spinal/core/ExternalNativeIntShadowRegistry.scala"
)
value = registry.read_text()
if "def definitionExpressionRootOf(" not in value:
    marker = "  /** Execute one untouched constructor with an active shadow scope. */\n"
    method = '''  /**
    * Return the exact compiler-retained definition root for one lowered native
    * integer expression. Identity, rather than rendered text or witness value,
    * is the provenance key. Generic width analysis uses this to prove two
    * independently retained expressions over one complete legal domain.
    */
  private[core] def definitionExpressionRootOf(
      lowered: ElaborationIntegerExpression
  ): Option[ParameterizedStructure.StructuralPredicateRoot] = synchronized {
    if (lowered == null) None
    else {
      reapDefinitionExpressionEvidence()
      definitionExpressionEvidence
        .get(new ExternalNativeIntExpressionIdentityRef(lowered, null))
        .map(_.root)
    }
  }

'''
    value = replace_once(value, marker, method + marker, "definition root accessor")
    registry.write_text(value)

helper = Path(
    "morphhdl/src/main/scala/spinal/core/internals/"
    "ExternalParameterizedWidthDomainProof.scala"
)
helper.write_text('''package spinal.core.internals

/**
  * Generic bounded proof kernel for parameter-derived packed widths.
  *
  * It has no knowledge of any SpinalHDL component, signal or library name.
  * Callers must separately prove that both evaluators belong to one exact
  * compiler-retained parameter root. Undefined, non-positive or unequal values
  * fail closed.
  */
private[internals] object ExternalParameterizedWidthDomainProof {
  private val MaximumValues = BigInt(65536)

  def equivalent(
      minimum: BigInt,
      maximum: BigInt
  )(
      left: BigInt => Option[BigInt],
      right: BigInt => Option[BigInt]
  ): Boolean = {
    val count = maximum - minimum + 1
    if (minimum > maximum || count < 1 || count > MaximumValues) return false

    var value = minimum
    while (value <= maximum) {
      val l = left(value)
      val r = right(value)
      if (l.isEmpty || r.isEmpty || l != r || l.exists(_ < 1)) return false
      value += 1
    }
    true
  }
}
''')

fallback = Path(
    "morphhdl/src/main/scala/spinal/core/internals/"
    "ExternalParameterizedVerilogNativeFallback.scala"
)
value = fallback.read_text()
if "def equivalentOverCompleteDomain(" not in value:
    marker = "      def ofBase(baseType: BaseType): WidthExpr = {\n"
    method = '''      private def provenanceRootsOf(
          expression: WidthExpr
      ): Vector[ParameterizedStructure.StructuralPredicateRoot] = expression match {
        case retained: WidthRetained =>
          Option(retainedOrigins.get(retained))
            .flatMap(ExternalNativeIntShadowRegistry.definitionExpressionRootOf)
            .toVector
        case WidthBinary(_, left, right, _, _, _, _, _) =>
          provenanceRootsOf(left) ++ provenanceRootsOf(right)
        case WidthSelect(_, whenTrue, whenFalse, _, _, _) =>
          provenanceRootsOf(whenTrue) ++ provenanceRootsOf(whenFalse)
        case _ => Vector.empty
      }

      /**
        * Prove equality only when both symbolic widths descend from the same
        * exact compiler-retained root and exhaustive bounded evaluation agrees
        * for every admitted root value. This is a component-neutral rule: no
        * source path, component class, declaration name or emitted signal name
        * participates in discovery.
        */
      def equivalentOverCompleteDomain(
          left: WidthExpr,
          right: WidthExpr
      ): Boolean = {
        if (!left.isSymbolic || !right.isSymbolic) return false
        val leftRoots = provenanceRootsOf(left)
        val rightRoots = provenanceRootsOf(right)
        if (leftRoots.isEmpty || rightRoots.isEmpty) return false
        val root = leftRoots.head
        if (!leftRoots.forall(_ eq root) || !rightRoots.forall(_ eq root)) {
          return false
        }

        ExternalParameterizedWidthDomainProof.equivalent(
          root.minimum,
          root.maximum
        )(
          value => evaluate(left, root, value),
          value => evaluate(right, root, value)
        )
      }

'''
    value = replace_once(value, marker, method + marker, "complete-domain proof method")

    marker = '''              if (
                targetWidth.isSymbolic && sourceWidth.isSymbolic &&
'''
    insertion = '''              val provenCompleteDomainEquivalent =
                widthInference.equivalentOverCompleteDomain(
                  targetWidth,
                  sourceWidth
                )
'''
    value = replace_once(value, marker, insertion + marker, "complete-domain proof invocation")

    old = '''                targetWidth != sourceWidth && !nativeCounterNext &&
                !provenAutoResize && !provenModularUpdate &&
                !provenCapturedDomainEquivalent
'''
    new = '''                targetWidth != sourceWidth && !nativeCounterNext &&
                !provenAutoResize && !provenModularUpdate &&
                !provenCapturedDomainEquivalent &&
                !provenCompleteDomainEquivalent
'''
    value = replace_once(value, old, new, "complete-domain validation gate")
    fallback.write_text(value)

unit = Path(
    "morphhdl/src/test/scala/spinal/core/internals/"
    "ExternalParameterizedWidthDomainProofTests.scala"
)
unit.parent.mkdir(parents=True, exist_ok=True)
unit.write_text('''package spinal.core.internals

import org.scalatest.funsuite.AnyFunSuite

class ExternalParameterizedWidthDomainProofTests extends AnyFunSuite {
  private def addressWidth(value: BigInt): BigInt =
    BigInt(math.max(1, (value - 1).bitLength))

  test("equivalent parameter-derived widths pass without component knowledge") {
    assert(
      ExternalParameterizedWidthDomainProof.equivalent(4, 16)(
        value => Some(addressWidth(value) + 1),
        value => Some(addressWidth(value * 2))
      )
    )
  }

  test("a non-equivalent width and an undefined evaluator fail closed") {
    assert(
      !ExternalParameterizedWidthDomainProof.equivalent(4, 16)(
        value => Some(addressWidth(value) + 1),
        value => Some(addressWidth(value * 2) + (if (value == 11) 1 else 0))
      )
    )
    assert(
      !ExternalParameterizedWidthDomainProof.equivalent(4, 16)(
        value => if (value == 9) None else Some(value),
        value => Some(value)
      )
    )
  }
}
''')

guard = Path("morphhdl/scripts/check-generic-parameterized-width-engine.sh")
guard.write_text('''#!/usr/bin/env bash
set -euo pipefail
files=(
  morphruntime/src/main/scala/spinal/core/ExternalNativeIntShadowRegistry.scala
  morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedWidthDomainProof.scala
  morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala
  morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedAutoResize.scala
  morphhdl/src/main/scala/spinal/core/internals/ParameterizedVerilogMemories.scala
)
for file in "${files[@]}"; do
  if grep -En 'StreamFifo(CC)?|BufferCC|pushToPopGray|popToPushGray' "$file"; then
    echo "component-specific recognition leaked into generic parameterization engine: $file" >&2
    exit 1
  fi
done
''')
guard.chmod(0o755)
