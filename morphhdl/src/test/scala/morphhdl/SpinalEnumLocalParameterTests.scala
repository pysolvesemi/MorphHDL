package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import scala.collection.JavaConverters._
import scala.sys.process.{Process, ProcessLogger}

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

import morphhdl.frontend.HdlInt

object Inc53bGlobalBinaryState extends SpinalEnum(binarySequential) {
  val IDLE, RUN = newElement()
  setGlobal()
}

object Inc53bGlobalOneHotState extends SpinalEnum(binaryOneHot) {
  val IDLE, RUN = newElement()
  setGlobal()
}

object Inc53bGlobalCollisionState extends SpinalEnum(binarySequential) {
  val IDLE, WAIT, RUN = newElement()
  setGlobal()
}

final class Inc53bBinaryEnumLeaf extends Component {
  setDefinitionName("Inc53bBinaryEnumLeaf")

  val select = in Bool()
  val active = out Bool()

  val state = Inc53bGlobalBinaryState()
  state := Inc53bGlobalBinaryState.IDLE
  when(select) {
    state := Inc53bGlobalBinaryState.RUN
  }
  active := state === Inc53bGlobalBinaryState.RUN
}

final class Inc53bOneHotEnumLeaf extends Component {
  setDefinitionName("Inc53bOneHotEnumLeaf")

  val select = in Bool()
  val active = out Bool()

  val state = Inc53bGlobalOneHotState()
  state := Inc53bGlobalOneHotState.IDLE
  when(select) {
    state := Inc53bGlobalOneHotState.RUN
  }
  active := state === Inc53bGlobalOneHotState.RUN
}

final class Inc53bEnumTop(width: HdlInt) extends Component {
  setDefinitionName("Inc53bEnumTop")

  val payload = in(morphhdl.frontend.UInt(width bits))
  val binaryActive = out Bool()
  val oneHotActive = out Bool()

  val binary = new Inc53bBinaryEnumLeaf
  val oneHot = new Inc53bOneHotEnumLeaf
  binary.select := payload(0)
  oneHot.select := payload(0)
  binaryActive := binary.active
  oneHotActive := oneHot.active
}

final class Inc53bEnumCollisionTop(width: HdlInt) extends Component {
  setDefinitionName("Inc53bEnumCollisionTop")

  val payload = in(morphhdl.frontend.UInt(width bits))
  val active = out Bool()

  val left = Inc53bGlobalBinaryState()
  val right = Inc53bGlobalCollisionState()
  left := Inc53bGlobalBinaryState.IDLE
  right := Inc53bGlobalCollisionState.IDLE
  when(payload(0)) {
    left := Inc53bGlobalBinaryState.RUN
    right := Inc53bGlobalCollisionState.RUN
  }
  active :=
    (left === Inc53bGlobalBinaryState.RUN) ^
      (right === Inc53bGlobalCollisionState.RUN)
}

class SpinalEnumLocalParameterTests extends AnyFunSuite {
  test("MorphVerilog replaces global enum macros with short module-local parameters") {
    withTemporaryDirectory { directory =>
      val firstDirectory = directory.resolve("first")
      val replayDirectory = directory.resolve("replay")
      Files.createDirectories(firstDirectory)
      Files.createDirectories(replayDirectory)

      val first = generate(firstDirectory)
      val replay = generate(replayDirectory)
      assert(
        java.util.Arrays.equals(
          Files.readAllBytes(first),
          Files.readAllBytes(replay)
        ),
        "module-local enum publication was not byte deterministic"
      )

      val verilog = read(first)
      val binary = module(verilog, "Inc53bBinaryEnumLeaf")
      val oneHot = module(verilog, "Inc53bOneHotEnumLeaf")

      assert(!verilog.contains("`define"))
      assert(!verilog.contains("`Inc53bGlobal"))
      assert(!verilog.contains("Inc53bGlobalBinaryState_"))
      assert(!verilog.contains("Inc53bGlobalOneHotState_"))

      assert(binary.contains("localparam IDLE = 1'd0;"))
      assert(binary.contains("localparam RUN = 1'd1;"))
      assert(!binary.contains("localparam Inc53b"))

      assert(oneHot.contains("localparam IDLE = 2'd1;"))
      assert(oneHot.contains("localparam IDLE_OH_ID = 0;"))
      assert(oneHot.contains("localparam RUN = 2'd2;"))
      assert(oneHot.contains("localparam RUN_OH_ID = 1;"))
      assert(!oneHot.contains("localparam Inc53b"))

      assert(verilog.split("localparam IDLE =", -1).length - 1 == 2)
      assert(verilog.split("localparam RUN =", -1).length - 1 == 2)
      lint(first, directory)
    }
  }

  test("ordinary SpinalVerilog keeps native global enum macro behavior") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "native_enum.v"
      SpinalVerilog(config) {
        component()
      }

      val verilog = read(directory.resolve("native_enum.v"))
      assert(verilog.contains("`define Inc53bGlobalBinaryState_IDLE"))
      assert(verilog.contains("`define Inc53bGlobalOneHotState_IDLE"))
      assert(verilog.contains("`Inc53bGlobalBinaryState_RUN"))
      assert(verilog.contains("`Inc53bGlobalOneHotState_RUN"))
    }
  }

  test("same-module short enum-name conflicts fail closed without prefixes") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "enum_collision.v"

      MorphVerilog.tryGenerate(config) {
        val width = HdlInt.param("WIDTH", default = 4, min = 1, max = 16)
        new Inc53bEnumCollisionTop(width)
      } match {
        case Left(failure) =>
          assert(failure.stage == MorphVerilogStage.SingleSourceGeneration)
          assert(
            failure.detail.contains(
              "SPINAL-PARAMETERIZED-VERILOG-ENUM-LOCAL-NAME-COLLISION"
            )
          )
          assert(failure.detail.contains("IDLE"))
        case Right(report) =>
          fail(s"Expected prefix-free enum-name collision failure, received $report")
      }
      assert(!Files.exists(directory.resolve("enum_collision.v")))
    }
  }

  private def component(): Inc53bEnumTop = {
    val width = HdlInt.param("WIDTH", default = 4, min = 1, max = 16)
    new Inc53bEnumTop(width)
  }

  private def generate(directory: Path): Path = {
    val config = SpinalConfig(targetDirectory = directory.toString)
    config.netlistFileName = "enum_localparams.v"
    val report = MorphVerilog(config) {
      component()
    }
    assert(report.toplevelName == "Inc53bEnumTop")
    assert(report.parameters.map(_.name) == Vector("WIDTH"))
    val output = directory.resolve("enum_localparams.v")
    assert(report.generatedSourcesPaths == Vector(output.toString))
    output
  }

  private def module(verilog: String, name: String): String =
    ("(?ms)^\\s*module\\s+" + java.util.regex.Pattern.quote(name) +
      "\\b.*?^\\s*endmodule\\b").r
      .findFirstIn(verilog)
      .getOrElse(fail(s"Module '$name' is missing"))

  private def lint(rtl: Path, directory: Path): Unit = {
    if (commandAvailable("iverilog")) {
      val result = run(
        directory,
        Seq(
          "iverilog",
          "-g2001",
          "-s",
          "Inc53bEnumTop",
          "-o",
          directory.resolve("enum_localparams.out").toString,
          rtl.toString
        )
      )
      assert(result._1 == 0, s"iverilog rejected localized enum RTL:\n${result._2}")
    }

    if (commandAvailable("verilator")) {
      val result = run(
        directory,
        Seq(
          "verilator",
          "--lint-only",
          "--language",
          "1364-2001",
          "-Wno-fatal",
          "-Wno-DECLFILENAME",
          "--top-module",
          "Inc53bEnumTop",
          rtl.toString
        )
      )
      assert(result._1 == 0, s"Verilator rejected localized enum RTL:\n${result._2}")
    }

    if (commandAvailable("yosys")) {
      val result = run(
        directory,
        Seq(
          "yosys",
          "-q",
          "-p",
          s"read_verilog ${rtl.toString}; hierarchy -check -top Inc53bEnumTop; proc; check"
        )
      )
      assert(result._1 == 0, s"Yosys rejected localized enum RTL:\n${result._2}")
    }
  }

  private def commandAvailable(command: String): Boolean =
    Process(Seq("sh", "-c", s"command -v $command >/dev/null 2>&1")).! == 0

  private def run(directory: Path, command: Seq[String]): (Int, String) = {
    val output = new StringBuilder
    val logger = ProcessLogger(
      line => output.append(line).append('\n'),
      line => output.append(line).append('\n')
    )
    Process(command, directory.toFile).!(logger) -> output.result()
  }

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def withTemporaryDirectory(body: Path => Unit): Unit = {
    val directory = Files.createTempDirectory("morphhdl-enum-localparam-test-")
    try body(directory)
    finally deleteTree(directory)
  }

  private def deleteTree(root: Path): Unit =
    if (Files.exists(root)) {
      val stream = Files.walk(root)
      try {
        stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach { path =>
          Files.deleteIfExists(path)
        }
      } finally stream.close()
    }
}
