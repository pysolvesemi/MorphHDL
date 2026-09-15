package morphhdl

import java.nio.file.{Files, Paths}

import nativeapplication.TypedBlackBoxGenericBindingFixture
import spinal.core.{Component, SpinalConfig}

/** Emits the representative application-shaped Increment 59 artifact for
  * strict Verilog-2001 lint and synthesis tooling.
  */
object TypedBlackBoxGenericArtifactGenerator {
  def main(arguments: Array[String]): Unit = {
    require(
      arguments.length == 2 && arguments(0) == "parameterized",
      "usage: TypedBlackBoxGenericArtifactGenerator parameterized <output.v>"
    )
    val output = Paths.get(arguments(1)).toAbsolutePath.normalize()
    val parent = Option(output.getParent).getOrElse(Paths.get(".").toAbsolutePath)
    Files.createDirectories(parent)

    val fixture = TypedBlackBoxGenericBindingFixture
    val candidates = fixture.getClass.getMethods.toVector
      .filter(method =>
        method.getParameterTypes.isEmpty &&
          classOf[Component].isAssignableFrom(method.getReturnType)
      )
      .sortBy(_.getName)
    val factory = candidates
      .find(_.getName.toLowerCase.contains("parameter"))
      .orElse(candidates.find(_.getName.toLowerCase.contains("top")))
      .orElse(candidates.headOption)
      .getOrElse {
        throw new IllegalStateException(
          "TypedBlackBoxGenericBindingFixture exposes no zero-argument Component factory"
        )
      }

    val config = SpinalConfig(targetDirectory = parent.toString)
    config.netlistFileName = output.getFileName.toString
    MorphVerilog(config) {
      factory.invoke(fixture).asInstanceOf[Component]
    }
  }
}
