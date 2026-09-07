package nativeapplication

import java.nio.file.{Files, Path, Paths}
import morphhdl.{MorphSignedCasts, MorphVerilog}
import morphhdl.frontend.{HdlBool, HdlInt}
import spinal.core._

/** Fresh neutral configs exercise the public default. The paired explicit
  * path and all native reference RTL are independently elaborated; no candidate
  * is used to reconstruct its reference arithmetic.
  */
object DefaultSignedVerilogArtifactWriter {
  private def config(path: Path): SpinalConfig = {
    Files.createDirectories(path.getParent)
    val value = SpinalConfig(targetDirectory = path.getParent.toString)
    value.netlistFileName = path.getFileName.toString
    value
  }

  def main(arguments: Array[String]): Unit = {
    require(arguments.length == 1, "expected output directory")
    val root = Paths.get(arguments(0)).toAbsolutePath.normalize()
    def width = HdlInt.param("WIDTH", default = 8, min = 1, max = 32)
    def target = HdlInt.param("TARGET", default = 5, min = 1, max = 32)
    def depth = HdlInt.param("DEPTH", default = 3, min = 1, max = 8)
    def pair(relative: String)(component: => Component): Unit = {
      MorphVerilog(config(root.resolve("default").resolve(relative)))(component)
      MorphVerilog(MorphSignedCasts.enable(config(root.resolve("explicit").resolve(relative))))(component)
    }
    import SignednessBoundaryFixture._
    pair("boundaries/scalars/candidate.v")(new Scalars(width, target))
    pair("boundaries/bundles/candidate.v")(new Bundles(width))
    pair("boundaries/vectors/candidate.v")(new Vectors(width, depth))
    pair("boundaries/vec-hierarchy/candidate.v")(new VecHierarchy(width, depth))
    pair("boundaries/hierarchy/candidate.v")(new Hierarchy(width, HdlBool.param("ENABLED", default = true)))
    pair("boundaries/channels/candidate.v")(new Channels(width))
    pair("pure/pure-true.v")(new PureSIntCastFixture.Top(width))
    pair("pure/boundaries.v")(new PureSIntCastFixture.Boundaries(width))
    pair("pure/baseline-clean.v")(SIntSignedVerilogBaselineFixture.parameterized())
    pair("pure/declaration-fixture-clean.v")(new SIntSignedDeclarationsFixture.Top(width))
  }
}
