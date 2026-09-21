# PR192 publication cleanup targeted qualification

AGENTS.md authorizes this separate, increment-specific controller. It starts from
integration155df6eb0e38ecce04a37de2067b0794702fcb83 and contains orchestration only.
It never updates source refs, launches full CI, merges, or cancels other work.

The manifest pins PR192, branch, exact candidate/tree, base, eight affected workflow
IDs, paths and byte digests. These are the first repaired-source qualification
runs; the earlier baseline diagnostic is positive reproduction evidence, not
an acceptance failure or repaired-source pass. The manifest explains each gate.

The launcher checks live PR/refs, source checkout, source review and workflow
identities; reuses exact-head dispatch successes/active runs; refuses failed or
duplicate exact-head runs and controller retries. It journals each intent before
POST and never retries uncertain writes. The hourly monitor inspects actual
run/job/lane outcomes and handles remaining qualification.
