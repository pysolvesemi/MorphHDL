package spinal.core.internals

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import morphhdl.{MorphNamedFieldVectors, MorphSignedCasts, MorphSignedDeclarations, MorphVerilog}
import morphhdl.frontend.{HdlInt, StructuralGenerateIfOps}
import spinal.core._
import spinal.lib._

/** All callbacks use the actual native tree. Captures are runtime data inputs. */
final class CompositeCaptureHardware(w: HdlInt, t: HdlInt, c: HdlInt, n: HdlInt, mode: HdlInt,
    moduleName: String) extends Component {
  setDefinitionName(moduleName)
  val records = in(Vec(BalancedCompositeRecord(w, t, c), n)).setName("records")
  val signedRecords = in(Vec(BalancedCompositeComplex(w), n)).setName("signedRecords")
  val biasA = in(UInt(c bits)).setName("biasA")
  val biasB = in(UInt(c bits)).setName("biasB")
  val mask = in(Bits(t bits)).setName("mask")
  val choose = in(Bool()).setName("choose")
  val offset = in(SInt(w bits)).setName("offset")
  val result = out(BalancedCompositeRecord(w, t, c)).setName("result")
  val signedResult = out(BalancedCompositeComplex(w)).setName("signedResult")
  (mode > HdlInt.literal(0)).generateIf("g_direct", "g_reverse") {
    val p = biasA; val q = biasB; val m = mask; val chooseMin = choose; val signedOffset = offset
    result := records.reduceBalancedTree((a: BalancedCompositeRecord, b: BalancedCompositeRecord) =>
      CompositeCaptureHelpers.combine(a, b, p, q, m, chooseMin))
    signedResult := signedRecords.reduceBalancedTree((a: BalancedCompositeComplex, b: BalancedCompositeComplex) =>
      CompositeCaptureHelpers.signed(a, b, signedOffset))
  }.otherwise {
    val p = biasA; val q = biasB; val m = mask; val chooseMax = !choose; val signedOffset = offset
    result := records.reduceBalancedTree((a: BalancedCompositeRecord, b: BalancedCompositeRecord) =>
      CompositeCaptureHelpers.combine(b, a, p, q, m, chooseMax))
    signedResult := signedRecords.reduceBalancedTree((a: BalancedCompositeComplex, b: BalancedCompositeComplex) =>
      CompositeCaptureHelpers.signed(b, a, signedOffset))
  }
}

object TypedBalancedReductionCompositeCaptureArtifacts {
  val profiles = for (layout <- Vector("packed", "fields"); signed <- Vector("legacy", "declarations", "casts"))
    yield layout -> signed
  val cases = ((for (w <- Vector(1, 5, 8, 32); n <- Vector(1, 2, 3, 5, 8, 9, 16, 17); m <- 0 to 1)
    yield (w, w, w, n, m)) ++ (for ((w, t, c) <- Vector((1, 3, 7), (5, 1, 8), (8, 32, 1), (32, 5, 7));
      n <- Vector(1, 3, 5, 17); m <- 0 to 1) yield (w, t, c, n, m))).distinct
  def config(path: Path, name: String): SpinalConfig = {
    Files.createDirectories(path)
    val c = SpinalConfig(targetDirectory = path.toString, headerWithDate = false,
      bitVectorWidthMax = 65536)
    c.netlistFileName = name + ".v"
    c
  }
  private def quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
  def main(args: Array[String]): Unit = {
    require(args.length == 1)
    val root = Paths.get(args(0)).toAbsolutePath.normalize()
    val candidates = profiles.map { case (layout, signed) =>
      val name = "CompositeCapture_" + layout + "_" + signed
      val base = config(root.resolve("candidate"), name)
      val shaped = if (layout == "fields") MorphNamedFieldVectors.enable(base) else base
      val configured = signed match {
        case "legacy" => shaped
        case "declarations" => MorphSignedDeclarations.enable(shaped)
        case "casts" => MorphSignedCasts.enable(shaped)
      }
      MorphVerilog(configured) {
        new CompositeCaptureHardware(HdlInt.param("WIDTH", 5, 1, 32), HdlInt.param("TAG", 3, 1, 32),
          HdlInt.param("COORD", 7, 1, 32), HdlInt.param("COUNT", 1, 1, 17), HdlInt.param("MODE", 0, 0, 1), name)
      }
      s"""{"layout":${quote(layout)},"signed":${quote(signed)},"module":${quote(name)},"file":"candidate/$name.v"}"""
    }
    val references = cases.map { case (w, t, c, n, m) =>
      val id = s"w${w}_t${t}_c${c}_n${n}_m$m"
      val name = "NativeCapture_" + id
      SpinalVerilog(config(root.resolve("native"), name)) {
        new nativeapplication.CompositeCaptureNativeOracle(w, t, c, n, m, name)
      }
      s"""{"id":${quote(id)},"width":$w,"tag":$t,"coord":$c,"count":$n,"mode":$m,"module":${quote(name)},"file":"native/$name.v"}"""
    }
    val text = s"""{"schema":1,"scope":"59i-composite-runtime-captures","candidates":[${candidates.mkString(",")}],"cases":[${references.mkString(",")}]}
"""
    Files.write(root.resolve("manifest.json"), text.getBytes(StandardCharsets.UTF_8))
  }
}
