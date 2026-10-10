package morphhdl.compiler

import org.scalatest.funsuite.AnyFunSuite

class MorphHdlAreaCollectionComponentTests extends AnyFunSuite {
  import MorphHdlCompilerTestSupport._
  private val definitions = """
package spinal.core {
  class Area
  class ElabInt
  object ElabInt { def literal(n: Int): ElabInt = new ElabInt }
  class Index {
    def choose[T](n: Int, label: String)(yes: => T)(no: => T): T = yes
    def onlyEqual(n: Int, label: String)(body: => Unit): Unit = body
  }
  class Collection[T] {
    def member[D](n: Int)(f: T => D): D = f(null.asInstanceOf[T])
    def documentation: Documentation[T] = new Documentation[T]
  }
  class Documentation[T] { def foreach(f: ((T, String)) => Unit): Unit = () }
  object ElabAreaCollection {
    def tabulate[T <: Area](n: ElabInt, name: String)(f: Index => T): Collection[T] = new Collection[T]
  }
}
"""

  test("Area bridge preserves ordinary loops without structural index branches") {
    assert(compile(definitions + """
import spinal.core._
object Example {
  val areas = for (index <- 0 until 3) yield new Area { val offset = index + 2 }
  val offsets: Seq[Int] = areas.map(_.offset)
}
""").isEmpty)
  }

  test("Area bridge respects nested Scala index shadowing") {
    val errors = compile(definitions + """
import spinal.core._
object Example {
  val areas = for (index <- 0 until 3) yield new Area {
    val selected = if (index == 0) true else false
    val ordinary: Int = (0 until 2).map { index => if (index == 0) 3 else 5 }.sum
  }
  val first: Boolean = areas(0).selected
}
""")
    assert(errors.isEmpty, errors.mkString("\n"))
  }

  test("Area bridge does not intercept unrelated Area classes") {
    assert(compile("""
class Area
object Example {
  val areas = for (index <- 0 until 3) yield new Area { val selected = if (index == 0) 1 else 2 }
  val values: Seq[Int] = areas.map(_.selected)
}
""").isEmpty)
    assert(compile(definitions + """
import spinal.core._
object Example {
  class Area
  val areas = for (index <- 0 until 3) yield new Area { val selected = if (index == 0) 1 else 2 }
  val values: Seq[Int] = areas.map(_.selected)
}
""").isEmpty)
  }

  test("Area bridge rejects unsupported aliases of the retained index") {
    val errors = compile(definitions + """
import spinal.core._
object Example {
  val areas = for (index <- 0 until 3) yield new Area {
    val escaped = index
    val selected = if (index == 0) true else false
  }
}
""")
    assert(errors.exists(_.message.contains("SPINAL-ELAB-AREA-INDEX-USE-UNSUPPORTED")), errors.mkString("\n"))
  }

  test("Area bridge rejects ordinary collection iteration over a retained template") {
    val errors = compile(definitions + """
import spinal.core._
object Example {
  val areas = for (index <- 0 until 3) yield new Area { val selected = if (index == 0) true else false }
  areas.zipWithIndex.foreach { case (area, index) => println(index) }
}
""")
    assert(errors.exists(_.message.contains("SPINAL-ELAB-AREA-COLLECTION-ITERATION-UNSUPPORTED")), errors.mkString("\n"))
  }
}
