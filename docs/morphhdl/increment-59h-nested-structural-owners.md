# Increment 59h — Balanced reduction in nested typed owners

Status: the production implementation is merged through [PR #170](https://github.com/pysolvesemi/MorphHDL/pull/170),
but qualification closure remains in progress and the roadmap stays unchecked.
The implementation head is `9c8cbb0951aca28a89d522717e7a6bacfd67afe0`; its merge
commit is `cba4717abc9192917d819e1f84cb246162488286`. The implementation branch
started from merged base `0018da2740645e0ac0c419ded7b67c01622d2bb7`.

## Induction scalability repair — September 7, 2026

The expanded CI run [34109938793](https://github.com/pysolvesemi/MorphHDL/actions/runs/34109938793)
failed on both Scala lanes at `registered-loop_w32_n16_r3_m0`. The preceding
simulation and arbitrary-initial-state reset-entry check passed. The induction
log ended with `Called with -verify and proof did time out!` at the fifth
induction step, with 608,450 variables and 1,712,963 clauses. A failed short
induction hypothesis is not a reachable zero-state counterexample; the final
timeout was not accepted as proof or as successful qualification.

The repair applies generic Yosys preparation only to the existing zero-state
induction path:

```text
zinit -all
opt -full -keepdc
dffunmap
check -assert
```

`zinit -all` makes the already-required initial-state contract explicit before
register optimization, while preserving explicit nonzero initial values by
inversion. `opt -full -keepdc` can then normalize equivalent native clock-enable
and reset structures without dropping don't-care semantics. State is not
paired by emitted names, and no primary input is constrained. The separate
reset-entry check continues to use the original uninitialized miter setup.
The original SAT commands, 90-second solver timeout, maximum induction steps,
parameter matrix, native RTL, and reference/model bodies are unchanged.

Three additional mutations pass through this exact induction preparation:
ignored clock enable, wrong reset value, and explicit nonzero initial state.
Each must produce a real SAT `bad=1` counterexample VCD. They supplement, rather
than replace, the original wrong-branch, stale-index and cross-instance controls.
The existing 519 matrix rejection controls remain required. Self-tests also
reject missing sequential mutation anchors and guard separation of zero-state
induction from arbitrary-initial-state reset entry.

### Local hardware requalification

The repaired checker was run against the complete, unchanged A/B Verilog from
CI head `be728de13d2d6ae0b618ea01e61de911014258b3`, not handwritten replacement
RTL. Both failed-run archives were digest-checked, their source archive/head
identities matched, and all **521 original candidate/reference RTL files** and
manifests matched across A/B and Scala 2.12.18/2.13.12. Each archived Scala lane
had passed **388 safety tests in 30 suites**, with zero failures/errors/skips.

The complete local replay of the Scala 2.12.18-generated artifact passed:

| Gate | Result |
| --- | --- |
| Expanded native-reference matrix | 516 / 516 specializations |
| Independent simulation | 86,514 input vectors; 20,988 registered clock cycles |
| Combinational SAT equivalence | 408 / 408 |
| Registered reset entry and unbounded induction | 108 / 108, both checks |
| Genuine RTL mutation counterexamples | All three original and all three new registered controls |
| Inherited 59b publication matrix | 32 / 32, including original reset-entry/induction and both mutations |

The previously timing-out 32-bit, 16-element registered case now reaches
`Induction step proven: SUCCESS!` at length one without increasing the timeout.
The local tool binaries were recovered from a digest-verified CI tool kit:
Yosys 0.41 (`c1ad37779`), Icarus 11.0 and Verilator 4.228. All 21 existing checker
helper/model/miter/mutation functions remained byte-identical; only the new
induction preparation and supplementary controls alter the qualification path.

Source-bound local evidence identifiers:

- Repaired checker SHA-256: `d7f94b55930e645de3057ac78fe01e926f2e9c76b55d3e5fcd3b1d6fb1cdd41e`.
- Expanded manifest SHA-256: `74ee8b4bfb4ad7ed1c727cf05a7987d789c52c9db3c8e628b19058caa01fca08`.
- Repaired local evidence SHA-256: `f7bcdb4a6571464f20fc463979e768846aced6cfa2b2c8469d8fb1bc1a526e91`.
- Current expanded `BalancedNestedLoop.v` SHA-256: `e907a0c4e1072470c38ee75c73d75998d7f88b433ac6fdea823f07a6dd0f3b78`.

This is a local HDL-tool replay of CI-generated RTL, not a new clean Scala
build or a claim of passing fresh exact-head CI. The new dual-Scala dedicated
workflow, applicable inherited gates, final review and roadmap closeout remain
required. The 59h checkbox stays unchecked until that qualification completes.

## September 7 qualification review (matrix-expansion checkpoint)

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

The initial matrix-only follow-up did not change production algorithms, native source manifests,
independent native reference bodies, reset/enable assumptions, solver commands
or any existing hardware mutation. The later induction-preparation repair is
recorded above. Both Scala lanes must newly generate and qualify the complete
matrix before the roadmap checkbox can be closed. The historical results below
cannot qualify the expanded candidate domain.

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
remain required. Local expanded-matrix results are recorded above; fresh
exact-head CI qualification remains pending.

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
