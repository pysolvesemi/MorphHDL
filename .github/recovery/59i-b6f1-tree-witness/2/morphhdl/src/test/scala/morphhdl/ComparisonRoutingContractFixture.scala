package morphhdl

import spinal.core._

import morphhdl.frontend._
import morphhdl.frontend.ParamRtlFrontend._
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.Design

private[morphhdl] object ComparisonRoutingContractFixture {
  def program(reverseConstructionOrder: Boolean): MorphProgram[Component] =
    MorphProgram(
      concreteWitness = new Component {
        setDefinitionName("ComparisonRouting")
        val din = in(morphhdl.frontend.Bits(8 bits))
        val dout = out(morphhdl.frontend.Bits(8 bits))
        val selected_inst = new Component {
          setDefinitionName("HighRoute")
          val high_in = in(morphhdl.frontend.Bits(8 bits))
          val high_out = out(morphhdl.frontend.Bits(8 bits))
          high_out := high_in
        }
        selected_inst.high_in := din
        dout := selected_inst.high_out
      },
      parameterizedDesign = {
        val packed = packedBits(8)
        val high = moduleDef(
          name = "HighRoute",
          parameters = Vector.empty,
          ports = ordered(
            Vector(port("high_in", Input, packed), port("high_out", Output, packed)),
            reverseConstructionOrder
          ),
          items = captureItems {
            emitContinuousAssign("high_out", ref("high_in"))
          }
        )
        val low = moduleDef(
          name = "LowRoute",
          parameters = Vector.empty,
          ports = ordered(
            Vector(port("low_in", Input, packed), port("low_out", Output, packed)),
            reverseConstructionOrder
          ),
          items = captureItems {
            emitContinuousAssign("low_out", ref("low_in"))
          }
        )

        val select = HdlInt.param("SELECT", default = 8, min = 0, max = 31)
        val threshold = HdlInt.param("THRESHOLD", default = 5, min = 0, max = 31)
        val top = moduleDef(
          name = "ComparisonRouting",
          parameters = ordered(
            Vector(integerParameter(select), integerParameter(threshold)),
            reverseConstructionOrder
          ),
          ports = ordered(
            Vector(port("din", Input, packed), port("dout", Output, packed)),
            reverseConstructionOrder
          ),
          items = captureItems {
            generateIf(select >= threshold, "g_high", "g_low") {
              emitInstance(
                name = "selected_inst",
                moduleName = high.name,
                portConnections = ordered(
                  Vector(
                    portConnection("high_in", ref("din")),
                    portConnection("high_out", ref("dout"))
                  ),
                  reverseConstructionOrder
                )
              )
            } otherwise {
              emitInstance(
                name = "selected_inst",
                moduleName = low.name,
                portConnections = ordered(
                  Vector(
                    portConnection("low_in", ref("din")),
                    portConnection("low_out", ref("dout"))
                  ),
                  reverseConstructionOrder
                )
              )
            }
          }
        )

        Design(
          top = top.name,
          modules = ordered(Vector(top, high, low), reverseConstructionOrder)
        )
      }
    )

  private def ordered[A](values: Vector[A], reverse: Boolean): Vector[A] =
    if (reverse) values.reverse else values
}
