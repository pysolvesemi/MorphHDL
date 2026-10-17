# Explicit source comments (LWR-18)

An exact leading `@rtl` token opts a Scala comment into generated hardware
documentation. Enable `RtlDocumentationOptions(captureScalaComments = true)` and
compile with the IDSL plugin and `-Yrangepos`:

```scala
// Implementation detail: this stays in Scala.
// @rtl Sticky timeout indication; reset clears it.
val fault = Reg(Bool()) init(False)

/* @rtl Pair adjacent lanes.
 * Preserve the final unpaired lane.
 */
val paired = makePairs()
```

The marker is removed. `// @rtl` emits a Verilog line comment; `/* @rtl` and
`/** @rtl` emit Verilog block comments. The first text remains on the opening
line if authored there; an opening newline remains a newline. Conventional leading
stars and indentation are normalized, and internal blank lines are preserved:

```verilog
/* Pair adjacent lanes.
 * Preserve the final unpaired lane.
 */
```

Scala permits nested block comments but Verilog does not. Embedded `/*` and `*/`
in comment prose become `/ *` and `* /` to prevent terminating or nesting the
emitted comment. This escaping changes documentation only.

`RTL:` remains a compatibility alias with its previous line-comment rendering.
`/** @rtl ... */` (or legacy `/** RTL: ... */`) additionally requires `captureScaladoc = true`. Ordinary
Scaladoc is excluded even with that flag enabled. Empty markers emit nothing;
`@RTL`, `@rtlExtra`, `@rtl:`, `rtl:` and markers inside prose, strings or character literals do not opt in.
Unmarked comments adjacent to marked comments remain unpublished and preserve
lexical attachment boundaries. Detached comments and ambiguous same-line
declarations retain the existing ownership rules; use `.doc` for ambiguity.

`.doc(...)`, `@doc(...)` and `rtlDoc(...)` retain their explicit behavior regardless
of source capture flags. No capture-all option remains. Recompile authored source
to publish newly marked comments. Legacy unmarked automatic annotations, missing-
range annotations and automatic regions from precompiled classes are ignored;
region bodies still execute exactly once. This prevents old compiler implementation
prose from leaking through runtime elaboration helpers.

LWR-17 ownership remains active for marked parent aliases: immediate-child output
aliases document the instance use-site, not the shared child's port declaration.
Child-authored port comments remain child-owned. Metadata does not retain hardware;
removed declarations lose their notes, and existing authenticated relocation carries
notes with surviving regions and declarations. There is no generated-text cleanup
or product-name rule.

The focused suite covers source lexical boundaries, mixed ordinary/marked comments,
CRLF, block stars, quoted marker text, source deletion and recompilation, range
position diagnostics, explicit APIs, direct/helper aliases, module sharing, pruned
owners, library-generated synchronizers, deterministic emission and unchanged
non-comment RTL tokens. See `explicit-rtl-comments-receipt.json` for exact checks.

The `@rtl` extension has additional style, blank-line, opening-line, nested-delimiter
and legacy-compatibility regressions. Its fresh evidence is recorded in
`at-rtl-comments-receipt.json`; the earlier LWR-18 receipt remains historical.
