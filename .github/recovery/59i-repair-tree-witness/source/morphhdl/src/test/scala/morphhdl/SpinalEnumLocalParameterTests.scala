package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

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

object Inc53bFormalState extends SpinalEnum(binarySequential) {
  val IDLE, LOAD, RUN, DONE = newElement()
  setGlobal()
}

object Inc53b1AXI4ReadState extends SpinalEnum(binaryOneHot) {
  val waitResp = newElement("waitResp")
  val HTTPDone = newElement("HTTPDone")
  setGlobal()
}

object Inc53b1FooBarState extends SpinalEnum(binarySequential) {
  val IDLE, RUN = newElement()
  setGlobal()
}

object Inc53b1Foo_BarState extends SpinalEnum(binarySequential) {
  val IDLE, RUN = newElement()
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

final class Inc53bFormalEnumTop(width: HdlInt) extends Component {
  setDefinitionName("Inc53bFormalEnumTop")

  val selector = in(morphhdl.frontend.UInt(width bits))
  val enable = in Bool()
  val active = out Bool()
  val encoded = out Bits (2 bits)

  val state = Reg(Inc53bFormalState()) init (Inc53bFormalState.IDLE)
  switch(state) {
    is(Inc53bFormalState.IDLE) {
      when(enable) {
        state := Inc53bFormalState.LOAD
      }
    }
    is(Inc53bFormalState.LOAD) {
      when(!enable) {
        state := Inc53bFormalState.IDLE
      } otherwise {
        when(selector(0)) {
          state := Inc53bFormalState.RUN
        }
      }
    }
    is(Inc53bFormalState.RUN) {
      when(enable) {
        state := Inc53bFormalState.DONE
      }
    }
    is(Inc53bFormalState.DONE) {
      when(!enable) {
        state := Inc53bFormalState.IDLE
      }
    }
  }

  active := state === Inc53bFormalState.DONE
  encoded := state.asBits
}

final class Inc53b1SnakeCaseTop(width: HdlInt) extends Component {
  setDefinitionName("Inc53b1SnakeCaseTop")

  val payload = in(morphhdl.frontend.UInt(width bits))
  val active = out Bool()

  val state = Inc53b1AXI4ReadState()
  state := Inc53b1AXI4ReadState.waitResp
  when(payload(0)) {
    state := Inc53b1AXI4ReadState.HTTPDone
  }
  active := state === Inc53b1AXI4ReadState.HTTPDone
}

final class Inc53b1SnakeCollisionTop(width: HdlInt) extends Component {
  setDefinitionName("Inc53b1SnakeCollisionTop")

  val payload = in(morphhdl.frontend.UInt(width bits))
  val active = out Bool()

  val left = Inc53b1FooBarState()
  val right = Inc53b1Foo_BarState()
  left := Inc53b1FooBarState.IDLE
  right := Inc53b1Foo_BarState.IDLE
  when(payload(0)) {
    left := Inc53b1FooBarState.RUN
    right := Inc53b1Foo_BarState.RUN
  }
  active :=
    (left === Inc53b1FooBarState.RUN) &&
      (right === Inc53b1Foo_BarState.RUN)
}

class SpinalEnumLocalParameterTests extends AnyFunSuite {
  test("MorphVerilog replaces global enum macros with SCREAMING_SNAKE_CASE enum-qualified module-local parameters") {
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
      assert(!verilog.contains("localparam Inc53bGlobal"))
      assert(!verilog.contains("localparam IDLE ="))
      assert(!verilog.contains("localparam RUN ="))

      assert(binary.contains("localparam INC53B_GLOBAL_BINARY_STATE_IDLE = 1'd0;"))
      assert(binary.contains("localparam INC53B_GLOBAL_BINARY_STATE_RUN = 1'd1;"))
      assert(!binary.contains("INC53B_BINARY_ENUM_LEAF_INC53B_GLOBAL_BINARY_STATE"))

      assert(oneHot.contains("localparam INC53B_GLOBAL_ONE_HOT_STATE_IDLE = 2'd1;"))
      assert(oneHot.contains("localparam INC53B_GLOBAL_ONE_HOT_STATE_IDLE_OH_ID = 0;"))
      assert(oneHot.contains("localparam INC53B_GLOBAL_ONE_HOT_STATE_RUN = 2'd2;"))
      assert(oneHot.contains("localparam INC53B_GLOBAL_ONE_HOT_STATE_RUN_OH_ID = 1;"))
      assert(!oneHot.contains("INC53B_ONE_HOT_ENUM_LEAF_INC53B_GLOBAL_ONE_HOT_STATE"))

      assert(
        verilog.split("localparam INC53B_GLOBAL_BINARY_STATE_IDLE =", -1).length - 1 == 1
      )
      assert(
        verilog.split("localparam INC53B_GLOBAL_ONE_HOT_STATE_IDLE =", -1).length - 1 == 1
      )
      lint(first, directory, "Inc53bEnumTop")
    }
  }

  test("MorphVerilog splits camel-case, acronym and digit boundaries in enum names") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "enum_snake_case.v"
      val report = MorphVerilog(config) {
        val width = HdlInt.param("WIDTH", default = 4, min = 1, max = 16)
        new Inc53b1SnakeCaseTop(width)
      }

      assert(report.toplevelName == "Inc53b1SnakeCaseTop")
      val output = directory.resolve("enum_snake_case.v")
      val verilog = read(output)
      assert(verilog.contains("localparam INC53B1_AXI4_READ_STATE_WAIT_RESP = 2'd1;"))
      assert(verilog.contains("localparam INC53B1_AXI4_READ_STATE_WAIT_RESP_OH_ID = 0;"))
      assert(verilog.contains("localparam INC53B1_AXI4_READ_STATE_HTTP_DONE = 2'd2;"))
      assert(verilog.contains("localparam INC53B1_AXI4_READ_STATE_HTTP_DONE_OH_ID = 1;"))
      assert(!verilog.contains("INC53B1AXI4READSTATE"))
      lint(output, directory, "Inc53b1SnakeCaseTop")
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
      assert(!verilog.contains("localparam INC53B_GLOBAL_BINARY_STATE_IDLE"))
    }
  }

  test("enum-qualified uppercase names avoid same-module element collisions") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "enum_collision.v"
      val report = MorphVerilog(config) {
        val width = HdlInt.param("WIDTH", default = 4, min = 1, max = 16)
        new Inc53bEnumCollisionTop(width)
      }

      assert(report.toplevelName == "Inc53bEnumCollisionTop")
      val output = directory.resolve("enum_collision.v")
      val verilog = read(output)
      assert(verilog.contains("localparam INC53B_GLOBAL_BINARY_STATE_IDLE = 1'd0;"))
      assert(verilog.contains("localparam INC53B_GLOBAL_COLLISION_STATE_IDLE = 2'd0;"))
      assert(verilog.contains("localparam INC53B_GLOBAL_COLLISION_STATE_WAIT_1 = 2'd1;"))
      assert(!verilog.contains("localparam IDLE ="))
      lint(output, directory, "Inc53bEnumCollisionTop")
    }
  }

  test("SCREAMING_SNAKE_CASE canonicalization collisions fail closed") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "enum_snake_collision.v"

      MorphVerilog.tryGenerate(config) {
        val width = HdlInt.param("WIDTH", default = 4, min = 1, max = 16)
        new Inc53b1SnakeCollisionTop(width)
      } match {
        case Left(failure) =>
          assert(failure.stage == MorphVerilogStage.SingleSourceGeneration)
          val diagnostic = failure.detail + failure.cause.map(_.toString).getOrElse("")
          assert(
            diagnostic.contains(
              "SPINAL-PARAMETERIZED-VERILOG-ENUM-LOCAL-NAME-COLLISION"
            )
          )
          assert(diagnostic.contains("INC53B1_FOO_BAR_STATE_IDLE"))
          assert(!Files.exists(directory.resolve("enum_snake_collision.v")))
        case Right(_) =>
          fail("distinct enum identifiers that canonicalize identically must fail closed")
      }
    }
  }

  test("Yosys formally proves legacy macro and SCREAMING_SNAKE_CASE localparam enum RTL equivalent") {
    withTemporaryDirectory { directory =>
      assert(commandAvailable("yosys"), "Yosys is required for the enum formal-equivalence contract")

      val legacyDirectory = directory.resolve("legacy")
      val localizedDirectory = directory.resolve("localized")
      Files.createDirectories(legacyDirectory)
      Files.createDirectories(localizedDirectory)

      val legacyConfig = formalConfig(legacyDirectory)
      legacyConfig.netlistFileName = "enum_macro.v"
      SpinalVerilog(legacyConfig) {
        buildFormalEnumComponent()
      }
      val legacyRtl = legacyDirectory.resolve("enum_macro.v")

      val localizedConfig = formalConfig(localizedDirectory)
      localizedConfig.netlistFileName = "enum_localparam.v"
      val report = MorphVerilog(localizedConfig) {
        buildFormalEnumComponent()
      }
      val localizedRtl = localizedDirectory.resolve("enum_localparam.v")

      assert(report.parameters.map(_.name) == Vector("WIDTH"))
      val legacy = read(legacyRtl)
      val localized = read(localizedRtl)
      assert(legacy.contains("`define Inc53bFormalState_IDLE"))
      assert(legacy.contains("`Inc53bFormalState_DONE"))
      assert(localized.contains("localparam INC53B_FORMAL_STATE_IDLE = 2'd0;"))
      assert(localized.contains("localparam INC53B_FORMAL_STATE_DONE = 2'd3;"))
      assert(!localized.contains("`define Inc53bFormalState"))
      assert(!localized.contains("`Inc53bFormalState"))

      val script = directory.resolve("enum_macro_vs_localparam.ys")
      val scriptText =
        s"""read_verilog -formal ${legacyRtl.toAbsolutePath}
           |prep -top Inc53bFormalEnumTop
           |rename Inc53bFormalEnumTop gold
           |design -stash gold
           |
           |read_verilog -formal ${localizedRtl.toAbsolutePath}
           |chparam -set WIDTH 4 Inc53bFormalEnumTop
           |prep -top Inc53bFormalEnumTop
           |rename Inc53bFormalEnumTop gate
           |design -stash gate
           |
           |design -reset
           |design -copy-from gold -as gold gold
           |design -copy-from gate -as gate gate
           |equiv_make gold gate equiv
           |hierarchy -check -top equiv
           |proc
           |opt_clean
           |equiv_simple -seq 8
           |equiv_induct -undef -seq 8
           |equiv_status -assert
           |""".stripMargin
      Files.write(script, scriptText.getBytes(StandardCharsets.UTF_8))

      val proof = run(directory, Seq("yosys", "-s", script.toString))
      assert(
        proof._1 == 0,
        s"Yosys failed macro/localparam formal equivalence:\n${proof._2}"
      )
    }
  }

  private def component(): Inc53bEnumTop = {
    val width = HdlInt.param("WIDTH", default = 4, min = 1, max = 16)
    new Inc53bEnumTop(width)
  }

  private def buildFormalEnumComponent(): Inc53bFormalEnumTop = {
    val width = HdlInt.param("WIDTH", default = 4, min = 1, max = 16)
    new Inc53bFormalEnumTop(width)
  }

  private def formalConfig(directory: Path): SpinalConfig =
    SpinalConfig(
      targetDirectory = directory.toString,
      defaultConfigForClockDomains = ClockDomainConfig(
        resetKind = SYNC,
        resetActiveLevel = HIGH
      )
    )

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

  private def lint(rtl: Path, directory: Path, top: String): Unit = {
    if (commandAvailable("iverilog")) {
      val result = run(
        directory,
        Seq(
          "iverilog",
          "-g2001",
          "-s",
          top,
          "-o",
          directory.resolve(top + ".out").toString,
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
          top,
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
          s"read_verilog ${rtl.toString}; hierarchy -check -top $top; proc; check"
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
