package morphhdl.backend.verilog2001

import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}
import morphhdl.paramrtl.IntExpr.{Add, AddressWidth, Divide, GenerateIndexRef, Literal, Multiply, ParameterRef}
import morphhdl.paramrtl.ModuleItem.{
  ContinuousAssign,
  GenerateFor,
  ModuleInstance,
  SynchronousReadFirstSinglePortMemory
}
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.RtlExpr.{IndexedPartSelect, Ref}
import morphhdl.paramrtl.Signedness.Unsigned
import morphhdl.paramrtl._
import org.scalatest.funsuite.AnyFunSuite

class AddressWidthEmitterTests extends AnyFunSuite {
  test("emits one exact handwritten-style Verilog-2001 constant function and a floor-one call") {
    val verilog = emit(localDesign(AddressWidth(ParameterRef("DEPTH"))))

    assert(verilog.contains(portableFunction), verilog)
    assert(verilog.contains("localparam integer VALUE = clog2(DEPTH, 1);"), verilog)
    assert(occurrences(verilog, "function integer clog2;") == 1, verilog)
    assert(!verilog.contains("$clog2"), verilog)
  }

  test("emits the helper only in modules whose rendered expressions need it") {
    val plain = emit(localDesign(Add(ParameterRef("DEPTH"), Literal(1))))
    assert(!plain.contains("function integer clog2;"), plain)

    val withTwoUses = ModuleDef(
      "TwoAddressWidths",
      Vector(boundedDepth()),
      Vector(
        Port("din", Input, PackedBits(AddressWidth(ParameterRef("DEPTH")), Unsigned)),
        Port("dout", Output, PackedBits(AddressWidth(ParameterRef("DEPTH")), Unsigned))
      ),
      Vector(ContinuousAssign(Ref("dout"), Ref("din"))),
      localParameters = Vector(
        IntegerLocalParameter("ADDRESS_WIDTH", AddressWidth(ParameterRef("DEPTH")))
      )
    )
    val rendered = emit(Design(withTwoUses.name, Vector(withTwoUses)))
    assert(occurrences(rendered, "function integer clog2;") == 1, rendered)
  }

  test("supports forward calls in ANSI port widths and surrounding arithmetic") {
    val direct = emit(identityDesign(AddressWidth(ParameterRef("DEPTH"))))
    val call = "clog2(DEPTH, 1)"
    assert(direct.contains(s"[($call)-1:0] din"), direct)
    assert(direct.contains(s"[($call)-1:0] dout"), direct)
    assert(direct.indexOf(call) < direct.indexOf("function integer clog2;"), direct)

    val operandArithmetic = emit(localDesign(AddressWidth(Add(ParameterRef("DEPTH"), Literal(0)))))
    assert(
      operandArithmetic.contains("localparam integer VALUE = clog2(DEPTH + 0, 1);"),
      operandArithmetic
    )

    val resultArithmetic = emit(localDesign(Add(AddressWidth(ParameterRef("DEPTH")), Literal(1))))
    assert(
      resultArithmetic.contains("localparam integer VALUE = clog2(DEPTH, 1) + 1;"),
      resultArithmetic
    )
  }

  test("uses the single-call lowering in memory generate and indexed part-select contexts") {
    val memoryVerilog = emit(memoryDesign())
    val capacityCall = "clog2(CAPACITY, 1)"
    assert(memoryVerilog.contains(s"reg [7:0] memory [0:($capacityCall)-1];"), memoryVerilog)
    assert(memoryVerilog.contains(s"if (address < $capacityCall) begin"), memoryVerilog)

    val generateVerilog = emit(generateCountDesign())
    val depthCall = "clog2(DEPTH, 1)"
    assert(
      generateVerilog.contains(s"for (i = 0; i < $depthCall; i = i + 1) begin : g_width"),
      generateVerilog
    )

    val sliceVerilog = emit(indexedSliceDesign())
    assert(sliceVerilog.contains(s"din[i * $depthCall +: $depthCall]"), sliceVerilog)
    assert(sliceVerilog.contains(s"dout[i * $depthCall +: $depthCall]"), sliceVerilog)
  }

  test("retains strict signed-32 operand checks") {
    val outside = BigInt(Int.MaxValue) + 1
    Verilog2001Emitter.emit(localDesign(AddressWidth(Literal(outside)))) match {
      case Left(diagnostics) =>
        val failures = diagnostics.values.filter(_.code == "V2001-INTEGER-EXPRESSION-OUT-OF-RANGE")
        assert(failures.exists(_.path.last == "operand"), failures.mkString("\n"))
      case Right(verilog) => fail(s"Expected target range diagnostic, emitted:\n$verilog")
    }
  }

  test("renders 900 directly nested address widths linearly with no log-specific cap") {
    val nested = (1 to 900).foldLeft[IntExpr](ParameterRef("DEPTH")) {
      case (value, _) => AddressWidth(value)
    }
    val verilog = emit(localDesign(nested))
    val valueLine = verilog.split("\\n").iterator.find(_.contains("localparam integer VALUE =")).get

    assert(occurrences(valueLine, "clog2(") == 900, valueLine)
    assert(valueLine.length < 30000, valueLine.length.toString)
  }

  test("validates and renders a 5000-node operand without recursive descent") {
    var operand: IntExpr = Literal(1)
    (1 to 5000).foreach { _ => operand = Add(operand, Literal(1)) }

    val verilog = emit(localDesign(AddressWidth(operand)))
    assert(verilog.contains("localparam integer VALUE = clog2(1 + 1 + 1"), verilog)
    assert(verilog.contains(", 1);"), verilog)

    def nest(base: IntExpr): IntExpr =
      (1 to 4096).foldLeft(base) { case (value, _) => AddressWidth(value) }
    Verilog2001Emitter.emit(identityDesign(nest(Divide(Literal(1), Literal(0))))) match {
      case Left(diagnostics) =>
        assert(diagnostics.codes.contains("PRTL-DIVISOR-MAY-BE-ZERO"), diagnostics.values.mkString("\n"))
      case Right(value) => fail(s"Expected inner divisor diagnostic, emitted:\n$value")
    }
  }

  private val portableFunction =
    """  function integer clog2;
      |    input integer value;
      |    input integer minimum_result;
      |    integer remaining;
      |    begin
      |      clog2 = 0;
      |      for (remaining = value - 1; remaining > 0; remaining = remaining >> 1) begin
      |        clog2 = clog2 + 1;
      |      end
      |      if (clog2 < minimum_result) begin
      |        clog2 = minimum_result;
      |      end
      |    end
      |  endfunction""".stripMargin

  private def occurrences(value: String, needle: String): Int =
    value.sliding(needle.length).count(_ == needle)

  private def boundedDepth(name: String = "DEPTH"): IntegerParameter =
    IntegerParameter(
      name,
      5,
      Vector(MinInclusive(1), MaxInclusive(BigInt(Int.MaxValue) - 1))
    )

  private def identityDesign(width: IntExpr): Design = {
    val packed = PackedBits(width, Unsigned)
    val module = ModuleDef(
      "AddressWidthPort",
      Vector(boundedDepth()),
      Vector(Port("din", Input, packed), Port("dout", Output, packed)),
      Vector(ContinuousAssign(Ref("dout"), Ref("din")))
    )
    Design(module.name, Vector(module))
  }

  private def localDesign(expression: IntExpr): Design = {
    val packed = PackedBits(Literal(8), Unsigned)
    val module = ModuleDef(
      "AddressWidthLocal",
      Vector(boundedDepth()),
      Vector(Port("din", Input, packed), Port("dout", Output, packed)),
      Vector(ContinuousAssign(Ref("dout"), Ref("din"))),
      localParameters = Vector(IntegerLocalParameter("VALUE", expression))
    )
    Design(module.name, Vector(module))
  }

  private def memoryDesign(): Design = {
    val elementType = PackedBits(Literal(8), Unsigned)
    val module = ModuleDef(
      "AddressWidthMemory",
      Vector(boundedDepth("CAPACITY")),
      Vector(
        Port("address", Input, PackedBits(Literal(5), Unsigned)),
        Port("clk", Input, PackedBits(Literal(1), Unsigned)),
        Port("read_data", Output, elementType),
        Port("read_enable", Input, PackedBits(Literal(1), Unsigned)),
        Port("write_data", Input, elementType),
        Port("write_enable", Input, PackedBits(Literal(1), Unsigned))
      ),
      Vector(
        SynchronousReadFirstSinglePortMemory(
          "p_memory",
          "memory",
          Ref("clk"),
          Ref("read_enable"),
          Ref("write_enable"),
          Ref("address"),
          Ref("write_data"),
          Ref("read_data"),
          elementType,
          AddressWidth(ParameterRef("CAPACITY"))
        )
      )
    )
    Design(module.name, Vector(module))
  }

  private def generateCountDesign(): Design = {
    val packed = PackedBits(Literal(8), Unsigned)
    val module = ModuleDef(
      "AddressWidthGenerate",
      Vector(boundedDepth()),
      Vector(Port("din", Input, packed), Port("dout", Output, packed)),
      Vector(
        ContinuousAssign(Ref("dout"), Ref("din")),
        GenerateFor("g_width", "i", AddressWidth(ParameterRef("DEPTH")), Vector.empty)
      )
    )
    Design(module.name, Vector(module))
  }

  private def indexedSliceDesign(): Design = {
    val width = AddressWidth(ParameterRef("DEPTH"))
    val childBits = PackedBits(width, Unsigned)
    val child = ModuleDef(
      "AddressWidthSliceChild",
      Vector(boundedDepth()),
      Vector(Port("din", Input, childBits), Port("dout", Output, childBits)),
      Vector(ContinuousAssign(Ref("dout"), Ref("din")))
    )
    val parentBits = PackedBits(Multiply(Literal(1), width), Unsigned)
    def slice(name: String): IndexedPartSelect =
      IndexedPartSelect(Ref(name), Multiply(GenerateIndexRef("i"), width), width)
    val parent = ModuleDef(
      "AddressWidthSliceParent",
      Vector(boundedDepth()),
      Vector(Port("din", Input, parentBits), Port("dout", Output, parentBits)),
      Vector(
        GenerateFor(
          "g_slice",
          "i",
          Literal(1),
          Vector(
            ModuleInstance(
              "child",
              child.name,
              parameterBindings = Vector(ParameterBinding("DEPTH", ParameterRef("DEPTH"))),
              portConnections = Vector(
                PortConnection("din", slice("din")),
                PortConnection("dout", slice("dout"))
              )
            )
          )
        )
      )
    )
    Design(parent.name, Vector(parent, child))
  }

  private def emit(design: Design): String =
    Verilog2001Emitter.emit(design) match {
      case Right(value)      => value
      case Left(diagnostics) => fail(diagnostics.values.mkString("\n"))
    }
}
