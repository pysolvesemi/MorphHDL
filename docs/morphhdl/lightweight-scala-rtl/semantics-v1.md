# Lightweight Scala RTL semantics, version 1

This contract tracks the 86 requirements in the application roadmap dated
14 October 2026. `baseline.json` records their exact text and the starting source
identities. Pending entries are requirements, not support claims. Historical
CA repairs remain completed historical work; composition probes determine the
new frontend gaps.

## Staging

Scala `Boolean` chooses elaboration code normally. A parameter predicate carries
its declaration identities and finite domain into a generate branch. Hardware
`Bool` belongs in `when`; it must not become a Scala Boolean or a parameter.
There is no implicit conversion from a parameter or index to Scala `Int` or
`Boolean`. The native elaborator's representative values are not public semantics.

The first public release uses `ElabInt` and `ElabBool` as canonical parameter
carriers rather than adding a parallel expression algebra. `ParamInt` in the
roadmap is proposed spelling. A concise declaration API must register a fresh
child-local formal with its actual, name and finite domain. Constructor forwarding
must preserve that distinction. Payload types and callbacks specialize a module;
integer parameters remain instance bindings.

The compiler rewrites symbolic control before Scala's Boolean type checking.
It must resolve lexical ownership, aliases and shadowing; matching identifier
spelling is insufficient. `==` and `!=` on authenticated parameter operands lower
to symbolic equality/inequality. `elabEq`/`elabNe` remain explicit escape hatches.
Concrete object equality is unchanged. An unrecognized symbolic expression must
fail, never silently select the default's branch.

## Supported subset and safety obligations

The target staged subset includes immutable local bindings, pure parameter helper
methods, hardware declarations/assignments, guarded child construction and finite
loops. Runtime register assignment remains `:=`. Host mutation, IO, exceptions,
early return and escaping branch/loop-owned values are not permitted inside a
staged alternative unless a separately reviewed operation explicitly supports
that effect. Expression-valued hardware branches require a common result type
and explicit compiler-owned join; choosing a representative branch's object alone
is not an implementation of a hardware result.

Concrete Scala loops retain their existing unrolling semantics. Symbolic loops
retain their index identities and bounds. Explicit retention of a concrete loop
continues to use the existing finite-range API until a documented opt-in exists.
Nonzero starts, inclusive ranges, reverse traversal and non-unit strides require
explicitly tested semantics; unsupported forms must reject. Ordered reductions
must preserve traversal and odd-tail order.

An affine selector retains the exact loop token, Vec identity and symbolic
coefficient/offset. An enclosing guard contributes its authenticated domain to
read, write, coverage and non-overlap proofs. Sibling or escaped indices cannot
borrow this authority. Nested arrays retain dimensions. Hardware `UInt` selection
is a separate runtime operation.

## Recursion and hierarchy

Direct self-construction requires compiler interception before executing the
recursive constructor. The definition's typed port schema and named parameters
must be available without recursively constructing its body. Each active recursive
edge must decrease over the full domain. A witness-depth cutoff, stack overflow
catch, authored BlackBox or textual HDL rewrite is not valid lowering. Mutual and
non-local recursion remain rejected until separately implemented and proved.

Adjacent-pair reduction preserves left-to-right operands, odd-tail bridges and
concrete bridge-level callbacks. Width, payload and callback identity specialize
the definition. Built-in pipeline masks compose with callback registers without
changing reset, enable or latency. Existing `reduceBalancedTree` behavior remains
unchanged until the new implementation passes equivalence checks.

Verilog-2001 aggregate ports use packed boundaries, with lane zero least
significant. Internal unpacked arrays retain symbolic dimensions and signedness.
Direct child connections must retain port ownership and geometry through all
intermediate expressions. User names, keep boundaries and attributes survive;
only authenticated compiler plumbing may be eliminated.

## Qualification and migration

Every capability needs compile-positive/negative probes and generated-RTL tests;
compile success alone does not establish retained symbolic semantics. Test multiple
overrides of one artifact, defaults at both domain boundaries, X/Z behavior and
negative ownership/domain cases. Test native RTL before cleanup independently.

Application migrations and compiler pin changes follow a qualified candidate.
The original application roadmap and source remain untouched during initial
compiler development. No baseline result qualifies a changed compiler. Remote
targeted CI precedes full CI under `AGENTS.md`.

## Initial implementation increment (qualification pending)

`RtlParam.int("COUNT", 3, 1, 64)` and `RtlParam.bool("ENABLED", true)`
declare native bounded integer and Boolean (0/1) formals. Typed-actual overloads
forward expressions explicitly without requiring `toElabInt` at call sites.
For now, annotate bindings as `ElabInt`/`ElabBool` to stage ordinary `if` reliably.
This is the declaration part of LWR-02, not automatic constructor inference.

A recognized `for (i <- 0 until count)` with an `ElabInt` bound lowers directly
to the existing finite-range capture. `0 to end` uses `end + 1`. Nonzero starts
reject explicitly; ordinary concrete loops remain unchanged. Direct `lanes(i)` delegates to the existing scoped selector. Literal affine
arithmetic and less-than guards delegate to native correlated proofs. Direct
recursive constructors are described in the prototype section below. No roadmap
phase is closed by these initial adapters alone.

Direct `lanes(i)` access for the exact scoped loop index delegates through
`ElabScopedIndex` to the retained finite selector. It does not grant authority to
an index used after its loop ends. Positive-coefficient, nonnegative-offset affine reads use the existing affine
proof; unit-coefficient shifted accesses use the existing subrange proof. Existing
`i.affine(...)` stays available. Arbitrary affine writes are not newly authorized.


### Validated initial example

```scala
val count: ElabInt = RtlParam.int("COUNT", 8, 1, 17)
val nextCount = (count + 1) / 2
val source = in(Vec(UInt(8 bits), count))
val result = out(Vec(UInt(8 bits), nextCount))
for (i <- 0 until nextCount) {
  if (2 * i + 1 < count)
    result(i) := source(2 * i) - source(2 * i + 1)
  else
    result(i) := source(2 * i)
}
```

The independent scoreboard checks pair ordering and odd tails on COUNT overrides
1/2/3/5/7/8/15/16/17, including X/Z data, with source defaults 1 and 8. Scoped
statement guards support `<` and equality/inequality against an integer
specialization. Other scoped equality rejects; it cannot silently become host
object equality. Scoped branch expressions cannot return a selected host value.
These are local checks; roadmap completion requires remote qualification and the
remaining composition, pipeline, recursion and migration evidence.

## Direct-constructor prototype (LWR-06, qualification pending)

A final component annotated with `@_root_.spinal.core.rtlRecursive` can declare
integer `RtlParam` formals and a single `io` Bundle, then construct itself directly
inside a proven parameter branch. The frontend derives an interface constructor
and exact formal bindings; it does not execute the recursive Scala constructor.
Every native edge still undergoes decreasing-domain, ownership, port geometry
and driver validation. The annotation requires the MorphHDL compiler plugin.

The current prototype requires one constructor argument list, formals declared
before `io`, and every recursive constructor argument supplied in declaration
order. Integer formals map to distinct constructor arguments; other arguments
must forward their exact unshadowed constructor binding. Changing an operation,
payload factory or other specialization during recursion is rejected. Construction paths
not rewritten by the frontend are rejected by an ancestry guard;
mutual recursion is not supported. An explicitly authored module name is retained;
otherwise the ordinary native class-derived definition name is established before
structural capture. This name is not an identity or termination proof.

Scalar and uniform-direction composite Vec ports are compared as authenticated
aggregate surfaces, not as their differing finite native lane inventories. Record
field order, scalar types and nested dimensions are retained; every geometry
factor is proved independently after substitution. Mixed-direction recursive
aggregate ports remain outside this prototype. Direct child Vec assignment works
without author-written packed transport carriers. The generated unpacked-to-packed
transport currently still includes a read helper; removing unnecessary transport
is tracked separately and is not claimed complete. Nested Vec/Bundle recursive
ports and fixed-width typed callbacks have local tests; register/pipeline
integration is not yet qualified. Nested affine reads carry the original
read-only proof through exact alias identities; this does not authorize affine
writes.

`LightweightRecursiveTests` is the executable example: unsigned addition and
subtraction use adjacent pairs, carry odd tails unchanged, and truncate each
operation to the configured width. It uses ordinary `if`, `for`, `Vec(i)`, guarded
`Vec(2*i+1)`, and `new LightweightRecursiveTree(...)`. Subtraction is tested against
an adjacent-pair reference, not a flat or associative reduction.

## Ordinary word slices (LWR-05, implementation under test)

Within a retained finite loop, `bits(i * width, width bits)` forwards an
`ElabInt` stride and width to the existing correlated packed-access proof.
`bits(i * 8, 8 bits)` uses its fixed-width counterpart. Both forms return the
native assignable Bits slice, so the same syntax supports reads and writes.
Offsets carry the issuing loop identity; neither a Scala integer witness nor a
hardware UInt conversion participates. Escaping the offset does not extend its
authority. Nonzero affine offsets are currently rejected explicitly; arbitrary
shifted windows are not claimed by this adapter. Existing advanced packed-access
APIs remain available with their own supported bounds and ownership rules.


`LightweightCompositeRecursiveTests` covers signed comparison, bitwise XOR and
Bundle-valued subtraction/validity callbacks. `LightweightNestedRecursiveTests`
covers row-wise adjacent subtraction with independent count/column overrides.
`LightweightFormalTests` proves two-state scalar addition/subtraction against an
independently unrolled adjacent-pair reference at counts 1/3/17 and widths 1/8/36.
Simulation supplies the separate X/Z checks; the SAT result does not establish
four-state equivalence. Synthesis profiles instantiate the emitted generic
module from a concrete wrapper, retaining its original recursive definition.
