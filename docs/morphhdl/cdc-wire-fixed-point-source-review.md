# CDC-WIRE-01: recursive generated expression cleanup

This successor implements the explicitly requested CDC-WIRE-01 item in the wire
pass roadmap. The implementation baseline is
`bbae646ba43e6189c69feb308f8decb9b677b15f`, tree
`c2f6e2abd588a131c5e6909173659935c62e77b7`. The scope is generic typed native
expression cleanup and its existing structured Verilog emitter. Applications,
component names, generated-name patterns and emitted-text rewriting do not
select candidates.

## Current implementation obligations

The existing ordered alias, expression, constant and ternary pipeline repeats
while a rewrite reports progress. Candidate inventories are rebuilt after a
successful rewrite. Cleanup removes drivers/declarations only after the exact
receiver count and remaining-reference checks succeed. The convergence guard
fails closed if repeated processing cannot settle.

The successor supplies missing structural proof for unsigned shifts, nested
casts, slices, fixed resize boundaries, bitwise trees and Boolean conditions.
Symbolic expressions use retained typed geometry and exact parameter identity
across the declared domain. Comparison operands and shift amounts use their own
required contexts; no default elaboration witness authorizes a symbolic width.
Expression copying preserves native reference identities and metadata. Shared
compiler type nodes are eligible only through generated/unnamed source origin
and authenticated pre-liveness preservation intent, or the existing separately
validated condition profile. Explicitly protected type nodes remain. The
private proof projection classifies a register before checking output-port
visibility, so an output register retains procedural scheduling while its port
observability remains separately represented. No native port or driver moves.
Direct-alias cleanup also accepts compiler type nodes only with the same
pre-liveness evidence. The unnamed route requires unnamed provenance; the named
route requires generated provenance and retains its ordinary continuous-use
restrictions. Legacy constructors have no evidence and retain those nodes.
The production pipeline supplies the captured intent. Explicit names, user
protection, metadata, packed-type equality and complete reference accounting
still apply; a same-component input remains the same declared identity.

The backend independently proves every occurrence before suppressing a wrapper.
Shared expression trees remain bounded by node and receiver limits. Mandatory
wrappers installed before depth cutting remain. A supported arbitrary unsigned
expression slice uses a pure Verilog-2001 function whose input has the full
original width and whose output is the precise selected range. This preserves
truncation and X/Z selection semantics. Local naming allocation prevents
collisions, and functions are cached by authoritative width and range. A
Morph-owned pre-emission phase reserves published parameter identifiers in the
same local namespace before allocating helper functions and their arguments.

Compiler-generated symbolic Gray-code carriers previously used the same
`dontSimplifyIt` annotation as explicit user preservation. The new
`ParameterizedExpressionCarrier` identity marks exactly those compiler-owned
geometry boundaries. Native simplification continues to retain them. Optional
cleanup can discharge this marker only through exact typed proof. Adding any
other tag or an explicit user preservation request still prevents removal.
Gray-code equations, domain validation and concrete code paths are unchanged.

User names, ports, registers, explicit protection, unsupported metadata, signed
or unknown width boundaries, side effects, hierarchy ownership, clock domains,
reset/enable behavior, partial writes and expansion limits remain eligibility
constraints. The ordinary Spinal backend and disabled Morph pipeline preserve
their inherited policy.

## Source audit integration

`check-cdc-wire-source-review.py` enumerates the successor's production, test and
review paths and pins its predecessor tree. All current governed files remain
subject to the existing cumulative WA-08 seal: exact immutable source bytes,
HEAD, index, worktree, modes, gitlinks, ignored source additions and foreign
source roots. The verifier implementation remains unchanged apart from its
manifest digest. No source root wildcard grants a new exception.

The existing native preservation checker remains unchanged. Its review policy
records the new geometry marker and the precise typed provenance/copy/emitter
and Gray carrier changes. Existing native classifications are retained. The
canonical manifest must regenerate identically from that policy. The inherited
formal source registry retains its original schema and path set; changed
fingerprints identify actual changed source bytes.

The CDC-WIRE source adapter authenticates current implementation bytes before
legacy scope checks are replayed. The original PR190/PR189 union checker runs
unchanged at `f4eaa67184f60147b0b2e3bc108276e43dea70c0`; the historical static
wire-pass contracts retain their original immutable replay source. These checks
preserve inherited obligations. They do not establish the behavior of the
current compiler. Current compilation, regression, emission, simulation and
formal jobs always execute the current feature head.

The branch boundary recognizes only `agent/wa-cdc-wire-01-fixed-point`, and only
after the current source seal and exact inventory pass. Controls reject a
lookalike branch, extra production paths, source tampering and application/text
recognition. Existing boundary controls remain intact.

## Additive regression inventory

The current 60f report adapter preserves the frozen inherited catalogue and all
37 sequential-successor tests. It validates current testcase identities against
the sealed source, including the four emitter cases whose expected behavior is
extended by CDC-WIRE-01. Two existing sequential emitter expectations now check
exact function truncation plus preserved reset and priority order; their case
identities and the 37-test sequential inventory remain unchanged. The native
sequential suite also has one explicitly reviewed case rename for its exact
arithmetic-slice expectation; its 18-case count is unchanged. The emitter
suite adds three cases, the native-copy
suite contributes its three cases to the 60f run, and the new cleanup and
namespace suites contribute eight and one cases respectively: 15 additional tests
and three additional suites.

Only copied reports lose those additions while the frozen catalogue is checked.
Original XML is unchanged. The final combined inventory must exactly match all
original current-head reports. Missing, duplicated, unreviewed, failed, errored,
skipped or stale cases and altered copied reports are rejected. Source enrollment
and report validation do not infer completion from a failed run's counts.

## Reproducible sealing and qualification

1. Commit the reviewed implementation, tests, audit code and native policy.
2. Run `python3 morphhdl/scripts/refresh-cdc-wire-source-seal.py native` and
   commit the canonical native manifest and refreshed formal fingerprints.
3. Run `python3 morphhdl/scripts/refresh-cdc-wire-source-seal.py seal` from that
   clean source checkpoint and commit the cumulative manifest and digest.
4. Run source controls and the focused CDC workflow on the resulting exact head.

The refresh utility does not approve source, change compiler code, fabricate
results or run inside CI. It serializes records which the original verifiers
then check. Every later implementation edit requires a new clean checkpoint
and regenerated records.

Behavioral evidence is produced by the current targeted workflow. This document
records implementation scope and review obligations, not a claim that tests or
CI have passed. After a CI failure, rerun the failing targeted workflows until
all pass; only then run full CI. Merge requires the applicable full workflows
passing for the completed implementation.
