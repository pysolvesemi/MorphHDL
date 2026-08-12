# Executable architecture fixtures

These files are executable output contracts for the parameterized backend.

- `parameterized_wire.v` is owned by Increment 2. CI generates it twice from
  ParamRTL, checks deterministic byte equality with this golden, and validates
  the actual generated artifact with external tools.
- `derived_width.v` is owned by Increment 3. Its two public parameters feed an
  acyclic local-parameter expression graph and its packed port width remains
  symbolic in the single public module definition.
- `lane_array.v` proves child parameter forwarding, one logical child module,
  a named generate-for loop and parameterized indexed part-selects.
- The generated-fixture testbenches cover default, minimum, awkward and mixed
  overrides. `DerivedWidthTb` checks widths 35, 4, 18, 27 and 23 in five
  simultaneous instances of the same emitted module.

Run:

```bash
./morphhdl/scripts/check-contracts.sh --require-tools
```

The required mode needs Verilator, Icarus Verilog, `vvp` and Yosys. Without the
flag, the script still performs structural, parity-manifest and
forbidden-SystemVerilog checks and reports missing external tools as skipped.
With no generated directory argument it checks the reviewed goldens. CI passes
the actual emitter output with `--generated-dir`.

The lane-array fixture remains an expected-output contract until later
increments implement hierarchy and generate support.
