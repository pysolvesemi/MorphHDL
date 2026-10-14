package spinal.core.internals

import org.scalatest.funsuite.AnyFunSuite

class NativeStructuralIndentationTests extends AnyFunSuite {
  private def check(expected: String): Unit = {
    val broken = expected.split("\n", -1).zipWithIndex.map { case (line, i) =>
      if (line.trim.isEmpty || line.trim.startsWith("`")) line else " " * (i % 11) + line.trim
    }.mkString("\n")
    val result = NativeStructuralIndentation.publish(broken)
    assert(result == expected)
    assert(result.split("\n", -1).map(_.dropWhile(_.isWhitespace)).toVector ==
      broken.split("\n", -1).map(_.dropWhile(_.isWhitespace)).toVector)
    assert(NativeStructuralIndentation.publish(result) == result)
  }
  test("retained for if else arrays recursive instances and pipeline processes have two-space structural layout") {
    check("""module Tree #(
  parameter COUNT = 8
) (
  input [COUNT*8-1:0] terms,
  output reg [7:0] result
);
  generate
    if (COUNT == 1) begin : base
      always @(*) begin
        result = terms[7:0];
      end
    end else begin : step
      wire [7:0] combined [0:(COUNT+1)/2-1];
      wire [((COUNT+1)/2)*8-1:0] nextTerms;
      Tree #(
        .COUNT ((COUNT+1)/2)
      ) nextLevel (
        .terms (nextTerms),
        .result (result)
      );
      for (i=0; i<(COUNT+1)/2; i=i+1) begin : combine
        assign nextTerms[i*8+:8] = combined[i];
        if (2*i+1<COUNT) begin : pair
          assign combined[i] = terms[2*i*8+:8] + terms[(2*i+1)*8+:8];
        end else begin : tail
          assign combined[i] = terms[2*i*8+:8];
        end
        if (PIPELINE) begin : pipe
          always @(posedge clk or negedge reset) begin
            if (!reset) begin
              stored <= 0;
            end else if (enable) begin
              stored <= combined[i];
            end
          end
        end
      end
    end
  endgenerate
endmodule""")
  }
  test("comments strings attributes escaped identifiers and directive continuations remain byte exact") {
    val input = """module Test;
`define TEXT begin \
    end
/* begin
   end */
(* note = "begin \" end", keep = "true" *) wire \begin.end ;
  // endgenerate
 generate
 if (1) begin : scope
 $display("end\\ begin \" endgenerate"); // begin
 assign \end.else  = 1;
 end
 endgenerate
endmodule"""
    val output = NativeStructuralIndentation.publish(input)
    assert(output.contains("  (* note = \"begin \\\" end\", keep = \"true\" *) wire \\begin.end ;"))
    assert(output.contains("      assign \\end.else  = 1;"))
    assert(output.contains("/* begin\n   end */"))
    assert(output.contains("`define TEXT begin \\\n    end"))
    assert(output.split("\n", -1).map(_.dropWhile(_.isWhitespace)).toVector ==
      input.split("\n", -1).map(_.dropWhile(_.isWhitespace)).toVector)
    assert(NativeStructuralIndentation.publish(output) == output)
  }
  test("conditional compilation and case function scopes preserve sibling alignment") {
    check("""module Test;
  function integer f;
    input integer v;
    begin
      case (v)
        0: begin
          f = 0;
        end
        default: begin
          f = 1;
        end
      endcase
    end
  endfunction
`ifdef FIRST
  generate
    if (1) begin : first
`else
  generate
    if (1) begin : other
`endif
      wire x;
    end
  endgenerate
endmodule""")
  }
  test("continued quoted strings preserve their closing quote and subsequent structural scope") {
    val text = "module T;\n initial $display(\"begin " + "\\" + "\n\");\n generate\n if(1) begin : g\n wire x;\n end\n endgenerate\nendmodule"
    val result = NativeStructuralIndentation.publish(text)
    assert(result.contains("\n  generate\n    if(1) begin : g\n      wire x;\n    end\n  endgenerate\nendmodule"))
    assert(result.split("\n", -1).map(_.dropWhile(_.isWhitespace)).toVector ==
      text.split("\n", -1).map(_.dropWhile(_.isWhitespace)).toVector)
  }
  test("native recursive array and optional pipeline publication is already canonically indented") {
    import spinal.core.{ElabInt, SpinalConfig, VerilogAggregateOptions}
    import java.nio.file.Files
    for (count <- Seq(1, 8)) {
      val dir = Files.createTempDirectory("native-indentation-")
      val config = SpinalConfig(targetDirectory = dir.toString, headerWithDate = false,
        flags = scala.collection.mutable.HashSet[Any](VerilogAggregateOptions(
          preserveConstantVecs = true, preserveConstantLoops = true,
          vecLayout = VerilogAggregateOptions.UnpackedArray)))
      config.netlistFileName = "tree.v"
      morphhdl.MorphVerilog(config)(new nativeapplication.NativeWidthCompositionTree("masked-soft-reset", ElabInt.literal(count)))
      val rtl = new String(Files.readAllBytes(dir.resolve("tree.v")), "UTF-8")
      assert(NativeStructuralIndentation.publish(rtl) == rtl)
      assert(rtl.contains("      always @(*) begin\n        result = logic_lanes[0];\n      end"))
      assert(rtl.contains("      NativeWidthCompositionTree #(\n"))
      assert(rtl.contains("      ) reference (\n"))
      assert(rtl.contains("      for ("))
      assert(rtl.contains("        if ("))
    }
  }

}
