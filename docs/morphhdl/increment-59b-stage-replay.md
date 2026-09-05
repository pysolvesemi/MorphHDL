# Increment 59b - Whole native stage and register-bridge replay

## Status

This implementation extends the prior operator-body checkpoint. It is not
parameterized COUNT publication and does not complete Increment 59b. The
controlling checkbox stays unchecked and PR #157 stays draft. New code and
hardware gates require exact-head CI results before any passing claim.

## Native graph authority

`TypedBalancedReductionStageReplay` invokes the original generic native helper
through `TypedBalancedReductionCapture`, observing every operator and bridge
as it completes. It certifies every row, including odd tails, rather than
assuming that the first operator proves an entire native tree. All operators
must use one admitted native associative primitive. Every row at a given level
must have identical bridge behavior. Levels may have different register-chain
lengths, but all registers must belong to one exact native clock domain.

The existing 14-primitive profile remains unchanged. `TypedBalancedReductionValueEvidence`
links each intermediate to an earlier opaque proof of that exact result object.
This carries the original independent WIDTH authority through the native graph
without manufacturing a symbolic width annotation from an equal Int witness.

The exact Vec identity/shape, scalar metadata, local drivers, primitive inputs,
initializers and clock identities are checked for freshness. A whole-statement
inventory also rejects assertion, memory or other statement effects outside
the captured declaration/assignment graph. No recorded effect is silently
removed. Certificates remain pre-normalization evidence, not post-phase permits.

## Bridge subprofile

`TypedBalancedReductionBridgeReplay` supports scalar identity/transparent-alias
bridges and unconditional register chains with zero initialization or no
initializer. Replay uses inherited native clone/register/assignment construction
inside the exact retained clock domain; it does not call the Scala bridge again.

Initializer literals may have local native scalar aliases. Those aliases and
all their drivers must be consumed, same-typed, constant-only and closed. The
profile rejects external/nonzero/unknown initializer expressions and sized
zeros whose width would constrain an inferred register above the smallest
certified data width. Fresh replay uses native unsized zero literals.

SpinalHDL `HardType` fixes a driven source width before cloning. An inferred
width becoming its already-proved parameter-free constant is safe. Freezing a
symbolic WIDTH to its default is not. Thus ordinary concrete RegNext is
admitted, whereas an untyped symbolic RegNext that freezes an intermediate
still rejects. Inferred native registers with width-independent initializers
exercise the symbolic-width certificate path without bypassing this guard.

Latency is measured in enabled sampling edges of the one native clock domain.
For the synchronous-reset/clock-enable fixture, native `emitClockedProcess`
places the clock-enable condition outside the synchronous-reset condition.
Therefore reset is sampled only when enable is active. Neither the Python
reference model nor the deliberate latency mutation changes that precedence.

## Independent hardware qualification

`TypedBalancedReductionStageArtifactWriter` separately elaborates ordinary
native reference and certified native replay designs, and repeats the entire
generation for deterministic byte comparison. WIDTH={1,5,8,32} crossed with
COUNT={1,2,3,5,8,9,16,17} and three bridge modes gives 96 concrete shapes.
Each exposes all 14 primitive outputs. Bridge modes are identity, one register
at every active level, and identity at level zero followed by two-register
chains at later active levels. Singleton domains invoke neither callback.

The read-only stage workflow requires all 95 Scala tests (the earlier 73 plus
22 stage cases), Verilog-2001 compilation, strict Verilator lint, full Yosys
synthesis, deterministic output and independent arithmetic/pipeline simulation.
Simulation includes in-flight resets with enable both low and high, long
stalls and directed input patterns. A reset-entry SAT proof starts from
unconstrained state and applies an enabled reset. Temporal induction then
proves equality from the resulting zero register state with subsequent reset,
enable and data unconstrained. A deliberate extra enabled cycle must produce
an actual counterexample and a VCD with bad=1. Errors, timeouts, UNKNOWN and
missing success markers are not passing evidence.

Artifacts explicitly retain `scope: concrete-native-stage-replay` and
`parameterized_tree_formal: not-run`. Adding these checks is not proof that
any new configuration has passed them.

## Remaining publication boundary

Uniform graphs observed at the maximum carrier do not establish arbitrary
Scala callback purity or stability across COUNT/WIDTH specializations. A
stateful bridge can produce uniform rows at the carrier while changing its
behavior when a smaller count invokes fewer earlier callbacks. This native
certificate therefore cannot authorize production publication; its
`requirePublicationCertificate()` always rejects.

Before completing 59b, establish the restricted callback/publication contract,
handle typed native cloning without width-default leakage, connect generic
symbolic stages and odd-tail assignments to the existing structural backend,
retain validity through the required native phases, and prove emitted
parameterized specializations against independent native references. Min/max
and widening replay remain outside this current width-preserving profile.
The latest integration branch and all inherited gates must also be reconciled.
