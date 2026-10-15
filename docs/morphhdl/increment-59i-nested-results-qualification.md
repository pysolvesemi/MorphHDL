# Increment 59i — Nested-result qualification record

Status: the scoped nested-Vec result slice is implemented and locally qualified.
The full Increment 59i join remains incomplete, unchecked and unmerged.
See [the implementation and actual Scala/Verilog examples](increment-59i-nested-results.md).

## Published-source local qualification — September 8, 2026

The functional source is published at
`d442a867893356de8378578ba354dd4d9bb60aad`, tree
`cda6c6d8bbd371a6c44a906b00a058a34dd984e5`. Reconstructing that complete tree
from the staging commit and all twelve tested file identities produced the
same tree SHA. The newer bounded static-read wrapper and declaration-filter
repairs are retained; the old saved 58ec patch was not applied over them.

| Executed local gate | Scala 2.12.18 | Scala 2.13.12 |
| --- | --- | --- |
| Changed-source compilation | Passed | Passed |
| Reduction / ownership regression tests | All 322 distinct tests / 28 suites passed after the tool-path rerun described below | 322 tests / 28 suites; zero failures, errors or skips |
| Nested-result parameter matrix | 96 cases, all four profiles per case | 96 cases, all four profiles per case |
| Strict Verilog-2001 lint, parse and synthesis | All cases passed | All cases passed |
| Independent native/software simulation | 28,872 vectors | 28,872 vectors |
| Combinational SAT equivalence | 96 proofs | 96 proofs |
| Actual RTL mutation controls | All four produced `bad=1` counterexamples | All four produced `bad=1` counterexamples |

The initial Scala 2.12 run completed all 322 tests: 320 passed and two failed
because that local process could not find the Verilator executable. Both
unchanged affected suites were rerun after prepending the verified tool-kit
path: all 25 tests passed, including the two previously failing tests. No source,
test assertion, matrix or proof assumption changed for that rerun. The initial
failing log/JUnit files and the successful rerun are retained separately; this
is not described as a single failure-free initial run. The resulting 322-test
inventory has no unresolved failures, errors or skips.

All 100 original RTL files (four candidates and 96 independent native
references), the manifests and the proof evidence match across independent A/B
generation and both Scala versions. Every recorded proof/simulation log and
all eight mutation VCDs were rechecked. The checker models and layout adapters
are not counted as hardware proof by themselves.

These are incremental local builds of the changed source using digest-verified
CI Scala compiler/plugin/classpath inputs, not clean SBT builds. The cached
runtime version banner still identifies its 58ec build; it is not used to
identify this modified source. Published file/tree fingerprints and the exact
source patch establish the current source identity. HDL execution used the
verified CI tool kit: Yosys 0.41 (`c1ad37779`), Icarus 11.0 and Verilator 4.228.
This is finite-matrix qualification, not universal parameter proof or complete
59i qualification. No sequential proof is claimed for this combinational
nested-result slice. The older registered-record and inherited 59b hardware
gates remain in the workflow and are not replaced by these results.

Native source preservation and the composed 59i/59g/59h exact-source reviews
passed locally and on the CI-created functional commit. Their mutation suites
rejected 65, 244 and 113 altered snapshots respectively. The inherited source
controls retained the 59c two-positive/20-negative and 59h two-positive/32-negative
checks. The nested checker passed five Python regression tests and its 106
matrix/mutation rejection controls.

The source-application run `34226105189` passed its hash, native and inherited
source-review gates, but its final bot push was refused because the bot could
not modify a workflow file. That failed publication run is not reported as a
passing workflow. The existing authorized repository connector then published
that exact already-checked commit without force or permission changes.

Fresh dedicated CI run `34226847838` started on the published functional commit.
Its results were not terminal when this qualification record was prepared;
clean build, complete inherited gates and final-head CI remain pending. The
published-head native-source guard passed. The new evidence retains
`complete_59i_join: false`, and the controlling 59i checkbox remains unchecked.

## Source-bound identifiers

- Reviewed patch SHA-256: `6ff93c71cb8f733a2b5dbf2f111489b237c1d99590c28df050e8fddc58ef5e97`.
- Nested checker SHA-256: `47a71fb4fae807321897f20fe129932b0884cc241aaf1769a13d98fb8dbeec43`.
- Manifest SHA-256: `5faeca19e6ee2f7741ba5bf31f04998c3bed7d032e2ee0cf5823e39936b43a05`.
- Both-lane evidence JSON SHA-256: `56c105a355a3477f6699c4b1286d2e34ece86b8b51b7dedd89e6d347c3207a39`.
- 59i reviewed-span contract SHA-256: `af633343f73d1e9d54cf68c05ff5e21a8d79c9738fc45af4ddd07f2086aab600`.
- CI source-audit artifact 10055883983 ZIP SHA-256: `621b2f8b4f8cd6f9a5a17591d22029a677dfc18de7573be152f2c8e2a578fa0f`.

## Remaining integration obligations

The latest integration-branch inherited-review changes are not merged into this
functional checkpoint. The overlapping 59g review adaptation needs explicit
reconciliation with the WA-07b compatibility audit; selecting either side
wholesale is not a reviewed solution. Widening composite stages, certified
composite captures and saturation, expanded registered composites, generated
child hierarchies, complete pairwise/end-to-end coverage and final review all
remain open before merge.

This record is documentation only. It does not change the twelve functional
source/test/workflow files identified above, their proof assumptions, the
parameter matrix, native-change permissions or the roadmap completion state.
