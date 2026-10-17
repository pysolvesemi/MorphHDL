package spinal.core.internals

import java.nio.file.Files
import morphhdl.MorphVerilog
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class SnapshotWidthChild(actual: ElabInt) extends Component {
  val width: Param[Int] = Param(actual, "WIDTH")
  require(width >= 1 && width <= 65535)
  val source = in Bits((width + 1) bits)
  val result = out Bits((width + 1) bits)
  result := ~source
}
class SnapshotWidthParent extends Component {
  val width: Param[Int] = Param(3)
  require(width >= 1 && width <= 65535)
  val source = in Bits((width + 1) bits)
  val result = out Bits((width + 1) bits)
  val first = new SnapshotWidthChild(width)
  val second = new SnapshotWidthChild(width)
  first.source := source
  second.source := source
  result := first.result
  val otherResult = out Bits((width + 1) bits)
  otherResult := second.result
}

class HierarchyBindingSnapshotTests extends AnyFunSuite {
  test("read-only binding snapshots reuse proofs only for exact components and expire on exit") {
    var top: SnapshotWidthParent = null
    MorphVerilog(SpinalConfig(targetDirectory=Files.createTempDirectory("binding-snapshot-").toString,
      bitVectorWidthMax=65536, headerWithDate=false)) {
      top = new SnapshotWidthParent
      top
    }
    val a=top.first; val b=top.second
    val af=a.width.expression.parameters.head; val bf=b.width.expression.parameters.head
    assert(af == bf && (af ne bf))
    var previous: Vector[ExternalTypedFormalBinding] = null
    NativeWidthFormalSchema.withBindingSnapshot {
      previous=NativeWidthFormalSchema.bindings(a)
      assert(previous.nonEmpty)
      for (_ <- 0 until 200) {
        // Exercise repeated exact-owner queries over the full finite domain.
        // Results retain their original declaration identities.
        assert(NativeWidthFormalSchema.bindings(a) eq previous)
        assert(NativeWidthFormalSchema.witness(a,b,af) eq bf)
      }
      val copied=af.copy()
      assert(NativeWidthFormalSchema.witness(a,b,copied) eq copied)
      assert(!NativeWidthFormalSchema.parameterCompatible(a,b,copied,bf))
      val other=NativeWidthFormalSchema.bindings(b)
      assert(other ne previous)
      assert(other.exists(_.binding.formal eq bf))
      assert(!other.exists(_.binding.formal eq af))
      intercept[IllegalStateException] {
        NativeWidthFormalSchema.withBindingSnapshot {
          assert(NativeWidthFormalSchema.bindings(a).nonEmpty)
          throw new IllegalStateException("snapshot unwind")
        }
      }
      assert(NativeWidthFormalSchema.bindings(a) eq previous)
    }
    assert(NativeWidthFormalSchema.bindings(a).nonEmpty)
    NativeWidthFormalSchema.withBindingSnapshot {
      assert(NativeWidthFormalSchema.bindings(a).nonEmpty)
    }
    // A later phase cannot reuse the earlier proof after changing ownership.
    val scope=a.source.parentScope
    try {
      a.source.parentScope=b.dslBody
      intercept[ParameterizedVerilogException] {
        NativeWidthFormalSchema.withBindingSnapshot { NativeWidthFormalSchema.bindings(a) }
      }
    } finally { a.source.parentScope=scope }
    NativeWidthFormalSchema.withBindingSnapshot { assert(NativeWidthFormalSchema.bindings(a).nonEmpty) }
  }
}
