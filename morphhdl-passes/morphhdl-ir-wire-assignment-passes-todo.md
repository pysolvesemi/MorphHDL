# MorphHDL IR simple-wire assignment passes roadmap

This file is the controlling implementation checklist for exactly two optional,
behavior-preserving passes over the canonical MorphHDL-owned IR after
parameterization/capture and before Verilog-2001 text emission:

1. eliminate eligible simple wire aliases represented by unnamed internal
   signals; and
2. eliminate eligible simple wire aliases represented by explicitly named
   internal signals.

These are narrowly bounded wire-alias elimination passes. Signal renaming,
pretty-printing and broader RTL optimization are not authorized by this
roadmap.

## Supersession

This roadmap replaces the earlier unimplemented `RP-01` through `RP-07`
generated-Verilog readability roadmap. That roadmap incorrectly proposed
parsing emitted Verilog and incorrectly substituted signal renaming and
formatting for the two requested wire-assignment passes. No `RP-*` increment was
implemented, so those identifiers are retired and replaced by the `WA-*`
increments below.

## Fixed architecture

The production flow for these passes is:

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
        +--> unnamed simple-wire assignment elimination
        |
        +--> named simple-wire assignment elimination
        |
        v
structured Verilog-2001 lowering and emission
        |
        v
final Verilog text
```

The passes must:

- reuse the canonical MorphHDL-owned IR produced after parameterization;
- operate on declaration, driver and reference identities rather than emitted
  identifiers;
- preserve symbolic parameter expressions and their declared constraints;
- execute before final Verilog text emission; and
- use the existing MorphHDL Verilog backend after transformation.

The passes must not:

- generate Verilog and parse it back into another IR;
- introduce a generic Verilog parser or file-to-file postprocessor;
- use regex or emitted-name patterns to identify candidates;
- reconstruct parameter intent from concrete constants;
- create a second semantic RTL representation that duplicates the canonical
  MorphHDL IR; or
- modify upstream-owned SpinalHDL source.

## Bounded definition of a simple wire alias

For this roadmap, an eliminable simple wire alias is initially limited to an
internal combinational signal for which all of the following are proven from
resolved MorphHDL IR metadata:

- it has exactly one full-object continuous driver;
- the driver is a direct reference to one existing signal or port;
- source and alias have equivalent packed width, signedness and value semantics
  throughout the complete declared parameter domain;
- the alias is never written by another continuous or procedural driver;
- the alias is never used as an assignment target, bidirectional endpoint,
  tri-state control, clock, reset, memory object or hierarchy boundary;
- replacing every read reference to the alias with the source reference cannot
  create a cycle or cross an illegal lexical or hierarchy scope;
- the alias is not externally visible and has no `keep`, `dontTouch`, probing,
  preservation, black-box, public-export or equivalent observability contract;
  and
- deleting its declaration and sole assignment cannot discard a required
  comment, attribute or source contract.

The first implementation boundary does not inline operators, literals, slices,
indexes, concatenations, casts, resizes, muxes, function calls or arbitrary
expressions. Expanding beyond direct wire-to-wire aliases requires a separate
reviewed roadmap update.

A valid transformation is therefore conceptually:

```verilog
wire [WIDTH-1:0] alias;
assign alias = source;
assign sink = alias;
```

into:

```verilog
assign sink = source;
```

The declaration and sole assignment for `alias` are removed, and every read of
that exact IR symbol is redirected to the exact source symbol. No remaining
signal is renamed.

## Unnamed and named classification

Classification must come from source/elaboration naming metadata retained in
the MorphHDL IR before backend-generated Verilog identifiers are allocated.

- **Unnamed internal signal:** no explicit user/source name was assigned to the
  signal. A backend-generated identifier such as `_zz_*` does not make the
  signal named, and matching such text is forbidden.
- **Named internal signal:** an explicit user/source name was assigned to the
  internal signal. Ports, module names, instance names, parameters,
  local-parameters, generate labels, memory names and public or hierarchical
  names are outside this pass.

The named-signal pass removes an eligible named alias and its assignment. It
must not transfer that name to the source or sink, rename another signal, or
invent a replacement name. Because removal changes internal waveform/debug
observability, the named pass remains separately selectable and must emit a
deterministic report containing each removed name and available source
location.

## Fixed scope and non-goals

Both passes must preserve RTL behavior and all parameterized behavior. Apart
from deleting a proven alias declaration and its sole assignment, and replacing
references to that exact alias, they must not change:

- module, instance, port, parameter, local-parameter or generate-label names;
- any surviving internal signal name;
- parameters, parameter expressions, constraints, widths or signedness;
- literals, operators, slices, indexes, concatenations, casts or resizes;
- clock, reset, sensitivity, assignment scheduling or procedural statement
  order;
- generate structure, hierarchy, module boundaries or parameter bindings;
- memory behavior, library algorithms, register state or latency;
- declaration or module-item order except for deletion of the proven alias
  declaration and assignment; or
- comments and attributes attached to surviving IR objects.

The following work is explicitly outside this roadmap:

- signal renaming or name beautification;
- formatting or pretty-printing;
- dead-code elimination beyond the exact removed alias;
- constant folding, propagation, algebraic simplification or logic reduction;
- common-subexpression elimination;
- process merging, retiming, register removal or hierarchy flattening; and
- any third pass.

A future signal-renaming pass may be planned later only through a separate,
explicitly reviewed roadmap update.

## Isolation and ownership boundary

All pass implementations, tests, fixtures, pass-specific build configuration
and generated evidence must live under the standalone top-level
`morphhdl-passes/` workspace.

The workspace must:

- use its own nested SBT and/or Mill build;
- not be added to the repository root SBT/Mill aggregate;
- consume a versioned, MorphHDL-owned canonical IR API rather than generated
  Verilog text;
- treat the MorphHDL IR and backend as dependencies, not copy or fork their
  models;
- not place source in a `spinal.*` package;
- not require a Git submodule;
- fail closed when exact symbol identity, driver ownership, type equivalence,
  name origin or observability metadata is unavailable; and
- not modify upstream-owned source under `core/`, `lib/`, `idslplugin`,
  `idslpayload`, `sim` or `tester`.

A uniquely named MorphHDL-owned workflow may be added solely for this standalone
workspace. No existing upstream workflow may be modified. The final integration
increment may add only the minimum optional handoff in MorphHDL-owned
orchestration/build code; pass logic remains owned by `morphhdl-passes/`.

## Dependency and execution discipline

- Only one wire-assignment increment is implemented at a time.
- Only the first unchecked increment whose dependencies are all implemented and
  merged into `parameterized-verilog` is eligible to start.
- A dependency on `PV-N` refers to Increment N in
  `docs/morphhdl/parameterized-verilog-todo.md` on the target branch.
- A dependency is satisfied only when its checkbox is `[x]` on
  `parameterized-verilog`; an implementation branch or open pull request does
  not satisfy it.
- If a requested increment is marked `BLOCKED`, implementation must stop with
  the exact unsatisfied dependencies. Work must not skip, partially implement
  or substitute another increment.
- An increment checkbox changes to `[x]` only in that increment's reviewed pull
  request after all required gates pass. The next increment's `Status` line is
  updated in the same pull request.
- Every pass and pass combination must be deterministic and idempotent:
  repeated execution with the same IR and configuration produces byte-identical
  emitted Verilog, and a second pass execution makes no further IR change.

## Incremental plan

- [ ] **WA-01 — Isolated IR-pass workspace and boundary guard**

  **Dependencies:** none.

  **Status:** `READY` after this roadmap pull request is merged.

  Create the standalone `morphhdl-passes/` build and test workspace without
  changing repository root build files or upstream-owned SpinalHDL source. Add
  a boundary guard that rejects pass-increment changes outside
  `morphhdl-passes/`, except for this roadmap, one uniquely named pass workflow,
  and the explicitly permitted final MorphHDL-owned integration handoff in
  WA-07. Establish shared pass result, diagnostic, configuration and
  elimination-report contracts. Do not implement an RTL transformation yet.

- [ ] **WA-02 — Canonical MorphHDL IR pass adapter and alias contract**

  **Dependencies:** WA-01 and PV-46 implemented and merged.

  **Status:** `BLOCKED` by WA-01 and PV-46.

  Bind the standalone pass workspace to the stable canonical MorphHDL-owned IR
  after external parameterization/capture and before Verilog lowering. Expose
  resolved declaration, driver, reference, packed-type, parameter-domain,
  name-origin, source-location and observability metadata needed to recognize
  the bounded simple-wire alias contract. Add a hard guard proving the adapter
  does not consume, parse or pattern-match generated Verilog text. Do not
  eliminate any alias yet.

- [ ] **WA-03 — Alias-elimination equivalence, safety and determinism gates**

  **Dependencies:** WA-02 implemented and merged.

  **Status:** `BLOCKED` by WA-02.

  Add the validation gates shared by both passes. Verify that an accepted
  transformation changes only the exact alias declaration, its sole assignment
  and references to that symbol. Prove type and parameter-domain equivalence,
  cycle freedom, legal scope replacement and preservation of every excluded
  contract. Validate strict Verilog-2001 compilation, lint and synthesis, plus
  representative behavioral simulations for concrete defaults and parameter
  overrides. Add negative fixtures for multiple drivers, type changes,
  attributes, comments, hierarchy references, clocks, resets, memories,
  bidirectional signals and ambiguous naming metadata. Add repeated-run
  determinism and idempotence tests.

- [ ] **WA-04 — Unnamed simple-wire assignment elimination pass**

  **Dependencies:** WA-03 implemented and merged.

  **Status:** `BLOCKED` by WA-03.

  Implement the first requested pass. Eliminate only aliases classified as
  unnamed from retained source/elaboration metadata. Replace reads through
  resolved symbol identity, delete the exact alias declaration and sole direct
  assignment, and leave every surviving identifier unchanged. Do not recognize
  unnamed candidates from `_zz_*` or any other emitted-name convention. Close
  the increment only after WA-03 proves accepted cases behavior-preserving and
  rejected cases unchanged with explicit diagnostics.

- [ ] **WA-05 — Named simple-wire assignment elimination pass**

  **Dependencies:** WA-04 implemented and merged.

  **Status:** `BLOCKED` by WA-04.

  Implement the second requested pass as a separately selectable transformation
  over explicitly named internal aliases. Apply the same safety contract as
  WA-04, with stricter rejection for any public, hierarchical, preservation,
  probe, attribute, comment or source-contract dependency. Removing an eligible
  alias must not rename or transfer its name to another signal. Emit a
  deterministic report of every removed name and available source location, and
  retain all ineligible named signals unchanged.

- [ ] **WA-06 — Ordered two-pass pipeline and regression closure**

  **Dependencies:** WA-05 implemented and merged.

  **Status:** `BLOCKED` by WA-05.

  Provide one optional MorphHDL-IR pipeline entrypoint inside
  `morphhdl-passes/` that can enable either pass independently or run the
  unnamed pass followed by the named pass. Keep both transformations disabled
  by default. Validate individual and combined operation over representative
  single-source parameterized designs, including alias chains and fanout,
  without parsing emitted Verilog. Prove deterministic elimination reports,
  idempotent IR results, byte-identical repeated Verilog emission, strict
  Verilog-2001 legality, synthesis acceptance and behavioral equivalence.

- [ ] **WA-07 — Final MorphHDL IR-stage production handoff**

  **Dependencies:** WA-06 and PV-48 implemented and merged.

  **Status:** `BLOCKED` by WA-06 and PV-48.

  Connect the completed optional pipeline to the final MorphHDL single-source
  production path after parameterization/capture and before structured
  Verilog-2001 lowering. Keep pass implementation under `morphhdl-passes/` and
  add only the minimum MorphHDL-owned integration/configuration glue. Existing
  generation remains unchanged unless one or both passes are explicitly
  enabled. Validate the final parameterized-Verilog path for individual and
  combined pass operation. Do not add a generated-Verilog parser,
  file-to-file postprocessor, signal-renaming pass, formatting pass or broader
  optimization pass.

## Completion target

This roadmap is complete at WA-07. Completion means MorphHDL can optionally
remove eligible direct wire-to-wire aliases from its canonical post-
parameterization IR, first for unnamed internal signals and then for explicitly
named internal signals, while preserving parameterized RTL behavior and every
surviving identifier. Signal renaming remains future work and is not part of
this roadmap.
