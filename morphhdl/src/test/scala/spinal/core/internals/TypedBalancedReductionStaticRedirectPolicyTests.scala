package spinal.core.internals

import java.nio.file.Files
import scala.collection.JavaConverters._
import morphhdl.frontend.HdlInt
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._

class TypedBalancedReductionStaticRedirectPolicyTests extends AnyFunSuite {
  private def elaborate(component: => Component): Unit = {
    val directory = Files.createTempDirectory("balanced-static-redirect-")
    try SpinalConfig(targetDirectory = directory.toString).generateVerilog(component)
    finally {
      val paths = Files.walk(directory)
      try paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists(_))
      finally paths.close()
    }
  }

  private def native: ElabBalancedReduction.Native[UInt] =
    (values, operation, bridge) =>
      new TraversableOnceAnyPimped[UInt](values).reduceBalancedTree(operation, bridge)

  test("a retained scalar static index remains a certified input across repeated reductions") {
    elaborate(new Component {
      val words = in(Vec(UInt(5 bits), HdlInt.param("COUNT", 1, 1, 3)))
      val selected, sum, parity = out(UInt(5 bits))
      val first = words(0)
      assert(first.compositeAssign.isInstanceOf[ParameterizedVecStaticAccessAssign])
      // Nested retained scalar aliases can add another known wrapper. Every
      // link must independently denote this same exact native scalar.
      first.compositeAssign = new ParameterizedVecStaticAccessAssign(
        words, 0, 0, first, first.compositeAssign)
      TypedBalancedReductionCallbackPolicy.requireSupportedValues(Vector(words, first))
      selected := first
      val add = TypedBalancedReductionCapture(words, (a: UInt, b: UInt) => a + b,
        (value: UInt, _: Int) => value, native)
      val xor = TypedBalancedReductionCapture(words, (a: UInt, b: UInt) => a ^ b,
        (value: UInt, _: Int) => value, native)
      assert(add.rows.exists(_.operator.nonEmpty) && xor.rows.exists(_.operator.nonEmpty))
      assert(add.result ne xor.result)
      sum := add.result
      parity := xor.result
    })
  }

  test("a known static wrapper around an opaque previous redirect rejects before any host hook") {
    elaborate(new Component {
      val words = in(Vec(UInt(5 bits), HdlInt.param("COUNT", 1, 1, 3)))
      val keep = out(Bool())
      keep := False
      val first = words.vec.head
      var calls = 0
      val opaque = new Assignable {
        override protected def assignFromImpl(that: AnyRef, target: AnyRef, kind: AnyRef)
            (implicit location: spinal.idslplugin.Location): Unit = { calls += 1 }
        override def getRealSourceNoRec: Any = { calls += 1; this }
      }
      first.compositeAssign = opaque
      val indexed = words(0)
      assert(indexed.compositeAssign.isInstanceOf[ParameterizedVecStaticAccessAssign])
      try {
        val error = intercept[IllegalArgumentException] {
          TypedBalancedReductionCallbackPolicy.requireSupportedValues(Vector(words))
          indexed.assignFrom(words.vec(1))
        }
        assert(error.getMessage.contains("opaque native assignment redirect"), error.getMessage)
        assert(calls == 0, "an opaque assignment or source hook ran before callback rejection")
      } finally first.compositeAssign = null
    })
  }

  test("static redirect admission rejects wrong leaf index identity shape and previous-chain evidence") {
    elaborate(new Component {
      val words = in(Vec(UInt(5 bits), HdlInt.param("COUNT", 1, 1, 3)))
      val ordinary = in(Vec(UInt(5 bits), 3))
      val keep = out(Bool())
      keep := False
      val first = words.vec.head
      val second = words.vec(1)
      val candidates = Vector(
        new ParameterizedVecStaticAccessAssign(words, 0, 0, second, null),
        new ParameterizedVecStaticAccessAssign(words, 1, 0, first, null),
        new ParameterizedVecStaticAccessAssign(words, 0, 1, first, null),
        new ParameterizedVecStaticAccessAssign(words, -1, 0, first, null),
        new ParameterizedVecStaticAccessAssign(words, words.vec.size, 0, first, null),
        new ParameterizedVecStaticAccessAssign(ordinary, 0, 0, first, null),
        new ParameterizedVecStaticAccessAssign(words, 0, 0, first,
          new ParameterizedVecStaticAccessAssign(words, 1, 0, second, null)))
      candidates.foreach { redirect =>
        first.compositeAssign = redirect
        try {
          val error = intercept[IllegalArgumentException] {
            TypedBalancedReductionCallbackPolicy.requireSupportedValues(Vector(words))
          }
          assert(error.getMessage.contains("opaque native assignment redirect"), error.getMessage)
        } finally first.compositeAssign = null
      }
      // Matching scalar identities alone do not authorize an unretained Vec.
      ordinary.vec.head.compositeAssign = new ParameterizedVecStaticAccessAssign(
        ordinary, 0, 0, ordinary.vec.head, null)
      try {
        val error = intercept[IllegalArgumentException] {
          TypedBalancedReductionCallbackPolicy.requireSupportedValues(Vector(ordinary))
        }
        assert(error.getMessage.contains("opaque native assignment redirect"), error.getMessage)
      } finally ordinary.vec.head.compositeAssign = null
    })
  }
}
