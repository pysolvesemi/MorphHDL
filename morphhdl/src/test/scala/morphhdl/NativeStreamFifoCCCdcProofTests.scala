package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import scala.collection.JavaConverters._
import scala.sys.process.{Process, ProcessLogger}

import org.scalatest.funsuite.AnyFunSuite

import morphhdl.frontend.HdlInt
import spinal.core._
import spinal.lib._

private object NativeStreamFifoCCCdcProofFixture {
  final class Top(depth: ElabInt, bufferedPopReset: Boolean)
      extends Component {
    setDefinitionName(
      if (bufferedPopReset) "NativeStreamFifoCC57aCdcBuffered"
      else "NativeStreamFifoCC57aCdcDirect"
    )

    val io = new Bundle {
      val pushClock = in Bool ()
      val pushReset = in Bool ()
      val popClock = in Bool ()
      val popReset = in Bool ()
      val pushValid = in Bool ()
      val pushReady = out Bool ()
      val pushPayload = in Bits (8 bits)
      val popValid = out Bool ()
      val popReady = in Bool ()
      val popPayload = out Bits (8 bits)
      val pushOccupancy = out UInt (5 bits)
      val popOccupancy = out UInt (5 bits)
    }

    private val clockConfig = ClockDomainConfig(
      clockEdge = RISING,
      resetKind = ASYNC,
      resetActiveLevel = HIGH
    )
    private val pushCd =
      ClockDomain(io.pushClock, io.pushReset, config = clockConfig)
    private val popCd =
      ClockDomain(io.popClock, io.popReset, config = clockConfig)

    val fifo = StreamFifoCC(
      HardType(Bits(8 bits)),
      depth,
      pushCd,
      popCd,
      bufferedPopReset
    )
    fifo.setName("fifo")

    fifo.io.push.valid := io.pushValid
    fifo.io.push.payload := io.pushPayload
    io.pushReady := fifo.io.push.ready
    io.popValid := fifo.io.pop.valid
    fifo.io.pop.ready := io.popReady
    io.popPayload := fifo.io.pop.payload
    io.pushOccupancy := fifo.io.pushOccupancy.resized
    io.popOccupancy := fifo.io.popOccupancy.resized
  }
}

/** Specialization-level CDC proof for Increment 57a.
  *
  * The same two parameterized Verilog artifacts (one per static reset policy)
  * are specialized at every supported depth. Strict Verilog-2001 parsing,
  * Verilator lint, Yosys synthesis, two opposite asynchronous clock ratios,
  * stopped clocks, FIFO full/drain and repeated wraparound are all checked by
  * external tools. The lane is opt-in because those tools belong to the pinned
  * workflow container rather than the ordinary developer JVM.
  */
class NativeStreamFifoCCCdcProofTests extends AnyFunSuite {
  import NativeStreamFifoCCCdcProofFixture._

  private final case class ClockSchedule(
      name: String,
      pushHalfPeriod: Int,
      popHalfPeriod: Int
  )

  private val Depths = Vector(2, 4, 8, 16)
  private val ResetModes = Vector(false, true)
  private val Schedules = Vector(
    ClockSchedule("push_faster", pushHalfPeriod = 3, popHalfPeriod = 7),
    ClockSchedule("pop_faster", pushHalfPeriod = 7, popHalfPeriod = 3)
  )
  private val GateEnvironment = "MORPHDL_RUN_STREAMFIFOCC_CDC_PROOF"
  private val WorkspaceEnvironment = "MORPHDL_STREAMFIFOCC_CDC_WORKSPACE"

  test("one typed CDC artifact is available for every static reset policy") {
    withWorkspace { directory =>
      val generated = ResetModes.map(buffered => generate(directory, buffered))
      generated.foreach { path =>
        val source = read(path)
        assert(source.contains("parameter integer DEPTH = 8"), source)
        assert(source.contains("module StreamFifoCC #("), source)
        assert(source.contains(".DEPTH(DEPTH)"), source)
        assert(source.contains("WIDTH"), source)
        assert(source.contains("keep_hierarchy"), source)
      }
      assert(generated.map(read).distinct.size == ResetModes.size)
    }
  }

  test(
    "typed CDC specializations pass strict lint synthesis and asynchronous simulation"
  ) {
    if (!sys.env.get(GateEnvironment).contains("1")) {
      cancel(s"Set $GateEnvironment=1 only in the pinned CDC proof container")
    }

    withWorkspace { directory =>
      requireTool(directory, Seq("iverilog", "-V"), "Icarus Verilog")
      requireTool(directory, Seq("vvp", "-V"), "Icarus vvp")
      requireTool(directory, Seq("verilator", "--version"), "Verilator")
      requireTool(directory, Seq("yosys", "-V"), "Yosys")

      ResetModes.foreach { buffered =>
        val rtl = generate(directory, buffered)
        Depths.foreach { depth =>
          lint(directory, rtl, depth, buffered)
          synthesize(directory, rtl, depth, buffered)
          Schedules.foreach { schedule =>
            simulate(directory, rtl, depth, buffered, schedule)
          }
        }
        Vector(3, 5, 15).foreach { invalidDepth =>
          simulateInvalidDepth(directory, rtl, invalidDepth, buffered)
          synthesize(directory, rtl, invalidDepth, buffered)
        }
      }
    }
  }

  private def generate(directory: Path, buffered: Boolean): Path = {
    val mode = resetMode(buffered)
    val target = directory.resolve(s"candidate-$mode")
    Files.createDirectories(target)
    val fileName = s"native_streamfifocc_57a_cdc_$mode.v"
    val config = SpinalConfig(targetDirectory = target.toString)
    config.netlistFileName = fileName
    val depth = HdlInt
      .param(
        "DEPTH",
        default = BigInt(8),
        min = BigInt(2),
        max = BigInt(16)
      )
      .asElabInt
    MorphVerilog(config)(new Top(depth, buffered))
    val output = target.resolve(fileName)
    assert(Files.isRegularFile(output), s"CDC artifact is missing: $output")
    output
  }

  private def lint(
      directory: Path,
      rtl: Path,
      depth: Int,
      buffered: Boolean
  ): Unit = {
    val output = runChecked(
      directory,
      Seq(
        "verilator",
        "--lint-only",
        "--language",
        "1364-2001",
        "-Wall",
        "-Wno-fatal",
        "-Wno-DECLFILENAME",
        "-Wno-WIDTH",
        "-Wno-UNUSED",
        "--top-module",
        top(buffered),
        s"-GDEPTH=$depth",
        rtl.toAbsolutePath.toString
      ),
      s"Verilator lint failed for depth=$depth buffered=$buffered"
    )
    assert(!output.contains("%Error"), output)
  }

  private def synthesize(
      directory: Path,
      rtl: Path,
      depth: Int,
      buffered: Boolean
  ): Unit = {
    val script = directory.resolve(
      s"streamfifocc_57a_synth_${resetMode(buffered)}_d$depth.ys"
    )
    write(
      script,
      s"""read_verilog -defer ${yosysPath(rtl)}
         |chparam -set DEPTH $depth ${top(buffered)}
         |hierarchy -check -top ${top(buffered)}
         |proc
         |memory
         |opt
         |check -assert
         |synth -top ${top(buffered)}
         |check -assert
         |stat
         |""".stripMargin
    )
    val output = runChecked(
      directory,
      Seq("yosys", "-Q", "-s", script.toAbsolutePath.toString),
      s"Yosys synthesis failed for depth=$depth buffered=$buffered"
    )
    assert(output.contains("Number of cells"), output)
  }

  private def simulate(
      directory: Path,
      rtl: Path,
      depth: Int,
      buffered: Boolean,
      schedule: ClockSchedule
  ): Unit = {
    val stem =
      s"${resetMode(buffered)}_d${depth}_${schedule.name}"
    val testbench = directory.resolve(s"streamfifocc_57a_tb_$stem.v")
    write(testbench, simulationTestbench(depth, buffered, schedule))
    val executable = directory.resolve(s"streamfifocc_57a_sim_$stem.out")
    runChecked(
      directory,
      Seq(
        "iverilog",
        "-g2001",
        "-Wall",
        "-Wimplicit",
        "-s",
        "tb",
        "-o",
        executable.toAbsolutePath.toString,
        rtl.toAbsolutePath.toString,
        testbench.toAbsolutePath.toString
      ),
      s"Icarus compile failed for $stem"
    )
    val output = runChecked(
      directory,
      Seq("vvp", executable.toAbsolutePath.toString),
      s"CDC simulation failed for $stem"
    )
    assert(output.contains(s"STREAMFIFOCC_57A_CDC_PASS $stem"), output)
  }

  private def simulateInvalidDepth(
      directory: Path,
      rtl: Path,
      depth: Int,
      buffered: Boolean
  ): Unit = {
    val stem = s"${resetMode(buffered)}_invalid_d$depth"
    val testbench = directory.resolve(s"streamfifocc_57a_tb_$stem.v")
    write(testbench, invalidDepthTestbench(depth, buffered))
    val executable = directory.resolve(s"streamfifocc_57a_sim_$stem.out")
    runChecked(
      directory,
      Seq(
        "iverilog",
        "-g2001",
        "-Wall",
        "-Wimplicit",
        "-s",
        "tb",
        "-o",
        executable.toAbsolutePath.toString,
        rtl.toAbsolutePath.toString,
        testbench.toAbsolutePath.toString
      ),
      s"invalid-depth Icarus compile failed for $stem"
    )
    val output = runChecked(
      directory,
      Seq("vvp", executable.toAbsolutePath.toString),
      s"invalid-depth fail-closed simulation failed for $stem"
    )
    assert(output.contains(s"STREAMFIFOCC_57A_INVALID_PASS $stem"), output)
  }

  private def simulationTestbench(
      depth: Int,
      buffered: Boolean,
      schedule: ClockSchedule
  ): String = {
    val total = depth * 6 + 5
    val stem = s"${resetMode(buffered)}_d${depth}_${schedule.name}"
    val releaseResets =
      if (buffered)
        "#31 io_pushReset = 1'b0; #19 io_popReset = 1'b0;"
      else
        "#43 io_pushReset = 1'b0; io_popReset = 1'b0;"

    s"""`timescale 1ns/1ps
       |module tb;
       |  localparam integer DEPTH = $depth;
       |  localparam integer TOTAL = $total;
       |  reg io_pushClock = 1'b0;
       |  reg io_pushReset = 1'b1;
       |  reg io_popClock = 1'b0;
       |  reg io_popReset = 1'b1;
       |  reg io_pushValid = 1'b0;
       |  wire io_pushReady;
       |  reg [7:0] io_pushPayload = 8'h00;
       |  wire io_popValid;
       |  reg io_popReady = 1'b0;
       |  wire [7:0] io_popPayload;
       |  wire [4:0] io_pushOccupancy;
       |  wire [4:0] io_popOccupancy;
       |  reg pushRun = 1'b1;
       |  reg popRun = 1'b1;
       |  integer sent = 0;
       |  integer received = 0;
       |  integer popCycles = 0;
       |  integer sawFull = 0;
       |  integer sawPushPause = 0;
       |  integer sawPopPause = 0;
       |  integer sawSimultaneous = 0;
       |  time lastPushTransferTime = 0;
       |  time lastPopTransferTime = 0;
       |
       |  always #${schedule.pushHalfPeriod} if (pushRun) io_pushClock = ~io_pushClock;
       |  always #${schedule.popHalfPeriod} if (popRun) io_popClock = ~io_popClock;
       |
       |  ${top(buffered)} #(.DEPTH(DEPTH)) dut (
       |    .io_pushClock(io_pushClock), .io_pushReset(io_pushReset),
       |    .io_popClock(io_popClock), .io_popReset(io_popReset),
       |    .io_pushValid(io_pushValid), .io_pushReady(io_pushReady),
       |    .io_pushPayload(io_pushPayload), .io_popValid(io_popValid),
       |    .io_popReady(io_popReady), .io_popPayload(io_popPayload),
       |    .io_pushOccupancy(io_pushOccupancy),
       |    .io_popOccupancy(io_popOccupancy)
       |  );
       |
       |  initial begin
       |    $releaseResets
       |  end
       |
       |  initial begin
       |    #270 popRun = 1'b0; sawPopPause = 1;
       |    #91 popRun = 1'b1;
       |    #233 pushRun = 1'b0; sawPushPause = 1;
       |    #79 pushRun = 1'b1;
       |  end
       |
       |  // A transfer is accepted on the active push edge. Count it there,
       |  // before ready can change in response to the DUT's pointer update.
       |  always @(posedge io_pushClock) begin
       |    if (io_pushReset) begin
       |      sent = 0;
       |    end else begin
       |      if (io_pushValid && io_pushReady) begin
       |        if ($$time == lastPopTransferTime)
       |          sawSimultaneous = 1;
       |        lastPushTransferTime = $$time;
       |        sent = sent + 1;
       |      end
       |      if (io_pushValid && !io_pushReady)
       |        sawFull = 1;
       |    end
       |    if (!io_pushReset && io_pushOccupancy > DEPTH) begin
       |      $$display("push occupancy out of range: %0d > %0d", io_pushOccupancy, DEPTH);
       |      $$fatal(1);
       |    end
       |  end
       |
       |  // Stimulus changes only on the inactive edge. When backpressured,
       |  // sent is unchanged, so valid and payload remain protocol-stable.
       |  always @(negedge io_pushClock) begin
       |    if (io_pushReset) begin
       |      io_pushValid = 1'b0;
       |      io_pushPayload = 8'h00;
       |    end else if (sent < TOTAL) begin
       |      io_pushValid = 1'b1;
       |      io_pushPayload = sent[7:0];
       |    end else begin
       |      io_pushValid = 1'b0;
       |    end
       |  end
       |
       |  always @(negedge io_popClock) begin
       |    if (io_popReset) begin
       |      io_popReady = 1'b0;
       |      popCycles = 0;
       |    end else begin
       |      popCycles = popCycles + 1;
       |      if ($$time < 650)
       |        io_popReady = 1'b0;
       |      else
       |        io_popReady = ((popCycles % 7) != 2) && ((popCycles % 11) != 5);
       |    end
       |    if (!io_popReset && io_popOccupancy > DEPTH) begin
       |      $$display("pop occupancy out of range: %0d > %0d", io_popOccupancy, DEPTH);
       |      $$fatal(1);
       |    end
       |  end
       |
       |  always @(posedge io_popClock) begin
       |    if (!io_popReset && io_popValid && io_popReady) begin
       |      if ($$time == lastPushTransferTime)
       |        sawSimultaneous = 1;
       |      lastPopTransferTime = $$time;
       |      if (io_popPayload !== received[7:0]) begin
       |        $$display("data mismatch expected=%0d actual=%0d", received, io_popPayload);
       |        $$fatal(1);
       |      end
       |      received = received + 1;
       |      if (received == TOTAL) begin
       |        if (sent != TOTAL || !sawFull || !sawPushPause || !sawPopPause || !sawSimultaneous) begin
       |          $$display("coverage missing sent=%0d full=%0d pushPause=%0d popPause=%0d simultaneous=%0d", sent, sawFull, sawPushPause, sawPopPause, sawSimultaneous);
       |          $$fatal(1);
       |        end
       |        #200;
       |        if (io_popValid !== 1'b0 || io_pushOccupancy !== 5'b0 ||
       |            io_popOccupancy !== 5'b0) begin
       |          $$display("drain did not settle valid=%b pushOcc=%0d popOcc=%0d", io_popValid, io_pushOccupancy, io_popOccupancy);
       |          $$fatal(1);
       |        end
       |        $$display("STREAMFIFOCC_57A_CDC_PASS $stem");
       |        #20 $$finish;
       |      end
       |    end
       |  end
       |
       |  initial begin
       |    #50000;
       |    $$display("timeout sent=%0d received=%0d", sent, received);
       |    $$fatal(1);
       |  end
       |endmodule
       |""".stripMargin
  }

  private def invalidDepthTestbench(depth: Int, buffered: Boolean): String = {
    val stem = s"${resetMode(buffered)}_invalid_d$depth"
    s"""`timescale 1ns/1ps
       |module tb;
       |  reg io_pushClock = 1'b0;
       |  reg io_pushReset = 1'b1;
       |  reg io_popClock = 1'b0;
       |  reg io_popReset = 1'b1;
       |  reg io_pushValid = 1'b1;
       |  wire io_pushReady;
       |  reg [7:0] io_pushPayload = 8'h5a;
       |  wire io_popValid;
       |  reg io_popReady = 1'b1;
       |  wire [7:0] io_popPayload;
       |  wire [4:0] io_pushOccupancy;
       |  wire [4:0] io_popOccupancy;
       |
       |  always #3 io_pushClock = ~io_pushClock;
       |  always #5 io_popClock = ~io_popClock;
       |
       |  ${top(buffered)} #(.DEPTH($depth)) dut (
       |    .io_pushClock(io_pushClock), .io_pushReset(io_pushReset),
       |    .io_popClock(io_popClock), .io_popReset(io_popReset),
       |    .io_pushValid(io_pushValid), .io_pushReady(io_pushReady),
       |    .io_pushPayload(io_pushPayload), .io_popValid(io_popValid),
       |    .io_popReady(io_popReady), .io_popPayload(io_popPayload),
       |    .io_pushOccupancy(io_pushOccupancy),
       |    .io_popOccupancy(io_popOccupancy)
       |  );
       |
       |  initial begin
       |    #23 io_pushReset = 1'b0; io_popReset = 1'b0;
       |    #100;
       |    if (io_pushReady !== 1'b0 || io_popValid !== 1'b0 ||
       |        io_pushOccupancy !== 5'b0 || io_popOccupancy !== 5'b0 ||
       |        io_popPayload !== 8'b0) begin
       |      $$display("invalid specialization did not fail closed");
       |      $$fatal(1);
       |    end
       |    $$display("STREAMFIFOCC_57A_INVALID_PASS $stem");
       |    $$finish;
       |  end
       |endmodule
       |""".stripMargin
  }

  private def top(buffered: Boolean): String =
    if (buffered) "NativeStreamFifoCC57aCdcBuffered"
    else "NativeStreamFifoCC57aCdcDirect"

  private def resetMode(buffered: Boolean): String =
    if (buffered) "buffered" else "direct"

  private def requireTool(
      directory: Path,
      command: Seq[String],
      name: String
  ): Unit = {
    val (exitCode, output) = run(directory, command)
    assert(exitCode == 0, s"$name is unavailable:\n$output")
  }

  private def runChecked(
      directory: Path,
      command: Seq[String],
      context: String
  ): String = {
    val (exitCode, output) = run(directory, command)
    assert(exitCode == 0, s"$context:\n$output")
    output
  }

  private def run(directory: Path, command: Seq[String]): (Int, String) = {
    val output = new StringBuilder
    val logger = ProcessLogger(
      line => output.append(line).append('\n'),
      line => output.append(line).append('\n')
    )
    val exitCode = Process(command, directory.toFile).!(logger)
    exitCode -> output.toString
  }

  private def withWorkspace(body: Path => Unit): Unit =
    sys.env.get(WorkspaceEnvironment) match {
      case Some(value) =>
        val directory = Paths.get(value).toAbsolutePath
        Files.createDirectories(directory)
        body(directory)
      case None => withTemporaryDirectory(body)
    }

  private def withTemporaryDirectory(body: Path => Unit): Unit = {
    val directory = Files.createTempDirectory("morphhdl-streamfifocc-57a-cdc-")
    try body(directory)
    finally {
      val stream = Files.walk(directory)
      try stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists)
      finally stream.close()
    }
  }

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def write(path: Path, value: String): Unit = {
    Files.createDirectories(path.toAbsolutePath.getParent)
    Files.write(path, value.getBytes(StandardCharsets.UTF_8))
    ()
  }

  private def yosysPath(path: Path): String =
    path.toAbsolutePath.toString.replace("\\", "/")
}
