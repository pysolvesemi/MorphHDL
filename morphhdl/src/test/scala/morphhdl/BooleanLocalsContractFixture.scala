package morphhdl

import spinal.core._

import morphhdl.frontend._
import morphhdl.frontend.ParamRtlFrontend._
import morphhdl.paramrtl.Design
import morphhdl.paramrtl.PortDirection.{Input, Output}

/** Ninth public MorphVerilog contract: a consumed mixed integer/Boolean local graph. */
private[morphhdl] object BooleanLocalsContractFixture {
  def program(reverseConstructionOrder: Boolean): MorphProgram[Component] =
    MorphProgram(
      concreteWitness = new Component {
        setDefinitionName("BooleanLocals")
        val din = in(Bits(8 bits))
        val dout = out(Bits(8 bits))

        val route_inst = new Component {
          setDefinitionName("BooleanLocalRoute")
          val din = in(Bits(8 bits))
          val dout = out(Bits(8 bits))

          val selected_inst = new Component {
            setDefinitionName("BooleanLocalHighRoute")
            val high_in = in(Bits(8 bits))
            val high_out = out(Bits(8 bits))
            high_out := high_in
          }

          selected_inst.high_in := din
          dout := selected_inst.high_out
        }

        route_inst.din := din
        dout := route_inst.dout
      },
      parameterizedDesign = {
        val packed = packedBits(8)
        val high = moduleDef(
          name = "BooleanLocalHighRoute",
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
          name = "BooleanLocalLowRoute",
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
          name = "BooleanLocalRoute",
          parameters = Vector.empty,
          ports = ordered(
            Vector(port("din", Input, packed), port("dout", Output, packed)),
            reverseConstructionOrder
          ),
          items = captureItems {
            generateIf(select, "g_high", "g_low") {
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
          },
          booleanParameters = Vector(booleanParameter(select))
        )

        val enable = HdlBool.param("ENABLE", default = true)
        val width = HdlInt.param("WIDTH", default = 8, min = 0, max = 31)
        val limit = HdlInt.param("LIMIT", default = 8, min = 0, max = 31)
        val effectiveWidth = localParam("EFFECTIVE_WIDTH", width)
        val widthOk = localParam("WIDTH_OK", effectiveWidth >= limit)
        val routeHigh = localParam("ROUTE_HIGH", enable && widthOk)
        val routeCode = localParam("ROUTE_CODE", routeHigh.select(1, 0))
        val top = moduleDef(
          name = "BooleanLocals",
          parameters = ordered(
            Vector(integerParameter(width), integerParameter(limit)),
            reverseConstructionOrder
          ),
          ports = ordered(
            Vector(port("din", Input, packed), port("dout", Output, packed)),
            reverseConstructionOrder
          ),
          items = captureItems {
            emitInstance(
              name = "route_inst",
              moduleName = route.name,
              booleanParameterBindings = Vector(
                parameterBinding("SELECT", routeCode.hdlEq(1))
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
          localParameters = ordered(
            Vector(
              integerLocalParameter(effectiveWidth),
              integerLocalParameter(routeCode)
            ),
            reverseConstructionOrder
          ),
          booleanParameters = Vector(booleanParameter(enable)),
          booleanLocalParameters = ordered(
            Vector(
              booleanLocalParameter(widthOk),
              booleanLocalParameter(routeHigh)
            ),
            reverseConstructionOrder
          )
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
