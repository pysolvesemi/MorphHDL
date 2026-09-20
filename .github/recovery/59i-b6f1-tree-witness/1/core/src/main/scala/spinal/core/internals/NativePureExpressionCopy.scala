package spinal.core.internals

import scala.util.control.NonFatal
import spinal.core.{BaseType, ParameterizedWidth, SpinalTagReady}

/** Bounded copy of pure native expression nodes after width inference.
  *
  * Only explicitly represented native classes are copied. Signal references
  * retain identity; each call owns fresh operator/literal nodes. Source
  * locations and authoritative inferred widths survive, while per-traversal
  * scratch marks do not. Tagged expressions and retained symbolic resize
  * identities fail closed instead of losing metadata while copying.
  */
object NativePureExpressionCopy {
  def apply(value: Expression): Option[Expression] = {
    var remaining = 256
    val active = new java.util.IdentityHashMap[Expression, java.lang.Boolean]()
    def copyNode(source: Expression): Expression = {
      remaining -= 1
      require(remaining >= 0 && source != null, "native expression copy budget")
      source match {
        case reference: BaseType => return reference
        case tagged: SpinalTagReady =>
          require(tagged.isEmptyOfTag, "tagged native expression cannot be copied")
        case _ =>
      }
      require(active.put(source, java.lang.Boolean.TRUE) == null,
        "cyclic native expression cannot be copied")
      try {
        val result: Expression = source match {
          case literal: BoolLiteral => literal.clone()
          case literal: BitsLiteral => literal.clone()
          case literal: UIntLiteral => literal.clone()
          case literal: SIntLiteral => literal.clone()
        case _: Operator.Bool.And => new Operator.Bool.And
        case _: Operator.Bool.Or => new Operator.Bool.Or
        case _: Operator.Bool.Xor => new Operator.Bool.Xor
        case _: Operator.Bool.Equal => new Operator.Bool.Equal
        case _: Operator.Bool.NotEqual => new Operator.Bool.NotEqual
        case _: Operator.Bool.Not => new Operator.Bool.Not
        case _: Operator.Bits.Cat => new Operator.Bits.Cat
        case _: Operator.Bits.Not => new Operator.Bits.Not
        case _: Operator.Bits.And => new Operator.Bits.And
        case _: Operator.Bits.Or => new Operator.Bits.Or
        case _: Operator.Bits.Xor => new Operator.Bits.Xor
        case _: Operator.Bits.Equal => new Operator.Bits.Equal
        case _: Operator.Bits.NotEqual => new Operator.Bits.NotEqual
        case _: Operator.Bits.ShiftLeftByUInt => new Operator.Bits.ShiftLeftByUInt
        case _: Operator.Bits.ShiftRightByUInt => new Operator.Bits.ShiftRightByUInt
        case _: Operator.UInt.Not => new Operator.UInt.Not
        case _: Operator.UInt.And => new Operator.UInt.And
        case _: Operator.UInt.Or => new Operator.UInt.Or
        case _: Operator.UInt.Xor => new Operator.UInt.Xor
        case _: Operator.UInt.Add => new Operator.UInt.Add
        case _: Operator.UInt.Sub => new Operator.UInt.Sub
        case _: Operator.UInt.Mul => new Operator.UInt.Mul
        case _: Operator.UInt.Div => new Operator.UInt.Div
        case _: Operator.UInt.Mod => new Operator.UInt.Mod
        case _: Operator.UInt.Equal => new Operator.UInt.Equal
        case _: Operator.UInt.NotEqual => new Operator.UInt.NotEqual
        case _: Operator.UInt.Smaller => new Operator.UInt.Smaller
        case _: Operator.UInt.SmallerOrEqual => new Operator.UInt.SmallerOrEqual
        case _: Operator.UInt.ShiftLeftByUInt => new Operator.UInt.ShiftLeftByUInt
        case _: Operator.UInt.ShiftRightByUInt => new Operator.UInt.ShiftRightByUInt
        case _: Operator.SInt.Not => new Operator.SInt.Not
        case _: Operator.SInt.Minus => new Operator.SInt.Minus
        case _: Operator.SInt.And => new Operator.SInt.And
        case _: Operator.SInt.Or => new Operator.SInt.Or
        case _: Operator.SInt.Xor => new Operator.SInt.Xor
        case _: Operator.SInt.Add => new Operator.SInt.Add
        case _: Operator.SInt.Sub => new Operator.SInt.Sub
        case _: Operator.SInt.Mul => new Operator.SInt.Mul
        case _: Operator.SInt.Div => new Operator.SInt.Div
        case _: Operator.SInt.Mod => new Operator.SInt.Mod
        case _: Operator.SInt.Equal => new Operator.SInt.Equal
        case _: Operator.SInt.NotEqual => new Operator.SInt.NotEqual
        case _: Operator.SInt.Smaller => new Operator.SInt.Smaller
        case _: Operator.SInt.SmallerOrEqual => new Operator.SInt.SmallerOrEqual
        case _: Operator.SInt.ShiftLeftByUInt => new Operator.SInt.ShiftLeftByUInt
        case _: Operator.SInt.ShiftRightByUInt => new Operator.SInt.ShiftRightByUInt
        case node: Operator.Bits.ShiftLeftByInt => new Operator.Bits.ShiftLeftByInt(node.shift)
        case node: Operator.Bits.ShiftRightByInt => new Operator.Bits.ShiftRightByInt(node.shift)
        case node: Operator.UInt.ShiftLeftByInt => new Operator.UInt.ShiftLeftByInt(node.shift)
        case node: Operator.UInt.ShiftRightByInt => new Operator.UInt.ShiftRightByInt(node.shift)
        case node: Operator.SInt.ShiftLeftByInt => new Operator.SInt.ShiftLeftByInt(node.shift)
        case node: Operator.SInt.ShiftRightByInt => new Operator.SInt.ShiftRightByInt(node.shift)
        case _: ResizeBits => new ResizeBits
        case _: ResizeUInt => new ResizeUInt
        case _: ResizeSInt => new ResizeSInt
        case _: BinaryMultiplexerBool => new BinaryMultiplexerBool
        case _: BinaryMultiplexerBits => new BinaryMultiplexerBits
        case _: BinaryMultiplexerUInt => new BinaryMultiplexerUInt
        case _: BinaryMultiplexerSInt => new BinaryMultiplexerSInt
        case _: BitsBitAccessFixed => new BitsBitAccessFixed
        case _: UIntBitAccessFixed => new UIntBitAccessFixed
        case _: SIntBitAccessFixed => new SIntBitAccessFixed
        case _: BitsBitAccessFloating => new BitsBitAccessFloating
        case _: UIntBitAccessFloating => new UIntBitAccessFloating
        case _: SIntBitAccessFloating => new SIntBitAccessFloating
        case _: BitsRangedAccessFixed => new BitsRangedAccessFixed
        case _: UIntRangedAccessFixed => new UIntRangedAccessFixed
        case _: SIntRangedAccessFixed => new SIntRangedAccessFixed
        case _: CastSIntToBits => new CastSIntToBits
        case _: CastUIntToBits => new CastUIntToBits
        case _: CastBitsToUInt => new CastBitsToUInt
        case _: CastSIntToUInt => new CastSIntToUInt
        case _: CastBitsToSInt => new CastBitsToSInt
        case _: CastUIntToSInt => new CastUIntToSInt
        case _: CastBoolToBits => new CastBoolToBits
          case _ => throw new IllegalArgumentException("unsupported native expression copy")
        }
        require(result.getClass == source.getClass,
          "unrepresented native expression subclass")
        (source, result) match {
          case (from: BinaryOperator, to: BinaryOperator) =>
            to.left = copyNode(from.left).asInstanceOf[to.T]
            to.right = copyNode(from.right).asInstanceOf[to.T]
          case (from: UnaryOperator, to: UnaryOperator) =>
            to.source = copyNode(from.source).asInstanceOf[to.T]
          case (from: ConstantOperator, to: ConstantOperator) =>
            to.source = copyNode(from.source).asInstanceOf[to.T]
          case (from: Resize, to: Resize) =>
            require(ParameterizedWidth.resizeExpressionOf(from).isEmpty,
              "retained symbolic resize identity cannot be copied")
            to.size = from.size
            to.input = copyNode(from.input).asInstanceOf[Expression with WidthProvider]
          case (from: Cast, to: Cast) => to.input = copyNode(from.input).asInstanceOf[to.T]
          case (from: BinaryMultiplexer, to: BinaryMultiplexer) =>
            to.cond = copyNode(from.cond)
            to.whenTrue = copyNode(from.whenTrue).asInstanceOf[to.T]
            to.whenFalse = copyNode(from.whenFalse).asInstanceOf[to.T]
          case (from: BitVectorBitAccessFixed, to: BitVectorBitAccessFixed) =>
            to.source = copyNode(from.source).asInstanceOf[Expression with WidthProvider]
            to.bitId = from.bitId
          case (from: BitVectorBitAccessFloating, to: BitVectorBitAccessFloating) =>
            to.source = copyNode(from.source).asInstanceOf[Expression with WidthProvider]
            to.bitId = copyNode(from.bitId).asInstanceOf[Expression with WidthProvider]
          case (from: BitVectorRangedAccessFixed, to: BitVectorRangedAccessFixed) =>
            to.source = copyNode(from.source).asInstanceOf[Expression with WidthProvider]
            to.hi = from.hi
            to.lo = from.lo
          case (_: Literal, _: Literal) =>
          case _ => throw new IllegalArgumentException("unsupported native expression payload")
        }
        // Copy each child edge separately. Repeated references to one source
        // operator must become separate receiver occurrences so later native
        // identity substitution neither changes hidden shared users nor loses
        // canonical reference-count accounting.
        result.setScalaLocated(source)
        (source, result) match {
          case (from: Widthable, to: Widthable) =>
            to.inferredWidth = from.inferredWidth
            to.widthWhenNotInferred = from.widthWhenNotInferred
          case _ =>
        }
        result
      } finally active.remove(source)
    }
    try Some(copyNode(value))
    catch { case NonFatal(_) => None }
  }
}
