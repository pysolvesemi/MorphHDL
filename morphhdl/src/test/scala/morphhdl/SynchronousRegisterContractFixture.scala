package morphhdl

import spinal.core._

import morphhdl.frontend._
import morphhdl.frontend.ParamRtlFrontend._
import morphhdl.paramrtl.Design
import morphhdl.paramrtl.PortDirection.{Input, Output}

/** Twelfth public MorphVerilog contract: one synchronous reset-to-zero register. */
private[morphhdl] object SynchronousRegisterContractFixture {
  def program(reverseConstructionOrder: Boolean): MorphProgram[Component] =
    MorphProgram(
      concreteWitness = new Component {
        setDefinitionName("SynchronousRegister")
        val clk = in(Bool()).setName("clk")
        val reset = in(Bool()).setName("reset")
        val data_in = in(Bits(8 bits)).setName("data_in")
        val data_out = out(Bits(8 bits)).setName("data_out")

        val registerClockDomain = ClockDomain(
          clock = clk,
          reset = reset,
          config = ClockDomainConfig(
            clockEdge = RISING,
            resetKind = SYNC,
            resetActiveLevel = HIGH
          )
        )
        val registerArea = new ClockingArea(registerClockDomain) {
          val state = Reg(Bits(8 bits)) init (0)
          state := data_in
          data_out := state
        }
      },
      parameterizedDesign = {
        val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 32)
        val packed = packedBits(width)
        val control = packedBits(1)

        val top = moduleDef(
          name = "SynchronousRegister",
          parameters = Vector(integerParameter(width)),
          ports = ordered(
            Vector(
              port("clk", Input, control),
              port("reset", Input, control),
              port("data_in", Input, packed),
              port("data_out", Output, packed)
            ),
            reverseConstructionOrder
          ),
          items = captureItems {
            emitSynchronousRegister(
              label = "p_sync_register",
              clock = ref("clk"),
              reset = ref("reset"),
              assignment = proceduralAssign("data_out", ref("data_in"))
            )
          }
        )

        Design(top = top.name, modules = Vector(top))
      }
    )

  private def ordered[A](values: Vector[A], reverse: Boolean): Vector[A] =
    if (reverse) values.reverse else values
}
