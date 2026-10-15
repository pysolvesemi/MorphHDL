# Current scalar-default publication golden

`increment-59i-publication-golden-scalar-defaults.json` succeeds the retained
`increment-59i-publication-golden-successor.json`. Both that prior expectation
and the original historical golden remain unchanged.

Compiler commit `b4bffb665f` deliberately separated canonical scalar schema
comparison from definition publication: one child's scalar default remains
its authored value; sharing different actual defaults may require normalization.
Top-level defaults and explicit instance actuals remain independent. The old
59i checker still required a minimum default for COUNT even for one child.
Both Scala lanes of run 38413304806 (head
`df46f6ba6b8bc85621643d1098e46df54bfcf7dd`) therefore failed that expectation.

Review of both independent emissions shows precisely two changes relative to
the hash-authenticated previous goldens: the child-packed and child-fields
artifacts declare `COUNT = 5` instead of `COUNT = 1` in `PublicationGoldenChild`.
Their complete bodies, other defaults, ports and parent actuals are identical.
The other eight profiles are unchanged (concrete profiles retain the existing
exact native-header normalization). No generated HDL was edited.

The new expectation changes only those two COUNT declaration/default records
and corresponding full-RTL hashes. The checker still requires exact public
schemas, full artifact hashes, distinct repeat emissions, signed layout rules
and all actual bindings. Mutation controls now reject replacing the authored
scalar default with the minimum, as well as altered width defaults and actuals.
All three reviewed expectations are protected against snapshot overwrites.

Diagnostic artifacts were read from the authenticated GitHub artifact endpoint
with byte ranges, ZIP CRC verification and retained per-file SHA256 hashes.
The complete archive SHA256 was not verified; these are reviewed predecessor
artifacts, not qualification of the repaired candidate. The current checker
and successor source must pass fresh exact-head targeted 59i CI before full CI.
Local review, diffs and commands are in
`~/.local/state/morphhdl/qualification/23dc9248-resume/ca044-045/`.
