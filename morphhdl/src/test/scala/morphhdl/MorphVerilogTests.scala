package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

import morphhdl.frontend._
import morphhdl.frontend.ParamRtlFrontend._
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

  test("recursive default shape follows parameter bindings through three hierarchy levels") {
    withTemporaryDirectory { directory =>
      val report = MorphVerilog(SpinalConfig(targetDirectory = directory.toString)) {
        MorphProgram(
          concreteWitness = forwardingWitness("ForwardingTop", width = 32),
          parameterizedDesign = forwardingDesign("ForwardingTop")
        )
      }

      assert(report.toplevelName == "ForwardingTop")
      assert(Files.isRegularFile(directory.resolve("ForwardingTop.v")))
    }
  }

  test("default shape accepts two differently bound instances of one symbolic module") {
    withTemporaryDirectory { directory =>
      val report = MorphVerilog(SpinalConfig(targetDirectory = directory.toString)) {
        MorphProgram(
          concreteWitness = multiBoundWitness("MultiBoundTop"),
          parameterizedDesign = multiBoundDesign("MultiBoundTop")
        )
      }

      assert(report.toplevelName == "MultiBoundTop")
      assert(Files.isRegularFile(directory.resolve("MultiBoundTop.v")))
    }
  }

  test("default shape selects only the false generate-if branch when its Boolean default is false") {
    withTemporaryDirectory { directory =>
      val topName = "FalseDefaultConditional"
      val report = MorphVerilog(SpinalConfig(targetDirectory = directory.toString)) {
        MorphProgram(
          concreteWitness = falseBranchWitness(topName),
          parameterizedDesign = falseDefaultConditionalDesign(topName)
        )
      }

      assert(report.toplevelName == topName)
      assert(Files.isRegularFile(directory.resolve(s"$topName.v")))
    }
  }

  test("default shape evaluates integer comparisons before selecting a generate-if branch") {
    withTemporaryDirectory { directory =>
      val topName = "ComparedDefaultConditional"
      val report = MorphVerilog(SpinalConfig(targetDirectory = directory.toString)) {
        MorphProgram(
          concreteWitness = trueBranchWitness(topName),
          parameterizedDesign = comparedDefaultConditionalDesign(topName)
        )
      }

      assert(report.toplevelName == topName)
      assert(Files.isRegularFile(directory.resolve(s"$topName.v")))
    }
  }

  test("default shape follows a false integer comparison into the alternate branch") {
    withTemporaryDirectory { directory =>
      val topName = "ComparedFalseDefaultConditional"
      val report = MorphVerilog(SpinalConfig(targetDirectory = directory.toString)) {
        MorphProgram(
          concreteWitness = falseBranchWitness(topName),
          parameterizedDesign = comparedDefaultConditionalDesign(topName, selectDefault = 3)
        )
      }

      assert(report.toplevelName == topName)
      assert(Files.isRegularFile(directory.resolve(s"$topName.v")))
    }
  }

  test("default shape evaluates integer comparisons through derived local facts") {
    withTemporaryDirectory { directory =>
      val topName = "ComparedLocalDefaultConditional"
      val report = MorphVerilog(SpinalConfig(targetDirectory = directory.toString)) {
        MorphProgram(
          concreteWitness = trueBranchWitness(topName),
          parameterizedDesign = comparedDefaultConditionalDesign(
            topName,
            selectDefault = 4,
            compareThroughLocal = true
          )
        )
      }

      assert(report.toplevelName == topName)
      assert(Files.isRegularFile(directory.resolve(s"$topName.v")))
    }
  }

  test("recursive default shape evaluates child comparisons from the parent binding context") {
    withTemporaryDirectory { directory =>
      val topName = "BoundComparedConditional"
      val report = MorphVerilog(SpinalConfig(targetDirectory = directory.toString)) {
        MorphProgram(
          concreteWitness = boundComparedConditionalWitness(topName),
          parameterizedDesign = boundComparedConditionalDesign(topName)
        )
      }

      assert(report.toplevelName == topName)
      assert(Files.isRegularFile(directory.resolve(s"$topName.v")))
    }
  }

  test("default shape follows the true branch of a conditional integer local width") {
    withTemporaryDirectory { directory =>
      val topName = "TrueConditionalWidth"
      val report = MorphVerilog(SpinalConfig(targetDirectory = directory.toString)) {
        MorphProgram(
          concreteWitness = conditionalWidthWitness(topName, width = 12),
          parameterizedDesign = conditionalWidthDesign(topName, wideDefault = true)
        )
      }

      assert(report.toplevelName == topName)
      assert(Files.isRegularFile(directory.resolve(s"$topName.v")))
    }
  }

  test("default shape follows the false branch of a conditional integer local width") {
    withTemporaryDirectory { directory =>
      val topName = "FalseConditionalWidth"
      val report = MorphVerilog(SpinalConfig(targetDirectory = directory.toString)) {
        MorphProgram(
          concreteWitness = conditionalWidthWitness(topName, width = 4),
          parameterizedDesign = conditionalWidthDesign(topName, wideDefault = false)
        )
      }

      assert(report.toplevelName == topName)
      assert(Files.isRegularFile(directory.resolve(s"$topName.v")))
    }
  }

  test("recursive default shape evaluates a local-bound conditional branch in instance context") {
    withTemporaryDirectory { directory =>
      val topName = "LocalBoundConditionalWidth"
      val report = MorphVerilog(SpinalConfig(targetDirectory = directory.toString)) {
        MorphProgram(
          concreteWitness = boundConditionalWidthWitness(topName, width = 6),
          parameterizedDesign = boundConditionalWidthDesign(topName, childWideDefault = false)
        )
      }

      assert(report.toplevelName == topName)
      assert(Files.isRegularFile(directory.resolve(s"$topName.v")))
    }
  }

  test("recursive default shape evaluates a public-bound conditional branch in instance context") {
    withTemporaryDirectory { directory =>
      val topName = "PublicBoundConditionalWidth"
      val report = MorphVerilog(SpinalConfig(targetDirectory = directory.toString)) {
        MorphProgram(
          concreteWitness = boundConditionalWidthWitness(topName, width = 14),
          parameterizedDesign = boundConditionalWidthDesign(topName, childWideDefault = true)
        )
      }

      assert(report.toplevelName == topName)
      assert(Files.isRegularFile(directory.resolve(s"$topName.v")))
    }
  }

  test("recursive default shape substitutes a true Boolean child binding from parent context") {
    withTemporaryDirectory { directory =>
      val topName = "TrueBoundBooleanConditional"
      val report = MorphVerilog(SpinalConfig(targetDirectory = directory.toString)) {
        MorphProgram(
          concreteWitness = boundBooleanConditionalWitness(topName, selectHigh = true),
          parameterizedDesign = boundBooleanConditionalDesign(
            topName,
            enableDefault = true,
            widthDefault = 7,
            limitDefault = 8,
            childDefault = false,
            bindChild = true
          )
        )
      }

      assert(report.toplevelName == topName)
      assert(Files.isRegularFile(directory.resolve(s"$topName.v")))
    }
  }

  test("recursive default shape substitutes a false Boolean child binding from parent context") {
    withTemporaryDirectory { directory =>
      val topName = "FalseBoundBooleanConditional"
      val report = MorphVerilog(SpinalConfig(targetDirectory = directory.toString)) {
        MorphProgram(
          concreteWitness = boundBooleanConditionalWitness(topName, selectHigh = false),
          parameterizedDesign = boundBooleanConditionalDesign(
            topName,
            enableDefault = false,
            widthDefault = 12,
            limitDefault = 8,
            childDefault = true,
            bindChild = true
          )
        )
      }

      assert(report.toplevelName == topName)
      assert(Files.isRegularFile(directory.resolve(s"$topName.v")))
    }
  }

  test("recursive default shape retains an unbound child Boolean declaration default") {
    withTemporaryDirectory { directory =>
      val topName = "UnboundBooleanConditional"
      val report = MorphVerilog(SpinalConfig(targetDirectory = directory.toString)) {
        MorphProgram(
          concreteWitness = boundBooleanConditionalWitness(topName, selectHigh = true),
          parameterizedDesign = boundBooleanConditionalDesign(
            topName,
            enableDefault = false,
            widthDefault = 1,
            limitDefault = 8,
            childDefault = true,
            bindChild = false
          )
        )
      }

      assert(report.toplevelName == topName)
      assert(Files.isRegularFile(directory.resolve(s"$topName.v")))
    }
  }

  test("default shape keeps opposite Boolean bindings separate for sibling child instances") {
    withTemporaryDirectory { directory =>
      val topName = "SiblingBoundBooleanConditional"
      val report = MorphVerilog(SpinalConfig(targetDirectory = directory.toString)) {
        MorphProgram(
          concreteWitness = siblingBoundBooleanConditionalWitness(topName),
          parameterizedDesign = siblingBoundBooleanConditionalDesign(topName)
        )
      }

      assert(report.toplevelName == topName)
      assert(Files.isRegularFile(directory.resolve(s"$topName.v")))
    }
  }

  test("recursive default shape forwards a substituted Boolean binding across two hierarchy hops") {
    withTemporaryDirectory { directory =>
      val topName = "TwoHopBoundBooleanConditional"
      val report = MorphVerilog(SpinalConfig(targetDirectory = directory.toString)) {
        MorphProgram(
          concreteWitness = twoHopBoundBooleanConditionalWitness(topName),
          parameterizedDesign = twoHopBoundBooleanConditionalDesign(topName)
        )
      }

      assert(report.toplevelName == topName)
      assert(Files.isRegularFile(directory.resolve(s"$topName.v")))
    }
  }

  test("an invalid inactive Boolean child binding fails whole-design validation") {
    withTemporaryDirectory { directory =>
      val topName = "InvalidInactiveBooleanBinding"
      val result = MorphVerilog.tryGenerate(SpinalConfig(targetDirectory = directory.toString)) {
        MorphProgram(
          concreteWitness = boundBooleanConditionalWitness(topName, selectHigh = false),
          parameterizedDesign = invalidInactiveBooleanBindingDesign(topName)
        )
      }

      result match {
        case Left(failure) =>
          assert(failure.stage == MorphVerilogStage.ParamRtlValidation)
          assert(failure.message.contains("PRTL-DIVISOR-MAY-BE-ZERO"))
        case Right(report) => fail(s"Expected Boolean-binding validation failure, received $report")
      }
      assert(!Files.exists(directory.resolve(s"$topName.v")))
    }
  }

  test("default shape follows a true Boolean-local dependency chain") {
    withTemporaryDirectory { directory =>
      val topName = "TrueBooleanLocalConditional"
      val report = MorphVerilog(SpinalConfig(targetDirectory = directory.toString)) {
        MorphProgram(
          concreteWitness = trueBranchWitness(topName),
          parameterizedDesign = booleanLocalConditionalDesign(
            topName,
            enableDefault = true,
            widthDefault = 8,
            limitDefault = 8
          )
        )
      }

      assert(report.toplevelName == topName)
      assert(Files.isRegularFile(directory.resolve(s"$topName.v")))
    }
  }

  test("default shape follows a false Boolean-local dependency chain") {
    withTemporaryDirectory { directory =>
      val topName = "FalseBooleanLocalConditional"
      val report = MorphVerilog(SpinalConfig(targetDirectory = directory.toString)) {
        MorphProgram(
          concreteWitness = falseBranchWitness(topName),
          parameterizedDesign = booleanLocalConditionalDesign(
            topName,
            enableDefault = true,
            widthDefault = 7,
            limitDefault = 8
          )
        )
      }

      assert(report.toplevelName == topName)
      assert(Files.isRegularFile(directory.resolve(s"$topName.v")))
    }
  }

  test("recursive default shape forwards a parent Boolean-local result into a child binding") {
    withTemporaryDirectory { directory =>
      val topName = "BooleanLocalBoundChild"
      val report = MorphVerilog(SpinalConfig(targetDirectory = directory.toString)) {
        MorphProgram(
          concreteWitness = boundBooleanConditionalWitness(topName, selectHigh = true),
          parameterizedDesign = booleanLocalBindingDesign(topName)
        )
      }

      assert(report.toplevelName == topName)
      assert(Files.isRegularFile(directory.resolve(s"$topName.v")))
    }
  }

  test("recursive default shape recomputes mixed child locals from parent-bound public values") {
    withTemporaryDirectory { directory =>
      val topName = "BoundChildBooleanLocals"
      val report = MorphVerilog(SpinalConfig(targetDirectory = directory.toString)) {
        MorphProgram(
          concreteWitness = boundBooleanConditionalWitness(topName, selectHigh = false),
          parameterizedDesign = boundChildBooleanLocalDesign(topName)
        )
      }

      assert(report.toplevelName == topName)
      assert(Files.isRegularFile(directory.resolve(s"$topName.v")))
    }
  }

  test("sibling instances keep independently evaluated Boolean-local contexts") {
    withTemporaryDirectory { directory =>
      val topName = "SiblingBooleanLocalContexts"
      val report = MorphVerilog(SpinalConfig(targetDirectory = directory.toString)) {
        MorphProgram(
          concreteWitness = siblingBoundBooleanConditionalWitness(topName),
          parameterizedDesign = siblingBooleanLocalDesign(topName)
        )
      }

      assert(report.toplevelName == topName)
      assert(Files.isRegularFile(directory.resolve(s"$topName.v")))
    }
  }

  test("an invalid inactive Boolean-local operand fails whole-design validation") {
    withTemporaryDirectory { directory =>
      val topName = "InvalidInactiveBooleanLocal"
      val result = MorphVerilog.tryGenerate(SpinalConfig(targetDirectory = directory.toString)) {
        MorphProgram(
          concreteWitness = witness(topName),
          parameterizedDesign = invalidInactiveBooleanLocalDesign(topName)
        )
      }

      result match {
        case Left(failure) =>
          assert(failure.stage == MorphVerilogStage.ParamRtlValidation)
          assert(failure.message.contains("PRTL-DIVISOR-MAY-BE-ZERO"))
        case Right(report) => fail(s"Expected Boolean-local validation failure, received $report")
      }
      assert(!Files.exists(directory.resolve(s"$topName.v")))
    }
  }

  test("default shape selects a matching generate-case choice") {
    withTemporaryDirectory { directory =>
      val topName = "MatchedGenerateCase"
      val report = MorphVerilog(SpinalConfig(targetDirectory = directory.toString)) {
        MorphProgram(
          concreteWitness = generateCaseWitness(topName, selected = 1),
          parameterizedDesign = generateCaseDesign(topName, modeDefault = 1)
        )
      }

      assert(report.toplevelName == topName)
      assert(Files.isRegularFile(directory.resolve(s"$topName.v")))
    }
  }

  test("default shape selects the mandatory generate-case default") {
    withTemporaryDirectory { directory =>
      val topName = "DefaultedGenerateCase"
      val report = MorphVerilog(SpinalConfig(targetDirectory = directory.toString)) {
        MorphProgram(
          concreteWitness = generateCaseWitness(topName, selected = 3),
          parameterizedDesign = generateCaseDesign(topName, modeDefault = 3)
        )
      }

      assert(report.toplevelName == topName)
      assert(Files.isRegularFile(directory.resolve(s"$topName.v")))
    }
  }

  test("default shape evaluates a generate-case selector through mixed Boolean and integer locals") {
    withTemporaryDirectory { directory =>
      val topName = "LocalGenerateCase"
      val report = MorphVerilog(SpinalConfig(targetDirectory = directory.toString)) {
        MorphProgram(
          concreteWitness = generateCaseWitness(topName, selected = 2),
          parameterizedDesign = generateCaseDesign(
            topName,
            modeDefault = 1,
            selectorThroughLocal = true
          )
        )
      }

      assert(report.toplevelName == topName)
      assert(Files.isRegularFile(directory.resolve(s"$topName.v")))
    }
  }

  test("recursive default shape evaluates a parent-bound child generate-case selector") {
    withTemporaryDirectory { directory =>
      val topName = "BoundGenerateCase"
      val report = MorphVerilog(SpinalConfig(targetDirectory = directory.toString)) {
        MorphProgram(
          concreteWitness = boundGenerateCaseWitness(topName, selected = 2),
          parameterizedDesign = boundGenerateCaseDesign(topName)
        )
      }

      assert(report.toplevelName == topName)
      assert(Files.isRegularFile(directory.resolve(s"$topName.v")))
    }
  }

  test("sibling instances keep independently selected generate-case contexts") {
    withTemporaryDirectory { directory =>
      val topName = "SiblingGenerateCaseContexts"
      val report = MorphVerilog(SpinalConfig(targetDirectory = directory.toString)) {
        MorphProgram(
          concreteWitness = siblingGenerateCaseWitness(topName),
          parameterizedDesign = siblingGenerateCaseDesign(topName)
        )
      }

      assert(report.toplevelName == topName)
      assert(Files.isRegularFile(directory.resolve(s"$topName.v")))
    }
  }

  test("inactive generate-case choices remain subject to whole-design validation") {
    withTemporaryDirectory { directory =>
      val topName = "InvalidInactiveGenerateCase"
      val result = MorphVerilog.tryGenerate(SpinalConfig(targetDirectory = directory.toString)) {
        MorphProgram(
          concreteWitness = generateCaseWitness(topName, selected = 0),
          parameterizedDesign = invalidInactiveGenerateCaseDesign(topName)
        )
      }

      result match {
        case Left(failure) =>
          assert(failure.stage == MorphVerilogStage.ParamRtlValidation)
          assert(failure.message.contains("MissingInactiveCaseLeaf"))
        case Right(report) => fail(s"Expected generate-case validation failure, received $report")
      }
      assert(!Files.exists(directory.resolve(s"$topName.v")))
    }
  }

  test("ceiling-log2 derived width preserves the default public shape and reviewed output") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "derived_width.v"
      val report = MorphVerilog(config) {
        DerivedWidthContractFixture.program(reverseConstructionOrder = false)
      }

      val output = directory.resolve("derived_width.v")
      assert(report.toplevelName == "DerivedWidth")
      assert(report.inheritedValidationPhaseIds == expectedPhaseIds)
      assert(Files.isRegularFile(output))
      assert(
        new String(Files.readAllBytes(output), StandardCharsets.UTF_8) ==
          expectedDerivedWidthVerilog
      )
    }
  }

  test("runtime combinational process preserves the default public shape and reviewed output") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "runtime_mux.v"
      val report = MorphVerilog(config) {
        RuntimeMuxContractFixture.program(reverseConstructionOrder = false)
      }

      val output = directory.resolve("runtime_mux.v")
      assert(report.toplevelName == "RuntimeMux")
      assert(report.inheritedValidationPhaseIds == expectedPhaseIds)
      assert(Files.isRegularFile(output))
      assert(
        new String(Files.readAllBytes(output), StandardCharsets.UTF_8) ==
          expectedRuntimeMuxVerilog
      )
    }
  }

  test("runtime combinational process validation fails before public output") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "runtime_mux.v"
      val fixture = RuntimeMuxContractFixture.program(reverseConstructionOrder = false)
      val result = MorphVerilog.tryGenerate(config) {
        MorphProgram(
          concreteWitness = fixture.concreteWitnessFactory(),
          parameterizedDesign = invalidRuntimeMuxDesign()
        )
      }

      result match {
        case Left(failure) =>
          assert(failure.stage == MorphVerilogStage.ParamRtlValidation)
          assert(failure.message.contains("PRTL-EMPTY-COMBINATIONAL-BRANCH"))
        case Right(report) => fail(s"Expected incomplete process validation failure, received $report")
      }
      assert(!Files.exists(directory.resolve("runtime_mux.v")))
    }
  }

  test("synchronous register preserves the default public shape and reviewed output") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "synchronous_register.v"
      val report = MorphVerilog(config) {
        SynchronousRegisterContractFixture.program(reverseConstructionOrder = false)
      }

      val output = directory.resolve("synchronous_register.v")
      assert(report.toplevelName == "SynchronousRegister")
      assert(report.inheritedValidationPhaseIds == expectedPhaseIds)
      assert(Files.isRegularFile(output))
      assert(
        new String(Files.readAllBytes(output), StandardCharsets.UTF_8) ==
          expectedSynchronousRegisterVerilog
      )
    }
  }

  test("synchronous register validation fails before public output") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "synchronous_register.v"
      val fixture = SynchronousRegisterContractFixture.program(reverseConstructionOrder = false)
      val result = MorphVerilog.tryGenerate(config) {
        MorphProgram(
          concreteWitness = fixture.concreteWitnessFactory(),
          parameterizedDesign = invalidSynchronousRegisterDesign()
        )
      }

      result match {
        case Left(failure) =>
          assert(failure.stage == MorphVerilogStage.ParamRtlValidation)
          assert(failure.message.contains("PRTL-UNRESOLVED-RTL-REFERENCE"))
        case Right(report) => fail(s"Expected synchronous-register validation failure, received $report")
      }
      assert(!Files.exists(directory.resolve("synchronous_register.v")))
    }
  }

  test("asynchronous register preserves the default public shape and reviewed output") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "asynchronous_register.v"
      val report = MorphVerilog(config) {
        AsynchronousRegisterContractFixture.program(reverseConstructionOrder = false)
      }

      val output = directory.resolve("asynchronous_register.v")
      assert(report.toplevelName == "AsynchronousRegister")
      assert(report.inheritedValidationPhaseIds == expectedPhaseIds)
      assert(Files.isRegularFile(output))
      assert(
        new String(Files.readAllBytes(output), StandardCharsets.UTF_8) ==
          expectedAsynchronousRegisterVerilog
      )
    }
  }

  test("asynchronous register validation fails before public output") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "asynchronous_register.v"
      val fixture = AsynchronousRegisterContractFixture.program(reverseConstructionOrder = false)
      val result = MorphVerilog.tryGenerate(config) {
        MorphProgram(
          concreteWitness = fixture.concreteWitnessFactory(),
          parameterizedDesign = invalidAsynchronousRegisterDesign()
        )
      }

      result match {
        case Left(failure) =>
          assert(failure.stage == MorphVerilogStage.ParamRtlValidation)
          assert(failure.message.contains("PRTL-UNRESOLVED-RTL-REFERENCE"))
        case Right(report) => fail(s"Expected asynchronous-register validation failure, received $report")
      }
      assert(!Files.exists(directory.resolve("asynchronous_register.v")))
    }
  }

  test("synchronous enabled register preserves the default public shape and reviewed output") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "synchronous_enabled_register.v"
      val report = MorphVerilog(config) {
        SynchronousEnabledRegisterContractFixture.program(reverseConstructionOrder = false)
      }

      val output = directory.resolve("synchronous_enabled_register.v")
      assert(report.toplevelName == "SynchronousEnabledRegister")
      assert(report.inheritedValidationPhaseIds == expectedPhaseIds)
      assert(Files.isRegularFile(output))
      assert(
        new String(Files.readAllBytes(output), StandardCharsets.UTF_8) ==
          expectedSynchronousEnabledRegisterVerilog
      )
    }
  }

  test("synchronous enabled register validation fails before public output") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "synchronous_enabled_register.v"
      val fixture =
        SynchronousEnabledRegisterContractFixture.program(reverseConstructionOrder = false)
      val result = MorphVerilog.tryGenerate(config) {
        MorphProgram(
          concreteWitness = fixture.concreteWitnessFactory(),
          parameterizedDesign = invalidSynchronousEnabledRegisterDesign()
        )
      }

      result match {
        case Left(failure) =>
          assert(failure.stage == MorphVerilogStage.ParamRtlValidation)
          assert(failure.message.contains("PRTL-UNRESOLVED-RTL-REFERENCE"))
        case Right(report) =>
          fail(s"Expected synchronous-enabled-register validation failure, received $report")
      }
      assert(!Files.exists(directory.resolve("synchronous_enabled_register.v")))
    }
  }

  test("asynchronous enabled register preserves the default public shape and reviewed output") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "asynchronous_enabled_register.v"
      val report = MorphVerilog(config) {
        AsynchronousEnabledRegisterContractFixture.program(reverseConstructionOrder = false)
      }

      val output = directory.resolve("asynchronous_enabled_register.v")
      assert(report.toplevelName == "AsynchronousEnabledRegister")
      assert(report.inheritedValidationPhaseIds == expectedPhaseIds)
      assert(Files.isRegularFile(output))
      assert(
        new String(Files.readAllBytes(output), StandardCharsets.UTF_8) ==
          expectedAsynchronousEnabledRegisterVerilog
      )
    }
  }

  test("asynchronous enabled register validation fails before public output") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "asynchronous_enabled_register.v"
      val fixture =
        AsynchronousEnabledRegisterContractFixture.program(reverseConstructionOrder = false)
      val result = MorphVerilog.tryGenerate(config) {
        MorphProgram(
          concreteWitness = fixture.concreteWitnessFactory(),
          parameterizedDesign = invalidAsynchronousEnabledRegisterDesign()
        )
      }

      result match {
        case Left(failure) =>
          assert(failure.stage == MorphVerilogStage.ParamRtlValidation)
          assert(failure.message.contains("PRTL-UNRESOLVED-RTL-REFERENCE"))
        case Right(report) =>
          fail(s"Expected asynchronous-enabled-register validation failure, received $report")
      }
      assert(!Files.exists(directory.resolve("asynchronous_enabled_register.v")))
    }
  }

  test("single-port memory preserves the default public shape and reviewed output") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "single_port_memory.v"
      val report = MorphVerilog(config) {
        SinglePortMemoryContractFixture.program(reverseConstructionOrder = false)
      }

      val output = directory.resolve("single_port_memory.v")
      assert(report.toplevelName == "SinglePortMemory")
      assert(report.inheritedValidationPhaseIds == expectedPhaseIds)
      assert(Files.isRegularFile(output))
      assert(
        new String(Files.readAllBytes(output), StandardCharsets.UTF_8) ==
          expectedSinglePortMemoryVerilog
      )
    }
  }

  test("portable address width agrees with concrete defaults at depths one two three and five") {
    Vector(1 -> 1, 2 -> 1, 3 -> 2, 5 -> 3).foreach { case (depth, expectedWidth) =>
      withTemporaryDirectory { directory =>
        val topName = s"AddressWidthDepth$depth"
        val config = SpinalConfig(targetDirectory = directory.toString)
        config.netlistFileName = s"address_width_depth_$depth.v"
        val report = MorphVerilog(config) {
          MorphProgram(
            concreteWitness = passThroughWitness(topName, expectedWidth),
            parameterizedDesign = addressWidthDesign(topName, depth)
          )
        }

        val output = directory.resolve(s"address_width_depth_$depth.v")
        assert(report.toplevelName == topName)
        assert(report.inheritedValidationPhaseIds == expectedPhaseIds)
        assert(Files.isRegularFile(output))
        val verilog = new String(Files.readAllBytes(output), StandardCharsets.UTF_8)
        assert(verilog.contains(s"parameter integer DEPTH = $depth"))
        assert(!verilog.contains("$clog2"))
      }
    }
  }

  test("portable address width evaluates local and parent-bound default context") {
    withTemporaryDirectory { directory =>
      val topName = "BoundAddressWidth"
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "bound_address_width.v"
      val report = MorphVerilog(config) {
        MorphProgram(
          concreteWitness = boundAddressWidthWitness(topName, width = 2),
          parameterizedDesign = boundAddressWidthDesign(topName)
        )
      }

      assert(report.toplevelName == topName)
      assert(report.inheritedValidationPhaseIds == expectedPhaseIds)
      assert(Files.isRegularFile(directory.resolve("bound_address_width.v")))
    }
  }

  test("portable logarithm helper uses a natural deterministic suffix on a module-local collision") {
    withTemporaryDirectory { directory =>
      val topName = "Clog2Collision"
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "clog2_collision.v"
      val report = MorphVerilog(config) {
        MorphProgram(
          concreteWitness = new Component {
            setDefinitionName(topName)
            val clog2 = in(morphhdl.frontend.Bits(3 bits)).setName("clog2")
            val data_out = out(morphhdl.frontend.Bits(3 bits)).setName("data_out")
            data_out := clog2
          },
          parameterizedDesign = {
            val depth = HdlInt.param("DEPTH", default = 5, min = 1, max = 8)
            val dataType = packedBits(depth.addressWidth)
            val top = moduleDef(
              name = topName,
              parameters = Vector(integerParameter(depth)),
              ports = Vector(
                port("clog2", Input, dataType),
                port("data_out", Output, dataType)
              ),
              items = captureItems {
                emitContinuousAssign("data_out", ref("clog2"))
              }
            )
            Design(top.name, Vector(top))
          }
        )
      }

      val output = directory.resolve("clog2_collision.v")
      val verilog = new String(Files.readAllBytes(output), StandardCharsets.UTF_8)
      assert(report.toplevelName == topName)
      assert(report.inheritedValidationPhaseIds == expectedPhaseIds)
      assert(verilog.contains("function integer clog2_1;"), verilog)
      assert(verilog.contains("clog2_1(DEPTH, 1)"), verilog)
      assert(!verilog.contains("function integer clog2;"), verilog)
    }
  }

  test("portable address width rejects a possibly nonpositive domain before public output") {
    withTemporaryDirectory { directory =>
      val topName = "UnsafeAddressWidth"
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "unsafe_address_width.v"
      val result = MorphVerilog.tryGenerate(config) {
        MorphProgram(
          concreteWitness = passThroughWitness(topName, width = 1),
          parameterizedDesign = addressWidthDesign(topName, depthDefault = 1, depthMinimum = 0)
        )
      }

      result match {
        case Left(failure) =>
          assert(failure.stage == MorphVerilogStage.ParamRtlValidation)
          assert(failure.message.contains("ADDRESS-WIDTH"))
        case Right(report) => fail(s"Expected unsafe address-width validation failure, received $report")
      }
      assert(!Files.exists(directory.resolve("unsafe_address_width.v")))
    }
  }

  test("single-port memory validation fails before public output") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "single_port_memory.v"
      val fixture = SinglePortMemoryContractFixture.program(reverseConstructionOrder = false)
      val result = MorphVerilog.tryGenerate(config) {
        MorphProgram(
          concreteWitness = fixture.concreteWitnessFactory(),
          parameterizedDesign = invalidSinglePortMemoryDesign()
        )
      }

      result match {
        case Left(failure) =>
          assert(failure.stage == MorphVerilogStage.ParamRtlValidation)
          assert(failure.message.contains("PRTL-UNRESOLVED-RTL-REFERENCE"))
        case Right(report) =>
          fail(s"Expected single-port-memory validation failure, received $report")
      }
      assert(!Files.exists(directory.resolve("single_port_memory.v")))
    }
  }

  test("parameterized counter preserves the default public shape and reviewed output") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "parameterized_counter.v"
      val report = MorphVerilog(config) {
        ParameterizedCounterContractFixture.program(reverseConstructionOrder = false)
      }

      val output = directory.resolve("parameterized_counter.v")
      assert(report.toplevelName == "ParameterizedCounter")
      assert(report.inheritedValidationPhaseIds == expectedPhaseIds)
      assert(Files.isRegularFile(output))
      assert(
        new String(Files.readAllBytes(output), StandardCharsets.UTF_8) ==
          expectedParameterizedCounterVerilog
      )
    }
  }

  test("parameterized counter validation fails before public output") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "parameterized_counter.v"
      val fixture = ParameterizedCounterContractFixture.program(reverseConstructionOrder = false)
      val result = MorphVerilog.tryGenerate(config) {
        MorphProgram(
          concreteWitness = fixture.concreteWitnessFactory(),
          parameterizedDesign = invalidParameterizedCounterDesign()
        )
      }

      result match {
        case Left(failure) =>
          assert(failure.stage == MorphVerilogStage.ParamRtlValidation)
          assert(failure.message.contains("PRTL-UNRESOLVED-RTL-REFERENCE"))
        case Right(report) =>
          fail(s"Expected parameterized-counter validation failure, received $report")
      }
      assert(!Files.exists(directory.resolve("parameterized_counter.v")))
    }
  }

  test("simple-dual-port memory preserves the default public shape and reviewed output") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "simple_dual_port_memory.v"
      val report = MorphVerilog(config) {
        SimpleDualPortMemoryContractFixture.program(reverseConstructionOrder = false)
      }

      val output = directory.resolve("simple_dual_port_memory.v")
      assert(report.toplevelName == "SimpleDualPortMemory")
      assert(report.inheritedValidationPhaseIds == expectedPhaseIds)
      assert(Files.isRegularFile(output))
      assert(
        new String(Files.readAllBytes(output), StandardCharsets.UTF_8) ==
          expectedSimpleDualPortMemoryVerilog
      )
    }
  }

  test("simple-dual-port memory validation fails before public output") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "simple_dual_port_memory.v"
      val fixture = SimpleDualPortMemoryContractFixture.program(reverseConstructionOrder = false)
      val result = MorphVerilog.tryGenerate(config) {
        MorphProgram(
          concreteWitness = fixture.concreteWitnessFactory(),
          parameterizedDesign = invalidSimpleDualPortMemoryDesign()
        )
      }

      result match {
        case Left(failure) =>
          assert(failure.stage == MorphVerilogStage.ParamRtlValidation)
          assert(failure.message.contains("PRTL-UNRESOLVED-RTL-REFERENCE"))
        case Right(report) =>
          fail(s"Expected simple-dual-port-memory validation failure, received $report")
      }
      assert(!Files.exists(directory.resolve("simple_dual_port_memory.v")))
    }
  }

  test("synchronous Stream FIFO preserves the default public shape and reviewed output") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(
        targetDirectory = directory.toString,
        defaultConfigForClockDomains = ClockDomainConfig(
          clockEdge = RISING,
          resetKind = SYNC,
          resetActiveLevel = HIGH
        )
      )
      config.netlistFileName = "synchronous_stream_fifo.v"
      val report = MorphVerilog(config) {
        SynchronousStreamFifoContractFixture.program(reverseConstructionOrder = false)
      }

      val output = directory.resolve("synchronous_stream_fifo.v")
      assert(report.toplevelName == "SynchronousStreamFifo")
      assert(report.inheritedValidationPhaseIds == expectedPhaseIds)
      assert(Files.isRegularFile(output))
      assert(
        new String(Files.readAllBytes(output), StandardCharsets.UTF_8) ==
          expectedSynchronousStreamFifoVerilog
      )
    }
  }

  test("synchronous Stream FIFO validation fails before public output") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(
        targetDirectory = directory.toString,
        defaultConfigForClockDomains = ClockDomainConfig(
          clockEdge = RISING,
          resetKind = SYNC,
          resetActiveLevel = HIGH
        )
      )
      config.netlistFileName = "synchronous_stream_fifo.v"
      val fixture =
        SynchronousStreamFifoContractFixture.program(reverseConstructionOrder = false)
      val result = MorphVerilog.tryGenerate(config) {
        MorphProgram(
          concreteWitness = fixture.concreteWitnessFactory(),
          parameterizedDesign = invalidSynchronousStreamFifoDesign()
        )
      }

      result match {
        case Left(failure) =>
          assert(failure.stage == MorphVerilogStage.ParamRtlValidation)
          assert(failure.message.contains("PRTL-UNRESOLVED-RTL-REFERENCE"))
        case Right(report) =>
          fail(s"Expected synchronous-Stream-FIFO validation failure, received $report")
      }
      assert(!Files.exists(directory.resolve("synchronous_stream_fifo.v")))
    }
  }

  test("synchronous Stream m2s pipe preserves the default public shape and reviewed output") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(
        targetDirectory = directory.toString,
        defaultConfigForClockDomains = ClockDomainConfig(
          clockEdge = RISING,
          resetKind = SYNC,
          resetActiveLevel = HIGH
        )
      )
      config.netlistFileName = "synchronous_stream_m2s_pipe.v"
      val report = MorphVerilog(config) {
        SynchronousStreamM2sPipeContractFixture.program(reverseConstructionOrder = false)
      }

      val output = directory.resolve("synchronous_stream_m2s_pipe.v")
      assert(report.toplevelName == "SynchronousStreamM2sPipe")
      assert(report.inheritedValidationPhaseIds == expectedPhaseIds)
      assert(Files.isRegularFile(output))
      assert(
        new String(Files.readAllBytes(output), StandardCharsets.UTF_8) ==
          expectedSynchronousStreamM2sPipeVerilog
      )
    }
  }

  test("synchronous Stream m2s pipe validation fails before public output") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(
        targetDirectory = directory.toString,
        defaultConfigForClockDomains = ClockDomainConfig(
          clockEdge = RISING,
          resetKind = SYNC,
          resetActiveLevel = HIGH
        )
      )
      config.netlistFileName = "synchronous_stream_m2s_pipe.v"
      val fixture =
        SynchronousStreamM2sPipeContractFixture.program(reverseConstructionOrder = false)
      val result = MorphVerilog.tryGenerate(config) {
        MorphProgram(
          concreteWitness = fixture.concreteWitnessFactory(),
          parameterizedDesign = invalidSynchronousStreamM2sPipeDesign()
        )
      }

      result match {
        case Left(failure) =>
          assert(failure.stage == MorphVerilogStage.ParamRtlValidation)
          assert(failure.message.contains("PRTL-UNRESOLVED-RTL-REFERENCE"))
        case Right(report) =>
          fail(s"Expected synchronous-Stream-m2s-pipe validation failure, received $report")
      }
      assert(!Files.exists(directory.resolve("synchronous_stream_m2s_pipe.v")))
    }
  }

  test("an invalid inactive conditional integer branch fails whole-design validation") {
    withTemporaryDirectory { directory =>
      val topName = "InvalidInactiveConditionalWidth"
      val result = MorphVerilog.tryGenerate(SpinalConfig(targetDirectory = directory.toString)) {
        MorphProgram(
          concreteWitness = passThroughWitness(topName, width = 8),
          parameterizedDesign = invalidInactiveConditionalWidthDesign(topName)
        )
      }

      result match {
        case Left(failure) =>
          assert(failure.stage == MorphVerilogStage.ParamRtlValidation)
          assert(failure.message.contains("PRTL-DIVISOR-MAY-BE-ZERO"))
        case Right(report) => fail(s"Expected inactive-branch validation failure, received $report")
      }
      assert(!Files.exists(directory.resolve(s"$topName.v")))
    }
  }

  test("inactive generate-if branches remain subject to whole-design validation") {
    withTemporaryDirectory { directory =>
      val topName = "InvalidInactiveConditional"
      val result = MorphVerilog.tryGenerate(SpinalConfig(targetDirectory = directory.toString)) {
        MorphProgram(
          concreteWitness = trueBranchWitness(topName),
          parameterizedDesign = invalidInactiveConditionalDesign(topName)
        )
      }

      assertStage(result, MorphVerilogStage.ParamRtlValidation)
      assert(!Files.exists(directory.resolve(s"$topName.v")))
    }
  }

  test("default-selected generate-if branch hierarchy multiplicity must match the concrete witness") {
    withTemporaryDirectory { directory =>
      val topName = "ConditionalMultiplicityMismatch"
      val result = MorphVerilog.tryGenerate(SpinalConfig(targetDirectory = directory.toString)) {
        MorphProgram(
          concreteWitness = falseBranchWitness(topName, copies = 2),
          parameterizedDesign = falseDefaultConditionalDesign(topName)
        )
      }

      assertStage(result, MorphVerilogStage.DefaultShapeAgreement)
      assert(!Files.exists(directory.resolve(s"$topName.v")))
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

  test("dual-factory includeFormal fails before either factory runs") {
    withTemporaryDirectory { directory =>
      var concreteRuns = 0
      var symbolicRuns = 0
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.includeFormal

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

  test("unsupported inherited output switches fail before factories") {
    withTemporaryDirectory { directory =>
      val configs = Vector(
        SpinalConfig(targetDirectory = directory.toString, genLineComments = true),
        SpinalConfig(targetDirectory = directory.toString, privateNamespace = true),
        SpinalConfig(targetDirectory = directory.toString, cutLongExpressions = false),
        SpinalConfig(targetDirectory = directory.toString, emitFullComponentBindings = false),
        SpinalConfig(targetDirectory = directory.toString).dumpWave()
      )

      configs.foreach { config =>
        var concreteRuns = 0
        var symbolicRuns = 0
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

  test("public orchestration preserves the complete HdlInt and local-parameter algebra") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "integer_algebra.v"

      val report = MorphVerilog(config) {
        MorphProgram(
          concreteWitness = passThroughWitness("IntegerAlgebra", width = 10),
          parameterizedDesign = integerAlgebraDesign()
        )
      }

      val verilog = new String(
        Files.readAllBytes(directory.resolve("integer_algebra.v")),
        StandardCharsets.UTF_8
      )
      assert(report.toplevelName == "IntegerAlgebra")
      assert(verilog.contains("localparam integer ADDED = BASE + 4;"))
      assert(verilog.contains("localparam integer SUBTRACTED = ADDED - 2;"))
      assert(verilog.contains("localparam integer MULTIPLIED = SUBTRACTED * 2;"))
      assert(verilog.contains("localparam integer DIVIDED = MULTIPLIED / DIVISOR;"))
      assert(verilog.contains("localparam integer REMAINDER = DIVIDED % DIVISOR;"))
      assert(verilog.contains("localparam integer NEGATED = -REMAINDER;"))
      assert(verilog.contains("localparam integer MINIMUM = (BASE < 10) ? BASE : 10;"))
      assert(verilog.contains("localparam integer MAXIMUM = (BASE > 14) ? BASE : 14;"))
      assert(verilog.contains("localparam integer WIDTH = DIVIDED + NEGATED + 1;"))
    }
  }

  test("public orchestration rejects a divisor domain containing zero") {
    withTemporaryDirectory { directory =>
      val result = MorphVerilog.tryGenerate(
        SpinalConfig(targetDirectory = directory.toString)
      ) {
        MorphProgram(
          concreteWitness = passThroughWitness("UnsafeDivisor", width = 4),
          parameterizedDesign = unsafeDivisorDesign()
        )
      }

      result match {
        case Left(failure) =>
          assert(failure.stage == MorphVerilogStage.ParamRtlValidation)
          assert(failure.message.contains("PRTL-DIVISOR-MAY-BE-ZERO"))
        case Right(report) => fail(s"Expected whole-domain divisor failure, received $report")
      }
      assert(!Files.exists(directory.resolve("UnsafeDivisor.v")))
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
      val data_in = in(morphhdl.frontend.Bits(width bits))
      val data_out = out(morphhdl.frontend.Bits(width bits))
      data_out := data_in
    }
  }

  private def passThroughWitness(requestedName: String, width: Int): Component =
    new Component {
      setDefinitionName(requestedName)
      val data_in = in(morphhdl.frontend.Bits(width bits))
      val data_out = out(morphhdl.frontend.Bits(width bits))
      data_out := data_in
    }

  private def conditionalWidthWitness(requestedName: String, width: Int): Component =
    new Component {
      setDefinitionName(requestedName)
      val din = in(morphhdl.frontend.Bits(width bits))
      val dout = out(morphhdl.frontend.Bits(width bits))
      dout := din
    }

  private def integerAlgebraDesign(): Design = {
    val base = HdlInt.param("BASE", default = 12, min = 8, max = 16)
    val divisor = HdlInt.param("DIVISOR", default = 3, min = 2, max = 4)
    val added = localParam("ADDED", base + 4)
    val subtracted = localParam("SUBTRACTED", added - 2)
    val multiplied = localParam("MULTIPLIED", subtracted * 2)
    val divided = localParam("DIVIDED", multiplied / divisor)
    val remainder = localParam("REMAINDER", divided % divisor)
    val negated = localParam("NEGATED", -remainder)
    val minimum = localParam("MINIMUM", base.min(HdlInt.literal(10)))
    val maximum = localParam("MAXIMUM", base.max(HdlInt.literal(14)))
    val width = localParam("WIDTH", divided + negated + 1)
    val packed = packedBits(width)
    val module = moduleDef(
      name = "IntegerAlgebra",
      parameters = Vector(integerParameter(base), integerParameter(divisor)),
      ports = Vector(port("data_in", Input, packed), port("data_out", Output, packed)),
      items = captureItems {
        emitContinuousAssign("data_out", ref("data_in"))
      },
      localParameters = Vector(
        integerLocalParameter(width),
        integerLocalParameter(negated),
        integerLocalParameter(remainder),
        integerLocalParameter(divided),
        integerLocalParameter(minimum),
        integerLocalParameter(maximum),
        integerLocalParameter(multiplied),
        integerLocalParameter(subtracted),
        integerLocalParameter(added)
      )
    )
    Design(module.name, Vector(module))
  }

  private def unsafeDivisorDesign(): Design = {
    val divisor = HdlInt.param("DIVISOR", default = 2, min = 0, max = 3)
    val width = localParam("WIDTH", HdlInt.literal(8) / divisor)
    val packed = packedBits(width)
    val module = moduleDef(
      name = "UnsafeDivisor",
      parameters = Vector(integerParameter(divisor)),
      ports = Vector(port("data_in", Input, packed), port("data_out", Output, packed)),
      items = captureItems {
        emitContinuousAssign("data_out", ref("data_in"))
      },
      localParameters = Vector(integerLocalParameter(width))
    )
    Design(module.name, Vector(module))
  }

  private def conditionalWidthDesign(requestedName: String, wideDefault: Boolean): Design = {
    val activeWidth = IntExpr.LocalParameterRef("ACTIVE_WIDTH")
    val module = ModuleDef(
      name = requestedName,
      parameters = Vector(
        IntegerParameter("NARROW_WIDTH", 4, Vector(MinInclusive(1), MaxInclusive(32))),
        IntegerParameter("WIDE_WIDTH", 12, Vector(MinInclusive(1), MaxInclusive(32)))
      ),
      ports = Vector(
        Port("din", Input, PackedBits(activeWidth, Unsigned)),
        Port("dout", Output, PackedBits(activeWidth, Unsigned))
      ),
      items = Vector(ContinuousAssign(Ref("dout"), Ref("din"))),
      localParameters = Vector(
        IntegerLocalParameter(
          "ACTIVE_WIDTH",
          IntExpr.Select(
            morphhdl.paramrtl.BoolExpr.ParameterRef("WIDE"),
            IntExpr.ParameterRef("WIDE_WIDTH"),
            IntExpr.ParameterRef("NARROW_WIDTH")
          )
        )
      ),
      booleanParameters = Vector(BooleanParameter("WIDE", wideDefault))
    )
    Design(module.name, Vector(module))
  }

  private def boundConditionalWidthWitness(requestedName: String, width: Int): Component =
    new Component {
      setDefinitionName(requestedName)
      val din = in(morphhdl.frontend.Bits(width bits))
      val dout = out(morphhdl.frontend.Bits(width bits))
      val child = new Component {
        setDefinitionName("BoundConditionalWidthChild")
        val child_in = in(morphhdl.frontend.Bits(width bits))
        val child_out = out(morphhdl.frontend.Bits(width bits))
        child_out := child_in
      }
      child.child_in := din
      dout := child.child_out
    }

  private def boundConditionalWidthDesign(
      requestedName: String,
      childWideDefault: Boolean
  ): Design = {
    val childWidth = IntExpr.Select(
      morphhdl.paramrtl.BoolExpr.Literal(childWideDefault),
      IntExpr.ParameterRef("WIDE_WIDTH"),
      IntExpr.Add(IntExpr.ParameterRef("NARROW_WIDTH"), IntExpr.Literal(1))
    )
    val child = ModuleDef(
      name = "BoundConditionalWidthChild",
      parameters = Vector(
        IntegerParameter("NARROW_WIDTH", 2, Vector(MinInclusive(1), MaxInclusive(32))),
        IntegerParameter("WIDE_WIDTH", 3, Vector(MinInclusive(1), MaxInclusive(32)))
      ),
      ports = Vector(
        Port("child_in", Input, PackedBits(childWidth, Unsigned)),
        Port("child_out", Output, PackedBits(childWidth, Unsigned))
      ),
      items = Vector(ContinuousAssign(Ref("child_out"), Ref("child_in")))
    )

    val topWidth = IntExpr.Select(
      morphhdl.paramrtl.BoolExpr.Literal(childWideDefault),
      IntExpr.ParameterRef("TOP_WIDE"),
      IntExpr.Add(IntExpr.LocalParameterRef("BOUND_NARROW"), IntExpr.Literal(1))
    )
    val topPacked = PackedBits(topWidth, Unsigned)
    val top = ModuleDef(
      name = requestedName,
      parameters = Vector(
        IntegerParameter("TOP_NARROW", 5, Vector(MinInclusive(1), MaxInclusive(16))),
        IntegerParameter("ALT_NARROW", 9, Vector(MinInclusive(1), MaxInclusive(16))),
        IntegerParameter("TOP_WIDE", 14, Vector(MinInclusive(1), MaxInclusive(32)))
      ),
      ports = Vector(
        Port("din", Input, topPacked),
        Port("dout", Output, topPacked)
      ),
      items = Vector(
        ModuleItem.ModuleInstance(
          name = "child",
          moduleName = child.name,
          parameterBindings = Vector(
            ParameterBinding("NARROW_WIDTH", IntExpr.LocalParameterRef("BOUND_NARROW")),
            ParameterBinding("WIDE_WIDTH", IntExpr.ParameterRef("TOP_WIDE"))
          ),
          portConnections = Vector(
            PortConnection("child_in", Ref("din")),
            PortConnection("child_out", Ref("dout"))
          )
        )
      ),
      localParameters = Vector(
        IntegerLocalParameter(
          "BOUND_NARROW",
          IntExpr.Select(
            morphhdl.paramrtl.BoolExpr.ParameterRef("USE_ALT_BINDING"),
            IntExpr.ParameterRef("ALT_NARROW"),
            IntExpr.ParameterRef("TOP_NARROW")
          )
        )
      ),
      booleanParameters = Vector(BooleanParameter("USE_ALT_BINDING", default = false))
    )
    Design(top.name, Vector(top, child))
  }

  private def invalidInactiveConditionalWidthDesign(requestedName: String): Design = {
    val activeWidth = IntExpr.LocalParameterRef("ACTIVE_WIDTH")
    val module = ModuleDef(
      name = requestedName,
      parameters = Vector(
        IntegerParameter("DIVISOR", 2, Vector(MinInclusive(0), MaxInclusive(3)))
      ),
      ports = Vector(
        Port("din", Input, PackedBits(activeWidth, Unsigned)),
        Port("dout", Output, PackedBits(activeWidth, Unsigned))
      ),
      items = Vector(ContinuousAssign(Ref("dout"), Ref("din"))),
      localParameters = Vector(
        IntegerLocalParameter(
          "ACTIVE_WIDTH",
          IntExpr.Select(
            morphhdl.paramrtl.BoolExpr.ParameterRef("WIDE"),
            IntExpr.Literal(8),
            IntExpr.Divide(IntExpr.Literal(8), IntExpr.ParameterRef("DIVISOR"))
          )
        )
      ),
      booleanParameters = Vector(BooleanParameter("WIDE", default = true))
    )
    Design(module.name, Vector(module))
  }

  private def witnessWithChild(requestedName: String): Component = {
    val symbolicWidth = HdlInt.param("WIDTH", default = 8, min = 1, max = 64)
    for (_ <- 0 until symbolicWidth) ()
    new Component {
      setDefinitionName(requestedName)
      val data_in = in(morphhdl.frontend.Bits(8 bits))
      val data_out = out(morphhdl.frontend.Bits(8 bits))
      val child = new Component {
        setDefinitionName("WitnessChild")
        val child_in = in(morphhdl.frontend.Bits(8 bits))
        val child_out = out(morphhdl.frontend.Bits(8 bits))
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
      val data_in = in(morphhdl.frontend.Bits(8 bits))
      val data_out = out(morphhdl.frontend.Bits(8 bits))
      val child = new Component {
        setDefinitionName("HierarchyLeaf")
        val leaf_in = in(morphhdl.frontend.Bits(childWidth bits))
        val leaf_out = out(morphhdl.frontend.Bits(childWidth bits))
        leaf_out := leaf_in
      }
      child.leaf_in := data_in.resized
      data_out := child.leaf_out.resized
    }
  }

  private def falseBranchWitness(requestedName: String, copies: Int = 1): Component =
    new Component {
      setDefinitionName(requestedName)
      val din = in(morphhdl.frontend.Bits(8 bits))
      val dout = out(morphhdl.frontend.Bits(8 bits))
      val selected = Vector.fill(copies) {
        new Component {
          setDefinitionName("FalseDefaultLeaf")
          val false_in = in(morphhdl.frontend.Bits(8 bits))
          val false_out = out(morphhdl.frontend.Bits(8 bits))
          false_out := false_in
        }
      }
      selected.foreach(_.false_in := din)
      dout := selected.head.false_out
    }

  private def trueBranchWitness(requestedName: String): Component =
    new Component {
      setDefinitionName(requestedName)
      val din = in(morphhdl.frontend.Bits(8 bits))
      val dout = out(morphhdl.frontend.Bits(8 bits))
      val selected = new Component {
        setDefinitionName("TrueDefaultLeaf")
        val true_in = in(morphhdl.frontend.Bits(8 bits))
        val true_out = out(morphhdl.frontend.Bits(8 bits))
        true_out := true_in
      }
      selected.true_in := din
      dout := selected.true_out
    }

  private def falseDefaultConditionalDesign(requestedName: String): Design = {
    val packed = PackedBits(IntExpr.Literal(8), Unsigned)
    val enabledLeaf = ModuleDef(
      name = "TrueDefaultLeaf",
      parameters = Vector.empty,
      ports = Vector(
        Port("true_in", Input, packed),
        Port("true_out", Output, packed)
      ),
      items = Vector(ContinuousAssign(Ref("true_out"), Ref("true_in")))
    )
    val disabledLeaf = ModuleDef(
      name = "FalseDefaultLeaf",
      parameters = Vector.empty,
      ports = Vector(
        Port("false_in", Input, packed),
        Port("false_out", Output, packed)
      ),
      items = Vector(ContinuousAssign(Ref("false_out"), Ref("false_in")))
    )
    val top = ModuleDef(
      name = requestedName,
      parameters = Vector.empty,
      ports = Vector(Port("din", Input, packed), Port("dout", Output, packed)),
      items = Vector(
        ModuleItem.GenerateIf(
          morphhdl.paramrtl.BoolExpr.ParameterRef("ENABLE"),
          GenerateBlock(
            "g_enabled",
            Vector(
              ModuleItem.ModuleInstance(
                "selected_inst",
                enabledLeaf.name,
                portConnections = Vector(
                  PortConnection("true_in", Ref("din")),
                  PortConnection("true_out", Ref("dout"))
                )
              )
            )
          ),
          GenerateBlock(
            "g_disabled",
            Vector(
              ModuleItem.ModuleInstance(
                "selected_inst",
                disabledLeaf.name,
                portConnections = Vector(
                  PortConnection("false_in", Ref("din")),
                  PortConnection("false_out", Ref("dout"))
                )
              )
            )
          )
        )
      ),
      booleanParameters = Vector(BooleanParameter("ENABLE", default = false))
    )
    Design(requestedName, Vector(top, enabledLeaf, disabledLeaf))
  }

  private def invalidInactiveConditionalDesign(requestedName: String): Design = {
    val packed = PackedBits(IntExpr.Literal(8), Unsigned)
    val enabledLeaf = ModuleDef(
      name = "TrueDefaultLeaf",
      parameters = Vector.empty,
      ports = Vector(
        Port("true_in", Input, packed),
        Port("true_out", Output, packed)
      ),
      items = Vector(ContinuousAssign(Ref("true_out"), Ref("true_in")))
    )
    def selectedInstance(moduleName: String): ModuleItem.ModuleInstance =
      ModuleItem.ModuleInstance(
        "selected_inst",
        moduleName,
        portConnections = Vector(
          PortConnection("true_in", Ref("din")),
          PortConnection("true_out", Ref("dout"))
        )
      )
    val top = ModuleDef(
      name = requestedName,
      parameters = Vector.empty,
      ports = Vector(Port("din", Input, packed), Port("dout", Output, packed)),
      items = Vector(
        ModuleItem.GenerateIf(
          morphhdl.paramrtl.BoolExpr.ParameterRef("ENABLE"),
          GenerateBlock("g_enabled", Vector(selectedInstance(enabledLeaf.name))),
          GenerateBlock("g_disabled", Vector(selectedInstance("MissingInactiveLeaf")))
        )
      ),
      booleanParameters = Vector(BooleanParameter("ENABLE", default = true))
    )
    Design(requestedName, Vector(top, enabledLeaf))
  }

  private def comparedDefaultConditionalDesign(
      requestedName: String,
      selectDefault: BigInt = 8,
      compareThroughLocal: Boolean = false
  ): Design = {
    val packed = PackedBits(IntExpr.Literal(8), Unsigned)
    val enabledLeaf = ModuleDef(
      name = "TrueDefaultLeaf",
      parameters = Vector.empty,
      ports = Vector(
        Port("true_in", Input, packed),
        Port("true_out", Output, packed)
      ),
      items = Vector(ContinuousAssign(Ref("true_out"), Ref("true_in")))
    )
    val disabledLeaf = ModuleDef(
      name = "FalseDefaultLeaf",
      parameters = Vector.empty,
      ports = Vector(
        Port("false_in", Input, packed),
        Port("false_out", Output, packed)
      ),
      items = Vector(ContinuousAssign(Ref("false_out"), Ref("false_in")))
    )
    val selectExpression =
      if (compareThroughLocal) IntExpr.LocalParameterRef("SELECT_LOCAL")
      else IntExpr.ParameterRef("SELECT")
    val top = ModuleDef(
      name = requestedName,
      parameters = Vector(
        IntegerParameter("SELECT", selectDefault, Vector(MinInclusive(0), MaxInclusive(31))),
        IntegerParameter("THRESHOLD", 5, Vector(MinInclusive(0), MaxInclusive(31)))
      ),
      ports = Vector(Port("din", Input, packed), Port("dout", Output, packed)),
      items = Vector(
        ModuleItem.GenerateIf(
          morphhdl.paramrtl.BoolExpr.GreaterThanOrEqual(
            selectExpression,
            IntExpr.ParameterRef("THRESHOLD")
          ),
          GenerateBlock(
            "g_enabled",
            Vector(
              ModuleItem.ModuleInstance(
                "selected_inst",
                enabledLeaf.name,
                portConnections = Vector(
                  PortConnection("true_in", Ref("din")),
                  PortConnection("true_out", Ref("dout"))
                )
              )
            )
          ),
          GenerateBlock(
            "g_disabled",
            Vector(
              ModuleItem.ModuleInstance(
                "selected_inst",
                disabledLeaf.name,
                portConnections = Vector(
                  PortConnection("false_in", Ref("din")),
                  PortConnection("false_out", Ref("dout"))
                )
              )
            )
          )
        )
      ),
      localParameters =
        if (compareThroughLocal)
          Vector(
            IntegerLocalParameter(
              "SELECT_LOCAL",
              IntExpr.Add(IntExpr.ParameterRef("SELECT"), IntExpr.Literal(1))
            )
          )
        else Vector.empty
    )
    Design(requestedName, Vector(top, enabledLeaf, disabledLeaf))
  }

  private def boundComparedConditionalWitness(requestedName: String): Component =
    new Component {
      setDefinitionName(requestedName)
      val din = in(morphhdl.frontend.Bits(8 bits))
      val dout = out(morphhdl.frontend.Bits(8 bits))

      val routedChild = new Component {
        setDefinitionName("BoundComparedChild")
        val child_in = in(morphhdl.frontend.Bits(8 bits))
        val child_out = out(morphhdl.frontend.Bits(8 bits))

        val selected = new Component {
          setDefinitionName("BoundHighLeaf")
          val high_in = in(morphhdl.frontend.Bits(8 bits))
          val high_out = out(morphhdl.frontend.Bits(8 bits))
          high_out := high_in
        }
        selected.high_in := child_in
        child_out := selected.high_out
      }

      routedChild.child_in := din
      dout := routedChild.child_out
    }

  private def boundComparedConditionalDesign(requestedName: String): Design = {
    val packed = PackedBits(IntExpr.Literal(8), Unsigned)
    val highLeaf = ModuleDef(
      name = "BoundHighLeaf",
      parameters = Vector.empty,
      ports = Vector(
        Port("high_in", Input, packed),
        Port("high_out", Output, packed)
      ),
      items = Vector(ContinuousAssign(Ref("high_out"), Ref("high_in")))
    )
    val lowLeaf = ModuleDef(
      name = "BoundLowLeaf",
      parameters = Vector.empty,
      ports = Vector(
        Port("low_in", Input, packed),
        Port("low_out", Output, packed)
      ),
      items = Vector(ContinuousAssign(Ref("low_out"), Ref("low_in")))
    )
    val child = ModuleDef(
      name = "BoundComparedChild",
      parameters = Vector(
        IntegerParameter("ROUTE", 2, Vector(MinInclusive(0), MaxInclusive(31)))
      ),
      ports = Vector(
        Port("child_in", Input, packed),
        Port("child_out", Output, packed)
      ),
      items = Vector(
        ModuleItem.GenerateIf(
          morphhdl.paramrtl.BoolExpr.GreaterThanOrEqual(
            IntExpr.LocalParameterRef("EFFECTIVE_ROUTE"),
            IntExpr.Literal(5)
          ),
          GenerateBlock(
            "g_high",
            Vector(
              ModuleItem.ModuleInstance(
                "selected_inst",
                highLeaf.name,
                portConnections = Vector(
                  PortConnection("high_in", Ref("child_in")),
                  PortConnection("high_out", Ref("child_out"))
                )
              )
            )
          ),
          GenerateBlock(
            "g_low",
            Vector(
              ModuleItem.ModuleInstance(
                "selected_inst",
                lowLeaf.name,
                portConnections = Vector(
                  PortConnection("low_in", Ref("child_in")),
                  PortConnection("low_out", Ref("child_out"))
                )
              )
            )
          )
        )
      ),
      localParameters = Vector(
        IntegerLocalParameter(
          "EFFECTIVE_ROUTE",
          IntExpr.Add(IntExpr.ParameterRef("ROUTE"), IntExpr.Literal(1))
        )
      )
    )
    val top = ModuleDef(
      name = requestedName,
      parameters = Vector(
        IntegerParameter("TOP_ROUTE", 8, Vector(MinInclusive(0), MaxInclusive(31)))
      ),
      ports = Vector(Port("din", Input, packed), Port("dout", Output, packed)),
      items = Vector(
        ModuleItem.ModuleInstance(
          name = "routed_child",
          moduleName = child.name,
          parameterBindings = Vector(
            ParameterBinding("ROUTE", IntExpr.ParameterRef("TOP_ROUTE"))
          ),
          portConnections = Vector(
            PortConnection("child_in", Ref("din")),
            PortConnection("child_out", Ref("dout"))
          )
        )
      )
    )
    Design(requestedName, Vector(top, child, highLeaf, lowLeaf))
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

  private def boundBooleanConditionalWitness(
      requestedName: String,
      selectHigh: Boolean
  ): Component =
    new Component {
      setDefinitionName(requestedName)
      val din = in(morphhdl.frontend.Bits(8 bits))
      val dout = out(morphhdl.frontend.Bits(8 bits))

      val routedChild = new Component {
        setDefinitionName("BoundBooleanChild")
        val child_in = in(morphhdl.frontend.Bits(8 bits))
        val child_out = out(morphhdl.frontend.Bits(8 bits))

        if (selectHigh) {
          val selected = new Component {
            setDefinitionName("BoundBooleanHighLeaf")
            val high_in = in(morphhdl.frontend.Bits(8 bits))
            val high_out = out(morphhdl.frontend.Bits(8 bits))
            high_out := high_in
          }
          selected.high_in := child_in
          child_out := selected.high_out
        } else {
          val selected = new Component {
            setDefinitionName("BoundBooleanLowLeaf")
            val low_in = in(morphhdl.frontend.Bits(8 bits))
            val low_out = out(morphhdl.frontend.Bits(8 bits))
            low_out := low_in
          }
          selected.low_in := child_in
          child_out := selected.low_out
        }
      }

      routedChild.child_in := din
      dout := routedChild.child_out
    }

  private def boundBooleanConditionalDesign(
      requestedName: String,
      enableDefault: Boolean,
      widthDefault: BigInt,
      limitDefault: BigInt,
      childDefault: Boolean,
      bindChild: Boolean
  ): Design = {
    val packed = PackedBits(IntExpr.Literal(8), Unsigned)
    val highLeaf = ModuleDef(
      name = "BoundBooleanHighLeaf",
      parameters = Vector.empty,
      ports = Vector(
        Port("high_in", Input, packed),
        Port("high_out", Output, packed)
      ),
      items = Vector(ContinuousAssign(Ref("high_out"), Ref("high_in")))
    )
    val lowLeaf = ModuleDef(
      name = "BoundBooleanLowLeaf",
      parameters = Vector.empty,
      ports = Vector(
        Port("low_in", Input, packed),
        Port("low_out", Output, packed)
      ),
      items = Vector(ContinuousAssign(Ref("low_out"), Ref("low_in")))
    )
    val child = ModuleDef(
      name = "BoundBooleanChild",
      parameters = Vector.empty,
      ports = Vector(
        Port("child_in", Input, packed),
        Port("child_out", Output, packed)
      ),
      items = Vector(
        ModuleItem.GenerateIf(
          morphhdl.paramrtl.BoolExpr.ParameterRef("SELECT"),
          GenerateBlock(
            "g_high",
            Vector(
              ModuleItem.ModuleInstance(
                "selected_inst",
                highLeaf.name,
                portConnections = Vector(
                  PortConnection("high_in", Ref("child_in")),
                  PortConnection("high_out", Ref("child_out"))
                )
              )
            )
          ),
          GenerateBlock(
            "g_low",
            Vector(
              ModuleItem.ModuleInstance(
                "selected_inst",
                lowLeaf.name,
                portConnections = Vector(
                  PortConnection("low_in", Ref("child_in")),
                  PortConnection("low_out", Ref("child_out"))
                )
              )
            )
          )
        )
      ),
      booleanParameters = Vector(BooleanParameter("SELECT", childDefault))
    )
    val effectiveWidth = IntExpr.LocalParameterRef("EFFECTIVE_WIDTH")
    val binding = morphhdl.paramrtl.BoolExpr.And(
      morphhdl.paramrtl.BoolExpr.ParameterRef("ENABLE"),
      morphhdl.paramrtl.BoolExpr.GreaterThanOrEqual(
        effectiveWidth,
        IntExpr.ParameterRef("LIMIT")
      )
    )
    val top = ModuleDef(
      name = requestedName,
      parameters = Vector(
        IntegerParameter("WIDTH", widthDefault, Vector(MinInclusive(0), MaxInclusive(31))),
        IntegerParameter("LIMIT", limitDefault, Vector(MinInclusive(0), MaxInclusive(31)))
      ),
      ports = Vector(Port("din", Input, packed), Port("dout", Output, packed)),
      items = Vector(
        ModuleItem.ModuleInstance(
          name = "routed_child",
          moduleName = child.name,
          portConnections = Vector(
            PortConnection("child_in", Ref("din")),
            PortConnection("child_out", Ref("dout"))
          ),
          booleanParameterBindings =
            if (bindChild) Vector(BooleanParameterBinding("SELECT", binding))
            else Vector.empty
        )
      ),
      localParameters = Vector(
        IntegerLocalParameter(
          "EFFECTIVE_WIDTH",
          IntExpr.Add(IntExpr.ParameterRef("WIDTH"), IntExpr.Literal(1))
        )
      ),
      booleanParameters = Vector(BooleanParameter("ENABLE", enableDefault))
    )
    Design(requestedName, Vector(top, child, highLeaf, lowLeaf))
  }

  private def booleanLocalConditionalDesign(
      requestedName: String,
      enableDefault: Boolean,
      widthDefault: BigInt,
      limitDefault: BigInt
  ): Design = {
    val packed = PackedBits(IntExpr.Literal(8), Unsigned)
    val highLeaf = ModuleDef(
      "TrueDefaultLeaf",
      Vector.empty,
      Vector(Port("true_in", Input, packed), Port("true_out", Output, packed)),
      Vector(ContinuousAssign(Ref("true_out"), Ref("true_in")))
    )
    val lowLeaf = ModuleDef(
      "FalseDefaultLeaf",
      Vector.empty,
      Vector(Port("false_in", Input, packed), Port("false_out", Output, packed)),
      Vector(ContinuousAssign(Ref("false_out"), Ref("false_in")))
    )
    val top = ModuleDef(
      name = requestedName,
      parameters = Vector(
        IntegerParameter("WIDTH", widthDefault, Vector(MinInclusive(0), MaxInclusive(31))),
        IntegerParameter("LIMIT", limitDefault, Vector(MinInclusive(0), MaxInclusive(31)))
      ),
      ports = Vector(Port("din", Input, packed), Port("dout", Output, packed)),
      items = Vector(
        ModuleItem.GenerateIf(
          morphhdl.paramrtl.BoolExpr.Equal(
            IntExpr.LocalParameterRef("ROUTE_CODE"),
            IntExpr.Literal(1)
          ),
          GenerateBlock(
            "g_high",
            Vector(
              ModuleItem.ModuleInstance(
                "selected_inst",
                highLeaf.name,
                portConnections = Vector(
                  PortConnection("true_in", Ref("din")),
                  PortConnection("true_out", Ref("dout"))
                )
              )
            )
          ),
          GenerateBlock(
            "g_low",
            Vector(
              ModuleItem.ModuleInstance(
                "selected_inst",
                lowLeaf.name,
                portConnections = Vector(
                  PortConnection("false_in", Ref("din")),
                  PortConnection("false_out", Ref("dout"))
                )
              )
            )
          )
        )
      ),
      localParameters = Vector(
        IntegerLocalParameter("EFFECTIVE_WIDTH", IntExpr.ParameterRef("WIDTH")),
        IntegerLocalParameter(
          "ROUTE_CODE",
          IntExpr.Select(
            morphhdl.paramrtl.BoolExpr.LocalParameterRef("ROUTE_HIGH"),
            IntExpr.Literal(1),
            IntExpr.Literal(0)
          )
        )
      ),
      booleanParameters = Vector(BooleanParameter("ENABLE", enableDefault)),
      booleanLocalParameters = Vector(
        BooleanLocalParameter(
          "WIDTH_OK",
          morphhdl.paramrtl.BoolExpr.GreaterThanOrEqual(
            IntExpr.LocalParameterRef("EFFECTIVE_WIDTH"),
            IntExpr.ParameterRef("LIMIT")
          )
        ),
        BooleanLocalParameter(
          "ROUTE_HIGH",
          morphhdl.paramrtl.BoolExpr.And(
            morphhdl.paramrtl.BoolExpr.ParameterRef("ENABLE"),
            morphhdl.paramrtl.BoolExpr.LocalParameterRef("WIDTH_OK")
          )
        )
      )
    )
    Design(requestedName, Vector(top, highLeaf, lowLeaf))
  }

  private def booleanLocalBindingDesign(requestedName: String): Design = {
    val base = boundBooleanConditionalDesign(
      requestedName,
      enableDefault = true,
      widthDefault = 8,
      limitDefault = 8,
      childDefault = false,
      bindChild = false
    )
    val top = base.modules.find(_.name == requestedName).get
    val instance = top.items.collectFirst { case value: ModuleItem.ModuleInstance => value }.get
    val updatedTop = top.copy(
      localParameters = Vector(
        IntegerLocalParameter("EFFECTIVE_WIDTH", IntExpr.ParameterRef("WIDTH")),
        IntegerLocalParameter(
          "ROUTE_CODE",
          IntExpr.Select(
            morphhdl.paramrtl.BoolExpr.LocalParameterRef("ROUTE_HIGH"),
            IntExpr.Literal(1),
            IntExpr.Literal(0)
          )
        )
      ),
      booleanLocalParameters = Vector(
        BooleanLocalParameter(
          "WIDTH_OK",
          morphhdl.paramrtl.BoolExpr.GreaterThanOrEqual(
            IntExpr.LocalParameterRef("EFFECTIVE_WIDTH"),
            IntExpr.ParameterRef("LIMIT")
          )
        ),
        BooleanLocalParameter(
          "ROUTE_HIGH",
          morphhdl.paramrtl.BoolExpr.And(
            morphhdl.paramrtl.BoolExpr.ParameterRef("ENABLE"),
            morphhdl.paramrtl.BoolExpr.LocalParameterRef("WIDTH_OK")
          )
        )
      ),
      items = Vector(
        instance.copy(
          booleanParameterBindings = Vector(
            BooleanParameterBinding(
              "SELECT",
              morphhdl.paramrtl.BoolExpr.Equal(
                IntExpr.LocalParameterRef("ROUTE_CODE"),
                IntExpr.Literal(1)
              )
            )
          )
        )
      )
    )
    base.copy(modules = base.modules.map(module => if (module.name == requestedName) updatedTop else module))
  }

  private def boundChildBooleanLocalDesign(requestedName: String): Design = {
    val packed = PackedBits(IntExpr.Literal(8), Unsigned)
    val highLeaf = ModuleDef(
      "BoundBooleanHighLeaf",
      Vector.empty,
      Vector(Port("high_in", Input, packed), Port("high_out", Output, packed)),
      Vector(ContinuousAssign(Ref("high_out"), Ref("high_in")))
    )
    val lowLeaf = ModuleDef(
      "BoundBooleanLowLeaf",
      Vector.empty,
      Vector(Port("low_in", Input, packed), Port("low_out", Output, packed)),
      Vector(ContinuousAssign(Ref("low_out"), Ref("low_in")))
    )
    val child = ModuleDef(
      name = "BoundBooleanChild",
      parameters = Vector(
        IntegerParameter("WIDTH", 9, Vector(MinInclusive(0), MaxInclusive(31))),
        IntegerParameter("LIMIT", 8, Vector(MinInclusive(0), MaxInclusive(31)))
      ),
      ports = Vector(Port("child_in", Input, packed), Port("child_out", Output, packed)),
      items = Vector(
        ModuleItem.GenerateIf(
          morphhdl.paramrtl.BoolExpr.Equal(
            IntExpr.LocalParameterRef("ROUTE_CODE"),
            IntExpr.Literal(1)
          ),
          GenerateBlock(
            "g_high",
            Vector(
              ModuleItem.ModuleInstance(
                "selected_inst",
                highLeaf.name,
                portConnections = Vector(
                  PortConnection("high_in", Ref("child_in")),
                  PortConnection("high_out", Ref("child_out"))
                )
              )
            )
          ),
          GenerateBlock(
            "g_low",
            Vector(
              ModuleItem.ModuleInstance(
                "selected_inst",
                lowLeaf.name,
                portConnections = Vector(
                  PortConnection("low_in", Ref("child_in")),
                  PortConnection("low_out", Ref("child_out"))
                )
              )
            )
          )
        )
      ),
      localParameters = Vector(
        IntegerLocalParameter("EFFECTIVE_WIDTH", IntExpr.ParameterRef("WIDTH")),
        IntegerLocalParameter(
          "ROUTE_CODE",
          IntExpr.Select(
            morphhdl.paramrtl.BoolExpr.LocalParameterRef("ROUTE_HIGH"),
            IntExpr.Literal(1),
            IntExpr.Literal(0)
          )
        )
      ),
      booleanParameters = Vector(BooleanParameter("ENABLE", default = true)),
      booleanLocalParameters = Vector(
        BooleanLocalParameter(
          "WIDTH_OK",
          morphhdl.paramrtl.BoolExpr.GreaterThanOrEqual(
            IntExpr.LocalParameterRef("EFFECTIVE_WIDTH"),
            IntExpr.ParameterRef("LIMIT")
          )
        ),
        BooleanLocalParameter(
          "ROUTE_HIGH",
          morphhdl.paramrtl.BoolExpr.And(
            morphhdl.paramrtl.BoolExpr.ParameterRef("ENABLE"),
            morphhdl.paramrtl.BoolExpr.LocalParameterRef("WIDTH_OK")
          )
        )
      )
    )
    val top = ModuleDef(
      name = requestedName,
      parameters = Vector(
        IntegerParameter("PARENT_WIDTH", 7, Vector(MinInclusive(0), MaxInclusive(31))),
        IntegerParameter("PARENT_LIMIT", 8, Vector(MinInclusive(0), MaxInclusive(31)))
      ),
      ports = Vector(Port("din", Input, packed), Port("dout", Output, packed)),
      items = Vector(
        ModuleItem.ModuleInstance(
          "routed_child",
          child.name,
          parameterBindings = Vector(
            ParameterBinding("WIDTH", IntExpr.ParameterRef("PARENT_WIDTH")),
            ParameterBinding("LIMIT", IntExpr.ParameterRef("PARENT_LIMIT"))
          ),
          portConnections = Vector(
            PortConnection("child_in", Ref("din")),
            PortConnection("child_out", Ref("dout"))
          ),
          booleanParameterBindings = Vector(
            BooleanParameterBinding(
              "ENABLE",
              morphhdl.paramrtl.BoolExpr.ParameterRef("PARENT_ENABLE")
            )
          )
        )
      ),
      booleanParameters = Vector(BooleanParameter("PARENT_ENABLE", default = true))
    )
    Design(requestedName, Vector(top, child, highLeaf, lowLeaf))
  }

  private def siblingBooleanLocalDesign(requestedName: String): Design = {
    val base = siblingBoundBooleanConditionalDesign(requestedName)
    val child = base.modules.find(_.name == "SiblingBoundBooleanChild").get
    val generate = child.items.collectFirst { case value: ModuleItem.GenerateIf => value }.get
    val updatedChild = child.copy(
      items = Vector(
        generate.copy(condition = morphhdl.paramrtl.BoolExpr.LocalParameterRef("ROUTE_HIGH"))
      ),
      booleanParameters = Vector(BooleanParameter("ENABLE", default = false)),
      booleanLocalParameters = Vector(
        BooleanLocalParameter(
          "ROUTE_HIGH",
          morphhdl.paramrtl.BoolExpr.ParameterRef("ENABLE")
        )
      )
    )
    val top = base.modules.find(_.name == requestedName).get
    val updatedTop = top.copy(
      items = top.items.map {
        case instance: ModuleItem.ModuleInstance =>
          instance.copy(
            booleanParameterBindings =
              instance.booleanParameterBindings.map(binding => binding.copy(parameterName = "ENABLE"))
          )
        case item => item
      }
    )
    base.copy(
      modules = base.modules.map {
        case module if module.name == child.name => updatedChild
        case module if module.name == top.name   => updatedTop
        case module                              => module
      }
    )
  }

  private def invalidInactiveBooleanLocalDesign(requestedName: String): Design = {
    val packed = PackedBits(IntExpr.Literal(8), Unsigned)
    val module = ModuleDef(
      name = requestedName,
      parameters = Vector(
        IntegerParameter("DIVISOR", 1, Vector(MinInclusive(0), MaxInclusive(2)))
      ),
      ports = Vector(Port("data_in", Input, packed), Port("data_out", Output, packed)),
      items = Vector(ContinuousAssign(Ref("data_out"), Ref("data_in"))),
      booleanLocalParameters = Vector(
        BooleanLocalParameter(
          "SAFE",
          morphhdl.paramrtl.BoolExpr.And(
            morphhdl.paramrtl.BoolExpr.Literal(false),
            morphhdl.paramrtl.BoolExpr.GreaterThan(
              IntExpr.Divide(IntExpr.Literal(8), IntExpr.ParameterRef("DIVISOR")),
              IntExpr.Literal(0)
            )
          )
        )
      )
    )
    Design(requestedName, Vector(module))
  }

  private def generateCaseWitness(requestedName: String, selected: Int): Component =
    new Component {
      setDefinitionName(requestedName)
      val din = in(morphhdl.frontend.Bits(8 bits))
      val dout = out(morphhdl.frontend.Bits(8 bits))

      if (selected == 0) {
        val selected_inst = new Component {
          setDefinitionName("GenerateCaseZeroLeaf")
          val zero_in = in(morphhdl.frontend.Bits(8 bits))
          val zero_out = out(morphhdl.frontend.Bits(8 bits))
          zero_out := zero_in
        }
        selected_inst.zero_in := din
        dout := selected_inst.zero_out
      } else if (selected == 1) {
        val selected_inst = new Component {
          setDefinitionName("GenerateCaseOneLeaf")
          val one_in = in(morphhdl.frontend.Bits(8 bits))
          val one_out = out(morphhdl.frontend.Bits(8 bits))
          one_out := one_in
        }
        selected_inst.one_in := din
        dout := selected_inst.one_out
      } else {
        val selected_inst = new Component {
          setDefinitionName("GenerateCaseDefaultLeaf")
          val default_in = in(morphhdl.frontend.Bits(8 bits))
          val default_out = out(morphhdl.frontend.Bits(8 bits))
          default_out := default_in
        }
        selected_inst.default_in := din
        dout := selected_inst.default_out
      }
    }

  private def generateCaseLeaves(): (ModuleDef, ModuleDef, ModuleDef) = {
    val packed = PackedBits(IntExpr.Literal(8), Unsigned)
    val zero = ModuleDef(
      "GenerateCaseZeroLeaf",
      Vector.empty,
      Vector(Port("zero_in", Input, packed), Port("zero_out", Output, packed)),
      Vector(ContinuousAssign(Ref("zero_out"), Ref("zero_in")))
    )
    val one = ModuleDef(
      "GenerateCaseOneLeaf",
      Vector.empty,
      Vector(Port("one_in", Input, packed), Port("one_out", Output, packed)),
      Vector(ContinuousAssign(Ref("one_out"), Ref("one_in")))
    )
    val default = ModuleDef(
      "GenerateCaseDefaultLeaf",
      Vector.empty,
      Vector(
        Port("default_in", Input, packed),
        Port("default_out", Output, packed)
      ),
      Vector(ContinuousAssign(Ref("default_out"), Ref("default_in")))
    )
    (zero, one, default)
  }

  private def generateCaseItem(
      selector: IntExpr,
      zero: ModuleDef,
      one: ModuleDef,
      default: ModuleDef,
      input: String = "din",
      output: String = "dout"
  ): ModuleItem.GenerateCase = {
    def instance(module: ModuleDef, inputPort: String, outputPort: String) =
      ModuleItem.ModuleInstance(
        "selected_inst",
        module.name,
        portConnections = Vector(
          PortConnection(inputPort, Ref(input)),
          PortConnection(outputPort, Ref(output))
        )
      )
    ModuleItem.GenerateCase(
      selector,
      Vector(
        GenerateCaseChoice(
          0,
          GenerateBlock("g_zero", Vector(instance(zero, "zero_in", "zero_out")))
        ),
        GenerateCaseChoice(
          1,
          GenerateBlock("g_one", Vector(instance(one, "one_in", "one_out")))
        )
      ),
      GenerateBlock(
        "g_default",
        Vector(instance(default, "default_in", "default_out"))
      )
    )
  }

  private def generateCaseDesign(
      requestedName: String,
      modeDefault: BigInt,
      selectorThroughLocal: Boolean = false
  ): Design = {
    val packed = PackedBits(IntExpr.Literal(8), Unsigned)
    val (zero, one, default) = generateCaseLeaves()
    val selector =
      if (selectorThroughLocal) IntExpr.LocalParameterRef("SELECTOR")
      else IntExpr.ParameterRef("MODE")
    val top = ModuleDef(
      name = requestedName,
      parameters = Vector(
        IntegerParameter("MODE", modeDefault, Vector(MinInclusive(0), MaxInclusive(7)))
      ),
      ports = Vector(Port("din", Input, packed), Port("dout", Output, packed)),
      items = Vector(generateCaseItem(selector, zero, one, default)),
      localParameters =
        if (selectorThroughLocal)
          Vector(
            IntegerLocalParameter(
              "SELECTOR",
              IntExpr.Select(
                morphhdl.paramrtl.BoolExpr.LocalParameterRef("USE_NEXT"),
                IntExpr.Add(IntExpr.ParameterRef("MODE"), IntExpr.Literal(1)),
                IntExpr.ParameterRef("MODE")
              )
            )
          )
        else Vector.empty,
      booleanParameters =
        if (selectorThroughLocal) Vector(BooleanParameter("ADVANCE", default = true))
        else Vector.empty,
      booleanLocalParameters =
        if (selectorThroughLocal)
          Vector(
            BooleanLocalParameter(
              "USE_NEXT",
              morphhdl.paramrtl.BoolExpr.And(
                morphhdl.paramrtl.BoolExpr.ParameterRef("ADVANCE"),
                morphhdl.paramrtl.BoolExpr.Equal(
                  IntExpr.ParameterRef("MODE"),
                  IntExpr.Literal(1)
                )
              )
            )
          )
        else Vector.empty
    )
    Design(requestedName, Vector(top, zero, one, default))
  }

  private def boundGenerateCaseWitness(requestedName: String, selected: Int): Component =
    new Component {
      setDefinitionName(requestedName)
      val din = in(morphhdl.frontend.Bits(8 bits))
      val dout = out(morphhdl.frontend.Bits(8 bits))
      val routed_child = new Component {
        setDefinitionName("BoundGenerateCaseChild")
        val child_in = in(morphhdl.frontend.Bits(8 bits))
        val child_out = out(morphhdl.frontend.Bits(8 bits))

        if (selected == 0) {
          val selected_inst = new Component {
            setDefinitionName("GenerateCaseZeroLeaf")
            val zero_in = in(morphhdl.frontend.Bits(8 bits))
            val zero_out = out(morphhdl.frontend.Bits(8 bits))
            zero_out := zero_in
          }
          selected_inst.zero_in := child_in
          child_out := selected_inst.zero_out
        } else if (selected == 1) {
          val selected_inst = new Component {
            setDefinitionName("GenerateCaseOneLeaf")
            val one_in = in(morphhdl.frontend.Bits(8 bits))
            val one_out = out(morphhdl.frontend.Bits(8 bits))
            one_out := one_in
          }
          selected_inst.one_in := child_in
          child_out := selected_inst.one_out
        } else {
          val selected_inst = new Component {
            setDefinitionName("GenerateCaseDefaultLeaf")
            val default_in = in(morphhdl.frontend.Bits(8 bits))
            val default_out = out(morphhdl.frontend.Bits(8 bits))
            default_out := default_in
          }
          selected_inst.default_in := child_in
          child_out := selected_inst.default_out
        }
      }
      routed_child.child_in := din
      dout := routed_child.child_out
    }

  private def boundGenerateCaseDesign(requestedName: String): Design = {
    val packed = PackedBits(IntExpr.Literal(8), Unsigned)
    val (zero, one, default) = generateCaseLeaves()
    val child = ModuleDef(
      name = "BoundGenerateCaseChild",
      parameters = Vector(
        IntegerParameter("MODE", 0, Vector(MinInclusive(0), MaxInclusive(7)))
      ),
      ports = Vector(
        Port("child_in", Input, packed),
        Port("child_out", Output, packed)
      ),
      items = Vector(
        generateCaseItem(
          IntExpr.ParameterRef("MODE"),
          zero,
          one,
          default,
          input = "child_in",
          output = "child_out"
        )
      )
    )
    val top = ModuleDef(
      name = requestedName,
      parameters = Vector(
        IntegerParameter("BASE_MODE", 1, Vector(MinInclusive(0), MaxInclusive(6)))
      ),
      ports = Vector(Port("din", Input, packed), Port("dout", Output, packed)),
      items = Vector(
        ModuleItem.ModuleInstance(
          "routed_child",
          child.name,
          parameterBindings = Vector(
            ParameterBinding("MODE", IntExpr.LocalParameterRef("BOUND_MODE"))
          ),
          portConnections = Vector(
            PortConnection("child_in", Ref("din")),
            PortConnection("child_out", Ref("dout"))
          )
        )
      ),
      localParameters = Vector(
        IntegerLocalParameter(
          "BOUND_MODE",
          IntExpr.Add(IntExpr.ParameterRef("BASE_MODE"), IntExpr.Literal(1))
        )
      )
    )
    Design(requestedName, Vector(top, child, zero, one, default))
  }

  private def siblingGenerateCaseWitness(requestedName: String): Component =
    new Component {
      setDefinitionName(requestedName)
      val zero_in = in(morphhdl.frontend.Bits(8 bits))
      val zero_out = out(morphhdl.frontend.Bits(8 bits))
      val one_in = in(morphhdl.frontend.Bits(8 bits))
      val one_out = out(morphhdl.frontend.Bits(8 bits))

      val zero_child = new Component {
        setDefinitionName("SiblingGenerateCaseChildZero")
        val child_in = in(morphhdl.frontend.Bits(8 bits))
        val child_out = out(morphhdl.frontend.Bits(8 bits))
        val selected_inst = new Component {
          setDefinitionName("GenerateCaseZeroLeaf")
          val zero_in = in(morphhdl.frontend.Bits(8 bits))
          val zero_out = out(morphhdl.frontend.Bits(8 bits))
          zero_out := zero_in
        }
        selected_inst.zero_in := child_in
        child_out := selected_inst.zero_out
      }
      val one_child = new Component {
        setDefinitionName("SiblingGenerateCaseChildOne")
        val child_in = in(morphhdl.frontend.Bits(8 bits))
        val child_out = out(morphhdl.frontend.Bits(8 bits))
        val selected_inst = new Component {
          setDefinitionName("GenerateCaseOneLeaf")
          val one_in = in(morphhdl.frontend.Bits(8 bits))
          val one_out = out(morphhdl.frontend.Bits(8 bits))
          one_out := one_in
        }
        selected_inst.one_in := child_in
        child_out := selected_inst.one_out
      }
      zero_child.child_in := zero_in
      zero_out := zero_child.child_out
      one_child.child_in := one_in
      one_out := one_child.child_out
    }

  private def siblingGenerateCaseDesign(requestedName: String): Design = {
    val packed = PackedBits(IntExpr.Literal(8), Unsigned)
    val (zero, one, default) = generateCaseLeaves()
    val child = ModuleDef(
      name = "SiblingGenerateCaseChild",
      parameters = Vector(
        IntegerParameter("MODE", 3, Vector(MinInclusive(0), MaxInclusive(7)))
      ),
      ports = Vector(
        Port("child_in", Input, packed),
        Port("child_out", Output, packed)
      ),
      items = Vector(
        generateCaseItem(
          IntExpr.ParameterRef("MODE"),
          zero,
          one,
          default,
          input = "child_in",
          output = "child_out"
        )
      )
    )
    def instance(name: String, mode: BigInt, input: String, output: String) =
      ModuleItem.ModuleInstance(
        name,
        child.name,
        parameterBindings = Vector(ParameterBinding("MODE", IntExpr.Literal(mode))),
        portConnections = Vector(
          PortConnection("child_in", Ref(input)),
          PortConnection("child_out", Ref(output))
        )
      )
    val top = ModuleDef(
      requestedName,
      Vector.empty,
      Vector(
        Port("zero_in", Input, packed),
        Port("zero_out", Output, packed),
        Port("one_in", Input, packed),
        Port("one_out", Output, packed)
      ),
      Vector(
        instance("zero_child", 0, "zero_in", "zero_out"),
        instance("one_child", 1, "one_in", "one_out")
      )
    )
    Design(requestedName, Vector(top, child, zero, one, default))
  }

  private def invalidInactiveGenerateCaseDesign(requestedName: String): Design = {
    val base = generateCaseDesign(requestedName, modeDefault = 0)
    val top = base.modules.find(_.name == requestedName).get
    val generate = top.items.collectFirst { case value: ModuleItem.GenerateCase => value }.get
    val invalidChoice = generate.choices
      .find(_.value == 1)
      .get
      .copy(
        block = GenerateBlock(
          "g_one",
          Vector(
            ModuleItem.ModuleInstance(
              "selected_inst",
              "MissingInactiveCaseLeaf",
              portConnections = Vector(
                PortConnection("one_in", Ref("din")),
                PortConnection("one_out", Ref("dout"))
              )
            )
          )
        )
      )
    val invalidGenerate = generate.copy(
      choices = generate.choices.map(choice => if (choice.value == 1) invalidChoice else choice)
    )
    val invalidTop = top.copy(items = Vector(invalidGenerate))
    base.copy(modules = base.modules.map(module => if (module.name == requestedName) invalidTop else module))
  }

  private def invalidInactiveBooleanBindingDesign(requestedName: String): Design = {
    val base = boundBooleanConditionalDesign(
      requestedName,
      enableDefault = false,
      widthDefault = 1,
      limitDefault = 8,
      childDefault = false,
      bindChild = false
    )
    val top = base.modules.find(_.name == requestedName).get
    val childInstance = top.items.collectFirst { case value: ModuleItem.ModuleInstance => value }.get
    val invalidBinding = BooleanParameterBinding(
      "SELECT",
      morphhdl.paramrtl.BoolExpr.And(
        morphhdl.paramrtl.BoolExpr.Literal(false),
        morphhdl.paramrtl.BoolExpr.GreaterThan(
          IntExpr.Divide(IntExpr.Literal(8), IntExpr.ParameterRef("DIVISOR")),
          IntExpr.Literal(0)
        )
      )
    )
    val invalidTop = top.copy(
      parameters = top.parameters :+
        IntegerParameter("DIVISOR", 1, Vector(MinInclusive(0), MaxInclusive(2))),
      items = Vector(childInstance.copy(booleanParameterBindings = Vector(invalidBinding)))
    )
    base.copy(modules = base.modules.map(module => if (module.name == requestedName) invalidTop else module))
  }

  private def siblingBoundBooleanConditionalWitness(requestedName: String): Component =
    new Component {
      setDefinitionName(requestedName)
      val high_in = in(morphhdl.frontend.Bits(8 bits))
      val low_in = in(morphhdl.frontend.Bits(8 bits))
      val high_out = out(morphhdl.frontend.Bits(8 bits))
      val low_out = out(morphhdl.frontend.Bits(8 bits))

      val highChild = new Component {
        setDefinitionName("SiblingBoundBooleanChildHigh")
        val child_in = in(morphhdl.frontend.Bits(8 bits))
        val child_out = out(morphhdl.frontend.Bits(8 bits))
        val selected = new Component {
          setDefinitionName("SiblingBoundBooleanHighLeaf")
          val high_in = in(morphhdl.frontend.Bits(8 bits))
          val high_out = out(morphhdl.frontend.Bits(8 bits))
          high_out := high_in
        }
        selected.high_in := child_in
        child_out := selected.high_out
      }
      val lowChild = new Component {
        setDefinitionName("SiblingBoundBooleanChildLow")
        val child_in = in(morphhdl.frontend.Bits(8 bits))
        val child_out = out(morphhdl.frontend.Bits(8 bits))
        val selected = new Component {
          setDefinitionName("SiblingBoundBooleanLowLeaf")
          val low_in = in(morphhdl.frontend.Bits(8 bits))
          val low_out = out(morphhdl.frontend.Bits(8 bits))
          low_out := low_in
        }
        selected.low_in := child_in
        child_out := selected.low_out
      }

      highChild.child_in := high_in
      high_out := highChild.child_out
      lowChild.child_in := low_in
      low_out := lowChild.child_out
    }

  private def siblingBoundBooleanConditionalDesign(requestedName: String): Design = {
    val packed = PackedBits(IntExpr.Literal(8), Unsigned)
    val highLeaf = ModuleDef(
      "SiblingBoundBooleanHighLeaf",
      Vector.empty,
      Vector(Port("high_in", Input, packed), Port("high_out", Output, packed)),
      Vector(ContinuousAssign(Ref("high_out"), Ref("high_in")))
    )
    val lowLeaf = ModuleDef(
      "SiblingBoundBooleanLowLeaf",
      Vector.empty,
      Vector(Port("low_in", Input, packed), Port("low_out", Output, packed)),
      Vector(ContinuousAssign(Ref("low_out"), Ref("low_in")))
    )
    val child = ModuleDef(
      name = "SiblingBoundBooleanChild",
      parameters = Vector.empty,
      ports = Vector(Port("child_in", Input, packed), Port("child_out", Output, packed)),
      items = Vector(
        ModuleItem.GenerateIf(
          morphhdl.paramrtl.BoolExpr.ParameterRef("SELECT"),
          GenerateBlock(
            "g_high",
            Vector(
              ModuleItem.ModuleInstance(
                "selected_inst",
                highLeaf.name,
                portConnections = Vector(
                  PortConnection("high_in", Ref("child_in")),
                  PortConnection("high_out", Ref("child_out"))
                )
              )
            )
          ),
          GenerateBlock(
            "g_low",
            Vector(
              ModuleItem.ModuleInstance(
                "selected_inst",
                lowLeaf.name,
                portConnections = Vector(
                  PortConnection("low_in", Ref("child_in")),
                  PortConnection("low_out", Ref("child_out"))
                )
              )
            )
          )
        )
      ),
      booleanParameters = Vector(BooleanParameter("SELECT", default = false))
    )
    def instance(name: String, input: String, output: String, selected: Boolean) =
      ModuleItem.ModuleInstance(
        name,
        child.name,
        portConnections = Vector(
          PortConnection("child_in", Ref(input)),
          PortConnection("child_out", Ref(output))
        ),
        booleanParameterBindings = Vector(
          BooleanParameterBinding("SELECT", morphhdl.paramrtl.BoolExpr.Literal(selected))
        )
      )
    val top = ModuleDef(
      requestedName,
      Vector.empty,
      Vector(
        Port("high_in", Input, packed),
        Port("low_in", Input, packed),
        Port("high_out", Output, packed),
        Port("low_out", Output, packed)
      ),
      Vector(
        instance("high_child", "high_in", "high_out", selected = true),
        instance("low_child", "low_in", "low_out", selected = false)
      )
    )
    Design(requestedName, Vector(top, child, highLeaf, lowLeaf))
  }

  private def twoHopBoundBooleanConditionalWitness(requestedName: String): Component =
    new Component {
      setDefinitionName(requestedName)
      val din = in(morphhdl.frontend.Bits(8 bits))
      val dout = out(morphhdl.frontend.Bits(8 bits))

      val middle = new Component {
        setDefinitionName("TwoHopBooleanMiddle")
        val mid_in = in(morphhdl.frontend.Bits(8 bits))
        val mid_out = out(morphhdl.frontend.Bits(8 bits))
        val leaf = new Component {
          setDefinitionName("TwoHopBooleanLeaf")
          val leaf_in = in(morphhdl.frontend.Bits(8 bits))
          val leaf_out = out(morphhdl.frontend.Bits(8 bits))
          val selected = new Component {
            setDefinitionName("TwoHopBooleanLowRoute")
            val low_in = in(morphhdl.frontend.Bits(8 bits))
            val low_out = out(morphhdl.frontend.Bits(8 bits))
            low_out := low_in
          }
          selected.low_in := leaf_in
          leaf_out := selected.low_out
        }
        leaf.leaf_in := mid_in
        mid_out := leaf.leaf_out
      }

      middle.mid_in := din
      dout := middle.mid_out
    }

  private def twoHopBoundBooleanConditionalDesign(requestedName: String): Design = {
    val packed = PackedBits(IntExpr.Literal(8), Unsigned)
    val highRoute = ModuleDef(
      "TwoHopBooleanHighRoute",
      Vector.empty,
      Vector(Port("high_in", Input, packed), Port("high_out", Output, packed)),
      Vector(ContinuousAssign(Ref("high_out"), Ref("high_in")))
    )
    val lowRoute = ModuleDef(
      "TwoHopBooleanLowRoute",
      Vector.empty,
      Vector(Port("low_in", Input, packed), Port("low_out", Output, packed)),
      Vector(ContinuousAssign(Ref("low_out"), Ref("low_in")))
    )
    val leaf = ModuleDef(
      name = "TwoHopBooleanLeaf",
      parameters = Vector.empty,
      ports = Vector(Port("leaf_in", Input, packed), Port("leaf_out", Output, packed)),
      items = Vector(
        ModuleItem.GenerateIf(
          morphhdl.paramrtl.BoolExpr.ParameterRef("ENABLE"),
          GenerateBlock(
            "g_high",
            Vector(
              ModuleItem.ModuleInstance(
                "selected_inst",
                highRoute.name,
                portConnections = Vector(
                  PortConnection("high_in", Ref("leaf_in")),
                  PortConnection("high_out", Ref("leaf_out"))
                )
              )
            )
          ),
          GenerateBlock(
            "g_low",
            Vector(
              ModuleItem.ModuleInstance(
                "selected_inst",
                lowRoute.name,
                portConnections = Vector(
                  PortConnection("low_in", Ref("leaf_in")),
                  PortConnection("low_out", Ref("leaf_out"))
                )
              )
            )
          )
        )
      ),
      booleanParameters = Vector(BooleanParameter("ENABLE", default = true))
    )
    val middle = ModuleDef(
      name = "TwoHopBooleanMiddle",
      parameters = Vector.empty,
      ports = Vector(Port("mid_in", Input, packed), Port("mid_out", Output, packed)),
      items = Vector(
        ModuleItem.ModuleInstance(
          "leaf",
          leaf.name,
          portConnections = Vector(
            PortConnection("leaf_in", Ref("mid_in")),
            PortConnection("leaf_out", Ref("mid_out"))
          ),
          booleanParameterBindings = Vector(
            BooleanParameterBinding(
              "ENABLE",
              morphhdl.paramrtl.BoolExpr.ParameterRef("ENABLE")
            )
          )
        )
      ),
      booleanParameters = Vector(BooleanParameter("ENABLE", default = true))
    )
    val top = ModuleDef(
      requestedName,
      Vector.empty,
      Vector(Port("din", Input, packed), Port("dout", Output, packed)),
      Vector(
        ModuleItem.ModuleInstance(
          "middle",
          middle.name,
          portConnections = Vector(
            PortConnection("mid_in", Ref("din")),
            PortConnection("mid_out", Ref("dout"))
          ),
          booleanParameterBindings = Vector(
            BooleanParameterBinding("ENABLE", morphhdl.paramrtl.BoolExpr.Literal(false))
          )
        )
      )
    )
    Design(requestedName, Vector(top, middle, leaf, highRoute, lowRoute))
  }

  private def forwardingWitness(requestedName: String, width: Int): Component =
    new Component {
      setDefinitionName(requestedName)
      val data_in = in(morphhdl.frontend.Bits(width bits))
      val data_out = out(morphhdl.frontend.Bits(width bits))
      val middle = new Component {
        setDefinitionName("ForwardingMiddle")
        val mid_in = in(morphhdl.frontend.Bits(width bits))
        val mid_out = out(morphhdl.frontend.Bits(width bits))
        val leaf = new Component {
          setDefinitionName("ForwardingLeaf")
          val leaf_in = in(morphhdl.frontend.Bits(width bits))
          val leaf_out = out(morphhdl.frontend.Bits(width bits))
          leaf_out := leaf_in
        }
        leaf.leaf_in := mid_in
        mid_out := leaf.leaf_out
      }
      middle.mid_in := data_in
      data_out := middle.mid_out
    }

  private def forwardingDesign(requestedName: String): Design = {
    def parameter(name: String, default: BigInt): IntegerParameter =
      IntegerParameter(name, default, Vector(MinInclusive(1), MaxInclusive(64)))

    def packed(width: IntExpr): PackedBits = PackedBits(width, Unsigned)

    val width = ParameterRef("WIDTH")
    val leaf = ModuleDef(
      name = "ForwardingLeaf",
      parameters = Vector(parameter("WIDTH", 1)),
      ports = Vector(
        Port("leaf_in", Input, packed(width)),
        Port("leaf_out", Output, packed(width))
      ),
      items = Vector(ContinuousAssign(Ref("leaf_out"), Ref("leaf_in")))
    )
    val middle = ModuleDef(
      name = "ForwardingMiddle",
      parameters = Vector(parameter("WIDTH", 2)),
      ports = Vector(
        Port("mid_in", Input, packed(width)),
        Port("mid_out", Output, packed(width))
      ),
      items = Vector(
        ModuleItem.ModuleInstance(
          name = "leaf",
          moduleName = leaf.name,
          parameterBindings = Vector(ParameterBinding("WIDTH", width)),
          portConnections = Vector(
            PortConnection("leaf_in", Ref("mid_in")),
            PortConnection("leaf_out", Ref("mid_out"))
          )
        )
      )
    )
    val top = ModuleDef(
      name = requestedName,
      parameters = Vector(parameter("WIDTH", 32)),
      ports = Vector(
        Port("data_in", Input, packed(width)),
        Port("data_out", Output, packed(width))
      ),
      items = Vector(
        ModuleItem.ModuleInstance(
          name = "middle",
          moduleName = middle.name,
          parameterBindings = Vector(ParameterBinding("WIDTH", width)),
          portConnections = Vector(
            PortConnection("mid_in", Ref("data_in")),
            PortConnection("mid_out", Ref("data_out"))
          )
        )
      )
    )
    Design(requestedName, Vector(top, middle, leaf))
  }

  private def multiBoundWitness(requestedName: String): Component =
    new Component {
      setDefinitionName(requestedName)
      val data_in_8 = in(morphhdl.frontend.Bits(8 bits))
      val data_out_8 = out(morphhdl.frontend.Bits(8 bits))
      val data_in_16 = in(morphhdl.frontend.Bits(16 bits))
      val data_out_16 = out(morphhdl.frontend.Bits(16 bits))

      class BoundLeaf(width: Int) extends Component {
        // Spinal requires distinct concrete definition names for layouts with
        // different widths. The symbolic side intentionally keeps one
        // parameterized MultiBoundLeaf module.
        setDefinitionName(s"MultiBoundLeaf$width")
        val leaf_in = in(morphhdl.frontend.Bits(width bits))
        val leaf_out = out(morphhdl.frontend.Bits(width bits))
        leaf_out := leaf_in
      }

      val leaf8 = new BoundLeaf(8)
      val leaf16 = new BoundLeaf(16)
      leaf8.leaf_in := data_in_8
      data_out_8 := leaf8.leaf_out
      leaf16.leaf_in := data_in_16
      data_out_16 := leaf16.leaf_out
    }

  private def multiBoundDesign(requestedName: String): Design = {
    val childWidth = ParameterRef("WIDTH")
    val child = ModuleDef(
      name = "MultiBoundLeaf",
      parameters = Vector(
        IntegerParameter("WIDTH", 4, Vector(MinInclusive(1), MaxInclusive(32)))
      ),
      ports = Vector(
        Port("leaf_in", Input, PackedBits(childWidth, Unsigned)),
        Port("leaf_out", Output, PackedBits(childWidth, Unsigned))
      ),
      items = Vector(ContinuousAssign(Ref("leaf_out"), Ref("leaf_in")))
    )
    def instance(name: String, width: Int): ModuleItem.ModuleInstance =
      ModuleItem.ModuleInstance(
        name = name,
        moduleName = child.name,
        parameterBindings = Vector(ParameterBinding("WIDTH", IntExpr.Literal(width))),
        portConnections = Vector(
          PortConnection("leaf_in", Ref(s"data_in_$width")),
          PortConnection("leaf_out", Ref(s"data_out_$width"))
        )
      )
    val top = ModuleDef(
      name = requestedName,
      parameters = Vector.empty,
      ports = Vector(
        Port("data_in_8", Input, PackedBits(IntExpr.Literal(8), Unsigned)),
        Port("data_out_8", Output, PackedBits(IntExpr.Literal(8), Unsigned)),
        Port("data_in_16", Input, PackedBits(IntExpr.Literal(16), Unsigned)),
        Port("data_out_16", Output, PackedBits(IntExpr.Literal(16), Unsigned))
      ),
      items = Vector(instance("leaf8", 8), instance("leaf16", 16))
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

  private def invalidRuntimeMuxDesign(): Design = {
    val width = ParameterRef("WIDTH")
    val packed = PackedBits(width, Unsigned)
    val top = ModuleDef(
      name = "RuntimeMux",
      parameters = Vector(
        IntegerParameter(
          "WIDTH",
          default = 8,
          constraints = Vector(MinInclusive(1), MaxInclusive(32))
        )
      ),
      ports = Vector(
        Port("sel", Input, PackedBits(IntExpr.Literal(1), Unsigned)),
        Port("data_false", Input, packed),
        Port("data_true", Input, packed),
        Port("result", Output, packed)
      ),
      items = Vector(
        ModuleItem.CombinationalIf(
          "p_runtime_mux",
          Ref("sel"),
          Vector(ProceduralAssign(Ref("result"), Ref("data_true"))),
          Vector.empty
        )
      )
    )
    Design(top.name, Vector(top))
  }

  private def invalidSynchronousRegisterDesign(): Design = {
    val width = ParameterRef("WIDTH")
    val packed = PackedBits(width, Unsigned)
    val top = ModuleDef(
      name = "SynchronousRegister",
      parameters = Vector(
        IntegerParameter(
          "WIDTH",
          default = 8,
          constraints = Vector(MinInclusive(1), MaxInclusive(32))
        )
      ),
      ports = Vector(
        Port("clk", Input, PackedBits(IntExpr.Literal(1), Unsigned)),
        Port("data_in", Input, packed),
        Port("data_out", Output, packed),
        Port("reset", Input, PackedBits(IntExpr.Literal(1), Unsigned))
      ),
      items = Vector(
        ModuleItem.SynchronousRegister(
          "p_sync_register",
          Ref("missing_clock"),
          Ref("reset"),
          ProceduralAssign(Ref("data_out"), Ref("data_in"))
        )
      )
    )
    Design(top.name, Vector(top))
  }

  private def invalidAsynchronousRegisterDesign(): Design = {
    val width = ParameterRef("WIDTH")
    val packed = PackedBits(width, Unsigned)
    val top = ModuleDef(
      name = "AsynchronousRegister",
      parameters = Vector(
        IntegerParameter(
          "WIDTH",
          default = 8,
          constraints = Vector(MinInclusive(1), MaxInclusive(32))
        )
      ),
      ports = Vector(
        Port("clk", Input, PackedBits(IntExpr.Literal(1), Unsigned)),
        Port("data_in", Input, packed),
        Port("data_out", Output, packed),
        Port("reset", Input, PackedBits(IntExpr.Literal(1), Unsigned))
      ),
      items = Vector(
        ModuleItem.AsynchronousRegister(
          "p_async_register",
          Ref("missing_clock"),
          Ref("reset"),
          ProceduralAssign(Ref("data_out"), Ref("data_in"))
        )
      )
    )
    Design(top.name, Vector(top))
  }

  private def invalidSynchronousEnabledRegisterDesign(): Design = {
    val width = ParameterRef("WIDTH")
    val packed = PackedBits(width, Unsigned)
    val top = ModuleDef(
      name = "SynchronousEnabledRegister",
      parameters = Vector(
        IntegerParameter(
          "WIDTH",
          default = 8,
          constraints = Vector(MinInclusive(1), MaxInclusive(32))
        )
      ),
      ports = Vector(
        Port("clk", Input, PackedBits(IntExpr.Literal(1), Unsigned)),
        Port("data_in", Input, packed),
        Port("data_out", Output, packed),
        Port("enable", Input, PackedBits(IntExpr.Literal(1), Unsigned)),
        Port("reset", Input, PackedBits(IntExpr.Literal(1), Unsigned))
      ),
      items = Vector(
        ModuleItem.SynchronousEnabledRegister(
          "p_sync_enabled_register",
          Ref("missing_clock"),
          Ref("reset"),
          Ref("enable"),
          ProceduralAssign(Ref("data_out"), Ref("data_in"))
        )
      )
    )
    Design(top.name, Vector(top))
  }

  private def invalidAsynchronousEnabledRegisterDesign(): Design = {
    val width = ParameterRef("WIDTH")
    val packed = PackedBits(width, Unsigned)
    val top = ModuleDef(
      name = "AsynchronousEnabledRegister",
      parameters = Vector(
        IntegerParameter(
          "WIDTH",
          default = 8,
          constraints = Vector(MinInclusive(1), MaxInclusive(32))
        )
      ),
      ports = Vector(
        Port("clk", Input, PackedBits(IntExpr.Literal(1), Unsigned)),
        Port("data_in", Input, packed),
        Port("data_out", Output, packed),
        Port("enable", Input, PackedBits(IntExpr.Literal(1), Unsigned)),
        Port("reset", Input, PackedBits(IntExpr.Literal(1), Unsigned))
      ),
      items = Vector(
        ModuleItem.AsynchronousEnabledRegister(
          "p_async_enabled_register",
          Ref("missing_clock"),
          Ref("reset"),
          Ref("enable"),
          ProceduralAssign(Ref("data_out"), Ref("data_in"))
        )
      )
    )
    Design(top.name, Vector(top))
  }

  private def invalidSinglePortMemoryDesign(): Design = {
    val width = ParameterRef("WIDTH")
    val depth = ParameterRef("DEPTH")
    val packed = PackedBits(width, Unsigned)
    val top = ModuleDef(
      name = "SinglePortMemory",
      parameters = Vector(
        IntegerParameter(
          "WIDTH",
          default = 8,
          constraints = Vector(MinInclusive(1), MaxInclusive(32))
        ),
        IntegerParameter(
          "DEPTH",
          default = 5,
          constraints = Vector(MinInclusive(1), MaxInclusive(5))
        )
      ),
      ports = Vector(
        Port("clk", Input, PackedBits(IntExpr.Literal(1), Unsigned)),
        Port("read_enable", Input, PackedBits(IntExpr.Literal(1), Unsigned)),
        Port("write_enable", Input, PackedBits(IntExpr.Literal(1), Unsigned)),
        Port("address", Input, PackedBits(IntExpr.AddressWidth(depth), Unsigned)),
        Port("write_data", Input, packed),
        Port("read_data", Output, packed)
      ),
      items = Vector(
        ModuleItem.SynchronousReadFirstSinglePortMemory(
          label = "p_memory",
          memoryName = "memory",
          clock = Ref("missing_clock"),
          readEnable = Ref("read_enable"),
          writeEnable = Ref("write_enable"),
          address = Ref("address"),
          writeData = Ref("write_data"),
          readData = Ref("read_data"),
          elementType = packed,
          depth = depth
        )
      )
    )
    Design(top.name, Vector(top))
  }

  private def invalidParameterizedCounterDesign(): Design = {
    val limit = ParameterRef("LIMIT")
    val control = PackedBits(IntExpr.Literal(1), Unsigned)
    val top = ModuleDef(
      name = "ParameterizedCounter",
      parameters = Vector(
        IntegerParameter(
          "LIMIT",
          default = 5,
          constraints = Vector(MinInclusive(1), MaxInclusive(8))
        )
      ),
      ports = Vector(
        Port("clk", Input, control),
        Port("reset", Input, control),
        Port("enable", Input, control),
        Port("count", Output, PackedBits(IntExpr.AddressWidth(limit), Unsigned))
      ),
      items = Vector(
        ModuleItem.SynchronousCounter(
          label = "p_counter",
          clock = Ref("missing_clock"),
          reset = Ref("reset"),
          enable = Ref("enable"),
          count = Ref("count"),
          limit = limit
        )
      )
    )
    Design(top.name, Vector(top))
  }

  private def invalidSimpleDualPortMemoryDesign(): Design = {
    val width = ParameterRef("WIDTH")
    val depth = ParameterRef("DEPTH")
    val packed = PackedBits(width, Unsigned)
    val address = PackedBits(IntExpr.AddressWidth(depth), Unsigned)
    val control = PackedBits(IntExpr.Literal(1), Unsigned)
    val top = ModuleDef(
      name = "SimpleDualPortMemory",
      parameters = Vector(
        IntegerParameter(
          "WIDTH",
          default = 8,
          constraints = Vector(MinInclusive(1), MaxInclusive(32))
        ),
        IntegerParameter(
          "DEPTH",
          default = 5,
          constraints = Vector(MinInclusive(1), MaxInclusive(8))
        )
      ),
      ports = Vector(
        Port("clk", Input, control),
        Port("read_enable", Input, control),
        Port("write_enable", Input, control),
        Port("read_address", Input, address),
        Port("write_address", Input, address),
        Port("write_data", Input, packed),
        Port("read_data", Output, packed)
      ),
      items = Vector(
        ModuleItem.SynchronousReadFirstSimpleDualPortMemory(
          label = "p_memory",
          memoryName = "memory",
          clock = Ref("missing_clock"),
          readEnable = Ref("read_enable"),
          writeEnable = Ref("write_enable"),
          readAddress = Ref("read_address"),
          writeAddress = Ref("write_address"),
          writeData = Ref("write_data"),
          readData = Ref("read_data"),
          elementType = packed,
          depth = depth
        )
      )
    )
    Design(top.name, Vector(top))
  }

  private def invalidSynchronousStreamFifoDesign(): Design = {
    val width = ParameterRef("WIDTH")
    val depth = ParameterRef("DEPTH")
    val packed = PackedBits(width, Unsigned)
    val control = PackedBits(IntExpr.Literal(1), Unsigned)
    val top = ModuleDef(
      name = "SynchronousStreamFifo",
      parameters = Vector(
        IntegerParameter(
          "WIDTH",
          default = 8,
          constraints = Vector(MinInclusive(1), MaxInclusive(32))
        ),
        IntegerParameter(
          "DEPTH",
          default = 5,
          constraints = Vector(MinInclusive(1), MaxInclusive(8))
        )
      ),
      ports = Vector(
        Port("clk", Input, control),
        Port("reset", Input, control),
        Port("push_valid", Input, control),
        Port("push_ready", Output, control),
        Port("push_data", Input, packed),
        Port("pop_valid", Output, control),
        Port("pop_ready", Input, control),
        Port("pop_data", Output, packed)
      ),
      items = Vector(
        ModuleItem.SynchronousStreamFifo(
          label = "p_fifo",
          memoryName = "memory",
          clock = Ref("missing_clock"),
          reset = Ref("reset"),
          pushValid = Ref("push_valid"),
          pushReady = Ref("push_ready"),
          pushData = Ref("push_data"),
          popValid = Ref("pop_valid"),
          popReady = Ref("pop_ready"),
          popData = Ref("pop_data"),
          elementType = packed,
          depth = depth
        )
      )
    )
    Design(top.name, Vector(top))
  }

  private def invalidSynchronousStreamM2sPipeDesign(): Design = {
    val width = ParameterRef("WIDTH")
    val packed = PackedBits(width, Unsigned)
    val control = PackedBits(IntExpr.Literal(1), Unsigned)
    val top = ModuleDef(
      name = "SynchronousStreamM2sPipe",
      parameters = Vector(
        IntegerParameter(
          "WIDTH",
          default = 8,
          constraints = Vector(MinInclusive(1), MaxInclusive(32))
        )
      ),
      ports = Vector(
        Port("clk", Input, control),
        Port("reset", Input, control),
        Port("push_valid", Input, control),
        Port("push_ready", Output, control),
        Port("push_data", Input, packed),
        Port("pop_valid", Output, control),
        Port("pop_ready", Input, control),
        Port("pop_data", Output, packed)
      ),
      items = Vector(
        ModuleItem.SynchronousStreamM2sPipe(
          label = "p_m2s_pipe",
          clock = Ref("missing_clock"),
          reset = Ref("reset"),
          pushValid = Ref("push_valid"),
          pushReady = Ref("push_ready"),
          pushData = Ref("push_data"),
          popValid = Ref("pop_valid"),
          popReady = Ref("pop_ready"),
          popData = Ref("pop_data"),
          elementType = packed
        )
      )
    )
    Design(top.name, Vector(top))
  }

  private def addressWidthDesign(
      requestedName: String,
      depthDefault: Int,
      depthMinimum: Int = 1
  ): Design = {
    val width = IntExpr.AddressWidth(IntExpr.ParameterRef("DEPTH"))
    val packed = PackedBits(width, Unsigned)
    val top = ModuleDef(
      name = requestedName,
      parameters = Vector(
        IntegerParameter(
          "DEPTH",
          default = depthDefault,
          constraints = Vector(MinInclusive(depthMinimum), MaxInclusive(5))
        )
      ),
      ports = Vector(
        Port("data_in", Input, packed),
        Port("data_out", Output, packed)
      ),
      items = Vector(ContinuousAssign(Ref("data_out"), Ref("data_in")))
    )
    Design(top.name, Vector(top))
  }

  private def boundAddressWidthWitness(requestedName: String, width: Int): Component =
    new Component {
      setDefinitionName(requestedName)
      val data_in = in(morphhdl.frontend.Bits(width bits))
      val data_out = out(morphhdl.frontend.Bits(width bits))
      val child = new Component {
        setDefinitionName("BoundAddressWidthChild")
        val child_in = in(morphhdl.frontend.Bits(width bits))
        val child_out = out(morphhdl.frontend.Bits(width bits))
        child_out := child_in
      }
      child.child_in := data_in
      data_out := child.child_out
    }

  private def boundAddressWidthDesign(requestedName: String): Design = {
    val childDepth = IntExpr.ParameterRef("DEPTH")
    val childWidth = IntExpr.AddressWidth(childDepth)
    val child = ModuleDef(
      name = "BoundAddressWidthChild",
      parameters = Vector(
        IntegerParameter("DEPTH", 1, Vector(MinInclusive(1), MaxInclusive(5)))
      ),
      ports = Vector(
        Port("child_in", Input, PackedBits(childWidth, Unsigned)),
        Port("child_out", Output, PackedBits(childWidth, Unsigned))
      ),
      items = Vector(ContinuousAssign(Ref("child_out"), Ref("child_in")))
    )

    val boundDepth = IntExpr.LocalParameterRef("BOUND_DEPTH")
    val topWidth = IntExpr.AddressWidth(boundDepth)
    val top = ModuleDef(
      name = requestedName,
      parameters = Vector(
        IntegerParameter("TOP_DEPTH", 2, Vector(MinInclusive(1), MaxInclusive(4)))
      ),
      ports = Vector(
        Port("data_in", Input, PackedBits(topWidth, Unsigned)),
        Port("data_out", Output, PackedBits(topWidth, Unsigned))
      ),
      items = Vector(
        ModuleItem.ModuleInstance(
          name = "child",
          moduleName = child.name,
          parameterBindings = Vector(ParameterBinding("DEPTH", boundDepth)),
          portConnections = Vector(
            PortConnection("child_in", Ref("data_in")),
            PortConnection("child_out", Ref("data_out"))
          )
        )
      ),
      localParameters = Vector(
        IntegerLocalParameter(
          "BOUND_DEPTH",
          IntExpr.Add(IntExpr.ParameterRef("TOP_DEPTH"), IntExpr.Literal(1))
        )
      )
    )
    Design(top.name, Vector(top, child))
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

  private val expectedDerivedWidthVerilog =
    """module DerivedWidth #(
      |  parameter integer DATA_WIDTH = 8,
      |  parameter integer LANES = 4
      |) (
      |  input  wire [PADDED_WIDTH-1:0] din,
      |  output wire [PADDED_WIDTH-1:0] dout
      |);
      |
      |  function integer clog2;
      |    input integer value;
      |    input integer minimum_result;
      |    integer remaining;
      |    begin
      |      clog2 = 0;
      |      for (remaining = value - 1; remaining > 0; remaining = remaining >> 1) begin
      |        clog2 = clog2 + 1;
      |      end
      |      if (clog2 < minimum_result) begin
      |        clog2 = minimum_result;
      |      end
      |    end
      |  endfunction
      |
      |  localparam integer CLAMPED_PADDING = (DATA_WIDTH < 3) ? DATA_WIDTH : 3;
      |  localparam integer LANE_INDEX_WIDTH = clog2(LANES, 0);
      |  localparam integer TOTAL_WIDTH = LANES * DATA_WIDTH;
      |  localparam integer PADDED_WIDTH = ((TOTAL_WIDTH + CLAMPED_PADDING > 4) ? TOTAL_WIDTH + CLAMPED_PADDING : 4) + LANE_INDEX_WIDTH;
      |
      |  assign dout = din;
      |
      |endmodule
      |""".stripMargin

  private val expectedRuntimeMuxVerilog =
    """module RuntimeMux #(
      |  parameter integer WIDTH = 8
      |) (
      |  input  wire [WIDTH-1:0] data_false,
      |  input  wire [WIDTH-1:0] data_true,
      |  output reg [WIDTH-1:0] result,
      |  input  wire [0:0] sel
      |);
      |
      |  always @* begin : p_runtime_mux
      |    if (sel == 1'b1) begin
      |      result = data_true;
      |    end else begin
      |      result = data_false;
      |    end
      |  end
      |
      |endmodule
      |""".stripMargin

  private val expectedSynchronousRegisterVerilog =
    """module SynchronousRegister #(
      |  parameter integer WIDTH = 8
      |) (
      |  input  wire [0:0] clk,
      |  input  wire [WIDTH-1:0] data_in,
      |  output reg [WIDTH-1:0] data_out,
      |  input  wire [0:0] reset
      |);
      |
      |  always @(posedge clk) begin : p_sync_register
      |    if (reset == 1'b1) begin
      |      data_out <= {WIDTH{1'b0}};
      |    end else begin
      |      data_out <= data_in;
      |    end
      |  end
      |
      |endmodule
      |""".stripMargin

  private val expectedAsynchronousRegisterVerilog =
    """module AsynchronousRegister #(
      |  parameter integer WIDTH = 8
      |) (
      |  input  wire [0:0] clk,
      |  input  wire [WIDTH-1:0] data_in,
      |  output reg [WIDTH-1:0] data_out,
      |  input  wire [0:0] reset
      |);
      |
      |  always @(posedge clk or posedge reset) begin : p_async_register
      |    if (reset == 1'b1) begin
      |      data_out <= {WIDTH{1'b0}};
      |    end else begin
      |      data_out <= data_in;
      |    end
      |  end
      |
      |endmodule
      |""".stripMargin

  private val expectedSynchronousEnabledRegisterVerilog =
    """module SynchronousEnabledRegister #(
      |  parameter integer WIDTH = 8
      |) (
      |  input  wire [0:0] clk,
      |  input  wire [WIDTH-1:0] data_in,
      |  output reg [WIDTH-1:0] data_out,
      |  input  wire [0:0] enable,
      |  input  wire [0:0] reset
      |);
      |
      |  always @(posedge clk) begin : p_sync_enabled_register
      |    if (reset == 1'b1) begin
      |      data_out <= {WIDTH{1'b0}};
      |    end else if (enable == 1'b1) begin
      |      data_out <= data_in;
      |    end
      |  end
      |
      |endmodule
      |""".stripMargin

  private val expectedAsynchronousEnabledRegisterVerilog =
    """module AsynchronousEnabledRegister #(
      |  parameter integer WIDTH = 8
      |) (
      |  input  wire [0:0] clk,
      |  input  wire [WIDTH-1:0] data_in,
      |  output reg [WIDTH-1:0] data_out,
      |  input  wire [0:0] enable,
      |  input  wire [0:0] reset
      |);
      |
      |  always @(posedge clk or posedge reset) begin : p_async_enabled_register
      |    if (reset == 1'b1) begin
      |      data_out <= {WIDTH{1'b0}};
      |    end else if (enable == 1'b1) begin
      |      data_out <= data_in;
      |    end
      |  end
      |
      |endmodule
      |""".stripMargin

  private val expectedSinglePortMemoryVerilog =
    """module SinglePortMemory #(
      |  parameter integer DEPTH = 5,
      |  parameter integer WIDTH = 8
      |) (
      |  input  wire [(clog2(DEPTH, 1))-1:0] address,
      |  input  wire [0:0] clk,
      |  output reg [WIDTH-1:0] read_data,
      |  input  wire [0:0] read_enable,
      |  input  wire [WIDTH-1:0] write_data,
      |  input  wire [0:0] write_enable
      |);
      |
      |  function integer clog2;
      |    input integer value;
      |    input integer minimum_result;
      |    integer remaining;
      |    begin
      |      clog2 = 0;
      |      for (remaining = value - 1; remaining > 0; remaining = remaining >> 1) begin
      |        clog2 = clog2 + 1;
      |      end
      |      if (clog2 < minimum_result) begin
      |        clog2 = minimum_result;
      |      end
      |    end
      |  endfunction
      |
      |  reg [WIDTH-1:0] memory [0:DEPTH-1];
      |
      |  always @(posedge clk) begin : p_memory
      |    if (address < DEPTH) begin
      |      if (read_enable == 1'b1) begin
      |        read_data <= memory[address];
      |      end
      |      if (write_enable == 1'b1) begin
      |        memory[address] <= write_data;
      |      end
      |    end else if (read_enable == 1'b1) begin
      |      read_data <= {WIDTH{1'b0}};
      |    end
      |  end
      |
      |endmodule
      |""".stripMargin

  private val expectedParameterizedCounterVerilog =
    """module ParameterizedCounter #(
      |  parameter integer LIMIT = 5
      |) (
      |  input  wire [0:0] clk,
      |  output reg [(clog2(LIMIT, 1))-1:0] count,
      |  input  wire [0:0] enable,
      |  input  wire [0:0] reset
      |);
      |
      |  function integer clog2;
      |    input integer value;
      |    input integer minimum_result;
      |    integer remaining;
      |    begin
      |      clog2 = 0;
      |      for (remaining = value - 1; remaining > 0; remaining = remaining >> 1) begin
      |        clog2 = clog2 + 1;
      |      end
      |      if (clog2 < minimum_result) begin
      |        clog2 = minimum_result;
      |      end
      |    end
      |  endfunction
      |
      |  always @(posedge clk) begin : p_counter
      |    if (reset == 1'b1) begin
      |      count <= {clog2(LIMIT, 1){1'b0}};
      |    end else if (enable == 1'b1) begin
      |      if (count == LIMIT - 1) begin
      |        count <= {clog2(LIMIT, 1){1'b0}};
      |      end else begin
      |        count <= count + 1'b1;
      |      end
      |    end
      |  end
      |
      |endmodule
      |""".stripMargin

  private val expectedSimpleDualPortMemoryVerilog =
    """module SimpleDualPortMemory #(
      |  parameter integer DEPTH = 5,
      |  parameter integer WIDTH = 8
      |) (
      |  input  wire [0:0] clk,
      |  input  wire [(clog2(DEPTH, 1))-1:0] read_address,
      |  output reg [WIDTH-1:0] read_data,
      |  input  wire [0:0] read_enable,
      |  input  wire [(clog2(DEPTH, 1))-1:0] write_address,
      |  input  wire [WIDTH-1:0] write_data,
      |  input  wire [0:0] write_enable
      |);
      |
      |  function integer clog2;
      |    input integer value;
      |    input integer minimum_result;
      |    integer remaining;
      |    begin
      |      clog2 = 0;
      |      for (remaining = value - 1; remaining > 0; remaining = remaining >> 1) begin
      |        clog2 = clog2 + 1;
      |      end
      |      if (clog2 < minimum_result) begin
      |        clog2 = minimum_result;
      |      end
      |    end
      |  endfunction
      |
      |  reg [WIDTH-1:0] memory [0:DEPTH-1];
      |
      |  always @(posedge clk) begin : p_memory
      |    if (read_address < DEPTH) begin
      |      if (read_enable == 1'b1) begin
      |        read_data <= memory[read_address];
      |      end
      |    end else if (read_enable == 1'b1) begin
      |      read_data <= {WIDTH{1'b0}};
      |    end
      |    if (write_address < DEPTH) begin
      |      if (write_enable == 1'b1) begin
      |        memory[write_address] <= write_data;
      |      end
      |    end
      |  end
      |
      |endmodule
      |""".stripMargin

  private val expectedSynchronousStreamFifoVerilog =
    """module SynchronousStreamFifo #(
      |  parameter integer DEPTH = 5,
      |  parameter integer WIDTH = 8
      |) (
      |  input  wire [0:0] clk,
      |  output reg [WIDTH-1:0] pop_data,
      |  input  wire [0:0] pop_ready,
      |  output reg [0:0] pop_valid,
      |  input  wire [WIDTH-1:0] push_data,
      |  output wire [0:0] push_ready,
      |  input  wire [0:0] push_valid,
      |  input  wire [0:0] reset
      |);
      |
      |  function integer clog2;
      |    input integer value;
      |    input integer minimum_result;
      |    integer remaining;
      |    begin
      |      clog2 = 0;
      |      for (remaining = value - 1; remaining > 0; remaining = remaining >> 1) begin
      |        clog2 = clog2 + 1;
      |      end
      |      if (clog2 < minimum_result) begin
      |        clog2 = minimum_result;
      |      end
      |    end
      |  endfunction
      |
      |  localparam integer POINTER_WIDTH = clog2(DEPTH, 1);
      |  localparam integer OCCUPANCY_WIDTH = clog2(DEPTH + 1, 1);
      |
      |  reg [WIDTH-1:0] memory [0:DEPTH-1];
      |  reg [POINTER_WIDTH-1:0] read_pointer;
      |  reg [POINTER_WIDTH-1:0] write_pointer;
      |  reg [OCCUPANCY_WIDTH-1:0] occupancy;
      |  wire push_fire;
      |  wire pop_fire;
      |
      |  assign push_ready = occupancy < DEPTH;
      |  assign push_fire = push_valid && push_ready;
      |  assign pop_fire = pop_valid && pop_ready;
      |
      |  always @(posedge clk) begin : p_fifo
      |    if (reset == 1'b1) begin
      |      read_pointer <= {POINTER_WIDTH{1'b0}};
      |      write_pointer <= {POINTER_WIDTH{1'b0}};
      |      occupancy <= {OCCUPANCY_WIDTH{1'b0}};
      |      pop_valid <= 1'b0;
      |    end else begin
      |      if ((pop_valid == 1'b0 && occupancy > 0) || (pop_fire == 1'b1 && occupancy > 1)) begin
      |        pop_data <= memory[read_pointer];
      |        pop_valid <= 1'b1;
      |        if (read_pointer == DEPTH - 1) begin
      |          read_pointer <= {POINTER_WIDTH{1'b0}};
      |        end else begin
      |          read_pointer <= read_pointer + 1'b1;
      |        end
      |      end else if (pop_fire == 1'b1) begin
      |        pop_valid <= 1'b0;
      |      end
      |      if (push_fire == 1'b1) begin
      |        memory[write_pointer] <= push_data;
      |        if (write_pointer == DEPTH - 1) begin
      |          write_pointer <= {POINTER_WIDTH{1'b0}};
      |        end else begin
      |          write_pointer <= write_pointer + 1'b1;
      |        end
      |      end
      |      if (push_fire != pop_fire) begin
      |        if (push_fire == 1'b1) begin
      |          occupancy <= occupancy + 1'b1;
      |        end else begin
      |          occupancy <= occupancy - 1'b1;
      |        end
      |      end
      |    end
      |  end
      |
      |endmodule
      |""".stripMargin

  private val expectedSynchronousStreamM2sPipeVerilog =
    """module SynchronousStreamM2sPipe #(
      |  parameter integer WIDTH = 8
      |) (
      |  input  wire [0:0] clk,
      |  output reg [WIDTH-1:0] pop_data,
      |  input  wire [0:0] pop_ready,
      |  output reg [0:0] pop_valid,
      |  input  wire [WIDTH-1:0] push_data,
      |  output wire [0:0] push_ready,
      |  input  wire [0:0] push_valid,
      |  input  wire [0:0] reset
      |);
      |
      |  assign push_ready = pop_ready || !pop_valid;
      |
      |  always @(posedge clk) begin : p_m2s_pipe
      |    if (reset == 1'b1) begin
      |      pop_valid <= 1'b0;
      |    end else if (push_ready == 1'b1) begin
      |      pop_valid <= push_valid;
      |    end
      |    if (push_ready == 1'b1) begin
      |      pop_data <= push_data;
      |    end
      |  end
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
