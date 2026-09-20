package spinal.core

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.collection.JavaConverters._
import morphhdl.MorphVerilog
import morphhdl.frontend.HdlInt
import org.scalatest.funsuite.AnyFunSuite

object TypedFormalCompoundPortFixture {
  final class Leaf(width: ElabInt, span: ElabInt, mode: ElabInt) extends Component {
    setDefinitionName("TypedFormalCompoundPortLeaf")
    val packedWidth = ElaborationWidthAuthority.add(width, span)
    val input = in(Bits(packedWidth bits))
    val result = out(Bits(packedWidth bits))
    val directInput = in(Bits(width bits))
    val directResult = out(Bits(width bits))
    directResult := directInput
    ElabControl.selectSymbolic(mode.elabEq(1), "typed-compound-port", 1) {
      val selected = Bits(packedWidth bits).setName("selected_first").dontSimplifyIt()
      selected := input
      result := selected
    } {
      val selected = Bits(packedWidth bits).setName("selected_second").dontSimplifyIt()
      selected := ~input
      result := selected
    }
  }

  final class Top(foreignConnection: Boolean, reflectedConnection: Boolean, check: Leaf => Unit) extends Component {
    setDefinitionName("TypedFormalCompoundPortTop")
    val width = HdlInt.param("WIDTH", 5, 3, 7).asElabInt
    val span = HdlInt.param("SPAN", 2, 1, 3).asElabInt
    val mode = HdlInt.param("MODE", 0, 0, 1).asElabInt
    val inputSpan = foreignConnection match {
      case true => HdlInt.param("OTHER_SPAN", 2, 1, 3).asElabInt
      case false => span
    }
    val connectedSpan = reflectedConnection match {
      case true => ElaborationWidthAuthority.subtract(ElabInt.literal(4), inputSpan)
      case false => inputSpan
    }
    val input = in(Bits(ElaborationWidthAuthority.add(width, connectedSpan) bits))
    val result = out(Bits(ElaborationWidthAuthority.add(width, span) bits))
    val directInput = in(Bits(width bits))
    val directResult = out(Bits(width bits))
    val child = ElabFormalComponent.parameters(Vector(
      ElabFormalComponent.Parameter(width, "WIDTH", 3, 7),
      ElabFormalComponent.Parameter(span, "SPAN", 1, 3),
      ElabFormalComponent.Parameter(mode + 1, "MODE", 1, 2))) { values =>
      new Leaf(values(0), values(1), values(2))
    }
    child.input := input
    result := child.result
    child.directInput := directInput
    directResult := child.directResult
    check(child)
  }
}

class TypedFormalCompoundPortTests extends AnyFunSuite {
  import TypedFormalCompoundPortFixture._

  private def emitted(foreignConnection: Boolean = false, reflectedConnection: Boolean = false)(
      check: Leaf => Unit = _ => ()): String = {
    val directory = Files.createTempDirectory("typed-formal-compound-")
    try {
      val config = SpinalConfig(targetDirectory = directory.toString, headerWithDate = false)
      config.netlistFileName = "compound.v"
      MorphVerilog(config)(new Top(foreignConnection, reflectedConnection, check))
      new String(Files.readAllBytes(directory.resolve("compound.v")), StandardCharsets.UTF_8)
    } finally {
      val stream = Files.walk(directory)
      try stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists(_))
      finally stream.close()
    }
  }

  test("atomic formals authenticate compound width ports and a separate control-only slot") {
    val rtl = emitted() { child =>
      assert(ExternalFormalParameterRegistry.completeTypedBindingsOf(child)
        .map(_.binding.formal.name) == Vector("WIDTH", "SPAN", "MODE"))
      // A port depending on two declaration roots cannot fabricate one slot's
      // scalar leaf token. Its complete family owns the exact width instead.
      assert(ExternalFormalParameterRegistry.typedBindingOf(child.input).isEmpty)
      assert(ExternalFormalParameterRegistry.typedBindingOf(child.result).isEmpty)
      assert(ExternalFormalParameterRegistry.typedBindingOf(child.directInput).nonEmpty)
    }
    val compact = rtl.replaceAll("\\s+", "")
    Vector(".WIDTH(WIDTH)", ".SPAN(SPAN)", ".MODE((MODE+1))")
      .foreach(binding => assert(compact.contains(binding), rtl))
    assert(compact.contains("WIDTH+SPAN"), rtl)
  }

  test("compound port publication rejects a changed retained full width function at the same default") {
    val failure = intercept[Exception] {
      emitted() { child =>
        val original = ParameterizedWidth.expressionOf(child.input).get
        val changed = ElaborationWidthAuthority.subtract(
          ElabInt.literal((original.default * 2).toInt).expression, original)
        assert(changed.default == original.default)
        assert(changed.minimum == original.minimum && changed.maximum == original.maximum)
        assert(!ElabInt.equivalentExpression(original, changed))
        ParameterizedWidth.retainNativeMuxWidth(child.input, Some(changed))
      }
    }
    assert(failure.getMessage.contains("SPINAL-ELAB-FORMAL-TYPED-LEAF-FAMILY-CONFLICT"),
      failure.getMessage)
  }

  test("compound port actuals reject an equal-default parent connection with an independent root") {
    val failure = intercept[Exception] { emitted(foreignConnection = true)() }
    assert(failure.getMessage.contains("SPINAL-PARAMETERIZED-VERILOG-FORMAL-ACTUAL-CONNECTION-CONFLICT"),
      failure.getMessage)
  }

  test("an atomic family's direct ports keep their exact retained width function as well as the scalar token") {
    val failure = intercept[Exception] {
      emitted() { child =>
        val original = ParameterizedWidth.expressionOf(child.directInput).get
        val changed = ElaborationWidthAuthority.subtract(ElabInt.literal(10).expression, original)
        assert(changed.default == original.default)
        assert(changed.minimum == original.minimum && changed.maximum == original.maximum)
        ParameterizedWidth.retainNativeMuxWidth(child.directInput, Some(changed))
      }
    }
    assert(failure.getMessage.contains("SPINAL-ELAB-FORMAL-TYPED-LEAF-FAMILY-CONFLICT"),
      failure.getMessage)
  }

  test("compound port actuals reject a changed parent function with the same roots bounds and default") {
    val failure = intercept[Exception] { emitted(reflectedConnection = true)() }
    assert(failure.getMessage.contains("SPINAL-PARAMETERIZED-VERILOG-FORMAL-ACTUAL-CONNECTION-CONFLICT"),
      failure.getMessage)
  }
}
