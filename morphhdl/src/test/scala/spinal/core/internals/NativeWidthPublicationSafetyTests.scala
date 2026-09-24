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

private[internals] final class NativeResizeWireTruncationFixture(width: HdlInt) extends Component {
  val source = in(UInt(8 bits)).setName("wireTruncSource")
  val fixed = UInt(8 bits).setName("wireTruncFixed")
  // Keep the actual resize result as the owner declaration. Assigning the resize
  // directly to an output introduces one extra native UInt carrier before the
  // publication capture phase and makes an unsafe cast observe that carrier
  // instead of the registry-owned Resize node.
  val target = fixed.resize(width.asElabInt).setName("wireTruncTarget")
  val receiver = out(UInt(width bits)).setName("wireTruncReceiver")
  fixed := source
  receiver := target
  val nativeResize = target.head.asInstanceOf[DataAssignmentStatement].source.asInstanceOf[Resize]
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

  private def inspectWireTruncation(body: NativeResizeWireTruncationFixture => Unit): Unit = {
    val width = HdlInt.param("WIRE_TRUNC_WIDTH", 5, 1, 8)
    val config = SpinalConfig(
      targetDirectory = Files.createTempDirectory("native-resize-wire-trunc-").toString,
      headerWithDate = false, headerWithRepoHash = false)
    config.phasesInserters += { phases =>
      ExternalParameterizedNativeResize.install(phases)
      val cleanup = phases.zipWithIndex.collect {
        case (_: PhaseRemoveIntermediateUnnameds, index) => index
      }.toVector
      assert(cleanup.size >= 3)
      // Exercise the handoff at the same late native boundary used by the
      // production WIRE pipeline: after width normalization, replacing the
      // third cleanup rather than injecting a narrowing assignment before
      // PhaseNormalizeNodeInputs can reject it for ordinary Spinal semantics.
      phases.update(cleanup(2), new PhaseMisc {
        override def impl(pc: PhaseContext): Unit =
          body(pc.topLevel.asInstanceOf[NativeResizeWireTruncationFixture])
      })
    }
    config.generateVerilog(new NativeResizeWireTruncationFixture(width))
  }

  private def highAccess(fixture: NativeWidthPublicationSafetyFixture): BitVectorBitAccessFixed =
    fixture.high.head.source.asInstanceOf[BitVectorBitAccessFixed]

  private def resizeNode(value: UInt): Resize = value.head.source.asInstanceOf[Resize]

  private def expectLineage(body: => Any): Unit = {
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

  test("WIRE truncation native-resize handoff is symbolic, exact and one-shot") {
    inspectWireTruncation { fixture =>
      val assignment = fixture.target.head.asInstanceOf[DataAssignmentStatement]
      val receiverAssignment = fixture.receiver.head.asInstanceOf[DataAssignmentStatement]
      val resize = fixture.nativeResize
      assert(ExternalParameterizedNativeResize.provesAssignment(fixture, assignment))
      assert(!ExternalParameterizedNativeResize.provesAssignment(fixture, receiverAssignment))
      assert(ExternalParameterizedNativeResize.beginLowBitTruncationConsumption(fixture, assignment))
      assert(!ExternalParameterizedNativeResize.provesAssignment(fixture, assignment))
      expectLineage(ExternalParameterizedNativeResize.withPublicationValidation(fixture) { "pending" })
      assignment.source = fixture.fixed
      ExternalParameterizedNativeResize.completeLowBitTruncationConsumption(
        fixture, assignment, fixture.fixed)
      assert(!ExternalParameterizedNativeResize.proves(fixture, resize))
      assert(!ExternalParameterizedNativeResize.provesAssignment(fixture, assignment))

      assert(ExternalParameterizedNativeResize.beginLowBitTruncationReceiverForwarding(
        fixture, assignment, receiverAssignment))
      assert(!ExternalParameterizedNativeResize.provesAssignment(fixture, receiverAssignment))
      expectLineage(ExternalParameterizedNativeResize.withPublicationValidation(fixture) {
        "pending receiver"
      })
      receiverAssignment.source = fixture.source
      ExternalParameterizedNativeResize.completeLowBitTruncationReceiverForwarding(
        fixture, receiverAssignment, fixture.source)
      assert(!ExternalParameterizedNativeResize.provesAssignment(fixture, receiverAssignment))

      assert(ExternalParameterizedNativeResize.withPublicationValidation(fixture) {
        assert(!ExternalParameterizedNativeResize.proves(fixture, resize))
        assert(ExternalParameterizedNativeResize.provesAssignment(fixture, assignment))
        assert(ExternalParameterizedNativeResize.provesAssignment(fixture, receiverAssignment))
        "valid"
      } == "valid")
      assert(!ExternalParameterizedNativeResize.provesAssignment(fixture, assignment))
      assert(!ExternalParameterizedNativeResize.provesAssignment(fixture, receiverAssignment))
      expectLineage(ExternalParameterizedNativeResize.beginLowBitTruncationConsumption(fixture, assignment))
      expectLineage(ExternalParameterizedNativeResize.beginLowBitTruncationReceiverForwarding(
        fixture, assignment, receiverAssignment))

      val originalScope = assignment.parentScope
      val borrowed = new ScopeStatement(null)
      borrowed.component = fixture
      assignment.parentScope = borrowed
      expectLineage(ExternalParameterizedNativeResize.withPublicationValidation(fixture) { "moved owner" })
      assignment.parentScope = originalScope
      assert(ExternalParameterizedNativeResize.withPublicationValidation(fixture) {
        assert(ExternalParameterizedNativeResize.provesAssignment(fixture, assignment))
        assert(ExternalParameterizedNativeResize.provesAssignment(fixture, receiverAssignment))
        "restored owner"
      } == "restored owner")

      val receiverScope = receiverAssignment.parentScope
      receiverAssignment.parentScope = borrowed
      expectLineage(ExternalParameterizedNativeResize.withPublicationValidation(fixture) { "moved receiver" })
      receiverAssignment.parentScope = receiverScope
      assert(ExternalParameterizedNativeResize.withPublicationValidation(fixture) {
        assert(ExternalParameterizedNativeResize.provesAssignment(fixture, receiverAssignment))
        "restored receiver"
      } == "restored receiver")
      assert(!ExternalParameterizedNativeResize.provesAssignment(fixture, receiverAssignment))
    }
  }

  test("WIRE truncation receiver forwarding can retain its fresh resize owner") {
    inspectWireTruncation { fixture =>
      val assignment = fixture.target.head.asInstanceOf[DataAssignmentStatement]
      val receiverAssignment = fixture.receiver.head.asInstanceOf[DataAssignmentStatement]
      assert(ExternalParameterizedNativeResize.provesAssignment(fixture, assignment))
      assert(ExternalParameterizedNativeResize.beginLowBitTruncationReceiverForwarding(
        fixture, assignment, receiverAssignment))
      assert(!ExternalParameterizedNativeResize.provesAssignment(fixture, receiverAssignment))
      expectLineage(ExternalParameterizedNativeResize.withPublicationValidation(fixture) {
        "pending fresh-owner receiver"
      })
      receiverAssignment.source = fixture.source
      ExternalParameterizedNativeResize.completeLowBitTruncationReceiverForwarding(
        fixture, receiverAssignment, fixture.source)
      assert(ExternalParameterizedNativeResize.provesAssignment(fixture, assignment))
      assert(!ExternalParameterizedNativeResize.provesAssignment(fixture, receiverAssignment))
      assert(ExternalParameterizedNativeResize.withPublicationValidation(fixture) {
        assert(ExternalParameterizedNativeResize.provesAssignment(fixture, assignment))
        assert(ExternalParameterizedNativeResize.provesAssignment(fixture, receiverAssignment))
        "valid fresh-owner receiver"
      } == "valid fresh-owner receiver")
      val receiverScope = receiverAssignment.parentScope
      val borrowed = new ScopeStatement(null)
      borrowed.component = fixture
      receiverAssignment.parentScope = borrowed
      expectLineage(ExternalParameterizedNativeResize.withPublicationValidation(fixture) {
        "moved fresh-owner receiver"
      })
      receiverAssignment.parentScope = receiverScope
      assert(ExternalParameterizedNativeResize.withPublicationValidation(fixture) {
        assert(ExternalParameterizedNativeResize.provesAssignment(fixture, receiverAssignment))
        "restored fresh-owner receiver"
      } == "restored fresh-owner receiver")
    }
  }

  test("WIRE truncation handoff rejects a captured fixed target resize") {
    inspect { fixture =>
      val assignment = fixture.resized.head.asInstanceOf[DataAssignmentStatement]
      assert(ExternalParameterizedNativeResize.provesAssignment(fixture, assignment))
      expectLineage(ExternalParameterizedNativeResize.beginLowBitTruncationConsumption(fixture, assignment))
      assert(ExternalParameterizedNativeResize.provesAssignment(fixture, assignment))
    }
  }
}
