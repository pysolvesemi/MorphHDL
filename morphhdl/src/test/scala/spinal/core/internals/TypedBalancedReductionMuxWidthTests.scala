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
      val expression = ParameterizedWidth.expressionOf(result).get
      val original = ParameterizedWidth.expressionOf(a).get
      val root = original.parameterRoots.head
      assert(ElaborationWidthAuthority.equivalent(expression, original))
      assert(expression.parameterRoots.size == 1)
      assert(expression.parameterRoots.head eq root)
      for (value <- 1 to 32) {
        assert(ElaborationWidthAuthority.evaluate(expression,
          Vector(root -> BigInt(value))).contains(BigInt(value)))
      }
      val output = out(UInt(5 bits))
      output := result
    })
  }

  test("equal default witnesses retain both independent roots in the native maximum width") {
    generate(new Component {
      val a = in(UInt(HdlInt.param("LEFT_WIDTH", 5, 1, 32) bits))
      val b = in(UInt(HdlInt.param("RIGHT_WIDTH", 5, 1, 32) bits))
      val select = in Bool()
      val result = Mux(select, a, b)
      val expression = ParameterizedWidth.expressionOf(result).get
      val leftRoot = ParameterizedWidth.expressionOf(a).get.parameterRoots.head
      val rightRoot = ParameterizedWidth.expressionOf(b).get.parameterRoots.head
      assert(expression.parameterRoots.size == 2)
      assert(expression.parameterRoots.exists(_ eq leftRoot))
      assert(expression.parameterRoots.exists(_ eq rightRoot))
      for (left <- 1 to 32; right <- 1 to 32) {
        assert(ElaborationWidthAuthority.evaluate(expression,
          Vector(leftRoot -> BigInt(left), rightRoot -> BigInt(right)))
          .contains(BigInt(left.max(right))))
      }
      val output = out(UInt(5 bits))
      output := result
    })
  }

  test("a fixed mux arm contributes its literal width throughout the symbolic arm domain") {
    generate(new Component {
      val a = in(UInt(HdlInt.param("WIDTH", 5, 1, 32) bits))
      val b = in(UInt(5 bits))
      val select = in Bool()
      val result = Mux(select, a, b)
      val expression = ParameterizedWidth.expressionOf(result).get
      val root = ParameterizedWidth.expressionOf(a).get.parameterRoots.head
      assert(expression.parameterRoots.size == 1)
      assert(expression.parameterRoots.head eq root)
      for (width <- 1 to 32) {
        assert(ElaborationWidthAuthority.evaluate(expression,
          Vector(root -> BigInt(width))).contains(BigInt(width.max(5))))
      }
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
