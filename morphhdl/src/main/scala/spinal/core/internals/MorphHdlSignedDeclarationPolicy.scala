package spinal.core.internals

import morphhdl.analysis.SignednessFacts._
import spinal.core._
import MorphHdlSignednessAnalysis._
import VerilogBase._

/** Declaration permission combines a native printer occurrence with a fresh
  * exact graph snapshot. ExpressionUse alone never authorizes a temporary.
  * Logical range publication remains with the existing typed width backend.
  * Declaration-only mode retains every cast; 60d adds a separate explicit opt-in.
  */
final class MorphHdlSignedDeclarationPolicy private[spinal] (
    emitter: VerilogBase,
    snapshot: Snapshot,
    eliminatePureCasts: Boolean = false
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
        // Declaration-only mode keeps the 60c guard. Boundary mode supplies
        // both exact function/result ranges and signed literal interpretation.
        if (!eliminatePureCasts && result.intent == SignedScalar && result.width != Fixed(result.nativeBits))
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
        val range = MorphHdlSignedWidth.resolve(snapshot, expression, evidence, ExpressionUse)
        if (range.symbolic) Some(s"[${range.text}-1:0]") else None
      case _ => None
    }
  }

  override def functionRange(occurrence: DeclarationOccurrence): Option[String] = {
    if (occurrence == null || (occurrence.emitter ne emitter) || occurrence.role != FunctionResultDeclaration)
      reject("function range needs its exact native declaration occurrence")
    occurrence.subject match {
      case value: SInt if eliminatePureCasts =>
        val range = MorphHdlSignedWidth.resolve(snapshot, value, snapshot.declaration(value), DeclarationUse)
        if (range.symbolic) Some(s"[${range.text}-1:0]") else None
      case _ => None
    }
  }

  private val castPolicy = if (eliminatePureCasts)
    Some(new MorphHdlPureSIntCastPolicy(emitter, snapshot)) else None

  override def elideSignedCast(occurrence: SignedCastOccurrence): Boolean =
    castPolicy.exists(_.elide(occurrence))

  override def signedLiteral(occurrence: SignedLiteralOccurrence): Boolean = {
    if (occurrence == null || (occurrence.emitter ne emitter))
      reject("literal occurrence belongs to another emitter")
    if (!eliminatePureCasts) return false
    val literal = occurrence.literal
    if (literal.getClass != classOf[SIntLiteral]) return false
    val fact = snapshot.validate(literal, snapshot.expression(literal), ExpressionUse)
    if (fact.intent != SignedScalar || fact.rule != morphhdl.analysis.SignednessFacts.Literal || fact.nativeBits <= 0)
      reject("signed literal requires an exact normalized scalar SInt carrier")
    !literal.hasPoison()
  }

  override def signedResize(occurrence: SignedResizeOccurrence): Option[String] = {
    if (occurrence == null || (occurrence.emitter ne emitter))
      reject("resize occurrence belongs to another emitter")
    if (!eliminatePureCasts) return None
    val resize = occurrence.resize
    if (resize.getClass != classOf[ResizeSInt])
      reject("signed resize needs an exact native SInt resize")
    snapshot.validateCastOperand(resize, 0, snapshot.castOperand(resize, 0))
    val target = MorphHdlSignedWidth.resolve(snapshot, resize, snapshot.expression(resize), ExpressionUse)
    val source = MorphHdlSignedWidth.resolve(snapshot, resize.input, snapshot.expression(resize.input), ExpressionUse)
    if (!target.symbolic && !source.symbolic) return None
    if (occurrence.inputReferenceRole.isEmpty)
      throw new MorphHdlSignednessException("MORPH-SIGNEDNESS-RESIZE-REFERENCE-UNSUPPORTED",
        "symbolic signed resize requires a native scalar reference or materialized expression wrapper")
    val input = occurrence.inputText
    val to = target.text
    val from = source.text
    // Both sides are sized explicitly. The concatenation is unsigned transport;
    // a scalar SInt destination/wrapper reconstructs its signed interpretation.
    // min() keeps the select in range; max() keeps replication non-negative,
    // even where independent parameter domains cross widening and narrowing.
    val selected = if (target.maximum <= source.minimum) to
      else if (target.minimum >= source.maximum) from else s"(($to < $from) ? $to : $from)"
    if (target.maximum <= source.minimum) Some(s"$input[$to-1:0]")
    else {
      val extra = s"(($to > $from) ? ($to - $from) : 0)"
      Some(s"{{$extra{$input[$from-1]}}, $input[$selected-1:0]}")
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
  def bind(emitter: PhaseVerilog, snapshot: Snapshot, eliminatePureCasts: Boolean = false): Unit =
    emitter.bindDeclarationPolicy(new MorphHdlSignedDeclarationPolicy(emitter, snapshot, eliminatePureCasts))
}
