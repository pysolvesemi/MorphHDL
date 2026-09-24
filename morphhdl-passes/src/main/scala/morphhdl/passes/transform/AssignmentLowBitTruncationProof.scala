package morphhdl.passes.transform

import scala.collection.mutable
import scala.util.control.NoStackTrace

import morphhdl.ir.v1._

/** Value proof for a whole unsigned assignment absorbing its outer low slice.
  *
  * This is an assignment-boundary proof, not an expression rewrite rule. The
  * native adapter must separately authenticate widths, declarations, metadata,
  * scopes and sampling. It supplies the actual captured expressions and the
  * actual continuous definitions selected for substitution, never witnesses.
  *
  * Both sides are compared after the receiver's identical low-bit projection.
  * Expansion recreates every removed declaration's packed fence. Only adjacent
  * identical unsigned resize fences are normalized; arithmetic, intermediate
  * overflow, muxes, casts and selection boundaries are not reassociated. The
  * identity therefore also holds for four-state values, without using 2-state
  * arithmetic identities. Reference occurrence IDs/locations are immaterial to
  * value comparison, but symbol and owner identities are retained.
  */
object AssignmentLowBitTruncationProof {
  final case class Definition(
      symbol: SymbolId,
      width: Int,
      value: RtlExpr,
      origin: NameOrigin
  )

  final case class Certificate private[transform] (
      sourceWidth: Int,
      receiverMinimum: BigInt,
      receiverMaximum: BigInt,
      substitutedDefinitions: Int
  )

  private sealed trait Key
  private final case class Read(symbol: SymbolId, owner: ScopeId) extends Key
  private final case class Constant(value: BigInt, width: Int) extends Key
  private final case class Unary(operator: RtlUnaryOperator, value: Key) extends Key
  private final case class Binary(operator: RtlBinaryOperator, left: Key, right: Key) extends Key
  private final case class Mux(condition: Key, yes: Key, no: Key) extends Key
  private final case class Concat(values: Vector[Key]) extends Key
  private final case class BitSelect(value: Key, index: Key) extends Key
  private final case class PartSelect(value: Key, offset: IntExpr, width: IntExpr) extends Key
  private final case class Resize(value: Key, width: IntExpr) extends Key
  private final case class Cast(value: Key) extends Key
  private case object Unproved extends RuntimeException with NoStackTrace

  def prove(
      before: RtlExpr,
      after: RtlExpr,
      receiverWidth: IntExpr,
      sourceWidth: Int,
      receiverMinimum: BigInt,
      receiverMaximum: BigInt,
      definitions: Vector[Definition] = Vector.empty
  ): Option[Certificate] = {
    if (before == null || after == null || receiverWidth == null ||
        sourceWidth < 1 || receiverMinimum < 1 ||
        receiverMaximum < receiverMinimum || receiverMaximum > sourceWidth ||
        definitions == null || definitions.size > 256 ||
        definitions.exists(value => value == null || value.symbol == null ||
          value.value == null || value.width < 1 ||
          (value.origin != NameOrigin.Unnamed && value.origin != NameOrigin.Generated)) ||
        definitions.map(_.symbol).distinct.size != definitions.size) return None

    receiverWidth match {
      case IntExpr.Literal(value) if value == receiverMinimum && value == receiverMaximum =>
      // The native adapter enrolls the exact effective receiver-width family;
      // the proof is universal over its positive, non-widening interval.
      case _: IntExpr.ParameterRef =>
      case _ => return None
    }
    val originalInput = before match {
      case RtlExpr.Resize(value, width, Signedness.Unsigned) if width == receiverWidth => value
      case RtlExpr.PartSelect(value, IntExpr.Literal(offset), width)
          if offset == 0 && width == receiverWidth => value
      case _ => return None
    }
    val bySymbol = definitions.map(value => value.symbol -> value).toMap

    def projected(expression: RtlExpr): Key = {
      var remaining = 1024 // Canonical capture adds a fence per native operator.
      val active = mutable.HashSet.empty[SymbolId]
      def resize(value: Key, width: IntExpr): Key = value match {
        case nested: Resize if nested.width == width => nested
        case _ => Resize(value, width)
      }
      def key(value: RtlExpr, depth: Int): Key = {
        remaining -= 1
        if (value == null || remaining < 0 || depth > 128) throw Unproved
        value match {
          case RtlExpr.Ref(_, symbol, owner, _) => bySymbol.get(symbol) match {
            case Some(definition) =>
              if (!active.add(symbol)) throw Unproved
              val result = resize(key(definition.value, depth + 1),
                IntExpr.Literal(BigInt(definition.width)))
              active -= symbol
              result
            case None => Read(symbol, owner)
          }
          case RtlExpr.Literal(number, width, false) if width > 0 => Constant(number, width)
          case RtlExpr.Unary(operator, child) => Unary(operator, key(child, depth + 1))
          case RtlExpr.Binary(operator, left, right) =>
            Binary(operator, key(left, depth + 1), key(right, depth + 1))
          case RtlExpr.Mux(condition, yes, no) =>
            Mux(key(condition, depth + 1), key(yes, depth + 1), key(no, depth + 1))
          case RtlExpr.Concat(values) if values.nonEmpty =>
            Concat(values.map(key(_, depth + 1)))
          case RtlExpr.BitSelect(child, index) => BitSelect(key(child, depth + 1), key(index, depth + 1))
          case RtlExpr.PartSelect(child, offset, width) =>
            // Nested selections must match exactly; they never inherit the
            // assignment-root permission, including high and dynamic slices.
            PartSelect(key(child, depth + 1), offset, width)
          case RtlExpr.Resize(child, width, Signedness.Unsigned) =>
            resize(key(child, depth + 1), width)
          case RtlExpr.Cast(child, Signedness.Unsigned) => Cast(key(child, depth + 1))
          case _ => throw Unproved
        }
      }
      resize(key(expression, 0), receiverWidth)
    }

    try {
      if (projected(originalInput) == projected(after))
        Some(Certificate(sourceWidth, receiverMinimum, receiverMaximum, definitions.size))
      else None
    } catch { case Unproved => None }
  }
}
