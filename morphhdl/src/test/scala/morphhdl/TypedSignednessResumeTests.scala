package spinal.core.internals

import java.nio.file.{Files, Path}
import scala.collection.JavaConverters._
import org.scalatest.funsuite.AnyFunSuite
import morphhdl.MorphVerilog
import morphhdl.frontend.HdlInt
import morphhdl.analysis.SignednessFacts._
import spinal.core._
import MorphHdlSignednessAnalysis._
import nativeapplication.SIntSignedVerilogBaselineFixture

/** Resume regressions must run alongside, never instead of, the original suite. */
final class TypedSignednessResumeTests extends AnyFunSuite {
  private def directory(body: Path => Unit): Unit = {
    val root = Files.createTempDirectory("signedness-resume-")
    try body(root)
    finally {
      val stream = Files.walk(root)
      try stream.iterator.asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists(_))
      finally stream.close()
    }
  }
  private def rejected(code: String)(body: => Any): Unit = {
    val error = intercept[MorphHdlSignednessException](body)
    assert(error.code == "MORPH-SIGNEDNESS-" + code)
  }

  test("an inferred declaration witness cannot impersonate exact parameterized width authority") {
    directory { root =>
      var dut: SIntSignedVerilogBaselineFixture.Top = null
      val config = SpinalConfig(targetDirectory = root.toString)
      config.phasesInserters += install { snapshot =>
        val subject = dut.product
        assert(!subject.isFixedWidth)
        assert(ParameterizedWidth.expressionOf(subject).isEmpty)
        val evidence = snapshot.declaration(subject)
        val f = snapshot.validate(subject, evidence, DeclarationUse)
        assert(f.value == SignedScalar)
        assert(f.nativeBits == 16)
        assert(f.width == UnknownWidth)
        assert(f.requirements.contains(InferredWidthAuthority))
        rejected("UNKNOWN-FACT")(snapshot.requireKnown(subject, evidence, DeclarationUse))
      }
      MorphVerilog(morphhdl.MorphSignedDeclarations.disable(config)) { dut = SIntSignedVerilogBaselineFixture.parameterized(); dut }
    }
  }

  test("replay includes typed width domains without depending on parameter or signal spelling") {
    def run(root: Path, maximum: Int, parameterName: String): String = {
      var result = ""
      val config = SpinalConfig(targetDirectory = root.toString)
      config.phasesInserters += install(snapshot => result = snapshot.replay)
      SpinalVerilog(config) {
        val width = HdlInt.param(parameterName, 8, 1, maximum)
        new Component {
          val a = in(SInt(width bits))
          val b = out(SInt(width bits))
          b := a
        }
      }
      result
    }
    directory { root =>
      val first = run(root.resolve("a"), 16, "FIRST_WIDTH")
      assert(first == run(root.resolve("b"), 16, "OTHER_WIDTH"))
      assert(first != run(root.resolve("c"), 32, "FIRST_WIDTH"))
      assert(!first.contains("FIRST_WIDTH"))
    }
  }

  test("a later phase inserter cannot bypass the validated capture boundary") {
    directory { root =>
      for (mutation <- Vector("remove-check", "move-observer", "separate-emitter", "duplicate-observer")) {
        val target = root.resolve(mutation)
        var called = false
        val config = SpinalConfig(targetDirectory = target.toString)
        config.phasesInserters += install(_ => called = true)
        config.phasesInserters += { phases =>
          mutation match {
            case "remove-check" => phases.remove(phases.indexWhere(_.getClass == classOf[PhaseCheckCrossClock]))
            case "move-observer" =>
              val emission = phases.indexWhere(_.isInstanceOf[PhaseVerilog])
              val observer = phases.remove(emission - 1)
              phases.insert(phases.indexWhere(_.getClass == classOf[PhaseCheckCrossClock]), observer)
            case "separate-emitter" =>
              phases.insert(phases.indexWhere(_.isInstanceOf[PhaseVerilog]), new PhaseMisc {
                override def impl(pc: PhaseContext): Unit = ()
              })
            case "duplicate-observer" => install(_ => ())(phases)
          }
        }
        rejected("PHASE-PLAN") {
          SpinalVerilog(config)(new Component {
            val a = in(Bool())
            val b = out(Bool())
            b := a
          })
        }
        assert(!called)
        if (Files.exists(target)) {
          val files = Files.walk(target)
          try assert(!files.iterator.asScala.exists(_.toString.endsWith(".v"))) finally files.close()
        }
      }
    }
  }


  test("memory templates cannot obtain declaration, expression or temporary occurrence evidence") {
    directory { root =>
      var dut: SIntSignedVerilogBaselineFixture.Top = null
      val config = SpinalConfig(targetDirectory = root.toString)
      config.phasesInserters += install { snapshot =>
        val memory = dut.sequential.signedMemory
        val template = memory.wordTypeLeaves.head
        assert(snapshot.validate(memory, snapshot.memoryElement(memory), MemoryElementUse).value == SignedScalar)
        rejected("USE-ROLE")(snapshot.declaration(template))
        rejected("USE-ROLE")(snapshot.expression(template))
        rejected("USE-ROLE")(snapshot.temporary(template))
        assert(snapshot.requireKnown(dut.left, snapshot.declaration(dut.left), DeclarationUse).value == SignedScalar)
        rejected("USE-ROLE")(snapshot.temporary(dut.left))
      }
      SpinalVerilog(config) { dut = SIntSignedVerilogBaselineFixture.fixed(); dut }
    }
  }

  test("expression-only snapshots cannot manufacture declaration or unplanned temporary roles") {
    directory { root =>
      var input: SInt = null
      val config = SpinalConfig(targetDirectory = root.toString)
      config.phasesInserters += install { snapshot =>
        val expressionSnapshot = expressions(Vector(input))
        val ref = expressionSnapshot.expression(input)
        assert(expressionSnapshot.requireKnown(input, ref, ExpressionUse).value == SignedScalar)
        rejected("USE-ROLE")(expressionSnapshot.declaration(input))
        rejected("USE-ROLE")(expressionSnapshot.temporary(input))
        assert(snapshot.requireKnown(input, snapshot.declaration(input), DeclarationUse).value == SignedScalar)
        assert(snapshot.replay != expressionSnapshot.replay)
      }
      SpinalVerilog(config)(new Component {
        val a = in(SInt(8 bits)); val b = out(SInt(8 bits))
        b := a
        input = a
      })
    }
  }

}
