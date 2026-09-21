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
Namespace reservation collects retained spellings without prematurely validating
the identity of separately bound child parameter roots. Optional late width
queries decline only the two explicit unavailable branch-evidence diagnostics;
construction-time width validation still fails normally. An unavailable proof
cannot become a concrete-width fallback. Remaining unsigned expression wrappers
retain their authoritative symbolic ranges in either signed-declaration mode,
subject to exact owner, positive-domain and native-witness checks.

Captured Vec operations and their exact slice/cast supporting chains remain
owned by the Vec publisher. Native cleanup and optional emitter wrapping retain
those identities and witness ranges until that publisher has validated and
rewritten them. The planner checks that ownership, including inferred
single-driver dependencies, before attempting scalar width composition; a
factorized Vec domain never gains scalar authority or requires Cartesian
expansion merely for optional cleanup. Captured structural declarations and resize records likewise
retain native identities, including invalid records whose precise rejection is
still owed. Identity scans include expression roots and descendants; they do not
recognize user names or substitute a concrete witness for unknown geometry.
The codec also retains its historical three-argument JVM constructor, which
delegates to the conservative capture policy without symbolic-expression opt-in.

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
namespace suites contribute nine and one cases respectively: 16 additional tests
and three additional suites.

The full-CI follow-up's late-wrapper regression covers Gray decoding and an
unrelated graph that exceeds the bounded-inlining limit at
widths 1, 33 and 65, with signed declarations enabled and disabled, independent
four-state oracles and public-output formal equivalence. Existing captured
resize and structural-scope negative cases run in both cleanup modes without
changing their identities or accepted diagnostics. The aggregate compatibility
case keeps its disabled golden byte-for-byte and checks optimized public/state
structure plus signed formal/four-state equivalence at widths 1, 8 and 64.

The long-expression stress case uses an independent width-explicit RTL gold.
The inherited passes-disabled depth wrapper freezes intermediate widths on that
100-stage graph at width 65, so it is not a valid semantic oracle for this case.
Both optimized signed-declaration modes must match the independent arithmetic
and four-state reference. The disabled backend is unchanged by this repair.

Existing WA-10 and remaining-wire artifact validators accept either a retained
full-width carrier selection or an exact unsigned slice function. They prove
the original input width, output width, low-bit range, unique driver/function,
unchanged other register writes and complete arithmetic expression. Twenty
additional controls reject malformed widths, bodies, slices, drivers and
arguments. The existing simulation, equivalence and semantic mutation gates
remain mandatory; emitted DUTs are never rewritten to satisfy these checks.

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

## Full-CI qualification follow-up

The focused run `35523660578` passed both Scala versions and the cross-Scala
comparison on `4358ecabd93a358fdd76875bf0a4ac465fc41904`. PR #191 then launched
the initial full workflow set on that source. The obsolete native Gray-carrier
guard was repaired without changing its algorithm, width or explicit-protection
requirements, and targeted run `35526004890` passed the sealed repair head
`8d3dff47c7722a3321f2e6d77e674bab89087886`.

The remaining initial full-CI failures exposed the scoped-width, captured
identity, remaining-wrapper range and ABI issues described above, plus checks
that required eliminated internal aliases or the old slice spelling. Their
repairs require fresh targeted qualification on the corrected sealed source.
Initial source-audit stages passed; this follow-up does not waive an audit,
extend its timeout or reinterpret a failed proof as success. Completion remains
unchecked until the targeted and final full workflow requirements are met.

## September 21 full-run follow-up

The user-authorized full run on `0a512546a38ed82b916f975025f5da278a68a6aa`
has the identical source tree as `a05f4cb83`. Its sequential and focused CDC
lanes exposed an over-broad captured-resize identity fence: ordinary concrete
`.resized` records were retaining aliases that the existing sequential tests
require to disappear. Cleanup now releases only records whose target, resize
carrier, original source and current driver all have positive, parameter-free
width proofs. Typed, inactive, symbolic and unavailable-width records remain
owned by publication, including invalid records awaiting precise diagnostics.
The six failing sequential tests are unchanged; the correction is in the
generic ownership query, not their retention expectations.

The WA-08 production artifact check also still classified same-component
signed/unsigned input aliases as hierarchy crossings. Its successor assertions
now require the exact disabled two-edge chain, removal of the alias, and a
unique direct input-to-output assignment. The protected aliases, port types,
four-state comparisons, formal equivalence and genuine mutations remain gates.
The public `symbolic_data_shapes.v` golden is regenerated by the ordinary
contract generator: only three internal direct bundle aliases disappear; all
ports, signed ranges, registers and clocked assignments are unchanged.
The strict contract shape checker requires the corresponding exact 27 symbolic
ports, three symbolic registers and 19 continuous assignments, rejects the
removed aliases, and checks each unique same-type Bundle input/output edge.
The golden is explicitly enrolled in the byte seal; existing simulation,
lint, synthesis and validation-phase inventory requirements are unchanged.
The Yosys JSON shape checker verifies those same direct bit identities and
rejects surviving aliases, retaining exact port signedness and all three
register widths, D/Q/clock connections and polarity checks.

These follow-up changes require fresh affected-workflow qualification after
sealing; no older-head run or local result establishes their CI completion.

Local follow-up verification passed the unchanged sequential plus captured
normalization/domain suites (70 tests on Scala 2.12), and all three focused
core suites (37 tests) plus the focused/captured Morph suites (121 tests) on
Scala 2.13. The complete WA-08 production artifact validator passed 31
deterministic artifacts, 6,672 four-state cases, 51 formal comparisons and four
genuine mutation rejections. All 21 generated public contract files match the
reviewed goldens. The changed bundle golden additionally passed real Yosys
equivalence against its previous committed version at WIDTH=1/8/64.

## Separate enabled and disabled contract oracles

The targeted Mill run `35558421879` on `e26eceae4` failed in both Scala
lanes at the data-shapes test's disabled-output golden comparison. The public
golden now describes the enabled pipeline, whereas the disabled pipeline
correctly retains the three internal Bundle aliases. The test now compares
enabled output byte-for-byte with that public golden and disabled output
byte-for-byte with an independent literal copied exactly from the golden at
`0a512546a38ed82b916f975025f5da278a68a6aa`. Neither DUT is rewritten.
All existing interface, register, sequential-statement, four-state simulation
and formal-equivalence checks at WIDTH=1/8/64 remain unchanged. This repair
changes test expectations only; compiler and workflow bytes are unchanged.
Fresh affected-workflow qualification is required; no CI pass is inferred
from copying or authenticating an oracle.

Local validation of this test-only repair passed the actual data-shapes test
on Scala 2.13.12, including strict Verilog compilation, four-state simulation
and Yosys equivalence at all three widths. The disabled literal was separately
verified byte-identical to the cited historical golden (3,235 bytes, SHA-256
`b9a6fa929edeec0e4779d330d7b43bc1e048236a14d30792a24eebb83df542f3`).
The regression-inventory controls still reject all 65 negative cases without
changing the original XML. Dual-Scala CI remains pending.

The full local `MorphSingleSourceVerilogTests` suite subsequently passed all
14 tests on Scala 2.13.12 at sealed head `b1176dde22` with no cancellations.
The first Mill-only controller stopped before any dispatch when the live target
advanced to `ce4a02c11b5ec19777c3d900e7fdc06ebbf6d7cd`. That target commit adds
only `AGENTS.md`, standardizing the targeted-CI protocol. It is incorporated by
an ordinary merge, with its exact instruction bytes enrolled in the source
review and cumulative seal. This integration changes no compiler, test, golden
or qualification-workflow bytes relative to `b1176dde22`; the dispatch identity
guards remain unchanged. The stopped controller must not be rerun.

## Baseline regenerated-contract count repair

Current-head baseline run `35565550600` passed its complete Scala 2.13 lane
and, on Scala 2.12, passed compilation and all 1,232 executed tests before the
contract-generation step. Both deterministic fixture generations succeeded.
That step then retained the pre-repair assertion that
`symbolic_data_shapes.v` contains 33 exact `[WIDTH-1:0]` ranges. The reviewed
enabled golden and the generated output contain 30: the 27 public scalar-width
ports and three registers. The difference is exactly the three internal Bundle
aliases intentionally removed by the already-qualified cleanup repair.

The baseline assertion now requires 30. Its independent exact golden `cmp`,
complete file inventory, construction-order determinism, packed-Vec ports,
clocked process, strict Verilog tools and all other structural checks remain
unchanged. This is a workflow expectation repair only; compiler, test and
generated-Verilog bytes are unchanged. The baseline workflow is explicitly
enrolled in the current source-review scope and cumulative seal. A fresh
exact-head baseline run must pass before final full CI.

## Hourly-monitoring instruction integration

The integration target advanced from `ce4a02c11b5ec19777c3d900e7fdc06ebbf6d7cd`
to `3aa132e87e10dd0d2e0062c7d34d26bddb58fa72` by one `[skip ci]`
documentation commit. That commit changes only the repository-level `AGENTS.md`:
it requires the already-active hourly monitor to remain enabled through targeted
qualification, final full CI, merge and verified closure, and records the same
no-duplicate, failed-log and exact-head rules followed here.

Ordinary two-parent merge `588d3bd148657efbfa9b223e1405c4f592cfe923`
incorporates that target commit after sealed baseline repair `cb72ea2851`. No
compiler, test, generated RTL, golden, workflow, native-review or formal-registry
byte changes are introduced. `AGENTS.md` was already an explicit source-review
and cumulative-seal path, so this integration requires a fresh documentation
checkpoint and outer seal but no native or formal-record refresh. Passing
targeted evidence remains retained for the unchanged implementation; applicable
final-head full CI is still required before completion.
