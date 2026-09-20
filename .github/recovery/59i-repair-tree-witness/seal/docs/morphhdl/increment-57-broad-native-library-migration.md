# Increment 57 — broad native library migration and proof

## Status and frozen scope

Increment 57 broadens the Increment 56 native-looking call surface only where
the underlying native algorithm has already been reviewed and can remain the
sole implementation. The migration is additive: a typed overload retains an
`ElabInt` or `ElabBool` until structural elaboration, then delegates every
selected alternative to the ordinary SpinalHDL algorithm. Existing
`Int`/`Boolean` signatures, defaults, descriptors and concrete behavior remain
authoritative.

The frozen migration surface is:

| Family | Typed ingress added or exercised | Authoritative implementation |
| --- | --- | --- |
| Stream pipeline | `pipelined(ElabBool, ElabBool, ElabBool)` | the five legal `Boolean` pipeline alternatives |
| Flow pipeline | `m2sPipe(ElabBool, ...)` | the existing `m2sPipe(Boolean, ...)` register algorithm |
| Synchronous queues | Stream `queue`, `queueWithOccupancy`, `queueWithAvailability`, and Flow occupancy/availability helpers with `ElabInt` depth | the reviewed native `StreamFifo` implementation |
| Counter and memory users | native `Counter(ElabInt, ...)` and `Mem(..., ElabInt)` calls in the same application-shaped queue component | the typed primitives completed before Increment 57 |
| Bus/register map | `BusSlaveFactory` read, write, event and read/write helpers with an `ElabInt` single address | the generic `AddressMapping` decoder used by native bus factories, with each address-normalizing factory declaring its low-bit alignment |

The reviewed native surface consists of `Stream.scala`, `Flow.scala`,
`bus/misc/Misc.scala`, `bus/misc/BusSlaveFactory.scala`, the alignment-policy
overrides in the AXI4, AXI4-Lite, TileLink and Wishbone factories, and typed
mapping fallbacks in the BRAM and AHB-Lite factories. The bus-family changes do
not introduce a typed decoder: all retain the one generic `AddressMapping`
path and their established transaction timing. Counter, Mem and StreamFifo
production changes from earlier increments are proof dependencies, not a
reason to reopen their algorithms.

## Deliberate exclusions

The following remain outside Increment 57:

- `StreamFifoCC`, clock-domain-crossing queue overloads and all other CDC
  algorithms;
- metadata-only `keep` and `crossClockData` flags, which remain static
  `Boolean` values;
- `queueOfReg`, `queueLowLatency` and other register-backed or low-latency FIFO
  variants;
- widening arbitrary bus mappings, ranges, masks or sequential allocation to
  symbolic values;
- a bus-family-specific symbolic decoder or an AXI4-specific typed shadow;
- removal of MorphHDL compatibility factories, adapters, registries or shadow
  paths, which is assigned to Increment 58; and
- additional symbolic roots, witness recovery, emitted-name matching or
  component/source recognition.

This is intentionally a reviewed cross-section rather than a claim that every
parameter-like value in `spinal.lib` is now symbolic.

### Successor note

The exclusions above record the exact Increment 57 closure boundary and remain
historically accurate. Increment 57a is a separate dependent increment that
may successor-refine only the native `StreamFifoCC` depth surface, its existing
cross-clock Stream helpers, typed-width `BufferCC` propagation and the
authenticated dual-clock memory path. It does not retroactively enlarge
Increment 57 or relax any of the other exclusions above.

## Typed pipeline control

`Stream.pipelined` accepts three explicit `ElabBool` arguments. It first proves
over the complete admitted domain that `halfRate` is never enabled together
with `m2s` or `s2m`. A concrete or domain-constant call takes the corresponding
ordinary overload directly. A symbolic call uses `ElabControl.selectSymbolic`
to connect exactly one of the five legal native alternatives to one stable
result carrier. Independent roots, null predicates and any domain containing
an illegal flag combination fail closed before RTL emission.

`Flow.m2sPipe` applies the same pattern to `holdPayload`: both structural
alternatives invoke the established Boolean method and drive one stable Flow.
`flush` remains an ordinary hardware signal. `crossClockData` remains a static
Boolean because it attaches CDC metadata rather than selecting a reviewed RTL
shape.

Neither overload reproduces the register, handshake or payload algorithm.
The typed code is limited to exact-domain validation, structural selection and
connection of native alternatives.

## Typed synchronous queue helpers

The Stream `queue`, occupancy and availability helpers accept an `ElabInt`
depth with explicit arities so overloaded default arguments are not duplicated.
The helpers instantiate the already typed native `StreamFifo` with the same
latency-derived async-read/bypass choices as the ordinary helper and with the
ordinary flush-disabled behavior. Flow occupancy and availability helpers use
the same typed FIFO and preserve pruning of the unused push-ready signal.

The migration applies only to single-clock synchronous helpers. It does not
make latency, `forFMax`, initialization, register-FIFO selection or clock
domains symbolic. It also does not add an `ElabInt` constructor to
`StreamFifoCC`.

The application fixture combines both queue families with a typed Counter and
a native synchronous Mem. This proves that one exact `DEPTH` root controls the
counter width, FIFO storage and accounting widths, and memory address and
unpacked-array geometry without a component-specific bridge.

## Generic typed single-address mapping

`ElabIntSingleMapping` is a narrow `AddressMapping` implementation for one
typed address. It projects the complete exact domain once, rejects every
negative value, and—when admitted by a factory—checks every value for that
factory's native address alignment and fit in the smallest possible bus-address
width. Full-address decoders default to one-byte alignment. AXI4, AXI4-Lite,
TileLink and Wishbone opt in only when their native address path clears or
reconstructs low bits. Its
hardware `hit` method materializes the projected expression at the native bus
address width with `ElabValue.uintLike`; it never replaces the expression with
a witness. Bounds expose the complete admitted range so the existing generic
decoder covers every override. Adding a fixed byte offset retains the
expression through typed arithmetic and revalidates the effective address.

Operations that inherently demand one concrete address (`hit(BigInt)`,
`randomPick` and `foreach`) are permitted only for a domain-constant mapping
and otherwise fail closed. `BusSlaveFactory` sends a concrete typed address to
the ordinary `BigInt` method and uses `ElabIntSingleMapping` only for a
symbolic address. Its explicit typed overloads cover direct and bit-offset
forms of `read`, `write`, `onRead`, `onWrite` and `readAndWrite`; each
immediately delegates to the same public or primitive path used by concrete
calls.

The AXI4 proof uses the real `Axi4SlaveFactory`. Its `OFFSET_WORD` parameter has
the exact domain 4 through 28 and becomes the typed byte address
`OFFSET_WORD * 4`; direct mappings, derived `+4` and `+8` register/event
mappings, and an unrelated fixed address all share the native factory. Concrete
witnesses cover byte addresses `0x010`, `0x040` and `0x070`. Negative,
unaligned and address-width-overflow domains are rejected before RTL emission.
The AXI4 production change is limited to declaring the alignment already
imposed by its masked native address. AXI4-Lite and TileLink declare the same
native word-alignment policy; Wishbone derives it from configured address
granularity.

APB3 supplies the complementary proof: typed literal address `1` emits
byte-identically to ordinary `BigInt(1)`, and a symbolic exact domain `1`
through `3` remains legal because APB compares the full byte address. Real
BRAM and AHB-Lite fixtures prove that their delayed factories consume exact
typed mappings. BRAM compares its registered address for one-cycle reads and
its live address for writes; AHB-Lite keeps its existing delayed transaction
path. Neither factory reproduces register-map behavior or uses a witness.

## Source and negative boundary

`check-broad-native-library-migration-boundary.sh` seals both the positive
surface and the exclusions. It requires:

- explicit typed Stream/Flow method signatures, `ElabControl` validation and
  delegation to ordinary alternatives;
- typed synchronous queue helpers backed by `StreamFifo`;
- the narrow typed mapping, factory-selected alignment policy and
  BusSlaveFactory overload set;
- typed mapping fallbacks for the native delayed BRAM and AHB-Lite paths;
- application-shaped source using ordinary `spinal.core._`, `spinal.lib._`
  and real AXI4, APB3, BRAM and AHB-Lite factories; and
- fail-closed concrete-only mapping operations.

It rejects typed CDC, register-FIFO and low-latency helper signatures, typed
`keep`/`crossClockData`, Morph-prefixed production factories, witness access
at application call sites, and AXI4-local typed/shadow machinery. Its isolated
self-test first checks copied good sources, then removes the Stream legality
proof, injects a typed CDC queue signature and removes BRAM's generic read
decoder. All mutations must be rejected with stable diagnostics.

## Required proof matrix

Closure requires the following evidence on the exact committed revision:

| Evidence | Required observation |
| --- | --- |
| Dual-Scala source/JVM compatibility | Scala 2.12.18 and 2.13.12 compile the new overloads while inherited literal clients and public descriptors remain compatible |
| Concrete parity | ordinary Boolean/Int calls emit deterministic, parameter-free RTL and the detached upstream/current concrete parity suite remains byte-identical |
| Pipeline overrides | all five legal `PIPE_MODE` values (m2s, s2m, half-rate, full and pass-through) elaborate/compile through the native Stream and Flow algorithms; independent roots and illegal domains fail closed |
| Queue/Counter/Mem overrides | representative `DEPTH` values, including non-powers of two, compile as strict Verilog-2001 with matching FIFO, counter and memory geometry |
| Bus/register-map overrides | direct and derived AXI4 offsets elaborate for every admitted value, concrete witnesses match ordinary factory output, APB accepts reachable unaligned byte offsets, BRAM/AHB consume exact typed mappings, and fixed mappings stay fixed |
| Formal equivalence and mutation | parameterized and independently generated concrete Stream/Flow pipelines are unbounded-equivalent for all five modes, and AXI4 register/event behavior is equivalent for all three offsets; each deliberate mutation must produce a real counterexample |
| Tool and determinism gates | Icarus, Verilator, Yosys/SymbiYosys and repeated emission checks retain the inherited strictness |
| Native audit | every native byte is covered by an independently reviewed span in the Increment 55 schema; no other native path changes |
| Boundary mutation | the Increment 57 boundary guard and its isolated mutation self-test pass |

`NativeLibraryMigrationTests` supplies the pipeline, queue/Counter/Mem,
literal, determinism and negative-domain cases.
`NativeAxi4SlaveFactoryParameterizedOffsetTests` supplies real-factory
parameter override and concrete comparison evidence, APB full-address parity,
and BRAM/AHB typed delayed-decoder coverage.
`NativeAxi4SlaveFactoryFormalEquivalenceTests` supplies the unbounded bus
equivalence and deliberate mutation control.
`NativeLibraryMigrationFormalEquivalenceTests` supplies the unbounded
Stream/Flow pipeline equivalence and mutation control, while
`ExternalHierarchyBoolLiteralBindingTests` locks the module-scope literal
binding needed by typed queue helpers. These focused suites supplement, rather
than replace, every inherited compatibility, retirement, formal,
strict-Verilog and approved-native-source gate.

## Closure record

The roadmap checkbox is an evidence-only transition. It remains unchecked on
the implementation revision until the Increment 57 workflow passes for the
exact sealed source scope. At Increment 57 closure, legacy adapter retirement
remained locked to Increment 58 after that merge. The successor roadmap now
additionally requires merged Increment 57a before Increment 58 may start.
