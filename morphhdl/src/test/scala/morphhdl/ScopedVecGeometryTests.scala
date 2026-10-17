package morphhdl

import java.nio.file.Files
import scala.collection.JavaConverters._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

case class ScopedGeometryWord() extends Bundle { val low = UInt(5 bits); val high = SInt(3 bits) }

/** Composition of native recursive ownership, logical Vec packing and finite writers. */
class ScopedVecGeometryTests extends AnyFunSuite {
  private def directory(): java.nio.file.Path = sys.env.get("MORPHHDL_VEC_EVIDENCE") match {
    case Some(path) => Files.createTempDirectory(Files.createDirectories(java.nio.file.Paths.get(path)), "scoped-vec-")
    case None => Files.createTempDirectory("scoped-vec-")
  }
  private def generate(mode: String, kind: String, unpacked: Boolean, default: Int,
      badWidth: Boolean = false, sharing: Boolean = false, differentBody: Boolean = false, split: Boolean = false): (java.nio.file.Path, String) = {
    val dir = directory()
    def word(): Data = kind match {
      case "signed" => SInt(8 bits)
      case "composite" => ScopedGeometryWord()
      case _ => Bits(8 bits)
    }
    def instance(actual: ElabInt, addition: Boolean = false) = new Component {
        setDefinitionName("ScopedTree")
        @dontName val count = ElabFormalParameter(actual, "COUNT", 1, 5)
        val terms = in Bits((if(mode=="shrinking") count*8 else ElabInt.literal(40)) bits)
        val result = out Bits(8 bits)
        val lanes = Vec(word(), if(mode=="finite") ElabInt.literal(5) else count).setName("lanes")
        if(mode=="finite") ElabFiniteRange.foreach(ElabInt.literal(5), "unpack") { index =>
          index(lanes).assignFromBits(index.packed(terms, 8, 8 bits))
        } else lanes.assignFromBits(terms.resize(count*8))
        ElabControl.selectSymbolic(count <= 1, "ScopedTree", 1) { result := lanes(0).asBits } {
          @dontName val nextCount = (count+1)/2
          val nextLanes = Vec(word(), nextCount).setName("nextLanes")
          for(i <- 0 until 3) ElabControl.generateSymbolic(count > 2*i, "ScopedTree", 2) {
            val combined = word()
            combined.flatten.foreach(_.dontSimplifyIt())
            if(i < 2) ElabControl.selectSymbolic(count > 2*i+1, "ScopedTree", 3) {
              val left = lanes(2*i).asBits.asUInt
              val right = lanes(2*i+1).asBits.asUInt
              combined.assignFromBits((if(addition) left + right else left - right).asBits)
            } { combined := lanes(2*i) }
            else combined := lanes(2*i)
            nextLanes(i) := combined
          }
          val nextLevel = new BlackBox {
            setBlackBoxName("ScopedTree")
            addGeneric("COUNT", nextCount)
            val terms = in Bits((if(mode=="shrinking") nextCount*8+(if(badWidth) 1 else 0) else ElabInt.literal(40)) bits)
            val result = out Bits(8 bits)
          }
          nextLevel.terms := (if(mode=="shrinking" && !badWidth) nextLanes.asBits
            else if(mode=="shrinking") nextLanes.asBits.resize(nextCount*8+1)
            else nextLanes.asBits.resize(40))
          result := nextLevel.result
        }
      }
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString, headerWithDate=false, oneFilePerComponent=split),
      preserveConstantVecs=true, preserveConstantLoops=true,
      vecLayout=if(unpacked) MorphAggregateOptions.UnpackedArray else MorphAggregateOptions.PackedVector)) {
      if (!sharing) instance(ElabInt.literal(default))
      else new Component {
        setDefinitionName("ScopedSharing")
        val source = in Bits(40 bits)
        val firstResult, secondResult = out Bits(8 bits)
        val first = instance(ElabInt.literal(default))
        val second = instance(ElabInt.literal(6-default), addition=differentBody)
        val firstInput = Bits(ElabInt.literal(if(mode=="shrinking") default*8 else 40) bits).dontSimplifyIt()
        val secondInput = Bits(ElabInt.literal(if(mode=="shrinking") (6-default)*8 else 40) bits).dontSimplifyIt()
        firstInput := source.resized
        secondInput := source.resized
        first.terms := firstInput
        second.terms := secondInput
        firstResult := first.result
        secondResult := second.result
      }
    }
    val files = Files.list(dir)
    val rtl = try files.iterator.asScala.filter(_.toString.endsWith(".v")).toVector.sortBy(_.getFileName.toString)
      .map(path => new String(Files.readAllBytes(path), "UTF-8")).mkString("\n") finally files.close()
    (dir, rtl)
  }

  for(mode <- Seq("shrinking", "unpack", "finite"); kind <- Seq("bits", "signed", "composite"); unpacked <- Seq(false,true))
  test(s"scoped recursive Vec geometry mode=$mode kind=$kind unpacked=$unpacked") {
    for(default <- Seq(1,5)) {
      val (dir, rtl) = generate(mode, kind, unpacked, default)
      assert(rtl == generate(mode, kind, unpacked, default)._2)
      assert("(?m)^module ScopedTree\\b".r.findAllIn(rtl).size == 1, rtl)
      assert(!rtl.contains("native_edge_"), rtl)
      val instances = (1 to 5).map { count =>
        val input = if(mode=="shrinking") s"terms[${count*8-1}:0]" else "terms"
        s"ScopedTree #(.COUNT($count)) dut$count(.terms($input), .result(result${count}));"
      }.mkString("\n")
      val comparisons = (1 to 5).map(count => s"""
for(j=0;j<5;j=j+1) work[j]=terms[j*8 +: 8];
size=$count;
while(size>1) begin
for(j=0;j<(size+1)/2;j=j+1) begin
if(j*2+1<size) work[j]=work[j*2]-work[j*2+1]; else work[j]=work[j*2]; end
size=(size+1)/2; end
if(result$count !== work[0]) begin $$display("COUNT=$count actual=%h expected=%h",result$count,work[0]); $$fatal; end
""").mkString
      val bench=s"""module tb;
reg [39:0] terms; wire [7:0] result1,result2,result3,result4,result5;
reg [7:0] work[0:4]; integer n,j,size;
$instances
initial begin
for(n=0;n<100;n=n+1) begin
case(n) 0:terms=0; 1:terms=40'hffffffffff; 2:terms=40'h0102040810;
3:terms=40'bx; 4:terms=40'bz; default:terms={$$random,$$random}; endcase
#2;
$comparisons
end $$display("PASS"); $$finish; end endmodule
"""
      Files.write(dir.resolve("tb.v"), bench.getBytes("UTF-8"))
      def balanced(values: Vector[String]): String = if(values.size==1) values.head
        else balanced(values.grouped(2).map(pair => if(pair.size==1) pair.head else s"(${pair(0)} - ${pair(1)})").toVector)
      for(count <- 1 to 5) {
        val width = if(mode=="shrinking") count*8 else 40
        val expected = balanced((0 until count).map(i => s"terms[${i*8} +: 8]").toVector)
        Files.write(dir.resolve(s"proof-$count.v"),
          s"module proof(input [$width-1:0] terms, output bad); wire [7:0] actual,expected; ScopedTree #(.COUNT($count)) dut(.terms(terms),.result(actual)); assign expected=$expected; assign bad=(actual != expected); endmodule\n".getBytes("UTF-8"))
      }
      val commands=Seq(
        Seq("iverilog","-g2001","-s","tb","-o","sim","ScopedTree.v","tb.v"),
        Seq("vvp","sim")) ++ (1 to 5).flatMap(count => Seq(
        Seq("verilator","--lint-only","--language","1364-2001","-DSYNTHESIS","-Wno-fatal","--top-module","ScopedTree",s"-GCOUNT=$count","ScopedTree.v"),
        Seq("yosys","-Q","-T","-p",s"read_verilog ScopedTree.v; chparam -set COUNT $count ScopedTree; hierarchy -check -top ScopedTree; proc; select -assert-none t:$$dlatch; synth; check -assert"),
        Seq("yosys","-Q","-T","-p",s"read_verilog -formal ScopedTree.v proof-$count.v; hierarchy -check -top proof; flatten; proc; opt; sat -verify -prove bad 0 -show-inputs")))
      commands.zipWithIndex.foreach { case(command,index) =>
        val (status, output)=Increment66ToolEvidence.run(dir,command)
        Files.write(dir.resolve(s"tool-$index.log"),(command.mkString(" ")+"\n"+output).getBytes("UTF-8"))
        assert(status==0, command.mkString(" ")+"\n"+output)
        if(command.head=="vvp") assert(output.contains("PASS"),output)
        if(command.head=="verilator") assert(!output.contains("%Warning-IMPLICIT") && !output.contains("%Warning-SELRANGE"),output)
      }
    }
  }
  for(mode <- Seq("shrinking", "unpack", "finite"); unpacked <- Seq(false,true); split <- Seq(false,true))
  test(s"recursive Vec definitions share caller defaults mode=$mode unpacked=$unpacked split=$split") {
    for(default <- Seq(1,5)) {
      val (dir, rtl) = generate(mode, "bits", unpacked, default, sharing=true, split=split)
      assert("(?m)^module ScopedTree\\b".r.findAllIn(rtl).size==1,rtl)
      assert(rtl == generate(mode, "bits", unpacked, default, sharing=true, split=split)._2)
      Files.write(dir.resolve("qualification.v"), rtl.getBytes("UTF-8"))
      val first = if(default==1) "source[7:0]" else "total"
      val second = if(default==1) "total" else "source[7:0]"
      Files.write(dir.resolve("tb.v"),s"""module tb;
reg [39:0] source; wire [7:0] firstResult,secondResult,total; integer i;
assign total=((source[7:0]-source[15:8])-(source[23:16]-source[31:24]))-source[39:32];
ScopedSharing dut(.source(source),.firstResult(firstResult),.secondResult(secondResult));
initial begin for(i=0;i<100;i=i+1) begin source={$$random,$$random}; #1;
if(firstResult !== $first || secondResult !== $second) $$fatal; end
$$display("PASS sharing"); $$finish; end endmodule
""".getBytes("UTF-8"))
      val commands=Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","qualification.v","tb.v"), Seq("vvp","sim"),
        Seq("verilator","--lint-only","--language","1364-2001","-DSYNTHESIS","-Wno-fatal","--top-module","ScopedSharing","qualification.v"))
      commands.zipWithIndex.foreach { case(command,index) =>
        val (status,output)=Increment66ToolEvidence.run(dir,command)
        Files.write(dir.resolve(s"sharing-tool-$index.log"),(command.mkString(" ")+"\n"+output).getBytes("UTF-8"))
        assert(status==0,output)
        if(command.head=="vvp") assert(output.contains("PASS sharing"),output)
      }
    }
  }

  test("recursive Vec definition sharing rejects different binary operations") {
    val error = intercept[MorphVerilogException] {
      generate("shrinking", "bits", true, 1, sharing=true, differentBody=true)
    }
    assert(error.getMessage.contains("different layout"), error.getMessage)
  }

  test("shrinking recursive port rejects wrong substituted geometry") {
    val error=intercept[MorphVerilogException] { generate("shrinking","bits",true,5,badWidth=true) }
    assert(error.getMessage.contains("RECURSION-PORT-SCHEMA-MISMATCH"),error.getMessage)
  }
  test("branch-local Vec packing rejects an escaped authoring owner") {
    val dir = directory()
    val error = intercept[MorphVerilogException] {
      MorphVerilog(SpinalConfig(targetDirectory=dir.toString)) {
        new Component {
          @dontName val count = ElabFormalParameter(ElabInt.literal(5), "COUNT", 1, 5)
          val terms = in Bits(40 bits)
          val result = out Bits(40 bits)
          var escaped: Vec[Bits] = null
          ElabControl.generateSymbolic(count > 1, "EscapedPacking", 1) {
            val lanes = Vec(Bits(8 bits), count)
            lanes.assignFromBits(terms.resize(count*8))
            escaped = lanes
          }
          result := escaped.asBits.resize(40)
        }
      }
    }
    assert(error.getMessage.contains("PROJECTION-SCOPE-EXPANSION"),error.getMessage)
  }
}
