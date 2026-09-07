# Increment 59h — Balanced reduction in nested typed owners

Status: implementation complete and merged through [PR #170](https://github.com/pysolvesemi/MorphHDL/pull/170).
The implementation head is `9c8cbb0951aca28a89d522717e7a6bacfd67afe0`; the
qualified integration commit is `cba4717abc9192917d819e1f84cb246162488286`.
Post-merge qualification was verified on September 7, 2026. This completion
record and the controlling roadmap closeout change documentation only; they do
not modify production code, tests, workflows, native manifests or proof oracles.
The implementation branch started from merged `parameterized-verilog` commit
`0018da2740645e0ac0c419ded7b67c01622d2bb7`.

## Scope

The native `Vec.reduceBalancedTree` algorithm remains authoritative. The
extension places its certified scalar templates and balanced transport in the
exact typed generate-if, generate-case or finite generate-for owner where the
helper was called. Ordinary child components retain their own module scope,
canonical parameter bindings and native clock/reset behavior.

The standalone qualification uses the existing scalar operations and zero-init
register bridges from 59b. Named-field, widening, composite-reduction and nested
aggregate-alias combinations remain the 59i integration scope. Unsupported
packed operations on a nested structural Vec aggregate alias remain rejected.
The wider register initialization, enable and clock/reset profiles belong to
59g; completion of 59h does not claim their combined qualification.

## Ownership and publication

- Lexical handles retain exact component and capture identities independently
  of the reduction count's parameter root.
- Registered region identities and their enclosing capture identities reject
  replaced branches, sibling migration, detached captures and stale retries.
- Exact case capture retains the selector and admitted root values for each
  block. Registration checks the literal/default pairing. Later freshness
  checks re-enter only the immutable domains retained by the original owner.
- A narrowed count uses an identity-preserving prefix of the original native
  carrier. The complete Vec and all original leaf evidence remain retained;
  no callback runs for carriers excluded by the branch's admitted count.
- Before native normalization, anchors and replay templates must belong to the
  exact direct owner. A reduction result cannot be consumed outside that owner
  or its descendants.
- Existing structural driver, branch-reference and finite-index validation
  runs over the full native graph. The publisher then replaces the certified
  templates inside that exact block, after index substitution. Each reduction
  must be consumed exactly once.
- Native expression and register emission remains authoritative. The new
  publisher emits only balanced transport and structural control.
- After a scoped tree replaces its checked synthetic zero drivers, expression
  publication recognizes those exact private anchors as already consumed.
  User-authored zero assignments retain the existing lineage checks.
- A retained scalar static-Vec-index assignment wrapper is admitted only when
  its complete wrapper chain proves the exact native scalar and Vec identities.
  Opaque assignment hooks still reject before executing callback code.

## Qualification

`TypedBalancedReductionNestedOwnerArtifactWriter` generates five candidate
profiles and independent concrete native references: nested conditional,
finite loop, ordinary hierarchy, registered loop and generated hierarchy.
The loop profiles use scalar Vec inputs plus independent row biases and nested
finite reads, without a handwritten candidate datapath.

`check-increment-59h-nested-owners.py` checks strict Verilog-2001, independent
simulation, synthesis, native-reference equivalence, reset entry and induction
for registered cases, and wrong-branch, stale-index and cross-instance RTL
mutations. The inherited 59b qualifications and both supported Scala lanes
remain required.

### Source-bound post-merge results

All results below belong to integration commit
`cba4717abc9192917d819e1f84cb246162488286`, not an earlier local checkpoint.
The retained archives were downloaded, their SHA-256 digests matched against
GitHub artifact metadata, and their recorded head identities and JUnit reports
were checked during closeout.

| Gate | Scala 2.12.18 | Scala 2.13.12 |
| --- | --- | --- |
| Dedicated nested-owner and inherited safety suites | 388 tests / 30 suites; no failures, errors or skips | 388 tests / 30 suites; no failures, errors or skips |
| Independent nested-owner hardware matrix | 105 / 105 specializations | 105 / 105 specializations |
| Independent simulation | 12,129 input vectors | 12,129 input vectors |
| Combinational SAT equivalence | 84 / 84 cases | 84 / 84 cases |
| Registered reset entry and induction | 21 / 21 cases, both checks | 21 / 21 cases, both checks |
| Genuine nested-owner RTL mutation counterexamples | All 3 detected | All 3 detected |
| Inherited 59b publication matrix | 32 / 32 cases; 5,586 simulation cycles; reset entry and induction; both mutations detected | Same |
| Complete formal-enabled inherited regressions | 1,854 tests / 182 suites; no failures, errors or skips | 1,854 tests / 182 suites; no failures, errors or skips |

The dedicated workflow is [post-merge 59h run 34094499276](https://github.com/pysolvesemi/MorphHDL/actions/runs/34094499276).
Both jobs completed successfully, including the source-preservation and
historical mutation controls, strict Verilog-2001 parsing/lint, synthesis,
formal qualification and inherited 59b proof step. The full regression lanes
and signedness cross-Scala comparison passed in
[post-merge closure run 34094499393](https://github.com/pysolvesemi/MorphHDL/actions/runs/34094499393).
The repository-wide merge-commit snapshot contained 45 workflow runs: 39
successful and six skipped, with no failed or unfinished runs. Skipped runs
are not counted as proof; both dedicated 59h lanes and both complete inherited
regression lanes actually ran and passed.

The 105-case matrix comprises 21 specializations for each of five profiles.
The three negative controls corrupt branch binding, a finite-loop index and
cross-instance result wiring. Every packed Vec element, row bias, child-instance
input and sequential control remains independently driven or unconstrained as
appropriate; the native reference is elaborated separately.

All 110 originally generated candidate/reference Verilog files match between
independent A/B writer invocations. The same 110 files, both manifests and the
nested-owner evidence JSON also match byte-for-byte between Scala versions.
Tool-generated miters and mutated candidates under the A directory are not
mistaken for additional original writer outputs.

These are finite parameter specializations, not universal quantification over
every legal parameter value. The registered profile retains the existing native
synchronous active-high reset and clock-enable contract. Broader 59g profiles
and the mixed 59c–59h feature combinations remain separate qualification work.

### Retained artifact identities

| Artifact | GitHub artifact ID | SHA-256 |
| --- | --- | --- |
| Dedicated 2.12.18 | 10008880578 | `4045187e3c8765dfbfa19e1ded14db52f4f4eadcf8c32078f51e7e89c83bdf76` |
| Dedicated 2.13.12 | 10008665174 | `53ee22643b9c861c47dbba003f25c99d4133ddfccddf6a14f91f98906722eb26` |
| Full regressions 2.12.18 | 10009723909 | `125bfbb02dc5c0db0711ac75727ad70757d6e2f37ce33cdb5c7b7bebb7fc6aac` |
| Full regressions 2.13.12 | 10009627453 | `617192b7a8162858e8292b9db206d0874968633dc986b7c6764068d719f374dc` |

The dedicated artifacts contain `target/increment-59h-nested-owners/head.txt`,
`source.tar.gz`, `a/manifest.json`, `a/evidence.json`, generated A/B RTL,
independent proof logs, mutation witnesses and the JUnit reports. The source
archive and writer make the evidence reproducible after CI artifact expiry.

## Scala source and actual generated Verilog

This example is the actual `BalancedNestedLoop` fixture in
`morphhdl/src/test/scala/spinal/core/internals/TypedBalancedReductionNestedOwnerArtifactWriter.scala`.
With the imports from that file, the source is:

```scala
final class BalancedNestedLoop(width: HdlInt, count: HdlInt, rows: HdlInt, mode: HdlInt)
    extends Component {
  setDefinitionName("BalancedNestedLoop")
  val words = in(Vec(UInt(width bits), count)).setName("words")
  val biases = in(Vec(UInt(width bits), rows)).setName("biases")
  val result = out(Vec(UInt(width bits), rows)).setName("result")
  ElabFiniteRange.foreach(rows.asElabInt, "row") { row =>
    val local = Vec(UInt(width bits), count).setName("row_words")
    ElabFiniteRange.foreach(count.asElabInt, "word") { lane =>
      lane(local) := lane(words) ^ row(biases)
    }
    (mode > HdlInt.literal(0)).generateIf("g_row_xor", "g_row_add") {
      row(result) := local.reduceBalancedTree((a: UInt, b: UInt) => a ^ b)
    }.otherwise {
      row(result) := local.reduceBalancedTree((a: UInt, b: UInt) => a + b)
    }
  }
}
```

The writer uses defaults `WIDTH=5`, `COUNT=1`, `ROWS=1`, `MODE=0` while retaining
those parameters in one generated definition. The following are two separate,
exact excerpts from
`a/candidate/loop/BalancedNestedLoop.v`; omitted declarations and later tree
levels remain in the complete artifact. These excerpts are not a standalone
replacement module.

```verilog
module BalancedNestedLoop #(
  parameter integer COUNT = 1,
  parameter integer MODE = 0,
  parameter integer ROWS = 1,
  parameter integer WIDTH = 5
) (
  input wire [(WIDTH * COUNT)-1:0] words,
  input wire [(WIDTH * ROWS)-1:0] biases,
  output wire [(WIDTH * ROWS)-1:0] result
);




  genvar row_index_1_1;
  genvar word_index_2_1;
  generate
    for (row_index_1_1 = 0; row_index_1_1 < ROWS; row_index_1_1 = row_index_1_1 + 1) begin : g_row_1_1
        wire [(WIDTH * COUNT)-1:0] row_words;
      for (word_index_2_1 = 0; word_index_2_1 < COUNT; word_index_2_1 = word_index_2_1 + 1) begin : g_word_2_1
          assign row_words[((word_index_2_1) * WIDTH) +: WIDTH] = (words[((word_index_2_1) * WIDTH) +: WIDTH] ^ biases[((row_index_1_1) * WIDTH) +: WIDTH]);
      end
      if (((MODE) > (0))) begin : g_row_xor
```

Within that exact row and branch owner, the first active tree level is emitted
as follows:

```verilog
            if (((COUNT) > (1))) begin : morphhdl_balanced_1_active_0
              for (morphhdl_balanced_1_i_0 = 0; morphhdl_balanced_1_i_0 < ((COUNT / 2)); morphhdl_balanced_1_i_0 = morphhdl_balanced_1_i_0 + 1) begin : pairs
                wire       [WIDTH-1:0]    morphhdl_balanced_1_l0_pair_left;
                  wire       [WIDTH-1:0]    morphhdl_balanced_1_l0_pair_right;
                  wire       [WIDTH-1:0]    _zz_morphhdl_balanced_1_l0_pair_result;
                  wire       [WIDTH-1:0]    morphhdl_balanced_1_l0_pair_result;
                  assign morphhdl_balanced_1_l0_pair_left = morphhdl_balanced_1_stage_0[((2 * morphhdl_balanced_1_i_0) * (WIDTH)) +: (WIDTH)];
                  assign morphhdl_balanced_1_l0_pair_right = morphhdl_balanced_1_stage_0[((2 * morphhdl_balanced_1_i_0 + 1) * (WIDTH)) +: (WIDTH)];
                  assign _zz_morphhdl_balanced_1_l0_pair_result = (morphhdl_balanced_1_l0_pair_left ^ morphhdl_balanced_1_l0_pair_right);
                  assign morphhdl_balanced_1_l0_pair_result = _zz_morphhdl_balanced_1_l0_pair_result;
                assign morphhdl_balanced_1_stage_1[((morphhdl_balanced_1_i_0) * (WIDTH)) +: (WIDTH)] = morphhdl_balanced_1_l0_pair_result;
              end
```

The complete generated file has SHA-256
`d9bea85f9282df51fbc5e51f12c49487c0a9142e683f55c39f8d8084ac422103`.
The shared nested-owner `a/evidence.json` has SHA-256
`7aba4d8bc6dd3e9d1c23beface1fc1bb74e050f93e721bfc8be5545b18bb8f41`.
The essential 59h change is that the native reduction remains inside its
parameterized row and selected branch; it is not moved to component scope or
specialized to the default count. COUNT=1 retains the native bypass behavior.

## Next integration boundary

The first remaining sequential roadmap item is 59g. Increment 59i remains
blocked until all of 59c–59h, including 59g, are implemented and merged. This
closeout does not start 59i or change the approved standalone/combined scope.
