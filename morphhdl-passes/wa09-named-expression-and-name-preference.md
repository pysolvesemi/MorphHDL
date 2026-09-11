# WA-09 named expression elimination and alias survivor preference

WA-09 is a successor to the completed WA-08 handoff. It adds one bounded pass
and refines direct-alias planning; it does not retroactively change the five
stages, evidence or source anchors qualified by WA-08.

## Six-stage production order

The existing all-or-none production flag keeps `enabled = false` as an exact
legacy opt-out. When enabled, the pipeline reaches a fixed point in this order:

1. unnamed direct-wire alias elimination;
2. named direct-wire alias elimination with survivor preference;
3. unnamed continuous wire-expression inlining;
4. named continuous wire-expression inlining;
5. constant-operand expression simplification; and
6. recursive Boolean ternary simplification.

Historical three-stage WA-07, four-stage WA-07a and five-stage WA-07b selections
remain exact frozen regression/proof contracts. WA-09 appends a new production
identity instead of relabeling an earlier artifact.

## Provenance before length

Name preference is a tie-breaker among declarations that are independently
safe to remove. The precedence is:

1. an identity that is not removable under the existing safety contract;
2. meaningful retained naming provenance (`Explicit` or `Reflected`);
3. a shorter retained meaningful name; and
4. deterministic name and canonical symbol-identity ordering.

`Unnamed` and `Generated` are provenance categories, not name patterns. Thus a
meaningful `abc` survives instead of a generated short temporary in
`assign abc = generatedTemporary;`, even if the backend later spells that
temporary `_zz`. Conversely, an explicitly user-named `_zz` is meaningful and
participates in the ordinary meaningful-name length comparison. Unknown naming
metadata fails closed.

The planner never renames a surviving declaration and never transfers the
loser's name. A non-removable declaration is always the anchor: if the other
side is safely removable, that removable alias is eliminated regardless of
name length. If neither side has a complete removal proof, both remain.

## Named expression inlining

The new pass shares the existing canonical expression eligibility and rewrite
engine. It removes an eligible internal named wire with one full-object
continuous non-reference driver, clones the exact captured expression into all
legal continuous receivers with fresh reference identities and an explicit
packed-type fence, then removes only that declaration and driver.

The required ordinary Scala topology is:

```scala
val bitSource = Bits(width bits)
bitSource := a ^ b
val bitCloneAlias = cloneOf(bitSource)
bitCloneAlias := bitSource
clonedResult := bitCloneAlias
```

The old five-stage enabled pipeline retains the named expression wire:

```verilog
wire [WIDTH-1:0] bitSource;
assign bitSource = (a ^ b);
assign clonedResult = bitSource;
```

Explicitly disabled optimization also retains `bitCloneAlias` and its direct
assignment, preserving the complete legacy chain.

The WA-09 six-stage default/explicit-on result must be:

```verilog
assign clonedResult = (a ^ b);
```

`clonedResult` is an output port and therefore a mandatory survivor. Its longer
spelling does not make it removable. `bitCloneAlias` first loses the safe direct
alias comparison; `bitSource` is then removed by named-expression inlining.

## Native handoff ownership

The native expression bridge captures the actual source and receiver RHS trees
through `NativeWireExpressionCodec`; it offers those exact trees to the
canonical decision and writes the captured source tree back only at whole-RHS
continuous receivers with the same packed kind, signedness, width and exact
retained symbolic-width identity. That receiver preserves the removed
assignment's type boundary. Independently allocated descriptors for the same
width are accepted only after exact parameter-root/schema checks and typed
elaboration equality. Source locations do not define width identity.

The bounded codec models one retained symbolic width family explicitly. This
allows a one-bit named comparison over `WIDTH`-sized inputs without replacing
their widths by the default value. Multiple unrelated width families remain
ineligible. A default width never replaces retained symbolic evidence, and
optimization cannot erase an invalid symbolic assignment before publication
checks it.

No representative XOR or other substitute expression is fabricated.
Unrepresented operators, selected/nested receivers, or incomplete
source inventories fail closed. The shared native preservation, metadata,
clock/control, type, scope and cycle checks remain mandatory, and native
`TypeBool` is represented at width one.

Direct-alias preference also preserves pass ownership. A reverse removal is
allowed only after the canonical named-alias pass validates the actual two-edge
`source := terminal; alias := source` relation and both native edges are
independently safe whole-RHS assignments. The bridge then removes the proven
source identity rather than merely reversing text. If the preferred source has
an expression driver, true `Unnamed` provenance dispatches to
`UnnamedWireExpressionNativePhase`; `Explicit`, `Reflected` and `Generated`
provenance dispatches to `NamedWireExpressionNativePhase`. Unknown provenance
or a failed proof retains the relation.

## Post-pass emitter-created wrappers

Reproduction exposed a second, later boundary: the native and canonical passes
can remove every eligible declaration they observe, after which the Verilog
emitter's `fillExpressionToWrap` planning can materialize fresh anonymous wires
for a nested `Operator.UInt.Add` or for the non-`BaseType` input of a `Resize`.
Those wrapper identities do not exist when the six pre-emission stages run, so
rerunning those stages cannot remove them.

WA-09 therefore permits one narrow emission-planning elision in addition to
the six canonical stages; it is not a seventh pass. The emitter may leave a
nested add expression inline only when the native graph proves an exact
homogeneous, fixed-width, unsigned `UInt` addition tree and a whole-object
fixed-width unsigned receiver. Every add operand and intermediate result must
have the receiver's same proven positive width. A leaf may be a fixed `UInt`
declaration of that width, including a tagged declaration that remains present,
or an exact fixed unsigned widening `ResizeUInt` from a narrower fixed `UInt`.
Every synthetic `Add` or `ResizeUInt` expression node selected for suppression
must itself be unannotated and uniquely used, and the whole-object target must
remain unannotated and singly driven. The would-be wrappers must be
emitter-created expression carriers rather than declarations with source,
observability, metadata or other identity contracts. Thus fixed native 16-to-18
`ResizeUInt` wrappers can inline completely without deleting their input
declarations.

The eligible root is specifically a top-level continuous
`DataAssignmentStatement`; initial assignments and all scoped/procedural
assignment forms retain their wrappers. A widening resize is fixed only when
its exact node has no retained symbolic target in
`ParameterizedWidth.resizeExpressionOf`; a concrete witness value alone is not
width authority.

The positive production witness uses four 16-bit unsigned inputs in an exact
18-bit addition domain, whose maximum is 262140, and verifies that the final
18-bit output contains the nested expression without anonymous add wrappers. A
separate four-input 18-bit witness proves that keeping the original tree inline
preserves its modulo-2^18 overflow behavior; the policy does not reassociate it.
Mixed operand or intermediate widths, signed operands/results, narrowing
resizes, and bit or part selections retain their wrappers. Direct symbolic-width
expression nodes still retain their wrappers. Parameterized inputs are more
precise: tagged parameterized resize carriers remain declared, but their fixed
18-bit `morphhdl_resize*` declarations may be proven leaves of the homogeneous
add tree, so only the emitter-created `Add` wrappers around them inline.
Unsupported operators, incomplete width/type evidence and any non-whole-object
receiver also fail closed. Leaf tags never authorize removing the leaf, and
annotated expression wrappers remain ineligible. This policy must be selected
from native graph/type facts before emission; emitted identifiers or generated
Verilog text never authorize it.

The committed reproduction source is
`morphhdl/src/test/scala/nativeapplication/NestedUnsignedExtendedSumProductionArtifactWriter.scala`,
whose runnable object is
`morphhdl.examples.NestedUnsignedExtendedSumProductionArtifactWriter`. From the
repository root, generate default, explicit-on and explicit-off fixed and
parameterized artifacts plus the same-width modular-overflow control twice with:

```text
sbt "morph/Test/runMain morphhdl.examples.NestedUnsignedExtendedSumProductionArtifactWriter target/wa09-nested-sum"
```

## Safety and remaining limits

WA-09 preserves all WA-05, WA-07 and WA-08 safety anchors. Candidates remain
internal combinational declarations with complete naming, type, scope and
observability metadata. Ports, registers, procedural or multiply driven
signals, hierarchy or black-box boundaries, bidirectional objects, clock/reset/
soft-reset/enable identities, memory-owned values, native metadata and typed
registry references, debug/preservation identities, comments and attributes
are retained. Unknown metadata or naming provenance fails closed.

Source and receiver scopes, exact symbolic width identity, packed kind,
signedness, assignment coverage, selected-use legality, cycle freedom and
four-state behavior must all be proven. An unsupported native RHS is retained;
the bridge must not substitute a representative or guessed expression. This is
not dead-code elimination, common-subexpression elimination, formatting,
general signal renaming, hierarchy flattening, process rewriting or arithmetic
optimization.

Qualification requires dual-Scala canonical and production tests, exact
historical pass-vector controls, deterministic repeated output, fixed-point and
idempotence checks, strict Verilog-2001 parsing/lint/synthesis, four-state
simulation, functional mutations, formal equivalence, and every inherited
source-integrity and compatibility gate on the final commit.
