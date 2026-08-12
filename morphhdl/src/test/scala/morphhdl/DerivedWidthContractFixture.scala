package morphhdl

import spinal.core._

import morphhdl.frontend._
import morphhdl.frontend.ParamRtlFrontend._
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.Design

private[morphhdl] object DerivedWidthContractFixture {
  def program(reverseConstructionOrder: Boolean): MorphProgram[Component] =
    MorphProgram(
      concreteWitness = new Component {
        setDefinitionName("DerivedWidth")
        val din = in(Bits(35 bits))
        val dout = out(Bits(35 bits))
        dout := din
      },
      parameterizedDesign = {
        val dataWidth = HdlInt.param("DATA_WIDTH", default = 8, min = 1, max = 1024)
        val lanes = HdlInt.param("LANES", default = 4, min = 1, max = 64)
        val totalWidth = localParam("TOTAL_WIDTH", lanes * dataWidth)
        val paddedWidth = localParam("PADDED_WIDTH", totalWidth + 3)
        val packed = packedBits(paddedWidth)
        val module = moduleDef(
          name = "DerivedWidth",
          parameters = ordered(
            Vector(integerParameter(dataWidth), integerParameter(lanes)),
            reverseConstructionOrder
          ),
          ports = ordered(
            Vector(port("din", Input, packed), port("dout", Output, packed)),
            reverseConstructionOrder
          ),
          items = captureItems {
            emitContinuousAssign("dout", ref("din"))
          },
          localParameters = ordered(
            Vector(
              integerLocalParameter(totalWidth),
              integerLocalParameter(paddedWidth)
            ),
            reverseConstructionOrder
          )
        )
        Design(top = module.name, modules = Vector(module))
      }
    )

  private def ordered[A](values: Vector[A], reverse: Boolean): Vector[A] =
    if (reverse) values.reverse else values
}
