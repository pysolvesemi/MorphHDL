package spinal.core.internals

import org.scalatest.funsuite.AnyFunSuite

class ParameterizedVerilogVecDeclarationTests extends AnyFunSuite {
  test("declaration prefix scan recognizes native nets and attributed signed ports") {
    Seq("wire [WIDTH-1:0] value;", "  input wire signed [WIDTH-1:0] value,",
      "\toutput reg value", "inout wire value", "logic value;",
      "/* prefix */ (* keep = 1 *) wire value;",
      """(* label = "fake *) wire", keep = 1 *) output wire value,""",
      """(* label = "escaped \" *) fake", keep = 1 *) (* keep = 1 *) reg value;""")
      .foreach(line => assert(ParameterizedVerilogVecs.isDeclarationCandidate(line), line))
  }

  test("expression text and malformed attributes cannot become declaration prefixes") {
    Seq("assign result = " + ("wire + input + " * 100000) + "value;",
      "  // wire value;", "/* unterminated wire value;", "(* label = \"unterminated *) wire value;",
      "wire_name value;", "wire$alias value;", "", "   ",
      "$display(\"wire value;\");", "(* keep = 1 *) assign value = wire_name;")
      .foreach(line => assert(!ParameterizedVerilogVecs.isDeclarationCandidate(line), line.take(100)))
  }
}
