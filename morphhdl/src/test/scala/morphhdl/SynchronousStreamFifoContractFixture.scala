package morphhdl

import spinal.core._
import spinal.lib.StreamFifo

import morphhdl.frontend._
import morphhdl.frontend.ParamRtlFrontend._
import morphhdl.paramrtl.Design
import morphhdl.paramrtl.PortDirection.{Input, Output}

/** Nineteenth public MorphVerilog contract: one bounded synchronous Stream FIFO. */
private[morphhdl] object SynchronousStreamFifoContractFixture {
  def program(reverseConstructionOrder: Boolean): MorphProgram[Component] =
    MorphProgram(
      concreteWitness = new StreamFifo(
        dataType = Bits(8 bits),
        depth = 5,
        withAsyncRead = false,
        withBypass = false
      ) {
        setDefinitionName("SynchronousStreamFifo")
        // The library's external occupancy/full tracker includes its
        // registered output stage, so constructor depth five is public
        // outstanding capacity five.
        io.push.valid.setName("push_valid")
        io.push.ready.setName("push_ready")
        io.push.payload.setName("push_data")
        io.pop.valid.setName("pop_valid")
        io.pop.ready.setName("pop_ready")
        io.pop.payload.setName("pop_data")
        io.flush.setAsDirectionLess()
        io.flush.addTag(allowDirectionLessIoTag)
        io.flush := False
        io.occupancy.setAsDirectionLess()
        io.occupancy.addTag(allowDirectionLessIoTag)
        io.availability.setAsDirectionLess()
        io.availability.addTag(allowDirectionLessIoTag)
      },
      parameterizedDesign = {
        val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 32)
        val depth = HdlInt.param("DEPTH", default = 5, min = 1, max = 8)
        val elementType = packedBits(width)
        val control = packedBits(1)

        val top = moduleDef(
          name = "SynchronousStreamFifo",
          parameters = ordered(
            Vector(integerParameter(width), integerParameter(depth)),
            reverseConstructionOrder
          ),
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
            emitSynchronousStreamFifo(
              label = "p_fifo",
              memoryName = "memory",
              clock = ref("clk"),
              reset = ref("reset"),
              pushValid = ref("push_valid"),
              pushReady = ref("push_ready"),
              pushData = ref("push_data"),
              popValid = ref("pop_valid"),
              popReady = ref("pop_ready"),
              popData = ref("pop_data"),
              elementType = elementType,
              depth = depth
            )
          }
        )

        Design(top = top.name, modules = Vector(top))
      }
    )

  private def ordered[A](values: Vector[A], reverse: Boolean): Vector[A] =
    if (reverse) values.reverse else values
}
