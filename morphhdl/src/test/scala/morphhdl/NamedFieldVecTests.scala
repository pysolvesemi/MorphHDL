package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

/** Layout contracts stay separate from the mandatory executable tool matrix.
  * Test names allow access, nested, storage and streams to be diagnosed alone.
  */
class NamedFieldVecTests extends AnyFunSuite {
  import NamedFieldVecFixture._

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
  private def generate(kind: String, named: Boolean = true): String =
    read(candidate(Files.createTempDirectory(s"named-field-$kind-"), kind, named))
  private def declaration(text: String, name: String): String = {
    val pattern = ("(?m)^\\s*(?:input|output|wire|reg)\\b[^;\\n]*\\b" +
      java.util.regex.Pattern.quote(name) + "\\s*(?:[,;]|$)").r
    pattern.findFirstIn(text).getOrElse(fail(s"missing declaration $name\n$text"))
  }
  private def fieldPorts(text: String, root: String): Unit = {
    Vector("red", "green", "blue", "metadata_valid").foreach { field =>
      val line = declaration(text, s"${root}_$field")
      assert(line.contains("COUNT"), line)
    }
    assert(!("(?m)^\\s*(?:input|output)\\b[^\\n]*\\b" + root + "_\\d+(?:_|\\b)").r
      .findFirstIn(text).nonEmpty, text)
  }

  test("basic unchanged Vec Bundle source retains named fields for whole assignment and reads") {
    val text = generate("basic")
    fieldPorts(text, "pixels")
    fieldPorts(text, "result")
    assert(declaration(text, "storage_red").contains("COUNT"))
    assert(declaration(text, "pixels_blue").contains("BLUE_WIDTH"))
    assert(!declaration(text, "first_red").contains("COUNT"))
  }

  test("access unchanged Vec Bundle source publishes one named vector per scalar field") {
    val text = generate("access")
    Vector("pixels", "alternate", "result", "alternateResult", "restored").foreach(fieldPorts(text, _))
    Vector("pixels_red", "pixels_green").foreach { name =>
      assert(declaration(text, name).contains("WIDTH"), declaration(text, name))
    }
    assert(declaration(text, "pixels_blue").contains("BLUE_WIDTH"))
    assert(!declaration(text, "pixels_blue").contains(" signed "),
      "a field containing several signed elements is an unsigned transport vector")
    assert(!declaration(text, "first_red").contains("COUNT"))
    assert(!declaration(text, "first_blue").contains("COUNT"))
    assert(declaration(text, "storage_red").contains("COUNT"))
    assert(text.contains("index") && text.contains("+:"), text)
  }

  test("access explicit legacy profile retains the packed module interface") {
    val text = generate("access", named = false)
    assert(declaration(text, "pixels").contains("COUNT"))
    assert(!"(?m)^\\s*input\\b[^\\n]*\\bpixels_red\\b".r.findFirstIn(text).nonEmpty)
    assert(declaration(text, "first_red").contains("WIDTH"))
  }

  test("access named field profile is deterministic and does not mutate the input configuration") {
    assert(generate("access") == generate("access"))
    val directory = Files.createTempDirectory("named-field-config-")
    val base = config(directory, "unused.v")
    val enabled = MorphNamedFieldVectors.enable(base)
    assert(!MorphNamedFieldVectors.isEnabled(base))
    assert(MorphNamedFieldVectors.isEnabled(enabled))
    assert(!MorphNamedFieldVectors.isEnabled(MorphNamedFieldVectors.disable(enabled)))
  }

  test("nested field vectors retain independent outer and inner dimensions and unequal widths") {
    val text = generate("nested")
    val tag = declaration(text, "pixels_tag")
    assert(tag.contains("COUNT") && !tag.contains("INNER"), tag)
    Vector("red", "green", "blue", "metadata_valid").foreach { field =>
      val line = declaration(text, s"pixels_colors_$field")
      assert(line.contains("COUNT") && line.contains("INNER"), line)
      assert(!text.contains(s"pixels_colors_0_$field"), text)
    }
    assert(declaration(text, "pixels_colors_blue").contains("BLUE_WIDTH"))
    assert(declaration(text, "storage_colors_red").contains("INNER"))
    assert(!declaration(text, "first_blue").contains("INNER"))
  }

  test("storage cloning HardType registers and named hierarchy retain field grouping and deduplication") {
    val text = generate("storage")
    fieldPorts(text, "pixels")
    fieldPorts(text, "result")
    Vector("cloned_red", "hard_red", "registers_red", "directRegisters_red").foreach { name =>
      assert(declaration(text, name).contains("COUNT"))
    }
    assert("(?m)^\\s*module\\s+NamedFieldVecChild\\b".r.findAllIn(text).size == 1, text)
    assert("\\.pixels_red\\s*\\(".r.findAllIn(text).size == 3, text)
    assert("\\.result_red\\s*\\(".r.findAllIn(text).size == 3, text)
  }

  test("streams and Flow preserve payload field vectors and ready valid directions") {
    val text = generate("streams")
    Vector("source_payload", "sink_payload", "flowSource_payload", "flowSink_payload")
      .foreach(fieldPorts(text, _))
    assert(declaration(text, "source_ready").trim.startsWith("output"))
    assert(declaration(text, "sink_ready").trim.startsWith("input"))
    assert(declaration(text, "source_valid").trim.startsWith("input"))
    assert(declaration(text, "sink_valid").trim.startsWith("output"))
  }

  test("scalar Vec keeps one WIDTH times COUNT carrier in both publication profiles") {
    val text = generate("scalar")
    val line = declaration(text, "words")
    assert(line.contains("WIDTH") && line.contains("COUNT"), line)
    assert(declaration(text, "result").contains("COUNT"))
    val legacy = generate("scalar", named = false)
    Vector("words", "result").foreach { name =>
      val legacyLine = declaration(legacy, name)
      assert(legacyLine.contains("WIDTH") && legacyLine.contains("COUNT"), legacyLine)
    }
    assert(!"(?m)^\\s*(?:input|output)\\b[^\\n]*\\bwords_\\d+\\b".r.findFirstIn(text).nonEmpty)
  }

  test("mixed directions Vec of Streams groups payload valid and ready fields in their native directions") {
    val text = generate("mixed")
    fieldPorts(text, "source_payload")
    fieldPorts(text, "sink_payload")
    Vector("source_valid", "sink_ready").foreach { name =>
      val line = declaration(text, name)
      assert(line.trim.startsWith("input") && line.contains("COUNT"), line)
    }
    Vector("source_ready", "sink_valid").foreach { name =>
      val line = declaration(text, name)
      assert(line.trim.startsWith("output") && line.contains("COUNT"), line)
    }
  }

  test("mixed directions retain the explicit legacy single carrier rejection") {
    val directory = Files.createTempDirectory("named-field-mixed-legacy-")
    val base = config(directory, "MixedLegacy.v")
    val selected = MorphSignedCasts.enable(MorphNamedFieldVectors.disable(base))
    val result = MorphVerilog.tryGenerate(selected) {
      val (width, blueWidth, count) = dimensions()
      new MixedDirections(width, blueWidth, count)
    }
    result match {
      case Left(failure) =>
        assert(failure.detail.contains("VEC"), failure.detail)
        assert(failure.detail.contains("DIRECTION") || failure.detail.contains("LINEAGE"), failure.detail)
      case Right(report) => fail(s"legacy mixed-direction single carrier unexpectedly published: $report")
    }
    assert(!Files.exists(directory.resolve("MixedLegacy.v")))
  }

  test("ordinary concrete SpinalVerilog ignores the named field profile") {
    def ordinary(enabled: Boolean): String = {
      val directory = Files.createTempDirectory("named-field-concrete-")
      val base = config(directory, "NativeFieldVec.v")
      val selected = if (enabled) MorphNamedFieldVectors.enable(base) else base
      SpinalVerilog(selected) { new NativeAccess(5, 3, 3) }
      read(directory.resolve("NativeFieldVec.v"))
    }
    val baseline = ordinary(enabled = false)
    assert(baseline == ordinary(enabled = true))
    assert(declaration(baseline, "pixels_0_red").contains("input"))
    assert(declaration(baseline, "pixels_2_blue").contains("input"))
  }
}
