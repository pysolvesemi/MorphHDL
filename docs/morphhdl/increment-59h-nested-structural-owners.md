# Increment 59h — Balanced reduction in nested typed owners

Status: the production implementation is merged through [PR #170](https://github.com/pysolvesemi/MorphHDL/pull/170),
but qualification closure remains in progress and the roadmap stays unchecked.
The implementation head is `9c8cbb0951aca28a89d522717e7a6bacfd67afe0`; its merge
commit is `cba4717abc9192917d819e1f84cb246162488286`. The implementation branch
started from merged base `0018da2740645e0ac0c419ded7b67c01622d2bb7`.

## September 7 qualification review

The merged-head dedicated workflow and full inherited regressions passed.
However, the dedicated matrix covered WIDTH `{1, 5, 8}` and COUNT
`{1, 2, 3, 5, 9}`, not the full scalar minimum required by the controlling
roadmap. Passing that subset is not sufficient to close Increment 59h.

The follow-up extends the candidate's declared finite domains to WIDTH 1–32 and
COUNT 1–17 while keeping the defaults WIDTH=5, COUNT=1, ROWS=1 and MODE=0.
It retains every original specialization and adds all 32 WIDTH/COUNT pairs:
WIDTH `{1, 5, 8, 32}` times COUNT `{1, 2, 3, 5, 8, 9, 16, 17}`, in each of
five owner profiles and all three branch modes. Existing extra row witnesses
remain present, for **516 specializations**: 96 conditional, 96 ordinary
hierarchy, and 108 each for finite loop, registered loop and generated hierarchy.
These counts specify required work; they are not a claim of passing new RTL.

The Python self-test now verifies the independent roadmap Cartesian product,
retention of the original 105 shapes, and rejection of every single missing
case, the old matrix, a duplicate and an out-of-domain count: **519 negative
matrix controls**. All self-tests passed locally. These parser/model controls
are not a substitute for Scala generation, hardware simulation or solver proof.

The follow-up does not change production algorithms, native source manifests,
independent native reference bodies, reset/enable assumptions, solver commands
or any existing hardware mutation. Both Scala lanes must newly generate and
qualify the complete matrix before the roadmap checkbox can be closed. The
historical results below cannot qualify the expanded candidate domain.

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
remain required. Final expanded-matrix results remain pending.

### Verified historical post-merge evidence

These results belong only to `cba4717abc9192917d819e1f84cb246162488286`, the
original narrower matrix. The retained ZIP digests, head files, manifests and
JUnit inventories were checked during the September 7 resume.

| Gate | Result on each Scala version (2.12.18 and 2.13.12) |
| --- | --- |
| Dedicated safety tests | 388 tests / 30 suites, zero failures/errors/skips |
| Nested-owner matrix | 105 specializations; 12,129 simulation vectors |
| Combinational equivalence | 84 SAT proofs |
| Registered equivalence | 21 reset-entry checks and 21 induction proofs |
| Genuine RTL mutations | All three produced bad=1 counterexamples |
| Inherited 59b matrix | 32 cases; 5,586 simulation cycles; both mutations detected |
| Complete inherited regressions | 1,854 tests / 182 suites, zero failures/errors/skips |

The dedicated source-bound run is [34094499276](https://github.com/pysolvesemi/MorphHDL/actions/runs/34094499276).
The full inherited regression and signedness closure run is
[34094499393](https://github.com/pysolvesemi/MorphHDL/actions/runs/34094499393).
All original 110 candidate/reference RTL files matched between independent A/B
writer invocations and across Scala versions; manifests and nested-owner
evidence JSON also matched. Proof-generated miters and mutated candidates are
not additional original writer outputs.

| Retained artifact | GitHub ID | SHA-256 |
| --- | --- | --- |
| Dedicated 2.12.18 | 10008880578 | `4045187e3c8765dfbfa19e1ded14db52f4f4eadcf8c32078f51e7e89c83bdf76` |
| Dedicated 2.13.12 | 10008665174 | `53ee22643b9c861c47dbba003f25c99d4133ddfccddf6a14f91f98906722eb26` |
| Full regressions 2.12.18 | 10009723909 | `125bfbb02dc5c0db0711ac75727ad70757d6e2f37ce33cdb5c7b7bebb7fc6aac` |
| Full regressions 2.13.12 | 10009627453 | `617192b7a8162858e8292b9db206d0874968633dc986b7c6764068d719f374dc` |

The dedicated artifacts retain `head.txt`, `source.tar.gz`, independent A/B
RTL, manifests, proof logs, counterexample VCDs and JUnit reports. Formal
coverage is a finite specialization matrix, not universal quantification over
all legal parameter values. The registered profile uses the existing native
synchronous active-high reset and clock-enable contract; 59g and 59i remain
responsible for their separately specified broader profiles and combinations.

## Actual Scala and generated-Verilog example

This is the unchanged `BalancedNestedLoop` fixture from
`morphhdl/src/test/scala/spinal/core/internals/TypedBalancedReductionNestedOwnerArtifactWriter.scala`:

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

The following two separate excerpts are actual generated Verilog from the
historical merged-head artifact `a/candidate/loop/BalancedNestedLoop.v`.
Declarations and later tree levels between the excerpts are omitted; this is
not a standalone replacement module or evidence for the expanded domain.

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
```

```verilog
    for (row_index_1_1 = 0; row_index_1_1 < ROWS; row_index_1_1 = row_index_1_1 + 1) begin : g_row_1_1
        wire [(WIDTH * COUNT)-1:0] row_words;
      for (word_index_2_1 = 0; word_index_2_1 < COUNT; word_index_2_1 = word_index_2_1 + 1) begin : g_word_2_1
          assign row_words[((word_index_2_1) * WIDTH) +: WIDTH] = (words[((word_index_2_1) * WIDTH) +: WIDTH] ^ biases[((row_index_1_1) * WIDTH) +: WIDTH]);
      end
      if (((MODE) > (0))) begin : g_row_xor
```

The historical complete file SHA-256 is
`d9bea85f9282df51fbc5e51f12c49487c0a9142e683f55c39f8d8084ac422103`.
The native balanced tree is published within the exact generated row and
branch, not hoisted to component scope or frozen to the COUNT=1 default.
