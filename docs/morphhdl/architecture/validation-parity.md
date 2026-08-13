# Validation parity

Parameterized RTL generation must preserve or strengthen the semantic coverage
of the inherited SpinalHDL checks. Selecting `MorphVerilog` must never silently
turn a check off.

## Mandatory validation legs

The completed generator has three independent, fail-closed validation legs:

1. A concrete witness runs through the inherited SpinalHDL phase plan using the
   same configuration and transformation hooks as `SpinalVerilog`.
2. The symbolic design runs through ParamRTL validation and target capability
   verification. These checks cover all legal parameter values rather than only
   the witness default.
3. The actual emitted Verilog passes strict parsing, lint, simulation and
   synthesis under default and non-default parameter values.

The concrete witness is validation data. Its generated RTL may be temporary and
is never substituted for the single public parameterized hierarchy.

## No-copy phase-plan rule

When the Morph frontend is connected to Spinal elaboration, the inherited phase
construction must be factored into one shared phase-plan factory. Both
`SpinalVerilog` and `MorphVerilog` consume that factory. Copying the current
phase list into a second backend is forbidden because it would drift during
upstream synchronization.

An inherited check is classified as one of:

- `reuse`: invoke the same target-neutral implementation;
- `adapt`: retain it and add symbolic handling;
- `equivalent`: implement the same semantic guard in ParamRTL;
- `not-applicable`: allowed only with a reviewed justification and regression.

Every unsupported symbolic case fails with a diagnostic. Default-value
specialization is not an acceptable fallback.

## Executable inventory

`morphhdl/contracts/validation-parity.tsv` records the disposition, status and
separate legacy/symbolic test evidence for each inherited check. Run its
development gate with:

```bash
sbt -batch ++2.12.18 \
  "core/Test/runMain spinal.core.internals.ValidationParityInventoryWriter --output target/morphhdl/validation-phase-ids.txt"
python3 morphhdl/scripts/check-validation-parity.py \
  morphhdl/contracts/validation-parity.tsv \
  --live-phase-ids target/morphhdl/validation-phase-ids.txt
```

The development gate permits explicit `planned` and `partial` entries so an
increment cannot conceal missing work. The final parameterized-Verilog release
uses `--release`, which rejects every entry that is not `implemented`.
It also rejects implemented entries without evidence on both validation legs.
Every invocation requires a live inventory; a hand-edited nonempty manifest is
never accepted as a substitute for the shared plan.

The inherited-check inventory is an explicit baseline snapshot. Before the first
release, the shared phase-plan factory must expose stable phase identifiers and
CI must compare that live inventory with this manifest. That prevents a newly
inherited upstream check from being omitted from both the code and the table.

Increment 7 implements that comparison. `SpinalVerilog` constructs its Verilog
pipeline through one shared factory and reports both the built-in and observed
ordered validation IDs. `MorphVerilog` rejects any removal, duplication or
reordering introduced by a phase inserter. CI exports the live list on Scala
2.12.18 and 2.13.12 and passes both files to:

```bash
python3 morphhdl/scripts/check-validation-parity.py \
  morphhdl/contracts/validation-parity.tsv \
  --live-phase-ids validation-phase-ids-2.12.txt \
  --live-phase-ids validation-phase-ids-2.13.txt
```

Increment 3 extends the partial `PhaseInferWidth` adaptation through bounded
integer arithmetic and acyclic local-parameter dependencies. It does not claim
the remaining v1 expression algebra or connection to the inherited phase plan;
those gaps remain visible as partial or planned work in the inventory.

Increment 4 advances `PhaseCheckHierarchy` to `partial`. ParamRTL now validates
module dependencies and named parameter/port bindings across the entire
symbolic design, with dedicated symbolic test evidence. The status remains
partial until the Morph frontend also executes the shared inherited hierarchy
phase on its concrete witness; the new ParamRTL checks do not replace that leg.

Increment 5 extends the partial hierarchy and width adaptations through a
lexically scoped generate index and parameterized indexed part-selects. It also
advances `PhaseCheck_noLatchNoOverride` to `partial`: ParamRTL proves that the
supported canonical generate loop partitions its packed output exactly once,
without gaps or overlap, for every legal count and width. This is deliberately
not a claim about conditional generate predicates, future processes or the
still-pending concrete-witness phase-plan leg.

Increment 6 moves the generate-for contract behind the public symbolic
frontend. The same Scala source now has a bounded concrete execution and a
single-body parameterized capture, and the reviewed `lane_array.v` is generated
from that frontend-authored loop. This does not advance any inherited phase to
implemented: `MorphVerilog` and the shared concrete-witness phase plan remain
the next required validation leg.

Increment 7 adds `MorphVerilog` and runs its concrete witness through the exact
shared `SpinalVerilog` plan, including transformation hooks, memory blackboxers
and phase inserters. ParamRTL validation and Verilog-2001 capability checking
remain independent symbolic gates, and public output is atomically written only
after both legs and default top-level name plus reachable flat-module and
recursive-hierarchy agreement succeed. This bounded agreement is not a
behavioral equivalence proof between arbitrary dual factories. `PhaseCheckIoBundle`,
`PhaseCheckHierarchy` and `PhaseContext.checkGlobalData` are now implemented in
the parity manifest. Width and driver checks remain partial until their
remaining symbolic algebras are implemented; register, loop and CDC checks
remain planned for their corresponding runtime-RTL tranches.

Increment 8 routes every current integer, local-parameter, hierarchy and
generate-for contract fixture through that public orchestration path. The
frontend now preserves the full implemented integer-expression AST and opaque
local identities; ParamRTL still proves complete legal domains, dependency
cycles, nonzero divisors, positive widths and target bounds. Strict external
tools consume only the four-file `MorphVerilog` artifact. `PhaseInferWidth`
remains partial because Boolean, comparison, conditional, `min`, `max` and
`clog2` algebra is still planned.

Increment 9 extends the symbolic hierarchy and driver legs through typed
Boolean parameters and one exact two-branch `GenerateIf`. ParamRTL validates
both branches, including modules unreachable under the default witness, and
requires unconditional-plus-true and unconditional-plus-false output coverage
to each equal one. `MorphVerilog` selects only the default branch for concrete
shape agreement after complete symbolic validation. `PhaseInferWidth` and
`PhaseCheck_noLatchNoOverride` remain partial. At Increment 9, integer
comparisons, conditional values, multiple/nested predicates and runtime
processes were still deferred, and the external release-strength artifact had
five files including `conditional_forwarding.v`.

Increment 10 extends the partial width adaptation through all six mathematical
integer comparisons. The default-shape leg now evaluates comparison conditions
using the current instance's public/local integer facts, while ParamRTL and the
target pass eagerly validate both operand subtrees. The sixth public fixture
proves that legal parameter overrides change the selected child hierarchy
without rerunning MorphHDL. `PhaseInferWidth` remains partial because
conditional integer values, `min`, `max` and `clog2` are still deferred;
multiple/nested predicates and runtime processes keep the driver guard partial.
The external release-strength artifact now contains exactly six generated
files, including `comparison_routing.v`.

Increment 11 extends the partial width adaptation through typed conditional
integer values. ParamRTL validates the Boolean condition and both integer
branches, computes exact defaults from the current instance context, and uses
the unconditional hull of both legal branch intervals without claiming guard
correlation. `MorphVerilog` carries module Boolean defaults alongside bound
public integer and recomputed local facts when checking packed widths, child
bindings and generate counts. The seventh public fixture proves wide, narrow
and awkward override widths without rerunning MorphHDL. `PhaseInferWidth`
remains partial because `min`, `max`, `clog2` and the remaining v1 runtime type
algebra are deferred; multiple/nested structural predicates and processes keep
the driver guard partial. The external release-strength artifact now contains
exactly seven generated files, including `conditional_width.v`.

Increment 12 extends the implemented symbolic hierarchy adaptation with typed
named Boolean child bindings. The binding value is validated in the parent
Boolean/public-integer/local-integer scope, including every subtree inactive
under its default. `MorphVerilog` substitutes that exact per-instance value
before recomputing child locals and selecting child generate-if hierarchy, so
two instances of one logical child may safely select opposite default shapes.
The eighth public fixture proves the true default, explicit disable,
below-limit comparison and inclusive equality boundary without regenerating
RTL. At Increment 12, the hierarchy disposition was implemented while width
and driver guards remained partial because Boolean locals, nested structural
predicates and runtime processes were deferred. That increment's external
artifact inventory contained exactly eight generated files, including
`boolean_forwarding.v`; Increment 13 adds the Boolean-local tranche below.

Increment 13 extends the partial symbolic width and hierarchy adaptation with
typed Boolean local parameters. ParamRTL orders integer and Boolean locals in
one graph, rejects same-kind and mixed cycles, and eagerly analyzes every
expression subtree. `MorphVerilog` recomputes that combined order from the
bound public context for each reachable instance before checking widths,
conditions or child bindings. The ninth fixture proves a consumed
integer-to-Boolean-to-integer chain in default, disabled, below-limit and
inclusive equality configurations. `PhaseInferWidth` remains partial because
`min`, `max`, `clog2` and remaining runtime type algebra are deferred; multiple
or nested predicates and runtime processes keep driver coverage partial. The
Increment 13's external artifact inventory contained exactly nine generated
files, including `boolean_locals.v`; Increment 14 adds the case tranche below.

Increment 14 extends hierarchy and driver adaptation through one bounded
integer-selected `GenerateCase`. ParamRTL validates every explicit choice and
the mandatory default, rejects sibling or nested conditional regions and
proves complete output coverage independently on every exclusive path.
`MorphVerilog` evaluates the selector from each exact bound and mixed-local
instance context, then compares only the matching choice or mandatory default
against the concrete witness. The tenth fixture proves direct, local-derived
and unmatched-default selections without regenerating RTL. Width inference and
driver parity remain partial because remaining runtime type algebra, nested
structure and runtime processes are deferred. A sibling `GenerateFor` remains
legal; the rejection applies to a second conditional and to nesting in any
direction. The external artifact inventory now contains exactly ten generated
files, including `case_routing.v`.

Increment 15 extends partial width and driver adaptation through one bounded
runtime `CombinationalIf`. The concrete Spinal witness still runs the inherited
width, no-latch/no-override and combinational-loop phases. ParamRTL independently
requires a one-bit input condition, exact target/value type compatibility and
the same non-empty output target set assigned exactly once with blocking intent
on both runtime paths. Mixed continuous, hierarchy, parameter-generate and
procedural ownership fails before emission. Direct input-to-output references
cannot encode feedback in this tranche; a general symbolic combinational-loop
graph remains planned. The eleventh fixture proves default and five-bit widths
with both select directions, while Yosys confirms one mux and no storage. The
external artifact inventory now contains exactly eleven generated files,
including `runtime_mux.v`.

Increment 16 adds one bounded `SynchronousRegister`. The concrete Spinal
witness still runs the inherited width, register-as-latch, cross-clock and
global-data checks. ParamRTL independently requires distinct exact unsigned
one-bit input clock/reset roles, exact full-width input/output type equivalence,
one sole process-owned output and complete active-high synchronous
reset-to-zero plus data-capture paths. Sibling processes, hierarchy, generate
and continuous assignments fail before emission. This advances
`PhaseCheck_noRegisterAsLatch` only to partial: clock enables/hold behavior,
multiple registers and general clocked statements or parameterized process
branches remain unimplemented. It advances `PhaseCheckCrossClock` only to
partial because one validated clock and the no-sibling rule make an internal
multi-domain crossing unrepresentable, but external input-domain provenance,
multiple clocks and CDC structures are not modeled or approved. The twelfth
fixture proves synchronous edge behavior and exact eight-/five-bit register
structure; the external artifact inventory now contains exactly twelve files,
including `synchronous_register.v`.

Increment 17 adds the matching bounded `AsynchronousRegister`. The concrete
Spinal witness uses an active-high `ASYNC` reset and still runs every inherited
validation phase. ParamRTL separately proves the same distinct roles, exact
control/data types, sole-output ownership and no-sibling restrictions, with
asynchronous reset assertion and priority fixed in the node semantics. This
extends the partial register and cross-clock evidence without claiming clock
enable/hold, multiple clocks, external input-domain provenance or general
clocked statements. The thirteenth fixture proves immediate reset assertion,
reset priority and later eight-/five-bit capture. Yosys also rejects
synchronous-reset, falling-edge-clock and reset-to-ones mutations, and the
external artifact inventory is exactly thirteen files including
`asynchronous_register.v`.

Increment 18 adds one bounded `SynchronousEnabledRegister`. The concrete
witness runs the inherited register and clock-domain checks with explicit
active-high enable gating. ParamRTL separately proves distinct exact one-bit
clock/reset/enable roles, full-width input/output equivalence, sole ownership,
reset priority, enabled capture and intentional disabled hold. This strengthens
the partial `PhaseCheck_noRegisterAsLatch` adaptation without claiming general
clocked statements, arbitrary hold expressions, multiple registers or
parameterized process branches. The one-clock/no-sibling rule retains the same
bounded `PhaseCheckCrossClock` evidence. The fourteenth fixture proves reset,
capture, hold and later capture at eight and five bits; Yosys rejects disabled
capture, enable-before-reset, active-low-enable, falling-edge and reset-to-ones
mutations. The
external artifact inventory is exactly fourteen files including
`synchronous_enabled_register.v`.

Increment 19 adds the parallel bounded `AsynchronousEnabledRegister`. The
concrete witness combines active-high `ASYNC` reset with explicit active-high
enable gating and still runs every inherited validation phase. ParamRTL
separately proves distinct exact one-bit clock/reset/enable roles, exact
full-width data/output equivalence, sole ownership, immediate reset priority,
enabled capture and intentional disabled hold. This completes the bounded
four-variant reset-timing/enable matrix without claiming general clocked
statements, arbitrary hold expressions, multiple registers/clocks or
parameterized process branches. The fifteenth fixture proves immediate reset,
priority, capture, hold and later capture at eight and five bits. Yosys rejects
disabled capture, enable-before-reset, active-low enable, falling-edge clock,
synchronous reset and reset-to-ones mutations. The external artifact inventory
is exactly fifteen files including `asynchronous_enabled_register.v`.
