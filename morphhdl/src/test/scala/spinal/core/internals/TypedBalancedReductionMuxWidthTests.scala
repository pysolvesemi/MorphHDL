package spinal.core.internals

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import morphhdl.frontend.HdlInt

class TypedBalancedReductionMuxWidthTests extends AnyFunSuite {
  private def generate(body: => Component): Unit =
    SpinalConfig(targetDirectory = Files.createTempDirectory("reduce-mux-width-").toString,
      headerWithDate = false, headerWithRepoHash = false).generateVerilog(body)

  test("native mux preserves the exact common typed arm-width authority") {
    val width = HdlInt.param("WIDTH", 5, 1, 32)
    generate(new Component {
      val a, b = in(UInt(width bits))
      val select = in Bool()
      val result = Mux(select, a, b)
      assert(ParameterizedWidth.expressionOf(result).get eq ParameterizedWidth.expressionOf(a).get)
      val output = out(UInt(5 bits))
      output := result
    })
  }

  test("equal native width witnesses from independent roots do not authorize a mux width") {
    generate(new Component {
      val a = in(UInt(HdlInt.param("LEFT_WIDTH", 5, 1, 32) bits))
      val b = in(UInt(HdlInt.param("RIGHT_WIDTH", 5, 1, 32) bits))
      val select = in Bool()
      val result = Mux(select, a, b)
      assert(ParameterizedWidth.expressionOf(result).isEmpty)
      val output = out(UInt(5 bits))
      output := result
    })
  }

  test("one untyped mux arm cannot borrow the other arm's symbolic width") {
    generate(new Component {
      val a = in(UInt(HdlInt.param("WIDTH", 5, 1, 32) bits))
      val b = in(UInt(5 bits))
      val select = in Bool()
      val result = Mux(select, a, b)
      assert(ParameterizedWidth.expressionOf(result).isEmpty)
      val output = out(UInt(5 bits))
      output := result
    })
  }

  test("ordinary concrete mux retains native maximum width and no symbolic annotation") {
    generate(new Component {
      val a = in(UInt(5 bits))
      val b = in(UInt(8 bits))
      val select = in Bool()
      val result = Mux(select, a, b)
      assert(result.getWidth == 8)
      assert(ParameterizedWidth.expressionOf(result).isEmpty)
      val output = out(UInt(8 bits))
      output := result
    })
  }
}
