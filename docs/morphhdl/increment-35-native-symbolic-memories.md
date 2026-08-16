# Increment 35: native symbolic memories

Increment 35 carries bounded symbolic element width, word count and address
width through ordinary SpinalHDL `Mem`, `readSync` and `write` calls. The
normal Spinal graph remains the concrete witness and still executes the shared
Spinal validation plan before MorphHDL rewrites the emitted Verilog.

## Source contract

A supported single-port memory uses one shared address:

```scala
val memory = Mem(Bits(width bits), depth)
val word = memory.readSync(
  address,
  enable = readEnable,
  readUnderWrite = readFirst
)
memory.write(address, writeData, enable = writeEnable)
```

Using distinct `readAddress` and `writeAddress` selects the existing
single-clock simple-dual-port `1R1W` contract. `depth` is an `HdlInt` with a
positive finite domain; `depth.addressWidth bits` retains the exact portable
`clog2(depth, 1)` correlation instead of specializing to the witness.

## Validated policy

The first native memory tranche admits exactly one memory per module with:

- one positive-edge `readSync` port and one whole-word `write` port;
- one shared clock and explicit active-high read and write enables;
- `readUnderWrite = readFirst`;
- no masks, initialization, mixed-width ports, asynchronous ports, combined
  read/write ports or forced black-box translation;
- equivalent unsigned read/write address types whose complete domains prove
  capacity for every legal depth;
- either one shared address or two independent addresses.

An enabled in-range read captures the old word. A disabled read holds. An
enabled surplus-address read captures zero, and a surplus write is ignored.
Same-address simultaneous read/write therefore returns the pre-write value and
then commits the write, matching the Increment 20--22 and 26 contracts.

## Strict Verilog-2001 lowering

MorphHDL retains the normal emitter's names and wiring, rewrites the memory
array to symbolic geometry, and replaces only its native clocked memory block
with the reviewed guarded process. The address ABI uses the collision-safe
module-local `clog2` helper from Increment 24; no SystemVerilog `$clog2`,
specialized module copy or independently overrideable address-width parameter
is emitted.

Ordinary `SpinalVerilog` remains unchanged and emits only the concrete witness.
Parameterized hierarchy, multiple memories, masks, initialization, alternate
collision modes, asynchronous ports and vendor primitive guarantees remain
deferred.

## Executable evidence

`NativeSymbolicMemoryTests` covers shared-address and independent-address
lowering, concrete-mode isolation, read-first enforcement, explicit enables,
whole-domain address capacity and mask rejection on Scala 2.12 and 2.13.
`HdlIntTests` also proves that derived bounded bit counts retain their concrete
witness, symbolic expression, complete range, public parameter schema and
source location across both supported Scala lines.

## Recommended next increment

Increment 36 should integrate the same retained parameter semantics with
ordinary Spinal library modules, beginning with real `Counter`, `StreamFifo`
and `m2sPipe` reuse rather than fixture-specific wrappers.
