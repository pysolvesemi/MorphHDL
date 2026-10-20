# Increment 59i — Combined Vec/reduction closure

**Qualification status:** the controlling item is in
`parameterized-verilog-todo.md`. Completion requires the source-bound results in
`increment-59i-final-qualification.md`; that record is published only after the
required gates pass. This document defines the implementation and qualification
obligations, and records the reconstruction history.

## First implementation slice

`TypedBalancedReductionBackend` now routes composite templates through the
same exact lexical-owner handoff used by scalar templates. Nested publication
keeps declarations inside that owner, avoids nested `generate` regions, and
reserves user structural labels before allocating transport names. Native
operator graphs, registers, reset/enable behavior, record layout and result
escape checks remain authoritative. No component name selects production logic.

The new fixture uses unchanged `Vec[Bundle].reduceBalancedTree` calls for stable
unsigned/signed whole-record min/max selection and registered unsigned records
inside nested typed case/if scopes. It exercises independently parameterized
key, tag and coordinate widths. The reference has separate concrete native
record types and callbacks. Packed-to-named adapters only select and concatenate
wires; they do not implement the reduction or register behavior.

Actual candidate source excerpt:

```scala
val records = in(Vec(BalancedCompositeRecord(width, tagWidth, coordWidth), count))
val selected = out(BalancedCompositeRecord(width, tagWidth, coordWidth))

(count > HdlInt.literal(1)).generateIf("g_tree", "g_singleton") {
  selected := records.reduceBalancedTree(
    (a: BalancedCompositeRecord, b: BalancedCompositeRecord) =>
      Mux(a.key <= b.key, a, b))
}.otherwise {
  selected := records(0)
}
```

Generated Verilog is qualified by fresh Scala generation and HDL-tool results
for the reviewed source, with the exact emitted files retained as evidence.

## Qualification authored for this slice

| Dimension | Required coverage |
| --- | --- |
| Scalar minimum | WIDTH `{1,5,8,32}` × COUNT `{1,2,3,5,8,9,16,17}` × both selection modes |
| Independent fields | Four unequal key/tag/coordinate shapes, singleton and odd counts |
| Publication modes | Legacy packed and named fields; legacy, signed declarations and cast cleanup |
| Elaboration defaults | COUNT defaults of both 1 and 5, independently of overrides |
| Total | 96 specializations × 12 publication/default profiles |
| Native behavior | Stable ties, signed keys, record association, odd tails, register latency, stalls and reset during stalls |
| Positive tools | Strict Verilog-2001 lint/parse, synthesis, independent native/software simulation, combinational SAT, arbitrary-state reset entry and zero-state unbounded induction |
| Negative tools | Actual RTL field/key misbinding, branch swap, ignored enable and wrong reset value; each must produce a `bad=1` counterexample |

The Python checker's local self-tests passed: all 96 case definitions, all 12
profile definitions and 115 rejection controls. This result is not Scala
compilation, generated RTL simulation or formal proof. The dedicated workflow
preserves its exact commit, source archive, compilation inputs, original A/B RTL,
JUnit reports and tool logs. Passing this slice explicitly does **not** mark
all of 59i complete: its evidence contains `complete_59i_join: false`.

## Combined closure obligations

The implementation adds generic widening composite leaves and their
full/partial/odd-tail width provenance, certified captured native graphs,
native saturation geometry, fresh nested-Vec clone metadata and atomic typed
child-parameter inventories. Full qualification requires the new matrices and
inherited gates to pass on the frozen source. The
[nested mechanism inventory](increment-59i-nested-mechanism-matrix.md) maps all
15 mechanism pairs and the two representative six-mechanism designs to their
fixtures, independent native references and actual RTL mutation controls.

The exact-source successor review, inherited source-audit integration, complete
59b proof/mutation retention, full compatibility matrix, final dual-Scala gates,
support matrix and publication documentation remain required. Existing source
audits are not relaxed or rehashed merely to accept this development patch.

The interface profile is independent of the arithmetic and register graph.
An unmodified configuration retains the packed Vec interface;
`MorphNamedFieldVectors.enable(config)` selects named field vectors and
`disable(config)` restores the packed interface. Both return configuration
copies. The merged 60g policy remains the signedness default: signed scalar
declarations with proven minimal casts. `MorphSignedCasts.disable(config)`
selects declarations with retained casts, and
`MorphSignedDeclarations.disable(config)` selects legacy unsigned declarations.
These signedness choices are independent of the Vec interface layout. Ordinary
concrete `SpinalVerilog` retains its native behavior.

## Consumer migration contract

Choose one interface profile for the complete generated hierarchy, then update
external instance connections to that profile. For a nested field, the named
port has the product of its exact scalar width and its outer-to-inner Vec
depths. The last axis varies fastest and coordinate zero occupies the least
significant slice. The
[59c layout and naming contract](increment-59c-named-field-vectors.md) continues
to define escaped names, collisions, directions and signed scalar boundaries.

| Consumer | Required connection |
| --- | --- |
| Existing packed Vec port | Keep the default or explicitly disable named fields; preserve the existing native field order and logical element stride. |
| Named recursive field vector | Connect each published field port separately; retain every independent Vec axis in its width and index formula. |
| Standalone result Bundle | Connect its ordinary scalar result leaves; the field-vector option adds no Vec axis to a Bundle. |
| Signed field inside a Vec | Treat the complete transport vector as packed bits and retain the native signed boundary of each selected scalar element. |
| Generated child | Forward the exact typed actuals in the parent context and use the same publication profile in parent and child. |

Compatibility adapters contain only slices, concatenations and wire
connections. They do not repeat callback arithmetic, choose a reduction tree,
insert registers or reinterpret reset/enable priority. Widening stages use the
retained recursive layout for logical offsets: changing a leaf width does not
replace an inner Vec depth with its finite carrier capacity.

## Reproducing the resumed candidate

The published starting point is
`954d9b2763b064dba60af71ad8fa509a9d7cada8`. The later `53ee9d7`
checkpoint reported in the attached handoff was unavailable in the recovered
repository and artifacts. Its reported test results are historical statements,
not qualification of the reconstructed source.

The committed widening/capture development transaction supplied the recoverable
candidate changes. Fresh tests then drove the additional geometry, clone,
hierarchy and source-audit repairs. The permanent combined workflow compiles
the checked-out production source, generates independent A/B artifacts and
compares both Scala lanes. A workflow that applies a candidate patch or rewrites
an expected source hash cannot supply final-head production qualification.

Runnable generic generators are the `TypedBalancedReduction*ArtifactWriter`
and `TypedBalancedReduction*Artifacts` entry points under `morphhdl/src/test`.
They emit the actual candidate and separately elaborated native references;
the workflow records their exact commands and full proof inventories. Existing
native library, 59b proof/mutation and deterministic contract gates remain
required. Unsupported callback graphs, aliases, owners and partial/foreign
parameter evidence continue to fail closed.
