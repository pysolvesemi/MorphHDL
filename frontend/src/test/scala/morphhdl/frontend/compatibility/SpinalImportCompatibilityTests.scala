package morphhdl.frontend.compatibility

import spinal.core._
import morphhdl.frontend._
import morphhdl.frontend.ParamRtlFrontend.{captureItems, emitInstance}
import morphhdl.paramrtl.IntExpr.ParameterRef
import morphhdl.paramrtl.IntExpr.{Add, Literal}
import morphhdl.paramrtl.ModuleItem.GenerateFor
import org.scalatest.funsuite.AnyFunSuite

class SpinalImportCompatibilityTests extends AnyFunSuite {
  test("keeps the native symbolic for spelling alongside spinal.core imports") {
    val lanes = HdlInt.param("LANES", default = 4, min = 1, max = 64)
    var invocations = 0

    val items = captureItems {
      for (lane <- 0 until lanes) {
        invocations += 1
        emitInstance(name = "lane_inst", moduleName = "PixelLane")
      }
    }

    assert(invocations == 1)
    assert(items.raw.size == 1)
    assert(items.raw.head.asInstanceOf[GenerateFor].count == ParameterRef("LANES"))
  }

  test("keeps Int-left symbolic arithmetic unambiguous alongside spinal.core imports") {
    val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 64)
    val expression: HdlInt = 3 + width

    assert(expression.witness == 11)
    assert(expression.expression == Add(Literal(3), ParameterRef("WIDTH")))
  }
}
