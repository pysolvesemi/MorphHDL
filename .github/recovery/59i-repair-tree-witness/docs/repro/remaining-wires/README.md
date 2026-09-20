# Native sequential wire consumers

This is an independent compiler regression. It imports only the local MorphHDL
projects and both required compiler plugins. It has no application RTL, display
controller repository, external DUT, or generated-Verilog cleanup step.

`RemainingWireRepro.scala` is the supplied public-API reproduction. `PPC4` stays
an overridable HDL parameter, with its original default of zero and lane width
`PPC4 * 3 + 1`. Neither the source behavior nor the default pass policy changes.

## Failure and repair

The observed baseline is `f5049ae2abfe5a47cd1fac3574ea08d630bd183f`.
There are two separate boundaries:

* The native condition adapter treated any register destination as an
  unsupported non-combinational consumer. The repair distinguishes blocking
  combinational writers from nonblocking register updates. The existing
  dependency/control closure still rejects blocking feedback. A register read
  remains a read of its pre-edge value, including when that register is also
  updated by the branch. No assignment, scope, clock, enable, reset or
  initialization is moved or replaced.
* The emitter can materialize a select-base wire after the native adapters have
  run. An identity-sized unsigned resize can print an existing named source
  reference, but the old wrapper planner still demanded a carrier because its
  AST node was not a `BaseType`. The repair proves exact native kind, width,
  metadata and source identity before allowing that reference as a select base.
  Updating only the earlier alias pass would not close this boundary.

The unnamed native direct-alias adapter additionally accepts exact typed aliases
in whole-register RHS expressions under ordinary `when` scopes. It receives the
existing pre-liveness source-intent inventory. A legacy no-argument caller has no
such inventory and fails closed for the new sequential profile.

Native condition names are classified by the existing name-provenance machinery;
unnamed aliases use native identity, not an emitted-name regular expression.
Tags, explicit names, registered identity references, clock-domain uses,
unsupported consumers and type mismatches remain preservation boundaries. The
normal native reports/read-only eligibility query retain diagnostic reasons.
The adapters inventory references before rewriting and verify no supported
reference remains before removing a helper declaration and driver.

### Deliberate limits

The late select-base proof accepts unannotated identity-sized `UInt`/`Bits`
resizes ending at an existing same-component, fixed-width declaration. It does
not approve signed resizes, arithmetic, changed widths, casts, symbolic widths
or unknown expression metadata. The named source is never deleted by this
emitter rule, even when it carries a keep attribute.

An arithmetic expression such as `(first + second).resize(13)` can still need an
18-bit temporary. Its temporary must remain a legal Verilog-2001 part-select
base; the fix must not emit `(first + second)[12:0]` or change the sum's width.
Partial register writes, unsupported control constructs and unproven aliases
are intentionally not covered by the sequential native profile.

## Commands

From this directory, reproduce only the supplied example:

```sh
sbt -batch 'runMain GenerateRemainingWireRepro generated'
rg -n 'when_|_zz_timing_[hv]Total' generated/RemainingWireRepro.v
```

The `rg` command is expected to find no matches with the standard default flow.
Do not use line-number suffixes to identify compiler candidates or assertions.

For the complete focused HDL matrix, use an absolute output directory:

```sh
sbt -batch "runMain GenerateRemainingWireMatrix $PWD/generated-matrix"
python3 check.py "$PWD/generated-matrix"
```

The checker requires `iverilog`, `vvp` and `yosys`; missing tools or failed gates
are errors, not silent skips. It writes testbenches, tool scripts, logs and a
receipt below the output directory. It hashes all emitted DUT files before and
after checking and fails if any was modified.

From the repository root, run the focused native suites:

```sh
sbt -batch \
  'core/Test/testOnly spinal.core.internals.VerilogEmitterExpressionInliningTests spinal.core.internals.SequentialWireEmitterTests' \
  'morph/Test/testOnly morphhdl.SequentialWireNativeTests morphhdl.examples.SequentialWireRetentionTests morphhdl.LaneWhenInliningRegressionTests morphhdl.examples.NativeWireExpressionCodecTests spinal.core.MorphVerilogExpressionInliningTests spinal.core.BooleanWidthNormalizationTests'
```

## Coverage and interpretation

The matrix generates ASYNC, SYNC and BOOT/initial-value configurations in default,
repeated, explicitly enabled and disabled modes. Enabled/default/repeated output
must match byte for byte; disabled output must retain the condition helpers and arithmetic carriers.
The disabled pipeline restores earlier Spinal cleanup and already emits direct
total slices; it is not expected to reproduce the enabled baseline's two
redundant direct-total aliases.
It compiles all four variants as Verilog-2001 for both `PPC4=0` and `PPC4=1`.
An independent expected-state model checks reset behavior, all Boolean control
combinations, error priority, maximum sums/truncation, counter wrap, deterministic
random sequences and four-state conditions/data. Priority/counter mutations are
made only to that expected model and must be detected.

Yosys compares default and disabled transition systems for each reset/PPC4
combination and separately synthesizes both versions. `async2sync` is used for
the formal comparison; the formal result alone is therefore not a proof of
arbitrary asynchronous event ordering. Between-clock asynchronous reset pulses
are checked separately in simulation.

A supplemental DUT compares optimized and disabled output across rising/falling
clock domains, opposite reset/enable polarities, repeated/nested predicates,
register feedback, last-assignment priority and signed narrowing/widening. Its
source also contains a real arithmetic slice base which must survive. Native
unit tests cover explicit/protected name lookalikes and unsupported uses; core
unit tests inject truly late carriers immediately before the emitter so that
an earlier native-pass change cannot make the emitter test pass accidentally.

`RemainingWireTrace` observes the native graph before/after production passes and
immediately before emission. It is read-only; its emitted DUT must match the
unobserved default DUT byte for byte. Its trace files distinguish expression
nodes already in the native graph from declarations created by the emitter.

A successful focused run is not full-repository qualification. The existing
sealed source-preservation/successor review gates and their historical anchors
must not be weakened or relabeled to make a new source head appear qualified.
No merge is justified solely by this focused workflow.
