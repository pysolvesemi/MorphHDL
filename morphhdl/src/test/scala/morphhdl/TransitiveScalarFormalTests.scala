package morphhdl

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.collection.JavaConverters._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import morphhdl.frontend.{HdlInt, formalParam}

class TransitiveScalarFormalTests extends AnyFunSuite {
  private class Leaf(actual: ElabInt) extends Component {
    @dontName private val value: ElabInt = formalParam(actual, "VALUE", 0, 255)
    val data = in UInt(8 bits)
    val result = out UInt(8 bits)
    result := data ^ ElabValue.uintLike(value, result, "constant_value")
  }
  private class Middle(actual: ElabInt, levels: Int) extends Component {
    @dontName private val value: ElabInt = formalParam(actual, "VALUE", 0, 255)
    val data = in UInt(8 bits)
    val result = out UInt(8 bits)
    if (levels == 1) {
      val child = new Leaf(value)
      child.data := data
      result := child.result
    } else {
      val child = new Middle(value, levels - 1)
      child.data := data
      result := child.result
    }
  }
  private class Top(levels: Int, mixed: Boolean, literal: Boolean) extends Component {
    setDefinitionName("ForwardingTop")
    @dontName private val value: ElabInt = if(literal) ElabInt.literal(201)
      else HdlInt.param("VALUE",7,0,255).asElabInt
    @dontName private val unused: ElabInt = HdlInt.param("UNUSED",3,0,9).asElabInt
    @dontName private val width: ElabInt = if(mixed) HdlInt.param("WIDTH",8,8,16).asElabInt
      else ElabInt.literal(8)
    val passIn = in Bits(width bits)
    val passOut = out Bits(width bits)
    passOut := passIn
    val data = in UInt(8 bits)
    val result = out UInt(8 bits)
    if(levels == 0) {
      val child = new Leaf(value)
      child.data := data
      result := child.result
    } else {
      val child = new Middle(value, levels)
      child.data := data
      result := child.result
    }
  }
  private def rtl(dir: Path): Vector[Path] = {
    val stream = Files.list(dir)
    try stream.iterator.asScala.filter(_.toString.endsWith(".v")).toVector.sortBy(_.toString)
    finally stream.close()
  }
  for(levels <- Seq(0,1,2); mixed <- Seq(false,true); split <- Seq(false,true)) {
    test(s"scalar actual survives every wrapper levels=$levels mixed=$mixed split=$split") {
      check(levels,mixed,split,literal=false)
    }
  }
  for(split <- Seq(false,true)) {
    test(s"literal scalar actual traverses two wrappers split=$split") {
      check(2,false,split,literal=true)
    }
  }
  private def check(levels: Int, mixed: Boolean, split: Boolean, literal: Boolean): Unit = {
    val dir = Files.createTempDirectory("transitive-scalar-")
    def generate(out: Path): Vector[Path] = {
      MorphVerilog(SpinalConfig(targetDirectory=out.toString,oneFilePerComponent=split,
        headerWithDate=false))(new Top(levels,mixed,literal))
      rtl(out)
    }
    val files = generate(dir)
    val again = Files.createTempDirectory("transitive-scalar-repeat-")
    assert(files.map(p => p.getFileName.toString -> new String(Files.readAllBytes(p),UTF_8)) ==
      generate(again).map(p => p.getFileName.toString -> new String(Files.readAllBytes(p),UTF_8)))
    val text = files.map(p => new String(Files.readAllBytes(p),UTF_8)).mkString("\n")
    assert(!text.contains("UNUSED"),text)
    assert(text.contains(if(literal) ".VALUE(201)" else ".VALUE(VALUE)"),text)
    if(!literal) assert(text.contains("parameter integer VALUE = 7"),text)
    val values = if(literal) Seq(201) else Seq(0,7,201,255)
    val bits = if(mixed) 16 else 8
    val instances = values.zipWithIndex.map { case (value,id) =>
      val parameters = (if(literal) Seq.empty else Seq(s".VALUE($value)")) ++
        (if(mixed) Seq(".WIDTH(16)") else Seq.empty)
      val args = if(parameters.isEmpty) "" else parameters.mkString(" #(",",",")")
      s"wire [7:0] y$id;wire [$bits-1:0] p$id;ForwardingTop$args dut$id(.data(data),.result(y$id),.passIn(passIn),.passOut(p$id));"
    }.mkString("\n")
    val checks = values.zipWithIndex.map { case (value,id) =>
      s"""if(y$id !== (data ^ 8'd$value) || p$id !== passIn) $$fatal(1,"forwarded value $value");"""
    }.mkString("\n")
    Files.write(dir.resolve("tb.v"),s"""module tb;
reg [7:0] data;reg [$bits-1:0] passIn;integer k;
$instances
initial begin passIn='h53; for(k=0;k<256;k=k+1) begin data=k;#1;$checks end $$finish;end
endmodule
""".getBytes(UTF_8))
    val names = files.map(_.getFileName.toString)
    val commands = Vector(
      Seq("iverilog","-g2001","-s","tb","-o","sim") ++ names ++ Seq("tb.v"),
      Seq("timeout","10","vvp","sim"),
      Seq("yosys","-Q","-p",s"read_verilog ${names.mkString(" ")}; synth -top ForwardingTop; check -assert")) ++
      (if(split) Vector(Seq("verilator","--lint-only","-Wall","--language","1364-2001",
        "-DSYNTHESIS","--top-module","ForwardingTop") ++ names) else Vector.empty)
    commands.foreach { command =>
      val (code,log) = Increment66ToolEvidence.run(dir,command)
      assert(code==0,s"$command failed in $dir\n$log")
    }
  }
}
