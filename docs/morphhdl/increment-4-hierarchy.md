# Increment 4: hierarchy and parameter forwarding

Increment 4 extends ParamRTL and the strict Verilog-2001 backend with named
module instances. A parent may forward public or derived parameter expressions
to a child without specializing either logical module in the emitted source.

## Implemented hierarchy tranche

- Stable child-module references and instance names.
- Named child-parameter bindings expressed in the parent parameter scope.
- Named child-port bindings to parent RTL references.
- Resolution of module, parameter, port and parent-signal references.
- Whole-domain checks for forwarded parameter constraints and compatible port
  widths.
- Rejection of recursive module-instantiation cycles.
- Dependency-first deterministic module emission with lexical tie-breaking.
- Strict Verilog-2001 named parameter and port association syntax.

Generate regions remain outside this increment. A hierarchy instance is one
explicit structural node, not a concretely expanded loop iteration.

## Executable vertical slice

The generated `parameter_forwarding.v` artifact contains exactly one
`ForwardingLeaf` definition and one `ParameterForwarding` definition. The child
defaults `WIDTH` to 1. The parent computes:

```verilog
localparam integer TOTAL_WIDTH = LANES * DATA_WIDTH;
```

and forwards it through one named instance:

```verilog
ForwardingLeaf #(
  .WIDTH(TOTAL_WIDTH)
) forwarded_inst (
  .din(din),
  .dout(dout)
);
```

The differing child default is deliberate: the default parent width is 32, so
an omitted or incorrectly folded parameter binding cannot accidentally satisfy
the hierarchy contract.

The same emitted hierarchy is externally elaborated in five configurations:

| Configuration | `LANES` | `DATA_WIDTH` | Forwarded `WIDTH` |
|---|---:|---:|---:|
| Default | 4 | 8 | 32 |
| Minimum | 1 | 1 | 1 |
| Awkward mixed | 3 | 5 | 15 |
| `LANES` only | 3 | 8 | 24 |
| `DATA_WIDTH` only | 4 | 5 | 20 |

CI generates the complete contract artifact directory twice and requires byte
identity with the reviewed goldens. The exact generated hierarchy then passes:

- strict IEEE 1364-2001 Verilator lint for all five configurations;
- one Icarus simulation containing all five simultaneous parent instances;
- Yosys `read_verilog -noautowire`, hierarchy and consistency checks;
- a pre-synthesis JSON check that dynamically resolves the elaborated child
  module type and proves exact named, bit-for-bit parent/child connections;
- fresh full synthesis and exact top-port width checks for every configuration.

The Yosys checker intentionally does not match implementation-specific
`$paramod` names. It resolves the child type recorded on `forwarded_inst`, which
also handles the minimum configuration where a tool may reuse the unspecialized
child definition.

## Validation parity

The ParamRTL hierarchy validator provides real symbolic evidence for the
inherited hierarchy guard, so `PhaseCheckHierarchy` advances from `planned` to
`partial`. It is not marked `implemented`: the future Morph frontend must still
run the shared inherited SpinalHDL phase plan on the concrete witness and map
that result to the same canonical hierarchy.

## Deliberately deferred

- `GenerateFor`, `GenerateIf`, `GenerateCase` and `GenIndex`.
- The public `HdlInt` and `MorphVerilog` frontend.
- Connection to the shared inherited SpinalHDL phase plan.
- Runtime expressions beyond direct references and their driver semantics.
