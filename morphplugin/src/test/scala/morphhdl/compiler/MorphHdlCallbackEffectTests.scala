package morphhdl.compiler

import org.scalatest.funsuite.AnyFunSuite

class MorphHdlCallbackEffectTests extends AnyFunSuite {
  import MorphHdlCompilerTestSupport._
  private val prefix = """
package spinal.core { final class rtlHardwareCallback extends scala.annotation.StaticAnnotation }
package example {
  import spinal.core._
  object Api { def hardware(@rtlHardwareCallback f: Int => Int): Int = f(1) }
"""
  private def errors(body: String) = compile(prefix + body + "\n}", "CallbackEffects.scala")
  for ((name, body) <- Seq(
    "direct" -> "var count = 0; val value = Api.hardware(x => { count += 1; x })",
    "qualified" -> "object State { var count = 0 }; val value = Api.hardware(x => { State.count += 1; x })",
    "helper" -> "var count = 0; def helper(x: Int): Int = { count = x; x }; val value = Api.hardware(x => helper(x))",
    "forward" -> "var count = 0; val value = Api.hardware(x => helper(x)); def helper(x: Int): Int = { count = x; x }",
    "stored" -> "var count = 0; val callback = (x: Int) => { count = x; x }; val value = Api.hardware(callback)",
    "local mutable" -> "val value = Api.hardware(x => { var y = x; y })",
    "throw" -> "val value = Api.hardware(x => throw new IllegalArgumentException)"
  )) test("source-visible callback effects reject " + name) {
    val found = errors("object Example { " + body + " }")
    assert(found.exists(_.message.contains("MORPHDL-HARDWARE-CALLBACK-HOST-EFFECT")), found.mkString("\n"))
  }
  test("immutable helpers and same-named unrelated methods retain their semantics") {
    val found = errors("""object Example {
      var state = 0
      def helper(x: Int): Int = { state = x; x }
      val value = Api.hardware { x =>
        def helper(y: Int): Int = y + 1
        helper(x)
      }
    }""")
    assert(found.isEmpty, found.mkString("\n"))
  }
  test("ordinary callbacks remain compatible") {
    val found = errors("object Example { var count = 0; val value = List(1).map(x => { count += 1; x }) }")
    assert(found.isEmpty, found.mkString("\n"))
  }
  test("captured immutable value does not replay its already evaluated initializer") {
    val found = errors("""object Example {
      var state = 0
      val captured = { state = 1; 7 }
      val value = Api.hardware(x => x + captured)
    }""")
    assert(found.isEmpty, found.mkString("\n"))
  }

  test("stored closure initializer is evaluated before capture") {
    val found = errors("""object Example {
      var state = 0
      val callback: Int => Int = { state = 1; (x: Int) => x + 7 }
      val value = Api.hardware(callback)
    }""")
    assert(found.isEmpty, found.mkString("\n"))
  }

  test("typed overload identity rejects only the called effectful helper") {
    val bad = errors("""object Example {
      var state = 0
      def helper(value: Int): Int = { state = value; value }
      def helper(value: String): Int = value.length
      val value = Api.hardware(x => helper(x))
    }""")
    assert(bad.exists(_.message.contains("MORPHDL-HARDWARE-CALLBACK-HOST-EFFECT")), bad.mkString("\n"))
    val good = errors("""object Example {
      var state = 0
      def helper(value: Int): Int = value + 1
      def helper(value: String): Int = { state = value.length; state }
      val value = Api.hardware(x => helper(x))
    }""")
    assert(good.isEmpty, good.mkString("\n"))
  }

}
