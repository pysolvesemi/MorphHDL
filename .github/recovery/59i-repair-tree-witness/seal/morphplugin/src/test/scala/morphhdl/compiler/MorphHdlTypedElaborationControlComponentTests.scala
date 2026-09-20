package morphhdl.compiler

import org.scalatest.funsuite.AnyFunSuite

package externalwildcardfixture {
  object clean

  package object members {
    final class ElabInt(val value: Int) {
      def >(other: Int): Boolean = value > other
    }
  }
}

class MorphHdlTypedElaborationControlComponentTests extends AnyFunSuite {
  import MorphHdlCompilerTestSupport._

  private val neutralDefinitions =
    """
      |package spinal.core {
      |  final class ElabBool
      |  final class ElabInt {
      |    def >(other: Int): ElabBool = new ElabBool
      |  }
      |  object ElabControl {
      |    def selectSymbolic[T](condition: ElabBool, source: String, line: Int)(
      |        whenTrue: => T
      |    )(
      |        whenFalse: => T
      |    ): T = whenTrue
      |    def requireCondition(condition: ElabBool, source: String, line: Int): Unit = ()
      |    def requireCondition(
      |        condition: ElabBool,
      |        message: => Any,
      |        source: String,
      |        line: Int
      |    ): Unit = ()
      |  }
      |}
      |""".stripMargin

  private val samePackageDefinitions =
    """
      |package spinal.core {
      |  final class ElabBool
      |  final class ElabInt {
      |    def >(other: Int): ElabBool = new ElabBool
      |  }
      |  object ElabControl {
      |    def selectSymbolic[T](condition: ElabBool, source: String, line: Int)(
      |        whenTrue: => T
      |    )(
      |        whenFalse: => T
      |    ): T = whenTrue
      |  }
      |  object SamePackageTypedControl {
      |    def choose(width: ElabInt): Int =
      |      if (width > 0) 1 else 0
      |  }
      |}
      |""".stripMargin

  test("canonical carrier imports, aliases and rooted names enable typed control") {
    val source = neutralDefinitions +
      """
        |package typedcontrol {
        |  import spinal.core.{ElabBool => Predicate, ElabInt => Width}
        |
        |  object AcceptedAliases {
        |  type WidthAlias = Width
        |
        |  def choose(width: WidthAlias): Int =
        |    if (width > 0) 1 else 0
        |
        |  def choosePredicate(predicate: Predicate): Int =
        |    if (predicate) 1 else 0
        |
        |  def guard(width: WidthAlias): Unit =
        |    require(width > 0, "width must be positive")
        |  }
        |
        |  object RootedNames {
        |  object spinal
        |  object scala
        |
        |  def choose(width: _root_.spinal.core.ElabInt): Int =
        |    if (width > 0) 1 else 0
        |
        |  def guard(width: _root_.spinal.core.ElabInt): Unit =
        |    _root_.scala.Predef.require(width > 0, "rooted require")
        |  }
        |}
        |
        |package wildcardcontrol {
        |  import spinal.core._
        |  object AcceptedWildcard {
        |    def choose(width: ElabInt): Int =
        |      if (width > 0) 1 else 0
        |  }
        |}
        |
        |package predefwildcardcontrol {
        |  import spinal.core._
        |  import scala.Predef._
        |  object AcceptedPredefWildcard {
        |    def choose(width: ElabInt): Int =
        |      if (width > 0) 1 else 0
        |  }
        |}
        |""".stripMargin

    val errors = compile(source)
    assert(errors.isEmpty, errors.mkString("\n"))
  }

  test("simple carriers are canonical inside package spinal.core") {
    val errors = compile(samePackageDefinitions)

    assert(errors.isEmpty, errors.mkString("\n"))
  }

  test("resolved external wildcards do not shadow canonical typed control") {
    val errors = compile(
      neutralDefinitions + """
        |package externalwildcardcontrol {
        |  import scala.collection.JavaConverters._
        |  import spinal.core._
        |
        |  object ProductionShapedImports {
        |    def choose(width: ElabInt): Int =
        |      if (width > 0) 1 else 0
        |
        |    def guard(width: ElabInt): Unit =
        |      require(width > 0, "external wildcard must not shadow Predef")
        |  }
        |}
        |""".stripMargin,
      "ExternalWildcardControl.scala"
    )

    assert(errors.isEmpty, errors.mkString("\n"))
  }

  test("root package prefixes do not make external wildcards source-owned") {
    val errors = compile(
      neutralDefinitions + """
        |package morphhdl {
        |  import scala.collection.JavaConverters._
        |  import spinal.core._
        |  import morphhdl.compiler.externalwildcardfixture.clean._
        |
        |  object ProductionRootPrefix {
        |    def choose(width: ElabInt): Int =
        |      if (width > 0) 1 else 0
        |  }
        |}
        |""".stripMargin,
      "RootPackagePrefixControl.scala"
    )

    assert(errors.isEmpty, errors.mkString("\n"))
  }

  test("resolved external package-object members remain ordinary") {
    val errors = compile(
      neutralDefinitions + """
        |package externalpackageobjectcontrol {
        |  import spinal.core._
        |
        |  object ExternalPackageObjectCollision {
        |    import morphhdl.compiler.externalwildcardfixture.members._
        |
        |    def choose(width: ElabInt): Int =
        |      if (width > 0) 1 else 0
        |  }
        |}
        |""".stripMargin,
      "ExternalPackageObjectCollision.scala"
    )

    assert(errors.isEmpty, errors.mkString("\n"))
  }

  test("same-run relative qualifiers shadow root external providers") {
    val errors = compile(
      neutralDefinitions + """
        |package relativequalifier {
        |  object spinal {
        |    object core {
        |      final class ElabInt {
        |        def >(other: Int): Boolean = true
        |      }
        |    }
        |  }
        |
        |  object scala {
        |    object Predef {
        |      def assert(
        |          condition: _root_.spinal.core.ElabBool,
        |          first: String,
        |          second: String
        |      ): Unit = ()
        |    }
        |
        |    object collection {
        |      object JavaConverters {
        |        final class ElabInt {
        |          def >(other: Int): Boolean = true
        |        }
        |      }
        |    }
        |  }
        |
        |  object Predef {
        |    def require(
        |        condition: _root_.spinal.core.ElabBool,
        |        first: String,
        |        second: String
        |    ): Unit = ()
        |  }
        |
        |  package object consumer {
        |    def assert(
        |        condition: _root_.spinal.core.ElabBool,
        |        first: String,
        |        second: String
        |    ): Unit = ()
        |  }
        |
        |  package consumer {
        |    import _root_.spinal.core._
        |
        |    object QualifiedCarrier {
        |      def choose(width: spinal.core.ElabInt): Int =
        |        if (width > 0) 1 else 0
        |    }
        |
        |    object RelativeWildcard {
        |      import scala.collection.JavaConverters._
        |
        |      def choose(width: ElabInt): Int =
        |        if (width > 0) 1 else 0
        |    }
        |
        |    object RelativePredef {
        |      def check(width: _root_.spinal.core.ElabInt): Unit = {
        |        scala.Predef.assert(width > 0, "relative", "scala.Predef")
        |        Predef.require(width > 0, "relative", "Predef")
        |        assert(width > 0, "relative", "package object")
        |      }
        |    }
        |  }
        |}
        |""".stripMargin,
      "RelativeQualifierCollision.scala"
    )

    assert(errors.isEmpty, errors.mkString("\n"))
  }

  test("local and explicitly imported fake carrier names remain ordinary Scala") {
    val errors = compile(
      neutralDefinitions + """
        |package fakecarrier {
        |  final class ElabInt(val value: Int) {
        |    def >(other: Int): Boolean = value > other
        |  }
        |}
        |
        |package localcollision {
        |  import spinal.core._
        |
        |  object LocalFake {
        |    final class ElabInt(val value: Int) {
        |      def >(other: Int): Boolean = value > other
        |    }
        |
        |    def choose(width: ElabInt): Int =
        |      if (width > 0) 1 else 0
        |  }
        |}
        |
        |package importedcollision {
        |  import spinal.core._
        |  import fakecarrier.ElabInt
        |
        |  object ImportedFake {
        |    def choose(width: ElabInt): Int =
        |      if (width > 0) 1 else 0
        |  }
        |}
        |""".stripMargin
    )

    assert(errors.isEmpty, errors.mkString("\n"))
  }

  test("noncanonical wildcard carrier imports remain ordinary Scala") {
    val errors = compile(
      neutralDefinitions + """
        |package fakecarrier {
        |  final class ElabInt(val value: Int) {
        |    def >(other: Int): Boolean = value > other
        |  }
        |}
        |
        |package wildcardcollision {
        |  import spinal.core._
        |
        |  object WildcardFake {
        |    import fakecarrier._
        |    def choose(width: ElabInt): Int =
        |      if (width > 0) 1 else 0
        |  }
        |}
        |""".stripMargin,
      "WildcardCarrier.scala"
    )

    assert(errors.isEmpty, errors.mkString("\n"))
  }

  test("arbitrary wildcards shadow unrooted carrier and Predef qualifiers") {
    val ordinary = compile(
      """
        |package fakequalifier {
        |  object Imports {
        |    object spinal {
        |      object core {
        |        final class ElabInt {
        |          def >(other: Int): Boolean = true
        |        }
        |      }
        |    }
        |    object scala {
        |      object Predef {
        |        def assert(condition: Boolean): Unit = ()
        |      }
        |    }
        |    object Predef {
        |      def require(condition: Boolean): Unit = ()
        |    }
        |  }
        |}
        |
        |package wildcardqualifier {
        |  import fakequalifier.Imports._
        |
        |  object FakePaths {
        |    def choose(width: spinal.core.ElabInt): Int =
        |      if (width > 0) 1 else 0
        |
        |    def guard(width: spinal.core.ElabInt): Unit = {
        |      scala.Predef.assert(width > 0)
        |      Predef.require(width > 0)
        |    }
        |  }
        |}
        |
        |package wildcardimportqualifier {
        |  import fakequalifier.Imports._
        |  import spinal.core._
        |
        |  object FakeImportPath {
        |    def choose(width: ElabInt): Int =
        |      if (width > 0) 1 else 0
        |  }
        |}
        |""".stripMargin,
      "WildcardQualifiers.scala"
    )
    assert(ordinary.isEmpty, ordinary.mkString("\n"))

    val rooted = compile(
      neutralDefinitions + """
        |package rootedqualifier {
        |  object FakeNames {
        |    object spinal
        |    object scala
        |    object Predef
        |  }
        |  import FakeNames._
        |
        |  object RootedPaths {
        |    def guard(width: _root_.spinal.core.ElabInt): Unit =
        |      _root_.scala.Predef.assert(width > 0)
        |  }
        |}
        |""".stripMargin,
      "RootedQualifiers.scala"
    )
    assert(rooted.isEmpty, rooted.mkString("\n"))
  }

  test("only unshadowed Predef require and assert calls are rewritten") {
    val accepted = compile(
      neutralDefinitions + """
        |package requireresolution {
        |  import spinal.core.{ElabBool, ElabInt}
        |
        |  object Validator {
        |  def require(condition: ElabBool, message: String): Unit = ()
        |  def assert(condition: ElabBool): Unit = ()
        |  }
        |
        |  object QualifiedCalls {
        |  def check(width: ElabInt): Unit = {
        |    Validator.require(width > 0, "custom")
        |    Validator.assert(width > 0)
        |  }
        |  }
        |
        |  object ShadowedCall {
        |  def require(condition: ElabBool, message: String): Unit = ()
        |  def check(width: ElabInt): Unit =
        |    require(width > 0, "local")
        |  }
        |
        |  object ImportedCall {
        |    import Validator.require
        |    def check(width: ElabInt): Unit =
        |      require(width > 0, "imported")
        |  }
        |
        |  object PredefCall {
        |  def check(width: ElabInt): Unit = {
        |    require(width > 0, "predef")
        |    assert(width > 0, "predef assertion")
        |  }
        |  }
        |
        |  object PredefAliases {
        |  import scala.Predef.{assert => typedAssert, require => typedRequire}
        |  def check(width: ElabInt): Unit = {
        |    typedRequire(width > 0, "aliased requirement")
        |    typedAssert(width > 0, "aliased assertion")
        |    require(width > 0, "automatic requirement")
        |    assert(width > 0, "automatic assertion")
        |  }
        |  }
        |
        |  object PredefExclusions {
        |  import scala.Predef.{assert => _, require => _, _}
        |  def check(width: ElabInt): Unit = {
        |    require(width > 0, "automatic requirement after exclusion")
        |    assert(width > 0, "automatic assertion after exclusion")
        |  }
        |  }
        |}
        |""".stripMargin
    )
    assert(accepted.isEmpty, accepted.mkString("\n"))
  }

  test("spinal.core and custom assert bindings are not rewritten") {
    val errors = compile(
      """
        |package spinal {
        |  package object core {
        |    def assert(condition: _root_.spinal.core.ElabBool): Unit = ()
        |  }
        |}
        |
        |package spinal.core {
        |  final class ElabBool
        |  final class ElabInt {
        |    def >(other: Int): ElabBool = new ElabBool
        |  }
        |}
        |
        |package hardwareassert {
        |  import spinal.core._
        |  object HardwareCall {
        |    def check(width: ElabInt): Unit = assert(width > 0)
        |  }
        |}
        |
        |package customassert {
        |  import spinal.core.{ElabBool, ElabInt}
        |
        |  object Validator {
        |    def assert(condition: ElabBool): Unit = ()
        |    def alternative(condition: ElabBool): Unit = ()
        |    def requirement(condition: ElabBool): Unit = ()
        |    object FakePredef {
        |      def assert(condition: ElabBool): Unit = ()
        |      def require(condition: ElabBool): Unit = ()
        |    }
        |  }
        |
        |  object QualifiedCall {
        |    def check(width: ElabInt): Unit = Validator.assert(width > 0)
        |  }
        |
        |  object ImportedCall {
        |    import Validator.assert
        |    def check(width: ElabInt): Unit = assert(width > 0)
        |  }
        |
        |  object RenamedCalls {
        |    import Validator.{alternative => assert, requirement => require}
        |    def check(width: ElabInt): Unit = {
        |      assert(width > 0)
        |      require(width > 0)
        |    }
        |  }
        |
        |  object RenamedQualifier {
        |    import Validator.{FakePredef => Predef}
        |    def check(width: ElabInt): Unit = {
        |      Predef.assert(width > 0)
        |      Predef.require(width > 0)
        |    }
        |  }
        |}
        |""".stripMargin,
      "CustomAssert.scala"
    )

    assert(errors.isEmpty, errors.mkString("\n"))
  }

  test("non-Predef wildcard imports do not authorize typed require rewriting") {
    val errors = compile(
      """
        |package spinal.core {
        |  final class ElabBool
        |  final class ElabInt {
        |    def >(other: Int): ElabBool = new ElabBool
        |  }
        |}
        |
        |package wildcardrequire {
        |  import spinal.core.{ElabBool, ElabInt}
        |
        |  object Validator {
        |    def require(condition: ElabBool, message: String): Unit = ()
        |  }
        |
        |  object Consumer {
        |    import Validator._
        |    def check(width: ElabInt): Unit =
        |      require(width > 0, "custom wildcard")
        |  }
        |}
        |""".stripMargin,
      "WildcardRequire.scala"
    )

    assert(errors.isEmpty, errors.mkString("\n"))
  }

  test("typed require arity diagnostics preserve the original source position") {
    val source = neutralDefinitions +
      """
        |package typeddiagnostic {
        |  import spinal.core.ElabInt
        |  object InvalidRequire {
        |  def check(width: ElabInt): Unit = {
        |    require(width > 0, "first", "extra") // expected-position
        |  }
        |  }
        |}
        |""".stripMargin
    val expectedLine = lineOf(source, "require(width > 0")
    val errors = compile(source, "TypedRequirePosition.scala")
    val diagnostic = errors.find(
      _.message.contains("MORPHDL-TYPED-REQUIRE-ARITY-UNSUPPORTED")
    )

    assert(diagnostic.nonEmpty, errors.mkString("\n"))
    assert(diagnostic.get.line == expectedLine, diagnostic.get.toString)
    assert(diagnostic.get.source == "TypedRequirePosition.scala", diagnostic.get.toString)
  }
}
