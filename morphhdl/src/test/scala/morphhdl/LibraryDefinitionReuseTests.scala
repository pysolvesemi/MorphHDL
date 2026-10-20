package morphhdl

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import scala.collection.JavaConverters._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import morphhdl.frontend.{HdlInt, formalParam}

/** Separate regression assertions for rejected scalar sharing and width duplication. */
class LibraryDefinitionReuseTests extends AnyFunSuite {
  private class ExternalWidth(actual: ElabInt) extends BlackBox {
    setBlackBoxName("ExternalWidth")
    addGeneric("WIDTH",actual)
    val dataIn = in Bits(actual bits)
    val result = out Bits(actual bits)
  }
  private class WidthChild(actual: ElabInt, body: String = "direct") extends Component {
    @dontName private val width = formalParam(actual, "WIDTH", 1, 64)
    val dataIn = in Bits(width bits)
    val result = out Bits(width bits)
    if(body == "nested") {
      val leaf = new WidthChild(width)
      leaf.dataIn := dataIn
      result := leaf.result
    } else if(body == "blackbox") {
      val leaf = new ExternalWidth(width)
      leaf.dataIn := dataIn
      result := leaf.result
    } else if(body == "internal") {
      val retained = Bits(width bits).dontSimplifyIt()
      retained := ~dataIn
      result := retained
    } else result := ~dataIn
  }
  test("standalone width formal retains supplied default and independent override") {
    val dir=Files.createTempDirectory("standalone-width-formal-")
    MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false))(
      new WidthChild(ElabInt.literal(8)))
    Files.write(dir.resolve("tb.v"), """module tb;
reg [7:0] x=8'h93; reg [15:0] a=16'h395a; wire [7:0] y; wire [15:0] b;
WidthChild normal(.dataIn(x),.result(y));
WidthChild #(.WIDTH(16)) changed(.dataIn(a),.result(b));
initial begin #1; if(y!==~x || b!==~a) $fatal; $finish; end
endmodule
""".getBytes(UTF_8))
    Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","WidthChild.v","tb.v"),
      Seq("timeout","10","vvp","sim"),
      Seq("verilator","--lint-only","-Wall","--language","1364-2001","-DSYNTHESIS","--top-module","WidthChild","WidthChild.v"),
      Seq("yosys","-Q","-p","read_verilog WidthChild.v; synth -top WidthChild; check -assert")
    ).foreach { command =>
      val (code,log)=Increment66ToolEvidence.run(dir,command)
      assert(code==0,s"$command failed in $dir\n$log")
    }
  }
  private class WidthTop(equal: Boolean, mode: String = "parameter", reverse: Boolean = false,
      body: String = "direct") extends Component {
    @dontName private val aw = if(mode == "literal" || mode == "mixed") ElabInt.literal(8)
      else if(mode == "derived") HdlInt.param("A_WIDTH", 4, 1, 60).asElabInt + 4
      else HdlInt.param("A_WIDTH", 8, 1, 64).asElabInt
    @dontName private val bw = if(mode == "literal") ElabInt.literal(if(equal) 8 else 16)
      else if(mode == "derived") HdlInt.param("B_WIDTH", if(equal) 4 else 12, 1, 60).asElabInt + 4
      else HdlInt.param("B_WIDTH", if(equal) 8 else 16, 1, 64).asElabInt
    val aIn = in Bits(aw bits)
    val bIn = in Bits(bw bits)
    val aOut = out Bits(aw bits)
    val bOut = out Bits(bw bits)
    val pair = if(reverse) {
      val b = new WidthChild(bw, body).setName("b")
      val a = new WidthChild(aw, body).setName("a")
      (a,b)
    } else {
      val a = new WidthChild(aw, body).setName("a")
      val b = new WidthChild(bw, body).setName("b")
      (a,b)
    }
    val a = pair._1
    val b = pair._2
    a.dataIn := aIn
    b.dataIn := bIn
    aOut := a.result
    bOut := b.result
  }
  private class SyncChild(actual: ElabInt, alternate: Boolean = false) extends Component {
    ClockDomain.current.renamePulledWires(clock = "clk", reset = "resetn")
    @dontName private val depth: ElabInt = formalParam(actual, "SYNC_STAGES", 2, 4)
    val dataIn = in Bool()
    val result = out Bool()
    val stages = Seq.fill(4)(Reg(Bool()) init(False))
    stages(0) := dataIn
    for(i <- 1 until 4) stages(i) := stages(i-1)
    @dontName private val useLast: ElabBool = if(alternate) depth == 2 else depth == 4
    if(useLast) result := stages(3)
    else if(depth == 3) result := stages(2)
    else result := stages(1)
  }
  private class SyncTop(equal: Boolean, alternate: Boolean = false, highMiddle: Boolean = false) extends Component {
    val clkA, clkB, clkC, rstA, rstB, rstC = in Bool()
    val dataIn = in Bool()
    val resultA, resultB, resultC = out Bool()
    val clocks = Seq(clkA, clkB, clkC)
    val resets = Seq(rstA, rstB, rstC)
    val results = Seq(resultA, resultB, resultC)
    val synchronizers = (0 until 3).map { i =>
      val domain = ClockDomain(clocks(i), resets(i), config=ClockDomainConfig(resetKind=ASYNC, resetActiveLevel=if(highMiddle && i==1) HIGH else LOW))
      val child = domain(new SyncChild(ElabInt.literal(if(equal) 2 else i+2), alternate && i==1))
      child.dataIn := dataIn
      results(i) := child.result
      child
    }
  }
  test("same native bodies with different structural selectors cannot share a definition") {
    val dir=Files.createTempDirectory("native-selector-conflict-")
    val error=intercept[Exception] {
      MorphVerilog(SpinalConfig(targetDirectory=dir.toString))(new SyncTop(true,alternate=true))
    }
    val messages=Iterator.iterate[Throwable](error)(_.getCause).takeWhile(_!=null)
      .flatMap(e => Option(e.getMessage)).mkString("\n")
    assert(messages.contains("SPINAL-PARAMETERIZED-VERILOG-FORMAL-STRUCTURE-CONFLICT"),messages)
  }
  test("same Scala synchronizer with different reset polarity retains separate definitions") {
    val dir=Files.createTempDirectory("native-reset-definitions-")
    MorphVerilog(SpinalConfig(targetDirectory=dir.toString))(new SyncTop(true,highMiddle=true))
    val rtl=new String(Files.readAllBytes(dir.resolve("SyncTop.v")),UTF_8)
    assert("(?m)^module SyncChild(?:_[0-9]+)?\\b".r.findAllIn(rtl).size==2,rtl)
    assert(rtl.contains("posedge resetn") && rtl.contains("negedge resetn"),rtl)
  }
  for(body <- Seq("internal","nested","blackbox"); split <- Seq(false,true)) {
    test(s"native symbolic definition includes retained internal and nested widths body=$body split=$split") {
      val dir=Files.createTempDirectory("native-width-structure-")
      MorphVerilog(SpinalConfig(targetDirectory=dir.toString,oneFilePerComponent=split,headerWithDate=false)) {
        new WidthTop(false,body=body)
      }
      val paths=Files.list(dir)
      val rtl=try paths.iterator.asScala.filter(_.toString.endsWith(".v"))
        .map(p => new String(Files.readAllBytes(p),UTF_8)).mkString("\n") finally paths.close()
      val expected=if(body=="nested") 2 else 1
      assert("(?m)^module WidthChild(?:_[0-9]+)?\\b".r.findAllIn(rtl).size==expected, s"$dir\n$rtl")
    }
  }
  test("equal witnesses with mixed literal and symbolic actuals publish order-independent definitions") {
    for(split <- Seq(false,true); body <- Seq("direct","internal","nested","blackbox")) {
      def emit(reverse: Boolean): Map[String,String] = {
        val dir=Files.createTempDirectory("mixed-width-sharing-")
        MorphVerilog(SpinalConfig(targetDirectory=dir.toString,
          oneFilePerComponent=split,headerWithDate=false)) {
          new WidthTop(true,mode="mixed",reverse=reverse,body=body)
        }
        val paths=Files.list(dir)
        val text=try paths.iterator.asScala.filter(_.toString.endsWith(".v"))
          .map(p => new String(Files.readAllBytes(p),UTF_8)).mkString("\n") finally paths.close()
        "(?s)module (WidthChild(?:_[0-9]+)?)\\b.*?endmodule".r.findAllMatchIn(text)
          .map(m => m.group(1) -> m.matched).toMap
      }
      val forward=emit(false)
      assert(forward.size==(if(body=="nested") 2 else 1))
      assert(forward==emit(true),s"mixed actual definition changed: body=$body split=$split")
    }
  }
  for(split <- Seq(false,true); mode <- Seq("parameter", "literal", "derived");
      body <- Seq("direct", "internal", "nested", "blackbox")) {
    test(s"width defaults and overrides preserve independent ports mode=$mode body=$body split=$split") {
      def emit(reverse: Boolean) = {
        val dir = Files.createTempDirectory("native-width-sharing-")
        MorphVerilog(SpinalConfig(targetDirectory=dir.toString, oneFilePerComponent=split, headerWithDate=false)) {
          new WidthTop(false, mode, reverse, body)
        }
        val paths=Files.list(dir)
        val files=try paths.iterator.asScala.filter(_.toString.endsWith(".v"))
          .map(p => p.getFileName.toString -> new String(Files.readAllBytes(p),UTF_8)).toMap finally paths.close()
        dir -> files
      }
      val (dir,files)=emit(false)
      val text=files.values.mkString("\n")
      assert("(?m)^module WidthChild(?:_[0-9]+)?\\b".r.findAllIn(text).size==(if(body=="nested") 2 else 1),text)
      assert(files==emit(false)._2,"repeat output changed")
      val child="(?s)module (WidthChild(?:_[0-9]+)?)\\b.*?endmodule".r
      def definitions(source: String) = child.findAllMatchIn(source).map(m => m.group(1) -> m.matched).toMap
      assert(definitions(text)==definitions(emit(true)._2.values.mkString("\n")),
        "definition changed with instance order")
      val overrides=mode match {
        case "parameter" => "#(.A_WIDTH(64),.B_WIDTH(1))"
        case "derived" => "#(.A_WIDTH(60),.B_WIDTH(1))"
        case _ => ""
      }
      val aWidth=if(mode=="literal") 8 else 64
      val bWidth=if(mode=="literal") 16 else if(mode=="derived") 5 else 1
      Files.write(dir.resolve("tb.v"),s"""module tb;
reg [7:0] a; reg [15:0] b; wire [7:0] x; wire [15:0] y;
reg [$aWidth-1:0] c; reg [$bWidth-1:0] d; wire [$aWidth-1:0] u; wire [$bWidth-1:0] v;
reg [63:0] direct; wire [63:0] inverted; integer i;
WidthTop normal(.aIn(a),.bIn(b),.aOut(x),.bOut(y));
WidthTop $overrides changed(.aIn(c),.bIn(d),.aOut(u),.bOut(v));
WidthChild #(.WIDTH(64)) independent(.dataIn(direct),.result(inverted));
initial begin for(i=0;i<256;i=i+1) begin
 a=i;b=16'ha500 | i;c={32'h9630a5c7,32'h5ac9036e} ^ i;d=i;direct=c;
 #1;if(x!==~a || y!==~b || u!==~c || v!==~d || inverted!==~direct) $$fatal(1,"WIDTH override");
end $$finish; end
endmodule
""".getBytes(UTF_8))
      val stub = if(body=="blackbox") {
        Files.write(dir.resolve("ExternalWidth.v"),
          "module ExternalWidth #(parameter WIDTH=8)(input [WIDTH-1:0] dataIn, output [WIDTH-1:0] result); assign result=~dataIn; endmodule\n".getBytes(UTF_8))
        Seq("ExternalWidth.v")
      } else Seq.empty
      val names=files.keys.toVector.sorted ++ stub
      Seq(Seq("iverilog","-g2001","-s","tb","-o","sim") ++ names ++ Seq("tb.v"),
        Seq("timeout","10","vvp","sim"),
        // Combined publication intentionally places several module names in one file.
        Seq("verilator","--lint-only","-Wall","--language","1364-2001","-DSYNTHESIS","--top-module","WidthTop") ++
          (if(split) Seq.empty else Seq("-Wno-DECLFILENAME")) ++ names,
        Seq("yosys","-Q","-p",s"read_verilog ${names.mkString(" ")}; synth -top WidthTop; check -assert")
      ).foreach { command =>
        val (code,log)=Increment66ToolEvidence.run(dir,command)
        assert(code==0,s"$command failed in $dir\n$log")
      }
    }
  }
  for(split <- Seq(false,true); equal <- Seq(true,false); scalar <- Seq(false,true)) {
    test(s"native definition sharing scalar=$scalar equal=$equal split=$split") {
      val dir = Files.createTempDirectory("library-definition-reuse-")
      MorphVerilog(SpinalConfig(targetDirectory=dir.toString, oneFilePerComponent=split, headerWithDate=false)) {
        if(scalar) new SyncTop(equal) else new WidthTop(equal)
      }
      val paths = Files.list(dir)
      val files = try paths.iterator.asScala.filter(_.toString.endsWith(".v")).toVector finally paths.close()
      val rtl = files.map(p => new String(Files.readAllBytes(p), UTF_8)).mkString("\n")
      val name = if(scalar) "SyncChild" else "WidthChild"
      assert(("(?m)^module " + name + "(?:_[0-9]+)?\\b").r.findAllIn(rtl).size == 1,
        s"expected one $name definition in $dir\n$rtl")
      if (scalar) {
        val checks = Seq("A", "B", "C").zipWithIndex.map { case (lane, index) =>
          val depth = if(equal) 2 else index + 2
          s"""reg [3:0] model$lane;
             |always @(posedge clk$lane or negedge rst$lane) begin
             |  if(!rst$lane) model$lane=0;
             |  else model$lane={model$lane[2:0],dataIn};
             |  #1; if(result$lane !== model$lane[${depth-1}]) $$fatal(1,"latency/reset $lane");
             |end
             |""".stripMargin
        }.mkString
        Files.write(dir.resolve("tb.v"), s"""module tb;
reg clkA=0,clkB=0,clkC=0,rstA=1,rstB=1,rstC=1,dataIn=0;
wire resultA,resultB,resultC;
SyncTop dut(.clkA(clkA),.clkB(clkB),.clkC(clkC),.rstA(rstA),.rstB(rstB),.rstC(rstC),
.dataIn(dataIn),.resultA(resultA),.resultB(resultB),.resultC(resultC));
always #2 clkA=~clkA;
always #3 clkB=~clkB;
always #5 clkC=~clkC;
always #7 dataIn=~dataIn;
$checks
initial begin
#1; rstA=0;rstB=0;rstC=0;
#10;rstA=1; #2;rstB=1; #4;rstC=1;
#101;rstB=0; #12;rstB=1;
#103;rstA=0; #8;rstA=1;
#107;rstC=0; #20;rstC=1;
#501; $$finish;
end
endmodule
""".getBytes(UTF_8))
        val names = files.map(_.getFileName.toString).sorted
        Seq(Seq("iverilog", "-g2001", "-s", "tb", "-o", "sim") ++ names ++ Seq("tb.v"),
          Seq("timeout", "10", "vvp", "sim"),
          Seq("yosys", "-Q", "-p", s"read_verilog ${names.mkString(" ")}; synth -top SyncTop; check -assert")
        ).foreach { command =>
          val (code, log) = Increment66ToolEvidence.run(dir, command)
          assert(code == 0, s"$command failed in $dir\n$log")
        }
      }
    }
  }
}
