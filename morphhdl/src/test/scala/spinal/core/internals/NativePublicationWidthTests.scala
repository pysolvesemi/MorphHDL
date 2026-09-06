package spinal.core.internals

import java.nio.file.Files
import morphhdl.MorphVerilog
import morphhdl.frontend.HdlInt
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class NativePublicationWidthTests extends AnyFunSuite {
  test("captured partial widths use their exact native owner after construction scope ends") {
    val count = HdlInt.param("COUNT", 1, 1, 8).asElabInt
    val config = SpinalConfig(targetDirectory = Files.createTempDirectory("native-owner-width-").toString,
      headerWithDate = false)
    var checked = false
    MorphVerilog(config) {
      new Component {
        val inputBit = in Bool()
        val observed = out Bool()
        val resizedOutput = out UInt(3 bits)
        val foreign = UInt(1 bits).setName("foreign")
        foreign := U(0)
        var local: UInt = null
        var derived: ElaborationIntegerExpression = null
        ElabControl.selectSymbolic(count > 1, "native-owner-width", 1) {
          local = UInt((count - 1).bits).setName("local_source")
          local := inputBit.asUInt.resize(count - 1)
          val width = ParameterizedWidth.expressionOf(local).get
          derived = ElaborationWidthAuthority.add(width, ElabInt.literal(0).expression)
          observed := local.msb
          resizedOutput := local.resize(3)
        } {
          observed := False
          resizedOutput := U(0)
        }
        val width = ParameterizedWidth.expressionOf(local).get
        // The source branch is now closed. The construction-only API rejects
        // this observation, but the captured declaration still owns the width.
        intercept[ParameterizedVerilogException](ElabInt.fromExpression(width).minimum)
        NativePublicationWidth.validate(width, this, local, "captured local width")
        assert(NativePublicationWidth.equivalentAtOwner(width, derived, this, local))
        val wrongOwner = intercept[ParameterizedVerilogException] {
          NativePublicationWidth.validate(width, this, foreign, "foreign declaration")
        }
        assert(wrongOwner.code == "SPINAL-ELAB-DOMAIN-PROJECTION-OWNER-SCOPE-MISMATCH")
        intercept[ParameterizedVerilogException] {
          NativePublicationWidth.validate(width.copy(), this, local, "copied width")
        }
        checked = true
      }
    }
    assert(checked)
  }
}
