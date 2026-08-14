package morphhdl

import spinal.core._

import morphhdl.frontend._
import morphhdl.frontend.ParamRtlFrontend._
import morphhdl.paramrtl.Design
import morphhdl.paramrtl.PortDirection.{Input, Output}

/** Sixteenth public MorphVerilog contract: one synchronous read-first memory. */
private[morphhdl] object SinglePortMemoryContractFixture {
  def program(reverseConstructionOrder: Boolean): MorphProgram[Component] =
    MorphProgram(
      concreteWitness = new Component {
        setDefinitionName("SinglePortMemory")
        val clk = in(Bool()).setName("clk")
        val write_enable = in(Bool()).setName("write_enable")
        val address = in(UInt(3 bits)).setName("address")
        val write_data = in(Bits(8 bits)).setName("write_data")
        val read_data = out(Bits(8 bits)).setName("read_data")

        val memoryClockDomain = ClockDomain(clock = clk)
        val memoryArea = new ClockingArea(memoryClockDomain) {
          val memory = Mem(Bits(8 bits), wordCount = 5)
          val addressInRange = address < 5
          val value = memory.readWriteSync(
            address = address,
            data = write_data,
            enable = addressInRange,
            write = write_enable,
            readUnderWrite = readFirst,
            duringWrite = doRead
          )
          val valid = RegNext(addressInRange)
        }
        read_data := Mux(memoryArea.valid, memoryArea.value, B(0, 8 bits))
      },
      parameterizedDesign = {
        val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 32)
        val depth = HdlInt.param("DEPTH", default = 5, min = 1, max = 5)
        val elementType = packedBits(width)
        val addressType = packedBits(3)
        val control = packedBits(1)

        val top = moduleDef(
          name = "SinglePortMemory",
          parameters = ordered(
            Vector(integerParameter(width), integerParameter(depth)),
            reverseConstructionOrder
          ),
          ports = ordered(
            Vector(
              port("clk", Input, control),
              port("write_enable", Input, control),
              port("address", Input, addressType),
              port("write_data", Input, elementType),
              port("read_data", Output, elementType)
            ),
            reverseConstructionOrder
          ),
          items = captureItems {
            emitSynchronousReadFirstSinglePortMemory(
              label = "p_memory",
              memoryName = "memory",
              clock = ref("clk"),
              writeEnable = ref("write_enable"),
              address = ref("address"),
              writeData = ref("write_data"),
              readData = ref("read_data"),
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
