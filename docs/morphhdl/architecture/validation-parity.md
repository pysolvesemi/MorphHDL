# Validation parity

Parameterized RTL generation must preserve or strengthen the semantic coverage
of the inherited SpinalHDL checks. Selecting `MorphVerilog` must never silently
turn a check off.

## Mandatory validation legs

The completed generator has three independent, fail-closed validation legs:

1. A concrete witness runs through the inherited SpinalHDL phase plan using the
   same configuration and transformation hooks as `SpinalVerilog`.
2. The symbolic design runs through ParamRTL validation and target capability
   verification. These checks cover all legal parameter values rather than only
   the witness default.
3. The actual emitted Verilog passes strict parsing, lint, simulation and
   synthesis under default and non-default parameter values.

The concrete witness is validation data. Its generated RTL may be temporary and
is never substituted for the single public parameterized hierarchy.

## No-copy phase-plan rule

When the Morph frontend is connected to Spinal elaboration, the inherited phase
construction must be factored into one shared phase-plan factory. Both
`SpinalVerilog` and `MorphVerilog` consume that factory. Copying the current
phase list into a second backend is forbidden because it would drift during
upstream synchronization.

An inherited check is classified as one of:

- `reuse`: invoke the same target-neutral implementation;
- `adapt`: retain it and add symbolic handling;
- `equivalent`: implement the same semantic guard in ParamRTL;
- `not-applicable`: allowed only with a reviewed justification and regression.

Every unsupported symbolic case fails with a diagnostic. Default-value
specialization is not an acceptable fallback.

## Executable inventory

`morphhdl/contracts/validation-parity.tsv` records the disposition, status and
separate legacy/symbolic test evidence for each inherited check. Run its
development gate with:

```bash
python3 morphhdl/scripts/check-validation-parity.py \
  morphhdl/contracts/validation-parity.tsv
```

The development gate permits explicit `planned` and `partial` entries so an
increment cannot conceal missing work. The final parameterized-Verilog release
uses `--release`, which rejects every entry that is not `implemented`.
It also rejects implemented entries without evidence on both validation legs.

The inherited-check inventory is an explicit baseline snapshot. Before the first
release, the shared phase-plan factory must expose stable phase identifiers and
CI must compare that live inventory with this manifest. That prevents a newly
inherited upstream check from being omitted from both the code and the table.

Increment 3 extends the partial `PhaseInferWidth` adaptation through bounded
integer arithmetic and acyclic local-parameter dependencies. It does not claim
the remaining v1 expression algebra or connection to the inherited phase plan;
those gaps remain visible as partial or planned work in the inventory.

Increment 4 advances `PhaseCheckHierarchy` to `partial`. ParamRTL now validates
module dependencies and named parameter/port bindings across the entire
symbolic design, with dedicated symbolic test evidence. The status remains
partial until the Morph frontend also executes the shared inherited hierarchy
phase on its concrete witness; the new ParamRTL checks do not replace that leg.

Increment 5 extends the partial hierarchy and width adaptations through a
lexically scoped generate index and parameterized indexed part-selects. It also
advances `PhaseCheck_noLatchNoOverride` to `partial`: ParamRTL proves that the
supported canonical generate loop partitions its packed output exactly once,
without gaps or overlap, for every legal count and width. This is deliberately
not a claim about conditional generate predicates, future processes or the
still-pending concrete-witness phase-plan leg.
