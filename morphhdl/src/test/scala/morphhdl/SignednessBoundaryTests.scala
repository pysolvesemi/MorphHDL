package spinal.core.internals

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer
import org.scalatest.funsuite.AnyFunSuite
import morphhdl.{MorphSignedCasts, MorphSignedDeclarations, MorphVerilog}
import morphhdl.frontend.{HdlBool, HdlInt}
import nativeapplication.{SIntSignedDeclarationsFixture, SIntSignedDeclarationsArtifactWriter}
import spinal.core._
import spinal.core.{SignednessBoundaryFixture => Fixture, SignednessBoundaryArtifactWriter => Writer}
import VerilogBase._

final class SignednessBoundaryTests extends AnyFunSuite {
  private def directory(body: Path => Unit): Unit = {
    val root = Files.createTempDirectory("signed-boundaries-")
    try body(root) finally {
      val stream = Files.walk(root)
      try stream.iterator.asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists(_))
      finally stream.close()
    }
  }
  private def text(file: Path): String = new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
  private def width: HdlInt = Writer.width
  private def emit(path: Path)(component: => Component): String = {
    MorphVerilog(MorphSignedCasts.enable(Writer.config(path)))(component)
    text(path)
  }
  private def signed(rtl: String, name: String): Boolean =
    ("(?m)^.*\\b(?:wire|reg)\\s+signed\\s+\\[[^\\]]+\\]\\s+" + name + "(?:\\s|[,;]).*$").r
      .findFirstIn(rtl).nonEmpty

  test("scalar boundary reconstruction is cast free without unsigned-consumer contamination") {
    directory { root =>
      val rtl = emit(root.resolve("scalars.v"))(new Fixture.Scalars(width, Writer.target))
      assert(!rtl.contains("$signed("), rtl)
      for (name <- Vector("a", "b", "mixedBits", "mixedUInt", "muxArithmetic", "signedShift", "selected"))
        assert(signed(rtl, name), name)
      for (name <- Vector("raw", "unsigned_1", "amount", "logicalBits", "logicalUInt"))
        assert(!signed(rtl, name), name)
      assert(rtl.contains("8'shff") && rtl.contains("5'sh1d"))
      assert(rtl.contains("assign leftShift = (a <<< amount);"))
      assert("wire\\s+\\[WIDTH-1:0\\]\\s+_zz_logicalUInt;".r.findFirstIn(rtl).nonEmpty)
    }
  }

  test("independent signed resize domains retain truncation before nested multiplication") {
    directory { root =>
      val rtl = emit(root.resolve("resize.v"))(new Fixture.Scalars(width, Writer.target))
      assert(rtl.contains("signed [TARGET-1:0] _zz_resizedProduct;"))
      assert(rtl.contains("signed [TARGET-1:0] _zz_resizedProduct_1;"))
      assert(rtl.contains("assign resizedProduct = (_zz_resizedProduct * _zz_resizedProduct_1);"))
      assert(rtl.contains("((TARGET > WIDTH) ? (TARGET - WIDTH) : 0)"))
      assert(rtl.contains("[((TARGET < WIDTH) ? TARGET : WIDTH)-1:0]"))
      assert(rtl.contains("assign crossedFixed = {{((5 > WIDTH) ? (5 - WIDTH) : 0)"))
    }
  }

  test("fixed and symbolic signed widening is enabled only in boundary mode") {
    directory { root =>
      val rtl = emit(root.resolve("wide.v"))(new Component {
        val a = in(SInt(width bits))
        val widened = out(SInt(64 bits))
        widened := a.resize(64)
      })
      assert(signed(rtl, "widened"))
      assert(rtl.contains("a[WIDTH-1]"))
      assert(rtl.contains("a[WIDTH-1:0]"))
    }
  }

  test("constant-function result and call wire have the same exact symbolic signed range") {
    directory { root =>
      val rtl = emit(root.resolve("function.v"))(new Fixture.Scalars(width, Writer.target))
      assert(rtl.contains("function signed [(WIDTH + 1)-1:0] zz_constantFunction(input dummy);"))
      assert(rtl.contains("wire signed [(WIDTH + 1)-1:0] _zz_1;"))
      assert(rtl.contains("zz_constantFunction = 9'sh1fe;"))
    }
  }

  test("mixed Bundle leaf ports remain independently typed while packed transport is unsigned") {
    directory { root =>
      val rtl = emit(root.resolve("bundle.v"))(new Fixture.Bundles(width))
      assert(signed(rtl, "incoming_value") && signed(rtl, "outgoing_value"))
      assert(!signed(rtl, "incoming_raw") && !signed(rtl, "outgoing_raw") && !signed(rtl, "packed"))
      assert(!rtl.contains("$signed("))
    }
  }

  test("signed Vec dynamic and static reads reconstruct leaves from unsigned packed carriers") {
    directory { root =>
      val rtl = emit(root.resolve("vec.v"))(new Fixture.Vectors(width, Writer.depth))
      assert(rtl.contains("input wire [(WIDTH * DEPTH)-1:0] packedIn"))
      assert(rtl.contains("output wire [(WIDTH * DEPTH)-1:0] packedOut"))
      assert(rtl.contains("reg [(WIDTH * DEPTH)-1:0] updated;"))
      assert(!"signed\\s+\\[\\(WIDTH \\* DEPTH\\)".r.findFirstIn(rtl).nonEmpty)
      assert(rtl.contains("wire signed [(WIDTH)-1:0] updated_0;"))
      assert(rtl.contains("assign updated_0 = updated[(0) +: WIDTH];"))
      assert(rtl.contains("assign first = (updated_0 >>> 1);"))
      assert(rtl.contains("$signed(updated["))
    }
  }

  test("parent internal Vec bridges and child input ports retain exact typed hierarchy and deduplication") {
    directory { root =>
      val rtl = emit(root.resolve("vec-hierarchy.v"))(new Fixture.VecHierarchy(width, Writer.depth))
      assert("\\bmodule SignedBoundaryVecChild\\b".r.findAllIn(rtl).size == 1)
      assert(rtl.contains("wire [(WIDTH * DEPTH)-1:0] middle;"))
      assert(rtl.contains(".incoming (middle)" ) || "\\.incoming\\s*\\(middle\\)".r.findFirstIn(rtl).nonEmpty)
      assert(rtl.contains("$signed(incoming["))
    }
  }

  test("canonical scalar children coexist with typed external generic binding without rewriting external ports") {
    directory { root =>
      val rtl = emit(root.resolve("hierarchy.v"))(new Fixture.Hierarchy(width, HdlBool.param("ENABLED", default = true)))
      assert("\\bmodule SignedBoundaryChild\\b".r.findAllIn(rtl).size == 1)
      assert(!rtl.contains("module SignedBoundaryExternal"))
      assert(signed(rtl, "externalOut"))
      assert(rtl.contains("assign externalShift = (externalOut >>> 1);"))
      for (name <- Vector("LABEL", "WIDTH", "COUNT", "DOUBLE_WIDTH", "ENABLED"))
        assert(("\\." + name + "\\s*\\(").r.findFirstIn(rtl).nonEmpty)
    }
  }

  test("Stream and Flow payload registers retain SInt leaves but not control or Bits fields") {
    directory { root =>
      val rtl = emit(root.resolve("channels.v"))(new Fixture.Channels(width))
      for (name <- Vector("push_payload_value", "pop_payload_value", "flowIn_payload", "flowOut_payload", "push_rData_value"))
        assert(signed(rtl, name), name)
      for (name <- Vector("push_payload_raw", "pop_payload_raw", "push_rData_raw", "push_ready"))
        assert(!signed(rtl, name), name)
    }
  }

  test("Mem SInt remains signed and a one-field Bundle memory remains unsigned") {
    directory { root =>
      val scalar = emit(root.resolve("scalar.v"))(new SIntSignedDeclarationsFixture.Surfaces(width))
      val aggregate = emit(root.resolve("aggregate.v"))(new SIntSignedDeclarationsFixture.Surfaces(width, aggregateMemory = true))
      assert(signed(scalar, "scalar_memory"))
      assert(!signed(aggregate, "bundle_memory"))
    }
  }

  test("boundary fixtures are deterministic and unrelated module names do not affect typing") {
    directory { root =>
      def component(): Fixture.Bundles = {
        val c = new Fixture.Bundles(width)
        c.setDefinitionName("UnrelatedLeafTransport")
        c
      }
      assert(emit(root.resolve("a.v"))(component()) == emit(root.resolve("b.v"))(component()))
    }
  }

  test("ordinary native Verilog and VHDL do not inherit signed boundary hooks") {
    directory { root =>
      val path = root.resolve("native.v")
      val config = Writer.config(path)
      SpinalVerilog(config)(new Fixture.Scalars(HdlInt.literal(5), HdlInt.literal(8)))
      val old = text(path)
      SpinalVerilog(MorphSignedCasts.enable(config))(new Fixture.Scalars(HdlInt.literal(5), HdlInt.literal(8)))
      assert(text(path) == old)
      val vhdl = root.resolve("native.vhd")
      SpinalVhdl(Writer.config(vhdl))(new Fixture.Bundles(HdlInt.literal(5)))
      val oldVhdl = text(vhdl)
      SpinalVhdl(MorphSignedCasts.enable(Writer.config(vhdl)))(new Fixture.Bundles(HdlInt.literal(5)))
      assert(text(vhdl) == oldVhdl)
    }
  }

  test("mode-off parameterized generation is byte-stable before and after boundary publication") {
    directory { root =>
      val path = root.resolve("same.v")
      val config = Writer.config(path)
      MorphVerilog(config)(new Fixture.Bundles(width))
      val old = text(path)
      emit(path)(new Fixture.Bundles(width))
      MorphVerilog(MorphSignedDeclarations.disable(MorphSignedCasts.enable(config)))(new Fixture.Bundles(width))
      assert(text(path) == old)
    }
  }

  test("Bits subclass named SInt cannot sign a Vec carrier or its selected result") {
    directory { root =>
      val rtl = emit(root.resolve("spoof.v"))(new Component {
        val vector = in(Vec(adversarial.vecfake.SInt(8), Writer.depth)).setName("vector")
        val index = in(UInt(3 bits))
        val result = out(Bits(8 bits))
        result := vector(index)
      })
      assert(!rtl.contains(" signed ") && !rtl.contains("$signed("))
    }
  }

  test("literal and resize occurrences reject foreign emitters clones and stale geometry") {
    directory { root =>
      var literalChecked = false
      var resizeChecked = false
      val config = Writer.config(root.resolve("native-hooks.v"))
      config.phasesInserters += { phases: ArrayBuffer[Phase] =>
        val emitter = phases.collect { case value: PhaseVerilog => value }.head
        MorphHdlSignednessAnalysis.install { snapshot =>
          val delegate = new MorphHdlSignedDeclarationPolicy(emitter, snapshot, true)
          val foreign = new MorphHdlSignedDeclarationPolicy(new VerilogBase {}, snapshot, true)
          emitter.bindDeclarationPolicy(new DeclarationPolicy {
            override def signed(o: DeclarationOccurrence): Boolean = delegate.signed(o)
            override def wrapperRange(o: DeclarationOccurrence): Option[String] = delegate.wrapperRange(o)
            override def functionRange(o: DeclarationOccurrence): Option[String] = delegate.functionRange(o)
            override def unsignedTransport(e: Expression): Boolean = delegate.unsignedTransport(e)
            override def elideSignedCast(o: SignedCastOccurrence): Boolean = delegate.elideSignedCast(o)
            override def signedLiteral(o: SignedLiteralOccurrence): Boolean = {
              val result = delegate.signedLiteral(o)
              if (result && !literalChecked) {
                literalChecked = true
                intercept[MorphHdlSignednessException](foreign.signedLiteral(o))
                intercept[MorphHdlSignednessException](emitter.literalIsSigned(o.literal.clone()))
                val value = o.literal.value
                o.literal.value = value + 1
                try intercept[MorphHdlSignednessException](delegate.signedLiteral(o))
                finally o.literal.value = value
                val poison = o.literal.poisonMask
                o.literal.poisonMask = BigInt(1)
                try intercept[MorphHdlSignednessException](delegate.signedLiteral(o))
                finally o.literal.poisonMask = poison
              }
              result
            }
            override def signedResize(o: SignedResizeOccurrence): Option[String] = {
              val result = delegate.signedResize(o)
              if (!resizeChecked) {
                resizeChecked = true
                intercept[MorphHdlSignednessException](foreign.signedResize(o))
                val size = o.resize.size
                o.resize.size = size + 1
                try intercept[MorphHdlSignednessException](delegate.signedResize(o))
                finally o.resize.size = size
              }
              result
            }
          })
        }(phases)
      }
      SpinalVerilog(config)(new Fixture.Scalars(HdlInt.literal(5), HdlInt.literal(8)))
      assert(literalChecked && resizeChecked)
    }
  }
}
