package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import morphhdl.frontend.HdlInt
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

private object NamedFieldVecCollisionFixture {
  final case class Red(width: ElabInt) extends Bundle { val red = UInt(width bits) }
  final case class Joined(width: ElabInt) extends Bundle { val b_c = UInt(width bits) }
  final case class Suffix(width: ElabInt) extends Bundle { val c = UInt(width bits) }
  final case class CarrierLike(width: ElabInt) extends Bundle {
    val x = UInt(width bits)
    val `0_x` = UInt(width bits)
  }
  final case class Nested(width: ElabInt, inner: ElabInt) extends Bundle {
    val nested_1 = UInt(width bits)
    val values = Vec(UInt(width bits), inner)
  }

  final class CrossRoots(width: ElabInt, count: ElabInt) extends Component {
    val pixels = in(Vec(Red(width), count))
    val pixels_red = in(Vec(UInt(width bits), count))
    val result = out(Vec(Red(width), count))
    val scalarResult = out(Vec(UInt(width bits), count))
    result := pixels
    scalarResult := pixels_red
  }

  final class JoinedRoots(width: ElabInt, count: ElabInt) extends Component {
    val a = in(Vec(Joined(width), count))
    val a_b = in(Vec(Suffix(width), count))
    val first = out(Vec(Joined(width), count))
    val second = out(Vec(Suffix(width), count))
    first := a
    second := a_b
  }

  final class OwnCarrier(width: ElabInt, count: ElabInt) extends Component {
    val pixels = in(Vec(CarrierLike(width), count))
    val result = out(Vec(CarrierLike(width), count))
    val selected = out(UInt(width bits))
    result := pixels
    selected := pixels(0).x
  }

  final class PackingNames(width: ElabInt, count: ElabInt) extends Component {
    val pixels = in(Vec(Red(width), count))
    val packedBits = out(Bits())
    val restored = out(Vec(Red(width), count))
    val restored_unpacked_red = in(Bool())
    val pixels_packed_layout_f0_i0 = in(Bool())
    val restored_unpacked_1_layout_f0_d0 = in(Bool())
    val observed = out(Bits(3 bits))
    packedBits := pixels.asBits
    restored.assignFromBits(packedBits)
    observed := restored_unpacked_red ## pixels_packed_layout_f0_i0 ## restored_unpacked_1_layout_f0_d0
  }

  final class ProjectionNames(width: ElabInt, count: ElabInt, inner: ElabInt) extends Component {
    val pixels = in(Vec(Nested(width, inner), count))
    val result = out(Vec(Nested(width, inner), count))
    val packedBits = out(Bits())
    result := pixels
    packedBits := pixels(0).values.asBits
  }

  final class StructuralNested(width: ElabInt, count: ElabInt, inner: ElabInt) extends Component {
    val pixels = in(Vec(Nested(width, inner), count))
    val result = out(Vec(UInt(width bits), count))
    ElabFiniteRange.foreach(count, "nested field stride") { index =>
      index(result) := index(pixels).nested_1
    }
  }

  final class StructuralNestedAggregate(width: ElabInt, count: ElabInt, inner: ElabInt) extends Component {
    val pixels = in(Vec(Nested(width, inner), count))
    val result = out(Vec(Nested(width, inner), count))
    ElabFiniteRange.foreach(count, "nested alias aggregate boundary") { index =>
      index(result) := index(pixels)
    }
  }
}

class NamedFieldVecCollisionTests extends AnyFunSuite {
  import NamedFieldVecCollisionFixture._

  private def generate(kind: String, named: Boolean = true): String = {
    val directory = Files.createTempDirectory("morphhdl-field-collisions-")
    val base = NamedFieldVecFixture.config(directory, "Collision.v")
    MorphVerilog(if (named) MorphNamedFieldVectors.enable(base) else base) {
      def parameter(name: String, default: Int, maximum: Int): ElabInt =
        ElabInt.fromExpression(HdlInt.param(name, default, 1, maximum).bits.expression.get)
      val width = parameter("WIDTH", 3, 7)
      val count = parameter("COUNT", 1, 3)
      val component = kind match {
        case "roots" => new CrossRoots(width, count)
        case "joined" => new JoinedRoots(width, count)
        case "carrier" => new OwnCarrier(width, count)
        case "packing" => new PackingNames(width, count)
        case "projection" => new ProjectionNames(width, count, parameter("INNER", 1, 3))
        case "structural" => new StructuralNested(width, count, parameter("INNER", 1, 3))
        case "structuralAggregate" => new StructuralNestedAggregate(width, count, parameter("INNER", 1, 3))
      }
      component.setDefinitionName("Collision")
      component
    }
    new String(Files.readAllBytes(directory.resolve("Collision.v")), StandardCharsets.UTF_8)
  }

  private def declared(text: String): Vector[String] = {
    val declaration = "(?m)^\\s*(?:(?:input|output|inout)\\s+)?(?:wire|reg|logic|genvar)\\s+(?:signed\\s+)?(?:\\[[^\\]]+\\]\\s+)?([A-Za-z_][A-Za-z0-9_$]*)\\s*[,;)]?\\s*$".r
    declaration.findAllMatchIn(text).map(_.group(1)).toVector
  }

  private def requireUnique(text: String): Vector[String] = {
    val names = declared(text)
    assert(names.nonEmpty, text)
    assert(names.distinct.size == names.size, text)
    names
  }

  test("a named Bundle field cannot shadow a different scalar Vec aggregate") {
    val text = generate("roots")
    val names = requireUnique(text)
    def sourceOf(target: String): String = {
      val assignment = ("assign\\s+" + target + "\\s*=\\s*([A-Za-z_][A-Za-z0-9_$]*);").r
      assignment.findFirstMatchIn(text).map(_.group(1)).getOrElse(fail(text))
    }
    val fieldSource = sourceOf("result_red")
    val scalarSource = sourceOf("scalarResult")
    assert(fieldSource != scalarSource, text)
    assert(Vector(fieldSource, scalarSource).forall(name => names.contains(name) && name.startsWith("pixels_red")), text)
  }

  test("different Vec roots with identical joined field names preserve distinct assignments") {
    val text = generate("joined")
    val names = requireUnique(text).filter(_.startsWith("a_b_c__p"))
    assert(names.size == 2, text)
    assert(text.contains(s"assign first_b_c = ${names.head};"), text)
    assert(text.contains(s"assign second_c = ${names.last};"), text)
    assert(generate("joined") == text)
  }

  test("a field resembling its own native carrier is renamed before carrier substitution") {
    val text = generate("carrier")
    val names = requireUnique(text)
    assert(names.exists(_.startsWith("pixels_0_x__p")), text)
    assert(!names.contains("pixels_0_x"), text)
    assert("assign selected = pixels_x\\[".r.findFirstIn(text).nonEmpty, text)
  }

  test("packed bridge allocation reserves complete field index and generate-label families") {
    val text = generate("packing")
    val names = requireUnique(text)
    assert(names.contains("restored_unpacked_red"), text)
    assert(names.contains("pixels_packed_layout_f0_i0"), text)
    assert(names.contains("restored_unpacked_1_layout_f0_d0"), text)
    assert(names.contains("restored_unpacked_2_red"), text)
    assert(names.contains("pixels_packed_layout_1_f0_i0"), text)
    assert(!text.contains("begin : restored_unpacked_1_layout_f0_d0"), text)
  }

  test("nested packed projection names cannot shadow a published field") {
    val text = generate("projection")
    val names = requireUnique(text)
    assert(names.contains("pixels_nested_1"), text)
    assert(names.contains("pixels_nested_2"), text)
    assert("(?m)^\\s*input wire[^\\n]*COUNT[^\\n]*pixels_nested_1".r.findFirstIn(text).nonEmpty, text)
    assert("(?m)^\\s*wire[^\\n]*INNER[^\\n]*pixels_nested_2".r.findFirstIn(text).nonEmpty, text)
  }

  test("legacy structural Vec selections retain nested symbolic packed strides") {
    val text = generate("structural", named = false)
    val assignments = text.linesIterator.filter(line => line.contains("assign ") && line.contains("pixels[")).toVector
    assert(assignments.nonEmpty, text)
    assert(assignments.forall(_.contains("INNER")), text)
  }

  test("nested structural alias aggregate operations cannot silently freeze their native capacity") {
    val failure = intercept[MorphVerilogException] { generate("structuralAggregate") }
    assert(failure.getMessage.contains("STRUCTURAL-ALIAS-AGGREGATE-UNSUPPORTED"), failure.getMessage)
  }
}
