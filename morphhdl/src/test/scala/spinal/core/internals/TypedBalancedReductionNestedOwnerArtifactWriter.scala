package spinal.core.internals

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import morphhdl.MorphVerilog
import morphhdl.frontend.{HdlInt, StructuralGenerateCaseOps, StructuralGenerateIfOps}
import spinal.core._
import spinal.lib._

/** Public helper fixtures: every tree comes from native Vec.reduceBalancedTree. */
final class BalancedNestedConditional(width: HdlInt, count: HdlInt, mode: HdlInt)
    extends Component {
  setDefinitionName("BalancedNestedConditional")
  val words = in(Vec(UInt(width bits), count)).setName("words")
  val result = out(UInt(width bits)).setName("result")
  mode.generateCase
    .choice(BigInt(0), "g_add") {
      // COUNT defaults to one. The non-default branch must still publish a
      // complete tree with its own narrowed COUNT domain.
      ElabControl.selectSymbolic(count.asElabInt > 1, "nested-count", 1) {
        result := words.reduceBalancedTree((a: UInt, b: UInt) => a + b)
      } {
        result := words(0)
      }
    }
    .choice(BigInt(1), "g_xor") {
      result := words.reduceBalancedTree((a: UInt, b: UInt) => a ^ b)
    }
    .default("g_or") {
      result := words.reduceBalancedTree((a: UInt, b: UInt) => a | b)
    }
}

/** A control-only child parameter retains an explicit ordinary formal binding. */
final class BalancedNestedFormalChild(width: HdlInt, count: HdlInt, mode: ElabInt)
    extends Component {
  setDefinitionName("BalancedNestedFormalChild")
  val words = in(Vec(UInt(width bits), count)).setName("words")
  val result = out(UInt(width bits)).setName("result")
  ElabControl.selectSymbolic(mode.elabEq(1), "formal-mode-add", 1) {
    ElabControl.selectSymbolic(count.asElabInt > 1, "formal-count", 1) {
      result := words.reduceBalancedTree((a: UInt, b: UInt) => a + b)
    } {
      result := words(0)
    }
  } {
    ElabControl.selectSymbolic(mode.elabEq(2), "formal-mode-xor", 1) {
      result := words.reduceBalancedTree((a: UInt, b: UInt) => a ^ b)
    } {
      result := words.reduceBalancedTree((a: UInt, b: UInt) => a | b)
    }
  }
}

/** Independent row biases expose stale indices and cross-instance result wiring. */
final class BalancedNestedLoop(width: HdlInt, count: HdlInt, rows: HdlInt, mode: HdlInt)
    extends Component {
  setDefinitionName("BalancedNestedLoop")
  val words = in(Vec(UInt(width bits), count)).setName("words")
  val biases = in(Vec(UInt(width bits), rows)).setName("biases")
  val result = out(Vec(UInt(width bits), rows)).setName("result")
  ElabFiniteRange.foreach(rows.asElabInt, "row") { row =>
    val local = Vec(UInt(width bits), count).setName("row_words")
    ElabFiniteRange.foreach(count.asElabInt, "word") { lane =>
      lane(local) := lane(words) ^ row(biases)
    }
    (mode > HdlInt.literal(0)).generateIf("g_row_xor", "g_row_add") {
      row(result) := local.reduceBalancedTree((a: UInt, b: UInt) => a ^ b)
    }.otherwise {
      row(result) := local.reduceBalancedTree((a: UInt, b: UInt) => a + b)
    }
  }
}

/** Both child instances share a logical definition but receive distinct inputs. */
final class BalancedNestedHierarchy(width: HdlInt, count: HdlInt, mode: HdlInt)
    extends Component {
  setDefinitionName("BalancedNestedHierarchy")
  val leftWords = in(Vec(UInt(width bits), count)).setName("leftWords")
  val rightWords = in(Vec(UInt(width bits), count)).setName("rightWords")
  val leftResult = out(UInt(width bits)).setName("leftResult")
  val rightResult = out(UInt(width bits)).setName("rightResult")
  val left = ElabFormalComponent.parameter(mode.asElabInt + 1, "MODE", BigInt(1), BigInt(3)) {
    childMode => new BalancedNestedFormalChild(width, count, childMode)
  }
  val right = ElabFormalComponent.parameter(mode.asElabInt + 1, "MODE", BigInt(1), BigInt(3)) {
    childMode => new BalancedNestedFormalChild(width, count, childMode)
  }
  left.words := leftWords
  right.words := rightWords
  leftResult := left.result
  rightResult := right.result
}

/** Exercises native register processes within the exact nested generate owner. */
final class BalancedNestedRegisteredLoop(width: HdlInt, count: HdlInt, rows: HdlInt, mode: HdlInt)
    extends Component {
  setDefinitionName("BalancedNestedRegisteredLoop")
  val clk = in(Bool()).setName("clk")
  val reset = in(Bool()).setName("reset")
  val enable = in(Bool()).setName("enable")
  val words = in(Vec(UInt(width bits), count)).setName("words")
  val biases = in(Vec(UInt(width bits), rows)).setName("biases")
  val result = out(Vec(UInt(width bits), rows)).setName("result")
  val registered = new ClockingArea(ClockDomain(clock = clk, reset = reset,
      clockEnable = enable, config = ClockDomainConfig(resetKind = SYNC,
        resetActiveLevel = HIGH, clockEnableActiveLevel = HIGH))) {
    ElabFiniteRange.foreach(rows.asElabInt, "registered_row") { row =>
      val local = Vec(UInt(width bits), count).setName("row_words")
      ElabFiniteRange.foreach(count.asElabInt, "registered_word") { lane =>
        lane(local) := lane(words) ^ row(biases)
      }
      (mode > HdlInt.literal(0)).generateIf("g_row_xor", "g_row_add") {
        row(result) := local.reduceBalancedTree((a: UInt, b: UInt) => a ^ b,
          (value: UInt, _: Int) => {
            val register = UInt()
            register.setAsReg()
            register := value
            register.init(U(0))
            register
          })
      }.otherwise {
        row(result) := local.reduceBalancedTree((a: UInt, b: UInt) => a + b,
          (value: UInt, _: Int) => {
            val register = UInt()
            register.setAsReg()
            register := value
            register.init(U(0))
            register
          })
      }
    }
  }
}

/** Entering a child suspends the parent's active region and restores it on exit. */
final class BalancedNestedHierarchyLoop(width: HdlInt, count: HdlInt, rows: HdlInt, mode: HdlInt)
    extends Component {
  setDefinitionName("BalancedNestedHierarchyLoop")
  val words = in(Vec(UInt(width bits), count)).setName("words")
  val biases = in(Vec(UInt(width bits), rows)).setName("biases")
  val result = out(Vec(UInt(width bits), rows)).setName("result")
  ElabFiniteRange.foreach(rows.asElabInt, "child_row") { row =>
    val child = ElabFormalComponent.parameter(mode.asElabInt + 1, "MODE", BigInt(1), BigInt(3)) {
      childMode => new BalancedNestedFormalChild(width, count, childMode)
    }
    val local = Vec(UInt(width bits), count).setName("child_row_words")
    ElabFiniteRange.foreach(count.asElabInt, "child_word") { lane =>
      lane(local) := lane(words) ^ row(biases)
    }
    child.words := local
    row(result) := child.result.asBits.asUInt
  }
}

/** Independent concrete elaboration: no HdlInt, structural capture or replay. */
final class BalancedNestedConditionalReference(width: Int, count: Int, mode: Int)
    extends Component {
  setDefinitionName(s"BalancedNestedConditionalReference_w${width}_n${count}_m$mode")
  val words = in(Bits(width * count bits)).setName("words")
  val result = out(UInt(width bits)).setName("result")
  val values = Vector.tabulate(count)(lane => words(lane * width, width bits).asUInt)
  mode match {
    case 0 =>
      if (count > 1) result := values.reduceBalancedTree((a: UInt, b: UInt) => a + b)
      else result := values.head
    case 1 => result := values.reduceBalancedTree((a: UInt, b: UInt) => a ^ b)
    case _ => result := values.reduceBalancedTree((a: UInt, b: UInt) => a | b)
  }
}

final class BalancedNestedLoopReference(width: Int, count: Int, rows: Int, mode: Int)
    extends Component {
  setDefinitionName(s"BalancedNestedLoopReference_w${width}_n${count}_r${rows}_m$mode")
  val words = in(Bits(width * count bits)).setName("words")
  val biases = in(Bits(width * rows bits)).setName("biases")
  val result = out(Bits(width * rows bits)).setName("result")
  for (row <- 0 until rows) {
    val bias = biases(row * width, width bits).asUInt
    val values = Vector.tabulate(count)(lane => words(lane * width, width bits).asUInt ^ bias)
    val reduced = if (mode > 0) values.reduceBalancedTree((a: UInt, b: UInt) => a ^ b)
      else values.reduceBalancedTree((a: UInt, b: UInt) => a + b)
    result(row * width, width bits) := reduced.asBits
  }
}

final class BalancedNestedHierarchyReference(width: Int, count: Int, mode: Int)
    extends Component {
  setDefinitionName(s"BalancedNestedHierarchyReference_w${width}_n${count}_m$mode")
  val leftWords = in(Bits(width * count bits)).setName("leftWords")
  val rightWords = in(Bits(width * count bits)).setName("rightWords")
  val leftResult = out(UInt(width bits)).setName("leftResult")
  val rightResult = out(UInt(width bits)).setName("rightResult")
  val left = new BalancedNestedConditionalReference(width, count, mode)
  val right = new BalancedNestedConditionalReference(width, count, mode)
  left.words := leftWords
  right.words := rightWords
  leftResult := left.result
  rightResult := right.result
}

final class BalancedNestedRegisteredLoopReference(width: Int, count: Int, rows: Int, mode: Int)
    extends Component {
  setDefinitionName(s"BalancedNestedRegisteredLoopReference_w${width}_n${count}_r${rows}_m$mode")
  val clk = in(Bool()).setName("clk")
  val reset = in(Bool()).setName("reset")
  val enable = in(Bool()).setName("enable")
  val words = in(Bits(width * count bits)).setName("words")
  val biases = in(Bits(width * rows bits)).setName("biases")
  val result = out(Bits(width * rows bits)).setName("result")
  val registered = new ClockingArea(ClockDomain(clock = clk, reset = reset,
      clockEnable = enable, config = ClockDomainConfig(resetKind = SYNC,
        resetActiveLevel = HIGH, clockEnableActiveLevel = HIGH))) {
    for (row <- 0 until rows) {
      val bias = biases(row * width, width bits).asUInt
      val values = Vector.tabulate(count)(lane => words(lane * width, width bits).asUInt ^ bias)
      val bridge = (value: UInt, _: Int) => {
        val register = UInt()
        register.setAsReg()
        register := value
        register.init(U(0))
        register
      }
      val reduced = if (mode > 0) values.reduceBalancedTree((a: UInt, b: UInt) => a ^ b, bridge)
        else values.reduceBalancedTree((a: UInt, b: UInt) => a + b, bridge)
      result(row * width, width bits) := reduced.asBits
    }
  }
}

final class BalancedNestedHierarchyLoopReference(width: Int, count: Int, rows: Int, mode: Int)
    extends Component {
  setDefinitionName(s"BalancedNestedHierarchyLoopReference_w${width}_n${count}_r${rows}_m$mode")
  val words = in(Bits(width * count bits)).setName("words")
  val biases = in(Bits(width * rows bits)).setName("biases")
  val result = out(Bits(width * rows bits)).setName("result")
  for (row <- 0 until rows) {
    val child = new BalancedNestedConditionalReference(width, count, mode)
    for (lane <- 0 until count) {
      child.words(lane * width, width bits) :=
        words(lane * width, width bits) ^ biases(row * width, width bits)
    }
    result(row * width, width bits) := child.result.asBits
  }
}

object TypedBalancedReductionNestedOwnerArtifactWriter {
  val profiles: Vector[String] = Vector("conditional", "loop", "hierarchy", "registered-loop", "hierarchy-loop")

  // The roadmap's complete scalar matrix supplements, rather than replaces,
  // the original row/count witnesses used by the ownership mutation controls.
  private val scalarWidths = Vector(1, 5, 8, 32)
  private val scalarCounts = Vector(1, 2, 3, 5, 8, 9, 16, 17)
  private val originalPoints = Vector((1, 1, 1), (5, 1, 3), (5, 2, 2),
    (8, 3, 3), (5, 5, 2), (8, 9, 3), (1, 5, 2))

  def points(profile: String): Vector[(Int, Int, Int)] = {
    require(profiles.contains(profile), "unknown nested-owner qualification profile")
    val common = for (width <- scalarWidths; count <- scalarCounts) yield (width, count, 3)
    (originalPoints ++ common).map { case (width, count, rows) =>
      (width, count, if (profile.endsWith("loop")) rows else 1)
    }.distinct
  }

  def config(directory: Path, fileName: String): SpinalConfig = {
    Files.createDirectories(directory)
    val result = SpinalConfig(targetDirectory = directory.toString)
    result.netlistFileName = fileName
    result
  }

  def candidateModule(profile: String): String = profile match {
    case "conditional" => "BalancedNestedConditional"
    case "loop" => "BalancedNestedLoop"
    case "hierarchy" => "BalancedNestedHierarchy"
    case "registered-loop" => "BalancedNestedRegisteredLoop"
    case "hierarchy-loop" => "BalancedNestedHierarchyLoop"
  }

  def referenceModule(profile: String, width: Int, count: Int, rows: Int, mode: Int): String =
    candidateModule(profile) + s"Reference_w${width}_n$count" +
      (if (profile.endsWith("loop")) s"_r$rows" else "") + s"_m$mode"

  def candidate(directory: Path, profile: String): Path = {
    val module = candidateModule(profile)
    MorphVerilog(config(directory, module + ".v")) {
      val width = HdlInt.param("WIDTH", 5, 1, 32)
      val count = HdlInt.param("COUNT", 1, 1, 17)
      val mode = HdlInt.param("MODE", 0, 0, 2)
      profile match {
        case "conditional" => new BalancedNestedConditional(width, count, mode)
        case "loop" => new BalancedNestedLoop(width, count, HdlInt.param("ROWS", 1, 1, 3), mode)
        case "hierarchy" => new BalancedNestedHierarchy(width, count, mode)
        case "registered-loop" => new BalancedNestedRegisteredLoop(width, count,
          HdlInt.param("ROWS", 1, 1, 3), mode)
        case "hierarchy-loop" => new BalancedNestedHierarchyLoop(width, count,
          HdlInt.param("ROWS", 1, 1, 3), mode)
      }
    }
    directory.resolve(module + ".v")
  }

  def reference(directory: Path, profile: String, width: Int, count: Int,
      rows: Int, mode: Int): Path = {
    val module = referenceModule(profile, width, count, rows, mode)
    Files.createDirectories(directory)
    val nativeConfig = SpinalConfig(targetDirectory = directory.toString,
      headerWithDate = false, headerWithRepoHash = false)
    nativeConfig.netlistFileName = module + ".v"
    nativeConfig.generateVerilog(profile match {
      case "conditional" => new BalancedNestedConditionalReference(width, count, mode)
      case "loop" => new BalancedNestedLoopReference(width, count, rows, mode)
      case "hierarchy" => new BalancedNestedHierarchyReference(width, count, mode)
      case "registered-loop" => new BalancedNestedRegisteredLoopReference(width, count, rows, mode)
      case "hierarchy-loop" => new BalancedNestedHierarchyLoopReference(width, count, rows, mode)
    })
    directory.resolve(module + ".v")
  }

  def main(args: Array[String]): Unit = {
    require(args.length == 1, "provide the nested-owner artifact directory")
    val root = Paths.get(args(0)).toAbsolutePath.normalize()
    Files.createDirectories(root)
    def relative(path: Path): String = root.relativize(path).toString.replace('\\', '/')
    val cases = profiles.flatMap { profile =>
      val rtl = relative(candidate(root.resolve("candidate").resolve(profile), profile))
      for {
        point <- points(profile)
        mode <- Vector(0, 1, 2)
      } yield {
        val (width, count, rowCount) = point
        val rows = if (profile.endsWith("loop")) rowCount else 1
        val module = referenceModule(profile, width, count, rows, mode)
        val ref = relative(reference(root.resolve(module), profile, width, count, rows, mode))
        s"""    {"profile":"$profile","width":$width,"count":$count,"rows":$rows,"mode":$mode,"candidate_module":"${candidateModule(profile)}","candidate_rtl":"$rtl","reference_module":"$module","reference_rtl":"$ref"}"""
      }
    }
    val manifest = "{\n  \"scope\":\"balanced-nested-typed-owners\",\n" +
      "  \"candidate_default\":{\"width\":5,\"count\":1,\"rows\":1,\"mode\":0},\n" +
      "  \"configurations\":[\n" + cases.mkString(",\n") + "\n  ]\n}\n"
    Files.write(root.resolve("manifest.json"), manifest.getBytes(StandardCharsets.UTF_8))
  }
}
