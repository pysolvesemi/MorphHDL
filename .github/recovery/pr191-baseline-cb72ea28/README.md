# PR191 exact-head baseline continuation

This separately reviewed one-shot controller targets only workflow 332279971
(`.github/workflows/morphhdl-baseline.yml`) on repaired and sealed feature head
`cb72ea2851b529fbe216c1a56a0de68d68770400`, tree
`19b18c49d278e0e0f65f5653478b97b0d8ffc1a5`.

Baseline run 35565550600 on prior head `025ae94b` passed Scala 2.13 and all
1,232 executed Scala 2.12 tests, then failed its regenerated-contract structure
check because the workflow still expected 33 exact WIDTH ranges. The reviewed
golden contains 30: 27 public ports and three registers after removal of three
eligible internal Bundle aliases. The repaired workflow changes that one count,
preserving exact golden comparison, inventories, strict tools and all tests.

The controller authenticates the open PR, live feature and target refs, source
tree and seal, workflow bytes and the substantive historical failure. It checks
for an active or successful exact-head dispatch before any write and again
immediately before its request. Its only permitted write is the exact baseline
workflow-dispatch endpoint with the exact feature branch payload. Intent is
fsynced first; an ambiguous request is never retried. It does not launch full CI,
modify source refs, cancel work, or touch any other workflow.

Local source review and ten controller safety tests passed. Dispatch is not
qualification; the hourly monitor must inspect all three baseline jobs.
