#!/usr/bin/env python3
from pathlib import Path
import re


def replace_once(value: str, old: str, new: str, label: str) -> str:
    count = value.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one marker, found {count}")
    return value.replace(old, new, 1)


# ---------------------------------------------------------------------------
# Exact native-expression domain identity
# ---------------------------------------------------------------------------
registry = Path(
    "morphruntime/src/main/scala/spinal/core/"
    "ExternalNativeIntShadowRegistry.scala"
)
r = registry.read_text()
if "private[core] def definitionExpressionRoot(" not in r:
    marker = "  /** Execute one untouched constructor with an active shadow scope. */\n"
    addition = '''  /**
    * Resolve the canonical parameter-domain root of one exact lowered native
    * integer expression. Lookup is by object identity only; rendered text,
    * numeric witnesses, signal names and component classes are intentionally
    * not discovery keys.
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
    r = replace_once(r, marker, addition + marker, "domain-root API")
    registry.write_text(r)


# ---------------------------------------------------------------------------
# Generic full-domain symbolic-width equivalence in the native fallback
# ---------------------------------------------------------------------------
fallback = Path(
    "morphhdl/src/main/scala/spinal/core/internals/"
    "ExternalParameterizedVerilogNativeFallback.scala"
)
s = fallback.read_text()
if "val provenFullDomainEquivalent =" not in s:
    marker = '''              val provenCapturedDomainEquivalent =
                isProvenCapturedDomainWidthEquivalence(
                  assignment,
                  targetWidth,
                  sourceWidth
                )
'''
    replacement = marker + '''              val provenFullDomainEquivalent =
                widthInference.provesFullDomainEquivalent(
                  targetWidth,
                  sourceWidth
                )
'''
    s = replace_once(s, marker, replacement, "assignment full-domain proof")
    old = '''                targetWidth != sourceWidth && !nativeCounterNext &&
                !provenAutoResize && !provenModularUpdate &&
                !provenCapturedDomainEquivalent
'''
    new = '''                targetWidth != sourceWidth && !nativeCounterNext &&
                !provenAutoResize && !provenModularUpdate &&
                !provenCapturedDomainEquivalent &&
                !provenFullDomainEquivalent
'''
    s = replace_once(s, old, new, "assignment full-domain guard")

if "def provesFullDomainEquivalent(" not in s:
    marker = "      def ofBase(baseType: BaseType): WidthExpr = {\n"
    addition = '''      /**
        * Prove equality of two independently retained symbolic width
        * expressions over their complete shared native-parameter domain.
        *
        * This is a generic graph/provenance rule. Every symbolic leaf must map
        * by exact object identity to one compiler-retained native-Int
        * expression, and all leaves must share the same canonical domain-root
        * identity. Unsupported operators, missing provenance, excessive
        * domains, undefined values, non-positive widths or any mismatch fail
        * closed.
        */
      def provesFullDomainEquivalent(
          left: WidthExpr,
          right: WidthExpr
      ): Boolean = {
        if (!left.isSymbolic || !right.isSymbolic) return false
        val origins =
          retainedDefinitionExpressions(left) ++
            retainedDefinitionExpressions(right)
        if (origins.isEmpty) return false
        ExternalParameterizedDomainEquivalence.prove(
          origins = origins,
          evaluateLeft = (root, value) => evaluate(left, root, value),
          evaluateRight = (root, value) => evaluate(right, root, value)
        )
      }

      private def retainedDefinitionExpressions(
          expression: WidthExpr
      ): Vector[ElaborationIntegerExpression] = expression match {
        case retained: WidthRetained =>
          Option(retainedOrigins.get(retained)).toVector
        case WidthBinary(_, left, right, _, _, _, _, _) =>
          retainedDefinitionExpressions(left) ++
            retainedDefinitionExpressions(right)
        case WidthSelect(_, whenTrue, whenFalse, _, _, _) =>
          retainedDefinitionExpressions(whenTrue) ++
            retainedDefinitionExpressions(whenFalse)
        case _ => Vector.empty
      }

'''
    s = replace_once(s, marker, addition + marker, "WidthInference proof")
else:
    # Replace the older inlined proof kernel when the previous diagnostic
    # candidate already inserted it.
    old = '''        val roots = origins.map(
          ExternalNativeIntShadowRegistry.definitionExpressionRoot
        )
        if (roots.exists(_.isEmpty)) return false
        val root = roots.head.get
        if (!roots.forall(_.exists(_ eq root))) return false

        val domainSize = root.maximum - root.minimum + 1
        if (
          domainSize < 1 ||
          domainSize > ExternalNativeIntShadowRegistry.MaximumStructuralPredicateDomainSize
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
'''
    new = '''        ExternalParameterizedDomainEquivalence.prove(
          origins = origins,
          evaluateLeft = (root, value) => evaluate(left, root, value),
          evaluateRight = (root, value) => evaluate(right, root, value)
        )
'''
    if old in s:
        s = s.replace(old, new, 1)
fallback.write_text(s)


# ---------------------------------------------------------------------------
# Reusable proof service and non-component regressions
# ---------------------------------------------------------------------------
domain_service = Path(
    "morphhdl/src/main/scala/spinal/core/internals/"
    "ExternalParameterizedDomainEquivalence.scala"
)
domain_service.write_text('''package spinal.core.internals

import spinal.core.{
  ElaborationIntegerExpression,
  ExternalNativeIntShadowRegistry,
  ParameterizedStructure
}

/**
  * Generic bounded-domain equivalence proof for retained native integer
  * expressions.
  *
  * Every symbolic origin must resolve by exact object identity to the same
  * canonical parameter-domain root. Both clients are then evaluated over every
  * admitted root value. Rendered Verilog text, concrete witnesses, signal
  * names, source filenames and component classes are never proof keys.
  */
private[internals] object ExternalParameterizedDomainEquivalence {
  type Root = ParameterizedStructure.StructuralPredicateRoot

  def prove(
      origins: Vector[ElaborationIntegerExpression],
      evaluateLeft: (Root, BigInt) => Option[BigInt],
      evaluateRight: (Root, BigInt) => Option[BigInt]
  ): Boolean = {
    if (origins.isEmpty) return false
    val roots = origins.map(
      ExternalNativeIntShadowRegistry.definitionExpressionRoot
    )
    if (roots.exists(_.isEmpty)) return false
    val root = roots.head.get
    if (!roots.forall(_.exists(_ eq root))) return false
    proveRange(
      minimum = root.minimum,
      maximum = root.maximum,
      maximumDomainSize =
        ExternalNativeIntShadowRegistry.MaximumStructuralPredicateDomainSize,
      evaluateLeft = value => evaluateLeft(root, value),
      evaluateRight = value => evaluateRight(root, value)
    )
  }

  private[internals] def proveRange(
      minimum: BigInt,
      maximum: BigInt,
      maximumDomainSize: BigInt,
      evaluateLeft: BigInt => Option[BigInt],
      evaluateRight: BigInt => Option[BigInt]
  ): Boolean = {
    val domainSize = maximum - minimum + 1
    if (
      domainSize < 1 || maximumDomainSize < 1 ||
      domainSize > maximumDomainSize
    ) return false
    var value = minimum
    while (value <= maximum) {
      val left = evaluateLeft(value)
      val right = evaluateRight(value)
      if (
        left.isEmpty || right.isEmpty || left != right ||
        left.exists(_ < 1)
      ) return false
      value += 1
    }
    true
  }
}
''')

domain_test = Path(
    "morphhdl/src/test/scala/spinal/core/internals/"
    "ExternalParameterizedDomainEquivalenceTests.scala"
)
domain_test.parent.mkdir(parents=True, exist_ok=True)
domain_test.write_text('''package spinal.core.internals

import org.scalatest.funsuite.AnyFunSuite

class ExternalParameterizedDomainEquivalenceTests extends AnyFunSuite {
  private def addressWidth(value: BigInt): BigInt =
    BigInt(1).max((value - 1).bitLength)

  test("equivalent integer-derived widths are proven over the complete domain") {
    assert(
      ExternalParameterizedDomainEquivalence.proveRange(
        minimum = 2,
        maximum = 257,
        maximumDomainSize = 256,
        evaluateLeft = value => Some(addressWidth(value) + 1),
        evaluateRight = value => Some(addressWidth(value * 2))
      )
    )
  }

  test("a single mismatch fails the complete-domain proof") {
    assert(
      !ExternalParameterizedDomainEquivalence.proveRange(
        minimum = 2,
        maximum = 64,
        maximumDomainSize = 64,
        evaluateLeft = value => Some(addressWidth(value) + 1),
        evaluateRight = value =>
          Some(addressWidth(value * 2) + (if (value == 17) 1 else 0))
      )
    )
  }

  test("undefined, non-positive and oversized domains fail closed") {
    assert(
      !ExternalParameterizedDomainEquivalence.proveRange(
        minimum = 2,
        maximum = 8,
        maximumDomainSize = 7,
        evaluateLeft = value => Some(value),
        evaluateRight = value => Some(value)
      )
    )
    assert(
      !ExternalParameterizedDomainEquivalence.proveRange(
        minimum = 2,
        maximum = 8,
        maximumDomainSize = 8,
        evaluateLeft = value => if (value == 5) None else Some(value),
        evaluateRight = value => Some(value)
      )
    )
    assert(
      !ExternalParameterizedDomainEquivalence.proveRange(
        minimum = 0,
        maximum = 2,
        maximumDomainSize = 3,
        evaluateLeft = value => Some(value),
        evaluateRight = value => Some(value)
      )
    )
  }
}
''')


# ---------------------------------------------------------------------------
# Generic compiler selection: typed native Component shape, never class/file name
# ---------------------------------------------------------------------------
plugin = Path(
    "morphplugin/src/main/scala/morphhdl/compiler/"
    "MorphHdlNativeIntShadowExpressionComponent.scala"
)
p = plugin.read_text()
for old, new in [
    ("nativeStreamFifoDepthReference", "nativeFormalIntReference"),
    ("nativeStreamFifoClassName", "nativeParameterizedOwnerClassName"),
    ("nativeStreamFifoConstructorParameters", "nativeConstructorParameters"),
    ("inNativeStreamFifo", "inNativeParameterizedOwner"),
    ("transformNativeStreamFifo", "transformNativeParameterizedComponent"),
]:
    p = p.replace(old, new)

p = p.replace(
    '''        normalizedPath.endsWith("/lib/src/main/scala/spinal/lib/Stream.scala") ||
        normalizedPath.endsWith("/lib/src/main/scala/spinal/lib/CrossClock.scala")
''',
    '''        normalizedPath.contains("/core/src/main/scala/spinal/") ||
        normalizedPath.contains("/lib/src/main/scala/spinal/")
'''
)
p = p.replace(
    '''        normalizedPath.endsWith("/lib/src/main/scala/spinal/lib/Stream.scala")
''',
    '''        normalizedPath.contains("/core/src/main/scala/spinal/") ||
        normalizedPath.contains("/lib/src/main/scala/spinal/")
'''
)

transform_marker = "    override def transform(tree: Tree): Tree = tree match {\n"
if "private def isNativeParameterizedComponent(value: ClassDef)" not in p:
    predicates = '''    private def isNativeIntConstructorParameter(
        parameter: ValDef
    ): Boolean =
      parameter.symbol != null && parameter.symbol != NoSymbol &&
        parameter.symbol.info != null &&
        (parameter.symbol.info =:= definitions.IntTpe)

    private def isNativeSpinalComponent(value: ClassDef): Boolean =
      value.symbol != null && value.symbol != NoSymbol &&
        value.symbol.info != null &&
        value.symbol.info.baseClasses.exists(
          symbol => symbol.fullName == "spinal.core.Component"
        )

    /**
      * Select every native SpinalHDL Component with exactly one Int constructor
      * parameter. Runtime shadow helpers remain identity/no-op outside an
      * active external formalization boundary. No source filename or component
      * name is part of selection.
      */
    private def isNativeParameterizedComponent(value: ClassDef): Boolean =
      isNativeSpinalComponent(value) &&
        nativeConstructorParameters(value)
          .count(isNativeIntConstructorParameter) == 1

'''
    p = replace_once(p, transform_marker, predicates + transform_marker,
                     "generic native Component predicates")

lines = p.splitlines(True)
out = []
i = 0
replaced_dispatch = 0
while i < len(lines):
    if "case value: ClassDef" in lines[i]:
        j = i
        block = []
        while j < len(lines) and j < i + 14:
            block.append(lines[j])
            if "transformNativeParameterizedComponent(value)" in lines[j]:
                break
            j += 1
        text = "".join(block)
        if (
            ("StreamFifo" in text or "Stream.scala" in text) and
            "transformNativeParameterizedComponent(value)" in text
        ):
            indent = lines[i].split("case")[0]
            out.append(
                indent +
                "case value: ClassDef if isNativeParameterizedComponent(value) =>\n"
            )
            out.append(
                indent + "  transformNativeParameterizedComponent(value)\n"
            )
            i = j + 1
            replaced_dispatch += 1
            continue
    out.append(lines[i])
    i += 1
p = "".join(out)
if (
    replaced_dispatch == 0 and
    "case value: ClassDef if isNativeParameterizedComponent(value)" not in p
):
    raise SystemExit("generic native Component dispatch was not installed")

start = p.find("    private def transformNativeParameterizedComponent(")
if start < 0:
    raise SystemExit("generic native Component transformer is missing")
brace = p.find("{", start)
depth = 0
end = None
for index in range(brace, len(p)):
    if p[index] == "{":
        depth += 1
    elif p[index] == "}":
        depth -= 1
        if depth == 0:
            end = index + 1
            break
if end is None:
    raise SystemExit("generic native Component transformer is unbalanced")
block = p[start:end]
if "filter(isNativeIntConstructorParameter)" not in block:
    patterns = [
        r'parameters\.find\(parameter => decoded\(parameter\.name\) == "depth"\)',
        r'parameters\.find\([^\n]*"depth"[^\n]*\)',
    ]
    lookup = '''parameters.filter(isNativeIntConstructorParameter) match {
        case Vector(single) => Some(single)
        case _              => None
      }'''
    replaced = 0
    for pattern in patterns:
        block, count = re.subn(pattern, lookup, block, count=1)
        if count:
            replaced = count
            break
    if replaced == 0:
        raise SystemExit("typed native Int constructor-parameter lookup missing")
block = re.sub(r"\bdepth\b", "formalParameter", block)
for old, new in [
    ("StreamFifoCC", "native Component"),
    ("StreamFifo", "native Component"),
    ("STREAMFIFO", "COMPONENT"),
    ("stream FIFO", "native Component"),
    ("stream fifo", "native component"),
]:
    block = block.replace(old, new)
p = p[:start] + block + p[end:]
p = p.replace(
    'Set("StreamFifo", "StreamFifoCC").contains(decoded(value.name))',
    "isNativeParameterizedComponent(value)"
)
p = p.replace(
    'Set("StreamFifoCC", "StreamFifo").contains(decoded(value.name))',
    "isNativeParameterizedComponent(value)"
)
plugin.write_text(p)


# ---------------------------------------------------------------------------
# Permanent generic boundary guard
# ---------------------------------------------------------------------------
guard = Path("morphhdl/scripts/check-generic-native-parameterization-engine.sh")
guard.write_text('''#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$root"
mapfile -t hits < <(
  grep -RInE 'StreamFifo(CC)?|Stream\\.scala|CrossClock\\.scala|BufferCC' \\
    morphplugin/src/main \\
    morphruntime/src/main \\
    morphhdl/src/main/scala/spinal/core/internals \\
    || true
)
if ((${#hits[@]} != 0)); then
  printf '%s\\n' \\
    'MORPHDL-GENERIC-NATIVE-PARAMETERIZATION-BOUNDARY-FAILED:' \\
    'parameterization engine code must not select native components by class name or source filename.' \\
    "${hits[@]}" >&2
  exit 1
fi
printf '%s\\n' \\
  'MORPHDL-GENERIC-NATIVE-PARAMETERIZATION-BOUNDARY-PASS:' \\
  'no FIFO/component/file-name selector exists in compiler, runtime, or backend parameterization code.'
''')
guard.chmod(0o755)


# ---------------------------------------------------------------------------
# Permanent CI workflows
# ---------------------------------------------------------------------------
parameterization_workflow = Path(
    ".github/workflows/morphhdl-native-streamfifocc-parameterization.yml"
)
parameterization_workflow.write_text('''name: MorphHDL native StreamFifoCC parameterization

on:
  push:
    branches:
      - agent/increment-53e-native-streamfifocc-parameterization
      - parameterized-verilog
  pull_request:
    branches:
      - parameterized-verilog
  workflow_dispatch:

permissions:
  contents: read

jobs:
  generic-boundary:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0
      - name: Prohibit component-specific parameterization selectors
        run: bash morphhdl/scripts/check-generic-native-parameterization-engine.sh
      - name: Preserve native SpinalHDL production sources
        run: bash morphhdl/scripts/check-native-streamfifocc-parameterization-boundary.sh

  scala-contract:
    needs: generic-boundary
    runs-on: ubuntu-latest
    container:
      image: ghcr.io/spinalhdl/docker:latest
    timeout-minutes: 90
    strategy:
      fail-fast: false
      matrix:
        scala: [2.12.18, 2.13.12]
    steps:
      - uses: actions/checkout@v4
      - name: Install pinned Mill bootstrap
        shell: bash
        run: |
          set -euo pipefail
          curl --fail --location --retry 5 --retry-all-errors \\
            https://repo.maven.apache.org/maven2/com/lihaoyi/mill-dist/1.1.0/mill-dist-1.1.0.exe \\
            --output /tmp/morphhdl-mill
          chmod +x /tmp/morphhdl-mill
      - name: Run generic domain, native contract and implementation proofs
        shell: bash
        env:
          MORPHDL_RUN_STREAMFIFOCC_IMPLEMENTATION_PROOF: "1"
          MORPHDL_STREAMFIFOCC_IMPLEMENTATION_WORKSPACE: /tmp/morphhdl-streamfifocc-implementation-${{ matrix.scala }}
        run: |
          set -euo pipefail
          /tmp/morphhdl-mill "morph[${{ matrix.scala }}].testOnly" \\
            spinal.core.internals.ExternalParameterizedDomainEquivalenceTests \\
            morphhdl.ParameterizedStreamFifoCCTests \\
            morphhdl.NativeStreamFifoCCImplementationProofTests

  inherited-regressions:
    needs: generic-boundary
    runs-on: ubuntu-latest
    container:
      image: ghcr.io/spinalhdl/docker:latest
    timeout-minutes: 90
    strategy:
      fail-fast: false
      matrix:
        scala: [2.12.18, 2.13.12]
    steps:
      - uses: actions/checkout@v4
      - name: Install pinned Mill bootstrap
        shell: bash
        run: |
          set -euo pipefail
          curl --fail --location --retry 5 --retry-all-errors \\
            https://repo.maven.apache.org/maven2/com/lihaoyi/mill-dist/1.1.0/mill-dist-1.1.0.exe \\
            --output /tmp/morphhdl-mill
          chmod +x /tmp/morphhdl-mill
      - name: Run complete MorphHDL regressions
        shell: bash
        run: |
          set -euo pipefail
          /tmp/morphhdl-mill "morph[${{ matrix.scala }}].test"
''')

formal_source = Path(
    ".github/workflows/morphhdl-native-streamfifo-formal-equivalence.yml"
)
formal_target = Path(
    ".github/workflows/morphhdl-native-streamfifocc-formal-equivalence.yml"
)
if not formal_source.exists():
    raise SystemExit("reviewed native StreamFifo formal workflow is missing")
f = formal_source.read_text()
for old, new in [
    ("Native StreamFifo formal equivalence", "Native StreamFifoCC formal equivalence"),
    ("native StreamFifo formal equivalence", "native StreamFifoCC formal equivalence"),
    ("NativeStreamFifoFormalEquivalenceTests", "NativeStreamFifoCCFormalEquivalenceTests"),
    ("STREAMFIFO_FORMAL", "STREAMFIFOCC_FORMAL"),
    ("streamfifo-formal", "streamfifocc-formal"),
    ("streamfifo_formal", "streamfifocc_formal"),
    ("streamfifo formal", "streamfifocc formal"),
    ("StreamFifo formal", "StreamFifoCC formal"),
]:
    f = f.replace(old, new)
branch_line = "      - parameterized-verilog\n"
if "agent/increment-53e-native-streamfifocc-parameterization" not in f:
    f = replace_once(
        f,
        branch_line,
        branch_line +
        "      - agent/increment-53e-native-streamfifocc-parameterization\n",
        "formal workflow feature trigger"
    )
formal_target.write_text(f)


# ---------------------------------------------------------------------------
# Roadmap closure and generic contract
# ---------------------------------------------------------------------------
roadmap = Path("docs/morphhdl/parameterized-verilog-todo.md")
t = roadmap.read_text()
open_line = (
    "- [ ] **Increment 53e — Native StreamFifoCC parameterization without "
    "source edits**"
)
closed_line = open_line.replace("- [ ]", "- [x]", 1)
if open_line in t:
    t = t.replace(open_line, closed_line, 1)
elif closed_line not in t:
    raise SystemExit("Increment 53e roadmap heading is missing")
requirement = (
    "  - The compiler/runtime/backend support must be generic across native "
    "SpinalHDL: class names, signal names and source filenames are forbidden "
    "as parameterization proof or dispatch keys. Use exact graph identity, "
    "compiler-retained provenance and exhaustive bounded-domain evaluation "
    "instead.\n"
)
if requirement not in t:
    t = t.replace(closed_line, closed_line + "\n" + requirement.rstrip(), 1)
roadmap.write_text(t)


# Final fail-closed implementation checks.
engine_files = [
    plugin,
    registry,
    fallback,
    domain_service,
]
for path in engine_files:
    text = path.read_text()
    for forbidden in ("StreamFifo", "StreamFifoCC", "Stream.scala", "CrossClock.scala", "BufferCC"):
        if forbidden in text:
            raise SystemExit(
                f"generic native parameterization boundary: {path} contains {forbidden}"
            )
