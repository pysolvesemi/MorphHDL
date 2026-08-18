package morphhdl

import spinal.core._

import morphhdl.frontend._
import morphhdl.frontend.ParamRtlFrontend._
import morphhdl.paramrtl.Design
import morphhdl.paramrtl.PortDirection.{Input, Output}

/** Tenth public MorphVerilog contract: one bounded, mandatory-default GenerateCase. */
private[morphhdl] object CaseRoutingContractFixture {
  def program(reverseConstructionOrder: Boolean): MorphProgram[Component] =
    MorphProgram(
      concreteWitness = new Component {
        setDefinitionName("CaseRouting")
        val din = in(morphhdl.frontend.Bits(8 bits))
        val dout = out(morphhdl.frontend.Bits(8 bits))

        val selected_inst = new Component {
          setDefinitionName("CaseZeroRoute")
          val zero_in = in(morphhdl.frontend.Bits(8 bits))
          val zero_out = out(morphhdl.frontend.Bits(8 bits))
          zero_out := zero_in
        }

        selected_inst.zero_in := din
        dout := selected_inst.zero_out
      },
      parameterizedDesign = {
        val packed = packedBits(8)
        val zero = moduleDef(
          name = "CaseZeroRoute",
          parameters = Vector.empty,
          ports = ordered(
            Vector(port("zero_in", Input, packed), port("zero_out", Output, packed)),
            reverseConstructionOrder
          ),
          items = captureItems {
            emitContinuousAssign("zero_out", ref("zero_in"))
          }
        )
        val one = moduleDef(
          name = "CaseOneRoute",
          parameters = Vector.empty,
          ports = ordered(
            Vector(port("one_in", Input, packed), port("one_out", Output, packed)),
            reverseConstructionOrder
          ),
          items = captureItems {
            emitContinuousAssign("one_out", ref("one_in"))
          }
        )
        val default = moduleDef(
          name = "CaseDefaultRoute",
          parameters = Vector.empty,
          ports = ordered(
            Vector(
              port("default_in", Input, packed),
              port("default_out", Output, packed)
            ),
            reverseConstructionOrder
          ),
          items = captureItems {
            emitContinuousAssign("default_out", ref("default_in"))
          }
        )

        val mode = HdlInt.param("MODE", default = 0, min = 0, max = 7)
        val offset = HdlInt.param("OFFSET", default = 0, min = 0, max = 2)
        val selector = localParam("SELECTOR", mode + offset)
        val top = moduleDef(
          name = "CaseRouting",
          parameters = ordered(
            Vector(integerParameter(mode), integerParameter(offset)),
            reverseConstructionOrder
          ),
          ports = ordered(
            Vector(port("din", Input, packed), port("dout", Output, packed)),
            reverseConstructionOrder
          ),
          items = captureItems {
            generateCase(selector)
              .choice(BigInt(0), "g_zero") {
                emitInstance(
                  name = "selected_inst",
                  moduleName = zero.name,
                  portConnections = ordered(
                    Vector(
                      portConnection("zero_in", ref("din")),
                      portConnection("zero_out", ref("dout"))
                    ),
                    reverseConstructionOrder
                  )
                )
              }
              .choice(BigInt(1), "g_one") {
                emitInstance(
                  name = "selected_inst",
                  moduleName = one.name,
                  portConnections = ordered(
                    Vector(
                      portConnection("one_in", ref("din")),
                      portConnection("one_out", ref("dout"))
                    ),
                    reverseConstructionOrder
                  )
                )
              }
              .default("g_default") {
                emitInstance(
                  name = "selected_inst",
                  moduleName = default.name,
                  portConnections = ordered(
                    Vector(
                      portConnection("default_in", ref("din")),
                      portConnection("default_out", ref("dout"))
                    ),
                    reverseConstructionOrder
                  )
                )
              }
          },
          localParameters = Vector(integerLocalParameter(selector))
        )

        Design(
          top = top.name,
          modules = ordered(Vector(top, zero, one, default), reverseConstructionOrder)
        )
      }
    )

  private def ordered[A](values: Vector[A], reverse: Boolean): Vector[A] =
    if (reverse) values.reverse else values
}
