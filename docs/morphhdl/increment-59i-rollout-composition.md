# Increment 59i — exact 59i/60g source composition

Status: reviewed development composition. Increment 59i remains incomplete,
unchecked, draft and unmerged.

- Common integration baseline: `64e8fddc432e859b6b532540bee96c5608d46efa`
- 59i feature parent: `ee2e7f2613e54f23158ce1eacae6028409a91ed6`
- Historical integrated `parameterized-verilog` parent: `2ebaa2ef5561eab35aa0ba9caced5c5a314d59f6`
- Exact pre-composition checkpoint commit: `71efa81bf56e8483f7837519b2e44cdeba908439`

The merged source is validated as one real HEAD/index/worktree tree. Historical
59i audits receive only the exact first-parent view, while 60g rollout audits
receive only the exact second-parent view. A whole-file hash map rejects any
third byte sequence; no checker accepts arbitrary edits between old span ranges.

The adapter also recognizes the separately reviewed local-enable successor when
present, strips it first, and then validates this immutable merged checkpoint.
This is source-review composition only; it does not substitute for Scala, RTL,
simulation, synthesis, formal-equivalence, mutation or final integration gates.
