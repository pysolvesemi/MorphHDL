package spinal.core

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.internals._
import morphhdl.{MorphAggregateOptions, MorphVerilog, Increment66ToolEvidence}

class TypedLoopRegisterOwnershipTests extends AnyFunSuite {
  private class Top(syncLow: Boolean = false, explicitClock: Boolean = false, symbolic: Boolean = false) extends Component {
    @dontName private val width = if(symbolic) morphhdl.frontend.HdlInt.param("WIDTH",8,1,64).asElabInt else ElabInt.literal(8)
    val x = in Vec(Bits(width bits),4)
    val y = out Vec(Bits(width bits),4)
    val en = in Bool()
    val clk = if(explicitClock) in Bool() else null
    val rst = if(explicitClock) in Bool() else null
    val ce = if(explicitClock) in Bool() else null
    val domain = if(explicitClock) ClockDomain(clk,rst,clockEnable=ce,
      config=ClockDomainConfig(resetKind=if(syncLow) SYNC else ASYNC,
        resetActiveLevel=if(syncLow) LOW else HIGH)) else ClockDomain.current
    val area = new ClockingArea(domain) {
      val values = Vec.fill(4)(Reg(Bits(width bits)) init (if(symbolic) 0 else 0x5a))
      ElabFiniteRange.foreach(ElabInt.literal(4),"copy_lanes") { i =>
        when(en) { i(values) := i(x) }
      }
    }
    y := area.values
  }
  private def config(dir: java.nio.file.Path) = MorphAggregateOptions(
    SpinalConfig(targetDirectory=dir.toString,headerWithDate=false),
    preserveConstantVecs=true,preserveConstantLoops=true,vecLayout=MorphAggregateOptions.UnpackedArray)

  for(mode <- Seq("clock","reset","bridge","selection","template","competing-writer"); symbolic <- Seq(false,true)) {
    test(s"relocated register storage rejects a changed exact owner mode=$mode" + (if(symbolic) " symbolic-width" else "")) {
      val dir=Files.createTempDirectory("typed-register-owner-")
      val options=config(dir)
      options.phasesInserters += { phases =>
        val index=phases.indexWhere(_.isInstanceOf[PhaseVerilog])
        require(index>=0)
        phases.insert(index,new PhaseMisc {
          override def impl(pc: PhaseContext): Unit = {
            val component=pc.topLevel
            val loop=ParameterizedStructure.regionsOf(component).collectFirst {
              case value: ParameterizedStructure.StructuralFor => value
            }.get
            val selection=loop.body.vecIndices.find(_.registerStorage.nonEmpty).get
            val template=selection.registerStorage.head
            mode match {
              case "clock" => template.alias.clockDomain=template.clock.copy()
              case "reset" => template.initialization.source=BitsLiteral(0x5a,8)
              case "bridge" => template.lanes.head.bridge.source=template.lanes(1).value
              case "selection" => loop.body.vecIndices=loop.body.vecIndices.filterNot(_ eq selection)
              case "template" => selection.registerStorage :+= template.copy()
              case "competing-writer" => component.dslBody.append(DataAssignmentStatement(template.lanes.head.value,template.alias))
            }
          }
        })
      }
      val error=intercept[Exception] { MorphVerilog(options)(new Top(symbolic=symbolic)) }
      val messages=Iterator.iterate[Throwable](error)(_.getCause).takeWhile(_!=null)
        .flatMap(e => Option(e.getMessage)).mkString("\n")
      assert(messages.contains("SPINAL-TYPED-LOOP-REGISTER-STORAGE-MISMATCH"),messages)
      assert(!Files.exists(dir.resolve("Top.v")),"invalid storage must not be published")
    }
  }
  for(syncLow <- Seq(false,true)) {
    test(s"register loop preserves clock enable and reset configuration syncLow=$syncLow") {
      val dir=Files.createTempDirectory("typed-register-clock-enable-")
      MorphVerilog(config(dir))(new Top(syncLow,explicitClock=true))
      val active=if(syncLow) 0 else 1
      val inactive=1-active
      Files.write(dir.resolve("tb.v"),s"""module tb;
reg clk=0,rst=$inactive,ce=1,en=0;reg [31:0] x=32'h12345678;wire [31:0] y;
Top dut(.clk(clk),.rst(rst),.ce(ce),.en(en),.x(x),.y(y));
task tick; begin #2;clk=1;#1;clk=0;#1;end endtask
initial begin
rst=$active;tick;if(y!==32'h5a5a5a5a) $$fatal(1,"reset");
rst=$inactive;ce=0;en=1;tick;if(y!==32'h5a5a5a5a) $$fatal(1,"clock enable hold");
ce=1;tick;if(y!==x) $$fatal(1,"load");
x=32'habcdef01;en=0;tick;if(y!==32'h12345678) $$fatal(1,"data enable hold");
en=1;tick;if(y!==x) $$fatal(1,"reload");
rst=$active;tick;if(y!==32'h5a5a5a5a) $$fatal(1,"reset after data");
$$finish;end
endmodule
""".getBytes(UTF_8))
      Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","Top.v","tb.v"),
        Seq("timeout","10","vvp","sim"),
        Seq("verilator","--lint-only","-Wall","--language","1364-2001","--top-module","Top","Top.v"),
        Seq("yosys","-Q","-p","read_verilog Top.v; synth -top Top; check -assert")
      ).foreach { command =>
        val (code,log)=Increment66ToolEvidence.run(dir,command)
        assert(code==0,s"$command failed in $dir\n$log")
      }
    }
  }
}
