#!/usr/bin/env python3
from pathlib import Path

path = Path(
    "morphhdl/src/main/scala/spinal/core/internals/"
    "ExternalParameterizedVerilogNativeFallback.scala"
)
value = path.read_text()

# Add a strict schema comparison for independently retained roots. Identity is
# still used to find each expression's own evaluator; schema equality only
# aligns their bounded domain coordinates.
marker = '''      /** Exact bounded evaluation; unsupported or unproven nodes return None. */
'''
method = '''      private def sameRootSchema(
          left: ParameterizedStructure.StructuralPredicateRoot,
          right: ParameterizedStructure.StructuralPredicateRoot
      ): Boolean =
        left.verilog == right.verilog &&
          left.default == right.default &&
          left.minimum == right.minimum &&
          left.maximum == right.maximum &&
          left.parameters == right.parameters

'''
if "private def sameRootSchema(" not in value:
    if value.count(marker) != 1:
        raise SystemExit("root-schema method marker is ambiguous")
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
          Option(retainedOrigins.get(retained)).flatMap { origin =>
            ExternalNativeIntShadowRegistry
              .definitionExpressionRoot(origin)
              .filter(originRoot => sameRootSchema(originRoot, root))
              .flatMap(originRoot =>
                ExternalNativeIntShadowRegistry.evaluateDefinitionExpression(
                  origin,
                  originRoot,
                  value
                )
              )
          }
'''
if old in value:
    value = value.replace(old, new, 1)

old = '''        val roots = (provenanceRoots(left) ++ provenanceRoots(right)).toVector
        if (roots.size != 1) return false
        val root = roots.head
'''
new = '''        val roots = (provenanceRoots(left) ++ provenanceRoots(right)).toVector
        if (roots.isEmpty) return false
        val root = roots.head
        if (!roots.forall(candidate => sameRootSchema(candidate, root))) return false
'''
if old in value:
    value = value.replace(old, new, 1)

path.write_text(value)
