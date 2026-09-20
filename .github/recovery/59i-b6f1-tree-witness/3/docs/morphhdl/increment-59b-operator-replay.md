# Increment 59b: closed native operator replay

This is an implementation checkpoint within the existing 59b roadmap item,
not a new increment and not completion of parameterized balanced reduction.

`TypedBalancedReductionOperatorReplay` checks actual captured native operator
bodies and replays an admitted body without invoking its Scala callback again.
It constructs no reduction tree and emits no Verilog. Fresh expressions of
exactly the captured native classes are rebound through SpinalHDL's existing
operator, comparison and multiplexer construction methods.

The current subprofile covers scalar Bool/Bits/UInt/SInt AND, OR and XOR,
equal-width modular UInt/SInt addition, and equal-width UInt/SInt minimum and
maximum. Certification uses exact native
classes, never names, witnesses or sample-value equivalence. Each primitive
input pair must contain both original operands through transparent root-scope aliases.
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
state, or safe publication after all native phases. Production dispatch also
requires callback-code admission, complete stage/bridge certificates and the
distinct native template handoff in `increment-59b-publication.md`.

The regular 59b workflow requires 23 operator replay tests in addition to the
existing 38 capture/plan/native tests, on both supported Scala versions. This
checkpoint also repairs canonical audit-policy regeneration by carrying the
already-reviewed 59b native delta into `increment-55-native-change-review.json`.
The guard is not weakened: it must regenerate a byte-identical manifest from
that canonical policy before any Scala tests execute.

Minimum and maximum require the exact native UInt/SInt binary multiplexer,
driven by the matching native strict less-than comparator. Both comparison
inputs and both mux arms must resolve to the two original operand identities.
Their order is preserved during replay. Local Bool selector aliases are checked
as Bool graphs, separately from the data-width authority. Signed/unsigned casts,
foreign selectors, other comparisons and repeated arms are rejected. The
semantic operation key distinguishes minimum from maximum even though both
have the same native mux class, so whole-stage certification can reject mixed
operations. Freshness guards cover the comparison inputs, selector and arms.

The operator and stage artifact matrices now require all 18 outputs, including
signed and unsigned minimum/maximum. Expected simulation values are computed
independently using integer signed decoding; exact-head hardware results must
be recorded separately before claiming these expanded matrices passed.

Widening addition remains rejected. Native `+^` introduces resize nodes and a
larger output width. Supporting it requires a separate proof that carries input
and output width formulas through every stage and reconciles shorter odd tails
with paired results and registered bridges. The existing equal-width proof
cannot infer that contract from the concrete carrier. This checkpoint does not
authorize parameterized publication or claim formal specialization equivalence.

The ordinary native BitVector `min`/`max` methods currently allocate a
fixed-width Mux result from the concrete width witness. Such graphs are admitted
for concrete element widths and remain rejected for symbolic widths whose
authority is lost by that allocation. Inferred native mux graphs can carry
symbolic WIDTH through this replay certificate, but that does not authorize a
Scala helper or callback for production publication. Preserving typed width
authority through ordinary native Mux construction requires a separate generic
clone/mux provenance change; the fixed-width guard is not weakened here.
