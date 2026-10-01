# Remaining parameterized increments — local handoff

Work branch: `work/remaining-parameterized-increments`.
Integration target remains `parameterized-verilog` at
`db54d01e5b21c7664f7a0de3795f061d77a3d259`, verified locally and remotely on
2 October 2026. No push, PR creation, remote CI or integration merge was performed
for this batch. No hourly monitor exists.

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

Root AGENTS.md requires a real enabled hourly monitor immediately after targeted
CI dispatch. This session exposes no general automation scheduler, so remote
qualification has not been launched. This is an actual scheduling blocker; there
is no background monitoring promise or fabricated monitor ID.

[59i PR 177](https://github.com/pysolvesemi/MorphHDL/pull/177) was still open at the
last live check. Preserve the user's integration freeze until it merges. Do not
move `parameterized-verilog` merely to publish this batch.

When scheduling is available, use the live final work-branch SHA/tree, inspect
current integration movement and reconcile any changes before qualification.
Publish a clean candidate with automatic CI suppressed, determine the affected
workflow IDs and paths, dispatch targeted workflows on that exact head, and
immediately enable the required hourly monitor with a durable checkpoint. Reuse
same-head runs; leave unrelated and 59i jobs intact. Full CI follows successful
targeted qualification. Any source repair requires a new head and fresh affected
qualification. Merge only after the integration freeze, required qualification
and completion gates are satisfied; preserve ancestry and suppress duplicate
post-merge CI. Roadmap checkboxes intentionally remain unchecked until closure.
