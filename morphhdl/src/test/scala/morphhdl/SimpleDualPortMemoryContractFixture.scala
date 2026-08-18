package morphhdl

import spinal.core._

import morphhdl.frontend._
import morphhdl.frontend.ParamRtlFrontend._
import morphhdl.paramrtl.Design
import morphhdl.paramrtl.PortDirection.{Input, Output}

/** Eighteenth public MorphVerilog contract: one synchronous read-first simple-dual-port memory. */
private[morphhdl] object SimpleDualPortMemoryContractFixture {
  def program(reverseConstructionOrder: Boolean): MorphProgram[Component] =
    MorphProgram(
      concreteWitness = new Component {
        setDefinitionName("SimpleDualPortMemory")
        val clk = in(Bool()).setName("clk")
        val read_enable = in(Bool()).setName("read_enable")
        val write_enable = in(Bool()).setName("write_enable")
        val read_address = in(morphhdl.frontend.UInt(3 bits)).setName("read_address")
        val write_address = in(morphhdl.frontend.UInt(3 bits)).setName("write_address")
        val write_data = in(morphhdl.frontend.Bits(8 bits)).setName("write_data")
        val read_data = out(morphhdl.frontend.Bits(8 bits)).setName("read_data")

        val memoryClockDomain = ClockDomain(clock = clk)
        val memoryArea = new ClockingArea(memoryClockDomain) {
          val memory = Mem(morphhdl.frontend.HardType(morphhdl.frontend.Bits(8 bits)), wordCount = 5)
          val readAddressInRange = read_address < 5
          val writeAddressInRange = write_address < 5
          val value = memory.readSync(
            address = read_address,
            enable = read_enable && readAddressInRange,
            readUnderWrite = readFirst
          )
          memory.write(
            address = write_address,
            data = write_data,
            enable = write_enable && writeAddressInRange
          )
          val delayedReadEnable = RegNext(read_enable)
          val delayedReadAddressInRange = RegNext(readAddressInRange)
          val enabledReadValue = Mux(delayedReadAddressInRange, value, B(0, 8 bits))
          val heldReadValue = RegNextWhen(enabledReadValue, delayedReadEnable)
        }
        read_data := Mux(
          memoryArea.delayedReadEnable,
          memoryArea.enabledReadValue,
          memoryArea.heldReadValue
        )
      },
      parameterizedDesign = {
        val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 32)
        val depth = HdlInt.param("DEPTH", default = 5, min = 1, max = 8)
        val elementType = packedBits(width)
        val addressType = packedBits(depth.addressWidth)
        val control = packedBits(1)

        val top = moduleDef(
          name = "SimpleDualPortMemory",
          parameters = ordered(
            Vector(integerParameter(width), integerParameter(depth)),
            reverseConstructionOrder
          ),
          ports = ordered(
            Vector(
              port("clk", Input, control),
              port("read_enable", Input, control),
              port("write_enable", Input, control),
              port("read_address", Input, addressType),
              port("write_address", Input, addressType),
              port("write_data", Input, elementType),
              port("read_data", Output, elementType)
            ),
            reverseConstructionOrder
          ),
          items = captureItems {
            emitSynchronousReadFirstSimpleDualPortMemory(
              label = "p_memory",
              memoryName = "memory",
              clock = ref("clk"),
              readEnable = ref("read_enable"),
              writeEnable = ref("write_enable"),
              readAddress = ref("read_address"),
              writeAddress = ref("write_address"),
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
