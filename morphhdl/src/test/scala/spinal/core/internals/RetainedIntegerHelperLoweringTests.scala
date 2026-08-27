package spinal.core.internals

import org.scalatest.funsuite.AnyFunSuite

import spinal.core.ParameterizedVerilogException

class RetainedIntegerHelperLoweringTests extends AnyFunSuite {
  test("retained helper lowering edits only unqualified active Verilog calls") {
    val source =
      """module RetainedIntegerHelperLexicalTop #(
        |  parameter integer WIDTH = 5
        |) (
        |  output wire observed
        |);
        |
        |  // morphhdl_address_width(WIDTH) and clog2 are inert comment text.
        |  /* morphhdl_ceil_log2(WIDTH) is inert block-comment text. */
        |  function [morphhdl_ceil_log2(WIDTH)-1:0] user_morphhdl_address_width;
        |    input integer value;
        |    begin
        |      user_morphhdl_address_width = value;
        |    end
        |  endfunction
        |  localparam integer STRUCTURAL_WIDTH =
        |    morphhdl_address_width /* keep-helper-gap */ (
        |      morphhdl_ceil_log2(WIDTH)
        |    );
        |  localparam integer TERNARY_WIDTH =
        |    WIDTH > 1 ? WIDTH : morphhdl_address_width(WIDTH);
        |  wire user_result;
        |  assign user_result = user_morphhdl_address_width(WIDTH);
        |  wire qualified_result;
        |  assign qualified_result = user_scope.morphhdl_address_width(WIDTH);
        |  assign observed = (STRUCTURAL_WIDTH != 0);
        |  initial $display("morphhdl_address_width(WIDTH) clog2");
        |endmodule
        |""".stripMargin

    val lowered = ExternalParameterizedVerilogNativeFallback
      .lowerRetainedIntegerHelpers(
        source,
        "RetainedIntegerHelperLexicalTop"
      )

    assert(lowered.contains("function integer clog2;"))
    assert(
      lowered.contains(
        "function [clog2(WIDTH, 0)-1:0] user_morphhdl_address_width;"
      )
    )
    assert(lowered.contains("clog2 /* keep-helper-gap */ ("))
    assert(lowered.contains("clog2(WIDTH, 0)"))
    assert(lowered.contains("WIDTH > 1 ? WIDTH : clog2(WIDTH, 1)"))
    assert(lowered.contains("user_morphhdl_address_width(WIDTH)"))
    assert(lowered.contains("user_scope.morphhdl_address_width(WIDTH)"))
    assert(lowered.contains("// morphhdl_address_width(WIDTH) and clog2"))
    assert(lowered.contains("/* morphhdl_ceil_log2(WIDTH) is inert"))
    assert(lowered.contains("\"morphhdl_address_width(WIDTH) clog2\""))
    assert(
      ExternalParameterizedVerilogNativeFallback
        .lowerRetainedIntegerHelpers(
          lowered,
          "RetainedIntegerHelperLexicalTop"
        ) == lowered
    )
  }

  test("a user function cannot be mistaken for retained helper IR") {
    val source =
      """module RetainedIntegerHelperCollisionTop #(
        |  parameter integer WIDTH = 5
        |) (
        |  output wire observed
        |);
        |  function [(WIDTH)-1:0] morphhdl_address_width;
        |    input integer value;
        |    begin
        |      morphhdl_address_width = value + 7;
        |    end
        |  endfunction
        |  assign observed = morphhdl_address_width(WIDTH);
        |endmodule
        |""".stripMargin

    val failure = intercept[ParameterizedVerilogException] {
      ExternalParameterizedVerilogNativeFallback
        .lowerRetainedIntegerHelpers(
          source,
          "RetainedIntegerHelperCollisionTop"
        )
    }
    assert(
      failure.code ==
        "SPINAL-PARAMETERIZED-VERILOG-NATIVE-INT-HELPER-NAME-COLLISION"
    )
    assert(failure.detail.contains("declares user function"))
  }

  test("a user identifier reserves the portable helper name while comments do not") {
    val source =
      """module RetainedIntegerPortableNameCollisionTop #(
        |  parameter integer WIDTH = 5
        |) (
        |  output wire observed
        |);
        |  // clog2_1 in a comment does not reserve another suffix.
        |  wire \clog2 ;
        |  wire clog2_1;
        |  assign \clog2  = 1'b0;
        |  assign clog2_1 = 1'b0;
        |  assign observed = \clog2  | clog2_1 |
        |    (morphhdl_address_width(WIDTH) != 0);
        |endmodule
        |""".stripMargin

    val lowered = ExternalParameterizedVerilogNativeFallback
      .lowerRetainedIntegerHelpers(
        source,
        "RetainedIntegerPortableNameCollisionTop"
      )

    assert(lowered.contains("wire \\clog2 ;"))
    assert(lowered.contains("wire clog2_1;"))
    assert(lowered.contains("function integer clog2_2;"))
    assert(lowered.contains("clog2_2(WIDTH, 1)"))
    assert(!lowered.contains("function integer clog2_3;"))
  }

  test("escaped helper declarations collide without becoming compiler IR") {
    val source =
      """module RetainedIntegerEscapedCollisionTop #(
        |  parameter integer WIDTH = 5
        |) (
        |  output wire observed
        |);
        |  function integer \morphhdl_address_width ;
        |    input integer value;
        |    begin
        |      \morphhdl_address_width  = value;
        |    end
        |  endfunction
        |  assign observed = morphhdl_address_width(WIDTH);
        |endmodule
        |""".stripMargin

    val failure = intercept[ParameterizedVerilogException] {
      ExternalParameterizedVerilogNativeFallback
        .lowerRetainedIntegerHelpers(
          source,
          "RetainedIntegerEscapedCollisionTop"
        )
    }
    assert(
      failure.code ==
        "SPINAL-PARAMETERIZED-VERILOG-NATIVE-INT-HELPER-NAME-COLLISION"
    )

    val escapedCall = helperCallModule(
      "RetainedIntegerEscapedCallTop",
      "\\morphhdl_address_width (WIDTH)"
    )
    val escapedFailure = intercept[ParameterizedVerilogException] {
      ExternalParameterizedVerilogNativeFallback
        .lowerRetainedIntegerHelpers(
          escapedCall,
          "RetainedIntegerEscapedCallTop"
        )
    }
    assert(
      escapedFailure.code ==
        "SPINAL-PARAMETERIZED-VERILOG-NATIVE-INT-HELPER-ESCAPED-UNSUPPORTED"
    )
  }

  test("module and task declaration names are never rewritten as helper calls") {
    val moduleSource =
      """module morphhdl_address_width (
        |  output wire observed
        |);
        |  assign observed = 1'b0;
        |endmodule
        |""".stripMargin
    assert(
      ExternalParameterizedVerilogNativeFallback
        .lowerRetainedIntegerHelpers(
          moduleSource,
          "morphhdl_address_width"
        ) == moduleSource
    )

    val moduleCollisionSource =
      moduleSource +
        helperCallModule(
          "RetainedIntegerModuleCollisionTop",
          "morphhdl_address_width(WIDTH)"
        )
    val moduleFailure = intercept[ParameterizedVerilogException] {
      ExternalParameterizedVerilogNativeFallback
        .lowerRetainedIntegerHelpers(
          moduleCollisionSource,
          "RetainedIntegerModuleCollisionTop"
        )
    }
    assert(
      moduleFailure.code ==
        "SPINAL-PARAMETERIZED-VERILOG-NATIVE-INT-HELPER-NAME-COLLISION"
    )

    val taskSource =
      """module RetainedIntegerTaskCollisionTop #(
        |  parameter integer WIDTH = 5
        |) (
        |  output reg observed
        |);
        |  task morphhdl_address_width;
        |    input integer value;
        |    begin
        |      observed = (value != 0);
        |    end
        |  endtask
        |  initial morphhdl_address_width(WIDTH);
        |endmodule
        |""".stripMargin
    val failure = intercept[ParameterizedVerilogException] {
      ExternalParameterizedVerilogNativeFallback
        .lowerRetainedIntegerHelpers(
          taskSource,
          "RetainedIntegerTaskCollisionTop"
        )
    }
    assert(
      failure.code ==
        "SPINAL-PARAMETERIZED-VERILOG-NATIVE-INT-HELPER-NAME-COLLISION"
    )
  }

  test("preprocessor definitions continuations and invocations remain opaque") {
    val source =
      """`define morphhdl_address_width(x) ((x) + 1)
        |`define USER_HELPER(x) \
        |  morphhdl_ceil_log2(x)
        |module RetainedIntegerMacroOpaqueTop #(
        |  parameter integer WIDTH = 5
        |) (
        |  output wire observed
        |);
        |  assign observed =
        |    (`morphhdl_address_width(WIDTH) != 0) |
        |    (`USER_HELPER(morphhdl_address_width(WIDTH)) != 0);
        |endmodule
        |""".stripMargin

    assert(
      ExternalParameterizedVerilogNativeFallback
        .lowerRetainedIntegerHelpers(
          source,
          "RetainedIntegerMacroOpaqueTop"
        ) == source
    )

    val incomplete = "`define INCOMPLETE(x) \\\n"
    val incompleteFailure = intercept[ParameterizedVerilogException] {
      ExternalParameterizedVerilogNativeFallback
        .lowerRetainedIntegerHelpers(incomplete, "IncompleteDirectiveTop")
    }
    assert(
      incompleteFailure.code ==
        "SPINAL-PARAMETERIZED-VERILOG-NATIVE-INT-HELPER-LEXICAL-ERROR"
    )
  }

  test("macro identifiers reserve portable helper names without exposing macro bodies") {
    val source =
      """`define clog2(x) (x)
        |`define USER_HELPER(x) \
        |  morphhdl_ceil_log2(x)
        |module RetainedIntegerMacroReserveTop #(
        |  parameter integer WIDTH = 5
        |) (
        |  output wire observed
        |);
        |  assign observed = (`USER_HELPER(WIDTH) != 0) |
        |    (morphhdl_address_width(WIDTH) != 0);
        |endmodule
        |""".stripMargin

    val lowered = ExternalParameterizedVerilogNativeFallback
      .lowerRetainedIntegerHelpers(
        source,
        "RetainedIntegerMacroReserveTop"
      )
    assert(lowered.contains("`define clog2(x) (x)"))
    assert(lowered.contains("morphhdl_ceil_log2(x)"))
    assert(lowered.contains("`USER_HELPER(WIDTH)"))
    assert(lowered.contains("function integer clog2_1;"))
    assert(lowered.contains("clog2_1(WIDTH, 1)"))

    val collisionSource =
      """`define morphhdl_address_width(x) ((x) + 1)
        |module RetainedIntegerMacroCollisionTop #(
        |  parameter integer WIDTH = 5
        |) (
        |  output wire observed
        |);
        |  assign observed = (morphhdl_address_width(WIDTH) != 0);
        |endmodule
        |""".stripMargin
    val failure = intercept[ParameterizedVerilogException] {
      ExternalParameterizedVerilogNativeFallback
        .lowerRetainedIntegerHelpers(
          collisionSource,
          "RetainedIntegerMacroCollisionTop"
        )
    }
    assert(
      failure.code ==
        "SPINAL-PARAMETERIZED-VERILOG-NATIVE-INT-HELPER-NAME-COLLISION"
    )
  }

  test("opaque fake module headers cannot redirect portable helper insertion") {
    val source =
      """`define FAKE_MODULE_HEADER \
        |  module RetainedIntegerOpaqueModuleHeaderTop (
        |/*
        |module RetainedIntegerOpaqueModuleHeaderTop (
        |*/
        |module RetainedIntegerOpaqueModuleHeaderTop #(
        |  parameter integer WIDTH = 5
        |) (
        |  output wire observed
        |);
        |  assign observed = (morphhdl_address_width(WIDTH) != 0);
        |endmodule
        |""".stripMargin

    val lowered = ExternalParameterizedVerilogNativeFallback
      .lowerRetainedIntegerHelpers(
        source,
        "RetainedIntegerOpaqueModuleHeaderTop"
      )
    assert(lowered.contains("`define FAKE_MODULE_HEADER \\\n  module"))
    assert(lowered.contains("/*\nmodule RetainedIntegerOpaqueModuleHeaderTop (\n*/"))
    assert(lowered.contains("function integer clog2;"))
    assert(lowered.contains("clog2(WIDTH, 1)"))
  }

  test("duplicate canonical portable helper declarations fail closed") {
    val source =
      moduleWithPortableHelpers(
        "RetainedIntegerDuplicatePortableHelperTop",
        Vector(portableHelper("clog2"), portableHelper("clog2"))
      )
    val failure = intercept[ParameterizedVerilogException] {
      ExternalParameterizedVerilogNativeFallback
        .lowerRetainedIntegerHelpers(
          source,
          "RetainedIntegerDuplicatePortableHelperTop"
        )
    }
    assert(
      failure.code ==
        "SPINAL-PARAMETERIZED-VERILOG-LOG-HELPER-AMBIGUOUS"
    )
  }

  test("commented portable helper lookalikes are not reused") {
    val commented = portableHelper("clog2").replace(
      "    input integer value;",
      "    input integer value;\n    // synthesis translate_off"
    )
    val source = moduleWithPortableHelpers(
      "RetainedIntegerCommentedPortableHelperTop",
      Vector(commented)
    )
    val lowered = ExternalParameterizedVerilogNativeFallback
      .lowerRetainedIntegerHelpers(
        source,
        "RetainedIntegerCommentedPortableHelperTop"
      )
    assert(lowered.contains("// synthesis translate_off"))
    assert(lowered.contains("function integer clog2;"))
    assert(lowered.contains("function integer clog2_1;"))
    assert(lowered.contains("clog2_1(WIDTH, 1)"))

    val attributed = moduleWithPortableHelpers(
      "RetainedIntegerAttributedPortableHelperTop",
      Vector("  (* keep = \"true\" *)\n" + portableHelper("clog2"))
    )
    val attributedLowered = ExternalParameterizedVerilogNativeFallback
      .lowerRetainedIntegerHelpers(
        attributed,
        "RetainedIntegerAttributedPortableHelperTop"
      )
    assert(attributedLowered.contains("(* keep = \"true\" *)"))
    assert(attributedLowered.contains("function integer clog2_1;"))
    assert(attributedLowered.contains("clog2_1(WIDTH, 1)"))
  }

  test("retained helpers require exactly one nonempty top-level argument") {
    val malformedCalls = Vector(
      "morphhdl_address_width()",
      "morphhdl_address_width(/* comment only */)",
      "morphhdl_address_width((/* nested comment only */))",
      "morphhdl_address_width({})",
      "morphhdl_address_width(([WIDTH)] )",
      "morphhdl_address_width(WIDTH, EXTRA)",
      "morphhdl_address_width(, WIDTH)",
      "morphhdl_address_width(WIDTH,)"
    )
    malformedCalls.foreach { call =>
      val source = helperCallModule("RetainedIntegerMalformedCallTop", call)
      val failure = intercept[ParameterizedVerilogException] {
        ExternalParameterizedVerilogNativeFallback
          .lowerRetainedIntegerHelpers(
            source,
            "RetainedIntegerMalformedCallTop"
          )
      }
      assert(
        failure.code ==
          "SPINAL-PARAMETERIZED-VERILOG-NATIVE-INT-HELPER-MALFORMED",
        call
      )
    }
  }

  test("nested calls and concatenations do not create top-level helper arguments") {
    val source = helperCallModule(
      "RetainedIntegerNestedArgumentTop",
      "morphhdl_address_width(select_width(WIDTH, 2) + " +
        "{WIDTH[1:0], WIDTH[3:2]})"
    )
    val lowered = ExternalParameterizedVerilogNativeFallback
      .lowerRetainedIntegerHelpers(
        source,
        "RetainedIntegerNestedArgumentTop"
      )
    assert(
      lowered.contains(
        "clog2(select_width(WIDTH, 2) + {WIDTH[1:0], WIDTH[3:2]}, 1)"
      )
    )
  }

  private def helperCallModule(name: String, call: String): String = {
    s"""module $name #(
       |  parameter integer WIDTH = 5,
       |  parameter integer EXTRA = 2
       |) (
       |  output wire observed
       |);
       |  assign observed = ($call != 0);
       |endmodule
       |""".stripMargin
  }

  private def moduleWithPortableHelpers(
      name: String,
      helpers: Vector[String]
  ): String = {
    s"""module $name #(
       |  parameter integer WIDTH = 5
       |) (
       |  output wire observed
       |);
       |${helpers.mkString("\n")}
       |  assign observed = (morphhdl_address_width(WIDTH) != 0);
       |endmodule
       |""".stripMargin
  }

  private def portableHelper(name: String): String =
    s"""  function integer $name;
       |    input integer value;
       |    input integer minimum_result;
       |    integer remaining;
       |    begin
       |      $name = 0;
       |      for (remaining = value - 1; remaining > 0; remaining = remaining >> 1) begin
       |        $name = $name + 1;
       |      end
       |      if ($name < minimum_result) begin
       |        $name = minimum_result;
       |      end
       |    end
       |  endfunction""".stripMargin
}
