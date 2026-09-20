# Increment 7: MorphVerilog orchestration and validation parity

Increment 7 introduces the supported parameterized-Verilog entry point and
connects its concrete witness to the inherited SpinalHDL checks without copying
their phase sequence.

## Public bounded entry point

The current frontend cannot yet lower one arbitrary Spinal `Component` into
both a concrete netlist and ParamRTL. The honest Increment 7 API therefore owns
two by-name, re-entrant factories:

```scala
MorphVerilog(SpinalConfig(targetDirectory = "rtl")) {
  MorphProgram(
    concreteWitness = new LaneArray(lanes = 4, dataWidth = 8),
    parameterizedDesign = LaneArrayParamRtl.design()
  )
}
```

The concrete factory may run again if Spinal enables its diagnostic replay.
The symbolic factory runs once, and only after the concrete leg is green.
`tryGenerate` returns a staged `Either`; `apply` throws a
`MorphVerilogException` containing the same structured failure.
The success report describes only the public symbolic artifact; it deliberately
does not expose a concrete `Component` whose private output directory has been
removed.

## Shared inherited phase plan

`SpinalVerilogBoot` now consumes `SpinalVerilogPhasePlan`, the single factory
for the inherited Verilog phases. Existing configuration transformation phases,
memory blackboxers, device handling and phase inserters stay on the same path.
Ordinary `SpinalVerilog` behavior and retry handling are unchanged.

The factory exposes these live stable IDs in order:

1. `PhaseCheckIoBundle`
2. `PhaseCheckHierarchy`
3. `PhaseInferWidth`
4. `PhaseCheck_noLatchNoOverride`
5. `PhaseCheck_noRegisterAsLatch`
6. `PhaseCheckCombinationalLoops`
7. `PhaseCheckCrossClock`
8. `PhaseContext.checkGlobalData`

Every built-in `PhaseCheck` is inventoried automatically unless it has an
explicit reviewed exclusion. `PhaseGetInfoRTL` retains its historic
`PhaseCheck` type for inserter compatibility but opts out because it only
collects report information; `PhaseInferWidth` explicitly opts in despite not
being a `PhaseCheck`. The global-data guard remains in its historic post-phase
finalizer position and has explicit inventory metadata. `MorphVerilog`
compares the built-in and observed inventories before starting symbolic
capture, so removal, an added duplicate baseline phase or reordering fails
closed. A same-ID replacement is treated as the same validation contract.

CI generates the inventory independently on Scala 2.12.18 and 2.13.12 and
compares both ordered files with `validation-parity.tsv`. The previous
hard-coded check set has been removed from the Python gate, making the live
shared plan authoritative.

## Dual-leg generation

Generation executes in this order:

1. Validate the bounded configuration surface.
2. Elaborate the concrete witness through normal `SpinalVerilog` in an isolated
   temporary directory and a concrete frontend session on Spinal's worker.
3. Require the observed inherited validation IDs to match the built-in plan.
4. Capture the symbolic `Design` once.
5. Run `ParamRtlValidator` over all legal parameter values.
6. Run strict Verilog-2001 capability verification.
7. Require the default concrete and symbolic top names and every reachable
   module instance's binding-aware flat port schema and recursive child-module
   multiplicities to match.
8. Render the symbolic design in memory.
9. Delete temporary concrete RTL, then atomically replace the one public `.v`
   file.

No concrete RTL path is returned and no witness-specific module variant can be
substituted for the parameterized hierarchy. A failed concrete, symbolic,
capability, agreement, cleanup or write stage leaves an existing public output
untouched. Filesystems without atomic same-directory replacement fail closed;
there is no non-atomic overwrite fallback.

The default-shape agreement catches accidentally paired same-named designs
with different reachable module interfaces or hierarchy. It is not a full
behavioral equivalence proof between arbitrary dual factories; the explicit
`MorphProgram` association remains part of this bounded Increment 7 contract.

The current output profile rejects VHDL, SystemVerilog interfaces and
`oneFilePerComponent`. `targetDirectory` and one relative `.v`
`netlistFileName` control public output; semantic Spinal configuration hooks
continue to validate the concrete witness. The filename is checked before
either factory runs, and mutable configuration collections are cloned so
witness generation cannot mutate the caller's `SpinalConfig`. Output-affecting
Spinal options that the direct parameterized emitter cannot honor (including
headers, timescale changes, namespace and signal-name controls, line comments,
ROM lowering, long-expression and component-binding controls, global prefixes,
obfuscation and verbose logging) are rejected up front. Increment 53f admits
one later, narrow exception for the single-source path: an exact
`config.includeFormal` profile (the sole `formal` generation flag paired with
`formalAsserts=true`) preserves native formal statements such as `cover`.
The dual-factory path, an unpaired formal flag, `formalAsserts` alone,
simulation or any combined generation-flag profile still fails during
configuration before either factory runs.

## Executable evidence

Cross-version tests cover successful dual-leg generation, phase hooks, removed
inherited phases, concrete and symbolic failures, target-capability failures,
top-name mismatch, same-name port/hierarchy mismatch, path traversal,
caller-config isolation, global-state leakage, preservation of an existing
artifact when validation fails, session recovery and byte-identical repeated
output. The `parameterized_wire.v` golden is regenerated through
`MorphVerilog`, then consumed with the other three existing fixtures by strict
Verilator, Icarus and Yosys gates. Routing the remaining derived-width,
hierarchy and generate-loop fixtures through the public entry point is deferred
to the next frontend-closure increment. Inherited `AttributeEmitTests` and
`ChecksTester` remain mandatory.

## Deliberately deferred

- A one-constructor `MorphVerilog { new Component(...) }` lowering path.
- Remaining `HdlInt` operators and frontend-owned local parameters.
- `HdlBool`, `GenerateIf`, `GenerateCase` and nested generate regions.
- Processes, registers, memories, aggregates and broader library adapters.
- Any fallback that specializes public RTL to a concrete witness.
