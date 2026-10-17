package morphhdl

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.collection.JavaConverters._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import morphhdl.frontend.{HdlInt, formalParam}

class ScalarFormalReuseTests extends AnyFunSuite {
  private class ConstantChild(actual: ElabInt, actualWidth: Option[ElabInt] = None,
      maximum: Int = 255, differentBody: Boolean = false) extends Component {
    setDefinitionName("ConstantChild")
    @dontName private val value = formalParam(actual, "VALUE", 0, maximum)
    @dontName private val width = actualWidth.map(w => formalParam(w, "WIDTH", 8, 16))
      .getOrElse(ElabInt.literal(8))
    val dataIn = in UInt(width bits)
    val result = out UInt(width bits)
    val constantValue = ElabValue.uintLike(value, UInt(width bits), "constantValue")
    result := (if (differentBody) dataIn | constantValue else dataIn ^ constantValue)
  }
  for (mixed <- Seq(false,true)) {
    test(s"standalone scalar formal retains its supplied default mixed=$mixed") {
      val dir = Files.createTempDirectory("standalone-scalar-formal-")
      val width = if(mixed) 16 else 8
      MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false))(
        new ConstantChild(ElabInt.literal(9), if(mixed) Some(ElabInt.literal(16)) else None))
      val widthOverride=if(mixed) ",.WIDTH(8)" else ""
      Files.write(dir.resolve("tb.v"), s"""module tb;
reg [$width-1:0] x=$width'hab53; wire [$width-1:0] y; wire [7:0] z;
ConstantChild a(.dataIn(x),.result(y));
ConstantChild #(.VALUE(42)$widthOverride) b(.dataIn(x[7:0]),.result(z));
initial begin #1; if(y!==(x ^ $width'd9) || z!==(x[7:0] ^ 8'd42)) $$fatal; $$finish; end
endmodule
""".getBytes(UTF_8))
      Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","ConstantChild.v","tb.v"),
        Seq("timeout","10","vvp","sim"),
        Seq("verilator","--lint-only","-Wall","--language","1364-2001","-DSYNTHESIS","--top-module","ConstantChild","ConstantChild.v"),
        Seq("yosys","-Q","-p","read_verilog ConstantChild.v; synth -top ConstantChild; check -assert")
      ).foreach { command =>
        val (code,log)=Increment66ToolEvidence.run(dir,command)
        assert(code==0,s"$command failed in $dir\n$log")
      }
    }
  }
  private class Top(mode: String, reverse: Boolean, mixed: Boolean = false,
      incompatibleDomain: Boolean = false, differentBody: Boolean = false) extends Component {
    setDefinitionName("Top")
    @dontName private val a = HdlInt.param("A", 4, 0, 255).asElabInt
    @dontName private val b = if (mode == "same-actual") a
      else HdlInt.param("B", if (mode == "same-default") 4 else 9, 0,
        if (incompatibleDomain) 127 else 255).asElabInt
    @dontName private val width = if (mixed) HdlInt.param("W", 8, 8, 16).asElabInt else ElabInt.literal(8)
    @dontName private val childWidth = if (mixed) Some(width) else None
    val dataIn = in UInt(width bits)
    val first, second = out UInt(width bits)
    def makeLeft = new ConstantChild(a, childWidth).setName("left")
    def makeRight = new ConstantChild(b, childWidth, if (incompatibleDomain) 127 else 255,
      differentBody).setName("right")
    val pair = if (reverse) {
      val right = makeRight
      val left = makeLeft
      (left, right)
    } else {
      val left = makeLeft
      val right = makeRight
      (left, right)
    }
    pair._1.dataIn := dataIn
    pair._2.dataIn := dataIn
    first := pair._1.result
    second := pair._2.result
  }
  private class MixedTop extends Component {
    @dontName private val a = HdlInt.param("A",4,0,255).asElabInt
    @dontName private val b = HdlInt.param("B",9,0,255).asElabInt
    @dontName private val aw = HdlInt.param("A_WIDTH",8,8,16).asElabInt
    @dontName private val bw = HdlInt.param("B_WIDTH",16,8,16).asElabInt
    val aIn = in UInt(aw bits)
    val bIn = in UInt(bw bits)
    val aOut = out UInt(aw bits)
    val bOut = out UInt(bw bits)
    val left = new ConstantChild(a,Some(aw))
    val right = new ConstantChild(b,Some(bw))
    left.dataIn := aIn
    right.dataIn := bIn
    aOut := left.result
    bOut := right.result
  }
  for(split <- Seq(false,true)) {
    test(s"independent scalar and width defaults share one definition split=$split") {
      val dir=Files.createTempDirectory("mixed-formal-defaults-")
      MorphVerilog(SpinalConfig(targetDirectory=dir.toString,oneFilePerComponent=split,headerWithDate=false))(new MixedTop)
      val rtl=sources(dir)
      assert("(?m)^module ConstantChild\\b".r.findAllIn(rtl.values.mkString("\n")).size==1)
      Files.write(dir.resolve("tb.v"),"""module tb;
reg [7:0] narrow; reg [15:0] wide; wire [7:0] a,d; wire [15:0] b,c; integer i;
MixedTop normal(.aIn(narrow),.bIn(wide),.aOut(a),.bOut(b));
MixedTop #(.A_WIDTH(16),.B_WIDTH(8),.A(255),.B(0)) changed(.aIn(wide),.bIn(narrow),.aOut(c),.bOut(d));
initial begin for(i=0;i<256;i=i+1) begin narrow=i;wide=16'ha500 | i;#1;
if(a!==(narrow ^ 8'd4) || b!==(wide ^ 16'd9) || c!==(wide ^ 16'd255) || d!==narrow) $fatal;
end $finish;end
endmodule
""".getBytes(UTF_8))
      val files=rtl.keys.toVector.sorted
      Seq(Seq("iverilog","-g2001","-s","tb","-o","sim") ++ files ++ Seq("tb.v"),
        Seq("timeout","10","vvp","sim"),
        Seq("verilator","--lint-only","-Wall","--language","1364-2001","-DSYNTHESIS","--top-module","MixedTop") ++
          (if(split) Seq.empty else Seq("-Wno-DECLFILENAME")) ++ files,
        Seq("yosys","-Q","-p",s"read_verilog ${files.mkString(" ")}; synth -top MixedTop; check -assert")
      ).foreach { command =>
        val (code,log)=Increment66ToolEvidence.run(dir,command)
        assert(code==0,s"$command failed in $dir\n$log")
      }
    }
  }
  private def sources(dir: Path): Map[String, String] = {
    val paths = Files.list(dir)
    try paths.iterator.asScala.filter(_.toString.endsWith(".v"))
      .map(path => path.getFileName.toString -> new String(Files.readAllBytes(path), UTF_8)).toMap
    finally paths.close()
  }
  private def emit(mode: String, reverse: Boolean, split: Boolean,
      mixed: Boolean = false): (Path, Map[String, String]) = {
    val dir = Files.createTempDirectory("scalar-formal-reuse-")
    MorphVerilog(SpinalConfig(targetDirectory = dir.toString, headerWithDate = false,
      oneFilePerComponent = split))(new Top(mode, reverse, mixed))
    dir -> sources(dir)
  }
  for (split <- Seq(false, true)) {
    test(s"mixed scalar and width formals preserve independent values and widened overrides split=$split") {
      val (dir, rtl) = emit("different", reverse = false, split, mixed = true)
      val text = rtl.values.mkString("\n")
      assert("(?m)^module ConstantChild\\b".r.findAllIn(text).size == 1, text)
      val child = "(?s)module ConstantChild\\b.*?endmodule".r
      assert(child.findFirstIn(text) == child.findFirstIn(
        emit("different", reverse = true, split, mixed = true)._2.values.mkString("\n")))
      assert(rtl == emit("different", reverse = false, split, mixed = true)._2)
      Files.write(dir.resolve("tb.v"), """module tb;
reg [7:0] x; reg [15:0] wide; wire [7:0] a,b; wire [15:0] c,d; integer pattern;
Top normal(.dataIn(x),.first(a),.second(b));
Top #(.A(17),.B(254),.W(16)) changed(.dataIn(wide),.first(c),.second(d));
initial begin for(pattern=0;pattern<256;pattern=pattern+1) begin
x=pattern; wide=16'ha500 | pattern; #1;
if(a!==(x ^ 8'd4) || b!==(x ^ 8'd9) ||
c!==(wide ^ 16'd17) || d!==(wide ^ 16'd254)) $fatal;
end $finish; end
endmodule
""".getBytes(UTF_8))
      val files = rtl.keys.toVector.sorted
      Seq(Seq("iverilog", "-g2001", "-s", "tb", "-o", "sim") ++ files ++ Seq("tb.v"),
        Seq("timeout", "10", "vvp", "sim"),
        Seq("verilator", "--lint-only", "-Wall", "--language", "1364-2001", "-DSYNTHESIS", "--top-module", "Top") ++
          (if (split) Seq.empty else Seq("-Wno-DECLFILENAME")) ++ files,
        Seq("yosys", "-Q", "-p", s"read_verilog ${files.mkString(" ")}; synth -top Top; check -assert")
      ).foreach { command =>
        val (code, log) = Increment66ToolEvidence.run(dir, command)
        assert(code == 0, s"$command failed in $dir\n$log")
      }
    }
  }
  for (domain <- Seq(false, true)) {
    test(s"incompatible scalar formal domain or body cannot share one forced definition domain=$domain") {
      val dir = Files.createTempDirectory("scalar-formal-incompatible-")
      val error = intercept[Exception] {
        MorphVerilog(SpinalConfig(targetDirectory = dir.toString))(new Top("different", reverse = false,
          incompatibleDomain = domain, differentBody = !domain))
      }
      val messages = Iterator.iterate[Throwable](error)(_.getCause).takeWhile(_ != null)
        .flatMap(e => Option(e.getMessage)).mkString("\n")
      assert(messages.contains("definition name 'ConstantChild' was already used once for a different layout"), messages)
    }
  }
  for (mode <- Seq("same-actual", "same-default", "different"); split <- Seq(false, true)) {
    test(s"scalar formal reuse preserves independent defaults and overrides mode=$mode split=$split") {
      val (dir, rtl) = emit(mode, reverse = false, split)
      val text = rtl.values.mkString("\n")
      assert("(?m)^module ConstantChild\\b".r.findAllIn(text).size == 1, text)
      val compact = text.replaceAll("\\s+", "")
      assert(compact.contains(".VALUE(A)"), text)
      assert(compact.contains(if (mode == "same-actual") ".VALUE(A)" else ".VALUE(B)"), text)
      assert(rtl == emit(mode, reverse = false, split)._2)
      val reversed = emit(mode, reverse = true, split)._2.values.mkString("\n")
      val child = "(?s)module ConstantChild\\b.*?endmodule".r
      assert(child.findFirstIn(text) == child.findFirstIn(reversed), "child definition depends on instance order")
      val defaultB = if (mode == "different") 9 else 4
      val overrides = if (mode == "same-actual") ".A(17)" else ".A(17),.B(254)"
      val overrideB = if (mode == "same-actual") 17 else 254
      Files.write(dir.resolve("tb.v"), s"""module tb;
reg [7:0] dataIn; wire [7:0] a,b,c,d; integer pattern;
Top normal(.dataIn(dataIn),.first(a),.second(b));
Top #($overrides) changed(.dataIn(dataIn),.first(c),.second(d));
initial begin for(pattern=0;pattern<256;pattern=pattern+1) begin
dataIn=pattern; #1;
if(a!==(dataIn ^ 8'd4) || b!==(dataIn ^ 8'd$defaultB) ||
c!==(dataIn ^ 8'd17) || d!==(dataIn ^ 8'd$overrideB)) $$fatal;
end $$finish; end
endmodule
""".getBytes(UTF_8))
      val files = rtl.keys.toVector.sorted
      Seq(Seq("iverilog", "-g2001", "-s", "tb", "-o", "sim") ++ files ++ Seq("tb.v"),
        Seq("timeout", "10", "vvp", "sim"),
        Seq("verilator", "--lint-only", "-Wall", "--language", "1364-2001", "-DSYNTHESIS", "--top-module", "Top") ++
          (if (split) Seq.empty else Seq("-Wno-DECLFILENAME")) ++ files,
        Seq("yosys", "-Q", "-p", s"read_verilog ${files.mkString(" ")}; synth -top Top; check -assert")
      ).foreach { command =>
        val (code, log) = Increment66ToolEvidence.run(dir, command)
        assert(code == 0, s"$command failed in $dir\n$log")
      }
    }
  }
}
