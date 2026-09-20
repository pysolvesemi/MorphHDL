package morphhdl.frontend

import java.util.concurrent.{Callable, Executors, TimeUnit}

import morphhdl.frontend.ParamRtlFrontend._
import morphhdl.paramrtl.IntExpr.{Literal, ParameterRef}
import morphhdl.paramrtl.ModuleItem.{
  AsynchronousEnabledRegister,
  AsynchronousRegister,
  CombinationalIf,
  ContinuousAssign,
  SynchronousEnabledRegister,
  SynchronousReadFirstSinglePortMemory,
  SynchronousRegister
}
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.Signedness.Unsigned
import morphhdl.paramrtl.{PackedBits, RtlExpr}
import morphhdl.paramrtl.RtlExpr.Ref
import org.scalatest.funsuite.AnyFunSuite

class SynchronousReadFirstSinglePortMemoryFrontendTests extends AnyFunSuite {
  private def memory(
      label: String = "p_memory",
      memoryName: String = "memory"
  ): Unit =
    emitSynchronousReadFirstSinglePortMemory(
      label,
      memoryName,
      ref("clk"),
      ref("read_enable"),
      ref("write_enable"),
      ref("address"),
      ref("write_data"),
      ref("read_data"),
      packedBits(8),
      5
    )

  private def comb(): Unit =
    emitCombinationalIf(
      "p_comb",
      ref("select"),
      Vector(proceduralAssign("data_out", ref("data_in"))),
      Vector(proceduralAssign("data_out", ref("other")))
    )

  private def sync(): Unit =
    emitSynchronousRegister(
      "p_sync",
      ref("clk"),
      ref("reset"),
      proceduralAssign("data_out", ref("data_in"))
    )

  private def async(): Unit =
    emitAsynchronousRegister(
      "p_async",
      ref("clk"),
      ref("reset"),
      proceduralAssign("data_out", ref("data_in"))
    )

  private def syncEnabled(): Unit =
    emitSynchronousEnabledRegister(
      "p_sync_enabled",
      ref("clk"),
      ref("reset"),
      ref("enable"),
      proceduralAssign("data_out", ref("data_in"))
    )

  private def asyncEnabled(): Unit =
    emitAsynchronousEnabledRegister(
      "p_async_enabled",
      ref("clk"),
      ref("reset"),
      ref("enable"),
      proceduralAssign("data_out", ref("data_in"))
    )

  test("captures one exact synchronous read-first single-port memory intent") {
    val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 32)
    val depth = HdlInt.param("DEPTH", default = 5, min = 1, max = 17)
    val items = captureItems {
      emitSynchronousReadFirstSinglePortMemory(
        "p_memory",
        "memory",
        ref("clk"),
        ref("read_enable"),
        ref("write_enable"),
        ref("address"),
        ref("write_data"),
        ref("read_data"),
        packedBits(width),
        depth
      )
    }

    assert(items.raw == Vector(
      SynchronousReadFirstSinglePortMemory(
        label = "p_memory",
        memoryName = "memory",
        clock = Ref("clk"),
        readEnable = Ref("read_enable"),
        writeEnable = Ref("write_enable"),
        address = Ref("address"),
        writeData = Ref("write_data"),
        readData = Ref("read_data"),
        elementType = PackedBits(ParameterRef("WIDTH"), Unsigned),
        depth = ParameterRef("DEPTH")
      )
    ))

    val module = moduleDef(
      name = "ParameterizedMemory",
      parameters = Vector(integerParameter(width), integerParameter(depth)),
      ports = Vector(
        port("clk", Input, packedBits(1)),
        port("read_enable", Input, packedBits(1)),
        port("write_enable", Input, packedBits(1)),
        port("address", Input, packedBits(5)),
        port("write_data", Input, packedBits(width)),
        port("read_data", Output, packedBits(width))
      ),
      items = items
    )
    assert(module.items == items.raw)
  }

  test("unions and discharges ref element-type depth and mixed local provenance") {
    val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 32)
    val depth = HdlInt.param("DEPTH", default = 5, min = 1, max = 17)
    val feature = HdlBool.param("FEATURE", default = true)
    val localWidth = localParam("LOCAL_WIDTH", width)
    val localDepth = localParam("LOCAL_DEPTH", depth)
    val localFeature = localParam("LOCAL_FEATURE", feature)

    val guardedReadEnable = FrontendNode[RtlExpr](
      Ref("read_enable"),
      parameters = width.parameters,
      booleanParameters = feature.parameters,
      localParameters = localWidth.localParameters,
      booleanLocalParameters = localFeature.booleanLocalParameters,
      origin = localFeature.origin
    )
    val items = captureItems {
      emitSynchronousReadFirstSinglePortMemory(
        "p_provenance",
        "memory",
        ref("clk"),
        guardedReadEnable,
        ref("write_enable"),
        ref("address"),
        ref("write_data"),
        ref("read_data"),
        packedBits(localWidth),
        localDepth
      )
    }

    val module = moduleDef(
      name = "MemoryProvenance",
      parameters = Vector(integerParameter(width), integerParameter(depth)),
      ports = Vector.empty,
      items = items,
      localParameters = Vector(
        integerLocalParameter(localWidth),
        integerLocalParameter(localDepth)
      ),
      booleanParameters = Vector(booleanParameter(feature)),
      booleanLocalParameters = Vector(booleanLocalParameter(localFeature))
    )
    assert(module.parameters.map(_.name) == Vector("WIDTH", "DEPTH"))
    assert(module.localParameters.map(_.name).toSet == Set("LOCAL_WIDTH", "LOCAL_DEPTH"))
    assert(module.booleanParameters.map(_.name) == Vector("FEATURE"))
    assert(module.booleanLocalParameters.map(_.name) == Vector("LOCAL_FEATURE"))
  }

  test("reports mismatched and foreign retained memory provenance at its source") {
    val declaredDepth = HdlInt.param("DEPTH", default = 5, min = 1, max = 17)
    val usedDepth = HdlInt.param("DEPTH", default = 7, min = 1, max = 19)
    val publicItems = captureItems {
      emitSynchronousReadFirstSinglePortMemory(
        "p_public_identity",
        "memory",
        ref("clk"),
        ref("read_enable"),
        ref("write_enable"),
        ref("address"),
        ref("write_data"),
        ref("read_data"),
        packedBits(8),
        usedDepth
      )
    }
    val mismatch = intercept[FrontendException] {
      moduleDef(
        "MemoryIdentityMismatch",
        Vector(integerParameter(declaredDepth)),
        Vector.empty,
        publicItems
      )
    }
    assert(mismatch.code == "MORPH-FRONTEND-PARAMETER-TOKEN-MISMATCH")
    assert(mismatch.origin == usedDepth.origin)

    val claimedDepth = localParam("CLAIMED_DEPTH", HdlInt.literal(5))
    moduleDef(
      "ClaimedMemoryOwner",
      Vector.empty,
      Vector.empty,
      captureItems {},
      localParameters = Vector(integerLocalParameter(claimedDepth))
    )
    val claimedItems = captureItems {
      emitSynchronousReadFirstSinglePortMemory(
        "p_claimed",
        "memory",
        ref("clk"),
        ref("read_enable"),
        ref("write_enable"),
        ref("address"),
        ref("write_data"),
        ref("read_data"),
        packedBits(8),
        claimedDepth
      )
    }
    val foreign = intercept[FrontendException] {
      moduleDef("ClaimedMemoryReuse", Vector.empty, Vector.empty, claimedItems)
    }
    assert(foreign.code == "MORPH-FRONTEND-LOCAL-PARAMETER-FOREIGN")
    assert(foreign.origin == claimedDepth.origin)
    assert(foreign.detail.contains("ClaimedMemoryOwner"))
  }

  test("rejects every null non-ref and invalid identifier role with stable diagnostics") {
    val element = packedBits(8)
    val memoryDepth = HdlInt.literal(5)
    def emit(
        label: String = "p_memory",
        memoryName: String = "memory",
        clock: FrontendNode[RtlExpr] = ref("clk"),
        readEnable: FrontendNode[RtlExpr] = ref("read_enable"),
        writeEnable: FrontendNode[RtlExpr] = ref("write_enable"),
        address: FrontendNode[RtlExpr] = ref("address"),
        writeData: FrontendNode[RtlExpr] = ref("write_data"),
        readData: FrontendNode[RtlExpr] = ref("read_data"),
        elementType: FrontendNode[PackedBits] = element,
        depth: HdlInt = memoryDepth
    ): Unit = captureItems {
      emitSynchronousReadFirstSinglePortMemory(
        label,
        memoryName,
        clock,
        readEnable,
        writeEnable,
        address,
        writeData,
        readData,
        elementType,
        depth
      )
    }

    val nonRef = indexedPartSelect("bus", 0, 1)
    val cases = Vector(
      intercept[FrontendException](emit(label = null)) ->
        "MORPH-FRONTEND-SINGLE-PORT-MEMORY-LABEL-INVALID",
      intercept[FrontendException](emit(label = "not-portable")) ->
        "MORPH-FRONTEND-SINGLE-PORT-MEMORY-LABEL-INVALID",
      intercept[FrontendException](emit(memoryName = null)) ->
        "MORPH-FRONTEND-SINGLE-PORT-MEMORY-NAME-INVALID",
      intercept[FrontendException](emit(memoryName = "not-portable")) ->
        "MORPH-FRONTEND-SINGLE-PORT-MEMORY-NAME-INVALID",
      intercept[FrontendException](emit(clock = null)) ->
        "MORPH-FRONTEND-SINGLE-PORT-MEMORY-CLOCK-NULL",
      intercept[FrontendException](emit(clock = nonRef)) ->
        "MORPH-FRONTEND-SINGLE-PORT-MEMORY-CLOCK-NOT-REF",
      intercept[FrontendException](emit(clock = ref("not-portable"))) ->
        "MORPH-FRONTEND-SINGLE-PORT-MEMORY-CLOCK-INVALID",
      intercept[FrontendException](emit(readEnable = null)) ->
        "MORPH-FRONTEND-SINGLE-PORT-MEMORY-READ-ENABLE-NULL",
      intercept[FrontendException](emit(readEnable = nonRef)) ->
        "MORPH-FRONTEND-SINGLE-PORT-MEMORY-READ-ENABLE-NOT-REF",
      intercept[FrontendException](emit(readEnable = ref("not-portable"))) ->
        "MORPH-FRONTEND-SINGLE-PORT-MEMORY-READ-ENABLE-INVALID",
      intercept[FrontendException](emit(writeEnable = null)) ->
        "MORPH-FRONTEND-SINGLE-PORT-MEMORY-WRITE-ENABLE-NULL",
      intercept[FrontendException](emit(writeEnable = nonRef)) ->
        "MORPH-FRONTEND-SINGLE-PORT-MEMORY-WRITE-ENABLE-NOT-REF",
      intercept[FrontendException](emit(writeEnable = ref("not-portable"))) ->
        "MORPH-FRONTEND-SINGLE-PORT-MEMORY-WRITE-ENABLE-INVALID",
      intercept[FrontendException](emit(address = null)) ->
        "MORPH-FRONTEND-SINGLE-PORT-MEMORY-ADDRESS-NULL",
      intercept[FrontendException](emit(address = nonRef)) ->
        "MORPH-FRONTEND-SINGLE-PORT-MEMORY-ADDRESS-NOT-REF",
      intercept[FrontendException](emit(address = ref("not-portable"))) ->
        "MORPH-FRONTEND-SINGLE-PORT-MEMORY-ADDRESS-INVALID",
      intercept[FrontendException](emit(writeData = null)) ->
        "MORPH-FRONTEND-SINGLE-PORT-MEMORY-WRITE-DATA-NULL",
      intercept[FrontendException](emit(writeData = nonRef)) ->
        "MORPH-FRONTEND-SINGLE-PORT-MEMORY-WRITE-DATA-NOT-REF",
      intercept[FrontendException](emit(writeData = ref("not-portable"))) ->
        "MORPH-FRONTEND-SINGLE-PORT-MEMORY-WRITE-DATA-INVALID",
      intercept[FrontendException](emit(readData = null)) ->
        "MORPH-FRONTEND-SINGLE-PORT-MEMORY-READ-DATA-NULL",
      intercept[FrontendException](emit(readData = nonRef)) ->
        "MORPH-FRONTEND-SINGLE-PORT-MEMORY-READ-DATA-NOT-REF",
      intercept[FrontendException](emit(readData = ref("not-portable"))) ->
        "MORPH-FRONTEND-SINGLE-PORT-MEMORY-READ-DATA-INVALID",
      intercept[FrontendException](emit(elementType = null)) ->
        "MORPH-FRONTEND-SINGLE-PORT-MEMORY-ELEMENT-TYPE-NULL",
      intercept[FrontendException](emit(depth = null)) ->
        "MORPH-FRONTEND-SINGLE-PORT-MEMORY-DEPTH-NULL"
    )
    cases.foreach { case (error, code) =>
      assert(error.code == code)
      assert(error.sourceLocation.nonEmpty)
      assert(error.suggestedReplacement.nonEmpty)
    }

    val invalidOrigin = SourceOrigin("ReadEnable.scala", 17)
    val invalidReadEnable = FrontendNode[RtlExpr](
      Ref("not-portable"),
      origin = invalidOrigin
    )
    val invalidReadEnableError = intercept[FrontendException] {
      emit(readEnable = invalidReadEnable)
    }
    assert(
      invalidReadEnableError.code ==
        "MORPH-FRONTEND-SINGLE-PORT-MEMORY-READ-ENABLE-INVALID"
    )
    assert(invalidReadEnableError.origin == invalidOrigin)
  }

  test("provides an explicit suggestion for every new frontend diagnostic") {
    val suggestions = Vector(
      "MORPH-FRONTEND-SINGLE-PORT-MEMORY-NESTED" ->
        "Emit the synchronous read-first single-port memory as a top-level module item outside all generate regions.",
      "MORPH-FRONTEND-SINGLE-PORT-MEMORY-MULTIPLE" ->
        "Emit one synchronous read-first single-port memory per module-item capture.",
      "MORPH-FRONTEND-SINGLE-PORT-MEMORY-MIXED" ->
        "Use a separate module definition instead of mixing the memory with another module item or generate region.",
      "MORPH-FRONTEND-SINGLE-PORT-MEMORY-LABEL-INVALID" ->
        "Use an identifier matching `[A-Za-z_][A-Za-z0-9_]*`.",
      "MORPH-FRONTEND-SINGLE-PORT-MEMORY-NAME-INVALID" ->
        "Use an identifier matching `[A-Za-z_][A-Za-z0-9_]*`.",
      "MORPH-FRONTEND-SINGLE-PORT-MEMORY-CLOCK-INVALID" ->
        "Use an identifier matching `[A-Za-z_][A-Za-z0-9_]*`.",
      "MORPH-FRONTEND-SINGLE-PORT-MEMORY-READ-ENABLE-INVALID" ->
        "Use an identifier matching `[A-Za-z_][A-Za-z0-9_]*`.",
      "MORPH-FRONTEND-SINGLE-PORT-MEMORY-WRITE-ENABLE-INVALID" ->
        "Use an identifier matching `[A-Za-z_][A-Za-z0-9_]*`.",
      "MORPH-FRONTEND-SINGLE-PORT-MEMORY-ADDRESS-INVALID" ->
        "Use an identifier matching `[A-Za-z_][A-Za-z0-9_]*`.",
      "MORPH-FRONTEND-SINGLE-PORT-MEMORY-WRITE-DATA-INVALID" ->
        "Use an identifier matching `[A-Za-z_][A-Za-z0-9_]*`.",
      "MORPH-FRONTEND-SINGLE-PORT-MEMORY-READ-DATA-INVALID" ->
        "Use an identifier matching `[A-Za-z_][A-Za-z0-9_]*`.",
      "MORPH-FRONTEND-SINGLE-PORT-MEMORY-CLOCK-NULL" ->
        "Pass a non-null ref(name) clock to emitSynchronousReadFirstSinglePortMemory.",
      "MORPH-FRONTEND-SINGLE-PORT-MEMORY-CLOCK-NOT-REF" ->
        "Pass a non-null ref(name) clock to emitSynchronousReadFirstSinglePortMemory.",
      "MORPH-FRONTEND-SINGLE-PORT-MEMORY-READ-ENABLE-NULL" ->
        "Pass a non-null ref(name) read enable to emitSynchronousReadFirstSinglePortMemory.",
      "MORPH-FRONTEND-SINGLE-PORT-MEMORY-READ-ENABLE-NOT-REF" ->
        "Pass a non-null ref(name) read enable to emitSynchronousReadFirstSinglePortMemory.",
      "MORPH-FRONTEND-SINGLE-PORT-MEMORY-WRITE-ENABLE-NULL" ->
        "Pass a non-null ref(name) write enable to emitSynchronousReadFirstSinglePortMemory.",
      "MORPH-FRONTEND-SINGLE-PORT-MEMORY-WRITE-ENABLE-NOT-REF" ->
        "Pass a non-null ref(name) write enable to emitSynchronousReadFirstSinglePortMemory.",
      "MORPH-FRONTEND-SINGLE-PORT-MEMORY-ADDRESS-NULL" ->
        "Pass a non-null ref(name) address to emitSynchronousReadFirstSinglePortMemory.",
      "MORPH-FRONTEND-SINGLE-PORT-MEMORY-ADDRESS-NOT-REF" ->
        "Pass a non-null ref(name) address to emitSynchronousReadFirstSinglePortMemory.",
      "MORPH-FRONTEND-SINGLE-PORT-MEMORY-WRITE-DATA-NULL" ->
        "Pass a non-null ref(name) write data value to emitSynchronousReadFirstSinglePortMemory.",
      "MORPH-FRONTEND-SINGLE-PORT-MEMORY-WRITE-DATA-NOT-REF" ->
        "Pass a non-null ref(name) write data value to emitSynchronousReadFirstSinglePortMemory.",
      "MORPH-FRONTEND-SINGLE-PORT-MEMORY-READ-DATA-NULL" ->
        "Pass a non-null ref(name) read data target to emitSynchronousReadFirstSinglePortMemory.",
      "MORPH-FRONTEND-SINGLE-PORT-MEMORY-READ-DATA-NOT-REF" ->
        "Pass a non-null ref(name) read data target to emitSynchronousReadFirstSinglePortMemory.",
      "MORPH-FRONTEND-SINGLE-PORT-MEMORY-ELEMENT-TYPE-NULL" ->
        "Pass a non-null packedBits(width) element type to emitSynchronousReadFirstSinglePortMemory.",
      "MORPH-FRONTEND-SINGLE-PORT-MEMORY-DEPTH-NULL" ->
        "Pass a non-null loop-invariant HdlInt depth to emitSynchronousReadFirstSinglePortMemory."
    )
    suggestions.foreach { case (code, expected) =>
      val error = intercept[FrontendException] {
        FrontendException.failAt(code, "test", SourceOrigin("Suggestions.scala", 1))
      }
      assert(error.suggestedReplacement == expected, code)
    }
  }

  test("defers declarations directions widths signedness roles and capacity to ParamRTL") {
    val items = captureItems {
      emitSynchronousReadFirstSinglePortMemory(
        "p_deferred",
        "memory",
        ref("same_signal"),
        ref("same_signal"),
        ref("same_signal"),
        ref("same_signal"),
        ref("same_signal"),
        ref("same_signal"),
        FrontendNode(
          PackedBits(Literal(0), Unsigned),
          origin = SourceOrigin("DeferredMemoryType.scala", 4)
        ),
        HdlInt.literal(0)
      )
    }
    assert(items.raw.head == SynchronousReadFirstSinglePortMemory(
      "p_deferred",
      "memory",
      Ref("same_signal"),
      Ref("same_signal"),
      Ref("same_signal"),
      Ref("same_signal"),
      Ref("same_signal"),
      Ref("same_signal"),
      PackedBits(Literal(0), Unsigned),
      Literal(0)
    ))
    assert(moduleDef("DeferredMemory", Vector.empty, Vector.empty, items).items == items.raw)
  }

  test("requires parameterized capture and rolls failed calls back atomically") {
    assert(
      intercept[FrontendException](memory()).code ==
        "MORPH-FRONTEND-EMIT-OUTSIDE-CAPTURE"
    )
    assert(
      intercept[FrontendException] {
        FrontendSession.concrete(memory())
      }.code == "MORPH-FRONTEND-EMIT-OUTSIDE-CAPTURE"
    )

    var invalid: FrontendException = null
    var duplicate: FrontendException = null
    val items = captureItems {
      invalid = intercept[FrontendException] {
        emitSynchronousReadFirstSinglePortMemory(
          "p_invalid",
          "memory",
          ref("clk"),
          indexedPartSelect("controls", 0, 1),
          ref("write_enable"),
          ref("address"),
          ref("write_data"),
          ref("read_data"),
          packedBits(8),
          5
        )
      }
      memory("p_retry", "retry_memory")
      duplicate = intercept[FrontendException](memory("p_discarded", "discarded"))
    }
    assert(
      invalid.code == "MORPH-FRONTEND-SINGLE-PORT-MEMORY-READ-ENABLE-NOT-REF"
    )
    assert(duplicate.code == "MORPH-FRONTEND-SINGLE-PORT-MEMORY-MULTIPLE")
    assert(
      items.raw.map(_.asInstanceOf[SynchronousReadFirstSinglePortMemory].label) ==
        Vector("p_retry")
    )
  }

  test("mutually excludes all existing runtime processes and ordinary items in both orders") {
    def mixed(first: () => Unit, second: () => Unit): FrontendException = {
      var error: FrontendException = null
      captureItems {
        first()
        error = intercept[FrontendException](second())
      }
      error
    }

    Vector(
      () => comb(),
      () => sync(),
      () => async(),
      () => syncEnabled(),
      () => asyncEnabled()
    ).foreach { process =>
      assert(mixed(() => memory(), process).code == "MORPH-FRONTEND-RUNTIME-PROCESS-MIXED")
      assert(mixed(process, () => memory()).code == "MORPH-FRONTEND-RUNTIME-PROCESS-MIXED")
    }

    var continuousAfter: FrontendException = null
    captureItems {
      memory()
      continuousAfter = intercept[FrontendException] {
        emitContinuousAssign("extra", ref("write_data"))
      }
    }
    assert(continuousAfter.code == "MORPH-FRONTEND-SINGLE-PORT-MEMORY-MIXED")

    var afterContinuous: FrontendException = null
    captureItems {
      emitContinuousAssign("extra", ref("write_data"))
      afterContinuous = intercept[FrontendException](memory())
    }
    assert(afterContinuous.code == "MORPH-FRONTEND-SINGLE-PORT-MEMORY-MIXED")

    var instanceAfter: FrontendException = null
    captureItems {
      memory()
      instanceAfter = intercept[FrontendException](emitInstance("helper", "Helper"))
    }
    assert(instanceAfter.code == "MORPH-FRONTEND-SINGLE-PORT-MEMORY-MIXED")

    var afterInstance: FrontendException = null
    captureItems {
      emitInstance("helper", "Helper")
      afterInstance = intercept[FrontendException](memory())
    }
    assert(afterInstance.code == "MORPH-FRONTEND-SINGLE-PORT-MEMORY-MIXED")
  }

  test("rejects generate nesting and generate siblings in both orders") {
    val condition = HdlBool.literal(true)
    val selector = HdlInt.literal(0)
    val count = HdlInt.param("COUNT", default = 1, min = 1, max = 1)

    Vector(
      intercept[FrontendException] {
        captureItems { generateIf(condition) { memory() } otherwise {} }
      },
      intercept[FrontendException] {
        captureItems {
          generateCase(selector).choice(0, "g_zero") { memory() }.default("g_other") {}
        }
      },
      intercept[FrontendException] {
        captureItems { for (_ <- 0 until count) memory() }
      }
    ).foreach(error => assert(error.code == "MORPH-FRONTEND-SINGLE-PORT-MEMORY-NESTED"))

    def memoryThenGenerate(generate: => Unit): FrontendException = {
      var error: FrontendException = null
      captureItems {
        memory()
        error = intercept[FrontendException](generate)
      }
      error
    }
    def generateThenMemory(generate: => Unit): FrontendException = {
      var error: FrontendException = null
      captureItems {
        generate
        error = intercept[FrontendException](memory())
      }
      error
    }

    Vector(
      memoryThenGenerate(generateIf(condition) {} otherwise {}),
      generateThenMemory(generateIf(condition) {} otherwise {}),
      memoryThenGenerate(
        generateCase(selector).choice(0, "g_zero") {}.default("g_other") {}
      ),
      generateThenMemory(
        generateCase(selector).choice(0, "g_zero") {}.default("g_other") {}
      ),
      memoryThenGenerate { for (_ <- 0 until count) () },
      generateThenMemory { for (_ <- 0 until count) () }
    ).foreach(error => assert(error.code == "MORPH-FRONTEND-SINGLE-PORT-MEMORY-MIXED"))
  }

  test("rejects escaped provenance and keeps capture ownership thread-local") {
    val count = HdlInt.param("COUNT", default = 1, min = 1, max = 1)
    var escapedAddress: FrontendNode[RtlExpr] = null
    captureItems {
      for (index <- 0 until count) {
        escapedAddress = FrontendNode(
          Ref("address"),
          scopes = Set(index.token),
          origin = index.origin
        )
      }
    }

    var escaped: FrontendException = null
    val retryItems = captureItems {
      escaped = intercept[FrontendException] {
        emitSynchronousReadFirstSinglePortMemory(
          "p_escaped",
          "memory",
          ref("clk"),
          ref("read_enable"),
          ref("write_enable"),
          escapedAddress,
          ref("write_data"),
          ref("read_data"),
          packedBits(8),
          5
        )
      }
      memory("p_retry", "retry_memory")
    }
    assert(escaped.code == "MORPH-FRONTEND-GENINDEX-ESCAPED")
    assert(escaped.origin == escapedAddress.origin)
    assert(retryItems.raw.size == 1)

    val executor = Executors.newSingleThreadExecutor()
    var foreign: FrontendException = null
    try {
      val ownerItems = captureItems {
        foreign = executor
          .submit(new Callable[FrontendException] {
            override def call(): FrontendException =
              intercept[FrontendException] { memory("p_foreign", "foreign_memory") }
          })
          .get(10, TimeUnit.SECONDS)
        memory("p_owner", "owner_memory")
      }
      assert(foreign.code == "MORPH-FRONTEND-EMIT-OUTSIDE-CAPTURE")
      assert(ownerItems.raw.size == 1)
    } finally {
      executor.shutdownNow()
      assert(executor.awaitTermination(10, TimeUnit.SECONDS))
    }
  }

  test("preserves prior frontend APIs and compile-time opacity") {
    assert(captureItems { comb() }.raw.head.isInstanceOf[CombinationalIf])
    assert(captureItems { sync() }.raw.head.isInstanceOf[SynchronousRegister])
    assert(captureItems { async() }.raw.head.isInstanceOf[AsynchronousRegister])
    assert(captureItems { syncEnabled() }.raw.head.isInstanceOf[SynchronousEnabledRegister])
    assert(captureItems { asyncEnabled() }.raw.head.isInstanceOf[AsynchronousEnabledRegister])
    assert(
      captureItems { emitContinuousAssign("result", ref("source")) }.raw.head ==
        ContinuousAssign(Ref("result"), Ref("source"))
    )

    assertTypeError("""
      import morphhdl.frontend._
      import morphhdl.frontend.ParamRtlFrontend._
      emitSynchronousReadFirstSinglePortMemory(
        "p_bad_address",
        "memory",
        ref("clk"),
        ref("read_enable"),
        ref("write_enable"),
        HdlInt.literal(0),
        ref("write_data"),
        ref("read_data"),
        packedBits(8),
        5
      )
    """)
    assertTypeError("""
      import morphhdl.frontend._
      import morphhdl.frontend.ParamRtlFrontend._
      import morphhdl.paramrtl.{IntExpr, PackedBits, Signedness}
      emitSynchronousReadFirstSinglePortMemory(
        "p_raw_type",
        "memory",
        ref("clk"),
        ref("read_enable"),
        ref("write_enable"),
        ref("address"),
        ref("write_data"),
        ref("read_data"),
        PackedBits(IntExpr.Literal(8), Signedness.Unsigned),
        5
      )
    """)
  }
}
