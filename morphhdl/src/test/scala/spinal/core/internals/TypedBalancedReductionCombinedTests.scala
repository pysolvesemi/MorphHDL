package spinal.core.internals

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import morphhdl.{MorphNamedFieldVectors, MorphVerilog}
import morphhdl.frontend.HdlInt
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class TypedBalancedReductionCombinedTests extends AnyFunSuite {
  private def text(path: Path): String = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
  private def rejected(component: => Component): String = {
    val directory = Files.createTempDirectory("combined-rejected-")
    val failure = intercept[Exception] {
      MorphVerilog(TypedBalancedReductionCombinedArtifactWriter.config(directory, "rejected.v"))(component)
    }
    assert(!Files.exists(directory.resolve("rejected.v")), "unsafe composite reached publication")
    val messages = scala.collection.mutable.ArrayBuffer.empty[String]
    var error: Throwable = failure
    while (error != null) {
      messages += Option(error.getMessage).getOrElse("")
      error = error.getCause
    }
    messages.mkString("\n")
  }

  for (layout <- TypedBalancedReductionCombinedArtifactWriter.layouts;
      signed <- TypedBalancedReductionCombinedArtifactWriter.signedModes;
      default <- TypedBalancedReductionCombinedArtifactWriter.defaults) {
    test(s"scoped unsigned/signed record selection and native register bridges: $layout $signed default=$default") {
      val rtl = text(TypedBalancedReductionCombinedArtifactWriter.candidate(
        Files.createTempDirectory("combined-record-"), layout, signed, default))
      Vector(s"COUNT = $default", "WIDTH = 5", "TAG_WIDTH = 3", "COORD_WIDTH = 7",
        "begin : g_minimum", "begin : g_maximum", "begin : g_registered_min",
        "begin : g_registered_max", "selected_tag", "signedSelected_imag").foreach { value =>
        assert(rtl.contains(value), s"missing $value\n$rtl")
      }
      assert(rtl.contains("always @(posedge clk)"), rtl)
      assert(rtl.contains("morphhdl_balanced_"), rtl)
      if (layout == "fields") {
        Vector("records_key", "records_tag", "records_x", "records_y", "signedRecords_real", "signedRecords_imag")
          .foreach(name => assert(rtl.contains(name), rtl))
      }
      // generate/endgenerate may surround regions, but may never be nested.
      var depth = 0
      "\\b(generate|endgenerate)\\b".r.findAllIn(rtl).foreach {
        case "generate" => depth += 1; assert(depth == 1, "nested generate region\n" + rtl)
        case "endgenerate" => depth -= 1; assert(depth == 0, "unbalanced generate region\n" + rtl)
      }
      assert(depth == 0)
    }
  }

  for (sibling <- Vector(false, true)) test(s"composite result cannot escape into ${if (sibling) "a sibling" else "the outer"} owner") {
    val message = rejected(new BalancedCombinedEscapingRecord(HdlInt.param("COUNT", 1, 1, 5),
      HdlInt.param("MODE", 1, 0, 1), sibling))
    assert(message.contains("RESULT-ESCAPE") || message.contains("BRANCH-REFERENCE"), message)
  }

  test("composite result retains native conflicting-driver rejection") {
    val message = rejected(new BalancedCombinedConflictingRecord(HdlInt.param("COUNT", 1, 1, 5),
      HdlInt.param("MODE", 1, 0, 1)))
    assert(message.toLowerCase.contains("driver") || message.contains("ASSIGNMENT OVERLAP"), message)
  }

  test("composite stage labels cannot collide with a user structural label") {
    val directory = Files.createTempDirectory("combined-labels-")
    MorphVerilog(TypedBalancedReductionCombinedArtifactWriter.config(directory, "labels.v")) {
      new BalancedCombinedLabelCollision(HdlInt.param("COUNT", 1, 1, 5), HdlInt.param("MODE", 1, 0, 1))
    }
    val rtl = text(directory.resolve("labels.v"))
    assert("begin : morphhdl_balanced_1_active_0\\b".r.findAllIn(rtl).size == 1, rtl)
    assert(rtl.contains("morphhdl_balanced_1_1_active_0"), rtl)
  }

  test("field-preserving interface remains explicit opt-in") {
    val config = SpinalConfig()
    assert(!MorphNamedFieldVectors.isEnabled(config))
    val fields = MorphNamedFieldVectors.enable(config)
    assert(MorphNamedFieldVectors.isEnabled(fields))
    assert(!MorphNamedFieldVectors.isEnabled(config))
    assert(!MorphNamedFieldVectors.isEnabled(MorphNamedFieldVectors.disable(fields)))
  }
}
