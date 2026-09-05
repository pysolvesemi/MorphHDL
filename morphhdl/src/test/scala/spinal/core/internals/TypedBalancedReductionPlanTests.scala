package spinal.core.internals

import morphhdl.frontend.HdlInt
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.{ElabBool, ElabInt, ElaborationIntegerExpression}

class TypedBalancedReductionPlanTests extends AnyFunSuite {
  private def parameter(default: Int, minimum: Int = 1, maximum: Int = 17): ElabInt =
    ElabInt.fromExpression(HdlInt.param("COUNT", default, minimum, maximum).bits.expression.get)

  private def at(value: ElabInt, rootValue: Int): BigInt = {
    val expression = value.projectedExpression("balanced reduction plan test")
    expression.exactDomain match {
      case Some(domain) => domain.evaluate(BigInt(rootValue)).get
      case None => expression.default
    }
  }

  private def at(value: ElabBool, rootValue: Int): Boolean = {
    val expression = value.projectedExpression("balanced reduction plan test")
    expression.exactDomain match {
      case Some(domain) => domain.evaluate(BigInt(rootValue)).get
      case None => expression.default
    }
  }

  private def check(plan: TypedBalancedReductionPlan, n: Int): Unit = {
    assert(at(plan.count, n) == n)
    assert(at(plan.resultDepth, n) == BigInt(n - 1).bitLength)
    var count = BigInt(n)
    var operators = BigInt(0)
    plan.stages.foreach { stage =>
      assert(at(stage.inputCount, n) == count)
      assert(at(stage.outputCount, n) == (count + 1) / 2)
      assert(at(stage.pairCount, n) == count / 2)
      assert(at(stage.active, n) == (count > 1))
      assert(at(stage.hasOddTail, n) == (count > 1 && count % 2 == 1))
      if (count > 1) operators += count / 2
      count = (count + 1) / 2
    }
    assert(count == 1)
    assert(operators == BigInt(n) - 1)
  }

  test("literal counts retain exact native pair and odd-tail geometry") {
    for (n <- 1 to 257) {
      val plan = TypedBalancedReductionPlan(ElabInt.literal(n))
      assert(plan.stages.size == BigInt(n - 1).bitLength)
      check(plan, n)
      assert(plan.count.parameters.isEmpty)
    }
  }

  test("symbolic plans retain exact values roots and extrema for odd and even bounds") {
    for (maximum <- 2 to 32) {
      val count = parameter(math.min(5, maximum), maximum = maximum)
      val plan = TypedBalancedReductionPlan(count)
      assert(plan.stages.size == BigInt(maximum - 1).bitLength)
      for (n <- 1 to maximum) check(plan, n)
      plan.stages.foreach { stage =>
        for (value <- Vector(stage.inputCount, stage.pairCount, stage.outputCount)) {
          assert(value.parameters.size == 1)
          assert(value.parameters.head eq count.parameters.head)
          val values = (1 to maximum).map(n => at(value, n))
          assert(value.expression.minimum == values.min)
          assert(value.expression.maximum == values.max)
        }
      }
    }
  }

  test("a singleton default retains all stages needed by non-singleton overrides") {
    val plan = TypedBalancedReductionPlan(parameter(1))
    assert(plan.stages.size == 5)
    assert(plan.stages.forall(stage => !at(stage.active, 1)))
    check(plan, 17)
  }

  test("singleton-only domains have no operator stage or level bridge") {
    val plan = TypedBalancedReductionPlan(parameter(1, 1, 1))
    assert(plan.stages.isEmpty)
    check(plan, 1)
  }

  test("same witnesses and names do not merge distinct declaration authorities") {
    val left = parameter(5)
    val right = parameter(5)
    val a = TypedBalancedReductionPlan(left)
    val b = TypedBalancedReductionPlan(right)
    assert(!(left.parameters.head eq right.parameters.head))
    assert(a.count.parameters.head eq left.parameters.head)
    assert(b.count.parameters.head eq right.parameters.head)
    assert(a.stages.map(_.inputCount.toString) == b.stages.map(_.inputCount.toString))
  }

  test("large concrete counts have bounded metadata and overflow-safe ceil-halving") {
    val plan = TypedBalancedReductionPlan(ElabInt.literal(Int.MaxValue))
    assert(plan.stages.size == 31)
    check(plan, Int.MaxValue)
    assert(at(plan.stages.head.outputCount, Int.MaxValue) == (BigInt(1) << 30))
    assert(plan.stages.map(_.inputCount.toString.length).max < 200)
  }

  test("invalid domains fail even when the default count is legal") {
    for (count <- Vector(ElabInt.literal(0), ElabInt.literal(-1),
                         parameter(5) - 1, parameter(5) - 2, null)) {
      val error = intercept[IllegalArgumentException] { TypedBalancedReductionPlan(count) }
      assert(error.getMessage.contains("MORPH-REDUCE-BALANCED-DOMAIN-INVALID"))
    }
    intercept[IllegalArgumentException] { TypedBalancedReductionPlan.forVec(null) }
  }

  test("non-canonical parameter-free expression summaries are not authority") {
    intercept[IllegalArgumentException] {
      val forged = ElabInt.fromExpression(ElaborationIntegerExpression(
        verilog = "0 + 1", default = BigInt(1), minimum = BigInt(1),
        maximum = BigInt(1), parameters = Vector.empty
      ))
      TypedBalancedReductionPlan(forged)
    }
  }
}
