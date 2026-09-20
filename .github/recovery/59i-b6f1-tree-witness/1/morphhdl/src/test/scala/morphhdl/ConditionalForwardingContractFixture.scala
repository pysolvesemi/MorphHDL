package morphhdl

import spinal.core._

import morphhdl.frontend._
import morphhdl.frontend.ParamRtlFrontend._
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.Design

private[morphhdl] object ConditionalForwardingContractFixture {
  def program(reverseConstructionOrder: Boolean): MorphProgram[Component] =
    MorphProgram(
      concreteWitness = new Component {
        setDefinitionName("ConditionalForwarding")
        val din = in(morphhdl.frontend.Bits(8 bits))
        val dout = out(morphhdl.frontend.Bits(8 bits))
        val selected_inst = new Component {
          setDefinitionName("ConditionalLeaf")
          val din = in(morphhdl.frontend.Bits(8 bits))
          val dout = out(morphhdl.frontend.Bits(8 bits))
          dout := din
        }
        selected_inst.din := din
        dout := selected_inst.dout
      },
      parameterizedDesign = {
        val leafWidth = HdlInt.param("WIDTH", default = 1, min = 1, max = 65536)
        val leafPacked = packedBits(leafWidth)
        val leaf = moduleDef(
          name = "ConditionalLeaf",
          parameters = Vector(integerParameter(leafWidth)),
          ports = ordered(
            Vector(port("din", Input, leafPacked), port("dout", Output, leafPacked)),
            reverseConstructionOrder
          ),
          items = captureItems {
            emitContinuousAssign("dout", ref("din"))
          }
        )

        val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 65536)
        val enable = HdlBool.param("ENABLE", default = true)
        val packed = packedBits(width)
        val top = moduleDef(
          name = "ConditionalForwarding",
          parameters = ordered(Vector(integerParameter(width)), reverseConstructionOrder),
          ports = ordered(
            Vector(port("din", Input, packed), port("dout", Output, packed)),
            reverseConstructionOrder
          ),
          items = captureItems {
            generateIf(enable, "g_enabled", "g_disabled") {
              emitSelectedInstance(width, reverseConstructionOrder)
            } otherwise {
              emitSelectedInstance(width, reverseConstructionOrder)
            }
          },
          booleanParameters = ordered(
            Vector(booleanParameter(enable)),
            reverseConstructionOrder
          )
        )

        Design(
          top = top.name,
          modules = ordered(Vector(top, leaf), reverseConstructionOrder)
        )
      }
    )

  private def emitSelectedInstance(width: HdlInt, reverseConstructionOrder: Boolean)(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): Unit =
    emitInstance(
      name = "selected_inst",
      moduleName = "ConditionalLeaf",
      parameterBindings = ordered(
        Vector(parameterBinding("WIDTH", width)),
        reverseConstructionOrder
      ),
      portConnections = ordered(
        Vector(
          portConnection("din", ref("din")),
          portConnection("dout", ref("dout"))
        ),
        reverseConstructionOrder
      )
    )

  private def ordered[A](values: Vector[A], reverse: Boolean): Vector[A] =
    if (reverse) values.reverse else values
}
