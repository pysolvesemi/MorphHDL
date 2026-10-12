# Guarded Vec publication and explicit shared definitions (CA-032–035)

These changes retain native assignment, declaration, scope and instance identities.
They do not change application code, the operator topology of `reduceBalancedTree`,
register placement, or the decreasing-metric rules for recursive edges.

* CA-032: the native assignment handoff now includes captured structural writes.
  Identical target/source text in separate generate alternatives retains separate
  assignment identities. Shared-process splitting still proves mutually exclusive
  owners and exact assignment capacities; text equality does not authorize a write.
* CA-033: constant Vec wire promotion consumes the complete authenticated guarded
  assignment set. Every driver must be an exact whole-leaf native assignment.
  Each assignment stays in its original generate branch. Literal drivers use an
  initialization process; signal-driven branches use combinational processes.
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
