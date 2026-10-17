package morphhdl

import java.nio.file.{Files,Path}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

// Production CDC contract retained verbatim except fixture class names.
case class Lwr28CdcConfig(
    livePlacement: Param[Int] = 0, ddrPlacement: Param[Int] = 0,
    liveDataBits: Param[Int] = 32, ddrDataBits: Param[Int] = 64,
    liveLanes: Param[Int] = 1, ddrLanes: Param[Int] = 1, generationBits: Param[Int] = 32,
    configBits: Param[Int] = 256, eventBits: Param[Int] = 128,
    fifoLogDepth: Param[Int] = 3, eventLogDepth: Param[Int] = 2,
    syncStages: Param[Int] = 2, resetStages: Param[Int] = 3,
    clockTimeout: Param[Int] = 1024, configTimeout: Param[Int] = 1024,
    fullMargin: Param[Int] = 2, emptyThreshold: Param[Int] = 1) {
  require(livePlacement >= 0 && livePlacement <= 2)
  require(ddrPlacement >= 0 && ddrPlacement <= 2)
  require(liveLanes == 1 || liveLanes == 4)
  require(ddrLanes == 1 || ddrLanes == 4)
  require(liveDataBits > 0 && liveDataBits <= 2048)
  require(ddrDataBits > 0 && ddrDataBits <= 2048)
  require(livePlacement != 0 || (liveDataBits == 32 && liveLanes == 1))
  require(ddrPlacement != 0 ||
    ((ddrDataBits == 32 || ddrDataBits == 64 || ddrDataBits == 128 || ddrDataBits == 256) && ddrLanes == 1))
  require(livePlacement != 1 || liveDataBits == liveLanes * 30)
  require(ddrPlacement != 1 || ddrDataBits == ddrLanes * 30)
  require(generationBits >= 2 && generationBits <= 64)
  require(configBits > 0 && configBits <= 65536)
  require(eventBits > 0 && eventBits <= 4096)
  // The selected afifo has a [LGFIFO-2:0] slice: depth 2 is NOT legal.
  require(fifoLogDepth >= 2 && fifoLogDepth <= 16)
  require(eventLogDepth >= 2 && eventLogDepth <= 16)
  require(syncStages >= 2 && syncStages <= 4)
  require(resetStages >= 2 && resetStages <= 8)
  require(clockTimeout >= 32 && clockTimeout <= (1 << 24))
  require(configTimeout >= 32 && configTimeout <= (1 << 24))
  val capacity: ElabInt = fifoLogDepth.pow2 + 1 // includes Dan's registered head
  require(fullMargin >= 1 && fullMargin < capacity)
  require(emptyThreshold >= 0 && emptyThreshold < capacity)
  val countBits: ElabInt = fifoLogDepth + 2
  val liveRecordBits: ElabInt = Lwr28RecordLayout.width(liveDataBits, liveLanes, generationBits)
  val ddrRecordBits: ElabInt = Lwr28RecordLayout.width(ddrDataBits, ddrLanes, generationBits)
}

/** Least-significant first: data, SOF, EOL, EOF, frame-valid, format[2:0],
  * generation, lane-valid, active-byte-count[15:0], tail. These are INTERNAL
  * complete records, never extra pins on the frozen external AXI4-Stream.
  */
object Lwr28RecordLayout {
  def generationOffset(dataBits: ElabInt): ElabInt = dataBits + 7
  def width(dataBits: ElabInt, lanes: ElabInt, generationBits: ElabInt): ElabInt =
    dataBits + 7 + generationBits + lanes + 16 + 1
}


class Lwr28Cdc(c:Lwr28CdcConfig) extends Component {
  setDefinitionName("CdcDomainInference")
  val source=in Bits(c.liveRecordBits bits)
  val result=out Bits(c.liveRecordBits bits)
  val capacity=out UInt(17 bits)
  val clockTimeout=out UInt(25 bits)
  val configTimeout=out UInt(25 bits)
  val fullMargin=out UInt(17 bits)
  val emptyThreshold=out UInt(17 bits)
  result:=source
  capacity:=U(c.capacity,17 bits)
  clockTimeout:=U(c.clockTimeout,25 bits)
  configTimeout:=U(c.configTimeout,25 bits)
  fullMargin:=U(c.fullMargin,17 bits)
  emptyThreshold:=U(c.emptyThreshold,17 bits)
}

case class Lwr28ShiftConfig(width:Param[Int]) {
  val limit=(1 << 32)+(1 << -32)+(-1 >>> 31)+((2147483647+1) >>> 30)+((1 << 31) / -2147483648)
  require(width>=1 && width<=limit)
  val signed=(2147483647+1)/2+width
}
class Lwr28Shift(c:Lwr28ShiftConfig) extends Component {
  setDefinitionName("HostShiftConstraints")
  val width=out UInt(3 bits);val signed=out SInt(32 bits)
  width:=U(c.width,3 bits);signed:=S(c.signed,32 bits)
}

class ConfigurationDomainInferenceTests extends AnyFunSuite {
  private def run(dir:Path,cmd:Seq[String]):Unit={
    val r=Increment66ToolEvidence.run(dir,cmd);assert(r._1==0,cmd.mkString(" ")+"\n"+r._2)
  }
  private def generate():Path={
    val dir=Files.createTempDirectory("cdc-domain-")
    MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false)) {new Lwr28Cdc(Lwr28CdcConfig())}
    dir
  }
  test("production CDC domains publish large compact formals and retain override guards") {
    val dir=generate()
    val rtl=new String(Files.readAllBytes(dir.resolve("CdcDomainInference.v")),"UTF-8")
    assert(rtl==new String(Files.readAllBytes(generate().resolve("CdcDomainInference.v")),"UTF-8"))
    for(name<-Seq("C_LIVE_LANES","C_FIFO_LOG_DEPTH","C_CLOCK_TIMEOUT","C_CONFIG_TIMEOUT","C_FULL_MARGIN","C_EMPTY_THRESHOLD"))assert(rtl.contains(name),rtl)
    assert(rtl.contains("16777216") && rtl.contains("65536"),rtl)
    for((depth,lanes,timeout,full,empty,valid)<-Seq(
      (2,1,32,1,0,true),(16,4,16777216,65536,65536,true),(3,4,1024,8,8,true),
      (2,2,32,1,0,false),(2,3,32,1,0,false),(2,1,31,1,0,false),
      (2,1,16777217,1,0,false),(2,1,32,5,0,false),(2,1,32,1,5,false),
      (2,1,32,0,0,false),(1,1,32,1,0,false),(17,1,32,1,0,false))) {
      val bits=32+7+32+lanes+16+1
      Files.write(dir.resolve("tb.v"),s"""module tb;
reg[$bits-1:0]source;wire[$bits-1:0]result;wire[16:0]capacity,fullMargin,emptyThreshold;wire[24:0]clockTimeout,configTimeout;
CdcDomainInference #(.C_LIVE_PLACEMENT(2),.C_LIVE_LANES($lanes),.C_FIFO_LOG_DEPTH($depth),
.C_DDR_PLACEMENT(2),.C_DDR_LANES($lanes),.C_CLOCK_TIMEOUT($timeout),.C_CONFIG_TIMEOUT($timeout),.C_FULL_MARGIN($full),.C_EMPTY_THRESHOLD($empty)) dut
(source,result,capacity,clockTimeout,configTimeout,fullMargin,emptyThreshold);
integer i;initial begin for(i=0;i<16;i=i+1)begin source={$$random,$$random,$$random};#1;
if(result!==source || capacity!==${(1<<depth)+1} || clockTimeout!==$timeout || configTimeout!==$timeout || fullMargin!==$full || emptyThreshold!==$empty)$$fatal(1,"override behavior");end
$$finish;end endmodule
""".getBytes("UTF-8"))
      run(dir,Seq("iverilog","-g2001","-s","tb","-o","sim","CdcDomainInference.v","tb.v"))
      val r=Increment66ToolEvidence.run(dir,Seq("vvp","sim"));assert((r._1==0)==valid,r._2)
      if(!valid)assert(r._2.contains("FATAL") && !r._2.contains("override behavior"),r._2)
    }
    run(dir,Seq("verilator","--lint-only","--language","1364-2001","-Wno-fatal","-DSYNTHESIS","--top-module","CdcDomainInference","CdcDomainInference.v"))
    run(dir,Seq("yosys","-Q","-T","-p","read_verilog CdcDomainInference.v; hierarchy -check -top CdcDomainInference; proc; opt; check -assert; select -assert-none t:$dlatch"))
  }

  test("large scalar admission does not admit oversized physical ports") {
    val dir=Files.createTempDirectory("cdc-domain-width-negative-")
    val error=intercept[Exception] {
      MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false)) {
        new Component {
          val n:Param[Int]=Param(8)
          require(n>=1 && n<=65536)
          val source=in Bits(n bits);val result=out Bits(n bits);result:=source
        }
      }
    }
    assert(error.toString.contains("SPINAL-PARAMETERIZED-VERILOG-EXPRESSION-DOMAIN-TOO-LARGE") && error.toString.contains("bitVectorWidthMax=4096"),error.toString)
  }

  test("public expression summaries cannot manufacture compact declaration authority") {
    val dir=Files.createTempDirectory("cdc-domain-authority-negative-")
    val error=intercept[Exception] {
      MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false)) {
        new Component {
          val parameter=ElaborationIntegerParameter("FORGED",32,0,16777216)
          val n=ElabInt.fromExpression(ElaborationIntegerExpression("FORGED",32,0,16777216,Vector(parameter)))
          val result=out UInt(25 bits);result:=U(n,25 bits)
        }
      }
    }
    assert(error.toString.contains("DOMAIN"),error.toString)
  }

  test("host shift distance masking and overflow determine emitted domains exactly") {
    val dir=Files.createTempDirectory("cdc-domain-shifts-")
    MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false)) {new Lwr28Shift(Lwr28ShiftConfig(1))}
    for(n<-Seq(1,6,7)) {
      Files.write(dir.resolve("tb.v"),s"""module tb;wire[2:0]width;wire signed[31:0]signedValue;
HostShiftConstraints #(.C_WIDTH($n)) dut(width,signedValue);
initial begin #1;if(width!==$n || signedValue!==${-1073741824+n})$$fatal(1,"host folding");$$finish;end endmodule
""".getBytes("UTF-8"))
      run(dir,Seq("iverilog","-g2001","-s","tb","-o","sim","HostShiftConstraints.v","tb.v"))
      val r=Increment66ToolEvidence.run(dir,Seq("vvp","sim"));assert((r._1==0)==(n<=6),r._2)
      if(n>6)assert(r._2.contains("FATAL") && !r._2.contains("host folding"),r._2)
    }
  }
}
