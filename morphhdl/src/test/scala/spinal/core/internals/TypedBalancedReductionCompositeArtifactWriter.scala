package spinal.core.internals

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import morphhdl.MorphVerilog
import morphhdl.frontend.HdlInt
import nativeapplication.BalancedCompositeNativeOracle
import spinal.core._

/** One singleton-default candidate is compared with separately elaborated native oracles. */
object TypedBalancedReductionCompositeArtifactWriter {
  val widthRoots = Vector("R_W", "G_W", "B_W", "KEY_W", "TAG_W", "COORD_W", "C_W", "U_W", "S_W", "BITS_W")
  val nativeCounts = Vector(1, 2, 3, 5, 8, 9, 16, 17)
  val candidateModule = "BalancedCompositePublication"

  private val candidateNativeShapes = scala.collection.mutable.LinkedHashMap.empty[String, String]

  /** Inspect the actual constructed candidate Data before normalization or emission. */
  private def nativeShape(value: Data): String = value match {
    case leaf: BaseType =>
      val kind = leaf match { case _: UInt => "UInt"; case _: SInt => "SInt"; case _: Bits => "Bits"; case _: Bool => "Bool" }
      val width = ParameterizedWidth.expressionOf(leaf).map(_.verilog).getOrElse(leaf.getBitsWidth.toString)
      s"""{"kind":${quoted(kind)},"width":${quoted(width)}}"""
    case vector: Vec[_] =>
      val count = ParameterizedVec.shapeOf(vector).map(_.depth.verilog).getOrElse(vector.vec.size.toString)
      s"""{"kind":"Vec","count":${quoted(count)},"element":${nativeShape(vector.vec.head)}}"""
    case bundle: Bundle =>
      val fields = bundle.elements.map { case (name, child) =>
        s"""{"name":${quoted(name)},"node":${nativeShape(child)}}"""
      }.mkString("[", ",", "]")
      s"""{"kind":"Bundle","fields":$fields}"""
    case other => throw new IllegalArgumentException("unsupported candidate native shape " + other.getClass.getName)
  }

  private def recordNativeShapes(module: String, inputs: Vector[(String, Data)], outputs: Vector[(String, Data)]): Unit = {
    def objectOf(values: Vector[(String, Data)]): String =
      values.map { case (name, value) => quoted(name) + ":" + nativeShape(value) }.mkString("{", ",", "}")
    candidateNativeShapes(module) = "{\"inputs\":" + objectOf(inputs) + ",\"outputs\":" + objectOf(outputs) + "}"
  }

  private def quoted(text: String): String = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
  private def relative(root: Path, file: Path): String = root.relativize(file).toString.replace('\\', '/')
  private def config(directory: Path, file: String): SpinalConfig = {
    Files.createDirectories(directory)
    val result = SpinalConfig(targetDirectory = directory.toString, headerWithDate = false, bitVectorWidthMax = 8192)
    result.netlistFileName = file
    result
  }

  def candidate(directory: Path, maximum: Int = 17): Path = {
    val w = widthRoots.map(name => HdlInt.param(name, 5, 1, 32))
    MorphVerilog(config(directory, candidateModule + ".v")) {
      val hardware = new BalancedCompositeHardware(w(0), w(1), w(2), w(3), w(4), w(5), w(6), w(7), w(8), w(9),
        HdlInt.param("COUNT", 1, 1, maximum), candidateModule)
      recordNativeShapes(candidateModule,
        Vector("rgbIn" -> hardware.rgbValues, "recordIn" -> hardware.recordValues,
          "complexIn" -> hardware.complexValues, "nestedIn" -> hardware.nestedValues),
        Vector("rgbMin" -> hardware.rgbMin, "rgbMax" -> hardware.rgbMax, "selected" -> hardware.selected,
          "complexResult" -> hardware.complexResult, "nestedResult" -> hardware.nestedResult,
          "pipelineResult" -> hardware.pipelineResult))
      hardware
    }
    val result = directory.resolve(candidateModule + ".v")
    require(Files.isRegularFile(result), "composite public-helper candidate was not emitted")
    result
  }

  val countedModule = "BalancedCompositeCountedPublication"
  private val countedWidthRoots = Vector("U_W", "S_W", "BITS_W", "TAG_W")
  private val countedDefaults = Vector("U_W" -> 5, "S_W" -> 5, "BITS_W" -> 5, "TAG_W" -> 5,
    "INNER" -> 1, "GRID_R" -> 1, "GRID_C" -> 1, "COUNT" -> 1)

  def countedCandidate(directory: Path, maximum: Int = 17): Path = {
    val w = countedWidthRoots.map(name => HdlInt.param(name, 5, 1, 32))
    MorphVerilog(config(directory, countedModule + ".v")) {
      val hardware = new BalancedCompositeCountedHardware(w(0), w(1), w(2), w(3),
        HdlInt.param("INNER", 1, 1, 3), HdlInt.param("GRID_R", 1, 1, 3),
        HdlInt.param("GRID_C", 1, 1, 3), HdlInt.param("COUNT", 1, 1, maximum))
      recordNativeShapes(countedModule, Vector("countedIn" -> hardware.records),
        Vector("countedResult" -> hardware.result))
      hardware
    }
    val result = directory.resolve(countedModule + ".v")
    require(Files.isRegularFile(result), "nested symbolic-count candidate was not emitted")
    result
  }

  private def countedEntries(root: Path, emitCandidate: Boolean = true): Vector[String] = {
    val candidate = if (emitCandidate) countedCandidate(root.resolve("counted-candidate"))
      else root.resolve("counted-candidate").resolve(countedModule + ".v")
    val shapes = Vector((1, 2, 3, Vector(1, 1, 1, 1)), (2, 3, 1, Vector(5, 7, 3, 2)),
      (3, 1, 2, Vector(8, 4, 9, 6)))
    val cases = shapes.flatMap { case (inner, rows, columns, widths) =>
      nativeCounts.map(count => (inner, rows, columns, count, widths))
    } :+ ((1, 1, 1, 1, Vector(5, 5, 5, 5)))
    cases.map { case (inner, rows, columns, count, w) =>
      val label = s"nested_i${inner}_r${rows}_c${columns}_n$count"
      val module = "BalancedCompositeCountedNative_" + label
      val directory = root.resolve(label).resolve("reference")
      var oracle: nativeapplication.BalancedCompositeCountedNativeOracle = null
      config(directory, module + ".v").generateVerilog {
        oracle = new nativeapplication.BalancedCompositeCountedNativeOracle(w(0), w(1), w(2), w(3),
          inner, rows, columns, count, module)
        oracle
      }
      val inputs = Vector("clk" -> oracle.clk, "reset" -> oracle.reset, "enable" -> oracle.enable,
        "countedIn" -> oracle.countedIn).map { case (name, value) => port(name, value) }
      var offset = 0
      val fields = Vector("key" -> oracle.key, "tag" -> oracle.tag, "samples" -> oracle.samples, "grid" -> oracle.grid)
      val physical = fields.map { case (path, value) =>
        val kind = value match { case _: UInt => "UInt"; case _ => "Bits" }
        val entry = s"""{"name":${quoted(value.getName())},"width":${value.getBitsWidth},"group":"countedResult","path":${quoted(path)},"offset":$offset,"kind":${quoted(kind)}}"""
        offset += value.getBitsWidth
        entry
      }
      val parameters = (countedWidthRoots.zip(w) ++ Vector("INNER" -> inner, "GRID_R" -> rows,
        "GRID_C" -> columns, "COUNT" -> count)).map { case (name, value) => quoted(name) + ":" + value }.mkString("{", ",", "}")
      s"""    {"label":${quoted(label)},"profile":"nested_counts","width":${w.head},"count":$count,"inner":$inner,"rows":$rows,"columns":$columns,"parameters":$parameters,"inputs":[${inputs.mkString(",")}],"outputs":[${physical.mkString(",")}],"input_leaf_shapes":{"countedIn":${leaves(oracle.words.head)}},"output_leaf_shapes":{"countedResult":[${physical.mkString(",")}]},"recursive_result_shapes":{"countedResult":${leaves(oracle.result)}},"candidate_module":${quoted(countedModule)},"candidate_rtl":${quoted(relative(root, candidate))},"reference_module":${quoted(module)},"reference_rtl":${quoted(relative(root, directory.resolve(module + ".v")))}}"""
    }
  }

  private def leaves(value: Data): String = {
    var offset = 0
    value.flattenLocalName.zip(value.flatten).map { case (path, leaf) =>
      val kind = leaf match { case _: UInt => "UInt"; case _: SInt => "SInt"; case _: Bits => "Bits"; case _: Bool => "Bool" }
      val width = leaf.getBitsWidth
      val result = s"""{"path":${quoted(path)},"width":$width,"kind":${quoted(kind)},"offset":$offset,"name":${quoted(leaf.getName())}}"""
      offset += width
      result
    }.mkString("[", ",", "]")
  }

  private def port(name: String, value: Data): String =
    s"""{"name":${quoted(name)},"width":${value.getBitsWidth}}"""

  def main(args: Array[String]): Unit = {
    if (args.length == 3 && args(0) == "--candidate-only") {
      val maximum = args(2).toInt
      require(maximum >= 1 && maximum <= 17, "diagnostic candidate maximum must be between 1 and 17")
      candidate(Paths.get(args(1)).toAbsolutePath.normalize(), maximum)
      return
    }
    if ((args.length == 2 || args.length == 3) && args(0) == "--counted-candidate-only") {
      val maximum = if (args.length == 3) args(2).toInt else 17
      require(maximum >= 1 && maximum <= 17, "diagnostic counted candidate maximum must be between 1 and 17")
      countedCandidate(Paths.get(args(1)).toAbsolutePath.normalize(), maximum)
      return
    }
    if (args.length == 4 && args(0) == "--oracle-only") {
      val width = args(2).toInt
      val count = args(3).toInt
      require(width >= 1 && width <= 32 && count >= 1 && count <= 17, "native diagnostic geometry is outside the candidate domain")
      val directory = Paths.get(args(1)).toAbsolutePath.normalize()
      val module = s"BalancedCompositeNative_base_w${width}_n$count"
      config(directory, module + ".v").generateVerilog {
        new BalancedCompositeNativeOracle(width, width, width, width, width, width, width, width, width, width, count, module)
      }
      return
    }
    val oraclesOnly = args.length == 2 && args(0) == "--oracles-only"
    require(args.length == 1 || oraclesOnly, "provide one artifact directory, or --oracles-only and one diagnostic directory")
    val root = Paths.get(if (oraclesOnly) args(1) else args(0)).toAbsolutePath.normalize()
    Files.createDirectories(root)
    val emitted = if (oraclesOnly) root.resolve("candidate").resolve(candidateModule + ".v")
      else candidate(root.resolve("candidate"))
    val cases = (for {
      width <- Vector(1, 5, 8, 32)
      count <- nativeCounts
    } yield (s"base_w${width}_n$count", "base", width, count, Vector.fill(10)(width))) ++
      (for {
        (widths, index) <- Vector(Vector(1, 2, 3, 4, 5, 6, 7, 8, 9, 10),
          Vector(10, 9, 8, 7, 6, 5, 4, 3, 2, 1)).zipWithIndex
        count <- Vector(3, 5, 9, 17)
      } yield (s"independent_${index}_n$count", "independent", widths.head, count, widths))
    val entries = cases.map { case (label, profile, width, count, w) =>
      val module = "BalancedCompositeNative_" + label
      val directory = root.resolve(label).resolve("reference")
      var oracle: BalancedCompositeNativeOracle = null
      config(directory, module + ".v").generateVerilog {
        oracle = new BalancedCompositeNativeOracle(w(0), w(1), w(2), w(3), w(4), w(5), w(6), w(7), w(8), w(9), count, module)
        oracle
      }
      val inputs = Vector("clk" -> oracle.clk, "reset" -> oracle.reset, "enable" -> oracle.enable,
        "rgbIn" -> oracle.rgbIn, "recordIn" -> oracle.recordIn,
        "complexIn" -> oracle.complexIn, "nestedIn" -> oracle.nestedIn).map { case (name, value) => port(name, value) }
      val nestedPhysicalFields: Vector[(String, Data)] = Vector("tag" -> oracle.nestedTagOut,
        "payload_unsigned" -> oracle.nestedUnsignedOut, "payload_signed" -> oracle.nestedSignedOut,
        "payload_bitsValue" -> oracle.nestedBitsOut, "payload_valid" -> oracle.nestedValidOut,
        "lanes" -> oracle.nestedLanesOut) ++
        (oracle.nestedGridOut: Data).flattenLocalName.zip((oracle.nestedGridOut: Data).flatten).map { case (path, value) => ("grid_" + path) -> value }
      var nestedOffset = 0
      val nestedPhysical = nestedPhysicalFields.map { case (path, leaf) =>
        val kind = leaf match { case _: UInt => "UInt"; case _: SInt => "SInt"; case _: Bits => "Bits"; case _: Bool => "Bool" }
        val entry = s"""{"name":${quoted(leaf.getName())},"width":${leaf.getBitsWidth},"group":"nestedResult","path":${quoted(path)},"offset":$nestedOffset,"kind":${quoted(kind)}}"""
        nestedOffset += leaf.getBitsWidth
        entry
      }
      val outputs = Vector("rgbMin" -> oracle.rgbMin, "rgbMax" -> oracle.rgbMax,
        "selected" -> oracle.selected, "complexResult" -> oracle.complexResult,
        "pipelineResult" -> oracle.pipelineResult).flatMap { case (group, value) =>
        var offset = 0
        value.flattenLocalName.zip(value.flatten).map { case (path, leaf) =>
          val kind = leaf match { case _: UInt => "UInt"; case _: SInt => "SInt"; case _: Bits => "Bits"; case _: Bool => "Bool" }
          val result = s"""{"name":${quoted(leaf.getName())},"width":${leaf.getBitsWidth},"group":${quoted(group)},"path":${quoted(path)},"offset":$offset,"kind":${quoted(kind)}}"""
          offset += leaf.getBitsWidth
          result
        }
      } ++ nestedPhysical
      val shapes = (Vector("rgbMin" -> oracle.rgbMin, "rgbMax" -> oracle.rgbMax,
        "selected" -> oracle.selected, "complexResult" -> oracle.complexResult,
        "pipelineResult" -> oracle.pipelineResult)
        .map { case (name, value) => quoted(name) + ":" + leaves(value) } :+
        (quoted("nestedResult") + ":" + nestedPhysical.mkString("[", ",", "]"))).mkString("{", ",", "}")
      val recursiveShapes = "{" + quoted("nestedResult") + ":" + leaves(oracle.nestedResult) + "}"
      val inputShapes = Vector("rgbIn" -> oracle.rgbValues.head, "recordIn" -> oracle.recordValues.head,
        "complexIn" -> oracle.complexValues.head, "nestedIn" -> oracle.nestedValues.head)
        .map { case (name, value) => quoted(name) + ":" + leaves(value) }.mkString("{", ",", "}")
      val parameters = (widthRoots.zip(w) :+ ("COUNT" -> count)).map { case (name, value) => quoted(name) + ":" + value }.mkString("{", ",", "}")
      s"""    {"label":${quoted(label)},"profile":${quoted(profile)},"width":$width,"count":$count,"parameters":$parameters,"inputs":[${inputs.mkString(",")}],"outputs":[${outputs.mkString(",")}],"input_leaf_shapes":$inputShapes,"output_leaf_shapes":$shapes,"recursive_result_shapes":$recursiveShapes,"candidate_module":${quoted(candidateModule)},"candidate_rtl":${quoted(relative(root, emitted))},"reference_module":${quoted(module)},"reference_rtl":${quoted(relative(root, directory.resolve(module + ".v")))}}"""
    }
    val nested = countedEntries(root, emitCandidate = !oraclesOnly)
    if (oraclesOnly) {
      val summary = s"""{"scope":"native-oracle-only","configurations":${entries.size + nested.size}}
"""
      Files.write(root.resolve("native-oracle-only.json"), summary.getBytes(StandardCharsets.UTF_8))
      return
    }
    val countedDefaultJson = countedDefaults.map { case (name, value) => quoted(name) + ":" + value }.mkString("{", ",", "}")
    val defaults = (widthRoots.map(_ -> 5) :+ ("COUNT" -> 1)).map { case (name, value) => quoted(name) + ":" + value }.mkString("{", ",", "}")
    val manifest = "{\n  \"scope\":\"parameterized-native-composite-balanced-reduction\",\n" +
      "  \"candidate_default\":" + defaults + ",\n  \"width_roots\":" + widthRoots.map(quoted).mkString("[", ",", "]") + ",\n" +
      "  \"candidate_defaults\":{" + quoted(candidateModule) + ":" + defaults + "," + quoted(countedModule) + ":" + countedDefaultJson + "},\n" +
      "  \"candidate_native_shapes\":" + candidateNativeShapes.map { case (module, shape) => quoted(module) + ":" + shape }.mkString("{", ",", "}") + ",\n" +
      "  \"packing\":\"native flatten order, first leaf least significant; each input element occupies one complete record\",\n" +
      "  \"configurations\":[\n" + (entries ++ nested).mkString(",\n") + "\n  ]\n}\n"
    Files.write(root.resolve("manifest.json"), manifest.getBytes(StandardCharsets.UTF_8))
  }
}
