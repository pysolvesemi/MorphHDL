# Current-head qualification and authenticated provenance

The 6 October 2026 policy in `AGENTS.md` supersedes recursive execution of
historical source reviewers. Current compiler regressions, simulations, lint,
synthesis, formal proofs, mutation controls and native-source checks remain
mandatory on the final candidate. Historical qualification is provenance only.

`check-candidate-provenance.py` authenticates the current integration manifest,
HEAD/tree and clean source once, then traverses the reviewed certificate inventory
without creating historical checkouts or executing historical programs. Each
certificate commit is visited once. Commit/tree/parent identities, required
ancestry and individual evidence hashes must agree. The inventory retains
historical reviewer, contract and committed qualification-record hashes. Its
references cover fixed reviewer anchors and the successor certificate chain;
archived intermediate manifests are retained as evidence, not treated as new
qualification instructions. In particular, obsolete `final_source_commit`
fields in intermediate WA-08 manifests do not create new certificate roots.
The native integration parents previously checked by the combined workflow and
the fixed Increment 61 predecessor are explicitly enrolled.

The existing native-source guard (workflow ID 336665858) is the producer. It
uploads the receipt only after its current-source gates and rejection controls
pass. Dispatch this priority workflow first for a fresh candidate, and retain
its successful exact-head run. Other affected workflows require its completed,
successful same-head/same-branch `workflow_dispatch` run and every producer job.
They authenticate the artifact digest reported by GitHub before reading its
single receipt. Missing, expired, failed, modified or mismatched evidence fails
closed. Consumers also check the receipt's candidate SHA/tree, policy, checker,
manifest identities, clean checkout and complete certificate results. Source,
checker, manifest or policy changes require a new candidate integrity result.
This is authenticated consumption within one candidate qualification, not reuse
of an old candidate's result or caching historical audit execution.

`run-parameterized-inherited-audit.py` retains an exact command allowlist as
migrated **scope identifiers**. It consumes the authenticated result and records
the corresponding frozen source hash. It does not claim that the named
historical command executed. Historical checkers, fixtures, certificates and
records remain unchanged. Current workflow commands outside the explicit source
migration, matrices, lane identities, job/tool deadlines, hardware proofs and
artifact obligations are retained.

`candidate-provenance-workflow-migration.json` records each exact enrollment hunk
against candidate f70a20cebdc37319a6b74d0bfe227a0e4403101f. Contract tests reverse
only those hunks before applying retained workflow assertions. Missing,
duplicate and changed enrollment blocks are rejected; unrelated changes remain
visible. The former audit-wrapper tests for historical subprocess/checkout
execution are explicitly replaced by real-Git source/evidence/ancestry/receipt
rejection tests and authenticated artifact-transport controls. Historical fixture
and timeout-preservation tests remain enrolled.

Local tests and traversal measurements are repair evidence only. Policy migration
and timing resolution remain pending until fresh targeted CI passes on the sealed
candidate. All 19 required targeted workflows and applicable lanes must then pass
on that candidate before full CI; no earlier-head success qualifies this change.
