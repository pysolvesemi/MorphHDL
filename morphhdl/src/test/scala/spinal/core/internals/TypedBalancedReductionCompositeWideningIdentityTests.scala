package spinal.core.internals

import java.nio.file.Files
import morphhdl.frontend.HdlInt
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._

final case class BalancedWideningIdentityRecord(sumWidth: ElabInt) extends Bundle {
  val sum = UInt(sumWidth bits)
  val marker = UInt(5 bits)
}

class TypedBalancedReductionCompositeWideningIdentityTests extends AnyFunSuite {
  private def generate(body: => Component): Unit =
    SpinalConfig(targetDirectory = Files.createTempDirectory("widening-identity-").toString,
      headerWithDate = false, headerWithRepoHash = false).generateVerilog(body)

  private val native: ElabBalancedReduction.Native[BalancedWideningIdentityRecord] =
    (values, op, bridge) => new TraversableOnceAnyPimped[BalancedWideningIdentityRecord](values)
      .reduceBalancedTree(op, bridge)

  test("fixed corresponding fields retain identity across widening and odd tails") {
    generate(new Component {
      val width = HdlInt.param("WIDTH", 5, 1, 8).asElabInt
      val values = Vec(BalancedWideningIdentityRecord(width),
        HdlInt.param("COUNT", 1, 1, 5))
      values.vec.foreach { value => value.sum := 0; value.marker := 0 }
      val proof = TypedBalancedReductionCompositeReplay.capture(values,
        (a: BalancedWideningIdentityRecord, b: BalancedWideningIdentityRecord) => {
          val sum = a.sum +^ b.sum
          val result = BalancedWideningIdentityRecord(ElabInt.widthOf(sum))
          result.sum := sum
          result.marker := a.marker
          result
        }, (value: BalancedWideningIdentityRecord, _: Int) => value, native)
      assert(proof.hasWidening)
      for (count <- 1 to 5) {
        val result = proof.replay(values.vec.take(count).toVector)
        assert(result.sum.getBitsWidth == 5 + (BigInt(count) - 1).bitLength)
        assert(result.marker.getBitsWidth == 5)
      }
      proof.requireFreshness()
    })
  }

  test("equal-witness cross-field aliases remain outside widening leaf authority") {
    val error = intercept[Exception] {
      generate(new Component {
        val width = HdlInt.param("WIDTH", 5, 1, 8).asElabInt
        val values = Vec(BalancedWideningIdentityRecord(width),
          HdlInt.param("COUNT", 2, 1, 2))
        values.vec.foreach { value => value.sum := 0; value.marker := 0 }
        TypedBalancedReductionCompositeReplay.capture(values,
          (a: BalancedWideningIdentityRecord, b: BalancedWideningIdentityRecord) => {
            val sum = a.sum +^ b.sum
            val result = BalancedWideningIdentityRecord(ElabInt.widthOf(sum))
            result.sum := sum
            result.marker := a.sum
            result
          }, (value: BalancedWideningIdentityRecord, _: Int) => value, native)
      })
    }
    val messages = Iterator.iterate(error: Throwable)(_.getCause).takeWhile(_ != null)
      .map(value => Option(value.getMessage).getOrElse("")).mkString("\n")
    assert(messages.contains("EXTERNAL-READ"), messages)
  }

  test("right operand aliases retain source identity through local chains and reject later rewiring") {
    generate(new Component {
      val width = HdlInt.param("WIDTH", 5, 1, 8).asElabInt
      val values = Vec(BalancedWideningIdentityRecord(width),
        HdlInt.param("COUNT", 1, 1, 5))
      values.vec.foreach { value => value.sum := 0; value.marker := 0 }
      val proof = TypedBalancedReductionCompositeReplay.capture(values,
        (a: BalancedWideningIdentityRecord, b: BalancedWideningIdentityRecord) => {
          val sum = a.sum +^ b.sum
          val first = UInt(5 bits)
          first := b.marker
          val second = UInt(5 bits)
          second := first
          val result = BalancedWideningIdentityRecord(ElabInt.widthOf(sum))
          result.sum := sum
          result.marker := second
          result
        }, (value: BalancedWideningIdentityRecord, _: Int) => value, native)
      def inputOf(value: BaseType, visited: Vector[BaseType] = Vector.empty): BaseType = {
        assert(!visited.exists(_ eq value), "replayed alias cycle")
        if (values.vec.exists(record => record.marker eq value)) value
        else {
          assert(value.hasOnlyOneStatement, "replayed alias lost its one native driver")
          value.head.source match {
            case source: BaseType => inputOf(source, visited :+ value)
            case other => fail("replayed alias changed primitive: " + other.getClass.getName)
          }
        }
      }
      for (count <- Vector(2, 3, 5)) {
        val result = proof.replay(values.vec.take(count).toVector)
        assert(inputOf(result.marker) eq values.vec(count - 1).marker)
      }
      proof.requireFreshness()
      val callback = proof.captured.rows.flatMap(_.operator).head
      val result = callback.result.asInstanceOf[BalancedWideningIdentityRecord]
      val driver = callback.assignments.find(_.finalTarget eq result.marker).get
      val original = driver.source
      try {
        driver.source = callback.operands.head.asInstanceOf[BalancedWideningIdentityRecord].marker
        val error = intercept[IllegalArgumentException](proof.requireFreshness())
        assert(error.getMessage.contains("MORPH-REDUCE-BALANCED"), error.getMessage)
      } finally driver.source = original
      proof.requireFreshness()
    })
  }
}
