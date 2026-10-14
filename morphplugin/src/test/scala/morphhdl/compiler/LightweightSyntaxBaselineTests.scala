package morphhdl.compiler

import org.scalatest.funsuite.AnyFunSuite

/** Parser/typechecking probes only. RTL semantics require the native suites. */
class LightweightSyntaxBaselineTests extends AnyFunSuite {
  import MorphHdlCompilerTestSupport._
  private val definitions = """
    package spinal.core {
      final class ElabBool
      final class ElabInt {
        def +(n: Int): ElabInt = this
        def /(n: Int): ElabInt = this
        def log2Up: ElabInt = this
        def addressWidth: ElabInt = this
        def pow2: ElabInt = this
        def isPow2: ElabBool = new ElabBool
        def >(n: Int): ElabBool = new ElabBool
        def elabEq(n: Int): ElabBool = new ElabBool
        def elabNe(n: Int): ElabBool = new ElabBool
      }
      final class ElabScopedCondition
      final class ElabFiniteIndex {
        def *(n: Int): ElabFiniteIndex = this
        def +(n: Int): ElabFiniteIndex = this
        def <(bound: ElabInt): ElabScopedCondition = new ElabScopedCondition
        def elabEq(n: Int): ElabScopedCondition = new ElabScopedCondition
        def elabNe(n: Int): ElabScopedCondition = new ElabScopedCondition
      }
      object ElabFiniteRange {
        def foreach(count: ElabInt, role: String)(body: ElabFiniteIndex => Unit): Unit = ()
      }
      object ElabControl {
        def generateSymbolic[T](p: ElabBool, file: String, line: Int)(body: => T): T = body
          def localValue[T](value: T, name: String): T = value
        def selectScoped[T](p: ElabScopedCondition, file: String, line: Int)(a: => T)(b: => T)(implicit unit: T =:= Unit): Unit = ()
        def selectSymbolic[T](p: ElabBool, file: String, line: Int)(a: => T)(b: => T): T = a
        def selectSymbolicChain[T](xs: Seq[(ElabBool, () => T, String, Int)], other: () => T, file: String, line: Int): T = other()
      }
    }
  """
  test("parameter equality and derived local guards do not require Boolean coercion") {
    val errors = compile(definitions + """
      package baseline {
        import spinal.core._
        object Probe {
          def stage(count: ElabInt): Int = {
            val next = (count + 1) / 2
            if (count == 1) 1 else if (next > 1) 2 else 3
          }
        }
      }
    """, "LightweightDerivedGuard.scala")
    assert(errors.isEmpty, errors.mkString("\n"))
  }
  test("a shadowing host integer retains ordinary Scala control") {
    val errors = compile(definitions + """
      package baseline {
        import spinal.core._
        object Probe {
          def stage(count: ElabInt): Int = {
            def concrete(count: Int): Int = if (count == 1) 7 else 9
            if (count != 1) concrete(4) else concrete(1)
          }
        }
      }
    """, "LightweightShadowing.scala")
    assert(errors.isEmpty, errors.mkString("\n"))
  }
  test("parameter values cannot escape as a host integer") {
    val errors = compile(definitions + """
      package baseline {
        object Probe { def concrete(count: spinal.core.ElabInt): Int = count }
      }
    """, "LightweightWitnessEscape.scala")
    assert(errors.exists(_.message.contains("type mismatch")), errors.mkString("\n"))
  }
  test("symbolic conditions cannot escape as host booleans") {
    val errors = compile(definitions + """
      package baseline {
        object Probe { def concrete(count: spinal.core.ElabInt): Boolean = count > 1 }
      }
    """, "LightweightBooleanEscape.scala")
    assert(errors.exists(_.message.contains("type mismatch")), errors.mkString("\n"))
  }
  test("zero-based symbolic until and to retain a finite index while concrete loops stay Int") {
    val errors = compile(definitions + """
      package baseline {
        import spinal.core._
        object Probe {
          def finite(i: ElabFiniteIndex): Unit = ()
          def ordinary(i: Int): Unit = ()
          def loops(count: ElabInt): Unit = {
            for (i <- 0 until count) finite(i)
            for (i <- 0 to count) finite(i)
            for (i <- 0 until 3) ordinary(i)
          }
        }
      }
    """, "LightweightRanges.scala")
    assert(errors.isEmpty, errors.mkString("\n"))
  }
  test("unsupported symbolic range starts fail explicitly") {
    val errors = compile(definitions + """
      package baseline {
        object Probe {
          def loops(count: spinal.core.ElabInt): Unit =
            for (i <- 1 until count) ()
        }
      }
    """, "LightweightRangeStart.scala")
    assert(errors.exists(_.message.contains("MORPHDL-TYPED-RANGE-START-UNSUPPORTED")), errors.mkString("\n"))
  }

  test("affine scoped guards stage statements and respect inner index shadowing") {
    val errors = compile(definitions + """
      package baseline {
        import spinal.core._
        object Probe {
          def loops(count: ElabInt): Unit = {
            for (i <- 0 until count) {
              val next = i * 2 + 1
              if (next < count) () else ()
              if (i == 0) () else ()
              if (0 != i) () else ()
              for (i <- 0 until 3) { if (i < 2) () else () }
            }
          }
        }
      }
    """, "LightweightAffineGuard.scala")
    assert(errors.isEmpty, errors.mkString("\n"))
  }
  test("scoped branches cannot return a representative host value") {
    val errors = compile(definitions + """
      package baseline {
        import spinal.core._
        object Probe {
          def loops(count: ElabInt): Unit = {
            for (i <- 0 until count) {
              val invalid: Int = if (i * 2 + 1 < count) 7 else 9
            }
          }
        }
      }
    """, "LightweightScopedValue.scala")
    assert(errors.nonEmpty, "scoped branches must not return a witness-selected host value")
  }

  test("unsupported scoped equality cannot fall back to host object equality") {
    val errors = compile(definitions + """
      package baseline {
        import spinal.core._
        object Probe {
          def loops(count: ElabInt): Unit = {
            for (i <- 0 until count) { if (i == count) () else () }
          }
        }
      }
    """, "LightweightScopedEquality.scala")
    assert(errors.exists(_.message.contains("MORPHDL-SCOPED-EQUALITY-UNSUPPORTED")), errors.mkString("\n"))
  }

  test("explicit helper return types stage local and member calls") {
    val errors = compile(definitions + """
      package baseline {
        import spinal.core._
        object Probe {
          def next(n: ElabInt): ElabInt = (n + 1) / 2
          def leaf(n: ElabInt): ElabBool = n.elabEq(1)
          def stage(count: ElabInt): Unit = {
            def local(n: ElabInt): ElabInt = next(n)
            if (leaf(count)) () else {
              for (i <- 0 until local(count)) ()
              if (next(count) > 1) () else ()
            }
          }
        }
      }
    """, "LightweightHelpers.scala")
    assert(errors.isEmpty, errors.mkString("\n"))
  }
  test("helper shadowing preserves ordinary return semantics") {
    val errors = compile(definitions + """
      package baseline {
        import spinal.core._
        object Probe {
          def leaf(n: ElabInt): ElabBool = n.elabEq(1)
          def stage(count: ElabInt): Int = {
            def leaf(n: Int): Boolean = n == 1
            if (leaf(1)) 7 else 9
          }
          def generic[ElabInt](value: ElabInt): ElabInt = value
          def host: Int = if (generic(true)) 7 else 9
        }
      }
    """, "LightweightHelperShadowing.scala")
    assert(errors.isEmpty, errors.mkString("\n"))
  }

  test("symbolic reverse and by reject with an explicit traversal diagnostic") {
    for (range <- Seq("(0 until count).reverse", "(0 until count).by(2)", "(0 to count).by(-1)")) {
      val errors = compile(definitions + s"""
        package baseline {
          object Probe {
            def loops(count: spinal.core.ElabInt): Unit = for (i <- $range) ()
          }
        }
      """, "LightweightRangeTraversal.scala")
      assert(errors.exists(_.message.contains("MORPHDL-TYPED-RANGE-TRAVERSAL-UNSUPPORTED")), errors.mkString("\n"))
    }
  }

  test("logarithmic geometry intermediates retain their symbolic carrier") {
    val errors = compile(definitions + """
      package baseline {
        import spinal.core._
        object Probe {
          def stage(count: ElabInt): Unit = {
            val levels = count.log2Up
            val words = levels.pow2
            val address = words.addressWidth
            if (levels == 0) () else for (i <- 0 until address) ()
            if (words.isPow2) () else ()
            val capacity: Int = 8
            for (level <- 0 until capacity) { if (levels == level) () }
          }
        }
      }
    """, "LightweightGeometryHelpers.scala")
    assert(errors.isEmpty, errors.mkString("\n"))
  }

}
