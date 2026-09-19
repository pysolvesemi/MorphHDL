package nativeapplication

import morphhdl.frontend.HdlInt
import spinal.core._
import spinal.lib._

/** Application-shaped Increment 57 source.
  *
  * All hardware construction uses ordinary SpinalHDL imports. MorphHDL is
  * imported only for parameter declaration; target-directed adaptation keeps
  * those declarations on the native ElabInt/ElabBool library surface.
  */
object NativeLibraryMigrationFixture {
  final class PipelineTop(pipeMode: HdlInt) extends Component {
    setDefinitionName("NativeLibraryMigrationPipelineTop")

    private val mode: ElabInt = pipeMode
    private val useM2s: ElabBool = mode.elabEq(0) || mode.elabEq(3)
    private val useS2m: ElabBool = mode.elabEq(1) || mode.elabEq(3)
    private val useHalfRate: ElabBool = mode.elabEq(2)
    private val holdFlowPayload: ElabBool = mode.elabEq(1)

    val streamInValid = in(Bool()).setName("stream_in_valid")
    val streamInReady = out(Bool()).setName("stream_in_ready")
    val streamInPayload = in(Bits(8 bits)).setName("stream_in_payload")
    val streamOutValid = out(Bool()).setName("stream_out_valid")
    val streamOutReady = in(Bool()).setName("stream_out_ready")
    val streamOutPayload = out(Bits(8 bits)).setName("stream_out_payload")

    val flowInValid = in(Bool()).setName("flow_in_valid")
    val flowInPayload = in(Bits(8 bits)).setName("flow_in_payload")
    val flowOutValid = out(Bool()).setName("flow_out_valid")
    val flowOutPayload = out(Bits(8 bits)).setName("flow_out_payload")

    val stream = Stream(Bits(8 bits))
    stream.valid := streamInValid
    stream.payload := streamInPayload
    streamInReady := stream.ready
    val streamPipe: Stream[Bits] = stream.pipelined(
      m2s = useM2s,
      s2m = useS2m,
      halfRate = useHalfRate
    )
    streamOutValid := streamPipe.valid
    streamPipe.ready := streamOutReady
    streamOutPayload := streamPipe.payload

    val flow = Flow(Bits(8 bits))
    flow.valid := flowInValid
    flow.payload := flowInPayload
    val flowPipe: Flow[Bits] = flow.m2sPipe(
      holdPayload = holdFlowPayload
    )
    flowOutValid := flowPipe.valid
    flowOutPayload := flowPipe.payload
  }

  final class QueueMemoryTop(depth: HdlInt) extends Component {
    setDefinitionName("NativeLibraryMigrationQueueMemoryTop")

    private val typedDepth: ElabInt = depth

    val increment = in(Bool()).setName("increment")
    val clear = in(Bool()).setName("clear")
    val count = out(UInt(typedDepth.addressWidth bits)).setName("count")
    val counterComplete = out(Bool()).setName("counter_complete")

    val streamInValid = in(Bool()).setName("stream_in_valid")
    val streamInReady = out(Bool()).setName("stream_in_ready")
    val streamInPayload = in(Bits(8 bits)).setName("stream_in_payload")
    val streamOutValid = out(Bool()).setName("stream_out_valid")
    val streamOutReady = in(Bool()).setName("stream_out_ready")
    val streamOutPayload = out(Bits(8 bits)).setName("stream_out_payload")
    val streamOccupancy = out(UInt((typedDepth + 1).addressWidth bits))
      .setName("stream_occupancy")

    val flowInValid = in(Bool()).setName("flow_in_valid")
    val flowInPayload = in(Bits(8 bits)).setName("flow_in_payload")
    val flowOutValid = out(Bool()).setName("flow_out_valid")
    val flowOutReady = in(Bool()).setName("flow_out_ready")
    val flowOutPayload = out(Bits(8 bits)).setName("flow_out_payload")
    val flowAvailability = out(UInt((typedDepth + 1).addressWidth bits))
      .setName("flow_availability")

    val memoryAddress = in(UInt(typedDepth.addressWidth bits))
      .setName("memory_address")
    val memoryReadEnable = in(Bool()).setName("memory_read_enable")
    val memoryWriteEnable = in(Bool()).setName("memory_write_enable")
    val memoryWriteData = in(Bits(8 bits)).setName("memory_write_data")
    val memoryReadData = out(Bits(8 bits)).setName("memory_read_data")

    val counter: Counter = Counter(depth, increment)
    when(clear) {
      counter.clear()
    }
    count := counter.value
    counterComplete := counter.willComplete

    val stream = Stream(Bits(8 bits))
    stream.valid := streamInValid
    stream.payload := streamInPayload
    streamInReady := stream.ready
    val (queuedStream, occupancy) = stream.queueWithOccupancy(
      depth,
      latency = 2,
      forFMax = false
    )
    streamOutValid := queuedStream.valid
    queuedStream.ready := streamOutReady
    streamOutPayload := queuedStream.payload
    streamOccupancy := occupancy

    val flow = Flow(Bits(8 bits))
    flow.valid := flowInValid
    flow.payload := flowInPayload
    val (queuedFlow, availability) = flow.queueWithAvailability(depth)
    flowOutValid := queuedFlow.valid
    queuedFlow.ready := flowOutReady
    flowOutPayload := queuedFlow.payload
    flowAvailability := availability

    val memory: Mem[Bits] = Mem(HardType(Bits(8 bits)), depth)
    memory.setName("memory")
    memory.write(
      memoryAddress,
      memoryWriteData,
      enable = memoryWriteEnable
    )
    memoryReadData := memory.readSync(
      memoryAddress,
      enable = memoryReadEnable,
      readUnderWrite = readFirst
    )
  }

  final class LiteralTop extends Component {
    setDefinitionName("NativeLibraryMigrationLiteralTop")

    val increment = in(Bool()).setName("increment")
    val dataIn = in(Bits(8 bits)).setName("data_in")
    val dataOut = out(Bits(8 bits)).setName("data_out")

    val counter: Counter = Counter(4, increment)

    val pipelineStream = Stream(Bits(8 bits))
    pipelineStream.valid := True
    pipelineStream.payload := dataIn
    val streamPipe: Stream[Bits] = pipelineStream.pipelined(
      m2s = true,
      s2m = false,
      halfRate = false
    )
    streamPipe.ready := True

    val pipelineFlow = Flow(Bits(8 bits))
    pipelineFlow.valid := True
    pipelineFlow.payload := dataIn
    val flowPipe: Flow[Bits] = pipelineFlow.m2sPipe(holdPayload = true)

    val queueStream = Stream(Bits(8 bits))
    queueStream.valid := True
    queueStream.payload := dataIn
    val queued: Stream[Bits] = queueStream.queue(4)
    queued.ready := True
    val queueFlow = Flow(Bits(8 bits))
    queueFlow.valid := True
    queueFlow.payload := dataIn
    val (flowQueued, _) = queueFlow.queueWithAvailability(4)
    flowQueued.ready := True

    val memory: Mem[Bits] = Mem(HardType(Bits(8 bits)), 4)
    memory.write(counter.value.resized, dataIn, enable = increment)
    dataOut := flowPipe.payload ^ memory.readAsync(counter.value.resized)
  }

  def pipeline(default: Int = 0): PipelineTop =
    new PipelineTop(
      HdlInt.param("PIPE_MODE", default = default, min = 0, max = 4)
    )

  def queueMemory(default: Int = 5): QueueMemoryTop =
    new QueueMemoryTop(
      HdlInt.param("DEPTH", default = default, min = 2, max = 8)
    )
}
