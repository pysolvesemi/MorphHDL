package spinal.core.internals

import morphhdl.analysis.SignednessFacts._
import spinal.core._
import MorphHdlSignednessAnalysis._
import VerilogBase._

/** Declaration permission combines a native printer occurrence with a fresh
  * exact graph snapshot. ExpressionUse alone never authorizes a temporary.
  * Logical range publication remains with the existing typed width backend.
  * This policy neither changes an expression printer nor removes a cast.
  */
private[spinal] final class MorphHdlSignedDeclarationPolicy(
    emitter: VerilogBase,
    snapshot: Snapshot
) extends DeclarationPolicy {
  require(emitter != null && snapshot != null, "signed declarations require an emitter and snapshot")

  private def reject(detail: String): Nothing =
    throw new MorphHdlSignednessException("MORPH-SIGNEDNESS-DECLARATION-USE", detail)

  override def signed(occurrence: DeclarationOccurrence): Boolean = {
    if (occurrence == null || (occurrence.emitter ne emitter))
      reject("declaration occurrence belongs to another emitter")
    val fact = (occurrence.role, occurrence.subject) match {
      case (FunctionResultDeclaration, value: BaseType) =>
        val result = snapshot.validate(value, snapshot.declaration(value), DeclarationUse)
        // Native constant-process functions currently retain concrete literal
        // sizing and result-wrapper widths. Signed declarations alone cannot
        // repair parameter-dependent return sizing. Require exact fixed width
        // until the typed literal/resize boundary is qualified; never freeze a
        // default witness or change implicit extension at publication.
        if (result.intent == SignedScalar && result.width != Fixed(result.nativeBits))
          throw new MorphHdlSignednessException("MORPH-SIGNEDNESS-FUNCTION-WIDTH-UNSUPPORTED",
            "native constant-process SInt functions require an exact fixed result width")
        result
      case (ScalarDeclaration, value: BaseType) =>
        snapshot.validate(value, snapshot.declaration(value), DeclarationUse)
      case (ExpressionWrapper, value: BaseType) =>
        // A local wrapper of a real child port uses that exact declaration.
        snapshot.validate(value, snapshot.declaration(value), DeclarationUse)
      case (ExpressionWrapper, value: Expression) =>
        snapshot.validate(value, snapshot.expression(value), ExpressionUse)
      case (MemoryElementDeclaration, value: Mem[_]) =>
        snapshot.validate(value, snapshot.memoryElement(value), MemoryElementUse)
      case _ => reject("unsupported subject or native declaration occurrence role")
    }
    val nativeSigned = occurrence.subject match {
      case expression: Expression => expression.getTypeObject == TypeSInt
      case _: Mem[_] => fact.intent == SignedScalar
      case _ => false
    }
    if (nativeSigned && (fact.intent != SignedScalar || fact.nativeBits <= 0 || fact.rule == Unsupported))
      reject("a signed declaration needs exact scalar intent and a positive native carrier")
    // A declaration owns interpretation even when its assigned expression is a
    // slice, mux, resize or memory transport. Value facts are not cast proofs.
    nativeSigned && fact.intent == SignedScalar
  }

  /** Native expression temporaries have no BaseType declaration for the
    * external width pass to rewrite. Render only the structured 60b width fact,
    * resolving every retained token through this exact expression use. No
    * witness, printed name or source position is used to reconstruct a width.
    */
  override def wrapperRange(occurrence: DeclarationOccurrence): Option[String] = {
    if (occurrence == null || (occurrence.emitter ne emitter) || occurrence.role != ExpressionWrapper)
      reject("wrapper width needs its exact native occurrence")
    occurrence.subject match {
      case _: BaseType => None // Existing declaration/hierarchy width authority.
      case expression: Expression if expression.getTypeObject == TypeSInt ||
          expression.getTypeObject == TypeUInt || expression.getTypeObject == TypeBits =>
        val evidence = snapshot.expression(expression)
        val fact = snapshot.validate(expression, evidence, ExpressionUse)
        final case class Range(text: String, minimum: BigInt, maximum: BigInt,
            default: BigInt, symbolic: Boolean)
        def combine(parts: Vector[Width])(f: (Range, Range) => Range): Option[Range] = {
          val values = parts.map(render)
          if (values.isEmpty || values.exists(_.isEmpty)) None else Some(values.map(_.get).reduce(f))
        }
        def render(width: Width): Option[Range] = width match {
          case Fixed(bits) => Some(Range(bits.toString, bits, bits, bits, false))
          case Retained(key) =>
            val value = snapshot.widthSource(expression, evidence, ExpressionUse, key)
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
          case Some(range) if range.minimum > 0 && range.default == fact.nativeBits =>
            if (range.symbolic) Some(s"[${range.text}-1:0]") else None
          case _ => reject("expression wrapper has no exact positive logical width over its parameter domain")
        }
      case _ => None
    }
  }

  override def unsignedTransport(expression: Expression): Boolean = expression match {
    case _: CastSIntToBits | _: CastSIntToUInt =>
      val fact = snapshot.validate(expression, snapshot.expression(expression), ExpressionUse)
      if (fact.intent != UnsignedScalar || fact.nativeBits <= 0)
        reject("an unsigned conversion carrier needs exact graph authority")
      true
    case _ => false
  }
}

/** Narrow binding bridge; the native backend never depends on MorphHDL. */
object MorphHdlSignedDeclarationPolicy {
  def bind(emitter: PhaseVerilog, snapshot: Snapshot): Unit =
    emitter.bindDeclarationPolicy(new MorphHdlSignedDeclarationPolicy(emitter, snapshot))
}
