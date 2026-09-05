# Increment 60c — Native signed declarations with casts retained

Status: 60c qualified. Native signed declarations remain an explicit opt-in,
and existing expression casts are retained. Parent Increment 60 and children
60d through 60g remain open.

## Explicit publication mode

```scala
import morphhdl.{MorphSignedDeclarations, MorphVerilog}

MorphVerilog(MorphSignedDeclarations.enable(config)) {
  new Design(...)
}
```

The option is disabled by default. Enabling/disabling copies the caller's flags
and phase inserters. The exact installer identity is the opt-in marker; no
generation flag is added or whitelisted. Only MorphHDL's single-source Verilog mode binds the policy.
Ordinary SpinalVerilog and VHDL are unaffected even when handed this option.
The mode owns the existing exact pre-emission signedness observer; installing a
second observer remains an explicit phase-plan error rather than an ordering
heuristic. No native SpinalConfig constructor field is added.

## Native declaration authority

A generation-local policy is bound to the exact native PhaseVerilog instance
immediately before emission, after inherited validation and name allocation.
VerilogBase constructs unforgeable declaration-occurrence objects at its actual
scalar, wrapper and unsplit memory declaration sites. They contain emitter,
subject and role identities, never source locations or emitted identifiers.
The policy revalidates the 60b snapshot before each declaration decision.

Scalar SInt input/output/inout ports, wire/reg declarations, process outputs,
output buffers and child-port wrappers become signed. Exact Mem[SInt] elements
are signed. One-field Bundle memories, masks, addresses, Bits, UInt, Bool and
flattened aggregate transports remain unsigned. Split masked-memory banks are
partial-bit transports, not complete scalar SInt values, and remain unsigned.
External BlackBox source and declarations are never changed.

Native constant-process function results reuse the exact scalar declaration
type. The native function fallback has only a Boolean dummy argument; enum
recoding helpers are not SInt functions. This increment does not invent a new
user-function lowering path or claim unrelated SystemVerilog support.

An expression wrapper needs both a real native wrapper occurrence and fresh
ExpressionUse evidence. 60b's graph-only TemporaryUse factory stays reserved and
continues rejecting unplanned temporaries. Independently declared scalar SInt
leaves own signed interpretation even when their drivers are slices, muxes,
resizes or memory reads; this does not grant any cast-elision permission.

## Symbolic widths and unsigned transport

Existing declaration, hierarchy and memory width rewriting remains authoritative.
Expression wrappers have no BaseType for that pass to rewrite. Their packed
range is therefore rendered from the structured 60b logical width fact, resolving
every retained root through the exact expression evidence. Bounds must remain
positive and the exact default must equal the native carrier width. Unknown
wrapper geometry fails closed rather than freezing a default-width witness.
No symbolic provenance is recovered from an Int, source text or emitted name.
The existing direct-declaration canonicalizer only preserves an optional signed
section token; it does not infer signedness.

Changing a declaration can expose a previously harmless no-op SInt-to-Bits/UInt
cast as signed in the target language. The opt-in native wrapper plan therefore
materializes those exact conversion nodes as unsigned transport wires. This
preserves their bit pattern and unsigned consumers without changing any native
expression or cast printer. Existing `$signed(...)` calls remain; redundant-cast
removal is still Increment 60d. This transport barrier is necessary for safe
60c publication, not the broader boundary simplification planned for 60e.

## Reviewed native changes

Only VerilogBase.scala and ComponentEmitterVerilog.scala change. The former adds
the default-disabled declaration policy and native-owned occurrence sites. The
latter makes one mode-gated addition to the existing expression-wrapper plan.
All expression/cast printer code from emitReference onward remains byte-identical.
The exact native blobs and byte-minimized edit spans are recorded in the existing
schema-2 native-source-preservation manifest and reproducible native edit review
policy. No library algorithm, native typed
API, compiler transformation, native configuration signature or VHDL path changes.

## Qualification contract

Both Scala 2.12.18 and 2.13.12 execute declaration/evidence/isolation tests and the
inherited 60b, single-source, canonical handoff and typed BlackBox suites.
The width-one-safe ordinary fixture covers WIDTH in {1,5,8,32}, compound widths,
registers, procedural outputs, memory and signed-to-unsigned consumers. One
parameterized candidate is specialized without regeneration and compared against
four independently elaborated ordinary native references. Simulation initializes
both memory words identically; sequential equivalence uses the inherited paired
uninitialized-state relation, not arbitrary independently initialized memories.

Require deterministic RTL, strict Icarus Verilog-2001 parsing/simulation,
Verilator lint, Yosys synthesis and sequential equivalence. A negative-result
mutation must produce a real solver counterexample. Missing tools, nonzero tool
exit, timeout, unknown result or missing proof markers fail the check. The sealed
60a oracle is regenerated unchanged and its full fixture is also compared against
the signed candidate at WIDTH=8. This finite declaration-mode qualification does
not close the broader 60e/60f operator, boundary and parameter-domain contracts.

## Resume qualification and retained boundaries

The scalar-memory and Bundle-memory declaration fixtures are separate modules.
The inherited publisher permits one symbolic memory per module, and this
increment does not bypass that restriction. The scalar SInt memory retains
symbolic WIDTH over 1 through 32. The one-field Bundle memory is a fixed five-bit
aggregate inside a parameterized module: it proves that an aggregate carrier
stays unsigned, not that symbolic Bundle-memory read reconstruction is supported.
A negative regression verifies that the existing unsupported symbolic Bundle
reconstruction still fails closed with the mode both off and on. Broader aggregate
closure remains 60e.

Fixed SInt widths 1, 5, 8 and 32 are tested inside otherwise parameterized modules.
The existing all-literal MorphVerilog rejection is not relaxed; ordinary
parameter-free SpinalVerilog remains the concrete front door. Inout ports, scalar
and aggregate memories, compound ranges and the native constant-process function
are parsed, linted and synthesized at all four WIDTH overrides. The function
fixture uses two explicit constant assignments with allowOverride, not a
constant when-condition that merely emits another wire. Its signed return and
result wrapper have an independent ordinary-SpinalVerilog equivalence reference.

The equivalence harness matches only top-level ports and actual sequential Q
nets after process and memory lowering. It hides every other internal wire from
name-based matching on both sides. New unsigned transport wrappers can reuse
names previously allocated to different combinational temporaries; such names
are not semantic correspondence evidence. This is a generic selection based on
sequential cell connections, not a blacklist of failed signals. Every matched
state bit and output must still be proven, with zero unproven cells. The negative
result mutation must also produce a genuine SAT counterexample.

The native no-sensitivity function fallback is qualified only for exact fixed
SInt result widths. Parameter-dependent result and literal sizing is not proved
by changing declarations. The opt-in mode rejects that case at a native-owned
FunctionResultDeclaration occurrence before publishing any candidate. It must
not retain a concrete-width function beneath a symbolic output or silently
change its implicit extension. Mode-off emission is unchanged. A regression
checks this diagnostic and verifies that no failed candidate file is published.

## Qualified implementation and retained evidence

Implementation head: `a0c45ca7b51740f272771f23b8f6f8c2844993ec`, integrated with
`parameterized-verilog` at `2be259338b87ecc30b44e47498f7f09c368e50d0` in PR #155.
The completion change is documentation-only; it does not alter the qualified
implementation, fixtures, oracle, tool pins or proof scripts.

[Dedicated qualification run 33964956846](https://github.com/pysolvesemi/MorphHDL/actions/runs/33964956846)
passed both Scala 2.12.18 and 2.13.12 lanes. Each lane passed all 74 tests across
six suites, with no failures, errors or skipped tests: 13 declaration tests,
26 typed-signedness authority tests, five signedness-resume tests, 14 single-source
tests, 12 canonical-handoff tests and four typed BlackBox tests.

The retained artifacts prove the following for that implementation:

- All 12 generated Verilog files are byte-identical between fresh JVM runs.
  The emitted RTL is also byte-identical across the two Scala lanes.
- Strict Icarus Verilog-2001 parsing and simulation, Verilator lint and Yosys
  synthesis pass. The one parameterized signed candidate is specialized without
  regeneration and is sequentially equivalent to the independently elaborated
  native references at WIDTH 1, 5, 8 and 32.
- The fixed native function result has its own independent equivalence proof.
  The complete sealed 60a fixture is also equivalent to the signed candidate at
  WIDTH 8, without changing the baseline source or its immutable checks.
- The deliberate negative-result mutation produces a genuine solver
  counterexample, not a parser error, timeout or missing tool.

Artifact ZIP SHA-256 digests, verified against GitHub metadata:

| Scala lane | Artifact ID | SHA-256 |
| --- | --- | --- |
| 2.12.18 | 9969156591 | `c706b53b5433e7f67236ed497e8ae3ccc698cedd4533e1578fbbb8d7204f3253` |
| 2.13.12 | 9969167443 | `6a81e9432d0c4b7a2f134e422be37a46b77397d20a3e1192dcfdc42ba586ab7f` |

Both source archives identify the exact implementation head above. Reconstructing
the source tree, including the recorded CocotbLib submodule entry, reproduces
GitHub tree `2f74620d7ef3ee9c37a4cf064ec592855c2af64c` exactly. Current RTL
from the 2.12 artifact was independently requalified locally with Icarus 11.0,
Verilator 4.228 and Yosys 0.41, including the unchanged 60a checker and live
mutations. This recheck used the current generated RTL, not older compiled
MorphHDL classes from the saved tool bundle.

The inherited baseline run 33964956822 passed both compiler lanes and strict
Verilog-2001 architecture contracts. The inherited native StreamFifo formal run
33964956815 passed both Scala lanes, including eight positive depth/helper proofs
and two live mutation counterexamples per lane. Its first Scala 2.13 attempt
stopped before compilation/tests because Maven reset a Coursier dependency
download; the missing proof-artifact error was secondary. A targeted retry passed
without changing any source, proof setting, tool pin or timeout.

All 30 executed pull-request workflows at the implementation head passed; seven
other workflows were skipped by their existing scope conditions, not counted as
passing tests. The documentation-only completion head is subject to its own
fresh required checks before merge. Parent Increment 60 remains unchecked.
