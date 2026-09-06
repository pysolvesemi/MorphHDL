package nativeapplication

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.collection.mutable
import morphhdl.{MorphSignedCasts, MorphSignedDeclarations, MorphVerilog}
import morphhdl.frontend.HdlInt
import spinal.core._

/** Compatibility and session-isolation closure for Increment 60f.
  *
  * Every invocation elaborates an existing ordinary component source afresh.
  * Native output is compared before header normalization, so enabling a Morph
  * option cannot change any native Verilog or VHDL byte. The persisted files
  * remove only the native generator header for cross-JVM/compiler replay.
  * The independent semantic-reference matrices remain owned by the 60a/60d/60e
  * writers; this writer exercises publication modes and their isolation.
  */
object SignednessCompatibilityArtifactWriter {
  val widths: Vector[Int] = Vector(1, 5, 8, 32)
  val kinds: Vector[String] = Vector("pure", "declarations", "bundles")
  val nativeModes: Vector[String] = Vector("before", "declarations", "cleanup", "after")
  val morphModes: Vector[String] = Vector(
    "disabled-before", "declarations", "cleanup", "declarations-after", "disabled-explicit", "disabled-after")

  // Anchor the complete generated header; never normalize RTL, names, casts,
  // whitespace, comments in the body, or compiler-dependent expression text.
  private val ordinaryHeader =
    """\A(//|--) Generator :[^\n]*\n\1 Component :[^\n]*\n(?:\1 Git hash  :[^\n]*\n)?\n""".r

  def canonicalHeader(rtl: String): String = ordinaryHeader.replaceFirstIn(rtl, "")

  def expectedFiles: Vector[String] =
    (for (kind <- kinds; width <- widths; mode <- nativeModes; extension <- Vector("v", "vhd"))
      yield s"$kind/native-$width-$mode.$extension") ++
      (for (kind <- kinds; mode <- morphModes) yield s"$kind/morph-$mode.v")

  private def parameter: HdlInt = HdlInt.param("WIDTH", default = 8, min = 1, max = 32)

  private def component(kind: String, width: HdlInt): Component = kind match {
    case "pure" => new PureSIntCastFixture.Top(width)
    case "declarations" => new SIntSignedDeclarationsFixture.Top(width)
    case "bundles" => new SignednessBoundaryFixture.Bundles(width)
    case _ => throw new IllegalArgumentException("unknown compatibility fixture: " + kind)
  }

  private def read(path: Path): String = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  /** root is the compatibility output directory supplied by the caller. */
  def generateKind(root: Path, kind: String): Unit = {
    require(kinds.contains(kind), "unknown compatibility fixture: " + kind)
    val out = root.resolve(kind)
    Files.createDirectories(out)
    val nativeBefore = mutable.Map.empty[(Int, String), String]
    val morphOutput = mutable.Map.empty[String, String]

    def native(mode: String, option: SpinalConfig => SpinalConfig): Unit = {
      for (width <- widths; extension <- Vector("v", "vhd")) {
        val path = out.resolve(s"native-$width-$mode.$extension")
        val config = option(SIntSignedDeclarationsArtifactWriter.config(path))
        if (extension == "v") SpinalVerilog(config)(component(kind, HdlInt.literal(width)))
        else SpinalVhdl(config) {
          // The ordinary VHDL backend rejects Top's readFirst memory policy.
          // Use the existing scalar/unsigned declaration source on this leg;
          // pure and bundles retain their complete source in both languages.
          if (kind == "declarations") new SIntSignedDeclarationsFixture.Direct(HdlInt.literal(width))
          else component(kind, HdlInt.literal(width))
        }
        val raw = read(path)
        val key = (width, extension)
        if (mode == "before") nativeBefore(key) = raw
        else require(raw == nativeBefore(key),
          s"$kind WIDTH=$width native $extension changed in $mode mode")
        Files.write(path, canonicalHeader(raw).getBytes(StandardCharsets.UTF_8))
      }
    }

    def morph(mode: String, option: SpinalConfig => SpinalConfig): Unit = {
      val path = out.resolve(s"morph-$mode.v")
      MorphVerilog(option(SIntSignedDeclarationsArtifactWriter.config(path)))(component(kind, parameter))
      morphOutput(mode) = read(path)
    }

    // Alternate actual enabled Morph publication with both native emitters.
    // The latter also receive each option explicitly, and finally no option.
    native("before", identity)
    morph("disabled-before", identity)
    morph("declarations", MorphSignedDeclarations.enable)
    native("declarations", MorphSignedDeclarations.enable)
    morph("cleanup", MorphSignedCasts.enable)
    native("cleanup", MorphSignedCasts.enable)
    morph("declarations-after", config => MorphSignedCasts.disable(MorphSignedCasts.enable(config)))
    morph("disabled-explicit", config => MorphSignedDeclarations.disable(MorphSignedCasts.enable(config)))
    morph("disabled-after", identity)
    native("after", identity)

    require(morphOutput("disabled-before") == morphOutput("disabled-explicit"),
      kind + " explicitly disabled Morph output changed")
    require(morphOutput("disabled-before") == morphOutput("disabled-after"),
      kind + " signed mode leaked into later default Morph publication")
    require(morphOutput("declarations") == morphOutput("declarations-after"),
      kind + " disabling cleanup failed to restore declaration-only publication")
  }

  def main(arguments: Array[String]): Unit = {
    require(arguments.length == 1, "expected one output directory")
    val root = Paths.get(arguments(0)).toAbsolutePath.normalize()
    kinds.foreach(generateKind(root, _))
    require(expectedFiles.size == 114, "compatibility artifact inventory changed")
    println(s"Increment 60f compatibility: ${expectedFiles.size} RTL artifacts, native byte compatibility and mode isolation passed")
  }
}
