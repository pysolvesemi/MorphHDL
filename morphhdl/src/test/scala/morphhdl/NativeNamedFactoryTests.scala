package morphhdl

import java.nio.file.Files
import java.nio.charset.StandardCharsets.UTF_8
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi.{Axi4,Axi4Config,Axi4SlaveFactory}
import spinal.lib.bus.amba4.axilite.{AxiLite4,AxiLite4Config,AxiLite4SlaveFactory}
import spinal.lib.bus.misc.BusSlaveFactory
import morphhdl.frontend.HdlInt
import org.scalatest.funsuite.AnyFunSuite
import scala.sys.process.{Process,ProcessLogger}

class NativeNamedFactoryTests extends AnyFunSuite {
  class Factory(lite: Boolean, named: Boolean, base: Int, symbolic: Boolean) extends Component {
    setDefinitionName(if(named) "NamedFactory" else "LiteralFactory")
    val factory: BusSlaveFactory = if(lite) {
      val bus=slave(AxiLite4(AxiLite4Config(8,32)))
      bus.setName("bus")
      new AxiLite4SlaveFactory(bus,useWriteStrobes=true)
    } else {
      val bus=slave(Axi4(Axi4Config(addressWidth=8,dataWidth=32,idWidth=2)))
      bus.setName("bus")
      Axi4SlaveFactory(bus)
    }
    val register=Reg(UInt(32 bits)) init(0)
    val high=Reg(UInt(32 bits)) init(0)
    val reads=Reg(UInt(8 bits)) init(0)
    val writes=Reg(UInt(8 bits)) init(0)
    val observed=out UInt(32 bits)
    val observedHigh=out UInt(32 bits)
    val readEvents=out UInt(8 bits)
    val writeEvents=out UInt(8 bits)
    observed:=register; observedHigh:=high; readEvents:=reads; writeEvents:=writes
    if(named) {
      @dontName val address = if(symbolic) HdlInt.param("BASE_WORD",1,0,14).asElabInt*4 else ElabInt.literal(base)
      val control=TypedLocalUInt("ADDR_CONTROL",address,8 bits)
      val event=TypedLocalUInt("ADDR_EVENT",control.elab+4,8 bits)
      val highAddress=TypedLocalUInt("ADDR_HIGH",BigInt(252),8 bits)
      factory.write(register,control)
      factory.read(register,control)
      factory.readAndWrite(high,highAddress)
      factory.onRead(event) { reads:=reads+1 }
      factory.onWrite(event) { writes:=writes+1 }
    } else {
      factory.write(register,BigInt(base)); factory.read(register,BigInt(base))
      factory.readAndWrite(high,BigInt(252))
      factory.onRead(BigInt(base+4)) { reads:=reads+1 }
      factory.onWrite(BigInt(base+4)) { writes:=writes+1 }
    }
  }
  for (lite <- Seq(false,true); mode <- 0 to 3)
  test(s"named factory mappings reject invalid alignment, overlap and duplicate reads, lite=$lite mode=$mode") {
    val error=intercept[Exception] {
      MorphVerilog(MorphWireAssignmentPasses(SpinalConfig(targetDirectory=Files.createTempDirectory("invalid-named-factory-").toString),enabled=false)) {
        new Component {
          val factory:BusSlaveFactory=if(lite) {
            val bus=slave(AxiLite4(AxiLite4Config(8,32))); bus.setName("bus"); AxiLite4SlaveFactory(bus)
          } else {
            val bus=slave(Axi4(Axi4Config(addressWidth=8,dataWidth=32,idWidth=2))); bus.setName("bus"); Axi4SlaveFactory(bus)
          }
          val first=Reg(UInt(32 bits)) init(0)
          val second=Reg(UInt(32 bits)) init(0)
          val address=TypedLocalUInt("ADDR_FIRST",BigInt(if(mode==0) 1 else 4),8 bits)
          factory.read(first,address)
          if(mode==1) {
            val duplicate=TypedLocalUInt("ADDR_ALIAS",BigInt(4),8 bits)
            factory.read(second,duplicate)
          }
          if(mode==2) {
            val other=TypedLocalUInt("ADDR_OTHER",HdlInt.param("OTHER_WORD",2,0,4).asElabInt*4,8 bits)
            factory.read(second,other)
          }
          if(mode==3) factory.read(second,BigInt(4))
        }
      }
    }
    val messages=Iterator.iterate[Throwable](error)(_.getCause).takeWhile(_!=null).map(x=>String.valueOf(x.getMessage)).mkString("\n")
    val code=if(mode==0) "BUS-ADDRESS-UNALIGNED" else if(mode==1) "DOUBLE-READ-ERROR" else "LOCALPARAM-BUS-ADDRESS-OVERLAP"
    assert(messages.contains(code),messages)
  }

  for(lite<-Seq(false,true); symbolic<-Seq(false,true))
  test(s"real slave factory named case maps preserve the literal implementation, lite=$lite symbolic=$symbolic") {
    val dir=Files.createTempDirectory("native-named-factory-")
    MorphVerilog(MorphWireAssignmentPasses(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false),enabled=false))(
      new Factory(lite,true,4,symbolic))
    val rtl=new String(Files.readAllBytes(dir.resolve("NamedFactory.v")),UTF_8)
    assert(rtl.contains("ADDR_CONTROL : begin") && rtl.contains("ADDR_EVENT : begin") && rtl.contains("ADDR_HIGH : begin"),rtl)
    val repeated=Files.createTempDirectory("native-named-factory-repeat-")
    MorphVerilog(MorphWireAssignmentPasses(SpinalConfig(targetDirectory=repeated.toString,headerWithDate=false),enabled=false))(
      new Factory(lite,true,4,symbolic))
    assert(rtl==new String(Files.readAllBytes(repeated.resolve("NamedFactory.v")),UTF_8))
    for(base<- (if(symbolic) Seq(0,4,56) else Seq(4))) {
      SpinalVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false))(new Factory(lite,false,base,false))
      val log=new StringBuilder
      def run(args:Seq[String]):Int={
        val result=Increment66ToolEvidence.run(dir,args)
        log.append(result._2)
        result._1
      }
      val specialize=if(symbolic) s"chparam -set BASE_WORD ${base/4} NamedFactory; " else ""
      assert(run(Seq("verilator","--lint-only","-Wno-fatal","--top-module","NamedFactory","NamedFactory.v"))==0,log.toString)
      assert(run(Seq("yosys","-p",s"read_verilog -DSYNTHESIS NamedFactory.v LiteralFactory.v; $specialize proc; memory; opt; async2sync; opt; equiv_make LiteralFactory NamedFactory equiv; hierarchy -top equiv; equiv_simple; equiv_induct -seq 8; equiv_status -assert"))==0,log.toString)
      assert(run(Seq("yosys","-p",s"read_verilog -DSYNTHESIS NamedFactory.v; $specialize synth -top NamedFactory; check -assert"))==0,log.toString)
      val script=new java.io.File("morphhdl/scripts/validate-named-factory.py").getAbsolutePath
      assert(run(Seq("python3",script,dir.toString,"--base",base.toString) ++
        (if(lite) Seq("--lite") else Seq.empty) ++ (if(symbolic) Seq("--symbolic") else Seq.empty))==0,log.toString)
    }
  }
}
