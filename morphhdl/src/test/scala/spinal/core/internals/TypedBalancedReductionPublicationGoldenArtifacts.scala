package spinal.core.internals

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import morphhdl.{MorphNamedFieldVectors, MorphSignedCasts, MorphSignedDeclarations, MorphVerilog}
import morphhdl.frontend.HdlInt
import nativeapplication.BalancedCombinedNativeOracle
import spinal.core._

/** Small public ABI/default corpus. Qualification compares independently
  * emitted bytes against a separately reviewed contract; this writer never
  * updates that contract or changes the generated Verilog after publication.
  */
object TypedBalancedReductionPublicationGoldenArtifacts {
  private final case class Profile(id: String, kind: String, layout: String,
      signedMode: String, count: Int = 5)
  private val profiles = Vector(
    Profile("default", "parameterized", "default", "default"),
    Profile("packed", "parameterized", "packed", "default"),
    Profile("fields", "parameterized", "fields", "default"),
    Profile("fields-legacy", "parameterized", "fields", "legacy"),
    Profile("fields-declarations", "parameterized", "fields", "declarations"),
    Profile("fields-casts", "parameterized", "fields", "casts"),
    Profile("child-packed", "child", "packed", "default"),
    Profile("child-fields", "child", "fields", "default"),
    Profile("concrete-singleton", "concrete", "native", "native", 1),
    Profile("concrete-unequal", "concrete", "native", "native"))

  private def quote(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  private def configured(base: SpinalConfig, profile: Profile): SpinalConfig = {
    val layout = profile.layout match {
      case "fields" => MorphNamedFieldVectors.enable(base)
      case "packed" => MorphNamedFieldVectors.disable(base)
      case _ => base
    }
    profile.signedMode match {
      case "legacy" => MorphSignedDeclarations.disable(MorphSignedCasts.disable(layout))
      case "declarations" => MorphSignedDeclarations.enable(MorphSignedCasts.disable(layout))
      case "casts" => MorphSignedCasts.enable(layout)
      case _ => layout
    }
  }

  def main(args: Array[String]): Unit = {
    require(args.length == 1, "usage: TypedBalancedReductionPublicationGoldenArtifacts OUTPUT")
    val root = Paths.get(args(0)).toAbsolutePath.normalize()
    Files.createDirectories(root)
    val records = profiles.map { profile =>
      val module = profile.kind match {
        case "child" => "PublicationGoldenChildTop"
        case "concrete" => "PublicationGoldenNative"
        case _ => "PublicationGoldenRecords"
      }
      val directory = root.resolve(profile.id)
      // The native generator records its source provenance in its fixed
      // leading comment header. Retain it in the original RTL and evidence.
      // MorphVerilog's direct emitter requires the default header settings.
      val base = TypedBalancedReductionCombinedArtifactWriter.config(directory, module + ".v")
      val config = configured(base, profile)
      if (profile.kind == "concrete") {
        val width = if (profile.count == 1) 5 else 1
        val mode = if (profile.count == 1) 0 else 1
        SpinalVerilog(config) {
          new BalancedCombinedNativeOracle(width, 3, 7, profile.count, mode, module)
        }
      } else if (profile.kind == "child") {
        MorphVerilog(config) {
          new BalancedCombinedGeneratedChildTop(HdlInt.param("WIDTH", 5, 1, 32),
            HdlInt.param("TAG_WIDTH", 3, 1, 32), HdlInt.param("COORD_WIDTH", 7, 1, 32),
            HdlInt.param("COUNT", profile.count, 1, 17), HdlInt.param("MODE", 0, 0, 1),
            module, "PublicationGoldenChild")
        }
      } else {
        MorphVerilog(config) {
          new BalancedCombinedScopedRecords(HdlInt.param("WIDTH", 5, 1, 32),
            HdlInt.param("TAG_WIDTH", 3, 1, 32), HdlInt.param("COORD_WIDTH", 7, 1, 32),
            HdlInt.param("COUNT", profile.count, 1, 17), HdlInt.param("MODE", 0, 0, 1), module)
        }
      }
      val file = directory.resolve(module + ".v")
      require(Files.isRegularFile(file), "missing publication golden artifact: " + profile.id)
      val relative = root.relativize(file).toString.replace('\\', '/')
      s"""{"profile":${quote(profile.id)},"file":${quote(relative)},"module":${quote(module)},"kind":${quote(profile.kind)},"layout":${quote(profile.layout)},"signed_mode":${quote(profile.signedMode)},"default_count":${profile.count}}"""
    }
    val manifest = s"""{"schema":1,"scope":"59i-publication-abi-defaults","profiles":[${records.mkString(",")}]}
"""
    Files.write(root.resolve("manifest.json"), manifest.getBytes(StandardCharsets.UTF_8))
  }
}
