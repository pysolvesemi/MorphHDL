# Increment 60g: integration with the completed WA-07a sibling

## Status

The qualified 60g implementation is `f496ae8251dc4ce26e3e3a33894e7f1e652c8f92`.
Its documentation-only completion is `4148286a5d18c496d1ad2430ddff0525197325f8`.
The target branch subsequently merged WA-07a in
`5db83983b42c71df4f43d6a7c37c5bd552cd96c5` (PR #158).
This integration retains both histories and both implementations. It requires
its own applicable CI before PR #167 can merge. The prior implementation's
results are not relabeled as this integration's qualification.

## Source-audit repair

The old 60g audit required its complete current production delta against the
59h base to contain only the nine 60g paths. A clean Git merge with WA-07a would
therefore fail that audit: the target introduces three unrelated pass sources.
The inherited 60f checker already recognizes exactly those three files and their
reviewed SHA-256 values. The outer 60g check now composes that same closed
profile rather than accepting arbitrary pass-workspace changes.

The original nine-path 60g inventory, all production and qualification hashes,
24 reversible ledgers, immutable RTL reference and exact native manifest remain
unchanged. The additional profile requires all three sibling paths, the actual
WA-07a merge ancestor, exact reviewed bytes, regular non-executable tracked
files, and agreement between HEAD, the index and the working tree. A descendant
cannot silently remove the completed sibling profile. An unmerged lookalike,
partial profile, unrelated production path or changed file remains rejected.
The two independent checker maps are checked for exact agreement.

The pass workspace tree and its workflow are copied unchanged from the merged
target. No 60g Scala production, signedness transfer rule, native emitter,
independent reference, public golden or HDL proof assumption changes. The old
golden patch is not reapplied. This audit-only repair does not change generated
Verilog; the existing actual-source examples remain in the 60g qualification
record.

## Tests and qualification boundary

`check-increment-60g-source-scope.py --self-test` now adds 27 real Git/index and
filesystem rejection controls, using explicitly synthetic file bodies. They
cover unmerged lookalikes, missing and additional paths, complete reversion,
modified or absent source, hidden staged changes, symlinks, executable files,
untracked exact bytes, committed drift and uncommitted restoration. Both clean
standalone and combined profiles are accepted. These controls are source-audit
tests, not HDL equivalence evidence.

Local validation before publication passed the existing 24 exact restorations
and 72 mutation rejections, the new 27 sibling controls, Python compilation,
and the full inherited 60f source-only/native-audit chain on the unchanged 60g
production checkout. The complete 3,051-case catalog rerun was stopped by the
local command time budget and is not claimed as a new pass. Actual combined
source, all inherited regressions and hardware jobs are left to the normal
exact-head CI; no failed or in-progress job is treated as successful.


## Inherited inventory projection repair

The combined `a6d6ad2e1d697a911577060e3ad317ffb0affd4b` CI identified
a missing composition layer. The outer 60g audit admitted the exact merged
WA-07a profile, but the 59h and 59c audits still counted those three files as
local production changes. The full 60f source chain therefore rejected the
combined tree before the dedicated HDL proofs. Both Scala compatibility and
full regression execution steps succeeded; their final inventory gate failed.
This is not recorded as successful hardware qualification.

The repaired 59c and 59h inventory adapters now project only the validated
sibling delta out of their local view. Before projection, the shared 60g helper
requires the complete sibling inventory, actual merge ancestry, exact file
hashes, non-executable regular files, and identical HEAD/index/worktree blobs.
It preserves any sibling path already changed between the caller's baseline
and the pre-sibling baseline, and leaves unrelated paths visible for rejection.
The outer 60f union still includes WA-07a and requires its exact pass tests;
this is not a global exclusion of pass-workspace changes.

The two audit adapters' 60g reversal entries and qualification hashes are
refreshed to their exact new bytes. All other reversal entries, 59c/59h original
contracts, Scala production and tests, native manifests, proof assumptions,
independent RTL references, public goldens and workflows remain unchanged.
The current target's WA-07b roadmap-only update is retained unchanged.

In addition to the existing 24 reversals, 72 restoration mutations and 27
sibling-scope attacks, the new self-test invokes both actual inherited inventory
adapters. It accepts standalone/combined views, checks historical overlap and
unrelated-path preservation, and rejects 44 corrupt, missing, executable,
linked, untracked, secretly staged, unmerged or reverted sibling cases. These
are source controls, not RTL proofs. The complete combined source-only chain
now passes locally; broader current-head CI remains required before merge.

This repair does not affect generated Verilog. Previous actual generated
examples and the qualified implementation record remain unchanged. Final-head
CI and post-merge results must retain their own source identities.


## Integration with the merged 59g register bridges

During the audit repair, PR #169 merged into the target as
`424a548f60a6c1fe003e3d4d908e3b0f75e56632`. The combined 60g branch preserves
that complete qualified register implementation, its original source-review
contract, native initializer ownership, test suites and hardware workflow. It
also retains the WA-07b roadmap change. This is a new integration revision,
not a claim that either predecessor's evidence qualifies their combination.

The outer 60g ledger is now measured against that actual merged baseline.
Seven MorphHDL production files and the six-line native PhaseContext lifecycle
hook remain the exact 60g implementation delta. The native fallback publisher
is byte-identical to the 59g baseline: that sibling already incorporates signed
initializer spelling and the current retained-initializer handling. It is
pinned unchanged, rather than claiming a redundant 60g restoration for it.
All native arithmetic/emitter code, the approved native manifest, independent
reference arithmetic and the already regenerated public goldens are retained.

The 59c, 59h and 59g source auditors first reverse the exact outer 60g layer,
then apply their unchanged predecessor contracts. Each inventory excludes only
proven later-layer paths while retaining historical overlap. WA-07a's three
files stay in the outer union with exact hashes and real merge ancestry; they
are not silently dropped when the inherited baseline already contains them.
The 25-entry outer ledger includes the register auditor and its mutation-test
adapter. Every original register mutation remains mandatory; overlapping
source mutations now require the precise earlier 60g diagnostic. No accepted
source set or test suite is selected by a passing report alone.

Local combined-source checks passed the complete 60f source-only/native audit
chain and all 22 current register-source rejection controls. Changed production
Scala and all current MorphHDL Java/Scala test sources compile with Scala
2.13.12 against the hash-verified archived dependency classpath. The compiler
configuration follows the repository's project-specific plugins and mixed
Java/Scala symbol analysis. This is an incremental local build, not a fresh
SBT or Mill build and not current-head hardware qualification.

The source-audit repair itself does not affect generated Verilog. The joined
register/default-signed behavior still requires the dedicated 59g and 60g
matrices, full inherited regression catalog, strict tools, independent formal
references, negative controls and cross-Scala determinism on the published
integration revision before merge. The original golden patch is not reapplied.
