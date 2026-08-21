# Increment 48 — Natural symbolic conditionals for explicit `HdlInt`/`HdlBool`

Increment 48 adds a MorphHDL-owned Scala compiler plugin that rewrites natural Scala `if` syntax only in explicitly MorphHDL-aware source units. Overload resolution proves each rewritten condition as either ordinary Scala `Boolean` or explicit `morphhdl.frontend.HdlBool`.

Ordinary `Boolean` executes as ordinary Scala control flow. Explicit `HdlBool` retains both by-name hardware alternatives through the existing external structural generate-if capture while the concrete witness selects the ordinary elaboration result. Chained `else if` remains ordered nested structure. No implicit `HdlBool`-to-`Boolean` conversion is introduced. Non-local `return` and `throw` fail closed with a stable diagnostic. Native SpinalHDL compiler, core, and library source files remain unchanged.
