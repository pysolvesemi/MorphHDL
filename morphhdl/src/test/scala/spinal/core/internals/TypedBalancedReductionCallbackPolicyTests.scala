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

  test("identity and direct native scalar register bridges are admitted") {
    Vector[AnyRef](
      (value: UInt, _: Int) => value,
      (value: UInt, _: Int) => RegNext(value),
      (value: SInt, _: Int) => RegNext(value),
      (value: Bits, _: Int) => RegNext(value),
      (value: Bool, _: Int) => RegNext(value)
    ).foreach(TypedBalancedReductionCallbackPolicy.requireSupportedBridge)
  }

  test("native min max widening callbacks and zero-initialized bridges are inspectable") {
    Vector[AnyRef](
      (a: UInt, b: UInt) => a.min(b), (a: UInt, b: UInt) => a.max(b),
      (a: SInt, b: SInt) => a.min(b), (a: SInt, b: SInt) => a.max(b),
      (a: UInt, b: UInt) => a +^ b, (a: SInt, b: SInt) => a +^ b,
      (a: UInt, b: UInt) => a * b, (a: SInt, b: SInt) => a * b
    ).foreach(TypedBalancedReductionCallbackPolicy.requireSupportedOperator)
    Vector[AnyRef](
      (value: UInt, _: Int) => RegNext(value).init(U(0)),
      (value: SInt, _: Int) => RegNext(value).init(S(0)),
      (value: Bits, _: Int) => RegNext(value).init(B(0))
    ).foreach(TypedBalancedReductionCallbackPolicy.requireSupportedBridge)
  }

  test("native literal resize construction is inspectable") {
    Vector[AnyRef](
      (a: UInt, b: UInt) => (a +^ b).resize(5),
      (a: SInt, b: SInt) => (a * b).resize(8),
      (a: Bits, b: Bits) => (a ^ b).resize(256),
      (a: UInt, b: UInt) => (a +^ b).resize(65536)
    ).foreach(TypedBalancedReductionCallbackPolicy.requireSupportedOperator)
    Vector[AnyRef](
      (value: UInt, _: Int) => RegNext(value.resize(8)),
      (value: SInt, _: Int) => RegNext(value.resize(8))
    ).foreach(TypedBalancedReductionCallbackPolicy.requireSupportedBridge)
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

  test("resize admission cannot erase symbolic width provenance or mutate operands") {
    reject((a: UInt, b: UInt) => (a +^ b).resize(a.getWidth))
    reject((a: SInt, b: SInt) => (a * b).resize(b.getBitsWidth))
    reject((value: UInt, _: Int) => RegNext(value.resize(value.getWidth)), bridge = true)
    reject((a: UInt, b: UInt) => { a.setWidth(5); a * b })
    reject((value: UInt, _: Int) => { value.setWidth(5); RegNext(value) }, bridge = true)
    reject((a: UInt, b: UInt) => (a +^ b).resized)
    reject((value: SInt, _: Int) => RegNext(value.resized), bridge = true)
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
