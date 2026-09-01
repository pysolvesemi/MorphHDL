package spinal.core

import java.nio.file.Files

import scala.collection.JavaConverters._

import org.scalatest.funsuite.AnyFunSuite

import spinal.lib.{Counter, StreamFifo}

/** Adversarial checks for the one central ElabInt consumption boundary. */
class CentralTypedAuthorityAdversarialTests extends AnyFunSuite {
  test("trusted concrete derivations normalize to canonical literal authority") {
    val derived = (ElabInt.literal(8) + 4) / 3

    assert(derived.expression.verilog == "4")
    assert(derived.expression.default == 4)
    assert(derived.expression.minimum == 4)
    assert(derived.expression.maximum == 4)
    assert(derived.bits.value == 4)
    assert(derived.slices.value == 4)

    val overflow = intercept[ParameterizedVerilogException] {
      ElabInt.literal(Int.MaxValue) + 1
    }
    assert(overflow.code == "SPINAL-ELAB-INT-DOMAIN-INVALID")
  }

  test("primitive entry points reject forged rootless and inexact constant carriers") {
    withSpinalElaboration {
      val wordType = HardType(Bits(8 bits))
      val resizeSource = Bits(8 bits)

      def forgedRootless: ElabInt =
        ElabInt.fromExpression(
          ElaborationIntegerExpression(
            verilog = "FORGED_ROOTLESS_DEPTH",
            default = 5,
            minimum = 5,
            maximum = 5,
            parameters = Vector.empty
          )
        )

      def inexactDomainConstant: ElabInt = {
        val schema = ElaborationIntegerParameter(
          "INEXACT_CONSTANT_DEPTH",
          default = 5,
          minimum = 1,
          maximum = 8
        )
        val root =
          ElaborationIntegerParameterRoot.fresh("INEXACT_CONSTANT_DEPTH")
        ElabInt.fromExpression(
          ElaborationIntegerExpression(
            verilog = "(INEXACT_CONSTANT_DEPTH - INEXACT_CONSTANT_DEPTH + 5)",
            default = 5,
            minimum = 5,
            maximum = 5,
            parameters = Vector(schema),
            parameterRoots = Vector(root)
          )
        )
      }

      def rejectEveryPrimitive(role: String, value: ElabInt): Unit = {
        var nativeVecGeneratorHits = 0
        var helperVecGeneratorHits = 0
        val operations = Vector[(String, () => Any)](
          "Mem" -> (() => Mem(wordType, value)),
          "Vec" -> (() =>
            Vec(
              {
                nativeVecGeneratorHits += 1
                Bits(8 bits)
              },
              value
            )
          ),
          "ParameterizedWidth.Vec" -> (() =>
            ParameterizedWidth.Vec(
              {
                helperVecGeneratorHits += 1
                Bits(8 bits)
              },
              value
            )
          ),
          "Counter" -> (() => Counter(value)),
          "Counter range" -> (() => Counter(value, value)),
          "Counter.down" -> (() => Counter.down(value)),
          "Counter.both" -> (() => Counter.both(value)),
          "StreamFifo" -> (() => StreamFifo(wordType, value)),
          "resize" -> (() => resizeSource.resize(value)),
          "slices" -> (() => value.slices)
        )

        operations.foreach { case (operation, run) =>
          withClue(s"$role $operation must fail closed: ") {
            intercept[ParameterizedVerilogException](run())
          }
        }
        assert(nativeVecGeneratorHits == 0)
        assert(helperVecGeneratorHits == 0)
      }

      rejectEveryPrimitive("forged rootless carrier", forgedRootless)
      rejectEveryPrimitive(
        "inexact symbolic domain-constant carrier",
        inexactDomainConstant
      )
    }
  }

  test("external Resize publication requires the exact target assignment and expression identities") {
    withSpinalElaboration {
      val parameter = ElaborationIntegerParameter(
        "LEGACY_RESIZE_WIDTH",
        default = 9,
        minimum = 1,
        maximum = 16
      )
      val width = ParameterizedBitCount(9, parameter)
      val source = Bits(8 bits)
      val target = ParameterizedWidth.attach(source.resize(9), width)
      val assignment = target.head
        .asInstanceOf[spinal.core.internals.DataAssignmentStatement]
      val resize = assignment.source.asInstanceOf[spinal.core.internals.Resize]
      val expression = ParameterizedWidth.expressionOf(target).get

      val copiedExpression = expression.copy()
      val copiedError = intercept[ParameterizedVerilogException] {
        ExternalParameterizedResizeRegistry.attach(
          resize,
          target,
          copiedExpression
        )
      }
      assert(
        copiedError.code ==
          "SPINAL-PARAMETERIZED-VERILOG-RESIZE-EXPRESSION-IDENTITY-MISMATCH"
      )
      assert(ExternalParameterizedResizeRegistry.expressionOf(resize).isEmpty)

      val foreignTarget = ParameterizedWidth.attach(source.resize(9), width)
      val foreignExpression = ParameterizedWidth.expressionOf(foreignTarget).get
      val targetError = intercept[ParameterizedVerilogException] {
        ExternalParameterizedResizeRegistry.attach(
          resize,
          foreignTarget,
          foreignExpression
        )
      }
      assert(
        targetError.code ==
          "SPINAL-PARAMETERIZED-VERILOG-RESIZE-TARGET-IDENTITY-MISMATCH"
      )
      assert(ExternalParameterizedResizeRegistry.expressionOf(resize).isEmpty)

      ExternalParameterizedResizeRegistry.attach(resize, target, expression)
      assert(
        ExternalParameterizedResizeRegistry
          .expressionOf(resize)
          .exists(_ eq expression)
      )

      val retryError = intercept[ParameterizedVerilogException] {
        ExternalParameterizedResizeRegistry.attach(
          resize,
          foreignTarget,
          foreignExpression
        )
      }
      assert(
        retryError.code ==
          "SPINAL-PARAMETERIZED-VERILOG-RESIZE-TARGET-IDENTITY-MISMATCH"
      )
      assert(
        ExternalParameterizedResizeRegistry
          .expressionOf(resize)
          .exists(_ eq expression)
      )
    }
  }

  private def withSpinalElaboration(body: => Unit): Unit = {
    val directory = Files.createTempDirectory("morphhdl-central-authority-")
    try {
      SpinalVerilog(
        SpinalConfig(
          targetDirectory = directory.toString,
          headerWithRepoHash = false,
          withTimescale = false,
          printFilelist = false
        )
      ) {
        new Component {
          val keep = out(Bool())
          keep := False
          body
        }
      }
    } finally {
      if (Files.exists(directory)) {
        val paths = Files.walk(directory)
        try {
          paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach { path =>
            Files.deleteIfExists(path)
          }
        } finally paths.close()
      }
    }
  }
}
