# Typed elaboration architecture

**Status:** Approved production architecture from Increment 53d onward  
**Decision date:** 2026-08-29

## Decision

MorphHDL preserves parameter-sensitive elaboration values as typed objects
through the SpinalHDL algorithms that consume them. Zero modification of every
SpinalHDL source file is no longer a higher priority than correctness,
simplicity and maintainability.

Small reviewed changes to native `core` and `lib` are allowed only for
parameter-sensitive types and overloads, typed helper functions, explicit type
annotations, and mechanical metadata propagation. The original SpinalHDL
algorithm remains authoritative. MorphHDL must never replace StreamFifo,
StreamFifoCC, StreamWidthAdapter, Counter, Mem, Stream/Flow or another native
primitive with a separately authored implementation.

## Typed values

A neutral low-level `ElabInt` carries one concrete elaboration witness, one
bounded symbolic integer expression, exact parameter identities and source
information, and the domain facts needed by structural and width consumers.
`ElabBool` carries the corresponding Boolean witness and symbolic predicate.
`HdlInt` and `HdlBool` may remain user-facing factories, but must lower into
these neutral carriers before entering native library code.

There is no implicit conversion from `ElabInt` to Scala `Int` or from
`ElabBool` to Scala `Boolean`. Explicit witness extraction is permitted only at
reviewed compatibility boundaries. A derived typed expression may become an
ordinary primitive only when its complete bounded domain proves it constant.

## Concrete compatibility

Existing `Int` and `Boolean` APIs remain available. An ordinary call such as
`StreamFifo(payload, 5)` or ordinary `widthOf(data)` follows the concrete native
path and produces parameter-free `SpinalVerilog`. A typed call such as
`StreamFifo(payload, depthParam)` or `widthOfExpr(data)` retains the symbolic
value and may produce Verilog parameters and generate structure. Overload
selection, not equal concrete values, chooses the path.

## Natural Scala syntax

A small compiler syntax bridge may preserve natural source such as:

```scala
if (depth == 1) oneStage()
else if (depth > 1) normalFifo()
```

The bridge operates only when the expression is already proven to be typed
`ElabInt`/`ElabBool`. It lowers typed equality, comparison, Boolean composition,
`require`, Boolean match, `.generate`, and parameter-bounded ranges into the
structural frontend. It does not infer symbolic origin from a plain Scala
`Int`, inspect component names, or instrument arbitrary host-language integer
code.

## Allowed native changes

Allowed changes are limited to parameter-sensitive formal types and overloads,
typed helper overloads, explicit annotations needed for overload resolution,
and mechanical propagation through an existing algorithm. Every changed native
file is listed in an audited manifest with its classification and justification.
Algorithm duplication, unrelated cleanup and semantic redesign are forbidden.

## Forbidden production techniques

The production path must not use component/source/module/port/signal-name
recognition, equal-witness or rendered-text identity guesses, parser-wide shadow
propagation through arbitrary native `Int`, source-position aliases as the
primary carrier, replay of branches in a graph typed for another witness, or a
MorphHDL-authored replacement primitive.

## Structural validation

Ordinary SpinalHDL validation sees only the concrete witness-selected graph.
MorphHDL validates each captured typed alternative under its narrowed parameter
domain. Widths, ranges, memory depths and assignments are therefore checked
against the correct branch domain rather than unrelated default geometry.

## Completion gates

Every migration passes Scala 2.12.18 and 2.13.12 under SBT and Mill, concrete
`SpinalVerilog` compatibility, deterministic strict Verilog-2001 generation,
applicable simulation/backpressure, lint and synthesis, independent formal
proof and mutation controls, and the audited native-change manifest.

This document and the authoritative roadmap section supersede older
zero-native-diff requirements for Increment 53d and later work.
