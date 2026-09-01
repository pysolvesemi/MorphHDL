package spinal.core

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import scala.collection.JavaConverters._

import org.scalatest.funsuite.AnyFunSuite

import morphhdl.MorphVerilog
import morphhdl.frontend.HdlInt

private object VecEmittedIdentityAdversarialFixture {
  final class ScalarFormalCollisionChild extends Component {
    setDefinitionName("ScalarFormalCollisionChild")
    val values_0 = in(Bits(8 bits)).setName("values_0")
    val observed = out(Bits(8 bits)).setName("observed")

    observed := values_0
  }

  final class CarrierNamedChild extends Component {
    setDefinitionName("values_0")
    val source = in(Bits(8 bits)).setName("source")
    val result = out(Bits(8 bits)).setName("result")

    result := source
  }

  final class ChildTypeMatchesVecCarrier(depth: ElabInt) extends Component {
    setDefinitionName("ChildTypeMatchesVecCarrier")
    val values = in(Vec(Bits(8 bits), depth)).setName("values")
    val observed = out(Bits(8 bits)).setName("observed")

    val child = new CarrierNamedChild
    child.setName("kept_child")
    child.source := values(0)
    observed := child.result
  }

  /** The child formal deliberately has the same spelling as the native
    * carrier for `values(0)`. Only the actual expression denotes that carrier;
    * the named-port label belongs to the child definition's namespace.
    */
  final class ScalarFormalMatchesVecCarrier(depth: ElabInt) extends Component {
    setDefinitionName("ScalarFormalMatchesVecCarrier")
    val values = in(Vec(Bits(8 bits), depth)).setName("values")
    val observed = out(Bits(8 bits)).setName("observed")

    val child = new ScalarFormalCollisionChild
    child.values_0 := values(0)
    observed := child.observed
  }

  final class CarrierNameInCommentAndString(depth: ElabInt) extends Component {
    setDefinitionName("CarrierNameInCommentAndString")
    addComment("carrier token values_0 stays in comment")
    val values = in(Vec(Bits(8 bits), depth)).setName("values")
    val observed = out(Bits(8 bits)).setName("observed")
    observed.addAttribute(
      "morphhdl_note",
      "carrier token values_0 stays in string"
    )

    observed := values(0)
  }

  final class PackedDeclarationNameInString(depth: ElabInt) extends Component {
    setDefinitionName("PackedDeclarationNameInString")
    val values = in(Vec(Bits(8 bits), depth)).setName("values")
    val packedValue = values.asBits.setName("packed_value").dontSimplifyIt()
    packedValue.addAttribute("morphhdl_note", "packed_value")
    val observed = out(Bool()).setName("observed")

    observed := packedValue(0)
  }

  final class CarrierNameInModuleAndAttributeSyntax(depth: ElabInt) extends Component {
    setDefinitionName("values_0")
    val values = in(Vec(Bits(8 bits), depth)).setName("values")
    val observed = out(Bits(8 bits)).setName("observed")
    observed.addAttribute("values_0", "kept *) values_0")

    observed := values(0)
  }

  final class StructuralNameMatchesVecCarrier(depth: ElabInt) extends Component {
    setDefinitionName("StructuralNameMatchesVecCarrier")
    val values = in(Vec(Bits(8 bits), depth)).setName("values")
    val observed = out(Bits(8 bits)).setName("observed")

    observed := values(0)

    val block = ParameterizedStructure.captureBlock(this, None) {
      val captured = Bits(8 bits).setName("captured_value").dontSimplifyIt()
      captured := values(0)
    }
    ParameterizedStructure.registerFor(
      this,
      label = "values_0",
      indexName = "collision_index",
      count = depth.expression,
      body = block,
      sourceLocation = None
    )
  }

  /** The second Vec's preferred aggregate spelling equals the first Vec's
    * exact element-zero carrier.  Occupying the first fallback proves that
    * allocation searches the complete retained namespace instead of applying
    * one hard-coded suffix.
    */
  final class AggregateMatchesForeignCarrier(depth: ElabInt) extends Component {
    setDefinitionName("AggregateMatchesForeignCarrier")
    val values = in(Vec(Bits(8 bits), depth)).setName("values")
    val values_0 = in(Vec(Bits(8 bits), depth)).setName("values_0")
    val fallbackBlocker =
      in(Bool()).setName("values_0_morphhdl_vec")
    val observed = out(Bool()).setName("observed")

    observed := values(0).xorR ^ values_0(0).xorR ^ fallbackBlocker
  }
}

class VecEmittedIdentityAdversarialTests extends AnyFunSuite {
  import VecEmittedIdentityAdversarialFixture._

  test("constant Vec carrier replacement preserves coincident child formal labels") {
    withTemporaryDirectory { directory =>
      val fileName = "vec_formal_label_collision.v"
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = fileName
      val report = MorphVerilog(config) {
        new ScalarFormalMatchesVecCarrier(
          HdlInt.param("DEPTH", default = 3, min = 1, max = 8).asElabInt
        )
      }
      val verilog = new String(
        Files.readAllBytes(Paths.get(report.generatedSourcesPaths.head)),
        StandardCharsets.UTF_8
      )

      val exactConnection =
        "(?m)^\\s*\\.values_0\\s*\\(\\s*values(?:\\s*\\[[^\\]]+\\])+\\s*\\)\\s*,?\\s*(?://.*)?$".r
      assert(exactConnection.findFirstIn(verilog).nonEmpty, verilog)
      assert(
        "\\.\\s*values\\s*\\[".r.findFirstIn(verilog).isEmpty,
        s"child formal label was rewritten as a Vec slice\n$verilog"
      )
    }
  }

  test("constant Vec carrier replacement preserves coincident child module types") {
    withTemporaryDirectory { directory =>
      val fileName = "vec_child_type_collision.v"
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = fileName
      val report = MorphVerilog(config) {
        new ChildTypeMatchesVecCarrier(
          HdlInt.param("DEPTH", default = 3, min = 1, max = 8).asElabInt
        )
      }
      val verilog = new String(
        Files.readAllBytes(Paths.get(report.generatedSourcesPaths.head)),
        StandardCharsets.UTF_8
      )

      assert(
        "(?m)^\\s*values_0\\s+kept_child\\s*\\(".r
          .findFirstIn(verilog)
          .nonEmpty,
        verilog
      )
      assert(
        "\\.source\\s*\\(\\s*values(?:\\s*\\[[^\\]]+\\])+\\s*\\)".r
          .findFirstIn(verilog)
          .nonEmpty,
        verilog
      )
    }
  }

  test("constant Vec carrier replacement preserves comments and string literals") {
    withTemporaryDirectory { directory =>
      val fileName = "vec_comment_string_collision.v"
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = fileName
      val report = MorphVerilog(config) {
        new CarrierNameInCommentAndString(
          HdlInt.param("DEPTH", default = 3, min = 1, max = 8).asElabInt
        )
      }
      val verilog = new String(
        Files.readAllBytes(Paths.get(report.generatedSourcesPaths.head)),
        StandardCharsets.UTF_8
      )

      assert(
        verilog.contains("\"carrier token values_0 stays in string\""),
        verilog
      )
      assert(
        "assign\\s+observed\\s*=\\s*values\\s*\\[[^\\]]+\\]\\s*;".r
          .findFirstIn(verilog)
          .nonEmpty,
        verilog
      )
    }
  }

  test("packed Vec declaration replacement preserves same-name attribute strings") {
    withTemporaryDirectory { directory =>
      val fileName = "vec_packed_declaration_string_collision.v"
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = fileName
      val report = MorphVerilog(config) {
        new PackedDeclarationNameInString(
          HdlInt.param("DEPTH", default = 3, min = 1, max = 8).asElabInt
        )
      }
      val verilog = new String(
        Files.readAllBytes(Paths.get(report.generatedSourcesPaths.head)),
        StandardCharsets.UTF_8
      )

      assert(verilog.contains("\"packed_value\""), verilog)
      val declaration =
        "(?m)^\\s*(?:\\(\\*.*\\*\\)\\s*)?wire\\s+\\[[^\\]]*DEPTH[^\\]]*\\]\\s+packed_value\\s*;\\s*$".r
      assert(declaration.findFirstIn(verilog).nonEmpty, verilog)
      assert(
        "assign\\s+observed\\s*=\\s*packed_value\\s*\\[\\s*0\\s*\\]\\s*;".r
          .findFirstIn(verilog)
          .nonEmpty,
        verilog
      )
    }
  }

  test("constant Vec carrier replacement preserves module and attribute syntax") {
    withTemporaryDirectory { directory =>
      val fileName = "vec_module_attribute_syntax_collision.v"
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = fileName
      val report = MorphVerilog(config) {
        new CarrierNameInModuleAndAttributeSyntax(
          HdlInt.param("DEPTH", default = 3, min = 1, max = 8).asElabInt
        )
      }
      val verilog = new String(
        Files.readAllBytes(Paths.get(report.generatedSourcesPaths.head)),
        StandardCharsets.UTF_8
      )

      assert("(?m)^\\s*module\\s+values_0\\b".r.findFirstIn(verilog).nonEmpty, verilog)
      assert(
        "values_0\\s*=\\s*\"kept \\*\\) values_0\"".r
          .findFirstIn(verilog)
          .nonEmpty,
        verilog
      )
      assert(
        "assign\\s+observed\\s*=\\s*values\\s*\\[[^\\]]+\\]\\s*;".r
          .findFirstIn(verilog)
          .nonEmpty,
        verilog
      )
    }
  }

  test("exact structural names cannot collide with retained Vec carriers") {
    withTemporaryDirectory { directory =>
      val fileName = "vec_structural_name_collision.v"
      val rtl = directory.resolve(fileName)
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = fileName
      val failure = MorphVerilog.tryGenerate(config) {
        new StructuralNameMatchesVecCarrier(
          HdlInt.param("DEPTH", default = 3, min = 1, max = 8).asElabInt
        )
      } match {
        case Left(value)  => value
        case Right(value) => fail(s"expected structural/Vec name collision rejection, received $value")
      }

      assert(
        failure.detail.contains(
          "SPINAL-PARAMETERIZED-VERILOG-VEC-STRUCTURAL-NAME-COLLISION"
        ),
        failure.detail
      )
      assert(!Files.exists(rtl), "structural/Vec name collision published partial RTL")
    }
  }

  test("Vec aggregate allocation avoids foreign carrier names and occupied fallbacks") {
    withTemporaryDirectory { directory =>
      val fileName = "vec_aggregate_foreign_carrier_collision.v"
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = fileName
      val report = MorphVerilog(config) {
        new AggregateMatchesForeignCarrier(
          HdlInt.param("DEPTH", default = 3, min = 1, max = 8).asElabInt
        )
      }
      val verilog = new String(
        Files.readAllBytes(Paths.get(report.generatedSourcesPaths.head)),
        StandardCharsets.UTF_8
      )

      def inputDeclaration(name: String) =
        ("(?m)^\\s*input\\s+wire(?:\\s+\\[[^\\]]+\\])?\\s+" +
          java.util.regex.Pattern.quote(name) + "\\s*[,;]\\s*$").r

      assert(inputDeclaration("values").findFirstIn(verilog).nonEmpty, verilog)
      assert(
        inputDeclaration("values_0_morphhdl_vec").findFirstIn(verilog).nonEmpty,
        verilog
      )
      assert(
        inputDeclaration("values_0_morphhdl_vec_2").findFirstIn(verilog).nonEmpty,
        verilog
      )
      assert(
        inputDeclaration("values_0").findFirstIn(verilog).isEmpty,
        s"foreign carrier spelling was reused as an aggregate declaration\n$verilog"
      )
    }
  }

  private def withTemporaryDirectory(body: Path => Unit): Unit = {
    val directory = Files.createTempDirectory("morphhdl-vec-emitted-identity-")
    try body(directory)
    finally {
      if (Files.exists(directory)) {
        val paths = Files.walk(directory)
        try
          paths
            .iterator()
            .asScala
            .toVector
            .sortBy(_.getNameCount)
            .reverse
            .foreach(path => Files.deleteIfExists(path))
        finally paths.close()
      }
    }
  }
}
