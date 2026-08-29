# Increment 53e generic native-parameterization proof

Increment 53e parameterizes the exact native `spinal.lib.StreamFifoCC` implementation. It does not copy, subclass, reconstruct, or replace its FIFO, RAM, Gray-pointer, synchronizer, clock-domain, reset, occupancy, or handshake logic.

The enabling compiler/runtime/backend rule is intentionally generic across native SpinalHDL:

- native components are selected from typed compiler structure, not a component class name or source filename;
- retained integer expressions are associated with their parameter domain by exact object identity;
- two symbolic widths are accepted as equivalent only when all retained leaves share one canonical parameter-root identity and exhaustive bounded evaluation proves equal, positive results over the complete legal domain;
- rendered Verilog text, equal concrete witnesses, signal names, component names, and source filenames are never proof keys;
- missing provenance, unsupported expressions, excessive domains, undefined values, non-positive widths, or any mismatch fail closed.

Permanent verification gates cover both Scala 2.12.18 and 2.13.12, depths 4, 8, and 16, independent push/pop clocks, both pop buffered-reset modes, deterministic generation, Verilog-2001 lint, behavioral ordering, Yosys synthesis, independently generated native concrete witnesses, solver-backed sequential equivalence, and a mutation that must fail.

The generic-engine boundary guard rejects any compiler/runtime/backend implementation that selects `StreamFifo`, `StreamFifoCC`, `BufferCC`, `Stream.scala`, or `CrossClock.scala` by name. Native SpinalHDL production trees remain unchanged.