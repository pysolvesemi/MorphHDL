# Direct resized child-output connections (LWR-44)

A parent can connect a public immediate-child Bits, UInt or SInt output through
an explicit resize without creating a preserved Scala alias:

```scala
sink.dataIn := source.dataOut.resize(64)
```

The source may have a child-owned width formal bound to a parent expression.
The destination can have concrete or independently authenticated symbolic
geometry. Unsigned values zero-extend; signed values sign-extend. Narrowing
retains the low bits. These rules apply at every legal parameter override,
including an override where a resize changes between extension, equality and
truncation. Existing assignment compatibility checks still apply.

Before normalization, the compiler creates one native parent-owned source
carrier for each exact immediate-child output used as a resize operand, a
signed-to-unsigned transport, or a derived-width expression read. Its
width comes from the child's retained declaration and formal-to-actual
substitution. It preserves the source type and shares this carrier across
uses. Ordinary direct unsigned assignments alone do not require these carriers.
Slices and predicates of derived-width ports retain one whole parent edge;
their result widths never become evidence for the source port's width.
Fixed accesses must fit the complete substituted source-width domain. An
authenticated native `.msb` request keeps its symbolic high-bit semantics across
the carrier substitution; an ordinary fixed index is never promoted to `.msb`.

Direct derived-width ports also retain their complete child-to-parent formal
substitution. For example, a child output with width `WIDTH * 2 + 1` can feed
a sibling whose width actual is that same parent expression. A concrete child
output can feed an explicitly equal literal width actual. It cannot establish
a varying width merely because the defaults happen to match. Shared declaration
widths are compared with retained expression/domain/root evidence before
formatting; different parentheses do not imply different geometry.

Parent Boolean connections can retain native muxes and packed reductions.
Ordinary comparison constants such as `child.selected === 1` need not carry an
explicit literal size when their native packed kind matches the opposite
operand and their value fits its complete positive-width domain. Poisoned
contextual literals, foreign operands and constants that fit only the default
remain rejected. Native operators retain four-state comparison, conditional
and reduction behavior.

The existing native resize publisher owns the result and its symbolic
extension/truncation. An explicit Int-sized result keeps its fixed geometry
when normalization removes its Resize operator. This also provides the exact
width required by a subsequent signed-to-bits transport. A child resize feeding
a fixed UInt consumer retains its intermediate result when that result's width
varies across overrides.

Publication checks the original child, output declaration, parent carrier,
driver, lexical scopes, type and substituted width again. Replacing the driver
with an equal-width sibling port does not preserve that authority. Conflicting
width metadata, private child signals, foreign owners, unrelated parameter
domains and competing drivers remain rejected. Native scalar-expression
validation admits only the supported resize operators with positive source
geometry and authenticated target geometry.

This addition covers module-scope immediate-child outputs and their parent
resize expressions. It does not authorize grandchild/private-signal reads,
inout resizing, arbitrary custom Resize subclasses, or moving branch-owned
signals to a wider lexical scope. Existing structural publication remains the
authority for generated scopes. The existing native publishers consume the
authenticated graph evidence; no generated-text inference, application-specific
alias map or user naming override is added.

Read-only canonical-schema comparison reuses the existing exact-component
formal-binding snapshot. It expires before publication prepares or mutates
the graph. The snapshot does not survive between compiler phases or reuse a
proof after an ownership change.

Focused tests cover all three packed types, fixed and symbolic target sizes,
equal-witness and equal-symbolic resizes, fanout, four-state values, domain
overrides, deterministic output, invalid ownership/widths/writers, and live
lineage mutations. Adjacent hierarchy, normalized-resize, ordinary-loop and
native resize regressions remain in the selected suite. Exact fresh results and
the isolated application replay are recorded in the adjacent JSON receipt.
Historical failures remain separate from acceptance results. No remote CI or
full qualification is claimed.

The final selected suites run 114 tests on each of Scala 2.12.18 and 2.13.12.
113 pass per version, including all 46 new tests and the existing native
high-bit/publication mutation tests. The selected suites also retain the unchanged
`LightweightPortCompositionTests` guarded affine Bits-selection case
(`affine=true`), which fails with
`SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SLICE-DOMAIN-UNSUPPORTED`.
An isolated control using the original compiler sources reproduces that failure.
It is retained as a separate limitation; these results do not qualify the
complete branch or claim that the selected suite is entirely green.

The frozen helper-free application at
`9c6c087e8f4f05c05052d327a80b1a85a88d2bc8` generates successfully twice with
`GenerateDisplayControllerTop <directory> 48 128 16 0` (address bits, data bits,
maximum burst beats, CSR apply timeout). All 36 generated Verilog files are
byte-identical. Both the direct-connection reproducer and its preserved-carrier
control generate successfully. No application alias helper is restored.

Four-state comparison simulations match all 31 public outputs against the
committed RTL baseline `24f8f701b8a64314ca6031833fe84c4613bd29e1` for YUV=0 and
YUV=1, using the matching public-port testbench. This checks the recorded top
profile, not every application configuration. The existing unassigned
`partialGeneration` register warning and initial generation retry remain in the
logs.

Standalone compiler fixtures pass strict Verilog-2001 compilation, Icarus
simulation, Verilator lint and Yosys synthesis/driver/no-latch checks. The whole
application passes Icarus SystemVerilog compilation and mixed-language
Verilator lint: generated `.v` files are parsed as Verilog-2001 and unchanged
third-party library bytes as SystemVerilog. A strict Icarus Verilog-2001 build
of the whole tree fails because the required external `axivdisplay.v` uses
localparams in its parameter-port list. Both failed attempts are retained
separately; no production library is patched. Verilator uses the recorded
`-Wno-fatal` policy and retains its warnings. Questa is unavailable locally.

The [JSON receipt](direct-child-resize.json) records implementation identity,
source/plugin hashes and exact evidence hashes. Commands, artifacts, build logs,
development attempts and final integrity/publication receipts are retained at
`/home/kartik/.local/state/morphhdl/lightweight-scala-rtl/lwr44-20261020/`.
The tested builds retain the original pre-commit build header
`723bc90d787a78c77a2d4bfff751f9e7db0bb8d6`; exact source hashes authenticate
the tested compiler changes. Advancing the application source dependency is
not a claim of rebuilt application compiler classes or regenerated delivery RTL.
