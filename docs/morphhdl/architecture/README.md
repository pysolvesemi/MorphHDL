# MorphHDL architecture contract

This directory defines the normative architecture for true parameterized RTL
generation. Increment 1 establishes the contract; later increments implement
it without silently weakening its rules.

The contract is split into these documents:

- [Frontend and elaboration](frontend-contract.md): public Scala types, entry
  points, dual concrete/parameterized behavior and symbolic-escape rules.
- [ParamRTL](paramrtl-contract.md): the target-neutral canonical semantic IR.
- [Strict Verilog-2001](verilog-2001-profile.md): legal output constructs,
  flattening policy and validation requirements.
- [Validation parity](validation-parity.md): preservation of inherited
  SpinalHDL checks and the no-silent-skip release gate.
- [Increment 3 parameter expressions](../increment-3-parameter-expressions.md):
  bounded integer arithmetic, local-parameter ordering and executable gates.
- [Increment 4 hierarchy](../increment-4-hierarchy.md): named instances,
  child-parameter forwarding and exact hierarchy validation.
- [Increment 5 generate-for](../increment-5-generate-for.md): scoped generate
  indices, indexed part-selects and exact symbolic lane partitioning.
- [Increment 6 symbolic frontend](../increment-6-symbolic-frontend.md):
  dual-valued `HdlInt`, scoped `GenIndex` and native Scala generate-loop
  capture.
- [Increment 7 MorphVerilog orchestration](../increment-7-morph-verilog.md):
  the public dual-leg entry point, shared inherited Verilog phase plan and
  live validation-inventory gate.
- [Increment 8 integer frontend closure](../increment-8-integer-frontend.md):
  complete current `HdlInt` arithmetic, identity-bearing local parameters and
  all four reviewed fixtures through `MorphVerilog`.
- [Increment 9 Boolean generate-if](../increment-9-boolean-generate-if.md):
  typed `HdlBool` expressions, one exact two-branch conditional region and a
  fifth reviewed fixture through `MorphVerilog`.
- [Increment 10 integer comparisons](../increment-10-integer-comparisons.md):
  six mathematical `HdlInt` comparisons, binding-aware default branch
  selection and a sixth reviewed hierarchy-switching fixture.
- [Increment 11 conditional integer values](../increment-11-conditional-integers.md):
  typed Boolean selection between two `HdlInt` expressions, conservative
  whole-domain analysis and a seventh reviewed parameterized-width fixture.
- [Increment 12 Boolean child forwarding](../increment-12-boolean-forwarding.md):
  typed named Boolean bindings, parent-context validation and instance-aware
  default substitution through an eighth reviewed hierarchy fixture.
- [Increment 13 Boolean local parameters](../increment-13-boolean-locals.md):
  identity-bearing Boolean locals, one combined cross-kind dependency graph
  and a ninth reviewed mixed-local hierarchy fixture.
- [Increment 14 generate-case](../increment-14-generate-case.md): one bounded
  integer-selected case region, mandatory default and a tenth reviewed
  hierarchy fixture.
- [Increment 15 runtime combinational mux](../increment-15-runtime-mux.md): one
  complete runtime if/else process, latch proof and an eleventh reviewed
  process fixture.
- [Increment 16 synchronous register](../increment-16-synchronous-register.md):
  one positive-edge register with active-high synchronous reset-to-zero and a
  twelfth reviewed process fixture.
- [Increment 17 asynchronous register](../increment-17-asynchronous-register.md):
  one positive-edge register with active-high asynchronous reset-to-zero and a
  thirteenth reviewed process fixture.
- [Increment 18 synchronous enabled register](../increment-18-synchronous-enabled-register.md):
  one positive-edge register with reset-priority active-high enable/capture,
  disabled hold and a fourteenth reviewed process fixture.
- [Increment 19 asynchronous enabled register](../increment-19-asynchronous-enabled-register.md):
  one positive-edge register with immediate reset-priority active-high
  asynchronous reset, enabled capture, disabled hold and a fifteenth fixture.
- [Increment 20 synchronous read-first single-port memory](../increment-20-single-port-memory.md):
  one guarded positive-edge whole-word memory with independent width/depth and
  a sixteenth reviewed fixture.
- [Increment 21 portable address width](../increment-21-portable-address-width.md):
  typed positive ceiling-log2 with a one-bit minimum and a depth-derived
  packed memory address ABI in strict Verilog-2001.
- [Increment 22 memory read enable](../increment-22-read-enable.md): one
  active-high whole-word read enable with disabled output hold, independent
  writes and retained read-first collisions.
- [v1 support matrix](../v1-support-matrix.md): the bounded feature and library
  scope required before the first parameterized-Verilog release.

Machine-readable target-profile capabilities are recorded in
`morphhdl/contracts/verilog-2001.properties`. A `structure.*` capability means
the bounded v1 target can represent it; separate `implementation.*` keys state
what is executable now. Current parameter-expression implementation status and
its separate frontend, validation and emission evidence are recorded in
`morphhdl/contracts/parameter-operators.tsv`. Increment 11 records conditional
integer selection as executable alongside the six comparison nodes from
Increment 10. Increment 12 records typed Boolean child binding support and
Increment 13 records typed Boolean local support in the target-profile
properties. Increment 14 makes the reserved bounded generate-case mapping
executable while nested structural regions remain deferred. Increment 15 makes
the first bounded runtime combinational process executable. Increments 16 and
17 make atomic synchronous- and asynchronous-reset register variants
executable. Increments 18 and 19 add bounded synchronous and asynchronous
enable/hold semantics. Increment 20 adds the first bounded parameterized
single-port memory while broader statements, multiple clocks/registers, CDC
structures and memory policies remain separate tranches. Increment 21 derives
that memory's address width from `DEPTH` through a portable typed conditional
expression without `$clog2` or an externally overrideable helper parameter.
Increment 22 adds an exact one-bit active-high read enable while keeping the
write guard independent and preserving read-first behavior.
Runnable contract examples live under `morphhdl/examples/contracts`.

## Architectural invariants

1. Existing concrete `SpinalVerilog` and `SpinalVhdl` behavior remains
   unchanged.
2. Parameterized generation is explicit and opt-in through a MorphHDL entry
   point.
3. A symbolic value is never implicitly converted to a Scala `Int` or
   `Boolean`.
4. ParamRTL is the canonical semantic IR for both current Verilog and possible
   future SystemVerilog targets.
5. There is one module definition per logical component, never one definition
   per parameter-value combination.
6. Unsupported symbolic constructs fail with a source-located diagnostic;
   they are not specialized using a default value.
7. Strict Verilog-2001 legalization is a separate, non-destructive backend
   pass.
8. Aggregate intent is retained in ParamRTL even when the Verilog ABI is flat.
9. Frontend capture state is lexical, exception-safe and isolated between
   concurrent elaboration threads.

Changing one of these invariants requires a reviewed architecture decision and
an update to the executable contract tests.
