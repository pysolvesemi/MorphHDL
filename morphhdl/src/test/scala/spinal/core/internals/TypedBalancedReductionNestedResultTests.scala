package spinal.core.internals

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import morphhdl.{MorphNamedFieldVectors, MorphVerilog}
import morphhdl.frontend.HdlInt
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class TypedBalancedReductionNestedResultTests extends AnyFunSuite {
  for ((layout, signed, defaultCount) <- TypedBalancedReductionNestedResultArtifactWriter.profiles) {
    test(s"scoped nested Vec results retain $layout $signed default=$defaultCount") {
      val file = TypedBalancedReductionNestedResultArtifactWriter.candidate(
        Files.createTempDirectory("nested-result-"), layout, signed, defaultCount)
      val rtl = new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
      Vector("parameter integer INNER", "parameter integer U_W", "parameter integer S_W",
        "parameter integer TAG_W", s"COUNT = $defaultCount", "begin : g_max", "begin : g_min",
        "selected_samples", "always @(*) begin").foreach(token => assert(rtl.contains(token), token))
      if (layout == "fields") {
        Vector("records_samples_unsigned", "records_samples_signed", "records_samples_valid",
          "selected_samples_unsigned", "selected_samples_signed").foreach(token => assert(rtl.contains(token), token))
      }
      var depth = 0
      "\\b(generate|endgenerate)\\b".r.findAllIn(rtl).foreach {
        case "generate" => depth += 1; assert(depth == 1, "nested generate")
        case "endgenerate" => depth -= 1; assert(depth == 0, "unbalanced generate")
      }
      assert(depth == 0)
    }
  }

  for (sibling <- Vector(false, true)) test(s"nested result cannot escape its owner; sibling=$sibling") {
    val directory = Files.createTempDirectory("nested-result-escape-")
    val error = intercept[Exception] {
      MorphVerilog(TypedBalancedReductionNestedResultArtifactWriter.config(directory, "bad")) {
        new BalancedNestedResultEscape(HdlInt.param("COUNT", 1, 1, 5),
          HdlInt.param("INNER", 1, 1, 3), HdlInt.param("MODE", 1, 0, 1), sibling)
      }
    }
    val messages = Iterator.iterate[Throwable](error)(_.getCause).takeWhile(_ != null)
      .map(value => Option(value.getMessage).getOrElse("")).mkString("\n")
    assert(messages.contains("RESULT-ESCAPE") || messages.contains("BRANCH-REFERENCE"), messages)
    assert(!Files.exists(directory.resolve("bad.v")))
  }

  test("a parsed isolated process does not confer result ownership") {
    assert(!TypedBalancedReductionBackend.ownsPublishedRecursiveAssignment(null, Vector.empty))
    val directory = Files.createTempDirectory("nested-result-unclaimed-")
    SpinalVerilog(SpinalConfig(targetDirectory = directory.toString, headerWithDate = false)) {
      new Component {
        val values = in(Vec(UInt(3 bits), HdlInt.param("INNER", 1, 1, 3)))
        val result = out(UInt(3 bits))
        result := values(0)
        val assignments = scala.collection.mutable.ArrayBuffer.empty[DataAssignmentStatement]
        result.foreachStatements {
          case assignment: DataAssignmentStatement => assignments += assignment
          case _ =>
        }
        assert(!TypedBalancedReductionBackend.ownsPublishedRecursiveAssignment(values, assignments.toVector))
      }
    }
  }

  test("only an isolated native combinational process may be repacked") {
    def admitted(body: String, start: Int = 0, line: Int = 1, end: Int = 2): Boolean =
      ParameterizedVerilogVecs.isolatedRecursiveResultProcess(body.split("\n", -1).toVector, start, end, line)
    assert(admitted("  always @(*) begin\n  target = source;\n  end"))
    assert(admitted("always @(*) begin\n// comment\n\n target = source;\nend", line = 3, end = 4))
    assert(!admitted("always @(posedge clk) begin\ntarget = source;\nend"))
    assert(!admitted("always @(enable) begin\ntarget = source;\nend"))
    assert(!admitted("always @(*) begin\nif (enable) begin\ntarget = source;\nend\nend", line = 2, end = 4))
    assert(!admitted("always @(*) begin\nother = source;\ntarget = source;\nend", line = 2, end = 3))
    assert(!admitted("always @(*) begin\n$display(\"effect\");\ntarget = source;\nend", line = 2, end = 3))
    assert(!admitted("always @(*) begin\ntarget = source;\nend else begin"))
    assert(!admitted("always @(*) begin\ntarget = source;\nend", start = -1))
    assert(!admitted("always @(*) begin\ntarget = source;\nend", line = 0))
    assert(!admitted("always @(*) begin\ntarget = source;\nend", end = 99))
  }
}
