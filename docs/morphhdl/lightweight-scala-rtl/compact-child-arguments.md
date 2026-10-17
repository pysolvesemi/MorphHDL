# Compact child argument ownership (LWR-31)

The hierarchy validator previously passed retained product-domain arguments to
`ElaborationProductDomain.owner` with an identity finite-set callback. That
legacy entry point deliberately rejects compact domains because it has no
compact-owner validator. Even `new Leaf(timeout - 1)` with a parent-owned
TIMEOUT domain 32..16777216 therefore failed before binding publication.

Hierarchy validation now uses `ownerWithCompact`. The finite callback resolves
the exact native child domain; the compact callback authenticates the immediate
parent/child object identities, the retained parent formal schema and its exact
root, and the enclosing instance chain. Equal names, domains or defaults do not
substitute for declaration identity. Captured structural instances require an
explicit compact projection capability and remain unsupported. No compact domain is
enumerated, narrowed or replaced by its construction default.

The existing formal actual bounds, retained dependency inventory, child legality
requirements, native port geometry, parameter overrides and binding validation
remain in force. This change supplies ownership evidence, not a proof of an
arbitrary correlated expression or permission to ignore child constraints.

Errors identify the parent module, child instance/definition, formal slot,
actual expression, participating domains/defaults and Scala declaration location.
When a native formal's actual has no source span, the declaring Component's
constructor frame is retained as diagnostic metadata. That frame grants no
parameter or ownership authority and does not affect emitted comments.

## Focused validation

Both Scala 2.12.18 and 2.13.12 builds pass. Ten affected suites pass 80 tests
on each version (160 total): compact child arguments/owners, scalable widths,
correlated values, captured width equivalence, independent domains, symbolic
publication, typed compound ports and hierarchy bindings.

Direct forwarding, arithmetic and logarithmic arguments are simulated at
TIMEOUT overrides 32, 33, 1023, 1024, 65536, 16777215 and 16777216. Checks cover
zero, threshold boundaries, maximum data and randomized data, symbolic binding
retention and repeat-generation determinism. Negative tests retain child domain
guards, unrelated equal-schema owners, wrong children, schema copies, forged
expression certificates and unsupported structural capture. The foreign-owner
rejection asserts the complete binding context and Scala file name.

Recorded tool commands across both versions include 116 Icarus compilations,
116 simulations, 14 Verilator lint runs and 30 Yosys checks. Sixteen simulation
exits are deliberately failing parameter-guard controls, asserted by their tests.
Questa is unavailable. All 62 application Scala sources compile with both plugins.
A diagnostic-only replay of the legacy ownership path identifies the original
binding as `DisplayControllerClockResetCdc.probe_0.CLOCK_TIMEOUT = C_CLOCK_TIMEOUT`,
with domain 32..16777216 and default 1024, at `CdcChannels.scala:28`. The direct
forwarding regression covers this form in addition to compound arithmetic.

The first final-source replay timed out after 600 seconds while running
concurrently with the diagnostic overlay under memory pressure. It is not a
production pass. The isolated replay also reaches the unchanged 600-second limit (recorded exit
124), so resource contention is not the only factor. A thread snapshot at about
492 seconds places it in `ParameterizedStructure.projectedObjectEvaluationOf`,
called by `NativePublicationWidth.evidence`, `NativeWidthFormalSchema.witness`
and later `ExternalParameterizedVerilogHierarchy.analyzeInstance` binding work.
The old compact-owner error is absent, but incomplete publication is **not** a
production pass. Production lint/cleanup and application simulation were not
reached. This remaining publication cost is not hidden by a raised timeout or
weakened validation. Final source-integrity results are recorded with the
published commit/tree in the separate closure receipt. The historical failure reproduction,
diagnostic-only overlay, and final-source validation are recorded separately. No full CI or
full qualification is requested by this increment. Application source and
delivery RTL are not changed.
