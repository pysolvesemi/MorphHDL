package morphhdl

import spinal.core._

import morphhdl.frontend.HdlInt

private[morphhdl] object ParameterizedWireContractFixture {
  final case class Config(width: HdlInt)

  /**
    * One ordinary SpinalHDL component is both the concrete witness and the
    * parameterized Verilog source. No parallel ParamRTL module is authored.
    */
  private final class ParameterizedWire(config: Config, reverseConstructionOrder: Boolean)
      extends Component {
    setDefinitionName("ParameterizedWire")

    private val ports =
      if (reverseConstructionOrder) {
        val reversedDout = out(UInt(config.width bits)).setName("dout")
        val reversedDin = in(UInt(config.width bits)).setName("din")
        (reversedDin, reversedDout)
      } else {
        val orderedDin = in(UInt(config.width bits)).setName("din")
        val orderedDout = out(UInt(config.width bits)).setName("dout")
        (orderedDin, orderedDout)
      }

    val din: UInt = ports._1
    val dout: UInt = ports._2
    dout := din
  }

  def component(reverseConstructionOrder: Boolean): Component = {
    val config = Config(HdlInt.param("WIDTH", default = 8, min = 1, max = 64))
    new ParameterizedWire(config, reverseConstructionOrder)
  }
}
