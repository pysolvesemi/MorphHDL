package morphhdl

import java.nio.file.Files
import java.nio.charset.StandardCharsets.UTF_8
import spinal.core._
import morphhdl.frontend.HdlInt
import org.scalatest.funsuite.AnyFunSuite
import scala.sys.process.{Process, ProcessLogger}

class NativeTypedLocalConstantTests extends AnyFunSuite {
  for (bits <- Seq(1,8,32,64)) test(s"named packed dependencies retain signed Int arithmetic width=$bits") {
    val dir=Files.createTempDirectory("native-local-arithmetic-")
    MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false)) {
      new Component {
        setDefinitionName("Top")
        @dontName val parameter=HdlInt.param("VALUE",1,0,1).asElabInt
        val first=TypedLocalUInt("FIRST",parameter,bits bits)
        val second=TypedLocalUInt("SECOND",first.elab+4,8 bits)
        val divided=TypedLocalUInt("DIVIDED",(first.elab+8) / 2,8 bits)
        val remainder=TypedLocalUInt("REMAINDER",(first.elab+8) % 3,8 bits)
        val product=TypedLocalUInt("PRODUCT",(first.elab-8)* -1,8 bits)
        val incoming=in Bool(); val echo=out Bool(); echo := incoming
        val raw=out UInt(bits bits)
        val added,quotient,modulo,multiplied=out UInt(8 bits)
        raw := first.asUInt; added := second.asUInt; quotient := divided.asUInt
        modulo := remainder.asUInt; multiplied := product.asUInt
      }
    }
    for(value <- Seq(0,1)) {
      Files.write(dir.resolve("tb.v"),s"""module tb;
reg incoming; wire echo; wire [$bits-1:0] raw; wire [7:0] added,quotient,modulo,multiplied;
Top #(.VALUE($value)) dut(incoming,echo,raw,added,quotient,modulo,multiplied);
initial begin incoming=1; #1;
if(echo!==incoming || raw!==$bits'd$value || added!==8'd${value+4} || quotient!==8'd${(value+8)/2} ||
 modulo!==8'd${(value+8)%3} || multiplied!==8'd${(value-8)* -1}) $$fatal;
$$finish;end endmodule
""".getBytes(UTF_8))
      Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","Top.v","tb.v"),
        Seq("timeout","10","vvp","sim"),
        Seq("verilator","--lint-only","-Wall","--language","1364-2001","-DSYNTHESIS","--top-module","Top",s"-GVALUE=$value","Top.v"),
        Seq("yosys","-Q","-p",s"read_verilog Top.v; chparam -set VALUE $value Top; synth -top Top; check -assert")
      ).foreach { command =>
        val (code,log)=Increment66ToolEvidence.run(dir,command)
        assert(code==0,s"$command failed in $dir\n$log")
      }
    }
  }
  class Decoder(symbolic: Boolean) extends Component {
    @dontName private val base = if (symbolic) HdlInt.param("BASE_WORD",1,0,14).asElabInt * 4 else ElabInt.literal(4)
    val control = TypedLocalUInt("ADDR_CONTROL",base,8 bits)
    val status = TypedLocalUInt("ADDR_STATUS",control.elab + 4,8 bits)
    val address = in UInt(8 bits)
    val writeFire = in Bool()
    val strobes = in Bits(4 bits)
    val hit = out Bool()
    val decoded = out UInt(8 bits)
    val summed = out UInt(8 bits)
    val concatenated = out Bits(16 bits)
    val shifted = out UInt(8 bits)
    val less = out Bool()
    val selectedStrobes = out Bits(4 bits)
    concatenated := status.asUInt.asBits ## control.asUInt.asBits
    shifted := (address |<< status.asUInt).resize(8)
    less := address < control.asUInt
    hit := address === control.asUInt
    summed := address + status.asUInt
    decoded := 0
    switch(address) { is(control) { decoded := 1 }; is(status) { decoded := 2 } }
    selectedStrobes := 0
    when(writeFire) { switch(address) { is(control) { selectedStrobes := strobes } } }
  }
  for ((mode, code) <- Seq(
      0 -> "DECLARATION-INVALID", 1 -> "VALUE-OUT-OF-RANGE", 2 -> "VALUE-OUT-OF-RANGE",
      3 -> "NAME-DUPLICATE", 4 -> "CONSUMER-WIDTH-MISMATCH", 5 -> "NAME-COLLISION",
      6 -> "OWNER-MISMATCH", 7 -> "SWITCH-OVERLAP", 8 -> "OWNER-MISMATCH", 9 -> "SWITCH-OVERLAP"))
  test(s"invalid named constants fail before publication, mode=$mode") {
    val error = intercept[Exception] {
      MorphVerilog(SpinalConfig(targetDirectory=Files.createTempDirectory("invalid-typed-local-").toString)) {
        new Component {
          val a = TypedLocalUInt(if(mode==0) "bad-name" else "ADDR_A",
            ElabInt.literal(if(mode==1) 256 else if(mode==2) -1 else 4),8 bits)
          if(mode==3) { TypedLocalUInt("ADDR_A",BigInt(8),8 bits) }
          val address=in UInt((if(mode==4) 9 else 8) bits)
          if(mode==5) address.setName("ADDR_A")
          val output=out UInt(8 bits)
          output:=0
          if(mode==6) {
            val child=new Component { val output=out UInt(8 bits); output:=a.asUInt }
            output:=child.output
          } else if(mode==8) {
            val dependency=a.elab+4
            val child=new Component {
              val foreign=TypedLocalUInt("FOREIGN",dependency,8 bits)
              val output=out UInt(8 bits); output:=foreign.asUInt
            }
            output:=child.output
          } else {
            val b=TypedLocalUInt("ADDR_B", if(mode==9) HdlInt.param("OFFSET",8,0,8).asElabInt else ElabInt.literal(4),8 bits)
            switch(address) {
              is(a) { output:=1 }
              if(mode==7 || mode==9) is(b) { output:=2 }
            }
          }
        }
      }
    }
    val messages=Iterator.iterate[Throwable](error)(_.getCause).takeWhile(_!=null)
      .map(x=>String.valueOf(x.getMessage)).mkString("\n")
    assert(messages.contains("SPINAL-LOCALPARAM-"+code),messages)
  }

  for (symbolic <- Seq(false,true))
  test(s"typed locals remain packed declarations and native case labels, symbolic=$symbolic") {
    val dir = Files.createTempDirectory("native-typed-local-")
    MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false))(new Decoder(symbolic))
    val rtl = new String(Files.readAllBytes(dir.resolve("Decoder.v")),UTF_8)
    assert(rtl.contains("localparam [7:0] ADDR_CONTROL") && rtl.contains("localparam [7:0] ADDR_STATUS"),rtl)
    assert(rtl.contains("ADDR_CONTROL : begin") && rtl.contains("ADDR_STATUS : begin"),rtl)
    assert(rtl.contains("$signed({{24{1'b0}},ADDR_CONTROL}) + 4"),rtl)
    val repeated=Files.createTempDirectory("native-typed-local-repeat-")
    MorphVerilog(SpinalConfig(targetDirectory=repeated.toString,headerWithDate=false))(new Decoder(symbolic))
    assert(rtl==new String(Files.readAllBytes(repeated.resolve("Decoder.v")),UTF_8))
    for (base <- (if(symbolic) Seq(0,1,14) else Seq(1))) {
      val overrideText=if(symbolic) s"#(.BASE_WORD($base))" else ""
      Files.write(dir.resolve("tb.v"),s"""module tb;
        |reg [7:0] address; reg writeFire; reg [3:0] strobes; wire [3:0] selectedStrobes;
        |wire hit,less; wire [7:0] decoded,summed,shifted; wire [15:0] concatenated; integer i,s,f;
        |Decoder $overrideText dut(address,writeFire,strobes,hit,decoded,summed,concatenated,shifted,less,selectedStrobes);
        |initial begin for(i=0;i<256;i=i+1) for(s=0;s<16;s=s+1) for(f=0;f<2;f=f+1) begin address=i; strobes=s; writeFire=f; #1;
        |if(hit !== (i==${base*4}) || decoded !== ((i==${base*4}) ? 8'd1 : (i==${base*4+4}) ? 8'd2 : 8'd0)) $$fatal(1,"LOCAL_DECODE");
        |if(summed !== ((i+${base*4+4}) & 255)) $$fatal(1,"LOCAL_ARITHMETIC");
        |if(concatenated !== ${((base*4+4)<<8)|(base*4)} || shifted !== ((i << ${base*4+4}) & 255) || less !== (i < ${base*4})) $$fatal(1,"LOCAL_NESTED");
        |if(selectedStrobes !== ((writeFire && i==${base*4}) ? strobes : 4'b0)) $$fatal(1,"LOCAL_WRITE");
        |end address=8'bxxxxxxxx; #1; if(decoded !== 0 || hit !== 1'bx) $$fatal(1,"LOCAL_X");
        |address=8'bzzzzzzzz; #1; if(decoded !== 0 || hit !== 1'bx) $$fatal(1,"LOCAL_Z");
        |address=${base*4}; writeFire=1; strobes=4'bxz01; #1; if(selectedStrobes !== strobes) $$fatal(1,"LOCAL_STROBE_XZ");
        |writeFire=1'bx; #1; if(selectedStrobes !== 0) $$fatal(1,"LOCAL_FIRE_X");
        |writeFire=1'bz; #1; if(selectedStrobes !== 0) $$fatal(1,"LOCAL_FIRE_Z");
        |$$finish; end endmodule
        |""".stripMargin.getBytes(UTF_8))
      val log=new StringBuilder
      def run(args:Seq[String]):Int={
        val result=Increment66ToolEvidence.run(dir,args)
        log.append(result._2)
        result._1
      }
      Files.write(dir.resolve("oracle.v"),s"""module oracle(input [7:0] address,input writeFire,input [3:0] strobes,
        |output hit,output [7:0] decoded,summed,output [15:0] concatenated,output [7:0] shifted,output less,output [3:0] selectedStrobes);
        |assign hit=address==${base*4};
        |assign decoded=(address==${base*4})?8'd1:(address==${base*4+4})?8'd2:8'd0;
        |assign summed=address+${base*4+4}; assign concatenated=${((base*4+4)<<8)|(base*4)};
        |assign shifted=address<<${base*4+4}; assign less=address<${base*4};
        |assign selectedStrobes=(writeFire && address==${base*4})?strobes:4'b0;
        |endmodule
        |""".stripMargin.getBytes(UTF_8))
      val lint=Increment66ToolEvidence.run(dir,Seq("verilator","--lint-only","-Wall","--language","1364-2001","-DSYNTHESIS","--top-module","Decoder") ++ (if(symbolic) Seq(s"-GBASE_WORD=$base") else Nil) ++ Seq("Decoder.v"))
      if(symbolic && base==0) {
        // At address zero the authored unsigned less-than output is always
        // false. Retain this exact warning as reviewed evidence, not clean lint.
        assert(lint._1!=0 && "%Warning-([A-Z0-9_]+):".r.findAllMatchIn(lint._2).map(_.group(1)).toVector==Vector("UNSIGNED"),lint._2)
      } else assert(lint._1==0,lint._2)
      val specialize=if(symbolic) s"chparam -set BASE_WORD $base Decoder; " else ""
      assert(run(Seq("yosys","-p",s"read_verilog -DSYNTHESIS Decoder.v; $specialize synth -top Decoder; check -assert"))==0,log.toString)
      assert(run(Seq("yosys","-p",s"read_verilog -DSYNTHESIS Decoder.v oracle.v; $specialize proc; opt; equiv_make oracle Decoder equiv; hierarchy -top equiv; equiv_simple; equiv_status -assert"))==0,log.toString)
      assert(run(Seq("iverilog","-g2001","-s","tb","-o","local.vvp","Decoder.v","tb.v"))==0,log.toString)
      assert(run(Seq("vvp","local.vvp"))==0,log.toString)
    }
  }
}
