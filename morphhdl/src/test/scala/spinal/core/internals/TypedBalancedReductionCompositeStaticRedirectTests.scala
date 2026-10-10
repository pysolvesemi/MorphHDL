package spinal.core.internals

import java.nio.file.Files
import morphhdl.frontend.HdlInt
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._

/** Exact read-wrapper evidence is shared by scalar and recursively owned Data.
  * Rejection controls run BEFORE either user callback or Assignable hook.
  */
class TypedBalancedReductionCompositeStaticRedirectTests extends AnyFunSuite {
  private def elaborate(body: => Unit): Unit = {
    SpinalConfig(targetDirectory = Files.createTempDirectory("balanced-composite-static-").toString,
      headerWithDate = false, headerWithRepoHash = false).generateVerilog(new Component {
      val anchor = out(Bool())
      anchor := False
      body
    })
  }

  private def records: Vec[BalancedCompositeRecord] =
    in(Vec(BalancedCompositeRecord(HdlInt.literal(5), HdlInt.literal(5), HdlInt.literal(5)),
      HdlInt.param("COUNT", 1, 1, 3)))

  private def reject(values: Seq[Data]): Unit = {
    val error = intercept[IllegalArgumentException] {
      TypedBalancedReductionCallbackPolicy.requireSupportedValues(values)
    }
    assert(error.getMessage.contains("CALLBACK-UNSUPPORTED"), error.getMessage)
  }

  test("whole records remain certifiable after a static read and repeated native reductions") {
    elaborate {
      val values = records
      val first = values(0)
      first.flatten.foreach(leaf => assert(leaf.compositeAssign.isInstanceOf[ParameterizedVecStaticAccessAssign]))
      TypedBalancedReductionCallbackPolicy.requireSupportedValues(values.vec)
      TypedBalancedReductionCallbackPolicy.requireSupportedValues(Vector(values, first))
      // Individual scalar entry must still audit its containing record first.
      TypedBalancedReductionCallbackPolicy.requireSupportedValues(Vector(first.tag, first.x))
      val native: ElabBalancedReduction.Native[BalancedCompositeRecord] =
        (items, operation, bridge) => new TraversableOnceAnyPimped[BalancedCompositeRecord](items)
          .reduceBalancedTree(operation, bridge)
      val min = TypedBalancedReductionCompositeReplay.capture(values,
        (a: BalancedCompositeRecord, b: BalancedCompositeRecord) => Mux(a.key <= b.key, a, b),
        (a: BalancedCompositeRecord, _: Int) => a, native)
      val max = TypedBalancedReductionCompositeReplay.capture(values,
        (a: BalancedCompositeRecord, b: BalancedCompositeRecord) => Mux(a.key >= b.key, a, b),
        (a: BalancedCompositeRecord, _: Int) => a, native)
      min.requireFreshness(); max.requireFreshness()
      assert(min.captured.rows.count(_.operator.nonEmpty) == 2)
      assert(max.captured.rows.count(_.operator.nonEmpty) == 2)
    }
  }

  test("recursive field and inner-Vec wrappers retain exact element and leaf identity") {
    elaborate {
      val innerCount = HdlInt.param("INNER", 2, 1, 3)
      val values = in(Vec(BalancedStaticNested(innerCount), HdlInt.param("COUNT", 1, 1, 3)))
      val first = values(0)
      val inner = first.lanes(0)
      val leaf = inner.signed
      assert(leaf.compositeAssign.isInstanceOf[ParameterizedVecStaticAccessAssign])
      TypedBalancedReductionCallbackPolicy.requireSupportedValues(Vector(values, first, inner, leaf))
      val wrapper = leaf.compositeAssign
      leaf.compositeAssign = new ParameterizedVecStaticAccessAssign(values, 0, 2, leaf, wrapper)
      try TypedBalancedReductionCallbackPolicy.requireSupportedValues(Vector(first))
      finally leaf.compositeAssign = wrapper
    }
  }

  test("equal-width fields cannot impersonate a different element or leaf") {
    elaborate {
      val values = records
      val first = values.vec.head
      val wrong = values.vec(1)
      val leaf = first.x
      val wrappers = Vector(
        new ParameterizedVecStaticAccessAssign(values, 0, 2, first.y, null),
        new ParameterizedVecStaticAccessAssign(values, 1, 2, leaf, null),
        new ParameterizedVecStaticAccessAssign(values, 0, 3, leaf, null),
        new ParameterizedVecStaticAccessAssign(values, -1, 2, leaf, null),
        new ParameterizedVecStaticAccessAssign(values, 3, 2, leaf, null),
        new ParameterizedVecStaticAccessAssign(values, 0, -1, leaf, null),
        new ParameterizedVecStaticAccessAssign(values, 0, 4, leaf, null),
        new ParameterizedVecStaticAccessAssign(values, 0, 2, leaf,
          new ParameterizedVecStaticAccessAssign(values, 1, 2, wrong.x, null)))
      wrappers.foreach { wrapper =>
        leaf.compositeAssign = wrapper
        try reject(Vector(first)) finally leaf.compositeAssign = null
      }
    }
  }

  test("unretained Vec and mismatching shape cannot lend wrapper authority") {
    elaborate {
      val ordinary = in(Vec(BalancedCompositeSafeValue(), 3))
      val leaf = ordinary.vec.head.value
      leaf.compositeAssign = new ParameterizedVecStaticAccessAssign(ordinary, 0, 0, leaf, null)
      try reject(Vector(leaf)) finally leaf.compositeAssign = null
      val values = records
      val shape = ParameterizedVec.shapeOf(values).get
      val first = values.vec.head
      first.x.compositeAssign = new ParameterizedVecStaticAccessAssign(values, 0, 2, first.x, null)
      // The evidence reader must check the complete retained leaf count, not
      // merely a selected field's width or matching scalar object identity.
      val snapshot = new java.util.IdentityHashMap[Data, Vector[BaseType]]()
      snapshot.put(first, Vector(first.key, first.tag, first.x))
      assert(!first.x.compositeAssign.asInstanceOf[ParameterizedVecStaticAccessAssign]
        .isCertifiedReadOf(first.x, snapshot))
      assert(shape.elementLeaves.size == 4)
      first.x.compositeAssign = null
    }
  }

  test("an opaque previous assignment hook is rejected without invoking it") {
    elaborate {
      val values = records
      val first = values.vec.head
      var calls = 0
      val opaque = new Assignable {
        override protected def assignFromImpl(that: AnyRef, target: AnyRef, kind: AnyRef)
            (implicit location: spinal.idslplugin.Location): Unit = { calls += 1 }
        override def getRealSourceNoRec: Any = { calls += 1; this }
      }
      first.tag.compositeAssign = new ParameterizedVecStaticAccessAssign(values, 0, 1, first.tag, opaque)
      try {
        reject(Vector(first.tag))
        assert(calls == 0, "a source/assignment hook ran during read admission")
      } finally first.tag.compositeAssign = null
    }
  }

  test("scalar wrapper entry cannot bypass an unsafe containing Bundle audit") {
    elaborate {
      val values = in(Vec(BalancedCompositeUnsafeClone(), HdlInt.param("COUNT", 1, 1, 3)))
      val leaf = values(0).value
      val before = BalancedCompositePolicyState.calls
      reject(Vector(leaf))
      assert(BalancedCompositePolicyState.calls == before, "unaudited clone or hook executed")
    }
  }

  test("excessively deep wrapper chains reject deterministically") {
    elaborate {
      val values = records
      val first = values.vec.head
      val leaf = first.x
      var wrapper: Assignable = null
      for (_ <- 0 until 65) wrapper = new ParameterizedVecStaticAccessAssign(values, 0, 2, leaf, wrapper)
      leaf.compositeAssign = wrapper
      try reject(Vector(first)) finally leaf.compositeAssign = null
    }
  }
}

final case class BalancedStaticLeaf() extends Bundle {
  val unsigned = UInt(5 bits)
  val signed = SInt(5 bits)
}
final case class BalancedStaticNested(inner: HdlInt) extends Bundle {
  val tag = Bits(5 bits)
  val lanes = Vec(BalancedStaticLeaf(), inner)
}
