package spinal.core

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._

import morphhdl.{MorphVerilog, MorphWireAssignmentPasses}
import morphhdl.frontend.HdlInt
import org.scalatest.funsuite.AnyFunSuite

private object MorphVerilogExpressionInliningFixture {
  final class FixedUnsignedExtendedSum(witnessWidth: HdlInt) extends Component {
    setDefinitionName("FixedUnsignedExtendedSum")
    val hActive, hFrontPorch, hSyncWidth, hBackPorch = in UInt (16 bits)
    val hTotal = out UInt (18 bits)
    val parameterWitnessIn = in UInt (witnessWidth bits)
    val parameterWitnessOut = out UInt (witnessWidth bits)
    hTotal := (((hActive.resize(18) + hFrontPorch.resize(18)) +
      hSyncWidth.resize(18)) + hBackPorch.resize(18))
    parameterWitnessOut := parameterWitnessIn
  }

  final class ParameterizedUnsignedExtendedSum(width: HdlInt) extends Component {
    setDefinitionName("ParameterizedUnsignedExtendedSum")
    val hActive, hFrontPorch, hSyncWidth, hBackPorch = in UInt (width bits)
    val hTotal = out UInt (18 bits)
    hTotal := (((hActive.resize(18) + hFrontPorch.resize(18)) +
      hSyncWidth.resize(18)) + hBackPorch.resize(18))
  }
}

class MorphVerilogExpressionInliningTests extends AnyFunSuite {
  import MorphVerilogExpressionInliningFixture._

  private def withDirectory[A](body: Path => A): A = {
    val directory = Files.createTempDirectory("morph-verilog-expression-inline-")
    try body(directory)
    finally {
      val paths = Files.walk(directory)
      try paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists(_))
      finally paths.close()
    }
  }

  private def config(directory: Path, fileName: String): SpinalConfig = {
    val value = SpinalConfig(targetDirectory = directory.toString)
    value.netlistFileName = fileName
    value
  }

  private def read(directory: Path, fileName: String): String =
    new String(Files.readAllBytes(directory.resolve(fileName)), StandardCharsets.UTF_8)

  private def fixedMorph(enabled: Boolean): String = withDirectory { directory =>
    val fileName = "fixed.v"
    val witnessWidth = HdlInt.param("WITNESS_WIDTH", default = 2, min = 1, max = 4)
    MorphVerilog(MorphWireAssignmentPasses(config(directory, fileName), enabled)) {
      new FixedUnsignedExtendedSum(witnessWidth)
    }
    read(directory, fileName)
  }

  private def parameterizedMorph(enabled: Boolean): String = withDirectory { directory =>
    val fileName = "parameterized.v"
    val width = HdlInt.param("WIDTH", default = 16, min = 1, max = 16)
    MorphVerilog(MorphWireAssignmentPasses(config(directory, fileName), enabled)) {
      new ParameterizedUnsignedExtendedSum(width)
    }
    read(directory, fileName)
  }

  private def wrapperAssignments(verilog: String): Vector[String] =
    verilog.split("\\n").iterator
      .filter(line => line.trim.startsWith("assign _zz_hTotal"))
      .toVector

  test("public MorphVerilog inlines the fixed unsigned extended addition tree") {
    val default = withDirectory { directory =>
      val fileName = "fixed.v"
      val witnessWidth = HdlInt.param("WITNESS_WIDTH", default = 2, min = 1, max = 4)
      MorphVerilog(MorphWireAssignmentPasses(config(directory, fileName))) {
        new FixedUnsignedExtendedSum(witnessWidth)
      }
      read(directory, fileName)
    }
    val explicit = fixedMorph(enabled = true)

    assert(default == explicit)
    assert(wrapperAssignments(default).isEmpty)
    assert(default.contains(
      "assign hTotal = ((({2'd0, hActive} + {2'd0, hFrontPorch}) + {2'd0, hSyncWidth}) + {2'd0, hBackPorch});"
    ))
  }

  test("explicitly disabled optimization preserves the legacy wrapper structure") {
    val disabled = fixedMorph(enabled = false)

    assert(wrapperAssignments(disabled).size == 6)
    assert(disabled.contains("assign _zz_hTotal_1 = (_zz_hTotal_2 + _zz_hTotal_3);"))
    assert(disabled.contains("assign hTotal = (_zz_hTotal + _zz_hTotal_5);"))
  }

  test("ordinary SpinalVerilog remains unchanged") {
    val ordinary = withDirectory { directory =>
      val fileName = "fixed.v"
      val witnessWidth = HdlInt.param("WITNESS_WIDTH", default = 2, min = 1, max = 4)
      SpinalVerilog(config(directory, fileName)) {
        new FixedUnsignedExtendedSum(witnessWidth)
      }
      read(directory, fileName)
    }

    assert(wrapperAssignments(ordinary).size == 6)
  }

  test("parameter-dependent resize carriers remain while redundant arithmetic wrappers inline") {
    val generated = parameterizedMorph(enabled = true)

    assert(generated.contains("parameter integer WIDTH = 16"))
    assert(wrapperAssignments(generated).isEmpty)
    assert(generated.contains("wire       [17:0]   morphhdl_resize;"))
    assert(generated.contains(
      "assign hTotal = (((morphhdl_resize + morphhdl_resize_1) + morphhdl_resize_2) + morphhdl_resize_3);"
    ))
  }

  test("disabled parameter-dependent generation preserves both resize and add wrappers") {
    val generated = parameterizedMorph(enabled = false)

    assert(wrapperAssignments(generated).size == 2)
    assert(generated.contains("wire       [17:0]   morphhdl_resize;"))
    assert(generated.contains("assign hTotal = (_zz_hTotal + morphhdl_resize_3);"))
  }

  test("repeated public generation is byte deterministic") {
    assert(fixedMorph(enabled = true) == fixedMorph(enabled = true))
    assert(fixedMorph(enabled = false) == fixedMorph(enabled = false))
  }

  test("a fixed native receiver cannot replace a distinct symbolic expression fence") {
    val generated = withDirectory { directory =>
      val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 8)
      MorphVerilog(config(directory, "boundary.v")) {
        new Component {
          setDefinitionName("DistinctReceiverBoundary")
          val a, b = in UInt(width bits)
          val result = out UInt(8 bits)
          val total = UInt(width bits)
          total := a + b
          result := total
        }
      }
      read(directory, "boundary.v")
    }

    // Publication may infer the output's width later. The wire phase must
    // retain the exact boundary until that separate inference has occurred.
    assert(generated.contains("wire       [WIDTH-1:0]    total;"), generated)
    assert(generated.contains("assign total = (a + b);"), generated)
    assert(generated.contains("assign result = total;"), generated)
  }

  test("optimization cannot erase a rejected symbolic assignment at an equal default width") {
    for (enabled <- Vector(false, true)) withDirectory { directory =>
      val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 8)
      val generated = MorphVerilog.tryGenerate(
        MorphWireAssignmentPasses(config(directory, "invalid.v"), enabled)
      ) {
        new Component {
          setDefinitionName("RejectedSymbolicBoundary")
          val a, b = in UInt(8 bits)
          val witnessIn = in UInt(width bits)
          val witnessOut = out UInt(width bits)
          val result = out UInt(8 bits)
          val total = UInt(width bits)
          total := a + b
          result := total
          witnessOut := witnessIn
        }
      }
      generated match {
        case Left(failure) =>
          assert(failure.detail.contains("ASSIGNMENT-WIDTH-MISMATCH"), failure.detail)
          assert(failure.detail.contains("symbolic signal 'total'"), failure.detail)
        case Right(_) => fail(s"enabled=$enabled erased an invalid symbolic assignment")
      }
      assert(!Files.exists(directory.resolve("invalid.v")))
    }
  }

  test("symbolic source widths preserve retained roots and permit valid shared-width inlining") {
    val generated = withDirectory { directory =>
      val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 16)
      MorphVerilog(config(directory, "source-boundary.v")) {
        new Component {
          setDefinitionName("SymbolicSourceBoundary")
          val a, b = in Bits(width bits)
          val result = out Bool()
          val equalInputs = Bool()
          equalInputs := a === b
          result := equalInputs
          val clonedResult, protectedResult = out Bits(width bits)
          val bitSource = Bits(width bits)
          bitSource := a ^ b
          val bitCloneAlias = cloneOf(bitSource)
          bitCloneAlias := bitSource
          clonedResult := bitCloneAlias
          val protectedAlias = cloneOf(bitSource)
          protectedAlias.addAttribute("keep")
          protectedAlias := bitSource
          protectedResult := protectedAlias
        }
      }
      read(directory, "source-boundary.v")
    }

    assert(!generated.contains("equalInputs"), generated)
    assert(generated.contains("assign result = (a == b);"), generated)
    assert(!generated.contains("bitSource"), generated)
    assert(!generated.contains("bitCloneAlias"), generated)
    assert(generated.contains("assign clonedResult = (a ^ b);"), generated)
    assert(generated.contains("assign protectedAlias = (a ^ b);"), generated)
    assert(generated.contains("assign protectedResult = protectedAlias;"), generated)

    val independent = withDirectory { directory =>
      val left = HdlInt.param("LEFT_WIDTH", default = 8, min = 1, max = 16)
      val right = HdlInt.param("RIGHT_WIDTH", default = 8, min = 1, max = 16)
      MorphVerilog(config(directory, "independent.v")) {
        new Component {
          setDefinitionName("IndependentSymbolicSources")
          val a = in UInt(left bits)
          val b = in UInt(right bits)
          val result = out Bool()
          val equalInputs = Bool()
          equalInputs := a === b
          result := equalInputs
        }
      }
      read(directory, "independent.v")
    }
    // The bounded snapshot supports one exact width family. Equal defaults
    // and equal bounds must not merge independent LEFT_WIDTH/RIGHT_WIDTH roots.
    assert(independent.contains("wire                equalInputs;"), independent)
    assert(independent.contains("assign equalInputs = (a == b);"), independent)
    assert(independent.contains("assign result = equalInputs;"), independent)
  }

  test("the private emitter marker does not admit unrelated generation flags") {
    withDirectory { directory =>
      val marked = MorphWireAssignmentPasses(config(directory, "rejected.v"))
      marked.includeSimulation
      val witnessWidth = HdlInt.param("WITNESS_WIDTH", default = 2, min = 1, max = 4)
      var factoryRuns = 0
      val result = MorphVerilog.tryGenerate(marked) {
        factoryRuns += 1
        new FixedUnsignedExtendedSum(witnessWidth)
      }

      result match {
        case Left(failure) => assert(failure.stage == morphhdl.MorphVerilogStage.Configuration)
        case Right(report) => fail(s"expected configuration failure, received $report")
      }
      assert(factoryRuns == 0)
    }
  }
}
