package spinal.core.internals

import java.nio.file.Files
import morphhdl.frontend.HdlInt
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

final case class BalancedFreshNestedCloneRecord(width: HdlInt, payloadWidth: HdlInt,
    depth: HdlInt) extends Bundle {
  val value = UInt(width bits)
  val payload = Vec(Vec(Bits(payloadWidth bits), depth), 2)
}

class TypedBalancedReductionNestedCloneShapeTests extends AnyFunSuite {
  private def elaborate(body: => Unit): Unit =
    SpinalConfig(targetDirectory = Files.createTempDirectory("nested-clone-shape-").toString,
      headerWithDate = false, headerWithRepoHash = false).generateVerilog(new Component {
      body
      val keep = out Bool()
      keep := False
    })

  private def source(): BalancedFreshNestedCloneRecord = {
    val result = BalancedFreshNestedCloneRecord(HdlInt.param("WIDTH", 3, 2, 5),
      HdlInt.param("PAYLOAD_WIDTH", 4, 1, 7), HdlInt.param("INNER", 2, 1, 3))
    result.value := 0
    result.payload.vec.foreach(_.vec.foreach(_ := 0))
    result
  }

  private def doubled(value: BaseType): ElaborationIntegerExpression = {
    val width = ParameterizedWidth.expressionOf(value).get
    ElaborationWidthAuthority.add(width, width)
  }

  test("nested widening clones retain independent widths and exact logical Vec depths through native cloning") {
    elaborate {
      val original = source()
      val outer = ParameterizedVec.shapeOf(original.payload).get
      val inner = ParameterizedVec.shapeOf(original.payload.vec.head).get
      val widths = original.flatten.toVector.map(doubled)
      val widened = TypedBalancedReductionCompositeReplay.cloneShape(original, widths)
        .asInstanceOf[BalancedFreshNestedCloneRecord]
      val widenedOuter = ParameterizedVec.shapeOf(widened.payload).get
      val widenedInner = ParameterizedVec.shapeOf(widened.payload.vec.head).get
      assert(widenedOuter.depth eq outer.depth)
      assert(widenedInner.depth eq inner.depth)
      assert(widenedInner.witnessDepth == 2 && widenedInner.carrierCapacity == 3)
      assert(widenedOuter.carrierCapacity == 2)
      assert(ElabInt.equivalentExpression(widenedInner.elementLeaves.head.width, widths(1)))
      assert(widenedOuter.logicalElementWidthDefault == 16)
      assert(widenedOuter.logicalElementWidthMaximum == 42)
      assert(ParameterizedVec.shapeOf(original.payload).contains(outer))
      assert(ParameterizedVec.shapeOf(original.payload.vec.head).contains(inner))
      assert(original.value.getBitsWidth == 3 && original.payload.vec.head.vec.head.getBitsWidth == 4)
      val cloned = cloneOf(widened)
      val hardTyped = HardType(widened)()
      val vectorClone = cloneOf(widened.payload)
      for (again <- Vector(cloned, hardTyped)) {
        assert(again.flatten.map(_.getBitsWidth) == widened.flatten.map(_.getBitsWidth))
        assert(ParameterizedVec.shapeOf(again.payload.vec.head).get.depth eq inner.depth)
        assert(ElabInt.equivalentExpression(
          ParameterizedVec.shapeOf(again.payload.vec.head).get.elementLeaves.head.width, widths(1)))
      }
      assert(ParameterizedVec.shapeOf(vectorClone).get.elementLayout.width(_.verilog) ==
        widenedOuter.elementLayout.width(_.verilog))
    }
  }

  test("fresh clone width substitution rejects caller-owned drivers and independent equal-default depth roots") {
    elaborate {
      val original = source()
      val assigned = cloneOf(original)
      assigned := original
      val before = ParameterizedVec.shapeOf(assigned.payload).get
      val driven = intercept[ParameterizedVerilogException] {
        ParameterizedVec.withFreshCloneWidths(original, assigned) { () }
      }
      assert(driven.code == "SPINAL-ELAB-VEC-FRESH-CLONE-SHAPE")
      assert(ParameterizedVec.shapeOf(assigned.payload).contains(before))
      val foreign = BalancedFreshNestedCloneRecord(original.width, original.payloadWidth,
        HdlInt.param("OTHER_INNER", 2, 1, 3))
      val foreignBefore = ParameterizedVec.shapeOf(foreign.payload.vec.head).get
      val root = intercept[ParameterizedVerilogException] {
        ParameterizedVec.withFreshCloneWidths(original, foreign) { () }
      }
      assert(root.code == "SPINAL-ELAB-VEC-FRESH-CLONE-SHAPE")
      assert(ParameterizedVec.shapeOf(foreign.payload.vec.head).contains(foreignBefore))
    }
  }

  test("failed nonuniform nested width substitution restores every fresh clone width and shape") {
    elaborate {
      val original = source()
      val target = cloneOf(original)
      val beforeOuter = ParameterizedVec.shapeOf(target.payload).get
      val beforeInner = ParameterizedVec.shapeOf(target.payload.vec.head).get
      val leaf = target.payload.vec.head.vec.head
      val beforeWidth = ParameterizedWidth.expressionOf(leaf).get
      val beforeFixed = leaf.fixedWidth
      val beforeObserved = leaf.widthWhenNotInferred
      val beforeInferred = leaf.inferredWidth
      val changed = doubled(leaf)
      val root = beforeWidth.completedParameterRoots.head
      val error = ElaborationDomainContext.withAdmitted(root, Set(beforeWidth.parameters.head.default), None) {
        val rejected = intercept[ParameterizedVerilogException] {
          ParameterizedVec.withFreshCloneWidths(original, target) {
            leaf.setWidth(changed.default.toInt)
            ParameterizedWidth.retainNativeMuxWidth(leaf, Some(changed))
          }
        }
        assert(ParameterizedWidth.expressionOf(leaf).get eq beforeWidth)
        assert(leaf.fixedWidth == beforeFixed)
        assert(leaf.widthWhenNotInferred == beforeObserved)
        assert(leaf.inferredWidth == beforeInferred)
        rejected
      }
      assert(error.code.contains("LAYOUT-MISMATCH"), error.getMessage)
      assert(ParameterizedVec.shapeOf(target.payload).contains(beforeOuter))
      assert(ParameterizedVec.shapeOf(target.payload.vec.head).contains(beforeInner))
      assert(leaf.getBitsWidth == 4)
      assert(ParameterizedWidth.expressionOf(leaf).contains(beforeWidth))
      assert(cloneOf(target).flatten.map(_.getBitsWidth) == original.flatten.map(_.getBitsWidth))
    }
  }

  test("recursive layout substitution preserves independent sibling dimensions and rejects same-witness root misbinding") {
    elaborate {
      val leftDepth = HdlInt.param("LEFT_INNER", 2, 1, 3).bits.expression.get
      val rightDepth = HdlInt.param("RIGHT_INNER", 2, 1, 3).bits.expression.get
      val leftWidth = HdlInt.param("LEFT_WIDTH", 4, 2, 6).bits.expression.get
      val rightWidth = HdlInt.param("RIGHT_WIDTH", 4, 2, 6).bits.expression.get
      import ParameterizedVecElementLayout._
      val layout = Layout(Fields(classOf[BalancedFreshNestedCloneRecord], Vector(
        "left" -> Dimension(leftDepth, 3, Scalar(TypeBits, leftWidth)),
        "right" -> Dimension(rightDepth, 3, Scalar(TypeBits, rightWidth)))))
      val wideLeft = ElaborationWidthAuthority.add(leftWidth, leftWidth)
      val wideRight = ElaborationWidthAuthority.add(rightWidth, rightWidth)
      val substituted = Vector.fill(3)(wideLeft) ++ Vector.fill(3)(wideRight)
      val changed = layout.withLeafWidths(substituted)
      assert(wideLeft.default == wideRight.default)
      assert(!ElaborationWidthAuthority.equivalent(wideLeft, wideRight))
      assert(changed.leaves.take(3).forall(_.dimensions.head._2 eq leftDepth))
      assert(changed.leaves.drop(3).forall(_.dimensions.head._2 eq rightDepth))
      assert(changed.leaves.map(_.dimensions.head._1) == Vector(0, 1, 2, 0, 1, 2))
      assert(changed.width(_.verilog).contains("LEFT_INNER"))
      assert(changed.width(_.verilog).contains("RIGHT_INNER"))
      val mismatched = intercept[ParameterizedVerilogException] {
        layout.withLeafWidths(substituted.updated(1, wideRight))
      }
      assert(mismatched.code == "SPINAL-ELAB-VEC-LAYOUT-WIDTH-SUBSTITUTION")
      intercept[ParameterizedVerilogException] {
        layout.withLeafWidths(substituted.updated(0, wideLeft.copy()))
      }
      assert(layout.leaves.head.width eq leftWidth)
      assert(layout.leaves.last.width eq rightWidth)
    }
  }
}
