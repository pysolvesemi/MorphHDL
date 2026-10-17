# 59i publication golden successor

The current publication gate uses `increment-59i-publication-golden-successor.json`.
The preceding `increment-59i-publication-golden.json` is retained unchanged for
historical provenance. The checker continues to compare every parameterized RTL
byte, parsed declarations and child bindings, both independent emissions, and
both concrete native bodies. It does not normalize helper names or carrier aliases.

The successor was derived from historical RTL authenticated against the preceding
contract, applying only these reviewed changes:

* Remove the generated helper `morphhdl_` prefix throughout the eight parameterized
  profiles. All uses follow their declarations.
* Retain six balanced-reduction input carrier aliases in each parameterized
  profile. Each alias already has an exact assignment from the former input;
  the source order is records, signedRecords, records, signedRecords, records,
  records. No arithmetic or stage geometry changes.
* In child-packed and child-fields, use `COUNT=1` for PublicationGoldenChild.
  CA-020 authenticates direct Vec-depth formals as definition-owned scalars and
  publishes their minimum default, just as child width formals do. The parent
  still defaults to COUNT=5 and passes `.COUNT(COUNT)` to the child. MODE, widths,
  ports and every other public declaration remain unchanged.

The independently transformed bytes exactly match both emissions in artifact
11415663116 from run 37443827111, attempt 2, Scala 2.12.18, source
`a3249b446f5d476ca2d967bd3c569ad448b6e13e`. The complete archive SHA-256 is
`76809e50b30c4855c39604987a98597f8ea1800d50b36c3d9e410be2fb384667`.
Both concrete native profiles retain their preceding body hashes.

Mutation coverage requires child minimum defaults and parent actual bindings
independently, now including COUNT alongside the three width formals. Existing
checks still reject altered defaults, ports, signedness, bodies, inventories,
raw emission differences and contract corruption. Snapshot output cannot replace
either reviewed contract. This review is repair evidence only: both Scala lanes,
cross-Scala determinism and all applicable exact-candidate gates must pass fresh
remote qualification. No historical result qualifies the successor candidate.
