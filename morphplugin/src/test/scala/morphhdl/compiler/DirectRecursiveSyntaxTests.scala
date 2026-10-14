package morphhdl.compiler

import org.scalatest.funsuite.AnyFunSuite

class DirectRecursiveSyntaxTests extends AnyFunSuite {
  import MorphHdlCompilerTestSupport._
  private val definitions = """
    package spinal.core {
      final class rtlRecursive extends scala.annotation.StaticAnnotation
      class Component
      class Data
      final class ElabInt
      object RtlParam {
        def int(name: String, actual: ElabInt, lo: Int, hi: Int): ElabInt = actual
      }
      object ElabRecursiveComponent {
        def guardConstructor(c: Component): Unit = ()
        def inferred[P <: Data](bindings: Seq[(ElabInt, ElabInt)])(factory: Vector[ElabInt] => P): Declaration[P] =
          new Declaration(factory(bindings.map(_._1).toVector))
        class Declaration[P <: Data](val ports: P) {
          def apply(overrides: (ElabInt, ElabInt)*): Reference[P] = new Reference(ports)
        }
        class Reference[P <: Data](val io: P) { def authoredName(name: String): this.type = this }
      }
    }
  """
  private def fixture(call: String, cls: String = "final class"): String = definitions + s"""
    package direct {
      import spinal.core._
      class Ports(val width: ElabInt) extends Data
      @_root_.spinal.core.rtlRecursive
      $cls Tree(countActual: ElabInt, widthActual: ElabInt, operation: Int) extends Component {
        val count: ElabInt = RtlParam.int("COUNT", countActual, 1, 64)
        val width: ElabInt = RtlParam.int("WIDTH", widthActual, 1, 64)
        val io = new Ports(width)
        val next = $call
        val returned = next.io.width
      }
    }
  """
  test("direct self construction infers ports and parameter overrides") {
    val errors = compile(fixture("new Tree(count, width, operation)"), "DirectRecursive.scala")
    assert(errors.isEmpty, errors.mkString("\n"))
  }
  test("specialization changes cannot hide in recursive construction") {
    val errors = compile(fixture("new Tree(count, width, operation + 1)"), "ChangedSpecialization.scala")
    assert(errors.exists(_.message.contains("recursive specialization arguments")), errors.mkString("\n"))
  }
  test("constructor shadowing cannot borrow specialization identity") {
    val errors = compile(fixture("{ val operation = 7; new Tree(count, width, operation) }"), "ShadowedRecursive.scala")
    assert(errors.exists(_.message.contains("bindings are shadowed")), errors.mkString("\n"))
  }
  test("unsupported inheritance and missing explicit bindings reject") {
    for (source <- Seq(fixture("new Tree(count, width, operation)", "class"),
        fixture("new Tree(count, width)"))) {
      val errors = compile(source, "UnsupportedRecursive.scala")
      assert(errors.exists(_.message.contains("MORPHDL-DIRECT-RECURSION-UNSUPPORTED")), errors.mkString("\n"))
    }
  }
}
