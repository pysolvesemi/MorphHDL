# Executable architecture fixtures

These files are executable output contracts for the parameterized backend.

- `parameterized_wire.v` is owned by Increment 2. CI generates it twice from
  ParamRTL, checks deterministic byte equality with this golden, and validates
  the actual generated artifact with external tools.
- `derived_width.v` is owned by Increment 3. Its two public parameters feed an
  acyclic local-parameter expression graph and its packed port width remains
  symbolic in the single public module definition.
- `parameter_forwarding.v` is owned by Increment 4. CI generates its one leaf
  and one parent definition together, checks a derived named child-parameter
  binding and proves the exact elaborated hierarchy before synthesis.
- `lane_array.v` is owned by Increment 5. CI generates one `PixelLane` and one
  `LaneArray` definition with a named generate-for loop, a scoped symbolic
  index and parameterized indexed part-select bindings. Its pre-synthesis
  structural gate resolves Yosys child types dynamically, so it does not
  depend on implementation-specific `$paramod` names or generated-cell names.
- The generated-fixture testbenches cover default, minimum, awkward and mixed
  overrides. `DerivedWidthTb` checks widths 35, 4, 18, 27 and 23 in five
  simultaneous instances of the same emitted module. `ParameterForwardingTb`
  checks forwarded widths 32, 1, 15, 24 and 20 through five simultaneous
  instances of one emitted hierarchy. `LaneArrayTb` exercises the same five
  flattened widths while the structural gate proves 4x8, 1x1, 3x5, 3x8 and
  4x5 exact lane partitions.

Run:

```bash
./morphhdl/scripts/check-contracts.sh --require-tools
```

The required mode needs Verilator, Icarus Verilog, `vvp` and Yosys. Without the
flag, the script still performs structural, parity-manifest and
forbidden-SystemVerilog checks and reports missing external tools as skipped.
With no generated directory argument it checks the reviewed goldens. CI passes
the actual emitter output with `--generated-dir`.
