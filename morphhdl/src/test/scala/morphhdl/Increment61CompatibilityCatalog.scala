package morphhdl

import nativeapplication.{
  BoundedRecursivePowerFixture,
  SIntSignedVerilogBaselineFixture,
  TypedBlackBoxGenericBindingFixture
}
import spinal.core._
import spinal.core.internals.{BalancedNestedHierarchy, BalancedPublicationHardware}

import morphhdl.frontend.HdlInt

/** Representative roadmap families which must preserve exact ownership when
  * parameterized publication is split into one file per canonical component.
  */
object Increment61CompatibilityCatalog {
  final case class CompatibilityCase(
      id: String,
      generatedTop: String,
      toolTop: String,
      requiredGeneratedModules: Set[String],
      forbiddenGeneratedModules: Set[String],
      supportFile: Option[(String, String)],
      build: () => Component
  )

  private val SignedExternalStub =
    """module SIntCastHeavyExternal #(
      |  parameter integer WIDTH = 8
      |) (
      |  input  wire signed [WIDTH-1:0] din,
      |  output wire signed [WIDTH-1:0] dout
      |);
      |  assign dout = din;
      |endmodule
      |""".stripMargin

  private val TypedBlackBoxStubs =
    """module TypedExternalLeaf #(
      |  parameter LABEL = "typed",
      |  parameter integer WIDTH = 8,
      |  parameter integer DEPTH = 4,
      |  parameter integer DOUBLE_WIDTH = 16,
      |  parameter integer CONCRETE_ENABLE = 1,
      |  parameter integer ENABLED = 1
      |) (
      |  input  wire [WIDTH-1:0] din,
      |  output wire [WIDTH-1:0] dout
      |);
      |  assign dout = ENABLED ? din : ~din;
      |endmodule
      |
      |module TypedParameterOnlyExternal #(
      |  parameter integer LATENCY = 2
      |) (
      |  input  wire [7:0] din,
      |  output wire [7:0] dout
      |);
      |  assign dout = din ^ {8{LATENCY[0]}};
      |endmodule
      |""".stripMargin

  private val RecursiveToolTop =
    """module Increment61RecursiveToolTop(
      |  input  wire [7:0] x,
      |  output wire [7:0] y
      |);
      |  BoundedRecursivePower #(.N(5)) dut(.x(x), .y(y));
      |endmodule
      |""".stripMargin

  val cases: Vector[CompatibilityCase] = Vector(
    CompatibilityCase(
      id = "named-field-storage",
      generatedTop = "NamedFieldVecStorage",
      toolTop = "NamedFieldVecStorage",
      requiredGeneratedModules = Set("NamedFieldVecChild", "NamedFieldVecStorage"),
      forbiddenGeneratedModules = Set.empty,
      supportFile = None,
      build = () => new NamedFieldVecFixture.Storage(
        NamedFieldVecFixture.parameter("WIDTH", default = 5, maximum = 32),
        NamedFieldVecFixture.parameter("BLUE_WIDTH", default = 3, maximum = 32),
        NamedFieldVecFixture.parameter("COUNT", default = 3, maximum = 17)
      )
    ),
    CompatibilityCase(
      id = "stream-fifo",
      generatedTop = "NativeParameterizedStreamFifoHarness",
      toolTop = "NativeParameterizedStreamFifoHarness",
      requiredGeneratedModules = Set("StreamFifo", "NativeParameterizedStreamFifoHarness"),
      forbiddenGeneratedModules = Set.empty,
      supportFile = None,
      build = () => new NativeParameterizedStreamFifoHarness(
        HdlInt.param("DEPTH", default = 5, min = 1, max = 16)
      )
    ),
    CompatibilityCase(
      id = "stream-fifo-cc",
      generatedTop = "NativeStreamFifoCCWidthDepth",
      toolTop = "NativeStreamFifoCCWidthDepth",
      requiredGeneratedModules = Set(
        "StreamFifoCCPopToPushBufferCC",
        "StreamFifoCCPushToPopBufferCC",
        "StreamFifoCC",
        "NativeStreamFifoCCWidthDepth"
      ),
      forbiddenGeneratedModules = Set.empty,
      supportFile = None,
      build = () => new NativeStreamFifoCCWidthDepthHarness(
        HdlInt.param("WIDTH", default = 5, min = 1, max = 32),
        HdlInt.param("DEPTH", default = 8, min = 2, max = 16)
      )
    ),
    CompatibilityCase(
      id = "signed-memory-hierarchy",
      generatedTop = "SIntCastHeavyBaseline",
      toolTop = "SIntCastHeavyBaseline",
      requiredGeneratedModules = Set("SIntCastHeavyChild", "SIntCastHeavyBaseline"),
      forbiddenGeneratedModules = Set("SIntCastHeavyExternal"),
      supportFile = Some("external.v" -> SignedExternalStub),
      build = () => SIntSignedVerilogBaselineFixture.parameterized()
    ),
    CompatibilityCase(
      id = "recursive-generate",
      generatedTop = "BoundedRecursivePower",
      toolTop = "Increment61RecursiveToolTop",
      requiredGeneratedModules = Set("BoundedRecursivePower"),
      forbiddenGeneratedModules = Set.empty,
      supportFile = Some("tool-top.v" -> RecursiveToolTop),
      build = () => BoundedRecursivePowerFixture.parameterized()
    ),
    CompatibilityCase(
      id = "balanced-reduction",
      generatedTop = "BalancedPublication",
      toolTop = "BalancedPublication",
      requiredGeneratedModules = Set("BalancedPublication"),
      forbiddenGeneratedModules = Set.empty,
      supportFile = None,
      build = () => new BalancedPublicationHardware(
        HdlInt.param("WIDTH", default = 5, min = 1, max = 32),
        HdlInt.param("COUNT", default = 3, min = 1, max = 17)
      )
    ),
    CompatibilityCase(
      id = "nested-reduction-hierarchy",
      generatedTop = "BalancedNestedHierarchy",
      toolTop = "BalancedNestedHierarchy",
      requiredGeneratedModules = Set("BalancedNestedFormalChild", "BalancedNestedHierarchy"),
      forbiddenGeneratedModules = Set.empty,
      supportFile = None,
      build = () => new BalancedNestedHierarchy(
        HdlInt.param("WIDTH", default = 5, min = 1, max = 32),
        HdlInt.param("COUNT", default = 3, min = 1, max = 17),
        HdlInt.param("MODE", default = 0, min = 0, max = 2)
      )
    ),
    CompatibilityCase(
      id = "typed-blackbox",
      generatedTop = "TypedBlackBoxGenericTop",
      toolTop = "TypedBlackBoxGenericTop",
      requiredGeneratedModules = Set("TypedBlackBoxGenericTop"),
      forbiddenGeneratedModules = Set("TypedExternalLeaf", "TypedParameterOnlyExternal"),
      supportFile = Some("external.v" -> TypedBlackBoxStubs),
      build = () => TypedBlackBoxGenericBindingFixture.parameterized()
    )
  )
}
