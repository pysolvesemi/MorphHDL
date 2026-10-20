# Array reset loops (LWR-38)

The native publisher compacts compatible reset assignments after unpacked-array
publication and late sequential-process grouping. Application initializers remain
ordinary `Vec.fill` / `Vec.tabulate` register initializers.

Each candidate comes from an exact native initializer identity and a retained
Vec leaf-to-array mapping. Text matching only verifies that the authenticated
assignment still has the expected whole-element target; it does not discover
array ownership or infer reset semantics. Signed register initializers retain
that identity when their targets move from scalar read aliases into the array.

Within the existing reset branch, a contiguous run of at least two uniform
literal assignments becomes a procedural loop. Adjacent runs for distinct arrays
may share a loop when their bounds match: their writes are disjoint and their
literal sources have no ordering dependencies. Different literal values remain
explicit or form separate uniform runs. Data writes, reset guards, event controls,
enables and nonblocking operators are unchanged.

```verilog
always @(posedge clk or negedge resetn) begin : reset_scope
  integer reset_index;
  if (!resetn) begin
    for (reset_index = 0; reset_index < 12; reset_index = reset_index + 1) begin
      shadow[reset_index] <= 32'h0;
    end
    shadow[12] <= 32'h00000010;
    shadow[13] <= 32'h00000400;
    shadow[14] <= 32'h00000200;
    for (reset_index = 0; reset_index < 17; reset_index = reset_index + 1) begin
      snapshot[reset_index] <= 32'h0;
      active[reset_index] <= 32'h0;
    end
  end else begin
    // Original native data/enable logic.
  end
end
```

A named process block permits an integer local to that process in Verilog-2001.
Labels and integer names are allocated against existing emitted identifiers;
separate processes never share mutable index storage. Adding a `reset_scope`
block is an intentional generated-hierarchy change, with no port or register-name
change.

The new compaction handles proven fixed, flat unpacked register arrays. Existing
symbolic array/initializer loop lowering remains in place and is regression tested
under width/depth overrides. Packed arrays, nonliteral reset sources, partial or
overlapping reset edges, singleton runs, soft resets, BOOT initialization and
incompatible process scopes are not newly combined. No generate loop or initial
block is introduced, and no application-specific name or Rust cleanup is involved.

Focused local evidence is in the adjacent JSON and
`~/.local/state/morphhdl/lightweight-scala-rtl/lwr38-20261018/`.
The baseline is `20b9c9c7822c0bb8e6125e23ae8819040700db73`; exact source hashes
identify the tested implementation. Final publication records its commit and tree.
No remote CI or full qualification is required by this roadmap item.

## Fresh local validation (18 October 2026)

Both normal Scala builds and all 72 focused test executions passed (36 each on
Scala 2.12.18 and 2.13.12). Coverage includes mixed CSR-sized resets, signed
nonzero-start runs, singleton and empty data ranges, index-name collisions,
synchronous/asynchronous reset and both polarities, reset before a clock edge,
reset/write priority, separate clocks, enables, soft reset, BOOT, nonliteral
reset dependencies, and existing symbolic width/depth initializer overrides.
The empty-data-range regression now distinguishes generate loops from the newly
valid procedural reset loops; its behavioral checks remain intact.

The unchanged production CSR source was freshly compiled and generated twice.
The artifacts are identical. Its existing testbench passes 11,189 default-profile
checks and 653 checks in the existing 64-bit parameter-profile mode. Verilog-2001
compilation, Verilator lint and Yosys driver/latch checks pass. Application source,
testbenches and delivery RTL were not modified. Questa is unavailable. No CI or
full qualification was launched.
