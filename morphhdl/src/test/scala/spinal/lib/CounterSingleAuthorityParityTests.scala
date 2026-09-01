package spinal.lib {
  import spinal.core._

  object CounterSingleAuthorityParityFixture {
    final case class CounterCase(
        label: String,
        direction: CounterDirection,
        policy: BoundaryPolicy,
        start: Int,
        handleOverflow: Boolean = true
    )

    val OutputWidth = 4
    val Cases: Vector[CounterCase] = {
      val ordinary = for {
        direction <- Vector(
          CounterDirection.Up,
          CounterDirection.Down,
          CounterDirection.Both
        )
        policy <- Vector(
          BoundaryPolicy.Wrap,
          BoundaryPolicy.Saturate,
          BoundaryPolicy.Freeze
        )
        start <- Vector(0, 3)
      } yield CounterCase(
        s"${direction.toString.toLowerCase}_${policy.toString.toLowerCase}_start_$start",
        direction,
        policy,
        start
      )
      ordinary ++ Vector(
        CounterCase(
          "both_wrap_modular_start_0",
          CounterDirection.Both,
          BoundaryPolicy.Wrap,
          start = 0,
          handleOverflow = false
        ),
        CounterCase(
          "both_wrap_modular_start_3",
          CounterDirection.Both,
          BoundaryPolicy.Wrap,
          start = 3,
          handleOverflow = false
        )
      )
    }

    abstract class MatrixBase extends Component {
      val increment = in(Bool()).setName("increment")
      val decrement = in(Bool()).setName("decrement")
      val clear = in(Bool()).setName("clear")
      val states = out(Bits((Cases.size * OutputWidth) bits)).setName("states")

      protected def attach(counter: Counter, item: CounterCase, index: Int): Unit = {
        item.direction match {
          case CounterDirection.Up =>
            when(increment) {
              counter.increment()
            }
          case CounterDirection.Down =>
            when(decrement) {
              counter.decrement()
            }
          case CounterDirection.Both =>
            when(increment) {
              counter.increment()
            }
            when(decrement) {
              counter.decrement()
            }
        }
        when(clear) {
          counter.clear()
        }
        val low = index * OutputWidth
        states(low + OutputWidth - 1 downto low) :=
          counter.value.resize(OutputWidth).asBits
      }
    }

    final class TypedMatrix(stateCount: ElabInt) extends MatrixBase {
      setDefinitionName("TypedCounterSingleAuthorityMatrix")

      Cases.zipWithIndex.foreach { case (item, index) =>
        val start = ElabInt.literal(item.start)
        val end = start + stateCount - 1
        val counter = new Counter(
          Counter.typedBounds(start, end),
          item.direction,
          item.policy,
          item.policy,
          item.handleOverflow
        )
        attach(counter, item, index)
      }
    }

    final class ConcreteMatrix(stateCount: Int) extends MatrixBase {
      setDefinitionName(s"ConcreteCounterSingleAuthorityMatrix$stateCount")

      Cases.zipWithIndex.foreach { case (item, index) =>
        val start = BigInt(item.start)
        val end = start + stateCount - 1
        val counter = new Counter(
          start,
          end,
          item.direction,
          item.policy,
          item.policy,
          item.handleOverflow
        )
        attach(counter, item, index)
      }
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
  import spinal.lib.CounterSingleAuthorityParityFixture._

  class CounterSingleAuthorityParityTests extends AnyFunSuite {
    private val StateCounts = Vector(1, 4, 5)

    test("typed Counter specializations match independent concrete policy matrices") {
      withTemporaryDirectory { directory =>
        val typedDirectory = directory.resolve("typed")
        val typedConfig = config(typedDirectory)
        typedConfig.netlistFileName = "typed_counter_single_authority.v"
        val stateCount = HdlInt
          .param(
            "STATE_COUNT",
            default = BigInt(1),
            min = BigInt(1),
            max = BigInt(5)
          )
          .asElabInt
        MorphVerilog(typedConfig) {
          new TypedMatrix(stateCount)
        }
        val typedRtl = typedDirectory.resolve(typedConfig.netlistFileName)
        val typedVerilog = read(typedRtl)
        assert(typedVerilog.contains("parameter integer STATE_COUNT = 1"))

        StateCounts.foreach { selected =>
          val concreteDirectory = directory.resolve(s"concrete_$selected")
          val concreteConfig = config(concreteDirectory)
          concreteConfig.netlistFileName = s"concrete_counter_$selected.v"
          SpinalVerilog(concreteConfig) {
            new ConcreteMatrix(selected)
          }
          val concreteRtl =
            concreteDirectory.resolve(concreteConfig.netlistFileName)
          val concreteVerilog = read(concreteRtl)
          assert(!concreteVerilog.contains("parameter integer STATE_COUNT"))
          assert(
            concreteVerilog.contains(
              s"module ConcreteCounterSingleAuthorityMatrix$selected"
            )
          )

          if (commandAvailable("iverilog") && commandAvailable("vvp"))
            simulateParity(
              directory.resolve(s"simulate_$selected"),
              typedRtl,
              concreteRtl,
              selected
            )
        }
      }
    }

    private def simulateParity(
        directory: Path,
        typedRtl: Path,
        concreteRtl: Path,
        stateCount: Int
    ): Unit = {
      Files.createDirectories(directory)
      val top = s"CounterSingleAuthorityParity${stateCount}Tb"
      val testbench = directory.resolve(s"$top.v")
      val executable = directory.resolve(s"$top.out")
      val statesWidth = Cases.size * OutputWidth
      val source =
        s"""module $top;
           |  reg clk;
           |  reg reset;
           |  reg increment;
           |  reg decrement;
           |  reg clear;
           |  wire [${statesWidth - 1}:0] typed_states;
           |  wire [${statesWidth - 1}:0] concrete_states;
           |  integer cycle;
           |
           |  TypedCounterSingleAuthorityMatrix #(.STATE_COUNT($stateCount)) typed_dut (
           |    .clk(clk),
           |    .reset(reset),
           |    .increment(increment),
           |    .decrement(decrement),
           |    .clear(clear),
           |    .states(typed_states)
           |  );
           |
           |  ConcreteCounterSingleAuthorityMatrix$stateCount concrete_dut (
           |    .clk(clk),
           |    .reset(reset),
           |    .increment(increment),
           |    .decrement(decrement),
           |    .clear(clear),
           |    .states(concrete_states)
           |  );
           |
           |  always #5 clk = ~clk;
           |
           |  task tick_and_compare;
           |    begin
           |      @(posedge clk);
           |      #1;
           |      cycle = cycle + 1;
           |      if (typed_states !== concrete_states) begin
           |        $$display("FAIL STATE_COUNT=%0d cycle=%0d typed=%h concrete=%h", $stateCount, cycle, typed_states, concrete_states);
           |        $$finish(2);
           |      end
           |    end
           |  endtask
           |
           |  initial begin
           |    clk = 1'b0;
           |    reset = 1'b1;
           |    increment = 1'b0;
           |    decrement = 1'b0;
           |    clear = 1'b0;
           |    cycle = 0;
           |    tick_and_compare;
           |    tick_and_compare;
           |    reset = 1'b0;
           |
           |    increment = 1'b1;
           |    repeat (8) tick_and_compare;
           |    increment = 1'b0;
           |    decrement = 1'b1;
           |    repeat (8) tick_and_compare;
           |    decrement = 1'b0;
           |
           |    clear = 1'b1;
           |    tick_and_compare;
           |    clear = 1'b0;
           |    increment = 1'b1;
           |    decrement = 1'b1;
           |    repeat (2) tick_and_compare;
           |
           |    increment = 1'b0;
           |    repeat (7) begin
           |      tick_and_compare;
           |      increment = ~increment;
           |      decrement = ~decrement;
           |    end
           |
           |    $$display("PASS Counter single authority STATE_COUNT=%0d", $stateCount);
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
          typedRtl.toString,
          concreteRtl.toString,
          testbench.toString
        )
      )
      assert(
        compile._1 == 0,
        s"iverilog STATE_COUNT=$stateCount failed:\n${compile._2}"
      )
      val simulation = run(directory, Seq("vvp", executable.toString))
      assert(
        simulation._1 == 0,
        s"vvp STATE_COUNT=$stateCount failed:\n${simulation._2}"
      )
      assert(
        simulation._2.contains(
          s"PASS Counter single authority STATE_COUNT=$stateCount"
        ),
        simulation._2
      )
    }

    private def config(directory: Path): SpinalConfig =
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
      val directory = Files.createTempDirectory(
        "morphhdl-counter-single-authority-parity-"
      )
      try body(directory)
      finally {
        val stream = Files.walk(directory)
        try
          stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach { path =>
            Files.deleteIfExists(path)
          }
        finally stream.close()
      }
    }
  }
}
