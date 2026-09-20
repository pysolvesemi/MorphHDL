package morphhdl.frontend

import java.util.concurrent.{Callable, Executors, TimeUnit}

import morphhdl.frontend.ParamRtlFrontend._
import morphhdl.paramrtl.ModuleItem.{CombinationalIf, ContinuousAssign, SynchronousRegister}
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.RtlExpr
import morphhdl.paramrtl.RtlExpr.Ref
import morphhdl.paramrtl.ProceduralAssign
import org.scalatest.funsuite.AnyFunSuite

class SynchronousRegisterFrontendTests extends AnyFunSuite {
  test("captures one exact posedge synchronous-reset register intent") {
    val items = captureItems {
      emitSynchronousRegister(
        label = "p_sync_register",
        clock = ref("clk"),
        reset = ref("reset"),
        assignment = proceduralAssign("data_out", ref("data_in"))
      )
    }

    assert(items.raw == Vector(
      SynchronousRegister(
        label = "p_sync_register",
        clock = Ref("clk"),
        reset = Ref("reset"),
        assignment = ProceduralAssign(Ref("data_out"), Ref("data_in"))
      )
    ))

    val module = moduleDef(
      name = "SyncRegister",
      parameters = Vector.empty,
      ports = Vector(
        port("clk", Input, packedBits(1)),
        port("reset", Input, packedBits(1)),
        port("data_in", Input, packedBits(8)),
        port("data_out", Output, packedBits(8))
      ),
      items = items
    )
    assert(module.items == items.raw)
  }

  test("retains integer Boolean and local provenance from every reference") {
    val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 32)
    val enabled = HdlBool.param("ENABLED", default = true)
    val localWidth = localParam("LOCAL_WIDTH", width)
    val localEnabled = localParam("LOCAL_ENABLED", enabled)

    def guardedRef(name: String): FrontendNode[RtlExpr] =
      FrontendNode(
        Ref(name),
        parameters = width.parameters ++ localEnabled.integerParameters,
        booleanParameters = enabled.parameters ++ localEnabled.parameters,
        localParameters = localWidth.localParameters ++ localEnabled.localParameters,
        booleanLocalParameters = localWidth.booleanLocalParameters ++
          localEnabled.booleanLocalParameters,
        origin = width.origin
      )

    val items = captureItems {
      emitSynchronousRegister(
        "p_provenance",
        guardedRef("clk"),
        guardedRef("reset"),
        proceduralAssign("data_out", guardedRef("data_in"))
      )
    }

    val module = moduleDef(
      name = "RegisterProvenance",
      parameters = Vector(integerParameter(width)),
      ports = Vector.empty,
      items = items,
      localParameters = Vector(integerLocalParameter(localWidth)),
      booleanParameters = Vector(booleanParameter(enabled)),
      booleanLocalParameters = Vector(booleanLocalParameter(localEnabled))
    )
    assert(module.parameters.map(_.name) == Vector("WIDTH"))
    assert(module.booleanParameters.map(_.name) == Vector("ENABLED"))
    assert(module.localParameters.map(_.name) == Vector("LOCAL_WIDTH"))
    assert(module.booleanLocalParameters.map(_.name) == Vector("LOCAL_ENABLED"))
  }

  test("reports distinct and undeclared identities retained by clock reset and data") {
    val declared = HdlInt.param("WIDTH", default = 8, min = 1, max = 32)
    val used = HdlInt.param("WIDTH", default = 4, min = 1, max = 16)
    val guardedClock = FrontendNode[RtlExpr](
      Ref("clk"),
      parameters = used.parameters,
      origin = used.origin
    )
    val items = captureItems {
      emitSynchronousRegister(
        "p_identity",
        guardedClock,
        ref("reset"),
        proceduralAssign("data_out", ref("data_in"))
      )
    }

    val mismatch = intercept[FrontendException] {
      moduleDef(
        "RegisterIdentityMismatch",
        Vector(integerParameter(declared)),
        Vector.empty,
        items
      )
    }
    assert(mismatch.code == "MORPH-FRONTEND-PARAMETER-TOKEN-MISMATCH")
    assert(mismatch.origin == used.origin)

    val missing = intercept[FrontendException] {
      moduleDef("RegisterIdentityMissing", Vector.empty, Vector.empty, items)
    }
    assert(missing.code == "MORPH-FRONTEND-PARAMETER-NOT-DECLARED")
    assert(missing.origin == used.origin)
  }

  test("reports foreign local identity retained by reset or data") {
    val foreign = localParam("FOREIGN_RESET", HdlBool.literal(true))
    moduleDef(
      "RegisterLocalOwner",
      Vector.empty,
      Vector.empty,
      captureItems {},
      booleanLocalParameters = Vector(booleanLocalParameter(foreign))
    )
    val guardedReset = FrontendNode[RtlExpr](
      Ref("reset"),
      booleanLocalParameters = foreign.booleanLocalParameters,
      origin = foreign.origin
    )
    val items = captureItems {
      emitSynchronousRegister(
        "p_foreign",
        ref("clk"),
        guardedReset,
        proceduralAssign("data_out", ref("data_in"))
      )
    }

    val error = intercept[FrontendException] {
      moduleDef("RegisterForeignLocal", Vector.empty, Vector.empty, items)
    }
    assert(error.code == "MORPH-FRONTEND-LOCAL-PARAMETER-FOREIGN")
    assert(error.origin == foreign.origin)
  }

  test("defers port resolution direction and packed-type proof to ParamRTL") {
    val items = captureItems {
      emitSynchronousRegister(
        "p_deferred",
        ref("missing_clock"),
        ref("wide_or_output_reset"),
        proceduralAssign("input_target", ref("output_value"))
      )
    }

    assert(items.raw.head == SynchronousRegister(
      "p_deferred",
      Ref("missing_clock"),
      Ref("wide_or_output_reset"),
      ProceduralAssign(Ref("input_target"), Ref("output_value"))
    ))
  }

  test("rejects null non-ref and invalid clock reset label and assignment inputs") {
    val assignment = proceduralAssign("data_out", ref("data_in"))

    def emit(
        label: String = "p_register",
        clock: FrontendNode[RtlExpr] = ref("clk"),
        reset: FrontendNode[RtlExpr] = ref("reset"),
        value: FrontendNode[ProceduralAssign] = assignment
    ): Unit = captureItems {
      emitSynchronousRegister(label, clock, reset, value)
    }

    assert(
      intercept[FrontendException](emit(label = null)).code ==
        "MORPH-FRONTEND-SYNCHRONOUS-REGISTER-LABEL-INVALID"
    )
    assert(
      intercept[FrontendException](emit(label = "not-portable")).code ==
        "MORPH-FRONTEND-SYNCHRONOUS-REGISTER-LABEL-INVALID"
    )
    assert(
      intercept[FrontendException](emit(clock = null)).code ==
        "MORPH-FRONTEND-SYNCHRONOUS-REGISTER-CLOCK-NULL"
    )
    assert(
      intercept[FrontendException](emit(clock = indexedPartSelect("bus", 0, 1))).code ==
        "MORPH-FRONTEND-SYNCHRONOUS-REGISTER-CLOCK-NOT-REF"
    )
    assert(
      intercept[FrontendException](emit(clock = ref(null))).code ==
        "MORPH-FRONTEND-SYNCHRONOUS-REGISTER-CLOCK-INVALID"
    )
    assert(
      intercept[FrontendException](emit(reset = null)).code ==
        "MORPH-FRONTEND-SYNCHRONOUS-REGISTER-RESET-NULL"
    )
    assert(
      intercept[FrontendException](emit(reset = indexedPartSelect("bus", 0, 1))).code ==
        "MORPH-FRONTEND-SYNCHRONOUS-REGISTER-RESET-NOT-REF"
    )
    assert(
      intercept[FrontendException](emit(reset = ref("not-portable"))).code ==
        "MORPH-FRONTEND-SYNCHRONOUS-REGISTER-RESET-INVALID"
    )
    assert(
      intercept[FrontendException](emit(value = null)).code ==
        "MORPH-FRONTEND-SYNCHRONOUS-REGISTER-ASSIGNMENT-NULL"
    )

    val invalidTarget = FrontendNode(
      ProceduralAssign(Ref("not-portable"), Ref("data_in")),
      origin = SourceOrigin("InvalidTarget.scala", 1)
    )
    assert(
      intercept[FrontendException](emit(value = invalidTarget)).code ==
        "MORPH-FRONTEND-SYNCHRONOUS-REGISTER-TARGET-INVALID"
    )
  }

  test("requires parameterized capture and never concretely specializes the register") {
    def emit(): Unit =
      emitSynchronousRegister(
        "p_capture_only",
        ref("clk"),
        ref("reset"),
        proceduralAssign("data_out", ref("data_in"))
      )

    assert(
      intercept[FrontendException](emit()).code == "MORPH-FRONTEND-EMIT-OUTSIDE-CAPTURE"
    )
    assert(
      intercept[FrontendException] {
        FrontendSession.concrete(emit())
      }.code == "MORPH-FRONTEND-EMIT-OUTSIDE-CAPTURE"
    )
  }

  test("failed sequential validation is atomic and retryable in the same capture") {
    var invalid: FrontendException = null
    var duplicate: FrontendException = null
    val items = captureItems {
      invalid = intercept[FrontendException] {
        emitSynchronousRegister(
          "bad-label",
          ref("clk"),
          ref("reset"),
          proceduralAssign("data_out", ref("data_in"))
        )
      }
      emitSynchronousRegister(
        "p_kept",
        ref("clk"),
        ref("reset"),
        proceduralAssign("data_out", ref("data_in"))
      )
      duplicate = intercept[FrontendException] {
        emitSynchronousRegister(
          "p_discarded",
          ref("clk"),
          ref("reset"),
          proceduralAssign("data_out", ref("data_in"))
        )
      }
    }
    assert(invalid.code == "MORPH-FRONTEND-SYNCHRONOUS-REGISTER-LABEL-INVALID")
    assert(duplicate.code == "MORPH-FRONTEND-SYNCHRONOUS-REGISTER-MULTIPLE")
    assert(items.raw.map(_.asInstanceOf[SynchronousRegister].label) == Vector("p_kept"))
  }

  test("rejects combinational and synchronous processes in both orders") {
    def sync(): Unit =
      emitSynchronousRegister(
        "p_sync",
        ref("clk"),
        ref("reset"),
        proceduralAssign("data_out", ref("data_in"))
      )

    def combinational(): Unit =
      emitCombinationalIf(
        "p_comb",
        ref("select"),
        Vector(proceduralAssign("data_out", ref("data_in"))),
        Vector(proceduralAssign("data_out", ref("other")))
      )

    var combAfterSync: FrontendException = null
    captureItems {
      sync()
      combAfterSync = intercept[FrontendException](combinational())
    }
    assert(combAfterSync.code == "MORPH-FRONTEND-RUNTIME-PROCESS-MIXED")

    var syncAfterComb: FrontendException = null
    captureItems {
      combinational()
      syncAfterComb = intercept[FrontendException](sync())
    }
    assert(syncAfterComb.code == "MORPH-FRONTEND-RUNTIME-PROCESS-MIXED")
  }

  test("rejects continuous and instance siblings in both orders") {
    def sync(label: String): Unit =
      emitSynchronousRegister(
        label,
        ref("clk"),
        ref("reset"),
        proceduralAssign("data_out", ref("data_in"))
      )

    var continuousAfter: FrontendException = null
    captureItems {
      sync("p_before_continuous")
      continuousAfter = intercept[FrontendException] {
        emitContinuousAssign("extra", ref("data_in"))
      }
    }
    assert(continuousAfter.code == "MORPH-FRONTEND-SYNCHRONOUS-REGISTER-MIXED")

    var afterContinuous: FrontendException = null
    captureItems {
      emitContinuousAssign("extra", ref("data_in"))
      afterContinuous = intercept[FrontendException](sync("p_after_continuous"))
    }
    assert(afterContinuous.code == "MORPH-FRONTEND-SYNCHRONOUS-REGISTER-MIXED")

    var instanceAfter: FrontendException = null
    captureItems {
      sync("p_before_instance")
      instanceAfter = intercept[FrontendException](emitInstance("helper", "Helper"))
    }
    assert(instanceAfter.code == "MORPH-FRONTEND-SYNCHRONOUS-REGISTER-MIXED")

    var afterInstance: FrontendException = null
    captureItems {
      emitInstance("helper", "Helper")
      afterInstance = intercept[FrontendException](sync("p_after_instance"))
    }
    assert(afterInstance.code == "MORPH-FRONTEND-SYNCHRONOUS-REGISTER-MIXED")
  }

  test("rejects generate siblings and nesting in every relevant direction") {
    val enabled = HdlBool.literal(true)
    val selector = HdlInt.literal(0)
    val count = HdlInt.param("COUNT", default = 1, min = 1, max = 1)

    def sync(label: String = "p_sync"): Unit =
      emitSynchronousRegister(
        label,
        ref("clk"),
        ref("reset"),
        proceduralAssign("data_out", ref("data_in"))
      )

    assert(
      intercept[FrontendException] {
        captureItems { generateIf(enabled) { sync() } otherwise {} }
      }.code == "MORPH-FRONTEND-SYNCHRONOUS-REGISTER-NESTED"
    )
    assert(
      intercept[FrontendException] {
        captureItems {
          generateCase(selector).choice(0, "g_zero") { sync() }.default("g_other") {}
        }
      }.code == "MORPH-FRONTEND-SYNCHRONOUS-REGISTER-NESTED"
    )
    assert(
      intercept[FrontendException] {
        captureItems { for (_ <- 0 until count) sync() }
      }.code == "MORPH-FRONTEND-SYNCHRONOUS-REGISTER-NESTED"
    )

    var ifAfter: FrontendException = null
    captureItems {
      sync("p_before_if")
      ifAfter = intercept[FrontendException] { generateIf(enabled) {} otherwise {} }
    }
    assert(ifAfter.code == "MORPH-FRONTEND-SYNCHRONOUS-REGISTER-MIXED")

    var afterCase: FrontendException = null
    captureItems {
      generateCase(selector).choice(0, "g_zero") {}.default("g_other") {}
      afterCase = intercept[FrontendException](sync("p_after_case"))
    }
    assert(afterCase.code == "MORPH-FRONTEND-SYNCHRONOUS-REGISTER-MIXED")

    var forAfter: FrontendException = null
    captureItems {
      sync("p_before_for")
      forAfter = intercept[FrontendException] { for (_ <- 0 until count) () }
    }
    assert(forAfter.code == "MORPH-FRONTEND-SYNCHRONOUS-REGISTER-MIXED")

    var afterFor: FrontendException = null
    captureItems {
      for (_ <- 0 until count) ()
      afterFor = intercept[FrontendException](sync("p_after_for"))
    }
    assert(afterFor.code == "MORPH-FRONTEND-SYNCHRONOUS-REGISTER-MIXED")
  }

  test("rejects escaped and foreign generate-scope provenance before emission") {
    val count = HdlInt.param("COUNT", default = 1, min = 1, max = 1)
    var escapedClock: FrontendNode[RtlExpr] = null
    captureItems {
      for (index <- 0 until count) {
        escapedClock = FrontendNode(
          Ref("clk"),
          scopes = Set(index.token),
          origin = index.origin
        )
      }
    }

    assert(
      intercept[FrontendException] {
        captureItems {
          emitSynchronousRegister(
            "p_escaped",
            escapedClock,
            ref("reset"),
            proceduralAssign("data_out", ref("data_in"))
          )
        }
      }.code == "MORPH-FRONTEND-GENINDEX-ESCAPED"
    )

    captureItems {
      for (_ <- 0 until count) {
        assert(
          intercept[FrontendException] {
            emitSynchronousRegister(
              "p_foreign_scope",
              escapedClock,
              ref("reset"),
              proceduralAssign("data_out", ref("data_in"))
            )
          }.code == "MORPH-FRONTEND-GENINDEX-ESCAPED"
        )
      }
    }
  }

  test("keeps capture ownership thread-local without poisoning the owner") {
    val executor = Executors.newSingleThreadExecutor()
    var foreign: FrontendException = null
    try {
      val items = captureItems {
        foreign = executor
          .submit(new Callable[FrontendException] {
            override def call(): FrontendException =
              intercept[FrontendException] {
                emitSynchronousRegister(
                  "p_foreign_thread",
                  ref("clk"),
                  ref("reset"),
                  proceduralAssign("data_out", ref("data_in"))
                )
              }
          })
          .get(10, TimeUnit.SECONDS)

        emitSynchronousRegister(
          "p_owner_thread",
          ref("clk"),
          ref("reset"),
          proceduralAssign("data_out", ref("data_in"))
        )
      }
      assert(foreign.code == "MORPH-FRONTEND-EMIT-OUTSIDE-CAPTURE")
      assert(items.raw.head.asInstanceOf[SynchronousRegister].label == "p_owner_thread")
    } finally {
      executor.shutdownNow()
      assert(executor.awaitTermination(10, TimeUnit.SECONDS))
    }
  }

  test("preserves old runtime APIs equality guards and compile-time opacity") {
    val continuous = captureItems { emitContinuousAssign("result", ref("source")) }
    assert(continuous.raw.head == ContinuousAssign(Ref("result"), Ref("source")))

    val combinational = captureItems {
      emitCombinationalIf(
        "p_existing",
        ref("select"),
        Vector(proceduralAssign("result", ref("a"))),
        Vector(proceduralAssign("result", ref("b")))
      )
    }
    assert(combinational.raw.head.isInstanceOf[CombinationalIf])

    val symbolic = HdlInt.param("VALUE", default = 1, min = 0, max = 2)
    assert(
      intercept[FrontendException](symbolic == HdlInt.literal(1)).code ==
        "MORPH-FRONTEND-SYMBOLIC-COMPARISON-UNSUPPORTED"
    )

    assertTypeError("""
      import morphhdl.frontend._
      import morphhdl.frontend.ParamRtlFrontend._
      emitSynchronousRegister(
        "p_bad_clock",
        HdlBool.literal(true),
        ref("reset"),
        proceduralAssign("data_out", ref("data_in"))
      )
    """)
    assertTypeError("""
      import morphhdl.frontend._
      import morphhdl.frontend.ParamRtlFrontend._
      import morphhdl.paramrtl.{ProceduralAssign, RtlExpr}
      emitSynchronousRegister(
        "p_raw_assignment",
        ref("clk"),
        ref("reset"),
        ProceduralAssign(RtlExpr.Ref("data_out"), RtlExpr.Ref("data_in"))
      )
    """)
  }
}
