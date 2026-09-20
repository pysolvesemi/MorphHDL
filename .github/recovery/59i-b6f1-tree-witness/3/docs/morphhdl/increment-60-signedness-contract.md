# Increment 60 signedness semantic contract

## Baseline ownership

The ordinary source is `SIntSignedVerilogBaselineFixture.scala`. The reference
is ordinary `SpinalVerilog`; the candidate is independently elaborated by
`MorphVerilog`. Neither printer is changed by 60a. Capture commit
`651ea013b5c1e46d290989696ce07dc9f4a9ba6d` changes only this test source and
its capture workflow relative to merged Increment 59 commit
`8f65bebf071445946bc905b447d64944aaa47235`.

`morphhdl/contracts/increment-60a-sint-baseline.json` seals the two captured
Verilog files with SHA-256 digests taken from run 33914495004. Regeneration must
match those digests, not refresh them. Only the ordinary volatile
Generator/Component/Git-hash header is removed. No RTL normalization is allowed.
The fixture's exact source blob is recorded so the reference is reproducible
independently of later emitter work. The checked-in copies live under
`morphhdl/examples/contracts/sint-baseline/` once the qualified capture is sealed.

This initial fixture covers WIDTH in [8,32] with a default witness of 8. It
includes full-width multiplication, fixed-width arithmetic right shift and a
four-bit narrowing resize. It does not claim width-one, mixed-width arithmetic,
or general domain-crossing resize coverage. Width-one and odd-width closure
remain explicit requirements of 60c/60e/60f; the final formal matrix remains
{1,5,8,32}. Do not narrow the final contract to match this initial fixture.

## Authority and identity

60b must distinguish signed scalar, unsigned scalar, unsigned structural
aggregate, Boolean and unknown/context-dependent facts. Each fact belongs to
one exact graph declaration or expression in one analysis session, with its
width and the exact typed symbolic width authority when present. Names,
locations and concrete witnesses are never identity or inference keys.

Keep semantic type intent separate from target-language interpretation. A
TypeSInt expression or signed destination alone does not prove that a printed
subexpression is already signed, nor that removing a cast preserves expression
sizing. 60b has no permission to alter publication or remove casts. Later
consumers must validate the exact subject identity and relevant width/context
before using a fact. Unknown facts retain casts or produce a diagnostic; they
must never default to signed.

## Declaration rules

An exact independently declared scalar SInt owns signed interpretation. Bits
and UInt are unsigned scalar carriers; Bool is Boolean. A flattened Vec or
Bundle carrier remains unsigned even when all its leaves are SInt. Independently
declared scalar leaves retain their own type facts. Exact scalar Mem[SInt]
elements are signed; packed composite words, addresses and masks are not.
Inspection must reuse retained native word-type leaves, never reevaluate a
user-supplied HardType generator to guess the memory type.

A local typed child/BlackBox connection may be a signed scalar declaration.
That does not certify the externally owned module body or the interpretation of
a packed port slice. Unknown external expressions remain unknown. No BlackBox
RTL definition may be synthesized, rewritten or guessed by this feature.

## Expression transfer rules

| Expression | Required conservative interpretation |
| --- | --- |
| Exact scalar reference | Referenced declaration authority; do not chase assignments to change its declared type. |
| Literal | Preserve explicit signed/unsigned/Boolean typing and width; unsized or unclassified forms require context. The numeric sign is not type authority. |
| Unary arithmetic/bitwise | Retain operand interpretation only for a reviewed operator and width; logical/reduction results are Boolean. |
| Arithmetic/bitwise binary | Signed only when both evaluated operands are signed and width/context rules agree; mixed or unknown operands cannot authorize cast removal. |
| Equality/relational | Boolean result; operand signedness and sizing must be checked separately. |
| Shift | Result follows the left operand subject to exact width rules; a signed shift amount does not make the result signed. |
| Mux | Join the value alternatives, not the selector; signed only when both value alternatives and their sizing are proven signed. |
| Explicit cast | Preserve the exact conversion/reinterpretation boundary; an explicit signed cast establishes a signed result but does not justify removing the cast itself. |
| Resize | Preserve semantic intent and both widths; narrowing selects and widening concatenations may lose target-language signedness. Never infer safety from the destination alone. |
| Concatenation/replication | Unsigned packed transport, including concatenated SInt operands. |
| Bit/part selection | Unsigned selected bits, even for a full-width part-select of an SInt. A scalar reconstruction requires a separate boundary. |
| Memory read | Use the exact memory and read-port identity. A lowered Bits transport stays unsigned; independently declared SInt reconstruction has its own fact. |
| Hierarchy boundary | Separate local declared type from external/packed transport interpretation. |
| Unsupported expression | Unknown/context-dependent; no cast-elimination permission. |

The width and context qualifications above are mandatory. A signed left-hand
side must not repair an unsigned or incorrectly sized right-hand operation.
The target-neutral facts do not by themselves constitute a Verilog-2001
cast-elimination proof.

## Executable baseline checks

The 60a workflow runs both Scala lanes, repeat generation, frozen-hash checking,
strict Icarus Verilog-2001 parsing and simulation, Verilator lint, Yosys synthesis
and an independently prepared fixed-vs-parameterized default-width equivalence
check. The memory has explicit read-first collision behavior, no initial state,
and an enabled synchronous read. Directed simulation compares memory only after
a preceding write/read sequence. The equivalence check keeps initial state
unconstrained and checks the same transition behavior on both sides.

The mutation changes exactly the candidate's `negative_out` assignment to zero.
It must compile and produce `FAIL:NEGATIVE_RESULT` for input -3, whereas the
unmutated candidate and reference produce +3 and `BASELINE_OK`. Parser failures,
missing tools, timeouts and missing success markers are errors, not passing
mutation or equivalence results. The mutation remains available to the broader
solver-backed counterexample harness required by 60f.

All analysis and emitter implementations must remain generic. Test-only text
assertions and the deliberately targeted test mutation are not production
signedness inference.
