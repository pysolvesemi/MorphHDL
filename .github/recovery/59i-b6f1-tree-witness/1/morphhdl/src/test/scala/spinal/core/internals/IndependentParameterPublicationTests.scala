package spinal.core.internals

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import morphhdl.MorphVerilog
import morphhdl.frontend.HdlInt
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

/** Exercise the native consumers, not just construction of an ElabInt sum. */
class IndependentParameterPublicationTests extends AnyFunSuite {
  private def parameter(name: String, default: Int = 2): ElabInt =
    HdlInt.param(name, default, 1, 8).asElabInt
  private def config = SpinalConfig(targetDirectory = Files.createTempDirectory("independent-publication-").toString,
    headerWithDate = false, headerWithRepoHash = true)
  private def messages(error: Throwable): String = {
    val out = new StringBuilder
    var current = error
    while (current != null) { out.append(current.getMessage).append('\n'); current = current.getCause }
    out.toString
  }

  test("product width authority stays bound to its exact captured owner") {
    val a = parameter("DATA_BITS", 1); val b = parameter("GENERATION_BITS", 2)
    var checked = false
    MorphVerilog(config) {
      new Component {
        val din = in Bits((a + b) bits)
        val observed = out Bool()
        val broad = Bits((a + b) bits)
        broad := din
        var local: Bits = null
        var width: ElaborationIntegerExpression = null
        var equivalent: ElaborationIntegerExpression = null
        ElabControl.selectSymbolic(a > 1, "independent-native-owner", 1) {
          local = Bits(((a - 1) + b) bits)
          local := din.resize((a - 1) + b)
          observed := local.orR
          width = ParameterizedWidth.expressionOf(local).get
          equivalent = ElaborationWidthAuthority.add(width, ElabInt.literal(0).expression)
        } { observed := din.orR }
        intercept[ParameterizedVerilogException](ElabInt.fromExpression(width).minimum)
        NativePublicationWidth.validate(width, this, local, "captured product width")
        assert(NativePublicationWidth.equivalentAtOwner(width, equivalent, this, local))
        val foreign = intercept[ParameterizedVerilogException] {
          NativePublicationWidth.validate(width, this, broad, "wrong product owner")
        }
        assert(foreign.code == "SPINAL-ELAB-DOMAIN-PROJECTION-OWNER-SCOPE-MISMATCH")
        intercept[ParameterizedVerilogException] {
          NativePublicationWidth.validate(width.copy(), this, local, "copied product width")
        }
        assert(!NativePublicationWidth.equivalentAtOwners(
          ParameterizedWidth.expressionOf(din).get, din, width, local, this))
        checked = true
      }
    }
    assert(checked)
  }

  private class Child(a: ElabInt, b: ElabInt, weighted: Boolean = false) extends Component {
    setDefinitionName("IndependentProofChild")
    val din = in Bits((if (weighted) a * 2 + b else a + b) bits)
    val observed = out Bool()
    observed := din.orR
  }
  private class Parent(a: ElabInt, b: ElabInt, childA: ElabInt, childB: ElabInt,
                       mismatch: Boolean = false) extends Component {
    setDefinitionName("IndependentProofParent")
    // Reversed authoring order must not make equal root functions unequal.
    val din = in Bits((if (mismatch) a + b * 2 else b + a) bits)
    val observed = out Bool()
    val child = new Child(childA, childB, mismatch)
    child.din := din
    observed := child.observed
  }

  test("compound child bindings preserve both inherited declaration identities") {
    val a = parameter("DATA_BITS"); val b = parameter("GENERATION_BITS")
    val report = MorphVerilog(config)(new Parent(a, b, a, b))
    val text = report.generatedSourcesPaths.map(path =>
      new String(Files.readAllBytes(java.nio.file.Paths.get(path)), StandardCharsets.UTF_8)).mkString("\n")
    assert(text.contains(".DATA_BITS(DATA_BITS)"))
    assert(text.contains(".GENERATION_BITS(GENERATION_BITS)"))
    assert("(?m)^module IndependentProofChild\\b".r.findAllIn(text).size == 1)
  }

  test("equal names defaults and compound widths cannot bind unrelated child roots") {
    val a = parameter("DATA_BITS"); val b = parameter("GENERATION_BITS")
    val foreignA = parameter("DATA_BITS"); val foreignB = parameter("GENERATION_BITS")
    val error = intercept[Exception](MorphVerilog(config)(new Parent(a, b, foreignA, foreignB)))
    assert(messages(error).contains("SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-INHERITED-IDENTITY-MISMATCH"), messages(error))
  }

  test("shared roots and equal default widths do not approve different child width functions") {
    val a = parameter("DATA_BITS"); val b = parameter("GENERATION_BITS")
    val error = intercept[Exception](MorphVerilog(config)(new Parent(a, b, a, b, mismatch = true)))
    val message = messages(error)
    assert(message.contains("SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-INHERITED-IDENTITY-MISMATCH") ||
      message.contains("SPINAL-PARAMETERIZED-VERILOG-WIDTH"), message)
  }
}
