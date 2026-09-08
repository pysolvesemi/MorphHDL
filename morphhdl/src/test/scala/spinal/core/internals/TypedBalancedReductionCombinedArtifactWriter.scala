package spinal.core.internals

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import morphhdl.{MorphNamedFieldVectors, MorphSignedCasts, MorphSignedDeclarations, MorphVerilog}
import morphhdl.frontend.HdlInt
import nativeapplication.BalancedCombinedNativeOracle
import spinal.core._

/** The first 59i qualification slice. This manifest deliberately does not claim
  * widening/captured composite or nested aggregate-alias support.
  */
object TypedBalancedReductionCombinedArtifactWriter {
  val module = "BalancedCombinedScopedRecords"
  val widths = Vector(1, 5, 8, 32)
  val counts = Vector(1, 2, 3, 5, 8, 9, 16, 17)
  val layouts = Vector("packed", "fields")
  val signedModes = Vector("legacy", "declarations", "casts")
  val defaults = Vector(1, 5)

  def config(directory: Path, file: String): SpinalConfig = {
    Files.createDirectories(directory)
    val result = SpinalConfig(targetDirectory = directory.toString, headerWithDate = false,
      bitVectorWidthMax = 65536)
    result.netlistFileName = file
    result
  }

  def candidate(directory: Path, layout: String, signedMode: String,
      defaultCount: Int = 1, maximum: Int = 17): Path = {
    require(layouts.contains(layout) && signedModes.contains(signedMode))
    val name = module + "_" + layout + "_" + signedMode + "_d" + defaultCount
    val base = config(directory, name + ".v")
    val shaped = if (layout == "fields") MorphNamedFieldVectors.enable(base) else base
    val configured = signedMode match {
      case "legacy" => shaped
      case "declarations" => MorphSignedDeclarations.enable(shaped)
      case "casts" => MorphSignedCasts.enable(shaped)
    }
    MorphVerilog(configured) {
      new BalancedCombinedScopedRecords(HdlInt.param("WIDTH", 5, 1, 32),
        HdlInt.param("TAG_WIDTH", 3, 1, 32), HdlInt.param("COORD_WIDTH", 7, 1, 32),
        HdlInt.param("COUNT", defaultCount, 1, maximum), HdlInt.param("MODE", 0, 0, 1), name)
    }
    val path = directory.resolve(name + ".v")
    require(Files.isRegularFile(path), "combined candidate was not emitted")
    path
  }

  private def quote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
  private def relative(root: Path, file: Path): String = root.relativize(file).toString.replace('\\', '/')

  def main(args: Array[String]): Unit = {
    require(args.length == 1, "usage: TypedBalancedReductionCombinedArtifactWriter OUTPUT")
    val root = Paths.get(args(0)).toAbsolutePath.normalize()
    Files.createDirectories(root)
    val candidates = for (layout <- layouts; signed <- signedModes; default <- defaults) yield {
      val path = candidate(root.resolve(s"candidate/$layout-$signed-n$default"), layout, signed, default)
      s"""{"layout":${quote(layout)},"signed_mode":${quote(signed)},"default_count":$default,"module":${quote(path.getFileName.toString.stripSuffix(".v"))},"file":${quote(relative(root, path))}}"""
    }
    // Full inherited WIDTH/COUNT product plus independent unequal-field overrides.
    val shapes = ((for (width <- widths; count <- counts; mode <- 0 to 1)
      yield (width, width, width, count, mode)) ++
      (for ((width, tag, coord) <- Vector((1, 3, 7), (5, 1, 8), (8, 32, 1), (32, 5, 7));
            count <- Vector(1, 3, 5, 17); mode <- 0 to 1)
        yield (width, tag, coord, count, mode))).distinct
    val cases = shapes.map { case (width, tag, coord, count, mode) =>
      val id = s"w${width}_t${tag}_c${coord}_n${count}_m$mode"
      val name = "NativeCombined_" + id
      val directory = root.resolve("native/" + id)
      SpinalVerilog(config(directory, name + ".v")) {
        new BalancedCombinedNativeOracle(width, tag, coord, count, mode, name)
      }
      val file = directory.resolve(name + ".v")
      require(Files.isRegularFile(file), "independent native reference was not emitted")
      s"""{"id":${quote(id)},"width":$width,"tag_width":$tag,"coord_width":$coord,"count":$count,"mode":$mode,"module":${quote(name)},"file":${quote(relative(root, file))}}"""
    }
    val manifest = s"""{"schema":1,"scope":"59i-scoped-fixed-shape-record-join","candidates":[${candidates.mkString(",")}],"cases":[${cases.mkString(",")}]}
"""
    Files.write(root.resolve("manifest.json"), manifest.getBytes(StandardCharsets.UTF_8))
  }
}
