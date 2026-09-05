# Increment 60c — Native signed declarations with casts retained

Status: implementation checkpoint; qualification pending. The child checkbox
and parent Increment 60 remain open.

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
