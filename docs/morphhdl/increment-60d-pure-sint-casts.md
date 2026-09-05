# Increment 60d — Pure-`SInt` redundant cast elimination

Status: implementation under qualification. 60d and parent Increment 60 remain
unchecked until the exact-head review and required gates pass.

## Explicit mode and unchanged defaults

```scala
import morphhdl.{MorphSignedCasts, MorphVerilog}

MorphVerilog(MorphSignedCasts.enable(config)) {
  new Design(...)
}
```

`MorphSignedCasts.enable` enables both signed declarations and pure signed cast
cleanup. The configuration is copied; caller flags and phase installers are not
mutated. Repeated enabling is idempotent. `MorphSignedCasts.disable` disables
cleanup while retaining signed declarations. Disabling declarations also makes
cleanup inactive. Ordinary SpinalVerilog and VHDL do not bind the MorphHDL policy,
even when passed the option. No mode becomes the default in this increment.

`MorphSignedDeclarations.enable` alone remains the separately qualified 60c
policy: signed declarations, with every existing expression cast retained.
The 60a source fixture, sealed baseline RTL and signedness analysis are unchanged.

## Exact removal rule

The native printer creates an unforgeable occurrence containing its emitter,
component printer, exact expression parent, operand slot and operand object.
The 60b snapshot validates that exact edge, current type, native carrier,
retained roots and dependency graph before a decision. No source spelling,
component name, emitted identifier, literal value or width witness recovers
signedness authority.

Cleanup admits addition, subtraction, multiplication, division, remainder,
unary negation, relational comparisons and constant/variable arithmetic right
shift over pure local scalar SInt references. A right-shift amount is separately
validated as unsigned; it is not an arithmetic operand requiring a signed cast.
The result of a comparison stays Boolean. Only exact reviewed native operator
classes are admitted, not arbitrary subclasses.

Removing `$signed(ref)` is permitted only when the native printer actually uses
an already-signed local scalar declaration or an already-materialized signed
expression wrapper. This preserves the identical atom's width and signed
interpretation for every legal parameter value. An explicit scalar reference is
terminal: its driver is not used to infer its type or reconstruct its width.
A wrapper must additionally retain the existing positive logical-width proof.
Foreign emitters, mismatched edges and stale facts fail closed. Reference
overrides, uncertain hierarchy and inline expressions retain their casts.

The existing native wrapper plan is unchanged. This matters because removing a
cast around an inline arithmetic expression can remove a self-determined width
boundary and change overflow. For example, a WIDTH-bit sum used by a 2*WIDTH-bit
multiply must still overflow at WIDTH before multiplication:

```verilog
wire signed [WIDTH-1:0] local_sum;
assign local_sum = (a + b);
assign nestedProduct = (local_sum * c);
```

Inlining `(a + b)` into the multiply is not part of this optimization. No
parentheses, operator precedence, resizing, truncation or pipeline timing is
changed. Native unary negation was already printed without an explicit cast;
60d uses its exact signed result when qualifying a subsequent operation.

## Boundaries retained for 60e

Literals, Bits/UInt conversions, selection, concatenation, replication, muxes,
resize and equality do not become pure just because their result is an SInt.
Those parent expressions keep their existing operand casts. The 60c unsigned
transport wrappers remain authoritative for SInt-to-Bits/UInt consumers.
A independently declared scalar SInt still owns its signed interpretation; it
is not conflated with an inline conversion expression.

Existing unsupported settings remain unsupported. In particular,
`cutLongExpressions=false` on MorphHDL single-source publication and general
symbolic signed widening are not enabled here. The fixed-width widening
regression tests retained boundary behavior, not new parameterized resize
support. Broad aggregate, literal, hierarchy and boundary minimization remains
60e; full rollout remains 60f/60g.

## Qualification

The ordinary `PureSIntCastFixture.Top` is independently elaborated through
native SpinalVerilog at WIDTH 1, 5, 8 and 32. One parameterized candidate is
specialized without regenerating it. Its pure arithmetic is required to contain
zero `$signed` calls. The boundary and full baseline fixtures retain real casts
but must contain no nested `$signed($signed(...))` pattern.

The tool harness maps only an external zero divisor to one, identically on
both sides. This covers every nonzero divisor, including the minimum negative
value, without imposing an assumption inside the DUT or treating division by
zero as defined. Simulation includes extrema, negative values, zero, overflow,
clock enables and an independent modular intermediate-width oracle.

Formal comparison pairs top-level ports and actual sequential Q nets, never
combinational temporaries by their printed names. Standard Yosys `wreduce`
canonicalizes redundant sign-extension widths on both independently lowered
sides; `opt_merge` merges identical cells. Neither transformation inserts
assumed equivalence points. Every output and state equivalence cell must be
proved; timeouts, unknown results, tool failures or missing proof markers fail.

Three live mutations must produce genuine SAT counterexamples: an incorrect
negative result, an incorrect quotient and an illegally widened intermediate
sum. The inherited 60c memory fixture is also compared against fresh native
references over the width matrix. The complete immutable 60a fixture is
regenerated and compared with cleanup enabled, including its original memory
validity and external BlackBox contract.

Both Scala lanes run the focused cleanup suite plus all inherited 60c identity,
isolation, single-source, canonical handoff and typed BlackBox suites. Fresh
JVM generations must be byte-identical. Strict Verilog-2001 parsing, Icarus
simulation, Verilator lint, Yosys synthesis and formal proof are mandatory.

## Native source review

Only VerilogBase.scala and ComponentEmitterVerilog.scala change in native core.
The former adds native-owned cast occurrences and an optional policy callback.
The latter routes the existing three signed-operand printers through that
callback; the rest of its expression printer and all wrapper planning are
byte-identical to merged 60c. The exact four reviewed edits are recorded in
`morphhdl/contracts/increment-60d-emitter-edits.json`; the 60c source guard
reverses only those edits before enforcing its original unchanged-printer rule.
The inherited native-change review and schema-2 source manifest pin all native
byte spans and unchanged intervals. No library algorithm, native configuration
signature, VHDL path or compiler transform is changed.
