# Ordinary bit selection and child-port composition

LWR-14 adds `Bits(i)` for an authenticated scoped finite-loop index. It delegates
to the existing finite-index selector, retaining ownership, full-domain bounds,
structural branch guards and assignment lineage. Guarded affine reads use a typed
Boolean Vec view internally; callers no longer need to author `asBools` views.
Concrete Int and hardware UInt indexing keep their existing meanings. An escaped
index or an access whose bounds cannot be proved is rejected. This does not add a
general conditional-process reconstruction rule.

LWR-15 projects an immediate child's formal width through its retained actual
binding before parent scalar width inference. The same projection applies to
clones of child ports, avoiding a foreign formal in the parent's schema. Native
arithmetic/casts remain native operators; their width rules are unchanged.
Different formal names may refer to the same authenticated parent expression.
Equal default values or equal spellings are not evidence of equal parameters.

LWR-16 accepts concrete child outputs in parent expressions and sibling inputs.
The native emitter retains the actual connection nets. A consumer's result width
does not constrain every operand to that result width. Supported parent expression
trees include Boolean predicates, native scalar arithmetic/casts, and fixed
slices whose entire range is proved against the source width domain. Direct
child outputs may drive a proved fixed parent slice; native assignment checks
still own overlap and coverage. Literal one-bit child outputs retain packed
`[0:0]` wrapper nets for indexed reads, while scalar input casts retain their
native connection spelling. Fixed concrete child inputs can retain bit/slice
overrides when exact unconditional native assignments cover the complete port
(including split defaults). Every partial source must have the exact selected
width throughout its domain. This concrete-port path also admits native fixed
resize and fixed-width left-shift expressions after validating their operands;
it does not infer a symbolic target width from the resize witness. Missing coverage, symbolic partial bindings and
competing drivers remain rejected. Whole-leaf
conditional/mux drivers can establish geometry from their proved source widths;
this geometry evidence does not establish assignment coverage. Native direction,
latch, overlap and competing-driver checks still apply.

These rules do not permit arbitrary descendant access, inout reinterpretation,
unauthenticated resize geometry or parameter-width inference from a witness.
Partial assignments do not supply the whole-leaf mux width proof. Applications
may still use explicit typed carriers where their intended resize/ownership
contract requires them.

`LightweightPortCompositionTests` checks direct and sliced connections, mux
sources, sibling arithmetic, differently named/derived actuals, concrete and
symbolic widths, guarded affine bit reads, disabled preservation, X/Z behavior,
and negative ownership/direction/binding cases. The LWR-13–16 completion receipt
records native and unchanged-cleaner validation separately from historical runs.

Fresh results: [LWR-13–16 local acceptance](constructor-composition-acceptance.md).
