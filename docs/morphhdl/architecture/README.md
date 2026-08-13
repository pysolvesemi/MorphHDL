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
- [v1 support matrix](../v1-support-matrix.md): the bounded feature and library
  scope required before the first parameterized-Verilog release.

Machine-readable target-profile capabilities are recorded in
`morphhdl/contracts/verilog-2001.properties`. A `structure.*` capability means
the bounded v1 target can represent it; separate `implementation.*` keys state
what is executable now. Current parameter-expression implementation status and
its separate frontend, validation and emission evidence are recorded in
`morphhdl/contracts/parameter-operators.tsv`. Increment 10 records all six
integer comparison nodes as executable. Generate-case remains deferred even
though the target profile reserves a legal mapping for it.
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
