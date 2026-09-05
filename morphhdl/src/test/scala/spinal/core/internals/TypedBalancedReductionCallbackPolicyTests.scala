package spinal.core.internals

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class TypedBalancedReductionCallbackPolicyTests extends AnyFunSuite {
  private def reject(callback: AnyRef, bridge: Boolean = false): Unit = {
    val error = intercept[IllegalArgumentException] {
      if (bridge) TypedBalancedReductionCallbackPolicy.requireSupportedBridge(callback)
      else TypedBalancedReductionCallbackPolicy.requireSupportedOperator(callback)
    }
    assert(error.getMessage.contains("CALLBACK-UNSUPPORTED"), error.getMessage)
  }

  test("direct native primitive callbacks require no new user API") {
    val operators: Vector[AnyRef] = Vector(
      (a: Bool, b: Bool) => a & b,
      (a: Bool, b: Bool) => a | b,
      (a: Bool, b: Bool) => a ^ b,
      (a: Bits, b: Bits) => a & b,
      (a: Bits, b: Bits) => a | b,
      (a: Bits, b: Bits) => a ^ b,
      (a: UInt, b: UInt) => a & b,
      (a: UInt, b: UInt) => a | b,
      (a: UInt, b: UInt) => a ^ b,
      (a: UInt, b: UInt) => a + b,
      (a: SInt, b: SInt) => a & b,
      (a: SInt, b: SInt) => a | b,
      (a: SInt, b: SInt) => a ^ b,
      (a: SInt, b: SInt) => a + b
    )
    operators.foreach(TypedBalancedReductionCallbackPolicy.requireSupportedOperator)
  }

  test("identity and direct native register bridges are admitted") {
    val identity = (value: UInt, _: Int) => value
    val register = (value: UInt, _: Int) => RegNext(value)
    TypedBalancedReductionCallbackPolicy.requireSupportedBridge(identity)
    TypedBalancedReductionCallbackPolicy.requireSupportedBridge(register)
  }

  test("native min max widening callbacks and zero-initialized bridges are inspectable") {
    Vector[AnyRef](
      (a: UInt, b: UInt) => a.min(b), (a: UInt, b: UInt) => a.max(b),
      (a: SInt, b: SInt) => a.min(b), (a: SInt, b: SInt) => a.max(b),
      (a: UInt, b: UInt) => a +^ b
    ).foreach(TypedBalancedReductionCallbackPolicy.requireSupportedOperator)
    TypedBalancedReductionCallbackPolicy.requireSupportedBridge(
      (value: UInt, _: Int) => RegNext(value).init(U(0)))
  }

  test("a bridge may choose native register latency solely from its level") {
    val bridge = (value: UInt, level: Int) => if (level == 0) value else RegNext(RegNext(value))
    TypedBalancedReductionCallbackPolicy.requireSupportedBridge(bridge)
  }

  test("an inline inferred register bridge has no captured host state") {
    val bridge = (value: UInt, _: Int) => {
      val register = UInt()
      register.setAsReg()
      register := value
      register.init(U(0))
      register
    }
    TypedBalancedReductionCallbackPolicy.requireSupportedBridge(bridge)
  }

  test("mutable captured operator state rejects before the callback executes") {
    var calls = 0
    val callback = (a: UInt, b: UInt) => { calls += 1; if (calls <= 4) a + b else a ^ b }
    reject(callback)
    assert(calls == 0)
  }

  test("mutable captured bridge state cannot borrow uniform maximum-count evidence") {
    var calls = 0
    val callback = (value: UInt, _: Int) => { calls += 1; if (calls <= 3) value else RegNext(value) }
    reject(callback, bridge = true)
    assert(calls == 0)
  }

  test("capture-free global field access is still rejected") {
    val callback = (a: UInt, b: UInt) => {
      TypedBalancedReductionCallbackPolicyTestState.calls += 1
      a + b
    }
    reject(callback)
    assert(TypedBalancedReductionCallbackPolicyTestState.calls == 0)
  }

  test("opaque closure implementations cannot claim a direct primitive body") {
    reject(new Function2[UInt, UInt, UInt] {
      override def apply(a: UInt, b: UInt): UInt = a + b
    })
  }

  test("native witness queries cannot select parameter-dependent callback behavior") {
    reject((a: UInt, b: UInt) => if (a.getWidth == 5) a + b else a ^ b)
    reject((value: UInt, _: Int) => if (value.getWidth == 5) value else RegNext(value), bridge = true)
  }

  test("host time or library calls cannot pass through capture-free lambdas") {
    reject((a: UInt, b: UInt) => if (System.nanoTime() == 0) a + b else a ^ b)
  }

  test("Scala callback loops reject even if their current iteration count is zero") {
    reject((value: UInt, level: Int) => {
      var index = 0
      while (index < level) index += 1
      value
    }, bridge = true)
  }
}

private[internals] object TypedBalancedReductionCallbackPolicyTestState {
  var calls = 0
}
