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
}
