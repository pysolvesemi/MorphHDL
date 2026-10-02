# MorphHDL comment and documentation API roadmap

Status: C01–C08b implemented and validated on Scala 2.12.18 and 2.13.12.
C09/C10 source-manifest and closure receipts are being finalized. CI and hourly
monitoring remain paused.

## Objective

Preserve user-authored hardware documentation in generated Verilog through:

- Explicit `@doc("...")`, `.doc("...")` and `rtlDoc("...") { ... }` APIs.
- Opt-in capture of ordinary Scala comments, including trailing comments such
  as `val data = in Bits(32 bits) // Input packet data`.
- Consistent placement on component definitions, instances, ports, signals,
  registers, aggregates and identifiable hardware statement regions.

Documentation must follow hardware identity through elaboration and publication.
Do not reconstruct ownership from emitted names or nearby Verilog lines. Do not
restore deprecated wire passes or implement their retired roadmap.

## Validation scope — direct user instruction

This work changes documentation only. Acceptance is based on validating comments
in generated Verilog, including placement, ownership, text, ordering and retention.
Do not run synthesis, simulation, formal proofs or external HDL-tool validation
for these increments: Yosys, Verilator, Icarus Verilog and similar tools are not
required. This task-specific instruction overrides broader qualification wording
for the comments roadmap.

Compile the affected Scala/plugin code and elaborate focused fixtures as needed
to generate the output under inspection. Compare the generated non-comment token
stream with its uncommented counterpart to catch accidental code changes; this
is an output check, not hardware simulation. Use comment-aware tokenization so
strings, attributes and tool directives are not silently discarded. Do not expand
these checks into unrelated behavioral regression or full hardware CI runs.

## Increment C01 — Define the contract and audit existing support

- [x] Audit `CommentTag`, component definition/instance comment emission,
  canonical IR declaration comments, source-location capture and compiler-plugin
  phases. Record which paths already preserve comments and which discard them.
- [x] Define one documentation metadata contract shared by explicit APIs and
  automatic capture: text, origin, source location, attachment role and stable
  ordering. Preserve authored text apart from documented newline normalization.
- [x] Distinguish module-definition documentation from instance documentation.
  A component-class annotation documents the module; a comment on a component
  instance declaration documents that instantiation.
- [x] Define explicit APIs as opt-in by their use. Keep automatic Scala-comment
  capture disabled by default and generation-local, without global state leaks.
- [x] Define coexistence with existing comment tags and synthesis/tool attributes.
  Documentation APIs must not silently reinterpret text as an attribute.
- [x] Define behavior when explicit and automatic documentation target the same
  object: retain distinct text in deterministic order; suppress only duplicates
  introduced by compiler transport, not unrelated equal-text comments.
- [x] Define diagnostics for unsupported annotation targets, ambiguous ownership
  and unavailable source text. Never attach documentation to an arbitrary neighbor.
- [x] Record supported generation entry points and Verilog dialects. Preserve
  existing behavior for other backends; do not claim untested backend support.

Acceptance: reviewed placement examples and an implementation-boundary document,
including Scala 2.12.18 and 2.13.12 plugin integration points.

## Increment C02 — Carry documentation through IR and scalar publication

- [x] Introduce or extend typed metadata rather than adding a parallel comment
  system. Preserve ordered comments and attachment roles through supported IR paths.
- [x] Emit module-definition comments before `module` and instance comments before
  the corresponding instantiation.
- [x] Emit scalar port, signal and register comments at their declarations.
- [x] Prefer trailing `//` for a single short declaration comment and preceding
  `//` lines for multiline text. Ensure ANSI port commas remain valid.
- [x] Handle Unicode, quotes, backslashes, tabs, CRLF, blank lines, `//` and `*/`
  safely. Comment content must not escape its comment or corrupt Verilog syntax.
- [x] Keep documentation distinct from tool directives; document that downstream
  tools can interpret certain comment spellings. Do not promise arbitrary comment
  text is semantically inert to every external tool.
- [x] Preserve source-only metadata without forcing hardware retention. Remove
  declaration comments when their hardware is removed.
- [x] Validate generated comment text and placement, including delimiters, line
  boundaries and ANSI port punctuation. Verify that non-comment Verilog tokens
  remain unchanged; no external HDL parser, simulation or synthesis is required.

Acceptance: metadata attached directly in fixtures survives all supported scalar
publication paths with correct ordering and placement.

## Increment C03 — Add the fluent `.doc(...)` API

- [x] Add a fluent API that preserves the original hardware value and its type:

  ```scala
  val data = (in Bits(32 bits)).doc("Input packet data")
  val count = (Reg(UInt(8 bits)) init(0)).doc("Buffered packet count")
  val fifo = (new PacketBuffer).doc("Receive-side buffer")
  ```

- [x] Define supported hosts: scalar Data, Vec, Bundle, component instances and
  an explicit module-definition documentation host.
- [x] Validate null inputs and define empty-string and repeated-call behavior.
- [x] Support documentation built from elaboration-time Scala strings without
  turning witness values into misleading descriptions of parameterized hardware.
- [x] Verify the API does not alter names, directions, widths, assignments,
  register initialization, component ownership or elaboration order.
- [x] Add executable examples for ports, internal wires, registers and instances.

Acceptance: the fluent API works without automatic source-comment capture.

## Increment C04 — Add the `@doc(...)` annotation

- [x] Define the annotation namespace and supported declaration targets.
- [x] Extend the compiler plugin to translate annotations into the same metadata
  used by `.doc(...)`; do not rely on runtime reflection finding local annotations.
- [x] Support component classes and hardware-valued declarations, including port,
  signal, register and component-instance `val` declarations.
- [x] Preserve initializer evaluation exactly once, including existing naming,
  post-initialization callbacks and `@dontName` behavior.
- [x] Define annotation argument requirements and reject unsupported arguments
  with a source-located diagnostic.
- [x] Define inheritance and overridden-member behavior; distinguish class
  documentation from instance-specific annotations.
- [x] Diagnose annotations on unsupported targets rather than silently dropping them.
- [x] Test clean and incremental compilation on both supported Scala versions.

Example:

```scala
@doc("Buffers incoming packets")
class PacketBuffer extends Component {
  @doc("Input packet data")
  val data = in Bits(32 bits)
}
```

Acceptance: annotation and fluent forms yield equivalent attachment semantics.

## Increment C05 — Preserve documentation through aggregates and hierarchy

- [x] Carry Vec/Bundle-level and field-level documentation through packed,
  unpacked, multidimensional and named-field publication.
- [x] Emit one aggregate comment per published aggregate group; retain distinct
  field comments on the appropriate field declarations.
- [x] Define constant-index element comments when elements collapse into one
  declaration: preserve element identity in an adjacent note rather than copying
  the comment onto every element or attributing it to the whole Vec.
- [x] Preserve packed port documentation when internal storage is unpacked.
- [x] Define clone and hierarchy propagation rules, avoiding incidental duplicate
  comments on compiler temporaries, transport wires or generated helper functions.
- [x] Keep instance-specific comments out of deduplicated module definitions.
  Define how differing definition documentation is handled during module deduplication.
- [x] Verify width expressions and array dimensions remain unchanged by documentation.
- [x] Test nested records, mixed-direction interfaces, parameter overrides,
  named-field mode, split output files and repeated instances.

Acceptance: documentation remains attributable after all currently supported Vec
layout transformations and component publication modes.

## Increment C06 — Capture trailing Scala comments

- [x] Add an opt-in automatic-capture configuration using the contract from C01.
- [x] Capture comments during Scala compilation while source text and declaration
  positions are available; embed the resulting metadata so generation does not
  require rereading source files at runtime.
- [x] Use compiler token information or a lexer that correctly distinguishes
  comments from strings, multiline strings, character literals and nested block
  comments. Do not extract comments with a line-only regular expression.
- [x] Attach a trailing comment to the single declaration completed on that line:

  ```scala
  val data = in Bits(32 bits)    // Input packet data
  val count = Reg(UInt(8 bits)) // Buffered packet count
  val fifo = new PacketBuffer  // Receive-side buffer
  ```

- [x] Support multiline declarations whose comment follows the completed
  initializer. Define how comments within the initializer are treated.
- [x] Support component-header comments such as:

  ```scala
  class PacketBuffer extends Component { // Buffers incoming packets
  ```

- [x] Diagnose ambiguous same-line declarations or require explicit documentation
  for them; never guess which declaration owns the text.
- [x] Exclude comments belonging to non-hardware declarations from RTL output.
- [x] Ensure editing only a comment invalidates the relevant compiled metadata
  and changes generated documentation under incremental compilation.
- [x] Test source files with CRLF, Unicode, semicolons, multiline expressions,
  misleading comment-like strings and precompiled component libraries.

Acceptance: trailing comments work for component definitions, instances, ports,
signals and registers, and are absent when automatic capture is disabled.

## Increment C07 — Capture preceding comments and Scaladoc

- [x] Attach immediately preceding standalone comment blocks using documented
  blank-line, indentation, scope and annotation boundaries.
- [x] Define optional Scaladoc capture separately from ordinary comments, including
  handling of prose, formatting and tags. Do not emit unrelated API documentation.
- [x] Exclude file headers, package/import comments, detached section comments and
  unrelated licensing text from declaration attachment.
- [x] Apply the coexistence and ordering rules when preceding, trailing and explicit
  documentation are all present on one declaration.
- [x] Test nested classes, Bundles, local declarations, annotations between comments
  and declarations, and documentation on inherited definitions.

Acceptance: preceding-comment capture is deterministic and scope-aware, with
diagnostics or documented exclusions for ambiguous cases.

## Increment C08 — Add `rtlDoc(...)` for hardware regions

- [x] Add an exception-safe region API that records the actual hardware statements
  created by its body without re-evaluating the body or changing its return value:

  ```scala
  rtlDoc("Accept a packet when space is available") {
    when(accept) {
      count := count + 1
    }
  }
  ```

- [x] Support identifiable assignments, `when`/`switch` regions and supported
  procedural or structural typed loops.
- [x] Support documentation on emitted `if` conditions, `else if` and `else`
  branches, `case` statements, individual case labels/branches and the default
  branch. Cover MorphHDL/SpinalHDL `when`/`elsewhen`/`otherwise` and
  `switch`/`is`/`default` constructs using explicit region documentation and
  unambiguous automatic comment capture.
- [x] Place condition comments before the corresponding `if`/`else if`, switch
  comments before `case`, and branch comments beside or immediately inside the
  appropriate branch. Preserve ownership when the compiler lowers `switch` to
  another equivalent control structure; do not attach a case note to a neighbor.
- [x] Distinguish elaboration-only Scala `if`/`match` from emitted hardware
  control flow: document the selected hardware region when one exists, and
  never invent a Verilog condition or case statement for a Scala-only decision.
- [x] Add generated-output tests for nested conditions, multiple case labels,
  defaults, empty or eliminated branches and conditional blocks inside typed
  loops. Check comment placement and unchanged non-comment tokens only.
- [x] Carry region documentation through statement regrouping and lowering using
  statement identity. Define placement when one region produces several blocks.
- [x] Emit comments once per retained generate/procedural region rather than once
  per elaboration witness or intermediate carrier.
- [x] Define nested regions, empty bodies, exceptions and eliminated regions.
- [x] Define ordinary Scala-unrolled-loop behavior separately from retained typed
  loops; do not suggest comments can reconstruct a lost hardware loop.
- [x] Where ownership is unambiguous, extend automatic source-comment capture to
  supported hardware statement regions. Require `rtlDoc` for ambiguous sections.
- [x] Inspect comment placement around reset/enable blocks, blocking/nonblocking
  assignments, generated branches and nested regions. Verify unchanged
  non-comment tokens rather than running behavioral or four-state simulation.

Acceptance: comments on supported code regions follow emitted hardware without
changing execution order, scope, sensitivity or generated structure.

## Increment C08b — Documentation on parameterized generate constructs

- [x] Preserve comments on supported typed symbolic conditions that emit Verilog
  `generate if`, including true/false branches, nested conditions and their
  generated scope labels.
- [x] Preserve comments on supported typed symbolic ranges that emit `generate
  for`, including the loop header, generated body and nested loops.
- [x] Cover constant typed conditions/ranges where the selected publication mode
  retains a generate construct; do not invent a generate block for ordinary Scala
  elaboration-only `if` or `for` code.
- [x] Support explicit `rtlDoc` regions and unambiguous automatic comments on the
  supported typed construct and its branches/body.
- [x] Carry documentation through representative-body capture and parameterized
  lowering using retained construct identity, not witness values or text searches.
- [x] Emit each construct-level comment once at the corresponding generate
  construct. Keep branch/body comments within their intended generated scope,
  without duplication for witness iterations or compiler-generated helpers.
- [x] Define zero-iteration, eliminated-branch and nested-generate behavior: omit
  comments with removed regions and preserve notes for surviving generated regions.
- [x] Add generated-output fixtures with symbolic parameter references, compound
  conditions, parameterized bounds and nested generate constructs. Assert comment
  ownership and unchanged non-comment tokens; no HDL simulation or synthesis.

Acceptance: parameterized generate comments remain attached to the intended
construct or branch through lowering, without changing its expressions or scopes.

## Increment C09 — Integration, documentation and generated-output validation

- [ ] Document APIs, configuration defaults, supported targets, ownership rules,
  placement examples and limitations. Clearly distinguish implemented from deferred
  automatic capture cases.
- [ ] Add an end-to-end fixture combining annotations, fluent docs, trailing and
  preceding comments, regions, hierarchy, Vecs and parameterized loops.
- [ ] Verify deterministic output across repeated generation and clean/incremental
  builds on Scala 2.12.18 and 2.13.12.
- [ ] Run focused Scala/plugin and generated-comment tests covering parameterized
  publication and aggregate layouts; add cases to the relevant test inventories.
- [ ] Compare representative generated output with capture enabled and disabled:
  validate comment text, attachment, ordering, deduplication and layout, and
  require unchanged non-comment tokens.
- [ ] Check that comments do not introduce declarations for otherwise removed
  signals or break publication matchers and source contracts. Use generated-output
  assertions only; no synthesis, simulation, formal proof or external HDL tools.
- [ ] Update affected native-source reviews and exact source manifests without
  weakening their checks or historical evidence.
- [ ] Record candidate source identity, commands, tool versions, test results and
  known limitations in a durable local receipt.

Acceptance: the supported comment/API matrix passes generated-output validation.
These checks are sufficient for this documentation-only scope; do not describe
these results as synthesis, simulation or full hardware qualification.

## Increment C10 — Review and closure

- [ ] Review the supported comment/API matrix and retain generated examples and
  focused test results for the final source revision.
- [ ] Confirm every implemented checklist item has comment-output evidence and
  document any deferred or unsupported attachment cases.
- [ ] Confirm non-comment output remains unchanged for paired fixtures and that
  documentation defaults and explicit opt-in settings match the contract.
- [ ] Preserve the existing CI and hourly-monitoring pause. This roadmap does not
  authorize resuming monitoring, dispatching CI or merging.
- [ ] If remote checks are separately requested, scope them to Scala compilation
  and generated-comment validation. Do not run synthesis, simulation, formal
  proofs, external HDL tools or aggregate hardware qualification for this work.
- [ ] Record implementation completion and output-validation receipts separately
  from any later publication or merge status; do not imply a merge occurred.

## Recommended implementation order

Implement C01–C04 first to establish metadata, rendering and explicit APIs. C05
then establishes aggregate and hierarchy behavior. Build C06 and C07 on that
foundation for ordinary Scala comments, followed by C08 for hardware regions and C08b for parameterized generate constructs.
Use C09 for combined generated-output evidence and C10 for review and closure.
Generated-comment validation is sufficient; hardware qualification is not required. Arbitrary Scala code with no identifiable emitted
hardware remains outside the preservation contract.
