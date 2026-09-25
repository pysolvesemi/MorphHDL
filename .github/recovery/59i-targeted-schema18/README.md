# Schema-18 targeted dispatcher

This bounded controller authenticates the passed schema-18 source and target-reconciliation evidence, then dispatches only the three still-failed workflows: WA08, Combined, and 59h. The already-passed 59f workflow is deliberately excluded. It cannot launch full CI, update refs, merge, or mark roadmap acceptance.
