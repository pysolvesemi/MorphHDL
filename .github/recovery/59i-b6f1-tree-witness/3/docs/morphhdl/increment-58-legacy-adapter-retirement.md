# Increment 58 — legacy adapter and shadow-path retirement

## Status and scope

Increment 58 depends on merged Increment 57b. It closes the production
ownership transition begun by the typed native-library increments: ordinary
parameterized application code now carries `HdlInt` / `HdlBool` into
`ElabInt` / `ElabBool`, and the retained typed native graph and the unchanged
native algorithms are the semantic authority. Compatibility surfaces remain
available where removing them would break historical clients or eliminate a
useful mutation oracle, but they are deprecated and are not alternate
production authorities.

This increment also connects the first honest producer of the versioned
MorphHDL IR introduced by Increment 54. The producer is deliberately bounded
to `SimpleWireAssignmentsV1`. Its profile and completeness facets make that
limit machine-checkable; it does not imply canonical capture of every native
construct supported by the normal single-source emitter.

The roadmap checkbox is an evidence-only transition. This document freezes
the implementation and verification contract but does not itself record a
successful closure.

## Post-retirement authority

The supported layers have distinct roles:

| Layer | Authoritative role after Increment 58 | Not an authority |
| --- | --- | --- |
| `HdlInt` / `HdlBool` and `ElabInt` / `ElabBool` | Retain public schemas, exact domains, expression/root identity and concrete elaboration witnesses until the native algorithm consumes them | A recovered Scala `Int` / `Boolean`, a rendered expression string or a coincident default |
| Native SpinalHDL graph and algorithms | Define component structure and library behavior; approved typed overloads propagate parameter facts through the same Counter, Mem, Vec, Stream, Flow, FIFO, bus and CDC implementations | A MorphHDL component clone or algorithm shadow |
| `morphhdl.ir.v1` | Supplies the validated, immutable post-parameterization/pre-emission interchange for the exact producer profile declared by a `CanonicalIrHandoff` | Raw hand-authored fixture IR, generated Verilog or legacy ParamRTL |
| Native Verilog emission | Serializes the validated native result; the existing typed single-source path remains the production emitter | A semantic database to parse, pattern-match or use to reconstruct identities |
| ParamRTL and its direct emitter | Preserve explicit historical fixtures, compatibility contracts and mutation oracles | The canonical single-source IR or a second production implementation |

Equal names, default values, bounds or target spellings never establish a
parameter or signal identity. Production decisions use exact retained objects,
typed schemas and complete domain evidence. Component names, source filenames
and emitted identifiers do not select algorithms or repairs.

## Deprecated compatibility surfaces

The following APIs remain linkable or source-compatible but are deprecated:

- `MorphProgram`, the `MorphVerilog` overloads that consume it, and
  `MorphVerilogReport`. Their concrete/symbolic dual factories remain explicit
  compatibility and mutation oracles only.
- MorphHDL frontend aliases for `Bits`, `UInt`, `SInt`, `cloneOf`, `HardType`,
  `Reg`, `Vec`, `Mem`, `Stream`, `Flow` and `StreamFifo`. Production source uses
  the ordinary `spinal.core` and `spinal.lib` factories and their typed
  overloads.
- `ParameterizedWidth.Bits` / `UInt` / `SInt` shadow factories. The native
  factories own parameterized construction; the old methods are historical
  aliases.
- `ParamRtlFrontend` and the ParamRTL-shaped single-source report view.
  ParamRTL itself and its direct Verilog-2001 emitter remain supported only for
  explicitly authored compatibility fixtures, reviewed goldens and negative
  mutations; they are compatibility-classified rather than deprecated public
  IR/emitter APIs, and are not paired with a second production component
  factory.
- public post-publication rewrite entry points. The typed single-source backend
  still invokes this bounded serializer internally to materialize already
  retained typed facts in the native Verilog publication. It is not the
  canonical-IR/pass handoff: the native emitter's exact canonical identity map
  supplies graph ownership, while emitted module names locate serialization
  spans only. Generated text and names must never establish a parameter,
  expression, component identity, or other semantic fact.

Deprecation is intentional instead of silent deletion. It preserves the
reviewed binary/source compatibility boundary while making any new dependency
on a shadow path visible at compile time and enforceable by the Increment 58
source guard.

`MorphSingleSourceVerilogReport` now exposes
`elaborationParameters: Vector[ElaborationIntegerParameter]`. These are the
native typed schemas retained from the successful single-source elaboration.
Its old ParamRTL-shaped `parameters` accessor, legacy constructor and pattern
extractor remain deprecated compatibility views. They may translate names,
defaults and inclusive bounds for an old caller; production code must consume
`elaborationParameters` or the validated canonical handoff and must not infer
identity or expressions from that lossy view.

The reviewed report compatibility boundary preserves the four-argument
constructor, `apply` / `unapply` / `copy`, accessors, `Product` behavior, JVM
linkage and the historical four-field serialized form. It does not preserve
Scala-reflection `isCaseClass` metadata or case-class macro/codec derivation;
clients relying on those metaprogramming details must migrate explicitly.

## Stable canonical-IR publication

`MorphVerilog.generateWithCanonicalIr` installs two read-only MorphHDL-owned
phase points. The graph snapshot runs after inherited width inference and input
normalization, immediately before the full unnamed-intermediate removal and
node simplification phases. This retains unnamed pass candidates and records
configured name-retention plus explicit vital/preservation intent before
inherited reachability pruning marks every output dependency as vital. The
completion phase runs immediately after
the unique inherited `PhaseCheckCrossClock`, validates the final phase plan,
and releases that immutable snapshot only after inherited checks succeed. It
remains before `PhasePropagateNames`, `PhaseAllocateNames` and `PhaseVerilog`.
The producer reads the live native graph and typed parameter evidence directly;
it does not read a generated file, inspect rendered expression text or recover
parameter meaning from a witness.

The result is a `MorphCanonicalIrReport` containing:

- the normal `MorphSingleSourceVerilogReport`;
- one `CanonicalIrHandoff` whose `Design` has schema `IrVersion.V1` and stage
  `PostParameterizationPreEmission`; and
- the observed phase-class inventory proving the temporal boundary.

`CanonicalIrHandoff` contains the validator's normalized `ValidatedDesign`,
the producer profile, and explicit completeness facets. The production profile
is `SimpleWireAssignmentsV1`; it requires complete declarations, continuous
drivers, reference occurrences, typed parameters and packed types, name
origins, and observability. Envelope construction also validates the bounded
profile shape itself, so a generally valid fixture containing hierarchy,
procedural drivers, repeated targets, compound widths or non-direct
expressions cannot self-assert production completeness. A missing facet,
profile-shape mismatch or canonical validation failure prevents publication.

`MorphVerilog.publishCanonicalIr` invokes a read-only `CanonicalIrPublisher`
with that exact handoff only after normal generation succeeds. This sequencing
prevents a consumer from observing a failed or partially published generation.
The snapshot itself still describes the earlier pre-emission graph. The
ordinary `MorphVerilog(config) { component }` entry point installs no canonical
capture and remains byte-identical.

The pass workspace consumes the profile-bearing envelope through
`CanonicalIrPassAdapter.bind(CanonicalIrHandoff)`. Binding a raw `Design`
or a bare `ValidatedDesign` remains available only as a deprecated
fixture/mutation compatibility path. Increment 58 does not execute an alias
pass, transform the handoff, write IR back to the native graph or replace the
native backend. The optional pipeline stays disabled until WA-07, after WA-04
through WA-06 have supplied the two transformations and their ordered closure.

## `SimpleWireAssignmentsV1` boundary

The bounded producer accepts one non-blackbox, hierarchy-free module with
root-scope declarations and full-object continuous assignments. It supports:

- `Bool`, `Bits`, `UInt` and `SInt` ports and internal combinational signals;
- literal packed widths or one exact direct typed integer-parameter width;
- complete positive finite parameter domains within canonical validation
  limits;
- direct declaration-to-declaration references and exact non-poison packed or
  Boolean literals; and
- explicit, reflected, generated or unnamed source/elaboration name origins,
  plus complete external-visibility, name-retention and preservation facts.

Every published parameter is an integer parameter referenced directly by at
least one packed width. Boolean or unused parameters are outside this initial
profile. Boolean, bit-vector, unsigned-integer and signed-integer packed types
must use the exact signedness/width combinations emitted for their native
counterparts. Direct-reference endpoints must have identical signedness and
value semantics, with widths equal over the complete admitted parameter
domain. Literal values must be representable by the target type at every
admitted width.

A direct typed width becomes a canonical `ParameterRef` only when one retained
root, one native `ElaborationIntegerParameter`, its complete exact domain and
identity-function evaluation all agree. The producer never parses the
expression's Verilog rendering or associates a parameter by name.

The profile fails closed for hierarchy, blackboxes, registers, memories,
analog/inout declarations, input-port drivers, nested scopes or generated
structure, partial or ordered repeated/override assignments, compound width
expressions, incompatible references or literals, operators, muxes, slices and
other non-direct RTL expressions. Stable
`MORPH-IR-PRODUCER-*` diagnostics identify the rejected boundary. A future
profile can expand this coverage only by declaring and validating the
additional completeness it actually supplies.

Canonical module, scope, parameter, declaration, driver and reference IDs are
allocated deterministically from graph order, independently of user or emitted
names. Renaming an otherwise identical simple-wire fixture may change retained
name metadata, but it does not change semantic identities, edges, packed types
or parameter domains.

## StreamFifoCC width-parametric formal closure

Merged Increment 57b established the payload-width extension to the native
`StreamFifoCC` relational proof. Increment 58 inherits and reruns that exact
proof on its final revision; it does not modify or enlarge the Increment 57b
claim. The typed leg supplies both `DEPTH: ElabInt` and `WIDTH: ElabInt` to the
ordinary native FIFO and `HardType(Bits(WIDTH bits))`; each reference leg is
independently elaborated from concrete `Int` depth and width values.

The inherited positive proof matrix is the complete 64-case Cartesian product
in each enabled Scala lane:

- payload `WIDTH` in `{1, 5, 8, 32}`;
- FIFO `DEPTH` in `{2, 4, 8, 16}`;
- both direct and buffered pop-reset topologies; and
- both deterministic asynchronous clock ratios, push 2x pop and pop 2x push.

Every candidate is specialized with both Yosys `DEPTH` and `WIDTH` parameters.
The miter ports and valid-gated payload comparison use the selected width, and
artifact/module names include it so one specialization cannot overwrite or be
mistaken for another. The negative control flips one observable payload bit at
width 5 after live CDC traffic; it must fail with an assertion counterexample
and a non-empty VCD trace. Thus the proof covers payload geometry rather than
only control behavior or the default eight-bit witness. This evidence is
finite: it qualifies only the listed widths, depths, reset topologies and clock
ratios, not every positive width or arbitrary asynchronous schedules.

## Required closure evidence

Increment 58 is complete only when the exact final revision passes:

- the legacy-production retirement guard and its isolated mutations;
- canonical-IR schema, handoff, producer, phase-order, completeness,
  determinism, rename-independence and fail-closed tests in both supported
  Scala versions;
- pass-adapter binding and WA-02/WA-03 safety/boundary checks, with optional
  pass execution still disabled;
- native source audit, layering, source/JVM/concrete compatibility and SBT/Mill
  package checks;
- inherited strict Verilog-2001 parse, lint, simulation, synthesis,
  determinism and formal-equivalence gates; and
- all 64 inherited StreamFifoCC depth/width/reset/ratio proofs plus the live
  width-five mutation counterexample.

Only after those checks pass may the controlling Increment 58 checkbox change
to `[x]`. WA-04 is the next ready pass-roadmap increment; WA-07 remains blocked
until WA-06 and merged PV-58.
