package spinal.core.internals

import org.scalatest.funsuite.AnyFunSuite

class ParameterizedVerilogStructuralLexicalTests extends AnyFunSuite {
  test("reference scanning ignores strings literals and comments") {
    val names = ParameterizedVerilogStructural.verilogReferenceNames(
      """real_before /* hidden_block
        |hidden_block_tail */ real_after
        |$display("hidden_string // not a comment", real_after_string);
        |$display("hidden_multiline
        |hidden_multiline_tail", real_after_multiline);
        |child instance_name (.din(connection_actual));
        |assign hierarchy_sink = scope.hierarchy_actual;
        |assign ternary_sink = condition ? left_actual : right_actual;
        |assign dollar_sink = foo$bar;
        |assign literal_sink = 8'hDEAD ^ 1'b0;
        |// hidden_line
        |real_tail
        |""".stripMargin
    )
    assert(names("real_before"))
    assert(names("real_after"))
    assert(names("real_after_string"))
    assert(names("real_after_multiline"))
    assert(names("connection_actual"))
    assert(names("literal_sink"))
    assert(names("right_actual"))
    assert(names("foo$bar"))
    assert(names("real_tail"))
    assert(!names("display"))
    assert(!names("child"))
    assert(!names("instance_name"))
    assert(!names("din"))
    assert(!names("scope"))
    assert(!names("hierarchy_actual"))
    assert(!names("hidden_block"))
    assert(!names("hidden_block_tail"))
    assert(!names("hidden_string"))
    assert(!names("hidden_multiline"))
    assert(!names("hidden_multiline_tail"))
    assert(!names("hDEAD"))
    assert(!names("hidden_line"))
  }

  test("native declaration type qualifiers cannot become signal dependencies") {
    val text = "wire signed [WIDTH-1:0] signed_value;\nreg unsigned [WIDTH-1:0] unsigned_value;\n" +
      "integer count;\nassign shifted = $signed(signed_value) >>> count;"
    val names = ParameterizedVerilogStructural.verilogReferenceNames(text)
    assert(names == Set("WIDTH", "signed_value", "unsigned_value", "count", "shifted"), names)
    val consumed = ParameterizedVerilogStructural.verilogConsumedNames(text)
    assert(consumed == Set("WIDTH", "signed_value", "count"), consumed)
  }

  test("consumer indexing excludes each definition without losing other references") {
    val names = ParameterizedVerilogStructural.verilogConsumedNames(
      """wire [WIDTH-1:0] local;
        |wire unused;
        |assign local = source;
        |assign self_only = self_only;
        |assign destination = local;
        |if (condition) begin : generated_label
        |  child instance_name (.din(connection));
        |end
        |$display("hidden
        |hidden_tail", displayed);
        |/* hidden_comment */ assign last = 8'hDEAD ^ input_actual;
        |""".stripMargin)
    assert(names == Set("WIDTH", "source", "local", "condition", "connection", "displayed", "input_actual"), names)
  }

  test("large bodies retain every independently consumed name") {
    val body = (0 until 1024).map(index =>
      s"wire local_$index;\nassign local_$index = source_$index;\nassign sink_$index = local_$index;").mkString("\n")
    val expected = (0 until 1024).flatMap(index => Vector(s"source_$index", s"local_$index")).toSet
    assert(ParameterizedVerilogStructural.verilogConsumedNames(body) == expected)
  }

  test("interval overlap index matches independent exhaustive pair checking") {
    import ParameterizedVerilogStructural.{LineRange, firstCaptureOverlap}
    val candidates = (for (start <- 0 to 4; end <- start to 4) yield LineRange(start, end)).toVector
    val random = new scala.util.Random(5909)
    for (_ <- 0 until 2048) {
      val ranges = Vector.fill(random.nextInt(16))(candidates(random.nextInt(candidates.size)))
      val shared = candidates.filter(_ => random.nextBoolean()).toSet
      val exhaustive = ranges.indices.exists(i => (i + 1 until ranges.size).exists { j =>
        val left = ranges(i); val right = ranges(j)
        left.start <= right.end && right.start <= left.end && !(left == right && shared(left))
      })
      val indexed = firstCaptureOverlap(ranges, shared)
      assert(indexed.nonEmpty == exhaustive, s"$ranges shared=$shared")
      indexed.foreach { case (left, right) =>
        assert(ranges.contains(left) && ranges.contains(right))
        assert(left.overlaps(right) && !(left == right && shared(left)))
      }
    }
  }

  test("shared duplicates cannot hide nested or touching capture overlaps") {
    import ParameterizedVerilogStructural.{LineRange, firstCaptureOverlap}
    val large = LineRange(2, 20)
    val inner = LineRange(3, 4)
    assert(firstCaptureOverlap(Vector(large, large), Set(large)).isEmpty)
    assert(firstCaptureOverlap(Vector(large, large), Set.empty).nonEmpty)
    assert(firstCaptureOverlap(Vector(large, inner, large), Set(large)).nonEmpty)
    assert(firstCaptureOverlap(Vector(LineRange(0, 2), LineRange(2, 4)), Set.empty).nonEmpty)
    assert(firstCaptureOverlap(Vector(LineRange(0, 1), LineRange(2, 4)), Set.empty).isEmpty)
    assert(firstCaptureOverlap(Vector.empty, Set.empty).isEmpty)
    assert(firstCaptureOverlap(Vector(large), Set.empty).isEmpty)
  }

}
