package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import org.scalatest.funsuite.AnyFunSuite
import spinal.core.SpinalConfig

class Increment40GoldenDiagnostic extends AnyFunSuite {
  test("print generated symbolic data-shape contract") {
    val directory = Files.createTempDirectory("increment-40-golden-diagnostic-")
    val config = SpinalConfig(targetDirectory = directory.toString)
    config.netlistFileName = "symbolic_data_shapes.v"
    MorphVerilog(config) {
      SymbolicDataShapesContractFixture.component(reverseConstructionOrder = false)
    }
    val actual = new String(
      Files.readAllBytes(directory.resolve("symbolic_data_shapes.v")),
      StandardCharsets.UTF_8
    )
    println("@@ACTUAL_BEGIN@@")
    println(actual)
    println("@@ACTUAL_END@@")
  }
}
