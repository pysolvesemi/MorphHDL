# Increment 60g — Default signed-Verilog rollout

**Status:** Implementation in progress; final-head qualification and merge are
not yet recorded. Both 60g and parent 60 remain unchecked.

**Integration base:** `99b6017d7ac69112a088680457029623620224d3`, including
qualified and merged 59d, 59e, 59f and 60f. The original branch began at
`ddbc9ff637ec0c42093111e7f8e48fc87957580f`; the 60g production delta and
reversible checker spans are measured against the merged integration state,
not against the earlier base. Integration target: `parameterized-verilog`.

## Production transition

Five MorphHDL-owned production files change: the three publication config
files plus `MorphHdlSignednessAnalysis.scala` and `MorphHdlSignedDeclarationPolicy.scala`. The single-source
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

`ad320814cd46d599d0937c246f078decf231694210fa4bd3fc0558065ad08d2a`

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

The current five-file production profile and the two new/extended rollout
qualification sources are SHA-256-pinned. The old 60f production-zero history,
59e/59f completed-increment source history, current native audit, unsigned
structural transport and every
sealed oracle remain mandatory. Source-restoration mutation controls reject
unrelated edits, duplicate spans and altered reviewed spans.

## Required qualification

The existing `SignednessCompatibilityTests` suite gains fifteen tests (twenty total) covering
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
minimum MorphHDL test count increases by fifteen. Zero failures/errors/skips
remain mandatory. Source-bound results and merge/post-merge checks are not yet
available in this in-progress record.

## Resume validation checkpoint

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

## Caller-installed observer compatibility

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


## Integration and execution-start closure

The current integration includes 59d's exact owner-aware width authority and
its independent widening matrix, without altering its reviewed native code or
arithmetic proofs. The 60g source ledger restores all six changed production
files to the complete merged 59d/59e/59f source before the inherited profile
checks run. Twelve complete source restorations and thirty-six source
mutation rejections are required. The twelve exact inherited source profiles
retain all 3,042 inventory rejection controls. On current 60g source, mutations
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
final five-file publication policy plus six-line lifecycle hook still requires
fresh compiler, independent-reference hardware, source-audit and compatibility
qualification before rollout completion.
