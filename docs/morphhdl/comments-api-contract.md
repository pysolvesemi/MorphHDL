# RTL documentation contract

Documentation is generation-local metadata. Explicit documentation is enabled by
using its API. Automatic ordinary comments and Scaladoc have separate default-off
switches in `RtlDocumentationOptions`. Text is normalized to LF; unsupported
control characters and null text are rejected; empty text emits nothing.

A class annotation describes a module definition; documentation on a component
value describes that instance. Data documentation describes its declaration.
Repeated explicit calls retain their authored order. Transport must not duplicate
metadata. Aggregate documentation is attached to the first published field; element
notes retain their element/field coordinates. Compiler-created aliases do not
inherit documentation merely because they carry the same value. Removed hardware
has no declaration on which to publish its comment. Documentation never keeps it
alive. Differing definition documentation participates in module deduplication.

Automatic capture embeds source text at compilation. A trailing comment belongs
to the single declaration ending on that line; a component-header comment belongs
to its class. An immediately preceding comment block may cross annotations but
not a blank line or another statement. File/package/import comments are excluded.
Ambiguous same-line declarations require explicit documentation. Ordinary Scala
values have no RTL attachment. Precompiled code retains embedded metadata; runtime
generation does not read source files. Explicit annotations require literal text.

Use inline `//` for one short declaration note (up to 80 characters) and
preceding `//` lines for multiline or multiple notes. Keep ANSI port commas
before trailing comments. Class annotations belong to that exact class and are
not implicitly inherited; cloned Data receives documentation only when explicitly
annotated again. Text resembling tool directives remains comment text;
external tools may give it their own meaning. Existing attributes are separate.
Only Verilog publication is covered; no new VHDL behavior is promised.

Validation compiles Scala and checks generated comment ownership/text/order and
unchanged non-comment tokens. No simulation, synthesis, formal or external HDL
validation is part of this roadmap. CI and monitoring remain paused.

## Compiler setup and examples

Import `spinal.core._`. Use `@doc("Packet buffer")` on a Component class,
`@doc("Input word") val data = in Bits(32 bits)`, or
`val data = (in Bits(32 bits)).doc("Input word")`. Use
`definition.doc("Packet buffer")` inside a component for explicit definition text.
Annotations accept literal strings; fluent calls accept elaboration-time strings.

Automatic capture requires the IDSL compiler plugin and `-Yrangepos` at Scala
compiler construction time. Both repository builds enable this option. External
projects must add it to their Scala compiler options and recompile source whose
comments they want to capture. Enabling automatic capture for metadata compiled
without range positions produces a diagnostic instead of silently guessing.

```scala
val config = RtlDocumentationOptions(
  SpinalConfig(targetDirectory = "rtl"),
  captureScalaComments = true,
  captureScaladoc = false
)
MorphVerilog(config)(new PacketBuffer)
```

Scaladoc capture preserves prose and tags as documentation text, without rendering
HTML or resolving links. Comments inside declaration initializers are not attached
to the declaration; use `.doc` or a comment after the completed initializer.

## Hardware regions

`rtlDoc("Accept packet") { when(accept) { count := count + 1 } }` documents
emitted hardware created by that invocation. Its by-name body runs exactly once,
returns its original result and restores the documentation context on exceptions.
A failed region emits no documentation. Empty regions emit no standalone text.
Nested regions retain outer-to-inner ordering. Region metadata does not retain
otherwise removed hardware.

A region spanning several native processes is described once in each emitted
process. Ordinary Scala loops still elaborate separate hardware: region comments
cannot reconstruct a Verilog loop. Put `rtlDoc` outside an unrolled loop for a
shared note, or inside it for a separate note on each elaborated region. Typed
retained generate/procedural loops use their retained construct identity; comments
on their representative body must appear only once in the generated body.

Automatic region capture recognizes hardware DSL methods by compiler symbol.
Supported forms are `when`/`elsewhen`/`otherwise`, `switch`/`is`/`default`,
`HdlRange.foreach`, explicit `generateIf` with `otherwise`, and generate-case
builder branches. Put construct notes immediately before the construct, and body
notes immediately after `{` (or after a loop lambda’s `=>`). Use `rtlDoc` for a
section spanning several statements or an attachment whose scope is otherwise
ambiguous. Automatic capture does not reinterpret ordinary Scala control flow.

```scala
// Choose the payload
when(accept) { // Accepted packet
  payload := incoming
} otherwise { // Fallback packet
  payload := fallback
}

rtlDoc("Replicate receive lanes") {
  (0 until lanes).named("g_lane", "lane").foreach { lane =>
    rtlDoc("Receive lane body") {
      // Construct the lane hardware here.
    }
  }
}
```

Empty or eliminated branches do not acquire standalone comments. A parameterized
loop whose public default is zero still retains its body documentation when its
legal parameter domain includes nonzero counts and a loop remains in Verilog.
Constant typed constructs follow the selected aggregate/loop publication mode.
Nested retained constructs keep their own headers and body notes.

Free-standing assignment and instance comments move with their exact native
owners when structural lowering relocates code into generated branches. Internal
identity envelopes carry that ownership across the handoff and are removed from
published output. They are not a public API. Declaration/region metadata also
participates in module deduplication so incompatible documentation is not silently
coalesced. Existing fixed-definition-name conflicts remain errors; allow normal
name allocation for independently documented definitions.

Ordering follows attachment execution: fluent calls in an initializer, explicit
annotation metadata, then automatic preceding and trailing notes. Equal text from
independent calls is retained. Compiler-generated transport projections of one
aggregate note are emitted once across its published fields and Vec layouts.

Whitespace indentation is cosmetic for automatic attachment. Adjacency, blank
lines, annotations and intervening Scala tokens define the boundary; a comment
never crosses a brace, import, package clause or another statement. Separate file
headers/licensing blocks from a declaration with a blank line. A block placed
directly against a declaration is treated as that declaration's documentation.
For unsupported or ambiguous placements, use an explicit API. Compiler diagnostics
identify ambiguous same-line declarations and unsupported explicit annotations.
