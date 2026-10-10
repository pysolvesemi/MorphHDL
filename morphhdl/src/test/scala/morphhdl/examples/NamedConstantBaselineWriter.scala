package morphhdl.examples

import java.nio.file.{Files, Paths}
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi.{Axi4, Axi4Config, Axi4SlaveFactory}
import spinal.lib.bus.amba4.axilite.{AxiLite4, AxiLite4Config, AxiLite4SlaveFactory}
import spinal.lib.bus.misc.BusSlaveFactory

object NamedConstantBaselineWriter {
  class LiteralFactory(lite: Boolean) extends Component {
    setDefinitionName(if (lite) "LiteralAxiLite" else "LiteralAxi4")
    val factory: BusSlaveFactory = if (lite) {
      val bus = slave(AxiLite4(AxiLite4Config(8, 32)))
      AxiLite4SlaveFactory(bus)
    } else {
      val bus = slave(Axi4(Axi4Config(addressWidth=8,dataWidth=32,idWidth=2)))
      Axi4SlaveFactory(bus)
    }
    val register = Reg(UInt(32 bits)) init(0)
    val observed = out UInt(32 bits)
    val events = out UInt(8 bits)
    val count = Reg(UInt(8 bits)) init(0)
    observed := register
    events := count
    factory.readAndWrite(register, BigInt(4))
    factory.onRead(BigInt(8)) { count := count + 1 }
    factory.onWrite(BigInt(8)) { count := count + 1 }
  }
  class LiteralDecoder extends Component {
    val address = in UInt(8 bits)
    val hit = out Bool()
    val decoded = out UInt(8 bits)
    hit := address === U(4,8 bits)
    decoded := 0
    switch(address) { is(4) { decoded := 1 }; is(8) { decoded := 2 } }
  }
  def main(args: Array[String]): Unit = {
    require(args.length == 1)
    val dir = Paths.get(args(0)).toAbsolutePath
    Files.createDirectories(dir)
    SpinalVerilog(SpinalConfig(targetDirectory=dir.resolve("custom").toString, headerWithDate=false))(new LiteralDecoder)
    for(lite <- Seq(false,true))
      SpinalVerilog(SpinalConfig(targetDirectory=dir.resolve(if(lite) "axilite" else "axi4").toString, headerWithDate=false))(new LiteralFactory(lite))
  }
}
