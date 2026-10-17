package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.internals.{BoolLiteral, PhaseContext, PhaseMisc, PhaseVerilog}

@_root_.spinal.core.rtlRecursive
final class LightweightImplicitClockTree(actual: ElabInt, foreignDomain: Boolean = false, guardedRegister: Boolean = false) extends Component {
  setDefinitionName("ImplicitClockTree")
  noIoPrefix()
  val count: ElabInt = RtlParam.int("COUNT", actual, 1, 8)
  val io = new Bundle {
    val terms = in(Vec(UInt(8 bits), count))
    val result = out UInt(8 bits)
  }
  val domain = if(foreignDomain) ClockDomain.current.copy() else ClockDomain.current
  val logic = new ClockingArea(domain) {
  if(count == 1) io.result := io.terms(0)
  else {
    val nextCount = (count+1)/2
    val stages = Vec(UInt(8 bits),nextCount)
    for(i<-0 until nextCount) {
      if(guardedRegister) {
        val value = Reg(UInt(8 bits)) init(0)
        if(2*i+1<count) value := io.terms(2*i)+io.terms(2*i+1)
        else value := io.terms(2*i)
        stages(i) := value
      } else {
        val combined = UInt(8 bits)
        if(2*i+1<count) combined := io.terms(2*i)+io.terms(2*i+1)
        else combined := io.terms(2*i)
        stages(i) := RegNext(combined) init(0)
      }
    }
    val nextLevel = new LightweightImplicitClockTree(nextCount, foreignDomain, guardedRegister)
    nextLevel.io.terms := stages
    io.result := nextLevel.io.result
  }
  }
}
final class LightweightClockGolden(count: Int) extends Component {
  setDefinitionName(s"Golden$count")
  val terms = in Bits(count*8 bits)
  val result = out UInt(8 bits)
  var words = (0 until count).map(i => terms(i*8, 8 bits).asUInt).toVector
  while(words.size>1) words = words.grouped(2).map { pair =>
    RegNext(if(pair.size==2) pair(0)+pair(1) else pair(0)) init(0)
  }.toVector
  result := words.head
}
class LightweightImplicitClockTests extends AnyFunSuite {
  for(default<-Seq(1,5); sync<-Seq(false,true); low<-Seq(false,true); guarded<-Seq(false,true)) {
    test(s"ordinary recursive registers forward clock reset soft-reset and enable default=$default sync=$sync low=$low guarded=$guarded") {
      val dir=Files.createTempDirectory("lightweight-implicit-clock-")
      val config=ClockDomainConfig(resetKind=if(sync) SYNC else ASYNC,
        resetActiveLevel=if(low) LOW else HIGH)
      def domain() = {
        val cd=ClockDomain.external("",config=config,withSoftReset=true,withClockEnable=true)
        cd.clock.setName("clk");cd.reset.setName("rst");cd.softReset.setName("sr");cd.clockEnable.setName("ce")
        cd
      }
      def generate(target: java.nio.file.Path): Unit = {
        MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=target.toString,headerWithDate=false),
          preserveConstantVecs=true,preserveConstantLoops=true,vecLayout=MorphAggregateOptions.UnpackedArray)) {
          domain().on(new LightweightImplicitClockTree(ElabInt.literal(default), false, guarded))
        }
      }
      generate(dir)
      val again=Files.createTempDirectory("lightweight-implicit-clock-repeat-");generate(again)
      val rtl=new String(Files.readAllBytes(dir.resolve("ImplicitClockTree.v")),"UTF-8")
      assert(rtl==new String(Files.readAllBytes(again.resolve("ImplicitClockTree.v")),"UTF-8"))
      assert(rtl.contains(".clk") && rtl.contains(".rst") && rtl.contains(".sr") && rtl.contains(".ce"),rtl)
      val counts=Seq(1,2,3,5,7,8)
      counts.foreach { n => SpinalConfig(targetDirectory=dir.toString,headerWithDate=false)
        .generateVerilog(domain().on(new LightweightClockGolden(n))) }
      val profiles=counts.map { n =>
        val clocks=if(n==1) "" else ",.clk(clk),.rst(rst),.sr(sr),.ce(ce)"
        s"""wire [7:0] actual$n,expected$n;
ImplicitClockTree #(.COUNT($n)) dut$n(.terms(data[${n*8-1}:0]),.result(actual$n),.clk(clk),.rst(rst),.sr(sr),.ce(ce));
Golden$n golden$n(.terms(data[${n*8-1}:0]),.result(expected$n)$clocks);
always @(negedge clk) if($$time>0) begin #1; if(actual$n !== expected$n) $$fatal(1,"count=$n mismatch"); end"""
      }.mkString("\n")
      val active=if(low) 0 else 1;val inactive=1-active
      Files.write(dir.resolve("tb.v"),s"""module tb;
reg clk=0,rst=$inactive,sr=0,ce=1;reg [63:0] data=0;integer i;
always #5 clk=~clk;
$profiles
initial begin #1;rst=$active;#20;rst=$inactive;#40;
data=~64'd0;#40;data='bx;#40;data='bz;#40;
for(i=0;i<64;i=i+1) begin data=64'd1 << i;#40;end
for(i=0;i<100;i=i+1) begin data={$$random,$$random};ce=(i%3!=0);#20;end
ce=0;rst=$active;#20;ce=1;#20;rst=$inactive;#40;
sr=1;ce=0;#20;ce=1;#20;sr=0;#80;$$finish;end endmodule
""".getBytes("UTF-8"))
      val sources=Seq("ImplicitClockTree.v")++counts.map(n=>s"Golden$n.v")
      for(cmd<-Seq(Seq("iverilog","-g2001","-s","tb","-o","sim")++sources++Seq("tb.v"),Seq("vvp","sim"),
        Seq("verilator","--lint-only","--language","1364-2001","-DSYNTHESIS","-Wno-fatal","ImplicitClockTree.v"),
        Seq("yosys","-Q","-T","-p","read_verilog ImplicitClockTree.v; chparam -set COUNT 8 ImplicitClockTree; hierarchy -check -top ImplicitClockTree; synth -top ImplicitClockTree; check -assert; select -assert-none t:$dlatch"))) {
        val(status,log)=Increment66ToolEvidence.run(dir,cmd);assert(status==0,s"$dir\n$log")
      }
    }
  }
  test("implicit recursive clocks reject a non-current external domain") {
    val dir=Files.createTempDirectory("lightweight-clock-domain-negative-")
    val error=intercept[Exception] {
      MorphVerilog(SpinalConfig(targetDirectory=dir.toString)) {
        new LightweightImplicitClockTree(ElabInt.literal(1), true)
      }
    }
    assert(error.toString.contains("SPINAL-DIRECT-RECURSION-CLOCK-DOMAIN-UNSUPPORTED"),error.toString)
  }
  for(mode<-Seq("source","cache")) test(s"implicit clock identity rejects changed $mode") {
    val dir=Files.createTempDirectory("lightweight-clock-identity-negative-")
    val options=MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString),
      preserveConstantVecs=true,preserveConstantLoops=true,vecLayout=MorphAggregateOptions.UnpackedArray)
    options.phasesInserters += { phases =>
      val index=phases.indexWhere(_.isInstanceOf[PhaseVerilog]);require(index>=0)
      phases.insert(index,new PhaseMisc {
        override def impl(pc: PhaseContext): Unit = {
          val reference=pc.topLevel.children.collectFirst { case b:BlackBox=>b }.get
          val clock=reference.getAllIo.find(_.getName()=="clk").get
          if(mode=="source") clock.head.source=new BoolLiteral(true)
          else reference.pulledDataCache.clear()
        }
      })
    }
    val error=intercept[Exception] {
      MorphVerilog(options)(new LightweightImplicitClockTree(ElabInt.literal(1)))
    }
    assert(error.toString.contains("typed recursive clock lost its exact"),error.toString)
    assert(!Files.exists(dir.resolve("ImplicitClockTree.v")))
  }

}
