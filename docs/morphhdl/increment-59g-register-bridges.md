# Increment 59g — Native register bridges

Status: implementation and qualification in progress. The roadmap checkbox
remains open until the required workflows pass and the increment is merged.

The ordinary `Vec(...).reduceBalancedTree(op, levelBridge)` source remains the
entry point. Bridge certification records the native register graph and replays
native register declarations, assignments, initialization and `when` conditions.
SpinalHDL still emits the clocked processes.

## Supported bridge forms

The scalar profile covers `UInt`, `SInt`, `Bits` and `Bool`:

- Identity and transparent local aliases.
- `RegNext`, inferred native registers, and chains of native registers.
- Register depth selected by the native zero-based bridge level.
- No initializer, zero, and typed nonzero constant initializers that fit every
  active width in the declared domain. Signed constants retain their sign.
- `RegNextWhen` with a closed local Boolean predicate of that register's exact
  data input: a Bool input, fixed bit, MSB, Boolean NOT, AND, OR or XOR.

The host callback contract remains capture-free and rejects unknown calls,
mutable host state and witness-width queries before execution. Arbitrary
external enable captures, feedback, partial assignments, false-arm updates,
soft reset, BOOT initialization and mixed clock domains are outside this
qualified bridge profile. These restrictions concern bridge certification;
they do not change ordinary native SpinalHDL register APIs.

Every pair result and odd tail passes through its native bridge. COUNT=1
bypasses all operators and bridges, including their callbacks, and adds no
register latency. With unconditional registers, latency is the sum of the
register counts at active levels, measured in enabled clock edges. A local
enable can stall its own register indefinitely; it does not promise fixed
transaction latency or a global pipeline stall.

## Clock and initialization contract

The clock matrix contains synchronous and asynchronous reset, active-high and
active-low reset, rising and falling clock edges, and active-high, active-low
or absent clock enable: 24 configurations. Native reset/enable precedence is
preserved. In particular, a synchronous reset under the native clock enable
is sampled on an enabled edge; asynchronous reset can affect initialized
registers between sampling edges.

Registers without an initializer start unconstrained. The `regResult` oracle
becomes comparable after its full pipeline depth of enabled sampling edges.
Reset does not initialize these registers. The proof does not force DUT state
to zero. Resettable results are checked after legal reset entry and under
arbitrary later inputs, enables and in-flight resets.

## Qualification matrix

One reusable singleton-default candidate is generated for each clock profile.
A second candidate with different WIDTH/COUNT defaults is generated for the
representative synchronous, active-high, rising-edge, active-high-enable
profile. All candidates retain WIDTH in 1..32 and COUNT in 1..17; candidates
are never regenerated for individual overrides.

The matrix includes the common WIDTH `{1,5,8,32}` by COUNT
`{1,2,3,5,8,9,16,17}` boundary cases. Clock configurations with an explicit
enable additionally cover WIDTH `{5,8}` by COUNT `{1,2,3,5,9}`; absent-enable
configurations cover WIDTH=5, COUNT=3. There are 25 candidate files, 190
independently elaborated native references and 222 candidate/reference
specialization comparisons, each with 11 independent outputs.

Both Scala 2.12.18 and 2.13.12 must pass graph and public API tests, repeated
byte-deterministic generation, strict Verilog-2001 parsing and lint, Icarus
simulation, full Yosys synthesis, sequential equivalence and functional
mutation controls. The checker exercises reset entry, enabled and stalled
edges, reset during a stall, asynchronous assertion between edges, and
in-flight reset. Added/removed stages, wrong initialization and changed
reset/enable precedence must produce actual failing output waveforms.

The native reference lives in
`morphhdl/src/test/scala/nativeapplication/BalancedBridgeNativeOracle.scala`.
Its concrete native reductions do not use candidate graph replay. The Python
cycle model provides an additional independent check of pairing, odd tails,
per-register state and enables. Passing this finite matrix is not a claim of
universal formal quantification over all parameter values.

Widening/composite/nested-owner combinations remain Increment 59i integration
gates. The inherited 59b, 59c, 59d, 59e and 59f checks remain in force.

## Example

```scala
val values = in Vec(UInt(width bits), count)
val result = out UInt(width bits)
result := values.reduceBalancedTree(
  (a: UInt, b: UInt) => a + b,
  (value: UInt, _: Int) => RegNextWhen(value, value.msb) init U(1)
)
```

The completion record will include the actual generated Verilog and the
tested source/merge revisions after qualification.
