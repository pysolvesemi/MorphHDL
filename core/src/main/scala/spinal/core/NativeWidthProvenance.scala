package spinal.core

import java.lang.ref.WeakReference
import scala.collection.mutable.ArrayBuffer
import spinal.core.internals._

/** Mechanical width transfers for native scalar IR. These rules carry typed
  * geometry beside the original graph; they never replace native operators,
  * alter width inference, or derive symbolic geometry from an Int witness.
  */
object NativeWidthProvenance {
  private val traversal = new ThreadLocal[java.util.IdentityHashMap[
    Expression, Option[ElaborationIntegerExpression]]]()
  private def constant(value: Int): Option[ElaborationIntegerExpression] =
    if (value < 0) None else Some(ElabInt.literal(value).expression)

  private def combine(
      left: Expression,
      right: Expression
  )(operation: (ElaborationIntegerExpression, ElaborationIntegerExpression) => ElaborationIntegerExpression)
      : Option[ElaborationIntegerExpression] =
    for (a <- widthOf(left); b <- widthOf(right)) yield operation(a, b)

  private def maximum(inputs: Vector[Expression]): Option[ElaborationIntegerExpression] = {
    val fixed = inputs.filterNot(InferWidth.canBeResized)
    val selected = if (fixed.nonEmpty) fixed else inputs
    val widths = selected.map(widthOf)
    if (widths.isEmpty || widths.exists(_.isEmpty)) None
    else Some(widths.flatten.reduceLeft(ElaborationWidthAuthority.maximum))
  }

  /** Exact retained geometry, or a width proved by one supported native graph.
    * A fixed concrete leaf is a constant width. An unsupported inferred graph
    * has no proof, even if native inference can calculate its default width.
    */
  def widthOf(expression: Expression): Option[ElaborationIntegerExpression] = {
    val isRoot = traversal.get() == null
    if (isRoot) traversal.set(new java.util.IdentityHashMap[
      Expression, Option[ElaborationIntegerExpression]]())
    val known = traversal.get()
    try {
      if (known.containsKey(expression)) known.get(expression)
      else {
        // A recursive assignment graph supplies no finite width proof.
        known.put(expression, None)
        val result = deriveWidth(expression)
        known.put(expression, result)
        result
      }
    } finally {
      if (isRoot) traversal.remove()
    }
  }

  private def deriveWidth(expression: Expression): Option[ElaborationIntegerExpression] = expression match {
    case _: Bool => constant(1)
    case data: BitVector =>
      ParameterizedWidth.expressionOf(data) match {
        case some @ Some(value) if value.exactDomain.nonEmpty => some
        case Some(value) if value.parameters.isEmpty => Some(value)
        case Some(value) if ElaborationWidthAuthority.isAuthoritative(value) => Some(value)
        case Some(_) => None // historical direct metadata has no typed domain authority
        case None if data.isFixedWidth => constant(data.fixedWidth)
        case None if data.hasOnlyOneStatement => data.head match {
          case assignment: DataAssignmentStatement
              if (assignment.target eq data) && (assignment.finalTarget eq data) =>
            widthOf(assignment.source)
          case _ => None
        }
        case None => None
      }
    case value: BitVectorLiteral => constant(value.getWidth)
    case _: BoolLiteral => constant(1)
    case value: CastBitVectorToBitVector => widthOf(value.input)
    case _: CastBoolToBits => constant(1)
    case value: Operator.BitVector.Mul =>
      combine(value.left, value.right)(ElaborationWidthAuthority.addNative)
    case value: Operator.Bits.Cat =>
      combine(value.left, value.right)(ElaborationWidthAuthority.addNative)
    case value: Operator.BitVector.Add => maximum(Vector(value.left, value.right))
    case value: Operator.BitVector.Sub => maximum(Vector(value.left, value.right))
    case value: Operator.BitVector.And => maximum(Vector(value.left, value.right))
    case value: Operator.BitVector.Or => maximum(Vector(value.left, value.right))
    case value: Operator.BitVector.Xor => maximum(Vector(value.left, value.right))
    case value: Operator.BitVector.Div => widthOf(value.left)
    case value: Operator.BitVector.Mod =>
      combine(value.left, value.right)(ElaborationWidthAuthority.minimum)
    case value: BinaryMultiplexerWidthable => maximum(Vector(value.whenTrue, value.whenFalse))
    case value: MultiplexerWidthable => maximum(value.inputs.toVector)
    case value: Operator.Bits.Not => widthOf(value.source)
    case value: Operator.UInt.Not => widthOf(value.source)
    case value: Operator.SInt.Not => widthOf(value.source)
    case value: Operator.SInt.Minus => widthOf(value.source)
    case value: Resize =>
      ParameterizedWidth.resizeExpressionOf(value).orElse(constant(value.size))
    case _: BitVectorBitAccessFixed => constant(1)
    case _: BitVectorBitAccessFloating => constant(1)
    case _ => None
  }

  private[core] def retainResult[T <: BaseType](result: T, expression: Expression): T = {
    result match {
      case _: BitVector => widthOf(expression).foreach(ParameterizedWidth.retainNativeWidth(result, _))
      case _ =>
    }
    result
  }

  private[core] def retainMuxType[T <: Data](inputs: Vector[T], result: T): T = {
    result match {
      case leaf: BitVector =>
        ParameterizedWidth.retainNativeMuxWidth(leaf,
          maximum(inputs.map(_.asInstanceOf[Expression])))
      case _ =>
    }
    result
  }

  private[core] def retainCloneShape[T <: Data](source: T, result: T): T = {
    val sourceLeaves = source.flatten
    if (sourceLeaves.exists(widthOf(_).exists(_.parameters.nonEmpty)))
      ParameterizedWidth.copyShape(source, result)
    else result
  }

  private final case class HighBit(
      access: WeakReference[BitVectorBitAccessFixed],
      source: WeakReference[BitVector],
      witnessIndex: Int
  )
  private val highBits = ArrayBuffer.empty[HighBit]

  /** Record that the native author requested msb, before its Int index loses
    * the distinction between a fixed index and the current source's high bit.
    */
  private[core] def retainHighBit(source: BitVector, result: Bool): Bool = synchronized {
    if (result.hasOnlyOneStatement) {
      result.head.source match {
        case access: BitVectorBitAccessFixed if access.source eq source =>
          highBits += HighBit(new WeakReference(access), new WeakReference(source), access.bitId)
        case _ =>
      }
    }
    result
  }

  /** Exact-node query; graph mutation cannot turn a fixed index into an msb. */
  def isHighBit(access: BitVectorBitAccessFixed): Boolean = synchronized {
    highBits --= highBits.filter(_.access.get == null)
    highBits.exists(record =>
      (record.access.get eq access) && (record.source.get eq access.source) &&
        record.witnessIndex == access.bitId &&
        record.source.get != null && record.source.get.getBitsWidth == record.witnessIndex + 1)
  }
}
