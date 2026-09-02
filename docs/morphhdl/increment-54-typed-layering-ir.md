# Increment 54 — typed elaboration layering and canonical IR cleanup

## Status

This document records the Increment 54 architecture, ownership rules and merge
gates. Roadmap closure is a separate step after the complete dual-build
validation matrix and reviewed source scope have passed.

Increment 54 is a consolidation increment. It does not add another way to
recover parameter intent from an ordinary Scala `Int`, a concrete witness, a
source position or generated Verilog. The explicit `ElabInt` / `ElabBool` path
established by Increments 53d–53g remains authoritative.

## Package and build ownership

The supported dependency direction is from neutral models and native metadata
toward capture, frontend compatibility and final orchestration:

| Layer | Increment 54 ownership | Forbidden dependency or behavior |
| --- | --- | --- |
| `core` / `spinal.core` | Neutral `ElabInt` and `ElabBool` expressions, exact bounded domains, formal-root identity and the reviewed native adapters such as `ParameterizedWidth`, `ParameterizedMemory` and `ParameterizedVec` | Depending on the MorphHDL frontend, compiler plugin or target backend; reconstructing typed intent after a concrete native API has consumed it |
| `morphruntime` / `spinal.core` | Typed control support, finite-domain checks, formal bindings, structural metadata and `ParameterizedProcess` capture over the native graph | Owning a second expression algebra, loading compiler-plugin classes or depending on frontend syntax types |
| `morphplugin` / `morphhdl.compiler` | MorphHDL typed-control lowering, explicit `HdlInt` / `HdlBool` compatibility lowering and Morph-specific symbolic-equality safety | A compile or runtime dependency on `morphruntime`, `core` or `frontend`; component-, filename-, witness- or emitted-name recognition |
| `idslplugin` | Generic SpinalHDL compiler-plugin transformations | References to MorphHDL packages, frontend symbolic types or Morph-specific diagnostics |
| `frontend` | Public compatibility syntax and translation into the neutral typed carriers and runtime capture APIs | Owning low-level process capture, native metadata registries or target rendering as semantic authority |
| `morphir` / `morphhdl.ir.v1` | Standalone, immutable and publishable canonical IR v1 schema/API, deterministic normalization, parameter-domain and guarded exact-expression validation, and bounded diagnostic reporting | Depending on SpinalHDL internals, frontend classes, a Verilog parser, an optional pass implementation or a current pipeline producer |
| Current MorphHDL orchestration and backends | Continue to consume native-graph metadata or legacy ParamRTL; Increment 54 does not add a canonical-v1 producer, translator, consumer or target-lowering handoff | Claiming that the current production pipeline publishes canonical-v1 `Design` values, or manufacturing future canonical facts from generated Verilog |

The SBT and Mill graphs must encode the same boundary. In particular,
`morphplugin` is a compiler-only module with no `morphruntime` project edge,
`morphruntime` is independently publishable where a published consumer needs
it, and `morphir` is a separately publishable module rather than an internal
class path of the orchestration artifact.

### Compiler-plugin separation

The MorphHDL plugin owns all Morph-specific typed-tree behavior:

1. explicit `ElabInt` / `ElabBool` control lowering;
2. the existing explicit `HdlInt` / `HdlBool` natural-control compatibility
   path; and
3. rejection of reverse Scala equality whose right operand has a statically
   proven MorphHDL symbolic frontend type.

The equality check prevents ordinary receiver-side `==`, `!=`, `equals`, `eq`
or `ne` dispatch from silently discarding parameter meaning. It checks typed
symbols by fully qualified name and does not load the frontend classes. Moving
this rule out of `idslplugin` keeps the native plugin Morph-free without
weakening the diagnostic or changing supported symbolic equality.

## Distinct expression layers

`spinal.core.ElabInt` and `spinal.core.ElabBool` are the low-level carriers.
Their retained expression record, concrete default, finite bounds, exact
admitted domain, formal roots, projection identity and source location travel
together. Exact domain/root metadata is semantic authority during elaboration;
the compatibility `verilog` spelling is target text only and must never be
used as identity or parsed to reconstruct the expression.

The following rules remain part of the carrier contract:

- ordinary `Int` and `Boolean` overloads continue through the original native
  algorithms;
- an `ElabInt` literal delegates to the corresponding concrete overload;
- there is no implicit conversion from a typed carrier to a concrete value;
- equal concrete defaults, bounds or rendered strings do not prove equal
  parameter roots;
- arithmetic and predicates fail closed when exact-domain or root evidence is
  insufficient; and
- the approved native adapters propagate typed metadata mechanically without
  replacing the native Stream, FIFO, Counter, memory or Vec algorithms.

Frontend compatibility expressions may be translated into these carriers, but
the frontend cannot supply a competing low-level authority.

Three similarly named expression algebras have different ownership and must
not be treated as interchangeable:

| Model | Role |
| --- | --- |
| `spinal.core.ElabInt` / `spinal.core.ElabBool` | Live typed elaboration carriers and the authority used by reviewed native adapters. They retain the concrete witness, exact finite domain, root/projection identity and source evidence needed while building the native graph. |
| `morphhdl.paramrtl.IntExpr` / `morphhdl.paramrtl.BoolExpr` | The legacy ParamRTL model for explicitly authored compatibility designs and its existing direct emitter. Increment 54 neither replaces it nor relabels it as canonical IR v1. |
| `morphhdl.ir.v1.IntExpr` / `morphhdl.ir.v1.BoolExpr` | Immutable nodes in the new versioned handoff schema. They describe what a future producer may publish; they are not elaboration carriers, a ParamRTL alias or evidence that such a producer is connected. |

Increment 54 adds no production conversion into the v1 model. Defining that
producer and finalizing the typed production handoff remain later roadmap work.

## Native tag authority and retired weak sidecars

Increment 54 retires the weak identity sidecars that previously duplicated
information now retained on the native graph:

- `ExternalParameterizedHardTypeShape`, `ExternalHardTypeIdentityRef` and
  `ExternalParameterizedHardTypeRegistry`; and
- `ExternalMemoryIdentityRef` and
  `ExternalParameterizedMemoryRegistry`.

`ParameterizedWidth.HardType` is the sole HardType geometry authority. It
evaluates one stable template and supplies the ordinary native `HardType`
generator. Each native invocation preserves both concrete and symbolic leaf
geometry through `cloneOf` / `copyShape`; frontend `HardType`, `Stream` and
`Flow` entry points delegate to that native path. No weak identity map, payload
reattachment or second generator evaluation is permitted.

`ParameterizedMemory` is the sole memory metadata authority:

- a public symbolic-depth `Mem` attaches one `ParameterizedMemoryTag`;
- an ordinary native `Int`-depth memory with symbolic element leaves is
  discovered from its already-instantiated `wordTypeLeaves`;
- discovery never evaluates the source `HardType` again;
- discovered leaf expressions are checked for complete exact domains and
  consistency with the native flattened widths before becoming authoritative;
- the discovered metadata records the literal native depth and symbolic
  aggregate element width; and
- discovery is idempotent, so repeated consumers observe the same tag rather
  than accumulating sidecar entries.

Backends consume `ParameterizedMemory` discovery, metadata and parameter
queries. `ParameterizedMemoryDepthOverrideTag` remains for the reviewed native
library depth overlays; its presence does not reintroduce a general external
registry.

## Runtime capture boundary

`ParameterizedProcess` belongs to `morphruntime`, alongside
`ParameterizedStructure`, `ElabControl`, `ElabFiniteRange` and formal capture.
Its package name and callable surface remain compatible, but its physical
location reflects its ownership: it records typed procedural and structural
evidence over native statements and cannot depend on frontend syntax.

The capture layer preserves exact formal roots, branch domains, source
locations and existing diagnostic codes for its current native-graph
consumers. A future canonical producer must reject unsupported or ambiguous
constructs rather than publish partial facts, but Increment 54 does not add
that producer. Moving the class does not authorize a duplicate frontend copy
or a shadow registry.

## Canonical IR v1 schema and API

The standalone `morphhdl.ir.v1` package defines the stable Morph-owned schema
and validation API intended for a post-parameterization, pre-emission graph.
Its schema marker is `IrVersion.V1`, and the only accepted stage marker is
`IrStage.PostParameterizationPreEmission`. These markers define the meaning of
a future published `Design`; they do not claim that the current orchestrator
already produces one.

The v1 contract includes:

- seven normalized, design-wide unique identity kinds that do not depend on
  emitted HDL names: `ModuleId`, `ScopeId`, `SymbolId`, `DriverId`,
  `ReferenceId`, `ParameterId` and `GenerateIndexId`. A distinct `ReferenceId`
  identifies every exact `RtlExpr.Ref` occurrence;
- complete bounded integer and Boolean parameter domains plus structured
  `SourceLocation(path, line, column)` values;
- target-neutral integer and Boolean parameter expressions;
- one rooted lexical scope tree per module, with resolved parents, cycle
  rejection and generate-index ownership;
- declarations with kind, packed type, `Signedness`, explicit
  `PackedValueSemantics`, name origin, optional structured source location,
  observability, attributes and comments;
- drivers with exact owner and target identities, driver kind, full/partial
  coverage, structured RTL expressions, exact symbol targets and occurrence-
  level `ReferenceId`, owner and optional source metadata, plus attributes and
  comments; and
- deterministic normalization that orders the graph without repairing,
  deduplicating or silently dropping invalid metadata.

`PackedValueSemantics` records whether packed bits represent a bit vector,
unsigned integer, signed integer or Boolean independently from the separate
target-neutral `Signedness` field. Validation enforces the legal combinations
and requires Boolean value semantics to have width one.

For each module, exactly one `ScopeKind.Module` scope is the parentless root.
Every non-module scope has a resolved parent, and parent cycles are rejected.
A driver's declaration target must be declared in the driver owner scope or
one of its lexical ancestors. Every `RtlExpr.Ref` target must likewise be
visible from the reference owner, and the reference owner must match its
driver owner. A generate index is owned by a `ScopeKind.Generate` scope and is
visible only in that scope and its descendants, including when its
`GenerateIndexId` appears in an `IntExpr` used by a declaration or RTL
operation.

Integer-expression semantics are evaluated over correlated exact assignments.
Each referenced integer parameter, Boolean parameter or generate index receives
one admitted value per assignment, and every repeated reference to the same ID
observes that same value. `IntExpr.Select` evaluates its condition first and
then only the selected branch for that assignment. Consequently, an invalid
operation in an unreachable branch does not cause a false diagnostic, while
the same operation is rejected whenever an admitted assignment can reach it.
Driver attributes and comments receive the same structured validation and
deterministic metadata ordering as declaration attributes and comments.

The stage marker has a precise temporal meaning: parameter intent and native
capture are complete, while target emission has not started. Any later
producer must satisfy that meaning and cannot claim the stage for a graph
reconstructed from emitted text. A later consumer cannot consult generated
Verilog to fill an unknown identity, type, driver, name origin, source location
or observability field.

### Bounded validation

Canonical IR is trusted only after validation. The v1 validator currently has
two fixed public bounds and one caller-selected diagnostic-output bound:

- `MaximumParameterDomainSize` (65,536) limits the admitted values of each
  integer parameter;
- `MaximumExactEvaluationCases` (65,536) limits the Cartesian product of
  admitted integer-parameter, Boolean-parameter and generate-index assignments
  for each independently validated integer-expression root; and
- the positive `maxErrors` argument limits diagnostic output and defaults to
  `DefaultMaximumDiagnostics` (256).

If an expression requires more than 65,536 exact assignments, validation fails
closed with `IrDiagnosticCode.ExactEvaluationLimitReached`
(`MORPH-IR-V1-EXACT-EVALUATION-LIMIT-REACHED`); it does not substitute an
uncorrelated interval approximation. When more failures are encountered than
`maxErrors` permits, the last retained entry is the stable
`MORPH-IR-V1-DIAGNOSTIC-LIMIT-REACHED` marker. An oversized domain is rejected,
and diagnostic truncation never licenses repair or partial publication.

The public entry point is
`CanonicalIrValidator.validate(design, maxErrors = DefaultMaximumDiagnostics)`.
It returns either an `IrDiagnosticSet` containing stable `IrDiagnosticCode`
values and structured paths/locations, or a normalized `ValidatedDesign`.

`MaximumExactEvaluationCases` is a per-expression Cartesian-assignment cap. It
is not a global validation-work budget and does not limit the number of graph
nodes, collections or expression roots. The v1 API also does not impose an
expression-node-count limit or a traversal-depth budget. This document
therefore makes no broader claim that total graph or recursive validation work
is bounded.

At minimum, validation must reject:

- a schema version or stage other than the supported v1 handoff;
- a missing, malformed or design-wide duplicate `ModuleId`, `ScopeId`,
  `SymbolId`, `DriverId`, `ReferenceId`, `ParameterId` or `GenerateIndexId`, or
  an unresolved identity use;
- a missing or multiply defined module-scope root, a missing or unresolved
  non-module parent, a scope-parent cycle, cross-module ownership or a missing
  top module;
- incomplete, duplicated or inconsistent parameter domains, including a
  default outside the admitted domain;
- expressions that reference unknown parameters, generate indices or
  declarations, or a declaration/reference/generate-index use that violates
  lexical visibility;
- invalid source coordinates, packed widths or literal widths;
- a declaration kind without its required packed type;
- unknown name provenance, incomplete observability or unknown driver
  coverage; and
- an integer domain with more than 65,536 admitted values or an integer
  expression whose exact Cartesian evaluation requires more than 65,536
  assignments.

Failures encountered beyond the configured diagnostic-output limit are
represented by the stable truncation marker described above.

`CanonicalIrValidator` normalizes only after successful validation. Directly
calling the ordering helper cannot make an invalid graph trusted or canonical.

## Optional-pass boundary

Increment 54 publishes the dependency-neutral schema and API that an optional
pass adapter can later consume. It does not implement a canonical producer,
the pass adapter or any pipeline connection. Binding the standalone
`morphhdl-passes` workspace to this API and defining its bounded alias contract
remain **WA-02**; finalizing the stable typed production handoff remains
**Increment 58**.

Until WA-02 is implemented, the main pipeline must not depend on an optional
pass artifact, and no wire-assignment optimization is implied by this
increment. WA-02 may bind and exercise the v1 contract without claiming the
final production handoff. When a production adapter is eventually connected,
it must consume a validated `PostParameterizationPreEmission` graph directly.
It may not parse generated Verilog, infer declaration identity from names or
introduce a duplicate pass IR.

## Compatibility boundaries

This consolidation preserves the following behavior:

- existing concrete APIs and native algorithms remain authoritative;
- explicit `HdlInt` / `HdlBool` compatibility entry points remain available;
- the process-capture move preserves its `spinal.core` source-level and JVM
  identity;
- exact parameter-root, projection and branch-domain identities are retained;
- established diagnostic codes, exact-domain limits and source locations are
  retained rather than replaced by new exception-only behavior;
- parameter-free designs retain their native elaboration and deterministic
  Verilog-2001 behavior; and
- the Increment 53g production-retirement rules continue to reject native-Int
  reconstruction, witness inference and emitted-name recognition.

This increment does not perform the Increment 55 public compatibility audit,
the Increment 56 native-looking surface migration, the Increment 57 library
migration or the Increment 58 legacy-adapter retirement. It also does not
implement deferred symbolic Vec operations or change an unrelated native
algorithm.

## Exact source-scope sealing

`morphhdl/contracts/increment-54-source-scope.txt` is the sorted, unique
inventory of every added, modified, renamed or deleted implementation path in
this increment. The canonical source job compares it byte-for-byte with the
branch diff against `parameterized-verilog` and rejects any unreviewed path.

The inventory excludes only
`docs/morphhdl/parameterized-verilog-todo.md`. Its exact Increment 54 checkbox
line is validated separately, so the final checkbox-only closure commit does
not change the reviewed implementation scope. On later
`parameterized-verilog` pushes, the source job continues to validate the
sealed inventory format and all negative contracts without reconstructing the
already-merged pull-request base.

## Acceptance gates

The architecture is mergeable only when all of the following gates pass. This
section defines required evidence; it does not record a result.

| Gate | Required evidence |
| --- | --- |
| Source ownership | Exactly one low-level carrier/capture authority; `ParameterizedProcess` only in `morphruntime`; retired HardType and memory sidecar symbols absent from production; no new frontend-to-core inversion |
| Compiler isolation | `morphplugin` compiles and packages without `morphruntime`; reverse symbolic equality retains its stable diagnostic code; `idslplugin` production sources and bytecode contain no MorphHDL package or symbolic-type references |
| Native metadata | HardType cloning retains exact symbolic leaf geometry without a weak map or generator re-evaluation; memory discovery is exact-domain checked, native-width checked and idempotent; existing symbolic-depth memory tags still work |
| Canonical API | `morphir` compiles as a standalone publishable artifact; all seven design-wide identity kinds, rooted lexical scopes and visibility, `ReferenceId` occurrence identity, `PackedValueSemantics`, Driver attributes/comments, optional structured source metadata, deterministic normalization, guarded correlated exact evaluation, the 65,536-entry parameter-domain and 65,536-case exact-evaluation limits, and bounded diagnostic output are covered without a SpinalHDL or frontend dependency |
| Pre-emission boundary | The schema exposes only `PostParameterizationPreEmission`; guards prove `morphir` does not read, parse or pattern-match generated Verilog, while explicitly making no Increment 54 producer or pipeline-integration claim |
| Build parity | SBT and Mill encode the same dependency and publication graph and compile the affected modules under Scala 2.12.18 and 2.13.12 |
| Typed regressions | Typed carrier, exact-domain, control, process/structure, HardType, memory, Vec, Counter, Stream/Flow, FIFO and negative ownership suites remain green |
| Output regressions | Required Verilog-2001, determinism, lint, synthesis, simulation, formal and mutation-controlled equivalence checks remain green |
| Retirement | The Increment 53g production-retirement guard and cumulative approved-native-source overlay remain green; no deleted reconstruction path or obsolete sidecar is restored |
| Closure | The reviewed source inventory is sealed and every preceding result is successful; a skipped or merely planned gate cannot close the increment |

Only after these gates and the exact source review succeed may the roadmap
checkbox be changed in a separate closure step.
