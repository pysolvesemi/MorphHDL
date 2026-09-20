# Increment 2: ParamRTL vertical slice

Increment 2 turns the `ParameterizedWire` architecture fixture into executable
compiler behavior.

## Implemented

- An isolated, target-neutral `paramrtl` Scala module.
- Immutable IR nodes for integer parameters, constrained packed widths, ports,
  references and continuous assignments.
- Fail-closed validation for declaration uniqueness, reference resolution,
  legal drivers, type consistency, parameter defaults and widths proven
  positive over the declared legal domain.
- An isolated strict Verilog-2001 backend with target keyword and portable
  integer capability checks, including proof that each legal parameter domain
  fits Verilog's signed 32-bit `integer` representation.
- Deterministic emission with canonical ordering, no timestamps or paths, and
  exactly one logical module definition.
- Generation of `ParameterizedWire` byte-for-byte equal to the checked-in
  golden contract.
- External validation of the generated file through Verilator lint, Icarus
  default/override simulation, and full Yosys synthesis at widths 8 and 13.
- A machine-readable inherited-check parity inventory and a stronger concrete
  SpinalHDL semantic-check regression.

## Deliberately deferred

- The public `HdlInt` and `MorphVerilog` frontend.
- Connection to the inherited SpinalHDL phase plan.
- Arithmetic parameter expressions, local parameters and Boolean parameters.
- Processes, registers, memories and aggregate types.
- Module instances, parameter forwarding and generate regions.

Those capabilities are added incrementally on top of this validated IR and
emitter boundary. No later increment may bypass the validator façade.
