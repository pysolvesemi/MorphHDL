package morphhdl.frontend

import java.util.concurrent.{Callable, Executors, TimeUnit}

import scala.collection.mutable.ArrayBuffer

import morphhdl.frontend.ParamRtlFrontend._
import morphhdl.paramrtl.IntExpr.{GenerateIndexRef, Multiply, ParameterRef}
import morphhdl.paramrtl.ModuleItem.{GenerateFor, ModuleInstance}
import morphhdl.paramrtl.RtlExpr.{IndexedPartSelect, Ref}
import morphhdl.paramrtl._
import org.scalatest.funsuite.AnyFunSuite

class GenerateForFrontendTests extends AnyFunSuite {
  test("captures one symbolic body from the native Scala for spelling") {
    val lanes = HdlInt.param("LANES", default = 4, min = 1, max = 64)
    val width = HdlInt.param("DATA_WIDTH", default = 8, min = 1, max = 1024)
    var invocations = 0

    val items = captureItems {
      for (lane <- (0 until lanes).named(label = "g_lane", index = "lane")) {
        invocations += 1
        val offset = lane * width
        emitInstance(
          name = "lane_inst",
          moduleName = "PixelLane",
          parameterBindings = Vector(parameterBinding("DATA_WIDTH", width)),
          portConnections = Vector(
            portConnection("data_in", indexedPartSelect("data_in", offset, width)),
            portConnection("data_out", indexedPartSelect("data_out", offset, width))
          )
        )
      }
    }

    assert(invocations == 1)
    assert(items.raw.size == 1)
    val generate = items.raw.head.asInstanceOf[GenerateFor]
    assert(generate.label == "g_lane")
    assert(generate.indexName == "lane")
    assert(generate.count == ParameterRef("LANES"))
    assert(generate.body.size == 1)

    val instance = generate.body.head.asInstanceOf[ModuleInstance]
    assert(instance.name == "lane_inst")
    assert(instance.parameterBindings == Vector(ParameterBinding("DATA_WIDTH", ParameterRef("DATA_WIDTH"))))
    instance.portConnections.foreach { connection =>
      assert(
        connection.actual == IndexedPartSelect(
          Ref(connection.portName),
          Multiply(GenerateIndexRef("lane"), ParameterRef("DATA_WIDTH")),
          ParameterRef("DATA_WIDTH")
        )
      )
    }
  }

  test("executes concrete mode once per witness with scoped indices") {
    val lanes = HdlInt.param("LANES", default = 4, min = 1, max = 64)
    val seen = ArrayBuffer.empty[BigInt]

    FrontendSession.concrete {
      for (lane <- 0 until lanes) {
        seen += lane.witness
      }
    }

    assert(seen.toVector == Vector[BigInt](0, 1, 2, 3))
  }

  test("does not use the concrete witness as a no-session fallback") {
    val lanes = HdlInt.param("LANES", default = 4, min = 1, max = 64)

    val error = intercept[FrontendException] {
      for (_ <- 0 until lanes) ()
    }
    assert(error.code == "MORPH-FRONTEND-SESSION-MISSING")
  }

  test("retains the loop origin and fails closed for GenIndex comparisons") {
    val lanes = HdlInt.param("LANES", default = 1, min = 1, max = 64)
    val errors = ArrayBuffer.empty[FrontendException]
    val loopLine = sourcecode.Line() + 2
    FrontendSession.concrete {
      for (lane <- 0 until lanes) {
        val same = lane
        errors += intercept[FrontendException](lane == same)
        errors += intercept[FrontendException](lane != same)
        errors += intercept[FrontendException](lane == 0)
        errors += intercept[FrontendException](lane != 0)
        errors += intercept[FrontendException](lane.hashCode)
      }
    }

    assert(errors.size == 5)
    errors.foreach { error =>
      assert(error.code == "MORPH-FRONTEND-SYMBOLIC-COMPARISON-UNSUPPORTED")
      assert(error.origin.file.endsWith("GenerateForFrontendTests.scala"))
      assert(error.origin.line == loopLine)
      assert(
        error.suggestedReplacement ==
          "Use a static Scala condition, or wait for the parameter-aware HdlBool comparison API."
      )
    }
  }

  test("assigns deterministic names to the bare loop spelling") {
    def capture(): Vector[ModuleItem] = captureItems {
      val lanes = HdlInt.param("LANES", default = 2, min = 1, max = 64)
      for (_ <- 0 until lanes) {
        emitInstance(name = "lane_inst", moduleName = "PixelLane")
      }
    }.raw

    val first = capture().head.asInstanceOf[GenerateFor]
    val second = capture().head.asInstanceOf[GenerateFor]

    assert(first == second)
    assert(first.label.matches("g_generate_GenerateForFrontendTests_l[0-9]+"))
    assert(first.indexName == first.label.replace("g_generate_", "gen_index_"))
  }

  test("assigns unique deterministic names within one capture") {
    val lanes = HdlInt.param("LANES", default = 2, min = 1, max = 64)
    val items = captureItems {
      for (_ <- 0 until lanes) {
        emitInstance(name = "first_inst", moduleName = "PixelLane")
      }
      for (_ <- 0 until lanes) {
        emitInstance(name = "second_inst", moduleName = "PixelLane")
      }
    }
    val generates = items.raw.map(_.asInstanceOf[GenerateFor])

    assert(generates.map(_.label).distinct.size == 2)
    assert(generates.forall(_.label.matches("g_generate_GenerateForFrontendTests_l[0-9]+")))
    assert(generates.forall(g => g.indexName == g.label.replace("g_generate_", "gen_index_")))
  }

  test("keeps bare names attached to source sites when construction order reverses") {
    val lanes = HdlInt.param("LANES", default = 2, min = 1, max = 64)
    def first(): Unit = for (_ <- 0 until lanes) {
      emitInstance(name = "first_inst", moduleName = "PixelLane")
    }
    def second(): Unit = for (_ <- 0 until lanes) {
      emitInstance(name = "second_inst", moduleName = "PixelLane")
    }
    def labels(reverse: Boolean): Map[String, String] = {
      val captured = captureItems {
        if (reverse) {
          second()
          first()
        } else {
          first()
          second()
        }
      }
      captured.raw.map { item =>
        val generate = item.asInstanceOf[GenerateFor]
        generate.body.head.asInstanceOf[ModuleInstance].name -> generate.label
      }.toMap
    }

    assert(labels(reverse = false) == labels(reverse = true))
  }

  test("rejects invalid and duplicate explicit generate names") {
    val lanes = HdlInt.param("LANES", default = 2, min = 1, max = 64)
    val invalid = intercept[FrontendException] {
      (0 until lanes).named(label = "not-portable", index = "lane")
    }
    assert(invalid.code == "MORPH-FRONTEND-INVALID-GENERATE-NAME")

    val duplicate = intercept[FrontendException] {
      captureItems {
        for (_ <- (0 until lanes).named("g_lane", "lane")) {
          emitInstance(name = "first", moduleName = "PixelLane")
        }
        for (_ <- (0 until lanes).named("g_lane", "other_lane")) {
          emitInstance(name = "second", moduleName = "PixelLane")
        }
      }
    }
    assert(duplicate.code == "MORPH-FRONTEND-GENERATE-NAME-DUPLICATE")

    val crossKind = intercept[FrontendException] {
      captureItems {
        for (_ <- (0 until lanes).named("shared", "shared")) ()
      }
    }
    assert(crossKind.code == "MORPH-FRONTEND-GENERATE-NAME-DUPLICATE")
  }

  test("rejects an escaped generate-index expression") {
    val lanes = HdlInt.param("LANES", default = 4, min = 1, max = 64)
    val width = HdlInt.param("DATA_WIDTH", default = 8, min = 1, max = 1024)
    var escaped: HdlInt = null

    captureItems {
      for (lane <- 0 until lanes) {
        escaped = lane * width
      }
    }

    val error = intercept[FrontendException](packedBits(escaped))
    assert(error.code == "MORPH-FRONTEND-GENINDEX-ESCAPED")
  }

  test("rejects a directly escaped GenIndex") {
    val lanes = HdlInt.param("LANES", default = 2, min = 1, max = 64)
    var escaped: GenIndex = null

    captureItems {
      for (lane <- 0 until lanes) {
        escaped = lane
      }
    }

    val error = intercept[FrontendException](escaped == 0)
    assert(error.code == "MORPH-FRONTEND-GENINDEX-ESCAPED")
  }

  test("rejects nested symbolic loops") {
    val lanes = HdlInt.param("LANES", default = 2, min = 1, max = 64)

    val explicitError = intercept[FrontendException] {
      captureItems {
        for (_ <- 0 until lanes) {
          for (_ <- 0 until lanes) ()
        }
      }
    }
    assert(explicitError.code == "MORPH-FRONTEND-NESTED-GENERATE-UNSUPPORTED")

    val comprehensionError = intercept[FrontendException] {
      captureItems {
        for {
          _ <- 0 until lanes
          _ <- 0 until lanes
        } ()
      }
    }
    assert(comprehensionError.code == "MORPH-FRONTEND-NESTED-GENERATE-UNSUPPORTED")
  }

  test("rejects generate-index-dependent unsupported consumers") {
    val lanes = HdlInt.param("LANES", default = 2, min = 1, max = 64)
    val width = HdlInt.param("DATA_WIDTH", default = 8, min = 1, max = 1024)

    val error = intercept[FrontendException] {
      captureItems {
        for (lane <- 0 until lanes) {
          parameterBinding("DATA_WIDTH", lane * width)
        }
      }
    }

    assert(error.code == "MORPH-FRONTEND-GENINDEX-CONSUMER-UNSUPPORTED")
  }

  test("rejects nonzero starts and invalid concrete witnesses") {
    val lanes = HdlInt.param("LANES", default = 2, min = 1, max = 64)
    val startError = intercept[FrontendException] {
      captureItems {
        for (_ <- 1 until lanes) ()
      }
    }
    assert(startError.code == "MORPH-FRONTEND-GENERATE-START-UNSUPPORTED")

    val countError = intercept[FrontendException] {
      val zero: HdlInt = 0
      captureItems {
        for (_ <- 0 until zero) ()
      }
    }
    assert(countError.code == "MORPH-FRONTEND-GENERATE-COUNT-NONPOSITIVE")

    val largeCountError = intercept[FrontendException] {
      val tooLarge = HdlInt.literal(BigInt(Int.MaxValue) + 1)
      captureItems {
        for (_ <- 0 until tooLarge) ()
      }
    }
    assert(largeCountError.code == "MORPH-FRONTEND-GENERATE-COUNT-TOO-LARGE")
  }

  test("restores the capture context and closes scopes after exceptions") {
    val lanes = HdlInt.param("LANES", default = 2, min = 1, max = 64)
    val width = HdlInt.param("DATA_WIDTH", default = 8, min = 1, max = 1024)
    var escaped: HdlInt = null

    intercept[IllegalStateException] {
      captureItems {
        for (lane <- 0 until lanes) {
          escaped = lane * width
          throw new IllegalStateException("body failed")
        }
      }
    }
    assert(intercept[FrontendException](packedBits(escaped)).code == "MORPH-FRONTEND-GENINDEX-ESCAPED")

    val recovered = captureItems {
      for (_ <- 0 until lanes) {
        emitInstance(name = "lane_inst", moduleName = "PixelLane")
      }
    }
    assert(recovered.raw.head.asInstanceOf[GenerateFor].label.startsWith("g_generate_"))
  }

  test("discards a failed body before continuing a caught capture") {
    val lanes = HdlInt.param("LANES", default = 2, min = 1, max = 64)

    val recovered = captureItems {
      try {
        for (_ <- 0 until lanes) {
          emitInstance(name = "partial_inst", moduleName = "PixelLane")
          throw new IllegalStateException("discard this body")
        }
      } catch {
        case _: IllegalStateException =>
      }
      for (_ <- 0 until lanes) {
        emitInstance(name = "complete_inst", moduleName = "PixelLane")
      }
    }

    assert(recovered.raw.size == 1)
    val generate = recovered.raw.head.asInstanceOf[GenerateFor]
    assert(generate.label.startsWith("g_generate_"))
    assert(generate.body.map(_.asInstanceOf[ModuleInstance].name) == Vector("complete_inst"))
  }

  test("rolls back explicit-name reservations when a captured body fails") {
    val lanes = HdlInt.param("LANES", default = 2, min = 1, max = 64)

    val recovered = captureItems {
      try {
        for (_ <- (0 until lanes).named("g_lane", "lane")) {
          emitInstance(name = "partial_inst", moduleName = "PixelLane")
          throw new IllegalStateException("discard this body")
        }
      } catch {
        case _: IllegalStateException =>
      }
      for (_ <- (0 until lanes).named("g_lane", "lane")) {
        emitInstance(name = "complete_inst", moduleName = "PixelLane")
      }
    }

    assert(recovered.raw.size == 1)
    val generate = recovered.raw.head.asInstanceOf[GenerateFor]
    assert(generate.label == "g_lane")
    assert(generate.indexName == "lane")
    assert(generate.body.map(_.asInstanceOf[ModuleInstance].name) == Vector("complete_inst"))
  }

  test("rejects module-item emission outside parameterized capture") {
    val error = intercept[FrontendException] {
      emitInstance(name = "lane_inst", moduleName = "PixelLane")
    }

    assert(error.code == "MORPH-FRONTEND-EMIT-OUTSIDE-CAPTURE")
  }

  test("isolates parallel capture sessions") {
    val executor = Executors.newFixedThreadPool(2)
    try {
      def task = new Callable[GenerateFor] {
        override def call(): GenerateFor = {
          val lanes = HdlInt.param("LANES", default = 2, min = 1, max = 64)
          captureItems {
            for (_ <- 0 until lanes) {
              emitInstance(name = "lane_inst", moduleName = "PixelLane")
            }
          }.raw.head.asInstanceOf[GenerateFor]
        }
      }

      val first = executor.submit(task)
      val second = executor.submit(task)
      val values = Vector(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))

      assert(values.head == values.last)
      assert(values.head.label.matches("g_generate_GenerateForFrontendTests_l[0-9]+"))
    } finally {
      executor.shutdownNow()
    }
  }

  test("rejects cross-thread concrete fallback during parameterized capture") {
    val lanes = HdlInt.param("LANES", default = 4, min = 1, max = 64)
    var workerError: FrontendException = null

    captureItems {
      val worker = new Thread(new Runnable {
        override def run(): Unit = {
          try {
            for (_ <- 0 until lanes) ()
          } catch {
            case error: FrontendException => workerError = error
          }
        }
      })
      worker.start()
      worker.join(10000)
      assert(!worker.isAlive)
      emitInstance(name = "lane_inst", moduleName = "PixelLane")
    }

    assert(workerError != null)
    assert(workerError.code == "MORPH-FRONTEND-SESSION-MISSING")

    val seen = ArrayBuffer.empty[BigInt]
    FrontendSession.concrete {
      for (lane <- 0 until lanes) seen += lane.witness
    }
    assert(seen.toVector == Vector[BigInt](0, 1, 2, 3))
  }

  test("preserves an unsafe symbolic count for ParamRTL to reject") {
    val lanes = HdlInt.param("LANES", default = 4, min = 0, max = 64)
    val items = captureItems {
      for (_ <- 0 until lanes) {
        emitInstance(name = "lane_inst", moduleName = "MissingLeaf")
      }
    }
    val module = moduleDef(
      name = "UnsafeCount",
      parameters = Vector(integerParameter(lanes)),
      ports = Vector.empty,
      items = items
    )

    ParamRtlValidator.validate(Design(module.name, Vector(module))) match {
      case Left(diagnostics) =>
        assert(
          diagnostics.codes.contains("PRTL-GENERATE-COUNT-NOT-PROVEN-POSITIVE"),
          diagnostics.values.mkString("\n")
        )
      case Right(_) => fail("expected the existing ParamRTL count proof to reject the design")
    }
  }
}
