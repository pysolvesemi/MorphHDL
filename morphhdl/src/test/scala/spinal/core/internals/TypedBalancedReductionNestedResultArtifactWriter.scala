package spinal.core.internals

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import morphhdl.{MorphNamedFieldVectors, MorphSignedCasts, MorphVerilog}
import morphhdl.frontend.HdlInt
import nativeapplication.BalancedNestedResultNativeOracle
import spinal.core._

object TypedBalancedReductionNestedResultArtifactWriter {
  val profiles = Vector(("packed", "legacy", 1), ("fields", "legacy", 1),
    ("packed", "casts", 5), ("fields", "casts", 5))
  val widths = Vector(1, 5, 8, 32)
  val counts = Vector(1, 2, 3, 5, 8, 9, 16, 17)
  def config(path: Path, name: String): SpinalConfig = {
    Files.createDirectories(path)
    val result = SpinalConfig(targetDirectory = path.toString,
      headerWithDate = false, bitVectorWidthMax = 65536)
    result.netlistFileName = name + ".v"
    result
  }
  def candidate(path: Path, layout: String, signed: String, defaultCount: Int): Path = {
    require(profiles.contains((layout, signed, defaultCount)))
    val name = s"BalancedNestedResult_${layout}_${signed}_d$defaultCount"
    val base = config(path, name)
    val shaped = if (layout == "fields") MorphNamedFieldVectors.enable(base) else base
    val configured = if (signed == "casts") MorphSignedCasts.enable(shaped) else shaped
    MorphVerilog(configured) {
      new BalancedCombinedNestedResult(HdlInt.param("U_W", 5, 1, 32),
        HdlInt.param("S_W", 3, 1, 32), HdlInt.param("TAG_W", 7, 1, 32),
        HdlInt.param("INNER", if (defaultCount == 1) 1 else 3, 1, 3),
        HdlInt.param("COUNT", defaultCount, 1, 17), HdlInt.param("MODE", 0, 0, 1), name)
    }
    val file = path.resolve(name + ".v")
    require(Files.isRegularFile(file))
    file
  }
  private def quote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
  private def relative(root: Path, file: Path): String = root.relativize(file).toString.replace('\\', '/')
  def main(args: Array[String]): Unit = {
    require(args.length == 1)
    val root = Paths.get(args(0)).toAbsolutePath.normalize()
    Files.createDirectories(root)
    val candidates = profiles.map { case (layout, signed, defaultCount) =>
      val file = candidate(root.resolve(s"candidate/$layout-$signed"), layout, signed, defaultCount)
      s"""{"layout":${quote(layout)},"signed_mode":${quote(signed)},"default_count":$defaultCount,"module":${quote(file.getFileName.toString.stripSuffix(".v"))},"file":${quote(relative(root, file))}}"""
    }
    val shapes = ((for (w <- widths; n <- counts; m <- 0 to 1) yield (w, w, w, 2, n, m)) ++
      (for ((u, s, t) <- Vector((1, 3, 7), (5, 1, 8), (8, 32, 1), (32, 5, 7));
            i <- Vector(1, 3); n <- Vector(1, 5); m <- 0 to 1) yield (u, s, t, i, n, m))).distinct
    val cases = shapes.map { case (u, s, t, i, n, m) =>
      val id = s"u${u}_s${s}_t${t}_i${i}_n${n}_m$m"
      val name = "NativeNestedResult_" + id
      val directory = root.resolve("native/" + id)
      SpinalVerilog(config(directory, name).copy(headerWithRepoHash = false)) {
        new BalancedNestedResultNativeOracle(u, s, t, i, n, m, name)
      }
      val file = directory.resolve(name + ".v")
      require(Files.isRegularFile(file))
      s"""{"id":${quote(id)},"uw":$u,"sw":$s,"tw":$t,"inner":$i,"count":$n,"mode":$m,"module":${quote(name)},"file":${quote(relative(root, file))}}"""
    }
    Files.write(root.resolve("manifest.json"),
      (s"""{"schema":1,"scope":"59i-scoped-nested-Vec-results","candidates":[${candidates.mkString(",")}],"cases":[${cases.mkString(",")}]}""" + "\n").getBytes(StandardCharsets.UTF_8))
  }
}
