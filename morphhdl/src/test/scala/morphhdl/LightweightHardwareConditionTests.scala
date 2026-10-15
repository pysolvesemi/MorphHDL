package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import scala.collection.JavaConverters._
import scala.reflect.internal.util.BatchSourceFile
import scala.tools.nsc.{Global, Settings}
import scala.tools.nsc.reporters.StoreReporter

/** Compile the actual public core API, without substituting a stub Bool type. */
class LightweightHardwareConditionTests extends AnyFunSuite {
  private def errors(body: String): Vector[String] = {
    val output = Files.createTempDirectory("hardware-condition-compile-")
    val settings = new Settings
    settings.usejavacp.value = true
    settings.outputDirs.setSingleOutput(output.toString)
    val reporter = new StoreReporter
    val compiler = new Global(settings, reporter)
    try {
      new compiler.Run().compileSources(List(new BatchSourceFile("HardwareCondition.scala", s"""
        import spinal.core._
        object HardwareCondition {
          def flag(value: Bool): Bool = value
          def test(a: Bool, b: Bool, x: UInt, y: UInt): Unit = { $body }
        }
      """)))
      reporter.infos.toVector.filter(_.severity == reporter.ERROR).map(_.msg)
    } finally {
      val files = Files.walk(output)
      try files.iterator().asScala.toVector.reverse.foreach(Files.deleteIfExists(_))
      finally files.close()
    }
  }

  for (condition <- Seq("a", "a && b", "!a", "flag(a)", "x === y"))
    test(s"hardware condition $condition directs Scala if to when") {
      val diagnostics = errors(s"if($condition) () else ()")
      assert(diagnostics.exists(_.contains("SPINAL-HARDWARE-BOOL-AS-SCALA-BOOLEAN")), diagnostics.mkString("\n"))
      assert(diagnostics.exists(_.contains("use when")), diagnostics.mkString("\n"))
    }

  test("ordinary Scala Boolean control remains ordinary") {
    assert(errors("val enabled = true; if(enabled) () else ()").isEmpty)
  }

  test("native when accepts bare and composed hardware Bool without adaptation") {
    val diagnostics = errors("val result = Bool(); when(a && flag(b)) { result := True } otherwise { result := False }")
    assert(diagnostics.isEmpty, diagnostics.mkString("\n"))
  }
}
