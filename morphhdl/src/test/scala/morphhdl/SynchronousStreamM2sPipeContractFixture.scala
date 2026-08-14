package morphhdl

import spinal.core._
import spinal.lib.Stream

import morphhdl.frontend._
import morphhdl.frontend.ParamRtlFrontend._
import morphhdl.paramrtl.Design
import morphhdl.paramrtl.PortDirection.{Input, Output}

/** Twentieth public MorphVerilog contract: one registered Stream m2s pipeline stage. */
private[morphhdl] object SynchronousStreamM2sPipeContractFixture {
  def program(reverseConstructionOrder: Boolean): MorphProgram[Component] =
    MorphProgram(
      concreteWitness = new Component {
        setDefinitionName("SynchronousStreamM2sPipe")

        val pushValid = in(Bool()).setName("push_valid")
        val pushReady = out(Bool()).setName("push_ready")
        val pushData = in(Bits(8 bits)).setName("push_data")
        val popValid = out(Bool()).setName("pop_valid")
        val popReady = in(Bool()).setName("pop_ready")
        val popData = out(Bits(8 bits)).setName("pop_data")

        val push = Stream(Bits(8 bits))
        push.valid := pushValid
        push.payload := pushData
        pushReady := push.ready

        val pop = push.m2sPipe()
        popValid := pop.valid
        pop.ready := popReady
        popData := pop.payload
      },
      parameterizedDesign = {
        val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 32)
        val elementType = packedBits(width)
        val control = packedBits(1)

        val top = moduleDef(
          name = "SynchronousStreamM2sPipe",
          parameters = Vector(integerParameter(width)),
          ports = ordered(
            Vector(
              port("clk", Input, control),
              port("reset", Input, control),
              port("push_valid", Input, control),
              port("push_ready", Output, control),
              port("push_data", Input, elementType),
              port("pop_valid", Output, control),
              port("pop_ready", Input, control),
              port("pop_data", Output, elementType)
            ),
            reverseConstructionOrder
          ),
          items = captureItems {
            emitSynchronousStreamM2sPipe(
              label = "p_m2s_pipe",
              clock = ref("clk"),
              reset = ref("reset"),
              pushValid = ref("push_valid"),
              pushReady = ref("push_ready"),
              pushData = ref("push_data"),
              popValid = ref("pop_valid"),
              popReady = ref("pop_ready"),
              popData = ref("pop_data"),
              elementType = elementType
            )
          }
        )

        Design(top = top.name, modules = Vector(top))
      }
    )

  private def ordered[A](values: Vector[A], reverse: Boolean): Vector[A] =
    if (reverse) values.reverse else values
}
