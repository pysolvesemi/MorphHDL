package spinal.core.internals

import morphhdl.analysis.SignednessFacts._
import MorphHdlSignednessAnalysis._

/** Logical geometry belongs to the exact graph object, not its current RHS
  * interpretation. A signed temporary may reconstruct an unsigned slice or
  * concatenation; its width must still be positive over the complete domain.
  */
private[spinal] object MorphHdlSignedWidth {
  final case class Range(text: String, minimum: BigInt, maximum: BigInt,
      default: BigInt, symbolic: Boolean)

  def resolve(snapshot: Snapshot, expression: Expression, evidence: Evidence, use: Use): Range = {
    val fact = snapshot.validate(expression, evidence, use)
    def combine(parts: Vector[Width])(f: (Range, Range) => Range): Option[Range] = {
      val values = parts.map(render)
      if (values.isEmpty || values.exists(_.isEmpty)) None else Some(values.map(_.get).reduce(f))
    }
    def render(width: Width): Option[Range] = width match {
      case Fixed(bits) => Some(Range(bits.toString, bits, bits, bits, false))
      case Retained(key) =>
        val value = snapshot.widthSource(expression, evidence, use, key)
        Some(Range(value.verilog, value.minimum, value.maximum, value.default, value.parameterRoots.nonEmpty))
      case Sum(parts) => combine(parts)((a, b) => Range(s"(${a.text} + ${b.text})",
        a.minimum + b.minimum, a.maximum + b.maximum, a.default + b.default, a.symbolic || b.symbolic))
      case Product(parts) => combine(parts)((a, b) => {
        val bounds = Vector(a.minimum*b.minimum, a.minimum*b.maximum, a.maximum*b.minimum, a.maximum*b.maximum)
        Range(s"(${a.text} * ${b.text})", bounds.min, bounds.max, a.default*b.default, a.symbolic || b.symbolic)
      })
      case Maximum(parts) => combine(parts)((a, b) => if (a == b) a else
        Range(s"((${a.text} > ${b.text}) ? ${a.text} : ${b.text})",
          a.minimum.max(b.minimum), a.maximum.max(b.maximum), a.default.max(b.default), a.symbolic || b.symbolic))
      case Minimum(parts) => combine(parts)((a, b) => if (a == b) a else
        Range(s"((${a.text} < ${b.text}) ? ${a.text} : ${b.text})",
          a.minimum.min(b.minimum), a.maximum.min(b.maximum), a.default.min(b.default), a.symbolic || b.symbolic))
      case Difference(left, right) => for (a <- render(left); b <- render(right)) yield
        Range(s"(${a.text} - ${b.text})", a.minimum-b.maximum, a.maximum-b.minimum,
          a.default-b.default, a.symbolic || b.symbolic)
      case _ => None
    }
    render(fact.width) match {
      case Some(range) if fact.rule != Unsupported && range.minimum > 0 && range.default == fact.nativeBits => range
      case _ => throw new MorphHdlSignednessException("MORPH-SIGNEDNESS-WIDTH-UNSUPPORTED",
        "boundary requires exact positive logical geometry over its complete parameter domain")
    }
  }
}
