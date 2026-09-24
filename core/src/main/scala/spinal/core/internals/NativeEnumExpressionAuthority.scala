package spinal.core.internals

import scala.util.control.NonFatal

import spinal.core.{EnumLiteral, SpinalEnum, SpinalEnumElement, SpinalEnumEncoding}

/** Exact native authority used by late, optional enum-expression rewrites.
  *
  * This deliberately records object identities as well as the resolved packed
  * representation.  Two enum definitions (or two encoding authorities) with
  * identical numeric codes are not interchangeable.  Numeric projection is
  * permitted only after every operand has been proved to carry this same
  * definition and encoding.
  */
object NativeEnumExpressionAuthority {
  final case class Element(
      native: SpinalEnumElement[_ <: SpinalEnum],
      encodedValue: BigInt
  )

  final case class Resolved(
      definition: SpinalEnum,
      encoding: SpinalEnumEncoding,
      width: Int,
      elements: Vector[Element]
  ) {
    def exactlyMatches(that: Resolved): Boolean =
      that != null &&
        (definition eq that.definition) &&
        (encoding eq that.encoding) &&
        width == that.width &&
        elements.size == that.elements.size &&
        elements.zip(that.elements).forall { case (left, right) =>
          (left.native eq right.native) && left.encodedValue == right.encodedValue
        }

    def encodedValueOf(literal: EnumLiteral[_]): Option[BigInt] =
      elements.collectFirst {
        case element if element.native eq literal.senum => element.encodedValue
      }

    def elementIndexOf(literal: EnumLiteral[_]): Option[Int] = {
      val index = elements.indexWhere(element => element.native eq literal.senum)
      if (index < 0) None else Some(index)
    }
  }

  final case class Comparison(
      authority: Resolved,
      left: Expression with EnumEncoded,
      right: Expression with EnumEncoded
  )

  /** Resolve width and every element code from the native encoding authority.
    * No name, declaration order by itself, or current emitted spelling is used.
    */
  def resolve(value: EnumEncoded): Option[Resolved] = {
    if (value == null) return None
    try {
      value match {
        case inferredValue: InferableEnumEncodingImpl =>
          inferredValue.encodingChoice match {
            case InferableEnumEncodingImplChoiceFixed |
                InferableEnumEncodingImplChoiceInferred =>
            case _ => return None
          }
        case _ => return None
      }
      val definition = value.getDefinition
      val encoding = value.getEncoding
      if (definition == null || encoding == null || definition.elements.isEmpty) return None
      val width = encoding.getWidth(definition)
      if (width < 1) return None
      val limit = BigInt(1) << width
      val elements = definition.elements.toVector.map { element =>
        val encoded = encoding.getValue(element)
        if (encoded < 0 || encoded >= limit)
          return None
        Element(element, encoded)
      }
      Some(Resolved(definition, encoding, width, elements))
    } catch {
      case NonFatal(_) => None
    }
  }

  /** Prove an enum comparison has one exact definition and resolved encoding
    * across the operator and both operands.  This is stronger than proving a
    * one-bit Boolean result: operand encoding controls native one-hot semantics.
    */
  def comparison(value: BinaryOperator with EnumEncoded): Option[Comparison] = {
    if (value == null || value.left == null || value.right == null) return None
    for {
      operator <- resolve(value)
      leftValue <- value.left match {
        case encoded: EnumEncoded =>
          Some(encoded.asInstanceOf[Expression with EnumEncoded])
        case _ => None
      }
      rightValue <- value.right match {
        case encoded: EnumEncoded =>
          Some(encoded.asInstanceOf[Expression with EnumEncoded])
        case _ => None
      }
      left <- resolve(leftValue) if operator.exactlyMatches(left)
      right <- resolve(rightValue) if operator.exactlyMatches(right)
      if literalBelongsTo(leftValue, operator)
      if literalBelongsTo(rightValue, operator)
    } yield Comparison(operator, leftValue, rightValue)
  }

  private def literalBelongsTo(
      value: Expression with EnumEncoded,
      authority: Resolved
  ): Boolean = value match {
    case literal: EnumLiteral[_] =>
      (literal.senum.spinalEnum eq authority.definition) &&
        authority.encodedValueOf(literal).nonEmpty
    case _ => true
  }
}
