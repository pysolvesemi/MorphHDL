# Increment 60g — Default signed-Verilog rollout

**Status:** Implementation in progress; final-head qualification and merge are
not yet recorded. Both 60g and parent 60 remain unchecked.

**Integration base:** `b25e367d99604e61b8f2c895b2c51ca1ab90d423`, including
qualified and merged 60f, 59f and 59e. The original branch began at
`ddbc9ff637ec0c42093111e7f8e48fc87957580f`; the 60g production delta and
reversible checker spans are measured against the merged integration state,
not against the earlier base. Integration target: `parameterized-verilog`.

## Production transition

Only three MorphHDL-owned production files change: `MorphVerilog.scala`,
`MorphSignedDeclarations.scala` and `MorphSignedCasts.scala`. The single-source
publication copy resolves a neutral config to the already-qualified minimal
cast policy. Exact no-op config markers retain an explicit opt-out; no global
or thread-local switch, source recognizer, signal-name recognizer, new signed
operator implementation or arithmetic rewrite is introduced.

Native SpinalVerilog and VHDL remain unchanged. The native emitters, typed
signedness analysis, boundary transfer rules and native-change manifests are
unchanged from the merged base. The final native-source manifest is
`morphhdl/contracts/native-source-preservation.json`, SHA-256:

`d0b9594b76b109222adaba7818233d239a7f68f553f29b758e062d388c1ad236`

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

The current three-file production profile and the two new/extended rollout
qualification sources are SHA-256-pinned. The old 60f production-zero history,
59e/59f completed-increment source history, current native audit, unsigned
structural transport and every
sealed oracle remain mandatory. Source-restoration mutation controls reject
unrelated edits, duplicate spans and altered reviewed spans.

## Required qualification

The existing `SignednessCompatibilityTests` suite gains eight tests covering
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
minimum MorphHDL test count increases by eight. Zero failures/errors/skips
remain mandatory. Source-bound results and merge/post-merge checks are not yet
available in this in-progress record.
