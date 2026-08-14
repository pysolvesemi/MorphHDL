package morphhdl

import spinal.core._
import spinal.lib.Counter

import morphhdl.frontend._
import morphhdl.frontend.ParamRtlFrontend._
import morphhdl.paramrtl.Design
import morphhdl.paramrtl.PortDirection.{Input, Output}

/** Seventeenth public MorphVerilog contract: one bounded parameterized synchronous counter. */
private[morphhdl] object ParameterizedCounterContractFixture {
  def program(reverseConstructionOrder: Boolean): MorphProgram[Component] =
    MorphProgram(
      concreteWitness = new Component {
        setDefinitionName("ParameterizedCounter")
        val clk = in(Bool()).setName("clk")
        val reset = in(Bool()).setName("reset")
        val enable = in(Bool()).setName("enable")
        val count = out(UInt(3 bits)).setName("count")

        val counterClockDomain = ClockDomain(
          clock = clk,
          reset = reset,
          config = ClockDomainConfig(
            clockEdge = RISING,
            resetKind = SYNC,
            resetActiveLevel = HIGH
          )
        )
        val counterArea = new ClockingArea(counterClockDomain) {
          val state = Counter(5)
          when(enable) {
            state.increment()
          }
          count := state.value
        }
      },
      parameterizedDesign = {
        val limit = HdlInt.param("LIMIT", default = 5, min = 1, max = 8)
        val control = packedBits(1)

        val top = moduleDef(
          name = "ParameterizedCounter",
          parameters = Vector(integerParameter(limit)),
          ports = ordered(
            Vector(
              port("clk", Input, control),
              port("reset", Input, control),
              port("enable", Input, control),
              port("count", Output, packedBits(limit.addressWidth))
            ),
            reverseConstructionOrder
          ),
          items = captureItems {
            emitSynchronousCounter(
              label = "p_counter",
              clock = ref("clk"),
              reset = ref("reset"),
              enable = ref("enable"),
              count = ref("count"),
              limit = limit
            )
          }
        )

        Design(top = top.name, modules = Vector(top))
      }
    )

  private def ordered[A](values: Vector[A], reverse: Boolean): Vector[A] =
    if (reverse) values.reverse else values
}
