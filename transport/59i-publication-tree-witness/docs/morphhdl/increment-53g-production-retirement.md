# Increment 53g — production retirement of native-`Int` reconstruction

## Result

Increment 53g removes the parser-wide native-`Int` reconstruction architecture
from the production path. Parameter-sensitive library code now reaches the
backend through explicit `ElabInt` / `ElabBool` carriers, exact typed domains,
typed structural ownership and `ElabFormalComponent` bindings.

This increment is not a claim that an ordinary Scala `Int` can remain symbolic.
It establishes the opposite boundary: production code must not rediscover a
parameter by observing a concrete witness, a source location, a component
filename, or an emitted hardware name.

At Increment 53g closure, the roadmap checkbox remained open until the
canonical dual-Scala workflow passed and the exact source scope was reviewed.

## Deleted production boundary

The following files form the closed deletion set enforced by
`morphhdl/contracts/increment-53g-production-retirement.contract`:

| Layer | Retired files |
| --- | --- |
| Frontend API and publishers | `NativeIntShadow.scala`, `NativeIntSymbolicConditional.scala`, `NativeMemAutoProvenance.scala`, `formalComponent.scala`, `formalRegion.scala`, `ExternalAnalyzedNativeIntFormalizationPublisher.scala`, `ExternalParameterizedCounterRegistry.scala` |
| Compiler plugin | `MorphHdlNativeIntShadowExpressionComponent.scala`, `MorphHdlNativeAxi4SlaveFactoryParameterizationComponent.scala` |
| Runtime registries and sidecars | `ExternalNativeAxi4SlaveFactoryParameterization.scala`, `ExternalNativeIntCompilerRuntime.scala`, `ExternalNativeIntFormalComponent.scala`, `ExternalNativeIntFormalizationRegistry.scala`, `ExternalNativeIntShadowExpression.scala`, `ExternalNativeIntShadowRegistry.scala`, `ExternalNativeIntStructuralPublisher.scala`, `ExternalParameterizedResizeRegistry.scala` |

`ExternalParameterizedVerilogNativeFallback.scala` and
`ExternalParameterizedAutoResize.scala` remain because they still publish or
normalize typed graph evidence. Their native-Counter, shadow-registry,
name-recovery and legacy resize-registry arms are retired; retaining the file
does not retain those authorities.

The shared `ExternalFormalParameterRegistry` also remains for the explicit
`formalParam` leaf path and opaque typed formal capabilities. Its orphaned
owner-explicit attach/retain transactions are removed, together with the
Counter-era `ParameterizedWidth.attachExistingAll` helper; production can no
longer publish a component-only formal after a native constructor returns.

## One compiler path

At Increment 53g closure, the default compiler plugin had exactly two phases,
in this order:

1. `MorphHdlTypedElaborationControlComponent`
2. `MorphHdlNaturalSymbolicConditionalComponent`

The first phase lowers control flow only when lexical types prove an explicit
`ElabInt` or `ElabBool` carrier. The second preserves the existing explicit
`HdlInt` / `HdlBool` frontend. Neither phase is allowed to recognize
`StreamFifo`, `StreamWidthAdapter`, `Counter`, `Mem`, `Axi4SlaveFactory`, a
particular Scala filename, or an emitted RTL name.

### Increment 54 historical amendment

Increment 54 deliberately extends that sealed sequence to exactly three phases
while preserving the original order:

1. `MorphHdlTypedElaborationControlComponent`
2. `MorphHdlNaturalSymbolicConditionalComponent`
3. `MorphHdlFrontendSymbolicEqualitySafetyComponent`

The third phase rejects reverse Scala equality whose right operand is
statically proven to be a MorphHDL symbolic frontend type. Moving this
Morph-specific check out of `idslplugin` keeps the generic SpinalHDL plugin
Morph-free. The retirement guard now seals this exact three-phase order;
removing, inserting, renaming or reordering a phase fails. This amendment does
not rewrite the historical fact that 53g originally closed with two phases.
Its exact phase name is `morphhdl-frontend-symbolic-equality-safety`; it runs
after `idsl-plugin` and `uncurry`, before `explicitouter`, and preserves
`MORPH-FRONTEND-SYMBOLIC-COMPARISON-UNSUPPORTED` at the original `Apply`
position.

## Negative production contract

`morphhdl/scripts/check-production-retirement.py` scans every repository
`src/main` root, not a hand-selected subset. Its manifest has a closed schema;
the source roots, six complete rule definitions and the 17 deleted paths are
also fixed in the checker so the manifest cannot silently narrow the audit.

The checker enforces six deny rules:

| Rule | Rejected production behavior |
| --- | --- |
| `retired-native-int-production-symbol` | Any reference to a deleted shadow, formalization, AXI, automatic-memory, resize or Counter-registry symbol |
| `retired-constructor-boundary-api` | Reintroduction of native constructor/region capture, owner-explicit formal retention or atomic attachment APIs |
| `file-specific-compiler-eligibility` | Selecting compiler behavior from a Scala filename or a recognized component-name string |
| `source-position-alias-reconstruction` | Parser-wide native-`Int` reconstruction that treats a source position as a symbolic alias or constructor-boundary key; explicit formal declarations are not reconstruction |
| `witness-value-inference` | Searching or grouping graph objects by a concrete witness, default, or observed bit width |
| `emitted-name-recognition` | Searching or grouping graph objects by emitted component, instance, or signal names |

There are no production allow-list exceptions. Historical examples may remain
only outside `src/main`, such as a narrowly scoped regression fixture or this
architecture record; they cannot be packaged as production classes.

The guard also inspects packaged frontend, runtime, plugin and orchestration
JARs. It rejects the retired top-level classes and every Scala companion or
nested class below their class-name prefixes. This catches stale classes even
when a source deletion was followed by an incomplete build cleanup.

## Guard self-test

`check-production-retirement.py --self-test` creates an isolated temporary
repository tree. It proves all of the following without modifying the checkout:

- a typed-only production tree and a historical test-only reference pass;
- restoration of any retired production file fails;
- an independent fixture for each of the six deny rules fails;
- the amended exact three-phase descriptor passes and appending a fourth
  default compiler phase fails;
- narrowing any canonical rule definition or source prefix fails;
- a clean JAR passes and a JAR containing a retired Scala companion fails.

The old native-source, typed-overlay, native-shadow, native-conditional and
external-formalization script paths remain as compatibility entry points for
stable required checks. Under 53g they delegate to the negative retirement
guard; they no longer require the deleted machinery to exist. Historical
worktrees without the 53g manifest retain the original typed-overlay audit.

## Canonical validation

`.github/workflows/increment-53g-production-retirement.yml` is the merge gate.
Every execution lane uses Scala 2.12.18 and 2.13.12 where Scala compilation or
elaboration is involved.

| Gate | Required evidence |
| --- | --- |
| Source contract | Guard syntax, isolated self-tests, production absence, compatibility entry points and exact source inventory |
| SBT full | All production/test compilation, full MorphHDL tests, native compatibility tests, production packaging and JAR absence |
| Mill full | Full MorphHDL tests and the same native compatibility targets through the independent Mill build |
| Focused typed | Typed carrier/domain/control, formal binding, memory, Vec, Counter, Stream/Flow, FIFO, adapter, hierarchy and adversarial ownership suites |
| V2001 / simulation / synthesis | Strict IEEE-1364-2001 contracts plus Verilator, Icarus and Yosys coverage of migrated typed designs |
| Formal | StreamWidthAdapter, Vec, primitive closure and StreamFifo equivalence with live mutation controls, plus the native Counter formal test |
| Determinism | Byte-identical repeated emission for the core emitter, adapter, FIFO and enum-localization paths |
| Closure | Every preceding result must be `success`; a skipped gate cannot close the increment |

The shared V2001 contract keeps its narrow Verilator 5 warning names. When the
canonical pinned container supplies pre-5 Verilator, the runner translates only
`UNUSEDSIGNAL` to the historical `UNUSED` family and the split width warnings
to `WIDTH`; every other lint option and every modern-tool invocation is
unchanged.

The permanent workflows that previously proved native-`Int` shadow,
formalization or AXI recognizer behavior retain their workflow and job names
where practical. Their semantics are reversed: they now prove production
absence and exercise a typed replacement path. Permanent memory, StreamFifo and
StreamWidthAdapter workflows no longer select deleted historical test suites.

## Exact source-scope sealing

`morphhdl/contracts/increment-53g-source-scope.txt` starts with the explicit
sentinel `# INCREMENT-53G-SOURCE-SCOPE-UNSEALED` while parallel implementation
work is in progress. The canonical source job deliberately rejects that
sentinel, so an unreviewed moving file inventory cannot close the increment.

At closure, replace the sentinel with the sorted, unique output of the
Increment 53g branch diff against `parameterized-verilog`. Include added,
modified, renamed and deleted paths. Exclude only
`docs/morphhdl/parameterized-verilog-todo.md`: the workflow validates its exact
53g checkbox line separately, which permits the final checkbox-only closure
commit without changing the reviewed implementation inventory.

The source job compares the sealed contract byte-for-byte with the branch diff
on the implementation PR. On later `parameterized-verilog` pushes it still
validates the sealed contract format and the complete negative retirement
boundary, without trying to reconstruct the already-merged PR base.

## Closure conditions

Increment 53g may be marked complete only after:

1. the source-scope sentinel has been replaced by the reviewed exact inventory;
2. the guard passes against sources and freshly packaged JARs;
3. every dual-Scala SBT, Mill, focused, V2001, formal and determinism matrix cell
   passes;
4. the canonical closure job reports success; and
5. the roadmap checkbox is changed in a separate closure step.

Until then, this document describes the implemented architecture and its merge
gate, not a completed checklist claim.
