# Remaining parameterized increments — local handoff

## Compiler repair hold, 3 October 2026

The user stopped qualification to fix the issues in
[morphhdl_compiler_issues.md](morphhdl_compiler_issues.md). The local runner and
hourly monitor are stopped; the timer is disabled. Preserve existing receipts,
but do not resume qualification or monitoring without user authorization. Focused
repair tests and application generation replays are separate from qualification.
The durable repair evidence is `~/.local/state/morphhdl/compiler-issues-20261003/`.

## Current state: 59i integrated, hardware wire passes retired

The local work branch contains the ancestry-preserving merge `bfed00f992281d83dde20dfabf319d8f1ea0bc57`
of 59i head `6bb250972f06ca55c9cfbd7100126adad9e81569`. The
`parameterized-verilog` integration target remains untouched.

The old `morphhdl-passes` workspace, its roadmap, public wire-pass API, native
wire-only emitter extensions, dedicated tests/repros and dedicated CI workflows
are removed. Canonical IR publication and parameterized-Verilog features remain.
WA-11 Boolean/integer normalization is retained, documented at
[WA-11](wa11-boolean-width-normalization.md), with its checker under
`morphhdl/scripts/`. Historical receipts and source-review contracts outside the
retired workspace are preserved as historical evidence.

The user stopped full local qualification and paused hourly monitoring. Both
remain paused. Focused integration tests are separate repair evidence, not full
qualification. No remote CI, remote PR merge or push has been performed for this
retirement.

Before future CI, reconcile the historical cumulative source-review entry points
that still describe wire-pass registries with this explicitly authorized
retirement. The new `check-parameterized-integration-source.py` records the exact
integration delta, and `check-retained-parameterized-regressions.py` requires the
retained test inventory. Neither replaces native preservation, behavioral proofs
or remote qualification. Existing historical source checks have not been
bypassed to claim a passing candidate.

### Local integration checks (not full qualification)

- Scala 2.12: 148 focused parameterization/59i/canonical-IR tests and 14 core
  tests passed after removal. A final rerun after the wrapper/import cleanup
  passed 14 WA-11/CDC tests and all 14 core tests.
- Scala 2.13: 114 focused parameterization/canonical-IR tests and 14 core tests
  passed. An ambiguous `spinal.lib._` import in the CDC fixture was replaced by
  the specific `fromGray` import; all legality and simulation assertions remain.
- The moved WA-11 checker compiled 30 generated artifacts and checked 76
  parameter overrides and 1,216 data patterns. Two historical address-helper
  negative fixtures still reject the missing portable `clog2` function and are
  not counted as successful artifacts. This focused run did not regenerate the
  historical reference.
- Production retirement, typed layering and legacy-adapter retirement checks
  pass. The regenerated native audit covers 7 roots, 58 changed paths and 260
  individually recorded edits. Native audit controls, exact report inventory controls, and modified
  workflow YAML/shell syntax checks pass.

Logs and the WA-11 JSON receipt are under
`~/.local/state/morphhdl/qualification/23dc9248-resume/`, with names beginning
`combined-retirement-`, `retirement-final-` and `retirement-wa11-`. The former full
qualification logs remain historical; no wire-pass proof was run.

## Historical pre-integration handoff

The following describes the earlier, stopped run. Its SHA, counts and monitor
status are historical and must not be credited to the combined candidate.


Work branch: `work/remaining-parameterized-increments`.
Integration target remains `parameterized-verilog` at
`db54d01e5b21c7664f7a0de3795f061d77a3d259`, verified locally and remotely on
2 October 2026. No push, PR creation, remote CI or integration merge was performed
for this batch. The local hourly continuation monitor is enabled (details below).

## Implemented scope

| Increment | Local implementation and validation | Qualification |
| --- | --- | --- |
| CDC-LEG-01 | Product-neutral legality labels; user-only fatal messages | Remote CI/merge pending |
| 64 | Exact owned derived-expression localparam graph | Remote CI/merge pending |
| 65 | Scoped structural legality, typed Boolean matches, bounded joint predicates and child binding | Remote CI/merge pending |
| 66 | Conditional packed loops, resize simplification, symbolic U values, explicit scalar/child formals, typed named locals and both AXI factories | Remote CI/merge pending |

Unmerged 59i is excluded. No item from the retired wire-assignment-pass roadmap
was implemented. Changes under the old pass workspace are confined to preserving
its existing source fingerprints and qualifying this exact branch through its
inherited CI boundary. No application source was changed.

Compiler/test source is `b6e83061d8c2b1ece94e76abadc956a2201a99dc`, tree
`77587de53911f687b97250f58fa7747ad39e6d40`. Later commits integrate exact source
review, retained evidence and CI report catalogs; Scala production/test source remains
identical. See [increment 66 receipts](evidence/increment66/README.md) for the
combined checks and the individual increment 64/65 handoffs for their earlier
source checkpoints.

Both Scala 2.12.18 and 2.13.12 passed 447 runtime tests, 35 native copy/expression
tests, 18 compiler-plugin tests and the separate two-test legacy AXI4 formal
suite. Real upstream binary comparisons and legacy source fixtures passed for
idslplugin/core/lib in both lanes. Representative generated RTL is byte-identical
across Scala versions. Local tools were used directly, without Docker.

Native audit: 7 roots, 59 paths, 267 reviewed edits and 8 negative controls.
Cumulative source audit: 510 controls, including exact deleted-source restoration
and reintroduction rejection. Successor review: 114 controls plus immutable
historical replay. Exact report catalog: 273 negative controls, with original XML
preserved. Existing branch-boundary routing and layering checks pass. The formal
registry retains all 98 identities; refreshed fingerprints are source identities,
not substitutes for running proofs.

## Pending qualification and closure

Root AGENTS.md requires a real enabled hourly monitor through qualification and
closure. On 2 October the local user-systemd timer
`morphhdl-remaining-increments.timer` was enabled during local qualification,
with `OnCalendar=hourly` (`RRULE:FREQ=HOURLY`). The scheduler reports `enabled` and
`active`; its first service invocation succeeded and retained the active local
queue without launching duplicate work. The next scheduled invocation at setup
was 2 October 2026, 10:00 IST. The durable prompt and checkpoint are
`~/.local/state/morphhdl/qualification/23dc9248-resume/monitor-prompt.txt` and
`checkpoint.json`. Reuse this monitor after targeted dispatch, adding the real PR,
final SHA/tree, workflow IDs and run links. No remote CI has been launched.

The monitor invokes the authenticated local Codex CLI when continuation is needed;
a file lock prevents overlapping monitor invocations. While the local queue holds
its own lock, the monitor only records a snapshot. This machine and its user
session must be available (`Linger=no`). The service and timer definitions are in
`~/.config/systemd/user/`. Keep the timer enabled through blockers and remote
qualification until verified closure or an explicit user stop.

The user has now authorized expanded local validation followed by targeted CI,
and explicitly excluded wire-pass formal proofs and wire-pass CI workflows.
The expanded local run is in progress on compiler/test source at
`23dc9248a09157c25030f6603fbdbd0d01f7ab6d`; only status documentation has changed
during this run. The isolated `morphhdl-passes` workspace is excluded, as is the
runtime cleanup suite that automatically invokes a wire proof. Retained compiler
and library simulation/formal gates remain enabled. Aggregate remote workflows
must be inspected for indirect invocation of excluded proofs before dispatch.
This scoped run must not be represented as successful execution of excluded
gates or as completed remote qualification.

At the latest local checkpoint, Scala 2.12 compilation, 697 compiler/IR tests,
1,347 runtime tests, 55 core tests and 296 upstream core simulation tests passed.
The upstream core formal and PSL groups also passed. The PSL group first exposed
missing local GHDL plugin/runtime setup; a user-local plugin installation and
`GHDL_PREFIX=/usr/lib/ghdl/mcode/vhdl` resolved those environment failures without
changing test or compiler source. Remaining upstream tester/library groups and
the expanded Scala 2.13 lane are still pending. A machine restart removed the previous `/tmp/morphhdl-full-local-23dc9248`
queue, raw logs and temporary proof workspaces. The recorded successful groups
above describe the pre-restart run; they are not claims that its raw evidence
survived. On resumption, 343 JUnit reports were preserved with SHA-256 hashes in
`~/.local/state/morphhdl/qualification/23dc9248-resume/recovery.json`. The
resumable queue and new per-attempt logs, reports and receipts now live in that
durable directory. The interrupted Scala 2.12 tester simulation group resumes
with only suites lacking a recovered successful nonempty report; remaining
formal/PSL/library groups and the expanded Scala 2.13 lane follow. A group is
complete only after its command succeeds and its receipt is written.

[59i PR 177](https://github.com/pysolvesemi/MorphHDL/pull/177) was still open at the
last live check. Preserve the user's integration freeze until it merges. Do not
move `parameterized-verilog` merely to publish this batch.

After the expanded local run passes, use the live final work-branch SHA/tree, inspect
current integration movement and reconcile any changes before qualification.
Publish a clean candidate with automatic CI suppressed, determine the affected
workflow IDs and paths, dispatch targeted workflows on that exact head, and
immediately enable the required hourly monitor with a durable checkpoint. Reuse
same-head runs; leave unrelated and 59i jobs intact. Full CI follows successful
targeted qualification. Any source repair requires a new head and fresh affected
qualification. Merge only after the integration freeze, required qualification
and completion gates are satisfied; preserve ancestry and suppress duplicate
post-merge CI. Roadmap checkboxes intentionally remain unchecked until closure.

## User-directed integration and retirement, 2 October 2026

The user stopped background qualification and explicitly paused the hourly
monitor. The timer is disabled and inactive. Earlier running/enabled status above
is historical. The new authorized work is to merge the latest 59i branch into
`work/remaining-parameterized-increments`, then remove the wire-pass implementation
tracked by `morphhdl-passes/morphhdl-ir-wire-assignment-passes-todo.md`. Preserve
existing evidence. Do not move `parameterized-verilog` or restart the hourly
monitor as part of this local integration and retirement work.
