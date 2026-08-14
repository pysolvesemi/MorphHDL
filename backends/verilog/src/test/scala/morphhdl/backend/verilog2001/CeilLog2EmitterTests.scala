package morphhdl.backend.verilog2001

import morphhdl.paramrtl.BoolExpr.{LessThan => BoolLessThan}
import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}
import morphhdl.paramrtl.IntExpr.{Add, AddressWidth, CeilLog2, Literal, Min, ParameterRef}
import morphhdl.paramrtl.ModuleItem.ContinuousAssign
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.RtlExpr.Ref
import morphhdl.paramrtl.Signedness.Unsigned
import morphhdl.paramrtl._
import org.scalatest.funsuite.AnyFunSuite

class CeilLog2EmitterTests extends AnyFunSuite {
  test("shares one helper while preserving exact ceil-log2 and address-width floors") {
    val packed = PackedBits(Literal(8), Unsigned)
    val module = ModuleDef(
      "BothPortableLogs",
      Vector(boundedLanes()),
      Vector(Port("din", Input, packed), Port("dout", Output, packed)),
      Vector(ContinuousAssign(Ref("dout"), Ref("din"))),
      localParameters = Vector(
        IntegerLocalParameter("CEILING", CeilLog2(ParameterRef("LANES"))),
        IntegerLocalParameter("ADDRESS", AddressWidth(ParameterRef("LANES")))
      )
    )
    val verilog = emit(Design(module.name, Vector(module)))

    assert(verilog.contains("localparam integer CEILING = morphhdl$ceil_log2(LANES, 0);"), verilog)
    assert(verilog.contains("localparam integer ADDRESS = morphhdl$ceil_log2(LANES, 1);"), verilog)
    assert(occurrences(verilog, "function integer morphhdl$ceil_log2;") == 1, verilog)
    assert(!verilog.contains("$clog2"), verilog)
  }

  test("keeps ceilLog2(1) distinct from the floor-one address-width spelling") {
    val ceil = emit(localDesign(CeilLog2(Literal(1))))
    val address = emit(localDesign(AddressWidth(Literal(1))))

    assert(ceil.contains("localparam integer VALUE = morphhdl$ceil_log2(1, 0);"), ceil)
    assert(address.contains("localparam integer VALUE = morphhdl$ceil_log2(1, 1);"), address)
  }

  test("supports a constant-function call in a forward ANSI port width") {
    val width = CeilLog2(ParameterRef("LANES"))
    val packed = PackedBits(width, Unsigned)
    val module = ModuleDef(
      "CeilLog2Port",
      Vector(boundedLanes(minimum = 2)),
      Vector(Port("din", Input, packed), Port("dout", Output, packed)),
      Vector(ContinuousAssign(Ref("dout"), Ref("din")))
    )
    val verilog = emit(Design(module.name, Vector(module)))
    val call = "morphhdl$ceil_log2(LANES, 0)"

    assert(verilog.contains(s"[($call)-1:0] din"), verilog)
    assert(verilog.indexOf(call) < verilog.indexOf("function integer morphhdl$ceil_log2;"), verilog)
  }

  test("discovers ceil-log2 under a Boolean local expression and emits no unused helpers elsewhere") {
    val packed = PackedBits(Literal(1), Unsigned)
    val withLog = ModuleDef(
      "BooleanCeilLog2",
      Vector(boundedLanes()),
      Vector(Port("din", Input, packed), Port("dout", Output, packed)),
      Vector(ContinuousAssign(Ref("dout"), Ref("din"))),
      booleanLocalParameters = Vector(
        BooleanLocalParameter(
          "SMALL",
          BoolLessThan(CeilLog2(ParameterRef("LANES")), Literal(4))
        )
      )
    )
    val plain = ModuleDef(
      "PlainSibling",
      Vector.empty,
      Vector(Port("din", Input, packed), Port("dout", Output, packed)),
      Vector(ContinuousAssign(Ref("dout"), Ref("din")))
    )
    val verilog = emit(Design(withLog.name, Vector(withLog, plain)))

    assert(occurrences(verilog, "function integer morphhdl$ceil_log2;") == 1, verilog)
    val plainText = verilog.substring(verilog.indexOf("module PlainSibling"))
    assert(!plainText.contains("morphhdl$ceil_log2"), plainText)
  }

  test("retains positive signed-32 target-domain checks for the helper input") {
    val outside = BigInt(Int.MaxValue) + 1
    Verilog2001Emitter.emit(localDesign(CeilLog2(Literal(outside)))) match {
      case Left(diagnostics) =>
        val failures = diagnostics.values.filter(_.code == "V2001-INTEGER-EXPRESSION-OUT-OF-RANGE")
        assert(failures.exists(_.path.last == "operand"), failures.mkString("\n"))
      case Right(verilog) => fail(s"Expected target range diagnostic, emitted:\n$verilog")
    }
  }

  test("renders a 5000-node ceil-log2 operand without a log-specific expansion cap") {
    var operand: IntExpr = Literal(1)
    (1 to 5000).foreach { _ => operand = Add(operand, Literal(1)) }

    val verilog = emit(localDesign(CeilLog2(operand)))
    assert(verilog.contains("localparam integer VALUE = morphhdl$ceil_log2(1 + 1 + 1"), verilog)
    assert(verilog.contains(", 0);"), verilog)
  }

  test("bounds a large shared Min DAG only with the retained MinMax expansion diagnostic") {
    var shared: IntExpr = CeilLog2(ParameterRef("LANES"))
    (1 to 2000).foreach { _ => shared = Min(shared, shared) }

    Verilog2001Emitter.emit(localDesign(shared)) match {
      case Left(diagnostics) =>
        assert(diagnostics.codes == Vector("V2001-MIN-MAX-EXPANSION-TOO-LARGE"), diagnostics.values.mkString("\n"))
      case Right(verilog) => fail(s"Expected MinMax expansion diagnostic, emitted:\n$verilog")
    }
  }

  private def occurrences(value: String, needle: String): Int =
    value.sliding(needle.length).count(_ == needle)

  private def boundedLanes(
      minimum: BigInt = 1,
      maximum: BigInt = BigInt(Int.MaxValue) - 1
  ): IntegerParameter =
    IntegerParameter(
      "LANES",
      minimum.max(BigInt(5)),
      Vector(MinInclusive(minimum), MaxInclusive(maximum))
    )

  private def localDesign(expression: IntExpr): Design = {
    val packed = PackedBits(Literal(8), Unsigned)
    val module = ModuleDef(
      "CeilLog2Local",
      Vector(boundedLanes()),
      Vector(Port("din", Input, packed), Port("dout", Output, packed)),
      Vector(ContinuousAssign(Ref("dout"), Ref("din"))),
      localParameters = Vector(IntegerLocalParameter("VALUE", expression))
    )
    Design(module.name, Vector(module))
  }

  private def emit(design: Design): String =
    Verilog2001Emitter.emit(design) match {
      case Right(value)      => value
      case Left(diagnostics) => fail(diagnostics.values.mkString("\n"))
    }
}
