package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._
import scala.sys.process.ProcessLogger
import scala.util.matching.Regex

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._

import morphhdl.frontend.HdlInt

class NativeLibraryReuseTests extends AnyFunSuite {
  private final class NativeCounter(width: HdlInt) extends Component {
    setDefinitionName("NativeLibraryCounter")

    val increment = in(Bool())
    val clear = in(Bool())
    val value = out(UInt(width bits))
    val will_complete = out(Bool())
    val flow_valid = out(Bool())
    val flow_payload = out(UInt(width bits))

    val counter = Counter(width bits)
    when(increment) {
      counter.increment()
    }
    when(clear) {
      counter.clear()
    }

    val flow = counter.toFlow()
    value := counter.value
    will_complete := counter.willComplete
    flow_valid := flow.valid
    flow_payload := flow.payload
  }

  private final class NativePipelines(width: HdlInt) extends Component {
    setDefinitionName("NativeLibraryPipelines")

    val stream_in_valid = in(Bool())
    val stream_in_ready = out(Bool())
    val stream_in_payload = in(Bits(width bits))
    val stream_out_valid = out(Bool())
    val stream_out_ready = in(Bool())
    val stream_out_payload = out(Bits(width bits))

    val flow_in_valid = in(Bool())
    val flow_in_payload = in(Bits(width bits))
    val flow_out_valid = out(Bool())
    val flow_out_payload = out(Bits(width bits))

    val stream = Stream(Bits(width bits))
    stream.valid := stream_in_valid
    stream.payload := stream_in_payload
    stream_in_ready := stream.ready

    val pipedStream = stream.m2sPipe().s2mPipe().halfPipe()
    stream_out_valid := pipedStream.valid
    pipedStream.ready := stream_out_ready
    stream_out_payload := pipedStream.payload

    val flow = Flow(Bits(width bits))
    flow.valid := flow_in_valid
    flow.payload := flow_in_payload
    val pipedFlow = flow.m2sPipe
    flow_out_valid := pipedFlow.valid
    flow_out_payload := pipedFlow.payload
  }

  private final class NativeStaticDepthStreamFifo(width: HdlInt) extends Component {
    setDefinitionName("NativeStaticDepthStreamFifo")

    val push_valid = in(Bool())
    val push_ready = out(Bool())
    val push_payload = in(Bits(width bits))
    val pop_valid = out(Bool())
    val pop_ready = in(Bool())
    val pop_payload = out(Bits(width bits))
    val flush = in(Bool())
    val occupancy = out(UInt(3 bits))
    val availability = out(UInt(3 bits))

    val fifo = StreamFifo(Bits(width bits), depth = 4, latency = 2)
    fifo.setName("fifo")
    fifo.io.push.valid := push_valid
    fifo.io.push.payload := push_payload
    push_ready := fifo.io.push.ready
    pop_valid := fifo.io.pop.valid
    fifo.io.pop.ready := pop_ready
    pop_payload := fifo.io.pop.payload
    fifo.io.flush := flush
    occupancy := fifo.io.occupancy.resized
    availability := fifo.io.availability.resized
  }

  test("ordinary Counter retains a symbolic full-range UInt without replacing the library algorithm") {
    withTemporaryDirectory { directory =>
      val parameterizedDirectory = directory.resolve("parameterized")
      val concreteDirectory = directory.resolve("concrete")
      Files.createDirectories(parameterizedDirectory)
      Files.createDirectories(concreteDirectory)

      val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 32)
      val parameterized = emitMorph(
        parameterizedDirectory,
        "native_library_counter.v",
        new NativeCounter(width),
        synchronousResetConfig(parameterizedDirectory)
      )
      val concrete = emitConcrete(
        concreteDirectory,
        "native_library_counter.v",
        new NativeCounter(width),
        synchronousResetConfig(concreteDirectory)
      )

      assert(
        nativeModule(concretize(parameterized, "NativeLibraryCounter", width = 8)) ==
          nativeModule(concrete)
      )
      assert(parameterized.contains("module NativeLibraryCounter #("))
      assert(parameterized.contains("parameter integer WIDTH = 8"))
      assert(hasDeclarationWidth(parameterized, "value", "[WIDTH-1:0]"))
      assert(hasDeclarationWidth(parameterized, "flow_payload", "[WIDTH-1:0]"))
      assert(parameterized.contains("always @(posedge clk)"))
      assert(parameterized.contains("increment"))
      assert(parameterized.contains("clear"))
      assert(parameterized.contains("will_complete"))
      assert(parameterized.contains("&counter_value"))
      assert(!parameterized.contains("ParamRTL"))

      compileOverride(
        parameterizedDirectory,
        parameterizedDirectory.resolve("native_library_counter.v"),
        "NativeLibraryCounter",
        """  reg increment;
          |  reg clear;
          |  wire [4:0] value;
          |  wire will_complete;
          |  wire flow_valid;
          |  wire [4:0] flow_payload;
          |""".stripMargin,
        """    .increment(increment),
          |    .clear(clear),
          |    .value(value),
          |    .will_complete(will_complete),
          |    .flow_valid(flow_valid),
          |    .flow_payload(flow_payload)
          |""".stripMargin
      )
    }
  }

  test("ordinary Stream and Flow pipeline primitives preserve one symbolic payload shape") {
    withTemporaryDirectory { directory =>
      val parameterizedDirectory = directory.resolve("parameterized")
      val concreteDirectory = directory.resolve("concrete")
      Files.createDirectories(parameterizedDirectory)
      Files.createDirectories(concreteDirectory)

      val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 32)
      val parameterized = emitMorph(
        parameterizedDirectory,
        "native_library_pipelines.v",
        new NativePipelines(width),
        synchronousResetConfig(parameterizedDirectory)
      )
      val concrete = emitConcrete(
        concreteDirectory,
        "native_library_pipelines.v",
        new NativePipelines(width),
        synchronousResetConfig(concreteDirectory)
      )

      assert(
        nativeModule(concretize(parameterized, "NativeLibraryPipelines", width = 8)) ==
          nativeModule(concrete)
      )
      assert(parameterized.contains("parameter integer WIDTH = 8"))
      Vector(
        "stream_in_payload",
        "stream_out_payload",
        "flow_in_payload",
        "flow_out_payload"
      ).foreach { name =>
        assert(hasDeclarationWidth(parameterized, name, "[WIDTH-1:0]"))
      }
      assert(count(parameterized, "always @(posedge clk)") >= 1)
      assert(parameterized.contains("stream_in_ready"))
      assert(parameterized.contains("stream_out_ready"))
      assert(parameterized.contains("flow_in_valid"))
      assert(parameterized.contains("flow_out_valid"))
      assert(!parameterized.contains("ParamRTL"))

      compileOverride(
        parameterizedDirectory,
        parameterizedDirectory.resolve("native_library_pipelines.v"),
        "NativeLibraryPipelines",
        """  reg stream_in_valid;
          |  wire stream_in_ready;
          |  reg [4:0] stream_in_payload;
          |  wire stream_out_valid;
          |  reg stream_out_ready;
          |  wire [4:0] stream_out_payload;
          |  reg flow_in_valid;
          |  reg [4:0] flow_in_payload;
          |  wire flow_out_valid;
          |  wire [4:0] flow_out_payload;
          |""".stripMargin,
        """    .stream_in_valid(stream_in_valid),
          |    .stream_in_ready(stream_in_ready),
          |    .stream_in_payload(stream_in_payload),
          |    .stream_out_valid(stream_out_valid),
          |    .stream_out_ready(stream_out_ready),
          |    .stream_out_payload(stream_out_payload),
          |    .flow_in_valid(flow_in_valid),
          |    .flow_in_payload(flow_in_payload),
          |    .flow_out_valid(flow_out_valid),
          |    .flow_out_payload(flow_out_payload)
          |""".stripMargin
      )
    }
  }

  test("ordinary StreamFifo reuses its native static-depth storage with a symbolic payload width") {
    withTemporaryDirectory { directory =>
      val parameterizedDirectory = directory.resolve("parameterized")
      val concreteDirectory = directory.resolve("concrete")
      Files.createDirectories(parameterizedDirectory)
      Files.createDirectories(concreteDirectory)

      val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 32)
      val parameterizedReport = emitMorphReport(
        parameterizedDirectory,
        "native_static_depth_stream_fifo.v",
        new NativeStaticDepthStreamFifo(width),
        synchronousResetConfig(parameterizedDirectory)
      )
      val parameterized = parameterizedReport._2
      val concrete = emitConcrete(
        concreteDirectory,
        "native_static_depth_stream_fifo.v",
        new NativeStaticDepthStreamFifo(width),
        synchronousResetConfig(concreteDirectory)
      )

      assert(parameterizedReport._1.parameters.map(_.name) == Vector("WIDTH"))
      assert(parameterized.contains("module NativeStaticDepthStreamFifo #("))
      assert(parameterized.contains("parameter integer WIDTH = 8"))
      assert("(?m)^module StreamFifo #\\(".r.findAllMatchIn(parameterized).size == 1)
      assert(parameterized.contains(".WIDTH(WIDTH)"))
      assert(!parameterized.contains("parameter integer DEPTH"))
      assert("(?m)^\\s*reg \\[WIDTH-1:0\\] [A-Za-z_][A-Za-z0-9_$]* \\[0:3\\];$".r
        .findFirstIn(parameterized).nonEmpty)
      assert(parameterized.contains("always @(posedge clk) begin : p_"))
      assert(parameterized.contains("< 4"))
      assert(parameterized.contains("<=") && parameterized.contains("["))
      assert(hasDeclarationWidth(parameterized, "push_payload", "[WIDTH-1:0]"))
      assert(hasDeclarationWidth(parameterized, "pop_payload", "[WIDTH-1:0]"))
      assert(!parameterized.contains("ParamRTL"))

      assert(!concrete.contains("parameter integer WIDTH"))
      assert(concrete.contains("[7:0]"))
      assert(concrete.contains("[0:3]"))

      compileOverride(
        parameterizedDirectory,
        parameterizedDirectory.resolve("native_static_depth_stream_fifo.v"),
        "NativeStaticDepthStreamFifo",
        """  reg push_valid;
          |  wire push_ready;
          |  reg [4:0] push_payload;
          |  wire pop_valid;
          |  reg pop_ready;
          |  wire [4:0] pop_payload;
          |  reg flush;
          |  wire [2:0] occupancy;
          |  wire [2:0] availability;
          |""".stripMargin,
        """    .push_valid(push_valid),
          |    .push_ready(push_ready),
          |    .push_payload(push_payload),
          |    .pop_valid(pop_valid),
          |    .pop_ready(pop_ready),
          |    .pop_payload(pop_payload),
          |    .flush(flush),
          |    .occupancy(occupancy),
          |    .availability(availability)
          |""".stripMargin
      )
    }
  }

  private def emitMorph(
      directory: Path,
      filename: String,
      component: => Component,
      config: SpinalConfig
  ): String = emitMorphReport(directory, filename, component, config)._2

  private def emitMorphReport(
      directory: Path,
      filename: String,
      component: => Component,
      config: SpinalConfig
  ): (MorphSingleSourceVerilogReport, String) = {
    config.netlistFileName = filename
    val report = MorphVerilog(config)(component)
    report -> read(directory.resolve(filename))
  }

  private def emitConcrete(
      directory: Path,
      filename: String,
      component: => Component,
      config: SpinalConfig
  ): String = {
    config.netlistFileName = filename
    SpinalVerilog(config)(component)
    read(directory.resolve(filename))
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

  private def nativeModule(verilog: String): String =
    "(?m)^module\\s".r
      .findFirstMatchIn(verilog)
      .map(module => verilog.substring(module.start))
      .getOrElse(verilog)

  private def concretize(verilog: String, moduleName: String, width: Int): String = {
    val header: Regex =
      ("(?s)module " + java.util.regex.Pattern.quote(moduleName) + " #\\(\\n.*?\\n\\) \\(").r
    header
      .replaceFirstIn(verilog, s"module $moduleName (")
      .replace("[WIDTH-1:0]", s"[${width - 1}:0]")
  }

  private def hasDeclarationWidth(
      verilog: String,
      name: String,
      range: String
  ): Boolean = {
    val pattern =
      (java.util.regex.Pattern.quote(range) + "\\s+" +
        java.util.regex.Pattern.quote(name) + "(?=\\s*(?:[,;]|\\)))").r
    pattern.findFirstIn(verilog).nonEmpty
  }

  private def compileOverride(
      directory: Path,
      verilog: Path,
      moduleName: String,
      declarations: String,
      connections: String
  ): Unit = {
    val testbench = directory.resolve(moduleName + "OverrideTb.v")
    val executable = directory.resolve(moduleName + "OverrideTb.out")
    val source =
      s"""module ${moduleName}OverrideTb;
         |  reg clk;
         |  reg reset;
         |$declarations
         |  $moduleName #(
         |    .WIDTH(5)
         |  ) dut (
         |    .clk(clk),
         |    .reset(reset),
         |$connections  );
         |endmodule
         |""".stripMargin
    Files.write(testbench, source.getBytes(StandardCharsets.UTF_8))

    val log = new StringBuilder
    val status = scala.sys.process.Process(
      Seq(
        "iverilog",
        "-g2001",
        "-s",
        moduleName + "OverrideTb",
        "-o",
        executable.toString,
        verilog.toString,
        testbench.toString
      ),
      directory.toFile
    ).!(ProcessLogger(line => log.append(line).append('\n')))
    assert(status == 0, log.toString)
  }

  private def count(value: String, needle: String): Int =
    value.sliding(needle.length).count(_ == needle)

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def withTemporaryDirectory(body: Path => Unit): Unit = {
    val directory = Files.createTempDirectory("morphhdl-native-library-test-")
    try body(directory)
    finally {
      val stream = Files.walk(directory)
      try {
        stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach {
          path => Files.deleteIfExists(path)
        }
      } finally stream.close()
    }
  }
}
