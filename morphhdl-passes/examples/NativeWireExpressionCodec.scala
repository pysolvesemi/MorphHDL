package morphhdl.examples

import scala.collection.mutable.ArrayBuffer

import morphhdl.ir.v1.{NameOrigin, ReferenceId, RtlBinaryOperator, RtlExpr,
  RtlUnaryOperator, ScopeId, SymbolId}
import spinal.core._
import spinal.core.internals._

/**
  * Lossless, deliberately bounded capture of native pure RTL expressions.
  *
  * The native wire passes use the canonical passes as their decision authority.
  * A decision is useful only when the canonical RHS is the same expression as
  * the live native RHS, so unsupported nodes return `None`; they are never
  * replaced by a representative or fabricated witness expression.
  */
private[examples] final class NativeWireExpressionCodec(
    scopeId: ScopeId,
    identifierPrefix: String,
    predefinedSources: Vector[(BaseType, SymbolId)] = Vector.empty
) {
  // Store the validated scalar value, not SymbolId itself: SymbolId is an
  // AnyVal and a missing Java-map read can otherwise trigger null unboxing.
  private val sourceIds = new java.util.IdentityHashMap[BaseType, String]()
  private val sources = ArrayBuffer.empty[(BaseType, SymbolId)]
  private val active = new java.util.IdentityHashMap[Expression, java.lang.Boolean]()
  private var nextReference = 0

  predefinedSources.foreach { case (source, id) =>
    sourceIds.put(source, id.value)
    sources += source -> id
  }

  def capturedSources: Vector[(BaseType, SymbolId)] = sources.toVector

  def capture(value: Expression): Option[RtlExpr] = capture(value, "rhs")

  private def capture(value: Expression, path: String): Option[RtlExpr] = {
    if (value == null || active.put(value, java.lang.Boolean.TRUE) != null) return None
    try captureNode(value, path)
    finally active.remove(value)
  }

  private def captureNode(value: Expression, path: String): Option[RtlExpr] = {
    def unary(node: UnaryOperator, operator: RtlUnaryOperator): Option[RtlExpr] =
      capture(node.source, path + ".value").map(RtlExpr.Unary(operator, _))

    def binary(node: BinaryOperator, operator: RtlBinaryOperator): Option[RtlExpr] =
      for {
        left <- capture(node.left, path + ".left")
        right <- capture(node.right, path + ".right")
      } yield RtlExpr.Binary(operator, left, right)

    def constantBinary(
        source: Expression,
        constant: Int,
        operator: RtlBinaryOperator
    ): Option[RtlExpr] =
      capture(source, path + ".value").map { captured =>
        val width = math.max(1, BigInt(constant).bitLength)
        RtlExpr.Binary(operator, captured, RtlExpr.Literal(BigInt(constant), width))
      }

    value match {
      case literal: BoolLiteral if !literal.hasPoison() =>
        Some(RtlExpr.Literal(if (literal.value) BigInt(1) else BigInt(0), 1))
      case literal: BitVectorLiteral if !literal.hasPoison() && literal.getWidth > 0 =>
        Some(
          RtlExpr.Literal(
            literal.getValue(),
            literal.getWidth,
            literal.isInstanceOf[SIntLiteral]
          )
        )
      case source: BaseType
          if !source.isAnalog && !source.isInOut && supportedPackedBase(source) =>
        val idValue = sourceIds.get(source)
        val id =
          if (idValue != null) SymbolId.unsafe(idValue)
          else {
            val created = SymbolId.unsafe(
              s"symbol.$identifierPrefix.source.${sources.size}"
            )
            sourceIds.put(source, created.value)
            sources += source -> created
            created
          }
        val reference = ReferenceId.unsafe(
          s"reference.$identifierPrefix.${nextReference}.${sanitize(path)}"
        )
        nextReference += 1
        Some(RtlExpr.Ref(reference, id, scopeId))

      case node: Operator.Bool.And => binary(node, RtlBinaryOperator.LogicalAnd)
      case node: Operator.Bool.Or => binary(node, RtlBinaryOperator.LogicalOr)
      case node: Operator.Bool.Xor => binary(node, RtlBinaryOperator.BitwiseXor)
      case node: Operator.Bool.Equal => binary(node, RtlBinaryOperator.Equal)
      case node: Operator.Bool.NotEqual => binary(node, RtlBinaryOperator.NotEqual)
      case node: Operator.Bool.Not => unary(node, RtlUnaryOperator.LogicalNot)

      case node: Operator.Bits.Cat =>
        for {
          left <- capture(node.left, path + ".left")
          right <- capture(node.right, path + ".right")
        } yield RtlExpr.Concat(Vector(left, right))
      case node: Operator.Bits.Not => unary(node, RtlUnaryOperator.BitwiseNot)
      case node: Operator.UInt.Not => unary(node, RtlUnaryOperator.BitwiseNot)
      case node: Operator.SInt.Not => unary(node, RtlUnaryOperator.BitwiseNot)
      case node: Operator.SInt.Minus => unary(node, RtlUnaryOperator.Negate)

      case node: Operator.BitVector.And => binary(node, RtlBinaryOperator.BitwiseAnd)
      case node: Operator.BitVector.Or => binary(node, RtlBinaryOperator.BitwiseOr)
      case node: Operator.BitVector.Xor => binary(node, RtlBinaryOperator.BitwiseXor)
      case node: Operator.BitVector.Add => binary(node, RtlBinaryOperator.Add)
      case node: Operator.BitVector.Sub => binary(node, RtlBinaryOperator.Subtract)
      case node: Operator.BitVector.Mul => binary(node, RtlBinaryOperator.Multiply)
      case node: Operator.BitVector.Div => binary(node, RtlBinaryOperator.Divide)
      case node: Operator.BitVector.Mod => binary(node, RtlBinaryOperator.Modulo)
      case node: Operator.BitVector.Equal => binary(node, RtlBinaryOperator.Equal)
      case node: Operator.BitVector.NotEqual => binary(node, RtlBinaryOperator.NotEqual)
      case node: Operator.UInt.Smaller => binary(node, RtlBinaryOperator.LessThan)
      case node: Operator.UInt.SmallerOrEqual =>
        binary(node, RtlBinaryOperator.LessThanOrEqual)
      case node: Operator.SInt.Smaller => binary(node, RtlBinaryOperator.LessThan)
      case node: Operator.SInt.SmallerOrEqual =>
        binary(node, RtlBinaryOperator.LessThanOrEqual)

      case node: Operator.BitVector.ShiftLeftByInt =>
        constantBinary(node.source, node.shift, RtlBinaryOperator.ShiftLeft)
      case node: Operator.BitVector.ShiftRightByInt =>
        constantBinary(node.source, node.shift, RtlBinaryOperator.ShiftRight)
      case node: Operator.BitVector.ShiftLeftByUInt =>
        binary(node, RtlBinaryOperator.ShiftLeft)
      case node: Operator.BitVector.ShiftRightByUInt =>
        binary(node, RtlBinaryOperator.ShiftRight)

      case node: BinaryMultiplexer =>
        for {
          condition <- capture(node.cond, path + ".condition")
          whenTrue <- capture(node.whenTrue, path + ".true")
          whenFalse <- capture(node.whenFalse, path + ".false")
        } yield RtlExpr.Mux(condition, whenTrue, whenFalse)

      case node: BitVectorBitAccessFixed if node.bitId >= 0 =>
        capture(node.source, path + ".value").map { source =>
          val width = math.max(1, BigInt(node.bitId).bitLength)
          RtlExpr.BitSelect(source, RtlExpr.Literal(BigInt(node.bitId), width))
        }
      case node: BitVectorBitAccessFloating =>
        for {
          source <- capture(node.source, path + ".value")
          index <- capture(node.bitId, path + ".index")
        } yield RtlExpr.BitSelect(source, index)
      case node: BitVectorRangedAccessFixed
          if node.lo >= 0 && node.hi >= node.lo =>
        capture(node.source, path + ".value").map { source =>
          RtlExpr.PartSelect(
            source,
            morphhdl.ir.v1.IntExpr.Literal(BigInt(node.lo)),
            morphhdl.ir.v1.IntExpr.Literal(BigInt(node.hi - node.lo + 1))
          )
        }

      // Canonical Cast records the exact signed/unsigned interpretation. All
      // enum and other unrepresented casts remain untouched by returning None.
      case node: CastSIntToBits => cast(node, signed = false, path)
      case node: CastUIntToBits => cast(node, signed = false, path)
      case node: CastBitsToUInt => cast(node, signed = false, path)
      case node: CastSIntToUInt => cast(node, signed = false, path)
      case node: CastBitsToSInt => cast(node, signed = true, path)
      case node: CastUIntToSInt => cast(node, signed = true, path)
      case node: CastBoolToBits => cast(node, signed = false, path)

      // A native Resize stores only its elaborated integer width. A retained
      // symbolic resize cannot be reconstructed here without its typed width
      // identity, so every Resize is conservatively ineligible.
      case _: Resize => None
      case _         => None
    }
  }

  private def cast(node: Cast, signed: Boolean, path: String): Option[RtlExpr] =
    capture(node.input, path + ".value").map(
      RtlExpr.Cast(_, if (signed) morphhdl.ir.v1.Signedness.Signed
      else morphhdl.ir.v1.Signedness.Unsigned)
    )

  private def supportedPackedBase(value: BaseType): Boolean = value match {
    case _: Bool | _: Bits | _: UInt | _: SInt => true
    case _                                     => false
  }

  private def sanitize(value: String): String =
    value.map {
      case character if character.isLetterOrDigit => character
      case _                                      => '-'
    }
}

/** Exact pre-allocation native name provenance; spelling never classifies a name. */
private[examples] object NativeWireNameProvenance {
  def origin(value: BaseType): Option[NameOrigin] = {
    if (value == null) None
    else if (value.isUnnamed) Some(NameOrigin.Unnamed)
    else {
      val name = Option(value.getName("")).map(_.trim).filter(_.nonEmpty)
      readPrivateByte(value, "namePriority").flatMap {
        case Nameable.USER_SET | Nameable.USER_WEAK =>
          name.map(NameOrigin.Explicit)
        case Nameable.DATAMODEL_STRONG | Nameable.DATAMODEL_WEAK =>
          name.map(NameOrigin.Reflected)
        case Nameable.REMOVABLE => Some(NameOrigin.Generated)
        case _                  => None
      }
    }
  }

  def meaningful(value: BaseType): Option[NameOrigin] =
    origin(value).filter {
      case _: NameOrigin.Explicit  => true
      case _: NameOrigin.Reflected => true
      case _                       => false
    }

  /** Named successor candidates include compiler-removable generated names. */
  def successorExpressionOrigin(value: BaseType): Option[NameOrigin] =
    origin(value).filter {
      case NameOrigin.Unnamed | NameOrigin.Unknown => false
      case _                                       => true
    }

  private def readPrivateByte(value: AnyRef, name: String): Option[Byte] = {
    var current: Class[_] = value.getClass
    while (current != null) {
      try {
        val field = current.getDeclaredField(name)
        field.setAccessible(true)
        return Some(field.getByte(value))
      } catch {
        case _: NoSuchFieldException => current = current.getSuperclass
        case _: Throwable            => return None
      }
    }
    None
  }
}
