package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

case class LightweightCapacityRecord(columns: ElabInt, width: ElabInt) extends Bundle {
  val tag=Bool()
  val payload=Vec(SInt(width bits),columns)
}
class LightweightRecordCapacityChild(actualRows: ElabInt, actualColumns: ElabInt, actualWidth: ElabInt) extends Component {
  setDefinitionName("RecordCapacityChild")
  val rows: ElabInt=RtlParam.int("ROWS",actualRows,1,4)
  val columns: ElabInt=RtlParam.int("COLS",actualColumns,1,5)
  val width: ElabInt=RtlParam.int("WIDTH",actualWidth,1,64)
  val source=in(Vec(LightweightCapacityRecord(columns,width),rows))
  val result=out(Vec(LightweightCapacityRecord(columns,width),rows))
  for(i<-0 until rows) {
    val a=source(i)
    val b=result(i)
    b.tag := a.tag
    for(j<-0 until columns) b.payload(j) := ~a.payload(j)
  }
}
class LightweightRecordCapacityTests extends AnyFunSuite {
  for(adapted<-Seq(false,true);named<-Seq(false,true);unpacked<-Seq(false,true))
    test(s"record capacity retains named fields and nested coordinates adapted=$adapted named=$named unpacked=$unpacked") {
      def generate() = {
        val dir=Files.createTempDirectory("record-capacity-")
        val config=SpinalConfig(targetDirectory=dir.toString,headerWithDate=false)
        MorphVerilog(MorphAggregateOptions(if(named) MorphNamedFieldVectors.enable(config) else MorphNamedFieldVectors.disable(config),
          preserveConstantVecs=true,preserveConstantLoops=true,
          vecLayout=if(unpacked) MorphAggregateOptions.UnpackedArray else MorphAggregateOptions.PackedVector)) {
          new Component {
            setDefinitionName("RecordCapacityTop")
            val rows: ElabInt=RtlParam.int("ROWS",1,1,if(adapted) 2 else 4)
            val columns: ElabInt=RtlParam.int("COLS",1,1,if(adapted) 3 else 5)
            val width: ElabInt=RtlParam.int("WIDTH",8,1,64)
            val source=in(Vec(LightweightCapacityRecord(columns,width),rows))
            val result=out(Vec(LightweightCapacityRecord(columns,width),rows))
            val child=new LightweightRecordCapacityChild(rows,columns,width)
            child.source := source
            result := child.result
          }
        }
        dir
      }
      val dir=generate();val again=generate()
      val files=dir.toFile.listFiles.filter(_.getName.endsWith(".v")).map(_.getName).sorted.toVector
      files.foreach(f=>assert(java.util.Arrays.equals(Files.readAllBytes(dir.resolve(f)),Files.readAllBytes(again.resolve(f))),f))
      val rows=if(adapted) 2 else 4;val columns=if(adapted) 3 else 5
      val profiles=(for(r<-1 to rows;c<-1 to columns;w<-Seq(1,8,36,64)) yield
        s"Check #(.R($r),.C($c),.W($w)) c_${r}_${c}_$w(data,tags);").mkString("\n")
      val connection=if(named) ".source_tag(tags[R-1:0]),.source_payload(data[R*C*W-1:0]),.result_tag(result_tag),.result_payload(result_payload)"
        else ".source(source_bits),.result(result_bits)"
      val namedResult=if(named) "assign result_bits[r*(1+C*W)]=result_tag[r]; assign result_bits[r*(1+C*W)+1+:C*W]=result_payload[r*C*W+:C*W];" else ""
      Files.write(dir.resolve("tb.v"),s"""module Check #(parameter R=1,C=1,W=1)(input [1279:0] data,input [3:0] tags);
wire [R*(1+C*W)-1:0] source_bits,result_bits,expected;
wire [R-1:0] result_tag;wire [R*C*W-1:0] result_payload;
genvar r;generate for(r=0;r<R;r=r+1) begin : pack
assign source_bits[r*(1+C*W)]=tags[r];
assign source_bits[r*(1+C*W)+1+:C*W]=data[r*C*W+:C*W];
assign expected[r*(1+C*W)]=tags[r];
assign expected[r*(1+C*W)+1+:C*W]=~data[r*C*W+:C*W];
$namedResult
end endgenerate
RecordCapacityTop #(.ROWS(R),.COLS(C),.WIDTH(W)) dut($connection);
always @(data or tags) begin #1; if(result_bits !== expected) $$fatal(1,"record boundary R=%d C=%d W=%d",R,C,W);end
endmodule
module tb;reg [1279:0] data;reg [3:0] tags;integer i,j;
$profiles
initial begin data=0;tags=0;#3;data=~1280'd0;tags=15;#3;data='bx;tags='bz;#3;data='bz;tags='bx;#3;
for(i=0;i<1280;i=i+1) begin data=1280'd1<<i;tags=i;#3;end
for(i=0;i<100;i=i+1) begin for(j=0;j<40;j=j+1) data[j*32+:32]=$$random;tags=$$random;#3;end
$$finish;end endmodule
""".getBytes("UTF-8"))
      for(command<-Seq(Seq("iverilog","-g2001","-s","tb","-o","sim")++files++Seq("tb.v"),Seq("vvp","sim"),
        Seq("verilator","--lint-only","--language","1364-2001","-DSYNTHESIS","-Wno-fatal","--top-module","RecordCapacityTop")++files,
        Seq("yosys","-Q","-T","-p",s"read_verilog ${files.mkString(" ")}; chparam -set ROWS $rows -set COLS $columns -set WIDTH 64 RecordCapacityTop; hierarchy -check -top RecordCapacityTop; synth -top RecordCapacityTop; check -assert; select -assert-none t:$$dlatch"))) {
        val(status,log)=Increment66ToolEvidence.run(dir,command);assert(status==0,s"$dir\n$log")
      }
    }
}
