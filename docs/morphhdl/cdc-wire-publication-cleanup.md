# CDC-WIRE-01 publication cleanup follow-up (PR192)

This is the existing recursive-cleanup scope, with the additional native resize
naming correction requested by the user. It does not replace CDC-WIRE-01's
historical evidence. Requirements and original standalone sources are under
`repro/recursive-fill-cleanup/`.

## Reproduced baseline

The integration compiler was built at
`155df6eb0e38ecce04a37de2067b0794702fcb83`, tree
`2dee374f6f77359f3b4845f9ae9172ac97e7c957`. Diagnostic fixture source was
`7ebe4ae5d8d92b097a612f584f099978d4981012`. Both required compiler plugins were
active. Workflow 363502212, run 35620756320, passed Scala 2.12.18 and 2.13.12.
The separate baseline workflow used the explicitly permitted temporary source
override only to run the supplied reproductions; acceptance does not use it.

`repro/recursive-fill-cleanup/baseline/` archives the actual compiler/probe
identities, complete trace, and all four emitted artifacts from the 2.13 lane.
`provenance.json` records the downloaded artifact identity and verified digest.
The fixture reproduces the residual expression classes; it is not a claim to
have regenerated the inaccessible Display Controller application.

The trace observer inspects native declaration, driver, receiver, provenance,
protection and publication identities before allocation, including identities
excluded before `proveCandidate`. Names below are explanatory source names, not
eligibility keys. Ordinals identify this trace only.

| Native identity | Baseline disposition | Cause |
| --- | --- | --- |
| `clampedUpper` (13) | `WA05-NATIVE-REGISTERED-IDENTITY` | Auto-resize recovery also owned the same-width forwarding assignment adjacent to an already protected native resize. |
| `zeroFill` (14) | `WA09-NATIVE-EXPRESSION-UNREPRESENTED` | Complete receiver capture could not represent the comparison's unsigned widening resize. |
| Read mux (5) | `WA09-NATIVE-EXPRESSION-UNREPRESENTED` | Same unsupported receiver-expression grammar. |
| Selected mux (10) | `WA09-NATIVE-REGISTERED-RECEIVER` | Receiver `selectedUpper` was incidentally enrolled by auto-resize recovery. |
| `selectedUpper` (11) | `PRESERVATION` | Exact native narrowing source retains `dontSimplify` and `noBackendCombMerge`. |
| Capacity value | `PRESERVATION` | Explicit typed parameter-value carrier. |
| Resize targets (8,12); naming subtraction source (10) | Excluded before proof | Type nodes had been assigned explicit weak-name provenance during resize capture. Protection independently remains necessary. |
| Final comparison-extension wrappers | Not present in final cleanup candidate graph | Created later by the native backend; not a cleanup rejection inferred from an `_zz` spelling. |

The archive is authoritative for exact widths, uses and phase snapshots. Scope,
structural ownership, signedness and expansion restrictions are distinguished by
the observer rather than attributed to every residual declaration.

## Generic repair

1. During capture, omit an auto-resize *recovery* record only when the live,
   root-scope, typed resize is already owned by a valid native-resize record and
   its exact source assignment, target, width authority and outer edge match.
   The protected native boundary and all its publication validators remain.
   No existing record is cleared early. Inactive, malformed, non-root and
   unowned records retain the existing recovery and diagnostics.
2. Permit the canonical expression codec to represent an unsigned fixed-target
   widening resize only when the full authoritative input domain fits that
   target. Signed and crossing/truncating geometry remains separately guarded.
3. Copy an exact poison-free symbolic unsigned zero as a one-bit zero only after
   proving that every enclosing receiver operator retains its authoritative
   width over the whole domain. This avoids carrying the default five-bit
   literal into a legal four-bit specialization. A Boolean final width alone
   is insufficient: `~zero` under a comparison is a permanent negative control.
4. Remove the two weak-name injections from native resize capture. Preserve its
   protection tags and object identity through final publication, which already
   resolves the normally allocated final name. Reserve explicit declaration
   names before weak generated allocation so an earlier generated proposal
   cannot steal a later explicit spelling. Duplicate explicit-name checking
   remains in `NamingScope.iWantIt`.

No generated HDL is parsed or rewritten by the repair. No generated-name,
component, concrete default-width or fixture recognizer participates in proof.
The original 32-occurrence and 256-node/aggregate budgets remain.

## Permanent acceptance

`CdcPublicationCleanupFixtures` retains the standalone fill expression and
extends the naming fixture with explicit generated-looking names, a collision,
and two separately bound child instances. The separate hierarchy fixture uses a
native `COUNT_BITS` formal over 4..18, exercised at depth+2. A derived-depth formal
initially exposed an existing declaration-range spelling conflict in hierarchy
publication; that is not accepted as a passing test or fixed by this patch. The
original standalone fixtures retain their exact depth+2 expressions. Observation checks native identities
and pre-allocation provenance. The existing fixed-point observer also invokes
production twice and compares every statement/expression identity, then checks
that observation and repeated generation do not change emitted bytes.

`check-cdc-publication-cleanup.py` compiles and drives the same emitted artifact
at depths 2,3,4,8,16. Its independent fill and modular-subtraction oracles cover
boundary cross-products, underflow, 1000 random vectors, every X/Z input bit and
four-state bypass controls. Comparisons use case inequality and Verilog ternary
merging. Both modes must agree exactly with the oracle and with each other.
Yosys checks synthesis consistency, on/off equivalence and each mode against an
independent RTL oracle. Hierarchy is flattened only in Yosys's design IR.

The focused workflow retains every previous test and artifact check and adds
these three fixtures, 15 parameter/fixture cases per Scala lane, native
publication-corruption controls, and cross-Scala byte comparison. The baseline
and Mill lanes remain required; local diagnostic builds using cached historical
classes do not qualify the source.

Exact acceptance commands (with both plugins supplied by the normal build):

```sh
sbt -batch '++2.13.12' 'morph/Test/testOnly morphhdl.CdcWireCleanupRegressionTests spinal.core.internals.NativeWidthPublicationSafetyTests'
sbt -batch '++2.13.12' 'morph/Test/runMain morphhdl.examples.CdcPublicationCleanupArtifactWriter /tmp/cdc-publication'
python3 morphhdl-passes/scripts/check-cdc-publication-cleanup.py /tmp/cdc-publication
```

Repeat through the workflow's 2.12.18 lane and independent Mill qualification.
The full focused workflow also runs inherited signedness, truncation, fanout,
alias-chain, expansion, protected-identity, structural and late-wrapper controls.

## Remaining boundaries and qualification status

The fill alias, shared zero and mux carrier must disappear. The parameter-value
carrier, selected narrowing source, and exact resize boundaries remain because
the native publisher owns their protected identities. Backend comparison
extension wrappers can still be introduced after cleanup. These are explained
boundaries, not claims that the current architecture makes them irreducible.
Further elimination needs an expression-capable publication boundary or an
additional proven emitter rule preserving source evaluation geometry.

Width-sensitive zero contexts lacking a complete unchanged-ancestor-width proof
retain the carrier with `WA10-NATIVE-ZERO-RECEIVER-WIDTH-AUTHORITY`. This optional
proof restriction is conservative; a typed symbolic literal/fence representation
could support more cases. User keep/debug/vital intent and signed/truncating,
structural, hierarchy and expansion barriers remain independently enforced.

This checkpoint is implementation under qualification, not completion. Baseline
reproduction is qualified. Repaired-source targeted CI, full CI and merge must
be recorded on the exact final SHA/tree before closure. PR192's distinct hourly
monitor remains enabled. No private application workspace was available.
