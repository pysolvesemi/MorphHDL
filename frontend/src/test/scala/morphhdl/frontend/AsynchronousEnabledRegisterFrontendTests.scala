package morphhdl.frontend

import java.util.concurrent.{Callable, Executors, TimeUnit}

import morphhdl.frontend.ParamRtlFrontend._
import morphhdl.paramrtl.ModuleItem.{
  AsynchronousEnabledRegister,
  AsynchronousRegister,
  CombinationalIf,
  ContinuousAssign,
  SynchronousEnabledRegister,
  SynchronousRegister
}
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.RtlExpr.Ref
import morphhdl.paramrtl.{ProceduralAssign, RtlExpr}
import org.scalatest.funsuite.AnyFunSuite

class AsynchronousEnabledRegisterFrontendTests extends AnyFunSuite {
  private def asyncEnabled(label: String = "p_async_enabled_register"): Unit =
    emitAsynchronousEnabledRegister(
      label,
      ref("clk"),
      ref("reset"),
      ref("enable"),
      proceduralAssign("data_out", ref("data_in"))
    )

  private def syncEnabled(label: String = "p_sync_enabled_register"): Unit =
    emitSynchronousEnabledRegister(
      label,
      ref("clk"),
      ref("reset"),
      ref("enable"),
      proceduralAssign("data_out", ref("data_in"))
    )

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

  test("captures one exact asynchronous-reset enabled-register intent") {
    val items = captureItems { asyncEnabled() }

    assert(items.raw == Vector(
      AsynchronousEnabledRegister(
        label = "p_async_enabled_register",
        clock = Ref("clk"),
        reset = Ref("reset"),
        enable = Ref("enable"),
        assignment = ProceduralAssign(Ref("data_out"), Ref("data_in"))
      )
    ))

    val module = moduleDef(
      name = "AsyncEnabledRegister",
      parameters = Vector.empty,
      ports = Vector(
        port("clk", Input, packedBits(1)),
        port("reset", Input, packedBits(1)),
        port("enable", Input, packedBits(1)),
        port("data_in", Input, packedBits(8)),
        port("data_out", Output, packedBits(8))
      ),
      items = items
    )
    assert(module.items == items.raw)
  }

  test("unions and discharges integer Boolean and local provenance from all inputs") {
    val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 32)
    val feature = HdlBool.param("FEATURE", default = true)
    val localWidth = localParam("LOCAL_WIDTH", width)
    val localFeature = localParam("LOCAL_FEATURE", feature)

    def guardedRef(name: String, origin: SourceOrigin): FrontendNode[RtlExpr] =
      FrontendNode(
        Ref(name),
        parameters = width.parameters ++ localFeature.integerParameters,
        booleanParameters = feature.parameters ++ localFeature.parameters,
        localParameters = localWidth.localParameters ++ localFeature.localParameters,
        booleanLocalParameters = localWidth.booleanLocalParameters ++
          localFeature.booleanLocalParameters,
        origin = origin
      )

    val items = captureItems {
      emitAsynchronousEnabledRegister(
        "p_provenance",
        guardedRef("clk", width.origin),
        guardedRef("reset", feature.origin),
        guardedRef("enable", localWidth.origin),
        proceduralAssign("data_out", guardedRef("data_in", localFeature.origin))
      )
    }

    val module = moduleDef(
      name = "AsyncEnabledProvenance",
      parameters = Vector(integerParameter(width)),
      ports = Vector.empty,
      items = items,
      localParameters = Vector(integerLocalParameter(localWidth)),
      booleanParameters = Vector(booleanParameter(feature)),
      booleanLocalParameters = Vector(booleanLocalParameter(localFeature))
    )
    assert(module.parameters.map(_.name) == Vector("WIDTH"))
    assert(module.booleanParameters.map(_.name) == Vector("FEATURE"))
    assert(module.localParameters.map(_.name) == Vector("LOCAL_WIDTH"))
    assert(module.booleanLocalParameters.map(_.name) == Vector("LOCAL_FEATURE"))
  }

  test("reports undeclared mismatched and foreign provenance at retained origins") {
    val declared = HdlInt.param("WIDTH", default = 8, min = 1, max = 32)
    val used = HdlInt.param("WIDTH", default = 4, min = 1, max = 16)
    val guardedEnable = FrontendNode[RtlExpr](
      Ref("enable"),
      parameters = used.parameters,
      origin = used.origin
    )
    val publicItems = captureItems {
      emitAsynchronousEnabledRegister(
        "p_public_identity",
        ref("clk"),
        ref("reset"),
        guardedEnable,
        proceduralAssign("data_out", ref("data_in"))
      )
    }

    val mismatch = intercept[FrontendException] {
      moduleDef(
        "AsyncEnabledIdentityMismatch",
        Vector(integerParameter(declared)),
        Vector.empty,
        publicItems
      )
    }
    assert(mismatch.code == "MORPH-FRONTEND-PARAMETER-TOKEN-MISMATCH")
    assert(mismatch.origin == used.origin)

    val missing = intercept[FrontendException] {
      moduleDef("AsyncEnabledIdentityMissing", Vector.empty, Vector.empty, publicItems)
    }
    assert(missing.code == "MORPH-FRONTEND-PARAMETER-NOT-DECLARED")
    assert(missing.origin == used.origin)

    val claimed = localParam("CLAIMED_ENABLE", HdlBool.literal(true))
    moduleDef(
      "ClaimedAsyncEnabledOwner",
      Vector.empty,
      Vector.empty,
      captureItems {},
      booleanLocalParameters = Vector(booleanLocalParameter(claimed))
    )
    val claimedEnable = FrontendNode[RtlExpr](
      Ref("enable"),
      booleanLocalParameters = claimed.booleanLocalParameters,
      origin = claimed.origin
    )
    val claimedItems = captureItems {
      emitAsynchronousEnabledRegister(
        "p_claimed",
        ref("clk"),
        ref("reset"),
        claimedEnable,
        proceduralAssign("data_out", ref("data_in"))
      )
    }
    val foreign = intercept[FrontendException] {
      moduleDef("ClaimedAsyncEnabledReuse", Vector.empty, Vector.empty, claimedItems)
    }
    assert(foreign.code == "MORPH-FRONTEND-LOCAL-PARAMETER-FOREIGN")
    assert(foreign.origin == claimed.origin)
    assert(foreign.detail.contains("ClaimedAsyncEnabledOwner"))
  }

  test("rejects null non-ref and invalid identifiers with stable role diagnostics") {
    val assignment = proceduralAssign("data_out", ref("data_in"))

    def emit(
        label: String = "p_register",
        clock: FrontendNode[RtlExpr] = ref("clk"),
        reset: FrontendNode[RtlExpr] = ref("reset"),
        enable: FrontendNode[RtlExpr] = ref("enable"),
        value: FrontendNode[ProceduralAssign] = assignment
    ): Unit = captureItems {
      emitAsynchronousEnabledRegister(label, clock, reset, enable, value)
    }

    val cases = Vector(
      intercept[FrontendException](emit(label = null)) ->
        "MORPH-FRONTEND-ASYNCHRONOUS-ENABLED-REGISTER-LABEL-INVALID",
      intercept[FrontendException](emit(label = "not-portable")) ->
        "MORPH-FRONTEND-ASYNCHRONOUS-ENABLED-REGISTER-LABEL-INVALID",
      intercept[FrontendException](emit(clock = null)) ->
        "MORPH-FRONTEND-ASYNCHRONOUS-ENABLED-REGISTER-CLOCK-NULL",
      intercept[FrontendException](emit(clock = indexedPartSelect("bus", 0, 1))) ->
        "MORPH-FRONTEND-ASYNCHRONOUS-ENABLED-REGISTER-CLOCK-NOT-REF",
      intercept[FrontendException](emit(clock = ref(null))) ->
        "MORPH-FRONTEND-ASYNCHRONOUS-ENABLED-REGISTER-CLOCK-INVALID",
      intercept[FrontendException](emit(reset = null)) ->
        "MORPH-FRONTEND-ASYNCHRONOUS-ENABLED-REGISTER-RESET-NULL",
      intercept[FrontendException](emit(reset = indexedPartSelect("bus", 0, 1))) ->
        "MORPH-FRONTEND-ASYNCHRONOUS-ENABLED-REGISTER-RESET-NOT-REF",
      intercept[FrontendException](emit(reset = ref("not-portable"))) ->
        "MORPH-FRONTEND-ASYNCHRONOUS-ENABLED-REGISTER-RESET-INVALID",
      intercept[FrontendException](emit(enable = null)) ->
        "MORPH-FRONTEND-ASYNCHRONOUS-ENABLED-REGISTER-ENABLE-NULL",
      intercept[FrontendException](emit(enable = indexedPartSelect("bus", 0, 1))) ->
        "MORPH-FRONTEND-ASYNCHRONOUS-ENABLED-REGISTER-ENABLE-NOT-REF",
      intercept[FrontendException](emit(enable = ref("not-portable"))) ->
        "MORPH-FRONTEND-ASYNCHRONOUS-ENABLED-REGISTER-ENABLE-INVALID",
      intercept[FrontendException](emit(value = null)) ->
        "MORPH-FRONTEND-ASYNCHRONOUS-ENABLED-REGISTER-ASSIGNMENT-NULL"
    )
    cases.foreach { case (error, code) =>
      assert(error.code == code)
      assert(error.sourceLocation.nonEmpty)
      assert(error.suggestedReplacement.nonEmpty)
    }

    val invalidTarget = FrontendNode(
      ProceduralAssign(Ref("not-portable"), Ref("data_in")),
      origin = SourceOrigin("InvalidAsyncEnabledTarget.scala", 7)
    )
    val targetError = intercept[FrontendException](emit(value = invalidTarget))
    assert(
      targetError.code ==
        "MORPH-FRONTEND-ASYNCHRONOUS-ENABLED-REGISTER-TARGET-INVALID"
    )
    assert(targetError.origin == invalidTarget.origin)

    val invalidValue = FrontendNode(
      ProceduralAssign(Ref("data_out"), Ref("not-portable")),
      origin = SourceOrigin("InvalidAsyncEnabledValue.scala", 9)
    )
    val valueError = intercept[FrontendException](emit(value = invalidValue))
    assert(
      valueError.code ==
        "MORPH-FRONTEND-ASYNCHRONOUS-ENABLED-REGISTER-VALUE-INVALID"
    )
    assert(valueError.origin == invalidValue.origin)
  }

  test("defers declarations directions widths packed types and role separation to ParamRTL") {
    val items = captureItems {
      emitAsynchronousEnabledRegister(
        "p_deferred",
        ref("same_control"),
        ref("same_control"),
        ref("same_control"),
        proceduralAssign("missing_or_input_target", ref("same_control"))
      )
    }

    assert(items.raw.head == AsynchronousEnabledRegister(
      "p_deferred",
      Ref("same_control"),
      Ref("same_control"),
      Ref("same_control"),
      ProceduralAssign(Ref("missing_or_input_target"), Ref("same_control"))
    ))
    assert(
      moduleDef("DeferredAsyncEnabledRoles", Vector.empty, Vector.empty, items).items ==
        items.raw
    )
  }

  test("requires parameterized capture and remains stateless under concrete elaboration") {
    assert(
      intercept[FrontendException](asyncEnabled()).code ==
        "MORPH-FRONTEND-EMIT-OUTSIDE-CAPTURE"
    )
    assert(
      intercept[FrontendException] {
        FrontendSession.concrete(asyncEnabled())
      }.code == "MORPH-FRONTEND-EMIT-OUTSIDE-CAPTURE"
    )
  }

  test("failed calls roll back atomically and a successful process remains singular") {
    var invalid: FrontendException = null
    var duplicate: FrontendException = null
    val items = captureItems {
      invalid = intercept[FrontendException] {
        emitAsynchronousEnabledRegister(
          "p_invalid",
          ref("clk"),
          ref("reset"),
          indexedPartSelect("controls", 0, 1),
          proceduralAssign("data_out", ref("data_in"))
        )
      }
      asyncEnabled("p_retry")
      duplicate = intercept[FrontendException](asyncEnabled("p_discarded"))
    }
    assert(
      invalid.code ==
        "MORPH-FRONTEND-ASYNCHRONOUS-ENABLED-REGISTER-ENABLE-NOT-REF"
    )
    assert(
      duplicate.code ==
        "MORPH-FRONTEND-ASYNCHRONOUS-ENABLED-REGISTER-MULTIPLE"
    )
    assert(
      items.raw.map(_.asInstanceOf[AsynchronousEnabledRegister].label) ==
        Vector("p_retry")
    )
  }

  test("mutually excludes every existing runtime process in both orders") {
    def mixed(first: => Unit, second: => Unit): FrontendException = {
      var error: FrontendException = null
      captureItems {
        first
        error = intercept[FrontendException](second)
      }
      error
    }

    Vector(
      mixed(asyncEnabled(), sync()),
      mixed(sync(), asyncEnabled()),
      mixed(asyncEnabled(), async()),
      mixed(async(), asyncEnabled()),
      mixed(asyncEnabled(), syncEnabled()),
      mixed(syncEnabled(), asyncEnabled()),
      mixed(asyncEnabled(), comb()),
      mixed(comb(), asyncEnabled())
    ).foreach { error =>
      assert(error.code == "MORPH-FRONTEND-RUNTIME-PROCESS-MIXED")
      assert(error.detail.contains("cannot share one module-item capture"))
    }
  }

  test("mutually excludes continuous assignments and hierarchy in both orders") {
    var continuousAfter: FrontendException = null
    captureItems {
      asyncEnabled("p_before_continuous")
      continuousAfter = intercept[FrontendException] {
        emitContinuousAssign("extra", ref("data_in"))
      }
    }
    assert(
      continuousAfter.code ==
        "MORPH-FRONTEND-ASYNCHRONOUS-ENABLED-REGISTER-MIXED"
    )

    var afterContinuous: FrontendException = null
    captureItems {
      emitContinuousAssign("extra", ref("data_in"))
      afterContinuous = intercept[FrontendException] {
        asyncEnabled("p_after_continuous")
      }
    }
    assert(
      afterContinuous.code ==
        "MORPH-FRONTEND-ASYNCHRONOUS-ENABLED-REGISTER-MIXED"
    )

    var instanceAfter: FrontendException = null
    captureItems {
      asyncEnabled("p_before_instance")
      instanceAfter = intercept[FrontendException](emitInstance("helper", "Helper"))
    }
    assert(
      instanceAfter.code ==
        "MORPH-FRONTEND-ASYNCHRONOUS-ENABLED-REGISTER-MIXED"
    )

    var afterInstance: FrontendException = null
    captureItems {
      emitInstance("helper", "Helper")
      afterInstance = intercept[FrontendException](asyncEnabled("p_after_instance"))
    }
    assert(
      afterInstance.code ==
        "MORPH-FRONTEND-ASYNCHRONOUS-ENABLED-REGISTER-MIXED"
    )
  }

  test("rejects generate nesting and generate siblings in both orders") {
    val condition = HdlBool.literal(true)
    val selector = HdlInt.literal(0)
    val count = HdlInt.param("COUNT", default = 1, min = 1, max = 1)

    Vector(
      intercept[FrontendException] {
        captureItems { generateIf(condition) { asyncEnabled() } otherwise {} }
      },
      intercept[FrontendException] {
        captureItems {
          generateCase(selector)
            .choice(0, "g_zero") { asyncEnabled() }
            .default("g_other") {}
        }
      },
      intercept[FrontendException] {
        captureItems { for (_ <- 0 until count) asyncEnabled() }
      }
    ).foreach { error =>
      assert(
        error.code ==
          "MORPH-FRONTEND-ASYNCHRONOUS-ENABLED-REGISTER-NESTED"
      )
    }

    def processThenGenerate(generate: => Unit): FrontendException = {
      var error: FrontendException = null
      captureItems {
        asyncEnabled()
        error = intercept[FrontendException](generate)
      }
      error
    }

    def generateThenProcess(generate: => Unit): FrontendException = {
      var error: FrontendException = null
      captureItems {
        generate
        error = intercept[FrontendException](asyncEnabled())
      }
      error
    }

    Vector(
      processThenGenerate(generateIf(condition) {} otherwise {}),
      generateThenProcess(generateIf(condition) {} otherwise {}),
      processThenGenerate(
        generateCase(selector).choice(0, "g_zero") {}.default("g_other") {}
      ),
      generateThenProcess(
        generateCase(selector).choice(0, "g_zero") {}.default("g_other") {}
      ),
      processThenGenerate { for (_ <- 0 until count) () },
      generateThenProcess { for (_ <- 0 until count) () }
    ).foreach { error =>
      assert(
        error.code == "MORPH-FRONTEND-ASYNCHRONOUS-ENABLED-REGISTER-MIXED"
      )
    }
  }

  test("rejects escaped scope provenance without leaking a process") {
    val count = HdlInt.param("COUNT", default = 1, min = 1, max = 1)
    var escapedEnable: FrontendNode[RtlExpr] = null
    captureItems {
      for (index <- 0 until count) {
        escapedEnable = FrontendNode(
          Ref("enable"),
          scopes = Set(index.token),
          origin = index.origin
        )
      }
    }

    var escaped: FrontendException = null
    val items = captureItems {
      escaped = intercept[FrontendException] {
        emitAsynchronousEnabledRegister(
          "p_escaped",
          ref("clk"),
          ref("reset"),
          escapedEnable,
          proceduralAssign("data_out", ref("data_in"))
        )
      }
      asyncEnabled("p_retry")
    }
    assert(escaped.code == "MORPH-FRONTEND-GENINDEX-ESCAPED")
    assert(escaped.origin == escapedEnable.origin)
    assert(
      items.raw.map(_.asInstanceOf[AsynchronousEnabledRegister].label) ==
        Vector("p_retry")
    )

    captureItems {
      for (_ <- 0 until count) {
        assert(
          intercept[FrontendException] {
            emitAsynchronousEnabledRegister(
              "p_foreign_scope",
              ref("clk"),
              ref("reset"),
              escapedEnable,
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
              intercept[FrontendException] {
                asyncEnabled("p_foreign_thread")
              }
          })
          .get(10, TimeUnit.SECONDS)

        asyncEnabled("p_owner_thread")
      }
      assert(foreign.code == "MORPH-FRONTEND-EMIT-OUTSIDE-CAPTURE")
      assert(
        items.raw.head.asInstanceOf[AsynchronousEnabledRegister].label ==
          "p_owner_thread"
      )
    } finally {
      executor.shutdownNow()
      assert(executor.awaitTermination(10, TimeUnit.SECONDS))
    }
  }

  test("preserves prior runtime APIs and compile-time frontend opacity") {
    val continuous = captureItems { emitContinuousAssign("result", ref("source")) }
    assert(continuous.raw.head == ContinuousAssign(Ref("result"), Ref("source")))
    assert(captureItems { comb() }.raw.head.isInstanceOf[CombinationalIf])
    assert(captureItems { sync() }.raw.head.isInstanceOf[SynchronousRegister])
    assert(captureItems { async() }.raw.head.isInstanceOf[AsynchronousRegister])
    assert(
      captureItems { syncEnabled() }.raw.head.isInstanceOf[SynchronousEnabledRegister]
    )

    assertTypeError("""
      import morphhdl.frontend._
      import morphhdl.frontend.ParamRtlFrontend._
      emitAsynchronousEnabledRegister(
        "p_bad_enable",
        ref("clk"),
        ref("reset"),
        HdlBool.literal(true),
        proceduralAssign("data_out", ref("data_in"))
      )
    """)
    assertTypeError("""
      import morphhdl.frontend._
      import morphhdl.frontend.ParamRtlFrontend._
      import morphhdl.paramrtl.{ProceduralAssign, RtlExpr}
      emitAsynchronousEnabledRegister(
        "p_raw_assignment",
        ref("clk"),
        ref("reset"),
        ref("enable"),
        ProceduralAssign(RtlExpr.Ref("data_out"), RtlExpr.Ref("data_in"))
      )
    """)
  }
}
