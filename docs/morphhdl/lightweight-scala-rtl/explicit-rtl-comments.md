# Explicit source comments (LWR-18)

Only an exact leading `RTL:` marker opts a Scala comment into generated hardware
documentation. Enable `RtlDocumentationOptions(captureScalaComments = true)` and
compile with the IDSL plugin and `-Yrangepos`:

```scala
// Implementation detail: this stays in Scala.
// RTL: Sticky timeout indication; reset clears it.
val fault = Reg(Bool()) init(False)

/* RTL:
 * Pair adjacent lanes.
 * Preserve the final unpaired lane.
 */
val paired = makePairs()
```

The marker and conventional block-comment stars do not appear in Verilog.
`/** RTL: ... */` additionally requires `captureScaladoc = true`. Ordinary
Scaladoc is excluded even with that flag enabled. Empty markers emit nothing;
`rtl:` and markers inside prose, strings or character literals do not opt in.
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
