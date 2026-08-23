# Increment 52 — nested symbolic control flow and side-effect safety

Increment 52 extends the source-proven native-`Int` conditional capture from
Increment 51 to recursively nested alternatives. It does not introduce a new
backend IR: nested regions reuse MorphHDL's existing `ParameterizedStructure`
tree and generic Verilog-2001 generate lowering.

## Recursive capture

Every proven native predicate still carries:

- an ordinary Scala `Boolean` witness for concrete SpinalHDL elaboration;
- a canonical child-definition expression rooted at the formal parameter; and
- a parent-instance expression rooted at the exact actual parameter.

When MorphVerilog capture is enabled, a conditional inside another captured
alternative opens a child structural block. The resulting tree preserves
source order and nesting. A bounded depth guard prevents accidental recursive
elaboration from producing an unbounded capture stack.

Ordinary `SpinalVerilog` remains unchanged: it executes only the witness-selected
source path and records no parameterized structural region.

## Safe structural contract

The accepted alternative body is intentionally hardware-oriented. It may use:

- nested proven native symbolic conditionals and source-ordered else-if chains;
- finite immutable range loops;
- immutable local values, including derived shadow-native integer predicates;
- ordinary SpinalHDL components, Areas and ClockingAreas;
- registers, memories, declarations and component instances;
- normal SpinalHDL naming APIs; and
- supported hardware connections and assignments such as `:=` and memory ports.

These operations are executed independently while each structural alternative
is captured, then relocated into the corresponding MorphHDL-owned block. The
ordinary concrete graph continues to retain only the default-witness path.

## Rejected Scala effects

The compiler classifies effects from the source AST before an alternative is
evaluated. A rejected body is wrapped in a capture-only guard. Therefore:

- MorphVerilog fails before executing the effect; and
- ordinary concrete SpinalHDL retains normal source behavior.

The fail-closed categories are:

| Category | Diagnostic |
|---|---|
| mutable Scala variables or assignment | `MORPH-FRONTEND-NATIVE-INT-SYMBOLIC-CONDITIONAL-MUTABLE-STATE-UNSUPPORTED` |
| console, file, stream or socket I/O | `MORPH-FRONTEND-NATIVE-INT-SYMBOLIC-CONDITIONAL-IO-UNSUPPORTED` |
| reflection and accessibility changes | `MORPH-FRONTEND-NATIVE-INT-SYMBOLIC-CONDITIONAL-REFLECTION-UNSUPPORTED` |
| random values, wall-clock time and UUID generation | `MORPH-FRONTEND-NATIVE-INT-SYMBOLIC-CONDITIONAL-NONDETERMINISM-UNSUPPORTED` |
| return, throw or try/catch/finally | `MORPH-FRONTEND-NATIVE-INT-SYMBOLIC-CONDITIONAL-CONTROL-EFFECT-UNSUPPORTED` |
| while/do-style mutable loops | `MORPH-FRONTEND-NATIVE-INT-SYMBOLIC-CONDITIONAL-UNBOUNDED-LOOP-UNSUPPORTED` |
| synchronization, threads, process execution and runtime loading | `MORPH-FRONTEND-NATIVE-INT-SYMBOLIC-CONDITIONAL-ARBITRARY-EFFECT-UNSUPPORTED` |

The scanner does not infer safety from a concrete witness or from generated
names. Unsupported source effects remain rejected even when the default
witness would not select that alternative, because MorphVerilog must capture
every source branch.

## Validation boundaries

The implementation remains MorphHDL-owned. It does not modify upstream-owned
SpinalHDL runtime, library, phase, emitter or compiler-plugin sources. Existing
driver, latch, clock, reset and hierarchy checks continue to run after nested
capture. A source-boundary guard rejects value-keyed provenance, emitted-name
recognition and native-source modifications.

## Proof matrix

`NativeIntNestedSymbolicControlFlowTests` proves:

| Contract | Evidence |
|---|---|
| nested structure | outer and inner native predicates lower as recursive generate regions |
| bounded loops | finite immutable loops inside alternatives retain every hardware instance |
| locals | a derived native local controls a nested predicate without witness matching |
| sequential hardware | branch-local registers and ClockingAreas remain in their structural region |
| memories | a branch-local memory declaration, write and read path are retained |
| Areas and naming | branch-local Areas and explicit instance/data names survive lowering |
| assignments | SpinalHDL connections and memory ports remain legal after relocation |
| effect safety | mutation, I/O, reflection, nondeterminism and arbitrary Scala effects fail before execution |
| concrete parity | ordinary SpinalVerilog elaborates only the witness-selected nested path |
| determinism | repeated elaboration emits byte-identical Verilog |

The permanent `MorphHDL native Int nested symbolic control flow` workflow runs
the new suite and inherited Increment 48 through 51 contracts on Scala 2.12.18
and Scala 2.13.12. The full repository baseline continues to provide strict
Verilog-2001 validation, deterministic generation and inherited semantic
checks.
