package spinal.core.internals

import java.nio.file.Files
import morphhdl.frontend.HdlInt
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

private[internals] final class NativeWidthPublicationSafetyFixture(width: HdlInt) extends Component {
  val source = in(UInt(width bits)).setName("source")
  val foreign = in(UInt(width bits)).setName("foreign")
  val high = source.msb.setName("high")
  val resized = source.resize(8).setName("resized")
  val equalResize = source.resize(5).setName("equalResize")
  val highOutput = out Bool()
  val resizeOutput = out UInt(8 bits)
  val equalOutput = out UInt(5 bits)
  highOutput := high
  resizeOutput := resized
  equalOutput := equalResize
}

class NativeWidthPublicationSafetyTests extends AnyFunSuite {
  private def inspect(body: NativeWidthPublicationSafetyFixture => Unit): Unit = {
    val width = HdlInt.param("WIDTH", 5, 1, 8)
    val config = SpinalConfig(
      targetDirectory = Files.createTempDirectory("native-width-safety-").toString,
      headerWithDate = false, headerWithRepoHash = false)
    config.phasesInserters += { phases =>
      ExternalParameterizedHighBit.install(phases)
      ExternalParameterizedNativeResize.install(phases)
      val boundary = phases.indexWhere(_.isInstanceOf[PhaseRemoveIntermediateUnnameds])
      assert(boundary >= 0)
      phases.insert(boundary, new PhaseMisc {
        override def impl(pc: PhaseContext): Unit =
          body(pc.topLevel.asInstanceOf[NativeWidthPublicationSafetyFixture])
      })
    }
    config.generateVerilog(new NativeWidthPublicationSafetyFixture(width))
  }

  private def highAccess(fixture: NativeWidthPublicationSafetyFixture): BitVectorBitAccessFixed =
    fixture.high.head.source.asInstanceOf[BitVectorBitAccessFixed]

  private def resizeNode(value: UInt): Resize = value.head.source.asInstanceOf[Resize]

  private def expectLineage(body: => String): Unit = {
    val error = intercept[ParameterizedVerilogException](body)
    assert(error.code.contains("LINEAGE-MISMATCH"), error.getMessage)
  }

  test("protected high-bit and resize edges reject same-width foreign source substitution") {
    inspect { fixture =>
      val access = highAccess(fixture)
      val resize = resizeNode(fixture.resized)
      assert(ExternalParameterizedHighBit.proves(fixture, access))
      assert(ExternalParameterizedNativeResize.proves(fixture, resize))
      access.source = fixture.foreign
      assert(!ExternalParameterizedHighBit.proves(fixture, access))
      access.source = fixture.source
      access.bitId -= 1
      assert(!ExternalParameterizedHighBit.proves(fixture, access))
      access.bitId += 1
      resize.input = fixture.foreign
      assert(!ExternalParameterizedNativeResize.proves(fixture, resize))
      resize.input = fixture.source
      assert(ExternalParameterizedHighBit.proves(fixture, access))
      assert(ExternalParameterizedNativeResize.proves(fixture, resize))
    }
  }

  test("retained assignments and declarations cannot borrow another scope in the same component") {
    inspect { fixture =>
      val access = highAccess(fixture)
      val resize = resizeNode(fixture.resized)
      val borrowed = new ScopeStatement(null)
      borrowed.component = fixture
      val values: Vector[Statement] = Vector(fixture.high.head, fixture.high,
        fixture.resized.head, fixture.resized, fixture.source)
      values.foreach { value =>
        val original = value.parentScope
        value.parentScope = borrowed
        if ((value eq fixture.high.head) || (value eq fixture.high) || (value eq fixture.source))
          assert(!ExternalParameterizedHighBit.proves(fixture, access))
        if ((value eq fixture.resized.head) || (value eq fixture.resized) || (value eq fixture.source))
          assert(!ExternalParameterizedNativeResize.proves(fixture, resize))
        value.parentScope = original
      }
      assert(ExternalParameterizedHighBit.proves(fixture, access))
      assert(ExternalParameterizedNativeResize.proves(fixture, resize))
    }
  }

  test("pruned protected declarations cannot retain publication authority") {
    inspect { fixture =>
      val access = highAccess(fixture)
      val resize = resizeNode(fixture.resized)
      val scope = fixture.source.parentScope
      fixture.source.removeStatement()
      assert(!ExternalParameterizedHighBit.proves(fixture, access))
      assert(!ExternalParameterizedNativeResize.proves(fixture, resize))
      scope.prepend(fixture.source)
      assert(ExternalParameterizedHighBit.proves(fixture, access))
      assert(ExternalParameterizedNativeResize.proves(fixture, resize))
    }
  }

  test("only the exact equal-width resize normalization retains its captured target geometry") {
    inspect { fixture =>
      val assignment = fixture.equalResize.head.asInstanceOf[DataAssignmentStatement]
      val resize = assignment.source.asInstanceOf[Resize]
      assert(ExternalParameterizedNativeResize.proves(fixture, resize))
      assignment.source = fixture.source
      assert(ExternalParameterizedNativeResize.proves(fixture, resize))
      assert(ExternalParameterizedNativeResize.targetWidthOf(fixture, fixture.equalResize).get.default == 5)
      assignment.source = fixture.foreign
      assert(!ExternalParameterizedNativeResize.proves(fixture, resize))
      assignment.source = resize
    }
  }

  test("emitted assignment collisions or wrong witness edges cannot choose a rewrite target") {
    inspect { fixture =>
      val highText = "assign high = source[4];"
      val resizeText = "assign resized = {3'd0, source};\nassign equalResize = source;"
      assert(ExternalParameterizedHighBit.rewrite(fixture, highText).contains("WIDTH"))
      assert(ExternalParameterizedNativeResize.rewrite(fixture, resizeText)
        .contains("1'b0"))
      expectLineage(ExternalParameterizedHighBit.rewrite(fixture, highText + "\n" + highText))
      expectLineage(ExternalParameterizedHighBit.rewrite(fixture, "assign high = foreign[4];"))
      expectLineage(ExternalParameterizedNativeResize.rewrite(fixture,
        resizeText + "\nassign resized = {3'd0, source};"))
      expectLineage(ExternalParameterizedNativeResize.rewrite(fixture,
        resizeText.replace("{3'd0, source}", "{3'd0, foreign}")))
    }
  }

  test("publication validation is scoped and rechecks mutations before returning text") {
    inspect { fixture =>
      val access = highAccess(fixture)
      val resize = resizeNode(fixture.resized)
      expectLineage {
        ExternalParameterizedHighBit.withPublicationValidation(fixture) {
          assert(ExternalParameterizedHighBit.proves(fixture, access))
          access.source = fixture.foreign
          "must not escape"
        }
      }
      assert(!ExternalParameterizedHighBit.proves(fixture, access))
      access.source = fixture.source
      assert(ExternalParameterizedHighBit.proves(fixture, access))

      expectLineage {
        ExternalParameterizedNativeResize.withPublicationValidation(fixture) {
          assert(ExternalParameterizedNativeResize.proves(fixture, resize))
          resize.input = fixture.foreign
          "must not escape"
        }
      }
      assert(!ExternalParameterizedNativeResize.proves(fixture, resize))
      var entered = false
      expectLineage {
        ExternalParameterizedNativeResize.withPublicationValidation(fixture) {
          entered = true
          "invalid entry"
        }
      }
      assert(!entered)
      resize.input = fixture.source
      assert(ExternalParameterizedNativeResize.proves(fixture, resize))
      assert(ExternalParameterizedNativeResize.withPublicationValidation(fixture) {
        ExternalParameterizedHighBit.withPublicationValidation(fixture) { "valid" }
      } == "valid")
      val savedCache = fixture.userCache.toVector
      try {
        expectLineage {
          ExternalParameterizedHighBit.withPublicationValidation(fixture) {
            fixture.userCache.clear()
            "removed high-bit capture"
          }
        }
      } finally fixture.userCache ++= savedCache
      try {
        expectLineage {
          ExternalParameterizedNativeResize.withPublicationValidation(fixture) {
            fixture.userCache.clear()
            "removed resize capture"
          }
        }
      } finally fixture.userCache ++= savedCache
      assert(ExternalParameterizedHighBit.proves(fixture, access))
      assert(ExternalParameterizedNativeResize.proves(fixture, resize))
    }
  }
}
