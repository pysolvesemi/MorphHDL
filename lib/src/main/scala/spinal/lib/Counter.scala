package spinal.lib

import spinal.core._

/** Direction in which a counter is allowed to advance. */
sealed trait CounterDirection
object CounterDirection {

  /** Increment only; calling `decrement()` is rejected at elaboration. */
  case object Up extends CounterDirection

  /** Decrement only; calling `increment()` is rejected at elaboration. */
  case object Down extends CounterDirection

  /** Both `increment()` and `decrement()` are allowed. */
  case object Both extends CounterDirection
}

/** Behavior when a counter would step past its upper or lower boundary. */
sealed trait BoundaryPolicy
object BoundaryPolicy {

  /** Modular wrap-around: at the upper boundary jump to the lower bound, and vice versa. */
  case object Wrap extends BoundaryPolicy

  /** Pin at the boundary; further steps in that direction are absorbed. */
  case object Saturate extends BoundaryPolicy

  /** Latch at the boundary on the cycle it is reached; only `clear()` or `load()` releases the latch. */
  case object Freeze extends BoundaryPolicy
}

/** Common interface implemented by every counter primitive in this file.
  *
  * Concrete counters expose the registered [[value]] and a combinational [[valueNext]] driven each
  * cycle, plus four "what is happening this cycle" status signals: [[willClear]], [[willLoad]],
  * [[willAdvance]] and [[willComplete]].
  *
  * @tparam T The bit-vector type carrying counter state (`UInt` for binary/Gray, `Bits` for one-hot/Johnson).
  */
trait CounterLike[T <: BitVector] extends ImplicitArea[T] {

  /** Current registered counter value. */
  def value: T

  /** Combinational next value driven into the register this cycle. */
  def valueNext: T

  /** True on the cycle the counter is being reset to its initial value. */
  val willClear: Bool

  /** True on the cycle the counter is being loaded from an external value. */
  val willLoad: Bool

  /** True on the cycle the counter is moving (incrementing or decrementing). */
  val willAdvance: Bool

  /** True on the cycle the counter is completing a wrap (overflow or underflow). */
  val willComplete: Bool

  /** Total number of legal states the counter can occupy. */
  def stateCount: BigInt

  /** Schedule a reset to the initial value on this cycle. */
  def clear(): Unit = willClear := True

  /** Schedule loading `raw` into the counter on this cycle. */
  def load(raw: T): Unit = {
    valueNext := raw
    willLoad := True
  }

  /** True when the counter is currently latched at a [[BoundaryPolicy.Freeze]] boundary. */
  def frozen: Bool = False

  /** Stream the counter as a [[Flow]] whose payload carries [[value]] and whose `valid` follows [[willAdvance]]. */
  def toFlow(): Flow[T] = {
    val flow = Flow(cloneOf(value))
    flow.payload := value
    flow.valid := willAdvance
    flow
  }

  override def implicitValue: T = value
}

/** Counters that support loading by ordinal: "go to the Nth state" without the caller having to
  * know the underlying encoding (binary, one-hot, Gray, ...).
  */
trait CounterAddressable[T <: BitVector] extends CounterLike[T] {

  /** Load the counter into its `index`-th ordinal state. `0` is the start, `stateCount - 1` is the end. */
  def loadOrdinal(index: UInt): Unit

  /** @see [[loadOrdinal(UInt)]] */
  def loadOrdinal(index: Int): Unit = loadOrdinal(U(index, log2Up(stateCount) bits))

  /** @see [[loadOrdinal(UInt)]] */
  def loadOrdinal(index: BigInt): Unit = loadOrdinal(U(index, log2Up(stateCount) bits))
}

/** Abstract base for finite-range counters with explicit upper/lower boundary policies.
  *
  * Subclasses provide storage and the per-step arithmetic (via [[willOverflowIfInc]] /
  * [[willUnderflowIfDec]] and writes to [[valueNext]]); this base wires up the shared control
  * surface: the four `willXxx` pulses, derived [[willOverflow]] / [[willUnderflow]] /
  * [[willAdvance]] / [[willComplete]], the [[BoundaryPolicy.Freeze]] latch, and the
  * direction-policed [[increment]] / [[decrement]] / [[freeRun]] / [[freeRunDown]] entry points.
  *
  * @param direction Which directions the counter accepts (mismatched calls fail at elaboration).
  * @param upper Policy applied when an increment would exceed the upper boundary.
  * @param lower Policy applied when a decrement would fall below the lower boundary.
  */
abstract class BoundedCounter[T <: BitVector](
    val direction: CounterDirection,
    val upper: BoundaryPolicy,
    val lower: BoundaryPolicy
) extends ImplicitArea[T]
    with CounterLike[T]
    with CounterAddressable[T] {

  protected val hasUp: Boolean = direction != CounterDirection.Down
  protected val hasDown: Boolean = direction != CounterDirection.Up

  /** True on the cycle an increment is requested. */
  val willIncrement = False.allowOverride

  /** True on the cycle a decrement is requested. */
  val willDecrement = False.allowOverride
  val willClear = False.allowOverride
  val willLoad = False.allowOverride

  /** True when the registered value sits at the upper boundary (i.e. an increment would overflow). */
  def willOverflowIfInc: Bool

  /** True when the registered value sits at the lower boundary (i.e. a decrement would underflow). */
  def willUnderflowIfDec: Bool

  /** [[willOverflowIfInc]] qualified with `willIncrement` (and, in `Both` mode, gated by `!willDecrement`). */
  lazy val willOverflow = Counter.guardedComplete(direction)(willOverflowIfInc, willIncrement, willDecrement)

  /** [[willUnderflowIfDec]] qualified with `willDecrement` (and, in `Both` mode, gated by `!willIncrement`). */
  lazy val willUnderflow = Counter.guardedComplete(direction)(willUnderflowIfDec, willDecrement, willIncrement)
  lazy val willAdvance = Counter.byDirection(direction)(willIncrement, willDecrement)
  lazy val willComplete = Counter.byDirection(direction)(willOverflow, willUnderflow)

  private lazy val freezeReg: Bool = Counter.freezeLatch(
    upperFreeze = hasUp && upper == BoundaryPolicy.Freeze,
    lowerFreeze = hasDown && lower == BoundaryPolicy.Freeze,
    willOverflow,
    willUnderflow,
    willClear,
    willLoad
  )
  override def frozen: Bool = freezeReg

  protected lazy val effectiveInc: Bool =
    if (hasUp && upper == BoundaryPolicy.Freeze) willIncrement && !freezeReg else willIncrement
  protected lazy val effectiveDec: Bool =
    if (hasDown && lower == BoundaryPolicy.Freeze) willDecrement && !freezeReg else willDecrement

  protected lazy val incOnly: Bool = effectiveInc && !effectiveDec
  protected lazy val decOnly: Bool = effectiveDec && !effectiveInc

  protected def enableStandardPruning(): Unit = {
    willOverflowIfInc.allowPruning()
    willOverflow.allowPruning()
    willUnderflowIfDec.allowPruning()
    willUnderflow.allowPruning()
  }

  private def kindName: String = getClass.getSimpleName

  /** Schedule an increment on this cycle. Requires `direction` to be `Up` or `Both`. */
  def increment(): Unit = {
    require(hasUp, s"$kindName.increment() requires direction Up or Both, got $direction")
    willIncrement := True
  }

  /** Schedule a decrement on this cycle. Requires `direction` to be `Down` or `Both`. */
  def decrement(): Unit = {
    require(hasDown, s"$kindName.decrement() requires direction Down or Both, got $direction")
    willDecrement := True
  }

  /** Make this counter free-running upward (increments every cycle). Requires `direction` to be `Up` or `Both`. */
  def freeRun(): this.type = {
    require(hasUp, s"$kindName.freeRun() requires direction Up or Both, got $direction")
    willIncrement.removeAssignments()
    willIncrement := True
    this
  }

  /** Make this counter free-running downward (decrements every cycle). Requires `direction` to be `Down` or `Both`. */
  def freeRunDown(): this.type = {
    require(hasDown, s"$kindName.freeRunDown() requires direction Down or Both, got $direction")
    willDecrement.removeAssignments()
    willDecrement := True
    this
  }

  /** True when the counter is currently pinned at the upper boundary by [[BoundaryPolicy.Saturate]];
    * always false if `upper` is not `Saturate`.
    */
  def saturatedHigh: Bool = {
    require(hasUp, s"$kindName.saturatedHigh requires direction Up or Both, got $direction")
    if (upper == BoundaryPolicy.Saturate) willOverflowIfInc else False
  }

  /** True when the counter is currently pinned at the lower boundary by [[BoundaryPolicy.Saturate]];
    * always false if `lower` is not `Saturate`.
    */
  def saturatedLow: Bool = {
    require(hasDown, s"$kindName.saturatedLow requires direction Down or Both, got $direction")
    if (lower == BoundaryPolicy.Saturate) willUnderflowIfDec else False
  }
}

/** Creates an always running counter
  *
  * See [[https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Libraries/utils.html?highlight=counter#counter]]
  */
object CounterFreeRun {
  def apply(stateCount: BigInt): Counter = Counter(stateCount).freeRun()
  def apply(bitCount: BitCount): Counter = Counter(bitCount).freeRun()
  def apply(stateCount: ElabInt): Counter = Counter(stateCount).freeRun()
}

/** Creates a counter
  *
  * See [[https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Libraries/utils.html?highlight=counter#counter]]
  */
object Counter {

  /** Internal counter geometry. Concrete callers deliberately retain their
    * ordinary BigInt path; typed callers keep exact expressions alongside the
    * one native construction witness.
    */
  private[lib] sealed trait Bounds {
    def startWitness: BigInt
    def endWitness: BigInt
    def stateCountWitness: BigInt
  }

  private[lib] final case class ConcreteBounds(
      startWitness: BigInt,
      endWitness: BigInt
  ) extends Bounds {
    def stateCountWitness: BigInt = endWitness - startWitness + 1
  }

  private[lib] final case class TypedBounds(
      start: ElabInt,
      end: ElabInt,
      stateCount: ElabInt,
      endExclusive: ElabInt,
      valueWidth: ElabInt,
      stepWidth: ElabInt
  ) extends Bounds {
    def startWitness: BigInt = BigInt(start.witness)
    def endWitness: BigInt = BigInt(end.witness)
    def stateCountWitness: BigInt = BigInt(stateCount.witness)
  }

  private def typedFailure(
      code: String,
      detail: String,
      sourceLocation: Option[String]
  ): Nothing =
    throw new ParameterizedVerilogException(code, detail, sourceLocation)

  private def requireExactCounterValue(value: ElabInt, role: String): Unit = {
    if (value == null)
      typedFailure(
        "SPINAL-ELAB-COUNTER-BOUND-NULL",
        s"$role must not be null",
        None
      )
    value.requireAuthoritativeIntegerDomain(
      role,
      "SPINAL-ELAB-COUNTER-EXACT-DOMAIN-REQUIRED",
      requireExactExtrema = false
    )
    val expression = value.projectedExpression(role)
    if (expression.generateIndex.nonEmpty)
      typedFailure(
        "SPINAL-ELAB-COUNTER-GENERATE-INDEX-UNSUPPORTED",
        s"$role expression '${expression.verilog}' depends on a generate index; " +
          "typed Counter bounds require definition-local parameter ownership",
        expression.sourceLocation
      )
    ElabInt.requireAuthoritativeIntegerDomain(
      expression,
      role,
      "SPINAL-ELAB-COUNTER-EXACT-DOMAIN-REQUIRED",
      requireExactExtrema = false
    )
  }

  private def checkedTypedBounds(
      start: ElabInt,
      end: ElabInt,
      stateCount: ElabInt
  ): TypedBounds = {
    requireExactCounterValue(start, "typed Counter start")
    requireExactCounterValue(end, "typed Counter end")
    requireExactCounterValue(stateCount, "typed Counter state count")
    ElabInt.requireSingleSymbolicRoot(
      "typed Counter bounds",
      start,
      end,
      stateCount
    )

    val derivedStateCount = end - start + 1
    if (!derivedStateCount.elabEq(stateCount).isAlwaysTrue)
      typedFailure(
        "SPINAL-ELAB-COUNTER-STATE-COUNT-MISMATCH",
        s"typed Counter state count '${stateCount
            .projectedExpression("typed Counter state count")
            .verilog}' is not exactly end - start + 1",
        stateCount.sourceLocation
          .orElse(start.sourceLocation)
          .orElse(end.sourceLocation)
      )
    if (start.minimum < 0)
      typedFailure(
        "SPINAL-ELAB-COUNTER-START-DOMAIN-NEGATIVE",
        s"typed Counter start must remain non-negative, but '${start
            .projectedExpression("typed Counter start")
            .verilog}' reaches ${start.minimum}",
        start.sourceLocation
      )
    if (end.minimum < 0)
      typedFailure(
        "SPINAL-ELAB-COUNTER-END-DOMAIN-NEGATIVE",
        s"typed Counter end must remain non-negative, but '${end
            .projectedExpression("typed Counter end")
            .verilog}' reaches ${end.minimum}",
        end.sourceLocation
      )
    if (stateCount.minimum < 1)
      typedFailure(
        "SPINAL-ELAB-COUNTER-STATE-COUNT-DOMAIN-NONPOSITIVE",
        s"typed Counter state count must remain positive, but '${stateCount
            .projectedExpression("typed Counter state count")
            .verilog}' reaches ${stateCount.minimum}",
        stateCount.sourceLocation
          .orElse(start.sourceLocation)
          .orElse(end.sourceLocation)
      )

    // For the canonical zero-based state-count API, retain the caller's exact
    // state-count identity instead of rebuilding the equivalent
    // `(stateCount - 1) + 1` syntax.  The equality above is an exact-domain
    // proof, and this keeps downstream native assignments on one typed width
    // expression without introducing algebraic/name-based reconstruction.
    val endExclusive =
      if (start.elabEq(0).isAlwaysTrue) stateCount
      else end + 1
    TypedBounds(
      start = start,
      end = end,
      stateCount = stateCount,
      endExclusive = endExclusive,
      valueWidth = endExclusive.addressWidth,
      stepWidth = stateCount.addressWidth
    )
  }

  private[lib] def typedBounds(start: ElabInt, end: ElabInt): TypedBounds = {
    requireExactCounterValue(start, "typed Counter start")
    requireExactCounterValue(end, "typed Counter end")
    checkedTypedBounds(start, end, end - start + 1)
  }

  private[lib] def typedStateCountBounds(stateCount: ElabInt): TypedBounds = {
    requireExactCounterValue(stateCount, "typed Counter state count")
    checkedTypedBounds(ElabInt.literal(0), stateCount - 1, stateCount)
  }

  private def fromTypedBounds(
      bounds: TypedBounds,
      direction: CounterDirection
  ): Counter =
    new Counter(
      bounds,
      direction,
      BoundaryPolicy.Wrap,
      BoundaryPolicy.Wrap,
      handleOverflow = true
    )

  private[lib] def freezeLatch(
      upperFreeze: Boolean,
      lowerFreeze: Boolean,
      willOverflow: Bool,
      willUnderflow: Bool,
      willClear: Bool,
      willLoad: Bool
  ): Bool = {
    if (!upperFreeze && !lowerFreeze) return False
    val setTrig =
      if (upperFreeze && lowerFreeze) willOverflow || willUnderflow
      else if (upperFreeze) willOverflow
      else willUnderflow
    RegInit(False).setWhen(setTrig).clearWhen(willClear || willLoad)
  }

  private[lib] def byDirection(direction: CounterDirection)(up: => Bool, down: => Bool): Bool =
    direction match {
      case CounterDirection.Up   => up
      case CounterDirection.Down => down
      case CounterDirection.Both => up || down
    }

  private[lib] def guardedComplete(direction: CounterDirection)(ifSig: Bool, trig: Bool, cancel: Bool): Bool =
    if (direction == CounterDirection.Both) ifSig && trig && !cancel
    else ifSig && trig

  /** Create a counter on `[start, end]` */
  def apply(start: BigInt, end: BigInt): Counter = new Counter(start, end)

  /** Create a counter on `[range.low, range.high]` */
  def apply(range: Range): Counter = {
    require(range.step == 1)
    apply(range.low, range.high)
  }

  /** Create a counter on `[0, stateCount-1]` */
  def apply(stateCount: BigInt): Counter = new Counter(0, stateCount - 1)

  /** Create a counter on the exact typed range `[0, stateCount-1]`.
    * Literal typed values deliberately re-enter the ordinary BigInt path.
    */
  def apply(stateCount: ElabInt): Counter = {
    if (stateCount == null)
      typedFailure(
        "SPINAL-ELAB-COUNTER-BOUND-NULL",
        "typed Counter state count must not be null",
        None
      )
    if (stateCount.isConcrete) apply(BigInt(stateCount.witness))
    else fromTypedBounds(typedStateCountBounds(stateCount), CounterDirection.Up)
  }

  /** Create a counter on the exact typed inclusive range `[start, end]`. */
  def apply(start: ElabInt, end: ElabInt): Counter = {
    if (start == null || end == null)
      typedFailure(
        "SPINAL-ELAB-COUNTER-BOUND-NULL",
        "typed Counter start and end must not be null",
        Option(start).flatMap(_.sourceLocation).orElse(Option(end).flatMap(_.sourceLocation))
      )
    if (start.isConcrete && end.isConcrete)
      apply(BigInt(start.witness), BigInt(end.witness))
    else
      fromTypedBounds(typedBounds(start, end), CounterDirection.Up)
  }

  /** Create a counter on `[0, 2^bitCount-1]` */
  def apply(bitCount: BitCount): Counter = new Counter(0, (BigInt(1) << bitCount.value) - 1)

  /** Create a counter on `[start, end]` with `inc` signal as increment enable */
  def apply(start: BigInt, end: BigInt, inc: Bool): Counter = {
    val c = apply(start, end)
    when(inc) { c.increment() }
    c
  }

  /** Create a counter on `[range.low, range.high]` with `inc` signal as increment enable */
  def apply(range: Range, inc: Bool): Counter = {
    require(range.step == 1)
    apply(range.low, range.high, inc)
  }

  /** Create a counter on `[0, stateCount-1]` with `inc` signal as increment enable */
  def apply(stateCount: BigInt, inc: Bool): Counter = apply(0, stateCount - 1, inc)

  /** Typed state-count counterpart of [[apply(BigInt, Bool)]]. */
  def apply(stateCount: ElabInt, inc: Bool): Counter = {
    val c = apply(stateCount)
    when(inc) { c.increment() }
    c
  }

  /** Typed inclusive-range counterpart of [[apply(BigInt, BigInt, Bool)]]. */
  def apply(start: ElabInt, end: ElabInt, inc: Bool): Counter = {
    val c = apply(start, end)
    when(inc) { c.increment() }
    c
  }

  /** Create a counter on `[0, 2^bitCount-1]` with `inc` signal as increment enable */
  def apply(bitCount: BitCount, inc: Bool): Counter = apply(0, (BigInt(1) << bitCount.value) - 1, inc)

  /** Create a counter on `[0, Clocks for given Time]` */
  def apply(time: TimeNumber): Counter = apply(
    ((time.toBigDecimal * ClockDomain.current.frequency.getValue.toBigDecimal)
      .setScale(0, BigDecimal.RoundingMode.UP))
      .toBigInt
  )

  /** Create a counter on `[0, Clocks for given Time]` with `inc` signal as increment enable */
  def apply(time: TimeNumber, inc: Bool): Counter = apply(
    ((time.toBigDecimal * ClockDomain.current.frequency.getValue.toBigDecimal)
      .setScale(0, BigDecimal.RoundingMode.UP))
      .toBigInt,
    inc
  )

  def down(stateCount: BigInt): Counter = new Counter(0, stateCount - 1, CounterDirection.Down)
  def both(stateCount: BigInt): Counter = new Counter(0, stateCount - 1, CounterDirection.Both)

  def down(stateCount: ElabInt): Counter = {
    if (stateCount == null)
      typedFailure(
        "SPINAL-ELAB-COUNTER-BOUND-NULL",
        "typed down-counter state count must not be null",
        None
      )
    if (stateCount.isConcrete) down(BigInt(stateCount.witness))
    else fromTypedBounds(typedStateCountBounds(stateCount), CounterDirection.Down)
  }

  def both(stateCount: ElabInt): Counter = {
    if (stateCount == null)
      typedFailure(
        "SPINAL-ELAB-COUNTER-BOUND-NULL",
        "typed bidirectional-counter state count must not be null",
        None
      )
    if (stateCount.isConcrete) both(BigInt(stateCount.witness))
    else fromTypedBounds(typedStateCountBounds(stateCount), CounterDirection.Both)
  }
}

/** General-purpose binary counter on the inclusive range `[start, end]`.
  *
  * @param start Lowest legal value (inclusive).
  * @param end Highest legal value (inclusive); must be `>= start`.
  * @param direction Allowed motion ([[CounterDirection.Up]], `Down`, or `Both`).
  * @param upper Policy applied at the upper boundary.
  * @param lower Policy applied at the lower boundary.
  * @param handleOverflow When `true` (default), the counter wraps at `stateCount`. Setting it to
  *  `false` only has an effect when `direction` is `Both`, both `upper` and `lower` are `Wrap`,
  *  `start == 0`, and the range is not a power of two: in that case the counter wraps at `2 ^ width`
  *  instead of `stateCount`, saving comparator logic at the cost of a visible difference in behavior.
  */
// start and end inclusive. `handleOverflow=false` opts into 2^width modular wrap
// for Both + both-Wrap + non-pow2 + start==0; default (true) wraps at stateCount.
class Counter private[lib] (
    private val counterBounds: Counter.Bounds,
    direction: CounterDirection,
    upper: BoundaryPolicy,
    lower: BoundaryPolicy,
    val handleOverflow: Boolean
) extends BoundedCounter[UInt](direction, upper, lower) {

  /** Legacy source- and binary-compatible constructor. Literal callers never
    * enter the typed path, including BigInt limits outside the Scala Int
    * domain.
    */
  def this(
      start: BigInt,
      end: BigInt,
      direction: CounterDirection = CounterDirection.Up,
      upper: BoundaryPolicy = BoundaryPolicy.Wrap,
      lower: BoundaryPolicy = BoundaryPolicy.Wrap,
      handleOverflow: Boolean = true
  ) = this(
    Counter.ConcreteBounds(start, end),
    direction,
    upper,
    lower,
    handleOverflow
  )

  val start: BigInt = counterBounds.startWitness
  val end: BigInt = counterBounds.endWitness
  private val typedBounds: Counter.TypedBounds = counterBounds match {
    case value: Counter.TypedBounds => value
    case _                          => null
  }

  require(start <= end)

  private def typedValue(value: ElabInt, prototype: UInt, stableName: String): UInt =
    ElabValue.uintLike(value, prototype, stableName)

  /** One geometry/value/control adapter is the only concrete-vs-typed seam in
    * the binary Counter algorithm. Direction selection, boundary policy,
    * natural wrap, the Both-mode step trick and compared stepping are authored
    * once below and operate on these identity-bearing values.
    */
  private final class AlgorithmValue(val witness: BigInt, val exact: ElabInt)
  private final class PreparedBoundary(
      val value: AlgorithmValue,
      val retainedCarrier: UInt
  )
  private sealed trait AlgorithmControl
  private final class ConcreteAlgorithmControl(val value: Boolean) extends AlgorithmControl
  private final class TypedAlgorithmControl(val value: ElabBool) extends AlgorithmControl

  private sealed trait AlgorithmAdapter {
    def lower: AlgorithmValue
    def upper: AlgorithmValue
    def stateCountValue: AlgorithmValue
    def valueWidthWitness: Int
    def valueType(): UInt
    def register(next: UInt, initial: AlgorithmValue): UInt
    def equalTo(signal: UInt, limit: AlgorithmValue): Bool
    def literal(value: BigInt): AlgorithmValue
    def prepareBoundary(limit: AlgorithmValue, prototype: UInt): PreparedBoundary
    def boundaryValue(prepared: PreparedBoundary): UInt
    def control(value: Boolean): AlgorithmControl
    def isZero(value: AlgorithmValue): AlgorithmControl
    def greaterThanOne(value: AlgorithmValue): AlgorithmControl
    def isPowerOfTwo(value: AlgorithmValue): AlgorithmControl
    def and(left: AlgorithmControl, right: AlgorithmControl): AlgorithmControl
    def or(left: AlgorithmControl, right: AlgorithmControl): AlgorithmControl
    def not(value: AlgorithmControl): AlgorithmControl
    def generateWhen(value: AlgorithmControl)(body: => Unit): Unit
    def select(value: AlgorithmControl)(ifTrue: => Unit)(ifFalse: => Unit): Unit
    def stepType(): UInt
    def prepareDecrementStep(step: UInt): UInt
    def assignDecrementStep(step: UInt, prepared: UInt): Unit
    def assignArithmetic(target: UInt, source: UInt): Unit
    def assignStepSum(target: UInt, source: UInt): Unit
    def withBothTarget(body: UInt => Unit): Unit
    def clearValue(initial: AlgorithmValue, prototype: UInt): UInt
  }

  private final class ConcreteAlgorithmAdapter extends AlgorithmAdapter {
    val lower = new AlgorithmValue(start, null)
    val upper = new AlgorithmValue(end, null)
    val stateCountValue = new AlgorithmValue(end - start + 1, null)
    val valueWidthWitness: Int = log2Up(end + 1)

    def valueType(): UInt = UInt(valueWidthWitness bit)

    def register(next: UInt, initial: AlgorithmValue): UInt =
      RegNext(next) init (initial.witness)

    def equalTo(signal: UInt, limit: AlgorithmValue): Bool =
      signal === limit.witness

    def literal(value: BigInt): AlgorithmValue =
      new AlgorithmValue(value, null)

    def prepareBoundary(
        limit: AlgorithmValue,
        prototype: UInt
    ): PreparedBoundary = new PreparedBoundary(limit, null)

    def boundaryValue(prepared: PreparedBoundary): UInt =
      U(prepared.value.witness, valueWidthWitness bits)

    private def concrete(value: AlgorithmControl): Boolean =
      value.asInstanceOf[ConcreteAlgorithmControl].value

    def control(value: Boolean): AlgorithmControl =
      new ConcreteAlgorithmControl(value)

    def isZero(value: AlgorithmValue): AlgorithmControl =
      control(value.witness == 0)

    def greaterThanOne(value: AlgorithmValue): AlgorithmControl =
      control(value.witness > 1)

    def isPowerOfTwo(value: AlgorithmValue): AlgorithmControl =
      control(isPow2(value.witness))

    def and(
        left: AlgorithmControl,
        right: AlgorithmControl
    ): AlgorithmControl = control(concrete(left) && concrete(right))

    def or(
        left: AlgorithmControl,
        right: AlgorithmControl
    ): AlgorithmControl = control(concrete(left) || concrete(right))

    def not(value: AlgorithmControl): AlgorithmControl =
      control(!concrete(value))

    def generateWhen(value: AlgorithmControl)(body: => Unit): Unit =
      if (concrete(value)) body

    def select(value: AlgorithmControl)(ifTrue: => Unit)(
        ifFalse: => Unit
    ): Unit =
      if (concrete(value)) ifTrue else ifFalse

    def stepType(): UInt = UInt(log2Up(stateCountValue.witness) bit)
    def prepareDecrementStep(step: UInt): UInt = null
    def assignDecrementStep(step: UInt, prepared: UInt): Unit =
      step := step.maxValue
    def assignArithmetic(target: UInt, source: UInt): Unit =
      target := source.resized
    def assignStepSum(target: UInt, source: UInt): Unit =
      target := source.resized
    def withBothTarget(body: UInt => Unit): Unit = body(valueNext)
    def clearValue(initial: AlgorithmValue, prototype: UInt): UInt =
      U(initial.witness, valueWidthWitness bits)
  }

  private final class TypedAlgorithmAdapter(
      bounds: Counter.TypedBounds
  ) extends AlgorithmAdapter {
    val lower = new AlgorithmValue(bounds.startWitness, bounds.start)
    val upper = new AlgorithmValue(bounds.endWitness, bounds.end)
    val stateCountValue =
      new AlgorithmValue(bounds.stateCountWitness, bounds.stateCount)
    val valueWidthWitness: Int = bounds.valueWidth.witness

    def valueType(): UInt = UInt(bounds.valueWidth bits)

    def register(next: UInt, initial: AlgorithmValue): UInt = {
      val register = ParameterizedWidth.Reg(next)
      register := next
      register.setCompositeName(next, "regNext", true)
      register init typedValue(initial.exact, register, "")
      register
    }

    def equalTo(signal: UInt, limit: AlgorithmValue): Bool =
      signal === typedValue(limit.exact, signal, "")

    def literal(value: BigInt): AlgorithmValue =
      new AlgorithmValue(value, ElabInt.fromBigInt(value))

    def prepareBoundary(
        limit: AlgorithmValue,
        prototype: UInt
    ): PreparedBoundary =
      new PreparedBoundary(limit, typedValue(limit.exact, prototype, ""))

    def boundaryValue(prepared: PreparedBoundary): UInt =
      prepared.retainedCarrier

    private def typed(value: AlgorithmControl): ElabBool =
      value.asInstanceOf[TypedAlgorithmControl].value

    def control(value: Boolean): AlgorithmControl =
      new TypedAlgorithmControl(ElabBool.literal(value))

    def isZero(value: AlgorithmValue): AlgorithmControl =
      new TypedAlgorithmControl(value.exact.elabEq(0))

    def greaterThanOne(value: AlgorithmValue): AlgorithmControl =
      new TypedAlgorithmControl(value.exact > 1)

    def isPowerOfTwo(value: AlgorithmValue): AlgorithmControl =
      new TypedAlgorithmControl(value.exact.isPow2)

    def and(
        left: AlgorithmControl,
        right: AlgorithmControl
    ): AlgorithmControl =
      new TypedAlgorithmControl(typed(left) && typed(right))

    def or(
        left: AlgorithmControl,
        right: AlgorithmControl
    ): AlgorithmControl =
      new TypedAlgorithmControl(typed(left) || typed(right))

    def not(value: AlgorithmControl): AlgorithmControl =
      new TypedAlgorithmControl(!typed(value))

    def generateWhen(value: AlgorithmControl)(body: => Unit): Unit =
      ElabControl.generateSymbolic(
        typed(value),
        sourcecode.File(),
        sourcecode.Line()
      ) {
        body
      }

    def select(value: AlgorithmControl)(ifTrue: => Unit)(
        ifFalse: => Unit
    ): Unit =
      ElabControl.selectSymbolic(
        typed(value),
        sourcecode.File(),
        sourcecode.Line()
      )(ifTrue)(ifFalse)

    def stepType(): UInt = UInt(bounds.stepWidth bits)

    def prepareDecrementStep(step: UInt): UInt =
      ElabValue.uintAllOnes(bounds.stepWidth, "")

    def assignDecrementStep(step: UInt, prepared: UInt): Unit =
      step := prepared

    def assignArithmetic(target: UInt, source: UInt): Unit =
      target := source.resized

    def assignStepSum(target: UInt, source: UInt): Unit =
      target := source.resized

    def withBothTarget(body: UInt => Unit): Unit = {
      val target = UInt(bounds.valueWidth bits)
      target.dontSimplifyIt()
      body(target)
      valueNext := target
    }

    def clearValue(initial: AlgorithmValue, prototype: UInt): UInt =
      typedValue(initial.exact, prototype, "")
  }

  private val algorithm: AlgorithmAdapter =
    if (typedBounds == null) new ConcreteAlgorithmAdapter
    else new TypedAlgorithmAdapter(typedBounds)

  private def naturalWrapControl(policy: BoundaryPolicy): AlgorithmControl = {
    val zeroBasedNonSingleton = algorithm.and(
      algorithm.isZero(algorithm.lower),
      algorithm.greaterThanOne(algorithm.stateCountValue)
    )
    algorithm.and(
      algorithm.control(policy == BoundaryPolicy.Wrap),
      algorithm.and(
        zeroBasedNonSingleton,
        algorithm.isPowerOfTwo(algorithm.stateCountValue)
      )
    )
  }

  private def stepTrickControl(
      bothWrap: Boolean,
      useNaturalOverflow: Boolean
  ): AlgorithmControl = {
    val eligible = algorithm.and(
      algorithm.control(bothWrap),
      algorithm.and(
        algorithm.isZero(algorithm.lower),
        algorithm.greaterThanOne(algorithm.stateCountValue)
      )
    )
    algorithm.and(
      eligible,
      algorithm.or(
        algorithm.control(!useNaturalOverflow),
        algorithm.isPowerOfTwo(algorithm.stateCountValue)
      )
    )
  }

  private val w: Int = algorithm.valueWidthWitness
  private val initialValue: AlgorithmValue =
    if (direction == CounterDirection.Down) algorithm.upper
    else algorithm.lower

  val valueNext: UInt = algorithm.valueType()
  val value: UInt = algorithm.register(valueNext, initialValue)

  val willOverflowIfInc: Bool = algorithm.equalTo(value, algorithm.upper)
  val willUnderflowIfDec: Bool = algorithm.equalTo(value, algorithm.lower)

  private def preparedBoundary(
      policy: BoundaryPolicy,
      wrapTo: AlgorithmValue,
      pinTo: AlgorithmValue,
      target: UInt
  ): PreparedBoundary =
    algorithm.prepareBoundary(
      if (policy == BoundaryPolicy.Wrap) wrapTo else pinTo,
      target
    )

  /** The sole single-step boundary algorithm for concrete and typed Counter. */
  private def applyOneStep(
      arith: UInt,
      boundary: Bool,
      policy: BoundaryPolicy,
      prepared: PreparedBoundary,
      target: UInt
  ): Unit = {
    // Keep the authoritative update as a whole-target native resize. MorphHDL
    // captures this exact assignment before Spinal removes its resize marker.
    algorithm.assignArithmetic(target, arith)
    algorithm.generateWhen(algorithm.not(naturalWrapControl(policy))) {
      when(boundary) {
        target := algorithm.boundaryValue(prepared)
      }
    }
  }

  /** Preserve the historical public/native five-argument update surface. */
  def stepOne(
      arith: UInt,
      boundary: Bool,
      policy: BoundaryPolicy,
      wrapTo: BigInt,
      pinTo: BigInt
  ): Unit = {
    val prepared = preparedBoundary(
      policy,
      algorithm.literal(wrapTo),
      algorithm.literal(pinTo),
      valueNext
    )
    applyOneStep(arith, boundary, policy, prepared, valueNext)
  }

  private def emitDirectionalStep(
      arith: UInt,
      boundary: Bool,
      policy: BoundaryPolicy,
      wrapTo: AlgorithmValue,
      pinTo: AlgorithmValue,
      target: UInt
  ): Unit = {
    val prepared = preparedBoundary(policy, wrapTo, pinTo, target)
    applyOneStep(arith, boundary, policy, prepared, target)
  }

  private def emitStepTrick(
      incrementOnly: Bool,
      decrementOnly: Bool,
      target: UInt
  ): Unit = {
    val step = algorithm.stepType()
    val decrementStep = algorithm.prepareDecrementStep(step)
    when(incrementOnly) { step := 1 }
      .elsewhen(decrementOnly) {
        algorithm.assignDecrementStep(step, decrementStep)
      }
      .otherwise { step := 0 }
    algorithm.assignStepSum(target, value + step)
  }

  private def emitComparedSteps(
      incrementOnly: Bool,
      decrementOnly: Bool,
      overflow: Bool,
      underflow: Bool,
      target: UInt,
      upperBoundary: PreparedBoundary,
      lowerBoundary: PreparedBoundary
  ): Unit = {
    target := value
    when(incrementOnly) {
      applyOneStep(value + 1, overflow, upper, upperBoundary, target)
    }
    when(decrementOnly) {
      applyOneStep(value - 1, underflow, lower, lowerBoundary, target)
    }
  }

  direction match {
    case CounterDirection.Up =>
      emitDirectionalStep(
        value + U(effectiveInc),
        willOverflow,
        upper,
        wrapTo = algorithm.lower,
        pinTo = algorithm.upper,
        target = valueNext
      )
    case CounterDirection.Down =>
      emitDirectionalStep(
        value - U(effectiveDec),
        willUnderflow,
        lower,
        wrapTo = algorithm.upper,
        pinTo = algorithm.lower,
        target = valueNext
      )
    case CounterDirection.Both =>
      // Resolve lazy controls before a typed adapter captures either structural
      // alternative, and retain one target across the shared algorithm.
      val bothIncOnly = incOnly
      val bothDecOnly = decOnly
      val bothWillOverflow = willOverflow
      val bothWillUnderflow = willUnderflow
      val bothWrap = upper == BoundaryPolicy.Wrap && lower == BoundaryPolicy.Wrap
      algorithm.withBothTarget { target =>
        val upperBoundary = preparedBoundary(
          upper,
          wrapTo = algorithm.lower,
          pinTo = algorithm.upper,
          target
        )
        val lowerBoundary = preparedBoundary(
          lower,
          wrapTo = algorithm.upper,
          pinTo = algorithm.lower,
          target
        )
        algorithm.select(stepTrickControl(bothWrap, handleOverflow)) {
          emitStepTrick(bothIncOnly, bothDecOnly, target)
        } {
          emitComparedSteps(
            bothIncOnly,
            bothDecOnly,
            bothWillOverflow,
            bothWillUnderflow,
            target,
            upperBoundary,
            lowerBoundary
          )
        }
      }
  }

  when(willClear) {
    valueNext := algorithm.clearValue(initialValue, valueNext)
  }

  enableStandardPruning()
  willOverflow.setCompositeName(this, "willOverflow", true)
  willUnderflow.setCompositeName(this, "willUnderflow", true)

  def stateCount: BigInt = counterBounds.stateCountWitness

  /** Exact typed state count for parameter-aware native consumers. */
  private[lib] def stateCountElab: Option[ElabInt] =
    Option(typedBounds).map(_.stateCount)

  private def requireTypedOrdinal(index: BigInt): Unit = {
    val source = typedBounds.stateCount.sourceLocation
      .orElse(typedBounds.valueWidth.sourceLocation)
    if (index < 0)
      Counter.typedFailure(
        "SPINAL-ELAB-COUNTER-ORDINAL-NEGATIVE",
        s"typed Counter ordinal must be non-negative, but found $index",
        source
      )
    if (index >= typedBounds.stateCount.minimum)
      Counter.typedFailure(
        "SPINAL-ELAB-COUNTER-ORDINAL-DOMAIN-OUT-OF-RANGE",
        s"typed Counter ordinal $index is not legal for every retained state-count value; the minimum state count is ${typedBounds.stateCount.minimum}",
        source
      )
    val requiredWidth = BigInt(index.bitLength)
    if (requiredWidth > typedBounds.valueWidth.minimum)
      Counter.typedFailure(
        "SPINAL-ELAB-COUNTER-ORDINAL-WIDTH-INSUFFICIENT",
        s"typed Counter ordinal $index requires $requiredWidth bits, outside the minimum retained value width ${typedBounds.valueWidth.minimum} bits",
        source
      )
  }

  private def requireTypedInitialValue(initValue: BigInt): Unit = {
    val source = typedBounds.start.sourceLocation
      .orElse(typedBounds.end.sourceLocation)
      .orElse(typedBounds.valueWidth.sourceLocation)
      .orElse(typedBounds.stateCount.sourceLocation)
    if (initValue < 0)
      Counter.typedFailure(
        "SPINAL-ELAB-COUNTER-INIT-NEGATIVE",
        s"typed Counter initial value must be non-negative, but found $initValue",
        source
      )
    val requiredWidth = BigInt(initValue.bitLength)
    if (requiredWidth > typedBounds.valueWidth.minimum)
      Counter.typedFailure(
        "SPINAL-ELAB-COUNTER-INIT-WIDTH-INSUFFICIENT",
        s"typed Counter initial value $initValue requires $requiredWidth bits, outside the minimum retained value width ${typedBounds.valueWidth.minimum} bits",
        source
      )
    if (
      initValue < typedBounds.start.maximum ||
      initValue > typedBounds.end.minimum
    )
      Counter.typedFailure(
        "SPINAL-ELAB-COUNTER-INIT-DOMAIN-OUT-OF-RANGE",
        s"typed Counter initial value $initValue is not legal for every retained state domain [${typedBounds.start.maximum}, ${typedBounds.end.minimum}]",
        source
      )
  }

  def loadOrdinal(index: UInt): Unit = {
    if (typedBounds == null)
      load((index.resize(w) + U(start, w bits)).resized)
    else {
      val offset = typedValue(
        typedBounds.start,
        value,
        ""
      )
      load(
        (index.resize(typedBounds.valueWidth) + offset)
          .resize(typedBounds.valueWidth)
      )
    }
  }

  override def loadOrdinal(index: Int): Unit = {
    if (typedBounds == null) super.loadOrdinal(index)
    else {
      requireTypedOrdinal(BigInt(index))
      loadOrdinal(U(index).resize(typedBounds.valueWidth))
    }
  }

  override def loadOrdinal(index: BigInt): Unit = {
    if (typedBounds == null) super.loadOrdinal(index)
    else {
      requireTypedOrdinal(index)
      loadOrdinal(U(index).resize(typedBounds.valueWidth))
    }
  }

  /** Override the reset value of the underlying register. */
  def init(initValue: BigInt): this.type = {
    if (typedBounds != null) requireTypedInitialValue(initValue)
    value.removeInitAssignments()
    value.init(initValue)
    this
  }

  def ===(that: UInt): Bool = value === that
  def =/=(that: UInt): Bool = value =/= that
  def !==(that: UInt): Bool = =/=(that)

  override def implicitValue: UInt = this.value
}

/** Binary up/down counter on `[0, stateCount - 1]`, kept for compatibility.
  *
  * Equivalent to `new Counter(0, stateCount - 1, CounterDirection.Both)`, plus four legacy aliases
  * (`incrementIt`, `decrementIt`, `mayOverflow`, `mayUnderflow`) that preserve the names used by
  * the previous `CounterUpDown` API.
  */
class CounterUpDown(
    stateCountArg: BigInt,
    handleOverflow: Boolean = true
) extends Counter(0, stateCountArg - 1, CounterDirection.Both, handleOverflow = handleOverflow) {
  val incrementIt = willIncrement
  val decrementIt = willDecrement
  val mayOverflow = willOverflowIfInc
  val mayUnderflow = willUnderflowIfDec
}

object CounterUpDown {

  /** Create a bidirectional counter with `stateCount` states. */
  def apply(stateCount: BigInt): CounterUpDown = new CounterUpDown(stateCount)

  /** Create a bidirectional counter with `stateCount` states, incremented while `incWhen` is high
    * and decremented while `decWhen` is high.
    */
  def apply(stateCount: BigInt, incWhen: Bool, decWhen: Bool): CounterUpDown =
    apply(stateCount, incWhen, decWhen, handleOverflow = true)

  /** @see [[Counter]] for the meaning of `handleOverflow`. */
  def apply(stateCount: BigInt, incWhen: Bool, decWhen: Bool, handleOverflow: Boolean): CounterUpDown = {
    val c = new CounterUpDown(stateCount, handleOverflow)
    when(incWhen) { c.increment() }
    when(decWhen) { c.decrement() }
    c
  }
}

/** Convenience factories for down-only counters. */
object DownCounter {

  /** Create a down counter on `[0, stateCount - 1]`. */
  def apply(stateCount: BigInt): Counter = Counter.down(stateCount)

  /** Typed state-count counterpart of [[apply(BigInt)]]. */
  def apply(stateCount: ElabInt): Counter = Counter.down(stateCount)

  /** Create a down counter on `[0, stateCount - 1]` with `dec` as decrement enable. */
  def apply(stateCount: BigInt, dec: Bool): Counter = {
    val c = Counter.down(stateCount)
    when(dec) { c.decrement() }
    c
  }

  /** Typed state-count counterpart of [[apply(BigInt, Bool)]]. */
  def apply(stateCount: ElabInt, dec: Bool): Counter = {
    val c = Counter.down(stateCount)
    when(dec) { c.decrement() }
    c
  }

  /** Create a down counter on `[0, 2^bitCount - 1]`. */
  def apply(bitCount: BitCount): Counter =
    new Counter(0, (BigInt(1) << bitCount.value) - 1, CounterDirection.Down)
}

/** One-hot encoded counter with `stateCount` states.
  *
  * The register is `stateCount` bits wide and carries exactly one set bit. Increment rotates the
  * hot bit toward the MSB; decrement rotates it toward the LSB.
  *
  * @param stateCount Number of states (bit-width of the register).
  * @param initialValue Index of the bit set after reset; must satisfy `0 <= initialValue < stateCount`.
  */
// One-hot encoded counter with stateCount states
class OneHotCounter(
    val stateCount: BigInt,
    val initialValue: BigInt = 0,
    direction: CounterDirection = CounterDirection.Up,
    upper: BoundaryPolicy = BoundaryPolicy.Wrap,
    lower: BoundaryPolicy = BoundaryPolicy.Wrap
) extends BoundedCounter[Bits](direction, upper, lower) {

  require(stateCount > 0)
  require(initialValue >= 0 && initialValue < stateCount)

  private val resetValue = Bits(stateCount bits)
  resetValue := B(BigInt(1) << initialValue.toInt, stateCount bits)

  val valueNext = Bits(stateCount bits)
  val value = RegNext(valueNext) init (resetValue)

  val willOverflowIfInc = value.msb
  val willUnderflowIfDec = value.lsb

  valueNext := value

  if (hasUp) {
    val guard = if (upper == BoundaryPolicy.Wrap) incOnly else incOnly && !willOverflowIfInc
    when(guard) { valueNext := value.rotateLeft(1) }
  }
  if (hasDown) {
    val guard = if (lower == BoundaryPolicy.Wrap) decOnly else decOnly && !willUnderflowIfDec
    when(guard) { valueNext := value.rotateRight(1) }
  }
  when(willClear) { valueNext := resetValue }

  enableStandardPruning()

  def ===(that: Bits): Bool = value === that
  def ===(that: Int): Bool = value(that)
  def ===(that: BigInt): Bool = value(that.toInt)
  def ===(that: UInt): Bool = value === UIntToOh(that, stateCount.toInt)

  def =/=(that: Bits): Bool = value =/= that
  def =/=(that: Int): Bool = !value(that)
  def =/=(that: BigInt): Bool = !value(that.toInt)
  def =/=(that: UInt): Bool = value =/= UIntToOh(that, stateCount.toInt)

  def !==(that: Bits): Bool = =/=(that)
  def !==(that: Int): Bool = =/=(that)
  def !==(that: BigInt): Bool = =/=(that)
  def !==(that: UInt): Bool = =/=(that)

  override def implicitValue: Bits = this.value

  /** Load with the bit at position `index` set. */
  def load(index: Int): Unit = { valueNext := B(BigInt(1) << index, stateCount bits); willLoad := True }

  /** Load with the bit at position `index` set. */
  def load(index: UInt): Unit = { valueNext := UIntToOh(index, stateCount.toInt); willLoad := True }

  def loadOrdinal(index: UInt): Unit = load(index)

  private def reinit(newReset: Bits): this.type = {
    resetValue.removeAssignments()
    resetValue := newReset
    value.removeInitAssignments()
    value.init(resetValue)
    this
  }

  /** Override the reset state to have bit `initValue` set. */
  def init(initValue: Int): this.type = reinit(B(BigInt(1) << initValue, stateCount bits))

  /** Override the reset state to have bit `initValue` set. */
  def init(initValue: BigInt): this.type = init(initValue.toInt)

  /** Override the reset state to the given one-hot pattern (caller is responsible for one-hot validity). */
  def init(initValue: Bits): this.type = reinit(initValue)

  /** Override the reset state with the one-hot encoding of `initValue`. */
  def init(initValue: UInt): this.type = reinit(UIntToOh(initValue, stateCount.toInt))
}

/** Creates a one-hot encoded counter */
object OneHotCounter {

  /** Create a one-hot counter with `stateCount` states */
  def apply(stateCount: BigInt): OneHotCounter = new OneHotCounter(stateCount)

  /** Create a one-hot counter with `bitCount` states */
  def apply(bitCount: BitCount): OneHotCounter = new OneHotCounter(bitCount.value)

  /** Create a one-hot counter with `stateCount` states and `inc` signal as increment enable */
  def apply(stateCount: BigInt, inc: Bool): OneHotCounter = {
    val c = new OneHotCounter(stateCount)
    when(inc) { c.increment() }
    c
  }

  /** Create a one-hot counter with `2^bitCount` states and `inc` signal as increment enable */
  def apply(bitCount: BitCount, inc: Bool): OneHotCounter =
    apply(BigInt(1) << bitCount.value, inc)

  /** Up-only one-hot counter with `stateCount` states. */
  def up(stateCount: BigInt): OneHotCounter = new OneHotCounter(stateCount, direction = CounterDirection.Up)

  /** Down-only one-hot counter with `stateCount` states. */
  def down(stateCount: BigInt): OneHotCounter = new OneHotCounter(stateCount, direction = CounterDirection.Down)

  /** Bidirectional one-hot counter with `stateCount` states. */
  def both(stateCount: BigInt): OneHotCounter = new OneHotCounter(stateCount, direction = CounterDirection.Both)
}

/** Creates a Johnson counter (also known as a twisted-ring or Möbius counter):
  * a shift register whose inverted MSB feeds back into the LSB, producing a `2*width`-state
  * sequence with only one bit transition per cycle.
  */
object JohnsonCounter {

  /** Create a Johnson counter of the given width */
  def apply(width: Int): JohnsonCounter = new JohnsonCounter(width)

  /** Create a Johnson counter of the given width with `inc` signal as increment enable */
  def apply(width: Int, inc: Bool): JohnsonCounter = {
    val c = JohnsonCounter(width)
    when(inc) { c.increment() }
    c
  }
}

/** Johnson (twisted-ring / Möbius) counter with `2 * width` legal states.
  *
  * Implementation note: the counter is self-recovering — any illegal start-up pattern reaches the
  * legal cycle within at most `width` increments — but recovery is not guaranteed in a single cycle.
  *
  * @param width Width of the underlying shift register; total legal state count is `2 * width`.
  *  Must be at least 2 (single-bit Johnson counters cannot self-recover).
  * @param upper Policy at the top of the cycle. [[BoundaryPolicy.Saturate]] is rejected because
  *  Johnson cycles do not have a meaningful saturate point.
  */
// Johnson (twisted-ring) counter with `2*width` legal states, self-recovering from illegal states
class JohnsonCounter(
    val width: Int,
    val upper: BoundaryPolicy = BoundaryPolicy.Wrap
) extends ImplicitArea[Bits]
    with CounterLike[Bits] {
  require(width >= 2, "JohnsonCounter needs at least 2 bits for stuck-state recovery")
  require(upper != BoundaryPolicy.Saturate, "Johnson counter does not support Saturate")

  val willIncrement = False.allowOverride
  val willClear = False.allowOverride
  val willLoad = False.allowOverride

  val value = Reg(Bits(width bits)).initZero()
  val valueNext = cloneOf(value)
  valueNext := value

  // True on the end-of-cycle legal state 10..0 and on any illegal state whose top two bits are 10;
  // both snap to 0 on increment. Remaining illegal states reach a detected state within a few shifts,
  // so the counter is self-recovering but not necessarily in a single increment.
  val willOverflowIfInc = value(width - 1) && !value(width - 2)
  val willOverflow = willOverflowIfInc && willIncrement

  val willAdvance: Bool = willIncrement
  val willComplete: Bool = willOverflow
  def stateCount: BigInt = 2 * width

  private val freezeReg: Bool =
    if (upper == BoundaryPolicy.Freeze)
      RegInit(False).setWhen(willOverflow).clearWhen(willClear || willLoad)
    else False
  override def frozen: Bool = freezeReg

  private val effectiveInc =
    if (upper == BoundaryPolicy.Freeze) willIncrement && !freezeReg else willIncrement

  when(effectiveInc) {
    valueNext := willOverflowIfInc ? B(0, width bits) |
      (value(width - 2 downto 0) ## !value(width - 1))
  }
  when(willClear) { valueNext := 0 }

  value := valueNext

  willOverflowIfInc.allowPruning()
  willOverflow.allowPruning()

  /** Schedule an increment on this cycle. */
  def increment(): Unit = willIncrement := True

  /** Make this counter free-running (increments every cycle) */
  def freeRun(): this.type = {
    willIncrement.removeAssignments()
    willIncrement := True
    this
  }

  /** A 50%-duty-cycle signal at 1/(2*width) of the clock. */
  def clkDiv: Bool = value((width - 1) / 2)

  override def implicitValue: Bits = value
}

/** Gray-coded counter with `2 ^ width` states.
  *
  * Adjacent states differ by exactly one bit, including across the wrap. The counter is
  * bidirectional via a parity flip: increment XORs the lowest set bit of `(1, gray[w-3:0],  even)`,
  * decrement XORs the lowest set bit of `(1, gray[w-3:0], !even)`.
  *
  * @param width Width of the underlying register; total state count is `2 ^ width`. Must be at least 2.
  */
// Gray counter with 2^width states. Bidirectional via parity flip:
//   increment: word = Cat(1, gray[width-3:0],  even)
//   decrement: word = Cat(1, gray[width-3:0], !even)
class GrayCounter(
    val width: Int,
    direction: CounterDirection = CounterDirection.Up,
    upper: BoundaryPolicy = BoundaryPolicy.Wrap,
    lower: BoundaryPolicy = BoundaryPolicy.Wrap
) extends BoundedCounter[UInt](direction, upper, lower) {

  require(width >= 2, "GrayCounter needs width >= 2")

  val value = Reg(UInt(width bits)) init (0)
  val valueNext = UInt(width bits)
  private[lib] val even = RegInit(True)

  // Top (ordinal = 2^N - 1) is the MSB-only pattern `1 << (N-1)`; bottom is all zeros.
  private[lib] val topState = U(BigInt(1) << (width - 1), width bits)
  val willOverflowIfInc = value === topState
  val willUnderflowIfDec = value === U(0, width bits)

  private val upperBlock: Bool = if (upper == BoundaryPolicy.Wrap) False else willOverflowIfInc
  private val lowerBlock: Bool = if (lower == BoundaryPolicy.Wrap) False else willUnderflowIfDec

  private val shouldInc = incOnly && !upperBlock
  private val shouldDec = decOnly && !lowerBlock

  private val midSlice = value(width - 3 downto 0).asBits
  private val incWord = Cat(True, midSlice, even)
  private val decWord = Cat(True, midSlice, !even)

  valueNext := value

  // Flip the first set bit of `word`; direction is in `word` (inc vs dec).
  private def applyGrayStep(step: Bool, word: Bits): Unit = {
    when(step) {
      val next = CombInit(value)
      var found = False
      for (i <- 0 until width) {
        when(word(i) && !found) {
          next(i) := !value(i)
          found \= True
        }
      }
      valueNext := next
      even := !even
    }
  }

  if (hasUp) applyGrayStep(shouldInc, incWord)
  if (hasDown) applyGrayStep(shouldDec, decWord)
  when(willClear) { valueNext := 0; even := True }

  value := valueNext

  enableStandardPruning()

  def stateCount: BigInt = BigInt(1) << width

  def loadOrdinal(index: UInt): Unit = {
    val i = index.resize(width)
    valueNext := toGray(i).asUInt
    willLoad := True
    even := !i.lsb
  }

  override def load(raw: UInt): Unit = {
    valueNext := raw
    willLoad := True
    even := !raw.xorR
  }
}

/** Factories for [[GrayCounter]]. */
object GrayCounter {

  /** Up-only Gray counter of the given width. */
  def apply(width: Int): GrayCounter = new GrayCounter(width)

  /** Function-style: returns the underlying gray `UInt` directly, gated by `enable` */
  def apply(width: Int, enable: Bool): UInt = {
    val c = new GrayCounter(width)
    when(enable) { c.increment() }
    c.value
  }

  /** Up-only Gray counter. */
  def up(width: Int): GrayCounter = new GrayCounter(width, CounterDirection.Up)

  /** Down-only Gray counter. */
  def down(width: Int): GrayCounter = new GrayCounter(width, CounterDirection.Down)

  /** Bidirectional Gray counter. */
  def both(width: Int): GrayCounter = new GrayCounter(width, CounterDirection.Both)
}

/** Counter built from a sequence of conditional update functions.
  *
  * Each `(cond, func)` pair contributes a clause: when `cond` is high, the next counter value is
  * fed through `func`. Pairs are applied in argument order, so later functions see the value
  * produced by earlier ones.
  *
  * @param width Width of the resulting `UInt`.
  * @param requests Pairs of (condition, transform) applied in order to compute the next value.
  * @return The registered counter value (post-update each cycle).
  */
object CounterMultiRequest {
  def apply(width: Int, requests: (Bool, (UInt) => UInt)*): UInt = {
    val counter = Reg(UInt(width bit)) init (0)
    var counterNext = cloneOf(counter)
    counterNext := counter
    for ((cond, func) <- requests) {
      when(cond) {
        counterNext \= func(counterNext)
      }
    }
    counter := counterNext
    counter
  }
}
