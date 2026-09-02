package spinal.core.internals

import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

/** Compatibility name retained for existing focused-test commands. Inventory
  * capture itself now uses only the baseline SpinalConfig phase-inserter API.
  */
class SpinalVerilogPhasePlanTests extends AnyFunSuite {
  private val expectedIds = ValidationPhaseInventory.expectedIds

  test("the baseline phase inserter exposes the inherited validation inventory") {
    withTemporaryDirectory { directory =>
      val (report, observed) = generateAndObserve(
        SpinalConfig(targetDirectory = directory.toString)
      )

      assert(observed == expectedIds)
      assert(observed.distinct == observed)
      assert(report.toplevelName == "PhasePlanProbe")
      assert(Files.isRegularFile(directory.resolve("PhasePlanProbe.v")))
    }
  }

  test("the baseline flow preserves transformation and inserter hooks") {
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

      val (_, observed) = generateAndObserve(config)

      assert(transformationRuns == 1)
      assert(insertedRuns == 1)
      assert(observed == expectedIds)
      assert(observed.last == "PhaseContext.checkGlobalData")
    }
  }

  test("the baseline global-data finalizer rejects state leaked by an inserted phase") {
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
        generateAndObserve(config)
      }

      assert(failure.getMessage.contains("dslScope stack is not empty"))
    }
  }

  test("the external inventory makes an inherited phase removed by an inserter visible") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.phasesInserters += { phases =>
        val index = phases.indexWhere(_.getClass == classOf[PhaseCheckHierarchy])
        assert(index >= 0)
        phases.remove(index)
      }

      val (_, observed) = generateAndObserve(config)

      assert(observed == expectedIds.filterNot(_ == "PhaseCheckHierarchy"))
    }
  }

  test("the external inventory makes a fresh duplicate inherited phase visible") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.phasesInserters += { phases =>
        phases += new PhaseCheckHierarchy()
      }

      val (_, observed) = generateAndObserve(config)

      assert(
        observed == expectedIds.dropRight(1) ++
          Vector("PhaseCheckHierarchy", expectedIds.last)
      )
    }
  }

  private def generateAndObserve(
      config: SpinalConfig
  ): (SpinalReport[_ <: Component], Vector[String]) = {
    var observed: Vector[String] = null
    config.phasesInserters += { phases =>
      observed = ValidationPhaseInventory.idsOf(phases)
    }
    val report = SpinalVerilog(config) {
      new Component {
        setDefinitionName("PhasePlanProbe")
        val input = in(Bool())
        val output = out(Bool())
        output := input
      }
    }
    if (observed == null) {
      throw new IllegalStateException("baseline phase inserter did not run")
    }
    report -> observed
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
