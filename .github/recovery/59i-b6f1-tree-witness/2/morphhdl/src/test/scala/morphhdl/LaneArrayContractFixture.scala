package morphhdl

import spinal.core._

import morphhdl.frontend._
import morphhdl.frontend.ParamRtlFrontend._
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.Design

private[morphhdl] object LaneArrayContractFixture {
  def program(reverseConstructionOrder: Boolean): MorphProgram[Component] =
    MorphProgram(
      concreteWitness = new Component {
        setDefinitionName("LaneArray")
        val data_in = in(morphhdl.frontend.Bits(32 bits))
        val data_out = out(morphhdl.frontend.Bits(32 bits))

        val laneIndices = ordered((0 until 4).toVector, reverseConstructionOrder)
        val laneInstances = laneIndices.map { lane =>
          val instance = new Component {
            setDefinitionName("PixelLane")
            val data_in = in(morphhdl.frontend.Bits(8 bits))
            val data_out = out(morphhdl.frontend.Bits(8 bits))
            data_out := data_in
          }
          instance.data_in := data_in(lane * 8 + 7 downto lane * 8)
          data_out(lane * 8 + 7 downto lane * 8) := instance.data_out
          instance
        }
      },
      parameterizedDesign = {
        val dataWidth = HdlInt.param("DATA_WIDTH", default = 8, min = 1, max = 1024)
        val lanes = HdlInt.param("LANES", default = 4, min = 1, max = 64)
        val lanePacked = packedBits(dataWidth)
        val arrayPacked = packedBits(lanes * dataWidth)
        val leaf = moduleDef(
          name = "PixelLane",
          parameters = Vector(integerParameter(dataWidth)),
          ports = ordered(
            Vector(
              port("data_in", Input, lanePacked),
              port("data_out", Output, lanePacked)
            ),
            reverseConstructionOrder
          ),
          items = captureItems {
            emitContinuousAssign("data_out", ref("data_in"))
          }
        )
        val top = moduleDef(
          name = "LaneArray",
          parameters = ordered(
            Vector(integerParameter(dataWidth), integerParameter(lanes)),
            reverseConstructionOrder
          ),
          ports = ordered(
            Vector(
              port("data_in", Input, arrayPacked),
              port("data_out", Output, arrayPacked)
            ),
            reverseConstructionOrder
          ),
          items = captureItems {
            for (lane <- (0 until lanes).named(label = "g_lane", index = "lane")) {
              val offset = lane * dataWidth
              emitInstance(
                name = "lane_inst",
                moduleName = "PixelLane",
                parameterBindings = ordered(
                  Vector(parameterBinding("DATA_WIDTH", dataWidth)),
                  reverseConstructionOrder
                ),
                portConnections = ordered(
                  Vector(
                    portConnection(
                      "data_in",
                      indexedPartSelect("data_in", offset, dataWidth)
                    ),
                    portConnection(
                      "data_out",
                      indexedPartSelect("data_out", offset, dataWidth)
                    )
                  ),
                  reverseConstructionOrder
                )
              )
            }
          }
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
