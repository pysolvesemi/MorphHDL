package repro

import spinal.core._
import morphhdl.MorphVerilog
import morphhdl.frontend.{HdlInt, formalParam}

/** Native projection, child binding, and mixed-legality boundaries. */
object IndependentAdvancedRepro extends App {
  require(args.length == 2, "Expected scenario and output directory")

  class Child(actualData: HdlInt, actualGeneration: HdlInt) extends Component {
    setDefinitionName("IndependentChild")
    @dontName private val data = actualData.asElabInt
    @dontName private val generation = actualGeneration.asElabInt
    val din = in Bits((data + generation) bits)
    val observed = out Bool()
    observed := din.orR
  }
  class Parent(data: HdlInt, generation: HdlInt) extends Component {
    setDefinitionName("IndependentParent")
    val din = in Bits((data.asElabInt + generation.asElabInt) bits)
    val observed = out Bool()
    val child = new Child(data, generation)
    child.din := din
    observed := child.observed
  }
  class Projection(data: ElabInt, generation: ElabInt) extends Component {
    setDefinitionName("IndependentProjection")
    val din = in Bits((data + generation) bits)
    val observed = out Bool()
    if (data > 1) {
      val local = Bits(((data - 1) + generation) bits)
      local := din.resize((data - 1) + generation)
      observed := local.orR
    } else {
      observed := din.orR
    }
  }
  class Legality(data: ElabInt, lanes: ElabInt, scenario: String) extends Component {
    setDefinitionName("IndependentLegality")
    val din = in Bits((data + lanes) bits)
    val observed = out Bool()
    observed := din.orR
    scenario match {
      case "always" => require((data > 0) && (lanes > 0), "universally positive")
      case "never" => require((data < 1) || (lanes < 1), "universally false")
      case "mixed" => require(!(lanes > 1) || (data >= lanes), "conditional payload/lane legality")
      case "joint-branch" =>
        if ((data > 2) && (lanes > 2)) {
          val scratch = Bits((data + lanes) bits)
          scratch := din
        }
    }
  }
  val config = SpinalConfig(targetDirectory = args(1), headerWithDate = false,
    headerWithRepoHash = true, oneFilePerComponent = false)
  config.netlistFileName = args(0) + ".v"
  MorphVerilog(config) {
    args(0) match {
      case "parent" => new Parent(HdlInt.param("DATA_BITS",32,1,2048), HdlInt.param("GENERATION_BITS",32,2,64))
      case "projection" => new Projection(HdlInt.param("DATA_BITS",1,1,2048).asElabInt,
        HdlInt.param("GENERATION_BITS",32,2,64).asElabInt)
      case scenario => new Legality(HdlInt.param("DATA_BITS",2,1,8).asElabInt,
        HdlInt.param("LIVE_LANES",2,1,4).asElabInt, scenario)
    }
  }
}
