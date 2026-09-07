# Increment 60g — Default signed-Verilog rollout

**Status:** Implementation and final-source qualification complete. All applicable
PR workflows passed at `f496ae8251dc4ce26e3e3a33894e7f1e652c8f92`.
The documentation-only completion transition marks 60g and parent 60 complete;
its applicable CI, merge and post-merge status are tracked separately in
[PR #167](https://github.com/pysolvesemi/MorphHDL/pull/167).
See [final implementation qualification](#final-implementation-qualification)
for exact source, actual generated RTL, full test inventories and proof evidence.

**Integration source base:** `cba4717abc9192917d819e1f84cb246162488286`,
including merged 59c, 59d, 59e, 59f, 59h and 60f, and the 59d documentation
closeout at `0018da2740645e0ac0c419ded7b67c01622d2bb7`. The previously
qualified 60g head `bebe490c49aa99ec2a8223419c23c64e2d66786c` preceded
59h's integration and does not qualify the combined source. The original branch began at
`ddbc9ff637ec0c42093111e7f8e48fc87957580f`; the 60g production delta and
reversible checker spans are measured against the merged integration state,
not against the earlier base. Integration target: `parameterized-verilog`.

## Production transition

Eight MorphHDL-owned production files change: the three publication config
files, signedness analysis and declaration policy, native symbolic resize
coordination, retained zero-initializer spelling and structural keyword scanning. The single-source
publication copy resolves a neutral config to the already-qualified minimal
cast policy. Exact no-op config markers retain an explicit opt-out; no global
or thread-local switch, source recognizer, signal-name recognizer, new signed
operator implementation or arithmetic rewrite is introduced.

Native SpinalVerilog and VHDL retain their output and phase order. Native
emitters and arithmetic boundary transfer rules remain unchanged. The sole
native edit is a six-line PhaseContext lifecycle hook, recorded in the native
manifest: a private monotonic flag, read-only getter and execution-start
assignment. Strict observers retain the full original capture. Publication-only
selection now captures signed objects and their complete dependencies, retaining
all occurrence roles and all existing identity, width and freshness checks.
Unrelated unsigned declarations/wrappers retain their original native authority;
they cannot fail merely because an unrelated branch has a narrower width domain.
The full original analysis and policy restore exactly through the scoped ledger. The final native-source manifest is
`morphhdl/contracts/native-source-preservation.json`, SHA-256:

`d4f3a0d62bfaaab2cc32e6e95baa194926f5e5b869324b429fb26b79562923b0`

The existing native helpers `emitSignedOperand`,
`operatorImplAsBinaryOperatorSigned`, `operatorImplAsBinaryOperatorLeftSigned`,
`shiftRightSignedByIntFixedWidthImpl` and `operatorImplResizeSigned` still have
live native/default-disabled and genuine-boundary callers. They are not dead
code and cannot be deleted while preserving the native compatibility contract.
The shared operand helper already delegates cast decisions to the exact typed
policy. The obsolete “experimental/opt-in-only” default description is removed;
necessary cast and resize helpers remain deliberately intact.

## API and migration example

```scala
import morphhdl.{MorphSignedCasts, MorphSignedDeclarations, MorphVerilog}
import morphhdl.frontend.HdlInt
import spinal.core._

final class SignedSum(width: HdlInt) extends Component {
  val a, b = in(SInt(width bits))
  val sum = out(SInt(width bits))
  sum := a + b
}

val config = SpinalConfig(targetDirectory = "rtl")
MorphVerilog(config) {
  new SignedSum(HdlInt.param("WIDTH", default = 8, min = 1, max = 32))
}
// Legacy output: MorphVerilog(MorphSignedDeclarations.disable(config)) { ... }
// Declarations only: MorphVerilog(MorphSignedCasts.disable(config)) { ... }
```

The default declares scalar ports as `wire signed [WIDTH-1:0]` and emits
`assign sum = (a + b);` without redundant casts. Overflow stays WIDTH-bit.
A packed vector containing signed elements remains unsigned transport.
The [architecture profile](architecture/verilog-2001-profile.md#native-signed-scalar-publication-increment-60g)
records all mode selections and the retained boundary rules.

## Independent references and source preservation

The 60a/60c shared artifact configs now request the old legacy mode explicitly.
No fixture arithmetic, native reference generator, sealed RTL hash or proof
assumption changes. The 60b observer tests likewise select the original legacy
mode before testing observational byte identity. Exact before/after manifests
restore only these reviewed configuration/historical-checker spans, then every
inherited guard still checks its complete original source contract.

The current production profile and rollout qualification sources are
SHA-256-pinned, including both scanner and compatibility regressions. The old 60f production-zero history,
59e/59f completed-increment source history, current native audit, unsigned
structural transport and every
sealed oracle remain mandatory. Source-restoration mutation controls reject
unrelated edits, duplicate spans and altered reviewed spans.

## Required qualification

The existing `SignednessCompatibilityTests` suite gains seventeen tests (twenty-two total) covering
WIDTH defaults 1/5/8/32, zero redundant pure-arithmetic casts, complete unchanged
unsigned ports, explicit legacy/declaration-only selection, config copies,
repeated mode transitions, same-session native Verilog/VHDL bytes, real casts,
`tryGenerate`, canonical-IR publication and null rejection before elaboration. The real-cast test uses a dynamic
selection from unsigned packed Vec transport; materialized scalar SInt
references are not required to retain redundant casts.
No inherited test or suite is removed.

A new writer emits ten actual neutral-config default candidates and ten
independently elaborated explicit-cleanup counterparts. It covers six 60e
boundary families plus pure arithmetic, mixed casts, the original 60a fixture
and declaration/memory surfaces. Every default file must match both its explicit
counterpart and the corresponding freshly generated inherited candidate.
The twenty-file corpus must reproduce in fresh JVMs and downloaded artifacts
from both Scala 2.12.18 and 2.13.12 at the exact same commit. The evidence gate
also rejects missing/empty/extra files, symlinks, stale source and RTL hashes,
wrong Scala lanes, malformed or duplicate manifest fields, fresh-JVM drift
and drift shared by all candidates. Eighteen synthetic rejection controls test
this gate; they are not substitutes for HDL mutation counterexamples.

The proof workspace copies the independent 213-file inherited corpus and
replaces those ten candidate paths with the actual default-generated bytes.
The complete 60f tool/equivalence checker then runs on this workspace: all 64
boundary tuples, inherited pure/declaration/baseline equivalence, genuine
mutations, exact 60a solver-witness replay and supplementary memory-validity
checks. This byte equality is a bridge into the existing independent proofs,
not a replacement for running the tools or a universal parameter proof.

Both compiler lanes, complete no-skip inherited regression inventories,
strict Verilog-2001 parsing, Icarus simulation, Verilator lint, Yosys synthesis,
formal counterexamples, native audits, public golden regeneration, baseline
and Mill CI are required before closure. The new tests extend an already
required suite, so the exact named suite inventory remains unchanged and its
minimum MorphHDL test count increases by eighteen (seventeen compatibility
cases and one lexical case). Zero failures/errors/skips
remain mandatory. The final implementation qualification below records their
source-bound terminal results. The merge and post-merge checks are separate
from that pre-merge evidence.

## Historical implementation checkpoints

The following checkpoint discussions describe the repairs leading to the
qualified implementation. Statements about then-pending tests or completion
apply to their historical heads, not to the final qualification recorded below.

### Resume validation checkpoint

At `7b540ceceaa471ed0df693ad04c2c30f3cd7eba7`, both dedicated Scala
lanes passed all 13 compatibility tests and the full inherited signedness
proof/mutation matrix. Only the public signed-shape golden step failed in that
workflow. Downloaded 20-file default/explicit RTL and all 21 public contract
outputs match across Scala versions. The updated golden is copied from those
actual generated bytes, not obtained by editing legacy RTL.

The broader regression run separately exposed unsigned branch-domain failures
from eager whole-graph signedness capture and unrelated unsigned wrapper sizing.
The publication-only dependency selection fixes that integration boundary while
keeping strict observer semantics and signed-use checks unchanged. Two additional
regressions cover mixed signed/unsigned hierarchy at WIDTH defaults 1 and 8, and
symbolic signed growth at defaults 4, 8 and 12. The original legacy rejection
was superseded by merged 59d: the integrated test requires success and exhaustive
Icarus comparison with signed assignment semantics in both publication modes
and under all three overrides.
These repairs still require fresh exact-head Scala, tools and full-regression CI.

### Caller-installed observer compatibility

A strict `MorphHdlSignednessAnalysis.install(observer)` and the default
publication consumer share one physical phase immediately before native
emission, in either installation order. The strict observer still receives
its full, unchanged capture; publication uses its independent signed dependency
capture. Both snapshots are created before any caller callback. A callback
mutation therefore cannot acquire newly refreshed publication permission.
Duplicate strict observers, duplicate publication consumers, duplicated/moved
physical phases and late registration remain rejected. Three rollout tests
cover both orders, repeated config use, unchanged native generation, five
invalid-plan controls and a stale-evidence mutation that must preserve the
previous public file. The original inferred-width observer regression again
uses unmodified `MorphVerilog(config)` rather than a default opt-out. Only the
sealed cast-heavy oracle comparison paths explicitly select legacy output.


### Integration and execution-start closure

The current integration includes 59d's exact owner-aware width authority and
its independent widening matrix, without altering its reviewed native code or
arithmetic proofs. The 60g source ledger restores all nine changed production
files to the complete merged 59c/59d/59e/59f/59h source before the inherited
profile checks run. Twenty-four complete source restorations and seventy-two
source mutation rejections are required. The twelve inherited source profiles
and named-field/nested-owner extensions retain all 3,051 inventory rejection
controls. On current 60g source, mutations
of a checker wrapped by the rollout ledger are rejected at that outer exact
blob boundary before reaching the historical 59d seam check; the fixture
expectations distinguish those exact errors and preserve historical profiles.

A scheduler-owned execution flag closes consumer registration before any
native or caller phase runs, including a phase inserted before creation. The
state belongs to the exact PhaseContext, is read-only to consumers, and is never
reset during that context's lifetime. No movable registration phase is inserted;
this preserves native verbose-mode graph checks after creation. First-ever
runtime installation and late attachment of either consumer role are rejected.
The original capture-phase placement, duplicate and freshness gates remain.
Two new tests cover nine early/runtime registration combinations, monotonic
state through a failing phase, and native verbose output with and without a
strict observer. No rejected callback runs or replaces an existing public
artifact. Inactive Morph signedness options also leave the complete native
phase inventory unchanged in all five option modes. The native hook changes no
arithmetic, graph rewrite, inherited validation, phase order or logging.

The predecessor `cefb24d51daaef9a080870b714059b84b6a5a988` passed its
60g, 60f, baseline, Mill and all applicable inherited PR workflows. Its results
are historical, not qualification of this integrated execution-start repair.
Current integration and repair qualification must be recorded before 60g or
parent 60 is marked complete.


Inactive signedness options do not add publication or registration phases to
ordinary native Verilog generation. The native-isolation regression compares
both emitted bytes and the complete native phase-class inventory across all
five option selections; an inactive option cannot accidentally run before
native verbose checksum traversal has a constructed top-level component.
Caller-requested strict observers remain separate from publication options.

The native lifecycle hook is deliberately separate from the signedness policy.
Its exact field/getter and execution-start assignment are recorded in the
existing native-change review and schema-v2 manifest; only Phase.scala changes
under native source roots. The older typed-overlay file remains historical and
its compatibility command delegates to the current canonical audit. The
final eight-file publication/serialization policy plus six-line lifecycle hook still requires
fresh compiler, independent-reference hardware, source-audit and compatibility
qualification before rollout completion.


### Recovered widening, selector and lexical integration fixes

Publication captures signed dependencies **before** evaluating a caller's
selector. Captured values, declarations, memory roles and expression edges are
revalidated after the selector and again after callbacks, even when the selector
returns false. Two added compatibility tests cover both selector outcomes,
clean publication and mutations that must leave a previous output untouched.

Signed cleanup no longer excludes native signed-resize capture. A resize owned
by the exact native symbolic publisher retains that publisher's protected scalar
boundary; the declaration policy validates its identity and source/target widths
before deferring that one occurrence. Symbolic sign extension, narrowing and
intermediate widths are therefore not frozen to an elaboration default.
Retained register-zero witnesses use the signed literal spelling only when that
printer mode is active; exact assignment ownership and match counts remain gates.

The Verilog keyword `signed` is excluded from structural reference sets. It
cannot join otherwise unrelated signal-dependency groups. The new lexical test
checks independent signed declarations and a real `$signed` operand reference.
The existing widening test generates both reusable default profiles twice,
checking independent widths, signed kinds, odd-tail geometry and byte identity.

Resize qualification follows the two actual multiplication operands and
requires distinct signed TARGET-wide declarations, rather than relying on the
old anonymous helper names. The sign-extension mutation still corrupts the
same actual resize edge and must produce a genuine solver counterexample; only
its exact emitted syntax changes. The full unchanged 59c source-attack fixture
also runs on its completed pre-rollout tree, separately from the same current
attacks rejected by the new outer ledger. Neither historical checks nor
synthetic rejection controls count as new HDL qualification.

Local recovery reached 30 passing Scala 2.13 compatibility/lexical/widening tests
with zero failures, errors or skips. Independently generated default/explicit
candidates agree across fresh JVMs, and all 21 actual public contract outputs
match the already-updated goldens. The 2.12 build and remaining independent HDL
proofs require their recorded terminal results. Local incremental compilation
uses verified archived classpaths with changed source rebuilt; it is not a
substitute for the complete exact-head SBT/Mill and hardware CI lanes. Final
qualification and merge remain pending.


### Integration with merged 59h

PR #170 merged at `cba4717abc9192917d819e1f84cb246162488286` during 60g's
qualification. Its lexical-owner, branch-domain, finite-index, static-Vec and
child-parameter work is retained in full. The two shared production files merge
without an algorithm change: the structural scanner still excludes the `signed`
keyword and the native publisher still preserves the signed reset-zero witness.
No old public golden patch is reapplied.

Source audits compose in strict reverse order: 60g publication changes, then
59h nested-owner changes, then the earlier 59c/59d/59e/59f authorities. The
immutable 59h source-review manifest is unchanged. The outer 60g ledger records
the reviewed integration adapters and updated native manifest, including full
before/after hashes. Shared-file mutations must fail at the outer exact-file
boundary; unshared files retain the original 59h diagnostics. All original
59h mutation controls are additionally replayed on its pre-rollout tree, with
the historical commit recorded separately from current-source evidence.

The complete regression catalog retains all nested-owner suites and all
reviewed rollout counts. The nested-owner hardware workflow runs for this 60g
integration and future integration-branch changes, so the 105-case native
reference matrix and three mutations cannot be bypassed by a branch-name
routing condition. No independent RTL oracle or arithmetic proof assumption is
changed. New combined-head CI is mandatory before completion or merge.

Before this integration, `bebe490c` passed both full 1,833-test/179-suite lanes,
both 60g lanes and CI cross-Scala comparisons, both 59c/59f integration lanes,
baseline, Mill and the other completed inherited checks. The structural/process
setup failure was a Mill-download connection error, and its retry passed without
source changes. The 59d hardware matrix was still running when 59h merged.
Those recorded results remain historical evidence for `bebe490c`, not a claim
that this new integration head is qualified.

## Final implementation qualification

**Implementation:** `f496ae8251dc4ce26e3e3a33894e7f1e652c8f92`
**Source tree:** `477d7f9c4b69d2c5277d5bd72a48581fcad51934`
**Integration base:** `cba4717abc9192917d819e1f84cb246162488286` (merged 59h)
**Pull request:** [MorphHDL #167](https://github.com/pysolvesemi/MorphHDL/pull/167)

### Qualified implementation status

The implementation, exact-head integration review and all applicable PR
workflows passed on September 7, 2026. Both widening hardware lanes also passed;
no required queued or running gate is being counted as proof. This record is the
basis for marking 60g and its controlling parent complete in a documentation-only
transition. The completion commit must pass its applicable checks before merge;
PR #167 records the separate merge and post-merge results. Pre-merge evidence
below is not relabeled as post-merge qualification.

### Implemented behavior

MorphHDL's strict Verilog-2001 single-source path now resolves a neutral
`SpinalConfig` to signed scalar declarations and proven minimal casts. All
necessary boundary casts remain. Ordinary `SpinalVerilog` and VHDL remain
unchanged, including their native phase sequence. Explicit legacy and
signed-declaration-only output are available:

```scala
MorphVerilog(config) { component } // Default signed declarations/minimal casts.
MorphVerilog(MorphSignedCasts.enable(config)) { component } // Explicit default.
MorphVerilog(MorphSignedCasts.disable(config)) { component } // Retain casts.
MorphVerilog(MorphSignedDeclarations.disable(config)) { component } // Legacy.
```

Default resolution occurs on a private configuration copy. Existing caller
observers share the validated emission boundary with independently scoped
publication evidence. Exact evidence precedes caller selectors and is checked
regardless of their Boolean result and after callbacks. Late registration is
rejected by the monotonic native PhaseContext execution-start flag.

Native signed resize ownership is preserved; only exact owner/identity/width-
proven occurrences defer to their existing publisher. Signed reset-zero
witnesses match the active printer. Structural dependency scanning treats
`signed` as a keyword while retaining references within `$signed(...)`.

Eight MorphHDL production files and the six-line native lifecycle hook change
relative to merged 59h. Native arithmetic and emitter implementations remain
unchanged. Source restoration composes 60g -> 59h -> earlier authorities.
The existing golden was not reapplied or text-reconstructed.

### Exact-head qualification results

| Gate | Evidence |
| --- | --- |
| Full inherited regressions | 1,872 tests / 182 suites on each Scala lane; zero failures/errors/skips |
| Dedicated 60g tests | 22 compatibility + 2 lexical tests on each Scala lane; no skips |
| Signedness hardware | 64 boundary tuples per lane, independent native-reference equivalence, strict tools, genuine mutations |
| Defined-state closure | Exact 60a SAT counterexample replayed with Icarus; arbitrary initial-state memory controls at WIDTH 1/5/8/32 |
| Downloaded default RTL | 20 files match across Scala 2.12.18 and 2.13.12 with exact-head manifests |
| Inherited/public bytes | 213 inherited and 21 public outputs match across Scala; all 21 public goldens match |
| Named-field integration | Each lane: 201 tests / 12 suites; 216 main + 16 register layouts; 48,512 samples; 1,877 nested SAT partitions; 11 + 2 mutation controls |
| Callback integration | Each lane: 294 tests / 23 suites; 64 cases; 27,330 samples; three solver-counterexample controls |
| Nested-owner integration | Each lane: 389 tests / 30 suites; 105 cases, including 21 registered reset-entry/induction checks; 12,129 samples; three mutation controls |
| Integration determinism | Actual A/B and cross-Scala main corpora: fields 125 files, callbacks 34, nested owners 110; register field corpus also matches |
| Widening integration | Each lane: 145 tests / 13 suites; 64 reset-entry and 64 temporal-induction proofs; 11,312 simulation cycles; four genuine counterexamples |
| Widening determinism | 34 generated A/B files match across Scala; all 64 reference/candidate source hashes agree |
| Source controls | 24 exact restorations / 72 mutations; 18 artifact-evidence negatives; 3,051 inherited regression-catalog rejection controls |
| Reviewed native manifest | 43 approved paths / 226 exact spans |

The supplementary memory validity proof is bounded to eight cycles; inherited
sequential induction remains separately required and passed. Parameter proofs
cover the documented finite matrices, not universal parameter quantification.
Synthetic source/inventory rejection controls do not substitute for RTL proofs.

Relevant exact-head runs:

- [60g default and cross-Scala](https://github.com/pysolvesemi/MorphHDL/actions/runs/34097973128)
- [60f full inherited and signedness](https://github.com/pysolvesemi/MorphHDL/actions/runs/34097972885)
- [59h nested owners](https://github.com/pysolvesemi/MorphHDL/actions/runs/34097973157)
- [59c named fields](https://github.com/pysolvesemi/MorphHDL/actions/runs/34097973133)
- [59f callbacks](https://github.com/pysolvesemi/MorphHDL/actions/runs/34097973102)
- [59e composites](https://github.com/pysolvesemi/MorphHDL/actions/runs/34097973007)
- [59d widening](https://github.com/pysolvesemi/MorphHDL/actions/runs/34097973078)
- [Baseline](https://github.com/pysolvesemi/MorphHDL/actions/runs/34097973135)
- [Mill](https://github.com/pysolvesemi/MorphHDL/actions/runs/34097973155)

### Same source and actual generated Verilog

The following are exact excerpts from the qualified
`nativeapplication.PureSIntCastFixture.Top`. Other tested inputs and outputs
are omitted here. The full source is retained at
`morphhdl/src/test/scala/nativeapplication/PureSIntCastFixture.scala`; the full
emitted module is `target/increment-60g/a/default/pure/pure-true.v` in both
qualified dedicated artifacts listed below.

```scala
    val a, b, c, divisor = in(SInt(width bits))
    val sum, difference, quotient, remainder, negative = out(SInt(width bits))
    val shiftConstant, shiftVariable, nestedShift, nestedDivision, nestedRemainder = out(SInt(width bits))
    val product, nestedProduct, negatedProduct = out(SInt((width + width) bits))

    val localSum = (a + b).setName("local_sum")
    sum := localSum
    difference := a - b
    product := a * b
    quotient := a / divisor
    remainder := a % divisor
    negative := -a
    shiftConstant := a |>> 1
    shiftVariable := a |>> amount
```

The default writer supplies
`HdlInt.param("WIDTH", default = 8, min = 1, max = 32)` and a neutral config.
The following lines are copied from its actual generated `pure/pure-true.v`:

```verilog
  input  wire signed [WIDTH-1:0] a,
  input  wire signed [WIDTH-1:0] b,
  output wire signed [WIDTH-1:0] sum,
  output wire signed [(WIDTH + WIDTH)-1:0] product,

  wire       signed [WIDTH-1:0] local_sum;
  reg        signed [WIDTH-1:0] saved;

  assign local_sum = (a + b);
  assign sum = local_sum;
  assign product = (a * b);
  assign shiftVariable = (a >>> amount);
  assign nestedProduct = (local_sum * c);
```

The WIDTH-bit `local_sum` still truncates before multiplication; changing its
width would change behavior. `Bits`, `UInt`, control signals and packed
aggregate transport remain unsigned. Dynamic selections of signed leaves from
unsigned packed transport still retain their necessary `$signed` boundary.

Source SHA-256: `7d9278c8ac16957885b834d542882774304ef3330cfaa6b7c3a137fdf075d85f`
Actual RTL SHA-256: `74f5566168d7710bc9782398057a691d42117dfbbb30bff42170240bc2fee836`
Native manifest SHA-256:
`d4f3a0d62bfaaab2cc32e6e95baa194926f5e5b869324b429fb26b79562923b0`

### Downloaded artifacts

| Artifact | ID | Verified ZIP SHA-256 |
| --- | --- | --- |
| evidence212 | 10009783423 | `a9127ace7a81df081a4164083813e5a64577b91936373f485cb04f74023d01dc` |
| evidence213 | 10010371654 | `e3ef8a69d962d6192dc39f2065f7beb8951d4ac4ddd655af5978a22c007a0fbd` |
| regressions212 | 10010712217 | `91fd76f59d1c6dd0612c4d5a93d103d732555f9ed08a88082194ecbb36640ebe` |
| regressions213 | 10010647849 | `978d0194d53f7428bbea1b52beb71d232ca7f9978d743658cd6c0e51ff16007d` |
| source | 10010665059 | `888390d9ab72377773fc60919ea0f14af999b3cbc6a7fc71691f817b117918af` |
| fields212 | 10011833107 | `b2f683dfbb29aa0b52049dfb73ca0389f4327fc9cec700fd2c2d2059a6dd993b` |
| fields213 | 10011424494 | `8aeb02defc2ee6163ec8f6f6f35c5e66e57059390d28aaa7ae754c236e82e4ec` |
| callbacks212 | 10011332194 | `666f7be3c72801b7517535c9a80480b8cb19e35f0fc112797b086d5c8ff0ad45` |
| callbacks213 | 10011247826 | `7c93b3f5200274608fe54b810f65549ee31445fd3a63093b4d0dcc9b6f038e9a` |
| nested212 | 10010517961 | `bf25f3fe26d6a8c0801fb8fdb919ebecde4dc7de0d281cb3444b07072afb1e27` |
| nested213 | 10010486459 | `eccfe6cada39fb6d171529dbec63d989f320beeef4f0f41b23d7c488e6eff243` |
| widening212 | 10013947900 | `6e0ae028205c5c637b1cad356642e1ded956a1de221a7130c91fa98a65feb54b` |
| widening213 | 10013830802 | `f55ed08df8886c92c1f04f62642c69070f682e02ad25af471437ec7f16517c72` |

### Integration evidence details

The field register-state comparison assumes equality of corresponding initial
flip-flop bits but leaves their values otherwise unconstrained; it does not
assume native initialization. Each register mutation has independently checked
equal initial states and divergent next states, plus checked native and mutant
transitions. Both archived trace hashes match their own VCD bytes. Those hashes
need not match a different solver run's dated VCD.

All 216 field cases and their 1,877 nested output partitions record PASS. All
64 callback and 105 nested-owner cases record PASS. Every registered nested
case records both reset entry and induction PASS. All main mutation controls
have nonempty VCDs and genuine solver model-found output rather than parser,
module-resolution or tool errors. Independently generated reference arithmetic
and proof-domain assumptions remain unchanged.

The dedicated, full-regression and widening artifacts are from the same
combined source, not predecessor CI. Widening covers two reusable default
profiles and WIDTH 1/5/8/32 crossed with COUNT 1/2/3/5/8/9/16/17. Every
specialization passed exact port-width checks, strict parsing and lint, complete
mapped synthesis, reset-entry proof and unbounded temporal induction. Each
native/candidate mapped hierarchy was checked without deleting cells or changing
state interfaces. The four live negative controls corrupt a carry bit, a sign
bit, a default-frozen result width and an odd-tail extension; each produced a
nonempty bad=1 solver witness. Parser/tool errors are not negative proofs.

### Legacy helpers

The native signed operand, signed binary/shift and resize helpers retain live
ordinary-native, explicit-legacy or real-boundary callers. They are not dead
code and were not deleted. The default-only opt-in wording is superseded, not
the genuine signed boundary operations.

### Closeout sequence

All implementation-head gates have passed. The completion transition changes
only this record and the two roadmap Markdown files. It does not alter tested
source, oracle arithmetic, proof assumptions, generated goldens, workflows or
tool pins. Normal applicable completion-head checks remain required, followed
by an expected-head-protected merge. Record the actual merge SHA and inspect
post-merge CI separately rather than relabeling these pre-merge results.
