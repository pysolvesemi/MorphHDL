package spinal.core

import java.nio.file.Files
import java.nio.charset.StandardCharsets
import scala.sys.process._
import morphhdl.MorphVerilog
import morphhdl.frontend.HdlInt
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.internals._

/** Increment 59d native construction contracts, before MorphHDL capture. */
class NativeSymbolicWidthProvenanceTests extends AnyFunSuite {
  private def generate(body: => Component): Unit = {
    SpinalConfig(targetDirectory = Files.createTempDirectory("native-symbolic-width-").toString,
      headerWithDate = false, headerWithRepoHash = false).generateVerilog(body)
  }

  private def sameRoot(result: BaseType, source: BaseType, width: Int): Unit = {
    assert(result.getBitsWidth == width)
    val actual = ParameterizedWidth.expressionOf(result).get
    val original = ParameterizedWidth.expressionOf(source).get
    assert(actual.default == width)
    assert(actual.parameterRoots.size == original.parameterRoots.size)
    actual.parameterRoots.zip(original.parameterRoots).foreach { case (a, b) => assert(a eq b) }
  }

  test("natural UInt widening, min/max, clone, HardType and RegNext preserve symbolic widths") {
    val width = HdlInt.param("WIDTH", 5, 1, 32)
    generate(new Component {
      val a = in(UInt(width bits))
      val b = in(UInt(width bits))
      val sum = a +^ b
      val product = a * b
      assert(!sum.isFixedWidth, "metadata transfer must not fix native inferred widths")
      assert(!product.isFixedWidth)
      val smallest = a.min(b)
      val largest = a.max(b)
      val resized = product.resize(width.asElabInt + 1)
      val fixedResize = product.resize(6)
      sameRoot(resized, a, 6)
      assert(ParameterizedWidth.expressionOf(fixedResize).isEmpty)
      val resizeNode = resized.head.source.asInstanceOf[Resize]
      assert(ParameterizedWidth.resizeExpressionOf(resizeNode).nonEmpty)
      val cloned = cloneOf(product)
      cloned := product
      val directClone = sum.clone
      directClone := sum
      val crafted = HardType(product)()
      crafted := product
      val registered = RegNext(sum)
      sameRoot(sum, a, 6)
      sameRoot(product, a, 10)
      sameRoot(smallest, a, 5)
      sameRoot(largest, a, 5)
      sameRoot(cloned, a, 10)
      sameRoot(directClone, a, 6)
      sameRoot(crafted, a, 10)
      sameRoot(registered, a, 6)
      val result = out(cloneOf(registered))
      result := registered
    })
  }

  test("native SInt expand preserves exact high-bit identity and signed widening widths") {
    val width = HdlInt.param("WIDTH", 5, 1, 32)
    generate(new Component {
      val a = in(SInt(width bits))
      val b = in(SInt(width bits))
      val sign = a.sign
      val fixedBit = a(4)
      val signAccess = sign.head.source.asInstanceOf[BitVectorBitAccessFixed]
      val fixedAccess = fixedBit.head.source.asInstanceOf[BitVectorBitAccessFixed]
      assert(NativeWidthProvenance.isHighBit(signAccess))
      assert(!NativeWidthProvenance.isHighBit(fixedAccess))
      signAccess.bitId = 3
      assert(!NativeWidthProvenance.isHighBit(signAccess))
      signAccess.bitId = 4
      val sum = a +^ b
      val product = a * b
      sameRoot(sum, a, 6)
      sameRoot(product, a, 10)
      sameRoot(a.min(b), a, 5)
      sameRoot(a.max(b), a, 5)
      val result = out(cloneOf(product))
      result := product
    })
  }

  test("mux selects native maximum geometry across unequal widths and independent roots") {
    val leftWidth = HdlInt.param("LEFT_WIDTH", 5, 1, 32)
    val rightWidth = HdlInt.param("RIGHT_WIDTH", 8, 1, 32)
    generate(new Component {
      val a = in(UInt(leftWidth bits))
      val b = in(UInt(rightWidth bits))
      val select = in Bool()
      val selected = Mux(select, a, b)
      val product = a * b
      assert(selected.getBitsWidth == 8)
      assert(product.getBitsWidth == 13)
      for (value <- Vector(selected, product)) {
        val expression = ParameterizedWidth.expressionOf(value).get
        val roots = expression.parameterRoots
        assert(roots.exists(_ eq ParameterizedWidth.expressionOf(a).get.parameterRoots.head))
        assert(roots.exists(_ eq ParameterizedWidth.expressionOf(b).get.parameterRoots.head))
      }
      val result = out(cloneOf(selected))
      result := selected
    })
  }

  test("plain concrete construction retains native inference and carries no symbolic width") {
    generate(new Component {
      val a = in UInt(5 bits)
      val b = in UInt(8 bits)
      val sum = a +^ b
      val product = a * b
      val selected = Mux(a < b, a, b)
      assert(sum.getBitsWidth == 9 && !sum.isFixedWidth)
      assert(product.getBitsWidth == 13 && !product.isFixedWidth)
      val registered = RegNext(sum)
      assert(selected.getBitsWidth == 8)
      assert(registered.getBitsWidth == 9)
      Vector(a, b, sum, product, selected, registered).foreach { value =>
        assert(ParameterizedWidth.expressionOf(value).isEmpty)
      }
      val result = out UInt(9 bits)
      result := registered
    })
  }

  test("symbolic source literal resize and msb simulate across narrowing and signed extension") {
    val directory = Files.createTempDirectory("native-resize-publication-")
    val width = HdlInt.param("WIDTH", 5, 1, 32)
    val config = SpinalConfig(targetDirectory = directory.toString,
      headerWithDate = false)
    config.netlistFileName = "NativeResizePublication.v"
    MorphVerilog(config) {
      new Component {
        setDefinitionName("NativeResizePublication")
        val unsignedSource = in UInt(width bits)
        val signedSource = in SInt(width bits)
        val fixedSource = in UInt(5 bits)
        val unsignedResult = out UInt(5 bits)
        val signedResult = out SInt(5 bits)
        val grownResult = out UInt(width bits)
        val sign = out Bool()
        unsignedResult := unsignedSource.resize(5)
        signedResult := signedSource.resize(5)
        grownResult := fixedSource.resize(width.asElabInt) ^ unsignedSource
        sign := signedSource.msb
      }
    }
    val rtl = new String(Files.readAllBytes(directory.resolve("NativeResizePublication.v")), StandardCharsets.UTF_8)
    assert(rtl.contains("[(WIDTH) - 1]"), rtl)
    val bench = """module tb;
      |parameter WIDTH = 5;
      |reg [WIDTH-1:0] u;
      |reg signed [WIDTH-1:0] s;
      |reg [4:0] fixed_source;
      |wire [4:0] ur, sr;
      |wire [WIDTH-1:0] grown;
      |wire sign;
      |reg [4:0] expected_u, expected_s;
      |reg [WIDTH-1:0] expected_grown;
      |integer n;
      |NativeResizePublication #(.WIDTH(WIDTH)) dut(
      |.unsignedSource(u), .signedSource(s), .fixedSource(fixed_source),
      |.unsignedResult(ur), .signedResult(sr), .grownResult(grown), .sign(sign));
      |initial begin
      |for(n=0; n < (1 << WIDTH); n=n+1) begin
      |u=n; s=n; fixed_source=(n*7)+3; expected_u=u; expected_s=s;
      |expected_grown=fixed_source; expected_grown=expected_grown ^ u; #1;
      |if(ur !== expected_u || sr !== expected_s || grown !== expected_grown || sign !== s[WIDTH-1]) begin
      |$display("FAIL WIDTH=%0d n=%0d ur=%0h sr=%0h", WIDTH, n, ur, sr); $finish;
      |end
      |end
      |$display("PASS"); $finish;
      |end
      |endmodule
      |""".stripMargin
    Files.write(directory.resolve("tb.v"), bench.getBytes(StandardCharsets.UTF_8))
    for (bits <- Vector(1, 5, 8)) {
      val binary = directory.resolve(s"sim_$bits")
      val command = Seq("iverilog", "-g2001", "-s", "tb", s"-Ptb.WIDTH=$bits", "-o", binary.toString,
        directory.resolve("NativeResizePublication.v").toString, directory.resolve("tb.v").toString)
      assert(Process(command).! == 0)
      val output = Process(Seq("vvp", binary.toString)).!!
      assert(output.split("\n").exists(_.trim == "PASS") && !output.contains("FAIL"), output)
    }
  }
}
