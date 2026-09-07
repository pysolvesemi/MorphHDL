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
