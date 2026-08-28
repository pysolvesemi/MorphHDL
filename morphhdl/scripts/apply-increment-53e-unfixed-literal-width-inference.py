#!/usr/bin/env python3
from pathlib import Path

path = Path(
    "morphhdl/src/main/scala/spinal/core/internals/"
    "ExternalParameterizedVerilogNativeFallback.scala"
)
value = path.read_text()

old = '''      private def inferExpression(expression: Expression): WidthExpr = expression match {
        case resize: Resize => WidthLiteral(BigInt(resize.size))
        case operator: Operator.BitVector.Add =>
          widthMax(operandWidth(operator.left), operandWidth(operator.right))
        case operator: Operator.BitVector.Sub =>
          widthMax(operandWidth(operator.left), operandWidth(operator.right))
'''
new = '''      /**
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

      private def inferExpression(expression: Expression): WidthExpr = expression match {
        case resize: Resize => WidthLiteral(BigInt(resize.size))
        case operator: Operator.BitVector.Add =>
          adaptiveLiteralBinaryWidth(operator.left, operator.right)
        case operator: Operator.BitVector.Sub =>
          adaptiveLiteralBinaryWidth(operator.left, operator.right)
'''

count = value.count(old)
if count != 1:
    raise SystemExit(
        f"unfixed-literal width inference: expected one match, found {count}"
    )

path.write_text(value.replace(old, new, 1))
