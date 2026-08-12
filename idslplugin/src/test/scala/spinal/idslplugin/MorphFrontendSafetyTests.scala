package spinal.idslplugin

import java.io.File
import java.nio.file.Files

import scala.reflect.internal.util.BatchSourceFile
import scala.tools.nsc.{Global, Settings}
import scala.tools.nsc.reporters.StoreReporter
import scala.collection.JavaConverters._

import org.scalatest.funsuite.AnyFunSuite

class MorphFrontendSafetyTests extends AnyFunSuite {
  private val symbolicDefinitions =
    """
      |package morphhdl.frontend {
      |  final class HdlInt
      |  final class GenIndex
      |}
      |""".stripMargin

  test("rejects reverse equality for statically typed MorphHDL integers") {
    val errors = compile(
      symbolicDefinitions +
        """
          |package comparison {
          |  object Rejected {
          |    type Alias = morphhdl.frontend.HdlInt
          |    val hdl = new morphhdl.frontend.HdlInt
          |    val alias: Alias = hdl
          |    val index = new morphhdl.frontend.GenIndex
          |    val a = BigInt(4) == hdl
          |    val b = BigDecimal(4) != hdl
          |    val c = "lane".equals(index)
          |    val d = new Object eq index
          |    val e = new Object ne index
          |    val f = 0 == alias
          |  }
          |}
          |""".stripMargin
    )

    assert(errors.size == 6, errors.mkString("\n"))
    assert(errors.forall(_.contains("MORPH-FRONTEND-SYMBOLIC-COMPARISON-UNSUPPORTED")))
  }

  test("does not intercept ordinary equality or symbolic receivers") {
    val errors = compile(
      symbolicDefinitions +
        """
          |package comparison {
          |  final case class Config(lanes: morphhdl.frontend.HdlInt)
          |  object Accepted {
          |    val hdl = new morphhdl.frontend.HdlInt
          |    val index = new morphhdl.frontend.GenIndex
          |    val a = BigInt(4) == BigInt(4)
          |    val b = hdl == BigInt(4)
          |    val c = index != 0
          |  }
          |}
          |""".stripMargin
    )

    assert(errors.isEmpty, errors.mkString("\n"))
  }

  private def compile(source: String): Vector[String] = {
    val output = Files.createTempDirectory("morph-frontend-plugin-test")
    val settings = new Settings
    settings.usejavacp.value = true
    settings.outputDirs.setSingleOutput(output.toString)
    val classLocation =
      new File(classOf[IdslPlugin].getProtectionDomain.getCodeSource.getLocation.toURI).getAbsolutePath
    val descriptorLocations = Option(classOf[IdslPlugin].getClassLoader.getResource("scalac-plugin.xml"))
      .filter(_.getProtocol == "file")
      .map(url => new File(url.toURI).getParentFile.getAbsolutePath)
      .toList
    settings.plugin.value = (classLocation :: descriptorLocations).distinct
    val reporter = new StoreReporter
    val compiler = new Global(settings, reporter)
    assert(compiler.plugins.exists(_.name == "idsl-plugin"), "nested compiler did not load idsl-plugin")
    val run = new compiler.Run
    try {
      run.compileSources(List(new BatchSourceFile("<morph-frontend-safety-test>", source)))
      reporter.infos.toVector.filter(_.severity == reporter.ERROR).map(_.msg)
    } finally {
      val paths = Files.walk(output)
      try paths.iterator().asScala.toVector.reverse.foreach(path => Files.deleteIfExists(path))
      finally paths.close()
    }
  }
}
