package spinal.core.internals

import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class SpinalVerilogPhasePlanTests extends AnyFunSuite {
  private val expectedIds = Vector(
    "PhaseCheckIoBundle",
    "PhaseCheckHierarchy",
    "PhaseInferWidth",
    "PhaseCheck_noLatchNoOverride",
    "PhaseCheck_noRegisterAsLatch",
    "PhaseCheckCombinationalLoops",
    "PhaseCheckCrossClock",
    "PhaseContext.checkGlobalData"
  )

  test("SpinalVerilog exposes the shared inherited validation inventory") {
    withTemporaryDirectory { directory =>
      val report = generate(SpinalConfig(targetDirectory = directory.toString))

      assert(report.expectedInheritedValidationPhaseIds == expectedIds)
      assert(report.inheritedValidationPhaseIds == expectedIds)
      assert(report.inheritedValidationPhaseIds.distinct == report.inheritedValidationPhaseIds)
      assert(Files.isRegularFile(directory.resolve("PhasePlanProbe.v")))
    }
  }

  test("the shared plan preserves transformation and inserter hooks") {
    withTemporaryDirectory { directory =>
      var transformationRuns = 0
      var insertedRuns = 0
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.transformationPhases += new PhaseMisc {
        override def impl(pc: PhaseContext): Unit = transformationRuns += 1
      }
      config.phasesInserters += { phases =>
        phases += new PhaseCheck {
          override def impl(pc: PhaseContext): Unit = insertedRuns += 1
        }
      }

      val report = generate(config)

      assert(transformationRuns == 1)
      assert(insertedRuns == 1)
      assert(report.inheritedValidationPhaseIds == expectedIds)
      assert(report.inheritedValidationPhaseIds.last == "PhaseContext.checkGlobalData")
    }
  }

  test("the historic global-data finalizer rejects state leaked by an inserted phase") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.debugComponents += classOf[Component]
      config.phasesInserters += { phases =>
        phases += new PhaseMisc {
          override def impl(pc: PhaseContext): Unit = {
            DslScopeStack.set(pc.topLevel.dslBody)
          }
        }
      }

      val failure = intercept[SpinalExit] {
        generate(config)
      }

      assert(failure.getMessage.contains("dslScope stack is not empty"))
    }
  }

  test("the report makes an inherited phase removed by an inserter visible") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.phasesInserters += { phases =>
        val index = phases.indexWhere(_.getClass.getSimpleName == "PhaseCheckHierarchy")
        assert(index >= 0)
        phases.remove(index)
      }

      val report = generate(config)

      assert(report.expectedInheritedValidationPhaseIds == expectedIds)
      assert(report.inheritedValidationPhaseIds == expectedIds.filterNot(_ == "PhaseCheckHierarchy"))
    }
  }

  test("the report makes a fresh duplicate inherited phase visible") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.phasesInserters += { phases =>
        phases += new PhaseCheckHierarchy()
      }

      val report = generate(config)

      assert(report.expectedInheritedValidationPhaseIds == expectedIds)
      assert(
        report.inheritedValidationPhaseIds ==
          expectedIds.dropRight(1) ++ Vector("PhaseCheckHierarchy", expectedIds.last)
      )
    }
  }

  private def generate(config: SpinalConfig): SpinalReport[Component] =
    SpinalVerilog(config) {
      new Component {
        setDefinitionName("PhasePlanProbe")
        val input = in(Bool())
        val output = out(Bool())
        output := input
      }
    }

  private def withTemporaryDirectory[A](body: Path => A): A = {
    val directory = Files.createTempDirectory("morphhdl-phase-plan-test-")
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
