# Increment 59i nested mechanism qualification inventory

This inventory reconstructs the missing nested fixture/checker source from the
59i handoff. The handoff's earlier counts are historical reports, not results
for these reconstructed bytes. The Increment 59i checkbox remains open until
the assembled source passes every applicable gate on both Scala lanes.

The two artifact entry points are
`spinal.core.internals.TypedBalancedReductionNestedMechanismArtifacts` and
`spinal.core.internals.TypedBalancedReductionNestedSaturationWideningArtifacts`.
Each emits one manifest, six candidate hierarchy files and sixteen independent
native-reference files. Candidate profiles are the complete product of packed
and named-field interfaces with explicit legacy, declarations-only and signed
cast modes. Every candidate defaults to COUNT=1 and INNER=1; every other
case specializes the same emitted definition through explicit parameters.

The fixture record contains independently widening unsigned and signed sums
and products, a fixed-width unsigned field, and a symbolic nested Vec of sample
bits. Sums read captured runtime bias/offset inputs at **every native operator
level**. MODE selects which independent bias and offset ports are captured.
INNER is an immutable typed capture; reconstructed field widths come from
native `ElabInt.widthOf` evidence rather than concrete width witnesses.
The first profile XORs the fixed field; the second uses ordinary native UInt
`+|` saturation in the same shape-changing callback. The native register bridge
clones and initializes the complete current shape. It retains synchronous
active-high reset, an active-high clock enable, enabled-reset precedence and
native odd-tail registration. COUNT=1 has no added stage or callback.

The generated parent binds all six child formals atomically using the public
typed constructor: U_W, S_W, TAG_W, INNER, COUNT and the positive encoding
`MODE + 1`. The child recovers logical MODE through typed subtraction. Both
logical MODE values are qualified. The parent wires native Data and ports;
it contains no alternate arithmetic or reduction tree.

The nested sample output uses a whole typed Vec assignment in both child and
parent. Its scalar-element Vec publishes the packed `resultSamples` port with
width TAG_W × INNER in both candidate layouts. This exercises certified
recursive whole-assignment transport; packed `asBits` reads of private
reduction-owned recursive Vecs remain unsupported. The independent concrete
native reference retains its ordinary packed Bits output.

## Frozen shape inventory

Every row below is run for both MODE=0 and MODE=1, giving sixteen cases per
matrix. Each case checks all six layout/signedness profiles. This is bounded
matrix coverage, not a universal parameter proof.

| U_W | S_W | TAG_W | INNER | COUNT | Purpose |
|---:|---:|---:|---:|---:|---|
| 1 | 1 | 1 | 1 | 1 | Minimum singleton |
| 3 | 2 | 4 | 1 | 1 | Unequal fields with singleton latency |
| 1 | 4 | 2 | 3 | 2 | Independent sign width and three inner entries |
| 4 | 1 | 3 | 1 | 3 | Unsigned maximum width and odd tail |
| 2 | 3 | 1 | 2 | 4 | Full balanced tree with unequal fields |
| 3 | 2 | 4 | 3 | 5 | Two odd-tail levels and all inner entries |
| 4 | 4 | 4 | 3 | 5 | Maximum declared width/count/inner bounds |
| 2 | 2 | 2 | 2 | 5 | Equal witnesses retain distinct field identities |

The hardware checkers fail if a profile/case is missing, duplicated, renamed
to overlap another artifact, assigned an invalid identifier, or supplied with
non-integer parameter metadata. Independently generated A/B manifests and all
original RTL files must be byte-identical. Focused `--case` development runs
delete any prior evidence file and never emit full-matrix evidence.
The checker also parses both generated module headers: signed offsets and
signed sum/product outputs must retain unsigned declarations in legacy mode
and signed declarations in the declarations/casts modes. UInt and Bits scalar
ports must remain unsigned. Profile metadata alone does not establish this
contract; internal native signed carriers and necessary casts remain allowed.
Within each layout, legacy and declarations-only must retain the same nonzero
native signed-cast count, and cast cleanup must reduce that count. This permits
necessary casts while detecting profiles that silently select the same mode.

## Fifteen pair joins

The symbols below distinguish mechanisms rather than test-suite names:
F = named field vectors; N = recursive composites/inner Vec; W = widening;
C = captured runtime operands; R = registered bridges; H = nested typed
owners and generated child hierarchy. Both new matrices exercise all six in
one native reduction. These are fixture obligations; only checker evidence
from passing assembled source constitutes qualification.

| Pair | Concrete boundary exercised |
|---|---|
| F–N | Per-field packing of outer COUNT and inner INNER dimensions |
| F–W | Independent input field widths and native terminal sum/product widths |
| F–C | Runtime bias/offset ports retained across field interfaces |
| F–R | Full record state crosses the native register bridge |
| F–H | Named-field Vec ports wired through an explicitly bound generated child |
| N–W | Nested Vec remains in the record as four other leaves change widths |
| N–C | Recursive record shares the certified callback with captured operands |
| N–R | All inner entries initialize, stall and advance with the record |
| N–H | Independent INNER binding remains owned by the generated child |
| W–C | Current-cycle captures participate at every active widening level |
| W–R | Per-stage result widths and sign extension survive registration |
| W–H | Widening reductions occur inside the child's MODE generate alternatives |
| C–R | Capture inputs can change while preceding register levels hold state |
| C–H | MODE+1 binding selects the child's exact capture identities |
| R–H | Reset, enable and pipeline outputs cross the real child boundary |

Whole-record comparison/selection and cross-field fixed-width arithmetic also
remain covered by the existing combined, nested-result and composite-capture
matrices. These new fixtures do not make a broader claim about shape-changing
cross-field arithmetic, arbitrary callback loops, alternative clock domains or
unbounded nested shapes. Existing rejection contracts remain applicable.

## Independent oracles and required hardware evidence

`nativeapplication.BalancedNestedMechanismNativeOracle` uses ordinary Int
geometry, its own record type, its own callback and the authoritative native
balanced reduction through ordinary SpinalVerilog. It imports no Morph
candidate type, callback, typed parameter or publisher. Its input splitting is
wiring only. Native inner entries use the underlying concrete `.vec` storage.

The Python adapter similarly contains only declarations, slices, concatenation
and port bindings. An independent integer model computes sums/products and
saturation while retaining the native old-state, enabled-register topology.
It explicitly applies the current bias/offset at each active level, including
cycles with changing captures, enable stalls and in-flight resets. Directed
vectors toggle every input bit independently across both Vec dimensions;
additional deterministic vectors include sign extrema and carry-producing
values. Both the generated candidate and native reference must match this
model before and after active clock edges.

For each case the checker requires strict Verilog-2001 lint, synthesis with
structural checks, simulation, arbitrary-initial-state reset-entry proof and
unbounded sequential equivalence induction from the explicitly established
zero state. No timeout or tool failure counts as proof.

After positive qualification, ten controls mutate actual emitted child RTL
under each of the six layout/signedness profiles: capture binding, MODE
binding, equal-width field binding, inner index binding, lost odd tail, lost
carry bit, lost sign bit, bypassed latency, ignored enable, and reset overriding
a stalled enable. Each of the sixty mutation variants must compile, produce a
software/native simulation mismatch, and yield a real formal counterexample
whose VCD exhibits `bad=1`. A missing mutation anchor fails the gate.

Run each artifact writer twice into distinct directories, then run:

```sh
python3 morphhdl/scripts/check-increment-59i-nested-mechanisms.py OUT_A OUT_B --jobs 2
python3 morphhdl/scripts/check-increment-59i-nested-saturation-widening.py SAT_A SAT_B --jobs 2
```

Each checker writes `evidence.json` only after its complete matrix and every
mutation passes. It records actual per-case cycle counts, reset/induction
results, mutation outcomes, the checker/manifest hashes and every original RTL
hash. The evidence intentionally retains `complete_59i_join: false`; inherited
59b–59h, existing 59i slices, source review, both Scala lanes and final-head
workflow gates remain separate completion requirements.
