# Increment 53d — Typed elaboration values and native StreamWidthAdapter

## Status

This increment supersedes the earlier native-`Int` shadow-reconstruction form
of Increment 53d. That experiment remains historical evidence, but it is not
the production implementation and must not be extended for new native-library
support.

## Objective

Parameterize the existing `spinal.lib.StreamWidthAdapter` algorithm using
neutral typed elaboration values while preserving ordinary concrete SpinalHDL
calls and parameter-free `SpinalVerilog` output.

The implementation may make small reviewed changes to SpinalHDL `core` and
`lib` for neutral carriers, parameter-sensitive signatures or overloads, and
generic helper compatibility. It must not copy, replace, specialize, or
reimplement the StreamWidthAdapter algorithm in MorphHDL.

## Typed architecture

`ElabInt` retains:

- the concrete `Int` witness used by ordinary SpinalHDL elaboration;
- an exact bounded `ElaborationIntegerExpression`;
- the originating formal parameter declarations and source identity.

`ElabBool` retains:

- the concrete `Boolean` witness;
- the exact symbolic predicate;
- whether the predicate is constant over its complete admitted domain.

An ordinary `Int` may be lifted to `ElabInt.literal`. There is no implicit
`ElabInt => Int` or `ElabBool => Boolean` conversion. Any compatibility helper
which extracts a witness must be explicitly named and prove that the expression
is constant over its complete domain.

Typed overloads preserve natural arithmetic and comparison syntax. In
particular, the more-specific overload for `depth == 1` returns `ElabBool`,
while an ordinary `Int == 1` remains a Scala `Boolean`.

Raw Scala `if` still requires `Boolean`, so MorphHDL retains a small pre-typer
syntax bridge. The bridge may rewrite only conditions statically proven to be
`ElabBool`; it performs syntax lowering, not source-position or equal-witness
provenance reconstruction.

## StreamWidthAdapter migration

The native adapter will obtain typed payload widths through a generic helper
such as `widthOfExpr(Data): ElabInt`. The existing concrete
`widthOf(Data): Int` API remains unchanged.

The current bounded contract deliberately gives each leaf a domain in which
the adapter relation and conversion factor are constant:

- equal path: the same symbolic width is used by input and output;
- downsize path: symbolic input `[9, 16]`, fixed output `8`, factor `2`;
- upsize path: fixed input `8`, symbolic output `[9, 16]`, factor `2`.

This permits the typed condition bridge to choose the one legal native
algorithm alternative for the whole admitted domain. It does not replay
non-selected alternatives inside a concrete witness graph. Later typed
increments may retain genuinely parameter-varying alternatives only after
those alternatives can be validated in their narrowed domains.

Generic typed helpers required by the unchanged algorithm include:

- `widthOfExpr`;
- typed arithmetic, comparison and equality;
- typed `require`/domain checks;
- `ParameterizedBitCount` conversion;
- typed resize width;
- a domain-constant typed Counter state count;
- a domain-constant subdivision count.

## Required closure evidence

Increment 53d is complete only when all of the following pass on the exact
final head for Scala 2.12.18 and 2.13.12:

- typed carrier compile-time and domain contracts;
- ordinary concrete StreamWidthAdapter generation remains parameter-free;
- one MorphHDL definition covers equal, downsize and upsize leaves;
- representative overrides compile, lint, synthesize and simulate under
  backpressure with correct byte ordering;
- independently generated concrete witnesses are sequentially formally
  equivalent to matching specializations of the parameterized definition;
- a deliberate output mutation produces a genuine formal counterexample;
- deterministic Verilog-2001 generation;
- no component-name, source-file, emitted-module, port or signal recognition in
  the typed production path;
- no MorphHDL-authored replacement StreamWidthAdapter algorithm.

StreamFifo and StreamFifoCC typed depth migration belongs to Increment 53e and
is not a prerequisite for closing this increment.
