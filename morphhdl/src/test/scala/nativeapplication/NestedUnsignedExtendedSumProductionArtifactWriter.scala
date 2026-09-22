package morphhdl.examples

import java.nio.file.{Files, Path, Paths}
import java.nio.charset.StandardCharsets

import morphhdl.{MorphVerilog, MorphWireAssignmentPasses}
import morphhdl.frontend.HdlInt
import spinal.core._
import spinal.core.internals.Resize

/** Exact fixed-width source form for the emitter-created-wrapper regression. */
private[examples] final class FixedNestedUnsignedExtendedSum(witnessWidth: HdlInt)
    extends Component {
  setDefinitionName("FixedNestedUnsignedExtendedSum")

  val hActive, hFrontPorch, hSyncWidth, hBackPorch = in UInt (16 bits)
  val hTotal = out UInt (18 bits)
  val parameterWitnessIn = in Bits (witnessWidth bits)
  val parameterWitnessOut = out Bits (witnessWidth bits)

  hTotal := (((hActive.resize(18) + hFrontPorch.resize(18)) +
    hSyncWidth.resize(18)) + hBackPorch.resize(18))
  parameterWitnessOut := parameterWitnessIn
}

/** The same expression with a real WIDTH domain covering every input width. */
private[examples] final class ParameterizedNestedUnsignedExtendedSum(width: HdlInt)
    extends Component {
  setDefinitionName("ParameterizedNestedUnsignedExtendedSum")

  val hActive, hFrontPorch, hSyncWidth, hBackPorch = in UInt (width bits)
  val hTotal = out UInt (18 bits)

  hTotal := (((hActive.resize(18) + hFrontPorch.resize(18)) +
    hSyncWidth.resize(18)) + hBackPorch.resize(18))
}

/** A same-width companion whose four-input sum deliberately wraps modulo 2^18. */
private[examples] final class FixedNestedUnsignedOverflowSum(witnessWidth: HdlInt)
    extends Component {
  setDefinitionName("FixedNestedUnsignedOverflowSum")

  val a, b, c, d = in UInt (18 bits)
  val overflowTotal = out UInt (18 bits)
  val parameterWitnessIn = in Bits (witnessWidth bits)
  val parameterWitnessOut = out Bits (witnessWidth bits)

  overflowTotal := (((a + b) + c) + d)
  parameterWitnessOut := parameterWitnessIn
}

/**
  * Public MorphVerilog reproduction for nested homogeneous unsigned additions.
  *
  * `default` and `enabled` select the complete production pipeline; `disabled`
  * is the unchanged native-emitter control. The parent production writer calls
  * [[writeRound]] so these artifacts share its repeat and cross-Scala gates.
  */
object NestedUnsignedExtendedSumProductionArtifactWriter {
  private def config(output: Path): SpinalConfig = {
    Files.createDirectories(output)
    val result = SpinalConfig(targetDirectory = output.toString)
    result.netlistFileName = "generated.v"
    result
  }

  private def selected(config: SpinalConfig, mode: String): SpinalConfig = mode match {
    case "default"  => config
    case "enabled"  => MorphWireAssignmentPasses(config, enabled = true)
    case "disabled" => MorphWireAssignmentPasses(config, enabled = false)
    case other       => throw new IllegalArgumentException(s"unsupported mode '$other'")
  }

  private def generateFixed(output: Path, mode: String): Unit = {
    val witnessWidth = HdlInt.param(
      "WITNESS_WIDTH",
      default = BigInt(4),
      min = BigInt(1),
      max = BigInt(8)
    )
    val original = config(output)
    MorphVerilog(selected(original, mode)) {
      new FixedNestedUnsignedExtendedSum(witnessWidth)
    }
  }

  private def generateParameterized(output: Path, mode: String): Unit = {
    val width = HdlInt.param(
      "WIDTH",
      default = BigInt(16),
      min = BigInt(1),
      max = BigInt(16)
    )
    val original = config(output)
    var native: ParameterizedNestedUnsignedExtendedSum = null
    MorphVerilog(selected(original, mode)) {
      native = new ParameterizedNestedUnsignedExtendedSum(width)
      native
    }
    val names = Vector(native.hActive, native.hFrontPorch, native.hSyncWidth, native.hBackPorch).map { input =>
      val targets = scala.collection.mutable.ArrayBuffer.empty[BaseType]
      native.dslBody.walkDeclarations {
        case value: BaseType if value.hasOnlyOneStatement => value.head.source match {
          case resize: Resize if resize.input eq input => targets += value
          case _ =>
        }
        case _ =>
      }
      require(targets.size == 1 && targets.head.getBitsWidth == 18,
        "each input must retain its exact native resize boundary")
      val name = targets.head.getName()
      require(name.matches("[A-Za-z_][A-Za-z0-9_$]*"))
      "\"" + input.getName() + "\":\"" + name + "\""
    }
    Files.write(output.resolve("native-resizes.json"),
      (names.mkString("{", ",", "}\n")).getBytes(StandardCharsets.UTF_8))
  }

  private def generateOverflow(output: Path, mode: String): Unit = {
    val witnessWidth = HdlInt.param(
      "WITNESS_WIDTH",
      default = BigInt(4),
      min = BigInt(1),
      max = BigInt(8)
    )
    val original = config(output)
    MorphVerilog(selected(original, mode)) {
      new FixedNestedUnsignedOverflowSum(witnessWidth)
    }
  }

  private[examples] def writeRound(output: Path): Unit = {
    for (mode <- Vector("default", "enabled", "disabled")) {
      generateFixed(output.resolve("nested-fixed-" + mode), mode)
      generateParameterized(output.resolve("nested-parameterized-" + mode), mode)
      generateOverflow(output.resolve("nested-overflow-" + mode), mode)
    }
  }

  def main(args: Array[String]): Unit = {
    require(args.length == 1, "usage: OUTPUT_DIRECTORY")
    val output = Paths.get(args(0)).toAbsolutePath.normalize
    for (round <- Vector("first", "repeat"))
      writeRound(output.resolve(round))
  }
}
