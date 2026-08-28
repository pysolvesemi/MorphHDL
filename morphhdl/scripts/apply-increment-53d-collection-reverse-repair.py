#!/usr/bin/env python3
from pathlib import Path

path = Path(
    "morphplugin/src/main/scala/morphhdl/compiler/"
    "MorphHdlNativeIntShadowExpressionComponent.scala"
)
text = path.read_text()
old = """        case Select(_, methodName) if unsupportedIntegerCalls.contains(decoded(methodName)) =>
          firstTrackedInteger(tree)
            .map(rewriteUnsupportedKnownCall(tree, _, decoded(methodName)))
            .getOrElse(Rewrite(super.transform(tree)))
"""
new = """        case Select(value, methodName) if unsupportedIntegerCalls.contains(decoded(methodName)) =>
          // A tracked Int nested somewhere below the receiver is not proof that
          // the selected method operates on an Int. In particular,
          // `subdivideIn(factor slices).reverse` is a collection reversal whose
          // collection expression merely contains the symbolic `factor`.
          trackedInteger(value)
            .map(rewriteUnsupportedKnownCall(tree, _, decoded(methodName)))
            .getOrElse(Rewrite(super.transform(tree)))
"""
if text.count(old) != 1:
    raise SystemExit("expected exactly one unsupported Select rewrite anchor")
path.write_text(text.replace(old, new))
