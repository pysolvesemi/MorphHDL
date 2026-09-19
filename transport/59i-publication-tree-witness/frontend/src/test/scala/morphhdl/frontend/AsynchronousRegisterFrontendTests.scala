package morphhdl.frontend

import java.util.concurrent.{Callable, Executors, TimeUnit}

import morphhdl.frontend.ParamRtlFrontend._
import morphhdl.paramrtl.ModuleItem.{
  AsynchronousRegister,
  CombinationalIf,
  ContinuousAssign,
  SynchronousRegister
}
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.{ProceduralAssign, RtlExpr}
import morphhdl.paramrtl.RtlExpr.Ref
import org.scalatest.funsuite.AnyFunSuite

class AsynchronousRegisterFrontendTests extends AnyFunSuite {
  private def async(label: String = "p_async_register"): Unit =
    emitAsynchronousRegister(
      label,
      ref("clk"),
      ref("reset"),
      proceduralAssign("data_out", ref("data_in"))
    )

  private def sync(label: String = "p_sync_register"): Unit =
    emitSynchronousRegister(
      label,
      ref("clk"),
      ref("reset"),
      proceduralAssign("data_out", ref("data_in"))
    )

  private def comb(label: String = "p_comb"): Unit =
    emitCombinationalIf(
      label,
      ref("select"),
      Vector(proceduralAssign("data_out", ref("data_in"))),
      Vector(proceduralAssign("data_out", ref("other")))
    )

  test("captures one exact posedge active-high asynchronous-reset register intent") {
    val items = captureItems { async() }

    assert(items.raw == Vector(
      AsynchronousRegister(
        label = "p_async_register",
        clock = Ref("clk"),
        reset = Ref("reset"),
        assignment = ProceduralAssign(Ref("data_out"), Ref("data_in"))
      )
    ))

    val module = moduleDef(
      name = "AsyncRegister",
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

  test("retains every public and local provenance identity with its origin") {
    val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 32)
    val enabled = HdlBool.param("ENABLED", default = true)
    val localWidth = localParam("LOCAL_WIDTH", width)
    val localEnabled = localParam("LOCAL_ENABLED", enabled)

    def guardedRef(name: String, origin: SourceOrigin): FrontendNode[RtlExpr] =
      FrontendNode(
        Ref(name),
        parameters = width.parameters ++ localEnabled.integerParameters,
        booleanParameters = enabled.parameters ++ localEnabled.parameters,
        localParameters = localWidth.localParameters ++ localEnabled.localParameters,
        booleanLocalParameters = localWidth.booleanLocalParameters ++
          localEnabled.booleanLocalParameters,
        origin = origin
      )

    val items = captureItems {
      emitAsynchronousRegister(
        "p_provenance",
        guardedRef("clk", width.origin),
        guardedRef("reset", enabled.origin),
        proceduralAssign("data_out", guardedRef("data_in", localWidth.origin))
      )
    }

    val module = moduleDef(
      name = "AsyncRegisterProvenance",
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

  test("reports distinct undeclared and already-claimed provenance at the retained origin") {
    val declared = HdlInt.param("WIDTH", default = 8, min = 1, max = 32)
    val used = HdlInt.param("WIDTH", default = 4, min = 1, max = 16)
    val guardedClock = FrontendNode[RtlExpr](
      Ref("clk"),
      parameters = used.parameters,
      origin = used.origin
    )
    val publicItems = captureItems {
      emitAsynchronousRegister(
        "p_public_identity",
        guardedClock,
        ref("reset"),
        proceduralAssign("data_out", ref("data_in"))
      )
    }

    val mismatch = intercept[FrontendException] {
      moduleDef(
        "AsyncIdentityMismatch",
        Vector(integerParameter(declared)),
        Vector.empty,
        publicItems
      )
    }
    assert(mismatch.code == "MORPH-FRONTEND-PARAMETER-TOKEN-MISMATCH")
    assert(mismatch.origin == used.origin)

    val missing = intercept[FrontendException] {
      moduleDef("AsyncIdentityMissing", Vector.empty, Vector.empty, publicItems)
    }
    assert(missing.code == "MORPH-FRONTEND-PARAMETER-NOT-DECLARED")
    assert(missing.origin == used.origin)

    val claimed = localParam("CLAIMED_RESET", HdlBool.literal(true))
    moduleDef(
      "ClaimedLocalOwner",
      Vector.empty,
      Vector.empty,
      captureItems {},
      booleanLocalParameters = Vector(booleanLocalParameter(claimed))
    )
    val claimedReset = FrontendNode[RtlExpr](
      Ref("reset"),
      booleanLocalParameters = claimed.booleanLocalParameters,
      origin = claimed.origin
    )
    val claimedItems = captureItems {
      emitAsynchronousRegister(
        "p_claimed",
        ref("clk"),
        claimedReset,
        proceduralAssign("data_out", ref("data_in"))
      )
    }
    val foreign = intercept[FrontendException] {
      moduleDef("ClaimedLocalReuse", Vector.empty, Vector.empty, claimedItems)
    }
    assert(foreign.code == "MORPH-FRONTEND-LOCAL-PARAMETER-FOREIGN")
    assert(foreign.origin == claimed.origin)
    assert(foreign.detail.contains("ClaimedLocalOwner"))
  }

  test("defers port direction width and packed-type proof to ParamRTL") {
    val items = captureItems {
      emitAsynchronousRegister(
        "p_deferred",
        ref("missing_clock"),
        ref("wide_or_output_reset"),
        proceduralAssign("input_target", ref("output_value"))
      )
    }

    assert(items.raw.head == AsynchronousRegister(
      "p_deferred",
      Ref("missing_clock"),
      Ref("wide_or_output_reset"),
      ProceduralAssign(Ref("input_target"), Ref("output_value"))
    ))
  }

  test("rejects null non-ref and invalid label clock reset target and value inputs") {
    val assignment = proceduralAssign("data_out", ref("data_in"))

    def emit(
        label: String = "p_register",
        clock: FrontendNode[RtlExpr] = ref("clk"),
        reset: FrontendNode[RtlExpr] = ref("reset"),
        value: FrontendNode[ProceduralAssign] = assignment
    ): Unit = captureItems {
      emitAsynchronousRegister(label, clock, reset, value)
    }

    val cases = Vector(
      intercept[FrontendException](emit(label = null)) ->
        "MORPH-FRONTEND-ASYNCHRONOUS-REGISTER-LABEL-INVALID",
      intercept[FrontendException](emit(label = "not-portable")) ->
        "MORPH-FRONTEND-ASYNCHRONOUS-REGISTER-LABEL-INVALID",
      intercept[FrontendException](emit(clock = null)) ->
        "MORPH-FRONTEND-ASYNCHRONOUS-REGISTER-CLOCK-NULL",
      intercept[FrontendException](emit(clock = indexedPartSelect("bus", 0, 1))) ->
        "MORPH-FRONTEND-ASYNCHRONOUS-REGISTER-CLOCK-NOT-REF",
      intercept[FrontendException](emit(clock = ref(null))) ->
        "MORPH-FRONTEND-ASYNCHRONOUS-REGISTER-CLOCK-INVALID",
      intercept[FrontendException](emit(reset = null)) ->
        "MORPH-FRONTEND-ASYNCHRONOUS-REGISTER-RESET-NULL",
      intercept[FrontendException](emit(reset = indexedPartSelect("bus", 0, 1))) ->
        "MORPH-FRONTEND-ASYNCHRONOUS-REGISTER-RESET-NOT-REF",
      intercept[FrontendException](emit(reset = ref("not-portable"))) ->
        "MORPH-FRONTEND-ASYNCHRONOUS-REGISTER-RESET-INVALID",
      intercept[FrontendException](emit(value = null)) ->
        "MORPH-FRONTEND-ASYNCHRONOUS-REGISTER-ASSIGNMENT-NULL"
    )
    cases.foreach { case (error, code) => assert(error.code == code) }

    val invalidTarget = FrontendNode(
      ProceduralAssign(Ref("not-portable"), Ref("data_in")),
      origin = SourceOrigin("InvalidTarget.scala", 7)
    )
    val targetError = intercept[FrontendException](emit(value = invalidTarget))
    assert(targetError.code == "MORPH-FRONTEND-ASYNCHRONOUS-REGISTER-TARGET-INVALID")
    assert(targetError.origin == invalidTarget.origin)

    val invalidValue = FrontendNode(
      ProceduralAssign(Ref("data_out"), Ref("not-portable")),
      origin = SourceOrigin("InvalidValue.scala", 9)
    )
    val valueError = intercept[FrontendException](emit(value = invalidValue))
    assert(valueError.code == "MORPH-FRONTEND-ASYNCHRONOUS-REGISTER-VALUE-INVALID")
    assert(valueError.origin == invalidValue.origin)
  }

  test("requires parameterized capture and remains stateless under concrete elaboration") {
    assert(
      intercept[FrontendException](async()).code == "MORPH-FRONTEND-EMIT-OUTSIDE-CAPTURE"
    )
    assert(
      intercept[FrontendException] {
        FrontendSession.concrete(async())
      }.code == "MORPH-FRONTEND-EMIT-OUTSIDE-CAPTURE"
    )
  }

  test("failed validation is atomic and permits a same-capture retry") {
    var invalid: FrontendException = null
    var duplicate: FrontendException = null
    val items = captureItems {
      invalid = intercept[FrontendException](async("bad-label"))
      async("p_kept")
      duplicate = intercept[FrontendException](async("p_discarded"))
    }
    assert(invalid.code == "MORPH-FRONTEND-ASYNCHRONOUS-REGISTER-LABEL-INVALID")
    assert(duplicate.code == "MORPH-FRONTEND-ASYNCHRONOUS-REGISTER-MULTIPLE")
    assert(items.raw.map(_.asInstanceOf[AsynchronousRegister].label) == Vector("p_kept"))
  }

  test("mutually excludes asynchronous synchronous and combinational processes in both orders") {
    def mixed(first: => Unit, second: => Unit): FrontendException = {
      var error: FrontendException = null
      captureItems {
        first
        error = intercept[FrontendException](second)
      }
      error
    }

    Vector(
      mixed(async(), sync()),
      mixed(sync(), async()),
      mixed(async(), comb()),
      mixed(comb(), async())
    ).foreach(error => assert(error.code == "MORPH-FRONTEND-RUNTIME-PROCESS-MIXED"))
  }

  test("mutually excludes ordinary continuous and instance siblings in both orders") {
    var continuousAfter: FrontendException = null
    captureItems {
      async("p_before_continuous")
      continuousAfter = intercept[FrontendException] {
        emitContinuousAssign("extra", ref("data_in"))
      }
    }
    assert(continuousAfter.code == "MORPH-FRONTEND-ASYNCHRONOUS-REGISTER-MIXED")

    var afterContinuous: FrontendException = null
    captureItems {
      emitContinuousAssign("extra", ref("data_in"))
      afterContinuous = intercept[FrontendException](async("p_after_continuous"))
    }
    assert(afterContinuous.code == "MORPH-FRONTEND-ASYNCHRONOUS-REGISTER-MIXED")

    var instanceAfter: FrontendException = null
    captureItems {
      async("p_before_instance")
      instanceAfter = intercept[FrontendException](emitInstance("helper", "Helper"))
    }
    assert(instanceAfter.code == "MORPH-FRONTEND-ASYNCHRONOUS-REGISTER-MIXED")

    var afterInstance: FrontendException = null
    captureItems {
      emitInstance("helper", "Helper")
      afterInstance = intercept[FrontendException](async("p_after_instance"))
    }
    assert(afterInstance.code == "MORPH-FRONTEND-ASYNCHRONOUS-REGISTER-MIXED")
  }

  test("rejects generate nesting and generate siblings in both orders") {
    val enabled = HdlBool.literal(true)
    val selector = HdlInt.literal(0)
    val count = HdlInt.param("COUNT", default = 1, min = 1, max = 1)

    Vector(
      intercept[FrontendException] {
        captureItems { generateIf(enabled) { async() } otherwise {} }
      },
      intercept[FrontendException] {
        captureItems {
          generateCase(selector).choice(0, "g_zero") { async() }.default("g_other") {}
        }
      },
      intercept[FrontendException] {
        captureItems { for (_ <- 0 until count) async() }
      }
    ).foreach(error => assert(error.code == "MORPH-FRONTEND-ASYNCHRONOUS-REGISTER-NESTED"))

    var ifAfter: FrontendException = null
    captureItems {
      async("p_before_if")
      ifAfter = intercept[FrontendException] { generateIf(enabled) {} otherwise {} }
    }
    assert(ifAfter.code == "MORPH-FRONTEND-ASYNCHRONOUS-REGISTER-MIXED")

    var afterIf: FrontendException = null
    captureItems {
      generateIf(enabled) {} otherwise {}
      afterIf = intercept[FrontendException](async("p_after_if"))
    }
    assert(afterIf.code == "MORPH-FRONTEND-ASYNCHRONOUS-REGISTER-MIXED")

    var caseAfter: FrontendException = null
    captureItems {
      async("p_before_case")
      caseAfter = intercept[FrontendException] {
        generateCase(selector).choice(0, "g_zero") {}.default("g_other") {}
      }
    }
    assert(caseAfter.code == "MORPH-FRONTEND-ASYNCHRONOUS-REGISTER-MIXED")

    var afterCase: FrontendException = null
    captureItems {
      generateCase(selector).choice(0, "g_zero") {}.default("g_other") {}
      afterCase = intercept[FrontendException](async("p_after_case"))
    }
    assert(afterCase.code == "MORPH-FRONTEND-ASYNCHRONOUS-REGISTER-MIXED")

    var forAfter: FrontendException = null
    captureItems {
      async("p_before_for")
      forAfter = intercept[FrontendException] { for (_ <- 0 until count) () }
    }
    assert(forAfter.code == "MORPH-FRONTEND-ASYNCHRONOUS-REGISTER-MIXED")

    var afterFor: FrontendException = null
    captureItems {
      for (_ <- 0 until count) ()
      afterFor = intercept[FrontendException](async("p_after_for"))
    }
    assert(afterFor.code == "MORPH-FRONTEND-ASYNCHRONOUS-REGISTER-MIXED")
  }

  test("rejects escaped and foreign generate-scope provenance without leaking a process") {
    val count = HdlInt.param("COUNT", default = 1, min = 1, max = 1)
    var escapedReset: FrontendNode[RtlExpr] = null
    captureItems {
      for (index <- 0 until count) {
        escapedReset = FrontendNode(
          Ref("reset"),
          scopes = Set(index.token),
          origin = index.origin
        )
      }
    }

    var escaped: FrontendException = null
    val items = captureItems {
      escaped = intercept[FrontendException] {
        emitAsynchronousRegister(
          "p_escaped",
          ref("clk"),
          escapedReset,
          proceduralAssign("data_out", ref("data_in"))
        )
      }
      async("p_retry")
    }
    assert(escaped.code == "MORPH-FRONTEND-GENINDEX-ESCAPED")
    assert(escaped.origin == escapedReset.origin)
    assert(items.raw.map(_.asInstanceOf[AsynchronousRegister].label) == Vector("p_retry"))

    captureItems {
      for (_ <- 0 until count) {
        assert(
          intercept[FrontendException] {
            emitAsynchronousRegister(
              "p_foreign_scope",
              ref("clk"),
              escapedReset,
              proceduralAssign("data_out", ref("data_in"))
            )
          }.code == "MORPH-FRONTEND-GENINDEX-ESCAPED"
        )
      }
    }
  }

  test("keeps capture ownership thread-local and leaves the owner retryable") {
    val executor = Executors.newSingleThreadExecutor()
    var foreign: FrontendException = null
    try {
      val items = captureItems {
        foreign = executor
          .submit(new Callable[FrontendException] {
            override def call(): FrontendException =
              intercept[FrontendException] { async("p_foreign_thread") }
          })
          .get(10, TimeUnit.SECONDS)

        async("p_owner_thread")
      }
      assert(foreign.code == "MORPH-FRONTEND-EMIT-OUTSIDE-CAPTURE")
      assert(items.raw.head.asInstanceOf[AsynchronousRegister].label == "p_owner_thread")
    } finally {
      executor.shutdownNow()
      assert(executor.awaitTermination(10, TimeUnit.SECONDS))
    }
  }

  test("preserves existing runtime APIs and compile-time frontend opacity") {
    val continuous = captureItems { emitContinuousAssign("result", ref("source")) }
    assert(continuous.raw.head == ContinuousAssign(Ref("result"), Ref("source")))
    assert(captureItems { comb() }.raw.head.isInstanceOf[CombinationalIf])
    assert(captureItems { sync() }.raw.head.isInstanceOf[SynchronousRegister])

    assertTypeError("""
      import morphhdl.frontend._
      import morphhdl.frontend.ParamRtlFrontend._
      emitAsynchronousRegister(
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
      emitAsynchronousRegister(
        "p_raw_assignment",
        ref("clk"),
        ref("reset"),
        ProceduralAssign(RtlExpr.Ref("data_out"), RtlExpr.Ref("data_in"))
      )
    """)
  }
}
