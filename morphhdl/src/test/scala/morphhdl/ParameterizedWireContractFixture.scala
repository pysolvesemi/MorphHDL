package morphhdl

import spinal.core._

import morphhdl.frontend._
import morphhdl.frontend.ParamRtlFrontend._
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.Design

private[morphhdl] object ParameterizedWireContractFixture {
  def program(reverseConstructionOrder: Boolean): MorphProgram[Component] =
    MorphProgram(
      concreteWitness = new Component {
        setDefinitionName("ParameterizedWire")
        val din = in(Bits(8 bits))
        val dout = out(Bits(8 bits))
        dout := din
      },
      parameterizedDesign = {
        val width = HdlInt.param("WIDTH", default = 8, min = 1)
        val packed = packedBits(width)
        val module = moduleDef(
          name = "ParameterizedWire",
          parameters = Vector(integerParameter(width)),
          ports = ordered(
            Vector(port("din", Input, packed), port("dout", Output, packed)),
            reverseConstructionOrder
          ),
          items = captureItems {
            emitContinuousAssign("dout", ref("din"))
          }
        )
        Design(top = module.name, modules = Vector(module))
      }
    )

  private def ordered[A](values: Vector[A], reverse: Boolean): Vector[A] =
    if (reverse) values.reverse else values
}
