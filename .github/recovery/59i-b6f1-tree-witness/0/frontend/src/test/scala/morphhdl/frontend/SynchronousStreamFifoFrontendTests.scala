package morphhdl.frontend

import morphhdl.frontend.ParamRtlFrontend._
import morphhdl.paramrtl.IntExpr.{Literal, ParameterRef}
import morphhdl.paramrtl.ModuleItem.SynchronousStreamFifo
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.RtlExpr.Ref
import morphhdl.paramrtl.Signedness.Unsigned
import morphhdl.paramrtl.{PackedBits, RtlExpr}
import org.scalatest.funsuite.AnyFunSuite

class SynchronousStreamFifoFrontendTests extends AnyFunSuite {
  private val Prefix = "MORPH-FRONTEND-SYNCHRONOUS-STREAM-FIFO"

  private def emit(depth: HdlInt, label: String = "p_fifo", memoryName: String = "memory"): Unit =
    emitSynchronousStreamFifo(
      label,
      memoryName,
      ref("clk"),
      ref("reset"),
      ref("push_valid"),
      ref("push_ready"),
      ref("push_data"),
      ref("pop_valid"),
      ref("pop_ready"),
      ref("pop_data"),
      packedBits(8),
      depth
    )

  test("captures the exact atomic FIFO intent and retained provenance") {
    val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 32)
    val depth = HdlInt.param("DEPTH", default = 5, min = 1, max = 8)
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
      emitSynchronousStreamFifo(
        "p_fifo",
        "memory",
        guardedClock,
        ref("reset"),
        ref("push_valid"),
        ref("push_ready"),
        ref("push_data"),
        ref("pop_valid"),
        ref("pop_ready"),
        ref("pop_data"),
        packedBits(localWidth),
        depth
      )
    }

    assert(items.raw == Vector(SynchronousStreamFifo(
      "p_fifo",
      "memory",
      Ref("clk"),
      Ref("reset"),
      Ref("push_valid"),
      Ref("push_ready"),
      Ref("push_data"),
      Ref("pop_valid"),
      Ref("pop_ready"),
      Ref("pop_data"),
      PackedBits(morphhdl.paramrtl.IntExpr.LocalParameterRef("LOCAL_WIDTH"), Unsigned),
      ParameterRef("DEPTH")
    )))

    val module = moduleDef(
      "FifoProvenance",
      Vector(integerParameter(width), integerParameter(depth)),
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
    assert(module.parameters.map(_.name).toSet == Set("WIDTH", "DEPTH"))
    assert(module.localParameters.map(_.name) == Vector("LOCAL_WIDTH"))
    assert(module.booleanParameters.map(_.name) == Vector("FEATURE"))
    assert(module.booleanLocalParameters.map(_.name) == Vector("LOCAL_FEATURE"))
  }

  test("reports null non-ref and invalid roles with stable diagnostics") {
    val depth = HdlInt.param("DEPTH", default = 5, min = 1, max = 8)
    val nonRef = indexedPartSelect("bus", 0, 1)
    def attempt(
        label: String = "p_fifo",
        memoryName: String = "memory",
        clock: FrontendNode[RtlExpr] = ref("clk"),
        reset: FrontendNode[RtlExpr] = ref("reset"),
        pushValid: FrontendNode[RtlExpr] = ref("push_valid"),
        pushReady: FrontendNode[RtlExpr] = ref("push_ready"),
        pushData: FrontendNode[RtlExpr] = ref("push_data"),
        popValid: FrontendNode[RtlExpr] = ref("pop_valid"),
        popReady: FrontendNode[RtlExpr] = ref("pop_ready"),
        popData: FrontendNode[RtlExpr] = ref("pop_data"),
        elementType: FrontendNode[PackedBits] = packedBits(8),
        fifoDepth: HdlInt = depth
    ): Unit = captureItems {
      emitSynchronousStreamFifo(
        label,
        memoryName,
        clock,
        reset,
        pushValid,
        pushReady,
        pushData,
        popValid,
        popReady,
        popData,
        elementType,
        fifoDepth
      )
    }

    val cases = Vector(
      intercept[FrontendException](attempt(label = "bad-label")) -> s"$Prefix-LABEL-INVALID",
      intercept[FrontendException](attempt(memoryName = "bad-name")) -> s"$Prefix-NAME-INVALID",
      intercept[FrontendException](attempt(clock = null)) -> s"$Prefix-CLOCK-NULL",
      intercept[FrontendException](attempt(clock = nonRef)) -> s"$Prefix-CLOCK-NOT-REF",
      intercept[FrontendException](attempt(reset = ref("bad-name"))) -> s"$Prefix-RESET-INVALID",
      intercept[FrontendException](attempt(pushValid = null)) -> s"$Prefix-PUSH-VALID-NULL",
      intercept[FrontendException](attempt(pushReady = nonRef)) -> s"$Prefix-PUSH-READY-NOT-REF",
      intercept[FrontendException](attempt(pushData = ref("bad-name"))) -> s"$Prefix-PUSH-DATA-INVALID",
      intercept[FrontendException](attempt(popValid = null)) -> s"$Prefix-POP-VALID-NULL",
      intercept[FrontendException](attempt(popReady = nonRef)) -> s"$Prefix-POP-READY-NOT-REF",
      intercept[FrontendException](attempt(popData = ref("bad-name"))) -> s"$Prefix-POP-DATA-INVALID",
      intercept[FrontendException](attempt(elementType = null)) -> s"$Prefix-ELEMENT-TYPE-NULL",
      intercept[FrontendException](attempt(fifoDepth = null)) -> s"$Prefix-DEPTH-NULL"
    )
    cases.foreach { case (error, code) =>
      assert(error.code == code)
      assert(error.sourceLocation.nonEmpty)
      assert(error.suggestedReplacement.nonEmpty)
    }
  }

  test("requires the exact positive public depth handle") {
    val depth = HdlInt.param("DEPTH", default = 5, min = 1, max = 8)
    val literal = intercept[FrontendException] { captureItems { emit(HdlInt.literal(5)) } }
    assert(literal.code == s"$Prefix-DEPTH-NOT-PUBLIC-PARAMETER")

    val modified = intercept[FrontendException] { captureItems { emit(depth + 0) } }
    assert(modified.code == s"$Prefix-DEPTH-NOT-PUBLIC-PARAMETER")

    val nonPositive = HdlInt.param("ZERO_DEPTH", default = 0, min = 0, max = 8)
    val witness = intercept[FrontendException] { captureItems { emit(nonPositive) } }
    assert(witness.code == s"$Prefix-DEPTH-WITNESS-NONPOSITIVE")
  }

  test("failed calls are atomic and one capture accepts exactly one FIFO") {
    val depth = HdlInt.param("DEPTH", default = 5, min = 1, max = 8)
    assert(intercept[FrontendException](emit(depth)).code == "MORPH-FRONTEND-EMIT-OUTSIDE-CAPTURE")

    var invalid: FrontendException = null
    var duplicate: FrontendException = null
    val items = captureItems {
      invalid = intercept[FrontendException] {
        emitSynchronousStreamFifo(
          "p_invalid",
          "bad_memory",
          ref("clk"),
          ref("reset"),
          ref("push_valid"),
          indexedPartSelect("bus", 0, 1),
          ref("push_data"),
          ref("pop_valid"),
          ref("pop_ready"),
          ref("pop_data"),
          packedBits(8),
          depth
        )
      }
      emit(depth, "p_retry", "retry_memory")
      duplicate = intercept[FrontendException](emit(depth, "p_discarded", "discarded_memory"))
    }
    assert(invalid.code == s"$Prefix-PUSH-READY-NOT-REF")
    assert(duplicate.code == s"$Prefix-MULTIPLE")
    assert(items.raw.map(_.asInstanceOf[SynchronousStreamFifo].label) == Vector("p_retry"))
  }

  test("rejects ordinary generate and runtime siblings while deferring RTL typing") {
    val depth = HdlInt.param("DEPTH", default = 5, min = 1, max = 8)
    var mixedOrdinary: FrontendException = null
    captureItems {
      emitContinuousAssign("out", ref("push_data"))
      mixedOrdinary = intercept[FrontendException](emit(depth))
    }
    assert(mixedOrdinary.code == s"$Prefix-MIXED")

    val nested = intercept[FrontendException] {
      captureItems { generateIf(HdlBool.literal(true)) { emit(depth) } otherwise {} }
    }
    assert(nested.code == s"$Prefix-NESTED")

    var mixedRuntime: FrontendException = null
    captureItems {
      emit(depth)
      mixedRuntime = intercept[FrontendException] {
        emitSynchronousRegister("p_reg", ref("clk"), ref("reset"), proceduralAssign("pop_data", ref("push_data")))
      }
    }
    assert(mixedRuntime.code == "MORPH-FRONTEND-RUNTIME-PROCESS-MIXED")

    val deferred = captureItems {
      emitSynchronousStreamFifo(
        "p_deferred",
        "memory",
        ref("same"),
        ref("same"),
        ref("same"),
        ref("same"),
        ref("same"),
        ref("same"),
        ref("same"),
        ref("same"),
        FrontendNode(PackedBits(Literal(0), Unsigned), origin = SourceOrigin("Deferred.scala", 1)),
        depth
      )
    }
    assert(deferred.raw.head.asInstanceOf[SynchronousStreamFifo].depth == ParameterRef("DEPTH"))
  }
}
