package morphhdl.backend.verilog2001

import morphhdl.paramrtl.BoolExpr.{LocalParameterRef => BoolLocalParameterRef, ParameterRef => BoolParameterRef}
import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}
import morphhdl.paramrtl.IntExpr.{
  Add,
  Literal,
  LocalParameterRef,
  Multiply,
  ParameterRef,
  Select
}
import morphhdl.paramrtl.ModuleItem.{ContinuousAssign, GenerateCase}
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.RtlExpr.Ref
import morphhdl.paramrtl.Signedness.Unsigned
import morphhdl.paramrtl._
import org.scalatest.funsuite.AnyFunSuite

class GenerateCaseEmitterTests extends AnyFunSuite {
  test("emits a strict Verilog-2001 generate case with sorted signed literals and default") {
    val verilog = emit(design())
    val expected =
      """module CaseWire #(
        |  parameter integer MODE = 2
        |) (
        |  output wire [7:0] aux,
        |  input  wire [7:0] din,
        |  output wire [7:0] dout
        |);
        |
        |  localparam integer LOCAL_MODE = MODE + 1;
        |  localparam integer PICK_LOCAL = 1;
        |
        |  generate
        |    case ((PICK_LOCAL == 1) ? LOCAL_MODE : MODE)
        |      -1: begin : g_minus_one
        |        assign aux = din;
        |        assign dout = din;
        |      end
        |      2: begin : g_two
        |        assign aux = din;
        |        assign dout = din;
        |      end
        |      10: begin : g_ten
        |        assign aux = din;
        |        assign dout = din;
        |      end
        |      default: begin : g_default
        |        assign aux = din;
        |        assign dout = din;
        |      end
        |    endcase
        |  endgenerate
        |
        |endmodule
        |""".stripMargin

    assert(verilog == expected)
  }

  test("emits numeric choice order and branch item order independently of construction order") {
    val normal = emit(design(reverseChoices = false, reverseBranchItems = false))
    val reversed = emit(design(reverseChoices = true, reverseBranchItems = true))

    assert(normal == reversed)
    assert(normal.indexOf("-1: begin") < normal.indexOf("2: begin"))
    assert(normal.indexOf("2: begin") < normal.indexOf("10: begin"))
    assert(normal.indexOf("assign aux = din;") < normal.indexOf("assign dout = din;"))
  }

  test("preserves selector arithmetic and conditional precedence") {
    val arithmetic = emit(simpleDesign(Multiply(Add(ParameterRef("MODE"), Literal(1)), Literal(2))))
    assert(caseLine(arithmetic) == "case ((MODE + 1) * 2)")

    val conditional = emit(simpleDesign(
      Select(BoolParameterRef("PICK"), Add(ParameterRef("MODE"), Literal(1)), Literal(-1)),
      booleanParameters = Vector(BooleanParameter("PICK", default = false))
    ))
    assert(caseLine(conditional) == "case ((PICK == 1) ? MODE + 1 : -1)")
  }

  test("rejects choice literals outside the portable signed 32-bit target range") {
    val outside = BigInt(Int.MaxValue) + 1
    val invalid = simpleDesign(Literal(0), choices = Vector(
      GenerateCaseChoice(outside, block("g_outside", reverse = false))
    ))

    val diagnostics = emitFailure(invalid)
    assert(diagnostics.codes.contains("V2001-INTEGER-OUT-OF-RANGE"), diagnostics.values.mkString("\n"))
    assert(
      diagnostics.values.exists(diagnostic =>
        diagnostic.code == "V2001-INTEGER-OUT-OF-RANGE" && diagnostic.path.contains(outside.toString)
      ),
      diagnostics.values.mkString("\n")
    )
  }

  test("rejects a selector whose full legal domain exceeds signed 32-bit target range") {
    val outside = BigInt(Int.MaxValue) + 1
    val parameter = IntegerParameter(
      "MODE",
      default = 0,
      constraints = Vector(MinInclusive(0), MaxInclusive(outside))
    )
    val invalid = simpleDesign(ParameterRef("MODE"), parameters = Vector(parameter))

    val diagnostics = emitFailure(invalid)
    assert(
      diagnostics.values.exists(diagnostic =>
        diagnostic.code == "V2001-INTEGER-EXPRESSION-OUT-OF-RANGE" && diagnostic.path.last == "selector"
      ),
      diagnostics.values.mkString("\n")
    )
  }

  test("rejects reserved identifiers in explicit and default case labels") {
    val reservedChoice = simpleDesign(
      Literal(0),
      choices = Vector(GenerateCaseChoice(0, block("case", reverse = false)))
    )
    assert(emitFailure(reservedChoice).codes.contains("V2001-RESERVED-IDENTIFIER"))

    val base = simpleDesign(Literal(0))
    val top = base.modules.head
    val generate = top.items.head.asInstanceOf[GenerateCase]
    val reservedDefault = Design(
      top.name,
      Vector(top.copy(items = Vector(generate.copy(default = block("default", reverse = false)))))
    )
    assert(emitFailure(reservedDefault).codes.contains("V2001-RESERVED-IDENTIFIER"))
  }

  private def design(
      reverseChoices: Boolean = false,
      reverseBranchItems: Boolean = false
  ): Design = {
    val choices = Vector(
      GenerateCaseChoice(10, block("g_ten", reverseBranchItems)),
      GenerateCaseChoice(-1, block("g_minus_one", !reverseBranchItems)),
      GenerateCaseChoice(2, block("g_two", reverseBranchItems))
    )
    val orderedChoices = if (reverseChoices) choices.reverse else choices
    val selector = Select(
      BoolLocalParameterRef("PICK_LOCAL"),
      LocalParameterRef("LOCAL_MODE"),
      ParameterRef("MODE")
    )
    val packed = PackedBits(Literal(8), Unsigned)
    val top = ModuleDef(
      name = "CaseWire",
      parameters = Vector(
        IntegerParameter("MODE", 2, Vector(MinInclusive(-1), MaxInclusive(10)))
      ),
      ports = Vector(
        Port("aux", Output, packed),
        Port("din", Input, packed),
        Port("dout", Output, packed)
      ),
      items = Vector(GenerateCase(selector, orderedChoices, block("g_default", reverseBranchItems))),
      localParameters = Vector(IntegerLocalParameter("LOCAL_MODE", Add(ParameterRef("MODE"), Literal(1)))),
      booleanLocalParameters = Vector(
        BooleanLocalParameter("PICK_LOCAL", BoolExpr.Literal(true))
      )
    )
    Design(top.name, Vector(top))
  }

  private def simpleDesign(
      selector: IntExpr,
      choices: Vector[GenerateCaseChoice] = Vector(
        GenerateCaseChoice(0, block("g_zero", reverse = false))
      ),
      parameters: Vector[IntegerParameter] = Vector(
        IntegerParameter("MODE", 0, Vector(MinInclusive(0), MaxInclusive(4)))
      ),
      booleanParameters: Vector[BooleanParameter] = Vector.empty
  ): Design = {
    val packed = PackedBits(Literal(8), Unsigned)
    val top = ModuleDef(
      "SimpleCase",
      parameters,
      Vector(Port("aux", Output, packed), Port("din", Input, packed), Port("dout", Output, packed)),
      Vector(GenerateCase(selector, choices, block("g_default", reverse = false))),
      booleanParameters = booleanParameters
    )
    Design(top.name, Vector(top))
  }

  private def block(label: String, reverse: Boolean): GenerateBlock = {
    val assignments = Vector(
      ContinuousAssign(Ref("dout"), Ref("din")),
      ContinuousAssign(Ref("aux"), Ref("din"))
    )
    GenerateBlock(label, if (reverse) assignments.reverse else assignments)
  }

  private def caseLine(verilog: String): String =
    verilog.split("\n").iterator.map(_.trim).find(_.startsWith("case (")).getOrElse(fail(verilog))

  private def emit(design: Design): String = Verilog2001Emitter.emit(design) match {
    case Right(value)      => value
    case Left(diagnostics) => fail(diagnostics.values.mkString("\n"))
  }

  private def emitFailure(design: Design): DiagnosticSet = Verilog2001Emitter.emit(design) match {
    case Left(diagnostics) => diagnostics
    case Right(verilog)    => fail(s"Expected target diagnostics, emitted:\n$verilog")
  }
}
