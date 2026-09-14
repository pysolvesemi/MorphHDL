package repro

import spinal.core._
import morphhdl.MorphVerilog
import morphhdl.frontend.HdlInt

/** Cases whose native publication must not request a Cartesian proof. */
object SymbolicPublicationRepro extends App {
  require(args.length == 2, "Expected scenario and output directory")
  class Child(width: ElabInt) extends Component {
    setDefinitionName("SymbolicWidthChild")
    val din = in Bits(width bits)
    val observed = out Bool()
    observed := din.orR
  }
  class Width(a: ElabInt, b: ElabInt, scenario: String) extends Component {
    setDefinitionName("SymbolicPublication_" + scenario.replace('-', '_'))
    @dontName private val width = scenario match {
      case "overlap" | "child" => (a + b) % (a + 1) + 1
      case "difference" => a - b
      case _ => a + b
    }
    val din = in Bits(width bits)
    val observed = out Bool()
    if (scenario == "child") {
      val child = new Child(width)
      child.din := din
      observed := child.observed
    } else observed := din.orR
    scenario match {
      case "mixed" => require(a >= b, "A must be >= B")
      case "message-format" =>
        require(a >= b, "A must be >= B; 100% literal %d %m; quoted \"A\"; backslash \\;\nnext line")
      case "default-invalid-require" => require(a < b, "A must be < B")
      case "concrete-invalid" => require(1 > 2, "known false")
      case "structural" =>
        if (((a + b) % (a + 1)) > b) {
          val local = Bits(width bits)
          local := din
        }
      case _ => ()
    }
  }
  class RequirementOnly(a: ElabInt, b: ElabInt) extends Component {
    setDefinitionName("SymbolicPublication_require_only")
    val din = in Bits(3 bits)
    val observed = out Bool()
    observed := din.orR
    require(a >= b, "A must be >= B")
  }
  val config = SpinalConfig(targetDirectory = args(1), headerWithDate = false,
    headerWithRepoHash = true, oneFilePerComponent = false)
  config.netlistFileName = args(0) + ".v"
  MorphVerilog(config) {
    val a = HdlInt.param("A", default=32, min=1, max=2048).asElabInt
    val b = HdlInt.param("B", default=2, min=1, max=2048).asElabInt
    if (args(0) == "require-only") new RequirementOnly(a,b)
    else new Width(a,b,args(0))
  }
}
