# Increment 59b: closed native operator replay

This is an implementation checkpoint within the existing 59b roadmap item,
not a new increment and not completion of parameterized balanced reduction.

`TypedBalancedReductionOperatorReplay` checks actual captured native operator
bodies and replays an admitted body without invoking its Scala callback again.
It constructs no reduction tree and emits no Verilog. A fresh expression of
exactly the captured native class is rebound through SpinalHDL's existing
`wrapBinaryOperator` algorithm.

The current subprofile covers scalar Bool/Bits/UInt/SInt AND, OR and XOR,
and equal-width modular UInt/SInt addition. Certification uses exact native
classes, never names, witnesses or sample-value equivalence. Both original
operands must occur exactly once through transparent root-scope aliases.
The result graph must consume every recorded declaration and assignment.
State, foreign reads, unused local effects, partial/conditional drivers,
resizes, widening arithmetic, non-associative primitives and unproved graph
shapes are rejected. Fixed widths matching a symbolic width's default are not
accepted as symbolic width evidence.

Every replay rechecks live native primitive input identity, local driver and
declaration state, and exact operand owner/type/width authority. A proof is
opaque and cannot be obtained by constructing a public case class or setting
a Boolean. It is still only an operator-body proof: it is not a certificate of
arbitrary Scala closure purity, stage uniformity, odd-tail correctness, bridge
state, or safe publication after all native phases. The production dispatch
therefore remains disabled until those separate obligations are satisfied.

The regular 59b workflow requires 15 operator replay tests in addition to the
existing 38 capture/plan/native tests, on both supported Scala versions. This
checkpoint also repairs canonical audit-policy regeneration by carrying the
already-reviewed 59b native delta into `increment-55-native-change-review.json`.
The guard is not weakened: it must regenerate a byte-identical manifest from
that canonical policy before any Scala tests execute.

Pending: whole-stage replay, min/max and widening result shapes, registered
level bridges, parameterized publication, formal specialization equivalence
and formal mutation. This document does not claim those gates passed.
