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
