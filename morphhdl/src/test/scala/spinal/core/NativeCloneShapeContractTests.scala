package spinal.core

import java.nio.file.Files

import scala.collection.JavaConverters._

import morphhdl.frontend.HdlInt
import org.scalatest.funsuite.AnyFunSuite

private object NativeCloneShapeFixture {
  final case class Payload(width: ElabInt) extends Bundle {
    val unsigned = UInt(width bits)
    val signed = SInt((width + 1) bits)
    val valid = Bool()
  }

  final case class Packet(width: ElabInt, count: ElabInt) extends Bundle {
    val header = Payload(width)
    val lanes = Vec(Payload(width), count)
    val fixed = Vec(Bits(width bits), 2)
  }
}

/** Exact registry identities are the subject; equal concrete widths are not
  * evidence that independent formal declarations describe the same shape.
  */
class NativeCloneShapeContractTests extends AnyFunSuite {
  import NativeCloneShapeFixture._

  private def elaborate(body: => Unit): Unit = {
    val directory = Files.createTempDirectory("native-clone-shape-")
    try {
      SpinalConfig(targetDirectory = directory.toString).generateVerilog(new Component {
        val keep = out Bool()
        keep := False
        body
      })
    } finally {
      val paths = Files.walk(directory)
      try paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists(_))
      finally paths.close()
    }
  }

  private def assertLeafWidths(source: Data, result: Data): Unit = {
    assert(source.flatten.size == result.flatten.size)
    source.flatten.zip(result.flatten).foreach { case (left, right) =>
      assert(left.getClass == right.getClass)
      assert(left.getBitsWidth == right.getBitsWidth)
      (ParameterizedWidth.expressionOf(left), ParameterizedWidth.expressionOf(right)) match {
        case (Some(a), Some(b)) => assert(ElabInt.equivalentExactFunction(a, b))
        case (None, None) =>
        case other => fail(s"lost exact cloned width: $other")
      }
    }
  }

  test("native cloneOf HardType Mux and RegNext retain nested Bundle Vec identities") {
    elaborate {
      val width: ElabInt = HdlInt.param("WIDTH", default = 5, min = 1, max = 8)
      val count: ElabInt = HdlInt.param("LANES", default = 3, min = 1, max = 4)
      val input = in(Packet(width, count))
      val alternate = in(Packet(width, count))
      val select = in Bool()
      val copied = cloneOf(input)
      copied := input
      val hard = HardType(input)()
      hard := input
      val selected = Mux(select, input, alternate)
      val registered = RegNext(input)
      Vector(copied, hard, selected, registered).foreach { value =>
        assertLeafWidths(input, value)
        val sourceShape = ParameterizedVec.shapeOf(input.lanes).get
        val resultShape = ParameterizedVec.shapeOf(value.lanes).get
        assert(ElabInt.equivalentExactFunction(sourceShape.depth, resultShape.depth))
        assert(sourceShape.depth.parameterRoots.head eq resultShape.depth.parameterRoots.head)
      }
      out(selected)
      out(registered)
    }
  }

  test("native scalar min max mux clone and register retain exact compatible width functions") {
    elaborate {
      val width: ElabInt = HdlInt.param("WIDTH", default = 5, min = 1, max = 8)
      val a = in UInt(width bits)
      val b = in UInt((width + 0) bits)
      val select = in Bool()
      val copied = cloneOf(a)
      copied := a
      val values = Vector(copied, Mux(select, a, b), a min b, a max b, RegNext(a))
      values.foreach { value =>
        assertLeafWidths(a, value)
        out(value)
      }
    }
  }

  test("native mux does not transfer one operand width to different formal or wider widths") {
    elaborate {
      val width: ElabInt = HdlInt.param("WIDTH", default = 5, min = 1, max = 8)
      val other: ElabInt = HdlInt.param("OTHER", default = 5, min = 1, max = 8)
      val a = in UInt(width bits)
      val b = in UInt(other bits)
      val c = in UInt((width + 1) bits)
      val select = in Bool()
      val differentRoot = Mux(select, a, b)
      val wider = Mux(select, a, c)
      assert(ParameterizedWidth.expressionOf(differentRoot).isEmpty)
      assert(ParameterizedWidth.expressionOf(wider).isEmpty)
      assert(wider.getBitsWidth == 6)
      out(differentRoot)
      out(wider)
    }
  }

  test("typed custom clones cannot change leaf kinds or widths and concrete clones keep native behavior") {
    elaborate {
      val width: ElabInt = HdlInt.param("WIDTH", default = 5, min = 1, max = 8)
      val typed = UInt(width bits)
      val wrongWidth = UInt(4 bits)
      val widthError = intercept[ParameterizedVerilogException] {
        ParameterizedWidth.copyCloneMetadata(typed, wrongWidth)
      }
      assert(widthError.code == "SPINAL-PARAMETERIZED-VERILOG-CLONE-SHAPE-MISMATCH")
      val wrongKind = Bits(5 bits)
      val kindError = intercept[ParameterizedVerilogException] {
        ParameterizedWidth.copyCloneMetadata[Data](typed, wrongKind)
      }
      assert(kindError.code == "SPINAL-PARAMETERIZED-VERILOG-CLONE-SHAPE-MISMATCH")
      assert(ParameterizedWidth.expressionOf(wrongWidth).isEmpty)
      assert(ParameterizedWidth.expressionOf(wrongKind).isEmpty)

      val concrete = UInt(3 bits)
      val customClone = UInt(4 bits)
      assert(ParameterizedWidth.copyCloneMetadata(concrete, customClone) eq customClone)
      assert(customClone.getBitsWidth == 4)
      val enumeration = new SpinalEnum { val idle, busy = newElement() }
      assert(cloneOf(enumeration()).getDefinition eq enumeration)
    }
  }
}
