package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class WireOptimizationDefaultOffTests extends AnyFunSuite {
  test("publication and deprecated convenience API disable all optional wire passes by default") {
    val original = SpinalConfig()
    val default = MorphWireAssignmentPasses(original)
    val disabled = MorphWireAssignmentPasses(original, enabled = false)
    val explicitLegacy = MorphWireAssignmentPasses(original, enabled = true)
    assert(default.phasesInserters == disabled.phasesInserters)
    assert(default.scopeProperties == disabled.scopeProperties)
    assert(default.phasesInserters != explicitLegacy.phasesInserters)
    assert(MorphWireAssignmentPasses.forPublication(original).phasesInserters == disabled.phasesInserters)
    assert(MorphWireAssignmentPasses.forPublication(explicitLegacy) eq explicitLegacy)
    assert(MorphWireAssignmentPasses.forPublication(default.copy()).phasesInserters == disabled.phasesInserters)
    assert(MorphWireAssignmentPasses(explicitLegacy).phasesInserters == disabled.phasesInserters)
    assert(original.phasesInserters.isEmpty)
  }

  test("ordinary MorphVerilog emits the same RTL as explicitly disabling the legacy pipeline") {
    def emit(explicitDisable: Boolean): String = {
      val directory = Files.createTempDirectory("wire-default-off-")
      val base = SpinalConfig(targetDirectory = directory.toString, oneFilePerComponent = true,
        headerWithDate = false, headerWithRepoHash = true)
      val config = if (explicitDisable) MorphWireAssignmentPasses(base, enabled = false) else base
      val width = morphhdl.frontend.HdlInt.param("WIDTH", 8, 4, 16)
      MorphVerilog(config) {
        new Component {
          setDefinitionName("WireDefaultOffFixture")
          val a, b = in UInt(width bits)
          val result = out UInt(width bits)
          val intermediate = UInt(width bits)
          intermediate := a ^ b
          result := intermediate
        }
      }
      new String(Files.readAllBytes(directory.resolve("WireDefaultOffFixture.v")), StandardCharsets.UTF_8)
    }
    val default = emit(false)
    assert(default == emit(true))
    assert(default.contains("parameter integer WIDTH"), default)
  }
}
