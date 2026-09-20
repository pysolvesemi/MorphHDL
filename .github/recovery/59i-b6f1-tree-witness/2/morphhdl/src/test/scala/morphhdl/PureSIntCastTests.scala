package spinal.core.internals

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer
import org.scalatest.funsuite.AnyFunSuite
import morphhdl.{MorphSignedCasts, MorphSignedDeclarations, MorphVerilog}
import morphhdl.frontend.HdlInt
import nativeapplication.{PureSIntCastFixture => Fixture, PureSIntCastArtifactWriter => Writer}
import nativeapplication.{SIntSignedDeclarationsFixture, SIntSignedVerilogBaselineFixture}
import spinal.core._
import VerilogBase._

final class PureSIntCastTests extends AnyFunSuite {
  private def directory(body: Path => Unit): Unit = {
    val root = Files.createTempDirectory("pure-sint-casts-")
    try body(root) finally {
      val stream = Files.walk(root)
      try stream.iterator.asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists(_))
      finally stream.close()
    }
  }
  private def text(file: Path): String = new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
  private def width: HdlInt = HdlInt.param("WIDTH", default = 8, min = 1, max = 32)
  private def casts(rtl: String): Int = "\\$signed\\(".r.findAllIn(rtl).size
  private def clean(path: Path): String = {
    MorphVerilog(MorphSignedCasts.enable(Writer.config(path)))(new Fixture.Top(width))
    text(path)
  }

  test("cleanup configuration is isolated idempotent and separately disabled") {
    val original = SpinalConfig()
    val enabled = MorphSignedCasts.enable(original)
    assert(!MorphSignedCasts.isEnabled(original))
    assert(!MorphSignedDeclarations.isEnabled(original))
    assert(MorphSignedCasts.isEnabled(enabled))
    assert(MorphSignedDeclarations.isEnabled(enabled))
    assert(MorphSignedCasts.enable(enabled).phasesInserters.size == enabled.phasesInserters.size)
    val declarations = MorphSignedCasts.disable(enabled)
    assert(!MorphSignedCasts.isEnabled(declarations))
    assert(MorphSignedDeclarations.isEnabled(declarations))
    assert(!MorphSignedCasts.isEnabled(MorphSignedDeclarations.disable(enabled)))
    assert(!MorphSignedCasts.isEnabled(null))
    intercept[IllegalArgumentException](MorphSignedCasts.enable(null))
    intercept[IllegalArgumentException](MorphSignedCasts.disable(null))
  }

  test("ordinary SpinalVerilog including nested operations stays byte identical") {
    directory { root =>
      val path = root.resolve("ordinary.v")
      val config = Writer.config(path)
      SpinalVerilog(config)(new Fixture.Top(HdlInt.literal(5)))
      val before = text(path)
      SpinalVerilog(MorphSignedCasts.enable(config))(new Fixture.Top(HdlInt.literal(5)))
      assert(text(path) == before)
      assert(casts(before) > 20)
    }
  }

  test("ordinary VHDL stays byte identical with the cleanup marker installed") {
    directory { root =>
      val path = root.resolve("ordinary.vhd")
      val config = Writer.config(path)
      SpinalVhdl(config)(new SIntSignedDeclarationsFixture.Direct(HdlInt.literal(5)))
      val before = text(path)
      SpinalVhdl(MorphSignedCasts.enable(config))(new SIntSignedDeclarationsFixture.Direct(HdlInt.literal(5)))
      assert(text(path) == before)
    }
  }

  test("declaration-only output retains casts and cleanup never leaks across generations") {
    directory { root =>
      val path = root.resolve("same.v")
      val config = Writer.config(path)
      MorphVerilog(config)(new Fixture.Top(width))
      val disabled = text(path)
      MorphVerilog(MorphSignedDeclarations.enable(config))(new Fixture.Top(width))
      val declarations = text(path)
      assert(casts(declarations) == casts(disabled))
      assert(clean(path) != declarations)
      MorphVerilog(MorphSignedCasts.disable(MorphSignedCasts.enable(config)))(new Fixture.Top(width))
      assert(text(path) == declarations)
      MorphVerilog(config)(new Fixture.Top(width))
      assert(text(path) == disabled)
    }
  }

  test("all pure signed arithmetic comparisons shifts and nested combinations are cast free") {
    directory { root =>
      val rtl = clean(root.resolve("pure.v"))
      assert(casts(rtl) == 0, rtl)
      for (operator <- Vector(" + ", " - ", " * ", " / ", " % ", " >>> ", " < ", " <= "))
        assert(rtl.contains(operator), "missing exercised operation " + operator)
      assert(rtl.contains("assign negative = (- a);"))
      assert(rtl.contains("assign shiftConstant = (a >>> 1);"))
      assert(rtl.contains("assign shiftVariable = (a >>> amount);"))
    }
  }

  test("native intermediate width boundaries and parenthesization are preserved") {
    directory { root =>
      val rtl = clean(root.resolve("pure.v"))
      assert("wire\\s+signed\\s+\\[WIDTH-1:0\\]\\s+local_sum;".r.findFirstIn(rtl).nonEmpty)
      assert(rtl.contains("assign local_sum = (a + b);"))
      assert(rtl.contains("assign nestedProduct = (local_sum * c);"))
      assert(rtl.contains("signed [(WIDTH + WIDTH)-1:0]"))
      assert(rtl.contains("signed [((WIDTH + WIDTH) + WIDTH)-1:0]") ||
        rtl.contains("signed [(WIDTH + WIDTH + WIDTH)-1:0]"))
      assert(!rtl.contains("assign nestedProduct = ((a + b) * c)"))
    }
  }

  test("60e reconstructs boundary atoms before removing their redundant casts") {
    directory { root =>
      val path = root.resolve("boundaries.v")
      MorphVerilog(MorphSignedCasts.enable(Writer.config(path)))(new Fixture.Boundaries(width))
      val rtl = text(path)
      for (name <- Vector("literalSum", "mixedSum", "muxSum", "widenedSum", "concatenatedSum", "selectedSum", "equal")) {
        val line = rtl.linesIterator.find(_.contains("assign " + name + " =")).get
        assert(!line.contains("$signed("), name + " retains a redundant cast around a signed atom")
      }
      assert(rtl.contains("5'sh1f"))
      assert("wire\\s+\\[WIDTH-1:0\\]\\s+_zz_unsignedShift;".r.findFirstIn(rtl).nonEmpty)
      assert(!rtl.contains("$signed($signed("))
    }
  }

  test("sealed full baseline becomes cleaner without nested casts or changing external ownership") {
    directory { root =>
      val path = root.resolve("baseline.v")
      MorphVerilog(MorphSignedDeclarations.enable(Writer.config(path)))(SIntSignedVerilogBaselineFixture.parameterized())
      val before = text(path)
      MorphVerilog(MorphSignedCasts.enable(Writer.config(path)))(SIntSignedVerilogBaselineFixture.parameterized())
      val after = text(path)
      assert(casts(after) == 0 && casts(before) > 0)
      assert(!"\\$signed\\(\\s*\\$signed\\(".r.findFirstIn(after).nonEmpty)
      assert(!after.contains("module SIntCastHeavyExternal"))
    }
  }

  test("fresh cleanup generation is deterministic") {
    directory { root => assert(clean(root.resolve("a.v")) == clean(root.resolve("b.v"))) }
  }

  test("unsupported cutLongExpressions false is not relaxed by cleanup") {
    directory { root =>
      val error = intercept[morphhdl.MorphVerilogException] {
        MorphVerilog(MorphSignedCasts.enable(Writer.config(root.resolve("bad.v"), cut = false)))(new Fixture.Top(width))
      }
      assert(error.getMessage.contains("cutLongExpressions"))
      assert(!Files.exists(root.resolve("bad.v")))
    }
  }

  test("declaration-only symbolic signed widening preserves exact sign extension") {
    directory { root =>
      val report = MorphVerilog(MorphSignedDeclarations.enable(Writer.config(root.resolve("grow.v"))))(new Component {
        setDefinitionName("DeclarationOnlySignedGrow")
        val source = in(SInt(width bits))
        val observed = out(SInt(64 bits))
        observed := source.resize(64)
      })
      morphhdl.NativeResizeCompatibilitySimulation.check(root, "grow.v",
        report.toplevelName, "WIDTH", Vector(1, 5, 8).map(value => (value, value, 64)),
        signedSource = true)
    }
  }

  test("native cast occurrences validate edge identity session and stale type evidence") {
    directory { root =>
      var checked = false
      val config = Writer.config(root.resolve("occurrences.v"))
      config.phasesInserters += { phases: ArrayBuffer[Phase] =>
        val emitter = phases.collect { case value: PhaseVerilog => value }.head
        MorphHdlSignednessAnalysis.install { snapshot =>
          val delegate = new MorphHdlSignedDeclarationPolicy(emitter, snapshot, true)
          emitter.bindDeclarationPolicy(new DeclarationPolicy {
            override def signed(o: DeclarationOccurrence): Boolean = delegate.signed(o)
            override def wrapperRange(o: DeclarationOccurrence): Option[String] = delegate.wrapperRange(o)
            override def unsignedTransport(e: Expression): Boolean = delegate.unsignedTransport(e)
            override def elideSignedCast(o: SignedCastOccurrence): Boolean = {
              val allowed = delegate.elideSignedCast(o)
              if (!checked && allowed && o.referenceRole.contains(ScalarDeclaration)) {
                checked = true
                val scalar = o.operand.asInstanceOf[SInt]
                val foreign = new VerilogBase {}
                val foreignPolicy = new MorphHdlPureSIntCastPolicy(foreign, snapshot)
                intercept[MorphHdlSignednessException](foreignPolicy.elide(o))
                intercept[IllegalArgumentException](foreign.canElideSignedCast(o.printer, o.parent, o.slot, o.operand))
                intercept[MorphHdlSignednessException](emitter.canElideSignedCast(o.printer, o.parent, 99, o.operand))
                val other = o.parent.asInstanceOf[BinaryOperator].right
                if (other ne scalar)
                  intercept[MorphHdlSignednessException](emitter.canElideSignedCast(o.printer, o.parent, o.slot, other))
                val bits = scalar.getWidth
                scalar.setWidth(bits + 1)
                try intercept[MorphHdlSignednessException](delegate.elideSignedCast(o))
                finally scalar.setWidth(bits)
              }
              allowed
            }
          })
        }(phases)
      }
      SpinalVerilog(config)(new Fixture.Top(HdlInt.literal(5)))
      assert(checked)
    }
  }

  test("overridden references and unmaterialized inline expressions never authorize cleanup") {
    directory { root =>
      var scalarChecked = false
      var wrapperChecked = false
      val config = Writer.config(root.resolve("references.v"))
      config.phasesInserters += { phases: ArrayBuffer[Phase] =>
        val emitter = phases.collect { case value: PhaseVerilog => value }.head
        MorphHdlSignednessAnalysis.install { snapshot =>
          val delegate = new MorphHdlSignedDeclarationPolicy(emitter, snapshot, true)
          emitter.bindDeclarationPolicy(new DeclarationPolicy {
            override def signed(o: DeclarationOccurrence): Boolean = delegate.signed(o)
            override def wrapperRange(o: DeclarationOccurrence): Option[String] = delegate.wrapperRange(o)
            override def unsignedTransport(e: Expression): Boolean = delegate.unsignedTransport(e)
            override def elideSignedCast(o: SignedCastOccurrence): Boolean = {
              val allowed = delegate.elideSignedCast(o)
              if (allowed && !scalarChecked && o.referenceRole.contains(ScalarDeclaration)) {
                scalarChecked = true
                val scalar = o.operand.asInstanceOf[SInt]
                o.printer.referencesOverrides(scalar) = scalar
                try assert(!delegate.elideSignedCast(o))
                finally o.printer.referencesOverrides.remove(scalar)
              }
              if (allowed && !wrapperChecked && o.referenceRole.contains(ExpressionWrapper)) {
                wrapperChecked = true
                val saved = o.printer.wrappedExpressionToName.remove(o.operand).get
                try assert(!delegate.elideSignedCast(o))
                finally o.printer.wrappedExpressionToName(o.operand) = saved
              }
              allowed
            }
          })
        }(phases)
      }
      SpinalVerilog(config)(new Fixture.Top(HdlInt.literal(5)))
      assert(scalarChecked && wrapperChecked)
    }
  }
}
