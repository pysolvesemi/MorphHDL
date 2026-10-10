# Increment 59i — Composite runtime captures

**Status: implemented development slice; full Increment 59i remains unchecked.**
This extends the existing fixed-shape composite reduction path. It does not
complete widening stages, saturation, composite register-local enables,
generated child hierarchies or the complete pairwise/end-to-end join.

## Added behavior

An unchanged `Vec[Bundle].reduceBalancedTree` callback can read explicitly
captured scalar `Bool`, `Bits`, `UInt` and `SInt` hardware, call audited pure
helpers, and combine different record fields. Each field retains its existing
symbolic width. Runtime inputs remain runtime inputs at every tree stage; they
are not sampled into Scala values or substituted by the elaboration default.

The pre-execution callback interpreter carries read-only/fresh permissions
through exact field accessors, locals, native casts and helper arguments.
Only freshly cloned result fields may be assigned. Operand/capture writes,
host state, unknown methods and unproved initialization remain rejected before
execution. A custom record method named like native `:=` is not a native
assignment: the application body cannot gain permission from its name.

The graph proof gives captures separate identity-bound operand slots. It
revalidates bindings and value evidence before replay and native handoff.
A capture that aliases a pair operand leaf is rejected rather than silently
being treated as a different tree lane. Aggregate captures remain unsupported;
scalar hardware locals must be captured instead of the surrounding Component.

A computed capture defined in the same typed generate owner is an external
input to each replay template, not a private declaration owned by all of them.
The structural extractor admits this boundary only from the validated capture
schema. It preserves existing scope, overlap, declaration and driver checks;
a capture from an incompatible sibling owner still fails publication.

Proved symbolic intermediate carriers remain protected through native
normalization, matching the scalar replay rule. Otherwise native inlining
could recreate an intermediate at its construction width. Operand/capture
reads do not rewrite their width metadata. No native core/lib source,
reduction algorithm or register process changes in this slice.

## Actual Scala and generated Verilog

The checked fixture calls the ordinary reduction from two alternative typed
owners. Its helper is a field-free Scala object whose body is inspected:

```scala
def combine(a: BalancedCompositeRecord, b: BalancedCompositeRecord,
    biasA: UInt, biasB: UInt, mask: Bits, choose: Bool): BalancedCompositeRecord = {
  val result = cloneOf(a)
  result.key := Mux(choose, a.key min b.key, a.key max b.key)
  result.tag := (a.tag ^ b.tag) ^ mask
  result.x := (a.x + b.y) ^ biasA
  result.y := (a.y - b.x) ^ biasB
  result
}

// Explicit scalar locals avoid capturing the enclosing Component.
val p = biasA; val q = biasB; val m = mask; val chooseMin = choose
result := records.reduceBalancedTree((a: BalancedCompositeRecord, b: BalancedCompositeRecord) =>
  CompositeCaptureHelpers.combine(a, b, p, q, m, chooseMin))
```

The opposite typed owner swaps the pair arguments and captures `!choose`.
A separate signed-record reduction combines real/imaginary fields with a
captured signed offset. These noncommutative callbacks preserve native pair
order, singleton bypass and odd-tail behavior.

Exact excerpts from the generated `CompositeCapture_fields_casts.v` (omitted
surrounding statements are not replaced by a hand-written RTL model):

```verilog
  input wire [(WIDTH * COUNT)-1:0] records_key,
  input wire [(TAG * COUNT)-1:0] records_tag,
  input wire [(COORD * COUNT)-1:0] records_x,
  input wire [(COORD * COUNT)-1:0] records_y,
  input  wire [COORD-1:0]    biasA,
  input  wire [COORD-1:0]    biasB,
  input  wire [TAG-1:0]    mask,
  input  wire          choose,
  input  wire signed [WIDTH-1:0] offset,
```

```verilog
assign _zz_morphhdl_balanced_1_l0_pair_result_leaf_1_1 = (morphhdl_balanced_1_l0_pair_left_leaf_1 ^ morphhdl_balanced_1_l0_pair_right_leaf_1);
assign _zz_morphhdl_balanced_1_l0_pair_result_leaf_1_2 = (_zz_morphhdl_balanced_1_l0_pair_result_leaf_1_1 ^ mask);
assign _zz_morphhdl_balanced_1_l0_pair_result_leaf_2_1 = (morphhdl_balanced_1_l0_pair_left_leaf_2 + morphhdl_balanced_1_l0_pair_right_leaf_3);
assign _zz_morphhdl_balanced_1_l0_pair_result_leaf_2_2 = (_zz_morphhdl_balanced_1_l0_pair_result_leaf_2_1 ^ biasA);
```

Native generated names are retained here, including `_zz`; this increment does
not claim a naming cleanup. The default interface and signedness modes remain
unchanged; named-field vectors and signed cleanup are explicit options.

## Executed local qualification

The implementation was compiled incrementally with the matching Scala compiler
and both compiler plugins ahead of unchanged classes from digest-verified CI
build kits at `d442a867`. Production sources at the starting `80a3b097` match
that kit source; the intervening integration changes affect review/workflow
infrastructure. The cached runtime version banner is not used to identify the
modified source. These results are not a clean exact-head SBT/CI build.

| Gate | Scala 2.12.18 | Scala 2.13.12 |
| --- | --- | --- |
| Changed production/test compilation | Passed | Passed |
| Focused permission/capture/policy tests | 36 tests, three suites passed | 36 tests, three suites passed |
| Capture hardware matrix | 96 cases, all six profiles | 96 cases, all six profiles |
| Strict Verilog-2001 lint, parse and synthesis | All cases passed | All cases passed |
| Native/software simulation | 61,188 vectors | 61,188 vectors |
| Combinational SAT equivalence | 96 proofs | 96 proofs |
| Real RTL mutation controls | Five `bad=1` counterexamples | Five `bad=1` counterexamples |

The matrix covers WIDTH `{1,5,8,32}` and COUNT `{1,2,3,5,8,9,16,17}` in both
typed owner choices, plus four independently overridden unequal field-width
shapes at singleton and odd counts. Every case uses packed/named layouts and
legacy/signed-declaration/cast-cleanup modes. All 102 original candidate/native
RTL files, manifests and evidence records match across independent A/B
generation and both Scala versions. The original 59i matrices are retained.

The independent native references define their own concrete record types and
inline callbacks and do not call candidate helpers. Named/packed adapters only
permute wires. The software model is additionally checked against a recursive
integer reference. Stimuli include every input bit, extrema, deterministic
random values, and capture-only changes while all lane values remain fixed.
Synthesis retains every implementation output, so a constant-zero equivalence
result cannot allow the actual datapaths to disappear before mapping.

Mutations swap the two bias inputs, freeze the mask, freeze a bias, discard an
offset bit, or freeze the selector. Each edits actual candidate RTL and must
yield a solver counterexample with `bad=1`, not a timeout or missing anchor.
This combinational slice adds no sequential proof and is finite-matrix
qualification, not a proof for all possible parameter values.

Eight checker regression tests and 107 matrix/anchor rejection controls passed.
The new source-view controls additionally check exact span reversal and reject
69 changed-span, prefix/suffix or stale-source mutations. Original review
contracts remain byte-identical. A separately sealed capture-sidecar records
five production changes and two inherited-review adapters; it restores their
exact predecessor bytes before the existing 59i/WA-07b/59g/59h audits run.
The inherited mutation test keeps every action and exact rejection check, but
recognizes that a newly reviewed file now fails the successor span check first.

The first local tool run failed because the relocated Yosys data and ABC paths
were unavailable. Restoring those verified tool inputs resolved that environment
failure without changing tools, warnings or proof assumptions. Initial logs are
retained separately from the successful full reruns. An initial concurrent
Scala 2.12 broad-regression process stopped before producing a final report;
that partial execution is not counted as a passing broad gate.

Source identifiers for the completed hardware runs:

- Checker SHA-256: `fc7dac59009f3f2593509898de9d22be6f6231f845abec3a99dfdd6fdbf9f4fe`.
- Manifest SHA-256: `c2cefebfc6d9653ac5be0a95b578a2973574f4ea4e7e6f2589a4dd3b83616752`.
- Both-lane evidence SHA-256: `e04da0a49e65f0424c86c4a0a0da4127d4a6ff33952d506619e261cca900f460`.
- Capture source-review SHA-256: `dc0b3c53221d5d36061870e31c5a63c57ac2ed747ae98a256a209f2cfd898537`.

## Remaining gates

The full 29-suite/332-test inherited regression inventory, clean final-source
Scala builds, existing registered/nested/inherited-59b matrices, source reviews
and cross-Scala gates remain mandatory in CI. The new capture matrix supplements
those gates and retains `complete_59i_join: false`. Widening/full-partial-tail
width provenance, saturation, register-local enables, expanded reset profiles,
generated hierarchies and complete mechanism-pair/end-to-end closure remain
open. The controlling roadmap checkbox is not changed by this document.
