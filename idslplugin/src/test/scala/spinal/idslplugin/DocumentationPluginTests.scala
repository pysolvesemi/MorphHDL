package spinal.idslplugin

import java.nio.file.{Files, Path, Paths}
import java.net.URLClassLoader
import org.scalatest.funsuite.AnyFunSuite
import scala.tools.nsc.{Global, Settings}
import scala.tools.nsc.reporters.StoreReporter

class DocumentationPluginTests extends AnyFunSuite {
  private val core = """package spinal.core {
    class doc(val text: String) extends scala.annotation.StaticAnnotation
    class Data
    class Component
    object RtlDocumentation {
      var notes = Vector.empty[String]
      def attach[T](value: T, text: String, origin: String, location: String, definition: Boolean): T = {
        notes :+= text; value
      }
    }
  }
  """
  private def compile(source: String, directory: Path = Files.createTempDirectory("doc-plugin-"),
      extra: String = "", ranges: Boolean = true): (Path, Vector[String]) = {
    val settings = new Settings
    settings.Yrangepos.value = ranges
    settings.usejavacp.value = true
    settings.classpath.value = System.getProperty("java.class.path") + java.io.File.pathSeparator + extra
    settings.outputDirs.setSingleOutput(directory.toString)
    val version = scala.util.Properties.versionNumberString.split('.').take(2).mkString(".")
    val jar = Paths.get("idslplugin", "target", "scala-" + version, s"spinalhdl-idsl-plugin_$version-dev.jar").toAbsolutePath
    assert(Files.isRegularFile(jar), jar.toString)
    settings.plugin.value = List(jar.toString)
    assert(settings.processArgumentString("-Xplugin-require:idsl-plugin")._1)
    val reporter = new StoreReporter
    val compiler = new Global(settings, reporter)
    val file = directory.resolve("Fixture.scala")
    Files.write(file, source.getBytes("UTF-8"))
    new compiler.Run().compile(List(file.toString))
    directory -> reporter.infos.toVector.filter(_.severity == reporter.ERROR).map(_.msg)
  }
  test("annotation plugin rejects unsupported targets with explicit diagnostics") {
    val (_, errors) = compile(core + """object Invalid {
      @spinal.core.doc("number") val value = 1
      @spinal.core.doc("method") def method = new spinal.core.Data
    }
    @spinal.core.doc("not hardware") class NotHardware
    @spinal.core.doc("object") object NotSupported
    """)
    assert(errors.exists(_.contains("hardware-valued declaration")), errors)
    assert(errors.exists(_.contains("methods is unsupported")), errors)
    assert(errors.exists(_.contains("Component definition")), errors)
    assert(errors.exists(_.contains("on objects is unsupported")), errors)
  }
  test("annotation plugin evaluates initializers once and supports precompiled class annotations") {
    val (library, errors) = compile(core + """@spinal.core.doc("library module") class Library extends spinal.core.Component""")
    assert(errors.isEmpty, errors)
    val (client, clientErrors) = compile("""object Probe {
      var calls = 0
      def make = { calls += 1; new spinal.core.Data }
      @spinal.core.doc("field") val value = make
      val library = new Library
      def result = calls.toString + ":" + spinal.core.RtlDocumentation.notes.mkString(",")
    }""", extra = library.toString)
    assert(clientErrors.isEmpty, clientErrors)
    val loader = new URLClassLoader(Array(client.toUri.toURL, library.toUri.toURL), getClass.getClassLoader)
    try {
      val cls = loader.loadClass("Probe$")
      val instance = cls.getField("MODULE$").get(null)
      assert(cls.getMethod("result").invoke(instance) == "1:field,library module")
    } finally loader.close()
  }
  test("source lexer distinguishes nested comments quoted literals and attachment boundaries") {
    import spinal.idslplugin.components.SourceDocumentation
    val source = "val a = \"// ignored\" // trailing\r\n/* outer /* nested */ text */\nval b = 1\n\n// detached\n\nval c = 2\n"
    val comments = SourceDocumentation.scan(source)
    assert(comments.map(_.text) == Vector("trailing", "outer /* nested */ text", "detached"))
    assert(SourceDocumentation.trailing(source, comments, source.indexOf(" // trailing")).map(_.text) == Vector("trailing"))
    assert(SourceDocumentation.preceding(source, comments, source.indexOf("val b")).map(_.text) == Vector("outer /* nested */ text"))
    assert(SourceDocumentation.preceding(source, comments, source.indexOf("val c")).isEmpty)
    val literals = "val x = \"\"\"// ignored /* ignored */\"\"\"; val c = '/' // real"
    assert(SourceDocumentation.scan(literals).map(_.text) == Vector("real"))
  }

  test("comment-only recompilation updates embedded metadata and requires no runtime source") {
    def source(note: String) = core + s"""object Probe {
      val value = new spinal.core.Data // $note
      def result = spinal.core.RtlDocumentation.notes.mkString(",")
    }"""
    val directory = Files.createTempDirectory("comment-recompile-")
    for (note <- Seq("first note λ", "edited note λ")) {
      val (_, errors) = compile(source(note).replace("\n", "\r\n"), directory)
      assert(errors.isEmpty, errors)
      Files.delete(directory.resolve("Fixture.scala"))
      val loader = new URLClassLoader(Array(directory.toUri.toURL), getClass.getClassLoader)
      try {
        val cls = loader.loadClass("Probe$")
        val instance = cls.getField("MODULE$").get(null)
        assert(cls.getMethod("result").invoke(instance) == note)
      } finally loader.close()
    }
  }

  test("missing range positions embed a precise automatic capture diagnostic") {
    val (directory, errors) = compile(core + """object Probe {
      val value = new spinal.core.Data // source note
      def result = spinal.core.RtlDocumentation.notes.mkString(",")
    }""", ranges = false)
    assert(errors.isEmpty, errors)
    val loader = new URLClassLoader(Array(directory.toUri.toURL), getClass.getClassLoader)
    try {
      val cls = loader.loadClass("Probe$")
      assert(cls.getMethod("result").invoke(cls.getField("MODULE$").get(null)).toString.contains("-Yrangepos"))
    } finally loader.close()
  }
  test("precompiled class comments survive source deletion without inheriting to unrelated classes") {
    val (library, errors) = compile(core + """class Library extends spinal.core.Component { // Library header λ
    }
    class Derived extends Library
    """)
    assert(errors.isEmpty, errors)
    Files.delete(library.resolve("Fixture.scala"))
    val (client, clientErrors) = compile("""object Probe {
      val value = new Library
      val derived = new Derived
      def result = spinal.core.RtlDocumentation.notes.mkString(",")
    }""", extra = library.toString)
    assert(clientErrors.isEmpty, clientErrors)
    val loader = new URLClassLoader(Array(client.toUri.toURL, library.toUri.toURL), getClass.getClassLoader)
    try {
      val cls = loader.loadClass("Probe$")
      assert(cls.getMethod("result").invoke(cls.getField("MODULE$").get(null)) == "Library header λ")
    } finally loader.close()
  }

  test("precompiled region markers capture comments without frontend package coupling") {
    val runtime = core.replace("var notes = Vector.empty[String]", """var notes = Vector.empty[String]
      def automaticRegion[T](text: String, scaladoc: Boolean)(body: => T): T = {
        notes :+= text; body
      }""")
    val (library, errors) = compile(runtime + """object IndependentRegions {
      @spinal.idslplugin.RtlDocumentationRegion
      def region(body: => Unit): Unit = body
      def ordinary(body: => Unit): Unit = body
    }""")
    assert(errors.isEmpty, errors)
    Files.delete(library.resolve("Fixture.scala"))
    val (client, clientErrors) = compile("""object Probe {
      var calls = 0
      // Captured region
      IndependentRegions.region { calls += 1 }
      // Not a hardware region
      IndependentRegions.ordinary { calls += 1 }
      def result = calls.toString + ":" + spinal.core.RtlDocumentation.notes.mkString(",")
    }""", extra = library.toString)
    assert(clientErrors.isEmpty, clientErrors)
    val loader = new URLClassLoader(Array(client.toUri.toURL, library.toUri.toURL), getClass.getClassLoader)
    try {
      val cls = loader.loadClass("Probe$")
      assert(cls.getMethod("result").invoke(cls.getField("MODULE$").get(null)) == "2:Captured region")
    } finally loader.close()
  }

}
