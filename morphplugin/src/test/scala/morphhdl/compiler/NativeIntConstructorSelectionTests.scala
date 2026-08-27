package morphhdl.compiler

import java.io.File
import java.nio.file.Files

import scala.collection.JavaConverters._
import scala.reflect.internal.util.BatchSourceFile
import scala.tools.nsc.{Global, Settings}
import scala.tools.nsc.reporters.StoreReporter

import org.scalatest.funsuite.AnyFunSuite

class NativeIntConstructorSelectionTests extends AnyFunSuite {
  private val runtimeBoundary =
    """
      |package spinal.core {
      |  object ExternalNativeIntCompilerRuntime {
      |    def compilerTrackArgument(
      |        value: Int,
      |        name: String,
      |        reference: String,
      |        file: String,
      |        line: Int
      |    ): Int = value
      |
      |    def compilerComparison(
      |        operation: String,
      |        left: Int,
      |        leftReference: String,
      |        leftLiteral: Boolean,
      |        right: Int,
      |        rightReference: String,
      |        rightLiteral: Boolean,
      |        resultReference: String,
      |        name: String,
      |        file: String,
      |        line: Int
      |    ): Boolean = operation match {
      |      case ">" => left > right
      |      case _   => false
      |    }
      |
      |    def selectSymbolicGenerate[T](
      |        condition: Boolean,
      |        predicateReference: String,
      |        sourceFile: String,
      |        sourceLine: Int
      |    )(body: => T): T =
      |      if (condition) body else null.asInstanceOf[T]
      |
      |    def selectSymbolicUnit(
      |        condition: Boolean,
      |        predicateReference: String,
      |        sourceFile: String,
      |        sourceLine: Int
      |    )(ifTrue: => Any)(ifFalse: => Any): Unit = {
      |      if (condition) ifTrue else ifFalse
      |      ()
      |    }
      |  }
      |
      |  object ExternalNativeIntFormalComponent {
      |    def parameter[C](
      |        actual: Int,
      |        name: String,
      |        minimum: BigInt,
      |        maximum: BigInt
      |    )(constructor: Int => C): C = constructor(actual)
      |  }
      |}
      |""".stripMargin

  test("derives the exact named constructor slot without a source-text gate") {
    val errors = compile(
      """
        |package genericcase {
        |  final class GenericNode(val payload: String, val capacity: Int) {
        |    val retained = capacity
        |    val condition = capacity > 1
        |
        |    // A ValDef RHS is a root expression and must still enter the
        |    // generic .generate dispatcher before typer sees this source.
        |    val rootAlternative = condition.generate(())
        |  }
        |
        |  object GenericNode {
        |    def apply(actual: Int): GenericNode =
        |      spinal.core
        |        .ExternalNativeIntFormalComponent
        |        .parameter(
        |          maximum = BigInt(8),
        |          name = "CAPACITY",
        |          actual = actual,
        |          minimum = BigInt(1)
        |        )(selected =>
        |          new GenericNode(capacity = selected, payload = "payload")
        |        )
        |  }
        |}
        |""".stripMargin
    )

    // The nested runtime deliberately exposes only selectSymbolicGenerate.
    // Routing root .generate through ordinary selectSymbolic would not typecheck.
    assert(errors.isEmpty, errors.mkString("\n"))
  }

  test("allows a proven anonymous-record field read inside a captured alternative") {
    val errors = compile(
      """
        |package fieldreadcase {
        |  final class FieldReadNode(val capacity: Int) {
        |    val condition = capacity > 1
        |    val hardwareLike = new {
        |      val flush = new Object
        |    }
        |    val selected = condition.generate(hardwareLike.flush)
        |  }
        |  object FieldReadNode {
        |    def apply(actual: Int): FieldReadNode =
        |      spinal.core.ExternalNativeIntFormalComponent.parameter(
        |        actual, "CAPACITY", BigInt(1), BigInt(8)
        |      )(selected => new FieldReadNode(selected))
        |  }
        |}
        |""".stripMargin
    )

    // The test runtime intentionally has no guardAlternative method. A false
    // I/O classification of the field read therefore becomes a type error.
    assert(errors.isEmpty, errors.mkString("\n"))
  }

  test("continues to guard an applied Scala flush call") {
    val errors = compile(
      """
        |package appliedflushcase {
        |  final class ScalaOutput {
        |    def flush(): AnyRef = new Object
        |  }
        |  final class AppliedFlushNode(val capacity: Int) {
        |    val condition = capacity > 1
        |    val output = new ScalaOutput
        |    val selected = condition.generate(output.flush())
        |  }
        |  object AppliedFlushNode {
        |    def apply(actual: Int): AppliedFlushNode =
        |      spinal.core.ExternalNativeIntFormalComponent.parameter(
        |        actual, "CAPACITY", BigInt(1), BigInt(8)
        |      )(selected => new AppliedFlushNode(selected))
        |  }
        |}
        |""".stripMargin
    )

    assert(errors.nonEmpty, "expected the applied flush call to be guarded")
    assert(errors.forall(_.contains("guardAlternative")), errors.mkString("\n"))
  }

  test("rejects a boundary witness used by more than one constructor slot") {
    val errors = compile(
      """
        |package ambiguouscase {
        |  final class AmbiguousNode(val first: Int, val second: Int)
        |  object AmbiguousNode {
        |    def apply(actual: Int): AmbiguousNode =
        |      spinal.core.ExternalNativeIntFormalComponent.parameter(
        |        actual, "SIZE", BigInt(1), BigInt(8)
        |      )(selected => new AmbiguousNode(selected, selected))
        |  }
        |}
        |""".stripMargin
    )

    assertHasOnlySelectionError(
      errors,
      "MORPHDL-NATIVE-INT-CONSTRUCTOR-ARGUMENT-AMBIGUOUS"
    )
  }

  test("rejects an indirect constructor boundary") {
    val errors = compile(
      """
        |package indirectcase {
        |  final class IndirectNode(val capacity: Int)
        |  object IndirectNode {
        |    private def construct(value: Int) = new IndirectNode(value)
        |    def apply(actual: Int): IndirectNode =
        |      spinal.core.ExternalNativeIntFormalComponent.parameter(
        |        actual, "CAPACITY", BigInt(1), BigInt(8)
        |      )(selected => construct(selected))
        |  }
        |}
        |""".stripMargin
    )

    assertHasOnlySelectionError(
      errors,
      "MORPHDL-NATIVE-INT-CONSTRUCTOR-DIRECT-NEW-REQUIRED"
    )
  }

  test("rejects auxiliary constructors") {
    val errors = compile(
      """
        |package auxiliarycase {
        |  final class AuxiliaryNode(val capacity: Int) {
        |    def this(capacity: Int, ignored: String) = this(capacity)
        |  }
        |  object AuxiliaryNode {
        |    def apply(actual: Int): AuxiliaryNode =
        |      spinal.core.ExternalNativeIntFormalComponent.parameter(
        |        actual, "CAPACITY", BigInt(1), BigInt(8)
        |      )(selected => new AuxiliaryNode(selected))
        |  }
        |}
        |""".stripMargin
    )

    assertHasOnlySelectionError(
      errors,
      "MORPHDL-NATIVE-INT-CONSTRUCTOR-SHAPE-AMBIGUOUS"
    )
  }

  test("rejects qualified constructor targets instead of matching a simple-name collision") {
    val errors = compile(
      """
        |package collisioncase {
        |  object First {
        |    final class CollidingNode(val capacity: Int)
        |  }
        |  object Second {
        |    final class CollidingNode(val unrelated: Int)
        |  }
        |  object Make {
        |    def apply(actual: Int): First.CollidingNode =
        |      spinal.core.ExternalNativeIntFormalComponent.parameter(
        |        actual, "CAPACITY", BigInt(1), BigInt(8)
        |      )(selected => new First.CollidingNode(selected))
        |  }
        |}
        |""".stripMargin
    )

    assertHasOnlySelectionError(
      errors,
      "MORPHDL-NATIVE-INT-CONSTRUCTOR-DIRECT-NEW-REQUIRED"
    )
  }

  test("rejects an unqualified constructor when its simple declaration name collides") {
    val errors = compile(
      """
        |package declarationcollisioncase {
        |  object First {
        |    final class CollidingNode(val capacity: Int)
        |  }
        |  object Second {
        |    final class CollidingNode(val unrelated: Int)
        |  }
        |  import First.CollidingNode
        |  object Make {
        |    def apply(actual: Int): CollidingNode =
        |      spinal.core.ExternalNativeIntFormalComponent.parameter(
        |        actual, "CAPACITY", BigInt(1), BigInt(8)
        |      )(selected => new CollidingNode(selected))
        |  }
        |}
        |""".stripMargin
    )

    assertHasOnlySelectionError(
      errors,
      "MORPHDL-NATIVE-INT-CONSTRUCTOR-DECLARATION-AMBIGUOUS"
    )
  }

  test("rejects nested constructor parameters that shadow the selected slot") {
    val errors = compile(
      """
        |package shadowcase {
        |  final class ShadowedNode(val capacity: Int) {
        |    final class Nested(val capacity: Int)
        |  }
        |  object ShadowedNode {
        |    def apply(actual: Int): ShadowedNode =
        |      spinal.core.ExternalNativeIntFormalComponent.parameter(
        |        actual, "CAPACITY", BigInt(1), BigInt(8)
        |      )(selected => new ShadowedNode(selected))
        |  }
        |}
        |""".stripMargin
    )

    assertHasOnlySelectionError(
      errors,
      "MORPHDL-NATIVE-INT-CONSTRUCTOR-LEXICAL-SHADOW-UNSUPPORTED"
    )
  }

  private def assertHasOnlySelectionError(
      errors: Vector[String],
      expectedCode: String
  ): Unit = {
    assert(errors.nonEmpty, s"expected $expectedCode")
    assert(errors.forall(_.contains(expectedCode)), errors.mkString("\n"))
  }

  private def compile(source: String): Vector[String] = {
    val output = Files.createTempDirectory("native-int-constructor-selection-test")
    val settings = new Settings
    settings.usejavacp.value = true
    settings.outputDirs.setSingleOutput(output.toString)
    val classLocation =
      new File(classOf[MorphHdlPlugin].getProtectionDomain.getCodeSource.getLocation.toURI)
        .getAbsolutePath
    val descriptorLocations = Option(
      classOf[MorphHdlPlugin].getClassLoader.getResource("scalac-plugin.xml")
    )
      .filter(_.getProtocol == "file")
      .map(url => new File(url.toURI).getParentFile.getAbsolutePath)
      .toList
    settings.plugin.value = (classLocation :: descriptorLocations).distinct
    val reporter = new StoreReporter
    val compiler = new Global(settings, reporter)
    assert(
      compiler.plugins.exists(_.name == "morphhdl"),
      "nested compiler did not load morphhdl plugin"
    )
    val run = new compiler.Run
    try {
      run.compileSources(
        List(
          new BatchSourceFile(
            "<native-int-constructor-selection-test>",
            runtimeBoundary + source
          )
        )
      )
      reporter.infos.toVector
        .filter(_.severity == reporter.ERROR)
        .map(_.msg)
    } finally {
      val paths = Files.walk(output)
      try paths.iterator().asScala.toVector.reverse.foreach(Files.deleteIfExists(_))
      finally paths.close()
    }
  }
}
