package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class LightweightTypedCapacityChild[T <: BaseType with BitwiseOp[T]](
    make: ElabInt => T, actualRows: ElabInt, actualColumns: ElabInt,
    actualWidth: ElabInt, hasWidth: Boolean) extends Component {
  setDefinitionName("TypedCapacityChild")
  val rows: ElabInt=RtlParam.int("ROWS",actualRows,1,4)
  val columns: ElabInt=RtlParam.int("COLS",actualColumns,1,5)
  val width: ElabInt=if(hasWidth) RtlParam.int("WIDTH",actualWidth,1,64) else ElabInt.literal(1)
  val source=in(Vec(Vec(make(width),columns),rows))
  val result=out(Vec(Vec(make(width),columns),rows))
  for(i<-0 until rows) {
    for(j<-0 until columns) result(i)(j).assignFrom(~source(i)(j))
  }
}

class LightweightNestedCapacityTypeTests extends AnyFunSuite {
  private def check[T <: BaseType with BitwiseOp[T]](make: ElabInt => T, hasWidth: Boolean, unpacked: Boolean): Unit = {
    def generate() = {
      val dir=Files.createTempDirectory("typed-nested-capacity-")
      MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false),
        preserveConstantVecs=true,preserveConstantLoops=true,
        vecLayout=if(unpacked) MorphAggregateOptions.UnpackedArray else MorphAggregateOptions.PackedVector)) {
        new Component {
          setDefinitionName("TypedCapacityTop")
          val rows: ElabInt=RtlParam.int("ROWS",1,1,2)
          val columns: ElabInt=RtlParam.int("COLS",1,1,3)
          val width: ElabInt=if(hasWidth) RtlParam.int("WIDTH",8,1,64) else ElabInt.literal(1)
          val source=in(Vec(Vec(make(width),columns),rows))
          val result=out(Vec(Vec(make(width),columns),rows))
          val child=new LightweightTypedCapacityChild(make,rows,columns,width,hasWidth)
          child.source := source
          result := child.result
        }
      }
      dir
    }
    val dir=generate();val again=generate()
    val files=dir.toFile.listFiles.filter(_.getName.endsWith(".v")).map(_.getName).sorted.toVector
    files.foreach(f=>assert(java.util.Arrays.equals(Files.readAllBytes(dir.resolve(f)),Files.readAllBytes(again.resolve(f))),f))
    val widths=if(hasWidth) Seq(1,8,36,64) else Seq(1)
    val widthBinding=if(hasWidth) ",.WIDTH(W)" else ""
    val profiles=(for(r<-1 to 2;c<-1 to 3;w<-widths) yield
      s"Check #(.R($r),.C($c),.W($w)) c_${r}_${c}_$w(data);").mkString("\n")
    Files.write(dir.resolve("tb.v"),s"""module Check #(parameter R=1,C=1,W=1)(input [383:0] data);
wire [R*C*W-1:0] result;
TypedCapacityTop #(.ROWS(R),.COLS(C)$widthBinding) dut(.source(data[R*C*W-1:0]),.result(result));
always @(data) begin #1; if(result !== ~data[R*C*W-1:0]) $$fatal; end endmodule
module tb; reg [383:0] data;integer i,j;
$profiles
initial begin data=0;#3;data=~384'd0;#3;data='bx;#3;data='bz;#3;
for(i=0;i<384;i=i+1) begin data=384'd1<<i;#3;end
for(i=0;i<100;i=i+1) begin for(j=0;j<12;j=j+1) data[j*32+:32]=$$random;#3;end
$$finish;end endmodule
""".getBytes("UTF-8"))
    val geometry=if(hasWidth) "-set ROWS 2 -set COLS 3 -set WIDTH 64" else "-set ROWS 2 -set COLS 3"
    for(command<-Seq(Seq("iverilog","-g2001","-s","tb","-o","sim")++files++Seq("tb.v"),Seq("vvp","sim"),
      Seq("verilator","--lint-only","--language","1364-2001","-DSYNTHESIS","-Wno-fatal","--top-module","TypedCapacityTop")++files,
      Seq("yosys","-Q","-T","-p",s"read_verilog ${files.mkString(" ")}; chparam $geometry TypedCapacityTop; hierarchy -check -top TypedCapacityTop; synth -top TypedCapacityTop; check -assert; select -assert-none t:$$dlatch"))) {
      val(status,log)=Increment66ToolEvidence.run(dir,command);assert(status==0,s"$dir\n$log")
    }
  }
  for(unpacked<-Seq(false,true)) {
    test(s"nested UInt capacity preserves typed geometry unpacked=$unpacked") { check(w=>UInt(w bits),true,unpacked) }
    test(s"nested Bits capacity preserves typed geometry unpacked=$unpacked") { check(w=>Bits(w bits),true,unpacked) }
    test(s"nested Bool capacity preserves typed geometry unpacked=$unpacked") { check(_=>Bool(),false,unpacked) }
  }
}
