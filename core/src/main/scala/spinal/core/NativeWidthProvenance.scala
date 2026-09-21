package spinal.core

import java.lang.ref.WeakReference
import scala.collection.mutable.ArrayBuffer
import spinal.core.internals._

/** Mechanical width transfers for native scalar IR. These rules carry typed
  * geometry beside the original graph; they never replace native operators,
  * alter width inference, or derive symbolic geometry from an Int witness.
  */
object NativeWidthProvenance {
  /** An optional late optimization cannot reopen an elaboration branch after
    * its captured domain has ended. Missing scoped evidence retains the
    * original graph; malformed metadata and unrelated compiler errors still
    * propagate. Construction-time callers continue to use widthOf directly.
    */
  def availableEvidence[A](proof: => A): Option[A] = {
    try Some(proof)
    catch {
      case failure: ParameterizedVerilogException
          if failure.code == "SPINAL-ELAB-DOMAIN-PROJECTION-SCOPE-EXPANSION" ||
            failure.code == "SPINAL-PARAMETERIZED-VERILOG-WIDTH-EXACT-DOMAIN-REQUIRED" => None
    }
  }

  def optionalWidthOf(expression: Expression): Option[ElaborationIntegerExpression] =
    availableEvidence(widthOf(expression)).flatten

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
    case value if value != null && value.getTypeObject == TypeBool => constant(1)
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
    case value: BitVectorLiteral => fillWidthOf(value).orElse(constant(value.getWidth))
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
    // Shift amount operands are self-determined; these transfers describe
    // only the packed result and never use a default-width witness as authority.
    case value: Operator.BitVector.ShiftRightByUInt => widthOf(value.left)
    case value: Operator.BitVector.ShiftRightByIntFixedWidth => widthOf(value.source)
    case value: Operator.BitVector.ShiftLeftByIntFixedWidth => widthOf(value.source)
    case value: Operator.BitVector.ShiftLeftByUIntFixedWidth => widthOf(value.left)
    case value: Operator.BitVector.ShiftRightByInt =>
      widthOf(value.source).map { source =>
        ElaborationWidthAuthority.maximum(ElabInt.literal(0).expression,
          ElaborationWidthAuthority.subtract(source, ElabInt.literal(value.shift).expression))
      }
    case value: Operator.BitVector.ShiftLeftByInt =>
      widthOf(value.source).map(ElaborationWidthAuthority.addNative(_, ElabInt.literal(value.shift).expression))
    case value: BitVectorRangedAccessFixed => constant(value.getWidth)
    case value: BitVectorRangedAccessFloating => constant(value.size)
    case value: Resize =>
      ParameterizedWidth.resizeExpressionOf(value).orElse(constant(value.size))
    case _: BitVectorBitAccessFixed => constant(1)
    case _: BitVectorBitAccessFloating => constant(1)
    case value: BitVectorRangedAccessFixed =>
      for (geometry <- rangeOf(value); width <- widthOf(value.source))
        yield geometry.resultWidth(width)
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
    val copied = ParameterizedWidth.copyCloneMetadata(source, result)
    NativeVecZeroConstruction.cloned(source, copied)
    copied
  }

  private def offset(width: ElaborationIntegerExpression, amount: Int): ElaborationIntegerExpression =
    if (amount == 0) width
    else if (amount < 0) ElaborationWidthAuthority.subtract(width, ElabInt.literal(-amount).expression)
    else ElaborationWidthAuthority.add(width, ElabInt.literal(amount).expression)

  /** Bounds explicitly recorded at a native source-relative construction site.
    * The flags are never inferred from equality with the default width.
    */
  final class RangeGeometry private[NativeWidthProvenance] (
      val highRelative: Boolean, val highOffset: Int,
      val lowRelative: Boolean, val lowOffset: Int
  ) {
    val key: (Boolean, Int, Boolean, Int) = (highRelative, highOffset, lowRelative, lowOffset)
    def high(width: ElaborationIntegerExpression): ElaborationIntegerExpression =
      if (highRelative) offset(width, highOffset) else ElabInt.literal(highOffset).expression
    def low(width: ElaborationIntegerExpression): ElaborationIntegerExpression =
      if (lowRelative) offset(width, lowOffset) else ElabInt.literal(lowOffset).expression
    def resultWidth(width: ElaborationIntegerExpression): ElaborationIntegerExpression =
      offset(ElaborationWidthAuthority.subtract(high(width), low(width)), 1)
    def validFor(width: ElaborationIntegerExpression): Boolean =
      low(width).minimum >= 0 && resultWidth(width).minimum >= 1 &&
        ElaborationWidthAuthority.subtract(width, high(width)).minimum >= 1
  }
  private final case class RangeRecord(
      access: WeakReference[BitVectorRangedAccessFixed],
      source: WeakReference[Expression with WidthProvider],
      hi: Int, lo: Int, sourceWidth: Int,
      authoritativeWidth: ElaborationIntegerExpression, geometry: RangeGeometry)
  private val retainedRanges = ArrayBuffer.empty[RangeRecord]

  /** A changed access/source/index no longer carries the original certificate.
    * Freshness compares the retained symbolic identity, not equivalence after
    * projecting to the currently active branch (which may be a singleton).
    */
  def rangeOf(access: BitVectorRangedAccessFixed): Option[RangeGeometry] = synchronized {
    retainedRanges --= retainedRanges.filter(_.access.get == null)
    retainedRanges.find(record => (record.access.get eq access) &&
      (record.source.get eq access.source) && record.source.get != null &&
      record.hi == access.hi && record.lo == access.lo &&
      record.source.get.getWidth == record.sourceWidth &&
      widthOf(access.source).exists(current =>
        ElabInt.equivalentExpression(record.authoritativeWidth, current))).map(_.geometry)
  }

  private[core] def retainRange(access: BitVectorRangedAccessFixed, geometry: RangeGeometry): Unit = synchronized {
    val width = widthOf(access.source).getOrElse(
      throw new IllegalArgumentException("SPINAL-NATIVE-RANGE-WIDTH-AUTHORITY: source width is unproved"))
    require(geometry.validFor(width) && geometry.high(width).default == access.hi &&
      geometry.low(width).default == access.lo,
      "SPINAL-NATIVE-RANGE-GEOMETRY: recorded native bounds must match throughout their exact domain")
    retainedRanges.find(_.access.get eq access) match {
      case Some(record) => require(rangeOf(access).contains(record.geometry) && record.geometry.key == geometry.key,
        "SPINAL-NATIVE-RANGE-CONFLICT: exact native range already has different geometry")
      case None => retainedRanges += RangeRecord(new WeakReference(access), new WeakReference(access.source),
        access.hi, access.lo, access.source.getWidth, width, geometry)
    }
  }

  private[core] def retainRelativeRange[T <: BitVector](source: BitVector, result: T,
      highOffset: Int, lowRelative: Boolean, lowOffset: Int): T = {
    if (result.hasOnlyOneStatement) result.head.source match {
      case access: BitVectorRangedAccessFixed if access.source eq source =>
        widthOf(source).foreach { width =>
          val geometry = new RangeGeometry(true, highOffset, lowRelative, lowOffset)
          if (geometry.validFor(width)) {
            retainRange(access, geometry)
            ParameterizedWidth.retainNativeWidth(result, geometry.resultWidth(width))
          }
        }
      case _ =>
    }
    result
  }

  private final case class RelativeWidth(
      target: WeakReference[BitVector], source: WeakReference[BitVector],
      amount: Int, width: ElaborationIntegerExpression,
      authoritativeSourceWidth: ElaborationIntegerExpression, targetWidth: Int, sourceWidth: Int)
  private val relativeWidths = ArrayBuffer.empty[RelativeWidth]

  private[core] def retainRelativeWidth[T <: BitVector](source: BitVector, result: T, amount: Int): T = synchronized {
    widthOf(source).foreach { sourceWidth =>
      val width = offset(sourceWidth, amount)
      if (width.minimum >= 1) {
        require(width.default == result.getBitsWidth,
          "SPINAL-NATIVE-RESULT-GEOMETRY: native result must match the retained source-relative width")
        ParameterizedWidth.retainNativeWidth(result, width)
        relativeWidths --= relativeWidths.filter(_.target.get == null)
        relativeWidths.find(_.target.get eq result) match {
          case Some(record) => require((record.source.get eq source) && record.amount == amount &&
            ElabInt.equivalentExpression(record.width, width) &&
            ElabInt.equivalentExpression(record.authoritativeSourceWidth, sourceWidth),
            "SPINAL-NATIVE-RESULT-CONFLICT: exact native result already has different geometry")
          case None => relativeWidths += RelativeWidth(new WeakReference(result), new WeakReference(source),
            amount, width, sourceWidth, result.getBitsWidth, source.getBitsWidth)
        }
      }
    }
    result
  }

  final class FillGeometry private[NativeWidthProvenance] (
      private val relativeSource: Option[WeakReference[BitVector]],
      val amount: Int, val width: ElaborationIntegerExpression
  ) {
    def source: Option[BitVector] = relativeSource.flatMap(value => Option(value.get))
    def resultWidth(sourceWidth: ElaborationIntegerExpression): ElaborationIntegerExpression =
      if (relativeSource.nonEmpty) offset(sourceWidth, amount) else width
  }
  private final case class FillRecord(literal: WeakReference[BitVectorLiteral],
      value: BigInt, bits: Int, specified: Boolean,
      sourceWidth: Option[ElaborationIntegerExpression], geometry: FillGeometry)
  private val retainedFills = ArrayBuffer.empty[FillRecord]

  def fillOf(literal: BitVectorLiteral): Option[FillGeometry] = synchronized {
    retainedFills --= retainedFills.filter(_.literal.get == null)
    retainedFills.find(record => (record.literal.get eq literal) && !literal.hasPoison &&
      literal.value == record.value && literal.bitCount == record.bits &&
      literal.hasSpecifiedBitCount == record.specified &&
      (record.sourceWidth match {
        case None => record.geometry.source.isEmpty
        case Some(original) => record.geometry.source.flatMap(widthOf).exists(current =>
          ElabInt.equivalentExpression(original, current))
      })).map(_.geometry)
  }
  def fillWidthOf(literal: BitVectorLiteral): Option[ElaborationIntegerExpression] =
    fillOf(literal).map(_.width)

  private[core] def retainFill(literal: BitVectorLiteral, width: ElaborationIntegerExpression,
      source: Option[BitVector], amount: Int): Unit = synchronized {
    require(width.minimum >= 1 && !literal.hasPoison && literal.value == (BigInt(1) << width.default.toInt) - 1 &&
      literal.getWidth == width.default,
      "SPINAL-NATIVE-FILL-GEOMETRY: only the exact native all-ones literal may retain fill geometry")
    val sourceWidth = source.flatMap(widthOf)
    // This is a fresh construction transfer in its admitted branch, unlike
    // the immutable source/width comparisons made when reusing a record.
    source.foreach(value => require(sourceWidth.exists(original =>
      ElaborationWidthAuthority.equivalent(offset(original, amount), width)),
      "SPINAL-NATIVE-FILL-SOURCE: source-relative fill must retain its exact width transfer"))
    retainedFills.find(_.literal.get eq literal) match {
      case Some(record) => require(fillOf(literal).contains(record.geometry) &&
        record.geometry.source.size == source.size &&
        record.geometry.source.zip(source).forall { case (a, b) => a eq b } && record.geometry.amount == amount &&
        ElabInt.equivalentExpression(record.geometry.width, width),
        "SPINAL-NATIVE-FILL-CONFLICT: exact native literal already has different geometry")
      case None => retainedFills += FillRecord(new WeakReference(literal), literal.value,
        literal.bitCount, literal.hasSpecifiedBitCount, sourceWidth,
        new FillGeometry(source.map(value => new WeakReference[BitVector](value)), amount, width))
    }
  }

  private[core] def retainAllOnes[T <: BitVector](target: BitVector, value: T): T = synchronized {
    widthOf(target).filter(_.minimum >= 1).foreach { width =>
      if (value.hasOnlyOneStatement) value.head.source match {
        case literal: BitVectorLiteral =>
          val relative = relativeWidths.find(record => (record.target.get eq target) &&
            record.source.get != null && record.source.get.getBitsWidth == record.sourceWidth &&
            target.getBitsWidth == record.targetWidth &&
            ElabInt.equivalentExpression(record.width, width) &&
            widthOf(record.source.get).exists(current =>
              ElabInt.equivalentExpression(record.authoritativeSourceWidth, current)))
          retainFill(literal, width, relative.map(_.source.get), relative.map(_.amount).getOrElse(0))
          ParameterizedWidth.retainNativeWidth(value, width)
        case _ =>
      }
    }
    value
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
