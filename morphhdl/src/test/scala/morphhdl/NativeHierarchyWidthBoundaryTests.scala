package morphhdl

import java.nio.file.Files
import java.nio.charset.StandardCharsets.UTF_8
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class NativeHierarchyWidthBoundaryTests extends AnyFunSuite {
  private class GenericChild(actual: ElabInt) extends Component {
    @dontName val width=morphhdl.frontend.formalParam(actual,"WIDTH",1,64)
    val x=in Bits(width bits)
    val y=out Bits(width bits)
    y := x
  }
  private class TypedChild(actual: ElabInt) extends Component {
    @dontName val width=morphhdl.frontend.formalParam(actual,"WIDTH",1,64)
    val bi=in Bits(width bits); val bo=out Bits(width bits)
    val ui=in UInt(width bits); val uo=out UInt(width bits)
    val si=in SInt(width bits); val so=out SInt(width bits)
    bo := ~bi; uo := ~ui; so := ~si
  }
  for (symbolic <- Seq(false,true); internal <- Seq(false,true); split <- Seq(false,true))
    test(s"typed literal and symbolic boundaries share a child deterministically symbolic=$symbolic internal=$internal split=$split") {
      def generate(): java.nio.file.Path = {
        val dir=Files.createTempDirectory("native-typed-internal-")
        MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false,oneFilePerComponent=split)) {
          new Component {
            setDefinitionName("Top")
            val lanes=Seq(8,16).map { bits => new Area {
              @dontName val width=if(symbolic) morphhdl.frontend.HdlInt.param(s"W$bits",bits,1,64).asElabInt else ElabInt.literal(bits)
              val bi=in Bits(width bits); val bo=out Bits(width bits)
              val ui=in UInt(width bits); val uo=out UInt(width bits)
              val si=in SInt(width bits); val so=out SInt(width bits)
              bi.setName(s"b${bits}i"); bo.setName(s"b${bits}o")
              ui.setName(s"u${bits}i"); uo.setName(s"u${bits}o")
              si.setName(s"s${bits}i"); so.setName(s"s${bits}o")
              val child=new TypedChild(width)
              if(internal) {
                val bw=Bits(width bits).dontSimplifyIt(); val br=Bits(width bits).dontSimplifyIt()
                val uw=UInt(width bits).dontSimplifyIt(); val ur=UInt(width bits).dontSimplifyIt()
                val sw=SInt(width bits).dontSimplifyIt(); val sr=SInt(width bits).dontSimplifyIt()
                bw := bi; child.bi := bw; br := child.bo; bo := br
                uw := ui; child.ui := uw; ur := child.uo; uo := ur
                sw := si; child.si := sw; sr := child.so; so := sr
              } else {
                child.bi := bi; bo := child.bo
                child.ui := ui; uo := child.uo
                child.si := si; so := child.so
              }
            }}
          }
        }
        dir
      }
      def rtl(dir: java.nio.file.Path): Map[String,String] = {
        import scala.collection.JavaConverters._
        val files=Files.list(dir)
        try files.iterator.asScala.filter(_.toString.endsWith(".v")).map(p => p.getFileName.toString -> new String(Files.readAllBytes(p),UTF_8)).toMap
        finally files.close()
      }
      val dir=generate(); val files=rtl(dir)
      assert(files==rtl(generate()),"repeat generation changed RTL")
      val all=files.values.mkString("\n")
      assert("(?m)^module TypedChild\\s*".r.findAllIn(all).length==1,all)
      assert(!all.contains("module TypedChild_1"),all)
      for(bits <- Seq(8,16)) {
        val actual=if(symbolic) s"W$bits" else bits.toString
        assert(("\\.WIDTH\\s*\\(\\s*"+actual+"\\s*\\)").r.findFirstIn(all).nonEmpty,all)
      }
      for (changed <- (if(symbolic) Seq(false,true) else Seq(false))) {
        val a=if(changed) 5 else 8; val b=if(changed) 23 else 16
        val bind=if(symbolic) s"#(.W8($a),.W16($b))" else ""
        Files.write(dir.resolve("tb.v"),s"""module tb;
reg [$a-1:0] a; reg [$b-1:0] b;
wire [$a-1:0] ab,au,asig; wire [$b-1:0] bb,bu,bsig; integer n;
Top $bind dut(.b8i(a),.u8i(a),.s8i(a),.b8o(ab),.u8o(au),.s8o(asig),
 .b16i(b),.u16i(b),.s16i(b),.b16o(bb),.u16o(bu),.s16o(bsig));
initial begin for(n=0;n<512;n=n+1) begin a=$$random;b=$$random;#1;
 if(ab!==~a || au!==~a || asig!==~a || bb!==~b || bu!==~b || bsig!==~b) $$fatal;
end $$display("TYPED_BOUNDARY_PASS");$$finish;end
endmodule
""".getBytes(UTF_8))
        val sources=files.keys.toSeq.sorted
        val overrides=if(symbolic) Seq(s"-GW8=$a",s"-GW16=$b") else Nil
        val synthOverride=if(symbolic) s"chparam -set W8 $a -set W16 $b Top; " else ""
        // Combined publication necessarily has several module names per file.
        // Strict filename-aware lint runs on the equivalent split publication.
        Seq(
          Seq("iverilog","-g2001","-s","tb","-o","sim") ++ sources ++ Seq("tb.v"),
          Seq("timeout","10","vvp","sim"),
          Seq("yosys","-Q","-p",s"read_verilog ${sources.mkString(" ")}; ${synthOverride}synth -top Top; check -assert")
        ).++(if(split) Seq(Seq("verilator","--lint-only","-Wall","--language","1364-2001","-DSYNTHESIS","--top-module","Top") ++ overrides ++ sources) else Nil).foreach { command =>
          val (code,log)=Increment66ToolEvidence.run(dir,command)
          assert(code==0,s"$command failed in $dir\n$log")
        }
      }
    }
  private class FixedChild extends Component {
    val x=in SInt(8 bits)
    val u=in UInt(8 bits)
    val negative=out Bool()
    val sum=out UInt(8 bits)
    negative := x < 0
    sum := u + U(1,8 bits)
  }
  for (symbolic <- Seq(false,true)) test(s"fixed child input keeps its definition width across parent domains symbolic=$symbolic") {
    val dir=Files.createTempDirectory("native-fixed-port-owner-")
    MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false,oneFilePerComponent=true)) {
      new Component {
        setDefinitionName("Top")
        @dontName val width=if(symbolic) morphhdl.frontend.HdlInt.param("PARENT_WIDTH",16,16,32).asElabInt else ElabInt.literal(16)
        val data=in Bits(width bits)
        val echo=out Bits(width bits)
        val sum=out UInt(8 bits)
        val negative=out Bool()
        val generic=new GenericChild(width)
        generic.x := data
        val bridge=echo
        bridge := generic.y
        val fixed=new FixedChild
        val signedInput=SInt(8 bits).dontSimplifyIt()
        val unsignedInput=UInt(8 bits).dontSimplifyIt()
        signedInput := bridge(7 downto 0).asSInt
        unsignedInput := bridge(15 downto 8).asUInt
        fixed.x := signedInput
        fixed.u := unsignedInput
        negative := fixed.negative
        sum := fixed.sum
      }
    }
    val stream=Files.list(dir)
    val rtl=try { import scala.collection.JavaConverters._; stream.iterator.asScala.filter(_.toString.endsWith(".v")).map(_.getFileName.toString).toVector.sorted } finally stream.close()
    for(width <- (if(symbolic) Seq(16,24,32) else Seq(16))) {
      val binding=if(symbolic) s"#(.PARENT_WIDTH($width))" else ""
      Files.write(dir.resolve("tb.v"),s"""module tb;
reg [$width-1:0] data; wire [$width-1:0] echo; wire negative; wire [7:0] sum; integer n;
reg [7:0] expected;
Top $binding dut(.data(data),.echo(echo),.negative(negative),.sum(sum));
initial begin for(n=0;n<512;n=n+1) begin
 data=$$random; expected=data[15:8]+8'd1; #1;
 if(echo!==data || negative!==data[7] || sum!==expected) $$fatal(1,"fixed child port");
end $$display("FIXED_CHILD_PASS"); $$finish; end
endmodule
""".getBytes(UTF_8))
      val overrides=if(symbolic) Seq(s"-GPARENT_WIDTH=$width") else Nil
      val yosysOverride=if(symbolic) s"chparam -set PARENT_WIDTH $width Top; " else ""
      val commands=Seq(
        Seq("iverilog","-g2001","-s","tb","-o","sim") ++ rtl ++ Seq("tb.v"),
        Seq("timeout","10","vvp","sim"),
        Seq("verilator","--lint-only","-Wall","--language","1364-2001","-DSYNTHESIS","--top-module","Top") ++ overrides ++ rtl,
        Seq("yosys","-Q","-p",s"read_verilog ${rtl.mkString(" ")}; ${yosysOverride}synth -top Top; check -assert"))
      commands.foreach { command =>
        val (code,log)=Increment66ToolEvidence.run(dir,command)
        assert(code==0,s"$command failed in $dir\n$log")
      }
    }
  }

  test("an authored slice invalid in the child formal domain still rejects") {
    val dir=Files.createTempDirectory("native-invalid-child-slice-")
    class Invalid(actual: ElabInt) extends Component {
      @dontName val width=morphhdl.frontend.formalParam(actual,"WIDTH",1,32)
      val x=in SInt(width bits)
      val negative=out Bool()
      negative := x(7 downto 0) < 0
    }
    val error=intercept[MorphVerilogException] {
      MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false)) {
        new Component {
          val x=in SInt(16 bits)
          val y=out Bool()
          val child=new Invalid(ElabInt.literal(16))
          child.x := x
          y := child.negative
        }
      }
    }
    assert(error.getMessage.contains("SPINAL-PARAMETERIZED-VERILOG-SLICE-DOMAIN-UNSUPPORTED"),error.getMessage)
    assert(!Files.exists(dir.resolve("Invalid.v")))
  }

  for (width <- Seq(8,16)) test(s"literal explicit formals accept exact fixed internal wires width=$width") {
    val dir=Files.createTempDirectory("native-literal-internal-")
    MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false,oneFilePerComponent=true)) {
      new Component {
        setDefinitionName("Top")
        val x=in Bits(width bits)
        val y=out Bits(width bits)
        val child=new GenericChild(ElabInt.literal(width))
        val inputWire=Bits(width bits).dontSimplifyIt()
        val outputWire=Bits(width bits).dontSimplifyIt()
        inputWire := x
        child.x := inputWire
        outputWire := child.y
        y := outputWire
      }
    }
    Files.write(dir.resolve("tb.v"),s"""module tb;
reg [$width-1:0] x; wire [$width-1:0] y; integer n;
Top dut(.x(x),.y(y));
initial begin for(n=0;n<512;n=n+1) begin x=$$random; #1; if(y!==x) $$fatal; end
$$display("LITERAL_WIRE_PASS"); $$finish; end
endmodule
""".getBytes(UTF_8))
    Seq(
      Seq("iverilog","-g2001","-s","tb","-o","sim","Top.v","GenericChild.v","tb.v"),
      Seq("timeout","10","vvp","sim"),
      Seq("verilator","--lint-only","-Wall","--language","1364-2001","-DSYNTHESIS","--top-module","Top","Top.v","GenericChild.v"),
      Seq("yosys","-Q","-p","read_verilog Top.v GenericChild.v; synth -top Top; check -assert")
    ).foreach { command =>
      val (code,log)=Increment66ToolEvidence.run(dir,command)
      assert(code==0,s"$command failed in $dir\n$log")
    }
  }

  for (partial <- Seq(false,true)) test(s"literal wire admission does not authorize varying widths or sliced connections partial=$partial") {
    val dir=Files.createTempDirectory("native-invalid-internal-")
    val error=intercept[MorphVerilogException] {
      MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false)) {
        new Component {
          @dontName val width=if(partial) ElabInt.literal(16) else morphhdl.frontend.HdlInt.param("PARENT_WIDTH",16,8,32).asElabInt
          val x=in Bits(16 bits)
          val y=out Bits((if(partial) 8 else 16) bits)
          val child=new GenericChild(width)
          val inputWire=Bits(16 bits).dontSimplifyIt()
          inputWire := x
          child.x := inputWire
          if(partial) y := child.y(7 downto 0)
          else {
            val outputWire=Bits(16 bits).dontSimplifyIt()
            outputWire := child.y
            y := outputWire
          }
        }
      }
    }
    val code=if(partial) "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-PORT-CONNECTION-UNSUPPORTED"
      else "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-BINDING-UNRESOLVED"
    assert(error.getMessage.contains(code),error.getMessage)
    val stream=Files.list(dir)
    try { import scala.collection.JavaConverters._; assert(!stream.iterator.asScala.exists(_.toString.endsWith(".v"))) }
    finally stream.close()
  }

  for (output <- Seq(false,true)) test(s"literal internal carrier width mismatch remains rejected output=$output") {
    val dir=Files.createTempDirectory("native-mismatched-internal-")
    val error=intercept[Exception] {
      MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false)) {
        new Component {
          val x=in Bits(8 bits); val y=out Bits((if(output) 7 else 8) bits)
          val child=new GenericChild(ElabInt.literal(8))
          if(output) {
            child.x := x
            val carrier=Bits(7 bits).dontSimplifyIt()
            carrier := child.y
            y := carrier
          } else {
            val carrier=Bits(7 bits).dontSimplifyIt()
            carrier := x(6 downto 0)
            child.x := carrier
            y := child.y
          }
        }
      }
    }
    assert(error.getMessage.contains("WIDTH MISMATCH"),error.getMessage)
    val stream=Files.list(dir)
    try { import scala.collection.JavaConverters._; assert(!stream.iterator.asScala.exists(_.toString.endsWith(".v"))) }
    finally stream.close()
  }

  for (foreign <- Seq(false,true)) test(s"literal actual does not authorize expression or foreign-owner input foreign=$foreign") {
    val dir=Files.createTempDirectory("native-invalid-binding-owner-")
    val error=intercept[MorphVerilogException] {
      MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false)) {
        new Component {
          val x=in Bits(16 bits); val y=out Bits(8 bits)
          val child=new GenericChild(ElabInt.literal(8))
          if(foreign) {
            val sibling=new GenericChild(ElabInt.literal(8))
            val carrier=Bits(8 bits).dontSimplifyIt()
            carrier := x(7 downto 0)
            sibling.x := carrier
            child.x := sibling.y
          } else child.x := x(7 downto 0)
          y := child.y
        }
      }
    }
    assert(error.getMessage.contains("SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-PORT-CONNECTION-UNSUPPORTED"),error.getMessage)
    val stream=Files.list(dir)
    try { import scala.collection.JavaConverters._; assert(!stream.iterator.asScala.exists(_.toString.endsWith(".v"))) }
    finally stream.close()
  }

  for (fixed <- Seq(false,true)) test(s"symbolic Bits resize retains its width through cast and comparison fixed=$fixed") {
    val dir=Files.createTempDirectory("native-resize-cast-")
    MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false)) {
      new Component {
        setDefinitionName("Top")
        @dontName val sourceWidth=morphhdl.frontend.HdlInt.param("SOURCE",17,1,32).asElabInt
        @dontName val targetWidth=if(fixed) ElabInt.literal(8) else morphhdl.frontend.HdlInt.param("TARGET",8,2,16).asElabInt
        val data=in Bits(sourceWidth bits)
        val expected=in UInt(targetWidth bits)
        val different=out Bool()
        val shifted=Bits(sourceWidth bits).dontSimplifyIt()
        shifted := data
        val converted=shifted.resize(targetWidth).asUInt.setName("converted").dontSimplifyIt()
        different := converted =/= expected
      }
    }
  }
}
