package morphhdl.compiler

import org.scalatest.funsuite.AnyFunSuite

class NativeSymbolicEqualitySafetyTests extends AnyFunSuite {
  import MorphHdlCompilerTestSupport._
  private val definitions = """
    package spinal.core { final class ElabInt; final class ElabBool }
  """
  test("inferred helper and qualified results cannot fall back to numeric Scala equality") {
    val errors = compile(definitions + """
      object Probe {
        def inferred = new spinal.core.ElabInt
        object Helpers { def count = new spinal.core.ElabInt }
        val a = inferred == 1
        val b = 1 != Helpers.count
        val c = Helpers.count == inferred
        val d = new spinal.core.ElabBool == true
      }
    """, "UnstagedNativeEquality.scala")
    assert(errors.count(_.message.contains("MORPHDL-NATIVE-SYMBOLIC-EQUALITY-UNSTAGED")) == 4,
      errors.mkString("\n"))
  }
  test("null checks explicit identity and unrelated host equality remain ordinary") {
    val errors = compile(definitions + """
      object Probe {
        case class Bounds(value: spinal.core.ElabInt)
        val a = new spinal.core.ElabInt
        val b = new spinal.core.ElabInt
        val absent = a == null
        val identical = a eq b
        val host = "width" == a
        val ordinary = 7 == 7
      }
    """, "NativeIdentityChecks.scala")
    assert(errors.isEmpty, errors.mkString("\n"))
  }
}
