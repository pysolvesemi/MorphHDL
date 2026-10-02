package spinal.core.internals

import java.nio.file.Files
import morphhdl.MorphVerilog
import morphhdl.frontend.{HdlInt, StructuralGenerateIfOps}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.idslplugin.Location
import spinal.lib._

/** Field-free helper bodies are inspected, never trusted by method name. */
object CompositeCaptureHelpers {
  def combine(a: BalancedCompositeRecord, b: BalancedCompositeRecord,
      biasA: UInt, biasB: UInt, mask: Bits, choose: Bool): BalancedCompositeRecord = {
    val result = cloneOf(a)
    result.key := Mux(choose, a.key min b.key, a.key max b.key)
    result.tag := (a.tag ^ b.tag) ^ mask
    result.x := (a.x + b.y) ^ biasA
    result.y := (a.y - b.x) ^ biasB
    result
  }
  def signed(a: BalancedCompositeComplex, b: BalancedCompositeComplex, offset: SInt): BalancedCompositeComplex = {
    val result = cloneOf(a)
    result.real := (a.real + b.imag) + offset
    result.imag := a.imag - b.real
    result
  }
  def write(a: BalancedCompositeRecord, b: BalancedCompositeRecord): BalancedCompositeRecord = {
    a.x := b.y
    a
  }
}
object CompositeCaptureEffects { var calls = 0 }
final class CompositeCaptureImpostor extends Bundle {
  val value = UInt(4 bits)
  // This is an application method, not DataPimper's native assignment.
  def :=(that: Data)(implicit loc: Location): Unit = { CompositeCaptureEffects.calls += 1 }
}

class TypedBalancedReductionCompositeCaptureTests extends AnyFunSuite {
  private def admit(callback: AnyRef): TypedBalancedReductionCaptureSchema =
    TypedBalancedReductionCertifiedCallbackPolicy.requireSupportedCompositeOperator(callback)
  private def reject(callback: AnyRef): Unit = {
    val error = intercept[IllegalArgumentException](admit(callback))
    assert(error.getMessage.contains("MORPH-REDUCE-BALANCED-"), error.getMessage)
  }
  private def native[T <: Data]: ElabBalancedReduction.Native[T] =
    (values, operation, bridge) => new TraversableOnceAnyPimped[T](values).reduceBalancedTree(operation, bridge)

  test("certified field getters and pure helpers preserve fresh record permission") {
    admit((a: BalancedCompositeRecord, b: BalancedCompositeRecord) => {
      val r = cloneOf(a)
      r.key := a.key min b.key
      r.tag := a.tag ^ b.tag
      r.x := a.x + b.y
      r.y := a.y - b.x
      r
    })
    admit((a: BalancedCompositeComplex, b: BalancedCompositeComplex) => CompositeCaptureHelpers.signed(a, b, a.real))
  }
  test("operand field writes cannot gain permission through helper calls or aliases") {
    reject((a: BalancedCompositeRecord, b: BalancedCompositeRecord) => { a.x := b.y; a })
    reject((a: BalancedCompositeRecord, b: BalancedCompositeRecord) => CompositeCaptureHelpers.write(a, b))
    reject((a: BalancedCompositeRecord, b: BalancedCompositeRecord) => { val alias = a; alias := b; alias })
    reject((a: BalancedCompositeRecord, b: BalancedCompositeRecord) => { a.x.msb := b.y.msb; a })
    reject((a: BalancedCompositeRecord, b: BalancedCompositeRecord) => { a.x.asBits := b.y.asBits; a })
    reject((a: BalancedCompositeRecord, b: BalancedCompositeRecord) => { a.tag.asBits := b.tag; a })
  }
  test("native-looking application assignment cannot bypass either callback inspector") {
    CompositeCaptureEffects.calls = 0
    val callback = (a: CompositeCaptureImpostor, b: CompositeCaptureImpostor) => {
      val result = cloneOf(a); result := b; result
    }
    reject(callback)
    val old = intercept[IllegalArgumentException](TypedBalancedReductionCallbackPolicy.requireSupportedOperator(callback))
    assert(old.getMessage.contains("CALLBACK-UNSUPPORTED"))
    val bridge = (a: CompositeCaptureImpostor, _: Int) => { val result = cloneOf(a); result := a; result }
    intercept[IllegalArgumentException](TypedBalancedReductionCallbackPolicy.requireSupportedBridge(bridge))
    assert(CompositeCaptureEffects.calls == 0, "uninspected application code executed")
  }
  test("host state, witness decisions and captured aggregate objects remain unsupported") {
    var counter = 0
    reject((a: BalancedCompositeRecord, b: BalancedCompositeRecord) => { counter += 1; a })
    reject((a: BalancedCompositeRecord, b: BalancedCompositeRecord) => if (a.x.getWidth == 8) a else b)
    assert(counter == 0)
  }
  test("runtime capture slots stay read-only and independently identity-bound") {
    SpinalConfig(targetDirectory = Files.createTempDirectory("composite-capture-policy-").toString,
      headerWithDate = false, headerWithRepoHash = false).generateVerilog(new Component {
      val biasA = in UInt(5 bits)
      val biasB = in UInt(5 bits)
      val keep = out UInt(5 bits)
      keep := biasA ^ biasB
      val check = {
        val first = biasA; val second = biasB
        val callback = (a: BalancedCompositeRecord, b: BalancedCompositeRecord) => {
          val r = cloneOf(a); r := a; r.x := (a.x + b.y) ^ first; r.y := second; r
        }
        val schema = admit(callback)
        assert(schema.hardwareInputs.size == 2)
        assert(schema.hardwareInputs.exists(_ eq first) && schema.hardwareInputs.exists(_ eq second))
        schema.validateBindings()
        reject((a: BalancedCompositeRecord, b: BalancedCompositeRecord) => { first := b.x; a })
        reject((a: BalancedCompositeRecord, b: BalancedCompositeRecord) => { first.msb := b.x.msb; a })
        reject((a: BalancedCompositeRecord, b: BalancedCompositeRecord) => { first.asBits := b.x.asBits; a })
      }
    })
  }
  test("capture aliasing a pair leaf rejects instead of remapping it to a replay lane") {
    SpinalConfig(targetDirectory = Files.createTempDirectory("composite-capture-alias-").toString,
      headerWithDate = false, headerWithRepoHash = false).generateVerilog(new Component {
      val records = in(Vec(BalancedCompositeRecord(HdlInt.literal(5), HdlInt.literal(5), HdlInt.literal(5)),
        HdlInt.param("COUNT", 1, 1, 3)))
      val keep = out Bool(); keep := False
      val check = {
        val captured = records.vec.head.x
        val callback = (a: BalancedCompositeRecord, b: BalancedCompositeRecord) => {
          val result = cloneOf(a)
          result.key := a.key; result.tag := b.tag
          result.x := a.x ^ captured; result.y := b.y
          result
        }
        val schema = admit(callback)
        val error = intercept[IllegalArgumentException] {
          TypedBalancedReductionCompositeReplay.capture(records, callback,
            (a: BalancedCompositeRecord, _: Int) => a, native[BalancedCompositeRecord], Some(schema))
        }
        assert(error.getMessage.contains("CAPTURE-ALIAS"), error.getMessage)
      }
    })
  }
  test("public Vec reduction retains external captures through native replay") {
    val directory = Files.createTempDirectory("composite-capture-public-")
    MorphVerilog(SpinalConfig(targetDirectory = directory.toString, headerWithDate = false)) {
      new Component {
        setDefinitionName("CompositeCapturedSmoke")
        val records = in(Vec(BalancedCompositeRecord(HdlInt.literal(5), HdlInt.literal(3), HdlInt.literal(7)),
          HdlInt.param("COUNT", 1, 1, 5)))
        val first = in UInt(7 bits); val second = in UInt(7 bits)
        val mask = in Bits(3 bits); val choose = in Bool()
        val result = out(BalancedCompositeRecord(HdlInt.literal(5), HdlInt.literal(3), HdlInt.literal(7)))
        val reduced = {
          val p = first; val q = second; val m = mask; val c = choose
          records.reduceBalancedTree((a: BalancedCompositeRecord, b: BalancedCompositeRecord) =>
            CompositeCaptureHelpers.combine(a, b, p, q, m, c))
        }
        result := reduced
      }
    }
    assert(Files.isRegularFile(directory.resolve("CompositeCapturedSmoke.v")))
  }
  test("computed captures retain their definition outside each replay template") {
    val directory = Files.createTempDirectory("composite-computed-capture-")
    MorphVerilog(SpinalConfig(targetDirectory = directory.toString, headerWithDate = false)) {
      new CompositeCaptureHardware(HdlInt.param("WIDTH", 5, 1, 8),
        HdlInt.param("TAG", 3, 1, 8), HdlInt.param("COORD", 7, 1, 8),
        HdlInt.param("COUNT", 1, 1, 5), HdlInt.param("MODE", 0, 0, 1), "ComputedCapture")
    }
    val rtl = new String(Files.readAllBytes(directory.resolve("ComputedCapture.v")), "UTF-8")
    assert(rtl.contains("g_reverse") && rtl.contains("biasA") && rtl.contains("offset"), rtl)
  }
  test("captured aggregate objects do not acquire scalar input permissions") {
    SpinalConfig(targetDirectory = Files.createTempDirectory("aggregate-capture-reject-").toString,
      headerWithDate = false, headerWithRepoHash = false).generateVerilog(new Component {
      val input = in(BalancedCompositeRecord(HdlInt.literal(5), HdlInt.literal(3), HdlInt.literal(7)))
      val keep = out UInt(5 bits); keep := input.key
      val check = {
        val capturedRecord = input
        reject((a: BalancedCompositeRecord, b: BalancedCompositeRecord) => capturedRecord)
      }
    })
  }
  test("captured nodes cannot escape a sibling typed owner") {
    val directory = Files.createTempDirectory("capture-owner-escape-")
    val failure = intercept[Exception] {
      MorphVerilog(SpinalConfig(targetDirectory = directory.toString, headerWithDate = false)) {
        new Component {
          setDefinitionName("EscapingCapture")
          val records = in(Vec(BalancedCompositeRecord(HdlInt.literal(5), HdlInt.literal(3), HdlInt.literal(7)),
            HdlInt.param("COUNT", 1, 1, 3)))
          val select = in Bool()
          val result = out(BalancedCompositeRecord(HdlInt.literal(5), HdlInt.literal(3), HdlInt.literal(7)))
          var escaped: Bool = null
          (HdlInt.param("MODE", 1, 0, 1) > HdlInt.literal(0)).generateIf("producer", "sibling") {
            escaped = !select
            result := records(0)
          }.otherwise {
            val captured = escaped
            result := records.reduceBalancedTree((a: BalancedCompositeRecord, b: BalancedCompositeRecord) =>
              Mux(captured, a, b))
          }
        }
      }
    }
    val messages = Iterator.iterate(failure.asInstanceOf[Throwable])(_.getCause).takeWhile(_ != null)
      .map(e => Option(e.getMessage).getOrElse("")).mkString("\n")
    assert(messages.contains("BRANCH-LOCAL") || messages.contains("BRANCH-REFERENCE") ||
      messages.contains("LEXICAL") || messages.contains("CONTINUOUS-ASSIGNMENT-DOMINANCE-UNPROVEN"), messages)
    assert(!Files.exists(directory.resolve("EscapingCapture.v")))
  }

}
