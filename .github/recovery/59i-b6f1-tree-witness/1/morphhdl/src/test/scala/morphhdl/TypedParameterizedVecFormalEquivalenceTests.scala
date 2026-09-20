package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import scala.collection.JavaConverters._
import scala.sys.process.{Process, ProcessLogger}

import org.scalatest.funsuite.AnyFunSuite

import morphhdl.frontend.HdlInt
import spinal.core._

private object TypedParameterizedVecFormalEquivalenceFixture {
  final class TypedTop(width: ElabInt, depth: ElabInt) extends Component {
    setDefinitionName("TypedParameterizedVecFormalTop")

    val vecIn = in(Vec(UInt(width bits), depth)).setName("vec_in")
    val index = in(UInt(3 bits)).setName("index")
    val writeData = in(UInt(width bits)).setName("write_data")
    val vecOut = out(Vec(UInt(width bits), depth)).setName("vec_out")
    val first = out(UInt(width bits)).setName("first")
    val selected = out(UInt(width bits)).setName("selected")

    val storage = Vec(UInt(width bits), depth).setName("storage")
    storage := vecIn
    storage(index) := writeData
    vecOut := storage
    first := storage(0)
    selected := storage(index)
  }

  final class ConcreteTop(width: Int, depth: Int) extends Component {
    require(width > 0)
    require(Vector(1, 3, 5, 8).contains(depth))
    setDefinitionName(s"ConcreteVecFormalTopW${width}D${depth}")

    val vecIn = in(Vec(UInt(width bits), depth)).setName("vec_in")
    val index = in(UInt(3 bits)).setName("index")
    val writeData = in(UInt(width bits)).setName("write_data")
    val vecOut = out(Vec(UInt(width bits), depth)).setName("vec_out")
    val first = out(UInt(width bits)).setName("first")
    val selected = out(UInt(width bits)).setName("selected")

    val storage = Vec(UInt(width bits), depth).setName("storage")
    val nativeIndex = index.resized
    val comparisonWidth = index.getWidth + 1
    val indexInRange =
      index.resize(comparisonWidth) < U(depth, comparisonWidth bits)
    storage := vecIn
    // Keep the ordinary native-Int Vec algorithm authoritative for every
    // legal index, while specifying the full-width address behavior that the
    // packed parameterized publication must preserve outside that range.
    when(indexInRange) {
      storage(nativeIndex) := writeData
    }
    vecOut := storage
    first := storage(0)
    selected := Mux(indexInRange, storage(nativeIndex), storage(depth - 1))
  }

  def typed(): TypedTop =
    new TypedTop(
      HdlInt.param("WIDTH", default = 5, min = 1, max = 16).asElabInt,
      HdlInt.param("DEPTH", default = 5, min = 1, max = 8).asElabInt
    )
}

/** Formal comparison of the packed typed Vec publication against independent
  * ordinary native-Int Vec elaborations.
  *
  * Candidate and reference legs are generated in disjoint directories and
  * flattened by separate Yosys processes before the miter is loaded. The
  * concrete leg deliberately retains ordinary exploded Vec ports; the miter
  * maps those ports to the canonical packed zero-based slices explicitly.
  */
class TypedParameterizedVecFormalEquivalenceTests extends AnyFunSuite {
  import TypedParameterizedVecFormalEquivalenceFixture._

  private final case class Witness(width: Int, depth: Int)
  private final case class GeneratedDuts(
      parameterized: Path,
      concrete: Map[Witness, Path]
  )
  private final case class PreparedDuts(candidate: Path, reference: Path)

  private val Witnesses = Vector(
    Witness(width = 3, depth = 1),
    Witness(width = 5, depth = 3),
    Witness(width = 7, depth = 5),
    Witness(width = 9, depth = 8)
  )
  private val FormalGateEnvironment =
    "MORPHDL_RUN_TYPED_VEC_FORMAL_EQUIVALENCE"
  private val FormalWorkspaceEnvironment =
    "MORPHDL_TYPED_VEC_FORMAL_WORKSPACE"
  private val ParameterizedFile = "typed_parameterized_vec_formal.v"
  private val ModuleDeclaration =
    """(?m)^\s*module\s+([A-Za-z_][A-Za-z0-9_$]*)\b""".r

  test("formal Vec references are independent native-Int elaborations of one typed definition") {
    withTemporaryDirectory { directory =>
      validateGeneratedDuts(generateDuts(directory))
    }
  }

  test("one typed Vec definition is formally equivalent at depths 1 3 5 and 8") {
    if (!sys.env.get(FormalGateEnvironment).contains("1")) {
      cancel(s"Set $FormalGateEnvironment=1 only in the pinned formal container")
    }

    withFormalWorkspace { directory =>
      requireFormalTool(directory, Seq("yosys", "-V"), "Yosys")
      requireFormalTool(directory, Seq("sby", "-h"), "SymbiYosys")
      requireFormalTool(
        directory,
        Seq("yices-smt2", "--version"),
        "Yices SMT2"
      )
      requireFormalTool(
        directory,
        Seq("yosys", "-Q", "-p", "help abc"),
        "Yosys ABC integration"
      )

      val generated = generateDuts(directory)
      validateGeneratedDuts(generated)
      val originalCandidate = Files.readAllBytes(generated.parameterized)
      val prepared = Witnesses.map { witness =>
        witness -> prepareDuts(directory, generated, witness)
      }.toMap

      Witnesses.foreach { witness =>
        val miter = directory.resolve(s"vec_equivalence_${stem(witness)}.v")
        write(miter, equivalenceMiter(witness, mutateFirst = false))
        val config = directory.resolve(s"vec_equivalence_${stem(witness)}.sby")
        write(
          config,
          positiveSby(prepared(witness), miter, miterModule(witness))
        )
        runSby(
          directory,
          config,
          expectedStatus = "PASS",
          requireCounterexample = false
        )
        assert(
          java.util.Arrays.equals(
            originalCandidate,
            Files.readAllBytes(generated.parameterized)
          ),
          "formal specialization rewrote the one parameterized Vec definition"
        )
      }

      val mutationWitness = Witness(width = 5, depth = 3)
      val mutationMiter =
        directory.resolve("vec_equivalence_w5_d3_mutation.v")
      write(
        mutationMiter,
        equivalenceMiter(mutationWitness, mutateFirst = true)
      )
      val mutationConfig =
        directory.resolve("vec_equivalence_w5_d3_mutation.sby")
      write(
        mutationConfig,
        mutationSby(
          prepared(mutationWitness),
          mutationMiter,
          miterModule(mutationWitness)
        )
      )
      runSby(
        directory,
        mutationConfig,
        expectedStatus = "FAIL",
        requireCounterexample = true
      )
    }
  }

  private def generateDuts(directory: Path): GeneratedDuts = {
    val candidateDirectory = directory.resolve("parameterized")
    Files.createDirectories(candidateDirectory)
    val candidateConfig = generationConfig(candidateDirectory)
    candidateConfig.netlistFileName = ParameterizedFile
    MorphVerilog(candidateConfig)(typed())
    val parameterized = candidateDirectory.resolve(ParameterizedFile)

    val concrete = Witnesses.map { witness =>
      val referenceDirectory = directory.resolve(s"concrete-${stem(witness)}")
      Files.createDirectories(referenceDirectory)
      val referenceConfig = generationConfig(referenceDirectory)
      val file = s"concrete_vec_${stem(witness)}.v"
      referenceConfig.netlistFileName = file
      SpinalVerilog(referenceConfig) {
        new ConcreteTop(witness.width, witness.depth)
      }
      witness -> referenceDirectory.resolve(file)
    }.toMap

    GeneratedDuts(parameterized, concrete)
  }

  private def validateGeneratedDuts(generated: GeneratedDuts): Unit = {
    val candidate = read(generated.parameterized)
    assert(candidate.contains("parameter integer DEPTH = 5"))
    assert(candidate.contains("parameter integer WIDTH = 5"))
    assert(candidate.contains("module TypedParameterizedVecFormalTop #("))
    assert(candidate.contains("vec_in"))
    assert(candidate.contains("vec_out"))
    assert(!candidate.contains("vec_in_0"))
    assert(!candidate.contains("vec_out_0"))
    assert(moduleNames(candidate) == Vector("TypedParameterizedVecFormalTop"))

    val concreteTexts = generated.concrete.toVector.map { case (witness, path) =>
      val value = read(path)
      assert(!value.contains("parameter integer WIDTH"))
      assert(!value.contains("parameter integer DEPTH"))
      assert(value.contains("vec_in_0"))
      assert(value.contains(s"vec_out_${witness.depth - 1}"))
      assert(
        moduleNames(value) ==
          Vector(s"ConcreteVecFormalTopW${witness.width}D${witness.depth}")
      )
      value
    }
    assert(
      concreteTexts.toSet.size == Witnesses.size,
      "concrete Vec witnesses were not independently specialized"
    )
  }

  private def prepareDuts(
      directory: Path,
      generated: GeneratedDuts,
      witness: Witness
  ): PreparedDuts = {
    val candidate = directory.resolve(s"candidate_${stem(witness)}.il")
    val candidateScript = directory.resolve(s"prepare_candidate_${stem(witness)}.ys")
    write(
      candidateScript,
      s"""read_verilog -defer ${yosysPath(generated.parameterized)}
         |chparam -set WIDTH ${witness.width} -set DEPTH ${witness.depth} TypedParameterizedVecFormalTop
         |hierarchy -check -top TypedParameterizedVecFormalTop
         |flatten
         |proc
         |opt_clean
         |check -assert
         |rename -top ${candidateTop(witness)}
         |write_rtlil ${yosysPath(candidate)}
         |""".stripMargin
    )
    runYosys(directory, candidateScript, candidate)

    val reference = directory.resolve(s"reference_${stem(witness)}.il")
    val referenceScript = directory.resolve(s"prepare_reference_${stem(witness)}.ys")
    write(
      referenceScript,
      s"""read_verilog -defer ${yosysPath(generated.concrete(witness))}
         |hierarchy -check -top ConcreteVecFormalTopW${witness.width}D${witness.depth}
         |flatten
         |proc
         |opt_clean
         |check -assert
         |rename -top ${referenceTop(witness)}
         |write_rtlil ${yosysPath(reference)}
         |""".stripMargin
    )
    runYosys(directory, referenceScript, reference)

    PreparedDuts(candidate, reference)
  }

  private def equivalenceMiter(
      witness: Witness,
      mutateFirst: Boolean
  ): String = {
    val totalWidth = witness.width * witness.depth
    val concreteInputs = (0 until witness.depth).map { index =>
      val low = index * witness.width
      val high = low + witness.width - 1
      s"    .vec_in_$index(vec_in[$high:$low])"
    }
    val concreteOutputs = (0 until witness.depth).map { index =>
      s"    .vec_out_$index(reference_vec_out_$index)"
    }
    val concreteConnections =
      (concreteInputs ++ concreteOutputs ++ Vector(
        "    .index(index)",
        "    .write_data(write_data)",
        "    .first(reference_first)",
        "    .selected(reference_selected)"
      )).mkString(",\n")
    val outputWires = (0 until witness.depth)
      .map(index => s"  wire [${witness.width - 1}:0] reference_vec_out_$index;")
      .mkString("\n")
    val packedReferenceAssignments = (0 until witness.depth)
      .map { index =>
        val low = index * witness.width
        s"  assign reference_vec_out[$low +: ${witness.width}] = reference_vec_out_$index;"
      }
      .mkString("\n")
    val comparedFirst =
      if (mutateFirst) s"(candidate_first ^ ${witness.width}'d1)"
      else "candidate_first"

    s"""module ${miterModule(witness)} (
       |  input wire [${totalWidth - 1}:0] vec_in,
       |  input wire [2:0] index,
       |  input wire [${witness.width - 1}:0] write_data
       |);
       |  wire [${totalWidth - 1}:0] candidate_vec_out;
       |  wire [${witness.width - 1}:0] candidate_first;
       |  wire [${witness.width - 1}:0] candidate_selected;
       |  wire [${totalWidth - 1}:0] reference_vec_out;
       |  wire [${witness.width - 1}:0] reference_first;
       |  wire [${witness.width - 1}:0] reference_selected;
       |$outputWires
       |
       |  ${candidateTop(witness)} candidate (
       |    .vec_in(vec_in),
       |    .index(index),
       |    .write_data(write_data),
       |    .vec_out(candidate_vec_out),
       |    .first(candidate_first),
       |    .selected(candidate_selected)
       |  );
       |
       |  ${referenceTop(witness)} reference (
       |$concreteConnections
       |  );
       |
       |$packedReferenceAssignments
       |
       |  always @* begin
       |    assert(candidate_vec_out == reference_vec_out);
       |    assert($comparedFirst == reference_first);
       |    assert(candidate_selected == reference_selected);
       |  end
       |endmodule
       |""".stripMargin
  }

  private def positiveSby(
      prepared: PreparedDuts,
      miter: Path,
      top: String
  ): String =
    s"""[options]
       |mode prove
       |expect pass
       |multiclock off
       |timeout 300
       |
       |[engines]
       |abc pdr
       |
       |[script]
       |read_rtlil ${prepared.candidate.getFileName}
       |read_rtlil ${prepared.reference.getFileName}
       |read_verilog -formal ${miter.getFileName}
       |hierarchy -check -top $top
       |prep -top $top
       |setundef -undriven -anyseq
       |opt_clean
       |check -assert
       |
       |[files]
       |${prepared.candidate.toAbsolutePath}
       |${prepared.reference.toAbsolutePath}
       |${miter.toAbsolutePath}
       |""".stripMargin

  private def mutationSby(
      prepared: PreparedDuts,
      miter: Path,
      top: String
  ): String =
    s"""[options]
       |mode bmc
       |depth 2
       |expect fail
       |multiclock off
       |timeout 120
       |
       |[engines]
       |smtbmc yices
       |
       |[script]
       |read_rtlil ${prepared.candidate.getFileName}
       |read_rtlil ${prepared.reference.getFileName}
       |read_verilog -formal ${miter.getFileName}
       |hierarchy -check -top $top
       |prep -top $top
       |setundef -undriven -anyseq
       |opt_clean
       |check -assert
       |
       |[files]
       |${prepared.candidate.toAbsolutePath}
       |${prepared.reference.toAbsolutePath}
       |${miter.toAbsolutePath}
       |""".stripMargin

  private def runSby(
      directory: Path,
      config: Path,
      expectedStatus: String,
      requireCounterexample: Boolean
  ): Unit = {
    val (exitCode, output) = run(
      directory,
      Seq("sby", "-f", config.getFileName.toString)
    )
    assert(
      exitCode == 0,
      s"SymbiYosys did not complete with expected status $expectedStatus for ${config.getFileName}:\n$output"
    )

    val workDirectory =
      directory.resolve(config.getFileName.toString.stripSuffix(".sby"))
    val statusFile = workDirectory.resolve("status")
    assert(
      Files.isRegularFile(statusFile),
      s"SymbiYosys published no status for ${config.getFileName}:\n$output"
    )
    val statusLines = read(statusFile)
      .split("\\r?\\n", -1)
      .iterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .toVector
    assert(
      statusLines.size == 1,
      s"ambiguous formal status for ${config.getFileName}: ${statusLines.mkString(" | ")}\n$output"
    )
    val statusTokens = statusLines.head.split("\\s+").toVector
    assert(
      statusTokens.nonEmpty && statusTokens.tail.forall(_.matches("[0-9]+")),
      s"malformed formal status for ${config.getFileName}: ${statusLines.head}\n$output"
    )
    assert(
      statusTokens.head == expectedStatus,
      s"expected $expectedStatus for ${config.getFileName}, received ${statusTokens.head}:\n$output"
    )

    if (requireCounterexample) {
      val files = regularFiles(workDirectory)
      assert(
        files.exists(path => path.getFileName.toString.endsWith(".vcd") && Files.size(path) > 0L),
        s"expected mutation counterexample has no non-empty trace:\n$output"
      )
      val engineLogs = files
        .filter { path =>
          val name = path.getFileName.toString
          name.endsWith(".txt") || name.endsWith(".log")
        }
        .map(read)
        .mkString("\n")
      assert(
        engineLogs.contains("Assert failed in"),
        s"expected FAIL was not an assertion counterexample:\n$output\n$engineLogs"
      )
    }
  }

  private def runYosys(
      directory: Path,
      script: Path,
      expectedOutput: Path
  ): Unit = {
    val (exitCode, output) =
      run(directory, Seq("yosys", "-q", "-s", script.getFileName.toString))
    assert(
      exitCode == 0,
      s"Yosys preprocessing failed for ${script.getFileName}:\n$output"
    )
    assert(
      Files.isRegularFile(expectedOutput) && Files.size(expectedOutput) > 0L,
      s"Yosys produced no RTLIL for ${script.getFileName}:\n$output"
    )
  }

  private def requireFormalTool(
      directory: Path,
      command: Seq[String],
      label: String
  ): Unit = {
    val (exitCode, output) = run(directory, command)
    assert(
      exitCode == 0 && output.trim.nonEmpty,
      s"required formal tool $label is unavailable (${command.mkString(" ")}):\n$output"
    )
  }

  private def generationConfig(directory: Path): SpinalConfig =
    SpinalConfig(targetDirectory = directory.toString)

  private def stem(witness: Witness): String =
    s"w${witness.width}_d${witness.depth}"

  private def candidateTop(witness: Witness): String =
    s"TypedVecCandidateW${witness.width}D${witness.depth}"

  private def referenceTop(witness: Witness): String =
    s"ConcreteVecReferenceW${witness.width}D${witness.depth}"

  private def miterModule(witness: Witness): String =
    s"TypedParameterizedVecMiterW${witness.width}D${witness.depth}"

  private def moduleNames(verilog: String): Vector[String] =
    ModuleDeclaration.findAllMatchIn(verilog).map(_.group(1)).toVector

  private def yosysPath(path: Path): String = {
    val absolute = path.toAbsolutePath.normalize.toString
    require(
      !absolute.exists(character => character.isWhitespace || character == '"'),
      s"formal path is not safely representable in Yosys: $absolute"
    )
    absolute
  }

  private def run(directory: Path, command: Seq[String]): (Int, String) = {
    val output = new StringBuilder
    val exitCode = Process(command, directory.toFile).!(
      ProcessLogger(
        line => output.append(line).append('\n'),
        line => output.append(line).append('\n')
      )
    )
    exitCode -> output.toString
  }

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def write(path: Path, content: String): Unit =
    Files.write(path, content.getBytes(StandardCharsets.UTF_8))

  private def regularFiles(directory: Path): Vector[Path] = {
    val stream = Files.walk(directory)
    try stream.iterator().asScala.filter(Files.isRegularFile(_)).toVector
    finally stream.close()
  }

  private def withTemporaryDirectory(body: Path => Unit): Unit = {
    val directory = Files.createTempDirectory("morphhdl-typed-vec-formal-")
    try body(directory)
    finally deleteRecursively(directory)
  }

  private def withFormalWorkspace(body: Path => Unit): Unit =
    sys.env.get(FormalWorkspaceEnvironment).filter(_.nonEmpty) match {
      case Some(configured) =>
        val directory = Paths.get(configured).toAbsolutePath
        Files.createDirectories(directory)
        body(directory)
      case None => withTemporaryDirectory(body)
    }

  private def deleteRecursively(directory: Path): Unit = {
    val stream = Files.walk(directory)
    try {
      stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach { path =>
        Files.deleteIfExists(path)
      }
    } finally stream.close()
  }
}
