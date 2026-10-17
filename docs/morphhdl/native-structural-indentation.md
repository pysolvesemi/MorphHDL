# Native retained structural indentation (CA-054)

Retained structural publication uses two spaces per module, generate, block,
case and function level. Parameter and port continuations are indented one level
from their opener. Closing delimiters and block ends align with their openers.
This runs after structural, array, documentation and scoped-name publication, so
fragments moved from different native scopes receive the same final layout.

Only leading spaces/tabs on code lines change. HDL tokens, intra-line spacing,
line count, native `_zz_` wires, comments, strings, escaped identifiers,
attributes and preprocessor directives remain intact. Multiline comment/string/
attribute contents and directive continuations keep their original bytes. The
formatter does not remove blank lines, retarget ports, simplify logic or change
application cleanup. Modules without retained structural regions retain their
existing layout.

`NativeStructuralIndentationTests` checks exact snapshots, idempotence, protected
lexical content and conditional compilation. `WidthCompositionTests` exercises
recursive arrays and optional pipeline processes through deterministic generation,
Verilog-2001 compilation, simulation, lint and synthesis. Qualification receipts
must identify the candidate and distinguish local validation from remote CI.

The current byte-exact publication expectation is
`morphhdl/contracts/increment-59i-publication-golden-indentation.json`.
Its ten profiles retain the scalar-default contract's interfaces and bindings;
only leading indentation changes in the retained structural profiles. The
scalar-default, successor and original contracts remain immutable. The checker
continues to require two independent complete emissions, exact bytes and all
existing mutation controls; it does not normalize whitespace during checking.
