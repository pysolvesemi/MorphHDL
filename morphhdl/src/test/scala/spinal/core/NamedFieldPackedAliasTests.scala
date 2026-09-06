package spinal.core

import java.nio.file.Files

import morphhdl.{MorphNamedFieldVectors, MorphVerilog}
import morphhdl.frontend.HdlInt
import org.scalatest.funsuite.AnyFunSuite

private object NamedFieldPackedAliasFixture {
  final case class Record() extends Bundle {
    val value = Bits(5 bits)
    val valid = Bool()
  }
  final class Design(depth: ElabInt, mutate: Boolean) extends Component {
    val source = in(Vec(Record(), depth))
    val other = in(Vec(Record(), depth))
    val result = out(Vec(Record(), depth))
    val otherResult = out(Vec(Record(), depth))
    val packed = out(Bits())
    packed := source.asBits
    result.assignFromBits(packed)
    otherResult := other
    val operation = ParameterizedVec.operationsOf(result).collect {
      case value: ParameterizedVecPackedAssignment => value
    }.last
    assert(operation.sourceAliases.size == 1)
    assert(operation.sourceAliases.head.target eq packed)
    if (mutate) operation.sourceAliases.head.assignment.source = other.asBits
  }
}

class NamedFieldPackedAliasTests extends AnyFunSuite {
  private def generate(mutate: Boolean): Unit = {
    val directory = Files.createTempDirectory("named-field-packed-alias-")
    val config = MorphNamedFieldVectors.enable(SpinalConfig(
      targetDirectory = directory.toString, headerWithDate = false))
    MorphVerilog(config) {
      new NamedFieldPackedAliasFixture.Design(HdlInt.param("COUNT", 1, 1, 5).asElabInt, mutate)
    }
  }

  test("complete Bits assignment preserves exact Vec packing provenance") {
    generate(mutate = false)
  }

  test("mutating a retained packed Bits alias fails before publication") {
    val error = intercept[Exception](generate(mutate = true))
    assert(error.getMessage.contains("PACKED-ALIAS-MISMATCH"), error.getMessage)
  }

  test("a fixed-width Bits copy cannot acquire a varying Vec shape from its witness") {
    val directory = Files.createTempDirectory("named-field-fixed-alias-")
    val error = intercept[Exception] {
      MorphVerilog(MorphNamedFieldVectors.enable(SpinalConfig(
        targetDirectory = directory.toString, headerWithDate = false))) {
        new Component {
          val count = HdlInt.param("COUNT", 1, 1, 5).asElabInt
          val source = in(Vec(NamedFieldPackedAliasFixture.Record(), count))
          val result = out(Vec(NamedFieldPackedAliasFixture.Record(), count))
          val fixed = Bits(6 bits)
          fixed := source.asBits
          result.assignFromBits(fixed)
        }
      }
    }
    assert(error.getMessage.contains("PACKED-SOURCE-PROVENANCE-MISSING"), error.getMessage)
  }
}
