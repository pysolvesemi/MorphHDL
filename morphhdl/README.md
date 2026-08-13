# MorphHDL fork integration

This directory contains MorphHDL-specific repository metadata and tooling. The
inherited compiler and library sources remain in their existing directories so
that updates from the public upstream repository can be merged with a small,
reviewable conflict surface.

The current upstream base is recorded in `upstream-base.conf`. Every upstream
synchronization must update that file in the same pull request as the merge.

Fork-specific implementation should follow these rules:

- Keep the existing concrete generation entry points behaviorally unchanged.
- Add new behavior through explicit MorphHDL entry points or isolated modules.
- Avoid mechanical renames of inherited packages and files.
- Preserve inherited license and copyright notices.
- Never update the recorded upstream commit without running the baseline gate.

See `docs/morphhdl/upstream-sync.md` for the synchronization procedure.
The parameterized-Verilog architecture contract starts at
`docs/morphhdl/architecture/README.md`.
The bounded symbolic Scala frontend is isolated under `frontend`; it lowers to
the target-neutral `paramrtl` module and does not change inherited concrete
SpinalHDL entry points.

The supported orchestration API is under `morphhdl/src`. Its
`MorphVerilog` entry point requires an explicit `MorphProgram` containing a
re-entrant concrete Spinal witness and a re-entrant symbolic ParamRTL design.
Every reachable module instance in their binding-aware default flat interface
and hierarchy must agree.
Only the validated parameterized Verilog is published; concrete witness RTL is
temporary validation data and is not exposed through the success report.

Increment 8 closes the current integer frontend through that entry point.
`HdlInt` retains addition, subtraction, multiplication, division, modulo and
negation, while the guarded package-local lowering facade owns identity-bearing
local parameters. All four reviewed contract artifacts are now produced by
their concrete and symbolic `MorphProgram` factories; no direct ParamRTL
fixture output is substituted into the release-strength artifact.

Increment 9 adds typed public Boolean parameters, dual-valued `HdlBool`
expressions and one mandatory two-branch `GenerateIf` per module capture. Both
branches are validated, while concrete default-shape agreement follows only
the selected branch. The fifth `conditional_forwarding.v` artifact is produced
through the same public orchestration and strict Verilog-2001 gates.

Increment 10 adds mathematical integer comparisons from `HdlInt` to `HdlBool`:
`<`, `<=`, `>`, `>=`, `hdlEq` and `hdlNe`. Ordinary Scala equality remains
fail-closed. `MorphVerilog` evaluates comparison defaults with binding-aware
integer and local facts, and the sixth `comparison_routing.v` artifact proves
that downstream parameter overrides select distinct legal child hierarchies.

Increment 11 adds `HdlBool.select(whenTrue, whenFalse): HdlInt`. Validation
retains and checks both value branches, while exact default-shape agreement
evaluates the choice with the Boolean, bound integer and recomputed local facts
of each reachable module instance. The seventh `conditional_width.v` artifact
uses that expression in `ACTIVE_WIDTH` and proves both Boolean choices and
awkward branch-width overrides without regenerating RTL.

Increment 12 adds typed named Boolean child-parameter bindings. Each binding is
validated in its parent scope and remains distinct from an integer binding in
ParamRTL, while strict Verilog-2001 legalizes both through named `parameter
integer` associations. `MorphVerilog` evaluates a binding from the current
parent Boolean, integer and local facts, substitutes the resulting child
Boolean default per instance and only then compares the recursively selected
default hierarchy. The eighth `boolean_forwarding.v` artifact proves true,
false and inclusive-boundary selection through one fixed-interface child.

Increment 13 adds identity-bearing Boolean local parameters and places integer
and Boolean locals in one deterministic dependency graph. Mixed dependencies
work in both directions through comparisons and conditional integer selects;
mixed cycles and foreign or reused handles fail closed. `MorphVerilog`
recomputes the combined local context separately for every reachable instance
before selecting default hierarchy. The ninth `boolean_locals.v` artifact
proves an integer-to-Boolean-to-integer local chain forwarded into a child
generate-if while retaining a fixed eight-bit interface.

Increment 14 adds one bounded integer-selected `GenerateCase` with explicit
unique choices and a mandatory default. ParamRTL validates every branch while
`MorphVerilog` follows only the exact default-selected choice (or default) in
each reachable instance context. The tenth `case_routing.v` artifact proves
literal-choice, local-selector and unmatched-default routing through distinct
leaf schemas behind one fixed eight-bit interface.

Increment 15 adds one bounded runtime `CombinationalIf` process with a one-bit
input condition, direct input values and complete output assignments on both
paths. ParamRTL proves type and driver completeness before strict Verilog-2001
legalizes the process to a named `always @*` block with blocking assignments
and a process-owned `output reg`. The eleventh `runtime_mux.v` artifact proves
both select directions at default and awkward parameterized widths without
specializing the emitted module.

Increment 16 adds one bounded `SynchronousRegister` process with distinct
one-bit clock/reset inputs, active-high synchronous reset-to-zero, one direct
full-width data input and one sole registered output. ParamRTL proves exact
types, complete reset/data ownership and the absence of sibling process,
hierarchy or generate items before strict Verilog-2001 emits a named
`always @(posedge clk)` block with nonblocking assignments. The twelfth
`synchronous_register.v` artifact proves synchronous reset and data capture at
default eight-bit and awkward five-bit widths without specializing the emitted
module.

Increment 17 adds the parallel bounded `AsynchronousRegister` process. The
same exact clock/reset/data roles and sole-output restrictions apply, while
active-high reset assertion is asynchronous and retains priority over
positive-edge data capture. Strict Verilog-2001 emits one named
`always @(posedge clk or posedge reset)` block with nonblocking assignments.
The thirteenth `asynchronous_register.v` artifact proves immediate reset,
priority and later capture at default eight-bit and awkward five-bit widths;
Yosys mutation gates reject synchronous reset, falling-edge clock and
reset-to-ones substitutions.

Increment 18 adds `SynchronousEnabledRegister`: reset retains priority, enable
high captures and enable low intentionally holds the sole registered output.
ParamRTL proves distinct exact one-bit controls, direct full-width data, sole
ownership and no sibling structure. Strict Verilog-2001 emits one named
positive-edge process with reset, then enable, and no final assignment branch.
The fourteenth `synchronous_enabled_register.v` artifact proves reset,
capture, hold and later capture at default eight-bit and awkward five-bit
widths; synthesis mutation gates reject disabled capture, reversed priority,
active-low enable, falling-edge clocking and reset-to-ones.

Increment 19 completes the bounded reset/enable matrix with
`AsynchronousEnabledRegister`. Active-high reset asserts immediately and has
priority over active-high enabled capture; enable low intentionally holds the
sole registered output. Strict Verilog-2001 emits one named
`always @(posedge clk or posedge reset)` process with reset first, enable
second and no final assignment branch. The fifteenth
`asynchronous_enabled_register.v` artifact proves immediate reset, priority,
capture, hold and later capture at eight and five bits; synthesis mutations
reject disabled capture, reversed control priority, active-low enable,
falling-edge clocking, synchronous reset and reset-to-ones.
