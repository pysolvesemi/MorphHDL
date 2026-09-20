package spinal.core.internals

import spinal.core._

/** Exact closure environment, not values reconstructed from elaboration witnesses.
  * Entries are in the JVM lambda's capture order; duplicate identities retain
  * their separate binding slots. Hardware entries remain runtime operands.
  */
private[spinal] final class TypedBalancedReductionCaptureSchema private[internals] (
    val callback: AnyRef,
    val owner: Component,
    private val entries: Vector[TypedBalancedReductionCaptureSchema.Entry],
    private val readCaptures: () => Vector[AnyRef]
) {
  import TypedBalancedReductionCaptureSchema._

  val hardwareInputs: Vector[BaseType] = entries.collect { case entry: Hardware => entry.value }
  val configurations: Vector[ElabInt] = entries.collect { case entry: Configuration => entry.value }

  /** Called before capture, before every stage replay and at native handoff. */
  def validateBindings(): Unit = {
    val now = readCaptures()
    if (now.size != entries.size || now.zip(entries).exists { case (value, entry) => value ne entry.value })
      fail("the exact serialized capture slots changed")
    if (entries.nonEmpty && owner == null)
      fail("captured configuration/input lifetime escaped its exact native component")
    val declarations = scala.collection.mutable.ArrayBuffer.empty[BaseType]
    if (owner != null) owner.dslBody.walkStatements {
      case value: BaseType => declarations += value
      case _ =>
    }
    entries.foreach {
      case entry: Configuration =>
        if (entry.value.expression ne entry.expression)
          fail("captured typed configuration changed its expression identity")
        ElabInt.requireAuthoritativeIntegerDomain(entry.expression, "balanced callback capture",
          "MORPH-REDUCE-BALANCED-CAPTURE-SCHEMA", requireExactExtrema = false)
      case entry: Hardware =>
        val fixedWidth = entry.value match {
          case value: BitVector => Some(value.fixedWidth)
          case _ => None
        }
        if (fixedWidth != entry.fixedWidth)
          fail("captured hardware changed its exact native fixed-width declaration")
        val width = ParameterizedWidth.expressionOf(entry.value)
        if ((entry.value.component ne owner) || !declarations.exists(_ eq entry.value) ||
            (entry.value.getTypeObject.asInstanceOf[AnyRef] ne entry.kind) ||
            entry.value.getBitsWidth != entry.nativeWidth ||
            entry.value.getDirection != entry.direction ||
            width.size != entry.width.size ||
            width.zip(entry.width).exists { case (a, b) => a ne b })
          fail("captured hardware changed owner, lifetime, type, direction or exact width authority")
    }
  }
}

private[spinal] object TypedBalancedReductionCaptureSchema {
  private[internals] sealed trait Entry { def value: AnyRef }
  private[internals] final case class Configuration(value: ElabInt,
      expression: ElaborationIntegerExpression) extends Entry
  private[internals] final case class Hardware(value: BaseType, kind: AnyRef,
      nativeWidth: Int, fixedWidth: Option[Int], width: Option[ElaborationIntegerExpression], direction: IODirection) extends Entry

  private def fail(detail: String): Nothing = throw new IllegalArgumentException(
    "MORPH-REDUCE-BALANCED-CAPTURE-SCHEMA: " + detail)

  private[internals] def apply(callback: AnyRef, captures: Vector[AnyRef],
      readCaptures: () => Vector[AnyRef]): TypedBalancedReductionCaptureSchema = {
    val owner = Component.current
    if (captures.nonEmpty && owner == null)
      fail("captured configuration/input admission requires its exact native component scope")
    val entries = captures.map {
      case value: ElabInt => Configuration(value, value.expression)
      case value: BaseType if Set[Class[_]](classOf[Bool], classOf[Bits], classOf[UInt], classOf[SInt])(value.getClass) =>
        Hardware(value, value.getTypeObject.asInstanceOf[AnyRef], value.getBitsWidth,
          value match { case bits: BitVector => Some(bits.fixedWidth); case _ => None },
          ParameterizedWidth.expressionOf(value), value.getDirection)
      case _ => fail("only exact immutable ElabInt and native scalar hardware capture identities are admitted")
    }
    val schema = new TypedBalancedReductionCaptureSchema(callback, owner, entries, readCaptures)
    schema.validateBindings()
    schema
  }
}
