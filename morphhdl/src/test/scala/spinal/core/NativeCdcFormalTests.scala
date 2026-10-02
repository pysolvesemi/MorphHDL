package spinal.core

import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets.UTF_8
import scala.sys.process.{Process, ProcessLogger}
import morphhdl.{MorphVerilog}
import morphhdl.frontend.{HdlInt, formalParam}
import spinal.lib.fromGray
import org.scalatest.funsuite.AnyFunSuite

class NativeCdcFormalTests extends AnyFunSuite {
  class CdcSynchronizer(actualWidth: ElabInt, actualStages: ElabInt) extends Component {
    @dontName private val width: ElabInt = formalParam(actualWidth, "WIDTH", 1, 18)
    @dontName private val depth: ElabInt = formalParam(actualStages, "STAGES", 2, 4)
    require(width >= 1 && width <= 18, "CDC WIDTH must be in 1..18")
    require(depth >= 2 && depth <= 4, "CDC STAGES must be in 2..4")
    val dataIn = in Bits(width bits)
    val dataOut = out Bits(width bits)
    val stages = Seq.fill(4)(Reg(Bits(width bits)) init(0))
    for ((stage, i) <- stages.zipWithIndex) {
      stage.setName(s"stage_$i")
      stage.addAttribute("ASYNC_REG", "TRUE")
    }
    stages.head.addTag(crossClockDomain)
    stages.head := dataIn
    for (i <- 1 until 4) stages(i) := stages(i - 1)
    if (depth == 4) dataOut := stages(3)
    else if (depth == 3) dataOut := stages(2)
    else dataOut := stages(1)
  }
  class GrayDecoder(actual: ElabInt) extends Component {
    @dontName private val width: ElabInt = formalParam(actual, "WIDTH", 1, 18)
    require(width >= 1 && width <= 18, "Gray WIDTH must be in 1..18")
    val gray = in Bits(width bits)
    val binary = out UInt(width bits)
    binary := fromGray(gray)
  }
  class CdcParent(standalone: Boolean = false) extends Component {
    @dontName private val fifo: ElabInt = if (standalone) HdlInt.param("WIDTH", 5, 1, 18).asElabInt
      else HdlInt.param("FIFO_LOG_DEPTH", 3, 2, 16).asElabInt
    @dontName private val depth: ElabInt = HdlInt.param("SYNC_STAGES", 2, 2, 4).asElabInt
    if (standalone) require(fifo >= 1 && fifo <= 18, "WIDTH must be in 1..18")
    else require(fifo >= 2 && fifo <= 16, "FIFO_LOG_DEPTH must be in 2..16")
    require(depth >= 2 && depth <= 4, "SYNC_STAGES must be in 2..4")
    @dontName private val width = if (standalone) fifo else fifo + 2
    val dataIn = in Bits(width bits)
    val flagIn = in Bits(1 bits)
    val grayIn = in Bits(width bits)
    val dataOut = out Bits(width bits)
    val flagOut = out Bits(1 bits)
    val decoded = out UInt(width bits)
    val dataSync = new CdcSynchronizer(width, depth)
    val flagSync = new CdcSynchronizer(ElabInt.literal(1), depth)
    val decoder = new GrayDecoder(width)
    dataSync.dataIn := dataIn
    flagSync.dataIn := flagIn
    decoder.gray := grayIn
    dataOut := dataSync.dataOut
    flagOut := flagSync.dataOut
    decoded := decoder.binary
  }
  private def run(dir: Path, args: Seq[String]): (Int, String) =
    morphhdl.Increment66ToolEvidence.run(dir,args)
  for (split <- Seq(false, true); standalone <- Seq(false, true))
  test(s"real CDC children preserve independent WIDTH/STAGES, reset, attributes and Gray logic, split=$split standalone=$standalone") {
    val dir = Files.createTempDirectory("native-cdc-formals-")
    def emit(destination: Path): Unit = {
      MorphVerilog(SpinalConfig(targetDirectory = destination.toString,
        oneFilePerComponent = split, headerWithDate = false,
        defaultConfigForClockDomains = ClockDomainConfig(resetKind = ASYNC, resetActiveLevel = LOW)))(new CdcParent(standalone))
    }
    emit(dir)
    val repeated = Files.createTempDirectory("native-cdc-formals-repeat-")
    emit(repeated)
    val stream = Files.list(dir)
    val sources = try {
      import scala.collection.JavaConverters._
      stream.iterator().asScala.filter(_.toString.endsWith(".v")).map(_.toString).toVector
    } finally stream.close()
    sources.foreach { path =>
      val file = java.nio.file.Paths.get(path)
      assert(java.util.Arrays.equals(Files.readAllBytes(file), Files.readAllBytes(repeated.resolve(file.getFileName))),
        s"nondeterministic CDC artifact $file")
    }
    val rtl = sources.map(x => new String(Files.readAllBytes(java.nio.file.Paths.get(x)), UTF_8)).mkString("\n")
    assert(rtl.contains(".STAGES(SYNC_STAGES)") && rtl.contains(".WIDTH(1)"), rtl)
    assert(rtl.contains("ASYNC_REG") && rtl.contains("negedge resetn"), rtl)
    val parameter = if (standalone) "WIDTH" else "FIFO_LOG_DEPTH"
    val lint = run(dir, Seq("verilator", "--lint-only", "-Wno-fatal", "--top-module", "CdcParent") ++ sources)
    assert(lint._1 == 0, lint._2)
    for (fifo <- (if (standalone) Seq(1, 5, 18) else Seq(2, 3, 16)); stages <- 2 to 4) {
      val width = if (standalone) fifo else fifo + 2
      val synth = run(dir, Seq("yosys", "-p",
        s"read_verilog -DSYNTHESIS ${sources.mkString(" ")}; chparam -set $parameter $fifo -set SYNC_STAGES $stages CdcParent; synth -top CdcParent; check -assert"))
      assert(synth._1 == 0, synth._2)
      Files.write(dir.resolve("tb.v"), s"""module tb;
        |localparam W=$width; reg clk=0; reg resetn=0;
        |reg [W-1:0] dataIn,grayIn; reg flagIn;
        |wire [W-1:0] dataOut,decoded; wire flagOut;
        |reg [W-1:0] model[0:3]; reg flags[0:3]; reg [W-1:0] expectedGray;
        |reg parity; integer i,j,k;
        |CdcParent #(.$parameter($fifo),.SYNC_STAGES($stages)) dut
        |(.dataIn(dataIn),.flagIn(flagIn),.grayIn(grayIn),.dataOut(dataOut),.flagOut(flagOut),.decoded(decoded),.clk(clk),.resetn(resetn));
        |initial begin
        |dataIn=0; grayIn=0; flagIn=0;
        |for(j=0;j<4;j=j+1) begin model[j]=0; flags[j]=0; end
        |#2; if(dataOut !== 0 || flagOut !== 0) $$fatal(1,"ASYNC_INITIAL_RESET"); resetn=1;
        |for(i=0;i<512;i=i+1) begin
        |clk=0; dataIn=$$random; flagIn=i%2; grayIn=dataIn^(dataIn>>1);
        |if(i==63) begin dataIn={W{1'bx}}; grayIn={W{1'bz}}; flagIn=1'bx; end
        |if(i==64) begin dataIn={W{1'bz}}; flagIn=1'bz; end
        |if(i==31 || i==95) begin
        |resetn=0; #1; if(dataOut !== 0 || flagOut !== 0) $$fatal(1,"ASYNC_MIDSTREAM_RESET");
        |for(j=0;j<4;j=j+1) begin model[j]=0; flags[j]=0; end
        |resetn=1;
        |end
        |#1; parity=0;
        |for(k=W-1;k>=0;k=k-1) begin parity=parity^grayIn[k]; expectedGray[k]=parity; end
        |if(decoded !== expectedGray) $$fatal(1,"GRAY_ORACLE");
        |for(j=3;j>0;j=j-1) begin model[j]=model[j-1]; flags[j]=flags[j-1]; end
        |model[0]=dataIn; flags[0]=flagIn; clk=1; #1;
        |if(dataOut !== model[$stages-1] || flagOut !== flags[$stages-1]) $$fatal(1,"CDC_LATENCY");
        |end
        |$$display("CDC_FORMALS_PASS"); $$finish;
        |end
        |endmodule
        |""".stripMargin.getBytes(UTF_8))
      val compiled = run(dir, Seq("iverilog", "-g2001", "-s", "tb", "-o", "cdc.vvp") ++ sources :+ "tb.v")
      assert(compiled._1 == 0, compiled._2)
      val simulated = run(dir, Seq("vvp", "cdc.vvp"))
      assert(simulated._1 == 0 && simulated._2.contains("CDC_FORMALS_PASS"), simulated._2)
    }
    for ((value, stages) <- Seq((0,"2"),(if (standalone) 19 else 17,"2"),(if (standalone) 5 else 3,"1"),(if (standalone) 5 else 3,"5"),(if (standalone) 5 else 3,"32'bx"),(if (standalone) 5 else 3,"32'bz"))) {
      Files.write(dir.resolve("invalid.v"), s"""module invalid;
        |CdcParent #(.$parameter($value),.SYNC_STAGES($stages)) dut();
        |initial begin #1; $$finish; end
        |endmodule
        |""".stripMargin.getBytes(UTF_8))
      val compiled = run(dir, Seq("iverilog", "-g2001", "-s", "invalid", "-o", "invalid.vvp") ++ sources :+ "invalid.v")
      if (standalone && value == 0) {
        assert(compiled._1 != 0 && compiled._2.contains("Concatenation repeat may not be zero"), compiled._2)
      } else {
        assert(compiled._1 == 0, compiled._2)
        val simulated = run(dir, Seq("vvp", "invalid.vvp"))
        assert(simulated._1 != 0 && simulated._2.contains("must be in"), simulated._2)
      }
    }
  }
}
