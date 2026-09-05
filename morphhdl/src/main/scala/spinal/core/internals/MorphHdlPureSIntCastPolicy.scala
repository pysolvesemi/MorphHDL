package spinal.core.internals

import spinal.core._
import morphhdl.analysis.SignednessFacts._
import MorphHdlSignednessAnalysis._
import VerilogBase._

/** 60d proof boundary: $signed(reference) and the same signed reference have
  * identical width, value and extension in every enclosing context. This is
  * an identity proof, not equality of concrete width witnesses. An inline
  * operation is deliberately NOT admitted: removing its self-determined cast
  * boundary could propagate a wider context into overflowing arithmetic.
  * The existing native wrapper plan is retained. It provides the exact
  * intermediate-width boundaries without recovering sizes from witnesses.
  */
private[spinal] final class MorphHdlPureSIntCastPolicy(emitter: VerilogBase, snapshot: Snapshot) {
  require(emitter != null && snapshot != null, "pure SInt casts require emitter and graph authority")

  private val binaryArithmetic: Set[Class[_]] = Set(
    classOf[Operator.SInt.Add], classOf[Operator.SInt.Sub], classOf[Operator.SInt.Mul],
    classOf[Operator.SInt.Div], classOf[Operator.SInt.Mod])
  private val relational: Set[Class[_]] = Set(
    classOf[Operator.SInt.Smaller], classOf[Operator.SInt.SmallerOrEqual])
  private val unaryArithmetic: Set[Class[_]] = Set(classOf[Operator.SInt.Minus])
  private val constantRightShift: Set[Class[_]] = Set(
    classOf[Operator.SInt.ShiftRightByInt], classOf[Operator.SInt.ShiftRightByIntFixedWidth])

  private def fact(expression: Expression): Fact =
    snapshot.validate(expression, snapshot.expression(expression), ExpressionUse)

  private def pure(expression: Expression, component: Component): Boolean = {
    val value = fact(expression)
    if (value.intent != SignedScalar || value.value != SignedScalar ||
        value.nativeBits <= 0 || value.rule == Unsupported) return false
    expression match {
      case scalar: SInt if (scalar.component eq component) && !scalar.isSuffix =>
        // A reference is terminal: never infer its type/width from its driver.
        val declared = snapshot.validate(scalar, snapshot.declaration(scalar), DeclarationUse)
        declared.intent == SignedScalar && declared.rule == Reference
      case op: BinaryOperator if binaryArithmetic(op.getClass) =>
        pure(op.left, component) && pure(op.right, component)
      case op: UnaryOperator if unaryArithmetic(op.getClass) =>
        pure(op.source, component)
      case op: ConstantOperator if constantRightShift(op.getClass) =>
        pure(op.source, component)
      case op: Operator.SInt.ShiftRightByUInt if op.getClass == classOf[Operator.SInt.ShiftRightByUInt] =>
        val amount = fact(op.right)
        pure(op.left, component) && amount.intent == UnsignedScalar &&
          amount.value == UnsignedScalar && amount.rule != Unsupported
      case _ => false // literals, resizes, muxes, selects, casts and external boundaries: 60e
    }
  }

  def elide(occurrence: SignedCastOccurrence): Boolean = {
    if (occurrence == null || (occurrence.emitter ne emitter))
      throw new MorphHdlSignednessException("MORPH-SIGNEDNESS-CAST-USE",
        "signed cast occurrence belongs to another native emitter")
    val parent = occurrence.parent
    // Validate the exact parent/slot edge even when its operator is ineligible.
    // A stale operand or a copied fact must never become removal permission.
    val operand = snapshot.validateCastOperand(parent, occurrence.slot,
      snapshot.castOperand(parent, occurrence.slot))
    snapshot.validate(occurrence.operand, snapshot.castOperand(parent, occurrence.slot), CastOperandUse)
    val parentFact = fact(parent)
    val admitted = parent match {
      case op: BinaryOperator if relational(op.getClass) =>
        parentFact.value == BooleanValue && pure(op.left, occurrence.component) &&
          pure(op.right, occurrence.component)
      case _ => pure(parent, occurrence.component)
    }
    if (!admitted || operand.intent != SignedScalar || operand.value != SignedScalar ||
        operand.nativeBits <= 0) return false
    occurrence.referenceRole match {
      case Some(ScalarDeclaration) =>
        occurrence.operand match {
          case scalar: SInt =>
            snapshot.validate(scalar, snapshot.declaration(scalar), DeclarationUse)
            true // same declared atom: no sizing or context boundary is removed
          case _ => false
        }
      case Some(ExpressionWrapper) =>
        // The real native signed wrapper has already validated its positive
        // symbolic range. Revalidate it here rather than freezing a witness.
        snapshot.requireKnown(occurrence.operand, snapshot.expression(occurrence.operand), ExpressionUse)
        occurrence.operand.getTypeObject == TypeSInt
      case _ => false // inline expressions, overrides and uncertain hierarchy
    }
  }
}
