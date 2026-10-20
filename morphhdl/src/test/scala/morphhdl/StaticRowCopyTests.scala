package morphhdl
import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class StaticRowCopyTests extends AnyFunSuite {
  for(packed<-Seq(false,true);named<-Seq(false,true);nested<-Seq(false,true))
    test(s"finite copies of static rows retain root coordinates packed=$packed named=$named nested=$nested") {
      val dir=Files.createTempDirectory("static-row-copy-")
      val base=SpinalConfig(targetDirectory=dir.toString,headerWithDate=false)
      val config=if(named)MorphNamedFieldVectors.enable(base) else MorphNamedFieldVectors.disable(base)
      MorphVerilog(MorphAggregateOptions(config,preserveConstantLoops=true,preserveConstantVecs=true,
        vecLayout=if(packed)MorphAggregateOptions.PackedVector else MorphAggregateOptions.UnpackedArray)) {
        new Component {
          setDefinitionName("RowCopies")
          val WIDTH:Param[Int]=Param(5);require(WIDTH>=1 && WIDTH<=36)
          val COUNT:Param[Int]=Param(8);require(COUNT>=1 && COUNT<=16)
          val source=in Bits(WIDTH*16*7 bits)
          val result=out Bits(COUNT*WIDTH*7 bits)
          val terms=Vec(Vec(UInt(WIDTH bits),ElabInt.literal(16)),ElabInt.literal(7))
          if(nested) {
            val incoming=Vec(Vec(UInt(WIDTH bits),ElabInt.literal(16)),ElabInt.literal(7))
            incoming.assignFromBits(source)
            val rowCount:ElabInt=ElabInt.literal(7)
            val columnCount:ElabInt=ElabInt.literal(16)
            for(row<-0 until rowCount) {
              for(column<-0 until columnCount)terms(row)(column):=incoming(row)(column)
            }
          } else terms.assignFromBits(source)
          val rows=Vec.tabulate(7) { counter =>
            val live=Vec(UInt(WIDTH bits),COUNT)
            for(port<-0 until COUNT)live(port):=terms(counter)(port)
            live.asBits
          }
          result:=rows.asBits
        }
      }
      val bench=new StringBuilder("module tb; integer n;\n")
      for(w<-Seq(1,5,36);c<-Seq(1,8,16)) {
        val tag=s"w${w}c$c"
        bench.append(s"reg [${7*16*w-1}:0] source_$tag;wire[${7*c*w-1}:0] result_$tag;\n")
        bench.append(s"RowCopies #(.WIDTH($w),.COUNT($c)) dut_$tag(source_$tag,result_$tag);\n")
      }
      bench.append("initial begin for(n=0;n<40;n=n+1)begin\n")
      for(w<-Seq(1,5,36);c<-Seq(1,8,16);r<-0 until 7;p<-0 until 16)
        bench.append(s"source_w${w}c$c[${(r*16+p)*w}+:$w]={$$random,$$random};\n")
      bench.append("#1;\n")
      for(w<-Seq(1,5,36);c<-Seq(1,8,16);r<-0 until 7;p<-0 until c)
        bench.append(s"""if(result_w${w}c$c[${(r*c+p)*w}+:$w]!==source_w${w}c$c[${(r*16+p)*w}+:$w])$$fatal(1,"row coordinates");
""")
      bench.append("end $finish;end endmodule\n")
      Files.write(dir.resolve("tb.v"),bench.toString.getBytes("UTF-8"))
      for(cmd<-Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","RowCopies.v","tb.v"),Seq("vvp","sim"),
        Seq("verilator","--lint-only","--language","1364-2001","-DSYNTHESIS","-Wno-fatal","RowCopies.v"),
        Seq("yosys","-Q","-p","read_verilog -DSYNTHESIS RowCopies.v; hierarchy -check -top RowCopies; proc; opt; check -assert; select -assert-none t:$dlatch"))) {
        val r=Increment66ToolEvidence.run(dir,cmd);assert(r._1==0,r._2)
      }
    }
  test("signed row reads preserve their exact lane bits") {
    val dir=Files.createTempDirectory("signed-row-copy-")
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false),
      preserveConstantLoops=true,preserveConstantVecs=true,vecLayout=MorphAggregateOptions.UnpackedArray)) {
      new Component {
        setDefinitionName("SignedRow")
        val source=in Bits(32 bits)
        val result=out Bits(16 bits)
        val rows=Vec(Vec(SInt(8 bits),ElabInt.literal(2)),ElabInt.literal(2))
        rows.assignFromBits(source)
        val count:ElabInt=ElabInt.literal(2)
        val live=Vec(SInt(8 bits),count)
        for(i<-0 until count)live(i):=rows(1)(i)
        result:=live.asBits
      }
    }
    Files.write(dir.resolve("tb.v"),"""module tb;
reg[31:0]source;wire[15:0]result;integer n;reg signed[7:0]a,b;reg signed[7:0]aa,bb;
SignedRow dut(source,result);
initial begin for(n=0;n<256;n=n+1)begin source=$random;a=source[23:16];b=source[31:24];aa=a;bb=b;#1;
if(result!=={bb,aa})$fatal(1,"signed");end $finish;end endmodule
""".getBytes("UTF-8"))
    for(cmd<-Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","SignedRow.v","tb.v"),Seq("vvp","sim"))) {
      val r=Increment66ToolEvidence.run(dir,cmd);assert(r._1==0,r._2)
    }
  }

  test("a row copy rejects a selector whose legal domain exceeds the source row") {
    val error=intercept[Exception] {
      MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=Files.createTempDirectory("row-domain-negative-").toString),
        preserveConstantLoops=true,preserveConstantVecs=true)) {
        new Component {
          val COUNT:Param[Int]=Param(2);require(COUNT>=1 && COUNT<=4)
          val source=in Bits(48 bits)
          val result=out Bits(COUNT*8 bits)
          val rows=Vec(Vec(UInt(8 bits),ElabInt.literal(3)),ElabInt.literal(2))
          rows.assignFromBits(source)
          val live=Vec(UInt(8 bits),COUNT)
          for(i<-0 until COUNT)live(i):=rows(1)(i)
          result:=live.asBits
        }
      }
    }
    val message=Iterator.iterate[Throwable](error)(_.getCause).takeWhile(_!=null).map(_.toString).mkString("\n")
    assert(message.contains("finite subrange exceeds logical Vec depth"),message)
  }

  test("a finite row read cannot import a child's private storage") {
    val error=intercept[Exception] {
      MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=Files.createTempDirectory("row-owner-negative-").toString),
        preserveConstantLoops=true,preserveConstantVecs=true)) {
        new Component {
          val source=in Bits(32 bits)
          val result=out Bits(16 bits)
          val child=new Component {
            val input=in Bits(32 bits)
            val rows=Vec(Vec(Bits(8 bits),ElabInt.literal(2)),ElabInt.literal(2))
            rows.assignFromBits(input)
          }
          child.input:=source
          val count:ElabInt=ElabInt.literal(2)
          val live=Vec(Bits(8 bits),count)
          for(i<-0 until count)live(i):=child.rows(0)(i)
          result:=live.asBits
        }
      }
    }
    val message=Iterator.iterate[Throwable](error)(_.getCause).takeWhile(_!=null).map(_.toString).mkString("\n")
    assert(message.contains("STRUCTURAL-VEC-COMPONENT-MISMATCH"),message)
  }

}
