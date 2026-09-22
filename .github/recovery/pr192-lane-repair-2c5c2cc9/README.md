# PR192 lane naming proof repair

Exact repaired head `2c5c2cc93fc0ed2ba848f82e0c55f1e028dd77ef`, tree
`6e0c42ddee090bef366b1c7a8360b16e8a4a6322`; integration target
`155df6eb0e38ecce04a37de2067b0794702fcb83`.

Full-CI run `35676871683` passed both Scala production/test suites but its two
lane proof jobs rejected the intentional disabled-mode resize-name correction
with `LANE_WHEN_VALIDATION_FAIL: disabled RTL changed: lane`. The repaired
validator matches the two retained carriers by exact driver and receiver,
requires ordinary `_zz_` names, and requires the entire disabled artifact to be
otherwise byte-identical. All other disabled artifacts, formal proofs,
simulations, mutations, full builds and cross-Scala requirements remain.

This one-shot controller dispatches only the four affected workflows: the
failed lane qualification plus CDC fixed-point/source review, cumulative source
overlay, and pass-workspace review gates. It never writes source refs, launches
full CI, merges, cancels work, or retries a request. A new full-CI launch is
prohibited until these exact-head targeted runs are terminal success.
