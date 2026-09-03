# Increment 57a — typed native StreamFifoCC depth and CDC proof

## Status and dependency

Increment 57a closes the native cross-clock FIFO deferral recorded by
Increment 57. It depends on merged Increment 57 and does not reopen that
increment's synchronous queue, pipeline or register-map scope. This document
freezes the successor contract; it does not itself record successful closure.

The implementation must parameterize the depth of the real
`spinal.lib.StreamFifoCC`. The native dual-clock algorithm remains the sole
implementation. Its RAM, binary and Gray pointers, full and empty detection,
two `BufferCC` synchronizers, synchronous read response pipeline, occupancy
views and optional push-to-pop reset buffering must not be copied into a
MorphHDL component or reconstructed after elaboration.

## Typed native ingress and compatibility

The reviewed typed surface is deliberately narrow:

| Surface | Required typed ingress | Concrete authority retained |
| --- | --- | --- |
| Native definition boundary | definition-only `private[lib]` primary construction with retained `ElabInt` depth | the existing public `Int` constructor descriptor, default reset argument and public `depth: Int`/`ptrWidth: Int` witness accessors |
| Public typed construction | data-type and connected-Stream companion `apply` forms with `ElabInt` depth | both existing `Int` companion entry points |
| Stream helpers | cross-clock `queue` and `queueWithPushOccupancy` with `ElabInt` size | the existing helpers with `Int` size |

An ordinary integer literal must continue to select the `Int` lane and emit
parameter-free RTL byte-identical to the selected upstream behavior. A typed
literal passed through a companion or helper must delegate to that same
concrete lane. Only an exact symbolic `ElabInt` passed through those public
factories may mint the `DEPTH` child formal and create the parameterized
component definition. The typed primary constructor is `private[lib]` and
definition-only; a public direct-child typed constructor could not soundly
establish that formal before construction. There is no symbolic-to-`Int`
conversion, and production geometry may not be recovered from the compatibility
witness accessor.
The existing `ram`, `pushCC` and `popCC` inspection/formal members also remain
aliases of the one native algorithm rather than separately constructed logic.

Clock domains and `withPopBufferedReset` stay ordinary static construction
arguments. Increment 57a does not make reset kind, polarity, synchronizer
depth, CDC attributes or clock topology parameter-selectable.

## Legal depth domain and fail-closed invalid values

The native FIFO accepts only depths that are powers of two and at least two.
The typed entry point must authenticate the complete exact source domain,
reject null or unauthoritative carriers, reject a construction default that is
not legal, and reject any domain below two. Values outside the Scala `Int`
range must already fail at the shared typed public ingress; the local maximum
check remains defensive. Legality must never be established from the default
witness alone.

A public integer parameter can have a contiguous declared range containing
both legal and illegal values. The parameterized definition therefore retains
the exact predicate:

```text
DEPTH >= 2 && isPow2(DEPTH)
```

The unchanged CDC algorithm exists only in the legal generated alternative.
An illegal specialization selects a separate inert alternative: push is not
accepted, pop is not advertised, occupancies are zero and no FIFO storage or
pointer algorithm is active. This is the fail-closed override behavior; it is
not a second FIFO implementation. The inert sibling must not reference or
drive Gray carriers owned by the legal generated alternative; its contract is
the complete public FIFO IO only. Formal equivalence and functional simulation
constrain or select legal power-of-two specializations, while negative tests
prove that invalid defaults and unauthenticated domains are rejected before
emission.

Definition-side `DEPTH` bounds may canonicalize only the invalid tail below
the next power of two; the caller's actual binding retains its exact authored
domain, and the canonical tail introduces no additional legal FIFO depth. A
nonfunctional declaration attribute records the generated legal branch's
projected maximum solely as native structural identity. This lets equal legal
domains share one definition while preventing different legal geometries with
coincidentally equal witness RTL from being merged under incompatible formal
schemas. No backend recognizes the attribute name or reconstructs the FIFO
from it.

## Symbolic geometry through the native algorithm

For every legal specialization, one exact `DEPTH` expression must control all
of the following without witness-sized residue:

- RAM depth;
- pointer and Gray-code width `log2Up(DEPTH) + 1`;
- RAM read and write address width `log2Up(DEPTH)`;
- push and pop occupancy width `log2Up(DEPTH + 1)`;
- the Gray full-comparison mask, including the depth-two boundary; and
- constants used by the native formal assertions.

The old full detector contains Scala slice bounds derived from the concrete
pointer width. The typed lane may replace only that width-sensitive expression
with an algebraically equivalent whole-vector Gray comparison; the concrete
lane must retain the original expression and output. Depth two is mandatory
evidence because its lower comparison slice is empty.

`toGray` and `fromGray` remain shared native helpers. Their typed path may
mechanically retain packed-width lineage and use a bounded parallel-prefix
decode whose stages derive from the authoritative maximum width, but it must
not add a FIFO-specific decoder or unroll only the default width. A typed width
above 32 is required regression evidence for that maximum-derived topology.
For Increment 57a's maximum depth 16, the maximum pointer width is five, so its
specialized decode evidence contains shifts 1, 2 and 4 only; fixed shifts 8 or
16 would be copied topology rather than a maximum-derived result. The concrete
helper path remains authoritative and unchanged in behavior.

## CDC hierarchy and memory preservation

Both Gray pointers must still cross through native `BufferCC` components, and
only Gray-coded pointer state may use those synchronizers. Typed width must be
retained through `BufferCC` ports, registers and returned clones without
changing its configured synchronizer depth or tags. A native zero initializer
on a retained-width synchronizer register must likewise emit from the typed
width rather than freeze the construction witness, and its by-name initializer
must be evaluated exactly once for each retained stage. Generated Verilog
instance parameter insertion must preserve any leading native Verilog
attributes, including `keep_hierarchy`.

The native RAM remains one dual-clock memory: writes occur in the push domain,
reads occur in the effective pop domain, and the native `clockCrossing` tag is
required. The parameterized memory path may admit distinct positive-edge
clocks only for that authenticated native crossing and its independent-address,
`dontCare` collision policy. It must continue to reject an untagged crossing,
unsupported collision behavior or aliased clock/data/control roles.

Fixed-shape aggregate payloads retain the native packed ordering through one
named write carrier; this is mechanical identity required by the memory
publisher, not a second data path. Two typed FIFOs may share the same supplied
clock-domain objects and use unequal depth domains. Their pointer and reset
synchronizers must remain owned by their respective generated definitions,
while structurally identical child definitions remain mergeable.

Reset behavior is not generalized. The default topology continues to derive a
buffered pop reset from the push clock domain. The alternate topology continues
to use the supplied pop reset. Verification of separate resets observes the
native startup contract and does not claim recovery from an arbitrary unilateral
mid-traffic reset.

Retained symbolic zero lowering is cardinality-preserving. An authenticated
initializer identifies the register; every exact, full-target invariant-zero
assignment to that same register is counted in the elaborated graph, and the
fallback rewrites exactly the same number of emitted witness edges. This covers
ordinary clear, flush and wrap-to-zero writes without authorizing partial or
nonzero assignments.

## Deliberate exclusions

Increment 57a does not parameterize or broaden:

- `StreamFifoLowLatency`, `queueLowLatency` or `queueOfReg`;
- `StreamCCByToggle`, `FlowCCByToggle` or other CDC protocols;
- synchronizer depth, CDC metadata, clock domains or reset selection;
- retained-width lowering through the optional Int-width-only
  `PhaseBufferCCBB` transform, which fails closed before witness freezing; its
  deterministic typed diagnostic bypasses SpinalVerilog's Scala-trace retry so
  a later reconstruction failure cannot mask the original rejection;
- arbitrary multi-clock memories without the native clock-crossing contract;
- payload initialization or a different FIFO storage/collision policy; or
- the analog metastability model of a physical synchronizer.

No native-`Int` shadow capture, source-position reconstruction, component or
file recognizer, emitted-name matching, separately authored FIFO, or
construction-witness legality shortcut is permitted.

## Required evidence

Closure requires all of the following on the exact committed revision:

| Evidence | Required observation |
| --- | --- |
| Dual-Scala and compatibility | Scala 2.12.18 and 2.13.12 compile all typed overloads; existing constructor, companion, helper and public-member clients retain source/JVM behavior |
| Concrete parity | the ordinary `Int` constructor and the `Int`/typed-literal companion and helper forms at depths 2, 4, 8 and 32 remain parameter-free and byte-identical |
| Parameter override geometry | one deterministic definition per static reset topology supports legal depths 2, 4, 8 and 16 with exact RAM, address, pointer, synchronizer and occupancy widths |
| Negative domain | null, missing exact authority, depth below two and invalid defaults fail with stable diagnostics; oversized domains fail at the public ingress (an upstream shared exact-publication diagnostic is acceptable); illegal non-default specializations select only the inert generated alternative |
| CDC simulation | push-faster and pop-faster schedules cover empty, full, wraparound, backpressure and temporary interruption of either clock; a separate shared-clock directed witness guarantees one accepted simultaneous push/pop for every depth and reset topology |
| Formal equivalence | independently generated concrete native witnesses and specializations of each static reset topology's parameterized definition are sequentially equivalent under shared push/pop clock schedules and reset assumptions; payload is compared only while pop-valid is asserted; PDR preparation preserves ordinary unknowns as nondeterministic inputs before normalizing residual initialization state symmetrically; sound sequential latch correlation precedes PDR on the full property set |
| Mutation control | a deliberate compared-output mutation produces a genuine solver counterexample; parse failure, missing modules, timeout, `UNKNOWN` or tool error cannot satisfy the control |
| CDC structure | both native synchronizers, their widths/tags, Gray-only crossings, dual-clock RAM contract and static reset/clock policy remain intact |
| Multi-instance and payload structure | unequal typed depth domains sharing static clocks own independent reset synchronizers; a fixed-shape aggregate payload preserves native packed memory ordering |
| Optional blackbox phase | default retry-enabled MorphVerilog and raw SpinalVerilog generation expose the same retained-width diagnostic and typed cause; concrete-width replacement remains available, and ordinary non-typed failures retain Spinal's retry |
| Tools and determinism | strict Verilog-2001 Icarus/Verilator checks, Yosys synthesis, repeated generation and the inherited full gates pass |
| Native audit and boundary | every native change is an independently reviewed manifest span, and the Increment 57a boundary self-test rejects its legality, inert-fallback, shared-Gray-authority, retained-zero-cardinality, formal-normalization, typed-diagnostic retry and CDC-contract mutations |

`NativeStreamFifoCCParameterizedTests` supplies typed ingress, compatibility,
geometry, invalid-domain and deterministic emission evidence.
`NativeStreamFifoCCCdcProofTests` supplies asynchronous ratio/pause stress, a
deterministic shared-edge simultaneous-transfer witness and strict synthesis
evidence. `NativeStreamFifoCCFormalEquivalenceTests` supplies
the candidate-versus-independent-concrete proof and live mutation control.
Its positive ABC engine runs `lcorr` before `pdr`: correlation merges only
SAT/induction-proven equivalent latch state, retains all five assertion outputs
and arbitrary inputs, and adds no proof assumptions. The separate mutation
control remains an unchanged Yices SMT BMC.
The permanent `morphhdl-streamfifocc-proof.yml` workflow enables those suites'
tool-backed proof tests with
`MORPHDL_RUN_STREAMFIFOCC_CDC_PROOF=1` and
`MORPHDL_RUN_STREAMFIFOCC_FORMAL_EQUIVALENCE=1`; it retains their evidence in
the directories selected by `MORPHDL_STREAMFIFOCC_CDC_WORKSPACE` and
`MORPHDL_STREAMFIFOCC_FORMAL_WORKSPACE`.
The inherited `SpinalSimStreamFifoCCTester` and `FormalFifoCCTester` remain
supporting native regressions; their fixed concrete depths do not by themselves
prove parameterized equivalence.

## Increment 57 successor boundary

Increment 57 correctly excluded `StreamFifoCC` when that increment closed. Its
historical document and closure evidence continue to describe that exact
revision. Increment 57a successor-refines only the typed native
`StreamFifoCC`, its two Stream helpers, typed-width `BufferCC` propagation and
the authenticated dual-clock memory path described above. All other Increment
57 exclusions remain binding.

## Closure record

The roadmap checkbox is an evidence-only transition. It remains unchecked on
the implementation revision until the exact source scope is sealed and every
canonical Increment 57a job passes. Changing `[ ]` to `[x]` is the final source
change; the checked revision must pass the same workflow before merge.
Increment 58 remains blocked until that checked revision is merged into
`parameterized-verilog`.
