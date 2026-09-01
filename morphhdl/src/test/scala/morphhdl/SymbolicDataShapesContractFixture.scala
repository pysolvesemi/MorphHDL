package morphhdl

import spinal.core._
import spinal.lib._

import morphhdl.frontend.HdlInt

private[morphhdl] object SymbolicDataShapesContractFixture {
  final case class Config(width: HdlInt)

  private final case class Payload(width: HdlInt) extends Bundle {
    val bits = morphhdl.frontend.Bits(width bits)
    val uint = morphhdl.frontend.UInt(width bits)
    val sint = morphhdl.frontend.SInt(width bits)
  }

  /** One ordinary SpinalHDL component exercises symbolic shape retention.
    * Its logic is deliberately limited to equal-shape leaf assignments and
    * one unconditional, uninitialized register path.
    */
  private final class SymbolicDataShapes(
      config: Config,
      reverseConstructionOrder: Boolean
  ) extends Component {
    setDefinitionName("SymbolicDataShapes")

    private val bitsType = morphhdl.frontend.HardType(morphhdl.frontend.Bits(config.width bits))
    private val uintType = morphhdl.frontend.HardType(morphhdl.frontend.UInt(config.width bits))
    private val sintType = morphhdl.frontend.HardType(morphhdl.frontend.SInt(config.width bits))
    private val payloadType = HardType(Payload(config.width))

    private var bitsIn: Bits = null
    private var bitsOut: Bits = null
    private var uintIn: UInt = null
    private var uintOut: UInt = null
    private var sintIn: SInt = null
    private var sintOut: SInt = null
    private var bundleIn: Payload = null
    private var bundleOut: Payload = null
    private var vecIn: Vec[Payload] = null
    private var vecOut: Vec[Payload] = null
    private var streamIn: Stream[Payload] = null
    private var streamOut: Stream[Payload] = null
    private var flowIn: Flow[Payload] = null
    private var flowOut: Flow[Payload] = null
    private var registerOut: Payload = null
    private var clk: Bool = null

    private def createScalarPorts(): Unit = {
      if (reverseConstructionOrder) {
        bitsOut = out(bitsType()).setName("bits_out")
        bitsIn = in(morphhdl.frontend.cloneOf(bitsOut)).setName("bits_in")
        uintOut = out(uintType()).setName("uint_out")
        uintIn = in(morphhdl.frontend.cloneOf(uintOut)).setName("uint_in")
        sintOut = out(sintType()).setName("sint_out")
        sintIn = in(morphhdl.frontend.cloneOf(sintOut)).setName("sint_in")
      } else {
        bitsIn = in(bitsType()).setName("bits_in")
        bitsOut = out(morphhdl.frontend.cloneOf(bitsIn)).setName("bits_out")
        uintIn = in(uintType()).setName("uint_in")
        uintOut = out(morphhdl.frontend.cloneOf(uintIn)).setName("uint_out")
        sintIn = in(sintType()).setName("sint_in")
        sintOut = out(morphhdl.frontend.cloneOf(sintIn)).setName("sint_out")
      }
    }

    private def createBundlePorts(): Unit = {
      if (reverseConstructionOrder) {
        bundleOut = out(payloadType()).setName("bundle_out")
        bundleIn = in(payloadType()).setName("bundle_in")
      } else {
        bundleIn = in(payloadType()).setName("bundle_in")
        bundleOut = out(payloadType()).setName("bundle_out")
      }
    }

    private def createVecPorts(): Unit = {
      // Keep the original concrete two-element logical Vec in this long-lived
      // fixture. Its typed element leaves now exercise Increment 53f's generic
      // single-packed-vector publication; symbolic depth is covered by the
      // focused typed-Vec fixtures rather than changing this contract's shape.
      if (reverseConstructionOrder) {
        vecOut = out(Vec(payloadType, 2)).setName("vec_out")
        vecIn = in(Vec(payloadType, 2)).setName("vec_in")
      } else {
        vecIn = in(Vec(payloadType, 2)).setName("vec_in")
        vecOut = out(Vec(payloadType, 2)).setName("vec_out")
      }
    }

    private def createStreamPorts(): Unit = {
      if (reverseConstructionOrder) {
        streamOut = master(Stream(payloadType)).setName("stream_out")
        streamIn = slave(Stream(payloadType)).setName("stream_in")
      } else {
        streamIn = slave(Stream(payloadType)).setName("stream_in")
        streamOut = master(Stream(payloadType)).setName("stream_out")
      }
    }

    private def createFlowPorts(): Unit = {
      if (reverseConstructionOrder) {
        flowOut = master(Flow(payloadType)).setName("flow_out")
        flowIn = slave(Flow(payloadType)).setName("flow_in")
      } else {
        flowIn = slave(Flow(payloadType)).setName("flow_in")
        flowOut = master(Flow(payloadType)).setName("flow_out")
      }
    }

    private def createRegisterPorts(): Unit = {
      if (reverseConstructionOrder) {
        registerOut = out(payloadType()).setName("register_out")
        clk = in(Bool()).setName("clk")
      } else {
        clk = in(Bool()).setName("clk")
        registerOut = out(payloadType()).setName("register_out")
      }
    }

    private val portBuilders = Vector[() => Unit](
      () => createScalarPorts(),
      () => createBundlePorts(),
      () => createVecPorts(),
      () => createStreamPorts(),
      () => createFlowPorts(),
      () => createRegisterPorts()
    )
    (if (reverseConstructionOrder) portBuilders.reverse else portBuilders).foreach(_())

    bitsOut := bitsIn
    uintOut := uintIn
    sintOut := sintIn

    val internalPayload = payloadType().setName("internal_payload")
    internalPayload := bundleIn
    bundleOut := internalPayload

    vecOut := vecIn

    streamOut.valid := streamIn.valid
    streamOut.payload := streamIn.payload
    streamIn.ready := streamOut.ready

    flowOut.valid := flowIn.valid
    flowOut.payload := flowIn.payload

    private val registerClockDomain = ClockDomain(clock = clk)
    private val registerArea = new ClockingArea(registerClockDomain) {
      val payloadRegister = Reg(payloadType()).setName("payload_register")
      payloadRegister := bundleIn
      registerOut := payloadRegister
    }
  }

  def component(reverseConstructionOrder: Boolean): Component = {
    componentWithWidth(
      HdlInt.param("WIDTH", default = 8, min = 1, max = 64),
      reverseConstructionOrder
    )
  }

  private[morphhdl] def componentWithWidth(
      width: HdlInt,
      reverseConstructionOrder: Boolean
  ): Component =
    new SymbolicDataShapes(Config(width), reverseConstructionOrder)
}
