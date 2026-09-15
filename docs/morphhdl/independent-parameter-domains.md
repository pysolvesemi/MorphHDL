# Independent HDL parameters: publication provenance and structural proof

## Scope and status

This is the existing PR #188 corrective repair on
`agent/independent-parameter-domain-composition`, targeting
`parameterized-verilog`; it is not a new numbered increment. This document
specifies the reworked architecture. Completion and merge require all applicable
checks on the exact final source head. Local results or a staged/importer commit
are not substitutes for that qualification.

The earlier product-domain implementation required attainable extrema while
constructing a symbolic expression. It could compose large independent sums,
but a non-separable expression still needed a bounded joint proof even when
Verilog, rather than Scala, would choose its value. That requirement was too
strong for ordinary packed-width publication.

The original reported compiler `3b547ae5622ae212c17f6e67cc96924127a46a41` rejects
`DATA_BITS + GENERATION_BITS` with
`SPINAL-ELAB-DOMAIN-EXACT-CORRELATION-UNSUPPORTED`. The retained standalone
baseline run `34827208111` reproduced that failure after Scala compilation.
Later transport head `91f6c2b347cc480118fc5ea3a2dd577482f54c7e` was not the
applied workflow correction: importer run `34866430100` failed to push workflow
changes because its Actions token lacked workflow-write permission. Recovered
source changes are reviewed independently of that historical job's status.

## Three levels of evidence

**Symbolic publication provenance.** `ElaborationProductDomain` retains a
private `TrustedExpressionEvidence` certificate attached to the exact expression
object. It contains the value-function AST and its original declaration axes,
schemas and active restrictions. Repeated references preserve root identity;
partially overlapping sets such as `{a,b}` and `{b,c}` keep the shared `b`.
Canceled axes remain in the provenance inventory even when their numerical
contribution disappears. Names, equal defaults and copyable expression fields
never grant authority.

**Exact/domain proof.** The existing exact machinery remains available.
`ElabInt.minimum`/`maximum`, exact owner extrema, width-equivalence queries and
structural truth queries can request attainable extrema or joint evaluation.
The old finite width-table API also remains available. An authentic expression
need not possess a materialized table of every possible result.

**Structural elaboration authority.** A decision about Scala graph creation
still requires a fixed condition, supported native generate capture, or explicit
rejection. A stored legality condition is not an assumption that narrows a
structural domain. The pipeline library's hardware-construction precondition
uses `ElabControl.requireStructuralCondition`, not deferred user legality.

The old correlation guards are not deleted. Legacy frontend-composed ASTs
without the corresponding native authority still reject; the frontend tests
keep that negative path distinct from explicit native typed composition.

## Packed widths and ordinary arithmetic

Native arithmetic constructs and authenticates its AST without calling the
Cartesian evaluator. A separate, memoized compositional **enclosure** is derived
from authenticated leaves. It is a conservative safety result, not an invented
exact-domain table or a claim that either endpoint is attainable. This enclosure
checks portable checked-Int arithmetic and may establish positivity cheaply.

Publication and captured-owner validation use that authenticated enclosure;
they do not request the exact value image. For example, with A and B each in
1..2048, `(a + b) % (a + 1) + 1` has 4,194,304 input tuples. It publishes without
joint enumeration. An explicit exact maximum query may still reject when its
non-separable proof exceeds the supported budget. The conservative positive
enclosure is enough to publish this width without a clamp or diagnostic.

The exact user fixture remains unchanged:

```scala
class RecordWidth(dataBits: ElabInt, generationBits: ElabInt) extends Component {
  setDefinitionName("RecordWidth")
  val record = in Bits((dataBits + generationBits) bits)
  val observed = out Bool()
  observed := record.orR
}
```

With `HdlInt.param("DATA_BITS",32,1,2048).asElabInt` and
`HdlInt.param("GENERATION_BITS",32,2,64).asElabInt`, the native output is:

```verilog
module RecordWidth #(
  parameter integer DATA_BITS = 32,
  parameter integer GENERATION_BITS = 32
) (
  input wire [(DATA_BITS + GENERATION_BITS)-1:0] record_1,
  output wire observed
);
  assign observed = (|record_1);
endmodule
```

The native emitter chooses `record_1`; the DUT is never renamed or rewritten by
the test harness. Default/minimum/maximum admitted widths are 64/3/2112. The
realistic fixture preserves three independent parameters in
`dataBits + 7 + generationBits + lanes + 16 + 1`.

Automatic derived-localparam factoring is deliberately a follow-up. It would
add declaration naming, dependency ordering and scope ownership through native
canonicalization and child binding. Direct expressions already satisfy this
repair without adding a separate factoring transformation or emitter text pass.

## Child actuals

Ordinary inherited child widths preserve authenticated shared declaration axes.
Explicit scalar formal bindings can also carry a compound actual, including a
non-separable expression that has no exact product value table. A fresh
single-root child formal retains the child's structural domain. The actual's
conservative enclosure must fit that formal's declared range; no Cartesian
proof is requested solely to produce `.WIDTH(parent_expression)`.

This does not infer unrelated formal identities from a default width. Existing
full-connection and canonical/actual identity checks remain. Arbitrary
multi-formal remapping, projected compound formal bindings and unsupported
structural construction remain rejected.

## Concrete and symbolic require

Concrete conditions are evaluated immediately. A known false condition fails
without emitting HDL. A symbolic predicate proved universally false likewise
fails with `SPINAL-ELAB-REQUIRE-ALWAYS-FALSE`.

A mixed or not-universally-classified symbolic condition is a legality
**obligation**, not approval of all tuples. In native parameterized mode it is
retained on its exact Component owner, even when the default tuple violates the
condition. The core `ComponentEmitterVerilog` emits the diagnostic directly:

```verilog
`ifndef SYNTHESIS
  generate
    if (!($signed(A) >= $signed(B))) begin : g_morphhdl_parameter_legality_0
      initial $fatal(1, "%s", "MorphHDL parameter legality failed: A must be >= B");
    end
  endgenerate
`endif
```

The simulation task is excluded from synthesis. Arithmetic, parameters, ports
and hardware remain outside the guard. A failed `require` is a fatal contract
violation, so the native emitter uses one `$fatal`, not `$error` or duplicate
`$error`/`$fatal` reports. Its `1` argument is the standard finish-number; it
must not be confused with a portable process exit-code setting. The driver
requires a nonzero process status, the original legality message, and no
continuation beyond the failure. Unrelated crashes do not count as rejection.
The fixed `"%s"` format keeps percent directives, quotes, backslashes and newlines
in user messages literal. Icarus and Verilator runtime probes exercise valid
and invalid tuples from the SAME generated file. Icarus `-DSYNTHESIS` checks
that diagnostics disappear; Yosys checks safe physical widths and proves the
reduction. A synthesis build of an invalid tuple does NOT make that tuple legal.

Universally true/false classifications use compositional proofs where possible.
`Unknown` means no universal outcome was established, not that the default was
accepted or that two witness tuples were necessarily found. Strong structural
queries can still ask the exact engine to distinguish a mixed domain.

The MorphVerilog publication entry point explicitly enables native legality on
its private configuration copy; its independent witness path explicitly disables
it. `ParameterizedVerilogMode` remains a baseline-compatible flags-only marker.
Setting that marker alone does not authorize deferred requirements, and native
legality setup must not leak into a subsequent marker-only SpinalVerilog call derived from
the caller's original configuration. The unchanged external-boundary fixture
still compiles the marker against the immutable Increment 0 native sources.

Only an active native parameterized Component can retain a deferred obligation.
Mixed requirements under a captured structural branch currently fail with
`SPINAL-ELAB-REQUIRE-STRUCTURAL-SCOPE-UNSUPPORTED`. They need branch activation
and transactional capture ownership before they can safely be deferred. The
compiler does not incorrectly promote a branch-local requirement to a global
one. Existing supported single-root structural branch projection remains intact.

Parameter schemas are compiler admissibility contracts. This repair emits
explicit symbolic requirements and uncertain-width positivity obligations; it
does not automatically emit every parameter schema bound as an assertion.
Overrides outside the declared schema remain outside the admitted contract.

## Potentially nonpositive symbolic widths

A genuinely symbolic raw width with a positive elaboration witness, but no
proven positive enclosure, uses a distinct authenticated physical-width AST:

```verilog
input wire [(($signed((A - B)) > 0) ? (A - B) : 1)-1:0] din;
```

Raw positivity is retained as a separate guarded legality obligation. A zero or
negative raw override therefore has an intentional one-bit physical range and
a simulation failure, never an accidental `[-1:0]` range. This is not evidence
that the raw width is positive. Widths whose schemas/enclosures already prove
positivity do not acquire this substitution. Invalid concrete/default widths
still fail immediately; arbitrary untrusted ASTs cannot request a clamp.

The signed comparison fence is significant: a preliminary unsigned-context
range expression behaved differently in the older local Verilator tool. The
adopted signed form was checked using actual native DUTs. Existing native range
replacement code now quotes its replacement string so a legitimate `$signed`
AST is not accidentally interpreted as a regular-expression capture reference.

With `SYNTHESIS` defined, diagnostics are absent and an illegal tuple retains
the safe physical width. A passing synthesis run for that tuple does **not**
make the tuple legal. Production configuration validation must obey the
parameter schemas and legality requirements before synthesis.

## Limits and safety

Checked Int arithmetic remains mandatory. A conservative enclosure that cannot
establish in-range arithmetic is rejected rather than fabricating exact
metadata or permitting possible overflow. Division/remainder still need a
nonnegative dividend and a provably positive divisor in this native path.

Existing per-root exact-domain limits remain. Certificates retain the current
32-axis and 256-normalized-term resource limits. The stronger non-separable
exact fallback is capped at 65,536 tuples; exceeding it rejects the requested
proof, not ordinary otherwise-safe symbolic publication. Unsupported joint
structural conditions reject explicitly. Single-root capability contracts in
Counter, structural Vec/memory construction and library adapters are not
silently widened by publication provenance.

Copied and stale expressions, unauthorized branch escape, conflicting schemas,
same-name independent declarations, equal-default root substitution and
unsupported legacy frontend authority remain negative regressions. No PROFILE,
specialized module family, Dan RTL, unrelated wire pass or application-specific
CDC rewrite is part of this change.

## Executable validation

```sh
cd repro/independent-parameters
sbt -batch 'runMain repro.IndependentParameterRepro out'
sbt -batch 'runMain repro.IndependentParameterRepro out-repeat'
cmp out/RecordWidth.v out-repeat/RecordWidth.v
python3 -m unittest -v test_check test_qualify test_layering test_symbolic_policy
python3 check.py
cd ../..
python3 repro/independent-parameters/qualify.py --scala 2.12.18
python3 repro/independent-parameters/qualify.py --scala 2.13.12
```

`check.py` generates each original fixture twice in separate invocations,
compares published bytes without normalization, then uses one unchanged file
for the requested Verilog-instantiation override matrix. It checks actual DUT
and child widths, parameter values, every individual input bit, the highest bit,
all-zero reduction and return to zero. It also invokes
`check_symbolic_publication.py`, which repeats this discipline for overlapping
arithmetic, compound children, safe subtraction widths, mixed requirements,
symbolically invalid defaults and parameters referenced only by requirements.

The policy matrix runs Icarus, Verilator with assertions enabled, and Yosys with
`SYNTHESIS` defined. It includes legal tuples, equal A/B (zero raw subtraction),
A below B (negative raw subtraction), both outcomes of mixed requirements, and
an independent Yosys SAT reduction oracle. The emitted DUT hash is checked
throughout. Guard-placement, unrelated-crash and zero-exit negative controls
protect the verification driver itself.

`qualify.py` starts a clean SBT build and requires every expected frontend and
selected native JUnit case with zero failures/skips. Local
`--java-classpath` driver runs are explicitly recorded as offline-classpath
qualification using rebuilt changed modules and archived unchanged dependencies;
they are never represented as clean SBT or exact-final-head GitHub CI.

## Inherited qualification integration

The native approval policy and canonical byte-span manifest must agree as well
as individually validate. The dedicated independent-parameter workflow now
regenerates the canonical manifest from the reviewed policy and requires a
byte-for-byte match. Its explicit policy entries include the authenticated
product-provenance and native-legality sources; no path wildcard or source-audit
exception is added.

The inherited 60f report validator retains all frozen predecessor catalogs.
PR188 is an additive, source-authenticated successor requiring 2047 executed
cases in 200 suites across eight projects, including 1162 native cases in 109
suites and 265 frontend cases in 23 suites. Only the complete source cluster
from the verified immutable overlay enables that catalog. XML alone, missing
sources, changed baseline identities, missing/new/renamed cases and skipped
reports cannot qualify it. The selected clean-build gate additionally requires
336 native cases in 33 suites and all 265 frontend cases. These are mandatory
inventories, not a claim that a pending final-head run has passed.
