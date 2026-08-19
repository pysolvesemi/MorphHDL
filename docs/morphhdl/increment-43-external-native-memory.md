# Increment 43 — Native memory reuse with zero `Mem.scala` changes

Increment 43 restores the selected upstream `Mem.scala` and native
`PhaseVerilog.scala` byte-for-byte. MorphHDL no longer changes ordinary Mem
constructors or routes memory rewriting through the native Verilog phase.

## External ownership

- `morphhdl.frontend.Mem` delegates construction to the ordinary native Mem
  factory, then records only bounded depth metadata in a weak object-identity
  registry.
- `ExternalParameterizedMemoryRegistry` discovers symbolic element geometry
  from the existing external HardType/width registry after normal elaboration
  and inherited validation. It also reads the temporary StreamFifo library-depth
  tags that remain until Increment 45.
- Generic width inference resolves native `MemReadSync` result geometry through
  that external registry, so read results and their downstream assignments retain
  the symbolic element-width expression instead of the concrete witness width.
- The existing Increment 35 memory analyzer/lowerer now lives in the MorphHDL
  orchestration module. It still derives native read/write ports, address
  expressions, enables, clocking, masks and collision policy directly from AST
  identities.
- The final publication order is memory, process, structure, then generic
  expression/connection/hierarchy lowering.

The returned memory object and its `readSync`, `write`, `readSyncPort` and
`writePort` algorithms are unmodified SpinalHDL implementations. Ordinary
`SpinalVerilog` ignores the external registry and remains concrete.

## Preserved contracts

The dual-Scala proof retains the complete Increment 35 policy:

- one reviewed synchronous read and whole-word write shape;
- explicit active-high enables;
- positive-edge shared clocking;
- explicit read-first collision behavior;
- address capacity over the complete declared depth domain;
- guarded out-of-range reads and writes with zero read fallback;
- parameterized element width and depth without specializing the module; and
- concrete-default parity, deterministic Verilog-2001, simulation, lint and
  synthesis gates.

The permanent `MorphHDL external native memory` workflow enforces the ownership
boundary and these contracts on Scala 2.12.18 and Scala 2.13.12 before merge.
