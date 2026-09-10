# Increment 61 — Parameterized `oneFilePerComponent` publication

## Dependency review

Increment 61 is publication-path work and is not architecturally blocked by
Increment 59i. The canonical native component identity map, parameterized module
rewrite path and completed signed-Verilog behavior already exist on
`parameterized-verilog`. Increment 59i changes balanced-reduction admission,
shape replay and qualification; it does not supply module-file ownership or
publication infrastructure.

Increment 61 therefore proceeds in parallel from the merged Increment 60 base.
If it merges before 59i, 59i must rebase and qualify its combined cases in both
consolidated and per-component modes before 59i is marked complete.

## Implementation checkpoint

The implementation uses native SpinalHDL `oneFilePerComponent` emission as the
source of file boundaries and the existing exact emitter canonical-component
map as identity authority. It does not concatenate generated files and does not
parse a consolidated Verilog file to reconstruct components.

The parameterized expression/hierarchy rewrite and enum-localization passes now
rewrite each canonical component file independently. A known component filename
is validated to contain exactly its expected module before publication. External
`BlackBox` definitions remain external. Module-local parameters, localparams,
functions, memories, signed declarations and generate regions remain in the
owning native component file.

Final publication is copied from a private workspace only after all rewrites
succeed. Output names are deterministic (`<definitionName>.v`), the top file is
reported last, and `<top>.lst` records the relative source order. A hidden V2
SHA-256 ownership manifest is paired with one deterministic hidden marker per
managed output. A previous manifest entry is trusted only when its marker names
the same path and hash; missing, extra, modified or orphaned markers fail before
any public file changes. This prevents a modified manifest from claiming and
deleting an unrelated user file. Stale generated files are deleted only when
both ownership evidence and file bytes remain unchanged. Unowned filename
collisions and user-modified managed files fail closed.

`netlistFileName` is rejected with `oneFilePerComponent = true` because one name
cannot identify several logical component definitions. Consolidated publication
and ordinary concrete `SpinalVerilog` remain unchanged.

## Current status

This checkpoint adds dual-Scala unit coverage for canonical repeated children,
parameter bindings, enum ownership, external modules, deterministic manifests,
paired ownership-marker validation, stale-file safety, manifest-tamper rejection
and configuration diagnostics. A dedicated workflow also compiles, lints and
synthesizes both split and consolidated hierarchy outputs. Increment 61 remains
unchecked until final-head CI, equivalence, mutation, inherited compatibility
and source-audit gates are complete.
