package morphhdl

import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._

import org.scalatest.funsuite.AnyFunSuite

import NativeStreamFifoCCProofSupport._

class NativeStreamFifoCCFormalEquivalenceTests extends AnyFunSuite {
  private val FormalGate = "MORPHDL_RUN_STREAMFIFOCC_FORMAL_EQUIVALENCE"

  private final case class Prepared(candidate: Path, concrete: Path)

  test("formal witnesses are independently generated native StreamFifoCC elaborations") {
    withTemporaryDirectory { directory =>
      Modes.foreach { buffered =>
        val candidate = generateCandidate(
          directory.resolve(s"candidate-${modeName(buffered)}"),
          buffered
        )
        val candidateText = read(candidate)
        assert(candidateText.contains("parameter integer DEPTH = 8"))
        Depths.foreach { depth =>
          val concrete = generateConcrete(
            directory.resolve(s"concrete-${depth}-${modeName(buffered)}"),
            depth,
            buffered
          )
          val concreteText = read(concrete)
          assert(!concreteText.contains("parameter integer DEPTH"))
          assert(candidateText != concreteText)
        }
      }
    }
  }

  test("specialized Morph StreamFifoCC is sequentially equivalent to native witnesses") {
    if (!sys.env.get(FormalGate).contains("1")) {
      cancel(s"Set $FormalGate=1 only in the pinned formal environment")
    }

    withTemporaryDirectory { directory =>
      requireTool(directory, Seq("yosys", "-V"), "Yosys")
      requireTool(directory, Seq("sby", "-h"), "SymbiYosys")
      requireTool(directory, Seq("z3", "-version"), "Z3")

      Modes.foreach { buffered =>
        val candidate = generateCandidate(
          directory.resolve(s"candidate-${modeName(buffered)}"),
          buffered
        )
        Depths.foreach { depth =>
          val concrete = generateConcrete(
            directory.resolve(s"concrete-${depth}-${modeName(buffered)}"),
            depth,
            buffered
          )
          val prepared = prepare(directory, candidate, concrete, depth, buffered)
          val miter = directory.resolve(s"miter-${depth}-${modeName(buffered)}.v")
          write(miter, equivalenceMiter(depth, buffered, mutateReady = false))
          val config = directory.resolve(s"equiv-${depth}-${modeName(buffered)}.sby")
          write(config, positiveSby(prepared, miter, miterTop(depth, buffered)))
          runSby(directory, config, expected = "PASS", counterexample = false)
        }
      }

      val depth = 4
      val buffered = false
      val candidate = generateCandidate(directory.resolve("mutation-candidate"), buffered)
      val concrete = generateConcrete(directory.resolve("mutation-concrete"), depth, buffered)
      val prepared = prepare(directory, candidate, concrete, depth, buffered)
      val miter = directory.resolve("miter-mutation.v")
      write(miter, equivalenceMiter(depth, buffered, mutateReady = true))
      val config = directory.resolve("equiv-mutation.sby")
      write(config, mutationSby(prepared, miter, miterTop(depth, buffered)))
      runSby(directory, config, expected = "FAIL", counterexample = true)
    }
  }

  private def prepare(
      directory: Path,
      candidate: Path,
      concrete: Path,
      depth: Int,
      buffered: Boolean
  ): Prepared = {
    val candidateIl = directory.resolve(s"candidate-${depth}-${modeName(buffered)}.il")
    val candidateScript = directory.resolve(s"prepare-candidate-${depth}-${modeName(buffered)}.ys")
    write(
      candidateScript,
      s"""read_verilog -defer ${yosysPath(candidate)}
         |chparam -set DEPTH $depth ${candidateTop(buffered)}
         |hierarchy -check -top ${candidateTop(buffered)}
         |flatten
         |proc
         |opt_clean
         |memory_dff
         |memory_collect
         |opt_clean
         |check -assert
         |rename -top ${candidatePreparedTop(depth, buffered)}
         |write_rtlil ${yosysPath(candidateIl)}
         |""".stripMargin
    )
    runYosys(directory, candidateScript, candidateIl)

    val concreteIl = directory.resolve(s"concrete-${depth}-${modeName(buffered)}.il")
    val concreteScript = directory.resolve(s"prepare-concrete-${depth}-${modeName(buffered)}.ys")
    write(
      concreteScript,
      s"""read_verilog -defer ${yosysPath(concrete)}
         |hierarchy -check -top ${concreteTop(depth, buffered)}
         |flatten
         |proc
         |opt_clean
         |memory_dff
         |memory_collect
         |opt_clean
         |check -assert
         |rename -top ${concretePreparedTop(depth, buffered)}
         |write_rtlil ${yosysPath(concreteIl)}
         |""".stripMargin
    )
    runYosys(directory, concreteScript, concreteIl)
    Prepared(candidateIl, concreteIl)
  }

  private def runYosys(directory: Path, script: Path, expected: Path): Unit = {
    val (code, output) = run(directory, Seq("yosys", "-Q", "-s", script.toString))
    assert(code == 0, s"Yosys preparation failed:\n$output")
    assert(Files.isRegularFile(expected), s"Yosys did not create $expected")
  }

  private def candidatePreparedTop(depth: Int, buffered: Boolean): String =
    s"morph_streamfifocc_${depth}_${modeName(buffered)}"

  private def concretePreparedTop(depth: Int, buffered: Boolean): String =
    s"native_streamfifocc_${depth}_${modeName(buffered)}"

  private def miterTop(depth: Int, buffered: Boolean): String =
    s"streamfifocc_equivalence_${depth}_${modeName(buffered)}"

  private def equivalenceMiter(
      depth: Int,
      buffered: Boolean,
      mutateReady: Boolean
  ): String = {
    val candidateReady =
      if (mutateReady) "(candidatePushReadyRaw ^ 1'b1)"
      else "candidatePushReadyRaw"
    s"""module ${miterTop(depth, buffered)} (
       |  input wire io_pushClock,
       |  input wire io_pushReset,
       |  input wire io_popClock,
       |  input wire io_popReset,
       |  input wire io_pushValid,
       |  input wire [7:0] io_pushPayload,
       |  input wire io_popReady
       |);
       |  wire nativePushReady;
       |  wire nativePopValid;
       |  wire [7:0] nativePopPayload;
       |  wire [4:0] nativePushOccupancy;
       |  wire [4:0] nativePopOccupancy;
       |  wire candidatePushReadyRaw;
       |  wire candidatePushReady;
       |  wire candidatePopValid;
       |  wire [7:0] candidatePopPayload;
       |  wire [4:0] candidatePushOccupancy;
       |  wire [4:0] candidatePopOccupancy;
       |
       |  assign candidatePushReady = $candidateReady;
       |
       |  ${concretePreparedTop(depth, buffered)} native_dut (
       |    .io_pushClock(io_pushClock),
       |    .io_pushReset(io_pushReset),
       |    .io_popClock(io_popClock),
       |    .io_popReset(io_popReset),
       |    .io_pushValid(io_pushValid),
       |    .io_pushReady(nativePushReady),
       |    .io_pushPayload(io_pushPayload),
       |    .io_popValid(nativePopValid),
       |    .io_popReady(io_popReady),
       |    .io_popPayload(nativePopPayload),
       |    .io_pushOccupancy(nativePushOccupancy),
       |    .io_popOccupancy(nativePopOccupancy)
       |  );
       |
       |  ${candidatePreparedTop(depth, buffered)} candidate_dut (
       |    .io_pushClock(io_pushClock),
       |    .io_pushReset(io_pushReset),
       |    .io_popClock(io_popClock),
       |    .io_popReset(io_popReset),
       |    .io_pushValid(io_pushValid),
       |    .io_pushReady(candidatePushReadyRaw),
       |    .io_pushPayload(io_pushPayload),
       |    .io_popValid(candidatePopValid),
       |    .io_popReady(io_popReady),
       |    .io_popPayload(candidatePopPayload),
       |    .io_pushOccupancy(candidatePushOccupancy),
       |    .io_popOccupancy(candidatePopOccupancy)
       |  );
       |
       |  reg pastValid = 1'b0;
       |  always @($$global_clock) begin
       |    pastValid <= 1'b1;
       |    if ($$initstate) begin
       |      assume(io_pushReset);
       |      assume(io_popReset);
       |    end
       |    if (pastValid) begin
       |      assert(nativePushReady == candidatePushReady);
       |      assert(nativePopValid == candidatePopValid);
       |      assert(nativePushOccupancy == candidatePushOccupancy);
       |      assert(nativePopOccupancy == candidatePopOccupancy);
       |      if (nativePopValid && candidatePopValid)
       |        assert(nativePopPayload == candidatePopPayload);
       |    end
       |  end
       |endmodule
       |""".stripMargin
  }

  private def positiveSby(
      prepared: Prepared,
      miter: Path,
      top: String
  ): String =
    s"""[options]
       |mode prove
       |depth 32
       |expect pass
       |multiclock on
       |timeout 600
       |
       |[engines]
       |smtbmc z3
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

  private def mutationSby(
      prepared: Prepared,
      miter: Path,
      top: String
  ): String =
    s"""[options]
       |mode bmc
       |depth 8
       |expect fail
       |multiclock on
       |timeout 120
       |
       |[engines]
       |smtbmc z3
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

  private def runSby(
      directory: Path,
      config: Path,
      expected: String,
      counterexample: Boolean
  ): Unit = {
    val work = directory.resolve(config.getFileName.toString.stripSuffix(".sby"))
    val (code, output) = run(
      directory,
      Seq("sby", "-f", "-d", work.toString, config.toString)
    )
    val status = work.resolve("status")
    val statusText = if (Files.isRegularFile(status)) read(status).trim else "MISSING"
    assert(
      statusText == expected,
      s"SymbiYosys status $statusText, expected $expected (exit=$code):\n$output"
    )
    if (expected == "PASS") assert(code == 0, output)
    if (counterexample) {
      val traces = if (Files.isDirectory(work)) {
        val stream = Files.walk(work)
        try stream.iterator().asScala.toVector.filter(path =>
          path.getFileName.toString.endsWith(".vcd") ||
            path.getFileName.toString.endsWith(".yw")
        )
        finally stream.close()
      } else Vector.empty
      assert(traces.nonEmpty, s"Mutation failed without a counterexample trace:\n$output")
    }
  }
}
