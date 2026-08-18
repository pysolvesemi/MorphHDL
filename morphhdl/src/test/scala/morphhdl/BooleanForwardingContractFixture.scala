package morphhdl

import spinal.core._

import morphhdl.frontend._
import morphhdl.frontend.ParamRtlFrontend._
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.Design

/** Eighth public MorphVerilog contract: one typed Boolean child binding. */
private[morphhdl] object BooleanForwardingContractFixture {
  def program(reverseConstructionOrder: Boolean): MorphProgram[Component] =
    MorphProgram(
      concreteWitness = new Component {
        setDefinitionName("BooleanForwarding")
        val high_in = in(morphhdl.frontend.Bits(8 bits))
        val low_in = in(morphhdl.frontend.Bits(8 bits))
        val dout = out(morphhdl.frontend.Bits(8 bits))

        val route_inst = new Component {
          setDefinitionName("BooleanRoute")
          val high_in = in(morphhdl.frontend.Bits(8 bits))
          val low_in = in(morphhdl.frontend.Bits(8 bits))
          val dout = out(morphhdl.frontend.Bits(8 bits))

          val selected_inst = new Component {
            setDefinitionName("BooleanHighRoute")
            val high_in = in(morphhdl.frontend.Bits(8 bits))
            val high_out = out(morphhdl.frontend.Bits(8 bits))
            high_out := high_in
          }

          selected_inst.high_in := high_in
          dout := selected_inst.high_out
        }

        route_inst.high_in := high_in
        route_inst.low_in := low_in
        dout := route_inst.dout
      },
      parameterizedDesign = {
        val packed = packedBits(8)
        val high = moduleDef(
          name = "BooleanHighRoute",
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
          name = "BooleanLowRoute",
          parameters = Vector.empty,
          ports = ordered(
            Vector(port("low_in", Input, packed), port("low_out", Output, packed)),
            reverseConstructionOrder
          ),
          items = captureItems {
            emitContinuousAssign("low_out", ref("low_in"))
          }
        )

        val select = HdlBool.param("SELECT", default = false)
        val route = moduleDef(
          name = "BooleanRoute",
          parameters = Vector.empty,
          ports = ordered(
            Vector(
              port("high_in", Input, packed),
              port("low_in", Input, packed),
              port("dout", Output, packed)
            ),
            reverseConstructionOrder
          ),
          items = captureItems {
            generateIf(select, "g_high", "g_low") {
              emitInstance(
                name = "selected_inst",
                moduleName = high.name,
                portConnections = ordered(
                  Vector(
                    portConnection("high_in", ref("high_in")),
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
                    portConnection("low_in", ref("low_in")),
                    portConnection("low_out", ref("dout"))
                  ),
                  reverseConstructionOrder
                )
              )
            }
          },
          booleanParameters = Vector(booleanParameter(select))
        )

        val enable = HdlBool.param("ENABLE", default = true)
        val width = HdlInt.param("WIDTH", default = 7, min = 0, max = 31)
        val offset = HdlInt.param("OFFSET", default = 1, min = 0, max = 8)
        val limit = HdlInt.param("LIMIT", default = 8, min = 0, max = 31)
        val effectiveWidth = localParam("EFFECTIVE_WIDTH", width + offset)
        val routeEnabled = enable && (effectiveWidth >= limit)
        val top = moduleDef(
          name = "BooleanForwarding",
          parameters = ordered(
            Vector(
              integerParameter(width),
              integerParameter(offset),
              integerParameter(limit)
            ),
            reverseConstructionOrder
          ),
          ports = ordered(
            Vector(
              port("high_in", Input, packed),
              port("low_in", Input, packed),
              port("dout", Output, packed)
            ),
            reverseConstructionOrder
          ),
          items = captureItems {
            emitInstance(
              name = "route_inst",
              moduleName = route.name,
              booleanParameterBindings = Vector(
                parameterBinding("SELECT", routeEnabled)
              ),
              portConnections = ordered(
                Vector(
                  portConnection("high_in", ref("high_in")),
                  portConnection("low_in", ref("low_in")),
                  portConnection("dout", ref("dout"))
                ),
                reverseConstructionOrder
              )
            )
          },
          localParameters = Vector(integerLocalParameter(effectiveWidth)),
          booleanParameters = Vector(booleanParameter(enable))
        )

        Design(
          top = top.name,
          modules = ordered(Vector(top, route, high, low), reverseConstructionOrder)
        )
      }
    )

  private def ordered[A](values: Vector[A], reverse: Boolean): Vector[A] =
    if (reverse) values.reverse else values
}
