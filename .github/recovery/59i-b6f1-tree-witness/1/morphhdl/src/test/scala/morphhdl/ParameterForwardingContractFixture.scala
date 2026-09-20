package morphhdl

import spinal.core._

import morphhdl.frontend._
import morphhdl.frontend.ParamRtlFrontend._
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.Design

private[morphhdl] object ParameterForwardingContractFixture {
  def program(reverseConstructionOrder: Boolean): MorphProgram[Component] =
    MorphProgram(
      concreteWitness = new Component {
        setDefinitionName("ParameterForwarding")
        val din = in(morphhdl.frontend.Bits(32 bits))
        val dout = out(morphhdl.frontend.Bits(32 bits))
        val forwarded_inst = new Component {
          setDefinitionName("ForwardingLeaf")
          val din = in(morphhdl.frontend.Bits(32 bits))
          val dout = out(morphhdl.frontend.Bits(32 bits))
          dout := din
        }
        forwarded_inst.din := din
        dout := forwarded_inst.dout
      },
      parameterizedDesign = {
        val leafWidth = HdlInt.param("WIDTH", default = 1, min = 1, max = 65536)
        val leafPacked = packedBits(leafWidth)
        val leaf = moduleDef(
          name = "ForwardingLeaf",
          parameters = Vector(integerParameter(leafWidth)),
          ports = ordered(
            Vector(port("din", Input, leafPacked), port("dout", Output, leafPacked)),
            reverseConstructionOrder
          ),
          items = captureItems {
            emitContinuousAssign("dout", ref("din"))
          }
        )

        val dataWidth = HdlInt.param("DATA_WIDTH", default = 8, min = 1, max = 1024)
        val lanes = HdlInt.param("LANES", default = 4, min = 1, max = 64)
        val totalWidth = localParam("TOTAL_WIDTH", lanes * dataWidth)
        val topPacked = packedBits(totalWidth)
        val top = moduleDef(
          name = "ParameterForwarding",
          parameters = ordered(
            Vector(integerParameter(dataWidth), integerParameter(lanes)),
            reverseConstructionOrder
          ),
          ports = ordered(
            Vector(port("din", Input, topPacked), port("dout", Output, topPacked)),
            reverseConstructionOrder
          ),
          items = captureItems {
            emitInstance(
              name = "forwarded_inst",
              moduleName = "ForwardingLeaf",
              parameterBindings = ordered(
                Vector(parameterBinding("WIDTH", totalWidth)),
                reverseConstructionOrder
              ),
              portConnections = ordered(
                Vector(
                  portConnection("din", ref("din")),
                  portConnection("dout", ref("dout"))
                ),
                reverseConstructionOrder
              )
            )
          },
          localParameters = Vector(integerLocalParameter(totalWidth))
        )

        Design(
          top = top.name,
          modules = ordered(Vector(top, leaf), reverseConstructionOrder)
        )
      }
    )

  private def ordered[A](values: Vector[A], reverse: Boolean): Vector[A] =
    if (reverse) values.reverse else values
}
