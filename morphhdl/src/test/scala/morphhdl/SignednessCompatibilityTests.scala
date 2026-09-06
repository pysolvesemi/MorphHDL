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
      morphhdl.MorphVerilog(neutral.copy(netlistFileName = "morph.v"))(dut)
      assert(read(root.resolve("morph.v")).contains("wire signed"))
      SpinalVerilog(neutral.copy(netlistFileName = "native-after.v"))(dut)
      SpinalVhdl(fresh(root.resolve("native-after.vhd")))(dut)
      assert(read(root.resolve("native-after.v")) == native)
      assert(read(root.resolve("native-after.vhd")) == vhdl)
      assert(neutral.phasesInserters.isEmpty)
    }
  }

  test("60g default retains real mixed-type boundaries and is shared by tryGenerate and canonical IR") {
    directory { root =>
      def parameter = HdlInt.param("WIDTH", default = 1, min = 1, max = 32)
      def dut = new nativeapplication.PureSIntCastFixture.Boundaries(parameter)
      morphhdl.MorphVerilog(fresh(root.resolve("default.v")))(dut)
      val default = read(root.resolve("default.v"))
      assert(signedDeclaration.findFirstIn(default).nonEmpty)
      assert(casts(default) > 0, "real boundaries must not be erased")
      assert(!default.contains("$signed($signed("))
      val result = morphhdl.MorphVerilog.tryGenerate(fresh(root.resolve("try.v")))(dut)
      assert(result.isRight)
      morphhdl.MorphVerilog.generateWithCanonicalIr(fresh(root.resolve("canonical.v")))(dut)
      assert(default == read(root.resolve("try.v")))
      assert(default == read(root.resolve("canonical.v")))
      morphhdl.MorphVerilog(MorphSignedCasts.enable(fresh(root.resolve("explicit.v"))))(dut)
      assert(default == read(root.resolve("explicit.v")))
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
