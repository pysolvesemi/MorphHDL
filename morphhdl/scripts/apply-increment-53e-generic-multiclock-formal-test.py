#!/usr/bin/env python3
from pathlib import Path

path = Path(
    "morphhdl/src/test/scala/morphhdl/"
    "NativeStreamFifoCCFormalEquivalenceTests.scala"
)
path.write_text(r'''package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._
import scala.sys.process.{Process, ProcessLogger}

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

import morphhdl.frontend.HdlInt
import morphhdl.frontend.HdlInt.hdlIntToParameterizedMemoryDepth

/**
  * Solver-backed equivalence for the exact untouched native StreamFifoCC.
  *
  * The proof infrastructure itself is generic: candidate and reference are
  * independent Verilog elaborations, prepared in separate Yosys processes and
  * joined only through a shared public-port miter. No internal FIFO signal name
  * or implementation structure participates in correspondence.
  */
class NativeStreamFifoCCFormalEquivalenceTests extends AnyFunSuite {
  private val Depths = Vector(4, 8, 16)
  private val ResetModes = Vector(false, true)
  private val Gate = "MORPHDL_RUN_STREAMFIFOCC_FORMAL_EQUIVALENCE"
  private val Workspace = "MORPHDL_STREAMFIFOCC_FORMAL_WORKSPACE"

  private final case class Generated(
      candidate: Path,
      concrete: Map[(Int, Boolean), Path]
  )
  private final case class Prepared(candidate: Path, concrete: Path)

  test("native multiclock witnesses are independently elaborated") {
    withTemporaryDirectory { directory =>
      val generated = generate(directory)
      validateGenerated(generated)
    }
  }

  test("parameterized native StreamFifoCC equals every concrete witness") {
    if (!sys.env.get(Gate).contains("1")) {
      cancel(s"Set $Gate=1 only in the pinned formal workflow")
    }
    withWorkspace { directory =>
      requireTool(directory, Seq("yosys", "-V"), "Yosys")
      requireTool(directory, Seq("sby", "-h"), "SymbiYosys")
      requireTool(directory, Seq("yosys-smtbmc", "-h"), "yosys-smtbmc")
      requireTool(directory, Seq("boolector", "--version"), "Boolector")

      val generated = generate(directory)
      validateGenerated(generated)
      val prepared = (for {
        depth <- Depths
        buffered <- ResetModes
      } yield (depth -> buffered) -> prepare(
        directory,
        generated,
        depth,
        buffered
      )).toMap

      for {
        depth <- Depths
        buffered <- ResetModes
      } {
        val key = depth -> buffered
        val miter = directory.resolve(s"miter_${suffix(depth, buffered)}.v")
        write(miter, miterText(depth, buffered, mutateReady = false))
        val config = directory.resolve(s"prove_${suffix(depth, buffered)}.sby")
        write(config, positiveConfig(prepared(key), miter, miterTop(depth, buffered)))
        runSby(directory, config, expected = "PASS", counterexample = false)
      }

      val mutationDepth = 4
      val mutationBuffered = false
      val mutationKey = mutationDepth -> mutationBuffered
      val mutation = directory.resolve("miter_mutated_ready.v")
      write(mutation, miterText(mutationDepth, mutationBuffered, mutateReady = true))
      val mutationConfig = directory.resolve("mutation.sby")
      write(
        mutationConfig,
        mutationConfigText(
          prepared(mutationKey),
          mutation,
          miterTop(mutationDepth, mutationBuffered)
        )
      )
      runSby(directory, mutationConfig, expected = "FAIL", counterexample = true)
    }
  }

  private def clocks(): (ClockDomain, ClockDomain) = {
    val config = ClockDomainConfig(resetKind = ASYNC, resetActiveLevel = HIGH)
    ClockDomain.external("push", config) -> ClockDomain.external("pop", config)
  }

  private def generate(directory: Path): Generated = {
    val candidateDirectory = directory.resolve("candidate")
    Files.createDirectories(candidateDirectory)
    val candidateConfig = SpinalConfig(targetDirectory = candidateDirectory.toString)
    candidateConfig.netlistFileName = "stream_fifocc_parameterized.v"
    val depth = HdlInt.param(
      "DEPTH",
      default = BigInt(8),
      min = BigInt(4),
      max = BigInt(16)
    )
    MorphVerilog(candidateConfig) {
      val (pushClock, popClock) = clocks()
      val component = morphhdl.frontend.StreamFifoCC(
        HardType(Bits(8 bits)),
        depth,
        pushClock,
        popClock,
        withPopBufferedReset = false
      )
      component.setDefinitionName("ParameterizedStreamFifoCC")
      component
    }
    val candidate = candidateDirectory.resolve("stream_fifocc_parameterized.v")

    val concrete = (for {
      selectedDepth <- Depths
      buffered <- ResetModes
    } yield {
      val concreteDirectory =
        directory.resolve(s"concrete-${suffix(selectedDepth, buffered)}")
      Files.createDirectories(concreteDirectory)
      val config = SpinalConfig(targetDirectory = concreteDirectory.toString)
      val file = s"stream_fifocc_concrete_${suffix(selectedDepth, buffered)}.v"
      config.netlistFileName = file
      SpinalVerilog(config) {
        val (pushClock, popClock) = clocks()
        val component = new spinal.lib.StreamFifoCC(
          HardType(Bits(8 bits)),
          selectedDepth,
          pushClock,
          popClock,
          withPopBufferedReset = buffered
        )
        component.setDefinitionName(concreteTop(selectedDepth, buffered))
        component
      }
      (selectedDepth -> buffered) -> concreteDirectory.resolve(file)
    }).toMap

    Generated(candidate, concrete)
  }

  private def validateGenerated(generated: Generated): Unit = {
    val candidate = read(generated.candidate)
    assert(candidate.contains("module ParameterizedStreamFifoCC #("))
    assert(candidate.contains("parameter integer DEPTH = 8"))
    requiredPorts.foreach(port => assert(candidate.contains(port), s"missing $port"))
    generated.concrete.foreach { case ((depth, buffered), path) =>
      val concrete = read(path)
      assert(concrete.contains(s"module ${concreteTop(depth, buffered)}"))
      assert(!concrete.contains("parameter integer DEPTH"))
      requiredPorts.foreach(port => assert(concrete.contains(port), s"missing $port"))
    }
    assert(generated.concrete.values.map(read).toSet.size == Depths.size * ResetModes.size)
  }

  private def prepare(
      directory: Path,
      generated: Generated,
      depth: Int,
      buffered: Boolean
  ): Prepared = {
    val candidate = directory.resolve(s"candidate_${suffix(depth, buffered)}.il")
    val candidateScript = directory.resolve(s"candidate_${suffix(depth, buffered)}.ys")
    write(
      candidateScript,
      s"""read_verilog -defer ${quote(generated.candidate)}
         |chparam -set DEPTH $depth ParameterizedStreamFifoCC
         |hierarchy -check -top ParameterizedStreamFifoCC
         |flatten
         |proc
         |opt
         |memory_dff
         |memory_collect
         |opt_clean
         |check -assert
         |rename -top ${candidateTop(depth, buffered)}
         |write_rtlil ${quote(candidate)}
         |""".stripMargin
    )
    runYosys(directory, candidateScript)

    val concrete = directory.resolve(s"reference_${suffix(depth, buffered)}.il")
    val concreteScript = directory.resolve(s"reference_${suffix(depth, buffered)}.ys")
    write(
      concreteScript,
      s"""read_verilog -defer ${quote(generated.concrete(depth -> buffered))}
         |hierarchy -check -top ${concreteTop(depth, buffered)}
         |flatten
         |proc
         |opt
         |memory_dff
         |memory_collect
         |opt_clean
         |check -assert
         |rename -top ${referenceTop(depth, buffered)}
         |write_rtlil ${quote(concrete)}
         |""".stripMargin
    )
    runYosys(directory, concreteScript)
    Prepared(candidate, concrete)
  }

  private def miterText(depth: Int, buffered: Boolean, mutateReady: Boolean): String = {
    val width = BigInt(depth).bitLength
    val comparedReady = if (mutateReady) "(candidate_push_ready ^ 1'b1)" else "candidate_push_ready"
    s"""module ${miterTop(depth, buffered)} (
       |  input wire push_clk,
       |  input wire push_reset,
       |  input wire pop_clk,
       |  input wire pop_reset,
       |  input wire io_push_valid,
       |  input wire [7:0] io_push_payload,
       |  input wire io_pop_ready
       |);
       |  wire reference_push_ready;
       |  wire reference_pop_valid;
       |  wire [7:0] reference_pop_payload;
       |  wire [${width - 1}:0] reference_push_occupancy;
       |  wire [${width - 1}:0] reference_pop_occupancy;
       |  wire candidate_push_ready;
       |  wire candidate_pop_valid;
       |  wire [7:0] candidate_pop_payload;
       |  wire [${width - 1}:0] candidate_push_occupancy;
       |  wire [${width - 1}:0] candidate_pop_occupancy;
       |
       |  ${referenceTop(depth, buffered)} reference (
       |    .io_push_valid(io_push_valid),
       |    .io_push_ready(reference_push_ready),
       |    .io_push_payload(io_push_payload),
       |    .io_pop_valid(reference_pop_valid),
       |    .io_pop_ready(io_pop_ready),
       |    .io_pop_payload(reference_pop_payload),
       |    .io_pushOccupancy(reference_push_occupancy),
       |    .io_popOccupancy(reference_pop_occupancy),
       |    .push_clk(push_clk),
       |    .push_reset(push_reset),
       |    .pop_clk(pop_clk),
       |    .pop_reset(pop_reset)
       |  );
       |
       |  ${candidateTop(depth, buffered)} candidate (
       |    .io_push_valid(io_push_valid),
       |    .io_push_ready(candidate_push_ready),
       |    .io_push_payload(io_push_payload),
       |    .io_pop_valid(candidate_pop_valid),
       |    .io_pop_ready(io_pop_ready),
       |    .io_pop_payload(candidate_pop_payload),
       |    .io_pushOccupancy(candidate_push_occupancy),
       |    .io_popOccupancy(candidate_pop_occupancy),
       |    .push_clk(push_clk),
       |    .push_reset(push_reset),
       |    .pop_clk(pop_clk),
       |    .pop_reset(pop_reset)
       |  );
       |
       |  always @($$global_clock) begin
       |    if ($$initstate) begin
       |      assume(push_reset);
       |      assume(pop_reset);
       |    end
       |    if (!$$initstate) begin
       |      assert(reference_push_ready == $comparedReady);
       |      assert(reference_pop_valid == candidate_pop_valid);
       |      assert(reference_push_occupancy == candidate_push_occupancy);
       |      assert(reference_pop_occupancy == candidate_pop_occupancy);
       |      if (reference_pop_valid && candidate_pop_valid)
       |        assert(reference_pop_payload == candidate_pop_payload);
       |    end
       |  end
       |endmodule
       |""".stripMargin
  }

  private def positiveConfig(prepared: Prepared, miter: Path, top: String): String =
    s"""[options]
       |mode prove
       |expect pass
       |multiclock on
       |timeout 600
       |
       |[engines]
       |abc pdr
       |
       |[script]
       |read_rtlil ${prepared.candidate.getFileName}
       |read_rtlil ${prepared.concrete.getFileName}
       |read_verilog -formal ${miter.getFileName}
       |hierarchy -check -top $top
       |prep -top $top
       |memory_map
       |setundef -undriven -anyseq
       |opt_clean
       |check -assert
       |
       |[files]
       |${prepared.candidate.toAbsolutePath}
       |${prepared.concrete.toAbsolutePath}
       |${miter.toAbsolutePath}
       |""".stripMargin

  private def mutationConfigText(prepared: Prepared, miter: Path, top: String): String =
    s"""[options]
       |mode bmc
       |depth 8
       |expect fail
       |multiclock on
       |timeout 120
       |
       |[engines]
       |smtbmc boolector
       |
       |[script]
       |read_rtlil ${prepared.candidate.getFileName}
       |read_rtlil ${prepared.concrete.getFileName}
       |read_verilog -formal ${miter.getFileName}
       |hierarchy -check -top $top
       |prep -top $top
       |memory_map
       |setundef -undriven -anyseq
       |opt_clean
       |check -assert
       |
       |[files]
       |${prepared.candidate.toAbsolutePath}
       |${prepared.concrete.toAbsolutePath}
       |${miter.toAbsolutePath}
       |""".stripMargin

  private def runSby(directory: Path, config: Path, expected: String, counterexample: Boolean): Unit = {
    val (code, output) = run(directory, Seq("sby", "-f", config.getFileName.toString))
    val status = directory.resolve(config.getFileName.toString.stripSuffix(".sby")).resolve("status")
    val statusText = if (Files.exists(status)) read(status).trim else "MISSING"
    assert(statusText == expected, s"expected $expected, got $statusText\n$output")
    if (counterexample) {
      val traces = Files.walk(directory.resolve(config.getFileName.toString.stripSuffix(".sby")))
      try assert(traces.iterator().asScala.exists(path => path.toString.endsWith(".vcd")))
      finally traces.close()
    } else assert(code == 0, output)
  }

  private def runYosys(directory: Path, script: Path): Unit = {
    val (code, output) = run(directory, Seq("yosys", "-Q", "-s", script.getFileName.toString))
    assert(code == 0, output)
  }

  private def requireTool(directory: Path, command: Seq[String], name: String): Unit = {
    val (code, output) = run(directory, command)
    assert(code == 0, s"$name unavailable\n$output")
  }

  private def run(directory: Path, command: Seq[String]): (Int, String) = {
    val output = new StringBuilder
    val logger = ProcessLogger(line => output.append(line).append('\n'), line => output.append(line).append('\n'))
    Process(command, directory.toFile).!(logger) -> output.toString
  }

  private def requiredPorts = Vector(
    "io_push_valid", "io_push_ready", "io_push_payload",
    "io_pop_valid", "io_pop_ready", "io_pop_payload",
    "io_pushOccupancy", "io_popOccupancy",
    "push_clk", "push_reset", "pop_clk", "pop_reset"
  )

  private def candidateTop(depth: Int, buffered: Boolean) = s"candidate_${suffix(depth, buffered)}"
  private def referenceTop(depth: Int, buffered: Boolean) = s"reference_${suffix(depth, buffered)}"
  private def concreteTop(depth: Int, buffered: Boolean) = s"ConcreteStreamFifoCC_${suffix(depth, buffered)}"
  private def miterTop(depth: Int, buffered: Boolean) = s"miter_${suffix(depth, buffered)}"
  private def suffix(depth: Int, buffered: Boolean) = s"d${depth}_${if (buffered) "buffered" else "direct"}"
  private def quote(path: Path) = path.toAbsolutePath.toString.replace("\\", "/")
  private def write(path: Path, value: String) = Files.write(path, value.getBytes(StandardCharsets.UTF_8))
  private def read(path: Path) = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def withTemporaryDirectory(body: Path => Unit): Unit = {
    val directory = Files.createTempDirectory("morphhdl-streamfifocc-formal-")
    try body(directory)
    finally delete(directory)
  }

  private def withWorkspace(body: Path => Unit): Unit = sys.env.get(Workspace) match {
    case Some(value) =>
      val directory = Path.of(value).toAbsolutePath
      Files.createDirectories(directory)
      body(directory)
    case None => withTemporaryDirectory(body)
  }

  private def delete(path: Path): Unit = if (Files.exists(path)) {
    val stream = Files.walk(path)
    try stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists)
    finally stream.close()
  }
}
''')
