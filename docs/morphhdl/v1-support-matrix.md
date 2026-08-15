# MorphHDL parameterized-Verilog v1 scope

This matrix defines the bounded v1 contract. "v1" means required before the
first parameterized-Verilog release, not implemented in Increment 1.

| Area | Construct | v1 status | Rule |
|---|---|---|---|
| Parameters | Integer public parameters | v1 | Literal defaults and explicit constraints; Increment 29 carries one direct positive `HdlInt.param` through an ordinary component width without a second ParamRTL design |
| Parameters | Boolean parameters | v1 | Increment 9 preserves typed public Boolean intent and legalizes it to integer `1`/`0` in Verilog |
| Parameters | Derived local parameters | v1 | Increments 8 and 13 capture identity-bearing integer and Boolean locals in one deterministic cross-kind dependency graph |
| Parameters | Integer comparisons | v1 | Increment 10 implements `<`, `<=`, `>`, `>=`, `hdlEq` and `hdlNe` as typed Boolean expressions |
| Parameters | Conditional integer values | v1 | Increment 11 implements `HdlBool.select(whenTrue, whenFalse)` with exact default selection and a conservative whole-domain branch hull |
| Parameters | Integer minimum/maximum | v1 | Increment 23 implements typed `HdlInt.min`/`max`, validates both complete operand domains and emits bounded deterministic Verilog-2001 ternaries |
| Parameters | Mathematical ceiling-log2 | v1 | Increment 24 implements `HdlInt.ceilLog2` with a positive-domain proof and exact `ceilLog2(1) = 0`; strict Verilog-2001 uses one module-local constant function per consuming module |
| Parameters | Portable address width | v1 | Increment 21 implements `HdlInt.addressWidth` as positive ceiling-log2 with a one-bit minimum; Increment 24 replaces its threshold chain with the shared module-local constant function while retaining strict Verilog-2001 |
| Parameters | Enum/type/string parameters | Post-v1 | Static in v1 |
| Shape | Parameter-dependent packed width | v1 | Width must be provably positive; Increment 29 supports a direct public `HdlInt` on top-level `UInt` input/output ports through ordinary `UInt(width bits)` syntax |
| Shape | Parameter-dependent memory depth | v1 | Increment 20 implements positive finitely bounded depth; Increment 21 correlates `AddressWidth(DEPTH)` so the public fixture's packed address is one, two or three bits at depths 1, 3 or 5 |
| Shape | Parameter-dependent port presence/direction | Rejected | Use a static interface profile |
| Structure | Child parameter forwarding | v1 | Increments 4 and 12 implement distinct named integer and Boolean mappings; binding expressions are validated in the parent instance context |
| Structure | Homogeneous generate-for | v1 | Increments 5 and 6 implement zero-based unit stride, one frontend-captured body, scoped `GenIndex` and a canonical packed-slice partition |
| Structure | Generate-if/case | v1 | Increments 9 and 14 implement one non-nested conditional region per module: mandatory two-branch generate-if or integer-selected generate-case with unique choices and mandatory default |
| Structure | Parameter-dependent Scala class selection | Static only | Not recoverable as HDL structure |
| RTL | Combinational operations | v1 | Increment 15 implements one complete two-branch runtime mux process over direct port references; broader expressions and statement forms remain bounded follow-on work |
| RTL | Registers, counter and sync/async reset | v1 | Increments 16 and 17 implement active-high synchronous/asynchronous reset-to-zero; Increments 18 and 19 add the matching reset-priority active-high enable with disabled hold; Increment 25 adds a direct-public-limit synchronous modulo-up counter with depth-derived state width; broader state remains bounded follow-on work |
| RTL | Supported memories | v1 | Increment 20 implements one synchronous read-first whole-word single-port memory; Increment 21 derives its public address ABI from depth; Increment 22 adds active-high read enable and hold; Increment 26 adds one single-clock simple-dual-port `1R1W` form with independent capacity-proven addresses/enables and deterministic read-first collisions, while its public fixture uses two exact depth-derived addresses; reset, initialization, masks, extra ports and independent clocks remain deferred |
| RTL | Tri-state/analog primitives | Post-v1 | Rejected by initial strict profile |
| Aggregates | Bundle/Vec internal intent | v1 | Preserved in ParamRTL, flattened for Verilog |
| Ports | Scalar and packed-vector ports | v1 | Stable flat ABI |
| Ports | Unpacked array, struct or interface ports | Post-v1 | Potential rich SystemVerilog ABI |
| Libraries | Bits/UInt/SInt and core operators | v1 | Increment 29 begins the ordinary-component path with top-level `UInt` ports and a direct same-parameter wire; `Bits`, `SInt`, cloning, internal symbolic values and derived-width operators remain Increment 30/31 work |
| Libraries | Stream, Flow and Counter | v1 | Increment 25 adapts the bounded `spinal.lib.Counter`; Increment 28 adds one atomic default `Stream.m2sPipe` registered stage with bubble-free replacement and valid-only synchronous reset; broader Stream operators and Flow remain selected-demand work |
| Libraries | Synchronous FIFO | v1 | Increment 27 implements one atomic single-clock ready/valid FIFO with a real latency-two `spinal.lib.StreamFifo` default-shape witness; public `DEPTH` is total externally observable capacity including the registered pop stage, with parameter overrides governed by the symbolic contract |
| Libraries | Stream m2s pipeline | v1 | Increment 28 implements one atomic capacity-one registered ready/valid stage matching default `Stream.m2sPipe()`: one-edge latency, full replacement without a bubble, stall hold, payload capture whenever ready and active-high synchronous valid-only reset |
| Libraries | AXI4/AXI4-Lite/AXI-Stream subset | v1 | Only blocks required by DisplayController |
| Libraries | Complete inherited library | Out of scope | Expand from demand and equivalence tests |
| CDC | Async FIFO and broad CDC library | Post-v1 | Requires a separate audited tranche |
| Verification | Assertions in emitted RTL | Post-v1 | Never silently discarded |
| Escape | Raw/verbatim HDL | Rejected | Not allowed in strict mode |
| Backends | Direct deterministic Verilog emitter | v1 | Existing ParamRTL designs use the direct Verilog-2001 emitter; Increment 29 adds a bounded native Spinal Verilog path that reads retained symbolic-width metadata from the same elaborated component |
| Compatibility | Ordinary `SpinalVerilog` | v1 | Increment 29 leaves parameterized mode off by default, ignores symbolic-width metadata and emits the concrete witness width with no public parameter; an `HdlInt` configuration literal such as `Config(8)` follows the same concrete path |
| Backends | CIRCT adapter | Experimental | Architecture spike; not a v1 dependency |
| Backends | SystemVerilog-flat | Post-v1 | Must consume the same ParamRTL |

## v1 completion criterion

One emitted Verilog-2001 hierarchy must be externally instantiated with
multiple legal parameter values without rerunning MorphHDL. The DisplayController
pilot and its selected library path must pass parse, synthesis, structural and
semantic comparison gates with one module definition per logical component.
