package nativeapplication

import morphhdl.frontend.{HdlBool, HdlInt}
import spinal.core._

/** Application-shaped BlackBox contract for Increment 59.
  *
  * The external modules are intentionally not authored here. Tests compile the
  * generated parent against independent Verilog stubs.
  */
object TypedBlackBoxGenericBindingFixture {
  final class TypedExternalLeaf(
      width: ElabInt,
      doubledWidth: ElabInt,
      enabled: ElabBool
  ) extends BlackBox {
    setBlackBoxName("TypedExternalLeaf")

    // Keep concrete and typed generic kinds interleaved. The publication pass
    // must replace only the exact typed named associations.
    addGeneric("LABEL", "typed")
    addGeneric("WIDTH", width)
    addGeneric("DEPTH", 4)
    addGeneric("DOUBLE_WIDTH", doubledWidth)
    addGeneric("CONCRETE_ENABLE", true)
    addGeneric("ENABLED", enabled)

    val din = in(Bits(width bits)).setName("din")
    val dout = out(Bits(width bits)).setName("dout")
  }

  final class TypedParameterOnlyExternal(latency: ElabInt)
      extends BlackBox {
    setBlackBoxName("TypedParameterOnlyExternal")
    addGeneric("LATENCY", latency)

    val din = in(Bits(8 bits)).setName("din")
    val dout = out(Bits(8 bits)).setName("dout")
  }

  final class ParameterizedTop(
      width: HdlInt,
      enabled: ElabBool,
      latency: HdlInt
  ) extends Component {
    setDefinitionName("TypedBlackBoxGenericTop")

    val narrowIn = in(Bits(width bits)).setName("narrow_in")
    val narrowOut = out(Bits(width bits)).setName("narrow_out")
    val wideIn = in(Bits((width + 1) bits)).setName("wide_in")
    val wideOut = out(Bits((width + 1) bits)).setName("wide_out")
    val fixedIn = in(Bits(8 bits)).setName("fixed_in")
    val fixedOut = out(Bits(8 bits)).setName("fixed_out")

    val externalA =
      new TypedExternalLeaf(width, width * 2, enabled)
    externalA.setName("external_a")
    externalA.din := narrowIn
    narrowOut := externalA.dout

    val externalB =
      new TypedExternalLeaf(width + 1, (width + 1) * 2, !enabled)
    externalB.setName("external_b")
    externalB.din := wideIn
    wideOut := externalB.dout

    val parameterOnly = new TypedParameterOnlyExternal(latency)
    parameterOnly.setName("parameter_only")
    parameterOnly.din := fixedIn
    fixedOut := parameterOnly.dout
  }

  final class ConcreteExternalLeaf(width: Int, enabled: Boolean)
      extends BlackBox {
    setBlackBoxName("TypedExternalLeaf")
    addGeneric("LABEL", "typed")
    addGeneric("WIDTH", width)
    addGeneric("DEPTH", 4)
    addGeneric("DOUBLE_WIDTH", width * 2)
    addGeneric("CONCRETE_ENABLE", true)
    addGeneric("ENABLED", enabled)

    val din = in(Bits(width bits)).setName("din")
    val dout = out(Bits(width bits)).setName("dout")
  }

  final class ConcreteParameterOnlyExternal(latency: Int)
      extends BlackBox {
    setBlackBoxName("TypedParameterOnlyExternal")
    addGeneric("LATENCY", latency)

    val din = in(Bits(8 bits)).setName("din")
    val dout = out(Bits(8 bits)).setName("dout")
  }

  final class ConcreteMatrixTop(
      width: Int,
      enabled: Boolean,
      latency: Int
  ) extends Component {
    setDefinitionName("TypedBlackBoxConcreteTop")

    val narrowIn = in(Bits(width bits)).setName("narrow_in")
    val narrowOut = out(Bits(width bits)).setName("narrow_out")
    val wideIn = in(Bits((width + 1) bits)).setName("wide_in")
    val wideOut = out(Bits((width + 1) bits)).setName("wide_out")
    val fixedIn = in(Bits(8 bits)).setName("fixed_in")
    val fixedOut = out(Bits(8 bits)).setName("fixed_out")

    val externalA = new ConcreteExternalLeaf(width, enabled)
    externalA.setName("external_a")
    externalA.din := narrowIn
    narrowOut := externalA.dout

    val externalB = new ConcreteExternalLeaf(width + 1, !enabled)
    externalB.setName("external_b")
    externalB.din := wideIn
    wideOut := externalB.dout

    val parameterOnly = new ConcreteParameterOnlyExternal(latency)
    parameterOnly.setName("parameter_only")
    parameterOnly.din := fixedIn
    fixedOut := parameterOnly.dout
  }

  final class LiteralTop extends Component {
    setDefinitionName("TypedBlackBoxLiteralTop")

    val din = in(Bits(8 bits)).setName("din")
    val dout = out(Bits(8 bits)).setName("dout")

    val external = new ConcreteExternalLeaf(8, enabled = true)
    external.setName("external")
    external.din := din
    dout := external.dout
  }

  final class DuplicateGenericExternal(width: ElabInt)
      extends BlackBox {
    setBlackBoxName("TypedDuplicateGenericExternal")
    addGeneric("WIDTH", width)
    addGeneric("WIDTH", 8)

    val din = in(Bits(8 bits)).setName("din")
    val dout = out(Bits(8 bits)).setName("dout")
  }

  final class DuplicateGenericTop(width: HdlInt) extends Component {
    setDefinitionName("TypedBlackBoxDuplicateGenericTop")

    val din = in(Bits(8 bits)).setName("din")
    val dout = out(Bits(8 bits)).setName("dout")

    val external = new DuplicateGenericExternal(width)
    external.setName("duplicate")
    external.din := din
    dout := external.dout
  }

  def parameterized(): ParameterizedTop =
    new ParameterizedTop(
      HdlInt.param("WIDTH", default = 8, min = 1, max = 16),
      HdlBool.param("ENABLE", default = true),
      HdlInt.param("LATENCY", default = 2, min = 0, max = 4)
    )

  def duplicate(): DuplicateGenericTop =
    new DuplicateGenericTop(
      HdlInt.param("WIDTH", default = 8, min = 1, max = 16)
    )
}
