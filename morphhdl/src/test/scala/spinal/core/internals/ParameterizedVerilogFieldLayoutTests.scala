package spinal.core.internals

import scala.collection.mutable

import morphhdl.frontend.HdlInt
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

/** Independent scalar-order checks for field-major storage and native packed
  * conversions. These tests need no elaborator or Verilog renderer.
  */
class ParameterizedVerilogFieldLayoutTests extends AnyFunSuite {
  private def literal(value: Int): ElaborationIntegerExpression = ElabInt.literal(value).expression
  private def parameter(name: String, default: Int, maximum: Int): ElaborationIntegerExpression =
    HdlInt.param(name, default, 1, maximum).bits.expression.get

  // The native packing and named-field metadata retain the same recursive
  // geometry. These manual fixtures populate both without elaborating Data.
  private def sharedLayout(tree: ParameterizedVecLayoutNode): ParameterizedVecElementLayout.Layout = {
    import ParameterizedVecElementLayout._
    def node(value: ParameterizedVecLayoutNode): Node = value match {
      case ParameterizedVecLayoutScalar(_, width, kind) => Scalar(kind, width)
      case ParameterizedVecLayoutArray(dimension, element) =>
        Dimension(dimension.depth, dimension.carrierCapacity, node(element))
      case ParameterizedVecLayoutRecord(children) =>
        Fields(classOf[Bundle], children.zipWithIndex.map { case (child, index) => index.toString -> node(child) })
    }
    Layout(node(tree))
  }

  private val tag = Vector("tag")
  private val red = Vector("samples", "red")
  private val delta = Vector("samples", "lanes", "delta")
  private val valid = Vector("samples", "lanes", "valid")
  private val tail = Vector("samples", "tail")
  private val checksum = Vector("checksum")

  private def nestedShape(): ParameterizedVecShape = {
    val outer = parameter("COUNT", 3, 7)
    val inner = ParameterizedVecDimensionShape(parameter("INNER", 3, 5), 3, 5)
    val lanes = ParameterizedVecDimensionShape(parameter("LANES", 1, 3), 1, 3)
    val width = parameter("WIDTH", 5, 9)
    val blueWidth = parameter("BLUE_WIDTH", 3, 7)
    val specs = Vector(
      (tag, Vector.empty[ParameterizedVecDimensionShape], literal(3), TypeBits),
      (red, Vector(inner), width, TypeUInt),
      (delta, Vector(inner, lanes), blueWidth, TypeSInt),
      (valid, Vector(inner, lanes), literal(1), TypeBool),
      (tail, Vector(inner), literal(2), TypeBits),
      (checksum, Vector.empty[ParameterizedVecDimensionShape], literal(4), TypeBits)
    )
    val byPath = specs.map { case (path, axes, bits, kind) => path -> (axes, bits, kind) }.toMap
    val nativeOrder = Vector(tag) ++ (0 until 5).flatMap { _ =>
      Vector(red) ++ (0 until 3).flatMap(_ => Vector(delta, valid)) ++ Vector(tail)
    }.toVector ++ Vector(checksum)
    val leaves = nativeOrder.map { path =>
      val (_, bits, kind) = byPath(path)
      ParameterizedVecLeafShape(path.mkString("_"), kind, bits)
    }
    val fields = specs.map { case (path, axes, bits, kind) =>
      ParameterizedVecFieldShape(path, axes, kind, bits,
        nativeOrder.zipWithIndex.collect { case (candidate, index) if candidate == path => index })
    }
    def scalar(path: Vector[String]): ParameterizedVecLayoutNode = {
      val (_, bits, kind) = byPath(path)
      ParameterizedVecLayoutScalar(path, bits, kind)
    }
    val tree = ParameterizedVecLayoutRecord(Vector(
      scalar(tag),
      ParameterizedVecLayoutArray(inner, ParameterizedVecLayoutRecord(Vector(
        scalar(red),
        ParameterizedVecLayoutArray(lanes, ParameterizedVecLayoutRecord(Vector(scalar(delta), scalar(valid)))),
        scalar(tail)
      ))),
      scalar(checksum)
    ))
    ParameterizedVecShape(outer, 3, 7, leaves, fields, tree, sharedLayout(tree), None)
  }

  test("independent nested axes preserve field order and native packed offsets across overrides") {
    val shape = nestedShape()
    for {
      count <- Vector(1, 3, 7)
      inner <- Vector(1, 3, 5)
      lanes <- Vector(1, 3)
      width <- Vector(1, 5, 9)
      blue <- Vector(1, 3, 7)
    } {
      val values = Map("COUNT" -> count, "INNER" -> inner, "LANES" -> lanes, "WIDTH" -> width, "BLUE_WIDTH" -> blue)
      val layout = ParameterizedVerilogFieldLayout.fromShape(shape, "pixels",
        expression => values.get(expression.verilog).map(_.toString).getOrElse(expression.verilog))
      assert(layout.fields.map(_.path) == Vector(tag, red, delta, valid, tail, checksum))
      val expectedElementWidth = 3 + inner * (width + lanes * (blue + 1) + 2) + 4
      assert(layout.elementWidth == expectedElementWidth.toString)
      assert(layout.totalWidth == (count * expectedElementWidth).toString)
      val fieldOffsets = mutable.Map.empty[Vector[String], Int].withDefaultValue(0)
      var packedOffset = 0
      def visit(path: Vector[String], outerIndex: Int, coordinates: Vector[Int], bits: Int): Unit = {
        val field = layout.fields.find(_.path == path).get
        val indices = outerIndex.toString +: coordinates.map(_.toString)
        assert(layout.slice(field, indices) == s"${field.name}[(${fieldOffsets(path)}) +: $bits]")
        assert(layout.packedSlice("packed", field, indices) == s"packed[($packedOffset) +: $bits]")
        fieldOffsets(path) += bits
        packedOffset += bits
      }
      for (outerIndex <- 0 until count) {
        visit(tag, outerIndex, Vector.empty, 3)
        for (innerIndex <- 0 until inner) {
          visit(red, outerIndex, Vector(innerIndex), width)
          for (laneIndex <- 0 until lanes) {
            visit(delta, outerIndex, Vector(innerIndex, laneIndex), blue)
            visit(valid, outerIndex, Vector(innerIndex, laneIndex), 1)
          }
          visit(tail, outerIndex, Vector(innerIndex), 2)
        }
        visit(checksum, outerIndex, Vector.empty, 4)
      }
      assert(packedOffset == count * expectedElementWidth)
      layout.fields.foreach(field => assert(field.width == fieldOffsets(field.path).toString))
    }
  }

  test("native carrier ordinals decode by capacity while slices use symbolic strides") {
    val shape = nestedShape()
    val layout = ParameterizedVerilogFieldLayout.fromShape(shape, "pixels", _.verilog)
    val field = layout.fields.find(_.path == delta).get
    val leaf = field.leafIndices(2 * 3 + 1)
    assert(layout.fieldForLeaf(leaf) eq field)
    assert(layout.leafCoordinates(leaf) == Vector(2, 1))
    val selected = layout.constantSlice(1, leaf)
    assert(selected.contains("INNER"))
    assert(selected.contains("LANES"))
    assert(selected.contains("BLUE_WIDTH"))
    assert(!selected.contains("COUNT"))
    assert(layout.dynamicSlice("address", leaf, clampRead = true).contains("COUNT"))
    assert(!layout.dynamicSlice("address", leaf, clampRead = false).contains("COUNT"))
    assert(layout.packedConstantSlice("packed", 1, leaf).contains("INNER"))
    assert(layout.packingOffset(leaf, "outer").contains("outer"))
  }

  test("packed bridge reverses only assignment direction and keeps symbolic loop bounds") {
    val layout = ParameterizedVerilogFieldLayout.fromShape(nestedShape(), "pixels", _.verilog)
    val forward = layout.packedBridge("packed", toPacked = true, labelPrefix = "bridge")
    val reverse = layout.packedBridge("packed", toPacked = false, labelPrefix = "bridge")
    assert(forward.filterNot(_.contains("assign ")) == reverse.filterNot(_.contains("assign ")))
    val assignment = "\\s*assign (.*) = (.*);".r
    val f = forward.collect { case assignment(target, source) => target -> source }
    val r = reverse.collect { case assignment(target, source) => source -> target }
    assert(f == r)
    assert(f.size == layout.fields.size)
    assert(forward.exists(_.contains("< (COUNT)")))
    assert(forward.exists(_.contains("< (INNER)")))
    assert(forward.exists(_.contains("< (LANES)")))
    assert(layout.aggregate == "{pixels_checksum, pixels_samples_tail, pixels_samples_lanes_valid, pixels_samples_lanes_delta, pixels_samples_red, pixels_tag}")
  }

  private def flatShape(paths: Vector[Vector[String]]): ParameterizedVecShape = {
    val bit = literal(1)
    val fields = paths.zipWithIndex.map { case (path, index) =>
      ParameterizedVecFieldShape(path, Vector.empty, TypeBool, bit, Vector(index))
    }
    val tree = ParameterizedVecLayoutRecord(paths.map(path => ParameterizedVecLayoutScalar(path, bit, TypeBool)))
    ParameterizedVecShape(literal(3), 3, 3,
      paths.map(path => ParameterizedVecLeafShape(path.mkString("_"), TypeBool, bit)),
      fields, tree, sharedLayout(tree), None)
  }

  test("joined paths escaped characters and occupied names allocate deterministic distinct identifiers") {
    val paths = Vector(Vector("a_b"), Vector("a", "b"), Vector("bad-name"), Vector("bad_u002d_name"), Vector("safe"))
    val shape = flatShape(paths)
    val occupied = Set("pixels_safe")
    val first = ParameterizedVerilogFieldLayout.fromShape(shape, "pixels", _.verilog, occupied)
    val second = ParameterizedVerilogFieldLayout.fromShape(shape, "pixels", _.verilog, occupied)
    assert(first.fields.map(_.name) == second.fields.map(_.name))
    assert(first.fields.map(_.name).distinct.size == paths.size)
    first.fields.foreach { field =>
      assert(field.name.matches("[A-Za-z_][A-Za-z0-9_$]*"))
      assert(!occupied(field.name))
    }
    assert(first.fields.take(2).forall(_.name.startsWith("pixels_a_b__p")))
    val allocated = first.fields.find(_.path == Vector("safe")).get.name
    val failure = intercept[ParameterizedVerilogException] {
      ParameterizedVerilogFieldLayout.fromShape(shape, "pixels", _.verilog, occupied + allocated)
    }
    assert(failure.code.endsWith("FIELD-NAME-COLLISION"))
  }

  test("packing tree field types widths and coverage fail closed on mutated metadata") {
    val shape = flatShape(Vector(Vector("left"), Vector("right")))
    val changedType = shape.copy(fieldLayout = ParameterizedVecLayoutRecord(Vector(
      ParameterizedVecLayoutScalar(Vector("left"), literal(1), TypeSInt),
      ParameterizedVecLayoutScalar(Vector("right"), literal(1), TypeBool))))
    val changedWidth = shape.copy(fieldLayout = ParameterizedVecLayoutRecord(Vector(
      ParameterizedVecLayoutScalar(Vector("left"), literal(2), TypeBool),
      ParameterizedVecLayoutScalar(Vector("right"), literal(1), TypeBool))))
    val repeatedLeaf = shape.copy(elementFields = shape.elementFields.map(_.copy(carrierLeafIndices = Vector(0))))
    val swappedTree = shape.copy(fieldLayout = ParameterizedVecLayoutRecord(Vector(
      ParameterizedVecLayoutScalar(Vector("right"), literal(1), TypeBool),
      ParameterizedVecLayoutScalar(Vector("left"), literal(1), TypeBool))))
    for (mutant <- Vector(changedType, changedWidth, repeatedLeaf, swappedTree)) {
      intercept[ParameterizedVerilogException] {
        ParameterizedVerilogFieldLayout.fromShape(mutant, "pixels", _.verilog)
      }
    }
  }
}
