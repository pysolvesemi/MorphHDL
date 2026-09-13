package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest

import scala.collection.JavaConverters._

import nativeapplication.{BoundedRecursivePowerFixture, TypedBlackBoxGenericBindingFixture}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.internals.MorphHdlSignednessAnalysis

import morphhdl.frontend.{formalParam, HdlInt}

object Increment61PerComponentFixture {
  final class Leaf(actualWidth: HdlInt) extends Component {
    setDefinitionName("Increment61Leaf")
    addAttribute("keep_hierarchy", "TRUE")

    @dontName private val width = formalParam(
      actualWidth,
      "WIDTH",
      minimum = BigInt(1),
      maximum = BigInt(64)
    )
    val din = in(morphhdl.frontend.Bits(width bits))
    val dout = out(morphhdl.frontend.Bits(width bits))
    dout := din
  }

  final class Top(leftWidth: HdlInt, rightWidth: HdlInt) extends Component {
    setDefinitionName("Increment61Top")

    val leftIn = in(morphhdl.frontend.Bits(leftWidth bits))
    val leftOut = out(morphhdl.frontend.Bits(leftWidth bits))
    val rightIn = in(morphhdl.frontend.Bits(rightWidth bits))
    val rightOut = out(morphhdl.frontend.Bits(rightWidth bits))

    val left = new Leaf(leftWidth)
    left.setName("left")
    val right = new Leaf(rightWidth)
    right.setName("right")
    left.din := leftIn
    leftOut := left.dout
    right.din := rightIn
    rightOut := right.dout
  }

  final class FlatTop(width: HdlInt) extends Component {
    setDefinitionName("Increment61Top")
    val din = in(morphhdl.frontend.Bits(width bits))
    val dout = out(morphhdl.frontend.Bits(width bits))
    dout := din
  }

  def hierarchical(): Component = {
    val leftWidth = HdlInt.param("LEFT_WIDTH", default = 5, min = 1, max = 64)
    val rightWidth = HdlInt.param("RIGHT_WIDTH", default = 5, min = 1, max = 64)
    new Top(leftWidth, rightWidth)
  }

  def flat(): Component = {
    val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 64)
    new FlatTop(width)
  }
}

object Increment61PerComponentPublicationArtifacts {
  def main(args: Array[String]): Unit = {
    if (args.length != 1) {
      throw new IllegalArgumentException(
        "Usage: Increment61PerComponentPublicationArtifacts <output-directory>"
      )
    }
    val root = Paths.get(args(0))
    val split = root.resolve("split")
    val consolidated = root.resolve("consolidated")
    Files.createDirectories(split)
    Files.createDirectories(consolidated)

    MorphVerilog(
      SpinalConfig(
        targetDirectory = split.toString,
        oneFilePerComponent = true
      )
    )(Increment61PerComponentFixture.hierarchical())

    val consolidatedConfig = SpinalConfig(targetDirectory = consolidated.toString)
    consolidatedConfig.netlistFileName = "Increment61Top.v"
    MorphVerilog(consolidatedConfig)(
      Increment61PerComponentFixture.hierarchical()
    )
  }
}

class Increment61PerComponentPublicationTests extends AnyFunSuite {
  import Increment61PerComponentFixture._

  test("split publication uses one deterministic file per canonical logical component") {
    withTemporaryDirectory { directory =>
      val report = MorphVerilog(
        SpinalConfig(
          targetDirectory = directory.toString,
          oneFilePerComponent = true
        )
      )(hierarchical())

      val names = report.generatedSourcesPaths.map(path => Paths.get(path).getFileName.toString)
      assert(names == Vector("Increment61Leaf.v", "Increment61Top.v"))
      assert(report.parameters.map(_.name) == Vector("LEFT_WIDTH", "RIGHT_WIDTH"))

      val leaf = read(directory.resolve("Increment61Leaf.v"))
      val top = read(directory.resolve("Increment61Top.v"))
      assert(moduleDefinitions(leaf) == Vector("Increment61Leaf"))
      assert(moduleDefinitions(top) == Vector("Increment61Top"))
      assert(leaf.contains("parameter integer WIDTH = 5"))
      assert(top.contains("parameter integer LEFT_WIDTH = 5"))
      assert(top.contains("parameter integer RIGHT_WIDTH = 5"))
      assert(top.sliding("Increment61Leaf #(".length).count(_ == "Increment61Leaf #(") == 2)
      assert(top.contains(".WIDTH(LEFT_WIDTH)"))
      assert(top.contains(".WIDTH(RIGHT_WIDTH)"))

      val list = read(directory.resolve("Increment61Top.lst"))
        .split("\n", -1)
        .toVector
        .filter(_.nonEmpty)
      assert(list == names)
      assert(
        Files.isRegularFile(
          directory.resolve(".Increment61Top.morphhdl-one-file-per-component.manifest")
        )
      )
      val owners = directory.resolve(".Increment61Top.morphhdl-one-file-per-component.owners")
      assert(Files.isDirectory(owners) && !Files.isSymbolicLink(owners))
      val ownerStream = Files.list(owners)
      try assert(ownerStream.iterator().asScala.toVector.size == 3)
      finally ownerStream.close()
    }
  }

  test("consolidated publication remains one unchanged source containing both definitions") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "combined.v"
      val report = MorphVerilog(config)(hierarchical())
      assert(report.generatedSourcesPaths == Vector(directory.resolve("combined.v").toString))
      assert(
        moduleDefinitions(read(directory.resolve("combined.v"))).toSet ==
          Set("Increment61Leaf", "Increment61Top")
      )
    }
  }

  test("split publication localizes enum constants into their owning component files") {
    withTemporaryDirectory { directory =>
      val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 64)
      val report = MorphVerilog(
        SpinalConfig(
          targetDirectory = directory.toString,
          oneFilePerComponent = true
        )
      )(new Inc53bEnumTop(width))

      val sources = report.generatedSourcesPaths.map(Paths.get(_))
      assert(sources.map(_.getFileName.toString) == Vector(
        "Inc53bBinaryEnumLeaf.v",
        "Inc53bOneHotEnumLeaf.v",
        "Inc53bEnumTop.v"
      ))
      val text = sources.map(read).mkString("\n")
      assert(text.contains("localparam INC53B_GLOBAL_BINARY_STATE_IDLE"))
      assert(text.contains("localparam INC53B_GLOBAL_ONE_HOT_STATE_IDLE"))
      assert(!text.contains("`define Inc53b"))
      assert(!Files.exists(directory.resolve("enumdefine.v")))
    }
  }

  test("split lifecycle preserves an existing strict signedness emission boundary") {
    for (split <- Vector(false, true)) {
      withTemporaryDirectory { directory =>
        var observations = 0
        val base = SpinalConfig(
          targetDirectory = directory.toString,
          oneFilePerComponent = split
        )
        val config = MorphSignedDeclarations.disable(base)
        config.phasesInserters += MorphHdlSignednessAnalysis.install { snapshot =>
          observations += 1
          assert(snapshot.facts.nonEmpty)
        }

        val report = MorphVerilog(config)(hierarchical())
        assert(observations == 1)
        assert(report.generatedSourcesPaths.nonEmpty)
      }
    }
  }

  test("split publication preserves a validated bounded recursive owner file") {
    withTemporaryDirectory { directory =>
      val report = MorphVerilog(
        SpinalConfig(
          targetDirectory = directory.toString,
          oneFilePerComponent = true
        )
      )(BoundedRecursivePowerFixture.parameterized())

      val names = report.generatedSourcesPaths.map(path => Paths.get(path).getFileName.toString)
      assert(names == Vector("BoundedRecursivePower.v"))
      val recursive = read(directory.resolve("BoundedRecursivePower.v"))
      assert(moduleDefinitions(recursive) == Vector("BoundedRecursivePower"))
      assert(recursive.contains("parameter integer N = 5"))
      assert(recursive.contains("BoundedRecursivePower #("))
      assert(!recursive.contains("__morphhdl_recursive_reference_"))
      assert(
        read(directory.resolve("BoundedRecursivePower.lst"))
          .split("\n", -1)
          .toVector
          .filter(_.nonEmpty) == names
      )
    }
  }

  test("split publication keeps typed BlackBox definitions external") {
    withTemporaryDirectory { directory =>
      val report = MorphVerilog(
        SpinalConfig(
          targetDirectory = directory.toString,
          oneFilePerComponent = true
        )
      )(TypedBlackBoxGenericBindingFixture.parameterized())

      val names = report.generatedSourcesPaths.map(path => Paths.get(path).getFileName.toString)
      assert(names == Vector("TypedBlackBoxGenericTop.v"))
      val top = read(directory.resolve("TypedBlackBoxGenericTop.v"))
      assert(moduleDefinitions(top) == Vector("TypedBlackBoxGenericTop"))
      assert(top.contains("TypedExternalLeaf #("))
      assert(!top.contains("module TypedExternalLeaf"))
      assert(!Files.exists(directory.resolve("TypedExternalLeaf.v")))
      assert(!Files.exists(directory.resolve("TypedParameterOnlyExternal.v")))
    }
  }

  test("repeated split publication deletes only unchanged stale owned files") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(
        targetDirectory = directory.toString,
        oneFilePerComponent = true
      )
      MorphVerilog(config)(hierarchical())
      val unrelated = directory.resolve("user-not-owned.keep")
      Files.write(unrelated, "keep".getBytes(StandardCharsets.UTF_8))

      val report = MorphVerilog(config)(flat())
      assert(report.generatedSourcesPaths.map(path => Paths.get(path).getFileName.toString) ==
        Vector("Increment61Top.v"))
      assert(!Files.exists(directory.resolve("Increment61Leaf.v")))
      assert(read(unrelated) == "keep")
    }
  }

  test("modified managed output fails closed before replacing public files") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(
        targetDirectory = directory.toString,
        oneFilePerComponent = true
      )
      MorphVerilog(config)(hierarchical())
      val leaf = directory.resolve("Increment61Leaf.v")
      Files.write(leaf, "user edit".getBytes(StandardCharsets.UTF_8))

      MorphVerilog.tryGenerate(config)(hierarchical()) match {
        case Left(failure) =>
          assert(failure.stage == MorphVerilogStage.OutputWrite)
          assert(failure.detail.contains("MORPHDL-ONE-FILE-PUBLISH-MANAGED-MODIFIED"))
        case Right(report) => fail(s"expected modified-output rejection, received $report")
      }
      assert(read(leaf) == "user edit")
    }
  }

  test("byte-identical unowned output collision fails closed") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(
        targetDirectory = directory.toString,
        oneFilePerComponent = true
      )
      val content =
        "module Increment61Top;\nendmodule\n".getBytes(StandardCharsets.UTF_8)
      val target = directory.resolve("Increment61Top.v")
      Files.write(target, content)

      MorphPerComponentPublication.publish(
        config,
        "Increment61Top",
        Vector(
          MorphPreparedPublicationFile(
            "Increment61Top.v",
            content,
            reportAsSource = true
          )
        )
      ) match {
        case Left(failure) =>
          assert(failure.stage == MorphVerilogStage.OutputWrite)
          assert(failure.detail.contains("MORPHDL-ONE-FILE-PUBLISH-UNOWNED-COLLISION"))
        case Right(paths) => fail(s"expected unowned collision rejection, received $paths")
      }

      assert(java.util.Arrays.equals(Files.readAllBytes(target), content))
      assert(!Files.exists(directory.resolve("Increment61Top.lst")))
      assert(
        !Files.exists(
          directory.resolve(".Increment61Top.morphhdl-one-file-per-component.manifest")
        )
      )
    }
  }

  test("a case-folded duplicate manifest path fails closed") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(
        targetDirectory = directory.toString,
        oneFilePerComponent = true
      )
      MorphVerilog(config)(hierarchical())
      val manifest = directory.resolve(
        ".Increment61Top.morphhdl-one-file-per-component.manifest"
      )
      val original = read(manifest)
      val first = original.split("\n", -1).toVector.drop(1).find(_.nonEmpty).get
      val fields = first.split("\t", -1)
      val duplicate = fields(0) + "\t" + fields(1).toUpperCase(java.util.Locale.ROOT) + "\n"
      Files.write(manifest, (original + duplicate).getBytes(StandardCharsets.UTF_8))

      MorphVerilog.tryGenerate(config)(hierarchical()) match {
        case Left(failure) =>
          assert(failure.stage == MorphVerilogStage.OutputWrite)
          assert(failure.detail.contains("MORPHDL-ONE-FILE-PUBLISH-MANIFEST-DUPLICATE"))
        case Right(report) => fail(s"expected case-folded duplicate rejection, received $report")
      }
      assert(Files.exists(directory.resolve("Increment61Leaf.v")))
    }
  }

  test("a modified manifest cannot claim and delete an unrelated user file") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(
        targetDirectory = directory.toString,
        oneFilePerComponent = true
      )
      MorphVerilog(config)(hierarchical())

      val unrelated = directory.resolve("user-owned.v")
      val unrelatedContent = "module UserOwned; endmodule\n".getBytes(StandardCharsets.UTF_8)
      Files.write(unrelated, unrelatedContent)
      val manifest = directory.resolve(
        ".Increment61Top.morphhdl-one-file-per-component.manifest"
      )
      Files.write(
        manifest,
        (read(manifest) + sha256(unrelatedContent) + "\tuser-owned.v\n")
          .getBytes(StandardCharsets.UTF_8)
      )

      MorphVerilog.tryGenerate(config)(flat()) match {
        case Left(failure) =>
          assert(failure.stage == MorphVerilogStage.OutputWrite)
          assert(failure.detail.contains("MORPHDL-ONE-FILE-PUBLISH-OWNER-INVENTORY"))
        case Right(report) => fail(s"expected manifest-ownership rejection, received $report")
      }
      assert(java.util.Arrays.equals(Files.readAllBytes(unrelated), unrelatedContent))
      assert(Files.exists(directory.resolve("Increment61Leaf.v")))
    }
  }

  test("a modified ownership marker fails closed before public files change") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(
        targetDirectory = directory.toString,
        oneFilePerComponent = true
      )
      MorphVerilog(config)(hierarchical())
      val top = directory.resolve("Increment61Top.v")
      val originalTop = Files.readAllBytes(top)
      val owners = directory.resolve(".Increment61Top.morphhdl-one-file-per-component.owners")
      val markerStream = Files.list(owners)
      val marker = try markerStream.iterator().asScala.toVector.sortBy(_.toString).head
      finally markerStream.close()
      Files.write(marker, "user edit".getBytes(StandardCharsets.UTF_8))

      MorphVerilog.tryGenerate(config)(hierarchical()) match {
        case Left(failure) =>
          assert(failure.stage == MorphVerilogStage.OutputWrite)
          assert(failure.detail.contains("MORPHDL-ONE-FILE-PUBLISH-OWNER-MARKER-TAMPERED"))
        case Right(report) => fail(s"expected owner-marker rejection, received $report")
      }
      assert(java.util.Arrays.equals(Files.readAllBytes(top), originalTop))
    }
  }

  test("a broken symlink output collision fails closed") {
    val outside = Files.createTempDirectory("morphhdl-increment-61-broken-target-")
    try {
      withTemporaryDirectory { directory =>
        val target = directory.resolve("Increment61Top.v")
        Files.createSymbolicLink(target, outside.resolve("missing.v"))
        val config = SpinalConfig(
          targetDirectory = directory.toString,
          oneFilePerComponent = true
        )
        val content =
          "module Increment61Top;\nendmodule\n".getBytes(StandardCharsets.UTF_8)

        MorphPerComponentPublication.publish(
          config,
          "Increment61Top",
          Vector(
            MorphPreparedPublicationFile(
              "Increment61Top.v",
              content,
              reportAsSource = true
            )
          )
        ) match {
          case Left(failure) =>
            assert(failure.stage == MorphVerilogStage.OutputWrite)
            assert(failure.detail.contains("MORPHDL-ONE-FILE-PUBLISH-TARGET-INVALID"))
          case Right(paths) => fail(s"expected broken-symlink collision rejection, received $paths")
        }
        assert(Files.isSymbolicLink(target))
        assert(!Files.exists(directory.resolve("Increment61Top.lst")))
      }
    } finally deleteTree(outside)
  }

  test("nested publication rejects a symlinked target parent before outside writes") {
    val outside = Files.createTempDirectory("morphhdl-increment-61-outside-")
    try {
      withTemporaryDirectory { directory =>
        Files.createSymbolicLink(directory.resolve("nested"), outside)
        val config = SpinalConfig(
          targetDirectory = directory.toString,
          oneFilePerComponent = true
        )
        val content =
          "module Increment61Top;\nendmodule\n".getBytes(StandardCharsets.UTF_8)

        MorphPerComponentPublication.publish(
          config,
          "Increment61Top",
          Vector(
            MorphPreparedPublicationFile(
              "nested/Increment61Top.v",
              content,
              reportAsSource = true
            )
          )
        ) match {
          case Left(failure) =>
            assert(failure.stage == MorphVerilogStage.OutputWrite)
            assert(failure.detail.contains("MORPHDL-ONE-FILE-PUBLISH-PARENT-SYMLINK"))
          case Right(paths) => fail(s"expected symlink-parent rejection, received $paths")
        }
        assert(!Files.exists(outside.resolve("Increment61Top.v")))
        assert(!Files.exists(directory.resolve("Increment61Top.lst")))
      }
    } finally deleteTree(outside)
  }

  test("oneFilePerComponent rejects a shared netlist filename before elaboration") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(
        targetDirectory = directory.toString,
        oneFilePerComponent = true
      )
      config.netlistFileName = "all-components.v"
      var factoryRuns = 0
      val result = MorphVerilog.tryGenerate(config) {
        factoryRuns += 1
        hierarchical()
      }
      result match {
        case Left(failure) =>
          assert(failure.stage == MorphVerilogStage.Configuration)
          assert(failure.detail.contains("netlistFileName"))
        case Right(report) => fail(s"expected configuration rejection, received $report")
      }
      assert(factoryRuns == 0)
    }
  }

  test("capture rejects case-insensitive file collisions without parsing module text") {
    withTemporaryDirectory { directory =>
      Files.write(directory.resolve("Top.v"), "module Top; endmodule\n".getBytes(StandardCharsets.UTF_8))
      Files.write(directory.resolve("top.v"), "module top; endmodule\n".getBytes(StandardCharsets.UTF_8))
      MorphPerComponentPublication.capture(directory, "Top") match {
        case Left(failure) =>
          assert(failure.stage == MorphVerilogStage.SingleSourceGeneration)
          assert(failure.detail.contains("FILENAME-COLLISION"))
        case Right(files) => fail(s"expected collision rejection, received $files")
      }
    }
  }

  private def moduleDefinitions(verilog: String): Vector[String] =
    "(?m)^\\s*module\\s+([A-Za-z_][A-Za-z0-9_$]*)\\b".r
      .findAllMatchIn(verilog)
      .map(_.group(1))
      .toVector

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .map(value => f"${value & 0xff}%02x")
      .mkString

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def withTemporaryDirectory[A](body: Path => A): A = {
    val directory = Files.createTempDirectory("morphhdl-increment-61-")
    try body(directory)
    finally deleteTree(directory)
  }

  private def deleteTree(root: Path): Unit = {
    if (Files.exists(root)) {
      val stream = Files.walk(root)
      try stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists)
      finally stream.close()
    }
  }
}
