# Increment 48 — Natural symbolic conditionals for explicit `HdlInt`/`HdlBool`

Increment 48 adds a MorphHDL-owned Scala compiler plugin that rewrites natural Scala `if` syntax only in explicitly MorphHDL-aware source units. Overload resolution proves each rewritten condition as either ordinary Scala `Boolean` or explicit `morphhdl.frontend.HdlBool`.

Ordinary `Boolean` executes as ordinary Scala control flow. Explicit `HdlBool` retains both by-name hardware alternatives through the existing external structural generate-if capture while the concrete witness selects the ordinary elaboration result. Chained `else if` remains ordered nested structure. No implicit `HdlBool`-to-`Boolean` conversion is introduced. Non-local `return` and `throw` fail closed with a stable diagnostic. Native SpinalHDL compiler, core, and library source files remain unchanged.

Natural symbolic predicates may override generated block labels without giving
up direct Scala `if` syntax. Use `.named("g_true")` on a non-final condition in
an `else if` chain. Use `.named("g_true", "g_false")` for a simple `if / else`
or on the final condition of a chain, where the second name labels the terminal
`else`. A nested `if` inside a source `else` remains nested beneath the named
false block rather than being mistaken for a direct `else if` continuation.
All labels must be portable Verilog identifiers and remain subject to duplicate
name diagnostics.
