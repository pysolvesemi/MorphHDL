package spinal.lib

import spinal.core._
import spinal.idslplugin.Location
import spinal.lib.eda.bench.{AlteraStdTargets, Bench, EfinixStdTargets, Rtl, XilinxStdTargets}

import scala.collection.Seq
import scala.collection.mutable

trait StreamPipe {
  /** Return a pipelined version of the provided [[Stream]] based on this [[StreamPipe]] kind. */
  def apply[T <: Data](m: Stream[T]): Stream[T]
}

/** Allows to define what kind of registering (if any) is inserted in a stream connection */
object StreamPipe {
  /** Connect directly */
  val NONE = new StreamPipe {
    override def apply[T <: Data](m: Stream[T]) = m.combStage()
  }

  /** Insert a stage that cut the `valid` and `payload` signals through registers */
  val M2S = new StreamPipe {
    override def apply[T <: Data](m: Stream[T]) = m.m2sPipe()
  }
  /** Insert a stage that cut the `ready` path through a register */
  val S2M = new StreamPipe {
    override def apply[T <: Data](m: Stream[T]) = m.s2mPipe()
  }
  /** Insert a stage that cut the `valid`, `ready` and `payload` signals through registers */
  val FULL = new StreamPipe {
    override def apply[T <: Data](m: Stream[T]) = m.s2mPipe().m2sPipe()
  }
  /** Insert a stage that cut all path, but divide the bandwidth by 2. */
  val HALF = new StreamPipe {
    override def apply[T <: Data](m: Stream[T]) = m.halfPipe()
  }

  val M2S_KEEP = new StreamPipe {
    override def apply[T <: Data](m: Stream[T]) = m.m2sPipe(keep=true)
  }
  val S2M_KEEP = new StreamPipe {
    override def apply[T <: Data](m: Stream[T]) = m.s2mPipe(keep=true)
  }
  val FULL_KEEP = new StreamPipe {
    override def apply[T <: Data](m: Stream[T]) = m.s2mPipe(keep=true).m2sPipe(keep=true)
  }
  val HALF_KEEP = new StreamPipe {
    override def apply[T <: Data](m: Stream[T]) = m.halfPipe(keep=true)
  }
  val HALF_X2_KEEP = new StreamPipe {
    override def apply[T <: Data](m: Stream[T]) = m.halfPipe(keep = true).halfPipe(keep = true)
  }
}

class StreamFactory extends MSFactory {
  object Fragment extends StreamFragmentFactory

  def apply[T <: Data](hardType: HardType[T]) = {
    val ret = new Stream(hardType)
    postApply(ret)
    ret
  }

  def apply[T <: Data](hardType: => T) : Stream[T] = apply(HardType(hardType))
}

object Stream extends StreamFactory

class EventFactory extends MSFactory {
  def apply = {
    val ret = new Stream(new NoData)
    postApply(ret)
    ret
  }
}

/** A simple interface with master payload `valid`, slave `ready` handshake.
  * 
  * When manually reading/driving the signals of a [[Stream]] keep in mind that:
  *
  *  - After being asserted, `valid` may only be deasserted once the current payload was
  *    acknowledged. This means `valid` can only toggle to 0 the cycle after a the slave did
  *    a read by asserting ready.
  *  - In contrast to that `ready` may change at any time.
  *  - A transfer is only done on cycles where both `valid` and `ready` are asserted.
  *  - `valid` of a [[Stream]] must not depend on `ready` in a combinatorial way and any path
  *    between the two must be registered.
  *
  * It is recommended that `valid` does not depend on `ready` at all.
  *  
  * @see [[https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Libraries/stream.html#stream Stream documentation]]
  */
class Stream[T <: Data](val payloadType :  HardType[T]) extends Bundle with IMasterSlave with DataCarrier[T] {
  /** Signal driven by the master, indicating `payload` present on the interface. */
  val valid   = Bool()

  /** Signal driven by the slave, indicating consumption of the `payload`,  don't care when `valid` is 0. */
  val ready   = Bool()

  /** Content of the transaction driven by the master, don't care when `valid` is 0. */
  val payload = payloadType()

  override def clone: Stream[T] =  Stream(payloadType)

  override def asMaster(): Unit = {
    out(valid)
    in(ready)
    out(payload)
  }


  def asDataStream = this.asInstanceOf[Stream[Data]]
  override def freeRun(): this.type = {
    ready := True
    this
  }

/** @return Return a flow driven by this stream. Ready of ths stream is always high
  */
  def toFlow: Flow[T] = {
    freeRun()
    val ret = Flow(payloadType)
    ret.valid := this.valid
    ret.payload := this.payload
    ret.setCompositeName(this, "toFlow", true)
  }

  def toFlowFire: Flow[T] = {
    val ret = Flow(payloadType)
    ret.valid := this.fire
    ret.payload := this.payload
    ret.setCompositeName(this, "toFlowFire", true)
  }

  def asFlow: Flow[T] = {
    val ret = Flow(payloadType)
    ret.valid := this.valid
    ret.payload := this.payload
    ret.setCompositeName(this, "asFlow", true)
  }

  /** Connect `slaveStream << masterStream` without any registering.*/
  def <<(that: Stream[T]): Stream[T] = connectFrom(that)

 /** Connect `masterStream >> slaveStream` without any registering.*/
  def >>(into: Stream[T]): Stream[T] = {
    into << this
    into
  }

  /** Connect `slaveStream <-< masterStream`. The `valid`/`payload` path are cut by an register stage. */
  def <-<(that: Stream[T]): Stream[T] = {
    this << that.stage()
    that
  }

  /** Connect `masterStream >-> slaveStream`. The `valid`/`payload` path are cut by an register stage. */
  def >->(into: Stream[T]): Stream[T] = {
    into <-< this
    into
  }

  /** Connect `slaveStream </< masterStream`. The `ready` path is cut by an register stage. */
  def </<(that: Stream[T]): Stream[T] = {
    this << that.s2mPipe()
    that
  }

  /** Connect `masterStream >/> slaveStream`. The `ready` path is cut by an register stage. */
  def >/>(that: Stream[T]): Stream[T] = {
    that </< this
    that
  }

  /** Connect `slaveStream <-/< masterStream`. The `valid`/`payload`/`ready` path are cut by an register stage. */
  def <-/<(that: Stream[T]): Stream[T] = {
    this << that.s2mPipe().m2sPipe()
    that
  }

  /** Connect `masterStream >/-> slaveStream`. The `valid`/`payload`/`ready` path are cut by an register stage. */
  def >/->(into: Stream[T]): Stream[T] = {
    into <-/< this;
    into
  }

  /** Return a pipelined version of this [[Stream]] based on the provided StreamPipe spec. */
  def pipelined(pipe: StreamPipe) = pipe(this)

  /** Return a pipelined version of this [[Stream]] based on the provided arguments.
   * 
   * @param m2s cut [[valid]] and [[payload]] with registers if `true`
   * @param s2m cut [[ready]] with a register if `true`
   * @param halfRate Cut [[valid]]/[[ready]]/[[payload]] with some registers. Bandwidth divided by 2.
   *                 Can be `true` only when `m2s` and `s2m` are false.
   */
  def pipelined(m2s : Boolean = false,
                s2m : Boolean = false,
                halfRate : Boolean = false) : Stream[T] = {
    (m2s, s2m, halfRate) match {
      case (false,false,false) => StreamPipe.NONE(this)
      case (true,false,false) =>  StreamPipe.M2S(this)
      case (false,true,false) =>  StreamPipe.S2M(this)
      case (true,true,false) =>   StreamPipe.FULL(this)
      case (false,false,true) =>  StreamPipe.HALF(this)
      case _ => { report(s"Parameters ($m2s, $s2m, $halfRate) are not valid for pipelined function.")
        null.asInstanceOf[Stream[T]]
      }
    }
  }

  /** Typed counterpart of [[pipelined(Boolean, Boolean, Boolean)]].
    *
    * The three predicates must share one exact elaboration domain.  Each
    * legal source alternative delegates to the ordinary Boolean overload, so
    * the native pipeline implementations remain authoritative; only their
    * structural selection is retained for parameterized lowering.
    */
  def pipelined(
      m2s: ElabBool,
      s2m: ElabBool,
      halfRate: ElabBool
  ): Stream[T] = {
    if (m2s == null || s2m == null || halfRate == null)
      throw new IllegalArgumentException(
        "typed Stream pipeline predicates must not be null"
      )

    val legal = !(halfRate && (m2s || s2m))
    ElabControl.requireCondition(
      legal,
      "halfRate can be enabled only when m2s and s2m are disabled",
      sourcecode.File(),
      sourcecode.Line()
    )

    if (!m2s.isSymbolic && !s2m.isSymbolic && !halfRate.isSymbolic)
      pipelined(m2s.witness, s2m.witness, halfRate.witness)
    else {
      // Keep module-scope packed input carriers ahead of the mutually
      // exclusive alternatives. A one-bit Bits value is used for valid so
      // its declaration remains outside every generated branch; each branch
      // then rebuilds only a local Stream connection to the native algorithm.
      val validCarrier = Bits(1 bits)
      validCarrier := 0
      when(this.valid) {
        validCarrier := 1
      }
      val payloadCarrier = payloadType()
      payloadCarrier := this.payload
      when(this.valid) {
        payloadCarrier := this.payload
      }
      val readyCarrier = Bool()
      this.ready := False
      when(readyCarrier) {
        this.ready := True
      }
      validCarrier.setAsVital().dontSimplifyIt().noBackendCombMerge()
      readyCarrier.setAsVital().dontSimplifyIt().noBackendCombMerge()
      payloadCarrier.flatten.foreach(
        _.setAsVital().dontSimplifyIt().noBackendCombMerge()
      )
      validCarrier.setCompositeName(this, "pipelinedSourceValid", true)
      readyCarrier.setCompositeName(this, "pipelinedSourceReady", true)
      payloadCarrier.setCompositeName(this, "pipelinedSourcePayload", true)

      def nativePipeline(
          nativeM2s: Boolean,
          nativeS2m: Boolean,
          nativeHalfRate: Boolean
      ): Stream[T] = {
        val branchSource = Stream(payloadType)
        branchSource.valid := validCarrier(0)
        branchSource.payload := payloadCarrier
        readyCarrier := branchSource.ready
        branchSource.pipelined(
          m2s = nativeM2s,
          s2m = nativeS2m,
          halfRate = nativeHalfRate
        )
      }
      val result = Stream(payloadType)
      def select(condition: ElabBool)(ifTrue: => Unit)(ifFalse: => Unit): Unit =
        ElabControl.selectSymbolic(
          condition,
          sourcecode.File(),
          sourcecode.Line()
        )(ifTrue)(ifFalse)

      select(halfRate) {
        result << nativePipeline(false, false, true)
        ()
      } {
        select(m2s) {
          select(s2m) {
            result << nativePipeline(true, true, false)
            ()
          } {
            result << nativePipeline(true, false, false)
            ()
          }
        } {
          select(s2m) {
            result << nativePipeline(false, true, false)
            ()
          } {
            result << nativePipeline(false, false, false)
            ()
          }
        }
      }
      result.setCompositeName(this, "pipelined", true)
    }
  }

  def &(cond: Bool): Stream[T] = continueWhen(cond)
  def ~[T2 <: Data](that: T2): Stream[T2] = translateWith(that)
  def ~~[T2 <: Data](translate: (T) => T2): Stream[T2] = map(translate)
  
 /** Return a [[Stream]] with payload calculated by a translate function.
   * 
   * Modify the payload of the x stream, while preserving the valid and ready signals
   */

  def map[T2 <: Data](translate: (T) => T2): Stream[T2] = {
    (this ~ translate(this.payload)).setCompositeName(this, "map", true)
  }

  /** Ignore the payload */
  def toEvent() : Event = {
    val ret = Event
    ret.arbitrationFrom(this)
    ret.setCompositeName(this, "toEvent", true)
  }

/** Connect this to a fifo and return its pop stream
  */
  def queue(size: Int, latency : Int = 2, forFMax : Boolean = false): Stream[T] = new Composite(this){
    val fifo = StreamFifo(payloadType, size, latency = latency, forFMax = forFMax).setCompositeName(this,"queue", true)
    fifo.io.push << self
  }.fifo.io.pop

  def queue(size: ElabInt): Stream[T] = queue(size, 2, false)

  def queue(size: ElabInt, latency: Int): Stream[T] =
    queue(size, latency, false)

  def queue(
      size: ElabInt,
      latency: Int,
      forFMax: Boolean
  ): Stream[T] = new Composite(this) {
    assert(latency >= 0 && latency <= 2)
    val fifo = StreamFifo(
      payloadType,
      size,
      withAsyncRead = latency < 2,
      withBypass = latency == 0,
      allowExtraMsb = true,
      forFMax = forFMax,
      useVec = false,
      initPayload = None
    ).setCompositeName(this, "queue", true)
    fifo.io.push << self
  }.fifo.io.pop

  /** Connect this to a register constructed fifo and return its pop stream
   */
  def queueOfReg(size: Int, latency : Int = 1, forFMax : Boolean = false, initPayload : => Option[T] = None): Stream[T] = new Composite(this){
    val fifo = new StreamFifo(payloadType, size, withBypass = latency == 0, withAsyncRead = true, useVec = true, forFMax = forFMax, initPayload = initPayload).setCompositeName(this,"queue", true)
    fifo.io.push << self
  }.fifo.io.pop

/** Connect this to an clock crossing fifo and return its pop stream
  */
  def queue(size: Int, pushClock: ClockDomain, popClock: ClockDomain): Stream[T] = {
    val fifo = new StreamFifoCC(payloadType, size, pushClock, popClock).setCompositeName(this,"queue", true)
    fifo.io.push << this
    fifo.io.pop
  }

  def queue(size: ElabInt, pushClock: ClockDomain, popClock: ClockDomain): Stream[T] = {
    val fifo = StreamFifoCC(payloadType, size, pushClock, popClock)
      .setCompositeName(this, "queue", true)
    fifo.io.push << this
    fifo.io.pop
  }

/** Connect this to a fifo and return its pop stream and its occupancy
  */
  private def queuedWithOccupancy(fifo: StreamFifo[T]): (Stream[T], UInt) =
    (fifo.io.pop, fifo.io.occupancy)

  def queueWithOccupancy(size: Int, latency : Int = 2, forFMax : Boolean = false): (Stream[T], UInt) = {
    val fifo = StreamFifo(payloadType, size, latency = latency, forFMax = forFMax).setCompositeName(this,"queueWithOccupancy", true)
    fifo.io.push << this
    queuedWithOccupancy(fifo)
  }

  def queueWithOccupancy(size: ElabInt): (Stream[T], UInt) =
    queueWithOccupancy(size, 2, false)

  def queueWithOccupancy(
      size: ElabInt,
      latency: Int
  ): (Stream[T], UInt) = queueWithOccupancy(size, latency, false)

  def queueWithOccupancy(
      size: ElabInt,
      latency: Int,
      forFMax: Boolean
  ): (Stream[T], UInt) = {
    assert(latency >= 0 && latency <= 2)
    val fifo = StreamFifo(
      payloadType,
      size,
      withAsyncRead = latency < 2,
      withBypass = latency == 0,
      allowExtraMsb = true,
      forFMax = forFMax,
      useVec = false,
      initPayload = None
    ).setCompositeName(this, "queueWithOccupancy", true)
    fifo.io.push << this
    queuedWithOccupancy(fifo)
  }

  private def queuedWithAvailability(fifo: StreamFifo[T]): (Stream[T], UInt) =
    (fifo.io.pop, fifo.io.availability)

  def queueWithAvailability(size: Int, latency : Int = 2, forFMax : Boolean = false): (Stream[T], UInt) = {
    val fifo = StreamFifo(payloadType, size, latency = latency, forFMax = forFMax).setCompositeName(this,"queueWithAvailability", true)
    fifo.io.push << this
    queuedWithAvailability(fifo)
  }

  def queueWithAvailability(size: ElabInt): (Stream[T], UInt) =
    queueWithAvailability(size, 2, false)

  def queueWithAvailability(
      size: ElabInt,
      latency: Int
  ): (Stream[T], UInt) = queueWithAvailability(size, latency, false)

  def queueWithAvailability(
      size: ElabInt,
      latency: Int,
      forFMax: Boolean
  ): (Stream[T], UInt) = {
    assert(latency >= 0 && latency <= 2)
    val fifo = StreamFifo(
      payloadType,
      size,
      withAsyncRead = latency < 2,
      withBypass = latency == 0,
      allowExtraMsb = true,
      forFMax = forFMax,
      useVec = false,
      initPayload = None
    ).setCompositeName(this, "queueWithAvailability", true)
    fifo.io.push << this
    queuedWithAvailability(fifo)
  }

/** Connect this to a cross clock domain fifo and return its pop stream and its push side occupancy
  */
  def queueWithPushOccupancy(size: Int, pushClock: ClockDomain, popClock: ClockDomain): (Stream[T], UInt) = {
    val fifo = new StreamFifoCC(payloadType, size, pushClock, popClock).setCompositeName(this,"queueWithPushOccupancy", true)
    fifo.io.push << this
    (fifo.io.pop, fifo.io.pushOccupancy)
  }

  def queueWithPushOccupancy(size: ElabInt, pushClock: ClockDomain, popClock: ClockDomain): (Stream[T], UInt) = {
    val fifo = StreamFifoCC(payloadType, size, pushClock, popClock)
      .setCompositeName(this, "queueWithPushOccupancy", true)
    fifo.io.push << this
    (fifo.io.pop, fifo.io.pushOccupancy)
  }


  /** Connect this to a zero latency fifo and return its pop stream
    */
  def queueLowLatency(size: Int, latency : Int = 0): Stream[T] = {
    val fifo = new StreamFifoLowLatency(payloadType, size, latency).setCompositeName(this,"queueLowLatency", true)
    fifo.setPartialName(this, "fifo", true)
    fifo.io.push << this
    fifo.io.pop
  }

  def ccToggle(pushClock: ClockDomain, popClock: ClockDomain): Stream[T] = {
    val cc = new StreamCCByToggle(payloadType, pushClock, popClock, initPayload = null.asInstanceOf[T]).setCompositeName(this,"ccToggle", true)
    cc.io.input << this
    cc.io.output
  }

  def ccToggleWithoutBuffer(pushClock: ClockDomain, popClock: ClockDomain): Stream[T] = {
    val cc = new StreamCCByToggle(payloadType, pushClock, popClock, withOutputBuffer=false, withInputWait=true, initPayload = null.asInstanceOf[T]).setCompositeName(this,"ccToggle", true)
    cc.io.input << this
    cc.io.output
  }

  def ccToggleInputWait(pushClock: ClockDomain, popClock: ClockDomain): Stream[T] = {
    val cc = new StreamCCByToggle(payloadType, pushClock, popClock, withInputWait=true, initPayload = null.asInstanceOf[T]).setCompositeName(this,"ccToggle", true)
    cc.io.input << this
    cc.io.output
  }


  /**
   * Connect this to a new stream that only advances every n elements, thus repeating the input several times.
   * @return A tuple with the resulting stream that duplicates the items and the counter, indicating how many
   *				 times the current element has been repeated.
   */
  def repeat(times: Int): (Stream[T], UInt) = {
    val ret = Stream(payloadType)
    val counter = Counter(times, ret.fire)
    ret.valid := this.valid
    ret.payload := this.payload
    this.ready := ret.ready && counter.willOverflowIfInc
    (ret.setCompositeName(this,"repeat", true), counter)
  }

  /**
   * Connect this to a new stream whose payload is n times as wide, but that only fires every n cycles.
   * It introduces 0 to factor-1 cycles of latency. Mapping a stream into memory and mapping a slowed
   * down stream into memory should yield the same result, thus the elements of the input will be
   * written from high bits to low bits.
   */
  def slowdown(factor: Int): Stream[Vec[T]] = {
    val next = new Stream(Vec(payloadType(), factor)).setCompositeName(this, "slowdown_x" + factor, true)
    next.payload(0) := this.payload
    for (i <- 1 until factor) {
      next.payload(i) := RegNextWhen(next.payload(i - 1), this.fire)
    }
    val counter = Counter(factor)
    when(this.fire) {
      counter.increment()
    }
    when(counter.willOverflowIfInc) {
      this.ready := next.ready
      next.valid := this.valid
    } otherwise {
      this.ready := True
      next.valid := False
    }
    next.setCompositeName(this,"slowdown", true)
  }

/** Return `True` when a transaction is present on the bus but the `ready` signal is low
    */
  def isStall : Bool = signalCache(this ->"isStall")((valid && !ready).setCompositeName(this, "isStall", true))

  /** Return `True` when a transaction has appeared (first cycle)
    */
  def isNew : Bool = signalCache(this ->"isNew")((valid && !(RegNext(isStall) init(False))).setCompositeName(this, "isNew", true))

  /** Return `True` when a transaction occurs on the bus (`valid && ready`)
  */
  override def fire: Bool = signalCache(this ->"fire")((valid & ready).setCompositeName(this, "fire", true))

/** Return `True` when the bus isn't stuck with a transaction (`!isStall`)
  */
  def isFree: Bool = signalCache(this ->"isFree")((!valid || ready).setCompositeName(this, "isFree", true))

  /** Connect this slave [[Stream]] to `that` master [[Stream]] */
  def connectFrom(that: Stream[T]): Stream[T] = {
    this.valid := that.valid
    that.ready := this.ready
    this.payload := that.payload
    that
  }

  /** Drive arbitration signals of this [[Stream]] from the provided [[Stream]] */
  def arbitrationFrom[T2 <: Data](that : Stream[T2]) : Unit = {
    this.valid := that.valid
    that.ready := this.ready
  }

  def translateFrom[T2 <: Data](that: Stream[T2])(dataAssignment: (T, that.payload.type) => Unit): Stream[T] = {
    this.valid := that.valid
    that.ready := this.ready
    dataAssignment(this.payload, that.payload)
    this
  }

  def translateInto[T2 <: Data](into: Stream[T2])(dataAssignment: (T2, T) => Unit): Stream[T2] = {
    into.translateFrom(this)(dataAssignment)
    into
  }

/** Replace this stream's payload with another one
  */
  def translateWith[T2 <: Data](that: T2): Stream[T2] = {
    val next = new Stream(that).setCompositeName(this, "translated", true)
    next.arbitrationFrom(this)
    next.payload := that
    next
  }

/** Change the payload's content type. The new type must have the same bit length as the current one.
  */
  def transmuteWith[T2 <: Data](that: HardType[T2]) = {
    val next = new Stream(that).setCompositeName(this, "transmuted", true)
    next.arbitrationFrom(this)
    next.payload.assignFromBits(this.payload.asBits)
    next
  }


  def swapPayload[T2 <: Data](that: HardType[T2]) = {
    val next = new Stream(that).setCompositeName(this, "swap", true)
    next.arbitrationFrom(this)
    next
  }


  /** A combinatorial stage doesn't do anything, but it is nice to separate signals for combinatorial transformations.
  */
  def combStage() : Stream[T] = {
    val ret = Stream(payloadType).setCompositeName(this, "combStage", true)
    ret << this
    ret
  }

  /** Connect this to a valid/payload register stage and return its output stream.
    * 
    * The cost is `(payload width + 1)` flip-flops and the latency is 1.
    * 
    * Equivalent to [[m2sPipe()]] but with "stage" name in the generated HDL.
    * @see [[https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Libraries/stream.html#functions stream documentation]]
    */
  def stage() : Stream[T] = this.m2sPipe().setCompositeName(this, "stage", true)

  /**
   * Delay the stream by a given number of cycles.
   * @param cycleCount Number of cycles to delay the stream
   * @return Delayed stream
   */
  def delay(cycleCount: Int): Stream[T] = {
    cycleCount match {
      case 0 => this
      case _ => this.stage().delay(cycleCount - 1)
    }
  }

  // ! if collapsBubble is enable then ready is not "don't care" during valid low !
  /** Return a stream that cut the `valid` and `payload` signals through registers.
    * 
    * The cost is `(payload width + 1)` flip-flops and the latency is 1.
    * 
    * The name "m2s" comes from from the fact that the signals that flow
    * from Master-to-Slave are pipelined  (namely `ready` and `payload`).
    * 
    * @param collapsBubble When `true`(the default), add the logic to allow to store an incoming payload when there is 
    *                      no stored payload and the slave is not ready.
    * @param crossClockData If `false`(the default), do not add tags on the payload signal for clock domain crossing.
    * @param flush An optional signal to set the `valid` register to 0.
    * @param holdPayload When `false`(the default), do not add the logic to keep the slave side payload constant after the one cycle
    *                    when the slave consumed the payload.
    * @param keep If `false`(the default), do not add an attribute to avoid optimization of the slave side valid and payload.
    * @param initPayload If not `null`, a value to initialize the payload registers.
    * 
    * @see [[stage()]]
    * @see [[https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Libraries/stream.html#functions stream documentation]]
    */
  def m2sPipe(collapsBubble : Boolean = true, crossClockData: Boolean = false, flush : Bool = null, holdPayload : Boolean = false, keep : Boolean = false, initPayload : => T = null.asInstanceOf[T]): Stream[T] = new Composite(this) {
    val m2sPipe = Stream(payloadType)

    val rValid = RegNextWhen(self.valid, self.ready) init(False)
    val rData = RegNextWhen(self.payload, if(holdPayload) self.fire else self.ready) initNull(initPayload)
    if (keep) KeepAttribute.apply(rValid, rData)

    if (crossClockData) {
      rData.addTag(crossClockDomain)
      rData.addTag(crossClockMaxDelay(1, useTargetClock = true))
    }
    if (flush != null) rValid clearWhen(flush)

    self.ready := m2sPipe.ready
    if (collapsBubble) self.ready setWhen(!m2sPipe.valid)

    m2sPipe.valid := rValid
    m2sPipe.payload := rData
  }.m2sPipe

  /** Return a stream that cut the `ready` path through a register.
    * 
    * As long as the slave is ready, the `valid` and `payload` signal are passed without registering.
    * When the slave `ready` goes to low, the payload is stored and will be consumed later at the 
    * first cycle of `ready` to high.
    * 
    * The cost is `payload width + 1` flip-flops and `payload width` mux2. The latency is 0.
    *     
    * The name "s2m" comes from from the fact that the signal that flows
    * from Slave-to-Master is pipelined (namely `valid`).
    * 
    * @param flush An optional signal to set the `valid` register to 0.
    * @param keep If `false`(the default), do not add an attribute to avoid optimization of the slave side valid and payload signals. 
    * @see [[https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Libraries/stream.html#functions stream documentation]]
    */
  def s2mPipe(flush : Bool = null, keep : Boolean = false, savePower: Boolean = false): Stream[T] = new Composite(this) {
    val s2mPipe = Stream(payloadType)

    val rValidN = RegInit(True) clearWhen(self.valid) setWhen(s2mPipe.ready)
    val rData = RegNextWhen(self.payload, if(savePower) self.fire && !s2mPipe.ready else self.ready)
    if (keep) KeepAttribute.apply(rValidN, rData)

    self.ready := rValidN

    s2mPipe.valid := self.valid || !rValidN
    s2mPipe.payload := Mux(rValidN, self.payload, rData)

    if(flush != null) rValidN.setWhen(flush)
  }.s2mPipe

  def s2mPipe(stagesCount : Int): Stream[T] = {
    stagesCount match {
      case 0 => this
      case _ => this.s2mPipe().s2mPipe(stagesCount-1)
    }
  }

  def validPipe(keep : Boolean = false) : Stream[T] = new Composite(this) {
    val validPipe = Stream(payloadType)

    val rValid = RegInit(False) setWhen(self.valid) clearWhen(validPipe.fire)
    if (keep) KeepAttribute.apply(rValid)

    self.ready := validPipe.fire

    validPipe.valid := rValid
    validPipe.payload := self.payload
  }.validPipe

  /** Return a [[Stream]] that cut all path, but divide the bandwidth by 2.
    * 
    * The cost is `(payload width + 2)` flip-flops and the latency is 1.
    */
  def halfPipe(flush : Bool = null, keep : Boolean = false): Stream[T] = new Composite(this) {
    val halfPipe = Stream(payloadType)

    val rValid = RegInit(False) setWhen(self.valid) clearWhen(halfPipe.fire)
    val rData = RegNextWhen(self.payload, self.ready)
    if (keep) KeepAttribute.apply(rValid, rData)

    self.ready := !rValid

    halfPipe.valid := rValid
    halfPipe.payload := rData

    if(flush != null) rValid clearWhen(flush)
  }.halfPipe

/** Block this when cond is `False`. Return the resulting [[Stream]]
  */
  def continueWhen(cond: Bool): Stream[T] = {
    val next = new Stream(payloadType)
    next.valid := this.valid && cond
    this.ready := next.ready && cond
    next.payload := this.payload
    next.setCompositeName(this, "continueWhen", true)
  }

  /**
   * Discard transactions when cond is `True`.
   *
   * This is the same as [[throwWhen()]] but with a semantically clearer function name.
   * Prefer [[discardWhen()]] over [[throwWhen()]] for new designs.
   *
   * @param cond Condition
   *
   * @return The resulting Stream
   */
  def discardWhen(cond: Bool): Stream[T] = {
    this throwWhen(cond)
  }

/** Drop transactions of this when cond is `True` and return the resulting [[Stream]].
  */
  def throwWhen(cond: Bool): Stream[T] = {
    val next = Stream(payloadType).setCompositeName(this, "thrown", true)

    next << this
    when(cond) {
      next.valid := False
      this.ready := True
    }
    next.setCompositeName(this, "throwWhen", true)
  }

  def clearValidWhen(cond : Bool): Stream[T] = {
    val next = Stream(payloadType).setCompositeName(this, "clearValidWhen", true)
    next.valid := this.valid && !cond
    next.payload := this.payload
    this.ready := next.ready
    next
  }

  /** Stop transactions on this when cond is `True` and return the resulting [[Stream]]. */
  def haltWhen(cond: Bool): Stream[T] = continueWhen(!cond).setCompositeName(this, "haltWhen", true)

  /** Drop transaction of this when cond is `False` and return the resulting [[Stream]]. */
  def takeWhen(cond: Bool): Stream[T] = throwWhen(!cond).setCompositeName(this, "takeWhen", true)


  def fragmentTransaction(bitsWidth: Int): Stream[Fragment[Bits]] = {
    val converter = new StreamToStreamFragmentBits(payload, bitsWidth)
    converter.io.input << this
    converter.io.output
  }
  
  /** Convert this [[Stream]] to a fragmented [[Stream]] by adding a last bit. 
    * 
    * To view it from another perspective, bundle together successive events as fragments of a larger whole.
    * You can then use enhanced operations on fragmented streams, like reducing of elements.
    */
  def addFragmentLast(last : Bool) : Stream[Fragment[T]] = {
    val ret = Stream(Fragment(payloadType))
    ret.arbitrationFrom(this)
    ret.last := last
    ret.fragment := this.payload
    ret.setCompositeName(this, "addFragmentLast", true)
  }
  
  /** Like addFragmentLast(Bool), but instead of manually telling which values go together,
    * let a counter do the job. 
    * 
    * The counter will increment for each passing element. Last
    * will be set high at the end of each revolution.
	  * @example {{{ outStream = inStream.addFragmentLast(new Counter(5)) }}}
    */
  def addFragmentLast(counter: Counter) : Stream[Fragment[T]] = {
    when (this.fire) {
      counter.increment()
    }
    val last = counter.willOverflowIfInc
    addFragmentLast(last)
  }
  
  def setIdle(): this.type = {
    this.valid := False
    this.payload.assignDontCare()
    this
  }
  
  def setBlocked(): this.type = {
    this.ready := False
    this
  }

  def forkSerial(cond : Bool): Stream[T] = new Composite(this, "forkSerial"){
    val next = Stream(payloadType)
    next.valid := self.valid
    next.payload := self.payload
    self.ready := next.ready && cond
  }.next

  override def getTypeString = getClass.getSimpleName + "[" + this.payload.getClass.getSimpleName + "]"

  def assertPersistence(): Unit = {
    assert(!(valid.fall(False) && !RegNext(ready).init(False)), "Stream valid persistence failed")
    val checkIt = RegNext(isStall) init(False)
    val ref = RegNext(payload)
    when(checkIt){
      assert(payload === ref, "Stream payload persistence failed")
    }
  }

  /**
   * Assert that this stream conforms to the stream semantics:
   * https://spinalhdl.github.io/SpinalDoc-RTD/dev/SpinalHDL/Libraries/stream.html#semantics
   * - After being asserted, valid may only be deasserted once the current payload was acknowledged.
   *
   * @param payloadInvariance Check that the payload does not change when valid is high and ready is low.
   */
  def formalAssertsMaster(payloadInvariance : Boolean = true)(implicit loc : Location) = new Composite(this, "asserts") {
    import spinal.core.formal._
    val stack = ScalaLocated.long
    when(past(isStall) init(False)) {
      assert(valid,  "Stream transaction disappeared:\n" + stack)
      if(payloadInvariance) assert(stable(payload), "Stream transaction payload changed:\n" + stack)
    }
  }

  def formalAssumesSlave(payloadInvariance : Boolean = true)(implicit loc : Location) = new Composite(this, "assumes") {
    import spinal.core.formal._
    when(past(isStall) init (False)) {
      assume(valid)
      if(payloadInvariance) assume(stable(payload))
    }
  }

  def formalCovers(back2BackCycles: Int = 1) = new Composite(this, "covers") {
    import spinal.core.formal._
    val hist = History(fire, back2BackCycles).reduce(_ && _)
    cover(hist)
    cover(isStall)
    // doubt that if this is required in generic scenario.
    // cover(this.ready && !this.valid)
  }

  def formalAssertsOrder(dataAhead : T, dataBehind : T)(implicit loc : Location) : Tuple2[Bool, Bool] = new Composite(this, "orders")  {
    import spinal.core.formal._
    val aheadOut = RegInit(False) setWhen (fire && dataAhead === payload)
    val behindOut = RegInit(False) setWhen (fire && dataBehind === payload)

    when(!aheadOut){ assert(!behindOut) }
    when(behindOut){ assert(aheadOut) }

    cover(aheadOut)
    cover(behindOut)
    
    val out = (aheadOut, behindOut)
  }.out

  // flags if subjects have entered the StreamFifo
  def formalAssumesOrder(dataAhead : T, dataBehind : T)(implicit loc : Location) : Tuple2[Bool, Bool] = new Composite(this, "orders") {
    import spinal.core.formal._
    // flags indicates if the subjects went in the StreamFIfo
    val aheadIn = RegInit(False) setWhen (fire && dataAhead === payload)
    val behindIn = RegInit(False) setWhen (fire && dataBehind === payload)
    // once subject entered, prevent duplicate payloads from entering the StreamFifo
    when(aheadIn) { assume(payload =/= dataAhead) }
    when(behindIn) { assume(payload =/= dataBehind) }
    
    // make sure our two subjects are distinguishable (different)
    assume(dataAhead =/= dataBehind)
    // assume our subjects go inside the StreamFifo in correct order
    when(!aheadIn) { assume(!behindIn) }
    when(behindIn) { assume(aheadIn) }
    // return which subjects went in the StreamFifo
    val out = (aheadIn, behindIn)
  }.out

  /** Assert that this stream conforms to the stream semantics:
    * https://spinalhdl.github.io/SpinalDoc-RTD/dev/SpinalHDL/Libraries/stream.html#semantics
    * - After being asserted, valid should be acknowledged in limited cycles.
    *
    * @param maxStallCycles Check that the max cycles the interface would hold in stall.
    */
  def formalAssertsTimeout(maxStallCycles: Int = 0) = new Composite(this, "timeout") {
    import spinal.core.formal._
    val logic = (maxStallCycles > 0) generate new Area {
      val counter = Counter(maxStallCycles, isStall)
      when(!isStall) { counter.clear() }
        .otherwise { assert(!counter.willOverflow) }
    }
  }

  def formalAssumesTimeout(maxStallCycles: Int = 0) = new Composite(this, "timeout") {
    import spinal.core.formal._
    val logic = (maxStallCycles > 0) generate new Area {
      val counter = Counter(maxStallCycles, isStall)
      when(!isStall) { counter.clear() }
        .elsewhen(counter.willOverflow) { assume(ready === True) }
    }
  }

  def toReg() : T = toReg(null.asInstanceOf[T])
  def toReg(init: T): T = {
    this.ready := True
    RegNextWhen(this.payload,this.fire,init)
  }
}

object StreamArbiter {

  /** An Arbitration will choose which input stream to take at any moment. */
  sealed trait ArbitrationPolicy {
    def apply(core: StreamArbiter[_ <: Data]) = new Area {}
  }

  /**
   * The arbiter will always choose the lowest numbered valid input, equally to a fixed priority arbiter.
   */
  object LowerFirst extends ArbitrationPolicy {
    override def apply(core: StreamArbiter[_ <: Data]) = new Area {
      import core._
      maskProposal := OHMasking.first(Vec(io.inputs.map(_.valid)))
    }
  }

  /**
   * The arbiter will choose inputs in a sequential order.
   * This arbiter contains an implicit transactionLock
  */
  object SequentialOrder extends ArbitrationPolicy {
    override def apply(core: StreamArbiter[_ <: Data]) = new Area {
      import core._
      if(portCount > 1) {
        val counter = Counter(core.portCount, io.output.fire).setPartialName(this, "seqCounter")
        for (i <- 0 until core.portCount) {
          maskProposal(i) := False
        }
        maskProposal(counter) := True
      }
    }
  }

  /**
   * The arbiter will choose inputs in a round-robin fashion.
   */
  object RoundRobin extends ArbitrationPolicy {
    override def apply(core: StreamArbiter[_ <: Data]) = new Area {
      import core._
      if(maskLockFlagEnable) {
        for(bitId  <- maskLocked.range){
          maskLocked(bitId) init(Bool(bitId == maskLocked.length - 1))
        }
        //maskProposal := maskLocked
        maskProposal := OHMasking.roundRobin(Vec(io.inputs.map(_.valid)),Vec(maskLocked.last +: maskLocked.take(maskLocked.length - 1)))
      }
    }
  }

  /**
   * The arbiter will choose the valid input directly as the output.
   * This arbiter requires that only one input is valid at any given time.
  */
  object AssumeOhInput extends ArbitrationPolicy {
    override def apply(core: StreamArbiter[_ <: Data]) = new Area {
      import core._
      (maskProposal, io.inputs).zipped.map(_ := _.valid)
    }
  }

  /** When a lock activates, the currently chosen input won't change until it is released. */
  sealed trait LockPolicy {
    def apply(core: StreamArbiter[_ <: Data]) = new Area {}
  }

  /**
   * No lock is applied. The chosen input may change at any moment.
   */
  object NoLock extends LockPolicy {

  }

  /**
   * Many handshaking protocols require that once valid is set, it must stay asserted and the payload
   * must not change until the transaction fires, e.g. until ready is set as well. Since some arbitrations
   * may change their chosen input at any moment in time (which is not wrong), this may violate such
   * handshake protocols. Use this lock to be compliant in those cases.
   */
  object TransactionLock extends LockPolicy {
    override def apply(core: StreamArbiter[_ <: Data]) = new Area {
      import core._
      if(lockFlagEnable) {
        locked setWhen(io.output.valid)
        locked.clearWhen(io.output.fire)
      }
    }
  }

  /**
   * lock/unlock the output based on a user-defined function.
   */
  object SetLock extends LockPolicy {
    var logic: (StreamArbiter[_ <: Data]) => Area = _
    override def apply(core: StreamArbiter[_ <: Data]) = new Area {
      logic(core).setWeakName("setLock")
    }
  }

  /**
   * Unlock the output when output payload meets a user-defined criteria.
   */
  object LambdaLock extends LockPolicy {
    var unlock: Stream[_ <: Data] => Bool = _
    override def apply(core: StreamArbiter[_ <: Data]) = new Area {
      import core._
      if(lockFlagEnable) {
        locked setWhen(io.output.valid)
        locked.clearWhen(io.output.fire && unlock(io.output))
      }
    }
  }

  /**
   * This lock ensures that once a fragmented transaction is started, it will be finished without
   * interruptions from other streams. Without this, fragments of different streams will get intermingled.
   * This is only relevant for fragmented streams.
   */
  object FragmentLock extends LockPolicy {
    override def apply(core: StreamArbiter[_ <: Data]) = new Area {
      val realCore = core.asInstanceOf[StreamArbiter[Fragment[_]]]
      import realCore._
      if(lockFlagEnable) {
        locked setWhen(io.output.valid)
        locked.clearWhen(io.output.fire && io.output.last)
      }
    }
  }
}

/** Arbitrate from several [[Stream]] to one with various algorithms.
 *
 * A [[StreamArbiter]] is like a [[StreamMux]], but with built-in complex selection logic that can
 * arbitrate input streams based on a schedule or handle fragmented streams. 
 *
 * Use a [[StreamArbiterFactory]] to create instances of this class.
 * @see [[https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Libraries/stream.html#streamarbiter Stream documentation]]
 */
class StreamArbiter[T <: Data](dataType: HardType[T],
                              val portCount: Int,
                              val arbitrationPolicy: StreamArbiter.ArbitrationPolicy,
                              val lockPolicy: StreamArbiter.LockPolicy) extends Component {
  val io = new Bundle {
    val inputs = Vec(slave Stream (dataType),portCount)
    val output = master Stream (dataType)
    val chosen = out UInt (log2Up(portCount) bit)
    val chosenOH = out Bits (portCount bit)
  }
  import StreamArbiter._
  val lockFlagEnable = portCount > 1 && lockPolicy != NoLock && arbitrationPolicy != AssumeOhInput
  var maskLockFlagEnable = lockFlagEnable
  if(arbitrationPolicy == RoundRobin) maskLockFlagEnable = portCount > 1

  val locked = ifGen(lockFlagEnable)(RegInit(False))

  val maskProposal = Vec(Bool(),portCount)
  val maskLocked = ifGen(maskLockFlagEnable)(Reg(Vec(Bool(), portCount)))
  val maskRouted = if(lockFlagEnable) Mux(locked, maskLocked, maskProposal) else maskProposal


  if(maskLockFlagEnable) {
    when(io.output.valid) {
      maskLocked := maskRouted
    }
  }

  val arbitration = arbitrationPolicy(this)
  val lock = lockPolicy(this)

  val singlePort = (portCount == 1) generate new Area {
    io.output << io.inputs.head
    io.chosen := 0
    io.chosenOH := 1
  }
  val multiPort = (portCount > 1) generate new Area {
    io.output.valid := (io.inputs, maskRouted).zipped.map(_.valid & _).reduce(_ | _)
    io.output.payload := MuxOH(maskRouted,Vec(io.inputs.map(_.payload)))
    (io.inputs, maskRouted).zipped.foreach { case(input, mask) => input.ready := mask & io.output.ready }

    io.chosenOH := maskRouted.asBits
    io.chosen := OHToUInt(io.chosenOH)
  }
}

/** Build a [[StreamArbiter]] from a list of [[Stream]].
  *
  * example:
  * {{{
  *   val streamA, streamB, streamC = Stream(Bits(8 bits))
  *   val arbiteredABC = StreamArbiterFactory.roundRobin.onArgs(streamA, streamB, streamC)
  *   val streamD, streamE, streamF = Stream(Bits(8 bits))
  *   val arbiteredDEF = StreamArbiterFactory.lowerFirst.noLock.onArgs(streamD, streamE, streamF)
  * }}}
  *
  * @see [[https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Libraries/stream.html#streamarbiter Stream documentation]]
  */
class StreamArbiterFactory {
  import StreamArbiter._
  var arbitrationPolicy: ArbitrationPolicy = LowerFirst
  var lockPolicy: LockPolicy = TransactionLock

  def build[T <: Data](dataType: HardType[T], portCount: Int): StreamArbiter[T] = {
    new StreamArbiter(dataType, portCount, arbitrationPolicy, lockPolicy)
  }

  def buildOn[T <: Data](inputs : Seq[Stream[T]]): StreamArbiter[T] = {
    val a = new StreamArbiter(inputs.head.payloadType, inputs.size, arbitrationPolicy, lockPolicy)
    (a.io.inputs, inputs).zipped.foreach(_ << _)
    a
  }

  def buildOn[T <: Data](first : Stream[T], others : Stream[T]*): StreamArbiter[T] = {
    buildOn(first :: others.toList)
  }

  /** Build the arbitered [[Stream]] from a variable number [[Stream]] as arguments */
  def onArgs[T <: Data](inputs: Stream[T]*): Stream[T] = on(inputs.seq)

  /** Build the arbitered [[Stream]] from a `Seq` of [[Stream]] */
  def on[T <: Data](inputs: Seq[Stream[T]]): Stream[T] = {
    val arbiter = build(inputs(0).payloadType, inputs.size)
    (arbiter.io.inputs, inputs).zipped.foreach(_ << _)
    val ret = arbiter.io.output.combStage()
//    arbiter.setCompositeName(ret, "arbiter")
    ret
  }

  /** Configure the builder so lower ports have priority over higher ports */
  def lowerFirst: this.type = {
    arbitrationPolicy = LowerFirst
    this
  }

  /** Configure the builder for fair round-robin arbitration */
  def roundRobin: this.type = {
    arbitrationPolicy = RoundRobin
    this
  }

  /** Configure the builder to retrieve transaction in a sequential order.
    *
    * First transaction should come from port zero, then from port one, ...
    */
  def sequentialOrder: this.type = {
    arbitrationPolicy = SequentialOrder
    this
  }

  /** Configure the builder to assume that only one input is valid at any given time.
   * User is responsible to ensure this condition is met.
   */
  def assumeOhInput: this.type = {
    arbitrationPolicy = AssumeOhInput
    this
  }

  /** Configure the builder so the port selection could change based on user-defined logic.
   */
  def setLock(body : (StreamArbiter[_ <: Data]) => Area) : this.type = {
    SetLock.logic = body
    lockPolicy = SetLock
    this
  }

  /** Configure the builder so the port selection could change every cycle,
    * even if the transaction on the selected port is not consumed.
    */
  def noLock: this.type = {
    lockPolicy = NoLock
    this
  }

  /** Configure the builder so the port selection is locked until the selected port finish its burst (last=True).
   *
   * Could be used to arbitrate `Stream[Fragment[T]]`.
   */
  def fragmentLock: this.type = {
    lockPolicy = FragmentLock
    this
  }

  /** Configure the builder so the port selection is locked until the transaction
   * on the selected port is consumed.
   */
  def transactionLock: this.type = {
    lockPolicy = TransactionLock
    this
  }

  /** Configure the builder so the locked selection is released until the output meets the given criteria.
   *
   */
  def lambdaLock[T <: Data](unlock: Stream[T] => Bool) : this.type = {
    LambdaLock.unlock = unlock.asInstanceOf[Stream[_ <: Data] => Bool]
    lockPolicy = LambdaLock
    this
  }
}

/**
 * This is equivalent to a StreamDemux, but with a counter attached to the port selector.
 */
// TODOTEST
object StreamDispatcherSequential {
  def apply[T <: Data](input: Stream[T], outputCount: Int): Vec[Stream[T]] = {
    val select = Counter(outputCount)
    when (input.fire) {
    select.increment()
    }
    StreamDemux(input, select, outputCount)
  }
}

/**
 * @deprecated Do not use
 */
// TODOTEST
object StreamDispatcherSequencial {
  def apply[T <: Data](input: Stream[T], outputCount: Int): Vec[Stream[T]] = {
    StreamDispatcherSequential(input, outputCount)
  }
}

/**
 * @deprecated Do not use. Use the companion object or a normal regular StreamMux instead.
 */
class StreamDispatcherSequencial[T <: Data](gen: HardType[T], n: Int) extends Component {
  val io = new Bundle {
    val input = slave Stream (gen)
    val outputs = Vec(master Stream (gen), n)
  }
  val counter = Counter(n, io.input.fire)

  if (n == 1) {
    io.input >> io.outputs(0)
  } else {
    io.input.ready := False
    for (i <- 0 to n - 1) {
      io.outputs(i).payload := io.input.payload
      when(counter =/= i) {
        io.outputs(i).valid := False
      } otherwise {
        io.outputs(i).valid := io.input.valid
        io.input.ready := io.outputs(i).ready
      }
    }
  }
}

/**
 * This is equivalent to a StreamMux, but with a counter attached to the port selector.
 */
// TODOTEST
object StreamCombinerSequential {
  def apply[T <: Data](inputs: Seq[Stream[T]]): Stream[T] = {
    val select = Counter(inputs.length)
    val stream = StreamMux(select, inputs)
    when (stream.fire) {
      select.increment()
    }
    stream
  }
}

/** Combine a stream and a flow to a new stream. If both input sources fire, the flow will be preferred. */
object StreamFlowArbiter {
  def apply[T <: Data](inputStream: Stream[T], inputFlow: Flow[T]): Flow[T] = {
    val output = cloneOf(inputFlow)

    output.valid := inputFlow.valid || inputStream.valid
    inputStream.ready := !inputFlow.valid
    output.payload := Mux(inputFlow.valid, inputFlow.payload, inputStream.payload)

    output
  }
}

//Give priority to the inputFlow
class StreamFlowArbiter[T <: Data](dataType: T) extends Area {
  val io = new Bundle {
    val inputFlow = slave Flow (dataType)
    val inputStream = slave Stream (dataType)
    val output = master Flow (dataType)
  }
  io.output.valid := io.inputFlow.valid || io.inputStream.valid
  io.inputStream.ready := !io.inputFlow.valid
  io.output.payload := Mux(io.inputFlow.valid, io.inputFlow.payload, io.inputStream.payload)
}

/**
 *  Multiplex multiple streams into a single one, always only processing one at a time.
 */
object StreamMux {
  def apply[T <: Data](select: UInt, inputs: Seq[Stream[T]]): Stream[T] = {
    val vec = Vec(inputs)
    StreamMux(select, vec)
  }

  def apply[T <: Data](select: UInt, inputs: Vec[Stream[T]]): Stream[T] = {
    val c = new StreamMux(inputs(0).payload, inputs.length)
    (c.io.inputs, inputs).zipped.foreach(_ << _)
    c.io.select := select
    c.io.output
  }

  /** joinSel joins the selection stream with the the output stream.
    * Making sure the selection stream is fully synchronized with the selected stream
    */
  def joinSel[T <: Data](select: Stream[UInt], inputs: Seq[Stream[T]]): Stream[T] = {
    val c = new StreamMux(inputs(0).payload, inputs.length)
    (c.io.inputs, inputs).zipped.foreach(_ << _)
    c.io.select := select.payload
    StreamJoin(c.io.output, select).map(_._1)
  }

  /** regSel select uses haltWhen on the selection stream, thus making sure it is only consumed when data is selected.
    * Caution: the other direction is not synchronized. (input valid without selection stream valid). See StreamMux.joinSel for a fully synchronized version.
    */
  def regSel[T <: Data](select: Stream[UInt], inputs: Seq[Stream[T]]): Stream[T] = {
    val c = new StreamMux(inputs(0).payload, inputs.length)
    (c.io.inputs, inputs).zipped.foreach(_ << _)
    select >> c.io.createStreamRegSelect()
    c.io.output
  }
}

class StreamMux[T <: Data](dataType: T, portCount: Int) extends Component {
  val io = new Bundle {
    val select = in UInt (log2Up(portCount) bit)
    val inputs = Vec(slave Stream (dataType), portCount)
    val output = master Stream (dataType)
    def createStreamRegSelect(): Stream[UInt] = new Composite(this, "selector") {
      val stream = Stream(cloneOf(select))
      val reg = stream.haltWhen(output.isStall).toReg(U(0))
      select := reg
    }.stream
  }
  for ((input, index) <- io.inputs.zipWithIndex) {
    input.ready := io.select === index && io.output.ready
  }
  io.output.valid := io.inputs(io.select).valid
  io.output.payload := io.inputs(io.select).payload
}

//TODOTEST
/** 
 *  Demultiplex one stream into multiple output streams, always selecting only one at a time.
 */
object StreamDemux{
  def apply[T <: Data](input: Stream[T], select : UInt, portCount: Int) : Vec[Stream[T]] = {
    val c = new StreamDemux(input.payload,portCount)
    c.io.input << input
    c.io.select := select
    c.io.outputs
  }

  /** regSel select uses haltWhen on the selection stream, thus making sure it is only consumed when data is selected.
    * Caution: the other direction is not synchronized. (input valid without selection stream valid). See StreamDemux.joinSel for a fully synchronized version.
    */
  def regSel[T <: Data](input: Stream[T], select: Stream[UInt], portCount: Int): Vec[Stream[T]] = {
    val c = new StreamDemux(input.payload, portCount)
    c.io.input << input
    select >> c.io.createStreamRegSelect()
    c.io.outputs
  }

  /** joinSel joins the selection stream with the the input stream.
    * Making sure the selection stream is synchronized with the input stream
    * If the select stream payload is out of range for the port count it will stall forever.
    */
  def joinSel[T <: Data](input: Stream[T], select: Stream[UInt], portCount: Int): Vec[Stream[T]] = {
    val c = new StreamDemux(input.payload, portCount)
    val joined = StreamJoin(input, select)
    c.io.input << joined.map(_._1)
    c.io.select := joined._2
    c.io.outputs
  }

  def two[T <: Data](input: Stream[T], select : UInt) : (Stream[T], Stream[T]) = {
    val demux = apply(input, select, 2)
    (demux(0).combStage(), demux(1).combStage())
  }
  def two[T <: Data](input: Stream[T], select : Bool) : (Stream[T], Stream[T]) = two(input, select.asUInt)
  def two[T <: Data](input: Stream[T], select : Stream[UInt]) : (Stream[T], Stream[T]) = {
    val demux = joinSel(input, select, 2)
    (demux(0).combStage(), demux(1).combStage())
  }
}

class StreamDemux[T <: Data](dataType: T, portCount: Int) extends Component {
  val io = new Bundle {
    val select = in UInt (log2Up(portCount) bit)
    val input = slave Stream (dataType)
    val outputs = Vec(master Stream (dataType),portCount)
    def createStreamRegSelect(): Stream[UInt] = new Composite(this, "selector") {
      val stream = Stream(cloneOf(select))
      val reg = stream.haltWhen(input.isStall).toReg(U(0))
      select := reg
    }.stream
  }
  io.input.ready := False
  for (i <- 0 to portCount - 1) {
    io.outputs(i).payload := io.input.payload
    when(i =/= io.select) {
      io.outputs(i).valid := False
    } otherwise {
      io.outputs(i).valid := io.input.valid
      io.input.ready := io.outputs(i).ready
    }
  }
}

object StreamDemuxOh{
  def apply[T <: Data](input : Stream[T], oh : Seq[Bool]) : Vec[Stream[T]] = oh.size match {
    case 1 => Vec(input.combStage())
    case _ => {
      val ret = Vec(oh.map{sel =>
        val output = cloneOf(input)
        output.valid   := input.valid && sel
        output.payload := input.payload
        output
      })
      input.ready    := (ret, oh).zipped.map(_.ready && _).orR
      ret
    }
  }
}

object StreamFork {
  def apply[T <: Data](input: Stream[T], portCount: Int, synchronous: Boolean = false): Vec[Stream[T]] = {
    val fork = new StreamFork(input.payloadType, portCount, synchronous).setCompositeName(input, "fork", true)
    fork.io.input << input
    fork.io.outputs
  }
}

object StreamFork2 {
  def apply[T <: Data](input: Stream[T], synchronous: Boolean = false): (Stream[T], Stream[T]) = {
    val fork = new StreamFork(input.payloadType, 2, synchronous).setCompositeName(input, "fork2", true)
    fork.io.input << input
    (fork.io.outputs(0), fork.io.outputs(1))
  }

  def takes[T <: Data](input: Stream[T],take0 : Bool, take1 : Bool, synchronous: Boolean = false): (Stream[T], Stream[T]) = new Composite(input, "fork2") {
    val forks = (cloneOf(input), cloneOf(input))
    val logic = new StreamForkArea(input, List(forks._1, forks._2), synchronous)
    val outputs = (forks._1.takeWhen(take0), forks._2.takeWhen(take1))
  }.outputs
}

object StreamFork3 {
  def apply[T <: Data](input: Stream[T], synchronous: Boolean = false): (Stream[T], Stream[T], Stream[T]) = {
    val fork = new StreamFork(input.payloadType, 3, synchronous).setCompositeName(input, "fork3", true)
    fork.io.input << input
    (fork.io.outputs(0), fork.io.outputs(1), fork.io.outputs(2))
  }
}

/**
 * A StreamFork will clone each incoming data to all its output streams. If synchronous is true,
 *  all output streams will always fire together, which means that the stream will halt until all 
 *  output streams are ready. If synchronous is false, output streams may be ready one at a time,
 *  at the cost of an additional flip flop (1 bit per output). The input stream will block until
 *  all output streams have processed each item regardlessly.
 *  
 *  Note that this means that when synchronous is true, the valid signal of the outputs depends on
 *  their inputs, which may lead to dead locks when used in combination with systems that have it the
 *  other way around. It also violates the handshake of the AXI specification (section A3.3.1).
 */
//TODOTEST
class StreamFork[T <: Data](dataType: HardType[T], portCount: Int, synchronous: Boolean = false) extends Component {
  val io = new Bundle {
    val input = slave Stream (dataType)
    val outputs = Vec(master Stream (dataType), portCount)
  }
  val logic = new StreamForkArea(io.input, io.outputs, synchronous)
}

class StreamForkArea[T <: Data](input : Stream[T], outputs : Seq[Stream[T]], synchronous: Boolean = false) extends Area {
  val portCount = outputs.size
  /*Used for async, Store if an output stream already has taken its value or not */
  val linkEnable = if(!synchronous && portCount > 1) Vec(RegInit(True), portCount) else null
  if (portCount == 1) {
    outputs.head << input
  } else if (synchronous) {
    input.ready := outputs.map(_.ready).reduce(_ && _)
    outputs.foreach(_.valid := input.valid && input.ready)
    outputs.foreach(_.payload := input.payload)
  } else {
    /* Ready is true when every output stream takes or has taken its value */
    input.ready := True
    for (i <- 0 until portCount) {
      when(!outputs(i).ready && linkEnable(i)) {
        input.ready := False
      }
    }

    /* Outputs are valid if the input is valid and they haven't taken their value yet.
     * When an output fires, mark its value as taken. */
    for (i <- 0 until portCount) {
      outputs(i).valid := input.valid && linkEnable(i)
      outputs(i).payload := input.payload
      when(outputs(i).fire) {
        linkEnable(i) := False
      }
    }

    /* Reset the storage for each new value */
    when(input.ready) {
      linkEnable.foreach(_ := True)
    }
  }
}


case class EventEmitter(on : Event){
  val reg = RegInit(False)
  when(on.ready){
    reg := False
  }
  on.valid := reg

  def emit(): Unit ={
    reg := True
  }
}

/** Join multiple streams into one. The resulting stream will only fire if all of them fire, so you may want to buffer the inputs. */
object StreamJoin {
  
  /**
   * Convert a tuple of streams into a stream of tuples
   */
  def apply[T1 <: Data,T2 <: Data](source1: Stream[T1], source2: Stream[T2]): Stream[TupleBundle2[T1, T2]] = {
    val sources = Seq(source1, source2)
    val combined = Stream(TupleBundle2(
        source1.payloadType,
        source2.payloadType
    ))
    combined.valid := sources.map(_.valid).reduce(_ && _)
    sources.foreach(_.ready := combined.fire)
    combined.payload._1 := source1.payload
    combined.payload._2 := source2.payload
    combined
  }

  /**
   * Convert a vector of streams into a stream of vectors.
   */
  def vec[T <: Data](sources: Seq[Stream[T]]): Stream[Vec[T]] = {
    val payload = Vec(sources.map(_.payload))
    val combined = Stream(payload)
    combined.payload := payload
    combined.valid := sources.map(_.valid).reduce(_ && _)
    sources.foreach(_.ready := combined.fire)
    combined
  }
  
  def arg(sources : Stream[_]*) : Event = apply(sources.seq)

  /** Join streams, but ignore the payload of the input streams. */
  def apply(sources: Seq[Stream[_]]): Event = {
    val event = Event
    val eventFire = event.fire
    event.valid := sources.map(_.valid).reduce(_ && _)
    sources.foreach(_.ready := eventFire)
    event
  }
  
  /**
   * Join streams, but ignore the payload and replace it with a custom one.
   * @param payload The payload of the resulting stream
   */
  def fixedPayload[T <: Data](sources: Seq[Stream[_]], payload: T): Stream[T] = StreamJoin(sources).translateWith(payload)
}

trait StreamFifoInterface[T <: Data]{
  def push          : Stream[T]
  def pop           : Stream[T]
  def pushOccupancy : UInt
  def popOccupancy  : UInt
}

object StreamFifo{
  def apply[T <: Data](dataType: HardType[T],
                       depth: Int,
                       latency: Int = 2,
                       forFMax: Boolean = false,
                       initPayload: => Option[T] = None): StreamFifo[T] = {
    assert(latency >= 0 && latency <= 2)
    new StreamFifo(
      dataType,
      depth,
      withAsyncRead = latency < 2,
      withBypass = latency == 0,
      forFMax = forFMax,
      initPayload = initPayload
    )
  }

  /** Typed depth entry point; ordinary literal ElabInt values stay concrete. */
  def apply[T <: Data](
      dataType: HardType[T],
      depth: ElabInt
  ): StreamFifo[T] =
    apply(
      dataType,
      depth,
      withAsyncRead = false,
      withBypass = false,
      allowExtraMsb = true,
      forFMax = false,
      useVec = false,
      initPayload = None
    )

  /** Full typed-depth entry point for the native StreamFifo configuration.
    * The ordinary constructor remains the sole FIFO algorithm; this factory
    * only supplies a definition-side formal when symbolic capture needs one.
    */
  def apply[T <: Data](
      dataType: HardType[T],
      depth: ElabInt,
      withAsyncRead: Boolean,
      withBypass: Boolean,
      allowExtraMsb: Boolean,
      forFMax: Boolean,
      useVec: Boolean,
      initPayload: => Option[T]
  ): StreamFifo[T] = {
    if (depth == null)
      throw new IllegalArgumentException("StreamFifo typed depth must not be null")
    if (depth.isConcrete)
      new StreamFifo(
        dataType,
        depth.witness,
        withAsyncRead,
        withBypass,
        allowExtraMsb,
        forFMax,
        useVec,
        initPayload
      )
    else if (!ParameterizedStructure.captureEnabled)
      new StreamFifo(
        dataType,
        ElabInt.literal(depth.witness),
        withAsyncRead,
        withBypass,
        allowExtraMsb,
        forFMax,
        useVec,
        initPayload
      )
    else
      ElabFormalComponent.parameter(
        actual = depth,
        name = "DEPTH",
        // The definition implements depth one even when this particular
        // caller's actual has a narrower lower bound.  Keeping that declared
        // domain prevents caller-specific folding from moving shared FIFO
        // infrastructure outside its definition-local storage alternative.
        minimum = BigInt(1),
        maximum = depth.maximum
      )(formal =>
        new StreamFifo(
          dataType,
          formal,
          withAsyncRead,
          withBypass,
          allowExtraMsb,
          forFMax,
          useVec,
          initPayload
        )
      )
  }

}

/** First-In-First-Out queue with a `push` and `pop` [[Stream]]
  *   
  * - latency of 0, 1, 2 cycles
  *
  * Fully redesigned in release 1.8.2 allowing improved timing closure.
  * @param dataType
  * @param depth Number of element stored in the fifo, Note that if `withAsyncRead==false`,
  *              then one extra transaction can be stored.
  * @param withAsyncRead Read the memory using asynchronous read port (ex distributed ram).
  *                      If false, add 1 cycle latency.
  * @param withBypass Bypass the push port to the pop port when the fifo is empty.If false, add 
  *                   1 cycle latency. Only available if `withAsyncRead == true`.
  * @param forFMax Tune the design to get the maximal clock frequency.
  * @param useVec Use an Vec of register instead of a Mem to store the content
  *               Only available if `withAsyncRead == true`.
  * @param initPayload Initialize the `Vec` of register with the initial value.
  * 
  * @see [[StreamFifoCC]] and [[StreamCCByToggle]] for cross clock domain FIFOs
  */
class StreamFifo[T <: Data](val dataType: HardType[T],
    private[lib] val elabDepth: ElabInt,
    val withAsyncRead: Boolean,
    val withBypass: Boolean,
    val allowExtraMsb: Boolean,
    val forFMax: Boolean,
    val useVec: Boolean,
    initPayload: => Option[T]
) extends Component {

  /** Source- and binary-compatible witness accessor; logic uses elabDepth. */
  val depth: Int = elabDepth.witness

  def this(
      dataType: HardType[T],
      depth: Int,
      withAsyncRead : Boolean = false,
      withBypass : Boolean = false,
      allowExtraMsb : Boolean = true,
      forFMax : Boolean = false,
      useVec : Boolean = false,
                            initPayload : => Option[T] = None) =
    this(
      dataType,
      ElabInt.literal(depth),
      withAsyncRead,
      withBypass,
      allowExtraMsb,
      forFMax,
      useVec,
      initPayload
    )

  require(elabDepth >= 0)

  if(withBypass) require(withAsyncRead)
  if(useVec) require (withAsyncRead)

  val io = new Bundle with StreamFifoInterface[T]{
    val push = slave Stream (dataType)
    val pop = master Stream (dataType)
    val flush = in Bool() default(False)
    // Preserve the legacy concrete depth-zero boundary, whose occupancy shape
    // is a native zero-bit UInt. Symbolic FIFO formals remain positive.
    val occupancy    = if (elabDepth.isConcrete) {
      out UInt (log2Up(depth + 1) bits)
    } else {
      out UInt ((elabDepth + 1).addressWidth bits)
    }
    val availability = if (elabDepth.isConcrete) {
      out UInt (log2Up(depth + 1) bits)
    } else {
      out UInt ((elabDepth + 1).addressWidth bits)
    }
    override def pushOccupancy = occupancy
    override def popOccupancy = occupancy
  }

  class CounterUpDownFmax(states : ElabInt, initValue: ElabInt) extends Area {
    def this(states: BigInt, init : BigInt) =
      this(ElabInt.fromBigInt(states), ElabInt.fromBigInt(init))

    val incr, decr = Bool()
    private val isConcrete = states.isConcrete && initValue.isConcrete
    val value = if (isConcrete) {
      Reg(UInt(log2Up(states.constantBigInt("CounterUpDownFmax states")) bits)) init(
        initValue.constantBigInt("CounterUpDownFmax initial value")
      )
    } else {
      Reg(UInt(log2Up(states) bits))
    }
    val initial = if (isConcrete) null else {
      ElabValue.uintLike(initValue, value, "typed_counter_initial")
    }
    if (!isConcrete) value init(initial)
    val plusOne = KeepAttribute(value + 1)
    val minusOne = KeepAttribute(value - 1)
    when(incr =/= decr){
      value := incr.mux(plusOne, minusOne)
    }
    when(io.flush) {
      if (isConcrete)
        value := initValue.constantBigInt("CounterUpDownFmax initial value")
      else
        value := initial
    }
  }

  private[lib] val elabWithExtraMsb: ElabBool = elabDepth.isPow2 && allowExtraMsb

  /** Source-compatible concrete witness; logic uses elabWithExtraMsb. */
  val withExtraMsb: Boolean = elabWithExtraMsb.witness
  val depthIsZero: ElabBool = elabDepth == 0
  val depthIsOne: ElabBool = elabDepth == 1
  val depthHasStorage: ElabBool = elabDepth > 1
  // Symbolic generate executes every native alternative once, even though its
  // source-compatible return value is null outside the selected witness. Keep
  // exact native references and capture-owner tokens explicitly so formal
  // helpers added later can extend the original branch without discovering it
  // from a source position, component name or emitted identifier.
  private var formalBypassOwner: ParameterizedStructuralOwner = null
  private var formalOneStageOwner: ParameterizedStructuralOwner = null
  private var formalOneStageBuffer: Stream[T] = null
  private var formalStorageOwner: ParameterizedStructuralOwner = null
  private var formalStorageVec: Vec[T] = null
  private var formalStorageRam: Mem[T] = null
  private var formalStoragePush: UInt = null
  private var formalStoragePop: UInt = null
  private var formalStorageEmpty: Bool = null

  val bypass = depthIsZero generate new Area {
    if (ParameterizedStructure.captureEnabled && !elabDepth.isConcrete)
      formalBypassOwner = ParameterizedStructure.currentOwner(
        elabDepth,
        "StreamFifo depth-zero branch owner"
      )
    io.push >> io.pop
    io.occupancy := 0
    io.availability := 0
  }
  val oneStage = depthIsOne generate new Area {
    if (ParameterizedStructure.captureEnabled && !elabDepth.isConcrete)
      formalOneStageOwner = ParameterizedStructure.currentOwner(
        elabDepth,
        "StreamFifo one-stage branch owner"
      )
    val doFlush = CombInit(io.flush)
    val buffer = initPayload match {
      case Some(initValue) => io.push.m2sPipe(flush = doFlush, initPayload = initValue)
      case None => io.push.m2sPipe(flush = doFlush)
    }
    io.pop << buffer
    io.occupancy := U(buffer.valid)
    io.availability := U(!buffer.valid)

    if(withBypass){
      when(!buffer.valid){
        io.pop.valid := io.push.valid
        io.pop.payload := io.push.payload
        doFlush setWhen(io.pop.ready)
      }
    }
    formalOneStageBuffer = buffer
  }
  val logic = depthHasStorage generate new Area {
    if (ParameterizedStructure.captureEnabled && !elabDepth.isConcrete)
      formalStorageOwner = ParameterizedStructure.currentOwner(
        elabDepth,
        "StreamFifo storage branch owner"
      )
    val vec = useVec generate {
      initPayload match {
        case Some(initValue) => Vec(Reg(dataType) init (initValue), elabDepth)
        case None => Vec(Reg(dataType), elabDepth)
      }
    }
    val ram = !useVec generate Mem(dataType, elabDepth)

    val ptr = new Area{
      val doPush, doPop = Bool()
      val full, empty = Bool()
      val pointerWidth: ElabInt = log2Up(elabDepth) + elabWithExtraMsb.toElabInt
      val push = Reg(UInt(pointerWidth bits)) init(0)
      val pop  = Reg(UInt(pointerWidth bits)) init(0)
      val occupancy = UInt(log2Up(elabDepth + 1) bits)
      val popOnIo = ParameterizedWidth.cloneOf(pop) // Used to track the global occupancy of the fifo (the extra buffer of !withAsyncRead)
      val wentUp = RegNextWhen(doPush, doPush =/= doPop) init(False) clearWhen (io.flush)

      val arb = new Area {
        val area = !forFMax generate {
          if (elabDepth.isConcrete) {
            withExtraMsb match {
              case true => { //as we have extra MSB, we don't need the "wentUp"
                full := (push ^ popOnIo ^ depth) === 0
                empty := push === pop
              }
              case false => {
                full := push === popOnIo && wentUp
                empty := push === pop && !wentUp
              }
            }
          } else {
            // Keep the pointer-width operands under their common storage owner.
            // The parameter predicate is hardware-constant after specialization,
            // while avoiding a narrower nested construction representative.
            val extraMsb = ElabValue
              .uintLike(
                elabWithExtraMsb.toElabInt,
                io.flush.asUInt,
                "typed_extra_msb"
              )
              .asBool
            val depthValue = ElabValue.uintLike(elabDepth, push, "typed_depth_xor")
            when(extraMsb) { //as we have extra MSB, we don't need the "wentUp"
                full := (push ^ popOnIo ^ depthValue) === 0
                empty := push === pop
              } otherwise {
                full := push === popOnIo && wentUp
                empty := push === pop && !wentUp
              }
          }
        }

        val fmax = forFMax generate new Area {
          val counterWidth: ElabInt = log2Up(elabDepth) + 1
          val emptyTracker = new CounterUpDownFmax(counterWidth.pow2, (counterWidth - 1).pow2) {
            incr := doPop
            decr := doPush
            empty := value.msb
          }

          val fullTracker = new CounterUpDownFmax(counterWidth.pow2, (counterWidth - 1).pow2 - elabDepth) {
            incr := io.push.fire
            decr := io.pop.fire
            full := value.msb
          }
        }
      }

      if (elabDepth.isConcrete) {
        when(doPush){
          push := push + 1
          if(!isPow2(depth)) when(push === depth - 1){ push := 0 }
        }
        when(doPop){
          pop := pop + 1
          if(!isPow2(depth)) when(pop === depth - 1){ pop := 0 }
        }
      } else {
        val depthIsNotPow2: ElabBool = !elabDepth.isPow2
        val wrapEnabled = ElabValue
          .uintLike(
            depthIsNotPow2.toElabInt,
            io.flush.asUInt,
            "typed_non_pow2_wrap"
          )
          .asBool
        val pushLast = ElabValue.uintLike(elabDepth - 1, push, "typed_push_last")
        val popLast = ElabValue.uintLike(elabDepth - 1, pop, "typed_pop_last")
        when(doPush){
          push := (push + 1).resized
          when(wrapEnabled && push === pushLast){ push := 0 }
        }
        when(doPop){
          pop := (pop + 1).resized
          when(wrapEnabled && pop === popLast){ pop := 0 }
        }
      }

      when(io.flush){
        push := U(0).resized
        pop := U(0).resized
      }


      val forPow2 = (elabWithExtraMsb && !forFMax) generate new Area{
        occupancy := push - popOnIo  //if no extra msb, could be U(full ## (push - popOnIo))
      }

      val notPow2 = (!elabWithExtraMsb && !forFMax) generate new Area{
        val counter = Reg(UInt(log2Up(elabDepth + 1) bits)) init(0)
        counter := counter + U(io.push.fire) - U(io.pop.fire)
        occupancy := counter

        when(io.flush) { counter := 0 }
      }
      val fmax = forFMax generate new CounterUpDownFmax(elabDepth + 1, ElabInt.literal(0)){
        incr := io.push.fire
        decr := io.pop.fire
        occupancy := value
      }
    }

    val push = new Area {
      io.push.ready := !ptr.full
      ptr.doPush := io.push.fire
      val onRam = !useVec generate new Area {
        val write = ram.writePort()
        write.valid := io.push.fire
        write.address := ptr.push.resize(log2Up(elabDepth))
        write.data := io.push.payload
      }
      val onVec = useVec generate new Area {
        if (elabDepth.isConcrete) {
          when(io.push.fire){
          vec.write(ptr.push.resize(log2Up(elabDepth)), io.push.payload)
          }
        } else {
          val writeIndex = UInt(log2Up(elabDepth) bits)
            .setName("typed_vec_write_index", weak = true)
            .dontSimplifyIt()
          writeIndex := ptr.push.resized
          val writeTarget = vec(writeIndex)
            .setName("typed_vec_write_target", weak = true)
            // Keep the authoritative Vec decoder guards at module scope while
            // retaining the native push-fire predicate and hold semantics
            // exactly: the shared direct source holds the selected register
            // unless the push fires.
          val writeData = dataType()
            .setName("typed_vec_write_data", weak = true)
            .dontSimplifyIt()
          writeData := writeTarget
          when(io.push.fire) {
            writeData := io.push.payload
          }
          writeTarget := writeData
        }
      }
    }

    val pop = new Area{
      val addressGen = Stream(UInt(log2Up(elabDepth) bits))
      addressGen.valid := !ptr.empty
      if (elabDepth.isConcrete) {
        addressGen.payload := ptr.pop.resize(log2Up(elabDepth))
      } else {
        val storagePopIndex = UInt(log2Up(elabDepth) bits)
          .setName("typed_storage_pop_index", weak = true)
          .dontSimplifyIt()
        storagePopIndex := ptr.pop.resized
        addressGen.payload := storagePopIndex
      }
      ptr.doPop := addressGen.fire

      val sync = !withAsyncRead generate new Area{
        assert(!useVec)
        val readArbitration = addressGen.m2sPipe(flush = io.flush)
        val readPort = ram.readSyncPort
        readPort.cmd := addressGen.toFlowFire
        io.pop << readArbitration.translateWith(readPort.rsp)

        val popReg = RegNextWhen(ptr.pop, readArbitration.fire) init(0)
        ptr.popOnIo := popReg
        when(io.flush){ popReg := 0 }
      }

      val async = withAsyncRead generate new Area{
        val readed = useVec match {
          case true =>
            val result = vec.read(addressGen.payload)
            if (!elabDepth.isConcrete)
              result
                .setName("typed_vec_read_data", weak = true)
                .dontSimplifyIt()
            result
          case false => ram.readAsync(addressGen.payload)
        }
        io.pop << addressGen.translateWith(readed)
        ptr.popOnIo := ptr.pop

        if(withBypass){
          when(ptr.empty){
            io.pop.valid := io.push.valid
            io.pop.payload := io.push.payload
            ptr.doPush clearWhen(io.pop.ready)
          }
        }
      }
    }

    io.occupancy := ptr.occupancy.resized
    if(!forFMax) {
      val depthValue = ElabValue.uintLike(elabDepth, ptr.occupancy, "typed_depth_availability")
      io.availability := (depthValue - ptr.occupancy).resized
    }
    val fmaxAvail = forFMax generate new CounterUpDownFmax(elabDepth + 1, elabDepth){
      incr := io.pop.fire
      decr := io.push.fire
      io.availability := value.resized
    }

    formalStorageVec = vec
    formalStorageRam = ram
    formalStoragePush = ptr.push
    formalStoragePop = ptr.pop
    formalStorageEmpty = ptr.empty
  }

  /** Mechanics adapter for the one authoritative formal-helper algorithm.
    *
    * Concrete helpers retain the inherited Scala finite enumeration, native
    * growing shift and active-witness branch selection. Symbolic helpers swap
    * only those mechanics for exact finite capture, typed mask geometry and
    * owner-local execution. Predicate application, last-push selection, RAM
    * mask decisions and folds remain in the shared bodies below.
    */
  private sealed trait FormalHelperAdapter {
    type Index
    type Conditions
    // Concrete keeps its native Vec mask; captured publication uses one flat
    // exact-width Bits mask so the shared decisions form one Verilog process.
    type Mask

    def conditions(
        role: String,
        stableName: String,
        existing: Option[Conditions]
    )(body: Index => Bool): Conditions
    def outerStorageConditions(stableName: String): Option[Conditions]
    def storagePayload(index: Index): T
    def selectCondition(
        condition: Conditions,
        index: UInt,
        stableName: String
    ): Bool
    def previousStorageIndex(pointer: UInt, stableName: String): UInt
    def checkedRam(
        target: Vec[Bool],
        mask: Mask,
        condition: Conditions
    )(combine: (Bool, Bool) => Bool): Vec[Bool]

    def newMask(stableName: String): Mask
    def assignMask(mask: Mask, value: Bits): Unit
    def maskIndex(pointer: UInt, stableName: String): UInt
    def maskOne(): UInt
    def shiftMaskOne(one: UInt, index: UInt, stableName: String): UInt
    def lowMask(value: UInt, stableName: String): Bits
    def stabilizeMask(value: Bits, stableName: String): Bits
    def clearMask(mask: Mask): Unit

    def assignBool(target: Bool, value: Bool): Bool
    def oneStageChecks(target: Vec[Bool], value: Bool): Vec[Bool]
    def emptyChecks(target: Vec[Bool]): Vec[Bool]

    def boolBranches(stableName: String)(
        storage: Bool => Bool,
        oneStageBranch: Bool => Bool,
        bypass: Bool => Bool
    ): Bool
    def ramBranches(stableName: String)(
        storage: Vec[Bool] => Vec[Bool],
        oneStageBranch: Vec[Bool] => Vec[Bool],
        bypass: Vec[Bool] => Vec[Bool]
    ): Vec[Bool]

    def reduceOr(checks: Vec[Bool]): Bool
    def countOne(checks: Vec[Bool]): UInt
    def growCountOperand(value: UInt, stableName: String): UInt
    def countResult(stableName: String, value: UInt): UInt
  }

  private val concreteFormalHelperAdapter = new FormalHelperAdapter {
    type Index = Int
    type Conditions = IndexedSeq[Bool]
    type Mask = Vec[Bool]

    override def conditions(
        role: String,
        stableName: String,
        existing: Option[IndexedSeq[Bool]]
    )(body: Int => Bool): IndexedSeq[Bool] =
      elabDepth.finiteRangeFromZero(role).map(body)

    override def outerStorageConditions(
        stableName: String
    ): Option[IndexedSeq[Bool]] = None

    override def storagePayload(index: Int): T =
      if (useVec) formalStorageVec(index) else formalStorageRam(index)

    override def selectCondition(
        condition: IndexedSeq[Bool],
        index: UInt,
        stableName: String
    ): Bool = condition(index.resized)

    override def previousStorageIndex(
        pointer: UInt,
        stableName: String
    ): UInt = {
      val depthValue = ElabValue.uintLike(
        elabDepth,
        pointer,
        "typed_formal_last_push_depth"
      )
      (pointer +^ depthValue -^ 1) % depthValue
    }

    override def checkedRam(
        target: Vec[Bool],
        mask: Vec[Bool],
        condition: IndexedSeq[Bool]
    )(combine: (Bool, Bool) => Bool): Vec[Bool] =
      Vec(mask.zipWithIndex.map { case (valid, index) =>
        combine(valid, condition(index))
      })

    override def newMask(stableName: String): Vec[Bool] =
      Vec(True, elabDepth)

    override def assignMask(mask: Vec[Bool], value: Bits): Unit =
      mask.assignFromBits(value)

    override def maskIndex(pointer: UInt, stableName: String): UInt =
      pointer.resize(log2Up(elabDepth))

    override def maskOne(): UInt = U(1)

    override def shiftMaskOne(
        one: UInt,
        index: UInt,
        stableName: String
    ): UInt = one << index

    override def lowMask(value: UInt, stableName: String): Bits = value.asBits

    override def stabilizeMask(value: Bits, stableName: String): Bits = value

    override def clearMask(mask: Vec[Bool]): Unit = mask := mask.getZero

    override def assignBool(target: Bool, value: Bool): Bool = value

    override def oneStageChecks(target: Vec[Bool], value: Bool): Vec[Bool] =
      Vec(value)

    override def emptyChecks(target: Vec[Bool]): Vec[Bool] = Vec[Bool](Seq())

    override def boolBranches(stableName: String)(
        storage: Bool => Bool,
        oneStageBranch: Bool => Bool,
        bypass: Bool => Bool
    ): Bool =
      if (logic != null) storage(null)
      else if (oneStage != null) oneStageBranch(null)
      else bypass(null)

    override def ramBranches(stableName: String)(
        storage: Vec[Bool] => Vec[Bool],
        oneStageBranch: Vec[Bool] => Vec[Bool],
        bypass: Vec[Bool] => Vec[Bool]
    ): Vec[Bool] =
      if (logic != null) storage(null)
      else if (oneStage != null) oneStageBranch(null)
      else bypass(null)

    override def reduceOr(checks: Vec[Bool]): Bool = checks.reduce(_ || _)

    override def countOne(checks: Vec[Bool]): UInt = CountOne(checks)

    override def growCountOperand(value: UInt, stableName: String): UInt =
      value.expand

    override def countResult(stableName: String, value: UInt): UInt = value
  }

  private val capturedFormalHelperAdapter = new FormalHelperAdapter {
    type Index = ElabFiniteIndex
    type Conditions = Vec[Bool]
    type Mask = Bits

    private var maskOrdinal = 0

    override def conditions(
        role: String,
        stableName: String,
        existing: Option[Vec[Bool]]
    )(body: ElabFiniteIndex => Bool): Vec[Bool] = {
      val result = existing.getOrElse(
        Vec(Bool(), elabDepth).setName(stableName)
      )
      ElabFiniteRange.foreach(elabDepth, role) { index =>
        index(result) := body(index)
      }
      result
    }

    override def outerStorageConditions(
        stableName: String
    ): Option[Vec[Bool]] =
      Some(Vec(Bool(), elabDepth).setName(stableName))

    override def storagePayload(index: ElabFiniteIndex): T =
      if (useVec) index(formalStorageVec) else index(formalStorageRam)

    override def selectCondition(
        condition: Vec[Bool],
        index: UInt,
        stableName: String
    ): Bool = condition.read(normalizedIndex(index, stableName))

    override def previousStorageIndex(
        pointer: UInt,
        stableName: String
    ): UInt = {
      val normalized = normalizedIndex(pointer, s"${stableName}_pointer")
      val lastIndex = ElabValue.uintLike(
        elabDepth - 1,
        normalized,
        s"${stableName}_last_index"
      )
      val one = ElabValue.uintLike(
        ElabInt.literal(1),
        normalized,
        s"${stableName}_one"
      )
      val decremented = ParameterizedWidth
        .copyShape(normalized, normalized - one)
        .setName(s"${stableName}_decremented", weak = true)
        .dontSimplifyIt()
      val result = UInt(elabDepth.addressWidth bits)
        .setName(stableName)
        .dontSimplifyIt()
      result := decremented
      when(normalized === 0) {
        result := lastIndex
      }
      result
    }

    override def checkedRam(
        target: Vec[Bool],
        mask: Bits,
        condition: Vec[Bool]
    )(combine: (Bool, Bool) => Bool): Vec[Bool] = {
      ElabFiniteRange.foreach(elabDepth, "stream_fifo_formal_ram_mask") { index =>
        index(target) := combine(index(mask), index(condition))
      }
      target
    }

    override def newMask(stableName: String): Bits = {
      maskOrdinal += 1
      val allOnesName = s"${stableName}_all_ones_$maskOrdinal"
      val result = Bits(elabDepth bits).setName(stableName, weak = true)
      val allOnes = ElabValue
        .uintAllOnes(elabDepth, s"${allOnesName}_zero")
        .setName(allOnesName, weak = true)
        .dontSimplifyIt()
      result := allOnes.asBits
      result
    }

    override def assignMask(mask: Bits, value: Bits): Unit =
      mask := value

    override def maskIndex(pointer: UInt, stableName: String): UInt =
      normalizedIndex(pointer, stableName)

    override def maskOne(): UInt = {
      val result = UInt(elabDepth bits)
        .setName("typed_formal_ram_mask_one", weak = true)
      result := 1
      result.setAsVital()
      result.dontSimplifyIt()
      result
    }

    override def shiftMaskOne(
        one: UInt,
        index: UInt,
        stableName: String
    ): UInt = {
      val result = ParameterizedWidth
        .copyShape(one, one |<< index)
        .setName(stableName, weak = true)
        .dontSimplifyIt()
      result
    }

    override def lowMask(value: UInt, stableName: String): Bits = {
      val resized = value
        .resize(elabDepth)
        .setName(s"${stableName}_uint", weak = true)
      resized.dontSimplifyIt()
      val result = resized.asBits.setName(stableName, weak = true)
      result.dontSimplifyIt()
      result
    }

    override def stabilizeMask(value: Bits, stableName: String): Bits = {
      val result = Bits(elabDepth bits)
        .setName(stableName, weak = true)
        .dontSimplifyIt()
      result := value
      result
    }

    override def clearMask(mask: Bits): Unit = mask := 0

    override def assignBool(target: Bool, value: Bool): Bool = {
      target := value
      target
    }

    override def oneStageChecks(target: Vec[Bool], value: Bool): Vec[Bool] = {
      target(0) := value
      target
    }

    override def emptyChecks(target: Vec[Bool]): Vec[Bool] = target

    override def boolBranches(stableName: String)(
        storage: Bool => Bool,
        oneStageBranch: Bool => Bool,
        bypass: Bool => Bool
    ): Bool = {
      val result = Bool().setName(stableName)
      if (formalStorageOwner != null)
        ParameterizedStructure.captureInto(
          formalStorageOwner,
          elabDepth,
          s"StreamFifo $stableName storage owner"
        ) {
          storage(result)
        }
      if (formalOneStageOwner != null)
        ParameterizedStructure.captureInto(
          formalOneStageOwner,
          elabDepth,
          s"StreamFifo $stableName one-stage owner"
        ) {
          oneStageBranch(result)
        }
      result
    }

    override def ramBranches(stableName: String)(
        storage: Vec[Bool] => Vec[Bool],
        oneStageBranch: Vec[Bool] => Vec[Bool],
        bypass: Vec[Bool] => Vec[Bool]
    ): Vec[Bool] = {
      val result = Vec(Bool(), elabDepth).setName(stableName)
      if (formalStorageOwner != null)
        ParameterizedStructure.captureInto(
          formalStorageOwner,
          elabDepth,
          s"StreamFifo $stableName storage owner"
        ) {
          storage(result)
        }
      if (formalOneStageOwner != null)
        ParameterizedStructure.captureInto(
          formalOneStageOwner,
          elabDepth,
          s"StreamFifo $stableName one-stage owner"
        ) {
          oneStageBranch(result)
        }
      result
    }

    override def reduceOr(checks: Vec[Bool]): Bool =
      ElabFiniteRange.reduceOr(checks.asBits, elabDepth)

    override def countOne(checks: Vec[Bool]): UInt =
      ElabFiniteRange.countOne(checks.asBits, elabDepth)(CountOne(checks))

    override def growCountOperand(value: UInt, stableName: String): UInt = {
      val result = value
        .resize((elabDepth + 1).addressWidth + 1)
        .setName(stableName, weak = true)
      result.dontSimplifyIt()
      result
    }

    override def countResult(stableName: String, value: UInt): UInt = {
      val result = value
        .resize((elabDepth + 1).addressWidth + 1)
        .setName(stableName)
      result.dontSimplifyIt()
      result
    }

    /** Pointer registers may retain the native extra-MSB width while formal
      * RAM addressing needs exactly the storage address width. Absorb that
      * intentional mismatch once at the symbolic geometry boundary.
      */
    private def normalizedIndex(index: UInt, stableName: String): UInt = {
      val result = UInt(elabDepth.addressWidth bits)
        .setName(stableName, weak = true)
        .dontSimplifyIt()
      result := index.resized
      result
    }
  }

  private def formalStorageConditions[A <: FormalHelperAdapter](
      adapter: A
  )(
      role: String,
      stableName: String,
      existing: Option[adapter.Conditions],
      cond: T => Bool
  ): adapter.Conditions =
    adapter.conditions(role, stableName, existing) { index =>
      cond(adapter.storagePayload(index))
    }

  private def formalLowMask[A <: FormalHelperAdapter](
      adapter: A,
      one: UInt,
      index: UInt,
      stableName: String
  ): Bits =
    adapter.lowMask(
      adapter.shiftMaskOne(one, index, s"${stableName}_shifted_one") - one,
      stableName
    )

  private def formalCheckLastPushAlgorithm[A <: FormalHelperAdapter](
      adapter: A,
      cond: T => Bool
  ): Bool = {
    // The carrier is declared before branch capture so its retained Vec shape
    // covers the complete depth domain. Predicate drivers and the packed
    // dynamic selector both remain local to the storage owner.
    val outerCondition =
      adapter.outerStorageConditions("formal_last_push_condition")
    adapter.boolBranches("formal_last_push")(
      storage = target => {
        val condition = formalStorageConditions(
          adapter
        )(
          "stream_fifo_formal_last_push",
          "formal_last_push_condition",
          outerCondition,
          cond
        )
        val lastPushIndex = adapter.previousStorageIndex(
          formalStoragePush,
          "typed_formal_last_push_previous_index"
        )
        adapter.assignBool(
          target,
          adapter.selectCondition(
            condition,
            lastPushIndex,
            "typed_formal_last_push_index"
          )
        )
      },
      oneStageBranch = target => adapter.assignBool(target, cond(formalOneStageBuffer.payload)),
      bypass = target => adapter.assignBool(target, cond(io.push.payload))
    )
  }

  private def formalCheckRamAlgorithm[A <: FormalHelperAdapter](
      adapter: A,
      cond: T => Bool
  ): Vec[Bool] = {
    adapter.ramBranches("formal_ram_check")(
      storage = target => {
        val condition = formalStorageConditions(
          adapter
        )(
          "stream_fifo_formal_ram_condition",
          "formal_ram_condition",
          None,
          cond
        )
        // Mask all valid storage payloads in the inclusive/exclusive interval
        // [popIndex, pushIndex), including the wrapped and empty cases.
        val mask = adapter.newMask("formal_ram_mask")
        val pushIndex = adapter.maskIndex(
          formalStoragePush,
          "typed_formal_ram_push_index"
        )
        val popIndex = adapter.maskIndex(
          formalStoragePop,
          "typed_formal_ram_pop_index"
        )
        val one = adapter.maskOne()
        val popMask = ~formalLowMask(
          adapter,
          one,
          popIndex,
          "typed_formal_ram_pop_low_mask"
        )
        val pushMask = formalLowMask(
          adapter,
          one,
          pushIndex,
          "typed_formal_ram_push_low_mask"
        )
        val orderedMask = adapter.stabilizeMask(
          pushMask & popMask,
          "typed_formal_ram_ordered_mask"
        )
        val wrappedMask = adapter.stabilizeMask(
          pushMask | popMask,
          "typed_formal_ram_wrapped_mask"
        )
        when(popIndex < pushIndex) {
          adapter.assignMask(mask, orderedMask)
        }.elsewhen(popIndex > pushIndex) {
          adapter.assignMask(mask, wrappedMask)
        }.elsewhen(formalStorageEmpty) {
          adapter.clearMask(mask)
        }
        adapter.checkedRam(target, mask, condition) { (valid, predicate) =>
          valid & predicate
        }
      },
      oneStageBranch = target =>
        adapter.oneStageChecks(
          target,
          formalOneStageBuffer.valid & cond(formalOneStageBuffer.payload)
        ),
      bypass = target => adapter.emptyChecks(target)
    )
  }

  private def formalContainsAlgorithm[A <: FormalHelperAdapter](
      adapter: A,
      cond: T => Bool
  ): Bool = {
    val checks = formalCheckRamAlgorithm(adapter, cond)
    adapter.reduceOr(checks) || formalCheckOutputStage(cond)
  }

  private def formalCountAlgorithm[A <: FormalHelperAdapter](
      adapter: A,
      stableName: String,
      cond: T => Bool
  ): UInt = {
    val checks = formalCheckRamAlgorithm(adapter, cond)
    // Mirror the native +^ graph through adapter-owned expansion mechanics so
    // the symbolic operands retain the exact fold width before the one shared
    // addition is built.
    val storageCount = adapter.growCountOperand(
      adapter.countOne(checks),
      "typed_formal_storage_count"
    )
    val outputCount = adapter.growCountOperand(
      U(formalCheckOutputStage(cond)),
      "typed_formal_output_count"
    )
    val count = storageCount + outputCount
    adapter.countResult(stableName, count)
  }

  private def formalFullToEmptyAlgorithm(empty: Bool): Bool = {
    val was_full = RegInit(False) setWhen (!io.push.ready)
    was_full && empty
  }

  private def capturedFormalResult[R <: Data](
      role: String
  )(body: FormalHelperAdapter => R): R = {
    requirePositiveSymbolicFormalDepth(role)
    requireSymbolicFormalOwnerCoverage(role)
    val local = this.rework(body(capturedFormalHelperAdapter))
    local.pull()
  }

  /** A pulled typed Vec remains owned by the FIFO child. Reattach the child's
    * exact late-created Vec formal, construct one caller-owned Vec from the
    * opaque binding actual, and keep the native whole-Vec assignment as the
    * exact hierarchy bridge. A subsequent public `asBits` is consequently
    * recorded against a carrier owned by that same caller.
    */
  private def publishCapturedFormalChecks(checks: Vec[Bool]): Vec[Bool] = {
    val callerDepth = ElabFormalComponent
      .parentActualAndRefreshVecFormals(this)
      .getOrElse(elabDepth)
    val result = Vec(Bool(), callerDepth)
    result := checks
    result
  }

  def formalCheckLastPush(cond: T => Bool) : Bool = {
    if (elabDepth.isConcrete) {
      new Composite(this) {
    val lastPush = formalCheckLastPushAlgorithm(
          concreteFormalHelperAdapter,
          cond
        )
      }.lastPush
    } else
      capturedFormalResult("StreamFifo.formalCheckLastPush") { adapter =>
        formalCheckLastPushAlgorithm(adapter, cond)
    }
  }

  // check a condition against all valid payloads in the FIFO RAM
  def formalCheckRam(cond: T => Bool): Vec[Bool] = {
    if (elabDepth.isConcrete) {
      new Composite(this){
    val vec = formalCheckRamAlgorithm(concreteFormalHelperAdapter, cond)
      }.vec
    } else {
      val pulled = capturedFormalResult("StreamFifo.formalCheckRam") { adapter =>
        formalCheckRamAlgorithm(adapter, cond)
      }
      publishCapturedFormalChecks(pulled)
    }
  }

  def formalCheckOutputStage(cond: T => Bool): Bool = {
    // only with sync RAM read, io.pop is directly connected to the m2sPipe() stage
    Bool(!withAsyncRead) & io.pop.valid & cond(io.pop.payload)
  }

  // verify this works, then we can simplify below
  //def formalCheck(cond: T => Bool): Vec[Bool] = new Area {
  //  Vec(formalCheckOutputStage(cond) +: formalCheckRam(cond))
  //}

  def formalContains(word: T): Bool =
    if (elabDepth.isConcrete) {
      val pulledWord = word.pull()
      formalContainsAlgorithm(concreteFormalHelperAdapter, _ === pulledWord)
    } else
      capturedFormalResult("StreamFifo.formalContains") { adapter =>
        val pulledWord = word.pull()
        formalContainsAlgorithm(adapter, _ === pulledWord)
  }
  def formalContains(cond: T => Bool): Bool = {
    if (elabDepth.isConcrete)
      formalContainsAlgorithm(concreteFormalHelperAdapter, cond)
    else
      capturedFormalResult("StreamFifo.formalContains") { adapter =>
        formalContainsAlgorithm(adapter, cond)
      }
  }

  def formalCount(word: T): UInt =
    if (elabDepth.isConcrete) {
      val pulledWord = word.pull()
      formalCountAlgorithm(
        concreteFormalHelperAdapter,
        "typed_formal_word_count",
        _ === pulledWord
      )
    } else
      capturedFormalResult("StreamFifo.formalCount") { adapter =>
        val pulledWord = word.pull()
        formalCountAlgorithm(
          adapter,
          "typed_formal_word_count",
          _ === pulledWord
        )
  }
  def formalCount(cond: T => Bool): UInt = {
    if (elabDepth.isConcrete)
      formalCountAlgorithm(
        concreteFormalHelperAdapter,
        "typed_formal_predicate_count",
        cond)
    else
      capturedFormalResult("StreamFifo.formalCount") { adapter =>
        formalCountAlgorithm(adapter, "typed_formal_predicate_count", cond)
      }
  }

  def formalFullToEmpty() = {
    if (elabDepth.isConcrete) {
      new Area {
        cover(formalFullToEmptyAlgorithm(logic.ptr.empty))
      }
    } else {
      requirePositiveSymbolicFormalDepth("StreamFifo.formalFullToEmpty")
      requireSymbolicFormalOwnerCoverage("StreamFifo.formalFullToEmpty")
      this.rework {
        // AssertStatement has no generic identity-to-emitted-process bridge in
        // structural capture. Keep one exact module-scope observation point
        // instead: each mutually-exclusive typed owner drives this retained
        // Bool, while the ordinary native cover remains outside every captured
        // branch. Branch-local registers therefore cannot escape their
        // generate owner, and an arbitrary captured assertion still fails
        // closed in ParameterizedStructure.
        val reachedEmpty = Bool().setName("formal_full_to_empty")
        reachedEmpty.dontSimplifyIt()
        if (formalStorageOwner != null)
          ParameterizedStructure.captureInto(
            formalStorageOwner,
            elabDepth,
            "StreamFifo formal full-to-empty storage owner"
          ) {
            reachedEmpty := formalFullToEmptyAlgorithm(formalStorageEmpty)
          }
        if (formalOneStageOwner != null)
          ParameterizedStructure.captureInto(
            formalOneStageOwner,
            elabDepth,
            "StreamFifo formal full-to-empty one-stage owner"
          ) {
            reachedEmpty := formalFullToEmptyAlgorithm(
              !formalOneStageBuffer.valid
            )
          }
        cover(reachedEmpty)
        new Area {}
      }
    }
  }

  private def requirePositiveSymbolicFormalDepth(role: String): Unit = {
    val expression = elabDepth.projectedExpression(role)
    if (expression.minimum < 1)
      throw new ParameterizedVerilogException(
        "SPINAL-ELAB-STREAMFIFO-FORMAL-DEPTH-DOMAIN-NONPOSITIVE",
        s"$role requires a strictly positive symbolic StreamFifo depth, but '${expression.verilog}' reaches ${expression.minimum}",
        elabDepth.sourceLocation
      )
  }

  private def requireSymbolicFormalOwnerCoverage(role: String): Unit =
    ParameterizedStructure.requireOwnerCoverage(
      this,
      elabDepth,
      Seq(formalOneStageOwner, formalStorageOwner),
      role
    )

}

object StreamFifoLowLatency{
  def apply[T <: Data](dataType: T, depth: Int) = new StreamFifoLowLatency(dataType,depth)
}

class StreamFifoLowLatency[T <: Data](val dataType: HardType[T],val depth: Int,val latency : Int = 0, useVec : Boolean = false, initPayload : => Option[T] = None) extends Component {
  assert(latency == 0 || latency == 1)

  val io = new Bundle with StreamFifoInterface[T]{
    val push = slave Stream (dataType)
    val pop = master Stream (dataType)
    val flush = in Bool() default(False)
    val occupancy    = out UInt (log2Up(depth + 1) bits)
    val availability = out UInt (log2Up(depth + 1) bits)
    override def pushOccupancy = occupancy
    override def popOccupancy = occupancy
  }

  val fifo = new StreamFifo(
    dataType = dataType,
    depth = depth,
    withAsyncRead = true,
    withBypass = latency == 0,
    useVec = useVec,
    initPayload = initPayload
  )

  io.push <> fifo.io.push
  io.pop <> fifo.io.pop
  io.flush <> fifo.io.flush
  io.occupancy <> fifo.io.occupancy
  io.availability <> fifo.io.availability
}

object StreamFifoCC{
  // Every mergeable pointer BufferCC definition must expose the same formal
  // schema. Call-site domains remain on the actual WIDTH binding; deriving the
  // child formal's bounds from one FIFO makes otherwise identical native
  // synchronizers incompatible when their defaults match but ranges differ.
  private val TypedPointerWidthMinimum = BigInt(2)
  // The largest legal power-of-two Int depth is 2^30, whose wrapped FIFO
  // pointer needs 31 bits.  log2Up(Int.MaxValue) expresses that exact ceiling.
  private val TypedPointerWidthMaximum = BigInt(log2Up(Int.MaxValue))

  /** Canonicalize only the invalid tail above the caller's highest legal
    * power-of-two depth.  All callers in one bucket therefore expose the same
    * formal schema without admitting another legal FIFO geometry.  The final
    * cap is the largest [2, max] formal that exact-domain publication can
    * represent; larger actuals retain the existing fail-closed rejection.
    */
  private def typedDepthFormalMaximum(depth: ElabInt): BigInt = {
    val maximum = depth.maximum
    if (maximum == 2) maximum
    else
      ((BigInt(1) << maximum.bitLength) - 1)
        .min(ElaborationExactDomain.MaximumDomainSize + 1)
  }

  private def validateTypedDepth(depth: ElabInt, role: String): ElabBool = {
    if (depth == null)
      throw new IllegalArgumentException(s"$role requires a non-null ElabInt depth")
    if (depth.isConcrete) {
      assert(
        isPow2(depth.witness) && depth.witness >= 2,
        "The depth of the StreamFifoCC must be a power of 2 and equal or bigger than 2"
      )
      return ElabBool.literal(true)
    }
    depth.requireAuthoritativeIntegerDomain(
      role,
      "SPINAL-STREAM-FIFO-CC-DEPTH-EXACT-DOMAIN-REQUIRED",
      requireExactExtrema = false
    )
    if (depth.minimum < 2 || depth.maximum > BigInt(Int.MaxValue))
      throw new ParameterizedVerilogException(
        "SPINAL-STREAM-FIFO-CC-DEPTH-DOMAIN-INVALID",
        s"$role must stay in the Int-sized domain at or above two, but reaches [${depth.minimum}, ${depth.maximum}]",
        depth.sourceLocation
      )
    val legal = (depth >= 2) && depth.isPow2
    if (!legal.witness)
      throw new ParameterizedVerilogException(
        "SPINAL-STREAM-FIFO-CC-DEPTH-DEFAULT-INVALID",
        s"$role default ${depth.witness} must be a power of two and at least two",
        depth.sourceLocation
      )
    if (legal.isAlwaysFalse)
      throw new ParameterizedVerilogException(
        "SPINAL-STREAM-FIFO-CC-DEPTH-NO-LEGAL-VALUE",
        s"$role admits no power-of-two value at or above two",
        depth.sourceLocation
      )
    legal
  }

  def apply[T <: Data](dataType: HardType[T], depth: Int, pushClock: ClockDomain, popClock: ClockDomain): StreamFifoCC[T] =
    new StreamFifoCC(dataType, depth, pushClock, popClock)

  def apply[T <: Data](dataType: HardType[T], depth: Int, pushClock: ClockDomain, popClock: ClockDomain, withPopBufferedReset: Boolean): StreamFifoCC[T] =
    new StreamFifoCC(dataType, depth, pushClock, popClock, withPopBufferedReset)

  def apply[T <: Data](dataType: HardType[T], depth: ElabInt, pushClock: ClockDomain, popClock: ClockDomain): StreamFifoCC[T] =
    apply(
      dataType,
      depth,
      pushClock,
      popClock,
      ClockDomain.crossClockBufferPushToPopResetGen.get
    )

  def apply[T <: Data](dataType: HardType[T], depth: ElabInt, pushClock: ClockDomain, popClock: ClockDomain, withPopBufferedReset: Boolean): StreamFifoCC[T] = {
    validateTypedDepth(depth, "StreamFifoCC typed depth")
    if (depth.isConcrete || !ParameterizedStructure.captureEnabled)
      new StreamFifoCC(
        dataType,
        depth.witness,
        pushClock,
        popClock,
        withPopBufferedReset
      )
    else {
      ElabFormalComponent.parameter(
        actual = depth,
        name = "DEPTH",
        minimum = BigInt(2),
        maximum = typedDepthFormalMaximum(depth)
      )(formal =>
        new StreamFifoCC(
          dataType,
          formal,
          pushClock,
          popClock,
          withPopBufferedReset
        )
      )
    }
  }

  def apply[T <: Data](push : Stream[T], pop : Stream[T], depth: Int, pushClock: ClockDomain, popClock: ClockDomain): StreamFifoCC[T] = {
    val fifo = new StreamFifoCC(push.payloadType, depth, pushClock, popClock)
    fifo.io.push << push
    fifo.io.pop >> pop
    fifo
  }

  def apply[T <: Data](push : Stream[T], pop : Stream[T], depth: ElabInt, pushClock: ClockDomain, popClock: ClockDomain): StreamFifoCC[T] = {
    val fifo = apply(push.payloadType, depth, pushClock, popClock)
    fifo.io.push << push
    fifo.io.pop >> pop
    fifo
  }
}

//class   StreamFifoCC[T <: Data](dataType: HardType[T], val depth: Int, val pushClock: ClockDomain,val popClock: ClockDomain) extends Component {
//
//  assert(isPow2(depth) & depth >= 2, "The depth of the StreamFifoCC must be a power of 2 and equal or bigger than 2")
//
//  val io = new Bundle with StreamFifoInterface[T]{
//    val push          = slave  Stream(dataType)
//    val pop           = master Stream(dataType)
//    val pushOccupancy = out UInt(log2Up(depth + 1) bits)
//    val popOccupancy  = out UInt(log2Up(depth + 1) bits)
//  }
//
//  val ptrWidth = log2Up(depth) + 1
//  def isFull(a: Bits, b: Bits) = a(ptrWidth - 1 downto ptrWidth - 2) === ~b(ptrWidth - 1 downto ptrWidth - 2) && a(ptrWidth - 3 downto 0) === b(ptrWidth - 3 downto 0)
//  def isEmpty(a: Bits, b: Bits) = a === b
//
//  val ram = Mem(dataType, depth)
//
//  val popToPushGray = Bits(ptrWidth bits)
//  val pushToPopGray = Bits(ptrWidth bits)
//
//  val pushCC = new ClockingArea(pushClock) {
//    val pushPtr     = Counter(depth << 1)
//    val pushPtrGray = RegNext(toGray(pushPtr.valueNext)) init(0)
//    val popPtrGray  = BufferCC(popToPushGray, B(0, ptrWidth bits))
//    val full        = isFull(pushPtrGray, popPtrGray)
//
//    io.push.ready := !full
//
//    when(io.push.fire) {
//      ram(pushPtr.resized) := io.push.payload
//      pushPtr.increment()
//    }
//
//    io.pushOccupancy := (pushPtr - fromGray(popPtrGray)).resized
//  }
//
//  val popCC = new ClockingArea(popClock) {
//    val popPtr      = Counter(depth << 1)
//    val popPtrGray  = RegNext(toGray(popPtr.valueNext)) init(0)
//    val pushPtrGray = BufferCC(pushToPopGray, B(0, ptrWidth bit))
//    val empty       = isEmpty(popPtrGray, pushPtrGray)
//
//    io.pop.valid   := !empty
//    io.pop.payload := ram.readSync(popPtr.valueNext.resized, clockCrossing = true)
//
//    when(io.pop.fire) {
//      popPtr.increment()
//    }
//
//    io.popOccupancy := (fromGray(pushPtrGray) - popPtr).resized
//  }
//
//  pushToPopGray := pushCC.pushPtrGray
//  popToPushGray := popCC.popPtrGray
//}



class StreamFifoCC[T <: Data] private[lib] (
    val dataType: HardType[T],
    private[lib] val elabDepth: ElabInt,
    val pushClock: ClockDomain,
    val popClock: ClockDomain,
    val withPopBufferedReset: Boolean
) extends Component {

  def this(dataType: HardType[T],
           depth: Int,
           pushClock: ClockDomain,
           popClock: ClockDomain,
           withPopBufferedReset: Boolean = ClockDomain.crossClockBufferPushToPopResetGen.get) =
    this(
      dataType,
      ElabInt.literal(depth),
      pushClock,
      popClock,
      withPopBufferedReset
    )

  private val depthIsLegal: ElabBool = StreamFifoCC.validateTypedDepth(
    elabDepth,
    "StreamFifoCC depth"
  )

  /** Source- and binary-compatible concrete witness accessor. */
  val depth: Int = elabDepth.witness

  val io = new Bundle with StreamFifoInterface[T]{
    val push          = slave  Stream(dataType)
    val pop           = master Stream(dataType)
    val pushOccupancy = if (elabDepth.isConcrete) {
      out UInt(log2Up(depth + 1) bits)
    } else {
      out UInt(log2Up(elabDepth + 1) bits)
    }
    val popOccupancy = if (elabDepth.isConcrete) {
      out UInt(log2Up(depth + 1) bits)
    } else {
      out UInt(log2Up(elabDepth + 1) bits)
    }
  }

  private val elabPtrWidth = log2Up(elabDepth) + 1
  val ptrWidth: Int = elabPtrWidth.witness

  def isFull(a: Bits, b: Bits) = {
    if (elabDepth.isConcrete) {
      a(ptrWidth - 1 downto ptrWidth - 2) === ~b(ptrWidth - 1 downto ptrWidth - 2) &&
        a(ptrWidth - 3 downto 0) === b(ptrWidth - 3 downto 0)
    } else {
      // For a legal power-of-two depth, toggling the two most-significant Gray
      // bits is exactly XOR by depth + depth/2. This whole-vector form avoids
      // freezing either slice boundary to the construction witness.
      val prototype = a.asUInt
      ParameterizedWidth.copy(a, prototype)
      val fullMask = ElabValue
        .uintLike(
          elabDepth + (elabDepth / 2),
          prototype,
          "stream_fifocc_full_mask"
        )
        .asBits
      (a ^ b) === fullMask
    }
  }
  def isEmpty(a: Bits, b: Bits) = a === b

  private def retainedRegNextWhen[T <: Data](next: T, condition: Bool, init: T): T = {
    if (elabDepth.isConcrete) RegNextWhen(next, condition) init(init)
    else {
      val result = ParameterizedWidth.Reg(next)
      if (init != null) result.init(init)
      result.setCompositeName(next, "regNextWhen", true)
      when(condition) {
        result := next
      }
      result
    }
  }

  private def retainPointerWidth(prototype: UInt, value: UInt): UInt = {
    if (elabDepth.isConcrete) value
    else {
      // Retaining metadata only on a nested arithmetic expression is not
      // enough: the native Verilog emitter may introduce a witness-width
      // temporary before the eventual typed consumer. Materialize the native
      // result through an explicit pointer-width carrier at that boundary.
      val result = UInt(elabPtrWidth bits).dontSimplifyIt()
      result := value
      result
    }
  }

  /** Preserve a stable native-algorithm result boundary without duplicating
    * the shared Gray-code implementation from Utils.scala.
    */
  private def retainedToGray(value: UInt, stableName: String): Bits = {
    if (elabDepth.isConcrete) toGray(value)
    else {
      val result = Bits(elabPtrWidth bits)
        .setName(stableName, weak = true)
        .dontSimplifyIt()
      result := toGray(value)
      result
    }
  }

  private def retainedFromGray(value: Bits, stableName: String): UInt = {
    if (elabDepth.isConcrete) fromGray(value)
    else {
      val result = UInt(elabPtrWidth bits)
        .setName(stableName, weak = true)
        .dontSimplifyIt()
      result := fromGray(value)
      result
    }
  }

  private def pointerZeroBits: Bits =
    if (elabDepth.isConcrete) B(0, ptrWidth bits) else B(0)

  private def pointerZeroUInt: UInt =
    if (elabDepth.isConcrete) U(0, ptrWidth bits) else U(0)

  private def retainedBufferCC(input: Bits, definitionName: String): Bits = {
    val attributes = List(crossClockMaxDelay(1, useTargetClock = false))
    if (elabDepth.isConcrete) {
      BufferCC(input, pointerZeroBits, inputAttributes = attributes)
    } else {
      // BufferCC is a kept child definition. Give that child its own exact
      // WIDTH formal, then bind the parent pointer-width expression at the
      // instance boundary instead of leaking a branch-projected root into it.
      val child = ElabFormalComponent.parameter(
        actual = elabPtrWidth,
        name = "WIDTH",
        minimum = StreamFifoCC.TypedPointerWidthMinimum,
        maximum = StreamFifoCC.TypedPointerWidthMaximum
      )(formal =>
        new BufferCC(
          Bits(formal bits),
          B(0),
          bufferDepth = None,
          randBoot = false,
          inputAttributes = attributes,
          allBufAttributes = List()
        ).setDefinitionName(definitionName, noMerge = false)
      )
      child.setCompositeName(input, "buffercc", true)
      // Both child ports must be constrained by carriers owned by this exact
      // legal-depth branch. A module-scope input would mix the unprojected
      // contiguous DEPTH domain with the branch-projected power-of-two domain.
      val branchInput = Bits(elabPtrWidth bits)
      branchInput := input
      child.io.dataIn := branchInput
      val result = Bits(elabPtrWidth bits)
      result := child.io.dataOut
      result
    }
  }

  trait PushCCMembers {
    val pushPtr: UInt
    val pushPtrPlus: UInt
    val pushPtrGray: Bits
    val popPtrGray: Bits
    val full: Bool
  }

  trait PopCCMembers {
    val popPtr: UInt
    val popPtrPlus: UInt
    val popPtrGray: Bits
    val pushPtrGray: Bits
    val addressGen: Stream[UInt]
    val empty: Bool
    val readArbitration: Stream[UInt]
    val readPort: MemReadPort[T]
    val ptrToPush: Bits
    val ptrToOccupancy: UInt
  }

  type PushCCArea = ClockingArea with PushCCMembers
  type PopCCArea = ClockingArea with PopCCMembers

  private var ramBacking: Mem[T] = null
  private var popToPushGrayBacking: Bits = null
  private var pushToPopGrayBacking: Bits = null
  private var finalPopCdBacking: ClockDomain = null
  private var pushCCBacking: PushCCArea = null
  private var popCCBacking: PopCCArea = null
  private var formalAlgorithmOwner: ParameterizedStructuralOwner = null
  private var formalInvalidDepthOwner: ParameterizedStructuralOwner = null

  /** Plain Scala references to the one native FIFO body. This carrier is not
    * an Area and therefore introduces no hardware hierarchy of its own.
    */
  private final class BuiltAlgorithm(
      val ram: Mem[T],
      val popToPushGray: Bits,
      val pushToPopGray: Bits,
      val finalPopCd: ClockDomain,
      val pushCC: PushCCArea,
      val popCC: PopCCArea
  )

  /** Elaborate the authoritative native StreamFifoCC algorithm exactly once.
    * Only individual geometry-producing expressions select a concrete or
    * retained-width spelling; RAM, Gray crossings, arbitration and reset
    * topology are shared by both entry lanes.
    */
  private def buildNativeAlgorithm(): BuiltAlgorithm = {
    val payloadWidth = widthOfExpr(io.push.payload)
    // A native Mem becomes parameterized when either its word count or any
    // packed payload leaf is symbolic.  In both cases the external memory
    // publisher requires stable native AST identities for the write roles.
    // Keep the historical shorthand only when both dimensions are concrete.
    val parameterizedMemoryRoles =
      !elabDepth.isConcrete || !payloadWidth.isConcrete
    val ram =
      if (elabDepth.isConcrete) Mem(dataType, depth)
      else Mem(dataType, elabDepth)

    val popToPushGray =
      if (elabDepth.isConcrete) Bits(ptrWidth bits)
      else Bits(elabPtrWidth bits)
    val pushToPopGray =
      if (elabDepth.isConcrete) Bits(ptrWidth bits)
      else Bits(elabPtrWidth bits)

    if (!elabDepth.isConcrete) {
      // The native emitter trace omits retained formal metadata. This
      // nonfunctional declaration attribute keeps different legal buckets from
      // merging when their default-width RTL happens to be textually equal.
      // Inside depthIsLegal, maximum is the exact projected legal ceiling.
      popToPushGray.addAttribute(
        "spinal_stream_fifocc_legal_depth_ceiling",
        elabDepth.maximum.toInt
      )
    }

    val pushCC = new ClockingArea(pushClock) with PushCCMembers {
      val pushPtr =
        if (elabDepth.isConcrete) Reg(UInt(log2Up(2 * depth) bits)) init (0)
        else Reg(UInt(elabPtrWidth bits)) init (0)
      val pushPtrPlus = if (elabDepth.isConcrete) {
        pushPtr + 1
      } else {
        val one = ElabValue.uintLike(
          ElabInt.literal(1),
          pushPtr,
          "stream_fifocc_push_pointer_one"
        )
        retainPointerWidth(pushPtr, pushPtr + one)
      }
      val pushPtrGrayNext =
        if (elabDepth.isConcrete) null
        else retainedToGray(pushPtrPlus, "stream_fifocc_push_gray")
      val pushPtrGray = if (elabDepth.isConcrete) {
        RegNextWhen(toGray(pushPtrPlus), io.push.fire) init (0)
      } else {
        retainedRegNextWhen(
          pushPtrGrayNext,
          io.push.fire,
          pointerZeroBits
        )
      }
      val popPtrGray = if (elabDepth.isConcrete) {
        BufferCC(
          popToPushGray,
          B(0, ptrWidth bits),
          inputAttributes = List(
            crossClockMaxDelay(1, useTargetClock = false)
          )
        )
      } else {
        retainedBufferCC(popToPushGray, "StreamFifoCCPopToPushBufferCC")
      }
      val full = isFull(pushPtrGray, popPtrGray)
      val writeData =
        if (!parameterizedMemoryRoles) null
        else
          Bits(payloadWidth bits)
            .setName("stream_fifocc_write_data", weak = true)
            .dontSimplifyIt()

      if (parameterizedMemoryRoles) writeData := io.push.payload.asBits

      io.push.ready := !full

      when(io.push.fire) {
        if (!parameterizedMemoryRoles) {
          ram(pushPtr.resized) := io.push.payload
        } else {
          val writeAddress = UInt(elabDepth.addressWidth bits)
            .setName("stream_fifocc_write_address", weak = true)
          writeAddress := pushPtr.resized
          // Native Mem flattens aggregate writes through an unnamed Cat AST.
          // The area-scoped packed carrier gives that same value one stable
          // identity without changing the native conditional write enable.
          ram.writeImpl(
            writeAddress,
            writeData,
            enable = null,
            mask = null,
            allowMixedWidth = false
          )
        }
        pushPtr := pushPtrPlus
      }

      if (elabDepth.isConcrete) {
        io.pushOccupancy := (pushPtr - fromGray(popPtrGray)).resized
      } else {
        io.pushOccupancy := retainPointerWidth(
          pushPtr,
          pushPtr - retainedFromGray(
            popPtrGray,
            "stream_fifocc_push_occupancy_gray"
          )
        ).resized
      }
    }

    // Construct the optional reset synchronizer alongside its sole consumers.
    // The concrete lane retains the historical cache path byte-for-byte. A
    // generated lane must use an owner-local instance because a cached child
    // cannot legally be shared by sibling generate scopes.
    val finalPopCd = if (elabDepth.isConcrete) {
      popClock.withOptionalBufferedResetFrom(withPopBufferedReset)(pushClock)
    } else {
      popClock.withOptionalBufferedResetFromUncached(withPopBufferedReset)(
        pushClock
      )
    }
    val popCC = new ClockingArea(finalPopCd) with PopCCMembers {
      val popPtr =
        if (elabDepth.isConcrete) Reg(UInt(log2Up(2 * depth) bits)) init (0)
        else Reg(UInt(elabPtrWidth bits)) init (0)
      val popPtrPlus = if (elabDepth.isConcrete) {
        KeepAttribute(popPtr + 1)
      } else {
        val one = ElabValue.uintLike(
          ElabInt.literal(1),
          popPtr,
          "stream_fifocc_pop_pointer_one"
        )
        val next = retainPointerWidth(popPtr, popPtr + one)
        KeepAttribute(next)
        next
      }
      val popPtrGray =
        if (elabDepth.isConcrete) toGray(popPtr)
        else retainedToGray(popPtr, "stream_fifocc_pop_gray")
      val pushPtrGray = if (elabDepth.isConcrete) {
        BufferCC(
          pushToPopGray,
          B(0, ptrWidth bit),
          inputAttributes = List(
            crossClockMaxDelay(1, useTargetClock = false)
          )
        )
      } else {
        retainedBufferCC(pushToPopGray, "StreamFifoCCPushToPopBufferCC")
      }
      val addressGen =
        if (elabDepth.isConcrete) Stream(UInt(log2Up(depth) bits))
        else Stream(UInt(elabDepth.addressWidth bits))
      val empty = isEmpty(popPtrGray, pushPtrGray)
      addressGen.valid := !empty
      addressGen.payload := popPtr.resized

      when(addressGen.fire) {
        popPtr := popPtrPlus
      }

      val readArbitration = addressGen.m2sPipe()
      val readPort = ram.readSyncPort(clockCrossing = true)
      readPort.cmd := addressGen.toFlowFire
      io.pop << readArbitration.translateWith(readPort.rsp)

      val ptrToPush = if (elabDepth.isConcrete) {
        RegNextWhen(popPtrGray, readArbitration.fire) init (0)
      } else {
        retainedRegNextWhen(
          popPtrGray,
          readArbitration.fire,
          pointerZeroBits
        )
      }
      val ptrToOccupancy = if (elabDepth.isConcrete) {
        RegNextWhen(popPtr, readArbitration.fire) init (0)
      } else {
        retainedRegNextWhen(
          popPtr,
          readArbitration.fire,
          pointerZeroUInt
        )
      }
      val decodedPushPtr =
        if (elabDepth.isConcrete) null
        else
          retainedFromGray(
            pushPtrGray,
            "stream_fifocc_pop_occupancy_gray"
          )
      if (elabDepth.isConcrete) {
        io.popOccupancy := (fromGray(pushPtrGray) - ptrToOccupancy).resized
      } else {
        io.popOccupancy := retainPointerWidth(
          ptrToOccupancy,
          decodedPushPtr - ptrToOccupancy
        ).resized
      }
    }

    pushToPopGray := pushCC.pushPtrGray
    popToPushGray := popCC.ptrToPush

    new BuiltAlgorithm(
      ram,
      popToPushGray,
      pushToPopGray,
      finalPopCd,
      pushCC,
      popCC
    )
  }

  if (elabDepth.isConcrete) {
    // Invoke the one body directly so the Int lane keeps its historical root
    // hierarchy. Method locals need explicit roots because they do not receive
    // member-name inference from the enclosing Component.
    val built = buildNativeAlgorithm()
    built.ram.setName("ram")
    built.popToPushGray.setName("popToPushGray")
    built.pushToPopGray.setName("pushToPopGray")
    built.pushCC.setName("pushCC")
    built.popCC.setName("popCC")

    ramBacking = built.ram
    popToPushGrayBacking = built.popToPushGray
    pushToPopGrayBacking = built.pushToPopGray
    finalPopCdBacking = built.finalPopCd
    pushCCBacking = built.pushCC
    popCCBacking = built.popCC
  } else {
    val algorithm = depthIsLegal generate new Area {
      if (ParameterizedStructure.captureEnabled)
        formalAlgorithmOwner = ParameterizedStructure.currentOwner(
          elabDepth,
          "StreamFifoCC legal-depth algorithm owner"
        )
      val built = buildNativeAlgorithm()
      val ram = built.ram
      val popToPushGray = built.popToPushGray
      val pushToPopGray = built.pushToPopGray
      val finalPopCd = built.finalPopCd
      val pushCC = built.pushCC
      val popCC = built.popCC
    }

    // Keep illegal parameter values outside the CDC algorithm. Synthesis and
    // simulation both see a safe, inert interface for an invalid specialization.
    val invalidDepth = (!depthIsLegal) generate new Area {
      if (ParameterizedStructure.captureEnabled)
        formalInvalidDepthOwner = ParameterizedStructure.currentOwner(
          elabDepth,
          "StreamFifoCC invalid-depth owner"
        )
      // Verilog-2001 `always @(*)` blocks whose right-hand sides are only
      // literals have an empty sensitivity set and therefore never awaken in
      // simulators such as Icarus.  Retain one masked input carrier so every
      // invalid-branch output still resolves to zero, including for an X
      // input, while each emitted combinational process has a real event
      // source at time zero.
      val inert = (io.push.valid & False)
        .setName("stream_fifocc_invalid_inert")
        .dontSimplifyIt()
      val popPayloadWidth = widthOfExpr(io.pop.payload)
      val retainedPayloadZero =
        if (popPayloadWidth.isConcrete) null
        else
          Bits(popPayloadWidth bits)
            .setName("stream_fifocc_invalid_payload_zero", weak = true)
            .dontSimplifyIt()
      if (retainedPayloadZero != null) retainedPayloadZero := 0
      io.push.ready := inert
      io.pushOccupancy := 0
      io.pop.valid := inert
      if (retainedPayloadZero == null)
        io.pop.payload.assignFromBits(B(0).resize(popPayloadWidth))
      else io.pop.payload.assignFromBits(retainedPayloadZero)
      io.popOccupancy := 0
      // Keep the width-sensitive zero assignments themselves in the existing
      // invariant-zero proof lane. Repeating them below a condition on the
      // continuously-driven carrier gives their procedural blocks a genuine
      // sensitivity without introducing a concrete-width resize or relying on
      // an emitter-generated bridge name for an aggregate payload.
      when(inert) {
        io.pushOccupancy := 0
        if (retainedPayloadZero == null)
          io.pop.payload.assignFromBits(B(0).resize(popPayloadWidth))
        else io.pop.payload.assignFromBits(retainedPayloadZero)
        io.popOccupancy := 0
      }
    }

    algorithm.popToPushGray.setName("popToPushGray")
    algorithm.pushToPopGray.setName("pushToPopGray")
    algorithm.setName("algorithm")
    if (invalidDepth != null) invalidDepth.setName("invalidDepth")

    ramBacking = algorithm.ram
    popToPushGrayBacking = algorithm.popToPushGray
    pushToPopGrayBacking = algorithm.pushToPopGray
    finalPopCdBacking = algorithm.finalPopCd
    pushCCBacking = algorithm.pushCC
    popCCBacking = algorithm.popCC
  }

  // Preserve the native public member surface for inspection and formal
  // clients. These are Scala identity aliases and add no hardware.
  val ram: Mem[T] = ramBacking
  val popToPushGray: Bits = popToPushGrayBacking
  val pushToPopGray: Bits = pushToPopGrayBacking
  val finalPopCd: ClockDomain = finalPopCdBacking
  val pushCC: PushCCArea = pushCCBacking
  val popCC: PopCCArea = popCCBacking

  private def depthBoundLike(value: UInt, offset: Int, stableName: String): UInt =
    if (elabDepth.isConcrete) U(depth + offset, value.getWidth bits)
    else ElabValue.uintLike(elabDepth + offset, value, stableName)

  def formalAsserts(gclk: ClockDomain): Composite[StreamFifoCC[T]] = {
    if (elabDepth.isConcrete) {
      new Composite(this, "asserts") {
        import spinal.core.formal._
        val pushArea = new ClockingArea(pushClock) {
          when(pastValid & changed(pushCC.popPtrGray)) {
            assert(fromGray(pushCC.popPtrGray) - past(fromGray(pushCC.popPtrGray)) <= depth)
          }
          assert(pushCC.pushPtrGray === toGray(pushCC.pushPtr))
          assert(pushCC.pushPtr - fromGray(pushCC.popPtrGray) <= depth)
        }

        val popCheckClock = if (withPopBufferedReset) popClock.copy(reset = pushClock.isResetActive) else popClock
        val popArea = new ClockingArea(popCheckClock) {
          when(pastValid & changed(popCC.pushPtrGray)) {
            assert(fromGray(popCC.pushPtrGray) - past(fromGray(popCC.pushPtrGray)) <= depth)
          }
          assert(popCC.popPtrGray === toGray(popCC.popPtr))
          assert(fromGray(popCC.pushPtrGray) - popCC.popPtr <= depth)
          assert(popCC.popPtr === fromGray(popCC.ptrToPush) + io.pop.valid.asUInt)
        }

        val globalArea = new ClockingArea(gclk) {
          when(io.push.ready) { assert(pushCC.pushPtr - popCC.popPtr <= depth - 1) }
            .otherwise { assert(pushCC.pushPtr - popCC.popPtr <= depth) }
        }
      }
    } else {
      val invalidDepthOwnerRequired = !depthIsLegal.isAlwaysTrue
      if (
        formalAlgorithmOwner == null ||
        (invalidDepthOwnerRequired && formalInvalidDepthOwner == null)
      )
        throw new ParameterizedVerilogException(
          "SPINAL-STREAM-FIFO-CC-FORMAL-OWNER-MISSING",
          "typed StreamFifoCC formalAsserts requires complete retained legality-branch ownership",
          elabDepth.sourceLocation
        )
      val formalOwners =
        Vector(formalAlgorithmOwner, formalInvalidDepthOwner).filter(_ != null)
      ParameterizedStructure.requireOwnerCoverage(
        this,
        elabDepth,
        formalOwners,
        "StreamFifoCC formal observation owners"
      )

      this.rework {
        new Composite(StreamFifoCC.this, "asserts") {
          import spinal.core.formal._

        // Assertions themselves remain ordinary module-scope statements.
        // Each mutually-exclusive structural owner only drives these retained
        // observations, keeping branch-local pointers and past registers under
        // their original legality generate.
        val pushChecks = Bool().setName("formal_stream_fifocc_push_checks")
        val popChecks = Bool().setName("formal_stream_fifocc_pop_checks")
        val globalChecks = Bool().setName("formal_stream_fifocc_global_checks")
        pushChecks.dontSimplifyIt()
        popChecks.dontSimplifyIt()
        globalChecks.dontSimplifyIt()

        if (formalInvalidDepthOwner != null) {
          ParameterizedStructure.captureInto(
            formalInvalidDepthOwner,
            elabDepth,
            "StreamFifoCC formal invalid-depth observations"
          ) {
            pushChecks := True
            popChecks := True
            globalChecks := True
          }
        }

        val pushArea = new ClockingArea(pushClock) {
          ParameterizedStructure.captureInto(
            formalAlgorithmOwner,
            elabDepth,
            "StreamFifoCC formal push observations"
          ) {
            val decodedPop = retainedFromGray(
              pushCC.popPtrGray,
              "stream_fifocc_formal_push_decode"
            )
            val previousDecodedPop = past(decodedPop)
            val delta = retainPointerWidth(
              pushCC.pushPtr,
              decodedPop - previousDecodedPop
            )
            val grayStepOk = !(pastValid & changed(pushCC.popPtrGray)) ||
              delta <= depthBoundLike(
                delta,
                0,
                "stream_fifocc_push_gray_depth"
              )
            val encodingOk = pushCC.pushPtrGray === retainedToGray(
              pushCC.pushPtr,
              "stream_fifocc_formal_push_encode"
            )
            val occupancy = retainPointerWidth(
              pushCC.pushPtr,
              pushCC.pushPtr - decodedPop
            )
            val occupancyOk = occupancy <= depthBoundLike(
              occupancy,
              0,
              "stream_fifocc_push_depth"
            )
            pushChecks := grayStepOk && encodingOk && occupancyOk
          }
          assert(pushChecks)
        }

        val popCheckClock = if (withPopBufferedReset) popClock.copy(reset = pushClock.isResetActive) else popClock
        val popArea = new ClockingArea(popCheckClock) {
          ParameterizedStructure.captureInto(
            formalAlgorithmOwner,
            elabDepth,
            "StreamFifoCC formal pop observations"
          ) {
            val decodedPush = retainedFromGray(
              popCC.pushPtrGray,
              "stream_fifocc_formal_pop_decode"
            )
            val previousDecodedPush = past(decodedPush)
            val delta = retainPointerWidth(
              popCC.popPtr,
              decodedPush - previousDecodedPush
            )
            val grayStepOk = !(pastValid & changed(popCC.pushPtrGray)) ||
              delta <= depthBoundLike(
                delta,
                0,
                "stream_fifocc_pop_gray_depth"
              )
            val encodingOk = popCC.popPtrGray === retainedToGray(
              popCC.popPtr,
              "stream_fifocc_formal_pop_encode"
            )
            val occupancy = retainPointerWidth(
              popCC.popPtr,
              decodedPush - popCC.popPtr
            )
            val occupancyOk = occupancy <= depthBoundLike(
              occupancy,
              0,
              "stream_fifocc_pop_depth"
            )
            val forwarded = retainedFromGray(
              popCC.ptrToPush,
              "stream_fifocc_formal_forwarded_decode"
            )
            val popValid = io.pop.valid.asUInt
              .resize(elabPtrWidth)
              .setName("stream_fifocc_formal_pop_valid", weak = true)
              .dontSimplifyIt()
            val forwardedNext = retainPointerWidth(
              popCC.popPtr,
              forwarded + popValid
            )
            val forwardedOk = popCC.popPtr === forwardedNext
            popChecks := grayStepOk && encodingOk && occupancyOk && forwardedOk
          }
          assert(popChecks)
        }

        val globalArea = new ClockingArea(gclk) {
          ParameterizedStructure.captureInto(
            formalAlgorithmOwner,
            elabDepth,
            "StreamFifoCC formal global observations"
          ) {
            val occupancy = retainPointerWidth(
              pushCC.pushPtr,
              pushCC.pushPtr - popCC.popPtr
            )
            val readyOk = occupancy <= depthBoundLike(
              occupancy,
              -1,
              "stream_fifocc_ready_depth"
            )
            val fullOk = occupancy <= depthBoundLike(
              occupancy,
              0,
              "stream_fifocc_full_depth"
            )
            globalChecks := io.push.ready.mux(readyOk, fullOk)
          }
          assert(globalChecks)
        }

        }
      }
    }
  }
}

object StreamAccessibleFifo {
  def apply[T <: Data](input: Stream[T], output: Stream[T], length: Int = 2): StreamAccessibleFifo[T] = {
    val inst = new StreamAccessibleFifo(input.payloadType, length)
    inst.io.push << input
    output << inst.io.pop
    inst
  }
}

class StreamAccessibleFifo[T <: Data](dataType: HardType[T], length: Int) extends Component {
  val io = new Bundle {
    val push          = slave  Stream(dataType)
    val pop           = master Stream(dataType)
    val states        = Vec(master Flow(dataType), length)
  }

  val pushToFirst = (1 until length).map(io.states(_).valid).andR
  val pushToLast = (1 until length).map(~io.states(_).valid).andR
  val pushToPos = pushToLast ## (1 until length - 1).map(i => 
    (i+1 until length).map(io.states(_).valid).andR & ~io.states(i).valid).asBits ## pushToFirst
  val pushToPosBits = CombInit(pushToPos)
  when(io.pop.fire) {
    pushToPosBits := (pushToPos << 1).resized
  }

  val pushId = OHToUInt(pushToPosBits)
  val pushStreams = StreamDemux(io.push, pushId, length)
  def builder(prev: Stream[T], left: Int): List[Stream[T]] = {
    left match {
      case 0 => Nil
      case 1 => prev :: Nil
      case _ => prev :: builder({
        val id = length + 1 - left
        StreamMux(pushToPosBits(id).asUInt, Vec(prev, pushStreams(id))).stage
      }, left - 1)
    }
  }
  val connections = Vec(builder(pushStreams(0), length))
  
  io.pop << connections.last
  (io.states, connections).zipped.foreach((x, y) => {
    x.valid := y.valid
    x.payload := y.payload
  })
}

object StreamShiftChain {
  def apply[T <: Data](input: Stream[T], output: Stream[T], length: Int = 2): StreamShiftChain[T] = {
    val inst = new StreamShiftChain(input.payloadType, length)
    inst.io.push << input
    output << inst.io.pop
    inst
  }
}

class StreamShiftChain[T <: Data](dataType: HardType[T], length: Int) extends Component {
  val io = new Bundle {
    val push          = slave  Stream(dataType)
    val pop           = master Stream(dataType)
    val states        = Vec(master Flow(dataType), length)
    val clear         = in Bool() default(False)
  }

  def builder(prev: Stream[T], left: Int): List[Stream[T]] = {
    left match {
      case 0 => Nil
      case 1 => prev :: Nil
      case _ => prev :: builder(prev.m2sPipe(flush = io.clear), left - 1)
    }
  }
  val connections = Vec(builder(io.push, length))
  io.pop << connections.last
  (io.states, connections).zipped.foreach((x, y) => {
    x.valid := y.valid
    x.payload := y.payload
  })
}

object StreamCCByToggle {
  def apply[T <: Data](input: Stream[T], inputClock: ClockDomain, outputClock: ClockDomain): Stream[T] = {
    val c = new StreamCCByToggle[T](input.payload, inputClock, outputClock)
    c.io.input << input
    c.io.output
  }

  def apply[T <: Data](dataType: T, inputClock: ClockDomain, outputClock: ClockDomain): StreamCCByToggle[T] = {
    new StreamCCByToggle[T](dataType, inputClock, outputClock)
  }
}

class StreamCCByToggle[T <: Data](dataType: HardType[T], 
                                  inputClock: ClockDomain, 
                                  outputClock: ClockDomain, 
                                  withOutputBuffer : Boolean = true,
                                  withInputWait : Boolean = false,
                                  withOutputBufferedReset : Boolean = ClockDomain.crossClockBufferPushToPopResetGen.get,
                                  initPayload : => T = null.asInstanceOf[T]) extends Component {
  val io = new Bundle {
    val input = slave Stream (dataType())
    val output = master Stream (dataType())
  }

  val outHitSignal = Bool()

  val pushArea = inputClock on new Area {
    val hit = BufferCC(outHitSignal, False, inputAttributes = Seq(crossClockMaxDelay(1, useTargetClock = true)))
    val accept = Bool()
    val target = RegInit(False) toggleWhen(accept)
    val data = RegNextWhen(io.input.payload, accept)

    if (!withInputWait) {
      accept := io.input.fire
      io.input.ready := (hit === target)
    } else {
      val busy = RegInit(False) setWhen(accept) clearWhen(io.input.ready)
      accept := (!busy) && io.input.valid
      io.input.ready := busy && (hit === target)
    }
  }

  val finalOutputClock = outputClock.withOptionalBufferedResetFrom(withOutputBufferedReset)(inputClock)
  val popArea = finalOutputClock on new Area {
    val stream = cloneOf(io.input)

    val target = BufferCC(pushArea.target, False, inputAttributes = Seq(crossClockMaxDelay(1, useTargetClock = true)))
    val hit = RegNextWhen(target, stream.fire) init(False)

    val withCcHit = withInputWait && withOutputBuffer
    if(!withCcHit) outHitSignal := hit
    val wiw = withCcHit generate new Area {
      val ccHit = RegNextWhen(target, io.output.fire) init(False)
      outHitSignal := ccHit
    }

    stream.valid := (target =/= hit)
    stream.payload := pushArea.data

    io.output << (if(withOutputBuffer) stream.m2sPipe(holdPayload = true, crossClockData = true, initPayload = initPayload) else stream)
  }
}

/**
 * Enumeration to present order of slices.
 */
sealed trait SlicesOrder
/** Slice with lower bits process first */
object LOWER_FIRST extends SlicesOrder
/** Slice with higher bits process first */
object HIGHER_FIRST extends SlicesOrder

object StreamWidthAdapter {
  def apply[T <: Data,T2 <: Data](input : Stream[T],output : Stream[T2], endianness: Endianness = LITTLE, padding : Boolean = false): Unit = {
    val inputWidth: ElabInt = widthOfExpr(input.payload)
    val outputWidth: ElabInt = widthOfExpr(output.payload)
    ElabInt.requireSingleSymbolicRoot(
      "StreamWidthAdapter payload widths",
      inputWidth,
      outputWidth
    )
    if(inputWidth == outputWidth) {
      output.arbitrationFrom(input)
      output.payload.assignFromBits(input.payload.asBits)
    } else if(inputWidth > outputWidth) new Composite(input, "widthAdapter") {
      require(inputWidth % outputWidth == 0 || padding)
      val factorExpr = (inputWidth + outputWidth - 1) / outputWidth
      val factor = factorExpr.constantInt("StreamWidthAdapter downsize factor")
      val paddedInputWidth = outputWidth * factor
      val counter = Counter(factor,inc = output.fire)
      output.valid := input.valid
      endianness match {
        case `LITTLE` => output.payload.assignFromBits(input.payload.asBits.resize(paddedInputWidth).subdivideIn(factor slices).read(counter))
        case `BIG`    => output.payload.assignFromBits(input.payload.asBits.resize(paddedInputWidth).subdivideIn(factor slices).reverse.read(counter))
      }
      input.ready := output.ready && counter.willOverflowIfInc
    } else new Composite(input, "widthAdapter"){
      require(outputWidth % inputWidth == 0 || padding)
      val factorExpr = (outputWidth + inputWidth - 1) / inputWidth
      val factor = factorExpr.constantInt("StreamWidthAdapter upsize factor")
        val paddedOutputWidth = inputWidth * factor
        val counter = Counter(factor,inc = input.fire)
      val buffer  = Reg(Bits(paddedOutputWidth - inputWidth bits))
      when(input.fire){
        buffer := input.payload ## (buffer >> inputWidth.constantInt("StreamWidthAdapter input chunk width"))
      }
      output.valid := input.valid && counter.willOverflowIfInc
      endianness match {
        case `LITTLE` => output.payload.assignFromBits((input.payload ## buffer).resize(outputWidth))
        case `BIG`    => output.payload.assignFromBits((input.payload ## buffer).subdivideIn(factor slices).reverse.asBits().resize(outputWidth))
      }
      input.ready := !(!output.ready && counter.willOverflowIfInc)
    }
  }

  def apply[T <: Data,T2 <: Data](input : Stream[T],output : Stream[T2], order : SlicesOrder): Unit = {
    StreamWidthAdapter(input, output, order, false)
  }

  def apply[T <: Data,T2 <: Data](input : Stream[T],output : Stream[T2], order : SlicesOrder, padding : Boolean): Unit = {
    val endianness = order match {
      case HIGHER_FIRST => BIG
      case LOWER_FIRST => LITTLE
    }
    StreamWidthAdapter(input, output, endianness, padding)
  }

  def make[T <: Data, T2 <: Data](input : Stream[T], outputPayloadType : HardType[T2], order : SlicesOrder) : Stream[T2] = {
    val ret = Stream(outputPayloadType())
    StreamWidthAdapter(input,ret,order,false)
    ret
  }

  def make[T <: Data, T2 <: Data](input : Stream[T], outputPayloadType : HardType[T2], order : SlicesOrder, padding : Boolean) : Stream[T2] = {
    val ret = Stream(outputPayloadType())
    StreamWidthAdapter(input,ret,order,padding)
    ret
  }

  def make[T <: Data, T2 <: Data](input : Stream[T], outputPayloadType : HardType[T2], endianness: Endianness = LITTLE, padding : Boolean = false) : Stream[T2] = {
    val ret = Stream(outputPayloadType())
    StreamWidthAdapter(input,ret,endianness,padding)
    ret
  }

  def main(args: Array[String]) : Unit = {
    SpinalVhdl(new Component{
      val input = slave(Stream(Bits(4 bits)))
      val output = master(Stream(Bits(32 bits)))
      StreamWidthAdapter(input,output)
    })
  }
}

//padding=true allow having the input output width modulo not being 0
//earlyLast=true add the hardware required to handle sizer where the last input transaction come before the fullness of the output buffer
//Return an area with an dataMask signal specifying which chunk of the output stream is loaded with data, when the output stream is valid. (outputWidth > inputWidth && earlyLast)
object StreamFragmentWidthAdapter {
  def apply[T <: Data,T2 <: Data](input : Stream[Fragment[T]],
                                  output : Stream[Fragment[T2]],
                                  endianness: Endianness = LITTLE,
                                  padding : Boolean = false,
                                  earlyLast : Boolean = false) = new Area{
    val inputWidth = widthOf(input.fragment)
    val outputWidth = widthOf(output.fragment)
    val dataMask = Bits((outputWidth+inputWidth-1)/inputWidth bits)
    if(inputWidth == outputWidth){
      output.arbitrationFrom(input)
      output.payload.assignFromBits(input.payload.asBits)
      dataMask.setAll()
    } else if(inputWidth > outputWidth) new Composite(input, "widthAdapter") {
      require(inputWidth % outputWidth == 0 || padding)
      val factor = (inputWidth + outputWidth - 1) / outputWidth
      val paddedInputWidth = factor * outputWidth
      val counter = Counter(factor,inc = output.fire)
      output.valid := input.valid
      endianness match {
        case `LITTLE` => output.fragment.assignFromBits(input.fragment.asBits.resize(paddedInputWidth).subdivideIn(factor slices).read(counter))
        case `BIG`    => output.fragment.assignFromBits(input.fragment.asBits.resize(paddedInputWidth).subdivideIn(factor slices).reverse.read(counter))
      }
      output.last := input.last && counter.willOverflowIfInc
      input.ready := output.ready && counter.willOverflowIfInc
      dataMask.setAll()
    } else new Composite(input, "widthAdapter"){
      require(outputWidth % inputWidth == 0 || padding)
      val factor  = (outputWidth + inputWidth - 1) / inputWidth
      val paddedOutputWidth = factor * inputWidth
      val counter = Counter(factor,inc = input.fire)
      val buffer  = Reg(Bits(paddedOutputWidth - inputWidth bits))
      val sendIt = CombInit(counter.willOverflowIfInc)
      output.valid := input.valid && sendIt
      output.last := input.last
      input.ready := output.ready || !sendIt

      if(earlyLast){
        sendIt setWhen(input.last)
        when(input.valid && input.last && output.ready) {
          counter.clear()
        }
      }

      val data = CombInit(input.fragment ## buffer)
      endianness match {
        case `LITTLE` => output.fragment.assignFromBits(data.resize(outputWidth))
        case `BIG`    => output.fragment.assignFromBits(data.subdivideIn(factor slices).reverse.asBits().resize(outputWidth))
      }

      earlyLast match {
        case false => {
          dataMask.setAll()
          when(input.fire) {
            buffer := input.fragment ## (buffer >> inputWidth)
          }
        }
        case true  => {
          endianness match {
            case `LITTLE` => for((bit, id) <- dataMask.asBools.zipWithIndex) bit := counter >= id
            case `BIG`    => for((bit, id) <- dataMask.asBools.reverse.zipWithIndex) bit := counter >= id
          }
          for((bit, id) <- dataMask.asBools.zipWithIndex) bit := counter >= id

          when(input.fire) {
            whenIndexed(buffer.subdivideIn(inputWidth bits), counter, relaxedWidth = true) {
              _ := input.fragment.asBits
            }
          }
          whenIndexed(data.subdivideIn(inputWidth bits).dropRight(1), counter, relaxedWidth = true) {
            _ := input.fragment.asBits
          }
        }
      }
    }
  }

  def apply[T <: Data,T2 <: Data](input : Stream[Fragment[T]],output : Stream[Fragment[T2]], order : SlicesOrder): Unit = {
    StreamFragmentWidthAdapter(input, output, order, false)
  }

  def apply[T <: Data,T2 <: Data](input : Stream[Fragment[T]],output : Stream[Fragment[T2]], order : SlicesOrder, padding : Boolean): Unit = {
    val endianness = order match {
      case HIGHER_FIRST => BIG
      case LOWER_FIRST => LITTLE
    }
    StreamFragmentWidthAdapter(input, output, endianness, padding)
  }

  def make[T <: Data, T2 <: Data](input : Stream[Fragment[T]], outputPayloadType : HardType[T2], order : SlicesOrder) : Stream[Fragment[T2]] = {
    val ret = Stream(Fragment(outputPayloadType()))
    StreamFragmentWidthAdapter(input,ret,order,false)
    ret
  }

  def make[T <: Data, T2 <: Data](input : Stream[Fragment[T]], outputPayloadType : HardType[T2], order : SlicesOrder, padding : Boolean) : Stream[Fragment[T2]] = {
    val ret = Stream(Fragment(outputPayloadType()))
    StreamFragmentWidthAdapter(input,ret,order,padding)
    ret
  }

  def make[T <: Data, T2 <: Data](input : Stream[Fragment[T]], outputPayloadType : HardType[T2], endianness: Endianness = LITTLE, padding : Boolean = false, earlyLast : Boolean = false) : Stream[Fragment[T2]] = {
    val ret = Stream(Fragment(outputPayloadType()))
    StreamFragmentWidthAdapter(input,ret,endianness,padding,earlyLast)
    ret
  }
}

case class StreamFifoMultiChannelPush[T <: Data](payloadType : HardType[T], channelCount : Int) extends Bundle with IMasterSlave {
  val channel = Bits(channelCount bits)
  val full = Bool()
  val stream = Stream(payloadType)

  override def asMaster(): Unit = {
    out(channel)
    master(stream)
    in(full)
  }
}

case class StreamFifoMultiChannelPop[T <: Data](payloadType : HardType[T], channelCount : Int) extends Bundle with IMasterSlave {
  val channel = Bits(channelCount bits)
  val empty   = Bits(channelCount bits)
  val stream  = Stream(payloadType)

  override def asMaster(): Unit = {
    out(channel)
    slave(stream)
    in(empty)
  }

  def toStreams(withCombinatorialBuffer : Boolean) = new Area{
    val bufferIn, bufferOut = Vec(Stream(payloadType), channelCount)
    (bufferOut, bufferIn).zipped.foreach((s, m) => if(withCombinatorialBuffer) s </< m else s <-< m)

    val needRefill = B(bufferIn.map(_.ready))
    val selOh = OHMasking.first(needRefill & ~empty) //TODO
    val nonEmpty = (~empty).orR
    channel := selOh
    for((feed, sel) <- (bufferIn, selOh.asBools).zipped){
      feed.valid := sel && nonEmpty
      feed.payload := stream.payload
    }
    stream.ready := (selOh & B(bufferIn.map(_.ready))).orR
  }.setCompositeName(this,"toStreams", true).bufferOut

}

//Emulate multiple fifo but with one push,one pop port and a shared storage
//io.availability has one cycle latency
case class StreamFifoMultiChannelSharedSpace[T <: Data](payloadType : HardType[T], channelCount : Int, depth : Int, withAllocationFifo : Boolean = false) extends Component{
  assert(isPow2(depth))
  val io = new Bundle {
    val push = slave(StreamFifoMultiChannelPush(payloadType, channelCount))
    val pop  = slave(StreamFifoMultiChannelPop(payloadType, channelCount))
    val availability = out UInt(log2Up(depth) + 1 bits)
  }
  val ptrWidth = log2Up(depth)

  val payloadRam = Mem(payloadType(), depth)
  val nextRam = Mem(UInt(ptrWidth bits), depth)

  val full = False
  io.push.full := full
  io.push.stream.ready := !full

  val pushNextEntry = UInt(ptrWidth bits)
  val popNextEntry = nextRam.wordType()



  val channels = for (channelId <- 0 until channelCount) yield new Area {
    val valid = RegInit(False)
    val headPtr = Reg(UInt(ptrWidth bits))
    val lastPtr = Reg(UInt(ptrWidth bits))
    val lastFire = False
    when(io.pop.stream.fire && io.pop.channel(channelId)) {
      headPtr := popNextEntry
      when(headPtr === lastPtr){
        lastFire := True
        valid := False
      }
    }

    when(!valid || lastFire){
      headPtr := pushNextEntry
    }

    when(io.push.stream.fire && io.push.channel(channelId)) {
      lastPtr := pushNextEntry
      valid := True
    }
    io.pop.empty(channelId) := !valid
  }

  val pushLogic = new Area{
    val previousAddress = MuxOH(io.push.channel, channels.map(_.lastPtr))
    when(io.push.stream.fire) {
      payloadRam.write(pushNextEntry, io.push.stream.payload)
      when((channels.map(_.valid).asBits() & io.push.channel).orR) {
        nextRam.write(previousAddress, pushNextEntry)
      }
    }
  }

  val popLogic = new Area {
    val readAddress = channels.map(_.headPtr).read(OHToUInt(io.pop.channel))
    io.pop.stream.valid := (io.pop.channel & ~io.pop.empty).orR
    io.pop.stream.payload := payloadRam.readAsync(readAddress)
    popNextEntry := nextRam.readAsync(readAddress)
  }

  val allocationByCounter = !withAllocationFifo generate new Area{
    val allocationPtr = Reg(UInt(ptrWidth bits)) init(0)

    when(io.push.stream.fire) {
      allocationPtr := allocationPtr + 1
    }

    val onChannels = for(c <- channels) yield new Area{
      full setWhen(c.valid && allocationPtr === c.headPtr)
      val wasValid = RegNext(c.valid) init(False)
      val availability = RegNext(c.headPtr-allocationPtr)
    }

    val (availabilityValid, availabilityValue) = onChannels.map(c => (c.wasValid, c.availability)).reduceBalancedTree{case (a,b) => (a._1 || b._1, (a._1 && (!b._1 || a._2 < b._2)) ? a._2 | b._2)}
    io.availability := (availabilityValid ? availabilityValue | depth)

    pushNextEntry := allocationPtr
  }


  val allocationByFifo = withAllocationFifo generate new Area{
    ???
  }


}

object StreamFifoMultiChannelBench extends App{
  val payloadType = HardType(Bits(8 bits))
  class BenchFpga(channelCount : Int) extends Rtl{
    override def getName(): String = "Bench" + channelCount
    override def getRtlPath(): String = getName() + ".v"
    SpinalVerilog(new Component{
      val push = slave(StreamFifoMultiChannelPush(payloadType, channelCount))
      val pop  = slave(StreamFifoMultiChannelPop(payloadType, channelCount))
      val fifo = StreamFifoMultiChannelSharedSpace(payloadType, channelCount, 32)

      fifo.io.push.channel := RegNext(push.channel)
      push.full := RegNext(fifo.io.push.full)
      fifo.io.push.stream  <-/< push.stream

      fifo.io.pop.channel := RegNext(pop.channel)
      pop.empty := RegNext(fifo.io.pop.empty)
      pop.stream  <-/<  fifo.io.pop.stream

      setDefinitionName(BenchFpga.this.getName())
    })
  }
  class BenchFpga2(channelCount : Int) extends Rtl{
    override def getName(): String = "BenchToStream" + channelCount
    override def getRtlPath(): String = getName() + ".v"
    SpinalVerilog(new Component{
      val push = slave(StreamFifoMultiChannelPush(payloadType, channelCount))
      val fifo = StreamFifoMultiChannelSharedSpace(payloadType, channelCount, 32)

      fifo.io.push.channel := RegNext(push.channel)
      push.full := RegNext(fifo.io.push.full)
      fifo.io.push.stream  <-/< push.stream

      setDefinitionName(BenchFpga2.this.getName())

      val outputs = fifo.io.pop.toStreams(false).map(_.s2mPipe().asMaster())
    })
  }


  val rtls = List(2,4,8).map(width => new BenchFpga(width)) ++ List(2,4,8).map(width => new BenchFpga2(width))

  val targets = EfinixStdTargets() ++ XilinxStdTargets() ++ AlteraStdTargets()


  Bench(rtls, targets)
}

object StreamTransactionCounter {
    def apply[T <: Data, T2 <: Data](
        trigger: Stream[T],
        target: Stream[T2],
        count: UInt,
        noDelay: Boolean = false
    ): StreamTransactionCounter = {
        val inst = new StreamTransactionCounter(count.getWidth, noDelay)
        inst.io.ctrlFire := trigger.fire
        inst.io.targetFire := target.fire
        inst.io.count := count
        inst
    }
}

class StreamTransactionCounter(
    countWidth: Int,
    noDelay: Boolean = false
) extends Component {
    val io = new Bundle {
        val ctrlFire   = in Bool ()
        val targetFire = in Bool ()
        val available  = out Bool ()
        val count      = in UInt (countWidth bits)
        val working    = out Bool ()
        val last       = out Bool ()
        val done       = out Bool ()
        val value      = out UInt (countWidth bit)
    }

    val countReg = RegNextWhen(io.count, io.ctrlFire)
    val counter  = Counter(io.count.getBitsWidth bits)
    val expected = if(noDelay) { countReg.getAheadValue() } else { CombInit(countReg) }

    val lastOne = counter >= expected
    val running = Reg(Bool()) init False
    val working = CombInit(running)

    val done         = lastOne && io.targetFire
    if(noDelay){
      when(io.ctrlFire) { working := True }
      when(done) { running := False }
      .otherwise { running := working }
    } else {
      when (io.ctrlFire) { running := True }
      .elsewhen(done) { running := False }
    }

    when(done) {
        counter.clear()
    } elsewhen (io.targetFire & working) {
        counter.increment()
    }

    io.working := working
    io.last := lastOne & working
    io.done := done & working
    io.value := counter
    if(noDelay) { io.available := !running } else { io.available := !working | io.done }

    def formalAsserts() = new Composite(this, "asserts") {
      val startedReg = Reg(Bool()) init False
      when(io.targetFire & io.working) {
        startedReg := True
      }
      when(done) { startedReg := False }
      assert(startedReg === (counter.value > 0))

      when(!io.working) { assert(counter.value === 0) }
      assert(counter.value <= expected)
    }
}

object StreamTransactionExtender {
    def apply[T <: Data](input: Stream[T], count: UInt, noDelay: Boolean = false)(
        implicit driver: (UInt, T, Bool) => T = (_: UInt, p: T, _: Bool) => p
    ): Stream[T] = {
        val c = new StreamTransactionExtender(input.payloadType, input.payloadType, count.getBitsWidth, noDelay, driver)
        c.io.input << input
        c.io.count := count
        c.io.output
    }

    def apply[T <: Data, T2 <: Data](input: Stream[T], output: Stream[T2], count: UInt)(
        driver: (UInt, T, Bool) => T2
    ): StreamTransactionExtender[T, T2] = StreamTransactionExtender(input, output, count, false)(driver)

    def apply[T <: Data, T2 <: Data](input: Stream[T], output: Stream[T2], count: UInt, noDelay: Boolean)(
        driver: (UInt, T, Bool) => T2
    ): StreamTransactionExtender[T, T2] = {
        val c = new StreamTransactionExtender(input.payloadType, output.payloadType, count.getBitsWidth, noDelay, driver)
        c.io.input << input
        c.io.count := count
        output << c.io.output
        c
    }
}

/* Extend one input transfer into serveral outputs, io.count represent delivering output (count + 1) times. */
class StreamTransactionExtender[T <: Data, T2 <: Data](
    dataType: HardType[T],
    outDataType: HardType[T2],
    countWidth: Int,
    noDelay: Boolean,
    driver: (UInt, T, Bool) => T2
) extends Component {
    val io = new Bundle {
        val count   = in UInt (countWidth bit)
        val input   = slave Stream dataType
        val output  = master Stream outDataType
        val working = out Bool ()
        val first   = out Bool ()
        val last    = out Bool ()
        val done    = out Bool ()
    }

    val counter  = StreamTransactionCounter(io.input, io.output, io.count, noDelay)
    val payloadReg  = Reg(io.input.payloadType)
    val lastOne  = counter.io.last
    val count = counter.io.value
    val payload = if(noDelay) CombInit(payloadReg.getAheadValue) else CombInit(payloadReg)

    when(io.input.fire) {
        payloadReg := io.input.payload
    }

    io.output.payload := driver(count, payload, lastOne)
    io.output.valid := counter.io.working
    io.input.ready := counter.io.available
    io.last := lastOne
    io.done := counter.io.done
    io.first := (counter.io.value === 0) && counter.io.working
    io.working := counter.io.working
    
    def formalAsserts() = counter.formalAsserts()
}

object StreamUnpacker {

  /** Decomposes a Data field into a map of words to Word-relative range -> Field-relative range. The starting bit
    * is any absolute position within some set of words,
    *
    * For example, a word with 16 bits starting at bit 4 decomposed into 8 bit words would result in:
    * {
    *   0 -> ((4 to 7) -> (0 to 3)),
    *   1 -> ((0 to 7) -> (4 to 11)),
    *   2 -> ((0 to 3) -> (12 to 15))
    * }
    *
    * @param wordWidth Word width to decompose into it
    * @param field Data to decompose
    * @param startBit Bit to start at, as absolute position (may be greater than `wordWidth`)
    * @return Map of word index to Word-relative range -> Field-relative range
    */
  def decomposeField(field: Data, startBit: Int, wordWidth: Int): Map[Int, (Range, Range)] = {
    val lastBit = startBit + field.getBitsWidth - 1
    // Determine which words the field falls into
    val firstWord = startBit / wordWidth
    val lastWord = (field.getBitsWidth + startBit - 1) / wordWidth

    (firstWord to lastWord).map { wordInd =>
      // Make the current word's range
      val curWord = (wordInd * wordWidth) until ((wordInd + 1) * wordWidth)

      // Find the largest range of the field that fits into the word, in absolute bits
      // This is merely clipping the field first and last bits by the current word's min and max
      val absWordRange = startBit.max(curWord.min) to lastBit.min(curWord.max)

      // Find the range that the field's word-indexed range maps to in the field itself
      // Just back off the starting bit from the word-indexed range
      val relFieldRange = absWordRange.min - startBit to absWordRange.max - startBit

      // Convert the absolute word range into a relative one
      val relWordRange = absWordRange.min - curWord.min to absWordRange.max - curWord.min

      wordInd -> (relWordRange -> relFieldRange)
    }.toMap
  }

  /** Converts a layout of Data and starting bit pairs into a map of word range to Data range slices for each word
    * that the Data spans, indexed by each Data. The return type is a 2D map relating each Data to each word index.
    * The range pairs for each word index represent which bits of the word (local to the width of the word) map to the
    * bits of Data that lie within the word.
    *
    * @param wordWidth Width of the Stream's words
    * @param layout List of Data to starting bit pairs
    * @return Map of Data, Map of word index to word range, Data range pair
    */
  def layoutToWordMap(
      wordWidth: Int,
      layout: List[(Data, Int)]
  ): mutable.LinkedHashMap[Data, Map[Int, (Range, Range)]] = {
    layout.map { case (data, startBit) =>
      data -> decomposeField(data, startBit, wordWidth)
    }.toMapLinked
  }

  /** Unpacks a Stream given a layout of Data fields.
    * Field layout is accepted as pairs of Data and their start bits. Starting bits are interpreted as absolute bit
    * positions within a multi-word layout. The StreamUnpacker will read as many words from `input` as necessary to
    * unpack all fields. Fields that exceed a word width will be wrapped into as many subsequent words needed.
    *
    * @param input Stream to read from
    * @param layout List of Data fields and their start bits
    * @tparam T Stream Data type
    * @return Unpacker instance
    */
  def apply[T <: Data](input: Stream[T], layout: List[(Data, Int)]): StreamUnpacker[T] = {
    require(layout.nonEmpty)

    new StreamUnpacker[T](input, layoutToWordMap(input.payloadType.getBitsWidth, layout))
  }

  /** Unpacks a Stream into a given PackedBundle
    * The StreamUnpacker will read as many words from `input` as necessary to unpack all fields. Fields that exceed a
    * word width will be wrapped into as many subsequent words needed.
    *
    * @param input Stream to read from
    * @param packedbundle PackedBundle to unpack into
    * @tparam T Stream Data type
    * @tparam B PackedBundle type
    * @return Unpacker instance
    */
  def apply[T <: Data, B <: PackedBundle](input: Stream[T], packedbundle: B): StreamUnpacker[T] = {
    // Defer to the other `apply` method with a layout derived from the PackedBundle's mappings
    StreamUnpacker(
      input,
      packedbundle.mappings.map { case (range, data) =>
        data -> range.min
      }.toList
    )
  }
}

/** Unpacks `stream`'s words into the given `layout`'s Data.
  * `stream` is directly driven by this area.
  * `layout` Data are driven through a register.
  *
  * `io.start` starts unpacking
  * `io.dones` is set of bits indicating when the associated Data in `layout` is unpacked.
  * `io.allDone` indicates when the last word has been unpacked.
  *
  * Use the companion object `StreamUnpacker` to create an instance.
  */
class StreamUnpacker[T <: Data](
    stream: Stream[T],
    layout: mutable.LinkedHashMap[Data, Map[Int, (Range, Range)]]
) extends Area {

  val io = new Bundle {
    val start = Bool()
    val dones = Bits(layout.keys.size bits)
    val allDone = Bool()
  }

  private val fields = layout.keys.toList

  // Make output registers, as bits
  private val rData = fields.map { d =>
    val regData = Reg(cloneOf(d.asBits)) init B(0)
    d.assignFromBits(regData)
    regData
  }

  private val running = Reg(Bool()) init False
  private val dones = Reg(Bits(fields.length bits)) init B(0)
  private val allDone = Reg(Bool()) init False
  private val counter = Counter(layout.values.flatMap(_.keys).max + 1)

  private val inFlow = stream.takeWhen(running).toFlow

  when(io.start) {
    counter.clear()
    running := True
  }

  // Dones are only asserted for a single cycle
  dones.clearAll()
  allDone.clear()

  when(inFlow.valid & running) {
    counter.increment()

    // Latch any data in the current word
    layout.foreach { case (layoutData, wordMap) =>
      wordMap.foreach { case (wordInd, (wordRange, dataRange)) =>
        when(counter.value === wordInd) {
          rData(fields.indexOf(layoutData))(dataRange) := inFlow.payload.asBits(wordRange)
        }
      }

      // Flag done at the last word of the data
      dones(fields.indexOf(layoutData)).setWhen(counter.value === wordMap.keys.max)
    }

    when(counter.willOverflowIfInc) {
      running.clear()
      allDone.set()
    }
  }

  // Output mapping
  io.dones := dones
  io.allDone := allDone
}

object StreamPacker {

  /** Packs a given layout of Data fields into a Stream.
    * Field layout is accepted as pairs of Data and their start bits. Starting bits are interpreted as absolute bit
    * positions within a multi-word layout. The StreamPacker will write as many words to `output` as necessary to pack
    * all fields. Fields that exceed a word width will be wrapped into as many subsequent words needed.
    *
    * Note, no overlap checking is performed.
    *
    * @param output Stream to write to
    * @param layout List of Data fields and their start bits
    * @tparam T Stream Data type
    * @return StreamPacker instance
    */
  def apply[T <: Data](output: Stream[T], layout: List[(Data, Int)]): StreamPacker[T] = {
    require(layout.nonEmpty)

    new StreamPacker[T](output, StreamUnpacker.layoutToWordMap(output.payloadType.getBitsWidth, layout))
  }

  /** Packs a given PackedBundle into a Stream.
    * The StreamPacker will write as many words to `output` as necessary to pack
    * all fields. Fields that exceed a word width will be wrapped into as many subsequent words needed.
    *
    * Note, no overlap checking is performed.
    *
    * @param output Stream to write to
    * @param packedbundle PackedBundle to pack from
    * @tparam T Stream Data type
    * @tparam B PackedBundel type
    * @return StreamPacker instance
    */
  def apply[T <: Data, B <: PackedBundle](output: Stream[T], packedbundle: B): StreamPacker[T] = {
    // Defer to the other `apply` method with a layout derived from the PackedBundle's mappings
    StreamPacker(
      output,
      packedbundle.mappings.map { case (range, data) =>
        data -> range.min
      }.toList
    )
  }
}

/** Packs `layout`'s Data into the given `stream`
  *
  * `stream` is directly driven by this area.
  *
  * `layout` Data is read directly
  *
  * `io.start` indicates when to start packing. All `layout`'s Data is registered before packing.
  *
  * `io.done` indicates when the last word has been packed.
  *
  * Use the companion object `StreamPacker` to create an instance.
  */
class StreamPacker[T <: Data](
    stream: Stream[T],
    layout: mutable.LinkedHashMap[Data, Map[Int, (Range, Range)]]
) extends Area {

  require(layout.nonEmpty)

  private val dataIn = layout.keys.toList

  val io = new Bundle {
    val start = Bool()
    val done = Bool()
  }

  private val counter = Counter(layout.values.flatMap(_.keys).max + 1)
  private val running = RegInit(False)

  private val outValid = RegInit(False)
  private val outDone = RegInit(False)
  private val nextWord = Reg(stream.payloadType)

  private val buffer = RegNextWhen(Vec(dataIn.map(_.asBits)), io.start)

  when(io.start) {
    running.set()
    counter.clear()
  }

  when(stream.fire) {
    outValid.clear()
    outDone.clear()
  }

  when(running && stream.isFree) {
    when(counter.willOverflowIfInc) {
      running.clear()
      outDone.set()
    } otherwise {
      counter.increment()
    }

    // Generate the word
    nextWord := nextWord.getZero
    outValid := True

    layout.foreach { case (layoutData, wordMap) =>
      wordMap.foreach { case (wordInd, (wordRange, dataRange)) =>
        when(counter.value === wordInd) {
          nextWord.assignFromBits(
            buffer(dataIn.indexOf(layoutData)).asBits(dataRange),
            wordRange.max,
            wordRange.min
          )
        }
      }
    }
  }

  // Connect the outputs
  stream.payload := nextWord
  stream.valid := outValid
  io.done := outDone
}



class StreamDelay[T <: Data](val payloadType : HardType[T], val delay: Int, val pendingMax : Option[Int] = Option.empty[Int], val timestampWidth : Int = 16) extends Component{
  val io = new Bundle{
    val push = slave Stream(payloadType())
    val pop = master Stream(payloadType())
  }
  case class StreamDelayWord() extends Bundle{
    val data = payloadType()
    val timestamp = UInt(timestampWidth bits)
  }
  val bypass = (delay == 0) generate {
    io.push >> io.pop
  }
  val staged = (delay == 1) generate {
    io.push >-> io.pop
  }
  val withFifo = (delay >= 2) generate {
    val time = CounterFreeRun(BigInt(1) << timestampWidth)
    val fifo = StreamFifo(StreamDelayWord(), pendingMax.getOrElse(1 << log2Up(delay)), latency = Math.min(delay, 2))
    fifo.io.push.arbitrationFrom(io.push)
    fifo.io.push.data := io.push.payload
    fifo.io.push.timestamp := time.value + delay

    val halt = (time - fifo.io.pop.timestamp).msb
    val halted = fifo.io.pop.translateWith(fifo.io.pop.data).haltWhen(halt)
    halted >> io.pop
  }
}
