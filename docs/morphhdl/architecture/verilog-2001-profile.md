# Strict Verilog-2001 target profile

The first MorphHDL target is synthesizable IEEE 1364-2001 Verilog. Output must
be accepted without enabling a SystemVerilog parser.

## Required mappings

| ParamRTL semantic construct | Verilog-2001 representation |
|---|---|
| Integer public parameter | `parameter integer` with a literal default |
| Boolean public parameter | `parameter integer` with canonical default `1` or `0` |
| Derived constant | `localparam` |
| Named module instance | `Child #(.PARAM(expr)) instance (.port(signal))` |
| Packed unsigned signal | `wire` or `reg [WIDTH-1:0]` |
| Combinational process | `always @*` |
| Clocked process | Edge-sensitive `always` |
| Structural loop | Named `generate`/`for` with `genvar` |
| Structural condition (implemented) | Named `generate`/`if` with explicit `== 1` Boolean references |
| Integer comparison (implemented) | `<`, `<=`, `>`, `>=`, `==` or `!=` after operand capability proof |
| Conditional integer value (implemented) | Parenthesized Boolean condition with Verilog-2001 `condition ? when_true : when_false` |
| Structural case (reserved v1 mapping) | Named `generate`/`case` |
| Logical record/vector port | Deterministically flattened scalar/packed ports |
| `clog2` | Generated portable constant function or legalized expression |
| Enum intent | Packed vector plus named local parameters |
| Parameterized memory | `reg [WIDTH-1:0] mem [0:DEPTH-1]` |

The emitter determines `wire` versus `reg` from driver semantics. Those words
are backend spellings and are not encoded in ParamRTL.

Boolean intent likewise remains typed in ParamRTL. Integer `1`/`0`
declarations and `NAME == 1` predicates are backend legalization, not the
canonical Boolean type. Increment 10 legalizes explicit mathematical integer
comparison nodes to Verilog operators only after each operand subtree fits the
target `integer` domain. Increment 11 legalizes conditional integer values only
after the condition and both value branches pass capability checks; the
condition is always parenthesized and expression precedence preserves the exact
tree. Increment 9 supports one named two-branch generate-if; generate-case and
nested conditional structure remain outside the executable profile.

## Flat ABI

The v1 public ABI permits scalar and packed-vector ports. Bundles, logical
vectors and protocol records are flattened using stable field order and naming.
The layout is retained in ParamRTL for a possible future SystemVerilog target.

Widths may depend on parameters. Port presence, name, direction and clock/reset
role may not depend on parameters. Designs requiring different interfaces use
separate static profiles or top modules.

## Forbidden output

Strict mode rejects SystemVerilog-only or ambiguous constructs, including:

- `logic`, `always_comb`, `always_ff` and `always_latch`;
- interfaces, modports, packages, structs, unions and typedefs;
- unpacked array ports and type parameters;
- classes, SVA/property syntax and SystemVerilog testbench constructs;
- `$clog2`, `$bits` and other non-Verilog-2001 sizing helpers;
- raw/verbatim HDL that bypasses target verification;
- configuration-specialized module suffixes such as `__v_lanes4`.

## Validation gate

Every emitted fixture must pass:

1. ParamRTL validation and Verilog-2001 capability verification.
2. A project-owned forbidden-construct check.
3. Icarus parsing with `-g2001`.
4. Yosys `read_verilog` without `-sv` followed by hierarchy and consistency
   checks.
5. Default, minimum, awkward non-power-of-two and mixed parameter overrides.
6. Structural checks confirming widths, generate counts and memory depths.
7. Differential equivalence or cycle-accurate comparison with concrete
   SpinalHDL specializations.
8. Byte-for-byte determinism and one definition per logical module.

The parser and synthesis checks supplement the internal capability verifier;
they do not replace it.
