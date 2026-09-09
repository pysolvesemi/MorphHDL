# WA-08 production pass handoff

## Scope

WA-08 installs the qualified MorphHDL wire-assignment/readability pipeline at
the production post-parameterization, pre-name-allocation boundary. The public
configuration remains one disabled-by-default Boolean switch. Enabling it runs
the fixed order below to convergence:

1. unnamed wire-alias elimination;
2. named wire-alias elimination;
3. unnamed wire-expression elimination;
4. constant-operand simplification;
5. Boolean-ternary simplification.

The implementation works on the native/canonical expression graph before
Verilog publication. It does not parse or rewrite emitted Verilog and it does
not select behavior from a component, source-file or emitted-signal name.
Ordinary generation remains unchanged when the switch is omitted or false.

## Canonical handoff contract

The production envelope uses `PureWireExpressionsV1` and requires the
`PureExpressions` completeness facet in addition to declarations, continuous
drivers, reference occurrences, typed parameters/packed types, name origins and
observability. `SimpleWireAssignmentsV1` remains available only for explicit
legacy fixtures.

The immutable WA-08 payload checkpoint is
`dd1850bc021627a5e3ff8370cbbf214bfced4e88`. Increment 62 adds an exact source
overlay around that payload so inherited closed-scope reviewers can inspect
their historical bytes without weakening the outer current-head audit. The
overlay rejects partial rollout, changed hashes, unknown governed paths,
symlinks, executable-mode changes, staged or untracked source, and incorrect
profile/facet claims.

## User-facing configuration

```scala
import morphhdl.MorphWireAssignmentPasses
import spinal.core.SpinalConfig

val config = MorphWireAssignmentPasses(
  config = SpinalConfig(targetDirectory = "generated"),
  enabled = true
)
```

Leaving `enabled` at its default `false` preserves the existing generation
path. WA-08 changes generated Verilog only when this opt-in switch is enabled.

## Qualification status

The implementation branch must pass the Increment 62 source-overlay gate, both
Scala lanes, the complete wire-assignment pass workspace, strict Verilog-2001,
Icarus/Verilator/Yosys checks, deterministic generation, four-state simulation,
formal equivalence and mutation controls, plus all applicable inherited
baseline and Mill workflows. Exact final-head and post-merge evidence will be
recorded here before the WA-08 and Increment 62 checkboxes are changed to
`[x]`.
