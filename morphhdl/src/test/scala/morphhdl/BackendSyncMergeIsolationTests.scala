package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import org.scalatest.funsuite.AnyFunSuite

import spinal.core._

import morphhdl.frontend.{formalComponent, HdlInt, NativeIntShadow}

object BackendSyncMergeIsolationSmoke {
  final class RegisterPair(isolateProcesses: Boolean) extends Component {
    setDefinitionName(
      if (isolateProcesses) "IsolatedSynchronousRegisterPair"
      else "MergedSynchronousRegisterPair"
    )

    val clk = in(Bool())
    val reset = in(Bool())
    val leftIn = in(Bits(8 bits))
    val rightIn = in(Bits(8 bits))
    val leftOut = out(Bits(8 bits))
    val rightOut = out(Bits(8 bits))

    private val registerClock = ClockDomain(
      clock = clk,
      reset = reset,
      config = ClockDomainConfig(
        clockEdge = RISING,
        resetKind = SYNC,
        resetActiveLevel = HIGH
      )
    )

    private val registers = new ClockingArea(registerClock) {
      val leftState = Reg(Bits(8 bits)) init (0)
      leftState.setName("left_state")
      leftState := leftIn

      val rightState = Reg(Bits(8 bits)) init (0)
      rightState.setName("right_state")
      rightState := rightIn

      if (isolateProcesses) {
        leftState.addTag(noBackendSyncMerge)
        rightState.addTag(noBackendSyncMerge)
      }
    }

    leftOut := registers.leftState
    rightOut := registers.rightState
  }

  final class CapturedMemoryLeaf(width: Int) extends Component {
    setDefinitionName("CapturedSynchronousMemoryLeaf")

    val payloadIn = in(Bits(width bits))
    val payloadOut = out(Bits(width bits))
    val readEnable = in(Bool())
    val writeEnable = in(Bool())
    val address = in(UInt(2 bits))
    val writeData = in(Bits(8 bits))
    val readData = out(Bits(8 bits))

    payloadOut := payloadIn

    @dontName val root = NativeIntShadow.captureArgument(width, "root")
    if (root > 2) {
      val memory = Mem(Bits(8 bits), 4).setName("captured_memory")
      val readWord = memory
        .readSync(
          address,
          enable = readEnable,
          readUnderWrite = readFirst
        )
        .setName("captured_read_word")
      memory.write(address, writeData, enable = writeEnable)

      val delayed = RegNext(readWord).setName("captured_delayed")
      readData := delayed
    } else {
      val fallback = RegNext(writeData).setName("fallback_delayed")
      readData := fallback
    }
  }

  final class CapturedMemoryTop(width: HdlInt) extends Component {
    setDefinitionName("CapturedSynchronousMemoryTop")

    val payloadIn = in(morphhdl.frontend.Bits(width bits))
    val payloadOut = out(morphhdl.frontend.Bits(width bits))
    val readEnable = in(Bool())
    val writeEnable = in(Bool())
    val address = in(UInt(2 bits))
    val writeData = in(Bits(8 bits))
    val readData = out(Bits(8 bits))

    val leaf = formalComponent(width, "WIDTH", BigInt(1), BigInt(4))(
      value => new CapturedMemoryLeaf(value)
    )(value => Vector(value.payloadIn, value.payloadOut))

    leaf.payloadIn := payloadIn
    leaf.readEnable := readEnable
    leaf.writeEnable := writeEnable
    leaf.address := address
    leaf.writeData := writeData
    payloadOut := leaf.payloadOut
    readData := leaf.readData
  }
}

class BackendSyncMergeIsolationTests extends AnyFunSuite {
  import BackendSyncMergeIsolationSmoke._

  test("ordinary same-domain registers retain the default merged synchronous process") {
    withTemporaryDirectory { directory =>
      val verilog = emitConcrete(
        directory,
        "merged_synchronous_register_pair.v",
        new RegisterPair(isolateProcesses = false)
      )

      assert(occurrences(verilog, "always @(posedge clk)") == 1)
      assert(occurrences(verilog, "if(reset) begin") == 1)
      assert(verilog.contains("left_state <= leftIn;"))
      assert(verilog.contains("right_state <= rightIn;"))
      assert(!verilog.contains("posedge reset"))
    }
  }

  test("exact noBackendSyncMerge registers retain clock and synchronous-reset semantics in distinct processes") {
    withTemporaryDirectory { directory =>
      val verilog = emitConcrete(
        directory,
        "isolated_synchronous_register_pair.v",
        new RegisterPair(isolateProcesses = true)
      )

      assert(occurrences(verilog, "always @(posedge clk)") == 2)
      assert(occurrences(verilog, "if(reset) begin") == 2)
      assert(verilog.contains("left_state <= leftIn;"))
      assert(verilog.contains("right_state <= rightIn;"))
      val firstProcess = verilog.indexOf("always @(posedge clk)")
      val secondProcess = verilog.indexOf(
        "always @(posedge clk)",
        firstProcess + 1
      )
      val leftUpdate = verilog.indexOf("left_state <= leftIn;")
      val rightUpdate = verilog.indexOf("right_state <= rightIn;")
      assert(firstProcess >= 0 && secondProcess > firstProcess)
      assert(leftUpdate > firstProcess && rightUpdate > firstProcess)
      assert((leftUpdate < secondProcess) != (rightUpdate < secondProcess))
      assert(!verilog.contains("posedge reset"))
      assert(!verilog.contains("negedge reset"))
    }
  }

  test("captured memory read feeding a register retains native synchronous-memory recognition") {
    withTemporaryDirectory { directory =>
      val width = HdlInt.param("WIDTH", default = 3, min = 1, max = 4)
      val verilog = emitMorph(
        directory,
        "captured_synchronous_memory.v",
        new CapturedMemoryTop(width)
      )

      val leaf = moduleBody(verilog, "CapturedSynchronousMemoryLeaf")
      val compactLeaf = leaf.replaceAll("\\s+", "")
      assert(compactLeaf.contains("if((WIDTH>2))begin"))
      assert(leaf.contains("captured_memory [0:3]"))
      assert(
        leaf.contains(
          "captured_memory_spinal_port0 <= captured_memory[address];"
        )
      )
      assert(
        leaf.contains("assign captured_read_word =")
      )
      assert(leaf.contains("captured_delayed <= captured_read_word;"))
      val trueBranchStart = leaf.indexOf("begin : g_if_")
      val falseBranchStart = leaf.indexOf("end else begin", trueBranchStart)
      assert(trueBranchStart >= 0 && falseBranchStart > trueBranchStart)
      val trueBranch = leaf.substring(trueBranchStart, falseBranchStart)
      assert(occurrences(trueBranch, "always @(posedge clk)") == 2)
      assert(occurrences(leaf, "input  wire          clk") == 1)
      assert(!leaf.contains("clk_1"))
    }
  }

  private def emitConcrete(
      directory: Path,
      filename: String,
      component: => Component
  ): String = {
    val config = SpinalConfig(targetDirectory = directory.toString)
    config.netlistFileName = filename
    SpinalVerilog(config)(component)
    read(directory.resolve(filename))
  }

  private def emitMorph(
      directory: Path,
      filename: String,
      component: => Component
  ): String = {
    val config = SpinalConfig(targetDirectory = directory.toString)
    config.netlistFileName = filename
    MorphVerilog(config)(component)
    read(directory.resolve(filename))
  }

  private def moduleBody(verilog: String, definitionName: String): String = {
    val start = verilog.indexOf(s"module $definitionName")
    assert(start >= 0, s"missing module $definitionName")
    val end = verilog.indexOf("endmodule", start)
    assert(end > start, s"unterminated module $definitionName")
    verilog.substring(start, end)
  }

  private def occurrences(value: String, token: String): Int =
    value.sliding(token.length).count(_ == token)

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def withTemporaryDirectory[A](body: Path => A): A = {
    val directory = Files.createTempDirectory("morphhdl-sync-merge-test-")
    try body(directory)
    finally deleteRecursively(directory)
  }

  private def deleteRecursively(path: Path): Unit = {
    if (Files.exists(path)) {
      val entries = Files.walk(path)
      try {
        entries
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(value => Files.deleteIfExists(value))
      } finally entries.close()
    }
  }
}
