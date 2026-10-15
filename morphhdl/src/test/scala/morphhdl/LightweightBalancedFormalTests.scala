package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib.BalancedTree

/** Independent two-state combinational proof; X/Z behavior is simulated separately. */
class LightweightBalancedFormalTests extends AnyFunSuite {
  for(count <- Seq(3,8)) test(s"library adjacent subtraction and odd-tail bridge formal COUNT=$count") {
    val directory=Files.createTempDirectory("lightweight-library-formal-")
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=directory.toString,headerWithDate=false),
      preserveConstantVecs=true,preserveConstantLoops=true,vecLayout=MorphAggregateOptions.UnpackedArray)) {
      BalancedTree.define(HardType(UInt(8 bits)),8,"FormalTree")(
        (a:UInt,b:UInt)=>a-b,(a:UInt,level:Int)=>a+U(level+1,8 bits)).component(ElabInt.literal(1))
    }
    val reference=new StringBuilder
    var words=(0 until count).map(i=>s"terms[${i*8}+:8]").toVector
    var level=0
    while(words.size>1) {
      words=words.grouped(2).zipWithIndex.map { case(group,lane)=>
        val name=s"level_${level}_$lane"
        val operation=if(group.size==1) group.head else s"(${group(0)} - ${group(1)})"
        reference.append(s"wire [7:0] ${name}_combined=$operation; wire [7:0] $name=${name}_combined + 8'd${level+1};\n")
        name
      }.toVector
      level+=1
    }
    Files.write(directory.resolve("proof.v"),s"""module proof(input [${count*8-1}:0] terms, output ok);
wire [7:0] result;
FormalTree #(.COUNT($count),.INITIAL_COUNT($count)) dut(.clk(1'b0),.reset(1'b0),.softReset(1'b0),.enable(1'b1),.terms(terms),.result(result));
$reference
assign ok=(result == ${words.head}); endmodule
""".getBytes("UTF-8"))
    val(status,log)=Increment66ToolEvidence.run(directory,Seq("yosys","-Q","-T","-p",
      "read_verilog FormalTree.v proof.v; prep -top proof -flatten; opt; sat -verify -prove ok 1 -show-inputs -show-outputs"))
    assert(status==0 && log.contains("SUCCESS"),s"$directory\n$log")
  }
}
