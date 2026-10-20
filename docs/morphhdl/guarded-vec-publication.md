# Guarded Vec publication and explicit shared definitions (CA-032–041)

These changes retain native assignment, declaration, scope and instance identities.
They do not change application code, the operator topology of `reduceBalancedTree`,
register placement, or the decreasing-metric rules for recursive edges.

* CA-032: the native assignment handoff now includes captured structural writes.
  Identical target/source text in separate generate alternatives retains separate
  assignment identities. Shared-process splitting still proves mutually exclusive
  owners and exact assignment capacities; text equality does not authorize a write.
* CA-033: constant Vec wire promotion consumes the complete authenticated guarded
  assignment set. Every driver must be an exact whole-leaf native assignment.
  Each assignment stays in its original generate branch. CA-038 supersedes the
  original constant-to-initial promotion: fixed guarded/default-override arrays
  retain their native scalar storage and expose a read-only wire aggregate.
  Constants remain continuous assignments; genuine register initialization is
  unchanged. No empty-sensitivity combinational process is introduced.
* CA-034: exact whole-scalar signed writes can drive packed transport before signed
  read aliases are reconstructed. Both `assignFromBits` and `:= bits.asSInt` retain
  signed comparisons. Partial or ambiguous targets are not accepted by this path.
* CA-035: native typed generic publication includes declaration-owned scalar formals
  even when no port width depends on them. The complete native body, formal schema,
  selectors and instance bindings remain checked. Different COUNT/MASK defaults can
  therefore share a recursive operation definition without specializing the body.

## Explicit sibling references

Ordinary real Component instances remain the preferred sharing API. When a caller
needs a typed BlackBox reference to an already present implementation, it can bind
that reference explicitly:

```scala
val implementation = new ParameterizedOperation(actual)
val reference = new OperationReference(otherActual) // typed addGeneric and ports
ElabModuleReference.bind(reference, implementation)
```

The reference and implementation must be direct siblings of the same native parent.
The implementation must be a real Component in the emitted hierarchy. The reference
must name that definition and supply its complete typed integer generic and port
interface, with no inline/external implementation. Publication proves every actual
against the formal domain and every port width after substitution, using exact
expression and owner evidence. This is an ordinary call, not a recursive edge.
The implementation's genuine self-references still require the existing bounded
recursion proof. Name-only peer references remain rejected.

This API does not authorize arbitrary non-local references, mutual recursion,
Boolean/untyped generics, inout ports, or substitutions exceeding the finite proof
budget. It does not merge conflicting operation bodies, clocks, resets or payloads.

## Validation

`GuardedVecPublicationTests` exercises repeated-source branches, mixed guarded
combinational/register writers, signed full-word writes, and recursive shared
COUNT/MASK instances. Packed and unpacked outputs are regenerated for determinism.
Icarus simulations compare independent references, including boundary, randomized
and X/Z data; Verilator lints the Verilog-2001 synthesis view; Yosys elaborates and
checks the complete synthesized hierarchy and absence of latches. Simulation keeps
existing runtime legality guards; the synthesis lint view excludes their legacy
`$fatal` extension. `SharedModuleReferenceTests` checks typed symbolic port
substitution, combined/per-component publication, and rejected identity/interface/
domain/implementation cases. Existing structural, recursive, signedness and Vec
regressions remain required. Local checks do not replace exact-candidate remote CI.

## Composed guarded words (CA-036–038)

Whole signed word writes consume exact native statement receipts when multiple
branches emit identical text. Unnamed branch-local Vec owners receive a weak
fallback name on retained static writes and packed reads, allowing native pruning
to honor existing leaf-retention requests. This does not make an unused Vec vital
or override authored names. Declaration and carrier inventories remain strict. Structural constant-branch
conversion recognizes native declaration attributes and preserves them when
changing the authenticated scalar storage kind.

Guarded/default-override combinational words retain independent native cells.
Publication restores each exact retained cell and bridges a required packed or
unpacked aggregate through continuous read-only assignments. Constant and clocked
siblings therefore need neither a constant-driver initial block nor multiple
storage kinds on a Verilog-2001 variable array. The native register/reset process,
branch ownership and signed operations are retained.

The composed fixtures include four-word padded and eight-word banks, manual
packing, repeated count/level/pipeline guards, COUNT 1–8, masks 0/7, both layouts,
startup and pre-clock checks, asynchronous reset, randomized and X/Z data.
Historical application failures and fresh compiler evidence remain separate.

## Scoped packing and recursive geometry (CA-039–041)

Shrinking recursive child ports are validated over their exact active branch.
For a `COUNT > 1` child, a `((COUNT + 1) / 2) * wordBits` input and its matching
next-level Vec retain that branch's domain. Packed-width expressions are captured
while their authoring scope is active; using a branch-local Vec outside that scope
remains an error.

Logical `Vec.assignFromBits` publication consumes its authenticated native unpacking
slices and support graph. Absent capacity lanes are not treated as unconditional
logical accesses. Guarded static reads retain exact assignment-owner bounds,
including signed and composite payloads. Packing bridges and helpers stay beside
their exact array declaration. An array declared at module scope remains there
when a finite writer and sibling structural branches consume it.

Explicit formal definitions retain the same packing audit wrappers for every
actual default, including an equal-width default. Native comparison uses the
logical derived width and the complete body; different binary operations still
fail same-name sharing. This applies to both shrinking and fixed-capacity public
ports. Instance actuals and the existing recursive termination checks are retained.
Legacy parameter APIs and independently rooted factorized geometry keep their
existing validation paths.

`ScopedVecGeometryTests` covers source defaults 1/5, overrides 1–5, Bits/SInt/Bundle
payloads, both layouts, reversed caller defaults, combined/per-component output,
repeat generation, wrong child geometry, escaped scope and incompatible bodies.
Its independent adjacent-pair subtraction model checks order, odd tails, zero,
all-one, randomized and X/Z values. Each artifact runs Icarus Verilog-2001
simulation, Verilator synthesis-view lint, Yosys synthesis/latch/driver checks and
combinational SAT comparison. Structural lexical tests distinguish comparisons
inside declaration widths from actual declaration initializers. Exact-candidate
remote qualification remains required in addition to these local tests.
