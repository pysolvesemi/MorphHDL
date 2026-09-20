package morphhdl

import spinal.core._

import morphhdl.frontend._
import morphhdl.frontend.ParamRtlFrontend._
import morphhdl.paramrtl.Design
import morphhdl.paramrtl.PortDirection.{Input, Output}

/** Eleventh public MorphVerilog contract: one complete runtime combinational mux. */
private[morphhdl] object RuntimeMuxContractFixture {
  def program(reverseConstructionOrder: Boolean): MorphProgram[Component] =
    MorphProgram(
      concreteWitness = new Component {
        setDefinitionName("RuntimeMux")
        val sel = in(Bool()).setName("sel")
        val data_false = in(morphhdl.frontend.Bits(8 bits)).setName("data_false")
        val data_true = in(morphhdl.frontend.Bits(8 bits)).setName("data_true")
        val result = out(morphhdl.frontend.Bits(8 bits)).setName("result")

        when(sel) {
          result := data_true
        } otherwise {
          result := data_false
        }
      },
      parameterizedDesign = {
        val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 32)
        val packed = packedBits(width)
        val selectType = packedBits(1)
        val selectRef = ref("sel")
        val trueAssignment = proceduralAssign("result", ref("data_true"))
        val falseAssignment = proceduralAssign("result", ref("data_false"))

        val top = moduleDef(
          name = "RuntimeMux",
          parameters = Vector(integerParameter(width)),
          ports = ordered(
            Vector(
              port("sel", Input, selectType),
              port("data_false", Input, packed),
              port("data_true", Input, packed),
              port("result", Output, packed)
            ),
            reverseConstructionOrder
          ),
          items = captureItems {
            emitCombinationalIf(
              label = "p_runtime_mux",
              condition = selectRef,
              whenTrue = Vector(trueAssignment),
              whenFalse = Vector(falseAssignment)
            )
          }
        )

        Design(top = top.name, modules = Vector(top))
      }
    )

  private def ordered[A](values: Vector[A], reverse: Boolean): Vector[A] =
    if (reverse) values.reverse else values
}
