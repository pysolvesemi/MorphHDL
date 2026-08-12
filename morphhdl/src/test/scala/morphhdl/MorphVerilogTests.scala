package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

import morphhdl.frontend._
import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}
import morphhdl.paramrtl.IntExpr.ParameterRef
import morphhdl.paramrtl.ModuleItem.ContinuousAssign
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.RtlExpr.Ref
import morphhdl.paramrtl.Signedness.Unsigned
import morphhdl.paramrtl._

class MorphVerilogTests extends AnyFunSuite {
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

  test("runs both validation legs and publishes only parameterized Verilog") {
    withTemporaryDirectory { directory =>
      var concreteLoops = 0
      var symbolicRuns = 0
      val config = SpinalConfig(targetDirectory = directory.toString)

      val report = MorphVerilog(config) {
        MorphProgram(
          concreteWitness = witness("ParameterizedWire", () => concreteLoops += 1),
          parameterizedDesign = {
            symbolicRuns += 1
            validDesign("ParameterizedWire")
          }
        )
      }

      val output = directory.resolve("ParameterizedWire.v")
      assert(concreteLoops == 8)
      assert(symbolicRuns == 1)
      assert(report.toplevelName == "ParameterizedWire")
      assert(report.inheritedValidationPhaseIds == expectedPhaseIds)
      assert(report.generatedSourcesPaths == Vector(output.toString))
      assert(new String(Files.readAllBytes(output), StandardCharsets.UTF_8) == expectedVerilog("ParameterizedWire"))
      val listing = Files.list(directory)
      try {
        assert(listing.iterator().asScala.map(_.getFileName.toString).toVector == Vector("ParameterizedWire.v"))
      } finally listing.close()
    }
  }

  test("a concrete failure prevents symbolic capture and public output") {
    withTemporaryDirectory { directory =>
      var symbolicRuns = 0
      val result = MorphVerilog.tryGenerate(
        SpinalConfig(targetDirectory = directory.toString)
      ) {
        MorphProgram[Component](
          concreteWitness = throw new IllegalStateException("concrete boom"),
          parameterizedDesign = {
            symbolicRuns += 1
            validDesign("ParameterizedWire")
          }
        )
      }

      assertStage(result, MorphVerilogStage.ConcreteWitness)
      assert(symbolicRuns == 0)
      assert(!Files.exists(directory.resolve("ParameterizedWire.v")))
    }
  }

  test("ParamRTL validation failure leaves no partial public file") {
    withTemporaryDirectory { directory =>
      val output = directory.resolve("ParameterizedWire.v")
      Files.write(output, "previous-good-output".getBytes(StandardCharsets.UTF_8))

      val result = MorphVerilog.tryGenerate(
        SpinalConfig(targetDirectory = directory.toString)
      ) {
        MorphProgram(
          concreteWitness = witness("ParameterizedWire"),
          parameterizedDesign = Design(top = "ParameterizedWire", modules = Vector.empty)
        )
      }

      assertStage(result, MorphVerilogStage.ParamRtlValidation)
      assert(new String(Files.readAllBytes(output), StandardCharsets.UTF_8) == "previous-good-output")
    }
  }

  test("Verilog-2001 capability failure prevents emission") {
    withTemporaryDirectory { directory =>
      val result = MorphVerilog.tryGenerate(
        SpinalConfig(targetDirectory = directory.toString)
      ) {
        MorphProgram(
          concreteWitness = witness("module"),
          parameterizedDesign = validDesign("module")
        )
      }

      assertStage(result, MorphVerilogStage.Verilog2001Capability)
      assert(!Files.exists(directory.resolve("module.v")))
    }
  }

  test("concrete and symbolic top names must agree") {
    withTemporaryDirectory { directory =>
      val result = MorphVerilog.tryGenerate(
        SpinalConfig(targetDirectory = directory.toString)
      ) {
        MorphProgram(
          concreteWitness = witness("ConcreteTop"),
          parameterizedDesign = validDesign("SymbolicTop")
        )
      }

      assertStage(result, MorphVerilogStage.DefaultShapeAgreement)
      assert(!Files.exists(directory.resolve("SymbolicTop.v")))
    }
  }

  test("same-name designs with different default port schemas fail closed") {
    withTemporaryDirectory { directory =>
      val result = MorphVerilog.tryGenerate(
        SpinalConfig(targetDirectory = directory.toString)
      ) {
        MorphProgram(
          concreteWitness = witness("ParameterizedWire", width = 16),
          parameterizedDesign = validDesign("ParameterizedWire")
        )
      }

      assertStage(result, MorphVerilogStage.DefaultShapeAgreement)
      assert(!Files.exists(directory.resolve("ParameterizedWire.v")))
    }
  }

  test("same-name designs with different default hierarchy fail closed") {
    withTemporaryDirectory { directory =>
      val result = MorphVerilog.tryGenerate(
        SpinalConfig(targetDirectory = directory.toString)
      ) {
        MorphProgram(
          concreteWitness = witnessWithChild("ParameterizedWire"),
          parameterizedDesign = validDesign("ParameterizedWire")
        )
      }

      assertStage(result, MorphVerilogStage.DefaultShapeAgreement)
      assert(!Files.exists(directory.resolve("ParameterizedWire.v")))
    }
  }

  test("same immediate hierarchy with a different child schema fails closed") {
    withTemporaryDirectory { directory =>
      val result = MorphVerilog.tryGenerate(
        SpinalConfig(targetDirectory = directory.toString)
      ) {
        MorphProgram(
          concreteWitness = hierarchicalWitness("HierarchyTop", childWidth = 4),
          parameterizedDesign = hierarchicalDesign("HierarchyTop", childWidth = 8)
        )
      }

      assertStage(result, MorphVerilogStage.DefaultShapeAgreement)
      assert(!Files.exists(directory.resolve("HierarchyTop.v")))
    }
  }

  test("removing an inherited phase fails closed before symbolic capture") {
    withTemporaryDirectory { directory =>
      var symbolicRuns = 0
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.phasesInserters += { phases =>
        val index = phases.indexWhere(_.getClass.getSimpleName == "PhaseCheckHierarchy")
        assert(index >= 0)
        phases.remove(index)
      }

      val result = MorphVerilog.tryGenerate(config) {
        MorphProgram(
          concreteWitness = witness("ParameterizedWire"),
          parameterizedDesign = {
            symbolicRuns += 1
            validDesign("ParameterizedWire")
          }
        )
      }

      assertStage(result, MorphVerilogStage.PhasePlanParity)
      assert(symbolicRuns == 0)
      assert(!Files.exists(directory.resolve("ParameterizedWire.v")))
    }
  }

  test("duplicating an inherited phase fails closed before symbolic capture") {
    withTemporaryDirectory { directory =>
      var symbolicRuns = 0
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.phasesInserters += { phases =>
        phases += new spinal.core.internals.PhaseCheckHierarchy()
      }

      val result = MorphVerilog.tryGenerate(config) {
        MorphProgram(
          concreteWitness = witness("ParameterizedWire"),
          parameterizedDesign = {
            symbolicRuns += 1
            validDesign("ParameterizedWire")
          }
        )
      }

      assertStage(result, MorphVerilogStage.PhasePlanParity)
      assert(symbolicRuns == 0)
      assert(!Files.exists(directory.resolve("ParameterizedWire.v")))
    }
  }

  test("custom transformation phases and inserters execute in the witness leg") {
    withTemporaryDirectory { directory =>
      var transformationRuns = 0
      var insertedRuns = 0
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.transformationPhases += new spinal.core.internals.PhaseMisc {
        override def impl(pc: spinal.core.internals.PhaseContext): Unit = transformationRuns += 1
      }
      config.phasesInserters += { phases =>
        phases += new spinal.core.internals.PhaseMisc {
          override def impl(pc: spinal.core.internals.PhaseContext): Unit = insertedRuns += 1
        }
      }

      MorphVerilog(config) {
        MorphProgram(
          concreteWitness = witness("ParameterizedWire"),
          parameterizedDesign = validDesign("ParameterizedWire")
        )
      }

      assert(transformationRuns == 1)
      assert(insertedRuns == 1)
    }
  }

  test("unsupported output modes fail before either factory runs") {
    withTemporaryDirectory { directory =>
      var concreteRuns = 0
      var symbolicRuns = 0
      val config = SpinalConfig(
        mode = VHDL,
        targetDirectory = directory.toString,
        oneFilePerComponent = true
      )

      val result = MorphVerilog.tryGenerate(config) {
        MorphProgram(
          concreteWitness = {
            concreteRuns += 1
            witness("ParameterizedWire")
          },
          parameterizedDesign = {
            symbolicRuns += 1
            validDesign("ParameterizedWire")
          }
        )
      }

      assertStage(result, MorphVerilogStage.Configuration)
      assert(concreteRuns == 0)
      assert(symbolicRuns == 0)
    }
  }

  test("invalid public filenames fail before factories and cannot escape the target directory") {
    withTemporaryDirectory { directory =>
      var concreteRuns = 0
      var symbolicRuns = 0
      val escaped = directory.getParent.resolve("escaped-witness.v")
      Files.deleteIfExists(escaped)
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "../escaped-witness.v"

      val result = MorphVerilog.tryGenerate(config) {
        MorphProgram(
          concreteWitness = {
            concreteRuns += 1
            witness("ParameterizedWire")
          },
          parameterizedDesign = {
            symbolicRuns += 1
            validDesign("ParameterizedWire")
          }
        )
      }

      assertStage(result, MorphVerilogStage.Configuration)
      assert(concreteRuns == 0)
      assert(symbolicRuns == 0)
      assert(!Files.exists(escaped))
    }
  }

  test("unsupported output-affecting config fails before factories") {
    withTemporaryDirectory { directory =>
      var concreteRuns = 0
      var symbolicRuns = 0
      val config = SpinalConfig(targetDirectory = directory.toString, rtlHeader = "ignored")

      val result = MorphVerilog.tryGenerate(config) {
        MorphProgram(
          concreteWitness = {
            concreteRuns += 1
            witness("ParameterizedWire")
          },
          parameterizedDesign = {
            symbolicRuns += 1
            validDesign("ParameterizedWire")
          }
        )
      }

      assertStage(result, MorphVerilogStage.Configuration)
      assert(concreteRuns == 0)
      assert(symbolicRuns == 0)
    }
  }

  test("witness generation does not mutate the caller's mutable configuration") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      assert(config.memBlackBoxers.isEmpty)

      MorphVerilog(config) {
        MorphProgram(
          concreteWitness = witness("ParameterizedWire"),
          parameterizedDesign = validDesign("ParameterizedWire")
        )
      }

      assert(config.memBlackBoxers.isEmpty)
    }
  }

  test("repeated successful runs are byte-identical after an intervening failure") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      val program = MorphProgram(
        concreteWitness = witness("ParameterizedWire"),
        parameterizedDesign = validDesign("ParameterizedWire")
      )

      MorphVerilog(config)(program)
      val output = directory.resolve("ParameterizedWire.v")
      val first = Files.readAllBytes(output).toVector

      val failed = MorphVerilog.tryGenerate(config) {
        MorphProgram(
          concreteWitness = witness("ParameterizedWire"),
          parameterizedDesign = Design("ParameterizedWire", Vector.empty)
        )
      }
      assertStage(failed, MorphVerilogStage.ParamRtlValidation)
      assert(Files.readAllBytes(output).toVector == first)

      MorphVerilog(config)(program)
      assert(Files.readAllBytes(output).toVector == first)
    }
  }

  private def witness(
      requestedName: String,
      onLoop: () => Unit = () => (),
      width: Int = 8
  ): Component = {
    val symbolicWidth = HdlInt.param("WIDTH", default = width, min = 1, max = 64)
    for (_ <- 0 until symbolicWidth) onLoop()
    new Component {
      setDefinitionName(requestedName)
      val data_in = in(Bits(width bits))
      val data_out = out(Bits(width bits))
      data_out := data_in
    }
  }

  private def witnessWithChild(requestedName: String): Component = {
    val symbolicWidth = HdlInt.param("WIDTH", default = 8, min = 1, max = 64)
    for (_ <- 0 until symbolicWidth) ()
    new Component {
      setDefinitionName(requestedName)
      val data_in = in(Bits(8 bits))
      val data_out = out(Bits(8 bits))
      val child = new Component {
        setDefinitionName("WitnessChild")
        val child_in = in(Bits(8 bits))
        val child_out = out(Bits(8 bits))
        child_out := child_in
      }
      child.child_in := data_in
      data_out := child.child_out
    }
  }

  private def hierarchicalWitness(requestedName: String, childWidth: Int): Component = {
    val symbolicWidth = HdlInt.param("WIDTH", default = 8, min = 1, max = 64)
    for (_ <- 0 until symbolicWidth) ()
    new Component {
      setDefinitionName(requestedName)
      val data_in = in(Bits(8 bits))
      val data_out = out(Bits(8 bits))
      val child = new Component {
        setDefinitionName("HierarchyLeaf")
        val leaf_in = in(Bits(childWidth bits))
        val leaf_out = out(Bits(childWidth bits))
        leaf_out := leaf_in
      }
      child.leaf_in := data_in.resized
      data_out := child.leaf_out.resized
    }
  }

  private def hierarchicalDesign(requestedName: String, childWidth: Int): Design = {
    val topPacked = PackedBits(morphhdl.paramrtl.IntExpr.Literal(8), Unsigned)
    val childPacked = PackedBits(morphhdl.paramrtl.IntExpr.Literal(childWidth), Unsigned)
    val child = ModuleDef(
      name = "HierarchyLeaf",
          parameters = Vector.empty,
          ports = Vector(
            Port("leaf_in", Input, childPacked),
            Port("leaf_out", Output, childPacked)
      ),
      items = Vector(ContinuousAssign(Ref("leaf_out"), Ref("leaf_in")))
    )
    val top = ModuleDef(
      name = requestedName,
      parameters = Vector.empty,
      ports = Vector(
        Port("data_in", Input, topPacked),
        Port("data_out", Output, topPacked)
      ),
      items = Vector(
        morphhdl.paramrtl.ModuleItem.ModuleInstance(
          name = "child",
          moduleName = child.name,
          portConnections = Vector(
            PortConnection("leaf_in", Ref("data_in")),
            PortConnection("leaf_out", Ref("data_out"))
          )
        )
      )
    )
    Design(requestedName, Vector(top, child))
  }

  private def validDesign(name: String): Design = {
    val width = ParameterRef("WIDTH")
    val packed = PackedBits(width, Unsigned)
    Design(
      top = name,
      modules = Vector(
        ModuleDef(
          name = name,
          parameters = Vector(
            IntegerParameter(
              "WIDTH",
              default = 8,
              constraints = Vector(MinInclusive(1), MaxInclusive(64))
            )
          ),
          ports = Vector(
            Port("data_in", Input, packed),
            Port("data_out", Output, packed)
          ),
          items = Vector(ContinuousAssign(Ref("data_out"), Ref("data_in")))
        )
      )
    )
  }

  private def expectedVerilog(name: String): String =
    s"""module $name #(
       |  parameter integer WIDTH = 8
       |) (
       |  input  wire [WIDTH-1:0] data_in,
       |  output wire [WIDTH-1:0] data_out
       |);
       |
       |  assign data_out = data_in;
       |
       |endmodule
       |""".stripMargin

  private def assertStage[T](
      result: Either[MorphVerilogFailure, T],
      expected: MorphVerilogStage
  ): Unit = result match {
    case Left(failure) => assert(failure.stage == expected, failure.message)
    case Right(value)  => fail(s"Expected ${expected.id} failure, received $value")
  }

  private def withTemporaryDirectory[A](body: Path => A): A = {
    val directory = Files.createTempDirectory("morphhdl-orchestration-test-")
    try body(directory)
    finally deleteTree(directory)
  }

  private def deleteTree(root: Path): Unit = {
    val stream = Files.walk(root)
    try {
      stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach { path =>
        Files.deleteIfExists(path)
      }
    } finally stream.close()
  }
}
