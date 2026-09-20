# Increment 60b — Typed declaration and expression signedness authority

## Scope and publication boundary

This increment implements the child roadmap's equivalently exact emitter
analysis option. It does not extend the existing bounded simple-wire canonical
IR producer to unsupported designs. No emitter calls this analysis by default;
no Verilog or VHDL publication rule changes. The next increment is 60c, not cast
removal. The prerequisite is merged 60a commit
`7087302067fc3b7ffdf4ead2d2b39c722196828c` (PR #152).

`SignednessFacts` is target-neutral. `MorphHdlSignednessAnalysis` binds those
facts to exact native objects and provides an opt-in read-only observer just
before `PhaseVerilog`, after inherited validation. The observer sees final
native declaration and expression identities, not a parsed copy of their RTL.

## Facts are descriptions, evidence owns a use

A Fact records the intended graph kind, conservatively transferred value kind,
concrete native width, logical width expression, transfer rule, operand IDs and
remaining target/context obligations. Its five kinds are signed scalar,
unsigned scalar, unsigned aggregate, Boolean and unknown. A fact's signed value
does not claim that the current unsigned-declaration printer emits a signed
subexpression. It does not authorize removing any cast.

A Snapshot owns private identity maps. IDs are deterministic traversal ordinals,
not names, source locations, object hashes or equal concrete values. Evidence
has a private constructor and no copy method. Declaration, expression temporary,
aggregate, memory element and expression uses are distinct. A cast-operand use
also binds the exact parent expression and operand slot. Consumers must validate
the subject, session and use role before inspecting the returned fact. Copying
an inspectable Fact cannot manufacture Evidence.

Validation checks all retained dependency shapes against the live graph,
including exact operand, type, width-source and ownership identities. A changed
operand or width invalidates old evidence even if it has the same witness.
References are terminals: analysis does not chase drivers, infer their declared
type from assignments, or mistake register feedback for expression recursion.
Actual non-reference expression cycles fail with a stable diagnostic.

## Typed widths and aggregates

Logical widths retain exact `ElaborationIntegerExpression` objects from the
existing typed registries. Session-local Retained keys are resolved only through
a validated subject use. Independent same-named roots remain independent. Width
sums, products, min/max and differences are structured target-neutral values;
no printed width expression is parsed. A flattened typed Vec keeps logical
`depth * sum(element leaf widths)` rather than its native carrier capacity.

An independently declared scalar SInt owns signed intent. A flattened Vec or
Bundle is unsigned transport, even with one signed field. Exact Mem element
classification inspects the already-retained `wordTypeLeaves`; it never calls
HardType again. A one-field Bundle memory is not a scalar SInt memory. Native
memory read expressions are Bits transport; their separately declared SInt
reconstruction has its own authority. Local typed child/BlackBox ports retain
scalar intent with a hierarchy-boundary obligation. External RTL is neither
inspected nor inferred.

## Conservative transfer and limits

References and explicitly sized literals retain typed intent. Unary operations,
arithmetic, muxes and shifts retain signed value kinds only through reviewed
operator identities and compatible value operands. Mux selectors and shift
amounts are separate dependencies, not value alternatives. Comparisons and
logical/reduction operations return Boolean with operand-sizing obligations.
Explicit casts retain their conversion obligation. Concatenation and replication
are unsigned packed transport; bit/part selections are unsigned selected bits.
Narrowing/widening resize retains both widths and remains context-dependent;
an exactly unchanged width can retain its operand kind but still records the
resize obligation.

Unknown operators (including subclasses of reviewed arithmetic nodes), unsized
or poisoned literals, and unsupported geometry do not receive a guessed signed
answer. Width-growing dynamic shifts and unsupported mixed-width memory geometry
retain UnknownWidth. `requireKnown` rejects unknown value or width facts; it is
not a target-language cast-elision proof. Later declaration/cast consumers must
satisfy the recorded target declaration mode, literal encoding, operand sizing,
explicit conversion, resize, selection, memory and hierarchy obligations.

## Validation

The dedicated workflow runs the signedness unit/negative/replay tests and the
inherited single-source, canonical-handoff and typed-BlackBox suites on Scala
2.12.18 and 2.13.12. Tests compare observer-enabled and observer-disabled native
and parameterized emission from the same ordinary 60a fixture. The frozen 60a
hashes and deterministic replay remain mandatory.

The existing 60a tool-backed checker is reused unchanged: strict Icarus
Verilog-2001 parsing and simulation, Verilator lint, Yosys synthesis, independent
native-vs-parameterized baseline equivalence and a live negative-result mutation.
That default-width baseline proof is not the later 60f broad width matrix. The
analysis tests include scalar widths 1, 5, 8 and 32 without claiming that new
signed declarations have been emitted for those widths.

The source-scope guard rejects changes to existing production/native emitters,
source parsing, emitted-name classification and fixture-specific production
recognizers. All existing native-preservation and typed-retirement guards remain
required. The child checkbox changes only after implementation and final-head
qualification; the parent and 60c–60g remain open.

## Resume hardening and reproducible evidence

The recovered checkpoint's ownerless memory templates are handled without
assuming a Component owner. Aggregate storage width is counted separately from
logical width, and immediate aggregate children retain nested Vec depth factors
inside Bundles. Neither operation reevaluates a HardType generator.

`requireKnown` also rejects transitive unknown obligations and widths that are
not provably positive across their retained domain. A positive native witness
is not enough. Conservative interval bounds qualify sizing only, not signedness
or cast removal. An explicit, valid declaration-mode proof is still future work.
The observer rechecks its final phase position when it runs, rejecting later
phase-plan edits that move it away from the validated pre-emission boundary.

The focused suites contain 31 tests, including real scalar declarations and
feedback registers at widths 1, 5, 8 and 32; same-spelling independent symbolic
roots; typed resize; nested Bundle/Vec geometry; memory templates; negative
identity/role/slot/staleness cases; and unchanged native/MorphVerilog output.
`TypedSignednessReplayArtifactWriter` runs twice in separate JVMs per Scala lane.
Both fact reports and observer-enabled RTL are compared byte for byte, and the
RTL is compared against the unchanged 60a artifacts. The workflow retains the
reports, generated sources, ScalaTest XML and tool-backed baseline proof logs.

## Final resume regression closure

The final resume reconciles the saved local work with the newer remote branch,
without replacing its transitive unknown-context and memory fixes. Three restored
regressions close gaps in inferred width, replay domains and phase-plan validation.
A symbolic multiplication temporary with no retained width metadata must not be
reported as Fixed(16) merely because WIDTH defaults to eight; its scalar intent
remains signed, but logical width is UnknownWidth with InferredWidthAuthority.
Exact operator evidence remains available separately. No drivers are chased to
manufacture declaration authority.

Replay includes deterministic, name-independent width-domain and root-identity
ordinals, so distinct parameter domains do not silently yield identical reports.
Capture requires ordered normalization, cross-clock checking, allocation and
emission, rejects a second observer, and rechecks the exact final boundary when
executed. These checks affect only the opt-in analysis and leave RTL untouched.

## Graph-derived occurrence roles

Being present in the fact index does not establish a declaration or temporary
occurrence. The snapshot separately records which roles led to capture: direct
native declarations, native memory declarations, aggregate ancestors and real
expression edges. Memory word templates are shape-only dependencies and cannot
obtain declaration or expression evidence merely because their type was indexed.
An expression-only snapshot does not grant declaration evidence even for a node
that is declared in another snapshot. Declaration and memory-element uses also
validate actual native scope membership, not only the object's owner pointer.
Role observations are included in deterministic replay.

This pre-emission analysis has no exact emitter wrapper plan yet. TemporaryUse
therefore remains reserved and fails closed with USE-ROLE for every request in
60b, rather than labeling arbitrary expressions as emitted temporaries. Future
60c wrapper integration must introduce exact occurrence evidence before using
that role. No existing target declarations, casts or publication are changed.

Two review regressions demonstrate memory-template and expression-only role
forgery rejection, while preserving valid scalar and memory uses. Existing
unknown-expression checks still require UNKNOWN-FACT through ExpressionUse;
unplanned temporary requests now fail earlier at their factory with USE-ROLE.
