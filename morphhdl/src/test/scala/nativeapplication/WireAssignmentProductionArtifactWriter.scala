package morphhdl.examples

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import morphhdl.{MorphVerilog, MorphWireAssignmentPasses}
import morphhdl.frontend.HdlInt
import spinal.core._

/** Ordinary source declarations: candidate aliases deliberately carry no test tags. */
private[examples] abstract class OrdinaryNamedAliasTopology(width: HdlInt) extends Component {
  val a, b = in Bits(width bits)
  val fixedIn = in Bits(8 bits)
  val signedIn = in SInt(8 bits)
  val unsignedIn = in UInt(8 bits)
  val choose, softResetIn = in Bool()
  val bitResult, clonedResult, keptResult, guardedResult, registeredResult, branchResult, softRegisterResult = out Bits(width bits)
  val flagResult, removableResult, timingResult, timingRegisterResult, rootBranchResult = out Bool()
  val signedResult, signedCloneResult = out SInt(8 bits)
  val unsignedResult, unsignedCloneResult = out UInt(8 bits)
  val hierarchyResult, hierarchyInputResult = out Bits(8 bits)

  val bitSource = Bits(width bits)
  bitSource := a ^ b
  val bitAlias = Bits(width bits)
  bitAlias := bitSource
  bitResult := bitAlias
  val bitCloneAlias = cloneOf(bitSource)
  bitCloneAlias := bitSource
  clonedResult := bitCloneAlias

  val flagSource = Bool()
  flagSource := choose === a(0)
  val flagAlias = Bool()
  flagAlias := flagSource
  flagResult := flagAlias

  val signedAlias = SInt(8 bits)
  signedAlias := signedIn
  signedResult := signedAlias
  val unsignedAlias = UInt(8 bits)
  unsignedAlias := unsignedIn
  unsignedResult := unsignedAlias
  val signedSource = SInt(8 bits)
  signedSource := ~signedIn
  val signedCloneAlias = cloneOf(signedSource)
  signedCloneAlias := signedSource
  signedCloneResult := signedCloneAlias
  val unsignedSource = UInt(8 bits)
  unsignedSource := ~unsignedIn
  val unsignedCloneAlias = cloneOf(unsignedSource)
  unsignedCloneAlias := unsignedSource
  unsignedCloneResult := unsignedCloneAlias

  val keptAlias = cloneOf(bitSource)
  keptAlias.addAttribute("keep")
  keptAlias := bitSource
  keptResult := keptAlias
  val guardedAlias = cloneOf(bitSource)
  guardedAlias.dontSimplifyIt()
  guardedAlias := bitSource
  guardedResult := guardedAlias

  // Both a sequential receiver and a conditional combinational receiver are
  // outside the continuous-use contract. Their alias declarations must survive.
  val sampledAlias = cloneOf(bitSource)
  sampledAlias := bitSource
  val sampled = Reg(Bits(width bits)) init 0
  sampled := sampledAlias
  registeredResult := sampled
  val conditionalAlias = cloneOf(bitSource)
  conditionalAlias := bitSource
  branchResult := a
  when(choose) { branchResult := conditionalAlias }
  val rootProceduralAlias = Bool()
  rootProceduralAlias := flagSource
  rootBranchResult := rootProceduralAlias
  when(choose) { rootBranchResult := False }

  val softResetSource = Bool()
  softResetSource := softResetIn | False
  val softResetAlias = Bool()
  softResetAlias := softResetSource
  val softArea = new ClockingArea(ClockDomain.current.copy(softReset = softResetAlias)) {
    val sampled = Reg(Bits(width bits)) init 0
    sampled := b
  }
  softRegisterResult := softArea.sampled

  // A compiler-created diagnostic name is not source/elaboration provenance.
  // Keep this local so Component reflection does not give it a source val name.
  removableResult := {
    val temporary = Bool().setName("removableAlias", Nameable.REMOVABLE)
    temporary := flagSource
    temporary
  }

  val timingSource = Bool()
  timingSource := choose ^ softResetIn
  val timingAlias = Bool()
  timingAlias := timingSource
  timingResult := timingAlias
  val timingRegister = Reg(Bool()) init False
  timingRegister := choose
  timingRegister.addTag(crossClockFalsePath(Some(timingAlias)))
  timingRegisterResult := timingRegister

  val child = new Component {
    val input = in Bits(8 bits)
    val output = out Bits(8 bits)
    output := ~input
  }
  val fixedSource = Bits(8 bits)
  fixedSource := ~fixedIn
  val toChildAlias = cloneOf(fixedSource)
  toChildAlias := fixedSource
  child.input := toChildAlias
  hierarchyInputResult := toChildAlias
  val fromChildAlias = Bits(8 bits)
  fromChildAlias := child.output
  hierarchyResult := fromChildAlias
}

private[examples] final class ProductionNamedAliases(width: HdlInt)
    extends OrdinaryNamedAliasTopology(width)
private[examples] final class UnrelatedPacketRouter(width: HdlInt)
    extends OrdinaryNamedAliasTopology(width)
private[examples] final class OpaqueNamedAliasMetadata(val payload: AnyRef) extends SpinalTag

/** Real public-API artifacts; historical reference generation stays independent. */
object WireAssignmentProductionArtifactWriter {
  private def config(output: Path): SpinalConfig = {
    Files.createDirectories(output)
    val result = SpinalConfig(targetDirectory = output.toString,
      defaultConfigForClockDomains = ClockDomainConfig(
        clockEdge = RISING, resetKind = SYNC, resetActiveLevel = HIGH))
    result.netlistFileName = "generated.v"
    result
  }

  private def fifo(output: Path, enabled: Option[Boolean]): Unit = {
    val original = config(output)
    val selected = enabled.map(value => MorphWireAssignmentPasses(original, value))
      .getOrElse(original)
    val width = HdlInt.param("WIDTH", default = BigInt(8), min = BigInt(1), max = BigInt(64))
    val depth = HdlInt.param("DEPTH", default = BigInt(5), min = BigInt(1), max = BigInt(8))
    MorphVerilog(selected) { new ParameterizedStreamFifo(width, depth) }
  }

  private def generic(output: Path, mode: String): Unit = {
    val original = config(output)
    val selected = mode match {
      case "enabled" => MorphWireAssignmentPasses(original, enabled = true)
      case "default" => MorphWireAssignmentPasses(original)
      case "reference" =>
        ConstantOperandWitnessPhasePlan.install(original, None)
        original
      case "disabled" => MorphWireAssignmentPasses(original, enabled = false)
      case "plain" => original
    }
    SpinalVerilog(selected) {
      new Component {
        setDefinitionName("ProductionExpressions")
        val a, b = in Bool()
        val y0, y1, y2, y3, y4, y5, y6, y7 = out Bool()
        y0 := (a === b) & True
        y1 := (a === b) & False
        y2 := Mux(a === b, True, False)
        y3 := Mux(a =/= b, False, True)
        y4 := Mux(Mux(a === b, True, False), False, True)
        y5 := Mux(a, a, b)
        y6 := a & True
        y7 := a ^ True
      }
    }
  }

  private def ordinaryNamed(output: Path, mode: String, unrelated: Boolean): Unit = {
    val original = config(output)
    val selected = mode match {
      case "enabled" => MorphWireAssignmentPasses(original, enabled = true)
      case "disabled" => MorphWireAssignmentPasses(original, enabled = false)
      case "default" => original
    }
    val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 16)
    MorphVerilog(selected) {
      if (unrelated) new UnrelatedPacketRouter(width)
      else new ProductionNamedAliases(width)
    }
  }

  private def debugNamed(output: Path, enabled: Boolean): Unit = {
    val original = config(output).copy(genLineComments = true)
    // Parameterized publication does not support line-comment emission. Keep
    // its rejection and test the supported public SpinalVerilog flag path.
    val rejected = MorphVerilog.tryGenerate(original) { new Component {} }
    require(rejected.isLeft)
    require(rejected.left.get.message.contains("genLineComments"))
    SpinalVerilog(MorphWireAssignmentPasses(original, enabled = enabled)) {
      new Component {
        setDefinitionName("ProductionDebugAliases")
        val a, b = in Bool()
        val y = out Bool()
        val debugSource = Bool()
        debugSource := a ^ b
        val debugAlias = Bool()
        debugAlias := debugSource
        y := debugAlias
      }
    }
  }

  private def opaqueNamed(output: Path, mode: String): Unit = {
    val original = config(output)
    val selected = mode match {
      case "enabled" => MorphWireAssignmentPasses(original, enabled = true)
      case "disabled" => MorphWireAssignmentPasses(original, enabled = false)
      case "default" => original
    }
    val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 16)
    MorphVerilog(selected) {
      new Component {
        setDefinitionName("ProductionOpaqueAliases")
        val a, b = in Bool()
        val y = out Bool()
        val data = in Bits(width bits)
        val observed = out Bits(width bits)
        val source = Bool()
        source := a ^ b
        val opaqueAlias = Bool()
        opaqueAlias := source
        y := opaqueAlias
        observed := data
        observed.addTag(new OpaqueNamedAliasMetadata(new Object))
      }
    }
  }

  def main(args: Array[String]): Unit = {
    require(args.length == 1, "usage: OUTPUT_DIRECTORY")
    val output = Paths.get(args(0)).toAbsolutePath.normalize
    val original = config(output.resolve("configuration"))
    val count = original.phasesInserters.size
    val enabled = MorphWireAssignmentPasses(original, enabled = true)
    val default = MorphWireAssignmentPasses(original)
    val disabled = MorphWireAssignmentPasses(default, enabled = false)
    require(original.phasesInserters.size == count)
    require(enabled.phasesInserters.size == count + 1)
    require(default.phasesInserters == enabled.phasesInserters)
    require(MorphWireAssignmentPasses(enabled).phasesInserters == enabled.phasesInserters)
    val withCustomInserter = enabled.copy(phasesInserters = enabled.phasesInserters.clone())
    withCustomInserter.phasesInserters += (_ => ())
    require(MorphWireAssignmentPasses(withCustomInserter).phasesInserters == withCustomInserter.phasesInserters)
    require(disabled.phasesInserters.size == count + 1)
    require(disabled.phasesInserters != enabled.phasesInserters)
    require(MorphWireAssignmentPasses(disabled).phasesInserters == enabled.phasesInserters)
    val copiedDisabled = disabled.copy()
    require(MorphWireAssignmentPasses.forPublication(copiedDisabled) eq copiedDisabled)
    require(MorphWireAssignmentPasses.forPublication(enabled) eq enabled)
    require(MorphWireAssignmentPasses.forPublication(original).phasesInserters == enabled.phasesInserters)
    require(!(enabled.phasesInserters eq original.phasesInserters))
    require(!(disabled.phasesInserters eq default.phasesInserters))
    for (round <- Vector("first", "repeat")) {
      val directory = output.resolve(round)
      fifo(directory.resolve("fifo-enabled"), Some(true))
      fifo(directory.resolve("fifo-disabled"), Some(false))
      fifo(directory.resolve("fifo-plain"), None)
      ParameterizedStreamFifoExample.main(Array(
        directory.resolve("fifo-legacy").toString, "generated.v"))
      val expected = directory.resolve("fifo-historical-all")
      ParameterizedStreamFifoBooleanTernaryWitness.main(Array(
        "all", expected.toString, "generated.v", expected.resolve("report.json").toString))
      for (mode <- Vector("reference", "enabled", "default", "disabled", "plain"))
        generic(directory.resolve("generic-" + mode), mode)
      for (mode <- Vector("default", "enabled", "disabled")) {
        ordinaryNamed(directory.resolve("named-" + mode), mode, unrelated = false)
        ordinaryNamed(directory.resolve("unrelated-" + mode), mode, unrelated = true)
      }
      debugNamed(directory.resolve("named-debug-enabled"), enabled = true)
      debugNamed(directory.resolve("named-debug-disabled"), enabled = false)
      for (mode <- Vector("default", "enabled", "disabled"))
        opaqueNamed(directory.resolve("named-opaque-" + mode), mode)
    }
    Files.write(output.resolve("configuration.txt"),
      "default-on selection, explicit opt-out and configuration isolation PASS\n".getBytes(StandardCharsets.UTF_8))
  }
}
