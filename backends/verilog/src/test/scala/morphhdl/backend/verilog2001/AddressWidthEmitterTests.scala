package morphhdl.backend.verilog2001

import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}
import morphhdl.paramrtl.BoolExpr.{
  And => BoolAnd,
  LessThan => BoolLessThan,
  Not => BoolNot,
  Or => BoolOr,
  ParameterRef => BoolParameterRef
}
import morphhdl.paramrtl.IntExpr.{Add, AddressWidth, Divide, GenerateIndexRef, Literal, Multiply, ParameterRef, Select}
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
  test("emits the exact portable right-associated address-width chain") {
    val verilog = emit(localDesign(AddressWidth(ParameterRef("DEPTH"))))

    assert(verilog.contains(s"localparam integer VALUE = $depthChain;"), verilog)
    assert(!verilog.contains("$clog2"), verilog)
    assert(depthChain.split("DEPTH <=", -1).length - 1 == 30)
    assert(depthChain.contains("(DEPTH <= 1073741824) ? 30 : 31"), depthChain)
  }

  test("parenthesizes address width in ports and surrounding arithmetic") {
    val direct = emit(identityDesign(AddressWidth(ParameterRef("DEPTH"))))
    assert(direct.contains(s"[($depthChain)-1:0] din"), direct)
    assert(direct.contains(s"[($depthChain)-1:0] dout"), direct)

    val operandArithmetic = emit(localDesign(AddressWidth(Add(ParameterRef("DEPTH"), Literal(0)))))
    val groupedOperandChain = depthChain.replace("DEPTH", "DEPTH + 0")
    assert(operandArithmetic.contains(s"localparam integer VALUE = $groupedOperandChain;"), operandArithmetic)

    val resultArithmetic = emit(localDesign(Add(AddressWidth(ParameterRef("DEPTH")), Literal(1))))
    assert(resultArithmetic.contains(s"localparam integer VALUE = ($depthChain) + 1;"), resultArithmetic)
  }

  test("parenthesizes address width in memory bounds and comparisons") {
    val verilog = emit(memoryDesign())
    val capacityChain = depthChain.replace("DEPTH", "CAPACITY")

    assert(verilog.contains(s"reg [7:0] memory [0:($capacityChain)-1];"), verilog)
    assert(verilog.contains(s"if (address < ($capacityChain)) begin"), verilog)
    assert(!verilog.contains("$clog2"), verilog)
  }

  test("parenthesizes address width in generate and indexed part-select contexts") {
    val generateVerilog = emit(generateCountDesign())
    assert(
      generateVerilog.contains(
        s"for (i = 0; i < ($depthChain); i = i + 1) begin : g_width"
      ),
      generateVerilog
    )

    val sliceVerilog = emit(indexedSliceDesign())
    assert(
      sliceVerilog.contains(s"din[i * ($depthChain) +: ($depthChain)]"),
      sliceVerilog
    )
    assert(
      sliceVerilog.contains(s"dout[i * ($depthChain) +: ($depthChain)]"),
      sliceVerilog
    )
  }

  test("retains strict signed-32 operand checks and old expression rendering") {
    val outside = BigInt(Int.MaxValue) + 1
    Verilog2001Emitter.emit(localDesign(AddressWidth(Literal(outside)))) match {
      case Left(diagnostics) =>
        val failures = diagnostics.values.filter(_.code == "V2001-INTEGER-EXPRESSION-OUT-OF-RANGE")
        assert(failures.exists(_.path.last == "operand"), failures.mkString("\n"))
      case Right(verilog) => fail(s"Expected target range diagnostic, emitted:\n$verilog")
    }

    val oldExpression = emit(localDesign(Add(ParameterRef("DEPTH"), Literal(1))))
    assert(oldExpression.contains("localparam integer VALUE = DEPTH + 1;"), oldExpression)
  }

  test("flattens direct nesting and rejects adversarial repeated expansion") {
    val twice = emit(
      localDesign(AddressWidth(AddressWidth(ParameterRef("DEPTH"))))
    )
    assert(
      twice.contains(
        "localparam integer VALUE = (DEPTH <= 4) ? 1 : ((DEPTH <= 16) ? 2 : ((DEPTH <= 256) ? 3 : ((DEPTH <= 65536) ? 4 : 5)));"
      ),
      twice
    )

    val directlyNested = (1 to 64).foldLeft[IntExpr](ParameterRef("DEPTH")) {
      case (value, _) => AddressWidth(value)
    }
    val flattened = emit(localDesign(directlyNested))
    assert(flattened.contains("localparam integer VALUE = 1;"), flattened)
    assert(flattened.length < 1000, flattened)

    val adversarial = (1 to 3).foldLeft[IntExpr](ParameterRef("DEPTH")) {
      case (value, _) => AddressWidth(Add(value, Literal(1)))
    }
    Verilog2001Emitter.emit(localDesign(adversarial)) match {
      case Left(diagnostics) =>
        assert(
          diagnostics.codes.contains("V2001-ADDRESS-WIDTH-EXPANSION-TOO-LARGE"),
          diagnostics.values.mkString("\n")
        )
      case Right(verilog) => fail(s"Expected bounded expansion diagnostic, emitted:\n$verilog")
    }
  }

  test("short-circuits over-budget shared expression DAGs") {
    var shared: IntExpr = ParameterRef("DEPTH")
    (1 to 64).foreach { _ =>
      shared = Add(shared, shared)
    }

    assert(!AddressWidthLowering.expansionWithin(AddressWidth(shared), 4096L))
    Verilog2001Emitter.emit(localDesign(AddressWidth(shared))) match {
      case Left(diagnostics) =>
        assert(
          diagnostics.codes.contains("V2001-ADDRESS-WIDTH-EXPANSION-TOO-LARGE"),
          diagnostics.values.mkString("\n")
        )
      case Right(verilog) => fail(s"Expected bounded public-pipeline rejection, emitted:\n$verilog")
    }
  }

  test("rejects a deep linear operand through the public pipeline without recursive descent") {
    var linear: IntExpr = ParameterRef("DEPTH")
    (1 to 5000).foreach { _ =>
      linear = Add(linear, Literal(1))
    }

    Verilog2001Emitter.emit(localDesign(AddressWidth(linear))) match {
      case Left(diagnostics) =>
        assert(
          diagnostics.codes.contains("V2001-ADDRESS-WIDTH-EXPANSION-TOO-LARGE"),
          diagnostics.values.mkString("\n")
        )
      case Right(verilog) => fail(s"Expected bounded deep-operand rejection, emitted:\n$verilog")
    }
  }

  test("handles deep Boolean selection conditions through the public pipeline") {
    val booleanParameters = Vector(BooleanParameter("ENABLE", default = true))
    def width(condition: BoolExpr): IntExpr =
      AddressWidth(Select(condition, Literal(5), Literal(3)))

    var deepNot: BoolExpr = BoolParameterRef("ENABLE")
    (1 to 5000).foreach { _ => deepNot = BoolNot(deepNot) }
    assertExpansionRejected(identityDesign(width(deepNot), booleanParameters))

    var shared: BoolExpr = BoolParameterRef("ENABLE")
    (1 to 64).foreach { index =>
      shared = if (index % 2 == 0) BoolAnd(shared, shared) else BoolOr(shared, shared)
    }
    assertExpansionRejected(identityDesign(width(shared), booleanParameters))

    var admitted: BoolExpr = BoolParameterRef("ENABLE")
    (1 to 100).foreach { _ => admitted = BoolNot(admitted) }
    val admittedVerilog = emit(identityDesign(width(admitted), booleanParameters))
    assert(!admittedVerilog.contains("$clog2"), admittedVerilog)

    var alternating: IntExpr = Literal(1)
    (1 to 5000).foreach { _ =>
      alternating = Select(BoolLessThan(alternating, Literal(2)), Literal(5), Literal(3))
    }
    assertExpansionRejected(identityDesign(AddressWidth(alternating)))
  }

  test("validates and emits 4096 direct layers without recursive descent") {
    def nest(base: IntExpr): IntExpr =
      (1 to 4096).foldLeft(base) { case (value, _) => AddressWidth(value) }

    val verilog = emit(identityDesign(nest(Literal(5))))
    assert(verilog.contains("input  wire [(1)-1:0] din"), verilog)
    assert(verilog.contains("output wire [(1)-1:0] dout"), verilog)

    Verilog2001Emitter.emit(identityDesign(nest(Divide(Literal(1), Literal(0))))) match {
      case Left(diagnostics) =>
        assert(diagnostics.codes.contains("PRTL-DIVISOR-MAY-BE-ZERO"), diagnostics.values.mkString("\n"))
        assert(
          !diagnostics.codes.contains("PRTL-ADDRESS-WIDTH-OPERAND-NOT-PROVEN-POSITIVE"),
          diagnostics.values.mkString("\n")
        )
      case Right(value) => fail(s"Expected inner divisor diagnostic, emitted:\n$value")
    }
  }

  private lazy val depthChain =
    "(DEPTH <= 2) ? 1 : ((DEPTH <= 4) ? 2 : ((DEPTH <= 8) ? 3 : ((DEPTH <= 16) ? 4 : ((DEPTH <= 32) ? 5 : ((DEPTH <= 64) ? 6 : ((DEPTH <= 128) ? 7 : ((DEPTH <= 256) ? 8 : ((DEPTH <= 512) ? 9 : ((DEPTH <= 1024) ? 10 : ((DEPTH <= 2048) ? 11 : ((DEPTH <= 4096) ? 12 : ((DEPTH <= 8192) ? 13 : ((DEPTH <= 16384) ? 14 : ((DEPTH <= 32768) ? 15 : ((DEPTH <= 65536) ? 16 : ((DEPTH <= 131072) ? 17 : ((DEPTH <= 262144) ? 18 : ((DEPTH <= 524288) ? 19 : ((DEPTH <= 1048576) ? 20 : ((DEPTH <= 2097152) ? 21 : ((DEPTH <= 4194304) ? 22 : ((DEPTH <= 8388608) ? 23 : ((DEPTH <= 16777216) ? 24 : ((DEPTH <= 33554432) ? 25 : ((DEPTH <= 67108864) ? 26 : ((DEPTH <= 134217728) ? 27 : ((DEPTH <= 268435456) ? 28 : ((DEPTH <= 536870912) ? 29 : ((DEPTH <= 1073741824) ? 30 : 31)))))))))))))))))))))))))))))"

  private def boundedDepth(name: String = "DEPTH"): IntegerParameter =
    IntegerParameter(
      name,
      5,
      Vector(MinInclusive(1), MaxInclusive(BigInt(Int.MaxValue) - 1))
    )

  private def identityDesign(
      width: IntExpr,
      booleanParameters: Vector[BooleanParameter] = Vector.empty
  ): Design = {
    val packed = PackedBits(width, Unsigned)
    val module = ModuleDef(
      "AddressWidthPort",
      Vector(boundedDepth()),
      Vector(Port("din", Input, packed), Port("dout", Output, packed)),
      Vector(ContinuousAssign(Ref("dout"), Ref("din"))),
      booleanParameters = booleanParameters
    )
    Design(module.name, Vector(module))
  }

  private def assertExpansionRejected(design: Design): Unit =
    Verilog2001Emitter.emit(design) match {
      case Left(diagnostics) =>
        assert(
          diagnostics.codes.contains("V2001-ADDRESS-WIDTH-EXPANSION-TOO-LARGE"),
          diagnostics.values.mkString("\n")
        )
      case Right(verilog) => fail(s"Expected bounded address-width rejection, emitted:\n$verilog")
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
        Port("write_data", Input, elementType),
        Port("write_enable", Input, PackedBits(Literal(1), Unsigned))
      ),
      Vector(
        SynchronousReadFirstSinglePortMemory(
          "p_memory",
          "memory",
          Ref("clk"),
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
