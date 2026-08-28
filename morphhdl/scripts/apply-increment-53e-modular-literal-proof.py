#!/usr/bin/env python3
from pathlib import Path

path = Path(
    "morphhdl/src/main/scala/spinal/core/internals/"
    "ExternalParameterizedVerilogNativeFallback.scala"
)
value = path.read_text()

replacements = [
    (
        '''    private final case class ModularUIntFacts(
        targetReferences: Int,
        booleanValues: Int
    )
''',
        '''    private final case class ModularUIntFacts(
        targetReferences: Int,
        independentValues: Int
    )
''',
        "modular fact fields",
    ),
    (
        '''      * A direct unsigned self-update made only from Add/Sub and Boolean values
      * is stable modulo the symbolic target width. Native normalization may
      * widen Boolean-to-UInt carriers to the concrete witness, but the whole
''',
        '''      * A direct unsigned self-update made only from Add/Sub and independent
      * Boolean or literal values is stable modulo the symbolic target width.
      * Native normalization may widen those operands to the concrete witness,
      * but the whole
''',
        "modular proof comment",
    ),
    (
        '''              leftFacts.targetReferences + rightFacts.targetReferences,
              leftFacts.booleanValues + rightFacts.booleanValues
''',
        '''              leftFacts.targetReferences + rightFacts.targetReferences,
              leftFacts.independentValues + rightFacts.independentValues
''',
        "modular fact combination",
    ),
    (
        '''              case _: CastBoolToBits => Some(ModularUIntFacts(0, 1))
              case resize: Resize
''',
        '''              case _: CastBoolToBits => Some(ModularUIntFacts(0, 1))
              case _: BitVectorLiteral => Some(ModularUIntFacts(0, 1))
              case resize: Resize
''',
        "literal fact recognition",
    ),
    (
        '''            facts.targetReferences == 1 && facts.booleanValues >= 1
''',
        '''            facts.targetReferences == 1 && facts.independentValues >= 1
''',
        "modular proof completion",
    ),
]

for old, new, label in replacements:
    count = value.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    value = value.replace(old, new, 1)

path.write_text(value)
