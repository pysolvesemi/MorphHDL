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
}
