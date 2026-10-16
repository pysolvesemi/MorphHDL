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
        def foreachDefault(count: ElabInt)(body: ElabFiniteIndex => Unit): Unit = ()
      }
      object ElabControl {
        def generateSymbolic[T](p: ElabBool, file: String, line: Int)(body: => T): T = body
          def localValue[T](value: T, name: String): T = value
        def generateScoped(p: ElabScopedCondition, file: String, line: Int)(a: => Unit): Unit = ()
        def selectScoped[T](p: ElabScopedCondition, file: String, line: Int)(a: => T)(b: => T)(implicit unit: T =:= Unit): Unit = ()
        def selectSymbolic[T](p: ElabBool, file: String, line: Int)(a: => T)(b: => T): T = a
        def generateSymbolicChain(xs: Seq[(ElabBool, () => Unit, String, Int)], file: String, line: Int): Unit = ()
        def selectSymbolicChain[T](xs: Seq[(ElabBool, () => T, String, Int)], other: () => T, file: String, line: Int): T = other()
      }
    }
  """
  test("single-expression parameter helpers infer through forward lexical calls") {
    val errors = compile(definitions + """
      package inferredhelpers {
        import spinal.core._
        object Probe {
          def stage(count: ElabInt): Int = {
            def upper(x: ElabInt) = lower(x)
            def lower(x: ElabInt) = (x + 1) / 2
            val next = upper(count)
            if(next > 1) 7 else 9
          }
        }
      }
    """, "InferredHelpers.scala")
    assert(errors.isEmpty,errors.mkString("\n"))
  }
  test("ordinary helper shadowing and metadata methods do not gain symbolic meaning") {
    val errors = compile(definitions + """
      package ordinaryhelpers {
        import spinal.core._
        object Probe {
          def half(x: ElabInt) = (x + 1) / 2
          def stage(count: ElabInt): Int = {
            def half(x: Int) = x / 2
            def description(x: ElabInt) = x.toString.length
            if(half(4) == 2 && description(count) >= 0) 1 else 0
          }
        }
      }
    """, "OrdinaryHelpers.scala")
    assert(errors.isEmpty,errors.mkString("\n"))
  }
  test("statement chains with no final else remain Unit and ordered") {
    val errors = compile(definitions + """
      package statementchain {
        import spinal.core._
        object Probe {
          def stage(count: ElabInt): Unit = {
            if (count == 1) new Object
            else if (count == 2) "discarded result"
          }
        }
      }
    """, "StatementChain.scala")
    assert(errors.isEmpty, errors.mkString("\n"))
  }
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

  for (body <- Seq("host = 1", "host += 1", "var local = 0; ()", "return ()", "throw new RuntimeException(\"effect\")");
       form <- Seq("branch", "range", "scoped")) {
    test(s"ordinary staged $form rejects direct host effect $body") {
      val statement = form match {
        case "branch" => s"if(count > 1) { $body } else ()"
        case "range" => s"for(i <- 0 until count) { $body }"
        case "scoped" => s"for(i <- 0 until count) { if(i < count) { $body } }"
      }
      val errors = compile(definitions + s"""
        package stageeffects {
          import spinal.core._
          object Probe {
            def run(count: ElabInt): Unit = { var host=0; $statement }
          }
        }
      """, "StagedEffects.scala")
      assert(errors.exists(_.message.contains("MORPHDL-STAGED-HOST-EFFECT-UNSUPPORTED")), errors.mkString("\n"))
    }
  }
  test("ordinary concrete mutation is unchanged and a val hardware operator is not a host variable assignment") {
    val errors = compile(definitions + """
      package stageeffectcontrols {
        import spinal.core._
        class Hardware {
          def :=(value: Int): Unit = ()
          def +=(value: Int): Unit = ()
        }
        object Probe {
          def concrete(n: Int): Int = {
            var host=0
            for(i <- 0 until n) host += i
            if(n == 0) return 0 else host
          }
          def staged(count: ElabInt): Unit = {
            var value=0
            for(i <- 0 until count) {
              val value = new Hardware
              value := 1
              value += 2
            }
          }
        }
      }
    """, "StagedEffectControls.scala")
    assert(errors.isEmpty, errors.mkString("\n"))
  }

  test("explicit generate preserves native metadata capture but cannot escape an ordinary staged body") {
    val compatible = compile(definitions + """
      package advancedgenerate {
        import spinal.core._
        object Probe {
          def run(condition: ElabBool): Unit = {
            var captured=0
            condition.generate { captured = 1 }
          }
        }
      }
    """, "AdvancedGenerate.scala")
    assert(compatible.isEmpty,compatible.mkString("\n"))
    val nested = compile(definitions + """
      package nestedgenerate {
        import spinal.core._
        object Probe {
          def run(count: ElabInt, condition: ElabBool): Unit = {
            var captured=0
            if(count > 1) condition.generate { captured = 1 }
          }
        }
      }
    """, "NestedGenerate.scala")
    assert(nested.exists(_.message.contains("MORPHDL-STAGED-HOST-EFFECT-UNSUPPORTED")),nested.mkString("\n"))
  }

  test("staged lexical helper effects propagate through forward calls and parameterless references") {
    for (body <- Seq(
      "def first(): Unit = second(); def second(): Unit = { host += 1 }; for(i <- 0 until count) first()",
      "def first: Unit = second; def second: Unit = { host = 2 }; if(count > 1) first",
      "def first(): Unit = { throw new IllegalArgumentException }; if(count > 1) first()",
      "def first(): Unit = { return }; for(i <- 0 until count) first()",
      "def first(): Unit = { var local = 0; local += 1 }; if(count > 1) first()"
    )) {
      val errors = compile(definitions + s"""
        package helpereffects {
          import spinal.core._
          object Probe { def run(count: ElabInt): Unit = { var host=0; $body } }
        }
      """, "HelperEffects.scala")
      assert(errors.exists(_.message.contains("MORPHDL-STAGED-HOST-EFFECT-UNSUPPORTED")), errors.mkString("\n"))
    }
  }

  test("helper effects preserve nearest lexical identity and concrete calls") {
    val errors = compile(definitions + """
      package helpercontrols {
        import spinal.core._
        object Probe {
          var host=0
          def effect(): Unit = { host += 1 }
          def run(count: ElabInt): Unit = {
            effect()
            def pure(): Unit = ()
            for(i <- 0 until count) {
              def effect(): Unit = pure()
              effect()
            }
          }
        }
      }
    """, "HelperControls.scala")
    assert(errors.isEmpty, errors.mkString("\n"))
  }

  test("forward local mutable declarations retain effect identity inside staged helpers") {
    val errors = compile(definitions + """
      package forwardmutation {
        import spinal.core._
        object Probe {
          def run(count: ElabInt): Unit = {
            def mutate(): Unit = { for(i <- 0 until count) host += 1 }
            var host=0
            mutate()
          }
        }
      }
    """, "ForwardMutation.scala")
    assert(errors.exists(_.message.contains("MORPHDL-STAGED-HOST-EFFECT-UNSUPPORTED")), errors.mkString("\n"))
  }

  test("this-qualified effects retain template identity through local shadowing") {
    for(body<-Seq(
      "for(i <- 0 until count) { val state=new Hardware; this.state += 1 }",
      "if(count > 1) this.mutate()",
      "if(count > 1) { def mutate(): Unit=(); this.mutate() }"
    )) {
      val errors=compile(definitions+s"""
        package thiseffects {
          import spinal.core._
          class Hardware { def +=(v: Int): Unit=() }
          class Probe {
            var state=0
            def mutate(): Unit={ this.state += 1 }
            def run(count: ElabInt): Unit={ $body }
          }
        }
      ""","ThisEffects.scala")
      assert(errors.exists(_.message.contains("MORPHDL-STAGED-HOST-EFFECT-UNSUPPORTED")),errors.mkString("\n"))
    }
    val positive=compile(definitions+"""
      package thiscontrols {
        import spinal.core._
        class Hardware { def +=(v: Int): Unit=() }
        class Probe {
          val state=new Hardware
          def pure(): Unit=()
          def run(count: ElabInt): Unit={
            var state=0
            for(i <- 0 until count) { this.state += 1; this.pure() }
          }
        }
      }
    ""","ThisControls.scala")
    assert(positive.isEmpty,positive.mkString("\n"))
  }

  test("this-qualified symbolic members retain template identity under local shadowing") {
    val errors = compile(definitions + """
      package thismembers {
        import spinal.core._
        class Probe(val count: ElabInt) {
          def half(x: ElabInt) = (x + 1) / 2
          def value = this.half(count)
          def stage: Int = {
            val count = 4
            def half(x: Int) = x / 2
            val value = 0
            if(this.count > 1) {
              if(this.half(this.count) > 1) 7 else 9
            } else if(this.value > 1) 3 else 5
          }
        }
      }
    """, "ThisMembers.scala")
    assert(errors.isEmpty, errors.mkString("\n"))
  }

  test("this-qualified ordinary members cannot borrow symbolic local identities") {
    val errors = compile(definitions + """
      package thisordinary {
        import spinal.core._
        object Probe {
          val count = 4
          def half(x: Int) = x / 2
          def stage(count: ElabInt): Int = {
            def half(x: ElabInt) = x + 1
            if(this.count > 1 && this.half(4) == 2) 7 else 9
          }
        }
      }
    """, "ThisOrdinary.scala")
    assert(errors.isEmpty, errors.mkString("\n"))
  }

  test("qualified outer this resolves its named template through nested shadowing") {
    val errors = compile(definitions + """
      package outerthis {
        import spinal.core._
        class Outer(val count: ElabInt) {
          def half(x: ElabInt) = (x + 1) / 2
          class Inner {
            val count = 4
            def half(x: Int) = x / 2
            def stage: Int = {
              val count = 0
              if(Outer.this.count > 1) {
                if(Outer.this.half(Outer.this.count) > 1) 7 else 9
              } else if(this.count > 1) 3 else 5
            }
          }
        }
      }
    """, "OuterThis.scala")
    assert(errors.isEmpty, errors.mkString("\n"))
  }

  test("qualified outer this retains staged helper mutation rejection") {
    val errors = compile(definitions + """
      package outereffects {
        import spinal.core._
        class Outer(val count: ElabInt) {
          var state = 0
          def mutate(): Unit = { state += 1 }
          class Inner {
            def mutate(): Unit = ()
            def stage: Unit = if(count > 1) Outer.this.mutate()
          }
        }
      }
    """, "OuterEffects.scala")
    assert(errors.exists(_.message.contains("MORPHDL-STAGED-HOST-EFFECT-UNSUPPORTED")), errors.mkString("\n"))
  }
  test("anonymous template this does not replace an explicitly qualified outer owner") {
    val errors = compile(definitions + """
      package anonymousthis {
        import spinal.core._
        class Outer(val count: ElabInt) {
          trait Member { def stage: Int }
          val member = new Member {
            val count = 3
            def stage: Int =
              if(Outer.this.count > 1) 7 else if(this.count > 1) 9 else 11
          }
        }
      }
    """, "AnonymousThis.scala")
    assert(errors.isEmpty, errors.mkString("\n"))
  }
}
