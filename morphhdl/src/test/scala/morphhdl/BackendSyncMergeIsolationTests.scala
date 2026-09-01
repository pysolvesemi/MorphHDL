package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import org.scalatest.funsuite.AnyFunSuite

import spinal.core._

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
