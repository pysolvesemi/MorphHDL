# Correlated finite word access and combinational Vec publication (CA-042/043)

CA-042 retains the relationship between a finite loop count and the width of its
packed source. This existing API now supports COUNT-sized ports directly:

```scala
val terms = in Bits((count * 8) bits)
val lanes = Vec(Bits(8 bits), count)
ElabFiniteRange.foreach(count, "unpack") { index =>
  index(lanes) := index.packed(terms, 8, 8 bits)
}
val packed = Bits((count * 8) bits)
ElabFiniteRange.foreach(count, "pack") { index =>
  index.packed(packed, 8, 8 bits) := index(lanes)
}
```

The native slice retains its exact source/result identities, index token, lexical
owner and pointwise width proof. Publication revalidates that proof in its captured
owner domain; a matching default or matching rendered expression is insufficient.
A whole-word writer can establish complete coverage only with equal stride/width,
exact total width, one native assignment, and the direct loop-body owner. Its
per-iteration driver stays inside the generate loop, while its declaration stays
in the authored enclosing scope. Partial writes do not acquire this authority.
Other structural/procedural slice APIs retain their existing validation rules.

The unpack loop emits a symbolic word slice such as
`terms[(i * 8) +: 8]`; the matching pack loop writes one symbolic word at a time.
Native helper wires, including `_zz_` intermediates, remain intact.

CA-043 extends the existing independent scalar-cell publication to symbolic
combinational Vecs with procedural cells. Native `wire` and `reg` declarations
and their drivers remain in place, with a read-only wire aggregate for loop or
whole-vector consumers. Bridges for capacity lanes are guarded by the symbolic
depth, so inactive elements never write outside the logical aggregate. This avoids
mixing continuous and procedural drivers on one Verilog-2001 variable, and avoids
combining unrelated procedural event identities. Continuous-only symbolic arrays
retain their established direct aggregate publication. Register storage, reset,
assignment priorities, structural branch ownership and attributes are unchanged.

## Regression coverage

`CorrelatedVecPublicationTests` checks recursive adjacent-pair subtraction at
COUNT overrides 1 through 8, using defaults 1 and 8 on the same generic topology.
It covers explicit word unpack/pack, a direct first cell with conditional later
cells, finite-loop consumption, Bits/SInt/composite words, and packed/unpacked
layouts. Repeated emission must be identical. An independent flat reference
checks zero, all-one, one-hot-pattern, randomized, and X/Z inputs. Every override
is compiled/simulated with Icarus, linted with Verilator, synthesized and checked
for latches/unresolved hierarchy with Yosys, and compared by a combinational SAT
proof. Invalid widths, inverse/independent width relationships, foreign sources,
overlapping writes and missing coverage must fail.

`FinitePackedCorrelationTests` replaces offset/width identities, removes the
proof/token, or removes the slice record. Each mutation must fail before
publication. Existing structural, Vec, recursion, signedness, hierarchy, process,
Area and field-layout regressions remain enrolled on both supported Scala versions.

The reported application sources are reproduced without editing the application
repository. Pipeline checks also exercise reset, enable stalls and all mask
settings against an independent cycle model. Local evidence is distinct from
exact-head remote targeted/full qualification. Local commercial simulation is
not claimed when the executable is unavailable.
