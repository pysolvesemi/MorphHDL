# MorphHDL parameterized-Verilog v1 scope

This matrix defines the bounded v1 contract. "v1" means required before the
first parameterized-Verilog release, not implemented in Increment 1.

| Area | Construct | v1 status | Rule |
|---|---|---|---|
| Parameters | Integer public parameters | v1 | Literal defaults and explicit constraints |
| Parameters | Boolean parameters | v1 | Increment 9 preserves typed public Boolean intent and legalizes it to integer `1`/`0` in Verilog |
| Parameters | Derived local parameters | v1 | Increment 8 captures identity-bearing integer locals; Boolean locals remain planned |
| Parameters | Integer comparisons | v1 | Increment 10 implements `<`, `<=`, `>`, `>=`, `hdlEq` and `hdlNe` as typed Boolean expressions |
| Parameters | Conditional integer values | v1 | Increment 11 implements `HdlBool.select(whenTrue, whenFalse)` with exact default selection and a conservative whole-domain branch hull |
| Parameters | Enum/type/string parameters | Post-v1 | Static in v1 |
| Shape | Parameter-dependent packed width | v1 | Width must be provably positive |
| Shape | Parameter-dependent memory depth | v1 | Supported memory modes are bounded |
| Shape | Parameter-dependent port presence/direction | Rejected | Use a static interface profile |
| Structure | Child parameter forwarding | v1 | Increments 4 and 12 implement distinct named integer and Boolean mappings; binding expressions are validated in the parent instance context |
| Structure | Homogeneous generate-for | v1 | Increments 5 and 6 implement zero-based unit stride, one frontend-captured body, scoped `GenIndex` and a canonical packed-slice partition |
| Structure | Generate-if/case | v1 | Increment 9 implements one mandatory two-branch, non-nested generate-if; Increment 12 permits its Boolean parameter to be bound by a parent; case remains planned |
| Structure | Parameter-dependent Scala class selection | Static only | Not recoverable as HDL structure |
| RTL | Combinational operations | v1 | Explicit width and signedness rules |
| RTL | Registers and sync/async reset | v1 | Target-neutral process semantics |
| RTL | Supported memories | v1 | Width/depth, latency and mask contract documented |
| RTL | Tri-state/analog primitives | Post-v1 | Rejected by initial strict profile |
| Aggregates | Bundle/Vec internal intent | v1 | Preserved in ParamRTL, flattened for Verilog |
| Ports | Scalar and packed-vector ports | v1 | Stable flat ABI |
| Ports | Unpacked array, struct or interface ports | Post-v1 | Potential rich SystemVerilog ABI |
| Libraries | Bits/UInt/SInt and core operators | v1 | Symbolic width-aware subset |
| Libraries | Stream, Flow and Counter | v1 | Adapt only symbolic construction sites |
| Libraries | Synchronous FIFO | v1 | Required pilot library block |
| Libraries | AXI4/AXI4-Lite/AXI-Stream subset | v1 | Only blocks required by DisplayController |
| Libraries | Complete inherited library | Out of scope | Expand from demand and equivalence tests |
| CDC | Async FIFO and broad CDC library | Post-v1 | Requires a separate audited tranche |
| Verification | Assertions in emitted RTL | Post-v1 | Never silently discarded |
| Escape | Raw/verbatim HDL | Rejected | Not allowed in strict mode |
| Backends | Direct deterministic Verilog emitter | v1 | Production backend for first release |
| Backends | CIRCT adapter | Experimental | Architecture spike; not a v1 dependency |
| Backends | SystemVerilog-flat | Post-v1 | Must consume the same ParamRTL |

## v1 completion criterion

One emitted Verilog-2001 hierarchy must be externally instantiated with
multiple legal parameter values without rerunning MorphHDL. The DisplayController
pilot and its selected library path must pass parse, synthesis, structural and
semantic comparison gates with one module definition per logical component.
