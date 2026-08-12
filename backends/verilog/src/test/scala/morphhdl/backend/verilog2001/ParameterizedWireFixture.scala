package morphhdl.backend.verilog2001

import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}
import morphhdl.paramrtl.IntExpr.ParameterRef
import morphhdl.paramrtl.ModuleItem.ContinuousAssign
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.RtlExpr.Ref
import morphhdl.paramrtl.Signedness.Unsigned
import morphhdl.paramrtl._

object ParameterizedWireFixture {
  val expected: String =
    """module ParameterizedWire #(
      |  parameter integer WIDTH = 8
      |) (
      |  input  wire [WIDTH-1:0] din,
      |  output wire [WIDTH-1:0] dout
      |);
      |
      |  assign dout = din;
      |
      |endmodule
      |""".stripMargin

  def design(reverseConstructionOrder: Boolean = false): Design = {
    val widthParameter = IntegerParameter(
      name = "WIDTH",
      default = 8,
      constraints = Vector(MinInclusive(1), MaxInclusive(Int.MaxValue))
    )
    val packed = PackedBits(ParameterRef("WIDTH"), Unsigned)
    val ports = Vector(
      Port("din", Input, packed),
      Port("dout", Output, packed)
    )

    Design(
      top = "ParameterizedWire",
      modules = Vector(
        ModuleDef(
          name = "ParameterizedWire",
          parameters = Vector(widthParameter),
          ports = if (reverseConstructionOrder) ports.reverse else ports,
          items = Vector(ContinuousAssign(Ref("dout"), Ref("din")))
        )
      )
    )
  }
}
