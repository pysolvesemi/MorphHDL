package spinal.core.internals

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import morphhdl.MorphVerilog
import morphhdl.frontend.HdlInt
import spinal.core._
import spinal.lib._

/** Exercises the public native helper. Candidate logic is never handwritten. */
final class BalancedPublicationHardware(width: HdlInt, count: HdlInt) extends Component {
  setDefinitionName("BalancedPublication")
  val clk = in(Bool()).setName("clk")
  val reset = in(Bool()).setName("reset")
  val enable = in(Bool()).setName("enable")
  val unsignedIn = in(Vec(UInt(width bits), count)).setName("unsignedIn")
  val signedIn = in(Vec(SInt(width bits), count)).setName("signedIn")
  val bitsIn = in(Vec(Bits(width bits), count)).setName("bitsIn")
  val boolIn = in(Vec(Bool(), count)).setName("boolIn")
  val uAdd = out(UInt(width bits)).setName("uAdd")
  val sAdd = out(SInt(width bits)).setName("sAdd")
  val bXor = out(Bits(width bits)).setName("bXor")
  val qAnd = out(Bool()).setName("qAnd")
  val rAdd = out(UInt(width bits)).setName("rAdd")
  uAdd := unsignedIn.reduceBalancedTree((a: UInt, b: UInt) => a + b, (v: UInt, _: Int) => v)
  sAdd := signedIn.reduceBalancedTree((a: SInt, b: SInt) => a + b, (v: SInt, _: Int) => v)
  bXor := bitsIn.reduceBalancedTree((a: Bits, b: Bits) => a ^ b, (v: Bits, _: Int) => v)
  qAnd := boolIn.reduceBalancedTree((a: Bool, b: Bool) => a & b, (v: Bool, _: Int) => v)
  val registered = new ClockingArea(ClockDomain(clock = clk, reset = reset,
      clockEnable = enable, config = ClockDomainConfig(resetKind = SYNC,
        resetActiveLevel = HIGH, clockEnableActiveLevel = HIGH))) {
    rAdd := unsignedIn.reduceBalancedTree((a: UInt, b: UInt) => a + b, (value: UInt, _: Int) => {
      val register = UInt()
      register.setAsReg()
      register := value
      register.init(U(0))
      register
    })
  }
}

/** Separate ordinary Spinal elaboration is the independent concrete reference. */
final class BalancedPublicationReference(width: Int, count: Int) extends Component {
  setDefinitionName(s"BalancedPublicationReference_w${width}_n$count")
  val clk = in(Bool()).setName("clk")
  val reset = in(Bool()).setName("reset")
  val enable = in(Bool()).setName("enable")
  val unsignedIn = in(Bits(width * count bits)).setName("unsignedIn")
  val signedIn = in(Bits(width * count bits)).setName("signedIn")
  val bitsIn = in(Bits(width * count bits)).setName("bitsIn")
  val boolIn = in(Bits(count bits)).setName("boolIn")
  val uAdd = out(UInt(width bits)).setName("uAdd")
  val sAdd = out(SInt(width bits)).setName("sAdd")
  val bXor = out(Bits(width bits)).setName("bXor")
  val qAnd = out(Bool()).setName("qAnd")
  val rAdd = out(UInt(width bits)).setName("rAdd")
  val unsigned = Vector.tabulate(count)(i => unsignedIn(i * width, width bits).asUInt)
  val signed = Vector.tabulate(count)(i => signedIn(i * width, width bits).asSInt)
  val packed = Vector.tabulate(count)(i => bitsIn(i * width, width bits))
  val flags = Vector.tabulate(count)(i => boolIn(i))
  uAdd := unsigned.reduceBalancedTree((a: UInt, b: UInt) => a + b)
  sAdd := signed.reduceBalancedTree((a: SInt, b: SInt) => a + b)
  bXor := packed.reduceBalancedTree((a: Bits, b: Bits) => a ^ b)
  qAnd := flags.reduceBalancedTree((a: Bool, b: Bool) => a & b)
  val registered = new ClockingArea(ClockDomain(clock = clk, reset = reset,
      clockEnable = enable, config = ClockDomainConfig(resetKind = SYNC,
        resetActiveLevel = HIGH, clockEnableActiveLevel = HIGH))) {
    rAdd := unsigned.reduceBalancedTree((a: UInt, b: UInt) => a + b, (value: UInt, _: Int) => {
      val register = UInt()
      register.setAsReg()
      register := value
      register.init(U(0))
      register
    })
  }
}

object TypedBalancedReductionPublicationArtifactWriter {
  def config(directory: Path, fileName: String): SpinalConfig = {
    Files.createDirectories(directory)
    val result = SpinalConfig(targetDirectory = directory.toString)
    result.netlistFileName = fileName
    result
  }

  def candidate(directory: Path): Path = {
    MorphVerilog(config(directory, "BalancedPublication.v")) {
      new BalancedPublicationHardware(HdlInt.param("WIDTH", 5, 1, 32),
        HdlInt.param("COUNT", 1, 1, 17))
    }
    val path = directory.resolve("BalancedPublication.v")
    require(Files.isRegularFile(path), "missing parameterized public-helper artifact")
    path
  }

  def main(args: Array[String]): Unit = {
    require(args.length == 1, "provide one parameterized publication artifact directory")
    val root = Paths.get(args(0)).toAbsolutePath.normalize()
    Files.createDirectories(root)
    val emitted = candidate(root.resolve("candidate"))
    val candidatePath = root.relativize(emitted).toString.replace('\\', '/')
    val cases = for {
      width <- Vector(1, 5, 8, 32)
      count <- Vector(1, 2, 3, 5, 8, 9, 16, 17)
    } yield {
      val module = s"BalancedPublicationReference_w${width}_n$count"
      val directory = root.resolve(s"w${width}_n$count").resolve("reference")
      SpinalConfig(targetDirectory = directory.toString, headerWithDate = false,
        headerWithRepoHash = false).generateVerilog(new BalancedPublicationReference(width, count))
      val relative = root.relativize(directory.resolve(module + ".v")).toString.replace('\\', '/')
      s"""    {"width":$width,"count":$count,"reference_module":"$module","reference_rtl":"$relative","candidate_module":"BalancedPublication","candidate_rtl":"$candidatePath"}"""
    }
    val manifest = "{\n  \"scope\":\"parameterized-native-balanced-publication\",\n" +
      "  \"candidate_default\":{\"width\":5,\"count\":1},\n" +
      "  \"independent_inputs\":[\"unsignedIn\",\"signedIn\",\"bitsIn\",\"boolIn\"],\n" +
      "  \"outputs\":[\"uAdd\",\"sAdd\",\"bXor\",\"qAnd\",\"rAdd\"],\n  \"configurations\":[\n" +
      cases.mkString(",\n") + "\n  ]\n}\n"
    Files.write(root.resolve("manifest.json"), manifest.getBytes(StandardCharsets.UTF_8))
  }
}
