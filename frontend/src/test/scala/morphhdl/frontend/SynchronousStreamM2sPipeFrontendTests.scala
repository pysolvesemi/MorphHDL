package morphhdl.frontend

import morphhdl.frontend.ParamRtlFrontend._
import morphhdl.paramrtl.IntExpr.{Literal, LocalParameterRef}
import morphhdl.paramrtl.ModuleItem.SynchronousStreamM2sPipe
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.RtlExpr.Ref
import morphhdl.paramrtl.Signedness.Unsigned
import morphhdl.paramrtl.{PackedBits, RtlExpr}
import org.scalatest.funsuite.AnyFunSuite

class SynchronousStreamM2sPipeFrontendTests extends AnyFunSuite {
  private val Prefix = "MORPH-FRONTEND-SYNCHRONOUS-STREAM-M2S-PIPE"

  private def emit(label: String = "p_m2s_pipe"): Unit =
    emitSynchronousStreamM2sPipe(
      label,
      ref("clk"),
      ref("reset"),
      ref("push_valid"),
      ref("push_ready"),
      ref("push_data"),
      ref("pop_valid"),
      ref("pop_ready"),
      ref("pop_data"),
      packedBits(8)
    )

  test("captures the exact atomic m2s pipe intent and retained provenance") {
    val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 32)
    val feature = HdlBool.param("FEATURE", default = true)
    val localWidth = localParam("LOCAL_WIDTH", width)
    val localFeature = localParam("LOCAL_FEATURE", feature)
    val guardedClock = FrontendNode[RtlExpr](
      Ref("clk"),
      parameters = width.parameters,
      booleanParameters = feature.parameters,
      localParameters = localWidth.localParameters,
      booleanLocalParameters = localFeature.booleanLocalParameters,
      origin = width.origin
    )

    val items = captureItems {
      emitSynchronousStreamM2sPipe(
        "p_m2s_pipe",
        guardedClock,
        ref("reset"),
        ref("push_valid"),
        ref("push_ready"),
        ref("push_data"),
        ref("pop_valid"),
        ref("pop_ready"),
        ref("pop_data"),
        packedBits(localWidth)
      )
    }

    assert(items.raw == Vector(SynchronousStreamM2sPipe(
      "p_m2s_pipe",
      Ref("clk"),
      Ref("reset"),
      Ref("push_valid"),
      Ref("push_ready"),
      Ref("push_data"),
      Ref("pop_valid"),
      Ref("pop_ready"),
      Ref("pop_data"),
      PackedBits(LocalParameterRef("LOCAL_WIDTH"), Unsigned)
    )))

    val module = moduleDef(
      "M2sPipeProvenance",
      Vector(integerParameter(width)),
      Vector(
        port("clk", Input, packedBits(1)),
        port("reset", Input, packedBits(1)),
        port("push_valid", Input, packedBits(1)),
        port("push_ready", Output, packedBits(1)),
        port("push_data", Input, packedBits(localWidth)),
        port("pop_valid", Output, packedBits(1)),
        port("pop_ready", Input, packedBits(1)),
        port("pop_data", Output, packedBits(localWidth))
      ),
      items,
      localParameters = Vector(integerLocalParameter(localWidth)),
      booleanParameters = Vector(booleanParameter(feature)),
      booleanLocalParameters = Vector(booleanLocalParameter(localFeature))
    )
    assert(module.parameters.map(_.name) == Vector("WIDTH"))
    assert(module.localParameters.map(_.name) == Vector("LOCAL_WIDTH"))
    assert(module.booleanParameters.map(_.name) == Vector("FEATURE"))
    assert(module.booleanLocalParameters.map(_.name) == Vector("LOCAL_FEATURE"))
  }

  test("reports null non-ref and invalid roles with stable diagnostics") {
    val nonRef = indexedPartSelect("bus", 0, 1)
    def attempt(
        label: String = "p_m2s_pipe",
        clock: FrontendNode[RtlExpr] = ref("clk"),
        reset: FrontendNode[RtlExpr] = ref("reset"),
        pushValid: FrontendNode[RtlExpr] = ref("push_valid"),
        pushReady: FrontendNode[RtlExpr] = ref("push_ready"),
        pushData: FrontendNode[RtlExpr] = ref("push_data"),
        popValid: FrontendNode[RtlExpr] = ref("pop_valid"),
        popReady: FrontendNode[RtlExpr] = ref("pop_ready"),
        popData: FrontendNode[RtlExpr] = ref("pop_data"),
        elementType: FrontendNode[PackedBits] = packedBits(8)
    ): Unit = captureItems {
      emitSynchronousStreamM2sPipe(
        label,
        clock,
        reset,
        pushValid,
        pushReady,
        pushData,
        popValid,
        popReady,
        popData,
        elementType
      )
    }

    val cases = Vector(
      intercept[FrontendException](attempt(label = "bad-label")) -> s"$Prefix-LABEL-INVALID",
      intercept[FrontendException](attempt(clock = null)) -> s"$Prefix-CLOCK-NULL",
      intercept[FrontendException](attempt(clock = nonRef)) -> s"$Prefix-CLOCK-NOT-REF",
      intercept[FrontendException](attempt(reset = ref("bad-name"))) -> s"$Prefix-RESET-INVALID",
      intercept[FrontendException](attempt(pushValid = null)) -> s"$Prefix-PUSH-VALID-NULL",
      intercept[FrontendException](attempt(pushReady = nonRef)) -> s"$Prefix-PUSH-READY-NOT-REF",
      intercept[FrontendException](attempt(pushData = ref("bad-name"))) -> s"$Prefix-PUSH-DATA-INVALID",
      intercept[FrontendException](attempt(popValid = null)) -> s"$Prefix-POP-VALID-NULL",
      intercept[FrontendException](attempt(popReady = nonRef)) -> s"$Prefix-POP-READY-NOT-REF",
      intercept[FrontendException](attempt(popData = ref("bad-name"))) -> s"$Prefix-POP-DATA-INVALID",
      intercept[FrontendException](attempt(elementType = null)) -> s"$Prefix-ELEMENT-TYPE-NULL"
    )
    cases.foreach { case (error, code) =>
      assert(error.code == code)
      assert(error.sourceLocation.nonEmpty)
      assert(error.suggestedReplacement.nonEmpty)
    }
  }

  test("failed calls are atomic and one capture accepts exactly one pipe") {
    assert(intercept[FrontendException](emit()).code == "MORPH-FRONTEND-EMIT-OUTSIDE-CAPTURE")

    var invalid: FrontendException = null
    var duplicate: FrontendException = null
    val items = captureItems {
      invalid = intercept[FrontendException] {
        emitSynchronousStreamM2sPipe(
          "p_invalid",
          ref("clk"),
          ref("reset"),
          ref("push_valid"),
          indexedPartSelect("bus", 0, 1),
          ref("push_data"),
          ref("pop_valid"),
          ref("pop_ready"),
          ref("pop_data"),
          packedBits(8)
        )
      }
      emit("p_retry")
      duplicate = intercept[FrontendException](emit("p_discarded"))
    }
    assert(invalid.code == s"$Prefix-PUSH-READY-NOT-REF")
    assert(duplicate.code == s"$Prefix-MULTIPLE")
    assert(items.raw.map(_.asInstanceOf[SynchronousStreamM2sPipe].label) == Vector("p_retry"))
  }

  test("rejects ordinary generate and runtime siblings while deferring RTL typing") {
    var mixedOrdinary: FrontendException = null
    captureItems {
      emitContinuousAssign("out", ref("push_data"))
      mixedOrdinary = intercept[FrontendException](emit())
    }
    assert(mixedOrdinary.code == s"$Prefix-MIXED")

    val nested = intercept[FrontendException] {
      captureItems { generateIf(HdlBool.literal(true)) { emit() } otherwise {} }
    }
    assert(nested.code == s"$Prefix-NESTED")

    var mixedRuntime: FrontendException = null
    captureItems {
      emit()
      mixedRuntime = intercept[FrontendException] {
        emitSynchronousRegister(
          "p_reg",
          ref("clk"),
          ref("reset"),
          proceduralAssign("pop_data", ref("push_data"))
        )
      }
    }
    assert(mixedRuntime.code == "MORPH-FRONTEND-RUNTIME-PROCESS-MIXED")

    val deferred = captureItems {
      emitSynchronousStreamM2sPipe(
        "p_deferred",
        ref("same"),
        ref("same"),
        ref("same"),
        ref("same"),
        ref("same"),
        ref("same"),
        ref("same"),
        ref("same"),
        FrontendNode(PackedBits(Literal(0), Unsigned), origin = SourceOrigin("Deferred.scala", 1))
      )
    }
    assert(deferred.raw.head.isInstanceOf[SynchronousStreamM2sPipe])
  }
}
