package morphhdl.backend.verilog2001

import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}
import morphhdl.paramrtl.IntExpr.{Add, Literal, LocalParameterRef, Multiply, ParameterRef}
import morphhdl.paramrtl.ModuleItem.ContinuousAssign
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.RtlExpr.Ref
import morphhdl.paramrtl.Signedness.Unsigned
import morphhdl.paramrtl._

object DerivedWidthFixture {
  val expected: String =
    """module DerivedWidth #(
      |  parameter integer DATA_WIDTH = 8,
      |  parameter integer LANES = 4
      |) (
      |  input  wire [PADDED_WIDTH-1:0] din,
      |  output wire [PADDED_WIDTH-1:0] dout
      |);
      |
      |  localparam integer TOTAL_WIDTH = LANES * DATA_WIDTH;
      |  localparam integer PADDED_WIDTH = TOTAL_WIDTH + 3;
      |
      |  assign dout = din;
      |
      |endmodule
      |""".stripMargin

  def design(reverseConstructionOrder: Boolean = false): Design = {
    val dataWidth = IntegerParameter(
      name = "DATA_WIDTH",
      default = 8,
      constraints = Vector(MinInclusive(1), MaxInclusive(1024))
    )
    val lanes = IntegerParameter(
      name = "LANES",
      default = 4,
      constraints = Vector(MinInclusive(1), MaxInclusive(64))
    )
    val totalWidth = IntegerLocalParameter(
      name = "TOTAL_WIDTH",
      value = Multiply(ParameterRef("LANES"), ParameterRef("DATA_WIDTH"))
    )
    val paddedWidth = IntegerLocalParameter(
      name = "PADDED_WIDTH",
      value = Add(LocalParameterRef("TOTAL_WIDTH"), Literal(3))
    )
    val packed = PackedBits(LocalParameterRef("PADDED_WIDTH"), Unsigned)
    val parameters = Vector(dataWidth, lanes)
    val ports = Vector(
      Port("din", Input, packed),
      Port("dout", Output, packed)
    )
    val localParameters = Vector(totalWidth, paddedWidth)

    Design(
      top = "DerivedWidth",
      modules = Vector(
        ModuleDef(
          name = "DerivedWidth",
          parameters = if (reverseConstructionOrder) parameters.reverse else parameters,
          ports = if (reverseConstructionOrder) ports.reverse else ports,
          items = Vector(ContinuousAssign(Ref("dout"), Ref("din"))),
          localParameters = if (reverseConstructionOrder) localParameters.reverse else localParameters
        )
      )
    )
  }
}
