#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
path = ROOT / "morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala"
value = path.read_text()

old = '''            Option(retainedOrigins.get(retained))
              .flatMap(ExternalNativeIntShadowRegistry.definitionExpressionRootOf)
              .fold(complete = false)(root =>
                roots.put(root, java.lang.Boolean.TRUE)
              )
'''
new = '''            Option(retainedOrigins.get(retained))
              .flatMap(ExternalNativeIntShadowRegistry.definitionExpressionRootOf) match {
              case Some(root) => roots.put(root, java.lang.Boolean.TRUE)
              case None       => complete = false
            }
'''
if old in value:
    value = value.replace(old, new, 1)

# Refuse any accidental text/formula recognition in the proof implementation.
proof_start = value.find("def provesEquivalentAcrossCompleteDomain")
if proof_start >= 0:
    proof_end = value.find("\n      def ofBase", proof_start)
    if proof_end < 0:
        raise SystemExit("generic proof end marker missing")
    proof = value[proof_start:proof_end]
    forbidden = (
        "StreamFifo",
        "StreamFifoCC",
        "BufferCC",
        "morphhdl_address_width(DEPTH)",
        "2 * DEPTH",
        "getName()",
    )
    for marker in forbidden:
        if marker in proof:
            raise SystemExit(f"generic proof contains forbidden recognition: {marker}")
else:
    raise SystemExit("generic complete-domain proof was not materialized")

path.write_text(value)
