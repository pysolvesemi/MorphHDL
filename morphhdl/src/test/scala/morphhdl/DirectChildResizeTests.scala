package morphhdl

import java.nio.file.{Files, Path}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class DirectChildResizeTests extends AnyFunSuite {
  private def element(kind: String, width: ElabInt): BitVector = kind match {
    case "bits" => Bits(width bits)
    case "uint" => UInt(width bits)
    case "sint" => SInt(width bits)
  }
  private class Source(WIDTH: Param[Int], kind: String) extends Component {
    require(WIDTH >= 1 && WIDTH <= 64)
    val dataIn = in(element(kind, WIDTH))
    val dataOut = out(element(kind, WIDTH))
    val local = element(kind, WIDTH)
    local.assignFromBits(dataIn.asBits)
    dataOut.assignFromBits(local.asBits)
  }
  private class Sink(width: Int, kind: String) extends Component {
    val dataIn = in(element(kind, ElabInt.literal(width)))
    val dataOut = out Bits(width bits)
    dataOut := dataIn.asBits
  }
  private class SymbolicSink(WIDTH: Param[Int], kind: String) extends Component {
    require(WIDTH >= 1 && WIDTH <= 129)
    val dataIn = in(element(kind, WIDTH))
    val dataOut = out Bits(WIDTH bits)
    dataOut := dataIn.asBits
  }
  private class Top(kind: String, size: Int, initial: Int, symbolic: Boolean, offset: Int) extends Component {
    setDefinitionName("DirectResize")
    val WIDTH: Param[Int] = Param(initial)
    require(WIDTH >= 1 && WIDTH <= 64)
    val dataIn = in Bits(WIDTH bits)
    @dontName val target: ElabInt = if(symbolic) WIDTH + offset else ElabInt.literal(size)
    val dataOut, fanout = out Bits(target bits)
    val source = new Source(WIDTH, kind)
    source.dataIn.assignFromBits(dataIn)
    val resized = if(symbolic) source.dataOut.resize(target) else source.dataOut.resize(size)
    if(symbolic) {
      val sink = new SymbolicSink(target, kind)
      val second = new SymbolicSink(target, kind)
      sink.dataIn.assignFromBits(resized.asBits)
      second.dataIn.assignFromBits(resized.asBits)
      dataOut := sink.dataOut; fanout := second.dataOut
    } else {
      val sink = new Sink(size, kind)
      val second = new Sink(size, kind)
      sink.dataIn.assignFromBits(resized.asBits)
      second.dataIn.assignFromBits(resized.asBits)
      dataOut := sink.dataOut; fanout := second.dataOut
    }
  }
  private def emit(body: => Component): (Path, String) = {
    val dir=Files.createTempDirectory("direct-child-resize-")
    MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false))(body)
    dir -> new String(Files.readAllBytes(dir.resolve("DirectResize.v")), "UTF-8")
  }
  private def run(dir: Path, command: String*): Unit = {
    val (code, log)=Increment66ToolEvidence.run(dir,command)
    assert(code==0,command.mkString(" ")+"\n"+log)
  }
  private class DerivedSource(WIDTH: Param[Int], kind: String) extends Component {
    require(WIDTH >= 1 && WIDTH <= 64)
    val dataIn = in Bits(WIDTH bits)
    val local = element(kind, WIDTH * 2 + 1)
    val dataOut = out(element(kind, WIDTH * 2 + 1))
    local.assignFromBits(dataIn ## dataIn ## True.asBits)
    dataOut.assignFromBits(local.asBits)
  }
  private class LiteralSink(WIDTH: Param[Int]) extends Component {
    require(WIDTH >= 1 && WIDTH <= 1024)
    val dataIn = in Bits(WIDTH bits)
    val dataOut = out Bits(WIDTH bits)
    dataOut := dataIn
  }

  private class BooleanSink extends Component {
    val predicate, choice = in Bool()
    val dataOut, selected = out Bool()
    dataOut := predicate
    selected := choice
  }

  for(kind <- Seq("bits","uint","sint"))
  test(s"direct sibling predicates retain contextual comparison constants and Boolean muxes kind=$kind") {
    def design = new Component {
      setDefinitionName("DirectResize")
      val WIDTH: Param[Int] = Param(8); require(WIDTH >= 1 && WIDTH <= 64)
      val dataIn = in Bits(WIDTH bits)
      val enable, hold, choose = in Bool()
      val dataOut, selected = out Bool()
      val reduced = out Bits(3 bits)
      val source = new Source(WIDTH,kind)
      source.dataIn.assignFromBits(dataIn)
      val control = new Component {
        val enable = in Bool()
        val running = out Bool()
        running := enable
      }
      control.enable := enable
      val sink = new BooleanSink
      val equal = kind match {
        case "bits" => source.dataOut.asBits === B(1)
        case "uint" => source.dataOut.asBits.asUInt === 1
        case "sint" => source.dataOut.asBits.asSInt === -1
      }
      sink.predicate := control.running && equal && !hold
      sink.choice := Mux(choose,control.running,hold)
      val reductions = new Component {
        val any, all, parity = in Bool()
        val result = out Bits(3 bits)
        result := any.asBits ## all.asBits ## parity.asBits
      }
      reductions.any := source.dataOut.asBits.orR
      reductions.all := source.dataOut.asBits.andR
      reductions.parity := source.dataOut.asBits.xorR
      dataOut := sink.dataOut
      selected := sink.selected
      reduced := reductions.result
    }
    val (dir,rtl)=emit(design)
    assert(rtl==emit(design)._2)
    Files.write(dir.resolve("tb.v"),s"""module Check #(parameter W=8)(
      |input [63:0] value,input enable,hold,choose);
      |wire result,selected;wire [2:0] reduced;
      |wire expected=enable && (value[W-1:0]==${if(kind=="sint") "{W{1'b1}}" else "1"}) && !hold;
      |wire expectedChoice=choose ? enable : hold;
      |DirectResize #(.WIDTH(W)) dut(.dataIn(value[W-1:0]),.enable(enable),.hold(hold),.choose(choose),
      |.dataOut(result),.selected(selected),.reduced(reduced));
      |always @(value or enable or hold or choose) begin #1;
      |if(result!==expected || selected!==expectedChoice ||
      |reduced!=={(|value[W-1:0]),(&value[W-1:0]),(^value[W-1:0])}) $$fatal(1,"PREDICATE W=%0d",W);end
      |endmodule
      |module tb;reg [63:0] value;reg enable,hold,choose;integer i;
      |${Seq(1,5,8,64).map(w=>s"Check #($w) c$w(value,enable,hold,choose);").mkString("\n")}
      |initial begin value=1;enable=1;hold=0;choose=1;#2;value=~64'd0;#2;
      |for(i=0;i<100;i=i+1)begin value={$$random,$$random};{enable,hold,choose}=i;#2;end
      |value='bx;enable=1;hold=0;choose=1'bx;#2;
      |value='bz;enable=1'bz;hold=1'bx;choose=1'bz;#2;$$finish;end endmodule
      |""".stripMargin.getBytes("UTF-8"))
    run(dir,"iverilog","-g2001","-s","tb","-o","sim","DirectResize.v","tb.v")
    run(dir,"vvp","sim")
    run(dir,"verilator","--lint-only","--language","1364-2001","-DSYNTHESIS","-Wno-fatal","--top-module","DirectResize","DirectResize.v")
    run(dir,"yosys","-Q","-p","read_verilog -DSYNTHESIS DirectResize.v; hierarchy -check -top DirectResize; proc; opt; check -assert; select -assert-none t:$dlatch")
  }

  private class CompoundSource(WIDTH: Param[Int], COUNT: Param[Int]) extends Component {
    require(WIDTH >= 8 && WIDTH <= 16)
    require(COUNT >= 1 && COUNT <= 3)
    val dataIn = in Bits((WIDTH * COUNT) bits)
    val dataOut = out Bits((WIDTH * COUNT) bits)
    dataOut := dataIn
  }

  for(mode <- Seq("width","private"))
  test(s"direct contextual predicates reject unproved comparison geometry or ownership mode=$mode") {
    val failure=intercept[Exception] { emit(new Component {
      setDefinitionName("DirectResize")
      val WIDTH: Param[Int] = Param(8); require(WIDTH >= 1 && WIDTH <= 64)
      val dataIn = in Bits(WIDTH bits)
      val dataOut = out Bool()
      val source = new Source(WIDTH,"uint")
      source.dataIn.assignFromBits(dataIn)
      val sink = new BooleanSink
      sink.choice := False
      if(mode=="width") sink.predicate := source.dataOut.asBits.asUInt === 2
      else sink.predicate := source.local.asBits.asUInt === 1
      dataOut := sink.dataOut
    }) }
    val code=if(mode=="width") "HIERARCHY-PORT-CONNECTION-UNSUPPORTED" else "HIERARCHY VIOLATION (read access)"
    assert(failure.toString.contains(code),failure.toString)
  }

  for(literal <- Seq(false, true))
  test(s"compound child output slices predicates and sibling uses retain whole geometry literal=$literal") {
    def design = new Component {
      setDefinitionName("DirectResize")
      val WIDTH: Param[Int] = Param(8); require(WIDTH >= 8 && WIDTH <= 16)
      val COUNT: Param[Int] = Param(2); require(COUNT >= 1 && COUNT <= 3)
      @dontName val w: ElabInt = if(literal) ElabInt.literal(8) else WIDTH
      @dontName val n: ElabInt = if(literal) ElabInt.literal(2) else COUNT
      val dataIn = in Bits((w * n) bits)
      val dataOut = out Bits(8 bits)
      val flag, parity, high = out Bool()
      val source = new CompoundSource(w,n)
      source.dataIn := dataIn
      val sink = new Sink(8,"bits")
      sink.dataIn.assignFromBits(source.dataOut(7 downto 0))
      dataOut := sink.dataOut
      flag := False
      when(source.dataOut(7)) { flag := True }
      parity := source.dataOut.xorR
      high := source.dataOut.msb
    }
    val (dir,rtl)=emit(design)
    assert(rtl==emit(design)._2)
    val profiles=if(literal) Seq(8->2) else for(w<-Seq(8,11,16);n<-Seq(1,2,3))yield w->n
    Files.write(dir.resolve("tb.v"),s"""module Check #(parameter W=8,N=2)(input [47:0] value);
      |wire [7:0] result;wire flag,parity,high;reg expectedFlag;
      |DirectResize ${if(literal) "" else "#(.WIDTH(W),.COUNT(N))"}
      |dut(.dataIn(value[W*N-1:0]),.dataOut(result),.flag(flag),.parity(parity),.high(high));
      |always @(value) begin #1;expectedFlag=0;if(value[7])expectedFlag=1;
      |if(result!==value[7:0] || flag!==expectedFlag || parity!==(^(value[W*N-1:0])) || high!==value[W*N-1])
      |$$fatal(1,"COMPOUND_READ W=%0d N=%0d",W,N);end
      |endmodule
      |module tb;reg [47:0] value;integer i;
      |${profiles.map { case(w,n)=>s"Check #($w,$n) c${w}_$n(value);" }.mkString("\n")}
      |initial begin value=0;#2;value=~48'd0;#2;
      |for(i=0;i<100;i=i+1)begin value={$$random,$$random};#2;end
      |value='bx;#2;value='bz;#2;$$finish;end endmodule
      |""".stripMargin.getBytes("UTF-8"))
    run(dir,"iverilog","-g2001","-s","tb","-o","sim","DirectResize.v","tb.v")
    run(dir,"vvp","sim")
    run(dir,"verilator","--lint-only","--language","1364-2001","-DSYNTHESIS","-Wno-fatal","--top-module","DirectResize","DirectResize.v")
    run(dir,"yosys","-Q","-p","read_verilog -DSYNTHESIS DirectResize.v; hierarchy -check -top DirectResize; proc; opt; check -assert; select -assert-none t:$dlatch")
  }

  for(width <- Seq(3,544))
  test(s"a concrete sibling output proves an equal literal formal actual width=$width") {
    val (dir, _) = emit(new Component {
      setDefinitionName("DirectResize")
      val dataIn = in Bits(width bits)
      val dataOut = out Bits(width bits)
      val source = new Component {
        val dataIn = in Bits(width bits)
        val dataOut = out Bits(width bits)
        dataOut := dataIn
      }
      val sink = new LiteralSink(Param(width))
      source.dataIn := dataIn
      sink.dataIn := source.dataOut
      dataOut := sink.dataOut
    })
    Files.write(dir.resolve("tb.v"), s"""module tb;
      |reg [$width-1:0] value;wire [$width-1:0] result;integer i;
      |DirectResize dut(.dataIn(value),.dataOut(result));
      |task check;begin #1;if(result!==value) $$fatal(1,"LITERAL_CHILD_OUTPUT");end endtask
      |initial begin value=0;check;value=~value;check;
      |for(i=0;i<100;i=i+1)begin value={${Seq.fill(17)("$random").mkString(",")}};check;end
      |value='bx;check;value='bz;check;$$finish;end endmodule
      |""".stripMargin.getBytes("UTF-8"))
    run(dir,"iverilog","-g2001","-s","tb","-o","sim","DirectResize.v","tb.v")
    run(dir,"vvp","sim")
  }

  test("compound child output reads reject a fixed index outside the complete width domain") {
    val failure=intercept[Exception] { emit(new Component {
      setDefinitionName("DirectResize")
      val WIDTH: Param[Int] = Param(16); require(WIDTH >= 8 && WIDTH <= 16)
      val COUNT: Param[Int] = Param(2); require(COUNT >= 1 && COUNT <= 3)
      val dataIn = in Bits((WIDTH * COUNT) bits)
      val dataOut = out Bool()
      val source = new CompoundSource(WIDTH,COUNT)
      source.dataIn := dataIn
      dataOut := source.dataOut(31)
    }) }
    assert(failure.toString.contains("SLICE-DOMAIN-UNSUPPORTED"),failure.toString)
  }

  test("a concrete sibling output cannot prove a varying formal actual from an equal witness") {
    val failure = intercept[Exception] { emit(new Component {
      setDefinitionName("DirectResize")
      val WIDTH: Param[Int] = Param(3); require(WIDTH >= 1 && WIDTH <= 8)
      val dataIn = in Bits(3 bits)
      val dataOut = out Bits(WIDTH bits)
      val source = new Component {
        val dataIn = in Bits(3 bits)
        val dataOut = out Bits(3 bits)
        dataOut := dataIn
      }
      val sink = new LiteralSink(WIDTH)
      source.dataIn := dataIn
      sink.dataIn := source.dataOut
      dataOut := sink.dataOut
    }) }
    assert(failure.toString.contains("HIERARCHY-PORT-WIDTH-MISMATCH"), failure.toString)
  }

  for(kind <- Seq("bits","uint","sint"); literal <- Seq(false,true))
  test(s"direct derived child port retains exact parent actuals kind=$kind literal=$literal") {
    def design = new Component {
      setDefinitionName("DirectResize")
      val WIDTH: Param[Int] = Param(8)
      require(WIDTH >= 1 && WIDTH <= 64)
      @dontName val actual: ElabInt = if(literal) ElabInt.literal(8) else WIDTH
      val dataIn = in Bits(actual bits)
      val dataOut = out Bits((actual * 2 + 1) bits)
      val source = new DerivedSource(actual, kind)
      val sink = new SymbolicSink(actual * 2 + 1, kind)
      source.dataIn := dataIn
      sink.dataIn.assignFromBits(source.dataOut.asBits)
      dataOut := sink.dataOut
    }
    val (dir, rtl) = emit(design)
    assert(rtl == emit(design)._2)
    val instances = (if(literal) Seq(8) else Seq(1,5,8,17,64))
      .map(w => s"Check #(.W($w)) c$w(value);").mkString("\n")
    Files.write(dir.resolve("tb.v"), s"""module Check #(parameter W=8)(input [63:0] value);
      |wire [2*W:0] result;
      |DirectResize ${if(literal) "" else "#(.WIDTH(W))"} dut(.dataIn(value[W-1:0]),.dataOut(result));
      |always @(value) begin #1;
      |if(result!=={value[W-1:0],value[W-1:0],1'b1}) $$fatal(1,"DERIVED_CHILD_OUTPUT");end
      |endmodule
      |module tb;reg [63:0] value;integer i;
      |$instances
      |initial begin value=0;#2;value=~64'd0;#2;
      |for(i=0;i<100;i=i+1)begin value={$$random,$$random};#2;end
      |value='bx;#2;value='bz;#2;$$finish;end endmodule
      |""".stripMargin.getBytes("UTF-8"))
    run(dir,"iverilog","-g2001","-s","tb","-o","sim","DirectResize.v","tb.v")
    run(dir,"vvp","sim")
    run(dir,"verilator","--lint-only","--language","1364-2001","-DSYNTHESIS","-Wno-fatal","--top-module","DirectResize","DirectResize.v")
    run(dir,"yosys","-Q","-p","read_verilog -DSYNTHESIS DirectResize.v; hierarchy -check -top DirectResize; proc; opt; check -assert; select -assert-none t:$dlatch")
  }

  for(mode <- Seq("unrelated","private"))
  test(s"derived child outputs retain $mode binding rejection") {
    val failure = intercept[Exception] { emit(new Component {
      setDefinitionName("DirectResize")
      val LEFT: Param[Int] = Param(8); require(LEFT >= 1 && LEFT <= 64)
      val RIGHT: Param[Int] = Param(8); require(RIGHT >= 1 && RIGHT <= 64)
      val dataIn = in Bits(LEFT bits)
      val dataOut = out Bits((RIGHT * 2 + 1) bits)
      val source = new DerivedSource(LEFT,"bits")
      val sink = new SymbolicSink(RIGHT * 2 + 1,"bits")
      source.dataIn := dataIn
      sink.dataIn.assignFromBits((if(mode=="private") source.local else source.dataOut).asBits)
      dataOut := sink.dataOut
    }) }
    assert(failure.toString.contains(if(mode=="private") "HIERARCHY VIOLATION (read access)" else "INDEPENDENT-ROOTS") ||
      (mode=="unrelated" && failure.toString.contains("FORMAL-ACTUAL-CONNECTION-CONFLICT")), failure.toString)
  }

  for(kind <- Seq("bits","uint","sint"); size <- Seq(1,8,64); initial <- Seq(1,8))
  test(s"direct $kind sibling resize size=$size default=$initial preserves all overrides and shared fanout") {
    verify(kind,size,initial,false)
  }
  for(kind <- Seq("bits","uint","sint"); offset <- Seq(0,1))
  test(s"direct $kind sibling resize retains a symbolic target width offset=$offset") {
    verify(kind,0,8,true,offset)
  }
  private def verify(kind:String,size:Int,initial:Int,symbolic:Boolean,offset:Int=1):Unit = {
    val (dir,rtl)=emit(new Top(kind,size,initial,symbolic,offset))
    assert(rtl==emit(new Top(kind,size,initial,symbolic,offset))._2)
    val instances=Seq(1,5,8,17,64).map { w =>
      s"Check #(.W($w),.T(${if(symbolic)w+offset else size})) c$w(inputData);"
    }.mkString("\n")
    Files.write(dir.resolve("tb.v"),s"""module Check #(parameter W=8,T=8)(input [63:0] inputData);
      |wire [W-1:0] inputValue=inputData[W-1:0];
      |wire [T-1:0] outputValue,fanout;
      |wire [T-1:0] expected=${if(kind=="sint") "$signed(inputValue)" else "inputValue"};
      |DirectResize #(.WIDTH(W)) dut(.dataIn(inputValue),.dataOut(outputValue),.fanout(fanout));
      |always @(inputData) begin #1;
      |if(outputValue !== expected || fanout !== expected) $$fatal(1,"resize W=%0d T=%0d got=%h expected=%h",W,T,outputValue,expected);
      |end endmodule
      |module tb;reg [63:0] inputData;integer i;
      |$instances
      |initial begin
      |inputData=0;#2;inputData=~64'd0;#2;
      |for(i=0;i<100;i=i+1)begin inputData={$$random,$$random};#2;end
      |inputData='bx;#2;inputData='bz;#2;$$display("DIRECT_RESIZE_PASS");$$finish;
      |end endmodule
      |""".stripMargin.getBytes("UTF-8"))
    run(dir,"iverilog","-g2001","-s","tb","-o","sim","DirectResize.v","tb.v")
    run(dir,"vvp","sim")
    run(dir,"verilator","--lint-only","--language","1364-2001","-DSYNTHESIS","-Wno-fatal","--top-module","DirectResize","DirectResize.v")
    run(dir,"yosys","-Q","-p","read_verilog -DSYNTHESIS DirectResize.v; hierarchy -check -top DirectResize; proc; opt; check -assert; select -assert-none t:$dlatch")
  }

  test("a concrete child output retains a typed resize before a fixed parent consumer") {
    val (dir, _) = emit(new Component {
      setDefinitionName("DirectResize")
      val WIDTH: Param[Int] = Param(3)
      require(WIDTH >= 1 && WIDTH <= 3)
      val dataIn = in UInt(3 bits)
      val dataOut = out UInt(3 bits)
      val source = new Component {
        val dataIn = in UInt(3 bits)
        val dataOut = out UInt(3 bits)
        dataOut := dataIn
      }
      source.dataIn := dataIn
      dataOut := source.dataOut.resize(WIDTH)
    })
    Files.write(dir.resolve("tb.v"), """module Check #(parameter W=3)(input [2:0] value);
      |wire [2:0] result;
      |wire [2:0] expected={{(3-W){1'b0}},value[W-1:0]};
      |DirectResize #(.WIDTH(W)) dut(.dataIn(value),.dataOut(result));
      |always @(value) begin #1;if(result!==expected)$fatal(1,"CONCRETE_RESIZE");end
      |endmodule
      |module tb;reg [2:0] value;integer i;
      |Check #(1) c1(value);Check #(2) c2(value);Check #(3) c3(value);
      |initial begin for(i=0;i<8;i=i+1)begin value=i;#2;end
      |value='bx;#2;value='bz;#2;$finish;end endmodule
      |""".stripMargin.getBytes("UTF-8"))
    run(dir,"iverilog","-g2001","-s","tb","-o","sim","DirectResize.v","tb.v")
    run(dir,"vvp","sim")
  }

  test("direct resized connections reject nonlocal reads and mismatched symbolic target widths") {
    for(mode <- Seq("foreign","width","drivers")) {
      val failure=intercept[Exception] { emit(new Component {
        setDefinitionName("DirectResize")
        val WIDTH:Param[Int]=Param(8);require(WIDTH>=1 && WIDTH<=64)
        val dataIn=in Bits(WIDTH bits);val dataOut=out Bits(WIDTH bits)
        val source=new Source(WIDTH,"bits")
        source.dataIn.assignFromBits(dataIn)
        val sink=new SymbolicSink(WIDTH,"bits")
        mode match {
          case "foreign" => sink.dataIn.assignFromBits(source.local.resize(8).asBits)
          case "width" => sink.dataIn.assignFromBits(source.dataOut.resize(WIDTH+1).asBits)
          case "drivers" =>
            sink.dataIn.assignFromBits(source.dataOut.resize(WIDTH).dontSimplifyIt().asBits)
            sink.dataIn.assignFromBits(~dataIn)
        }
        dataOut:=sink.dataOut
      }) }
      withClue(s"$mode: ") { assert(failure.toString.toUpperCase.contains("HIERARCHY") ||
        failure.toString.toUpperCase.contains("WIDTH") ||
        failure.toString.toUpperCase.contains("OVERLAP"),failure.toString) }
    }
  }
}
