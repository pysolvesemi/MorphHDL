package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class LightweightNestedCapacityChild(actualRows: ElabInt, actualColumns: ElabInt, actualWidth: ElabInt) extends Component {
  noIoPrefix()
  val rows: ElabInt = RtlParam.int("ROWS", actualRows, 1, 4)
  val columns: ElabInt = RtlParam.int("COLS", actualColumns, 1, 5)
  val width: ElabInt = RtlParam.int("WIDTH", actualWidth, 1, 64)
  val source = in(Vec(Vec(SInt(width bits), columns), rows))
  val result = out(Vec(Vec(SInt(width bits), columns), rows))
  for (i <- 0 until rows) {
    for (j <- 0 until columns) result(i)(j) := ~source(i)(j)
  }
}

class LightweightNestedCapacityTests extends AnyFunSuite {
  for (mode <- Seq("same", "inner", "both", "literal"); unpacked <- Seq(false, true))
    test(s"nested child capacity adapts exact coordinates mode=$mode unpacked=$unpacked") {
      def generate() = {
      val dir = Files.createTempDirectory("nested-capacity-")
      MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString, headerWithDate=false),
        preserveConstantVecs=true, preserveConstantLoops=true,
        vecLayout=if(unpacked) MorphAggregateOptions.UnpackedArray else MorphAggregateOptions.PackedVector)) {
        new Component {
          setDefinitionName("NestedCapacityTop")
          val rows: ElabInt = if(mode == "literal") ElabInt.literal(2)
            else RtlParam.int("ROWS", 2, 1, if(mode == "both") 2 else 4)
          val columns: ElabInt = if(mode == "literal") ElabInt.literal(3)
            else RtlParam.int("COLS", 3, 1, if(mode == "same") 5 else 3)
          val width: ElabInt = RtlParam.int("WIDTH", 8, 1, 64)
          val source = in(Vec(Vec(SInt(width bits), columns), rows))
          val result = out(Vec(Vec(SInt(width bits), columns), rows))
          val child = new LightweightNestedCapacityChild(rows, columns, width)
          child.source := source
          result := child.result
        }
      }
      dir
      }
      val dir=generate(); val again=generate()
      val files=dir.toFile.listFiles.filter(_.getName.endsWith(".v")).map(_.getName).sorted.toVector
      files.foreach(file => assert(java.util.Arrays.equals(Files.readAllBytes(dir.resolve(file)),
        Files.readAllBytes(again.resolve(file))),file))
      val rowValues=if(mode == "literal") Seq(2) else (1 to (if(mode == "both") 2 else 4)).toVector
      val colValues=if(mode == "literal") Seq(3) else (1 to (if(mode == "same") 5 else 3)).toVector
      val binding=if(mode == "literal") "" else ".ROWS(R),.COLS(C),"
      val profiles=(for(r<-rowValues;c<-colValues;w<-Seq(1,8,36,64)) yield
        s"Check #(.R($r),.C($c),.W($w)) c_${r}_${c}_$w(data);").mkString("\n")
      Files.write(dir.resolve("tb.v"),s"""module Check #(parameter R=2,C=3,W=8)(input [1279:0] data);
wire [R*C*W-1:0] result;
NestedCapacityTop #($binding.WIDTH(W)) dut(.source(data[R*C*W-1:0]),.result(result));
always @(data) begin #1; if(result !== ~data[R*C*W-1:0]) $$fatal(1,"nested boundary R=%d C=%d W=%d",R,C,W); end
endmodule
module tb; reg [1279:0] data; integer i,j;
$profiles
initial begin data=0; #3; data=~1280'd0; #3; data='bx; #3; data='bz; #3;
for(i=0;i<1280;i=i+1) begin data=1280'd1<<i; #3; end
for(i=0;i<100;i=i+1) begin for(j=0;j<40;j=j+1) data[j*32+:32]=$$random; #3; end
$$finish; end endmodule
""".getBytes("UTF-8"))
      val synthesisGeometry=if(mode == "literal") "-set WIDTH 64"
        else s"-set WIDTH 64 -set ROWS ${rowValues.max} -set COLS ${colValues.max}"
      for(command<-Seq(Seq("iverilog","-g2001","-s","tb","-o","sim")++files++Seq("tb.v"),Seq("vvp","sim"),
        Seq("verilator","--lint-only","--language","1364-2001","-DSYNTHESIS","-Wno-fatal","--top-module","NestedCapacityTop")++files,
        Seq("yosys","-Q","-T","-p",s"read_verilog ${files.mkString(" ")}; chparam $synthesisGeometry NestedCapacityTop; hierarchy -check -top NestedCapacityTop; synth -top NestedCapacityTop; check -assert; select -assert-none t:$$dlatch"))) {
        val(status,log)=Increment66ToolEvidence.run(dir,command);assert(status==0,s"$dir\n$log")
      }
      val r=rowValues.max;val c=colValues.max;val w=64
      val actual=if(mode == "literal") "" else s".ROWS($r),.COLS($c),"
      Files.write(dir.resolve("miter.v"),s"""module Miter(input [${r*c*w-1}:0] data,output ok);
wire [${r*c*w-1}:0] result;
NestedCapacityTop #($actual.WIDTH($w)) dut(.source(data),.result(result));
assign ok = result == ~data;
endmodule
""".getBytes("UTF-8"))
      val(status,log)=Increment66ToolEvidence.run(dir,Seq("yosys","-Q","-T","-p",
        s"read_verilog -formal -DSYNTHESIS ${files.mkString(" ")} miter.v; prep -top Miter -flatten; memory_map; opt; check -assert; sat -verify -prove ok 1 -set-def-inputs"))
      assert(status==0,s"$dir\n$log")
    }
}
