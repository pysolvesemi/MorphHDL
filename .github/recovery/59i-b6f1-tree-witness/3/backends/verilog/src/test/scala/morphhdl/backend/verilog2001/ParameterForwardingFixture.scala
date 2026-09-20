package morphhdl.backend.verilog2001

import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}
import morphhdl.paramrtl.IntExpr.{LocalParameterRef, Multiply, ParameterRef}
import morphhdl.paramrtl.ModuleItem.{ContinuousAssign, ModuleInstance}
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.RtlExpr.Ref
import morphhdl.paramrtl.Signedness.Unsigned
import morphhdl.paramrtl._

object ParameterForwardingFixture {
  val expected: String =
    """module ForwardingLeaf #(
      |  parameter integer WIDTH = 1
      |) (
      |  input  wire [WIDTH-1:0] din,
      |  output wire [WIDTH-1:0] dout
      |);
      |
      |  assign dout = din;
      |
      |endmodule
      |
      |module ParameterForwarding #(
      |  parameter integer DATA_WIDTH = 8,
      |  parameter integer LANES = 4
      |) (
      |  input  wire [TOTAL_WIDTH-1:0] din,
      |  output wire [TOTAL_WIDTH-1:0] dout
      |);
      |
      |  localparam integer TOTAL_WIDTH = LANES * DATA_WIDTH;
      |
      |  ForwardingLeaf #(
      |    .WIDTH(TOTAL_WIDTH)
      |  ) forwarded_inst (
      |    .din(din),
      |    .dout(dout)
      |  );
      |
      |endmodule
      |""".stripMargin

  def design(reverseConstructionOrder: Boolean = false): Design = {
    val dataWidth = IntegerParameter(
      "DATA_WIDTH",
      8,
      Vector(MinInclusive(1), MaxInclusive(1024))
    )
    val lanes = IntegerParameter(
      "LANES",
      4,
      Vector(MinInclusive(1), MaxInclusive(64))
    )
    val totalWidth = IntegerLocalParameter(
      "TOTAL_WIDTH",
      Multiply(ParameterRef("LANES"), ParameterRef("DATA_WIDTH"))
    )
    val topPacked = PackedBits(LocalParameterRef("TOTAL_WIDTH"), Unsigned)
    val leafPacked = PackedBits(ParameterRef("WIDTH"), Unsigned)
    val instance = ModuleInstance(
      name = "forwarded_inst",
      moduleName = "ForwardingLeaf",
      parameterBindings = Vector(ParameterBinding("WIDTH", LocalParameterRef("TOTAL_WIDTH"))),
      portConnections = Vector(
        PortConnection("din", Ref("din")),
        PortConnection("dout", Ref("dout"))
      )
    )
    val leaf = ModuleDef(
      name = "ForwardingLeaf",
      parameters = Vector(
        IntegerParameter("WIDTH", 1, Vector(MinInclusive(1), MaxInclusive(65536)))
      ),
      ports = Vector(
        Port("din", Input, leafPacked),
        Port("dout", Output, leafPacked)
      ),
      items = Vector(ContinuousAssign(Ref("dout"), Ref("din")))
    )
    val top = ModuleDef(
      name = "ParameterForwarding",
      parameters = ordered(Vector(dataWidth, lanes), reverseConstructionOrder),
      ports = ordered(
        Vector(Port("din", Input, topPacked), Port("dout", Output, topPacked)),
        reverseConstructionOrder
      ),
      items = Vector(
        instance.copy(
          parameterBindings = ordered(instance.parameterBindings, reverseConstructionOrder),
          portConnections = ordered(instance.portConnections, reverseConstructionOrder)
        )
      ),
      localParameters = Vector(totalWidth)
    )

    Design(
      top = top.name,
      modules = ordered(Vector(top, leaf), reverseConstructionOrder)
    )
  }

  private def ordered[A](values: Vector[A], reverse: Boolean): Vector[A] =
    if (reverse) values.reverse else values
}
