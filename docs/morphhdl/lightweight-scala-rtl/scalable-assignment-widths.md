# Scalable native assignment width checks (LWR-30)

The production failure was identified as `CdcClockProbe.probe_age := 0`:
`ceil_log2(CLOCK_TIMEOUT + 1)` was compared with the zero literal's construction
width, 11. CLOCK_TIMEOUT admits 32..16777216. An eager equivalence query attempted
to build an image of 16,777,185 values before the existing invariant-zero rule
could validate the assignment. An initialized counter alone did not reproduce
it; an explicit clear assignment did.

Complete-domain width equivalence is now evaluated only when assignment
validation needs that proof. Known typed comparisons first use authenticated
expression identities and conservative enclosures. Dominated `min`/`max` arms
are simplified only when their ordering holds throughout the complete domain.
A failed enclosure is not an equivalence proof: the existing bounded exact
fallback is retained for small enumerable domains, while an unresolved large
domain returns unproved. Assignment validation still requires a separate valid
rule or reports a width mismatch. Exact-extrema queries, structural capture,
root identity, enumeration limits and independent parameter domains are unchanged.

Failure diagnostics include the component definition, assignment target/source,
compared width expressions, participating parameter bounds/defaults and retained
source location. Native assignment locations are stored independently of optional
Verilog/VHDL line comments and copied with native source provenance. These strings
are diagnostic metadata only, never width or ownership authority.

## Focused validation

Both Scala 2.12.18 and 2.13.12 builds pass. The six affected suites pass all
61 tests on each version (122 total): `ScalableAssignmentWidthTests`,
`ScalableWidthProofTests`, `CorrelatedValueBoundsTests`,
`CapturedDomainWidthEquivalenceTests`, `IndependentParameterDomainTests` and
`SymbolicPublicationEvidenceTests`.

The reduced counter is compiled in strict Verilog-2001 mode and simulated with
reset, clear, enable stalls and modular updates at timeout overrides 32, 33,
63, 64, 65, 1023, 1024, 1025, 16777215 and 16777216. Yosys checks those ten
geometries for unresolved drivers and latches. Equivalent dominated widths pass
transport simulation; widths equal at the construction default but unequal at
power-of-two boundaries fail with module, expression, domain and source-line
context. Direct proof tests cover every power-of-two boundary in the complete
timeout domain, forged certificates, nonpositive widths and distinct roots.

Across both Scala runs, tool evidence records 70 Icarus compilations, 70
simulations, 24 Yosys checks and 8 Verilator lint runs. Twelve simulation exits
are expected negative parameter-guard controls, independently asserted by their
tests. They are not successful positive simulations. Questa is unavailable.

The application compiles from its current sources with both required plugins.
Fresh production replay with `bitVectorWidthMax=65536` passes the repaired width
check and reaches a separate hierarchy rejection:
`SPINAL-ELAB-DOMAIN-COMPACT-OWNER-UNSUPPORTED`, role `compound child formal actual`,
in `ExternalParameterizedVerilogHierarchy.analyzeInstance`. Its legacy owner
validator does not accept compact domains. The replay exits 1: this is **not** a
production publication pass, and production lint/cleanup were not reached.
The original timeout enumeration failure is absent. Final source-integrity
checks are recorded separately with the published commit and tree.
No full CI or full qualification is requested for this increment. Historical
failure and diagnostic runs are distinct from the final compiled-source results.

## Limits

This adds sound sufficient proofs, not a complete symbolic algebra solver.
Unresolved large-domain comparisons remain unproved and assignments must satisfy
another authenticated conversion rule or fail. Arbitrary callback relations and
exact extrema retain their existing bounded behavior. No legal domain, timeout,
source-review gate or enumeration cap is enlarged. Application Scala and delivery
RTL remain unchanged.
