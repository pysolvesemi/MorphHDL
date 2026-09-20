package nativeapplication

import morphhdl.frontend.{formalParam, HdlBool, HdlInt}
import spinal.core._
import spinal.lib._

/** Application-shaped source contract for the native typed call surface.
  *
  * Hardware is constructed only through ordinary SpinalHDL imports. MorphHDL
  * contributes parameter declarations and the explicit child-formal binding;
  * companion-scope forward conversions select the native ElabInt/ElabBool
  * lanes without exposing a concrete witness.
  */
object NativeTypedLibraryCallSurfaceFixture {
  object OverloadProbe {
    def integer(value: Int): String = "int"
    def integer(value: ElabInt): String = "elab-int"
    def boolean(value: Boolean): String = "boolean"
    def boolean(value: ElabBool): String = "elab-bool"

    def legacy(value: HdlInt): String = "hdl-int"
    def legacy(value: ElabInt): String = "elab-int"
  }

  final class FeatureSink(invert: Boolean) extends Component {
    setDefinitionName(
      if (invert) "NativeTypedFeatureDisabledSink"
      else "NativeTypedFeatureEnabledSink"
    )
    val din = in(Bits(8 bits)).setName("din")
    val observed = out(Bool()).setName("observed")
    observed := (if (invert) ~din else din).orR
  }

  final class Child(actualWidth: HdlInt) extends Component {
    setDefinitionName("NativeTypedLibraryChild")

    @dontName
    private val width = formalParam(
      actualWidth,
      "WIDTH",
      minimum = BigInt(1),
      maximum = BigInt(32)
    )

    val din = in(Bits(width bits)).setName("din")
    val dout = out(Bits(width bits)).setName("dout")
    dout := din
  }

  final class ParameterizedTop(
      width: HdlInt,
      depth: HdlInt,
      enabled: _root_.spinal.core.ElabBool
  ) extends Component {
    setDefinitionName("NativeTypedLibraryTop")

    val increment = in(Bool()).setName("increment")
    val readEnable = in(Bool()).setName("read_enable")
    val writeEnable = in(Bool()).setName("write_enable")
    val streamInValid = in(Bool()).setName("stream_in_valid")
    val streamInReady = out(Bool()).setName("stream_in_ready")
    val streamInPayload = in(Bits(width bits)).setName("stream_in_payload")
    val streamOutValid = out(Bool()).setName("stream_out_valid")
    val streamOutReady = in(Bool()).setName("stream_out_ready")
    val streamOutPayload = out(Bits(width bits)).setName("stream_out_payload")
    val flowInValid = in(Bool()).setName("flow_in_valid")
    val flowInPayload = in(Bits(width bits)).setName("flow_in_payload")
    val flowOutValid = out(Bool()).setName("flow_out_valid")
    val flowOutPayload = out(Bits(width bits)).setName("flow_out_payload")
    val featureInput = in(Bits(8 bits)).setName("feature_input")

    val counter: Counter = Counter(depth, increment)
    val count = out(UInt(depth.addressWidth bits)).setName("count")
    count := counter.value

    val memory: Mem[Bits] = Mem(HardType(Bits(width bits)), depth)
    memory.setName("memory")
    val memoryAddress = in(memory.addressType()).setName("memory_address")
    val memoryWriteData = in(Bits(width bits)).setName("memory_write_data")
    val memoryReadData = out(Bits(width bits)).setName("memory_read_data")
    val memoryReadWord = memory.readSync(
      memoryAddress,
      enable = readEnable,
      readUnderWrite = readFirst
    )
    memory.write(memoryAddress, memoryWriteData, enable = writeEnable)
    memoryReadData := memoryReadWord

    val vector: Vec[Bits] = Vec(Bits(width bits), depth)
    vector.setName("vector")
    val vectorInput = in(Vec(Bits(width bits), depth)).setName("vector_input")
    val vectorOutput = out(Vec(Bits(width bits), depth)).setName("vector_output")
    vector := vectorInput
    vectorOutput := vector

    val stream: Stream[Bits] = Stream(Bits(width bits))
    stream.valid := streamInValid
    stream.payload := streamInPayload
    streamInReady := stream.ready
    val streamPipe: Stream[Bits] = stream.m2sPipe().s2mPipe().halfPipe()
    streamOutValid := streamPipe.valid
    streamPipe.ready := streamOutReady
    streamOutPayload := streamPipe.payload

    val flow: Flow[Bits] = Flow(Bits(width bits))
    flow.valid := flowInValid
    flow.payload := flowInPayload
    val flowPipe: Flow[Bits] = flow.m2sPipe()
    flowOutValid := flowPipe.valid
    flowOutPayload := flowPipe.payload

    val child: Child = new Child(width)
    child.setName("child")
    child.din := streamInPayload

    val childOutput = out(Bits(width bits)).setName("child_output")
    childOutput := child.dout

    if (enabled) attachFeature(invert = false)
    else attachFeature(invert = true)

    private def attachFeature(invert: Boolean): Unit = {
      val sink = new FeatureSink(invert)
      sink.din := featureInput
    }
  }

  final class LiteralTop extends Component {
    setDefinitionName("NativeTypedLibraryLiteralTop")

    val increment = in(Bool()).setName("increment")
    val writeEnable = in(Bool()).setName("write_enable")
    val address = in(UInt(2 bits)).setName("address")
    val dataIn = in(Bits(8 bits)).setName("data_in")
    val dataOut = out(Bits(8 bits)).setName("data_out")

    val counter: Counter = Counter(5, increment)
    val count = out(UInt(3 bits)).setName("count")
    count := counter.value.resized

    val memory: Mem[Bits] = Mem(HardType(Bits(8 bits)), 4)
    memory.write(address, dataIn, enable = writeEnable)
    dataOut := memory.readAsync(address)

    val vector: Vec[Bits] = Vec(Bits(8 bits), 4)
    vector.foreach(_ := dataIn)

    val stream: Stream[Bits] = Stream(Bits(8 bits))
    stream.valid := True
    stream.payload := dataIn
    val streamPipe: Stream[Bits] = stream.m2sPipe(keep = true)
    streamPipe.ready := True

    val flow: Flow[Bits] = Flow(Bits(8 bits))
    flow.valid := True
    flow.payload := dataIn
    val flowPipe: Flow[Bits] = flow.m2sPipe(holdPayload = true)
    flowPipe.payload.dontSimplifyIt()
  }

  def parameterized(): ParameterizedTop =
    new ParameterizedTop(
      HdlInt.param("PARENT_WIDTH", default = 8, min = 1, max = 32),
      HdlInt.param("DEPTH", default = 5, min = 1, max = 8),
      HdlBool.param("ENABLE", default = true)
    )

  def literalSelections: (String, String) =
    OverloadProbe.integer(5) -> OverloadProbe.boolean(true)

  def parameterSelections: (String, String, String) = {
    val depth = HdlInt.param("PROBE_DEPTH", default = 5, min = 1, max = 8)
    val enabled = HdlBool.param("PROBE_ENABLE", default = true)
    (
      OverloadProbe.integer(depth),
      OverloadProbe.boolean(enabled),
      OverloadProbe.legacy(depth)
    )
  }
}
