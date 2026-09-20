# Increment 59i — Scoped nested-Vec result publication

Status: development slice; the full 59i join is still incomplete and unchecked.
This extends the newer branch source at `b8e26eba079a9e65e7cc1b80f815504e135c914f`.
It does not apply the older saved patch over the bounded static-read wrapper or
the declaration-filter repair already on the branch.

## What changed

A whole-record reduction can now publish independently counted inner Vecs from
alternative typed structural owners. Previously, the Vec publisher searched for
one continuous assignment to each public leaf. Two valid branch-local reduction
results produced two native assignments to that leaf; those assignments were
also isolated native combinational processes rather than continuous assignments.

The publisher now starts with the retained native source/target identities and
complete assignment collection. It finds the exact source leaf, requires an
already-published reduction result in the exact retained lexical owner, and
admits only an isolated full blocking combinational assignment. It replaces the
public leaf target with its typed packed/field slice while preserving the native
process. Inactive inner lanes receive local parameter guards, not nested
`generate/endgenerate` wrappers or runtime datapath guards.

The new permission cannot be acquired by an application Vec, replay operand,
unpublished result, unknown owner, clocked/conditional/multi-statement process,
partial target or nonblocking assignment. The native callbacks, record shapes,
reduction algorithm and ordinary concrete SpinalVerilog implementation are not
replaced. No new native `core` or `lib` change is introduced by this slice.

## Actual source and generated output

`TypedBalancedReductionNestedResultHardware.scala` uses the existing recursive
record (key, tag, samples containing UInt/SInt/Bits/Bool leaves, and a Bool grid):

```scala
val records = in(Vec(BalancedCompositeCountedRecord(uw, sw, uw, tw, inner,
  HdlInt.literal(1), HdlInt.literal(1)), count)).setName("records")
val selected = out(BalancedCompositeCountedRecord(uw, sw, uw, tw, inner,
  HdlInt.literal(1), HdlInt.literal(1))).setName("selected")
(mode > HdlInt.literal(0)).generateIf("g_max", "g_min") {
  selected := records.reduceBalancedTree((a: BalancedCompositeCountedRecord,
    b: BalancedCompositeCountedRecord) => Mux(a.key >= b.key, a, b))
}.otherwise {
  selected := records.reduceBalancedTree((a: BalancedCompositeCountedRecord,
    b: BalancedCompositeCountedRecord) => Mux(a.key <= b.key, a, b))
}
```

Actual generated named-field header excerpt (no source rewriting is required):

```verilog
input wire [(U_W * COUNT * INNER)-1:0] records_samples_unsigned,
input wire [(S_W * COUNT * INNER)-1:0] records_samples_signed,
input wire [(U_W * COUNT * INNER)-1:0] records_samples_bitsValue,
input wire [(COUNT * INNER)-1:0] records_samples_valid,
output reg [(U_W * INNER)-1:0] selected_samples_unsigned,
output reg [(S_W * INNER)-1:0] selected_samples_signed,
output reg [(U_W * INNER)-1:0] selected_samples_bitsValue,
output reg [(INNER)-1:0] selected_samples_valid,
```

The generated native process for inner lane one, inside `g_max`, is preserved
with a compile-time guard (indentation omitted here):

```verilog
if ((1 < (INNER))) begin
always @(*) begin
  selected_samples_unsigned[(U_W) +: U_W] = morphhdl_balanced_1_result_leaf_6;
end
end
```

The legacy packed interface remains the default; named-field publication and
signed-cast cleanup remain explicit options. The exact generated files, not
these documentation excerpts, are the simulation and equivalence inputs.

## Bounded qualification contract

The new checker requires 96 parameter cases: the full WIDTH `{1,5,8,32}` by
COUNT `{1,2,3,5,8,9,16,17}` by two selection modes with INNER=2, plus independent
unsigned/signed/tag width shapes at INNER `{1,3}` and singleton/odd outer counts.
Four profiles cover packed/field layouts with legacy/default COUNT=1, INNER=1,
and signed-cast cleanup/default COUNT=5, INNER=3. This is not a claim that every
legal parameter tuple or every signedness/default combination has been proved.

Independent concrete native record types and callbacks supply the reference.
Boundary adapters contain only fixed slices, wires and concatenation. A separate
flat, stable software selector checks every record bit, including signed samples,
valid bits, tags, ties, singleton behavior and odd tails. Strict Verilog-2001
lint/parse, synthesis, simulation and combinational SAT are required for every
case/profile. Actual RTL mutations cover wrong owner-branch binding, field swap,
inner-index swap and signed-bit loss; each must yield a genuine `bad=1` trace.

The source audit adds two explicit reversible spans without changing any prior
reviewed before/after text: exact nested-result owner evidence and the bounded
native-process publication change. Tests reject altered ownership/process
checks. Existing native, 59i, 59g and 59h source audits remain required.

At this development checkpoint both Scala versions compiled the changed source,
and all 100 original candidate/reference files matched across independent A/B
and cross-Scala generation. The 96-case hardware runs and inherited regression
runs are separate qualification evidence; no completion or final-head CI pass
is implied by this source publication. `complete_59i_join` remains false.

## Still outside this slice

Changing composite stage widths, composite captures and saturation, expanded
registered-composite profiles, generated child hierarchy and the complete
pairwise/end-to-end join remain separate open 59i work. Integration with the
latest `parameterized-verilog` inherited-review changes, complete final-head
CI/formal/tool gates and final review are required before merging the increment.
