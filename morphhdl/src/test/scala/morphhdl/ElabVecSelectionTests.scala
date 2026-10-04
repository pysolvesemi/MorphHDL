package morphhdl

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import morphhdl.frontend.{HdlInt, formalParam}

class ElabVecSelectionTests extends AnyFunSuite {
  private class Child(kind: String, widthActual: ElabInt, depthActual: ElabInt) extends Component {
    setDefinitionName("Child")
    @dontName private val width = if(kind == "bool") ElabInt.literal(1) else formalParam(widthActual, "WIDTH", 1, 64)
    @dontName private val depth = formalParam(depthActual, "DEPTH", 2, 8)
    private def leaf: BaseType = kind match {
      case "bool" => Bool()
      case "bits" => Bits(width bits)
      case "uint" => UInt(width bits)
      case "sint" => SInt(width bits)
    }
    val lanes = in(Vec(leaf, depth))
    val first, middle, last = out(leaf)
    val parity = out Bool()
    parity := lanes.asBits.xorR
    first.assignFrom(ElabVec.select(lanes, ElabInt.literal(0)))
    middle.assignFrom(ElabVec.select(lanes, depth / 2))
    last.assignFrom(ElabVec.select(lanes, depth - 1))
  }
  private class Top(kind: String) extends Component {
    @dontName private val width = if(kind == "bool") ElabInt.literal(1) else HdlInt.param("WIDTH", 5, 1, 64).asElabInt
    @dontName private val depth = HdlInt.param("DEPTH", 3, 2, 8).asElabInt
    val child = new Child(kind, width, depth)
    private def leaf: BaseType = kind match {
      case "bool" => Bool()
      case "bits" => Bits(width bits)
      case "uint" => UInt(width bits)
      case "sint" => SInt(width bits)
    }
    val lanes = in(Vec(leaf, depth))
    val first, middle, last = out(leaf)
    val parity = out Bool()
    parity := child.parity
    child.lanes := lanes
    first.assignFrom(child.first); middle.assignFrom(child.middle); last.assignFrom(child.last)
  }
  private def run(dir: Path, args: Seq[String]): Unit = {
    val (code, log) = Increment66ToolEvidence.run(dir, args)
    assert(code == 0, s"$args in $dir\n$log")
  }
  for(kind <- Seq("bool", "bits", "uint", "sint"); unpacked <- Seq(false, true)) {
    test(s"public parameter Vec selection kind=$kind unpacked=$unpacked") {
      val dir = Files.createTempDirectory("elab-vec-select-")
      def generate(at: Path): Unit = MorphVerilog(MorphAggregateOptions(
        SpinalConfig(targetDirectory=at.toString, headerWithDate=false, oneFilePerComponent=true),
        preserveConstantVecs=true, preserveConstantLoops=true,
        vecLayout=if(unpacked) MorphAggregateOptions.UnpackedArray else MorphAggregateOptions.PackedVector))(new Top(kind))
      generate(dir)
      val repeat = Files.createTempDirectory("elab-vec-repeat-"); generate(repeat)
      for(name <- Seq("Top.v", "Child.v"))
        assert(java.util.Arrays.equals(Files.readAllBytes(dir.resolve(name)), Files.readAllBytes(repeat.resolve(name))))
      val rtl = new String(Files.readAllBytes(dir.resolve("Child.v")), UTF_8)
      assert(!rtl.contains(" ? ") && !rtl.contains("typed_vec_read_address") && !rtl.contains("param_value"), rtl)
      val profiles = for(w <- (if(kind=="bool") Seq(1) else Seq(1,5,64)); d <- Seq(2,3,8)) yield (w,d)
      val instances = profiles.map { case(w,d) => s"wire done${w}_$d;profile #(.W($w),.D($d)) p${w}_$d(done${w}_$d);" }.mkString("\n")
      val all = profiles.map { case(w,d) => s"done${w}_$d" }.mkString(" && ")
      Files.write(dir.resolve("tb.v"),s"""module profile #(parameter W=1,D=2)(output reg done=0);
reg[D*W-1:0] lanes;wire[W-1:0] first,middle,last;wire parity;integer k,n;
Top #(${if(kind=="bool") "" else ".WIDTH(W),"}.DEPTH(D)) dut(.lanes(lanes),.first(first),.middle(middle),.last(last),.parity(parity));
initial begin for(k=0;k<48;k=k+1) begin
for(n=0;n<D;n=n+1) lanes[n*W+:W]={$$random,$$random};#1;
if(parity!==( ^lanes) || first!==lanes[0+:W] || middle!==lanes[(D/2)*W+:W] || last!==lanes[(D-1)*W+:W]) $$fatal(1,"selector order");
end done=1;end endmodule
module tb;$instances initial begin wait($all);$$finish;end endmodule
""".getBytes(UTF_8))
      run(dir,Seq("iverilog","-g2001","-s","tb","-o","sim","Top.v","Child.v","tb.v"))
      run(dir,Seq("timeout","15","vvp","sim"))
      for((w,d) <- profiles) {
        run(dir,Seq("verilator","--lint-only","-Wall","--language","1364-2001","-DSYNTHESIS","--top-module","Top",s"-GDEPTH=$d") ++ (if(kind=="bool") Seq.empty else Seq(s"-GWIDTH=$w")) ++ Seq("Top.v","Child.v"))
        run(dir,Seq("yosys","-Q","-p",s"read_verilog Top.v Child.v; chparam ${if(kind=="bool") "" else s"-set WIDTH $w"} -set DEPTH $d Top; synth -top Top; check -assert"))
      }
    }
  }

  for(unpacked <- Seq(false,true)) {
    test(s"parameter Bool selection preserves stopped-clock reset release unpacked=$unpacked") {
      val dir=Files.createTempDirectory("constant-reset-release-")
      MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false,
        defaultConfigForClockDomains=ClockDomainConfig(resetKind=ASYNC,resetActiveLevel=HIGH)),
        preserveConstantVecs=true,preserveConstantLoops=true,
        vecLayout=if(unpacked) MorphAggregateOptions.UnpackedArray else MorphAggregateOptions.PackedVector))(new Component {
        setDefinitionName("Top")
        @dontName private val depth=HdlInt.param("DEPTH",3,2,8)
        val released=out Bool()
        val stages=Vec.fill(depth.asElabInt)((Reg(Bool()) init False).addAttribute("ASYNC_REG","TRUE"))
        stages(0):=True
        import morphhdl.frontend.{HdlIntRangeStart,StructuralVecOps}
        (0 until (depth-1)).named("shift","lane").foreach { i => stages(i+1):=stages(i) }
        released:=ElabVec.select(stages,depth.asElabInt-1)
      })
      val rtl=new String(Files.readAllBytes(dir.resolve("Top.v")),UTF_8)
      assert(!rtl.contains("param_value") && !rtl.contains("typed_vec_read_address") && !rtl.contains(" ? "),rtl)
      assert(rtl.contains("ASYNC_REG"),rtl)
      val instances=(2 to 8).map(d=>s"wire done$d;profile #(.D($d)) p$d(done$d);").mkString("\n")
      val all=(2 to 8).map(d=>s"done$d").mkString(" && ")
      Files.write(dir.resolve("tb.v"),s"""module profile #(parameter D=3)(output reg done=0);
reg clk=0,reset=0;wire released;reg[D-1:0] q=0;integer k,r;
Top #(.DEPTH(D)) dut(.clk(clk),.reset(reset),.released(released));
initial begin for(r=0;r<3;r=r+1) begin
clk=0;reset=1;q=0;#2;if(released!==0) $$fatal(1,"stopped-clock async assertion");
reset=0;#7;if(released!==0) $$fatal(1,"release without clock");
for(k=0;k<D+2;k=k+1) begin clk=1;q={q[D-2:0],1'b1};#1;
if(released!==q[D-1]) $$fatal(1,"release latency");clk=0;#1;end end done=1;end endmodule
module tb;$instances initial begin wait($all);$$finish;end endmodule
""".getBytes(UTF_8))
      run(dir,Seq("iverilog","-g2001","-s","tb","-o","sim","Top.v","tb.v"))
      run(dir,Seq("timeout","15","vvp","sim"))
      for(d <- 2 to 8) {
        run(dir,Seq("verilator","--lint-only","-Wall","--language","1364-2001","-DSYNTHESIS","--top-module","Top",s"-GDEPTH=$d","Top.v"))
        run(dir,Seq("yosys","-Q","-p",s"read_verilog Top.v; chparam -set DEPTH $d Top; synth -top Top; check -assert"))
      }
    }
  }
  for(mode <- Seq("negative","past-end","partial-domain")) {
    test(s"constant selection rejects unproven bounds mode=$mode") {
      val dir=Files.createTempDirectory("constant-select-negative-")
      val error=intercept[Exception] {
        MorphVerilog(SpinalConfig(targetDirectory=dir.toString))(new Component {
          @dontName private val depth=HdlInt.param("DEPTH",3,2,8).asElabInt
          val lanes=in Vec(UInt(8 bits),depth)
          val selected=out UInt(8 bits)
          val index=mode match {
            case "negative" => ElabInt.literal(-1)
            case "past-end" => depth
            case "partial-domain" => ElabInt.literal(2)
          }
          selected:=ElabVec.select(lanes,index)
        })
      }
      val message=Iterator.iterate[Throwable](error)(_.getCause).takeWhile(_!=null).map(_.getMessage).mkString("\n")
      assert(message.contains("constant Vec selection must be in range"),message)
    }
  }

  for(unpacked <- Seq(false,true)) {
    test(s"independent constant index remains live with literal Vec depth unpacked=$unpacked") {
      val dir=Files.createTempDirectory("constant-index-literal-depth-")
      MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false),
        preserveConstantVecs=true,vecLayout=if(unpacked) MorphAggregateOptions.UnpackedArray else MorphAggregateOptions.PackedVector))(new Component {
        setDefinitionName("Top")
        @dontName private val index=HdlInt.param("INDEX",3,0,7).asElabInt
        val lanes=in Vec(UInt(8 bits),8)
        val address=in UInt(3 bits)
        val selected,runtime=out UInt(8 bits)
        selected:=ElabVec.select(lanes,index)
        runtime:=lanes(address)
      })
      val rtl=new String(Files.readAllBytes(dir.resolve("Top.v")),UTF_8)
      assert(rtl.contains("parameter integer INDEX"),rtl)
      val instances=(0 to 7).map(i=>s"wire done$i;profile #(.I($i)) p$i(done$i);").mkString("\n")
      val all=(0 to 7).map(i=>s"done$i").mkString(" && ")
      Files.write(dir.resolve("tb.v"),s"""module profile #(parameter I=3)(output reg done=0);
reg[63:0] lanes;reg[2:0] address;wire[7:0] selected,runtime;integer k;
Top #(.INDEX(I)) dut(.lanes(lanes),.address(address),.selected(selected),.runtime(runtime));
initial begin for(k=0;k<64;k=k+1) begin lanes={$$random,$$random};address=k%8;#1;
if(selected!==lanes[I*8+:8] || runtime!==lanes[address*8+:8]) $$fatal(1,"constant/runtime selection");
end done=1;end endmodule
module tb;$instances initial begin wait($all);$$finish;end endmodule
""".getBytes(UTF_8))
      run(dir,Seq("iverilog","-g2001","-s","tb","-o","sim","Top.v","tb.v"))
      run(dir,Seq("timeout","15","vvp","sim"))
      for(i <- Seq(0,3,7)) {
        run(dir,Seq("verilator","--lint-only","-Wall","--language","1364-2001","-DSYNTHESIS","--top-module","Top",s"-GINDEX=$i","Top.v"))
        run(dir,Seq("yosys","-Q","-p",s"read_verilog Top.v; chparam -set INDEX $i Top; synth -top Top; check -assert"))
      }
    }
  }

  test("constant selector rejects independently declared same-name parameter roots") {
    val dir=Files.createTempDirectory("constant-selector-roots-")
    val error=intercept[Exception] {
      MorphVerilog(SpinalConfig(targetDirectory=dir.toString))(new Component {
        @dontName private val width=HdlInt.param("INDEX",3,1,7).asElabInt
        @dontName private val index=HdlInt.param("INDEX",3,1,7).asElabInt
        val extra=in Bits(width bits)
        val parity=out Bool();parity:=extra.xorR
        val lanes=in Vec(UInt(8 bits),8)
        val result=out UInt(8 bits)
        result:=ElabVec.select(lanes,index)
      })
    }
    val message=Iterator.iterate[Throwable](error)(_.getCause).takeWhile(_!=null).map(_.getMessage).mkString("\n")
    assert(message.contains("INDEPENDENT-ROOTS"),message)
  }

  test("scalar selector formals remain shared and forwarded through a wrapper") {
    val dir=Files.createTempDirectory("constant-selector-forwarding-")
    class Leaf(actual: ElabInt) extends Component {
      setDefinitionName("Leaf",noMerge=false)
      @dontName private val index=formalParam(actual,"INDEX",0,7)
      val lanes=in Vec(UInt(8 bits),8)
      val selected=out UInt(8 bits)
      val parity=out Bool()
      selected:=ElabVec.select(lanes,index)
      parity:=lanes.asBits.xorR
    }
    class Middle(actual: ElabInt) extends Component {
      setDefinitionName("Middle",noMerge=false)
      @dontName private val index=formalParam(actual,"INDEX",0,7)
      val lanes=in Vec(UInt(8 bits),8)
      val selected=out UInt(8 bits)
      val parity=out Bool()
      val child=new Leaf(index)
      child.lanes:=lanes;selected:=child.selected;parity:=child.parity
    }
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false,oneFilePerComponent=true),
      preserveConstantVecs=true,vecLayout=MorphAggregateOptions.UnpackedArray))(new Component {
      setDefinitionName("Top")
      @dontName private val a=HdlInt.param("A",0,0,7).asElabInt
      @dontName private val b=HdlInt.param("B",7,0,7).asElabInt
      val lanes=in Vec(UInt(8 bits),8)
      val first,last=out UInt(8 bits)
      val parityA,parityB=out Bool()
      val left=new Middle(a);val right=new Middle(b)
      left.lanes:=lanes;right.lanes:=lanes
      first:=left.selected;last:=right.selected;parityA:=left.parity;parityB:=right.parity
    })
    val top=new String(Files.readAllBytes(dir.resolve("Top.v")),UTF_8)
    val middle=new String(Files.readAllBytes(dir.resolve("Middle.v")),UTF_8)
    assert(top.contains(".INDEX(A)") && top.contains(".INDEX(B)"),top)
    assert(middle.contains(".INDEX(INDEX)"),middle)
    Files.write(dir.resolve("tb.v"),"""module profile #(parameter A=0,B=7)(output reg done=0);
reg[63:0] lanes;wire[7:0] first,last;wire parityA,parityB;integer k;
Top #(.A(A),.B(B)) dut(.lanes(lanes),.first(first),.last(last),.parityA(parityA),.parityB(parityB));
initial begin for(k=0;k<32;k=k+1) begin lanes={$random,$random};#1;
if(first!==lanes[A*8+:8] || last!==lanes[B*8+:8] || parityA!==( ^lanes) || parityB!==( ^lanes)) $fatal(1,"selector forwarding");
end done=1;end endmodule
module tb;wire a,b,c;profile defaults(a);profile #(.A(7),.B(0)) reverse(b);profile #(.A(3),.B(5)) middle(c);
initial begin wait(a && b && c);$finish;end endmodule
""".getBytes(UTF_8))
    run(dir,Seq("iverilog","-g2001","-s","tb","-o","sim","Top.v","Middle.v","Leaf.v","tb.v"))
    run(dir,Seq("timeout","15","vvp","sim"))
    run(dir,Seq("verilator","--lint-only","-Wall","--language","1364-2001","-DSYNTHESIS","--top-module","Top","Top.v","Middle.v","Leaf.v"))
    run(dir,Seq("yosys","-Q","-p","read_verilog Top.v Middle.v Leaf.v; synth -top Top; check -assert"))
  }
}
