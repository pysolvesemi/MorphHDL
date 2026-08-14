package morphhdl.frontend

import morphhdl.frontend.ParamRtlFrontend._
import morphhdl.paramrtl.IntExpr.{Literal, ParameterRef}
import morphhdl.paramrtl.ModuleItem.SynchronousReadFirstSimpleDualPortMemory
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.RtlExpr.Ref
import morphhdl.paramrtl.Signedness.Unsigned
import morphhdl.paramrtl.{PackedBits, RtlExpr}
import org.scalatest.funsuite.AnyFunSuite

class SynchronousReadFirstSimpleDualPortMemoryFrontendTests extends AnyFunSuite {
  private val Prefix = "MORPH-FRONTEND-SIMPLE-DUAL-PORT-MEMORY"

  private def memory(
      label: String = "p_memory",
      memoryName: String = "memory"
  ): Unit =
    emitSynchronousReadFirstSimpleDualPortMemory(
      label,
      memoryName,
      ref("clk"),
      ref("read_enable"),
      ref("write_enable"),
      ref("read_address"),
      ref("write_address"),
      ref("write_data"),
      ref("read_data"),
      packedBits(8),
      5
    )

  test("captures the exact simple dual-port memory intent and retained provenance") {
    val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 32)
    val depth = HdlInt.param("DEPTH", default = 5, min = 1, max = 8)
    val feature = HdlBool.param("FEATURE", default = true)
    val localWidth = localParam("LOCAL_WIDTH", width)
    val localDepth = localParam("LOCAL_DEPTH", depth)
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
      emitSynchronousReadFirstSimpleDualPortMemory(
        "p_memory",
        "memory",
        guardedClock,
        ref("read_enable"),
        ref("write_enable"),
        ref("read_address"),
        ref("write_address"),
        ref("write_data"),
        ref("read_data"),
        packedBits(localWidth),
        localDepth
      )
    }

    assert(items.raw == Vector(SynchronousReadFirstSimpleDualPortMemory(
      label = "p_memory",
      memoryName = "memory",
      clock = Ref("clk"),
      readEnable = Ref("read_enable"),
      writeEnable = Ref("write_enable"),
      readAddress = Ref("read_address"),
      writeAddress = Ref("write_address"),
      writeData = Ref("write_data"),
      readData = Ref("read_data"),
      elementType = PackedBits(morphhdl.paramrtl.IntExpr.LocalParameterRef("LOCAL_WIDTH"), Unsigned),
      depth = morphhdl.paramrtl.IntExpr.LocalParameterRef("LOCAL_DEPTH")
    )))

    val module = moduleDef(
      "DualPortProvenance",
      Vector(integerParameter(width), integerParameter(depth)),
      Vector(
        port("clk", Input, packedBits(1)),
        port("read_enable", Input, packedBits(1)),
        port("write_enable", Input, packedBits(1)),
        port("read_address", Input, packedBits(depth.addressWidth)),
        port("write_address", Input, packedBits(depth.addressWidth)),
        port("write_data", Input, packedBits(localWidth)),
        port("read_data", Output, packedBits(localWidth))
      ),
      items,
      localParameters = Vector(integerLocalParameter(localWidth), integerLocalParameter(localDepth)),
      booleanParameters = Vector(booleanParameter(feature)),
      booleanLocalParameters = Vector(booleanLocalParameter(localFeature))
    )
    assert(module.parameters.map(_.name).toSet == Set("WIDTH", "DEPTH"))
    assert(module.localParameters.map(_.name).toSet == Set("LOCAL_WIDTH", "LOCAL_DEPTH"))
    assert(module.booleanParameters.map(_.name) == Vector("FEATURE"))
    assert(module.booleanLocalParameters.map(_.name) == Vector("LOCAL_FEATURE"))
  }

  test("reports every null non-ref and invalid runtime role with stable diagnostics") {
    val element = packedBits(8)
    val memoryDepth = HdlInt.literal(5)
    def emit(
        label: String = "p_memory",
        memoryName: String = "memory",
        clock: FrontendNode[RtlExpr] = ref("clk"),
        readEnable: FrontendNode[RtlExpr] = ref("read_enable"),
        writeEnable: FrontendNode[RtlExpr] = ref("write_enable"),
        readAddress: FrontendNode[RtlExpr] = ref("read_address"),
        writeAddress: FrontendNode[RtlExpr] = ref("write_address"),
        writeData: FrontendNode[RtlExpr] = ref("write_data"),
        readData: FrontendNode[RtlExpr] = ref("read_data"),
        elementType: FrontendNode[PackedBits] = element,
        depth: HdlInt = memoryDepth
    ): Unit = captureItems {
      emitSynchronousReadFirstSimpleDualPortMemory(
        label,
        memoryName,
        clock,
        readEnable,
        writeEnable,
        readAddress,
        writeAddress,
        writeData,
        readData,
        elementType,
        depth
      )
    }
    val nonRef = indexedPartSelect("bus", 0, 1)

    val cases = Vector(
      intercept[FrontendException](emit(label = "bad-label")) -> s"$Prefix-LABEL-INVALID",
      intercept[FrontendException](emit(memoryName = "bad-name")) -> s"$Prefix-NAME-INVALID",
      intercept[FrontendException](emit(clock = null)) -> s"$Prefix-CLOCK-NULL",
      intercept[FrontendException](emit(clock = nonRef)) -> s"$Prefix-CLOCK-NOT-REF",
      intercept[FrontendException](emit(clock = ref("bad-name"))) -> s"$Prefix-CLOCK-INVALID",
      intercept[FrontendException](emit(readEnable = null)) -> s"$Prefix-READ-ENABLE-NULL",
      intercept[FrontendException](emit(readEnable = nonRef)) -> s"$Prefix-READ-ENABLE-NOT-REF",
      intercept[FrontendException](emit(readEnable = ref("bad-name"))) -> s"$Prefix-READ-ENABLE-INVALID",
      intercept[FrontendException](emit(writeEnable = null)) -> s"$Prefix-WRITE-ENABLE-NULL",
      intercept[FrontendException](emit(writeEnable = nonRef)) -> s"$Prefix-WRITE-ENABLE-NOT-REF",
      intercept[FrontendException](emit(writeEnable = ref("bad-name"))) -> s"$Prefix-WRITE-ENABLE-INVALID",
      intercept[FrontendException](emit(readAddress = null)) -> s"$Prefix-READ-ADDRESS-NULL",
      intercept[FrontendException](emit(readAddress = nonRef)) -> s"$Prefix-READ-ADDRESS-NOT-REF",
      intercept[FrontendException](emit(readAddress = ref("bad-name"))) -> s"$Prefix-READ-ADDRESS-INVALID",
      intercept[FrontendException](emit(writeAddress = null)) -> s"$Prefix-WRITE-ADDRESS-NULL",
      intercept[FrontendException](emit(writeAddress = nonRef)) -> s"$Prefix-WRITE-ADDRESS-NOT-REF",
      intercept[FrontendException](emit(writeAddress = ref("bad-name"))) -> s"$Prefix-WRITE-ADDRESS-INVALID",
      intercept[FrontendException](emit(writeData = null)) -> s"$Prefix-WRITE-DATA-NULL",
      intercept[FrontendException](emit(writeData = nonRef)) -> s"$Prefix-WRITE-DATA-NOT-REF",
      intercept[FrontendException](emit(writeData = ref("bad-name"))) -> s"$Prefix-WRITE-DATA-INVALID",
      intercept[FrontendException](emit(readData = null)) -> s"$Prefix-READ-DATA-NULL",
      intercept[FrontendException](emit(readData = nonRef)) -> s"$Prefix-READ-DATA-NOT-REF",
      intercept[FrontendException](emit(readData = ref("bad-name"))) -> s"$Prefix-READ-DATA-INVALID",
      intercept[FrontendException](emit(elementType = null)) -> s"$Prefix-ELEMENT-TYPE-NULL",
      intercept[FrontendException](emit(depth = null)) -> s"$Prefix-DEPTH-NULL"
    )
    cases.foreach { case (error, code) =>
      assert(error.code == code)
      assert(error.sourceLocation.nonEmpty)
      assert(error.suggestedReplacement.nonEmpty)
    }
  }

  test("failed calls are atomic and one capture accepts exactly one memory") {
    assert(intercept[FrontendException](memory()).code == "MORPH-FRONTEND-EMIT-OUTSIDE-CAPTURE")

    var invalid: FrontendException = null
    var duplicate: FrontendException = null
    val items = captureItems {
      invalid = intercept[FrontendException] {
        emitSynchronousReadFirstSimpleDualPortMemory(
          "p_invalid",
          "invalid_memory",
          ref("clk"),
          ref("read_enable"),
          ref("write_enable"),
          indexedPartSelect("bus", 0, 1),
          ref("write_address"),
          ref("write_data"),
          ref("read_data"),
          packedBits(8),
          5
        )
      }
      memory("p_retry", "retry_memory")
      duplicate = intercept[FrontendException](memory("p_discarded", "discarded_memory"))
    }
    assert(invalid.code == s"$Prefix-READ-ADDRESS-NOT-REF")
    assert(duplicate.code == s"$Prefix-MULTIPLE")
    assert(items.raw.map(_.asInstanceOf[SynchronousReadFirstSimpleDualPortMemory].label) == Vector("p_retry"))
  }

  test("rejects runtime ordinary and generate siblings and generate nesting") {
    var mixedRuntime: FrontendException = null
    captureItems {
      memory()
      mixedRuntime = intercept[FrontendException] {
        emitCombinationalIf(
          "p_comb",
          ref("select"),
          Vector(proceduralAssign("out", ref("a"))),
          Vector(proceduralAssign("out", ref("b")))
        )
      }
    }
    assert(mixedRuntime.code == "MORPH-FRONTEND-RUNTIME-PROCESS-MIXED")

    var mixedOrdinary: FrontendException = null
    captureItems {
      emitContinuousAssign("out", ref("write_data"))
      mixedOrdinary = intercept[FrontendException](memory())
    }
    assert(mixedOrdinary.code == s"$Prefix-MIXED")

    val nested = intercept[FrontendException] {
      captureItems {
        generateIf(HdlBool.literal(true)) { memory() } otherwise {}
      }
    }
    assert(nested.code == s"$Prefix-NESTED")

    var siblingGenerate: FrontendException = null
    captureItems {
      memory()
      siblingGenerate = intercept[FrontendException] {
        generateIf(HdlBool.literal(true)) {} otherwise {}
      }
    }
    assert(siblingGenerate.code == s"$Prefix-MIXED")
  }

  test("defers port directions widths signedness roles and capacity to ParamRTL") {
    val items = captureItems {
      emitSynchronousReadFirstSimpleDualPortMemory(
        "p_deferred",
        "memory",
        ref("same"),
        ref("same"),
        ref("same"),
        ref("same"),
        ref("same"),
        ref("same"),
        ref("same"),
        FrontendNode(PackedBits(Literal(0), Unsigned), origin = SourceOrigin("Deferred.scala", 1)),
        HdlInt.literal(0)
      )
    }
    assert(items.raw.head == SynchronousReadFirstSimpleDualPortMemory(
      "p_deferred",
      "memory",
      Ref("same"),
      Ref("same"),
      Ref("same"),
      Ref("same"),
      Ref("same"),
      Ref("same"),
      Ref("same"),
      PackedBits(Literal(0), Unsigned),
      Literal(0)
    ))
  }
}
