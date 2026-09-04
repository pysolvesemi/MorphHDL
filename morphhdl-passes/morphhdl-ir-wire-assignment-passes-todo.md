# MorphHDL IR simple-wire assignment passes roadmap

This is the controlling checklist for exactly two optional,
behavior-preserving passes over the canonical MorphHDL-owned IR after
parameterization/capture and before Verilog-2001 emission:

1. remove eligible simple wire aliases represented by unnamed internal
   signals; and
2. remove eligible simple wire aliases represented by explicitly named
   internal signals.

No signal-renaming, formatting, generated-Verilog parsing or broader
optimization pass is authorized by this roadmap.

## Fixed architecture

```text
ordinary SpinalHDL component
        |
        v
SpinalHDL elaboration and inherited validation
        |
        v
MorphHDL external parameterization, capture and lowering
        |
        v
canonical MorphHDL-owned IR
        |
        +--> unnamed simple-wire alias elimination
        |
        +--> named simple-wire alias elimination
        |
        v
structured Verilog-2001 lowering and emission
        |
        v
final Verilog text
```

PV-58 realizes the read-only publication into this boundary for the bounded
`SimpleWireAssignmentsV1` profile. Its `CanonicalIrHandoff` carries the
validator-normalized graph, producer profile and complete facets directly from
the typed native graph. The pass and writeback arrows in the diagram remain
the roadmap target: neither pass executes in production until WA-07.

The passes must:

- reuse the canonical MorphHDL-owned IR produced after parameterization;
- operate on declaration, driver and reference identities rather than emitted
  identifiers;
- preserve symbolic parameter expressions and constraints;
- be implemented generically over canonical IR semantics and identities,
  without recognizing a component, library primitive, module/class name or
  signal name;
- run before final Verilog text emission; and
- use the existing MorphHDL Verilog backend after transformation.

The passes must not:

- emit Verilog and parse it into another IR;
- introduce a generic Verilog parser or file-to-file postprocessor;
- use regex or emitted-name patterns to identify candidates;
- reconstruct parameter intent from concrete constants;
- duplicate or fork the canonical MorphHDL semantic IR;
- special-case `StreamFifo`, `ParameterizedStreamFifo` or any other component
  or library implementation; or
- modify upstream-owned SpinalHDL source.

## Component-generic implementation rule

Every adapter, validator, pass and ordered pipeline in this roadmap must be
component-generic. Eligibility and transformation decisions may depend only on
validated canonical IR identities, kinds, scopes, drivers, references, packed
types, parameter domains, naming provenance, observability, comments and
attributes defined by this roadmap. No implementation may recognize
`StreamFifo`, `StreamFifoCC`, `ParameterizedStreamFifo`, any other component or
library class, a module/class name or component name, a source filename, or a
generated HDL identifier to select a code path. Pass implementation code must
not inspect a source filename. `SourceLocation` may be retained and
reported, but its path must not change eligibility. Renaming an otherwise
identical fixture from a library component name to an unrelated name must not
change adapter facts, diagnostics, classification or transformation.

## Bounded simple-wire alias contract

An initially eligible alias is an internal combinational signal for which the
canonical IR proves all of the following:

- exactly one full-object continuous driver exists;
- the driver is a direct reference to one existing signal or port;
- source and alias have equivalent packed width, signedness and value semantics
  over the complete declared parameter domain;
- no other continuous or procedural driver targets the alias;
- the alias is not an assignment target, bidirectional endpoint, tri-state
  control, clock, reset, memory object or hierarchy boundary;
- replacing all reads with the exact source symbol cannot create a cycle or
  cross an illegal scope;
- the alias is not externally visible and has no `keep`, `dontTouch`, probe,
  preservation, black-box, public-export or equivalent observability contract;
  and
- deleting the declaration and sole assignment cannot discard a required
  comment, attribute or source contract.

The first implementation boundary does not inline operators, literals, slices,
indexes, concatenations, casts, resizes, muxes, function calls or arbitrary
expressions. Expanding beyond direct wire-to-wire aliases requires a separate
reviewed roadmap update.

Conceptually:

```verilog
wire [WIDTH-1:0] alias;
assign alias = source;
assign sink = alias;
```

may become:

```verilog
assign sink = source;
```

Only the exact alias declaration, its sole assignment and references to that
symbol may change. No surviving signal is renamed.

## Unnamed and named classification

Classification comes from source/elaboration naming metadata retained in the
MorphHDL IR before backend Verilog identifiers are allocated.

- **Unnamed internal signal:** no explicit user/source name was assigned. A
  backend identifier such as `_zz_*` does not make it named, and matching such
  text is forbidden.
- **Named internal signal:** an explicit user/source name was assigned to the
  internal signal. Ports, module names, instance names, parameters,
  local-parameters, generate labels, memory names and public or hierarchical
  names are outside this pass.

The named pass removes an eligible named alias and its assignment. It must not
transfer the removed name to another signal or invent a replacement name.
Because this removes an internal waveform/debug point, the named pass is
separately selectable and reports every removed name and available source
location deterministically.

## Fixed non-goals

Apart from the exact alias substitution and deletion described above, the two
passes must not change:

- module, instance, port, parameter, local-parameter or generate-label names;
- any surviving internal signal name;
- parameters, expressions, constraints, widths or signedness;
- literals, operators, slices, indexes, concatenations, casts or resizes;
- clock, reset, sensitivity, scheduling or procedural statement order;
- generate structure, hierarchy, module boundaries or parameter bindings;
- memory behavior, library algorithms, register state or latency;
- declaration or module-item order except for the removed alias artifacts; or
- comments and attributes attached to surviving IR objects.

The following remain outside this roadmap:

- signal renaming or name beautification;
- formatting or pretty-printing;
- dead-code elimination beyond the exact removed alias;
- constant folding or propagation;
- algebraic or logic simplification;
- common-subexpression elimination;
- process merging, retiming, register removal or hierarchy flattening; and
- any third pass.

A signal-renaming pass may be planned later only through a separate explicit
roadmap update.

## Isolation and ownership boundary

All pass implementations, tests, fixtures, pass-specific build configuration
and evidence live under the standalone top-level `morphhdl-passes/` workspace.

The workspace must:

- use its own nested SBT and/or Mill build;
- not be added to the repository root SBT/Mill aggregate;
- consume a versioned MorphHDL-owned canonical IR API rather than Verilog text;
- treat the MorphHDL IR and backend as dependencies rather than copy them;
- not place source in a `spinal.*` package;
- not require a Git submodule;
- fail closed when symbol identity, driver ownership, type equivalence,
  name-origin or observability metadata is unavailable; and
- not modify upstream-owned source under `core/`, `lib/`, `idslplugin/`,
  `idslpayload/`, `sim/` or `tester/`.

One uniquely named MorphHDL-owned workflow may validate this workspace. The
final WA-07 increment may add only the minimum optional handoff in
MorphHDL-owned orchestration code; pass logic remains under `morphhdl-passes/`.

## Dependency and execution discipline

- Implement one `WA-*` increment at a time.
- Only the first unchecked increment whose dependencies are implemented and
  merged into `parameterized-verilog` is eligible to start.
- `PV-N` means Increment N in
  `docs/morphhdl/parameterized-verilog-todo.md`. The numbering includes the
  inserted native-memory provenance Increment 45.
- The canonical-IR adapter gate is PV-54 and the stable production-publication
  gate is PV-58; these pass-roadmap dependencies are unaffected by parallel
  work on independent earlier PV increments.
- A dependency is satisfied only when its checkbox is `[x]` on
  `parameterized-verilog`; a branch or open pull request does not satisfy it.
- A request for a `BLOCKED` increment must stop with the exact unsatisfied
  dependency. Work must not skip, partially implement or substitute another
  increment.
- Mark an increment `[x]` only in its reviewed pull request after all applicable
  gates pass, and update the next increment's `Status` in the same pull request.
- Every pass and pass combination must be deterministic and idempotent.

## Mandatory genericity, common witness and formal baseline

The following rules apply to every transforming pass and every enabled pass
combination in this roadmap:

- The pass implementation must remain generic over the canonical IR. It must
  not contain a `StreamFifo` recognizer, component-specific RTL logic,
  module-name check, library-structure check, source-position inference or
  emitted-signal-name pattern. Component-specific tests may exercise a generic
  pass but may not drive its implementation.
- Every pass must be applied to the shared parameterized StreamFifo source at
  `morphhdl-passes/examples/ParameterizedStreamFifo.scala`, retaining symbolic
  `WIDTH` and `DEPTH`. Additional small generic positive and negative fixtures
  remain mandatory so the shared witness cannot become a hidden special case.
- For each proof run, emit and retain the reference Verilog from the canonical
  design snapshot immediately before the entire passes phase, before any pass
  has executed.
- After each individual pass, and after every supported pass combination, emit
  the transformed Verilog through the same structured backend and formally
  compare it with that one common pre-pass reference Verilog. Comparing only
  with the output of the preceding pass is not sufficient.
- The formal comparison must use identical legal parameter assumptions and
  bindings on both sides. When the selected formal flow requires concrete
  parameter values, it must cover the complete admitted bounded parameter
  domain rather than only defaults or hand-picked values.
- A transforming pass cannot be checked complete without retained formal
  success evidence for the shared StreamFifo witness and a mutation test that
  demonstrates the equivalence harness fails for an intentional functional
  change.

WA-02 is a read-only adapter and produces no transformed Verilog. WA-03 must
establish the common pre-pass capture and formal-equivalence harness before
WA-04 or WA-05 can remove an alias.

## Incremental plan

- [x] **WA-01 — Isolated IR-pass workspace and boundary guard**

  **Dependencies:** none.

  **Status:** `COMPLETED`.

  Added a standalone nested SBT workspace supporting Scala 2.12.18 and 2.13.12
  without changing repository root build files. Added immutable pass
  configuration, result, diagnostic and elimination-report contracts for only
  the two authorized passes. Added a boundary guard and self-tests that permit
  `morphhdl-passes/**` and `.github/workflows/morphhdl-passes.yml`, reject root
  and upstream-owned changes, and reserve MorphHDL-owned production handoff
  paths for an eligible WA-07 branch after WA-06 and PV-58 are checked. No RTL
  transformation is implemented by WA-01.

- [x] **WA-02 — Canonical MorphHDL IR pass adapter and alias contract**

  **Dependencies:** WA-01 and PV-54 implemented and merged.

  **Status:** `COMPLETED`.

  Bound the standalone workspace to the stable canonical MorphHDL-owned IR
  after external parameterization/capture and before Verilog lowering. Added a
  read-only identity-indexed adapter exposing declarations, drivers, references,
  packed types, parameter domains, naming provenance, source locations and
  observability metadata required by the bounded alias contract. Added
  fail-closed diagnostics, cross-Scala tests, and mutation-tested guards against
  generated-Verilog parsing, emitted-name matching, Spinal implementation
  coupling and component-specific logic. Added the common parameterized
  StreamFifo witness and froze the common pre-pass formal-equivalence baseline
  for every later transforming pass and supported pass combination. No alias was
  eliminated.

- [x] **WA-03 — Alias-elimination equivalence, safety and determinism gates**

  **Dependencies:** WA-02 implemented and merged.

  **Status:** `COMPLETED`.

  Add validation shared by both passes. Prove type and parameter-domain
  equivalence, cycle freedom, legal scope replacement and preservation of every
  exclusion. Establish the common pre-pass Verilog capture and formally compare
  the output after each pass with that unchanged reference on the shared
  parameterized StreamFifo witness. Validate strict Verilog-2001 compilation,
  lint and synthesis, complete admitted parameter-domain proof where
  concretization is required, representative simulations, negative safety
  fixtures, a formal mutation test, repeated-run determinism and idempotence.

- [x] **WA-04 — Unnamed simple-wire assignment elimination pass**

  **Dependencies:** WA-03 implemented and merged.

  **Status:** `COMPLETED`.

  Eliminate only aliases classified as unnamed from retained source/elaboration
  metadata. Replace reads by exact symbol identity, remove the exact declaration
  and sole direct assignment, and leave all surviving names unchanged. Never
  recognize candidates from `_zz_*` or another emitted-name convention. Apply
  the generic pass to the shared parameterized StreamFifo and formally compare
  its post-pass Verilog with the common pre-pass reference.

- [ ] **WA-05 — Named simple-wire assignment elimination pass**

  **Dependencies:** WA-04 implemented and merged.

  **Status:** `READY`.

  Apply the same safety contract to explicitly named internal aliases with
  stricter rejection for public, hierarchical, preservation, probe, attribute,
  comment or source-contract dependencies. Do not rename or transfer the
  removed name. Report each removed name and source location deterministically.
  Apply the generic pass to the shared parameterized StreamFifo and formally
  compare its post-pass Verilog with the common pre-pass reference.

- [ ] **WA-06 — Ordered two-pass pipeline and regression closure**

  **Dependencies:** WA-05 implemented and merged.

  **Status:** `BLOCKED` by WA-05.

  Provide one optional MorphHDL-IR pipeline entrypoint that can enable either
  pass independently or run unnamed then named. Keep both disabled by default.
  Validate alias chains and fanout without parsing emitted Verilog, including
  deterministic reports, idempotent IR, byte-identical repeated emission,
  strict Verilog-2001 legality, synthesis and formal equivalence of each
  individual pass and the ordered combination against the one common pre-pass
  StreamFifo reference.

- [ ] **WA-07 — Final MorphHDL IR-stage production handoff**

  **Dependencies:** WA-06 and PV-58 implemented and merged.

  **Status:** `BLOCKED` by WA-06 and PV-58.

  Consume PV-58's validated `SimpleWireAssignmentsV1` publication and connect
  the optional pipeline to the MorphHDL single-source production path after
  parameterization/capture and before Verilog lowering. PV-58 publishes a
  read-only snapshot; it does not execute or write back either pass. Keep pass
  implementation under `morphhdl-passes/` and add only minimum MorphHDL-owned
  integration/configuration glue. Existing generation remains unchanged unless
  one or both passes are explicitly enabled. Do not add a generated-Verilog
  parser, file postprocessor, signal-renaming pass, formatting pass or broader
  optimization pass.

## Completion target

This roadmap completes at WA-07 when MorphHDL can optionally remove eligible
direct wire-to-wire aliases from its canonical post-parameterization IR, first
for unnamed internal signals and then for explicitly named internal signals,
while preserving parameterized RTL behavior and every surviving identifier.
Signal renaming remains future work.
