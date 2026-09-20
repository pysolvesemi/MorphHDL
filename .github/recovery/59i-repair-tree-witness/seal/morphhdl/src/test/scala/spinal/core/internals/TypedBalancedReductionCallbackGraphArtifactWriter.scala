package spinal.core.internals

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import morphhdl.MorphVerilog
import morphhdl.frontend.HdlInt
import spinal.core._
import spinal.lib._

/** User-authored, field-free helper: its actual body must pass effect admission. */
object BalancedCallbackPureHelper {
  def combine(left: UInt, right: UInt): UInt = {
    val sum = left + right
    sum ^ left
  }
}

/** Public native helper source; neither callback graphs nor RTL are handwritten by replay. */
final class BalancedCallbackGraphHardware(width: HdlInt, count: HdlInt,
    moduleName: String) extends Component {
  setDefinitionName(moduleName)
  val dataIn = in(Vec(UInt(width bits), count)).setName("dataIn")
  val bias = in(UInt(width bits)).setName("bias")
  val otherBias = in(UInt(width bits)).setName("otherBias")
  val signedIn = in(Vec(SInt(width bits), count)).setName("signedIn")
  val signedBias = in(SInt(width bits)).setName("signedBias")
  val bitsIn = in(Vec(Bits(width bits), count)).setName("bitsIn")
  val boolIn = in(Vec(Bool(), count)).setName("boolIn")
  val composed = out(UInt(width bits)).setName("composed")
  val subtraction = out(UInt(width bits)).setName("subtraction")
  val helper = out(UInt(width bits)).setName("helper")
  val selected = out(UInt(width bits)).setName("selected")
  val conditioned = out(UInt(width bits)).setName("conditioned")
  val sliced = out(UInt(width bits)).setName("sliced")
  val part = out(UInt(width bits)).setName("part")
  val biased = out(UInt(width bits)).setName("biased")
  val alternate = out(UInt(width bits)).setName("alternate")
  val saturated = out(UInt(width bits)).setName("saturated")
  val signedComposed = out(SInt(width bits)).setName("signedComposed")
  val signedSub = out(SInt(width bits)).setName("signedSub")
  val signedBiased = out(SInt(width bits)).setName("signedBiased")
  val signedSelect = out(SInt(width bits)).setName("signedSelect")
  val bitsComposed = out(Bits(width bits)).setName("bitsComposed")
  val boolComposed = out(Bool()).setName("boolComposed")

  composed := dataIn.reduceBalancedTree((a: UInt, b: UInt) => (a + b) ^ a)
  subtraction := dataIn.reduceBalancedTree((a: UInt, b: UInt) => a - b)
  helper := dataIn.reduceBalancedTree((a: UInt, b: UInt) => BalancedCallbackPureHelper.combine(a, b))
  selected := dataIn.reduceBalancedTree((a: UInt, b: UInt) => Mux(a(0), a, b))
  conditioned := dataIn.reduceBalancedTree((a: UInt, b: UInt) => {
    val result = UInt()
    result := b
    when(a > b) { result := a }
    result
  })

  // Local aliases are explicit exact closure operands, not a capture of this Component.
  locally {
    val callbackWidth = width.asElabInt
    sliced := dataIn.reduceBalancedTree((a: UInt, b: UInt) =>
      (a.asBits ## b.asBits).asUInt.resize(callbackWidth) ^ a)
    part := dataIn.reduceBalancedTree((a: UInt, b: UInt) =>
      a(0, 1 bits).resize(callbackWidth) ^ b)
  }
  locally {
    val capturedBias = bias
    biased := dataIn.reduceBalancedTree((a: UInt, b: UInt) => (a + b) + capturedBias)
  }
  locally {
    val capturedBias = otherBias
    alternate := dataIn.reduceBalancedTree((a: UInt, b: UInt) => (a ^ b) + capturedBias)
  }
  locally {
    val callbackWidth = width.asElabInt
    saturated := dataIn.reduceBalancedTree((a: UInt, b: UInt) => {
      val sum = a.resize(callbackWidth + 1) + b.resize(callbackWidth + 1)
      // Width remains a native typed root; no host Int or BigInt width witness.
      val maximum = UInt(callbackWidth.bits)
      maximum := ~U(0).resize(callbackWidth)
      Mux(sum > maximum.resize(callbackWidth + 1), maximum, sum.resize(callbackWidth))
    })
  }
  signedComposed := signedIn.reduceBalancedTree((a: SInt, b: SInt) => (a + b) ^ a)
  signedSub := signedIn.reduceBalancedTree((a: SInt, b: SInt) => a - b)
  locally {
    val capturedBias = signedBias
    signedBiased := signedIn.reduceBalancedTree((a: SInt, b: SInt) => (a + b) + capturedBias)
  }
  signedSelect := signedIn.reduceBalancedTree((a: SInt, b: SInt) => Mux(a > b, a, b))
  bitsComposed := bitsIn.reduceBalancedTree((a: Bits, b: Bits) => (a ^ b) & a)
  boolComposed := boolIn.reduceBalancedTree((a: Bool, b: Bool) => (a ^ b) | a)
}

/** Separately elaborated ordinary concrete Spinal source. No candidate/replay helpers. */
final class BalancedCallbackGraphReference(width: Int, count: Int) extends Component {
  setDefinitionName(s"BalancedCallbackGraphReference_w${width}_n$count")
  val dataIn = in(Bits(width * count bits)).setName("dataIn")
  val bias = in(UInt(width bits)).setName("bias")
  val otherBias = in(UInt(width bits)).setName("otherBias")
  val signedIn = in(Bits(width * count bits)).setName("signedIn")
  val signedBias = in(SInt(width bits)).setName("signedBias")
  val bitsIn = in(Bits(width * count bits)).setName("bitsIn")
  val boolIn = in(Bits(count bits)).setName("boolIn")
  val values = Vector.tabulate(count)(i => dataIn(i * width, width bits).asUInt)
  val signedValues = Vector.tabulate(count)(i => signedIn(i * width, width bits).asSInt)
  val bitsValues = Vector.tabulate(count)(i => bitsIn(i * width, width bits))
  val boolValues = Vector.tabulate(count)(i => boolIn(i))
  val composed = out(UInt(width bits)).setName("composed")
  val subtraction = out(UInt(width bits)).setName("subtraction")
  val helper = out(UInt(width bits)).setName("helper")
  val selected = out(UInt(width bits)).setName("selected")
  val conditioned = out(UInt(width bits)).setName("conditioned")
  val sliced = out(UInt(width bits)).setName("sliced")
  val part = out(UInt(width bits)).setName("part")
  val biased = out(UInt(width bits)).setName("biased")
  val alternate = out(UInt(width bits)).setName("alternate")
  val saturated = out(UInt(width bits)).setName("saturated")
  val signedComposed = out(SInt(width bits)).setName("signedComposed")
  val signedSub = out(SInt(width bits)).setName("signedSub")
  val signedBiased = out(SInt(width bits)).setName("signedBiased")
  val signedSelect = out(SInt(width bits)).setName("signedSelect")
  val bitsComposed = out(Bits(width bits)).setName("bitsComposed")
  val boolComposed = out(Bool()).setName("boolComposed")

  def exact(result: UInt): UInt = {
    require(result.getClass == classOf[UInt], "independent native leaf kind changed")
    require(result.getWidth == width, "independent native reduction result width changed")
    result
  }
  composed := exact(values.reduceBalancedTree((a, b) => (a + b) ^ a))
  subtraction := exact(values.reduceBalancedTree((a, b) => a - b))
  // This reference deliberately inlines the semantics instead of calling the candidate helper.
  helper := exact(values.reduceBalancedTree((a, b) => (a + b) ^ a))
  selected := exact(values.reduceBalancedTree((a, b) => Mux(a(0), a, b)))
  conditioned := exact(values.reduceBalancedTree((a, b) => {
    val result = UInt(width bits)
    result := b
    when(a > b) { result := a }
    result
  }))
  sliced := exact(values.reduceBalancedTree((a, b) =>
    (a.asBits ## b.asBits).asUInt.resize(width) ^ a))
  part := exact(values.reduceBalancedTree((a, b) => a(0, 1 bits).resize(width) ^ b))
  biased := exact(values.reduceBalancedTree((a, b) => (a + b) + bias))
  alternate := exact(values.reduceBalancedTree((a, b) => (a ^ b) + otherBias))
  saturated := exact(values.reduceBalancedTree((a, b) => {
    val sum = a.resize(width + 1) + b.resize(width + 1)
    require(sum.getWidth == width + 1, "independent saturation carry width changed")
    val maximum = U((BigInt(1) << width) - 1, width bits)
    Mux(sum > maximum.resize(width + 1), maximum, sum.resize(width))
  }))
  def exactSigned(result: SInt): SInt = {
    require(result.getClass == classOf[SInt] && result.getWidth == width,
      "independent native signed result kind/width changed")
    result
  }
  signedComposed := exactSigned(signedValues.reduceBalancedTree((a, b) => (a + b) ^ a))
  signedSub := exactSigned(signedValues.reduceBalancedTree((a, b) => a - b))
  signedBiased := exactSigned(signedValues.reduceBalancedTree((a, b) => (a + b) + signedBias))
  signedSelect := exactSigned(signedValues.reduceBalancedTree((a, b) => Mux(a > b, a, b)))
  val bitsResult = bitsValues.reduceBalancedTree((a, b) => (a ^ b) & a)
  require(bitsResult.getClass == classOf[Bits] && bitsResult.getWidth == width,
    "independent native Bits result kind/width changed")
  bitsComposed := bitsResult
  val boolResult = boolValues.reduceBalancedTree((a, b) => (a ^ b) | a)
  require(boolResult.getClass == classOf[Bool] && boolResult.getBitsWidth == 1,
    "independent native Bool result kind/width changed")
  boolComposed := boolResult
}

object TypedBalancedReductionCallbackGraphArtifactWriter {
  val outputs = Vector("composed", "subtraction", "helper", "selected", "conditioned",
    "sliced", "part", "biased", "alternate", "saturated", "signedComposed", "signedSub",
    "signedBiased", "signedSelect", "bitsComposed", "boolComposed")
  val defaults = Vector(("singleton", 5, 1), ("alternate", 8, 3))

  private def config(directory: Path, fileName: String, nativeReference: Boolean = false): SpinalConfig = {
    Files.createDirectories(directory)
    val result = if (nativeReference) SpinalConfig(targetDirectory = directory.toString,
      headerWithDate = false, headerWithRepoHash = false)
      else SpinalConfig(targetDirectory = directory.toString)
    result.netlistFileName = fileName
    result
  }

  def main(args: Array[String]): Unit = {
    require(args.length == 1 || (args.length == 2 && args(1) == "--references-only"),
      "provide one callback-graph artifact directory and optional --references-only")
    val referencesOnly = args.length == 2
    val root = Paths.get(args(0)).toAbsolutePath.normalize()
    Files.createDirectories(root)
    val profiles = defaults.map { case (profile, defaultWidth, defaultCount) =>
      val module = "BalancedCallbackGraph_" + profile
      val directory = root.resolve("candidate").resolve(profile)
      if (referencesOnly) {
        require(Files.isRegularFile(directory.resolve(module + ".v")),
          "reference refresh requires the existing sole candidate for " + profile)
      } else {
        MorphVerilog(config(directory, module + ".v")) {
          new BalancedCallbackGraphHardware(HdlInt.param("WIDTH", defaultWidth, 1, 32),
            HdlInt.param("COUNT", defaultCount, 1, 17), module)
        }
      }
      s"""    {"profile":"$profile","width":$defaultWidth,"count":$defaultCount,"module":"$module","rtl":"candidate/$profile/$module.v"}"""
    }
    val cases = for {
      width <- Vector(1, 5, 8, 32)
      count <- Vector(1, 2, 3, 5, 8, 9, 16, 17)
    } yield {
      val module = s"BalancedCallbackGraphReference_w${width}_n$count"
      val relative = s"reference/w${width}_n$count/$module.v"
      val directory = root.resolve("reference").resolve(s"w${width}_n$count")
      config(directory, module + ".v", nativeReference = true)
        .generateVerilog(new BalancedCallbackGraphReference(width, count))
      s"""    {"width":$width,"count":$count,"reference_module":"$module","reference_rtl":"$relative","scalar_result_width":$width,"bool_result_width":1,"result_kinds":["UInt","SInt","Bits","Bool"]}"""
    }
    val manifest = "{\n  \"scope\":\"parameterized-native-safe-callback-graphs\",\n" +
      "  \"independent_inputs\":[\"dataIn\",\"bias\",\"otherBias\",\"signedIn\",\"signedBias\",\"bitsIn\",\"boolIn\"],\n" +
      "  \"outputs\":[" + outputs.map(value => "\"" + value + "\"").mkString(",") + "],\n" +
      "  \"profiles\":[\n" + profiles.mkString(",\n") + "\n  ],\n" +
      "  \"configurations\":[\n" + cases.mkString(",\n") + "\n  ]\n}\n"
    Files.write(root.resolve("manifest.json"), manifest.getBytes(StandardCharsets.UTF_8))
  }
}
