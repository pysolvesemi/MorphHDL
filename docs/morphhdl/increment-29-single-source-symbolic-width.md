# Increment 29: single-source symbolic-width bridge

Increment 29 begins the corrective single-source path. A typed public
parameter now survives the ordinary SpinalHDL width front door, so one normal
component source supplies both its concrete elaboration witness and its
parameterized Verilog. The component does not author a parallel ParamRTL
`Design` and the production emitter contains no component-name special case.

## Public component syntax

The bounded path accepts the configuration shape the project originally set
out to support:

```scala
final case class WireConfig(width: HdlInt)

final class ParameterizedWire(config: WireConfig) extends Component {
  val din = in UInt(config.width bits)
  val dout = out UInt(config.width bits)

  dout := din
}

MorphVerilog(SpinalConfig(targetDirectory = "rtl")) {
  new ParameterizedWire(
    WireConfig(HdlInt.param("WIDTH", default = 8, min = 1, max = 64))
  )
}
```

`HdlInt.bits` keeps the concrete witness needed by normal Spinal elaboration
and also carries the direct public parameter schema. The matching `UInt`
factory creates the ordinary concrete-width node and retains core-neutral
symbolic-width metadata on it. The native Verilog emitter reads that metadata
only when the Morph single-source entry point explicitly enables parameterized
generation.

A literal `HdlInt`, including the implicit `Int` used by `WireConfig(8)`, uses
the same component body but carries no symbolic tag. Ordinary `SpinalVerilog`
therefore emits its concrete `UInt` width exactly as before. Explicit
single-source Morph generation requires parameterized ports and rejects an
all-literal or mixed tagged/untagged interface instead of inventing a public
parameter.

The result is an honest single-source report. It records the top name,
published source path, validated public parameter schemas and inherited
validation-phase inventory; it does not claim to contain a ParamRTL design
that was never built.

## Bounded bridge contract

This first bridge deliberately accepts only:

- one top-level ordinary SpinalHDL `Component` with no child components;
- a direct public integer `HdlInt.param`, with a positive complete domain and
  a finite `Int`-sized concrete witness and bounds that fit the configured
  `bitVectorWidthMax`;
- top-level `UInt` input and output ports whose widths reference that direct
  parameter; and
- an ordinary direct assignment between ports carrying the same parameter.

Parameter declarations are deduplicated by schema, emitted deterministically
and retain their natural caller-provided name. The default width remains the
concrete elaboration witness. A same-named parameter with a different schema,
an invalid identifier, a nonpositive domain, an unbounded or non-`Int` domain,
a mismatched symbolic assignment, unsupported symbolic node placement or any
unsupported component structure fails closed before publication.
Diagnostics involving retained width metadata carry the `HdlInt.bits` source
location. Pure structural failures that have no symbolic tag still identify
the stable failure code and affected component or port.

This is a generic shape rule. Tests repeat the same source pattern under a
different component and parameter name so an implementation branch keyed to
`ParameterizedWire` cannot satisfy the contract.

## Native and compatibility modes

The single-source `MorphVerilog` overload enables the native symbolic-width
mode for its temporary generation only. It still runs the inherited Spinal
validation phase plan and requires the observed stable phase identifiers to
match the expected plan before publishing the result atomically.

Ordinary `SpinalVerilog` remains source- and behavior-compatible. Its
parameterized flag defaults to false, symbolic metadata does not alter normal
emission, and the example above therefore produces concrete `[7:0]` ports
with no module parameter when generated through the legacy front door. This
gives the same component a concrete mode without converting `HdlInt` back to a
Scala `Int` or maintaining a second implementation. The same is true when the
configuration is constructed directly as `WireConfig(8)`.

The compatibility `MorphProgram` overload and the ParamRTL direct emitter stay
available for the other nineteen reviewed fixtures. Increment 29 does not
pretend that they have already migrated.

## Strict Verilog-2001 output

The migrated `parameterized_wire.v` remains byte-compatible with its reviewed
golden and keeps the public inventory at exactly twenty files:

```verilog
module ParameterizedWire #(
  parameter integer WIDTH = 8
) (
  input  wire [WIDTH-1:0] din,
  output wire [WIDTH-1:0] dout
);

  assign dout = din;

endmodule
```

No `$clog2`, SystemVerilog construct, helper function, local parameter,
generated module suffix or runtime sizing hardware is introduced. The public
minimum and maximum constraints validate generation but are not emitted as an
overrideable Verilog ABI.

Normal and reverse fixture runs must retain the exact twenty-file inventory,
be byte-identical and match every committed golden. Icarus simulates widths 1,
8, 13 and 64 concurrently. Verilator parses and lints the default, minimum,
awkward and maximum configurations in IEEE 1364-2001 mode. Yosys elaborates
and synthesizes all four widths and proves exact input/output port sizes.
Independent source mutations that freeze either port back to `[7:0]` must
fail the 64-bit ABI checker. Scala 2.12.18 and 2.13.12 tests also cover legacy concrete emission,
generic renamed-component behavior, invalid domains, schema conflicts,
unsupported placements, determinism and failure atomicity.

## Deliberately deferred

This increment does not yet carry symbolic widths through `Bits`, `SInt`,
internal signals, registers, cloning, `HardType`, `Bundle`, `Vec`, `Stream`,
`Flow`, hierarchy, derived expressions, arithmetic, processes, memories or
loops. It does not lower arbitrary native Spinal graphs to ParamRTL. Those are
explicit later entries in the controlling roadmap rather than hidden claims
of this bridge.

## Recommended next increment

The first remaining unchecked roadmap item is Increment 30, symbolic data
shapes. It should propagate the retained width representation through the
ordinary core types and aggregate/payload cloning paths before broader
expressions or library components are attempted.
