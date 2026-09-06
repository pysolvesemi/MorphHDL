# Increment 59f — Certified scalar callback graphs and exact captured inputs

## Scope and completion rule

This is the standalone callback-expressiveness successor to merged 59b. It does
not depend on composite shapes, changing stage-result widths, expanded clocked
bridges or nested structural owners from 59c–59h. Those combinations belong to
59i. The public source remains ordinary `Vec(...).reduceBalancedTree(op,
levelBridge)` and ordinary native scalar operations.

Local implementation and the qualification recorded below are complete.
Integration-head GitHub workflows and merge remain pending. Local results do
not establish a completed roadmap checkbox or a merged increment. Completion
requires definitive passing evidence from the exact integration head in both
Scala lanes.

## Admission contract

Callback admission happens before callback effects execute. An inspectable
compiler lambda and its complete supported helper/effect graph must establish
host-code safety; matching a few executed callback results is not purity proof.
Immutable native typed configuration and read-only hardware captures retain
exact object identity, scalar kind, symbolic width authority, native owner and
scope. A captured runtime operand is connected as a runtime operand at every
native operator use, not frozen to an elaboration witness or applied once at the
end of the reduction.

Source helper names have no semantic role. A user-authored final field-free helper is
accepted only when its actual available method body and initialization/effect
boundaries pass the same certification as an inline form. Unknown calls, mutable
host fields, recursive/unbounded helpers, foreign writes, partial drivers and
unproven native graph transfers continue to fail closed.
The final receiver class fixes virtual dispatch to the inspected method body;
a module-shaped mutable binding to an overriding subclass is rejected before
the helper can execute.

All admitted graphs have one scalar fixed-result-width function. Internal nodes
may carry locally certified symbolic widths, including WIDTH+1 and 2*WIDTH, but
each pair's result and each odd tail retain WIDTH. Width evidence is derived
from typed native nodes, never from equal concrete witness widths. This does not
claim the changing-width stage-result contract of 59d.

The minimal native mux hook preserves typed width authority after the unchanged
concrete native maximum-width/clone operation. The registry helper transfers
authority only when every mux input has an equivalent exact symbolic-width
function with matching roots. Missing or mismatched authority remains unchanged;
equal default widths alone do not authorize propagation. `BitVector` and
`ParameterizedWidth` changes remain covered by the cumulative approved native
review and canonical source-preservation manifest.

Symbolic intermediate carriers retain their native normalization boundaries so
the publisher can address the exact typed resize nodes after native emission.
For unsigned widening, an authoritative source width (or an invariant native
literal/fixed selection) proves the complete symbolic zero-padding count. A
direct native zero assignment on one live typed carrier emits a replication of
that carrier's symbolic width. Both transfers require exact native graph and
emitted-assignment lineage; matching a default literal or width alone grants no
authority. This keeps part-selection and saturation callbacks free of implicit
width-extension warnings at WIDTH=1 and at larger overrides.

## Qualification fixtures

`TypedBalancedReductionCallbackGraphArtifactWriter` uses sixteen ordinary native
scalar reduction callbacks. These examples exercise generic graph transfers;
none is a production class, callback-name or emitted-text recognizer.

| Output | Native source behavior | Qualification purpose |
| --- | --- | --- |
| `composed` | `(a + b) ^ a` | Multiple dependent operations and order sensitivity |
| `subtraction` | `a - b` | Exact native left/right pairing without reassociation |
| `helper` | Pure user helper implementing `(a + b) ^ a` | Inspected source-equivalent helper form |
| `selected` | `Mux(a(0), a, b)` | Native bit selection and mux |
| `conditioned` | Local UInt initialized from b, overwritten by a under `when(a > b)` | Complete local assignment graph and hardware condition |
| `sliced` | Concatenate a/b, convert, resize to captured typed WIDTH, XOR a | Locally widened intermediate, conversion, truncation and typed configuration capture |
| `part` | Extract one-bit UInt part at bit zero, resize to typed WIDTH, XOR b | Native part selection with legal WIDTH=1 boundary |
| `biased` | `(a + b) + bias` | Read-only runtime capture at each operator use |
| `alternate` | `(a ^ b) + otherBias` | Independent capture identity and runtime values |
| `saturated` | Explicit WIDTH+1 resizes and sum, compare to widened all-ones WIDTH constant, select max or narrowed sum | Unsigned saturation with a widened intermediate and typed symbolic constant |
| `signedComposed`, `signedSub`, `signedBiased` | SInt composition, ordered subtraction and captured signed input addition | Signed interpretation, modular extremes and exact signed capture binding |
| `signedSelect` | `Mux(a > b, a, b)` over SInt | Native signed comparison and mux with symbolic WIDTH |
| `bitsComposed` | `(a ^ b) & a` over Bits | Multi-node bit-vector graph with independent elements |
| `boolComposed` | `(a ^ b) \| a` over Bool | Multi-node native one-bit graph with independent flags |

The saturation candidate forms a typed `UInt(callbackWidth.bits)` maximum and
assigns `~U(0).resize(callbackWidth)` to it.
`callbackWidth` is the immutable native `ElabInt` obtained before callback
construction. No Scala Int/BigInt width witness constructs the symbolic maximum.
The independent native reference uses an ordinary concrete-width BigInt literal,
which is legal because each reference is separately elaborated at one fixed
specialization.

Runtime `bias`, `otherBias` and `signedBias` are separate module inputs. The source aliases each
input into a local before forming its closure, so the actual captured object is
the native scalar rather than the enclosing Component. Captured typed WIDTH is
also a local exact native configuration object. No replacement reduction API or
user-authored algorithm is introduced.

## Exact topology and oracle independence

At every level, native adjacent pairs are reduced left-to-right, and the odd last
operand passes through its native identity bridge unchanged. COUNT=1 bypasses
all operator and bridge uses. This gives COUNT−1 operator uses while preserving
non-associative subtraction, compositions and per-use captured bias semantics.
The implementation must not insert neutral padding or algebraically reassociate
these graphs.

Two static candidate profiles are generated once each: WIDTH=5/COUNT=1 and
WIDTH=8/COUNT=3, both with declared positive domains WIDTH=1..32 and COUNT=1..17.
Each sole candidate is specialized across WIDTH={1,5,8,32} and
COUNT={1,2,3,5,8,9,16,17}. The same candidate file must be reused for all 32 points
of its profile, including overrides larger and smaller than its defaults.

The separate reference Component uses ordinary concrete Spinal elaboration,
unpacked independent element slices and ordinary native `reduceBalancedTree`.
It does not call candidate helpers, graph replay or the parameterized backend.
Each reference asserts the native reduction result is exactly its UInt/SInt/Bits
kind of WIDTH or Bool kind of one bit, and that the saturation sum is exactly
WIDTH+1, before result wiring. The miter
contains wiring and comparisons only, with no arithmetic implementation or
bilateral width padding. Independently generated artifact trees must match byte
for byte.

`check-increment-59f-callback-graphs.py` additionally uses an independent integer
model with native adjacent pairing and untouched odd tails. It drives separate
element patterns in four independent Vecs and changes all three captures while
holding the element vectors fixed. Formal inputs leave all elements and captures independently
unconstrained.

## Required evidence

The dual-Scala workflow `increment-59f-callback-graphs.yml` requires Scala 2.12.18
and 2.13.12. It runs expanded and inherited callback/reduction source tests,
approved-native-change/source-retirement gates, repeated artifact generation,
all 64 new specializations and the inherited 59b publication matrix.
The inherited retained-value/resize tests and captured-domain assignment
normalization suites also run in each lane, including rejection controls for
changed native zero literals and duplicate emitted drivers.

The 59f publisher adds symbolic widths to exact native zero literals and proven
unsigned resize padding. `increment-59f-publisher-edits.json` records only those
fallback changes and the inherited 60e checker's restoration hook, against
merged base `5a669d32095ee722c313bd069b771e7c350a1f81`. Its SHA-256 is pinned in
`check-increment-59f-source-scope.py`. Unique exact spans and complete before/after
blob hashes must agree before the inherited 60c/60e comparisons run. The 60f
sealed-checker comparison similarly restores only that reviewed 60e hook; all
independent writers, native signed hooks and authority checks stay sealed.
`test-increment-59f-source-scope.py` checks three positive and twelve negative
isolated fixtures, including changed proofs, extra source, duplicate spans,
altered or missing manifests/helpers, and unrelated oracle/native changes.

Each new specialization requires Icarus Verilog-2001 compilation and simulation,
strict Verilator Verilog-2001 lint, full Yosys synthesis of candidate/reference,
and a definitive combinational SAT equivalence success. Tool errors, timeouts,
UNKNOWN, skipped tests and missing success markers fail the gate.

Mutation controls change actual generated candidate logic: subtraction operand
order, a dropped native XOR operation and runtime capture binding. Each must
produce a definitive failing equivalence result and a VCD showing `bad=1`.
Mutation-only miter corruption is not acceptable. Source-level controls also
cover host mutation/effects, foreign writes, partial drivers, graph changes,
illegal capture identities/ownership and ambiguous width/domain evidence.

Successful complete runs write `evidence.json` with the candidate profile,
specialization, native operator-use count, simulation count, artifact SHA-256
digests and mutation controls. A focused `--case` check deliberately writes no
complete qualification evidence. The finite matrix is specialization evidence,
not universal formal quantification over all parameter values.

## Local reproduction

Generate twice using
`morph/Test/runMain spinal.core.internals.TypedBalancedReductionCallbackGraphArtifactWriter <directory>`
with each supported Scala version, then run:

```sh
python3 morphhdl/scripts/check-increment-59f-callback-graphs.py <first-directory> <second-directory>
```

The tool-independent integer/mutation-guard checks are available via `--self-test`.
Use `--case singleton_w5_n3` only for focused development; it does not replace the
complete qualification gate.

## Recorded local verification — 2026-09-06

The implementation and qualification fixtures through `10e7e408c` passed:

| Check | Scala 2.12.18 | Scala 2.13.12 |
| --- | --- | --- |
| Targeted and inherited regression tests, 17 suites | 209 passed; no failures or skips | 209 passed; no failures or skips |
| Callback profiles and specializations | 64 passed | 64 passed |
| Independent callback simulation vectors | 27,330 passed | 27,330 passed |
| Changed callback RTL counterexamples | All 3 produced SAT failure and `bad=1` VCD | All 3 produced SAT failure and `bad=1` VCD |
| Inherited 59b publication specializations | 32 passed, including reset entry and induction | 32 passed, including reset entry and induction |
| Inherited publication mutations | Both produced genuine counterexamples | Both produced genuine counterexamples |

Every HDL specialization passed strict Verilog-2001 lint/parsing, full synthesis,
independent simulation and definitive equivalence. Repeated candidate and native
reference artifacts passed byte comparison. The native preservation and
retirement audits, inherited source-only gates, and 37 isolated source controls
(13 positive, 24 negative) also passed.

The final helper-dispatch rejection test was added after the initial 208-test
runs; the affected policy and publication suites were rerun successfully in
both lanes. The resulting XML inventory contains 209 distinct passing tests per
lane. Native references were independently re-elaborated at one revision after
that safety change so their generator comments also matched. The optional writer
argument `--references-only` supports this local refresh, requires existing sole
candidates, and leaves the strict artifact comparison unchanged. CI uses two
complete generations from its exact checked-out head.

These results are local evidence. GitHub publication was blocked by automatic
approval review, so remote workflows and integration merge have not run and the
59f roadmap checkbox remains open.

Standing publication approval was subsequently confirmed. Publication through
the connected GitHub API preserves the reviewed file tree with a new Git commit
identity. The publisher audit is anchored to the merged 60f base; both audited
before-blobs are byte-identical to the original local checkpoint. The exact
span and complete before/after hash checks remain in force.
