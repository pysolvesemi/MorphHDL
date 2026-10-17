# LWR-20 / LWR-21 focused completion

Starting compiler: `3bd906d29fb7ebc9f50b6874309f862da31f7080`, branch
`work/remaining-parameterized-increments`. The accompanying [receipt](constant-bit-writes-and-generate-labels.json)
records source-overlay manifests, commands, artifact hashes and durable evidence paths.
Historical base results are not reused as current-source checks.

Constant bit assignments on retained register Vec elements now retain the exact
native assignment target, carrier, index and source identity through emission.
Publication uses the authenticated assignment edge and Vec geometry, including
packed layouts, while preserving guards, assignment order and reset values.
Changed targets, carriers, sources, invalid width domains and foreign drivers
remain rejected. This does not introduce dynamic/ranged partial-write support or
permit whole-array assignment consolidation to widen a partial override.

Default lightweight ElabInt and HdlRange structural loops now carry private
identity-based default-label authority. Publication allocates `g_GEN_1`,
`g_GEN_2`, etc. in traversal order, reserving existing declarations, child names
and explicit scopes. Explicit names remain unchanged. Source positions remain
available for diagnostics. Default generated hierarchy labels intentionally
change; source-only comments and blank lines no longer change them. Existing
procedural/body support is unchanged.

## Fresh checks

Both Scala 2.12.18 and 2.13.12 builds pass. Per version: 55 runtime/frontend
checks, 42 compiler-plugin checks and 2 spacing-variant checks pass: 198 total.
Coverage includes packed/unpacked register bit/word priority and differing
resets, invalid identity mutations, nested/helper loops, HdlRange register loops,
explicit names, colliding ports/instances, repeated output, existing generate-index
and structural switch regressions. Spacing variants and both Scala versions
produce identical label-fixture RTL hashes. Icarus Verilog-2001 simulation,
Verilator lint and Yosys synthesis/checks pass in the focused suites.

The frozen original application snapshot with the CSR copy inside its switch
and the original `shadow(0)(11) := False` now compiles and generates product-top
RTL twice identically. Native and unchanged Rust-cleaned RTL pass lint. The
matching standalone CSR default profile passes all four existing unit benches
on native output and all four on cleaned output. Initial attempts applying those
benches to a different concrete product profile failed due to mismatched bench
geometry; those attempts are retained separately and are not passing evidence.

Application Scala, delivery RTL and Rust cleanup source were not edited.
Questa is unavailable. No formal campaign, remote CI or full qualification was
run, per the focused-check scope of these roadmap items.

Commands and exact plugin/source hashes are in each `results.json` listed in the
receipt. Runners are preserved beside those results under the durable evidence
root. Source-integrity seals are refreshed after the implementation commit;
the final published identity is recorded in the application roadmap/checkpoint.
