package spinal.core {
  import spinal.lib.Counter

  object TypedCounterAllOnesFixture {
    final class Dut(depth: ElabInt) extends Component {
      setDefinitionName("TypedCounterAllOnes")

      val increment = in(Bool()).setName("increment")
      val decrement = in(Bool()).setName("decrement")
      val value = out(UInt(3 bits)).setName("value")

      val counter = Counter.both(depth)
      when(increment) {
        counter.increment()
      }
      when(decrement) {
        counter.decrement()
      }
      value := counter.value.resize(3)
    }
  }
}

package morphhdl {
  import java.nio.charset.StandardCharsets
  import java.nio.file.{Files, Path}

  import scala.collection.JavaConverters._
  import scala.sys.process.{Process, ProcessLogger}

  import org.scalatest.funsuite.AnyFunSuite

  import morphhdl.frontend.HdlInt
  import spinal.core._
  import spinal.core.TypedCounterAllOnesFixture.Dut

  class TypedCounterAllOnesTests extends AnyFunSuite {
    private val Depths = Vector(1, 2, 4, 8)

    test("typed bidirectional Counter decrement remains all ones at every power-of-two width") {
      withTemporaryDirectory { directory =>
        val config = synchronousResetConfig(directory)
        config.netlistFileName = "typed_counter_all_ones.v"
        val depth = HdlInt
          .param(
            "DEPTH",
            default = BigInt(1),
            min = BigInt(1),
            max = BigInt(8)
          )
          .asElabInt
        MorphVerilog(config) {
          new Dut(depth)
        }

        val rtl = directory.resolve(config.netlistFileName)
        val verilog = read(rtl)
        val compact = verilog.replaceAll("\\s+", "")
        val zeroDeclarations =
          "(?m)^\\s*wire\\s+\\[([^\\]]+)\\]\\s+([A-Za-z_][A-Za-z0-9_$]*)\\s*;\\s*$".r
            .findAllMatchIn(verilog)
            .filter(value =>
              value.group(1).replaceAll("\\s+", "").contains("clog2(DEPTH,1)") &&
                compact.contains(s"~${value.group(2)}")
            )
            .toVector
        assert(
          zeroDeclarations.size == 1,
          verilog
        )

        if (commandAvailable("iverilog") && commandAvailable("vvp")) {
          Depths.foreach(simulate(directory, rtl, _))
        }
      }
    }

    private def simulate(directory: Path, rtl: Path, depth: Int): Unit = {
      val top = s"TypedCounterAllOnesDepth${depth}Tb"
      val testbench = directory.resolve(s"$top.v")
      val executable = directory.resolve(s"$top.out")
      val source =
        s"""module $top;
           |  reg clk;
           |  reg reset;
           |  reg increment;
           |  reg decrement;
           |  wire [2:0] value;
           |
           |  TypedCounterAllOnes #(.DEPTH($depth)) dut (
           |    .clk(clk),
           |    .reset(reset),
           |    .increment(increment),
           |    .decrement(decrement),
           |    .value(value)
           |  );
           |
           |  always #5 clk = ~clk;
           |
           |  initial begin
           |    clk = 1'b0;
           |    reset = 1'b1;
           |    increment = 1'b0;
           |    decrement = 1'b0;
           |    repeat (2) @(posedge clk);
           |    #1 reset = 1'b0;
           |
           |    decrement = 1'b1;
           |    @(posedge clk);
           |    #1 decrement = 1'b0;
           |    if (value !== ($depth - 1)) begin
           |      $$display("FAIL decrement DEPTH=%0d value=%0d", $depth, value);
           |      $$finish(2);
           |    end
           |
           |    increment = 1'b1;
           |    @(posedge clk);
           |    #1 increment = 1'b0;
           |    if (value !== 0) begin
           |      $$display("FAIL increment DEPTH=%0d value=%0d", $depth, value);
           |      $$finish(2);
           |    end
           |
           |    $$display("PASS typed Counter all ones DEPTH=%0d", $depth);
           |    $$finish;
           |  end
           |endmodule
           |""".stripMargin
      Files.write(testbench, source.getBytes(StandardCharsets.UTF_8))

      val compile = run(
        directory,
        Seq(
          "iverilog",
          "-g2001",
          "-Wall",
          "-s",
          top,
          "-o",
          executable.toString,
          rtl.toString,
          testbench.toString
        )
      )
      assert(compile._1 == 0, s"iverilog DEPTH=$depth failed:\n${compile._2}")
      val simulation = run(directory, Seq("vvp", executable.toString))
      assert(simulation._1 == 0, s"vvp DEPTH=$depth failed:\n${simulation._2}")
      assert(
        simulation._2.contains(s"PASS typed Counter all ones DEPTH=$depth"),
        simulation._2
      )
    }

    private def synchronousResetConfig(directory: Path): SpinalConfig =
      SpinalConfig(
        targetDirectory = directory.toString,
        defaultConfigForClockDomains = ClockDomainConfig(
          clockEdge = RISING,
          resetKind = SYNC,
          resetActiveLevel = HIGH
        )
      )

    private def commandAvailable(name: String): Boolean =
      Process(Seq("sh", "-c", s"command -v $name >/dev/null 2>&1")).! == 0

    private def run(directory: Path, command: Seq[String]): (Int, String) = {
      val output = new StringBuilder
      val logger = ProcessLogger(
        line => output.append(line).append('\n'),
        line => output.append(line).append('\n')
      )
      Process(command, directory.toFile).!(logger) -> output.toString
    }

    private def read(path: Path): String =
      new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

    private def withTemporaryDirectory[T](body: Path => T): T = {
      val directory = Files.createTempDirectory("morphhdl-typed-counter-all-ones-")
      try body(directory)
      finally {
        val stream = Files.walk(directory)
        try {
          stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach { path =>
            Files.deleteIfExists(path)
          }
        } finally stream.close()
      }
    }
  }
}
