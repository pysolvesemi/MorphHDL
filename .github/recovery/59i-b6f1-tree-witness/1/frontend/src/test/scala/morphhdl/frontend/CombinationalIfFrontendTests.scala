package morphhdl.frontend

import java.util.concurrent.{Callable, Executors, TimeUnit}

import morphhdl.frontend.ParamRtlFrontend._
import morphhdl.paramrtl.ModuleItem.{CombinationalIf, ContinuousAssign, GenerateIf}
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.RtlExpr
import morphhdl.paramrtl.RtlExpr.Ref
import morphhdl.paramrtl.ProceduralAssign
import org.scalatest.funsuite.AnyFunSuite

class CombinationalIfFrontendTests extends AnyFunSuite {
  test("captures one named ref-only runtime if/else process exactly") {
    val items = captureItems {
      emitCombinationalIf(
        label = "runtime_mux",
        condition = ref("select"),
        whenTrue = Vector(proceduralAssign("result", ref("when_true"))),
        whenFalse = Vector(proceduralAssign("result", ref("when_false")))
      )
    }

    assert(items.raw.size == 1)
    assert(
      items.raw.head == CombinationalIf(
        label = "runtime_mux",
        condition = Ref("select"),
        whenTrue = Vector(ProceduralAssign(Ref("result"), Ref("when_true"))),
        whenFalse = Vector(ProceduralAssign(Ref("result"), Ref("when_false")))
      )
    )

    val module = moduleDef(
      name = "RuntimeMux",
      parameters = Vector.empty,
      ports = Vector(
        port("select", Input, packedBits(1)),
        port("when_true", Input, packedBits(8)),
        port("when_false", Input, packedBits(8)),
        port("result", Output, packedBits(8))
      ),
      items = items
    )
    assert(module.items == items.raw)
  }

  test("retains integer Boolean and local provenance from condition and both branches") {
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
      emitCombinationalIf(
        "runtime_provenance",
        guardedRef("select"),
        Vector(proceduralAssign("result", guardedRef("when_true"))),
        Vector(proceduralAssign("result", guardedRef("when_false")))
      )
    }

    val module = moduleDef(
      name = "RuntimeProvenance",
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

  test("reports distinct and undeclared identities retained by process references") {
    val declared = HdlInt.param("WIDTH", default = 8, min = 1, max = 32)
    val used = HdlInt.param("WIDTH", default = 4, min = 1, max = 16)
    val usedCondition = FrontendNode[RtlExpr](
      Ref("select"),
      parameters = used.parameters,
      origin = used.origin
    )
    val items = captureItems {
      emitCombinationalIf(
        "runtime_identity",
        usedCondition,
        Vector(proceduralAssign("result", ref("a"))),
        Vector(proceduralAssign("result", ref("b")))
      )
    }

    val mismatch = intercept[FrontendException] {
      moduleDef(
        "RuntimeIdentityMismatch",
        Vector(integerParameter(declared)),
        Vector.empty,
        items
      )
    }
    assert(mismatch.code == "MORPH-FRONTEND-PARAMETER-TOKEN-MISMATCH")
    assert(mismatch.origin == used.origin)

    val missing = intercept[FrontendException] {
      moduleDef("RuntimeIdentityMissing", Vector.empty, Vector.empty, items)
    }
    assert(missing.code == "MORPH-FRONTEND-PARAMETER-NOT-DECLARED")
    assert(missing.origin == used.origin)
  }

  test("reports a foreign local retained by a process assignment") {
    val foreign = localParam("FOREIGN", HdlInt.literal(1))
    moduleDef(
      "LocalOwner",
      Vector.empty,
      Vector.empty,
      captureItems {},
      localParameters = Vector(integerLocalParameter(foreign))
    )
    val guardedValue = FrontendNode[RtlExpr](
      Ref("a"),
      localParameters = foreign.localParameters,
      origin = foreign.origin
    )
    val items = captureItems {
      emitCombinationalIf(
        "foreign_local",
        ref("select"),
        Vector(proceduralAssign("result", guardedValue)),
        Vector(proceduralAssign("result", ref("b")))
      )
    }

    val error = intercept[FrontendException] {
      moduleDef("ForeignLocal", Vector.empty, Vector.empty, items)
    }
    assert(error.code == "MORPH-FRONTEND-LOCAL-PARAMETER-FOREIGN")
    assert(error.origin == foreign.origin)
  }

  test("preserves empty and duplicate assignments for ParamRTL validation") {
    val duplicate = proceduralAssign("result", ref("a"))
    val items = captureItems {
      emitCombinationalIf(
        "delegated_proof",
        ref("select"),
        Vector(duplicate, duplicate),
        Vector.empty
      )
    }

    val process = items.raw.head.asInstanceOf[CombinationalIf]
    assert(process.whenTrue.map(_.target.name) == Vector("result", "result"))
    assert(process.whenFalse.isEmpty)
  }

  test("rejects non-ref and invalid assignment operands at their source sites") {
    val nullValue = intercept[FrontendException] {
      proceduralAssign("result", null)
    }
    assert(nullValue.code == "MORPH-FRONTEND-COMBINATIONAL-VALUE-NULL")
    assert(nullValue.origin.file.endsWith("CombinationalIfFrontendTests.scala"))

    assert(
      intercept[FrontendException] {
        proceduralAssign(null, ref("a"))
      }.code == "MORPH-FRONTEND-COMBINATIONAL-TARGET-INVALID"
    )
    assert(
      intercept[FrontendException] {
        proceduralAssign("not-portable", ref("a"))
      }.code == "MORPH-FRONTEND-COMBINATIONAL-TARGET-INVALID"
    )
    assert(
      intercept[FrontendException] {
        proceduralAssign("result", ref(null))
      }.code == "MORPH-FRONTEND-COMBINATIONAL-VALUE-INVALID"
    )
    assert(
      intercept[FrontendException] {
        proceduralAssign("result", indexedPartSelect("data", 0, 1))
      }.code == "MORPH-FRONTEND-COMBINATIONAL-VALUE-NOT-REF"
    )
  }

  test("rejects invalid conditions labels and null branch containers") {
    val assignment = proceduralAssign("result", ref("a"))

    def emit(
        label: String = "runtime_guard",
        condition: FrontendNode[RtlExpr] = ref("select"),
        whenTrue: Vector[FrontendNode[ProceduralAssign]] = Vector(assignment),
        whenFalse: Vector[FrontendNode[ProceduralAssign]] = Vector(assignment)
    ): Unit = captureItems {
      emitCombinationalIf(label, condition, whenTrue, whenFalse)
    }

    assert(
      intercept[FrontendException](emit(label = null)).code ==
        "MORPH-FRONTEND-COMBINATIONAL-LABEL-INVALID"
    )
    assert(
      intercept[FrontendException](emit(label = "not-portable")).code ==
        "MORPH-FRONTEND-COMBINATIONAL-LABEL-INVALID"
    )
    assert(
      intercept[FrontendException](emit(condition = null)).code ==
        "MORPH-FRONTEND-COMBINATIONAL-CONDITION-NULL"
    )
    assert(
      intercept[FrontendException](emit(condition = ref(null))).code ==
        "MORPH-FRONTEND-COMBINATIONAL-CONDITION-INVALID"
    )
    assert(
      intercept[FrontendException](emit(condition = indexedPartSelect("data", 0, 1))).code ==
        "MORPH-FRONTEND-COMBINATIONAL-CONDITION-NOT-REF"
    )
    assert(
      intercept[FrontendException](emit(whenTrue = null)).code ==
        "MORPH-FRONTEND-COMBINATIONAL-BRANCH-NULL"
    )
    assert(
      intercept[FrontendException](emit(whenFalse = Vector(null))).code ==
        "MORPH-FRONTEND-COMBINATIONAL-ASSIGNMENT-NULL"
    )
  }

  test("requires parameterized capture and does not specialize in concrete mode") {
    def emit(): Unit =
      emitCombinationalIf(
        "runtime_only",
        ref("select"),
        Vector(proceduralAssign("result", ref("a"))),
        Vector(proceduralAssign("result", ref("b")))
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

  test("permits one process per capture and failed validation does not consume it") {
    var multiple: FrontendException = null
    val items = captureItems {
      val invalid = intercept[FrontendException] {
        emitCombinationalIf(
          "invalid_first",
          indexedPartSelect("data", 0, 1),
          Vector.empty,
          Vector.empty
        )
      }
      assert(invalid.code == "MORPH-FRONTEND-COMBINATIONAL-CONDITION-NOT-REF")

      emitCombinationalIf(
        "kept",
        ref("select"),
        Vector(proceduralAssign("result", ref("a"))),
        Vector(proceduralAssign("result", ref("b")))
      )
      multiple = intercept[FrontendException] {
        emitCombinationalIf(
          "discarded",
          ref("select"),
          Vector(proceduralAssign("result", ref("a"))),
          Vector(proceduralAssign("result", ref("b")))
        )
      }
    }
    assert(multiple.code == "MORPH-FRONTEND-COMBINATIONAL-PROCESS-MULTIPLE")
    assert(items.raw.map(_.asInstanceOf[CombinationalIf].label) == Vector("kept"))
  }

  test("rejects process nesting in generate-if generate-case and generate-for") {
    val enabled = HdlBool.literal(true)
    val selector = HdlInt.literal(0)
    val lanes = HdlInt.param("LANES", default = 1, min = 1, max = 1)

    def process(): Unit =
      emitCombinationalIf(
        "nested_process",
        ref("select"),
        Vector(proceduralAssign("result", ref("a"))),
        Vector(proceduralAssign("result", ref("b")))
      )

    assert(
      intercept[FrontendException] {
        captureItems {
          generateIf(enabled) { process() } otherwise {}
        }
      }.code == "MORPH-FRONTEND-COMBINATIONAL-PROCESS-NESTED"
    )
    assert(
      intercept[FrontendException] {
        captureItems {
          generateCase(selector)
            .choice(0, "g_zero") { process() }
            .default("g_other") {}
        }
      }.code == "MORPH-FRONTEND-COMBINATIONAL-PROCESS-NESTED"
    )
    assert(
      intercept[FrontendException] {
        captureItems {
          for (_ <- 0 until lanes) process()
        }
      }.code == "MORPH-FRONTEND-COMBINATIONAL-PROCESS-NESTED"
    )
  }

  test("rejects sibling process and generate regions in either order") {
    val enabled = HdlBool.literal(true)
    val selector = HdlInt.literal(0)
    val lanes = HdlInt.param("LANES", default = 1, min = 1, max = 1)

    def process(label: String): Unit =
      emitCombinationalIf(
        label,
        ref("select"),
        Vector(proceduralAssign("result", ref("a"))),
        Vector(proceduralAssign("result", ref("b")))
      )

    var afterProcessIf: FrontendException = null
    captureItems {
      process("before_if")
      afterProcessIf = intercept[FrontendException] {
        generateIf(enabled) {} otherwise {}
      }
    }
    assert(afterProcessIf.code == "MORPH-FRONTEND-COMBINATIONAL-PROCESS-MIXED")

    var afterProcessCase: FrontendException = null
    captureItems {
      process("before_case")
      afterProcessCase = intercept[FrontendException] {
        generateCase(selector)
      }
    }
    assert(afterProcessCase.code == "MORPH-FRONTEND-COMBINATIONAL-PROCESS-MIXED")

    var afterProcessFor: FrontendException = null
    captureItems {
      process("before_for")
      afterProcessFor = intercept[FrontendException] {
        for (_ <- 0 until lanes) ()
      }
    }
    assert(afterProcessFor.code == "MORPH-FRONTEND-COMBINATIONAL-PROCESS-MIXED")

    var afterIf: FrontendException = null
    captureItems {
      generateIf(enabled) {} otherwise {}
      afterIf = intercept[FrontendException](process("after_if"))
    }
    assert(afterIf.code == "MORPH-FRONTEND-COMBINATIONAL-PROCESS-MIXED")

    var afterCase: FrontendException = null
    captureItems {
      generateCase(selector).choice(0, "g_zero") {}.default("g_other") {}
      afterCase = intercept[FrontendException](process("after_case"))
    }
    assert(afterCase.code == "MORPH-FRONTEND-COMBINATIONAL-PROCESS-MIXED")

    var afterFor: FrontendException = null
    captureItems {
      for (_ <- 0 until lanes) ()
      afterFor = intercept[FrontendException](process("after_for"))
    }
    assert(afterFor.code == "MORPH-FRONTEND-COMBINATIONAL-PROCESS-MIXED")
  }

  test("rejects sibling continuous and instance items in either order") {
    def process(label: String): Unit =
      emitCombinationalIf(
        label,
        ref("select"),
        Vector(proceduralAssign("result", ref("a"))),
        Vector(proceduralAssign("result", ref("b")))
      )

    var continuousAfterProcess: FrontendException = null
    val keptProcess = captureItems {
      process("before_continuous")
      continuousAfterProcess = intercept[FrontendException] {
        emitContinuousAssign("extra", ref("a"))
      }
    }
    assert(continuousAfterProcess.code == "MORPH-FRONTEND-COMBINATIONAL-PROCESS-MIXED")
    assert(keptProcess.raw.size == 1)

    var processAfterContinuous: FrontendException = null
    val keptContinuous = captureItems {
      emitContinuousAssign("extra", ref("a"))
      processAfterContinuous = intercept[FrontendException](process("after_continuous"))
    }
    assert(processAfterContinuous.code == "MORPH-FRONTEND-COMBINATIONAL-PROCESS-MIXED")
    assert(keptContinuous.raw.head.isInstanceOf[ContinuousAssign])

    var instanceAfterProcess: FrontendException = null
    captureItems {
      process("before_instance")
      instanceAfterProcess = intercept[FrontendException] {
        emitInstance("helper", "Helper")
      }
    }
    assert(instanceAfterProcess.code == "MORPH-FRONTEND-COMBINATIONAL-PROCESS-MIXED")

    var processAfterInstance: FrontendException = null
    captureItems {
      emitInstance("helper", "Helper")
      processAfterInstance = intercept[FrontendException](process("after_instance"))
    }
    assert(processAfterInstance.code == "MORPH-FRONTEND-COMBINATIONAL-PROCESS-MIXED")
  }

  test("rejects generate-index expressions inside escaped and foreign loop scopes") {
    val lanes = HdlInt.param("LANES", default = 1, min = 1, max = 1)
    var escaped: FrontendNode[RtlExpr] = null
    captureItems {
      for (lane <- 0 until lanes) {
        escaped = indexedPartSelect("data", lane * 1, 1)
        assert(
          intercept[FrontendException] {
            proceduralAssign("result", escaped)
          }.code == "MORPH-FRONTEND-COMBINATIONAL-VALUE-NOT-REF"
        )
      }
    }

    assert(
      intercept[FrontendException] {
        proceduralAssign("result", escaped)
      }.code == "MORPH-FRONTEND-GENINDEX-ESCAPED"
    )

    captureItems {
      for (_ <- 0 until lanes) {
        assert(
          intercept[FrontendException] {
            proceduralAssign("result", escaped)
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
                emitCombinationalIf(
                  "foreign_thread",
                  ref("select"),
                  Vector(proceduralAssign("result", ref("a"))),
                  Vector(proceduralAssign("result", ref("b")))
                )
              }
          })
          .get(10, TimeUnit.SECONDS)

        emitCombinationalIf(
          "owner_thread",
          ref("select"),
          Vector(proceduralAssign("result", ref("a"))),
          Vector(proceduralAssign("result", ref("b")))
        )
      }
      assert(foreign.code == "MORPH-FRONTEND-EMIT-OUTSIDE-CAPTURE")
      assert(items.raw.head.asInstanceOf[CombinationalIf].label == "owner_thread")
    } finally {
      executor.shutdownNow()
      assert(executor.awaitTermination(10, TimeUnit.SECONDS))
    }
  }

  test("leaves existing frontend source behavior and symbolic equality guards intact") {
    val continuous = captureItems {
      emitContinuousAssign("result", ref("source"))
    }
    assert(continuous.raw.head == ContinuousAssign(Ref("result"), Ref("source")))

    val generated = captureItems {
      generateIf(HdlBool.literal(true)) {} otherwise {}
    }
    assert(generated.raw.head.isInstanceOf[GenerateIf])

    val symbolic = HdlInt.param("VALUE", default = 1, min = 0, max = 2)
    assert(
      intercept[FrontendException](symbolic == HdlInt.literal(1)).code ==
        "MORPH-FRONTEND-SYMBOLIC-COMPARISON-UNSUPPORTED"
    )
  }

  test("rejects compile-time misuse of guarded process values") {
    assertTypeError("""
      import morphhdl.frontend._
      import morphhdl.frontend.ParamRtlFrontend._
      proceduralAssign("result", HdlInt.literal(1))
    """)
    assertTypeError("""
      import morphhdl.frontend._
      import morphhdl.frontend.ParamRtlFrontend._
      emitCombinationalIf(
        "bad_condition",
        HdlBool.literal(true),
        Vector(proceduralAssign("result", ref("a"))),
        Vector(proceduralAssign("result", ref("b")))
      )
    """)
    assertTypeError("""
      import morphhdl.frontend._
      import morphhdl.frontend.ParamRtlFrontend._
      import morphhdl.paramrtl.{ProceduralAssign, RtlExpr}
      emitCombinationalIf(
        "raw_assignment",
        ref("select"),
        Vector(ProceduralAssign(RtlExpr.Ref("result"), RtlExpr.Ref("a"))),
        Vector.empty
      )
    """)
  }
}
