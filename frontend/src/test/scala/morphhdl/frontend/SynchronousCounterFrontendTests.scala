package morphhdl.frontend

import morphhdl.frontend.ParamRtlFrontend._
import morphhdl.paramrtl.IntExpr.{AddressWidth, ParameterRef}
import morphhdl.paramrtl.ModuleItem.SynchronousCounter
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.RtlExpr.Ref
import morphhdl.paramrtl.Signedness.Unsigned
import morphhdl.paramrtl.{PackedBits, RtlExpr}
import org.scalatest.funsuite.AnyFunSuite

class SynchronousCounterFrontendTests extends AnyFunSuite {
  private def counter(limit: HdlInt): Unit =
    emitSynchronousCounter(
      "p_counter",
      ref("clk"),
      ref("reset"),
      ref("enable"),
      ref("count"),
      limit
    )

  test("captures one exact synchronous counter intent from a direct public limit") {
    val limit = HdlInt.param("LIMIT", default = 5, min = 1, max = 8)
    val items = captureItems { counter(limit) }

    assert(items.raw == Vector(
      SynchronousCounter(
        "p_counter",
        Ref("clk"),
        Ref("reset"),
        Ref("enable"),
        Ref("count"),
        ParameterRef("LIMIT")
      )
    ))
    val module = moduleDef(
      "ParameterizedCounter",
      Vector(integerParameter(limit)),
      Vector(
        port("clk", Input, packedBits(1)),
        port("reset", Input, packedBits(1)),
        port("enable", Input, packedBits(1)),
        port("count", Output, packedBits(limit.addressWidth))
      ),
      items
    )
    assert(module.items == items.raw)
    assert(module.ports.find(_.name == "count").get.dataType ==
      PackedBits(AddressWidth(ParameterRef("LIMIT")), Unsigned))
  }

  test("requires the exact unmodified HdlInt.param limit handle") {
    val limit = HdlInt.param("LIMIT", default = 5, min = 1, max = 8)
    val literal = intercept[FrontendException] {
      captureItems { counter(HdlInt.literal(5)) }
    }
    assert(literal.code == "MORPH-FRONTEND-SYNCHRONOUS-COUNTER-LIMIT-NOT-PUBLIC-PARAMETER")

    val derived = intercept[FrontendException] {
      captureItems { counter(limit + 0) }
    }
    assert(derived.code == "MORPH-FRONTEND-SYNCHRONOUS-COUNTER-LIMIT-NOT-PUBLIC-PARAMETER")

    val local = localParam("LOCAL_LIMIT", limit)
    val localFailure = intercept[FrontendException] {
      captureItems { counter(local) }
    }
    assert(localFailure.code == "MORPH-FRONTEND-SYNCHRONOUS-COUNTER-LIMIT-NOT-PUBLIC-PARAMETER")

    val nonPositive = HdlInt.param("ZERO", default = 0, min = 0, max = 8)
    val witness = intercept[FrontendException] {
      captureItems { counter(nonPositive) }
    }
    assert(witness.code == "MORPH-FRONTEND-SYNCHRONOUS-COUNTER-LIMIT-WITNESS-NONPOSITIVE")

    val nullLimit = intercept[FrontendException] {
      captureItems { counter(null) }
    }
    assert(nullLimit.code == "MORPH-FRONTEND-SYNCHRONOUS-COUNTER-LIMIT-NULL")
  }

  test("reports null non-ref and invalid counter references without partial emission") {
    val limit = HdlInt.param("LIMIT", default = 5, min = 1, max = 8)
    def emit(
        label: String = "p_counter",
        clock: FrontendNode[RtlExpr] = ref("clk"),
        reset: FrontendNode[RtlExpr] = ref("reset"),
        enable: FrontendNode[RtlExpr] = ref("enable"),
        count: FrontendNode[RtlExpr] = ref("count")
    ): Unit =
      emitSynchronousCounter(label, clock, reset, enable, count, limit)

    val nullClock = intercept[FrontendException] { captureItems { emit(clock = null) } }
    assert(nullClock.code == "MORPH-FRONTEND-SYNCHRONOUS-COUNTER-CLOCK-NULL")
    val nonRef = indexedPartSelect("bus", HdlInt.literal(0), HdlInt.literal(1))
    val nonRefCount = intercept[FrontendException] { captureItems { emit(count = nonRef) } }
    assert(nonRefCount.code == "MORPH-FRONTEND-SYNCHRONOUS-COUNTER-COUNT-NOT-REF")
    val invalid = intercept[FrontendException] { captureItems { emit(label = "bad-label") } }
    assert(invalid.code == "MORPH-FRONTEND-SYNCHRONOUS-COUNTER-LABEL-INVALID")

    val items = captureItems {
      intercept[FrontendException] { emit(reset = null) }
      emit()
    }
    assert(items.raw.size == 1)
  }

  test("rejects nesting multiple counters and mixed module items") {
    val limit = HdlInt.param("LIMIT", default = 5, min = 1, max = 8)
    val nested = intercept[FrontendException] {
      captureItems {
        generateIf(HdlBool.param("FEATURE", default = true)) {
          counter(limit)
        }.otherwise {}
      }
    }
    assert(nested.code == "MORPH-FRONTEND-SYNCHRONOUS-COUNTER-NESTED")

    val multiple = intercept[FrontendException] {
      captureItems {
        counter(limit)
        counter(limit)
      }
    }
    assert(multiple.code == "MORPH-FRONTEND-SYNCHRONOUS-COUNTER-MULTIPLE")

    val mixedAfter = intercept[FrontendException] {
      captureItems {
        counter(limit)
        emitContinuousAssign("count", ref("enable"))
      }
    }
    assert(mixedAfter.code == "MORPH-FRONTEND-SYNCHRONOUS-COUNTER-MIXED")

    val mixedBefore = intercept[FrontendException] {
      captureItems {
        emitContinuousAssign("count", ref("enable"))
        counter(limit)
      }
    }
    assert(mixedBefore.code == "MORPH-FRONTEND-SYNCHRONOUS-COUNTER-MIXED")
  }

  test("module ownership rejects a distinct same-named public limit token") {
    val declared = HdlInt.param("LIMIT", default = 5, min = 1, max = 8)
    val used = HdlInt.param("LIMIT", default = 5, min = 1, max = 8)
    val items = captureItems { counter(used) }
    val failure = intercept[FrontendException] {
      moduleDef("CounterIdentity", Vector(integerParameter(declared)), Vector.empty, items)
    }
    assert(failure.code == "MORPH-FRONTEND-PARAMETER-TOKEN-MISMATCH")
    assert(failure.origin == used.origin)
  }
}
