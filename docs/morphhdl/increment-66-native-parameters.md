# Increment 66 — Native parameter values and readable RTL

Local implementation is on `work/remaining-parameterized-increments`, together
with CDC-LEG-01, 64 and 65. Remote qualification and merge remain outstanding.
The work excludes unmerged 59i, the retired wire-assignment-pass roadmap and
application changes. Completion boxes remain unchecked until qualified closure.

## Supported APIs

`U(value: ElabInt, width: BitCount)` constructs an explicitly sized unsigned
hardware value. The complete admitted value domain must fit that width. Native
publication retains a typed carrier through arithmetic, concatenation, comparisons
and both shift operands. It never publishes the default witness as the value.
Existing `ElabValue.uintLike` remains available. This increment adds neither
`S(ElabInt, ...)` nor `B(ElabInt, ...)`, nor a new parameterized-width overload.

```scala
val bytes: ElabInt = busLogBytes.pow2
val value = out UInt(8 bits)
val plusThree = out UInt(9 bits)
value := U(bytes, 8 bits)
plusThree := U(bytes, 9 bits) + 3
```

`morphhdl.frontend.formalParam(actual: ElabInt, name, minimum, maximum)` and
`spinal.core.ElabFormalParameter` declare a child-local scalar formal from an
exact typed actual. Use an explicit `ElabInt` annotation when that formal feeds
ordinary Scala structural control that the frontend captures before typechecking.
Separate declarations retain separate identity even when defaults coincide.
Derived parent actuals, scalar logarithms and zero-valued non-width formals are
supported. A zero packed width is not authorized by a scalar formal. Existing
HdlInt formals now retain their declarations even when only derived widths use
them. Native simulation guards reject out-of-domain and X/Z overrides.

```scala
@dontName val width: ElabInt = formalParam(actualWidth, "WIDTH", 1, 64)
@dontName val stages: ElabInt = formalParam(actualStages, "STAGES", 2, 4)
val din = in Bits(width bits)
```

`TypedLocalUInt(name, value, fixedWidth)` creates an immutable module-local packed
unsigned constant. `value` may be an ElabInt or an Int-bounded BigInt. Values are
nonnegative and at most `Int.MaxValue`, and must fit the declared 1–4096-bit width
throughout their admitted domain. Larger unsigned integer values and signed locals
are not claimed. `.elab` creates elaboration dependencies; `.asUInt` produces a
hardware expression with its packed width. Locals remain non-overridable.

```scala
val control = TypedLocalUInt("ADDR_CONTROL", baseAddress, 8 bits)
val status = TypedLocalUInt("ADDR_STATUS", control.elab + 4, 8 bits)
switch(address) {
  is(control) { selected := True }
  is(status)  { selected := False }
}
factory.readAndWrite(controlRegister, control)
factory.onWrite(status) { event := True }
```

Custom switch keys must match the unsigned selector width. Both AXI4 and AXI-Lite
slave factories support named single-address read, write, readAndWrite, onRead and
onWrite mappings. Bus address width must match the local width; existing alignment,
masking and side-effect rules still apply. Named case arms must be disjoint across
the admitted domain. Ranges and masks retain their existing paths. Reuse one handle
for a shared constant; separate names for the same retained expression identity are rejected rather
than silently choosing a name. Duplicate/illegal names, collisions, foreign-owner
handles and foreign dependencies fail. Declarations are eager and immutable, so
forward references and dependency cycles are not a supported API.

## Conditional procedural loops

Ordinary Scala Int ranges still execute during elaboration and unroll. Use the
existing captured HdlRange with the minimal explicit conditional selector:

```scala
(0 until lanes).named("selected_lane", "lane").foreach { lane =>
  lane.whenSelected(selected) {
    assembled(lane * pixelWidth, pixelWidth) := pixel
    mask(lane * HdlInt.literal(1), HdlInt.literal(1)) := B(1, 1 bits)
  }
}
```

Supported bodies contain one contiguous indexed write per distinct packed
combinational target, with ordinary defaults before the loop. The native emitter
retains assignment priority and procedural four-state `if` behavior. One-bit
writes emit bit selects; wider writes emit indexed part-selects. Native process
partitioning allocates distinct deterministic integer loop variables, including
when the requested name collides with an existing signal. Sequential targets,
nested control/loops, overlapping writes and target reads fail explicitly.
This is not a general loop optimizer or a hardware loop-counter register.

## Native resize and shared authority

Proven same-width resizes publish the source; proven widening publishes only the
necessary sign/zero extension; proven narrowing publishes a slice. Unproved width
relations retain conservative lowering. All decisions use exact retained width
and assignment identities, not generated-text matching. The shared Increment 64
localparam graph retains derived parameter dependencies and ownership. Child
width composition preserves parent localparam references only with exact proof;
legacy inferred concrete carriers and native resized clones retain their existing
hierarchy rewrite.

## Reproduction and evidence boundaries

Run the permanent suites `NativeConditionalProcessTests`,
`NativeResizeSimplificationTests`, `NativeSymbolicHardwareValueTests`,
`NativeExplicitFormalTests`, `NativeFormalOwnershipTests`, `NativeCdcFormalTests`,
`NativeTypedLocalConstantTests` and `NativeNamedFactoryTests` in project `morph`.
`morphhdl.examples.Increment66ArtifactWriter` writes representative after-RTL;
pass an absolute output directory because runMain uses the subproject directory.
Historical before-RTL and rejected probes are under `evidence/increment66/baseline`.

The suites independently exercise strict Verilog parsing, lint, synthesis,
two-state equivalence and four-state simulation. CDC tests cover digital latency,
reset and Gray conversion; they are not physical metastability qualification.
Factory tests cover both real implementations, AXI4 bursts/IDs, independent
AXI-Lite channels, stalls, resets, byte strobes, address masking and event counts.
These fixtures do not qualify a production application's CSR map.

Set `MORPHDL_INCREMENT66_EVIDENCE` to an absolute directory to retain tool commands,
source identity, input contents/hashes and complete output logs for the new suites.
Remote targeted qualification, final full CI and merge receipts are separate
requirements; local success alone does not complete this increment.

## Local completion receipt

Compiler/test checkpoint `b6e83061d8c2b1ece94e76abadc956a2201a99dc` passed both
Scala lanes. Across focused and subsequent interaction runs, each lane passed
447 distinct runtime, 35 native and 18 plugin tests, plus two legacy AXI4 formal
tests. Binary and legacy source compatibility passed in both lanes. All 29
representative RTL files match byte-for-byte across Scala versions. Native and
inherited source audits, relocation controls and exact additive report-catalog
controls pass. Actual inputs, logs and digests are retained in
[the evidence directory](evidence/increment66/README.md). CI/merge remain pending.
