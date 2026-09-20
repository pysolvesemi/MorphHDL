package spinal.core.internals

import spinal.core._
import morphhdl.analysis.SignednessFacts._
import MorphHdlSignednessAnalysis._
import VerilogBase._

/** Same-atom cast elimination. 60e extends the 60d identity proof to signed
  * reconstruction temporaries and signed literals, never to inline expression
  * trees. The native wrapper plan continues to enforce every intermediate
  * overflow/truncation boundary, independent of the destination's signedness.
  */
private[spinal] final class MorphHdlPureSIntCastPolicy(emitter: VerilogBase, snapshot: Snapshot) {
  require(emitter != null && snapshot != null, "SInt casts require emitter and graph authority")

  private val signedBinary: Set[Class[_]] = Set(
    classOf[Operator.SInt.Add], classOf[Operator.SInt.Sub], classOf[Operator.SInt.Mul],
    classOf[Operator.SInt.Div], classOf[Operator.SInt.Mod],
    classOf[Operator.SInt.Smaller], classOf[Operator.SInt.SmallerOrEqual],
    classOf[Operator.SInt.Equal], classOf[Operator.SInt.EqualSim], classOf[Operator.SInt.NotEqual])
  private val constantShift: Set[Class[_]] = Set(
    classOf[Operator.SInt.ShiftRightByInt], classOf[Operator.SInt.ShiftRightByIntFixedWidth],
    classOf[Operator.SInt.ShiftLeftByInt], classOf[Operator.SInt.ShiftLeftByIntFixedWidth])

  private def fact(expression: Expression): Fact =
    snapshot.validate(expression, snapshot.expression(expression), ExpressionUse)

  def elide(occurrence: SignedCastOccurrence): Boolean = {
    if (occurrence == null || (occurrence.emitter ne emitter))
      throw new MorphHdlSignednessException("MORPH-SIGNEDNESS-CAST-USE",
        "signed cast occurrence belongs to another native emitter")
    val parent = occurrence.parent
    // Validate both the original edge and the current operand before deciding.
    val operand = snapshot.validateCastOperand(parent, occurrence.slot,
      snapshot.castOperand(parent, occurrence.slot))
    snapshot.validate(occurrence.operand, snapshot.castOperand(parent, occurrence.slot), CastOperandUse)
    val parentFact = fact(parent)
    val admitted = parent match {
      case op: BinaryOperator if signedBinary(op.getClass) =>
        fact(op.left).intent == SignedScalar && fact(op.right).intent == SignedScalar
      case op: ConstantOperator if constantShift(op.getClass) =>
        fact(op.source).intent == SignedScalar
      case op: BinaryOperator if op.getClass == classOf[Operator.SInt.ShiftRightByUInt] ||
          op.getClass == classOf[Operator.SInt.ShiftLeftByUIntFixedWidth] =>
        val amount = fact(op.right)
        occurrence.slot == 0 && fact(op.left).intent == SignedScalar &&
          amount.intent == UnsignedScalar && amount.value == UnsignedScalar && amount.rule != Unsupported
      case _ => false
    }
    if (!admitted || parentFact.rule == Unsupported || operand.intent != SignedScalar ||
        operand.nativeBits <= 0 || operand.rule == Unsupported) return false
    occurrence.referenceRole match {
      case Some(ScalarDeclaration) => occurrence.operand match {
        case scalar: SInt =>
          val declared = snapshot.validate(scalar, snapshot.declaration(scalar), DeclarationUse)
          declared.intent == SignedScalar && declared.rule == Reference
        case _ => false
      }
      case Some(ExpressionWrapper) =>
        // A declared signed atom owns interpretation, including reconstructed
        // slices, mux choices, resizes and Bits/UInt conversions. Its unsigned
        // driver does not invalidate the declaration, nor authorize inlining.
        MorphHdlSignedWidth.resolve(snapshot, occurrence.operand,
          snapshot.expression(occurrence.operand), ExpressionUse)
        occurrence.operand.getTypeObject == TypeSInt
      case None => occurrence.operand match {
        case literal: SIntLiteral if literal.getClass == classOf[SIntLiteral] && !literal.hasPoison() =>
          occurrence.isSignedLiteral // the native literal printer uses the same policy
        case _ => false // selection, concatenation, overrides and uncertain external boundaries
      }
      case _ => false
    }
  }
}
