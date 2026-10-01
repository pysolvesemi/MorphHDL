package spinal.core.internals

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import morphhdl.{MorphNamedFieldVectors, MorphVerilog}
import morphhdl.frontend.HdlInt
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._

/** Fixed-width unsigned saturation combined with other whole-record fields.
  * The unchanged native callback remains the sole datapath description.
  */
final case class BalancedSaturatingRecord(width: HdlInt, tagWidth: HdlInt) extends Bundle {
  val value = UInt(width bits)
  val tag = Bits(tagWidth bits)
  val valid = Bool()
}

final class BalancedSaturatingReduction(width: HdlInt, tagWidth: HdlInt,
    count: HdlInt, moduleName: String) extends Component {
  setDefinitionName(moduleName)
  val values = in(Vec(BalancedSaturatingRecord(width, tagWidth), count)).setName("values")
  val result = out(BalancedSaturatingRecord(width, tagWidth)).setName("result")

  result := values.reduceBalancedTree((a: BalancedSaturatingRecord, b: BalancedSaturatingRecord) => {
    val combined = cloneOf(a)
    combined.value := a.value +| b.value
    combined.tag := a.tag ^ b.tag
    combined.valid := a.valid | b.valid
    combined
  })
}

object TypedBalancedReductionCompositeSaturationArtifacts {
  val layouts = Vector("packed", "fields")

  private def config(directory: Path, file: String): SpinalConfig = {
    Files.createDirectories(directory)
    val result = SpinalConfig(targetDirectory = directory.toString,
      headerWithDate = false, bitVectorWidthMax = 8192)
    result.netlistFileName = file
    result
  }

  def emit(root: Path, layout: String): Path = {
    require(layouts.contains(layout))
    val module = "BalancedSaturatingReduction_" + layout
    val file = module + ".v"
    val base = config(root.resolve(layout), file)
    val selected = if (layout == "fields") MorphNamedFieldVectors.enable(base) else base
    MorphVerilog(selected) {
      new BalancedSaturatingReduction(
        HdlInt.param("WIDTH", 5, 1, 32),
        HdlInt.param("TAG_WIDTH", 3, 1, 16),
        HdlInt.param("COUNT", 5, 1, 17),
        module)
    }
    val path = root.resolve(layout).resolve(file)
    require(Files.isRegularFile(path), "saturation candidate was not emitted: " + path)
    path
  }

  // Each concrete reference invokes the same native Component through ordinary
  // SpinalVerilog, without the MorphVerilog reduction backend or publisher.
  val hardwareCases: Vector[(Int, Int, Int)] = (for {
    (width, tagWidth) <- Vector((1, 1), (1, 16), (32, 1), (32, 16),
      (5, 3), (3, 5), (5, 5))
    count <- Vector(1, 2, 3, 5, 8, 9, 16, 17)
  } yield (width, tagWidth, count)).toVector

  def main(args: Array[String]): Unit = {
    require(args.length == 1, "expected saturation hardware artifact directory")
    val root = Paths.get(args(0)).toAbsolutePath.normalize()
    Files.createDirectories(root)
    val candidates = layouts.map { layout =>
      emit(root.resolve("candidate"), layout)
      val name = "BalancedSaturatingReduction_" + layout
      s"""{"layout":"$layout","module":"$name","file":"candidate/$layout/$name.v"}"""
    }
    val references = hardwareCases.map { case (width, tagWidth, count) =>
      val id = s"w${width}_t${tagWidth}_n$count"
      val name = "NativeSaturatingReduction_" + id
      val selected = config(root.resolve("native"), name + ".v").copy(headerWithRepoHash = false)
      SpinalVerilog(selected) {
        new BalancedSaturatingReduction(HdlInt.literal(width), HdlInt.literal(tagWidth),
          HdlInt.literal(count), name)
      }
      require(Files.isRegularFile(root.resolve("native").resolve(name + ".v")))
      s"""{"id":"$id","width":$width,"tag_width":$tagWidth,"count":$count,"module":"$name","file":"native/$name.v"}"""
    }
    val manifest = s"""{"schema":1,"scope":"59i-composite-saturation-hardware","candidates":[${candidates.mkString(",")}],"cases":[${references.mkString(",")}]}
"""
    Files.write(root.resolve("manifest.json"), manifest.getBytes(StandardCharsets.UTF_8))
  }
}

class TypedBalancedReductionCompositeSaturationTests extends AnyFunSuite {
  private def text(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def outputRoot(): Path = Option(System.getenv("MORPHHDL_59I_SAT_OUTPUT"))
    .filter(_.nonEmpty).map(Paths.get(_)).getOrElse(Files.createTempDirectory("balanced-saturation-"))

  test("unsigned saturation composes with whole-record reduction in packed and field layouts") {
    val root = outputRoot()
    for (layout <- TypedBalancedReductionCompositeSaturationArtifacts.layouts) {
      val rtl = text(TypedBalancedReductionCompositeSaturationArtifacts.emit(root, layout))
      assert(rtl.contains("WIDTH = 5"), rtl)
      assert(rtl.contains("TAG_WIDTH = 3"), rtl)
      assert(rtl.contains("COUNT = 5"), rtl)
      assert(rtl.contains("morphhdl_balanced_"), rtl)
      assert(rtl.contains("result_value") && rtl.contains("result_tag") && rtl.contains("result_valid"), rtl)
      if (layout == "fields") {
        assert(rtl.contains("values_value") && rtl.contains("values_tag") && rtl.contains("values_valid"), rtl)
      }
      var depth = 0
      "\\b(generate|endgenerate)\\b".r.findAllIn(rtl).foreach {
        case "generate" => depth += 1; assert(depth == 1, "nested generate region\n" + rtl)
        case "endgenerate" => depth -= 1; assert(depth == 0, "unbalanced generate region\n" + rtl)
      }
      assert(depth == 0)
    }
  }

  test("native fixed-width saturation publishes with symbolic counts in both layouts") {
    // This is a separate, genuinely constant-width profile, not a weakening of
    // the independent WIDTH/TAG_WIDTH override test above. The only datapath
    // description remains UInt.+| and the unchanged native balanced reduction.
    val root = outputRoot().resolve("constant-width")
    for (layout <- TypedBalancedReductionCompositeSaturationArtifacts.layouts) {
      val directory = root.resolve(layout)
      Files.createDirectories(directory)
      val module = "BalancedSaturatingFixedWidth_" + layout
      val config = SpinalConfig(targetDirectory = directory.toString,
        headerWithDate = false, bitVectorWidthMax = 8192)
      config.netlistFileName = module + ".v"
      val selected = if (layout == "fields") MorphNamedFieldVectors.enable(config) else config
      MorphVerilog(selected) {
        new BalancedSaturatingReduction(HdlInt.literal(5), HdlInt.literal(3),
          HdlInt.param("COUNT", 5, 1, 17), module)
      }
      val rtl = text(directory.resolve(module + ".v"))
      assert(rtl.contains("COUNT = 5"), rtl)
      assert(!rtl.contains("WIDTH = 5") && !rtl.contains("TAG_WIDTH = 3"), rtl)
      assert(rtl.contains("morphhdl_balanced_") && rtl.contains("generate"), rtl)
      assert(rtl.contains("result_value") && rtl.contains("result_tag") &&
        rtl.contains("result_valid"), rtl)
      if (layout == "fields") assert(rtl.contains("values_value") &&
        rtl.contains("values_tag") && rtl.contains("values_valid"), rtl)
    }
  }

  private def native: ElabBalancedReduction.Native[BalancedSaturatingRecord] =
    (values, operation, bridge) => new TraversableOnceAnyPimped[BalancedSaturatingRecord](values)
      .reduceBalancedTree(operation, bridge)

  private def combine(a: BalancedSaturatingRecord, b: BalancedSaturatingRecord): BalancedSaturatingRecord = {
    val result = cloneOf(a)
    result.value := a.value +| b.value
    result.tag := a.tag ^ b.tag
    result.valid := a.valid | b.valid
    result
  }

  private def withFixedRecords(body: Vec[BalancedSaturatingRecord] => Unit): Unit = {
    SpinalConfig(targetDirectory = Files.createTempDirectory("saturation-partition-").toString,
      headerWithDate = false, headerWithRepoHash = false).generateVerilog(new Component {
      val records = in(Vec(BalancedSaturatingRecord(HdlInt.literal(5), HdlInt.literal(5)),
        HdlInt.param("COUNT", 1, 1, 5)))
      body(records)
      val keep = out Bool()
      keep := False
    })
  }

  private def capture(records: Vec[BalancedSaturatingRecord],
      operation: (BalancedSaturatingRecord, BalancedSaturatingRecord) => BalancedSaturatingRecord = combine _
  ): TypedBalancedReductionCompositeReplay.Certificate[BalancedSaturatingRecord] =
    TypedBalancedReductionCompositeReplay.capture(records, operation,
      (value: BalancedSaturatingRecord, _: Int) => value, native)

  private def details(error: Throwable): String =
    if (error == null) "" else Option(error.getMessage).getOrElse("") + "\n" + details(error.getCause)

  test("native saturation field partitions replay singleton and odd counts without rerunning callbacks") {
    withFixedRecords { records =>
      var calls = 0
      val proof = capture(records, (a, b) => { calls += 1; combine(a, b) })
      val capturedCalls = calls
      assert(capturedCalls == 4)
      assert(!proof.hasWidening, "fixed-width conditional replay must not masquerade as widening")
      for (count <- 1 to 5) {
        val result = proof.replay(records.vec.take(count).toVector)
        assert(result.flattenLocalName == Seq("value", "tag", "valid"))
        assert(result.flatten.map(_.getBitsWidth) == Seq(5, 5, 1))
        assert(proof.latencyFor(count) == 0)
        assert(count != 1 || (result eq records.vec.head))
      }
      assert(calls == capturedCalls)
      proof.requireFreshness()
    }
  }

  test("conditional field partitions reject an equal-width cross-field read") {
    val error = intercept[Exception] {
      withFixedRecords { records =>
        capture(records, (a, b) => {
          val result = cloneOf(a)
          // TAG has the same concrete width as VALUE, but is not that operand.
          result.value := a.value +| b.tag.asUInt
          result.tag := a.tag ^ b.tag
          result.valid := a.valid | b.valid
          result
        })
      }
    }
    assert(details(error).contains("EXTERNAL-READ"), details(error))
  }

  test("conditional field partitions reject writes to a caller-owned operand") {
    val error = intercept[Exception] {
      withFixedRecords { records =>
        capture(records, (a, b) => {
          a.tag := b.tag
          combine(a, b)
        })
      }
    }
    assert(details(error).contains("CALLBACK-EXTERNAL-WRITE"), details(error))
  }

  test("conditional field replay freezes the native saturation condition") {
    withFixedRecords { records =>
      val proof = capture(records)
      val callback = proof.captured.rows.flatMap(_.operator).head
      val statement = callback.statements.collectFirst { case value: WhenStatement => value }.get
      val original = statement.cond
      statement.cond = new BoolLiteral(false)
      try {
        val error = intercept[IllegalArgumentException](proof.requireFreshness())
        // Replacing the entire predicate also makes its exact native cone
        // unreachable. The closure proof rejects that before stale-key replay.
        assert(details(error).contains("GRAPH-UNREACHABLE"), details(error))
      } finally statement.cond = original
      proof.requireFreshness()
    }
  }

  test("conditional field replay freezes every native part-selection bound") {
    withFixedRecords { records =>
      val proof = capture(records)
      val callback = proof.captured.rows.flatMap(_.operator).head
      val ranges = scala.collection.mutable.ArrayBuffer.empty[BitVectorRangedAccessFixed]
      def visit(expression: Expression): Unit = {
        expression match { case value: BitVectorRangedAccessFixed => ranges += value; case _ => }
        expression.foreachExpression(visit)
      }
      callback.assignments.foreach(value => visit(value.source))
      assert(ranges.nonEmpty, "native saturation must actually contain fixed part selections")
      for (range <- ranges.distinct) {
        val hi = range.hi
        range.hi = hi + 1
        val error = intercept[IllegalArgumentException](proof.requireFreshness())
        assert(details(error).contains("CHANGED") || details(error).contains("STALE"), details(error))
        range.hi = hi
        proof.requireFreshness()
      }
    }
  }

  test("singleton and odd concrete reductions retain native saturation semantics") {
    val directory = Files.createTempDirectory("balanced-saturation-concrete-")
    val file = "BalancedSaturatingConcrete.v"
    val config = SpinalConfig(targetDirectory = directory.toString,
      headerWithDate = false, headerWithRepoHash = false)
    config.netlistFileName = file
    SpinalVerilog(config) {
      new BalancedSaturatingReduction(HdlInt.literal(3), HdlInt.literal(2),
        HdlInt.literal(5), "BalancedSaturatingConcrete")
    }
    val rtl = text(directory.resolve(file))
    assert(rtl.contains("module BalancedSaturatingConcrete"), rtl)
    assert(!rtl.contains("parameter"), rtl)
    assert(rtl.contains("result_value") && rtl.contains("result_valid"), rtl)
  }
}
