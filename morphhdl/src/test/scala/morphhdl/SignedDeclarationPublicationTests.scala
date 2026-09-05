package spinal.core.internals

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.collection.JavaConverters._
import org.scalatest.funsuite.AnyFunSuite
import morphhdl.{MorphSignedDeclarations, MorphVerilog}
import morphhdl.frontend.HdlInt
import nativeapplication.{SIntSignedDeclarationsFixture => Fixture, SIntSignedDeclarationsArtifactWriter => Writer}
import nativeapplication.SIntSignedVerilogBaselineFixture
import spinal.core._
import VerilogBase._

final class SignedDeclarationPublicationTests extends AnyFunSuite {
  private def directory(body: Path => Unit): Unit = {
    val root = Files.createTempDirectory("signed-declaration-")
    try body(root) finally {
      val stream = Files.walk(root)
      try stream.iterator.asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists(_))
      finally stream.close()
    }
  }
  private def text(file: Path): String = new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
  private def width: HdlInt = HdlInt.param("WIDTH", default = 8, min = 1, max = 32)
  private def signedDeclaration(rtl: String, name: String): Boolean =
    ("(?m)^.*\\b(?:wire|reg)\\s+signed\\s+\\[[^\\]]+\\]\\s+" + name + "(?:\\s|[,;]).*$").r
      .findFirstIn(rtl).nonEmpty

  test("mode configuration is isolated, idempotent and explicitly disabled") {
    val original = SpinalConfig()
    val size = original.phasesInserters.size
    val enabled = MorphSignedDeclarations.enable(original)
    val twice = MorphSignedDeclarations.enable(enabled)
    assert(!MorphSignedDeclarations.isEnabled(original))
    assert(original.phasesInserters.size == size)
    assert(MorphSignedDeclarations.isEnabled(enabled))
    assert(twice.phasesInserters.size == enabled.phasesInserters.size)
    assert(!MorphSignedDeclarations.isEnabled(MorphSignedDeclarations.disable(enabled)))
    intercept[IllegalArgumentException](MorphSignedDeclarations.enable(null))
  }

  test("ordinary SpinalVerilog remains byte-identical even with the Morph-only option") {
    directory { root =>
      val path = root.resolve("ordinary.v")
      val config = Writer.config(path)
      SpinalVerilog(config)(new Fixture.Top(HdlInt.literal(5)))
      val before = text(path)
      SpinalVerilog(MorphSignedDeclarations.enable(config))(new Fixture.Top(HdlInt.literal(5)))
      assert(text(path) == before)
      SpinalVerilog(config)(new Fixture.Top(HdlInt.literal(5)))
      assert(text(path) == before)
    }
  }

  test("VHDL remains byte-identical with the Morph-only declaration option") {
    directory { root =>
      val path = root.resolve("ordinary.vhd")
      val config = Writer.config(path)
      SpinalVhdl(config)(new Fixture.Direct(HdlInt.literal(5)))
      val before = text(path)
      SpinalVhdl(MorphSignedDeclarations.enable(config))(new Fixture.Direct(HdlInt.literal(5)))
      assert(text(path) == before)
    }
  }

  test("fixed scalar declarations retain concrete widths inside parameterized publication") {
    directory { root =>
      for (bits <- Vector(1, 5, 8, 32)) {
        val path = root.resolve(s"fixed-$bits.v")
        MorphVerilog(MorphSignedDeclarations.enable(Writer.config(path)))(
          new Fixture.FixedScalars(bits, width))
        val rtl = text(path)
        assert(signedDeclaration(rtl, "a"))
        assert(signedDeclaration(rtl, "b"))
        assert(!signedDeclaration(rtl, "transportIn"))
        assert(rtl.contains("parameter integer WIDTH"))
        assert(("wire signed\\s+\\[" + (bits - 1) + ":0\\]\\s+a").r.findFirstIn(rtl).nonEmpty)
      }
    }
  }

  test("disabled Morph publication and later generations do not inherit signed mode") {
    directory { root =>
      val path = root.resolve("candidate.v")
      val config = Writer.config(path)
      MorphVerilog(config)(new Fixture.Top(width))
      val before = text(path)
      val enabled = MorphSignedDeclarations.enable(config)
      MorphVerilog(enabled)(new Fixture.Top(width))
      assert(signedDeclaration(text(path), "a"))
      MorphVerilog(MorphSignedDeclarations.disable(enabled))(new Fixture.Top(width))
      assert(text(path) == before)
      MorphVerilog(config)(new Fixture.Top(width))
      assert(text(path) == before)
    }
  }

  test("declarations retain casts, scalar memories and compound symbolic ranges") {
    directory { root =>
      val path = root.resolve("signed.v")
      MorphVerilog(MorphSignedDeclarations.enable(Writer.config(path)))(new Fixture.Top(width))
      val rtl = text(path)
      for (name <- Vector("a", "b", "sum", "product", "negative", "shifted", "selected",
          "widened", "regOut", "memOut", "accumulator", "scalar_memory"))
        assert(signedDeclaration(rtl, name), "missing signed scalar: " + name + "\n" + rtl)
      for (name <- Vector("raw", "rawOut", "packedBits", "logical", "unsignedProduct", "address", "amount"))
        assert(!signedDeclaration(rtl, name), "unsigned transport became signed: " + name)
      assert(rtl.contains("packedBits"))
      assert(rtl.contains("$signed("))
      assert(rtl.contains("WIDTH"))
      assert("(?m)^.*signed.*\\[.*WIDTH.*WIDTH.*\\].*product.*$".r.findFirstIn(rtl).nonEmpty)
    }
  }

  test("simple declaration canonicalization preserves signed sections") {
    directory { root =>
      val path = root.resolve("direct.v")
      MorphVerilog(MorphSignedDeclarations.enable(Writer.config(path)))(new Fixture.Direct(width))
      val rtl = text(path)
      assert(signedDeclaration(rtl, "a") && signedDeclaration(rtl, "b"))
      assert(!signedDeclaration(rtl, "bitsIn") && !signedDeclaration(rtl, "bitsOut"))
      assert(rtl.contains("WIDTH + 1") || rtl.contains("1 + WIDTH"))
    }
  }

  test("inout and scalar memory are signed but one-field Bundle memory is unsigned") {
    directory { root =>
      val path = root.resolve("surfaces.v")
      MorphVerilog(MorphSignedDeclarations.enable(Writer.config(path)))(new Fixture.Surfaces(width))
      val rtl = text(path)
      assert(signedDeclaration(rtl, "analogPort"))
      assert(signedDeclaration(rtl, "scalar_memory"))
      assert(signedDeclaration(rtl, "constantOutput"))
      MorphVerilog(MorphSignedDeclarations.enable(Writer.config(path)))(
        new Fixture.Surfaces(width, aggregateMemory = true))
      val packedRtl = text(path)
      assert(packedRtl.contains("bundle_memory"))
      assert(!signedDeclaration(packedRtl, "bundle_memory"))
      assert(signedDeclaration(packedRtl, "analogPort"))
    }
  }

  test("frozen 60a fixture retains every signed cast and external module ownership") {
    directory { root =>
      val path = root.resolve("baseline.v")
      MorphVerilog(Writer.config(path))(SIntSignedVerilogBaselineFixture.parameterized())
      val before = text(path)
      MorphVerilog(MorphSignedDeclarations.enable(Writer.config(path)))(SIntSignedVerilogBaselineFixture.parameterized())
      val after = text(path)
      assert("\\$signed\\(".r.findAllIn(after).size == "\\$signed\\(".r.findAllIn(before).size)
      assert(!after.contains("module SIntCastHeavyExternal"))
      assert(signedDeclaration(after, "left") && signedDeclaration(after, "signed_memory"))
    }
  }

  test("native constant-process function results and wrappers are signed") {
    directory { root =>
      val path = root.resolve("functions.v")
      MorphVerilog(Writer.config(path))(new Fixture.Functions(width))
      val before = text(path)
      MorphVerilog(MorphSignedDeclarations.enable(Writer.config(path)))(new Fixture.Functions(width))
      val after = text(path)
      assert(before.contains("function [4:0]"))
      assert(after.contains("function signed [4:0]"))
      assert(after.contains("(input dummy)"))
      assert(after.contains("wire signed [4:0]"))
      assert(signedDeclaration(after, "constantOutput"))
      assert(!signedDeclaration(after, "transportIn"))
    }
  }

  test("unsupported symbolic Bundle-memory reconstruction still fails closed in both modes") {
    directory { root =>
      for (enabled <- Vector(false, true)) {
        val config = Writer.config(root.resolve("unsupported.v"))
        val error = intercept[morphhdl.MorphVerilogException] {
          MorphVerilog(if (enabled) MorphSignedDeclarations.enable(config) else config)(
            new Fixture.SymbolicBundleMemory(width))
        }
        assert(error.getMessage.contains("DOMAIN") || error.getMessage.contains("INFERRED"),
          error.getMessage)
      }
    }
  }

  test("emitter occurrences reject foreign sessions, staleness and unplanned temporaries") {
    directory { root =>
      var input: SInt = null
      val config = Writer.config(root.resolve("identity.v"))
      config.phasesInserters += MorphHdlSignednessAnalysis.install { snapshot =>
        val printer = new VerilogBase {}
        val policy = new MorphHdlSignedDeclarationPolicy(printer, snapshot)
        printer.bindDeclarationPolicy(policy)
        assert(printer.emitType(input).startsWith("signed "))
        assert(printer.emitExpressionWrap(input, "local_copy").contains("signed "))
        val foreign = new VerilogBase {}
        foreign.bindDeclarationPolicy(policy)
        intercept[MorphHdlSignednessException](foreign.emitType(input))
        intercept[MorphHdlSignednessException](snapshot.temporary(input))
        intercept[IllegalArgumentException](printer.bindDeclarationPolicy(policy))
        val before = input.getWidth
        input.setWidth(before + 1)
        try intercept[MorphHdlSignednessException](printer.emitType(input))
        finally input.setWidth(before)
      }
      SpinalVerilog(config)(new Component {
        input = in(SInt(5 bits)).setName("signed_input")
        val copiedOut = out(SInt(5 bits))
        copiedOut := input
      })
    }
  }
}
