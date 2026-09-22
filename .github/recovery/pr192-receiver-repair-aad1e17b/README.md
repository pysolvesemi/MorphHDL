# PR192 receiver naming proof repair

Exact repaired head `aad1e17b23e58beea985e7ddacbfbea5ab68b84e`, tree
`d19d225af835fcba77bcf96e142cf8a79f4a0fb6`; integration target
`155df6eb0e38ecce04a37de2067b0794702fcb83`.

Exact-head targeted run `35683234812` passed both Scala production/test suites
but its two proof jobs rejected the receiver fixture's intentional disabled-mode
resize-name correction with
`LANE_WHEN_VALIDATION_FAIL: disabled RTL changed: receivers`. The repaired
validator matches the receiver fixture's one retained `lanes` to `io_de`
carrier by exact driver and receiver, requires ordinary `_zz_` naming, and
requires the entire disabled artifact to be otherwise byte-identical. The two
previously authenticated lane carriers, disabled conditions artifact, formal
proofs, simulations, mutations, full builds and cross-Scala requirements remain.

This one-shot controller dispatches only the four affected workflows: the
failed lane qualification plus CDC fixed-point/source review, cumulative source
overlay, and pass-workspace review gates. It never writes source refs, launches
full CI, merges, cancels work, or retries a request. A new full-CI launch is
prohibited until these exact-head targeted runs are terminal success.
