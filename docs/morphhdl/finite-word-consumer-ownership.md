# Finite word consumers and direct child ports (CA-044/045)

CA-044 retains exact native alias-use multiplicity when a complete finite Vec
writer feeds a sibling affine consumer. A signed maximum consumes each selected
leaf in both its comparison and its mux. Native cast materialization can add
intermediate declarations, so the publication check records occurrences during
its existing authenticated alias substitution, after validating the exact leaf,
owner, layout, selection and live declaration. The final aggregate occurrence
count must equal those receipts. Complete-writer and full-domain affine proofs
remain mandatory. This does not authorize partial or foreign drivers, and does
not remove native `_zz_` helpers.

CA-045 accepts direct child ports as finite packed-access operands and checks
write direction separately. Parent-owned writable carriers and direct child
inputs remain writable. Child outputs may be read but cannot be assigned by the
parent. Child-internal and grandchild carriers remain rejected. The previous
negative test that rejected a legal child output now uses an inaccessible
grandchild output; separate child-internal and output-write rejection tests were
added. Width/domain checks are unchanged.

The regression corpus covers recursive signed/unsigned maximum and composite
payloads, COUNT 1..8 (defaults 1 and 8), both Vec layouts, repeated generation,
random data and mixed X/Z inputs. The independent flat reference preserves
adjacent pairing and odd tails. Tests run Verilog-2001 compilation, simulation,
lint, synthesis and SAT. Direct-child bit/word fixtures cover 1/3/4 lanes,
whole-port writes to child inputs, output reads, deterministic emission and
four-state simulation. Existing overlap, incomplete coverage, wrong geometry,
independent roots and tampered finite-access capabilities remain negative gates.

Scope limits: these repairs do not add parent-domain substitution for
child-owned formal widths. A direct finite slice of such a port can still need
an authenticated parent carrier. Partially consumed concrete child ports remain
subject to the existing full-port connection rules. No new support for those
hierarchy forms is claimed. The reported three-bit direct-child case is covered.
Application Scala, RTL, compiler pins and issue checklist remain unchanged.

Qualification evidence is retained outside the source tree under
`~/.local/state/morphhdl/qualification/23dc9248-resume/ca044-045/`.
Historical failures and development attempts are separate from final results;
remote targeted/full qualification status is recorded in the live checkpoint.
