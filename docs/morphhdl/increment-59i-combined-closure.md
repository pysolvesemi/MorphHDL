# Increment 59i — Combined Vec/reduction closure

**Status: in progress; not qualified, not complete and not merged.**
The controlling item remains unchecked in `parameterized-verilog-todo.md`.
This document records a development checkpoint, not a substitute for the full
59i scope or for its inherited source, compatibility and formal gates.

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

No generated Verilog is presented as verified at this checkpoint. The fresh
Scala generation and HDL-tool runs must produce it before that claim is made.

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

## Remaining closure obligations

The full roadmap still requires generic widening composite leaves and their
full/partial/odd-tail width provenance; certified captured composite graphs;
expanded composite register profiles; nested aggregate projection and generated
child hierarchy; every mechanism pair and representative end-to-end designs.
The saturation, product, cross-field, carry/sign, index/capture, odd-tail and
latency mutation coverage must expand with those mechanisms.

The exact-source successor review, inherited source-audit integration, complete
59b proof/mutation retention, full compatibility matrix, final dual-Scala gates,
support matrix and publication documentation remain required. Existing source
audits are not relaxed or rehashed merely to accept this development patch.

The legacy packed layout and signedness behavior remain the defaults. Named
field vectors and signedness cleanup remain explicit options. Unsupported
captured/widening composite graphs and nested aggregate aliases retain their
existing rejection paths until their own replacement coverage is qualified.
