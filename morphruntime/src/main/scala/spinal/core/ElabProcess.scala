package spinal.core

import scala.collection.mutable.ArrayBuffer
import spinal.core.internals._

/** Bounded combinational operations whose index never escapes its owning
  * process. MorphVerilog retains ordered blocking assignments and native `if`
  * semantics (an X/Z predicate does not select a write). Ordinary SpinalVerilog
  * retains a concrete, unrolled implementation.
  */
object ElabProcess {
  private object Key
  private[core] final class Operation(
      val input: Bits, val take: Option[UInt], val result: BitVector,
      val count: ElabInt, val elementWidth: ElabInt, val resultWidth: ElabInt,
      val tree: WhenStatement, val condition: Bool, val conditionDriver: Expression,
      val default: DataAssignmentStatement, val assignment: DataAssignmentStatement,
      val source: Expression) {
    var emitting: Option[(ComponentEmitterVerilog, String)] = None
    var emitted = false
  }
  private[core] def operations(component: Component): Vector[Operation] =
    component.userCache.get(Key).toVector.flatMap(_.asInstanceOf[ArrayBuffer[Operation]])

  private def fail(message: String): Nothing = ParameterizedVerilogException.fail(
    "SPINAL-ELAB-PROCESS-OPERATION-UNSUPPORTED", message, None)

  private def bounded(value: ElabInt, role: String): Unit = {
    if (value == null) fail(s"$role must be an authenticated positive integer")
    ElaborationWidthAuthority.requireAuthoritative(value.expression, role, "SPINAL-ELAB-PROCESS-DOMAIN-MISSING")
    if (value.minimum < 1 || value.maximum >= Int.MaxValue)
      fail(s"$role must be positive and smaller than the signed procedural-index limit")
  }

  private def checkSource(source: Bits, width: ElabInt): Unit = {
    if (source == null || Component.current == null ||
        (source.component ne Component.current) || DslScopeStack.get != Component.current.dslBody)
      fail("bounded operations require an owner-local source in component scope")
    val actual = NativeWidthProvenance.widthOf(source).getOrElse(
      fail("source needs authenticated packed-width provenance"))
    if (!ElabFiniteRange.equivalentLogicalCount(ElabInt.projectExpression(actual, "process source"),
        ElabInt.projectExpression(width.expression, "process source geometry")))
      fail("source width must equal the operation's geometry over the complete parameter domain")
  }

  /** Highest selected bit position plus one; zero for an empty mask.
    * This is an ordered priority reduction, not a population count.
    */
  def highestSetBitPlusOne(source: Bits, count: ElabInt, resultWidth: ElabInt): UInt = {
    bounded(count, "priority count")
    bounded(resultWidth, "priority result width")
    if (resultWidth.maximum > 32 || count.maximum >= (BigInt(1) << resultWidth.minimum.toInt))
      fail("priority result width must represent every position plus one and be at most 32 bits")
    checkSource(source, count)
    val result = ParameterizedExpressionCarrier.retain(UInt(resultWidth bits))
    result.setWeakName("priority_count")
    result := 0
    if (ParameterizedStructure.captureEnabled)
      retain(source, None, result, count, ElabInt.literal(1), resultWidth)
    else {
      // Ordinary generation must never publish the retained operation's witness.
      for (index <- 0 until count.witness)
        when(source(index)) { result := U(index + 1, resultWidth.witness bits) }
    }
    result
  }

  /** Copy elements with unsigned index < take, leaving all other elements zero.
    * Element zero occupies the least-significant packed slice.
    */
  def prefixCopy(source: Bits, take: UInt, count: ElabInt, elementWidth: ElabInt): Bits = {
    bounded(count, "prefix count")
    bounded(elementWidth, "prefix element width")
    val width = count * elementWidth
    bounded(width, "prefix packed width")
    checkSource(source, width)
    if (take == null || (take.component ne Component.current) || take.getWidth < 1 || take.getWidth > 32 ||
        ParameterizedWidth.expressionOf(take).exists(e => e.minimum != e.maximum))
      fail("prefix take needs an owner-local unsigned width from 1 through 32 bits")
    val result = ParameterizedExpressionCarrier.retain(Bits(width bits))
    result.setWeakName("prefix_word")
    result := 0
    if (ParameterizedStructure.captureEnabled)
      retain(source, Some(take), result, count, elementWidth, width)
    else {
      for (index <- 0 until count.witness) {
        // An index outside take's complete representable range can never win.
        if (BigInt(index) < (BigInt(1) << take.getWidth))
          when(U(index, take.getWidth bits) < take) {
            result(index * elementWidth.witness, elementWidth.witness bits) :=
              source(index * elementWidth.witness, elementWidth.witness bits)
          }
      }
    }
    result
  }

  private def retain(source: Bits, take: Option[UInt], result: BitVector,
      count: ElabInt, elementWidth: ElabInt, width: ElabInt): Unit = {
    val component = Component.current
    val default = result.head.asInstanceOf[DataAssignmentStatement]
    // These exact native edges keep input dependencies, default coverage and
    // ownership visible to the normal compiler phases. Only the registered
    // operation may publish their bounded index-dependent meaning.
    val input = ParameterizedExpressionCarrier.retain(ParameterizedWidth.cloneOf(source))
    input.setWeakName("process_input")
    input := source
    val condition = ParameterizedExpressionCarrier.retain(take.map(_ =/= 0).getOrElse(input.orR))
    var tree: WhenStatement = null
    var assignment: DataAssignmentStatement = null
    when(condition) {
      tree = DslScopeStack.get.parentStatement.asInstanceOf[WhenStatement]
      result match {
        case value: UInt => value := U(1)
        case value: Bits => value := input
      }
      assignment = tree.whenTrue.last.asInstanceOf[DataAssignmentStatement]
    }
    if (take.isEmpty) {
      // Authenticate the exact native literal, not its disposable UInt wrapper.
      assignment.source = assignment.source.asInstanceOf[UInt].head
        .asInstanceOf[DataAssignmentStatement].source
    }
    val record = new Operation(input, take, result, count, elementWidth, width, tree, condition,
      condition.head.asInstanceOf[DataAssignmentStatement].source, default, assignment, assignment.source)
    component.userCache.getOrElseUpdate(Key, ArrayBuffer.empty[Operation])
      .asInstanceOf[ArrayBuffer[Operation]] += record
  }
}
