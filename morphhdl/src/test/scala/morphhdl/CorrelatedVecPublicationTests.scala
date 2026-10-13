package morphhdl

import java.nio.file.Files
import scala.collection.JavaConverters._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._


/** Composition of native recursive ownership, logical Vec packing and finite writers. */
class CorrelatedVecPublicationTests extends AnyFunSuite {
  private def directory(): java.nio.file.Path = sys.env.get("MORPHHDL_VEC_EVIDENCE") match {
    case Some(path) => Files.createTempDirectory(Files.createDirectories(java.nio.file.Paths.get(path)), "scoped-vec-")
    case None => Files.createTempDirectory("scoped-vec-")
  }
  private def generate(mode: String, kind: String, unpacked: Boolean, default: Int): (java.nio.file.Path, String) = {
    val dir = directory()
    def word(): Data = kind match {
      case "signed" => SInt(8 bits)
      case "composite" => ScopedGeometryWord()
      case _ => Bits(8 bits)
    }
    def instance(actual: ElabInt) = new Component {
        setDefinitionName("CorrelatedTree")
        @dontName val count = ElabFormalParameter(actual, "COUNT", 1, 8)
        val terms = in Bits((count*8) bits)
        val result = out Bits(8 bits)
        val lanes = Vec(word(), count).setName("lanes")
        if(mode=="words") ElabFiniteRange.foreach(count, "unpack") { index =>
          index(lanes).assignFromBits(index.packed(terms, 8, 8 bits))
        } else lanes.assignFromBits(terms.resize(count*8))
        ElabControl.selectSymbolic(count <= 1, "CorrelatedTree", 1) { result := lanes(0).asBits } {
          @dontName val nextCount = (count+1)/2
          val nextLanes = Vec(word(), nextCount).setName("nextLanes")
          val mixed = if(mode=="mixed") Some(Vec(word(), nextCount).setName("combined")) else None
          for(i <- 0 until 4) ElabControl.generateSymbolic(count > 2*i, "CorrelatedTree", 2) {
            val combined = mixed.map(_(i)).getOrElse(word())
            combined.flatten.foreach(_.dontSimplifyIt())
            ElabControl.selectSymbolic(count > 2*i+1, "CorrelatedTree", 3) {
              val left = lanes(2*i).asBits.asUInt
              val right = lanes(2*i+1).asBits.asUInt
              combined.assignFromBits((left - right).asBits)
            } { combined := lanes(2*i) }
            if(mixed.isEmpty) nextLanes(i) := combined
          }
          mixed.foreach(vector => ElabFiniteRange.foreach(nextCount, "consume") { i => i(nextLanes) := i(vector) })
          val nextLevel = new BlackBox {
            setBlackBoxName("CorrelatedTree")
            addGeneric("COUNT", nextCount)
            val terms = in Bits((nextCount*8) bits)
            val result = out Bits(8 bits)
          }
          val packed = Bits((nextCount*8) bits).dontSimplifyIt()
          if(mode=="words") ElabFiniteRange.foreach(nextCount, "pack") { i => i.packed(packed, 8, 8 bits) := i(nextLanes).asBits }
          else packed := nextLanes.asBits
          nextLevel.terms := packed
          result := nextLevel.result
        }
      }
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString, headerWithDate=false),
      preserveConstantVecs=true, preserveConstantLoops=true,
      vecLayout=if(unpacked) MorphAggregateOptions.UnpackedArray else MorphAggregateOptions.PackedVector)) {
      instance(ElabInt.literal(default))
    }
    val files = Files.list(dir)
    val rtl = try files.iterator.asScala.filter(_.toString.endsWith(".v")).toVector.sortBy(_.getFileName.toString)
      .map(path => new String(Files.readAllBytes(path), "UTF-8")).mkString("\n") finally files.close()
    (dir, rtl)
  }

  for(mode <- Seq("words", "mixed"); kind <- Seq("bits", "signed", "composite"); unpacked <- Seq(false,true))
  test(s"scoped recursive Vec geometry mode=$mode kind=$kind unpacked=$unpacked") {
    for(default <- Seq(1,8)) {
      val (dir, rtl) = generate(mode, kind, unpacked, default)
      assert(rtl == generate(mode, kind, unpacked, default)._2)
      assert("(?m)^module CorrelatedTree\\b".r.findAllIn(rtl).size == 1, rtl)
      assert(!rtl.contains("native_edge_"), rtl)
      val instances = (1 to 8).map { count =>
        val input = s"terms[${count*8-1}:0]"
        s"CorrelatedTree #(.COUNT($count)) dut$count(.terms($input), .result(result${count}));"
      }.mkString("\n")
      val comparisons = (1 to 8).map(count => s"""
for(j=0;j<8;j=j+1) work[j]=terms[j*8 +: 8];
size=$count;
while(size>1) begin
for(j=0;j<(size+1)/2;j=j+1) begin
if(j*2+1<size) work[j]=work[j*2]-work[j*2+1]; else work[j]=work[j*2]; end
size=(size+1)/2; end
if(result$count !== work[0]) begin $$display("COUNT=$count actual=%h expected=%h",result$count,work[0]); $$fatal; end
""").mkString
      val bench=s"""module tb;
reg [63:0] terms; wire [7:0] result1,result2,result3,result4,result5,result6,result7,result8;
reg [7:0] work[0:7]; integer n,j,size;
$instances
initial begin
for(n=0;n<100;n=n+1) begin
case(n) 0:terms=0; 1:terms=64'hffffffffffffffff; 2:terms=64'h0102040810204080;
3:terms=64'bx; 4:terms=64'bz; default:terms={$$random,$$random}; endcase
#2;
$comparisons
end $$display("PASS"); $$finish; end endmodule
"""
      Files.write(dir.resolve("tb.v"), bench.getBytes("UTF-8"))
      def balanced(values: Vector[String]): String = if(values.size==1) values.head
        else balanced(values.grouped(2).map(pair => if(pair.size==1) pair.head else s"(${pair(0)} - ${pair(1)})").toVector)
      for(count <- 1 to 8) {
        val width = count*8
        val expected = balanced((0 until count).map(i => s"terms[${i*8} +: 8]").toVector)
        Files.write(dir.resolve(s"proof-$count.v"),
          s"module proof(input [$width-1:0] terms, output bad); wire [7:0] actual,expected; CorrelatedTree #(.COUNT($count)) dut(.terms(terms),.result(actual)); assign expected=$expected; assign bad=(actual != expected); endmodule\n".getBytes("UTF-8"))
      }
      val commands=Seq(
        Seq("iverilog","-g2001","-s","tb","-o","sim","CorrelatedTree.v","tb.v"),
        Seq("vvp","sim")) ++ (1 to 8).flatMap(count => Seq(
        Seq("verilator","--lint-only","--language","1364-2001","-DSYNTHESIS","-Wno-fatal","--top-module","CorrelatedTree",s"-GCOUNT=$count","CorrelatedTree.v"),
        Seq("yosys","-Q","-T","-p",s"read_verilog CorrelatedTree.v; chparam -set COUNT $count CorrelatedTree; hierarchy -check -top CorrelatedTree; proc; select -assert-none t:$$dlatch; synth; check -assert"),
        Seq("yosys","-Q","-T","-p",s"read_verilog -formal CorrelatedTree.v proof-$count.v; hierarchy -check -top proof; flatten; proc; opt; sat -verify -prove bad 0 -show-inputs")))
      commands.zipWithIndex.foreach { case(command,index) =>
        val (status, output)=Increment66ToolEvidence.run(dir,command)
        Files.write(dir.resolve(s"tool-$index.log"),(command.mkString(" ")+"\n"+output).getBytes("UTF-8"))
        assert(status==0, command.mkString(" ")+"\n"+output)
        if(command.head=="vvp") assert(output.contains("PASS"),output)
        if(command.head=="verilator") assert(!output.contains("%Warning-IMPLICIT") && !output.contains("%Warning-SELRANGE"),output)
      }
    }
  }
  for(mode <- Seq("short", "inverse", "independent", "foreign", "overlap", "missing"))
  test(s"finite word geometry rejects $mode") {
    val dir = directory()
    val error = intercept[MorphVerilogException] {
      MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString),
        preserveConstantVecs=true, preserveConstantLoops=true)) { new Component {
        @dontName val count = ElabFormalParameter(ElabInt.literal(4), "COUNT", 1, 8)
        @dontName val other = ElabFormalParameter(ElabInt.literal(4), "OTHER", 1, 8)
        val sourceWidth = mode match {
          case "short" => count*8-1
          case "inverse" => (ElabInt.literal(9)-count)*8
          case "independent" => other*8
          case _ => count*8
        }
        val source = in Bits(sourceWidth bits)
        val result = out Bits((count*8) bits)
        val foreign = if(mode=="foreign") Some(new Component {
          val value = out Bits(64 bits)
          value := 0
        }) else None
        if(mode=="overlap") result(0,8 bits) := B(0,8 bits)
        val bound = if(mode=="missing") count-1 else count
        ElabFiniteRange.foreach(bound, "copy") { i =>
          i.packed(result,8,8 bits) := i.packed(foreign.map(_.value).getOrElse(source),8,8 bits)
        }
      }}
    }
    val message = error.getMessage
    mode match {
      case "short" | "inverse" => assert(message.contains("SLICE-DOMAIN-UNSUPPORTED"), message)
      case "independent" => assert(message.contains("DOMAIN") || message.contains("ROOT"), message)
      case "foreign" => assert(message.contains("SOURCE-OWNER-MISMATCH"), message)
      case "overlap" => assert(message.contains("ASSIGNMENT OVERLAP") || message.contains("MULTIPLE") || message.contains("driver"), message)
      case "missing" => assert(message.contains("LATCH") || message.contains("DOMAIN") || message.contains("DRIVER"), message)
    }
  }
}
