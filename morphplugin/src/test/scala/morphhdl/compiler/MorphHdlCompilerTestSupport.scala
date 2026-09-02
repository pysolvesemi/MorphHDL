package morphhdl.compiler

import java.io.File
import java.nio.file.Files

import scala.collection.JavaConverters._
import scala.reflect.internal.util.BatchSourceFile
import scala.tools.nsc.{Global, Settings}
import scala.tools.nsc.reporters.StoreReporter

private[compiler] object MorphHdlCompilerTestSupport {
  final case class Diagnostic(message: String, line: Int, source: String)

  def compile(
      source: String,
      sourceName: String = "MorphHdlCompilerFixture.scala"
  ): Vector[Diagnostic] = {
    val output = Files.createTempDirectory("morphhdl-compiler-plugin-test")
    val settings = new Settings
    settings.usejavacp.value = true
    settings.outputDirs.setSingleOutput(output.toString)
    val pluginLocation =
      new File(
        classOf[MorphHdlPlugin].getProtectionDomain.getCodeSource.getLocation.toURI
      ).getAbsolutePath
    settings.plugin.value = List(pluginLocation)
    val reporter = new StoreReporter
    val compiler = new Global(settings, reporter)
    assert(
      compiler.plugins.exists(_.name == "morphhdl"),
      "nested compiler did not load MorphHDL plugin"
    )
    val run = new compiler.Run
    try {
      run.compileSources(List(new BatchSourceFile(sourceName, source)))
      reporter.infos.toVector
        .filter(_.severity == reporter.ERROR)
        .map { diagnostic =>
          val position = diagnostic.pos
          Diagnostic(
            diagnostic.msg,
            if (position != null && position.isDefined) position.line else -1,
            if (
              position != null && position.isDefined &&
              position.source != null && position.source.file != null
            ) position.source.file.name
            else ""
          )
        }
    } finally {
      val paths = Files.walk(output)
      try paths.iterator().asScala.toVector.reverse.foreach(path => Files.deleteIfExists(path))
      finally paths.close()
    }
  }

  def lineOf(source: String, marker: String): Int = {
    val index = source.indexOf(marker)
    require(index >= 0, s"missing source marker '$marker'")
    source.substring(0, index).count(_ == '\n') + 1
  }
}
