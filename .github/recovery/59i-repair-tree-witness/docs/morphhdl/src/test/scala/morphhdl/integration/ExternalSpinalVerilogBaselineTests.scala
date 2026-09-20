package morphhdl.integration

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicInteger

import scala.collection.JavaConverters._
import scala.collection.mutable

import org.scalatest.funsuite.AnyFunSuite

import spinal.core._
import spinal.core.internals.{PhaseContext, PhaseMisc}
import morphhdl.runtime.ParameterizedVerilogMode

class ExternalBoundaryPassThrough extends Component {
  val io = new Bundle {
    val input = in UInt (8 bits)
    val output = out UInt (8 bits)
  }
  io.output := (io.input + 1).resized
}

class ExternalBoundaryChild extends Component {
  val io = new Bundle {
    val input = in UInt (4 bits)
    val output = out UInt (4 bits)
  }
  io.output := io.input
}

class ExternalBoundaryHierarchy extends Component {
  val io = new Bundle {
    val input = in UInt (4 bits)
    val output = out UInt (4 bits)
  }
  val child = new ExternalBoundaryChild
  child.io.input := io.input
  io.output := child.io.output
}

class ExternalBoundaryLoop extends Component {
  val io = new Bundle {
    val seed = in Bool ()
    val result = out Bool ()
  }
  val a = Bool()
  val b = Bool()
  a := b ^ io.seed
  b := !a
  io.result := b
}

final class CountingPhase(counter: AtomicInteger) extends PhaseMisc {
  override def impl(pc: PhaseContext): Unit = {
    if (pc.topLevel == null) {
      throw new IllegalStateException("counting phase ran without a top-level component")
    }
    counter.incrementAndGet()
  }
}

class ExternalSpinalVerilogBaselineTests extends AnyFunSuite {
  private def withTemporaryDirectory(testBody: Path => Unit): Unit = {
    val directory = Files.createTempDirectory("morphhdl-external-boundary-")
    try testBody(directory)
    finally {
      if (Files.exists(directory)) {
        val stream = Files.walk(directory)
        try {
          stream.iterator().asScala.toVector
            .sortBy(_.getNameCount)
            .reverse
            .foreach(path => Files.deleteIfExists(path))
        }
        finally stream.close()
      }
    }
  }

  private def read(path: String): String =
    new String(Files.readAllBytes(java.nio.file.Paths.get(path)), StandardCharsets.UTF_8)

  test("external boundary performs native elaboration, graph inspection and Verilog publication") {
    withTemporaryDirectory { directory =>
      val report = ExternalSpinalVerilog(
        SpinalConfig(targetDirectory = directory.toString)
      ) {
        new ExternalBoundaryHierarchy
      }

      assert(report.nativeReport.toplevelName == report.inspection.top.definitionName)
      assert(report.inspection.components.size == 2)
      assert(report.inspection.top.children.size == 1)
      assert(report.inspection.top.ports.map(_.direction).toSet == Set("input", "output"))
      assert(report.inspection.top.ports.forall(_.width == 4))
      assert(report.generatedSourcesPaths.size == 1)
      assert(
        report.inheritedValidationPhaseIds ==
          ExternalSpinalVerilog.expectedInheritedValidationPhaseIds
      )
      assert(
        report.expectedInheritedValidationPhaseIds ==
          ExternalSpinalVerilog.expectedInheritedValidationPhaseIds
      )

      val verilog = read(report.generatedSourcesPaths.head)
      assert(verilog.contains(s"module ${report.nativeReport.toplevelName}"))
      assert(verilog.contains("ExternalBoundaryChild"))

      val phaseNames = report.phaseClassNames
      val hierarchyCheck = phaseNames.indexWhere(_.endsWith(".PhaseCheckHierarchy"))
      val latchCheck = phaseNames.indexWhere(_.endsWith(".PhaseCheck_noLatchNoOverride"))
      val loopCheck = phaseNames.indexWhere(_.endsWith(".PhaseCheckCombinationalLoops"))
      val publication = phaseNames.indexWhere(_.endsWith(".PhaseVerilog"))
      val inspection = phaseNames.indexWhere(_.contains("ExternalSpinalVerilog$CapturePhase"))
      assert(hierarchyCheck >= 0)
      assert(latchCheck > hierarchyCheck)
      assert(loopCheck > latchCheck)
      assert(publication > loopCheck)
      assert(inspection > publication)
      assert(inspection == phaseNames.size - 1)
    }
  }

  test("parameterized mode clones baseline flags without changing the caller config") {
    val config = SpinalConfig()
    config.flags += GenerationFlags.formal

    val enabled = ParameterizedVerilogMode.enable(config)
    assert(!(enabled.flags eq config.flags))
    assert(enabled.flags.contains(GenerationFlags.formal))
    assert(ParameterizedVerilogMode.isEnabled(enabled))
    assert(!ParameterizedVerilogMode.isEnabled(config))

    val disabled = ParameterizedVerilogMode.disable(enabled)
    assert(!(disabled.flags eq enabled.flags))
    assert(disabled.flags.contains(GenerationFlags.formal))
    assert(!ParameterizedVerilogMode.isEnabled(disabled))
    assert(ParameterizedVerilogMode.isEnabled(enabled))
  }

  test("external boundary preserves configured transformation phases and phase inserters") {
    withTemporaryDirectory { directory =>
      val transformationCount = new AtomicInteger(0)
      val insertedCount = new AtomicInteger(0)
      val inspectorCount = new AtomicInteger(0)
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.transformationPhases += new CountingPhase(transformationCount)
      config.phasesInserters += { phases =>
        phases += new CountingPhase(insertedCount)
      }
      val originalTransformations = config.transformationPhases.size
      val originalInserters = config.phasesInserters.size

      val report = ExternalSpinalVerilog.inspect(config) {
        new ExternalBoundaryPassThrough
      } { top =>
        inspectorCount.incrementAndGet()
        NativeGraphSnapshot.capture(top)
      }

      assert(transformationCount.get() == 1)
      assert(insertedCount.get() == 1)
      assert(inspectorCount.get() == 1)
      assert(config.transformationPhases.size == originalTransformations)
      assert(config.phasesInserters.size == originalInserters)
      assert(report.phaseClassNames.count(_.contains("CountingPhase")) == 2)
    }
  }

  test("external boundary does not bypass inherited native validation") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(
        targetDirectory = directory.toString,
        debugComponents = mutable.HashSet[Class[_]](classOf[ExternalBoundaryLoop])
      )
      val error = intercept[Throwable] {
        ExternalSpinalVerilog(config) {
          new ExternalBoundaryLoop
        }
      }
      val rendered = Option(error.getMessage).getOrElse("") + "\n" + error.toString
      assert(
        rendered.toLowerCase.contains("combinational") ||
          rendered.toLowerCase.contains("loop") ||
          rendered.toLowerCase.contains("phasecheckcombinationalloops")
      )
    }
  }
}
