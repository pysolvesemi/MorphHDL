package morphhdl.frontend

import java.util.concurrent.{Callable, Executors, TimeUnit}

import scala.collection.mutable.ArrayBuffer

import morphhdl.frontend.ParamRtlFrontend._
import morphhdl.paramrtl.BoolExpr.{And, GreaterThanOrEqual, Not, ParameterRef}
import morphhdl.paramrtl.IntExpr.{
  LocalParameterRef,
  ParameterRef => IntParameterRef
}
import morphhdl.paramrtl.ModuleItem.{GenerateIf, ModuleInstance}
import org.scalatest.funsuite.AnyFunSuite

class GenerateIfFrontendTests extends AnyFunSuite {
  test("captures both guarded branches with explicit stable labels") {
    val enabled = HdlBool.param("ENABLED", default = true)
    val bypass = HdlBool.param("BYPASS", default = false)

    val items = captureItems {
      generateIf(enabled && !bypass, "g_enabled", "g_disabled") {
        emitInstance(name = "enabled_inst", moduleName = "EnabledPath")
      } otherwise {
        emitInstance(name = "disabled_inst", moduleName = "DisabledPath")
      }
    }

    assert(items.raw.size == 1)
    val conditional = items.raw.head.asInstanceOf[GenerateIf]
    assert(conditional.condition == And(ParameterRef("ENABLED"), Not(ParameterRef("BYPASS"))))
    assert(conditional.whenTrue.label == "g_enabled")
    assert(conditional.whenFalse.label == "g_disabled")
    assert(conditional.whenTrue.body.map(_.asInstanceOf[ModuleInstance].name) == Vector("enabled_inst"))
    assert(conditional.whenFalse.body.map(_.asInstanceOf[ModuleInstance].name) == Vector("disabled_inst"))

    val module = moduleDef(
      name = "ConditionalTop",
      parameters = Vector.empty,
      ports = Vector.empty,
      items = items,
      booleanParameters = Vector(booleanParameter(enabled), booleanParameter(bypass))
    )
    assert(module.booleanParameters.map(_.name) == Vector("ENABLED", "BYPASS"))
  }

  test("captures integer comparison conditions and discharges all condition provenance") {
    val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 64)
    val threshold = HdlInt.param("THRESHOLD", default = 5, min = 0, max = 64)
    val enabled = HdlBool.param("ENABLED", default = true)
    val items = captureItems {
      generateIf(width >= threshold && enabled, "g_high", "g_low") {
        emitInstance(name = "high_inst", moduleName = "High")
      } otherwise {
        emitInstance(name = "low_inst", moduleName = "Low")
      }
    }

    assert(
      items.raw.head.asInstanceOf[GenerateIf].condition == And(
        GreaterThanOrEqual(IntParameterRef("WIDTH"), IntParameterRef("THRESHOLD")),
        ParameterRef("ENABLED")
      )
    )
    val module = moduleDef(
      name = "ComparedTop",
      parameters = Vector(integerParameter(width), integerParameter(threshold)),
      ports = Vector.empty,
      items = items,
      booleanParameters = Vector(booleanParameter(enabled))
    )
    assert(module.parameters.map(_.name) == Vector("WIDTH", "THRESHOLD"))
  }

  test("retains integer comparison identities for mismatch and undeclared diagnostics") {
    val declared = HdlInt.param("SELECT", default = 5, min = 0, max = 10)
    val used = HdlInt.param("SELECT", default = 7, min = 0, max = 10)
    val items = captureItems {
      generateIf(used >= 5) {} otherwise {}
    }

    val mismatch = intercept[FrontendException] {
      moduleDef(
        name = "ComparedMismatch",
        parameters = Vector(integerParameter(declared)),
        ports = Vector.empty,
        items = items
      )
    }
    assert(mismatch.code == "MORPH-FRONTEND-PARAMETER-TOKEN-MISMATCH")
    assert(mismatch.origin == used.origin)

    val missing = intercept[FrontendException] {
      moduleDef(
        name = "ComparedMissing",
        parameters = Vector.empty,
        ports = Vector.empty,
        items = items
      )
    }
    assert(missing.code == "MORPH-FRONTEND-PARAMETER-NOT-DECLARED")
    assert(missing.origin == used.origin)
  }

  test("retains local comparison identities for declared, undeclared and foreign diagnostics") {
    val declaredLocal = localParam("SAME_LOCAL", HdlInt.literal(5))
    val usedLocal = localParam("SAME_LOCAL", HdlInt.literal(7))
    val mismatchItems = captureItems {
      generateIf(usedLocal > 4) {} otherwise {}
    }
    val mismatch = intercept[FrontendException] {
      moduleDef(
        name = "ComparedLocalMismatch",
        parameters = Vector.empty,
        ports = Vector.empty,
        items = mismatchItems,
        localParameters = Vector(integerLocalParameter(declaredLocal))
      )
    }
    assert(mismatch.code == "MORPH-FRONTEND-LOCAL-PARAMETER-TOKEN-MISMATCH")
    assert(mismatch.origin == usedLocal.origin)

    val local = localParam("LOCAL_THRESHOLD", HdlInt.literal(5))
    val localItems = captureItems {
      generateIf(local >= 3) {} otherwise {}
    }
    val localConditional = localItems.raw.head.asInstanceOf[GenerateIf]
    assert(
      localConditional.condition == GreaterThanOrEqual(
        LocalParameterRef("LOCAL_THRESHOLD"),
        morphhdl.paramrtl.IntExpr.Literal(3)
      )
    )
    moduleDef(
      name = "ComparedLocal",
      parameters = Vector.empty,
      ports = Vector.empty,
      items = localItems,
      localParameters = Vector(integerLocalParameter(local))
    )

    val missingLocal = localParam("MISSING_LOCAL", HdlInt.literal(2))
    val missingItems = captureItems {
      generateIf(missingLocal.hdlNe(0)) {} otherwise {}
    }
    val missing = intercept[FrontendException] {
      moduleDef(
        name = "ComparedMissingLocal",
        parameters = Vector.empty,
        ports = Vector.empty,
        items = missingItems
      )
    }
    assert(missing.code == "MORPH-FRONTEND-LOCAL-PARAMETER-NOT-DECLARED")
    assert(missing.origin == missingLocal.origin)

    val foreignItems = captureItems {
      generateIf(local >= 3) {} otherwise {}
    }
    val foreign = intercept[FrontendException] {
      moduleDef(
        name = "ComparedForeignLocal",
        parameters = Vector.empty,
        ports = Vector.empty,
        items = foreignItems
      )
    }
    assert(foreign.code == "MORPH-FRONTEND-LOCAL-PARAMETER-FOREIGN")
    assert(foreign.origin == local.origin)
  }

  test("rejects generate-index comparison operands and escaped comparison values") {
    val lanes = HdlInt.param("LANES", default = 1, min = 1, max = 4)
    var inside: FrontendException = null
    var escaped: HdlInt = null
    captureItems {
      for (lane <- 0 until lanes) {
        val indexed = lane * HdlInt.literal(1)
        escaped = indexed
        inside = intercept[FrontendException](indexed < lanes)
      }
    }
    assert(inside.code == "MORPH-FRONTEND-GENINDEX-CONSUMER-UNSUPPORTED")
    assert(intercept[FrontendException](escaped >= lanes).code == "MORPH-FRONTEND-GENINDEX-ESCAPED")
  }

  test("derives deterministic labels from the generateIf source site") {
    def capture(): GenerateIf = captureItems {
      val enabled = HdlBool.param("ENABLED", default = true)
      generateIf(enabled) {
        emitInstance(name = "enabled_inst", moduleName = "EnabledPath")
      } otherwise {}
    }.raw.head.asInstanceOf[GenerateIf]

    val first = capture()
    val second = capture()
    assert(first.whenTrue.label == second.whenTrue.label)
    assert(first.whenFalse.label == second.whenFalse.label)
    assert(first.whenTrue.label.matches("g_if_GenerateIfFrontendTests_l[0-9]+_true"))
    assert(first.whenFalse.label == first.whenTrue.label.replace("_true", "_false"))
  }

  test("concrete mode executes only the witness-selected branch") {
    val seen = ArrayBuffer.empty[String]

    FrontendSession.concrete {
      generateIf(HdlBool.param("TRUE_PATH", default = true)) {
        seen += "true"
      } otherwise {
        seen += "unexpected-false"
      }
    }
    FrontendSession.concrete {
      generateIf(HdlBool.param("FALSE_PATH", default = false)) {
        seen += "unexpected-true"
      } otherwise {
        seen += "false"
      }
    }

    assert(seen.toVector == Vector("true", "false"))
  }

  test("requires exactly one otherwise and rejects an escaped builder") {
    val enabled = HdlBool.param("ENABLED", default = true)
    var escaped: GenerateIfBuilder = null
    val missing = intercept[FrontendException] {
      captureItems {
        escaped = generateIf(enabled) {}
      }
    }
    assert(missing.code == "MORPH-FRONTEND-GENERATE-IF-OTHERWISE-MISSING")

    val escapedError = intercept[FrontendException] {
      escaped.otherwise {}
    }
    assert(escapedError.code == "MORPH-FRONTEND-GENERATE-IF-ESCAPED")

    var duplicate: FrontendException = null
    captureItems {
      val builder = generateIf(enabled) {}
      builder.otherwise {}
      duplicate = intercept[FrontendException](builder.otherwise {})
    }
    assert(duplicate.code == "MORPH-FRONTEND-GENERATE-IF-OTHERWISE-DUPLICATE")
  }

  test("rejects nested generate-if and generate-for regions in either direction") {
    val enabled = HdlBool.param("ENABLED", default = true)
    val lanes = HdlInt.param("LANES", default = 2, min = 1, max = 4)

    val nestedIf = intercept[FrontendException] {
      captureItems {
        generateIf(enabled) {
          generateIf(enabled) {} otherwise {}
        } otherwise {}
      }
    }
    assert(nestedIf.code == "MORPH-FRONTEND-NESTED-GENERATE-UNSUPPORTED")

    val loopInsideIf = intercept[FrontendException] {
      captureItems {
        generateIf(enabled) {
          for (_ <- 0 until lanes) ()
        } otherwise {}
      }
    }
    assert(loopInsideIf.code == "MORPH-FRONTEND-NESTED-GENERATE-UNSUPPORTED")

    val ifInsideLoop = intercept[FrontendException] {
      captureItems {
        for (_ <- 0 until lanes) {
          generateIf(enabled) {} otherwise {}
        }
      }
    }
    assert(ifInsideLoop.code == "MORPH-FRONTEND-NESTED-GENERATE-UNSUPPORTED")
  }

  test("permits one conditional per capture and reserves labels across generate kinds") {
    val enabled = HdlBool.param("ENABLED", default = true)
    var multiple: FrontendException = null
    captureItems {
      generateIf(enabled) {} otherwise {}
      multiple = intercept[FrontendException] {
        generateIf(enabled) {} otherwise {}
      }
    }
    assert(multiple.code == "MORPH-FRONTEND-GENERATE-IF-MULTIPLE")

    val lanes = HdlInt.param("LANES", default = 1, min = 1, max = 1)
    val collision = intercept[FrontendException] {
      captureItems {
        for (_ <- (0 until lanes).named("g_enabled", "lane")) ()
        generateIf(enabled, "g_enabled", "g_disabled") {} otherwise {}
      }
    }
    assert(collision.code == "MORPH-FRONTEND-GENERATE-NAME-DUPLICATE")
  }

  test("rejects invalid labels and null conditions at their call sites") {
    val enabled = HdlBool.param("ENABLED", default = true)
    assert(
      intercept[FrontendException] {
        generateIf(enabled, "not-portable", "g_disabled") {}
      }.code == "MORPH-FRONTEND-INVALID-GENERATE-NAME"
    )
    assert(
      intercept[FrontendException] {
        captureItems {
          generateIf(null) {} otherwise {}
        }
      }.code == "MORPH-FRONTEND-BOOLEAN-CONDITION-NULL"
    )
  }

  test("rejects distinct same-named and undeclared Boolean identities") {
    val declared = HdlBool.param("ENABLED", default = true)
    val used = HdlBool.param("ENABLED", default = false)
    val items = captureItems {
      generateIf(used) {} otherwise {}
    }

    val mismatch = intercept[FrontendException] {
      moduleDef(
        name = "Mismatch",
        parameters = Vector.empty,
        ports = Vector.empty,
        items = items,
        booleanParameters = Vector(booleanParameter(declared))
      )
    }
    assert(mismatch.code == "MORPH-FRONTEND-BOOLEAN-PARAMETER-TOKEN-MISMATCH")
    assert(mismatch.origin == used.origin)

    val missing = intercept[FrontendException] {
      moduleDef(
        name = "Missing",
        parameters = Vector.empty,
        ports = Vector.empty,
        items = items
      )
    }
    assert(missing.code == "MORPH-FRONTEND-BOOLEAN-PARAMETER-NOT-DECLARED")
  }

  test("rejects cross-kind names and restores capture state after branch failures") {
    val integer = HdlInt.param("SHARED", default = 1, min = 0, max = 1)
    val boolean = HdlBool.param("SHARED", default = true)
    val collision = intercept[FrontendException] {
      moduleDef(
        name = "Collision",
        parameters = Vector(integerParameter(integer)),
        ports = Vector.empty,
        items = captureItems {},
        booleanParameters = Vector(booleanParameter(boolean))
      )
    }
    assert(collision.code == "MORPH-FRONTEND-PARAMETER-KIND-COLLISION")

    val failed = intercept[IllegalStateException] {
      captureItems {
        generateIf(boolean) {
          emitInstance(name = "discarded", moduleName = "Discarded")
          throw new IllegalStateException("branch failed")
        } otherwise {}
      }
    }
    assert(failed.getMessage == "branch failed")

    val recovered = captureItems {
      generateIf(boolean) {
        emitInstance(name = "kept", moduleName = "Kept")
      } otherwise {}
    }
    assert(recovered.raw.size == 1)
  }

  test("rolls back failed true and false branches inside the same capture") {
    val enabled = HdlBool.param("ENABLED", default = true)

    val afterTrueFailure = captureItems {
      try {
        generateIf(enabled, "g_true", "g_false") {
          emitInstance(name = "discarded_true", moduleName = "Discarded")
          throw new IllegalStateException("true failed")
        } otherwise {}
      } catch {
        case error: IllegalStateException => assert(error.getMessage == "true failed")
      }
      generateIf(enabled, "g_true", "g_false") {
        emitInstance(name = "kept_true", moduleName = "Kept")
      } otherwise {}
    }
    assert(afterTrueFailure.raw.size == 1)
    assert(
      afterTrueFailure.raw.head
        .asInstanceOf[GenerateIf]
        .whenTrue
        .body
        .map(_.asInstanceOf[ModuleInstance].name) == Vector("kept_true")
    )

    val afterFalseFailure = captureItems {
      try {
        generateIf(enabled, "g_true", "g_false") {} otherwise {
          emitInstance(name = "discarded_false", moduleName = "Discarded")
          throw new IllegalStateException("false failed")
        }
      } catch {
        case error: IllegalStateException => assert(error.getMessage == "false failed")
      }
      generateIf(enabled, "g_true", "g_false") {} otherwise {
        emitInstance(name = "kept_false", moduleName = "Kept")
      }
    }
    assert(afterFalseFailure.raw.size == 1)
    assert(
      afterFalseFailure.raw.head
        .asInstanceOf[GenerateIf]
        .whenFalse
        .body
        .map(_.asInstanceOf[ModuleInstance].name) == Vector("kept_false")
    )
  }

  test("rejects otherwise from a foreign thread without stealing the originating session") {
    val enabled = HdlBool.param("ENABLED", default = true)
    val executor = Executors.newSingleThreadExecutor()
    var foreign: FrontendException = null
    try {
      val missing = intercept[FrontendException] {
        captureItems {
          val builder = generateIf(enabled) {}
          foreign = executor
            .submit(new Callable[FrontendException] {
              override def call(): FrontendException =
                intercept[FrontendException](builder.otherwise {})
            })
            .get(10, TimeUnit.SECONDS)
        }
      }
      assert(missing.code == "MORPH-FRONTEND-GENERATE-IF-OTHERWISE-MISSING")
      assert(foreign.code == "MORPH-FRONTEND-GENERATE-IF-ESCAPED")
    } finally {
      executor.shutdownNow()
      assert(executor.awaitTermination(10, TimeUnit.SECONDS))
    }
  }
}
