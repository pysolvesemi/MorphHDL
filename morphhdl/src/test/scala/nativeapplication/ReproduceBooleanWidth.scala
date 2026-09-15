import spinal.core._
import morphhdl.MorphVerilog
import morphhdl.frontend.HdlBool

class BooleanWidthExample(width: ElabInt) extends Component {
  setDefinitionName("BooleanWidthExample")

  val dataIn  = in Bits(width bits)
  val dataOut = out Bits(width bits)

  dataOut := dataIn
}

object ReproduceBooleanWidth extends App {
  require(args.length == 1, "Usage: ReproduceBooleanWidth <output-directory>")

  val config = SpinalConfig(
    targetDirectory = args(0),
    oneFilePerComponent = false,
    headerWithDate = false,
    headerWithRepoHash = true
  )
  config.netlistFileName = "BooleanWidthExample.v"

  val report = MorphVerilog(config) {
    val width =
      HdlBool.param("PPC4", default = false).asElabBool.toElabInt * 3 + 1

    new BooleanWidthExample(width)
  }

  println(report.generatedSourcesPaths.mkString("\n"))
}
