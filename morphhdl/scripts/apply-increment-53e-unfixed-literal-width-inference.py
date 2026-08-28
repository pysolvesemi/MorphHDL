#!/usr/bin/env python3
from pathlib import Path

path = Path(
    "morphhdl/src/main/scala/spinal/core/internals/"
    "ExternalParameterizedVerilogNativeFallback.scala"
)
value = path.read_text()

infer_marker = '''      private def inferExpression(expression: Expression): WidthExpr = expression match {
'''
helper = '''      /**
        * Spinal normalizes an unsized Scala literal to the concrete witness
        * width of its UInt peer. That normalized literal is not a fixed packed
        * width contract. When exactly one Add/Sub operand retains symbolic
        * width and the other is still proven to be an unfixed literal, keep the
        * symbolic peer width instead of turning the witness width into a floor.
        * Explicitly sized literals and two non-literal operands retain the
        * ordinary native max-width rule.
        */
      private def adaptiveLiteralBinaryWidth(
          leftExpression: Expression,
          rightExpression: Expression
      ): WidthExpr = {
        val left = operandWidth(leftExpression)
        val right = operandWidth(rightExpression)
        if (left.isSymbolic && isUnfixedLiteral(rightExpression)) left
        else if (right.isSymbolic && isUnfixedLiteral(leftExpression)) right
        else widthMax(left, right)
      }

'''
if value.count(infer_marker) != 1:
    raise SystemExit(
        f"unfixed-literal helper marker count={value.count(infer_marker)}"
    )
value = value.replace(infer_marker, helper + infer_marker, 1)

old_cases = '''        case operator: Operator.BitVector.Add =>
          widthMax(operandWidth(operator.left), operandWidth(operator.right))
        case operator: Operator.BitVector.Sub =>
          widthMax(operandWidth(operator.left), operandWidth(operator.right))
'''
new_cases = '''        case operator: Operator.BitVector.Add =>
          adaptiveLiteralBinaryWidth(operator.left, operator.right)
        case operator: Operator.BitVector.Sub =>
          adaptiveLiteralBinaryWidth(operator.left, operator.right)
'''
if value.count(old_cases) != 1:
    raise SystemExit(
        f"unfixed-literal Add/Sub marker count={value.count(old_cases)}"
    )
value = value.replace(old_cases, new_cases, 1)

path.write_text(value)
