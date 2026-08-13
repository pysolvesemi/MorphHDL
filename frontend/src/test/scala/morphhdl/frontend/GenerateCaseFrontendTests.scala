package morphhdl.frontend

import java.util.concurrent.{Callable, Executors, TimeUnit}

import scala.collection.mutable.ArrayBuffer

import morphhdl.frontend.ParamRtlFrontend._
import morphhdl.paramrtl.IntExpr.{Literal, ParameterRef}
import morphhdl.paramrtl.ModuleItem.{GenerateCase, ModuleInstance}
import org.scalatest.funsuite.AnyFunSuite

class GenerateCaseFrontendTests extends AnyFunSuite {
  test("captures every explicit branch once with stable labels and sorted literal choices") {
    val selector = HdlInt.param("SELECT", default = 1, min = 0, max = 1)
    val seen = ArrayBuffer.empty[String]

    val items = captureItems {
      generateCase(selector)
        .choice(1, "g_one") {
          seen += "one"
          emitInstance("shared_inst", "One")
        }
        .choice(-1, "g_negative") {
          seen += "negative"
          emitInstance("shared_inst", "Negative")
        }
        .choice(0, "g_zero") {
          seen += "zero"
          emitInstance("shared_inst", "Zero")
        }
        .default("g_other") {
          seen += "default"
          emitInstance("shared_inst", "Other")
        }
    }

    assert(seen.toVector == Vector("one", "negative", "zero", "default"))
    assert(items.raw.size == 1)
    val generated = items.raw.head.asInstanceOf[GenerateCase]
    assert(generated.selector == ParameterRef("SELECT"))
    assert(generated.choices.map(_.value) == Vector(BigInt(-1), BigInt(0), BigInt(1)))
    assert(
      generated.choices.map(_.block.label) ==
        Vector("g_negative", "g_zero", "g_one")
    )
    assert(generated.default.label == "g_other")
    assert(
      generated.choices.map(_.block.body.head.asInstanceOf[ModuleInstance].name) ==
        Vector("shared_inst", "shared_inst", "shared_inst")
    )
    assert(generated.default.body.head.asInstanceOf[ModuleInstance].name == "shared_inst")

    val module = moduleDef(
      name = "CaseTop",
      parameters = Vector(integerParameter(selector)),
      ports = Vector.empty,
      items = items
    )
    assert(module.items == items.raw)
  }

  test("retains selector and every branch provenance until module ownership is discharged") {
    val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 16)
    val branchWidth = HdlInt.param("BRANCH_WIDTH", default = 4, min = 1, max = 16)
    val enabled = HdlBool.param("ENABLED", default = true)
    val localWidth = localParam("LOCAL_WIDTH", width + 1)
    val localEnabled = localParam("LOCAL_ENABLED", enabled && (localWidth > width))
    val selector = localEnabled.select(localWidth, width)

    val items = captureItems {
      generateCase(selector)
        .choice(9, "g_selected") {
          emitInstance(
            "selected_inst",
            "Selected",
            parameterBindings = Vector(parameterBinding("WIDTH", branchWidth))
          )
        }
        .default("g_other") {}
    }

    assert(items.parameters == selector.parameters ++ branchWidth.parameters)
    assert(items.booleanParameters == selector.booleanParameters)
    assert(items.localParameters == selector.localParameters)
    assert(items.booleanLocalParameters == selector.booleanLocalParameters)

    val module = moduleDef(
      name = "ProvenanceCase",
      parameters = Vector(integerParameter(width), integerParameter(branchWidth)),
      ports = Vector.empty,
      items = items,
      localParameters = Vector(integerLocalParameter(localWidth)),
      booleanParameters = Vector(booleanParameter(enabled)),
      booleanLocalParameters = Vector(booleanLocalParameter(localEnabled))
    )
    assert(module.parameters.map(_.name) == Vector("WIDTH", "BRANCH_WIDTH"))
    assert(module.localParameters.map(_.name) == Vector("LOCAL_WIDTH"))
    assert(module.booleanLocalParameters.map(_.name) == Vector("LOCAL_ENABLED"))
  }

  test("retains selector identity for mismatch and undeclared diagnostics") {
    val declared = HdlInt.param("SELECT", default = 0, min = 0, max = 3)
    val used = HdlInt.param("SELECT", default = 1, min = 0, max = 3)
    val items = captureItems {
      generateCase(used)
        .choice(1, "g_one") {}
        .default("g_other") {}
    }

    val mismatch = intercept[FrontendException] {
      moduleDef(
        name = "CaseMismatch",
        parameters = Vector(integerParameter(declared)),
        ports = Vector.empty,
        items = items
      )
    }
    assert(mismatch.code == "MORPH-FRONTEND-PARAMETER-TOKEN-MISMATCH")
    assert(mismatch.origin == used.origin)

    val missing = intercept[FrontendException] {
      moduleDef(
        name = "CaseMissing",
        parameters = Vector.empty,
        ports = Vector.empty,
        items = items
      )
    }
    assert(missing.code == "MORPH-FRONTEND-PARAMETER-NOT-DECLARED")
    assert(missing.origin == used.origin)
  }

  test("concrete mode executes exactly the witness-selected choice or default") {
    val seen = ArrayBuffer.empty[String]

    FrontendSession.concrete {
      generateCase(HdlInt.param("SELECT_ONE", default = 1, min = 0, max = 4))
        .choice(0, "g_zero") { seen += "unexpected-zero" }
        .choice(1, "g_one") { seen += "one" }
        .choice(2, "g_two") { seen += "unexpected-two" }
        .default("g_other") { seen += "unexpected-default" }
    }
    FrontendSession.concrete {
      generateCase(HdlInt.param("SELECT_OTHER", default = 4, min = 0, max = 4))
        .choice(0, "g_zero") { seen += "unexpected-zero" }
        .choice(1, "g_one") { seen += "unexpected-one" }
        .default("g_other") { seen += "default" }
    }

    assert(seen.toVector == Vector("one", "default"))
  }

  test("requires a default and rejects escaped, replayed and late builders") {
    val selector = HdlInt.literal(0)
    var escaped: GenerateCaseBuilder = null
    val missing = intercept[FrontendException] {
      captureItems {
        escaped = generateCase(selector).choice(0, "g_zero") {}
      }
    }
    assert(missing.code == "MORPH-FRONTEND-GENERATE-CASE-DEFAULT-MISSING")
    assert(
      intercept[FrontendException] {
        escaped.default("g_other") {}
      }.code == "MORPH-FRONTEND-GENERATE-CASE-ESCAPED"
    )

    var completedChoice: FrontendException = null
    var duplicateDefault: FrontendException = null
    captureItems {
      val builder = generateCase(selector).choice(0, "g_zero") {}
      builder.default("g_other") {}
      completedChoice = intercept[FrontendException] {
        builder.choice(1, "g_one") {}
      }
      duplicateDefault = intercept[FrontendException] {
        builder.default("g_again") {}
      }
    }
    assert(completedChoice.code == "MORPH-FRONTEND-GENERATE-CASE-COMPLETED")
    assert(duplicateDefault.code == "MORPH-FRONTEND-GENERATE-CASE-DEFAULT-DUPLICATE")
  }

  test("rejects duplicate literals and labels without leaking or invalidating retryable state") {
    val selector = HdlInt.literal(0)
    var duplicateBodyRan = false
    var duplicateValue: FrontendException = null
    var duplicateLabel: FrontendException = null

    val items = captureItems {
      val builder = generateCase(selector).choice(0, "g_zero") {}
      duplicateValue = intercept[FrontendException] {
        builder.choice(0, "g_duplicate_value") { duplicateBodyRan = true }
      }
      duplicateLabel = intercept[FrontendException] {
        builder.choice(1, "g_zero") { duplicateBodyRan = true }
      }
      builder
        .choice(1, "g_one") {}
        .default("g_other") {}
    }

    assert(duplicateValue.code == "MORPH-FRONTEND-GENERATE-CASE-CHOICE-DUPLICATE")
    assert(duplicateLabel.code == "MORPH-FRONTEND-GENERATE-NAME-DUPLICATE")
    assert(!duplicateBodyRan)
    assert(items.raw.head.asInstanceOf[GenerateCase].choices.map(_.value) == Vector(0, 1))
  }

  test("allows recovery after missing choices and invalid or null inputs") {
    val selector = HdlInt.literal(0)
    var missingChoice: FrontendException = null
    var nullChoice: FrontendException = null
    var invalidChoiceLabel: FrontendException = null
    var nullChoiceLabel: FrontendException = null
    var invalidDefaultLabel: FrontendException = null
    var nullDefaultLabel: FrontendException = null

    val items = captureItems {
      val builder = generateCase(selector)
      missingChoice = intercept[FrontendException] {
        builder.default("g_other") {}
      }
      nullChoice = intercept[FrontendException] {
        builder.choice(null: BigInt, "g_null_value") {}
      }
      invalidChoiceLabel = intercept[FrontendException] {
        builder.choice(0, "not-portable") {}
      }
      nullChoiceLabel = intercept[FrontendException] {
        builder.choice(0, null) {}
      }
      builder.choice(0, "g_zero") {}
      invalidDefaultLabel = intercept[FrontendException] {
        builder.default("also-not-portable") {}
      }
      nullDefaultLabel = intercept[FrontendException] {
        builder.default(null) {}
      }
      builder.default("g_other") {}
    }

    assert(missingChoice.code == "MORPH-FRONTEND-GENERATE-CASE-CHOICE-MISSING")
    assert(nullChoice.code == "MORPH-FRONTEND-GENERATE-CASE-CHOICE-NULL")
    assert(invalidChoiceLabel.code == "MORPH-FRONTEND-INVALID-GENERATE-NAME")
    assert(nullChoiceLabel.code == "MORPH-FRONTEND-INVALID-GENERATE-NAME")
    assert(invalidDefaultLabel.code == "MORPH-FRONTEND-INVALID-GENERATE-NAME")
    assert(nullDefaultLabel.code == "MORPH-FRONTEND-INVALID-GENERATE-NAME")
    assert(items.raw.size == 1)

    assert(
      intercept[FrontendException] {
        captureItems {
          generateCase(null)
        }
      }.code == "MORPH-FRONTEND-GENERATE-CASE-SELECTOR-NULL"
    )
    assert(
      intercept[FrontendException] {
        generateIf(HdlBool.literal(true), null, "g_false") {}
      }.code == "MORPH-FRONTEND-INVALID-GENERATE-NAME"
    )
    val lanes = HdlInt.param("LANES", default = 1, min = 1, max = 1)
    assert(
      intercept[FrontendException] {
        (0 until lanes).named(null, "lane")
      }.code == "MORPH-FRONTEND-INVALID-GENERATE-NAME"
    )
  }

  test("permits one conditional region and rejects case-if siblings in either order") {
    val selector = HdlInt.literal(0)
    val enabled = HdlBool.literal(true)

    var secondCase: FrontendException = null
    captureItems {
      generateCase(selector).choice(0, "g_zero") {}.default("g_other") {}
      secondCase = intercept[FrontendException] {
        generateCase(selector)
      }
    }
    assert(secondCase.code == "MORPH-FRONTEND-GENERATE-CASE-MULTIPLE")

    var ifAfterCase: FrontendException = null
    captureItems {
      generateCase(selector).choice(0, "g_zero") {}.default("g_other") {}
      ifAfterCase = intercept[FrontendException] {
        generateIf(enabled) {} otherwise {}
      }
    }
    assert(ifAfterCase.code == "MORPH-FRONTEND-GENERATE-IF-MULTIPLE")

    var caseAfterIf: FrontendException = null
    captureItems {
      generateIf(enabled) {} otherwise {}
      caseAfterIf = intercept[FrontendException] {
        generateCase(selector)
      }
    }
    assert(caseAfterIf.code == "MORPH-FRONTEND-GENERATE-CASE-MULTIPLE")
  }

  test("rejects nested case, if and for regions in every direction") {
    val selector = HdlInt.literal(0)
    val enabled = HdlBool.literal(true)
    val lanes = HdlInt.param("LANES", default = 1, min = 1, max = 2)

    def nested(body: => Unit): Unit =
      assert(
        intercept[FrontendException] {
          captureItems(body)
        }.code == "MORPH-FRONTEND-NESTED-GENERATE-UNSUPPORTED"
      )

    nested {
      generateCase(selector)
        .choice(0, "g_outer") {
          generateCase(selector)
        }
        .default("g_outer_default") {}
    }
    nested {
      generateCase(selector)
        .choice(0, "g_outer") {}
        .default("g_outer_default") {
          generateCase(selector)
        }
    }
    nested {
      generateCase(selector)
        .choice(0, "g_outer") {
          generateIf(enabled) {} otherwise {}
        }
        .default("g_outer_default") {}
    }
    nested {
      generateIf(enabled) {
        generateCase(selector)
      } otherwise {}
    }
    nested {
      generateCase(selector)
        .choice(0, "g_outer") {
          for (_ <- 0 until lanes) ()
        }
        .default("g_outer_default") {}
    }
    nested {
      for (_ <- 0 until lanes) {
        generateCase(selector)
      }
    }
  }

  test("rejects generate-index selectors inside and after their lexical loop scope") {
    val lanes = HdlInt.param("LANES", default = 1, min = 1, max = 2)
    var inside: FrontendException = null
    var escaped: HdlInt = null

    captureItems {
      for (lane <- 0 until lanes) {
        val indexed = lane * 1
        escaped = indexed
        inside = intercept[FrontendException] {
          generateCase(indexed)
        }
      }
    }
    assert(inside.code == "MORPH-FRONTEND-GENINDEX-CONSUMER-UNSUPPORTED")

    val after = intercept[FrontendException] {
      captureItems {
        generateCase(escaped)
      }
    }
    assert(after.code == "MORPH-FRONTEND-GENINDEX-ESCAPED")
  }

  test("rolls back failed choice and default bodies for same-capture label reuse") {
    val selector = HdlInt.literal(0)

    val afterChoiceFailure = captureItems {
      try {
        generateCase(selector).choice(0, "g_zero") {
          emitInstance("discarded_choice", "Discarded")
          throw new IllegalStateException("choice failed")
        }
      } catch {
        case error: IllegalStateException => assert(error.getMessage == "choice failed")
      }
      generateCase(selector)
        .choice(0, "g_zero") {
          emitInstance("kept_choice", "Kept")
        }
        .default("g_other") {}
    }
    assert(afterChoiceFailure.raw.size == 1)
    assert(
      afterChoiceFailure.raw.head
        .asInstanceOf[GenerateCase]
        .choices
        .head
        .block
        .body
        .head
        .asInstanceOf[ModuleInstance]
        .name == "kept_choice"
    )

    val afterDefaultFailure = captureItems {
      try {
        generateCase(selector)
          .choice(0, "g_zero") {}
          .default("g_other") {
            emitInstance("discarded_default", "Discarded")
            throw new IllegalStateException("default failed")
          }
      } catch {
        case error: IllegalStateException => assert(error.getMessage == "default failed")
      }
      generateCase(selector)
        .choice(0, "g_zero") {}
        .default("g_other") {
          emitInstance("kept_default", "Kept")
        }
    }
    assert(afterDefaultFailure.raw.size == 1)
    assert(
      afterDefaultFailure.raw.head
        .asInstanceOf[GenerateCase]
        .default
        .body
        .head
        .asInstanceOf[ModuleInstance]
        .name == "kept_default"
    )
  }

  test("rejects cross-thread continuation without stealing the originating session") {
    val executor = Executors.newSingleThreadExecutor()
    var foreign: FrontendException = null
    try {
      val missing = intercept[FrontendException] {
        captureItems {
          val builder = generateCase(HdlInt.literal(0)).choice(0, "g_zero") {}
          foreign = executor
            .submit(new Callable[FrontendException] {
              override def call(): FrontendException =
                intercept[FrontendException](builder.default("g_other") {})
            })
            .get(10, TimeUnit.SECONDS)
        }
      }
      assert(missing.code == "MORPH-FRONTEND-GENERATE-CASE-DEFAULT-MISSING")
      assert(foreign.code == "MORPH-FRONTEND-GENERATE-CASE-ESCAPED")
    } finally {
      executor.shutdownNow()
      assert(executor.awaitTermination(10, TimeUnit.SECONDS))
    }
  }

  test("literal selector conversion and raw case equality remain source-compatible") {
    val items = captureItems {
      generateCase(1)
        .choice(1, "g_one") {}
        .default("g_other") {}
    }
    val generated = items.raw.head.asInstanceOf[GenerateCase]
    assert(generated.selector == Literal(1))
    assert(generated == generated.copy())
  }
}
