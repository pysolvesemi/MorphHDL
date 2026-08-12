# Executable architecture fixtures

These files are executable output contracts for the parameterized backend.

- `parameterized_wire.v` is owned by Increment 2. CI generates it twice from
  ParamRTL, checks deterministic byte equality with this golden, and validates
  the actual generated artifact with external tools.
- `lane_array.v` proves child parameter forwarding, one logical child module,
  a named generate-for loop and parameterized indexed part-selects.
- The matching testbenches instantiate both defaults and awkward overrides
  (`WIDTH=13`, `LANES=3`, `DATA_WIDTH=5`).

Run:

```bash
./morphhdl/scripts/check-contracts.sh --require-tools
```

The required mode needs Verilator, Icarus Verilog, `vvp` and Yosys. Without the
flag, the script still performs structural, parity-manifest and
forbidden-SystemVerilog checks and reports missing external tools as skipped.

The lane-array fixture remains an expected-output contract until later
increments implement hierarchy and generate support.
