# Qualification integration repairs — 4 October 2026

Targeted qualification of `6f8711bf55413b9bb7a6df481c2f6cfd5cda25ed`
found integration failures beyond the focused CA-012–CA-016 reproductions.
The initial ten-run ledger is retained in PR #195's qualification checkpoint.
Only external formalization passed that candidate; skipped dependent jobs and
historical source results are not qualification of a successor.

The repair batch preserves typed Vec index and affine range proofs from their
original authoring branch. Publication checks the same expression, loop, shape,
carrier and assignment identities after the branch context has exited. Static
accesses use the retained complete-domain depth minimum, never a witness width.
Complete prefix-network reads require the exact loop, scalar prefix writes and
final-selector identity. A spelling prefilter avoids expensive identity work on
unrelated statements; it cannot authorize a selector.

Native compiler temporaries now resolve consuming-owner demands to a bounded
fixed point. Enclosing/descendant claims can share one continuous assignment
when its declarations agree; unrelated overlapping siblings remain rejected.
Outward promotion still requires a unique whole driver, visible declarations,
available sources and final dominance. An explicitly declared one-bit wire can
carry a Boolean conversion. Procedural ownership retains its existing rules.

Regression assertions now check the concise published genvar against its exact
loop bound, sized localparam slices against their defining expression, and both
forms of sensitized zero output. CDC topology checks require two logarithmic
stage arrays and loops with symbolic pointer widths and exact layer counts.
Reusable explicit child formals use a deterministic definition default while
instances preserve their own actuals. Existing reset, clock, attribute, latch,
missing-lane, competing-writer and ownership rejection checks remain mandatory.

BOOT initial blocks now invoke the same optional assignment-source publication
hook as reset processes, using the exact retained init statement. The native
fallback remains unchanged when no policy supplies a replacement. This keeps
parameterized initializer emission and its strict lineage count consistent.

The native conditional-loop test locates its committed reference through both
working-directory and code-source ancestors, supporting Mill's test directory.
Documentation source-coordinate decoding belongs to its metadata owner rather
than the canonical hardware producer; the retirement guard remains unchanged.

Historical source checks execute at their actual two-file seal,
`9c88f5f75921e28aff329e3cff2d40132315e861`, which precedes integration parent
`6bb250972f06ca55c9cfbd7100126adad9e81569`. The current exact integration seal
separately authenticates all subsequent changes. Historical receipts never
qualify current RTL. Tests reject moving candidates, unreviewed source,
unallowlisted commands and nonzero historical controls.

Local focused validation and fresh targeted CI are required for this repair
batch before full qualification. No full-CI or merge closure is claimed here.

## Local validation

Both Scala 2.12.18 and 2.13.12 pass 245 focused tests across 21 suites, plus
five native phase-plan tests and four baseline tests per Scala version. Eight
conditional-process tests also pass from outside the repository root. The BOOT
scoreboard checks defaults and independent width overrides with two clocks,
initialization, updates and stalls; its separate module files pass Icarus
simulation, strict Verilator lint and Yosys synthesis/check without waivers.
Retirement and CDC boundary mutation controls and seven audit-wrapper controls
pass. The retained full-CI catalog adds the one BOOT test (2,456 total), retains
every previous case and updates only the four repaired existing source hashes.

These results are local repair evidence. Fresh remote targeted qualification,
full CI and verified merge closure remain separate requirements.
