package spinal.core.internals

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import morphhdl.{MorphNamedFieldVectors, MorphSignedCasts, MorphSignedDeclarations, MorphVerilog}
import morphhdl.frontend.HdlInt
import nativeapplication.BalancedCombinedNativeOracle
import spinal.core._

/** Exercise the real generated-child formal bindings against the independent
  * native oracle, over the complete existing combined-record matrix.
  */
object TypedBalancedReductionGeneratedChildArtifactWriter {
  private val matrix = TypedBalancedReductionCombinedArtifactWriter
  private def quote(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
  private def relative(root: Path, file: Path): String =
    root.relativize(file).toString.replace('\\', '/')

  def main(args: Array[String]): Unit = {
    require(args.length == 1, "usage: TypedBalancedReductionGeneratedChildArtifactWriter OUTPUT")
    val root = Paths.get(args(0)).toAbsolutePath.normalize()
    Files.createDirectories(root)
    val candidates = for (layout <- matrix.layouts; signed <- matrix.signedModes;
        default <- matrix.defaults) yield {
      val suffix = layout + "_" + signed + "_d" + default
      val top = "BalancedCombinedGeneratedChildTop_" + suffix
      val child = "BalancedCombinedGeneratedChild_" + suffix
      val directory = root.resolve(s"candidate/$layout-$signed-n$default")
      val base = matrix.config(directory, top + ".v")
      val shaped = if (layout == "fields") MorphNamedFieldVectors.enable(base) else base
      val configured = signed match {
        case "legacy" => MorphSignedDeclarations.disable(MorphSignedCasts.disable(shaped))
        case "declarations" => MorphSignedDeclarations.enable(shaped)
        case "casts" => MorphSignedCasts.enable(shaped)
      }
      MorphVerilog(configured) {
        new BalancedCombinedGeneratedChildTop(HdlInt.param("WIDTH", 5, 1, 32),
          HdlInt.param("TAG_WIDTH", 3, 1, 32), HdlInt.param("COORD_WIDTH", 7, 1, 32),
          HdlInt.param("COUNT", default, 1, 17), HdlInt.param("MODE", 0, 0, 1), top, child)
      }
      val file = directory.resolve(top + ".v")
      require(Files.isRegularFile(file), "generated-child candidate was not emitted")
      s"""{"layout":${quote(layout)},"signed_mode":${quote(signed)},"default_count":$default,"module":${quote(top)},"file":${quote(relative(root, file))}}"""
    }
    val shapes = ((for (width <- matrix.widths; count <- matrix.counts; mode <- 0 to 1)
      yield (width, width, width, count, mode)) ++
      (for ((width, tag, coord) <- Vector((1, 3, 7), (5, 1, 8), (8, 32, 1), (32, 5, 7));
            count <- Vector(1, 3, 5, 17); mode <- 0 to 1)
        yield (width, tag, coord, count, mode))).distinct
    val cases = shapes.map { case (width, tag, coord, count, mode) =>
      val id = s"w${width}_t${tag}_c${coord}_n${count}_m$mode"
      val name = "NativeCombined_" + id
      val directory = root.resolve("native/" + id)
      SpinalVerilog(matrix.config(directory, name + ".v")) {
        new BalancedCombinedNativeOracle(width, tag, coord, count, mode, name)
      }
      val file = directory.resolve(name + ".v")
      require(Files.isRegularFile(file), "independent native reference was not emitted")
      s"""{"id":${quote(id)},"width":$width,"tag_width":$tag,"coord_width":$coord,"count":$count,"mode":$mode,"module":${quote(name)},"file":${quote(relative(root, file))}}"""
    }
    val manifest = s"""{"schema":1,"scope":"59i-generated-child-record-join","candidates":[${candidates.mkString(",")}],"cases":[${cases.mkString(",")}]}
"""
    Files.write(root.resolve("manifest.json"), manifest.getBytes(StandardCharsets.UTF_8))
  }
}
