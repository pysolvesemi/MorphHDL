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

  test("callback helper effects retain exact identities across source files") {
    val helper = """package example
      object SeparateHelper {
        var state = 0
        def update(value: Int): Int = { state = value; value }
        def pure(value: Int): Int = value + 1
      }
    """
    for ((method, reject) <- Seq("update" -> true, "pure" -> false)) {
      val found = compile(prefix + s"object Example { val value = Api.hardware(x => SeparateHelper.$method(x)) } }",
        "Callback.scala", List("SeparateHelper.scala" -> helper))
      if (reject) assert(found.exists(d => d.message.contains("MORPHDL-HARDWARE-CALLBACK-HOST-EFFECT") &&
        d.source == "SeparateHelper.scala"), found.mkString("\n"))
      else assert(found.isEmpty, found.mkString("\n"))
    }
  }

  for ((name, body) <- Seq(
    "buffer append" -> "val values = scala.collection.mutable.ArrayBuffer.empty[Int]; val value = Api.hardware(x => { values += x; x })",
    "map update" -> "val values = scala.collection.mutable.Map.empty[Int, Int]; val value = Api.hardware(x => { values.update(x, x); x })",
    "array update" -> "val values = Array(0); val value = Api.hardware(x => { values(0) = x; x })",
    "console" -> "val value = Api.hardware(x => { println(x); x })",
    "stream" -> "val value = Api.hardware(x => { System.out.println(x); x })"
  )) test("known host APIs reject " + name) {
    val found = errors("object Example { " + body + " }")
    assert(found.exists(_.message.contains("MORPHDL-HARDWARE-CALLBACK-HOST-EFFECT")), found.mkString("\n"))
  }

  test("immutable collection operations and same-named authored methods remain supported") {
    val found = errors("""object Example {
      class Pure { def update(x: Int): Int = x + 1; def println(x: Int): Int = x }
      val pure = new Pure
      val value = Api.hardware(x => List(x, 1).map(pure.update).sum + pure.println(x))
    }""")
    assert(found.isEmpty, found.mkString("\n"))
  }

}
