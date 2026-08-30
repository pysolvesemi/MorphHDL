package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._
import scala.sys.process.{Process, ProcessLogger}

import org.scalatest.funsuite.AnyFunSuite

import spinal.core._
import spinal.lib._

import morphhdl.frontend.HdlInt

/**
  * Independent sequential-equivalence proof for the direct typed-elaboration
  * path through the authoritative native StreamWidthAdapter algorithm.
  */
object TypedStreamWidthAdapterFormalEquivalenceSmoke {
  final class TypedTop(
      equalWidth: ElabInt,
      downWidth: ElabInt,
      upWidth: ElabInt
  ) extends Component {
    setDefinitionName("TypedStreamWidthAdapterFormalTop")

    val io = new Bundle {
      val equalInput = slave(Stream(Bits(equalWidth bits)))
      val equalOutput = master(Stream(Bits(equalWidth bits)))
      val downInput = slave(Stream(Bits(downWidth bits)))
      val downOutput = master(Stream(Bits(8 bits)))
      val upInput = slave(Stream(Bits(8 bits)))
      val upOutput = master(Stream(Bits(upWidth bits)))
      val mutationProbe = out Bool()
    }

    StreamWidthAdapter(io.equalInput, io.equalOutput, LITTLE, padding = true)
    StreamWidthAdapter(io.downInput, io.downOutput, LITTLE, padding = true)
    StreamWidthAdapter(io.upInput, io.upOutput, LITTLE, padding = true)

    io.mutationProbe :=
      io.equalOutput.valid ^ io.downOutput.valid ^ io.upOutput.valid
    io.mutationProbe.setName("mutation_probe")
  }

  final class ConcreteTop(
      equalWidth: Int,
      downWidth: Int,
      upWidth: Int
  ) extends Component {
    setDefinitionName("ConcreteStreamWidthAdapterFormalTop")

    val io = new Bundle {
      val equalInput = slave(Stream(Bits(equalWidth bits)))
      val equalOutput = master(Stream(Bits(equalWidth bits)))
      val downInput = slave(Stream(Bits(downWidth bits)))
      val downOutput = master(Stream(Bits(8 bits)))
      val upInput = slave(Stream(Bits(8 bits)))
      val upOutput = master(Stream(Bits(upWidth bits)))
      val mutationProbe = out Bool()
    }

    StreamWidthAdapter(io.equalInput, io.equalOutput, LITTLE, padding = true)
    StreamWidthAdapter(io.downInput, io.downOutput, LITTLE, padding = true)
    StreamWidthAdapter(io.upInput, io.upOutput, LITTLE, padding = true)

    io.mutationProbe :=
      io.equalOutput.valid ^ io.downOutput.valid ^ io.upOutput.valid
    io.mutationProbe.setName("mutation_probe")
  }

  def typed(): TypedTop =
    new TypedTop(
      HdlInt.param("EQ_WIDTH", default = 8, min = 1, max = 16).asElabInt,
      HdlInt.param("DOWN_WIDTH", default = 12, min = 9, max = 16).asElabInt,
      HdlInt.param("UP_WIDTH", default = 12, min = 9, max = 16).asElabInt
    )
}

class TypedStreamWidthAdapterFormalEquivalenceTests extends AnyFunSuite {
  import TypedStreamWidthAdapterFormalEquivalenceSmoke._

  private final case class Witness(equal: Int, down: Int, up: Int)

  private val witnesses = Vector(
    Witness(5, 9, 9),
    Witness(8, 12, 12),
    Witness(16, 16, 16)
  )

  test("typed native StreamWidthAdapter specializations are sequentially equivalent") {
    requireTool("yosys")
    val directory = Files.createTempDirectory("morphhdl-typed-adapter-formal-")
    val parameterized = directory.resolve("parameterized.v")
    emitParameterized(directory, parameterized.getFileName.toString)

    val parameterizedText = read(parameterized)
    assert(parameterizedText.contains("parameter integer EQ_WIDTH"))
    assert(parameterizedText.contains("parameter integer DOWN_WIDTH"))
    assert(parameterizedText.contains("parameter integer UP_WIDTH"))
    assert(!parameterizedText.contains("NativeIntShadow"))

    witnesses.foreach { witness =>
      val concrete = directory.resolve(
        s"concrete_${witness.equal}_${witness.down}_${witness.up}.v"
      )
      emitConcrete(directory, concrete.getFileName.toString, witness)
      val concreteText = read(concrete)
      assert(!concreteText.contains("parameter integer EQ_WIDTH"))
      assert(!concreteText.contains("parameter integer DOWN_WIDTH"))
      assert(!concreteText.contains("parameter integer UP_WIDTH"))
      proveEquivalent(directory, concrete, parameterized, witness)
    }
  }

  test("typed StreamWidthAdapter mutation produces a genuine counterexample") {
    requireTool("yosys")
    requireTool("sby")
    requireTool("yices-smt2")
    val directory = Files.createTempDirectory("morphhdl-typed-adapter-mutation-")
    val parameterized = directory.resolve("parameterized.v")
    val concrete = directory.resolve("concrete.v")
    val mutated = directory.resolve("mutated.v")
    val witness = Witness(8, 12, 12)

    emitParameterized(directory, parameterized.getFileName.toString)
    emitConcrete(directory, concrete.getFileName.toString, witness)

    val original = read(parameterized)
    val assignment =
      "(?mi)^(\\s*assign\\s+[A-Za-z_][A-Za-z0-9_$]*mutation[A-Za-z0-9_$]*\\s*=\\s*)([^;]+);".r
    val matched = assignment
      .findFirstMatchIn(original)
      .getOrElse(fail("mutation probe assignment was not found"))
    val replacement = matched.group(1) + "!(" + matched.group(2) + ");"
    val changed =
      original.substring(0, matched.start) + replacement + original.substring(matched.end)
    Files.write(mutated, changed.getBytes(StandardCharsets.UTF_8))

    proveMutationCounterexample(directory, concrete, mutated, witness)
  }

  private def emitParameterized(directory: Path, fileName: String): Unit = {
    val config = generationConfig(directory)
    config.netlistFileName = fileName
    MorphVerilog(config)(typed())
  }

  private def emitConcrete(
      directory: Path,
      fileName: String,
      witness: Witness
  ): Unit = {
    val config = generationConfig(directory)
    config.netlistFileName = fileName
    config.generateVerilog(
      new ConcreteTop(witness.equal, witness.down, witness.up)
    )
  }

  private def proveEquivalent(
      directory: Path,
      concrete: Path,
      parameterized: Path,
      witness: Witness
  ): Unit = {
    val setup =
      s"""read_verilog -formal ${quote(concrete)}
         |read_verilog -formal ${quote(parameterized)}
         |chparam -set EQ_WIDTH ${witness.equal} -set DOWN_WIDTH ${witness.down} -set UP_WIDTH ${witness.up} TypedStreamWidthAdapterFormalTop
         |equiv_make ConcreteStreamWidthAdapterFormalTop TypedStreamWidthAdapterFormalTop equiv
         |hierarchy -check -top equiv
         |prep -top equiv
         |""".stripMargin
    val script = directory.resolve(
      s"prove_${witness.equal}_${witness.down}_${witness.up}_equivalent.ys"
    )
    val log = directory.resolve(
      s"prove_${witness.equal}_${witness.down}_${witness.up}_equivalent.log"
    )
    val body =
      s"""$setup
         |equiv_simple -undef
         |equiv_induct -undef -seq 12
         |equiv_status -assert
         |""".stripMargin
    Files.write(script, body.getBytes(StandardCharsets.UTF_8))

    val (status, output) = runYosys(directory, script, log)
    val diagnostic = output + "\n" + readIfPresent(log)
    assert(
      status == 0,
      s"equivalence failed for $witness\n$diagnostic"
    )
  }

  private def proveMutationCounterexample(
      directory: Path,
      concrete: Path,
      mutated: Path,
      witness: Witness
  ): Unit = {
    val config = directory.resolve(
      s"prove_${witness.equal}_${witness.down}_${witness.up}_mutation.sby"
    )
    val miter = "TypedStreamWidthAdapterMutationMiter"
    val concreteFile = safeFormalFile(concrete)
    val mutatedFile = safeFormalFile(mutated)
    val content =
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
         |read_verilog -formal ${concrete.getFileName}
         |read_verilog -formal ${mutated.getFileName}
         |chparam -set EQ_WIDTH ${witness.equal} -set DOWN_WIDTH ${witness.down} -set UP_WIDTH ${witness.up} TypedStreamWidthAdapterFormalTop
         |miter -equiv -make_assert ConcreteStreamWidthAdapterFormalTop TypedStreamWidthAdapterFormalTop $miter
         |flatten $miter
         |hierarchy -check -top $miter
         |prep -top $miter
         |setundef -undriven -anyseq
         |opt_clean
         |check -assert
         |
         |[files]
         |$concreteFile
         |$mutatedFile
         |""".stripMargin
    Files.write(config, content.getBytes(StandardCharsets.UTF_8))
    runExpectedCounterexample(directory, config)
  }

  private def runExpectedCounterexample(directory: Path, config: Path): Unit = {
    val output = new StringBuilder
    val exitCode = Process(
      Seq("sby", "-f", config.getFileName.toString),
      directory.toFile
    ).!(
      ProcessLogger(
        line => output.append(line).append('\n'),
        line => output.append(line).append('\n')
      )
    )
    val diagnostic = output.toString
    assert(
      exitCode == 0,
      s"SymbiYosys did not complete with expected FAIL for ${config.getFileName}:\n$diagnostic"
    )

    val stem = config.getFileName.toString.stripSuffix(".sby")
    val workDirectory = directory.resolve(stem)
    val statusFile = workDirectory.resolve("status")
    assert(
      Files.isRegularFile(statusFile),
      s"SymbiYosys published no status for ${config.getFileName}:\n$diagnostic"
    )
    val statusLines = read(statusFile).split("\\r?\\n", -1).iterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .toVector
    assert(
      statusLines.size == 1,
      s"SymbiYosys published an ambiguous status for ${config.getFileName}: ${statusLines.mkString(" | ")}\n$diagnostic"
    )
    val statusTokens = statusLines.head.split("\\s+").toVector
    assert(
      statusTokens.nonEmpty &&
        statusTokens.tail.forall(_.matches("[0-9]+")),
      s"SymbiYosys published a malformed status for ${config.getFileName}: ${statusLines.head}\n$diagnostic"
    )
    assert(
      statusTokens.head == "FAIL",
      s"Expected formal FAIL for ${config.getFileName}, received ${statusTokens.head}:\n$diagnostic"
    )

    val files = regularFiles(workDirectory)
    val traces = files.filter(path => path.getFileName.toString.endsWith(".vcd"))
    assert(
      traces.exists(path => Files.size(path) > 0L),
      s"Expected formal FAIL had no non-empty counterexample trace:\n$diagnostic"
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
      s"Expected formal FAIL was not caused by an assertion counterexample:\n$diagnostic\n$engineLogs"
    )
  }

  private def runYosys(
      directory: Path,
      script: Path,
      log: Path
  ): (Int, String) = {
    val output = new StringBuilder
    val status = Process(
      Seq("yosys", "-ql", log.toString, "-s", script.toString),
      directory.toFile
    ).!(
      ProcessLogger(
        line => output.append(line).append('\n'),
        line => output.append(line).append('\n')
      )
    )
    (status, output.toString)
  }

  private def generationConfig(directory: Path): SpinalConfig =
    SpinalConfig(
      targetDirectory = directory.toString,
      defaultConfigForClockDomains = ClockDomainConfig(
        clockEdge = RISING,
        resetKind = SYNC,
        resetActiveLevel = HIGH
      )
    )

  private def quote(path: Path): String =
    "\"" + path.toAbsolutePath.normalize.toString
      .replace("\\", "\\\\")
      .replace("\"", "\\\"") + "\""

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def readIfPresent(path: Path): String =
    if (Files.isRegularFile(path)) read(path) else ""

  private def safeFormalFile(path: Path): String = {
    val absolute = path.toAbsolutePath.normalize.toString
    require(
      !absolute.exists(character => character.isWhitespace || character == '"'),
      s"Formal workspace path is not safely representable in an SBY file: $absolute"
    )
    absolute
  }

  private def regularFiles(directory: Path): Vector[Path] = {
    val stream = Files.walk(directory)
    try stream.iterator().asScala.filter(Files.isRegularFile(_)).toVector
    finally stream.close()
  }

  private def requireTool(name: String): Unit = {
    val status = Process(
      Seq("sh", "-c", s"command -v $name >/dev/null 2>&1")
    ).!
    assert(status == 0, s"required formal tool '$name' is unavailable")
  }
}
