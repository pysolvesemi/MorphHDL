package spinal.core

import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets.UTF_8
import scala.sys.process.{Process, ProcessLogger}
import morphhdl.{MorphVerilog}
import morphhdl.frontend.HdlInt
import org.scalatest.funsuite.AnyFunSuite

class NativeSymbolicHardwareValueTests extends AnyFunSuite {
  private def run(dir: Path, args: Seq[String]): (Int, String) =
    morphhdl.Increment66ToolEvidence.run(dir,args)
  private class ValueExpressions(value: ElabInt) extends Component {
    val runtime = in UInt(8 bits)
    val shift = in UInt(3 bits)
    val constant = U(value, 8 bits)
    val sum = out UInt(8 bits)
    val joined = out Bits(16 bits)
    val less = out Bool()
    val left = out UInt(8 bits)
    val right = out UInt(8 bits)
    val asCount = out UInt(8 bits)
    val cloneValue = cloneOf(constant)
    cloneValue := constant
    sum := cloneValue + runtime
    joined := constant.asBits ## runtime.asBits
    less := runtime < constant
    left := constant |<< shift
    right := constant |>> shift
    asCount := runtime |<< constant
  }
  private class ConcreteValueExpressions(value: Int) extends Component {
    val runtime = in UInt(8 bits)
    val shift = in UInt(3 bits)
    val constant = U(value, 8 bits)
    val sum = out UInt(8 bits)
    val joined = out Bits(16 bits)
    val less = out Bool()
    val left = out UInt(8 bits)
    val right = out UInt(8 bits)
    val asCount = out UInt(8 bits)
    val cloneValue = cloneOf(constant)
    cloneValue := constant
    sum := cloneValue + runtime
    joined := constant.asBits ## runtime.asBits
    less := runtime < constant
    left := constant |<< shift
    right := constant |>> shift
    asCount := runtime |<< constant
  }
  for (split <- Seq(false, true))
  test(s"unsigned symbolic values retain exact packed type through nested operations, split=$split") {
    val dir = Files.createTempDirectory("symbolic-hardware-values-")
    MorphVerilog(SpinalConfig(targetDirectory = dir.toString,
      oneFilePerComponent = split, headerWithDate = false)) {
      new ValueExpressions(HdlInt.param("BUS_LOG2_BYTES", 3, 2, 5).asElabInt.pow2)
    }
    val rtl = new String(Files.readAllBytes(dir.resolve("ValueExpressions.v")), UTF_8)
    assert(rtl.contains("BUS_LOG2_BYTES") && rtl.contains("1 <<"), rtl)
    for (log <- 2 to 5) {
      val value = 1 << log
      Files.write(dir.resolve("tb.v"), s"""module tb;
        |reg [7:0] runtime; reg [2:0] shift;
        |wire [7:0] sum,left,right,asCount; wire [15:0] joined; wire less;
        |reg [7:0] expectedSum,expectedLeft,expectedRight,expectedCount; integer i;
        |localparam [7:0] V=8'd$value;
        |ValueExpressions #(.BUS_LOG2_BYTES($log)) dut(runtime,shift,sum,joined,less,left,right,asCount);
        |task check; begin
        |expectedSum=V+runtime; expectedLeft=V<<shift; expectedRight=V>>shift; expectedCount=runtime<<V;
        |#1;
        |if(sum !== expectedSum || joined !== {V,runtime} || less !== (runtime < V)) $$fatal(1,"VALUE_ARITHMETIC");
        |if(left !== expectedLeft || right !== expectedRight || asCount !== expectedCount) $$fatal(1,"VALUE_SHIFT_WIDTH");
        |end endtask
        |initial begin
        |for(i=0;i<2048;i=i+1) begin runtime=i/8; shift=i%8; check; end
        |runtime=8'bxz01zx10; shift=1; check;
        |runtime=8'hff; shift=3'bxxx; check;
        |$$display("SYMBOLIC_VALUE_PASS"); $$finish;
        |end
        |endmodule
        |""".stripMargin.getBytes(UTF_8))
      val compiled = run(dir, Seq("iverilog", "-g2001", "-s", "tb", "-o", "value.vvp", "ValueExpressions.v", "tb.v"))
      assert(compiled._1 == 0, compiled._2)
      val simulated = run(dir, Seq("vvp", "value.vvp"))
      assert(simulated._1 == 0 && simulated._2.contains("SYMBOLIC_VALUE_PASS"), simulated._2)
      val synth = run(dir, Seq("yosys", "-p", s"read_verilog ValueExpressions.v; chparam -set BUS_LOG2_BYTES $log ValueExpressions; synth -top ValueExpressions; check -assert"))
      assert(synth._1 == 0, synth._2)
      val reference = dir.resolve(s"reference-$log")
      SpinalVerilog(SpinalConfig(targetDirectory = reference.toString, headerWithDate = false)) {
        new ConcreteValueExpressions(value)
      }
      val script = s"""read_verilog ValueExpressions.v
        |chparam -set BUS_LOG2_BYTES $log ValueExpressions
        |hierarchy -top ValueExpressions
        |proc; flatten; opt
        |rename ValueExpressions gate
        |design -stash candidate
        |read_verilog ${reference.resolve("ConcreteValueExpressions.v")}
        |hierarchy -top ConcreteValueExpressions
        |proc; flatten; opt
        |rename ConcreteValueExpressions gold
        |design -copy-from candidate -as gate gate
        |equiv_make gold gate equiv
        |hierarchy -top equiv
        |equiv_simple
        |equiv_status -assert
        |""".stripMargin
      Files.write(dir.resolve("proof.ys"), script.getBytes(UTF_8))
      val proof = run(dir, Seq("yosys", "-s", "proof.ys"))
      assert(proof._1 == 0 && proof._2.contains("Equivalence successfully proven"), proof._2)
    }
  }
  test("unchanged output-only value example supports all bus-byte overrides") {
    val dir = Files.createTempDirectory("symbolic-value-example-")
    morphhdl.examples.SymbolicHardwareValueArtifactWriter.main(Array(dir.toString))
    for (log <- 2 to 5) {
      Files.write(dir.resolve("tb.v"), s"""module tb;
        |wire [7:0] value; wire [8:0] plusThree;
        |SymbolicValueProbe #(.BUS_LOG2_BYTES($log)) dut(value,plusThree);
        |initial begin #1;
        |if(value !== 8'd${1 << log} || plusThree !== 9'd${(1 << log) + 3}) $$fatal(1,"VALUE_EXAMPLE");
        |$$finish; end
        |endmodule
        |""".stripMargin.getBytes(UTF_8))
      val compiled = run(dir, Seq("iverilog", "-g2001", "-s", "tb", "-o", "example.vvp", "SymbolicValueProbe.v", "tb.v"))
      assert(compiled._1 == 0, compiled._2)
      val simulated = run(dir, Seq("vvp", "example.vvp"))
      assert(simulated._1 == 0, simulated._2)
    }
  }
  test("unsigned value construction rejects insufficient widths and forged evidence") {
    def reject(expected: String)(value: => ElabInt): Unit = {
      val error = intercept[Exception] {
        MorphVerilog(SpinalConfig(targetDirectory = Files.createTempDirectory("value-negative-").toString)) {
          new Component { val observed = out UInt(4 bits); observed := U(value, 4 bits) }
        }
      }
      val details = Iterator.iterate[Throwable](error)(_.getCause).takeWhile(_ != null)
        .map(x => String.valueOf(x.getMessage)).mkString("\n")
      assert(details.contains(expected), details)
    }
    reject("VALUE-WIDTH-INSUFFICIENT")(HdlInt.param("VALUE", 8, 0, 16).asElabInt)
    reject("VALUE-DOMAIN-UNSUPPORTED")(HdlInt.param("VALUE", 0, -1, 1).asElabInt)
    val value = HdlInt.param("VALUE", 8, 0, 15).asElabInt
    reject("SPINAL-ELAB-DOMAIN-EXACT-AUTHORITY-MISSING")(ElabInt.fromExpression(value.expression.copy()))
  }
  test("zero and maximum values preserve exact explicit widths and concrete literal behavior") {
    val dir = Files.createTempDirectory("value-boundaries-")
    MorphVerilog(SpinalConfig(targetDirectory = dir.toString,
      headerWithDate = false))(new Component {
      setDefinitionName("ValueBoundary")
      val root: ElabInt = HdlInt.param("VALUE", 0, 0, 15).asElabInt
      val observed = out UInt(4 bits)
      val zero = out UInt(4 bits)
      val maximum = out UInt(4 bits)
      observed := U(root, 4 bits)
      zero := U(ElabInt.literal(0), 4 bits)
      maximum := U(ElabInt.literal(15), 4 bits)
    })
    for (value <- 0 to 15) {
      Files.write(dir.resolve("tb.v"), s"""module tb;
        |wire [3:0] observed,zero,maximum;
        |ValueBoundary #(.VALUE($value)) dut(observed,zero,maximum);
        |initial begin #1;
        |if(observed !== 4'd$value || zero !== 4'd0 || maximum !== 4'd15) $$fatal(1,"VALUE_BOUNDARY");
        |$$finish; end
        |endmodule
        |""".stripMargin.getBytes(UTF_8))
      val compiled = run(dir, Seq("iverilog", "-g2001", "-s", "tb", "-o", "boundary.vvp", "ValueBoundary.v", "tb.v"))
      assert(compiled._1 == 0, compiled._2)
      val simulated = run(dir, Seq("vvp", "boundary.vvp"))
      assert(simulated._1 == 0, simulated._2)
    }
  }

}
