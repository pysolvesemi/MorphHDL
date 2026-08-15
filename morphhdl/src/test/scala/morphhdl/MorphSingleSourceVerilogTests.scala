package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import scala.collection.JavaConverters._

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

import morphhdl.frontend.HdlInt
import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}
import morphhdl.paramrtl.IntegerParameter

class MorphSingleSourceVerilogTests extends AnyFunSuite {
  private val expectedPhaseIds = Vector(
    "PhaseCheckIoBundle",
    "PhaseCheckHierarchy",
    "PhaseInferWidth",
    "PhaseCheck_noLatchNoOverride",
    "PhaseCheck_noRegisterAsLatch",
    "PhaseCheckCombinationalLoops",
    "PhaseCheckCrossClock",
    "PhaseContext.checkGlobalData"
  )

  test("one ordinary component factory emits the parameterized wire contract") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "parameterized_wire.v"

      val report = MorphVerilog(config) {
        ParameterizedWireContractFixture.component(reverseConstructionOrder = false)
      }

      val output = directory.resolve("parameterized_wire.v")
      assert(report.toplevelName == "ParameterizedWire")
      assert(report.generatedSourcesPaths == Vector(output.toString))
      assert(
        report.parameters == Vector(
          IntegerParameter(
            "WIDTH",
            8,
            Vector(MinInclusive(1), MaxInclusive(64))
          )
        )
      )
      assert(report.inheritedValidationPhaseIds == expectedPhaseIds)
      assert(read(output) == expectedParameterizedWire)
      val listing = Files.list(directory)
      try {
        assert(listing.iterator().asScala.map(_.getFileName.toString).toVector == Vector("parameterized_wire.v"))
      } finally listing.close()
    }
  }

  test("single-source emission is independent of component and parameter names") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "renamed_payload_bridge.v"

      val report = MorphVerilog(config) {
        genericDirectWire("RenamedPayloadBridge", "PAYLOAD_BITS", defaultWidth = 13)
      }

      val verilog = read(directory.resolve("renamed_payload_bridge.v"))
      assert(report.toplevelName == "RenamedPayloadBridge")
      assert(verilog.contains("module RenamedPayloadBridge #("))
      assert(verilog.contains("parameter integer PAYLOAD_BITS = 13"))
      assert(verilog.contains("input  wire [PAYLOAD_BITS-1:0] payload"))
      assert(verilog.contains("output wire [PAYLOAD_BITS-1:0] result"))
      assert(verilog.contains("assign result = payload;"))
      assert(!verilog.contains("ParameterizedWire"))
    }
  }

  test("parameterized native emission canonicalizes reversed port construction") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "parameterized_wire.v"

      MorphVerilog(config) {
        ParameterizedWireContractFixture.component(reverseConstructionOrder = true)
      }

      assert(read(directory.resolve("parameterized_wire.v")) == expectedParameterizedWire)
    }
  }

  test("ordinary SpinalVerilog ignores symbolic metadata when parameterized mode is disabled") {
    withTemporaryDirectory { directory =>
      val report = SpinalVerilog(SpinalConfig(targetDirectory = directory.toString)) {
        ParameterizedWireContractFixture.component(reverseConstructionOrder = false)
      }

      val verilog = read(Paths.get(report.generatedSourcesPaths.head))
      assert(verilog.contains("module ParameterizedWire ("))
      assert(verilog.contains("input  wire [7:0]    din"))
      assert(verilog.contains("output wire [7:0]    dout"))
      assert(!verilog.contains("parameter integer WIDTH"))
      assert(!verilog.contains("[WIDTH-1:0]"))
    }
  }

  test("an unsupported symbolic width leaves the previous public output untouched") {
    withTemporaryDirectory { directory =>
      val output = directory.resolve("preserved.v")
      Files.write(output, "previous-good-output".getBytes(StandardCharsets.UTF_8))
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "preserved.v"

      val result = MorphVerilog.tryGenerate(config) {
        val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 64)
        val derivedWidth = width + HdlInt.literal(1)
        new Component {
          setDefinitionName("UnsupportedDerivedWidth")
          val payload = in UInt(derivedWidth bits)
          val result = out UInt(derivedWidth bits)
          result := payload
        }
      }

      result match {
        case Left(failure) =>
          assert(failure.stage == MorphVerilogStage.SingleSourceGeneration)
          assert(failure.detail.contains("MORPH-FRONTEND-SPINAL-WIDTH-NOT-DIRECT-PARAMETER"))
        case Right(report) => fail(s"Expected unsupported symbolic-width failure, received $report")
      }
      assert(read(output) == "previous-good-output")
    }
  }

  test("a native bridge failure preserves its stable code and publishes nothing") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "untagged.v"

      val result = MorphVerilog.tryGenerate(config) {
        new Component {
          setDefinitionName("UntaggedDirectWire")
          val payload = in UInt(8 bits)
          val result = out UInt(8 bits)
          result := payload
        }
      }

      result match {
        case Left(failure) =>
          assert(failure.stage == MorphVerilogStage.SingleSourceGeneration)
          assert(failure.detail.contains("SPINAL-PARAMETERIZED-VERILOG-UNTAGGED-PORT"))
        case Right(report) => fail(s"Expected untagged-port failure, received $report")
      }
      assert(!Files.exists(directory.resolve("untagged.v")))
    }
  }

  test("native bridge diagnostics retain the symbolic width source location") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "colliding_parameter.v"

      val result = MorphVerilog.tryGenerate(config) {
        val width = HdlInt.param("payload", default = 8, min = 1, max = 64)
        new Component {
          setDefinitionName("CollidingParameterWire")
          val payload = in UInt(width bits)
          val result = out UInt(width bits)
          result := payload
        }
      }

      result match {
        case Left(failure) =>
          assert(failure.stage == MorphVerilogStage.SingleSourceGeneration)
          assert(
            failure.detail.contains(
              "SPINAL-PARAMETERIZED-VERILOG-PARAMETER-PORT-NAME-COLLISION"
            )
          )
          assert(failure.detail.contains("MorphSingleSourceVerilogTests.scala:"))
        case Right(report) => fail(s"Expected parameter/port collision, received $report")
      }
      assert(!Files.exists(directory.resolve("colliding_parameter.v")))
    }
  }

  test("configuration failure occurs before the single component factory runs") {
    withTemporaryDirectory { directory =>
      var factoryRuns = 0
      val result = MorphVerilog.tryGenerate(
        SpinalConfig(mode = SystemVerilog, targetDirectory = directory.toString)
      ) {
        factoryRuns += 1
        genericDirectWire("NeverElaborated", "WIDTH", defaultWidth = 8)
      }

      result match {
        case Left(failure) => assert(failure.stage == MorphVerilogStage.Configuration)
        case Right(report) => fail(s"Expected configuration failure, received $report")
      }
      assert(factoryRuns == 0)
      val listing = Files.list(directory)
      try assert(listing.iterator().asScala.isEmpty)
      finally listing.close()
    }
  }

  private def genericDirectWire(
      componentName: String,
      parameterName: String,
      defaultWidth: Int
  ): Component = {
    val width = HdlInt.param(parameterName, default = defaultWidth, min = 1, max = 64)
    new Component {
      setDefinitionName(componentName)
      val payload = in UInt(width bits)
      val result = out UInt(width bits)
      result := payload
    }
  }

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def withTemporaryDirectory(body: Path => Unit): Unit = {
    val directory = Files.createTempDirectory("morphhdl-single-source-test-")
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

  private val expectedParameterizedWire =
    """module ParameterizedWire #(
      |  parameter integer WIDTH = 8
      |) (
      |  input  wire [WIDTH-1:0] din,
      |  output wire [WIDTH-1:0] dout
      |);
      |
      |  assign dout = din;
      |
      |endmodule
      |""".stripMargin
}
