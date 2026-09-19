package spinal.core.internals

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._
import morphhdl.frontend.HdlInt
import morphhdl.{MorphVerilog, MorphVerilogException}

/** Width assertions use independently specified native contracts, including
  * each odd tail; equality of truncated output values cannot satisfy them. */
class TypedBalancedReductionWidthTransferTests extends AnyFunSuite {
  private def native[T <: BaseType]: ElabBalancedReduction.Native[T] =
    (values, op, bridge) => new TraversableOnceAnyPimped[T](values).reduceBalancedTree(op, bridge)

  private def generate(body: => Component): Unit =
    SpinalConfig(targetDirectory = Files.createTempDirectory("balanced-width-transfer-").toString,
      headerWithDate = false, headerWithRepoHash = false).generateVerilog(body)

  test("UInt full products preserve every narrower native odd tail") {
    val width = HdlInt.param("WIDTH", 5, 1, 32)
    val count = HdlInt.param("COUNT", 1, 1, 5)
    generate(new Component {
      val words = Vec(UInt(width bits), count)
      words.vec.foreach(_ := 0)
      val proof = TypedBalancedReductionStageReplay.capture(words,
        (a: UInt, b: UInt) => a * b, (a: UInt, _: Int) => a, native[UInt])
      assert(proof.captured.rows.groupBy(_.level).toVector.sortBy(_._1).map(_._2.map(
        _.bridge.result.getBitsWidth)) == Vector(Vector(10, 10, 5), Vector(20, 5), Vector(25)))
      for (size <- 1 to 5) {
        val result = proof.replay(words.vec.take(size).toVector)
        assert(result.getBitsWidth == 5 * size)
        val expected = ElaborationWidthAuthority.multiply(ParameterizedWidth.expressionOf(words.vec.head).get,
          ElabInt.literal(size).expression)
        assert(ElaborationWidthAuthority.equivalent(ParameterizedWidth.expressionOf(result).get, expected))
      }
    })
  }

  test("SInt full products and natural RegNext preserve tail shapes and native latency") {
    val width = HdlInt.param("WIDTH", 5, 1, 32)
    val count = HdlInt.param("COUNT", 1, 1, 5)
    generate(new Component {
      val words = Vec(SInt(width bits), count)
      words.vec.foreach(_ := 0)
      val proof = TypedBalancedReductionStageReplay.capture(words,
        (a: SInt, b: SInt) => a * b, (a: SInt, _: Int) => RegNext(a) init S(0), native[SInt])
      assert(proof.captured.rows.groupBy(_.level).toVector.sortBy(_._1).map(_._2.map(
        _.bridge.result.getBitsWidth)) == Vector(Vector(10, 10, 5), Vector(20, 5), Vector(25)))
      for (size <- 1 to 5) {
        val result = proof.replay(words.vec.take(size).toVector)
        assert(result.getTypeObject == TypeSInt)
        assert(result.getBitsWidth == 5 * size)
        assert(proof.latencyFor(size) == (BigInt(size) - 1).bitLength)
      }
    })
  }

  test("native UInt and SInt widening sums retain carries and symbolic terminal widths") {
    val width = HdlInt.param("WIDTH", 5, 1, 32)
    val count = HdlInt.param("COUNT", 1, 1, 9)
    generate(new Component {
      val unsigned = Vec(UInt(width bits), count)
      val signed = Vec(SInt(width bits), count)
      unsigned.vec.foreach(_ := 0)
      signed.vec.foreach(_ := 0)
      val u = TypedBalancedReductionStageReplay.capture(unsigned,
        (a: UInt, b: UInt) => a +^ b, (a: UInt, _: Int) => a, native[UInt])
      val s = TypedBalancedReductionStageReplay.capture(signed,
        (a: SInt, b: SInt) => a +^ b, (a: SInt, _: Int) => a, native[SInt])
      for (size <- 1 to 9) {
        val expected = 5 + (BigInt(size) - 1).bitLength
        assert(u.replay(unsigned.vec.take(size).toVector).getBitsWidth == expected)
        val result = s.replay(signed.vec.take(size).toVector)
        assert(result.getBitsWidth == expected)
        assert(result.getTypeObject == TypeSInt)
      }
      val shape = ParameterizedWidth.expressionOf(unsigned.vec.head).get
      val schedule = TypedBalancedReductionStageReplay.widths(u.captured.plan, shape,
        u.stages.flatMap(_.operators).headOption)
      assert(schedule.terminal.default == 5)
      assert(schedule.terminal.parameterRoots.toSet ==
        (shape.parameterRoots ++ u.captured.plan.count.expression.parameterRoots).toSet)
      assert(schedule.terminal.maximum == 36)
    })
  }

  test("generic scalar replay composes independent roots without rebinding their identities") {
    val width = HdlInt.param("WIDTH", 5, 1, 32)
    val otherWidth = HdlInt.param("OTHER_WIDTH", 5, 1, 16)
    generate(new Component {
      val words = Vec(UInt(width bits), HdlInt.param("COUNT", 2, 1, 2))
      words.vec.foreach(_ := 0)
      val certificate = TypedBalancedReductionStageReplay.capture(words,
        (a: UInt, b: UInt) => a * b, (a: UInt, _: Int) => a, native[UInt])
      val operator = certificate.stages.head.operators.head
      val other = UInt(otherWidth bits)
      other := 0
      val left = ParameterizedWidth.expressionOf(words.vec.head).get
      val right = ParameterizedWidth.expressionOf(other).get
      val result = operator.replayWithWidths(words.vec.head, other, left, right)
      val resultWidth = ParameterizedWidth.expressionOf(result).get
      assert(resultWidth.default == 10 && resultWidth.minimum == 2 && resultWidth.maximum == 48)
      assert(resultWidth.parameterRoots.toSet == (left.parameterRoots ++ right.parameterRoots).toSet)
      intercept[IllegalArgumentException] { operator.replay(words.vec.head, other) }
    })
  }

  test("native high-bit mutation invalidates the widening signed graph certificate") {
    val width = HdlInt.param("WIDTH", 5, 1, 32)
    generate(new Component {
      val words = Vec(SInt(width bits), HdlInt.param("COUNT", 2, 1, 2))
      words.vec.foreach(_ := 0)
      val proof = TypedBalancedReductionStageReplay.capture(words,
        (a: SInt, b: SInt) => a +^ b, (a: SInt, _: Int) => a, native[SInt])
      val access = proof.captured.rows.head.operator.get.assignments.collectFirst {
        case assignment if assignment.source.isInstanceOf[SIntBitAccessFixed] =>
          assignment.source.asInstanceOf[SIntBitAccessFixed]
      }.get
      val old = access.bitId
      access.bitId = 0
      intercept[IllegalArgumentException] { proof.requireFreshness() }
      access.bitId = old
      proof.requireFreshness()
    })
  }

  test("inactive native groups cannot overflow a legal seventeen-element result width") {
    // Only these tiny prototype nodes are elaborated as hardware. Large widths
    // exercise the certified transfer and schedule through pure substitutions.
    val lower = Int.MaxValue / 17 - 1
    val upper = lower + 1
    val large = HdlInt.param("LARGE_WIDTH", lower, lower, upper).bits.expression.get
    val excessive = HdlInt.param("EXCESSIVE_WIDTH", upper + 1, upper + 1, upper + 2).bits.expression.get
    val count = ElabInt.fromExpression(HdlInt.param("SCHEDULE_COUNT", 1, 1, 17).bits.expression.get)
    generate(new Component {
      val words = Vec(UInt(3 bits), HdlInt.param("PROTOTYPE_COUNT", 2, 1, 2))
      words.vec.foreach(_ := 0)
      val certificate = TypedBalancedReductionStageReplay.capture(words,
        (a: UInt, b: UInt) => a * b, (a: UInt, _: Int) => a, native[UInt])
      val operator = certificate.stages.head.operators.head
      val schedule = TypedBalancedReductionStageReplay.widths(TypedBalancedReductionPlan(count), large, Some(operator))
      assert(schedule.terminal.minimum == lower)
      assert(schedule.terminal.default == lower)
      assert(schedule.terminal.maximum == BigInt(upper) * 17)
      assert(!schedule.stages.last.fullPairPossible)
      assert(!schedule.stages.last.tailPossible)
      val partial = operator.resultWidthFor(schedule.stages.last.partialLeft, schedule.stages.last.partialRight)
      assert(partial.maximum == BigInt(upper) * 17)
      val error = intercept[ParameterizedVerilogException] {
        TypedBalancedReductionStageReplay.widths(TypedBalancedReductionPlan(count), excessive, Some(operator))
      }
      assert(error.getMessage.contains("SPINAL-ELAB-WIDTH-RESULT-OUT-OF-RANGE"))
    })
  }

  test("template reachability follows the exact native odd-tail and partial-pair domain") {
    val count = ElabInt.fromExpression(HdlInt.param("FIVE", 5, 5, 5).bits.expression.get)
    generate(new Component {
      val words = Vec(UInt(3 bits), HdlInt.param("PROTOTYPE_COUNT", 2, 1, 2))
      words.vec.foreach(_ := 0)
      val certificate = TypedBalancedReductionStageReplay.capture(words,
        (a: UInt, b: UInt) => a * b, (a: UInt, _: Int) => a, native[UInt])
      val schedule = TypedBalancedReductionStageReplay.widths(TypedBalancedReductionPlan(count),
        ElabInt.literal(3).expression, certificate.stages.head.operators.headOption)
      assert(schedule.stages.map(_.tailPossible) == Vector(true, true, false))
      assert(schedule.stages.map(_.partialPairPossible) == Vector(false, false, true))
      assert(schedule.stages.map(_.fullPairPossible) == Vector(true, true, false))
      assert(schedule.terminal.default == 15)
    })
  }


  test("correlated leaf widths exclude oversized full groups outside their native active count") {
    val count = ElabInt.fromExpression(HdlInt.param("CORRELATED_COUNT", 1, 1, 17).bits.expression.get)
    val leaf = (ElabInt.literal(Int.MaxValue) / count).expression
    generate(new Component {
      val words = Vec(UInt(3 bits), HdlInt.param("PROTOTYPE_COUNT", 2, 1, 2))
      words.vec.foreach(_ := 0)
      val certificate = TypedBalancedReductionStageReplay.capture(words,
        (a: UInt, b: UInt) => a * b, (a: UInt, _: Int) => a, native[UInt])
      val schedule = TypedBalancedReductionStageReplay.widths(TypedBalancedReductionPlan(count),
        leaf, certificate.stages.head.operators.headOption)
      val root = count.expression.parameterRoots.head
      for (size <- 1 to 17) {
        val expected = BigInt(Int.MaxValue / size) * size
        assert(ElaborationWidthAuthority.evaluate(schedule.terminal,
          Vector(root -> BigInt(size))).contains(expected))
      }
      assert(schedule.terminal.maximum <= Int.MaxValue)
      assert(schedule.stages.head.fullInput.maximum <= Int.MaxValue / 2)
      assert(schedule.stages.forall(stage => stage.outputFull.maximum <= Int.MaxValue))
    })
  }


  test("finite native scalar widths cannot overflow their packed intermediate transport") {
    val count = ElabInt.fromExpression(HdlInt.param("TRANSPORT_COUNT",
      Int.MaxValue - 1, Int.MaxValue - 1, Int.MaxValue).bits.expression.get)
    generate(new Component {
      val words = Vec(UInt(3 bits), HdlInt.param("PROTOTYPE_COUNT", 2, 1, 2))
      words.vec.foreach(_ := 0)
      val certificate = TypedBalancedReductionStageReplay.capture(words,
        (a: UInt, b: UInt) => (a +^ b).resize(5), (a: UInt, _: Int) => a, native[UInt])
      val operator = certificate.stages.head.operators.head
      assert(operator.resultWidthFor(ElabInt.literal(1).expression,
        ElabInt.literal(1).expression).maximum == 5)
      assert(ElaborationWidthAuthority.multiply(ElabInt.literal(1).expression,
        count.expression).maximum == Int.MaxValue)
      // No huge Vec or declaration is constructed: only a two-value COUNT
      // certificate demonstrates that the first packed stage exceeds Int.
      val error = intercept[ParameterizedVerilogException] {
        TypedBalancedReductionStageReplay.widths(TypedBalancedReductionPlan(count),
          ElabInt.literal(1).expression, Some(operator))
      }
      assert(error.getMessage.contains("SPINAL-ELAB-WIDTH-RESULT-OUT-OF-RANGE"))
    })
  }


  test("published packed stages respect the configured native vector width limit") {
    val count = HdlInt.param("COUNT", 1, 1, 4)
    val config = SpinalConfig(targetDirectory = Files.createTempDirectory("balanced-transport-limit-").toString,
      headerWithDate = false, bitVectorWidthMax = 6)
    val error = intercept[MorphVerilogException] {
      MorphVerilog(config) {
        new Component {
          val words = in(Vec(UInt(1 bits), count))
          val result = out UInt(5 bits)
          result := words.reduceBalancedTree((a: UInt, b: UInt) => (a +^ b).resize(5))
        }
      }
    }
    assert(error.getMessage.contains("MORPH-REDUCE-BALANCED-PUBLICATION-TRANSPORT-WIDTH"))
    assert(error.getMessage.contains("bitVectorWidthMax"))
  }

  test("a partial pair retains its wider correlated operands when the full template is guarded") {
    val count = HdlInt.param("COUNT", 3, 1, 4)
    val width = count * (HdlInt.literal(6) - count)
    val config = SpinalConfig(targetDirectory = Files.createTempDirectory("balanced-correlated-partial-").toString,
      headerWithDate = false)
    MorphVerilog(config) {
      new Component {
        val words = in(Vec(UInt(width bits), count))
        val result = out UInt(width bits)
        result := words.reduceBalancedTree((a: UInt, b: UInt) => a + b)
        val anchors = scala.collection.mutable.Map.empty[String, Int]
        dslBody.walkStatements {
          case scalar: UInt if scalar.isNamed => anchors += scalar.getName() -> scalar.getBitsWidth
          case _ =>
        }
        // At COUNT=3 the actual final pair needs nine bits. The distinct full
        // group exists only at COUNT=4, where its guarded template needs eight.
        assert(anchors("morphhdl_balanced_1_l1_pair_left") == 8)
        assert(anchors("morphhdl_balanced_1_l1_pair_right") == 8)
        assert(anchors("morphhdl_balanced_1_l1_partial_pair_left") == 9)
        assert(anchors("morphhdl_balanced_1_l1_partial_pair_right") == 9)
        assert(anchors("morphhdl_balanced_1_l1_partial_pair_result") == 9)
      }
    }
  }

}
