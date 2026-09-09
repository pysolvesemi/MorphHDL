package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import scala.collection.JavaConverters._

import nativeapplication.{
  BoundedRecursivePowerFixture,
  SIntSignedVerilogBaselineFixture,
  TypedBlackBoxGenericBindingFixture
}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.internals.{BalancedNestedHierarchy, BalancedPublicationHardware}

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

  final case class CompatibilityCase(
      id: String,
      generatedTop: String,
      toolTop: String,
      requiredGeneratedModules: Set[String],
      forbiddenGeneratedModules: Set[String],
      supportFile: Option[(String, String)],
      build: () => Component
  )

  private val SignedExternalStub =
    """module SIntCastHeavyExternal #(
      |  parameter integer WIDTH = 8
      |) (
      |  input  wire signed [WIDTH-1:0] din,
      |  output wire signed [WIDTH-1:0] dout
      |);
      |  assign dout = din;
      |endmodule
      |""".stripMargin

  private val TypedBlackBoxStubs =
    """module TypedExternalLeaf #(
      |  parameter LABEL = "typed",
      |  parameter integer WIDTH = 8,
      |  parameter integer DEPTH = 4,
      |  parameter integer DOUBLE_WIDTH = 16,
      |  parameter integer CONCRETE_ENABLE = 1,
      |  parameter integer ENABLED = 1
      |) (
      |  input  wire [WIDTH-1:0] din,
      |  output wire [WIDTH-1:0] dout
      |);
      |  assign dout = ENABLED ? din : ~din;
      |endmodule
      |
      |module TypedParameterOnlyExternal #(
      |  parameter integer LATENCY = 2
      |) (
      |  input  wire [7:0] din,
      |  output wire [7:0] dout
      |);
      |  assign dout = din ^ {8{LATENCY[0]}};
      |endmodule
      |""".stripMargin

  private val RecursiveToolTop =
    """module Increment61RecursiveToolTop(
      |  input  wire [7:0] x,
      |  output wire [7:0] y
      |);
      |  BoundedRecursivePower #(.N(5)) dut(.x(x), .y(y));
      |endmodule
      |""".stripMargin

  def compatibilityCases: Vector[CompatibilityCase] = Vector(
    CompatibilityCase(
      id = "named-field-storage",
      generatedTop = "NamedFieldVecStorage",
      toolTop = "NamedFieldVecStorage",
      requiredGeneratedModules = Set("NamedFieldVecChild", "NamedFieldVecStorage"),
      forbiddenGeneratedModules = Set.empty,
      supportFile = None,
      build = () => new NamedFieldVecFixture.Storage(
        NamedFieldVecFixture.parameter("WIDTH", default = 5, maximum = 32),
        NamedFieldVecFixture.parameter("BLUE_WIDTH", default = 3, maximum = 32),
        NamedFieldVecFixture.parameter("COUNT", default = 3, maximum = 17)
      )
    ),
    CompatibilityCase(
      id = "stream-fifo",
      generatedTop = "NativeParameterizedStreamFifoHarness",
      toolTop = "NativeParameterizedStreamFifoHarness",
      requiredGeneratedModules = Set("StreamFifo", "NativeParameterizedStreamFifoHarness"),
      forbiddenGeneratedModules = Set.empty,
      supportFile = None,
      build = () => new NativeParameterizedStreamFifoHarness(
        HdlInt.param("DEPTH", default = 5, min = 1, max = 16)
      )
    ),
    CompatibilityCase(
      id = "stream-fifo-cc",
      generatedTop = "NativeStreamFifoCCWidthDepth",
      toolTop = "NativeStreamFifoCCWidthDepth",
      requiredGeneratedModules = Set(
        "StreamFifoCCPopToPushBufferCC",
        "StreamFifoCCPushToPopBufferCC",
        "StreamFifoCC",
        "NativeStreamFifoCCWidthDepth"
      ),
      forbiddenGeneratedModules = Set.empty,
      supportFile = None,
      build = () => new NativeStreamFifoCCWidthDepthHarness(
        HdlInt.param("WIDTH", default = 5, min = 1, max = 32),
        HdlInt.param("DEPTH", default = 8, min = 2, max = 16)
      )
    ),
    CompatibilityCase(
      id = "signed-memory-hierarchy",
      generatedTop = "SIntCastHeavyBaseline",
      toolTop = "SIntCastHeavyBaseline",
      requiredGeneratedModules = Set("SIntCastHeavyChild", "SIntCastHeavyBaseline"),
      forbiddenGeneratedModules = Set("SIntCastHeavyExternal"),
      supportFile = Some("external.v" -> SignedExternalStub),
      build = () => SIntSignedVerilogBaselineFixture.parameterized()
    ),
    CompatibilityCase(
      id = "recursive-generate",
      generatedTop = "BoundedRecursivePower",
      toolTop = "Increment61RecursiveToolTop",
      requiredGeneratedModules = Set("BoundedRecursivePower"),
      forbiddenGeneratedModules = Set.empty,
      supportFile = Some("tool-top.v" -> RecursiveToolTop),
      build = () => BoundedRecursivePowerFixture.parameterized()
    ),
    CompatibilityCase(
      id = "balanced-reduction",
      generatedTop = "BalancedPublication",
      toolTop = "BalancedPublication",
      requiredGeneratedModules = Set("BalancedPublication"),
      forbiddenGeneratedModules = Set.empty,
      supportFile = None,
      build = () => new BalancedPublicationHardware(
        HdlInt.param("WIDTH", default = 5, min = 1, max = 32),
        HdlInt.param("COUNT", default = 3, min = 1, max = 17)
      )
    ),
    CompatibilityCase(
      id = "nested-reduction-hierarchy",
      generatedTop = "BalancedNestedHierarchy",
      toolTop = "BalancedNestedHierarchy",
      requiredGeneratedModules = Set("BalancedNestedFormalChild", "BalancedNestedHierarchy"),
      forbiddenGeneratedModules = Set.empty,
      supportFile = None,
      build = () => new BalancedNestedHierarchy(
        HdlInt.param("WIDTH", default = 5, min = 1, max = 32),
        HdlInt.param("COUNT", default = 3, min = 1, max = 17),
        HdlInt.param("MODE", default = 0, min = 0, max = 2)
      )
    ),
    CompatibilityCase(
      id = "typed-blackbox",
      generatedTop = "TypedBlackBoxGenericTop",
      toolTop = "TypedBlackBoxGenericTop",
      requiredGeneratedModules = Set("TypedBlackBoxGenericTop"),
      forbiddenGeneratedModules = Set("TypedExternalLeaf", "TypedParameterOnlyExternal"),
      supportFile = Some("external.v" -> TypedBlackBoxStubs),
      build = () => TypedBlackBoxGenericBindingFixture.parameterized()
    )
  )

  def publishCompatibilityCase(
      root: Path,
      entry: CompatibilityCase
  ): MorphSingleSourceVerilogReport = {
    val directory = root.resolve(entry.id)
    Files.createDirectories(directory)
    val report = MorphVerilog(
      SpinalConfig(
        targetDirectory = directory.toString,
        oneFilePerComponent = true
      )
    )(entry.build())
    require(report.toplevelName == entry.generatedTop,
      s"${entry.id}: unexpected generated top ${report.toplevelName}")

    val sourcePaths = report.generatedSourcesPaths.map(Paths.get(_))
    val sourceNames = sourcePaths.map(_.getFileName.toString)
    require(sourcePaths.nonEmpty && sourceNames.distinct.size == sourceNames.size,
      s"${entry.id}: duplicate or empty generated source list")
    val definitions = sourcePaths.flatMap { path =>
      require(path.normalize().getParent == directory.normalize(),
        s"${entry.id}: generated source escaped its case directory: $path")
      val modules = moduleDefinitions(read(path))
      require(modules.size == 1,
        s"${entry.id}: ${path.getFileName} owns ${modules.size} module definitions")
      require(path.getFileName.toString == modules.head + ".v",
        s"${entry.id}: ${path.getFileName} does not own module ${modules.head}")
      modules
    }
    require(definitions.distinct.size == definitions.size,
      s"${entry.id}: duplicate generated module definition")
    require(entry.requiredGeneratedModules.subsetOf(definitions.toSet),
      s"${entry.id}: missing generated module(s) ${entry.requiredGeneratedModules.diff(definitions.toSet)}")
    require(entry.forbiddenGeneratedModules.intersect(definitions.toSet).isEmpty,
      s"${entry.id}: external definition was regenerated")
    require(definitions.last == entry.generatedTop,
      s"${entry.id}: generated top is not the final dependency-ordered source")

    val list = read(directory.resolve(entry.generatedTop + ".lst"))
      .split("\n", -1).toVector.filter(_.nonEmpty)
    require(list == sourceNames, s"${entry.id}: source list differs from report order")
    entry.supportFile.foreach { case (name, text) =>
      val support = directory.resolve(name)
      Files.write(support, text.getBytes(StandardCharsets.UTF_8))
      require(!sourceNames.contains(name),
        s"${entry.id}: external/tool support file leaked into generated source ownership")
    }
    report
  }

  private def moduleDefinitions(verilog: String): Vector[String] =
    "(?m)^\\s*module\\s+([A-Za-z_][A-Za-z0-9_$]*)\\b".r
      .findAllMatchIn(verilog).map(_.group(1)).toVector

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
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

    val compatibility = root.resolve("compatibility")
    Files.createDirectories(compatibility)
    val rows = Increment61PerComponentFixture.compatibilityCases.map { entry =>
      Increment61PerComponentFixture.publishCompatibilityCase(compatibility, entry)
      val support = entry.supportFile.map(_._1).getOrElse("-")
      Vector(entry.id, entry.generatedTop, entry.toolTop, support).mkString("\t")
    }
    Files.write(
      compatibility.resolve("cases.tsv"),
      (rows.mkString("\n") + "\n").getBytes(StandardCharsets.UTF_8)
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

  test("roadmap compatibility matrix preserves exact component ownership") {
    withTemporaryDirectory { directory =>
      val compatibility = directory.resolve("compatibility")
      compatibilityCases.foreach { entry =>
        val report = publishCompatibilityCase(compatibility, entry)
        assert(report.generatedSourcesPaths.nonEmpty, entry.id)
      }
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
