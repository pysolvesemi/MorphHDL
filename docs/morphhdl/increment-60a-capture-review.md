# Increment 60a capture review

The original two printer oracles are unchanged. Their exact hashes are in
`morphhdl/contracts/increment-60a-sint-baseline.json`.

Review found that `cutLongExpressions=false` does **not** expose nested signed
casts in this fixture. `ComponentEmitterVerilog.fillExpressionToWrap` also
places nested SInt subexpressions in temporary wires. Consequently the uncut
capture is byte-identical to the ordinary fixed capture. The historical file
name `sint_cast_heavy_nested.v` does not establish the existence of nested casts.
All three captured artifacts contain repeated sibling casts, not a literal
nested `$signed($signed(...))` expression. No captured artifact is replaced to
force the earlier claim to be true.

The original broad regex incorrectly treated two sibling casts before a
semicolon as nesting. The test-only balanced-parenthesis detector now has
positive nested-call tests and negative sibling, comment and string tests.
It checks the actual absence of nesting in all three frozen captures.

`nested_signed_cast_reproducer.v` is explicitly **hand-authored test RTL**, not
SpinalHDL/MorphHDL captured output and not a replacement compatibility oracle.
It isolates the requested nested-cast semantic case for later cast cleanup.
Icarus checks every eight-bit input, including sign extension to eleven bits;
Yosys proves that the nested expression agrees with the independent single-cast
reference for all inputs. The implementation remains entirely test-side.

The primary semantic reference remains independently generated ordinary
SpinalVerilog from the same ordinary component source as MorphVerilog. The
baseline mutation, hash, replay, synthesis and equivalence checks are retained.
The child roadmap's phrase "focused reproducer" is satisfied by this explicitly
separated semantic fixture; it is not evidence that this particular current
native printer naturally generates nested signed calls.
