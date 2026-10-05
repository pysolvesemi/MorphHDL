package morphhdl

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.collection.JavaConverters._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import morphhdl.frontend.{HdlInt, formalParam}

class FiniteBitsAndRamSharingTests extends AnyFunSuite {
  private def run(dir: Path, args: String*): Unit = {
    val (code, log) = Increment66ToolEvidence.run(dir, args)
    assert(code == 0, s"${args.mkString(" ")}\n$log")
  }
  private def emit(dir: Path)(body: => Component): Unit =
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString,
      headerWithDate=false, oneFilePerComponent=true), preserveConstantVecs=true,
      preserveConstantLoops=true, vecLayout=MorphAggregateOptions.UnpackedArray))(body)
  private def text(dir: Path, name: String): String = new String(Files.readAllBytes(dir.resolve(name)), UTF_8)

  for(width <- Seq(1,3,4); view <- Seq(false,true); symbolic <- Seq(false,true) if !view || !symbolic) {
    test(s"finite bit register copy width=$width view=$view symbolic=$symbolic") {
      val dir=Files.createTempDirectory("finite-bits-literal-")
      emit(dir)(new Component {
        setDefinitionName("Top")
        @dontName private val count=if(symbolic) HdlInt.param("WIDTH",width,1,4).asElabInt else ElabInt.literal(width)
        val source=in Bits(count bits)
        val result=out Bits(count bits)
        val enable=in Bool()
        val flags=Vec.fill(count)(Reg(Bool()) init False)
        val lanes=if(view) source.asBools else null
        ElabFiniteRange.foreach(count,"copy_bits") { i =>
          when(enable) { i(flags):= (if(view) i(lanes) else i(source)) }
        }
        result:=flags.asBits
      })
      for(w <- (if(symbolic) Seq(1,3,4) else Seq(width))) {
        Files.write(dir.resolve("tb.v"),s"""module tb;
reg clk=0,reset=0,enable=0;reg[${w-1}:0] source=0,expected=0;wire[${w-1}:0] result;integer k;
Top ${if(symbolic)s"#(.WIDTH($w))" else ""} dut(.clk(clk),.reset(reset),.enable(enable),.source(source),.result(result));
initial begin reset=1;#2;if(result!==0) $$fatal(1,"reset");reset=0;
for(k=0;k<64;k=k+1) begin source=$$random;enable=(k%3!=0);#1;clk=1;
if(enable) expected=source;#1;if(result!==expected) $$fatal(1,"copy/hold");clk=0;#1;end $$finish;end endmodule
""".getBytes(UTF_8))
        run(dir,"iverilog","-g2001","-s","tb","-o","sim","Top.v","tb.v");run(dir,"vvp","sim")
        val overrides=if(symbolic) Seq(s"-GWIDTH=$w") else Seq.empty
        run(dir,(Seq("verilator","--lint-only","-Wall","--language","1364-2001","-DSYNTHESIS","--top-module","Top")++overrides++Seq("Top.v")):_*)
        run(dir,"yosys","-Q","-p",s"read_verilog Top.v; ${if(symbolic)s"chparam -set WIDTH $w Top;" else ""} synth -top Top; check -assert")
      }
    }
  }
  for(mode <- Seq("count","owner")) {
    test(s"literal finite Bits rejects $mode mismatch") {
      val error=intercept[Exception] {
        emit(Files.createTempDirectory("finite-bits-reject-"))(new Component {
          val source=in Bits(ElabInt.literal(3) bits)
          val selected=if(mode=="owner") {
            val child=new Component { val port=in Bits(ElabInt.literal(3) bits) }
            child.port:=source; child.port
          } else source
          val count=ElabInt.literal(4)
          val result=out(Vec(Bool(),count))
          ElabFiniteRange.foreach(count,"copy") { i => i(result):=i(selected) }
        })
      }
      assert(error.toString.contains(if(mode=="owner") "FINITE-INDEX-COMPONENT-MISMATCH" else "BITS-WIDTH-MISMATCH"),error.toString)
    }
  }

  private class Ram(w: ElabInt, a: ElabInt, variant: Int = 0) extends Component {
    setDefinitionName("SharedRam",noMerge=false)
    ClockDomain.current.renamePulledWires(clock="clk")
    @dontName private val width=formalParam(w,"DATA_BITS",1,64)
    @dontName private val address=formalParam(a,"ADDRESS_BITS",1,6)
    val din=in Bits(width bits);val dout=out Bits(width bits)
    val wr,rd=in Bool();val wa,ra=in UInt(address bits)
    val memory=Mem(Bits(width bits),address.pow2)
    if(variant==2) memory.addAttribute("ram_style","distributed")
    if(variant==4) memory.initBigInt(Vector.fill(memory.wordCount)(BigInt(0)))
    val writeData=Bits(width bits).setName("write_data").dontSimplifyIt()
    writeData:= (if(variant==1) ~din else din)
    memory.write(wa,writeData,wr)
    dout:=memory.readSync(ra,if(variant==3) rd && wr else rd)
  }
  private class RamTop(mode: String, reverse: Boolean, independent: Boolean, variant: Int = 0) extends Component {
    setDefinitionName("Top")
    @dontName private val line=HdlInt.param("LINE",4,2,5).asElabInt
    val clock_a=in Bool()
    val clock_b=if(independent) in Bool() else null
    val din=in Bits((if(mode=="wide")64 else if(mode=="same")10 else 20) bits)
    val address=in UInt((if(mode=="symbolic")line+1 else ElabInt.literal(4)) bits)
    val wr,rd=in Bool()
    val first,second=out Bits(64 bits)
    def instance(secondInstance: Boolean): Bits = {
      val width=if(mode=="wide") {if(secondInstance)64 else 1} else if(secondInstance && mode!="same")20 else 10
      val depth=if(mode=="symbolic") line+(if(secondInstance) -1 else 1) else ElabInt.literal(4)
      val child=ClockDomain(if(secondInstance && independent)clock_b else clock_a).on {
        new Ram(ElabInt.literal(width),depth,if(secondInstance)variant else 0)
      }
      child.setName(if(secondInstance)"b" else "a")
      val pin=Bits(width bits).dontSimplifyIt();val pout=Bits(width bits).dontSimplifyIt()
      val addr=UInt(depth bits).dontSimplifyIt()
      pin:=din.resize(width);addr:=address.resize(depth)
      child.din:=pin;child.wa:=addr;child.ra:=addr;child.wr:=wr;child.rd:=rd;pout:=child.dout
      pout.resize(64)
    }
    if(reverse) {second:=instance(true);first:=instance(false)}
    else {first:=instance(false);second:=instance(true)}
  }
  for(mode <- Seq("same","width","symbolic","wide"); reverse <- Seq(false,true); independent <- Seq(false,true)) {
    test(s"RAM native sharing mode=$mode reverse=$reverse independent=$independent") {
      val dir=Files.createTempDirectory("ram-formal-sharing-");val repeat=Files.createTempDirectory("ram-formal-repeat-")
      emit(dir)(new RamTop(mode,reverse,independent));emit(repeat)(new RamTop(mode,reverse,independent))
      val files=Files.list(dir);val names=try files.iterator.asScala.map(_.getFileName.toString).filter(_.endsWith(".v")).toVector finally files.close()
      assert(names.filter(_.startsWith("SharedRam"))==Vector("SharedRam.v"),names)
      names.foreach(n=>assert(text(dir,n)==text(repeat,n),n))
      for(line <- (if(mode=="symbolic") Seq(3,4) else Seq(4))) {
        val wa=if(mode=="wide")1 else 10;val wb=if(mode=="wide")64 else if(mode=="same")10 else 20
        val da=if(mode=="symbolic")1<<(line+1) else 16;val db=if(mode=="symbolic")1<<(line-1) else 16
        Files.write(dir.resolve("tb.v"),s"""module tb;
reg clock_a=0,clock_b=0,wr=0,rd=0;reg[63:0] din=0;reg[5:0] address=0;
wire[63:0] first,second;reg[63:0] a[0:${da-1}],b[0:${db-1}];reg[63:0] hold_a,hold_b;integer k;
Top ${if(mode=="symbolic")s"#(.LINE($line))" else ""} dut(.clock_a(clock_a),${if(independent)".clock_b(clock_b)," else ""}.wr(wr),.rd(rd),.din(din),.address(address),.first(first),.second(second));
task tick;begin #2;clock_a=1;#1;clock_a=0;${if(independent)"#3;clock_b=1;#1;clock_b=0;" else ""}#1;end endtask
initial begin
wr=1;for(k=0;k<64;k=k+1)begin address=k;din={$$random,$$random};a[k%$da]=din & 64'h${((BigInt(1)<<wa)-1).toString(16)};b[k%$db]=din & 64'h${((BigInt(1)<<wb)-1).toString(16)};tick;end
wr=0;rd=1;for(k=0;k<64;k=k+1)begin address=k;tick;if(first!==a[k%$da] || second!==b[k%$db]) $$fatal(1,"memory geometry/data");end
hold_a=first;hold_b=second;rd=0;address=3;din=0;tick;if(first!==hold_a || second!==hold_b) $$fatal(1,"read enable hold");
rd=1;tick;if(first!==a[3%$da] || second!==b[3%$db]) $$fatal(1,"write enable hold");$$finish;end endmodule
""".getBytes(UTF_8))
        run(dir,"iverilog","-g2001","-s","tb","-o","sim","Top.v","SharedRam.v","tb.v");run(dir,"vvp","sim")
        run(dir,(Seq("verilator","--lint-only","-Wall","--language","1364-2001","-DSYNTHESIS","--top-module","Top")++(if(mode=="symbolic")Seq(s"-GLINE=$line")else Seq.empty)++Seq("Top.v","SharedRam.v")):_*)
        run(dir,"yosys","-Q","-p",s"read_verilog Top.v SharedRam.v; ${if(mode=="symbolic")s"chparam -set LINE $line Top;"else ""} synth -top Top; check -assert")
      }
    }
  }
  for(variant <- Seq(1,2,3)) {
    test(s"RAM sharing preserves semantic difference $variant") {
      val dir=Files.createTempDirectory("ram-sharing-negative-")
      emit(dir)(new RamTop("width",false,true,variant))
      val files=Files.list(dir);val count=try files.iterator.asScala.count(_.getFileName.toString.matches("SharedRam.*\\.v")) finally files.close()
      assert(count==2)
    }
  }
  test("RAM sharing does not admit unsupported initialized memory") {
    val error=intercept[Exception] {
      emit(Files.createTempDirectory("ram-sharing-init-negative-"))(new RamTop("width",false,true,4))
    }
    assert(error.toString.contains("MEMORY-INITIALIZATION-UNSUPPORTED"),error.toString)
  }

}
