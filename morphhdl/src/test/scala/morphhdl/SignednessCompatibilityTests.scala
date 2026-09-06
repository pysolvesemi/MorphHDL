package spinal.core.internals

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.collection.JavaConverters._
import org.scalatest.funsuite.AnyFunSuite
import morphhdl.{MorphSignedCasts, MorphSignedDeclarations}
import morphhdl.frontend.HdlInt
import nativeapplication.{SignednessCompatibilityArtifactWriter => Writer}
import nativeapplication.{SIntSignedDeclarationsArtifactWriter, SIntSignedDeclarationsFixture}
import spinal.core._

final class SignednessCompatibilityTests extends AnyFunSuite {
  private def directory(body: Path => Unit): Unit = {
    val root = Files.createTempDirectory("signedness-compatibility-")
    try body(root) finally {
      val stream = Files.walk(root)
      try stream.iterator.asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists(_))
      finally stream.close()
    }
  }

  private def read(path: Path): String = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
  private def casts(rtl: String): Int = "\\$signed\\(".r.findAllIn(rtl).size
  private val signedDeclaration = "(?m)^.*\\b(?:wire|reg)\\s+signed\\s+\\[[^\\]]+\\].*$".r

  private def port(rtl: String, name: String): String =
    ("(?m)^\\s*(?:input|output|inout)\\s+(?:wire|reg)\\s+(?:signed\\s+)?" +
      "(?:\\[[^\\]]+\\]\\s+)?" + name + "\\s*[,;]?$" ).r.findFirstIn(rtl)
      .getOrElse(fail("missing port " + name)).trim.replaceAll("\\s+", " ")

  for (kind <- Writer.kinds) {
    test(kind + " preserves native Verilog VHDL and opt-out Morph bytes across all signed modes") {
      directory { root =>
        // generateKind first compares complete native bytes before stripping
        // headers; these assertions also validate the published replay corpus.
        Writer.generateKind(root, kind)
        val out = root.resolve(kind)
        val stream = Files.walk(root)
        val actual = try stream.iterator.asScala.filter(Files.isRegularFile(_))
          .map(path => root.relativize(path).toString.replace('\\', '/')).toSet
        finally stream.close()
        assert(actual == Writer.expectedFiles.filter(_.startsWith(kind + "/")).toSet)

        for (width <- Writer.widths; extension <- Vector("v", "vhd")) {
          val before = read(out.resolve(s"native-$width-before.$extension"))
          for (mode <- Writer.nativeModes.tail)
            assert(read(out.resolve(s"native-$width-$mode.$extension")) == before)
          if (extension == "v") {
            assert(signedDeclaration.findFirstIn(before).isEmpty)
            assert(casts(before) > 0)
          } else {
            assert(before.contains("signed("), "VHDL must retain its native signed type")
            assert(!before.contains("$signed("))
          }
        }

        val disabled = read(out.resolve("morph-disabled-before.v"))
        val declarations = read(out.resolve("morph-declarations.v"))
        val cleanup = read(out.resolve("morph-cleanup.v"))
        assert(disabled == read(out.resolve("morph-disabled-explicit.v")))
        assert(disabled == read(out.resolve("morph-disabled-after.v")))
        assert(declarations == read(out.resolve("morph-declarations-after.v")))
        assert(signedDeclaration.findFirstIn(disabled).isEmpty)
        assert(signedDeclaration.findFirstIn(declarations).nonEmpty)
        assert(signedDeclaration.findFirstIn(cleanup).nonEmpty)
        assert(casts(disabled) > 0)
        assert(casts(declarations) == casts(disabled))
        assert(casts(cleanup) < casts(declarations))
        assert(!"\\$signed\\(\\s*\\$signed\\(".r.findFirstIn(cleanup).nonEmpty)
        assert(cleanup.contains("parameter integer WIDTH"))

        // Check the complete unrelated port declaration, including width and
        // direction, to catch more than an accidentally added signed keyword.
        val unsignedPorts = kind match {
          case "pure" => Vector("clk", "enable", "amount", "less", "lessEqual", "greater", "greaterEqual", "nestedLess")
          case "declarations" => Vector("clk", "enable", "choose", "write", "address", "amount", "raw", "packedBits",
            "logical", "unsignedProduct", "unsignedLess", "signedLess", "rawOut")
          // packed is a reserved word in the native naming policy.
          case "bundles" => Vector("incoming_raw", "incoming_flag", "outgoing_raw", "outgoing_flag", "packed_1")
        }
        for (name <- unsignedPorts) {
          val original = port(disabled, name)
          assert(!original.contains("signed "))
          assert(port(declarations, name) == original, name)
          assert(port(cleanup, name) == original, name)
        }
      }
    }
  }

  test("ordinary VHDL retains its readFirst memory rejection under every signed option") {
    directory { root =>
      val options = Vector[SpinalConfig => SpinalConfig](identity, MorphSignedDeclarations.enable,
        MorphSignedCasts.enable, identity)
      val diagnostics = options.zipWithIndex.map { case (option, index) =>
        val config = option(SIntSignedDeclarationsArtifactWriter.config(root.resolve(s"unsupported-$index.vhd")))
        val error = intercept[SpinalExit] {
          SpinalVhdl(config)(new SIntSignedDeclarationsFixture.Top(HdlInt.literal(5)))
        }
        error.getMessage
      }
      assert(diagnostics.head.contains("memReadSync with readFirst"))
      assert(diagnostics.distinct.size == 1, "a Morph signedness option changed the native VHDL rejection")
    }
  }

  test("deterministic normalization removes only the native generated header") {
    val verilog = "module example;\n  // Generator : a body comment\n  wire signed [4:0] x;\n  assign x = $signed(5'h1f);\nendmodule\n"
    val vhdl = "library ieee;\n-- Generator : a body comment\nentity example is end example;\n"
    for ((prefix, body) <- Vector(("//", verilog), ("--", vhdl))) {
      val header = s"$prefix Generator : SpinalHDL sample\n$prefix Component : example\n$prefix Git hash  : abc123\n\n"
      assert(Writer.canonicalHeader(header + body) == body)
      assert(Writer.canonicalHeader(body) == body)
      assert(Writer.canonicalHeader("// user comment\n" + header + body) == "// user comment\n" + header + body)
    }
  }


  // Unlike the sealed writers, rollout tests start with a genuinely neutral
  // config. No opt-out helper or environment default participates in this leg.
  private def fresh(path: Path): SpinalConfig = {
    Files.createDirectories(path.getParent)
    val result = SpinalConfig(targetDirectory = path.getParent.toString)
    result.netlistFileName = path.getFileName.toString
    result
  }

  for (width <- Vector(1, 5, 8, 32)) {
    test(s"60g default and explicit minimal casts agree at default WIDTH=$width without config mutation") {
      directory { root =>
        def parameter = HdlInt.param("WIDTH", default = width, min = 1, max = 32)
        val config = fresh(root.resolve("default.v"))
        val inserters = config.phasesInserters.toVector
        val flags = config.flags.toSet
        morphhdl.MorphVerilog(config)(new nativeapplication.PureSIntCastFixture.Top(parameter))
        val default = read(root.resolve("default.v"))
        assert(config.phasesInserters.toVector == inserters)
        assert(config.flags.toSet == flags)
        assert(!MorphSignedDeclarations.isEnabled(config))
        morphhdl.MorphVerilog(MorphSignedCasts.enable(fresh(root.resolve("explicit.v"))))(
          new nativeapplication.PureSIntCastFixture.Top(parameter))
        morphhdl.MorphVerilog(MorphSignedDeclarations.disable(fresh(root.resolve("legacy.v"))))(
          new nativeapplication.PureSIntCastFixture.Top(parameter))
        assert(default == read(root.resolve("explicit.v")))
        assert(casts(default) == 0, "pure signed operations must not retain redundant casts")
        assert(signedDeclaration.findFirstIn(default).nonEmpty)
        val legacy = read(root.resolve("legacy.v"))
        assert(signedDeclaration.findFirstIn(legacy).isEmpty)
        assert(casts(legacy) > 0)
        for (name <- Vector("clk", "enable", "amount", "less", "lessEqual", "greater", "greaterEqual", "nestedLess"))
          assert(port(default, name) == port(legacy, name), name)
        // Reusing exactly the same caller config must not inherit a prior mode.
        morphhdl.MorphVerilog(config)(new nativeapplication.PureSIntCastFixture.Top(parameter))
        assert(read(root.resolve("default.v")) == default)
      }
    }
  }

  test("60g explicit disable and declaration-only selections survive copies and re-enabling") {
    directory { root =>
      def parameter = HdlInt.param("WIDTH", default = 8, min = 1, max = 32)
      val selections = Vector[(String, SpinalConfig => SpinalConfig)](
        "legacy" -> MorphSignedDeclarations.disable _,
        "legacy-again" -> ((c: SpinalConfig) => MorphSignedDeclarations.disable(MorphSignedDeclarations.disable(c))),
        "declarations" -> MorphSignedDeclarations.enable _,
        "casts-off" -> MorphSignedCasts.disable _,
        "cleanup-off" -> ((c: SpinalConfig) => MorphSignedCasts.disable(MorphSignedCasts.enable(c))),
        "re-enabled" -> ((c: SpinalConfig) => MorphSignedCasts.enable(MorphSignedDeclarations.disable(c))),
        "cleanup-again" -> ((c: SpinalConfig) => MorphSignedCasts.enable(MorphSignedCasts.enable(c))),
        "default" -> ((c: SpinalConfig) => c))
      val generated = selections.map { case (name, select) =>
        val base = fresh(root.resolve(name + ".v"))
        val value = select(base)
        assert(base.phasesInserters.isEmpty, "selecting a mode mutated the original config")
        val copied = value.copy(phasesInserters = value.phasesInserters.clone(), flags = value.flags.clone())
        morphhdl.MorphVerilog(copied)(new nativeapplication.PureSIntCastFixture.Top(parameter))
        name -> read(root.resolve(name + ".v"))
      }.toMap
      assert(generated("legacy") == generated("legacy-again"))
      assert(generated("declarations") == generated("casts-off"))
      assert(generated("declarations") == generated("cleanup-off"))
      assert(generated("default") == generated("re-enabled"))
      assert(generated("default") == generated("cleanup-again"))
      assert(casts(generated("declarations")) == casts(generated("legacy")))
      assert(signedDeclaration.findFirstIn(generated("declarations")).nonEmpty)
      assert(signedDeclaration.findFirstIn(generated("legacy")).isEmpty)
      assert(casts(generated("default")) == 0)
    }
  }

  test("60g default publication leaves native Verilog and VHDL bytes unchanged in the same session") {
    directory { root =>
      def dut = new SIntSignedDeclarationsFixture.Direct(HdlInt.literal(5))
      val neutral = fresh(root.resolve("native-before.v"))
      SpinalVerilog(neutral)(dut)
      val native = read(root.resolve("native-before.v"))
      SpinalVhdl(fresh(root.resolve("native-before.vhd")))(dut)
      val vhdl = read(root.resolve("native-before.vhd"))
      // The native leg stays concrete; the same component source receives a
      // retained parameter on the MorphHDL leg, as required by its front door.
      morphhdl.MorphVerilog(neutral.copy(netlistFileName = "morph.v"))(
        new SIntSignedDeclarationsFixture.Direct(HdlInt.param("WIDTH", default = 5, min = 1, max = 32)))
      assert(read(root.resolve("morph.v")).contains("wire signed"))
      SpinalVerilog(neutral.copy(netlistFileName = "native-after.v"))(dut)
      SpinalVhdl(fresh(root.resolve("native-after.vhd")))(dut)
      assert(read(root.resolve("native-after.v")) == native)
      assert(read(root.resolve("native-after.vhd")) == vhdl)
      assert(neutral.phasesInserters.isEmpty)
      // Inactive options must preserve the complete native execution plan,
      // not just RTL text. In particular they cannot prepend a lifecycle phase
      // before the native verbose logger has a constructed component to walk.
      val options = Vector[SpinalConfig => SpinalConfig](identity,
        MorphSignedDeclarations.enable _, MorphSignedCasts.enable _,
        MorphSignedDeclarations.disable _, MorphSignedCasts.disable _)
      val nativePlans = options.zipWithIndex.map { case (select, index) =>
        val path = root.resolve(s"native-plan-$index.v")
        val config = select(fresh(path))
        var classes = Vector.empty[String]
        config.phasesInserters += { phases => classes = phases.map(_.getClass.getName).toVector }
        SpinalVerilog(config)(dut)
        assert(read(path) == native)
        classes
      }
      assert(nativePlans.head.nonEmpty && nativePlans.distinct.size == 1)
    }
  }

  test("60g default retains real mixed-type boundaries and is shared by tryGenerate and canonical IR") {
    directory { root =>
      def parameter = HdlInt.param("WIDTH", default = 1, min = 1, max = 32)
      // Materialized scalar boundaries legitimately need no expression cast.
      // A dynamic select of an unsigned Vec carrier needs an actual signed
      // slice boundary; use that established 60e fixture for this contract.
      def dut = new spinal.core.SignednessBoundaryFixture.Vectors(
        parameter, HdlInt.param("DEPTH", default = 3, min = 1, max = 8))
      morphhdl.MorphVerilog(fresh(root.resolve("default.v")))(dut)
      val default = read(root.resolve("default.v"))
      assert(signedDeclaration.findFirstIn(default).nonEmpty)
      assert(default.contains("$signed(updated["), "dynamic signed leaf boundary must not be erased")
      assert(port(default, "packedIn").contains("[(WIDTH * DEPTH)-1:0]"))
      assert(!port(default, "packedIn").contains("signed"))
      assert(!port(default, "packedOut").contains("signed"))
      assert(!default.contains("$signed($signed("))
      val result = morphhdl.MorphVerilog.tryGenerate(fresh(root.resolve("try.v")))(dut)
      assert(result.isRight)
      assert(default == read(root.resolve("try.v")))
      morphhdl.MorphVerilog(MorphSignedCasts.enable(fresh(root.resolve("explicit.v"))))(dut)
      assert(default == read(root.resolve("explicit.v")))

      // Canonical IR's simple-wire profile has no nested Vec scopes or compound
      // widths. Exercise its supported scalar surface independently, rather
      // than broadening that producer as part of a signedness-default rollout.
      def scalar: Component = {
        // Keep the symbolic config outside the native Component val callback.
        // That callback hashes arbitrary member values for reflective naming.
        val width = parameter
        new Component {
          setDefinitionName("DefaultSignedCanonicalWire")
          val a = in(SInt(width bits))
          val b = out(SInt(width bits))
          b := a
        }
      }
      morphhdl.MorphVerilog(fresh(root.resolve("scalar.v")))(scalar)
      val scalarDefault = read(root.resolve("scalar.v"))
      assert(port(scalarDefault, "a").contains("wire signed [WIDTH-1:0]"))
      val captured = morphhdl.MorphVerilog.generateWithCanonicalIr(
        fresh(root.resolve("canonical.v")))(scalar)
      assert(scalarDefault == read(root.resolve("canonical.v")))
      assert(captured.handoff.design.modules.head.declarations.forall(
        _.packedType.exists(_.valueSemantics == morphhdl.ir.v1.PackedValueSemantics.SignedInteger)))
      var received: morphhdl.ir.v1.CanonicalIrHandoff = null
      val published = morphhdl.MorphVerilog.publishCanonicalIr(
        fresh(root.resolve("published.v")),
        new morphhdl.ir.v1.CanonicalIrPublisher {
          override def publish(handoff: morphhdl.ir.v1.CanonicalIrHandoff): Unit = {
            assert(received == null)
            assert(Files.isRegularFile(root.resolve("published.v")))
            received = handoff
          }
        })(scalar)
      assert(received eq published.handoff)
      assert(scalarDefault == read(root.resolve("published.v")))
    }
  }

  test("60g unsigned generated domains retain native authority beside signed scalars") {
    directory { root =>
      // Reuse the inherited fixture's qualified unsigned witness. Its legal
      // WIDTH domain still includes one; the fixture's native representative
      // bridge cannot itself elaborate with a width-one witness. Preserve that
      // rejection independently instead of attributing it to signedness.
      def unsignedWidth = HdlInt.param("WIDTH", default = 6, min = 1, max = 8)
      def config(path: Path) = fresh(path).copy(defaultConfigForClockDomains =
        ClockDomainConfig(clockEdge = RISING, resetKind = SYNC, resetActiveLevel = HIGH))
      def unsigned = new morphhdl.CapturedAssignmentNormalizationSmoke.NestedInitializedRegisters(unsignedWidth)
      val defaultPath = root.resolve("unsigned.v")
      val legacyPath = root.resolve("unsigned-legacy.v")
      morphhdl.MorphVerilog(config(defaultPath))(unsigned)
      morphhdl.MorphVerilog(MorphSignedDeclarations.disable(config(legacyPath)))(unsigned)
      assert(read(defaultPath) == read(legacyPath), "unsigned publication must not change")
      for ((name, select) <- Vector[(String, SpinalConfig => SpinalConfig)](
          "default" -> ((c: SpinalConfig) => c), "legacy" -> MorphSignedDeclarations.disable _)) {
        val output = root.resolve(s"unsupported-witness-$name.v")
        val result = morphhdl.MorphVerilog.tryGenerate(select(config(output))) {
          new morphhdl.CapturedAssignmentNormalizationSmoke.NestedInitializedRegisters(
            HdlInt.param("WIDTH", default = 1, min = 1, max = 8))
        }
        assert(result.left.toOption.exists(_.detail.contains(
          "SPINAL-ELAB-DOMAIN-PROJECTION-OWNER-REPRESENTATIVE-MISMATCH")))
        assert(!Files.exists(output))
      }

      for (signedWidth <- Vector(1, 8)) {
        def mixed: Component = {
          val width = unsignedWidth
          val signed = HdlInt.param("SIGNED_WIDTH", default = signedWidth, min = 1, max = 8)
          new Component {
            setDefinitionName("MixedSignedAndUnsignedDomains")
            val a = in(SInt(signed bits))
            val sum = out(SInt(signed bits))
            val load = in(Bool())
            val raw = in(UInt(width bits))
            val data = out(UInt(width bits))
            sum := a + a
            val child = new morphhdl.CapturedAssignmentNormalizationSmoke.NestedInitializedRegisters(width)
            child.load := load
            child.din := raw
            data := child.dout
          }
        }
        val mixedPath = root.resolve(s"mixed-$signedWidth.v")
        val explicitPath = root.resolve(s"mixed-explicit-$signedWidth.v")
        morphhdl.MorphVerilog(config(mixedPath))(mixed)
        morphhdl.MorphVerilog(MorphSignedCasts.enable(config(explicitPath)))(mixed)
        val rtl = read(mixedPath)
        assert(rtl == read(explicitPath))
        assert(port(rtl, "a").contains("wire signed [SIGNED_WIDTH-1:0]"))
        assert(!port(rtl, "data").contains("signed"))
        assert(rtl.contains("g_outer_wide") && rtl.contains("g_outer_narrow"))
        assert(rtl.contains("g_inner_wide") && rtl.contains("g_inner_middle"))
        assert(casts(rtl) == 0)
      }
    }
  }

  test("60g signed grow retains merged 59d support in both default and legacy modes") {
    directory { root =>
      for (defaultWidth <- Vector(4, 8, 12)) {
        def dut = new morphhdl.CapturedDomainWidthEquivalenceSmoke.TypedSIntResizeNamedGrowCarrier(
          HdlInt.param("WIDTH", default = defaultWidth, min = 4, max = 12).asElabInt)
        val output = root.resolve(s"grow-$defaultWidth.v")
        val explicit = root.resolve(s"grow-explicit-$defaultWidth.v")
        morphhdl.MorphVerilog(fresh(output))(dut)
        morphhdl.MorphVerilog(MorphSignedCasts.enable(fresh(explicit)))(dut)
        val rtl = read(output)
        assert(rtl == read(explicit))
        assert(rtl.contains("source[WIDTH-1]"), "sign extension must not freeze the witness bit")
        assert(port(rtl, "observed").contains("wire signed [(WIDTH + 1)-1:0]"))
        val legacy = root.resolve(s"grow-legacy-$defaultWidth.v")
        val result = morphhdl.MorphVerilog.tryGenerate(MorphSignedDeclarations.disable(fresh(legacy)))(dut)
        // 59d now owns symbolic growth in the native/legacy path too. Do not
        // preserve an obsolete rejection by regressing its width authority.
        assert(result.isRight)
        val legacyRtl = read(legacy)
        assert(legacyRtl.contains("source[WIDTH-1]"))
        assert(!port(legacyRtl, "observed").contains("signed"))
        for (generated <- Vector(output, legacy)) {
          morphhdl.NativeResizeCompatibilitySimulation.check(root,
            generated.getFileName.toString, "TypedSIntResizeNamedGrowCarrier", "WIDTH",
            Vector(4, 8, 12).map(w => (w, w, w + 1)), signedSource = true)
        }
      }
    }
  }

  test("60g strict observers coexist with default publication in either installation order") {
    directory { root =>
      for (width <- Vector(1, 8); publicationFirst <- Vector(false, true)) {
        val prefix = s"observer-$width-$publicationFirst"
        def parameter = HdlInt.param("WIDTH", default = width, min = 1, max = 32)
        val plain = root.resolve(prefix + "-plain.v")
        morphhdl.MorphVerilog(fresh(plain))(new SIntSignedDeclarationsFixture.Direct(parameter))
        val output = root.resolve(prefix + ".v")
        val neutral = fresh(output)
        val config = if (publicationFirst) MorphSignedCasts.enable(neutral) else neutral
        var dut: SIntSignedDeclarationsFixture.Direct = null
        var calls = 0
        var replays = Vector.empty[String]
        config.phasesInserters += MorphHdlSignednessAnalysis.install { snapshot =>
          calls += 1
          import MorphHdlSignednessAnalysis.DeclarationUse
          import morphhdl.analysis.SignednessFacts.{SignedScalar, UnsignedScalar}
          assert(snapshot.validate(dut.a, snapshot.declaration(dut.a), DeclarationUse).intent == SignedScalar)
          // A strict observer must still see unrelated unsigned declarations.
          assert(snapshot.validate(dut.bitsIn, snapshot.declaration(dut.bitsIn), DeclarationUse).intent == UnsignedScalar)
          replays :+= snapshot.replay
        }
        val originalInserters = config.phasesInserters.toVector
        for (iteration <- 1 to 2) {
          morphhdl.MorphVerilog(config) { dut = new SIntSignedDeclarationsFixture.Direct(parameter); dut }
          assert(calls == iteration)
          assert(read(output) == read(plain))
          assert(config.phasesInserters.toVector == originalInserters)
        }
        assert(replays.distinct.size == 1)
        val native = root.resolve(prefix + "-native.v")
        SpinalVerilog(config.copy(netlistFileName = native.getFileName.toString)) {
          dut = new SIntSignedDeclarationsFixture.Direct(HdlInt.literal(width)); dut
        }
        assert(calls == 3)
        assert(signedDeclaration.findFirstIn(read(native)).isEmpty)
      }
    }
  }

  test("60g shared capture retains duplicate-consumer and phase-placement rejection") {
    directory { root =>
      for (mutation <- Vector("duplicate-observer", "duplicate-publication", "separate-emitter",
          "move-observer", "duplicate-phase")) {
        val output = root.resolve(mutation + ".v")
        val config = fresh(output)
        var called = false
        config.phasesInserters += MorphHdlSignednessAnalysis.install(_ => called = true)
        config.phasesInserters += { phases =>
          val emission = phases.indexWhere(_.isInstanceOf[PhaseVerilog])
          mutation match {
            case "duplicate-observer" => MorphHdlSignednessAnalysis.install(_ => ())(phases)
            case "duplicate-publication" =>
              MorphHdlSignednessAnalysis.installPublication(_ => (), () => true)(phases)
            case "separate-emitter" => phases.insert(emission, new PhaseMisc {
              override def impl(pc: PhaseContext): Unit = ()
            })
            case "move-observer" =>
              val observer = phases.remove(emission - 1)
              phases.insert(phases.indexWhere(_.getClass == classOf[PhaseCheckCrossClock]), observer)
            case "duplicate-phase" => phases.insert(emission, phases(emission - 1))
          }
        }
        val result = morphhdl.MorphVerilog.tryGenerate(config) {
          new SIntSignedDeclarationsFixture.Direct(HdlInt.param("WIDTH", default = 8, min = 1, max = 32))
        }
        assert(result.left.toOption.exists(_.detail.contains("MORPH-SIGNEDNESS-PHASE-PLAN")), mutation)
        assert(!called, mutation)
        assert(!Files.exists(output), mutation)
      }
    }
  }

  test("60g rejects consumer registration from any running phase before capture") {
    directory { root =>
      for (role <- Vector("observer", "publisher", "first-observer");
           position <- Vector("before-create", "after-validation", "after-allocation")) {
        val output = root.resolve(s"late-$role-$position.v")
        val previous = "// previously published artifact\n"
        Files.write(output, previous.getBytes(StandardCharsets.UTF_8))
        var called = false
        val config = role match {
          case "observer" => MorphSignedCasts.enable(fresh(output))
          case _ => MorphSignedDeclarations.disable(fresh(output))
        }
        if (role == "publisher")
          config.phasesInserters += MorphHdlSignednessAnalysis.install(_ => called = true)
        config.phasesInserters += { phases =>
          val index = position match {
            case "before-create" => phases.indexWhere(_.isInstanceOf[PhaseCreateComponent])
            case "after-validation" => phases.indexWhere(_.getClass == classOf[PhaseCheckCrossClock]) + 1
            case "after-allocation" => phases.indexWhere(_.getClass == classOf[PhaseAllocateNames]) + 1
          }
          var attempted = false
          phases.insert(index, new PhaseMisc {
            override def impl(pc: PhaseContext): Unit = {
              // First-ever runtime installation must also fail, even if a
              // mutable plan revisits this phase after a rejected insertion.
              if (!attempted) {
                attempted = true
                if (role == "publisher")
                  MorphHdlSignednessAnalysis.installPublication(_ => called = true, () => true)(phases)
                else MorphHdlSignednessAnalysis.install(_ => called = true)(phases)
              }
            }
          })
        }
        val result = morphhdl.MorphVerilog.tryGenerate(config) {
          new SIntSignedDeclarationsFixture.Direct(HdlInt.param("WIDTH", default = 8, min = 1, max = 32))
        }
        assert(result.left.toOption.exists(_.detail.contains("MORPH-SIGNEDNESS-PHASE-PLAN")), s"$role/$position")
        assert(!called, s"$role/$position")
        assert(read(output) == previous, s"$role/$position")
      }
    }
  }

  test("60g scheduler lifecycle is monotonic and strict native observers preserve verbose generation") {
    // The scheduler closes registration before even an early caller phase
    // runs, including phases which fail. No removable guard phase is involved.
    val pc = new PhaseContext(SpinalConfig())
    assert(!pc.hasStartedPhaseExecution)
    intercept[IllegalStateException] {
      pc.doPhase(new PhaseMisc {
        override def impl(context: PhaseContext): Unit = {
          assert(context.hasStartedPhaseExecution)
          throw new IllegalStateException("intentional lifecycle control")
        }
      })
    }
    assert(pc.hasStartedPhaseExecution)
    pc.doPhase(new PhaseMisc {
      override def impl(context: PhaseContext): Unit = assert(context.hasStartedPhaseExecution)
    })
    assert(pc.hasStartedPhaseExecution)

    directory { root =>
      // Native verbose output uses this fixed path. Restore a pre-existing log
      // after the sequential regression and close each native report's writer.
      val verboseLog = java.nio.file.Paths.get("verbose.log")
      val saved = if (Files.exists(verboseLog)) Some(Files.readAllBytes(verboseLog)) else None
      try {
        for (observe <- Vector(false, true)) {
          val output = root.resolve(s"native-verbose-$observe.v")
          val config = fresh(output).copy(verbose = true)
          var called = false
          var planned = Vector.empty[String]
          config.phasesInserters += { phases =>
            assert(!GlobalData.get.phaseContext.hasStartedPhaseExecution)
            if (observe) MorphHdlSignednessAnalysis.install { _ =>
              called = true
              assert(GlobalData.get.phaseContext.hasStartedPhaseExecution)
            }(phases)
            planned = phases.map(_.getClass.getName).toVector
            assert(phases.head.isInstanceOf[PhaseCreateComponent])
          }
          val report = SpinalVerilog(config)(new SIntSignedDeclarationsFixture.Direct(HdlInt.literal(5)))
          report.globalData.phaseContext.verboseLog.close()
          assert(called == observe)
          assert(planned.count(_.contains("ObservationPhase")) == (if (observe) 1 else 0))
          assert(read(verboseLog).contains("checksum:"))
        }
        assert(read(root.resolve("native-verbose-true.v")) == read(root.resolve("native-verbose-false.v")))
      } finally {
        saved match {
          case Some(bytes) => Files.write(verboseLog, bytes)
          case None => Files.deleteIfExists(verboseLog)
        }
      }
    }
  }

  test("60g caller observation cannot refresh publication evidence after a graph mutation") {
    directory { root =>
      val output = root.resolve("preserved.v")
      val previous = "// previous public artifact\n"
      Files.write(output, previous.getBytes(StandardCharsets.UTF_8))
      val config = fresh(output)
      var dut: SIntSignedDeclarationsFixture.Direct = null
      config.phasesInserters += MorphHdlSignednessAnalysis.install { snapshot =>
        assert(snapshot.declaration(dut.a) != null)
        dut.a.setWidth(dut.a.getWidth + 1)
      }
      val result = morphhdl.MorphVerilog.tryGenerate(config) {
        dut = new SIntSignedDeclarationsFixture.Direct(HdlInt.param("WIDTH", default = 8, min = 1, max = 32)); dut
      }
      assert(result.left.toOption.exists(_.detail.contains("MORPH-SIGNEDNESS-STALE-EVIDENCE")))
      assert(read(output) == previous)
    }
  }

  test("60g null options fail before elaboration without changing later publication") {
    intercept[IllegalArgumentException](MorphSignedDeclarations.enable(null))
    intercept[IllegalArgumentException](MorphSignedDeclarations.disable(null))
    intercept[IllegalArgumentException](MorphSignedCasts.enable(null))
    intercept[IllegalArgumentException](MorphSignedCasts.disable(null))
    var invoked = false
    val result = morphhdl.MorphVerilog.tryGenerate(null: SpinalConfig) {
      invoked = true
      new Component {}
    }
    assert(result.isLeft)
    assert(!invoked)
    assert(!MorphSignedDeclarations.isEnabled(null))
    assert(!MorphSignedCasts.isEnabled(null))
  }
}
