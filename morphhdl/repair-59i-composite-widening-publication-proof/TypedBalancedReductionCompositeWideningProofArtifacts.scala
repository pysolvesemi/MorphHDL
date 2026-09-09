package spinal.core.internals

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import morphhdl.{MorphNamedFieldVectors, MorphSignedCasts, MorphSignedDeclarations, MorphVerilog}
import morphhdl.frontend.HdlInt
import nativeapplication.NativeCompositeWideningReference
import spinal.core._
import spinal.lib._

object CompositeWideningProofHelpers {
  def typedWidth(value: BaseType): ElabInt =
    ElabInt.fromExpression(ParameterizedWidth.expressionOf(value)
      .getOrElse(ElabInt.literal(value.getBitsWidth).expression))

  def combine(a: BalancedCompositeWideningValue,
      b: BalancedCompositeWideningValue): BalancedCompositeWideningValue = {
    val unsignedSum = a.unsignedSum +^ b.unsignedSum
    val unsignedProduct = a.unsignedProduct * b.unsignedProduct
    val signedSum = a.signedSum +^ b.signedSum
    val signedProduct = a.signedProduct * b.signedProduct
    val result = BalancedCompositeWideningValue(typedWidth(unsignedSum), typedWidth(unsignedProduct),
      typedWidth(signedSum), typedWidth(signedProduct))
    result.unsignedSum := unsignedSum
    result.unsignedProduct := unsignedProduct
    result.signedSum := signedSum
    result.signedProduct := signedProduct
    result
  }
}

final class CompositeWideningProofCandidate(
    unsignedSumWidth: HdlInt,
    unsignedProductWidth: HdlInt,
    signedSumWidth: HdlInt,
    signedProductWidth: HdlInt,
    count: HdlInt,
    moduleName: String) extends Component {
  setDefinitionName(moduleName)
  private def width(value: HdlInt): ElabInt = ElabInt.fromExpression(value.bits.expression.get)
  val values = in(Vec(BalancedCompositeWideningValue(width(unsignedSumWidth),
    width(unsignedProductWidth), width(signedSumWidth), width(signedProductWidth)), count))
    .setName("values")
  val reduced = values.reduceBalancedTree(
    (a: BalancedCompositeWideningValue, b: BalancedCompositeWideningValue) =>
      CompositeWideningProofHelpers.combine(a, b))
  val result = out(BalancedCompositeWideningValue(
    CompositeWideningProofHelpers.typedWidth(reduced.unsignedSum),
    CompositeWideningProofHelpers.typedWidth(reduced.unsignedProduct),
    CompositeWideningProofHelpers.typedWidth(reduced.signedSum),
    CompositeWideningProofHelpers.typedWidth(reduced.signedProduct))).setName("result")
  result := reduced
}

object TypedBalancedReductionCompositeWideningProofArtifacts {
  final case class Defaults(name: String, us: Int, up: Int, ss: Int, sp: Int, count: Int)
  val defaults = Vector(Defaults("singleton", 5, 5, 5, 5, 1),
    Defaults("alternate", 3, 4, 5, 2, 5))
  val layouts = Vector("packed", "fields")
  val signedModes = Vector("legacy", "declarations", "casts")
  val shapes = Vector((1, 1, 1, 1), (3, 5, 4, 2), (5, 3, 7, 4),
    (8, 4, 1, 6), (16, 7, 5, 3))
  val counts = Vector(1, 2, 3, 5)

  private def config(directory: Path, file: String): SpinalConfig = {
    Files.createDirectories(directory)
    val result = SpinalConfig(targetDirectory = directory.toString,
      headerWithDate = false, bitVectorWidthMax = 65536)
    result.netlistFileName = file
    result
  }
  private def quote(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
  private def relative(root: Path, path: Path): String =
    root.relativize(path).toString.replace('\\', '/')

  private def candidate(root: Path, layout: String, signed: String,
      defaults: Defaults): (String, Path) = {
    val module = s"CompositeWidening_${layout}_${signed}_${defaults.name}"
    val directory = root.resolve(s"candidate/${layout}-${signed}-${defaults.name}")
    val base = config(directory, module + ".v")
    val shaped = if (layout == "fields") MorphNamedFieldVectors.enable(base) else base
    val configured = signed match {
      case "legacy" => shaped
      case "declarations" => MorphSignedDeclarations.enable(shaped)
      case "casts" => MorphSignedCasts.enable(shaped)
    }
    MorphVerilog(configured) {
      new CompositeWideningProofCandidate(
        HdlInt.param("UNSIGNED_SUM_WIDTH", defaults.us, 1, 16),
        HdlInt.param("UNSIGNED_PRODUCT_WIDTH", defaults.up, 1, 16),
        HdlInt.param("SIGNED_SUM_WIDTH", defaults.ss, 1, 16),
        HdlInt.param("SIGNED_PRODUCT_WIDTH", defaults.sp, 1, 16),
        HdlInt.param("COUNT", defaults.count, 1, 5), module)
    }
    val path = directory.resolve(module + ".v")
    require(Files.isRegularFile(path), "missing composite widening candidate")
    module -> path
  }

  def main(args: Array[String]): Unit = {
    require(args.length == 1, "usage: TypedBalancedReductionCompositeWideningProofArtifacts OUTPUT")
    val root = Paths.get(args(0)).toAbsolutePath.normalize()
    Files.createDirectories(root)
    val candidates = for {
      defaults <- defaults
      layout <- layouts
      signed <- signedModes
    } yield {
      val (module, path) = candidate(root, layout, signed, defaults)
      s"""{"layout":${quote(layout)},"signed_mode":${quote(signed)},"defaults":${quote(defaults.name)},"default_us":${defaults.us},"default_up":${defaults.up},"default_ss":${defaults.ss},"default_sp":${defaults.sp},"default_count":${defaults.count},"module":${quote(module)},"file":${quote(relative(root, path))}}"""
    }
    val cases = for {
      (us, up, ss, sp) <- shapes
      count <- counts
    } yield {
      val id = s"us${us}_up${up}_ss${ss}_sp${sp}_n$count"
      val module = "NativeCompositeWidening_" + id
      val directory = root.resolve("reference/" + id)
      val report = config(directory, module + ".v").generateVerilog(
        new NativeCompositeWideningReference(us, up, ss, sp, count, module))
      val top = report.toplevel
      val outputs = Vector(
        "unsignedSum" -> top.result.unsignedSum.getBitsWidth,
        "unsignedProduct" -> top.result.unsignedProduct.getBitsWidth,
        "signedSum" -> top.result.signedSum.getBitsWidth,
        "signedProduct" -> top.result.signedProduct.getBitsWidth)
        .map { case (name, bits) => quote(name) + ":" + bits }.mkString(",")
      val path = directory.resolve(module + ".v")
      require(Files.isRegularFile(path), "missing independent composite widening reference")
      s"""{"id":${quote(id)},"us":$us,"up":$up,"ss":$ss,"sp":$sp,"count":$count,"module":${quote(module)},"file":${quote(relative(root, path))},"outputs":{$outputs}}"""
    }
    val manifest = s"""{"schema":1,"scope":"59i-composite-widening-publication-proof","candidates":[${candidates.mkString(",")}],"cases":[${cases.mkString(",")}]}
"""
    Files.write(root.resolve("manifest.json"), manifest.getBytes(StandardCharsets.UTF_8))
  }
}
