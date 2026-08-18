package morphhdl

import spinal.core._

import morphhdl.frontend._
import morphhdl.frontend.ParamRtlFrontend._
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.Design

/** Seventh public MorphVerilog contract: a Boolean-selected packed width. */
private[morphhdl] object ConditionalWidthContractFixture {
  def program(reverseConstructionOrder: Boolean): MorphProgram[Component] =
    MorphProgram(
      concreteWitness = new Component {
        setDefinitionName("ConditionalWidth")
        val din = in(morphhdl.frontend.Bits(12 bits))
        val dout = out(morphhdl.frontend.Bits(12 bits))
        dout := din
      },
      parameterizedDesign = {
        val wide = HdlBool.param("WIDE", default = true)
        val narrowWidth = HdlInt.param("NARROW_WIDTH", default = 4, min = 1, max = 32)
        val wideWidth = HdlInt.param("WIDE_WIDTH", default = 12, min = 1, max = 32)
        val activeWidth = localParam("ACTIVE_WIDTH", wide.select(wideWidth, narrowWidth))
        val packed = packedBits(activeWidth)

        val top = moduleDef(
          name = "ConditionalWidth",
          parameters = ordered(
            Vector(integerParameter(narrowWidth), integerParameter(wideWidth)),
            reverseConstructionOrder
          ),
          ports = ordered(
            Vector(port("din", Input, packed), port("dout", Output, packed)),
            reverseConstructionOrder
          ),
          items = captureItems {
            emitContinuousAssign("dout", ref("din"))
          },
          booleanParameters = Vector(booleanParameter(wide)),
          localParameters = Vector(integerLocalParameter(activeWidth))
        )

        Design(top = top.name, modules = Vector(top))
      }
    )

  private def ordered[A](values: Vector[A], reverse: Boolean): Vector[A] =
    if (reverse) values.reverse else values
}
