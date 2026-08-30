#!/usr/bin/env python3
from pathlib import Path

roadmap = Path("docs/morphhdl/parameterized-verilog-todo.md")
text = roadmap.read_text(encoding="utf-8")

architecture = '''## Authoritative typed-elaboration architecture from Increment 53d onward

This section is the controlling architecture for Increment 53d and every later
parameterization increment. It supersedes earlier zero-native-diff requirements
where they conflict with this section. Completed preservation increments remain
valuable historical evidence, but future implementation must not recover
symbolic meaning after a parameter-sensitive value has already collapsed to a
plain Scala `Int` or `Boolean`.

MorphHDL shall retain elaboration-time parameters as neutral typed values,
`ElabInt` and `ElabBool`, through the SpinalHDL algorithms that consume them.
`HdlInt` and `HdlBool` may remain user-facing construction APIs, but they must
lower to the same typed elaboration values rather than shadow metadata attached
after value erasure.

Small reviewed changes to SpinalHDL `core` and `lib` are explicitly allowed
when limited to parameter-sensitive formal types or overloads, typed helper
functions, explicit type annotations needed for overload resolution, and
mechanical metadata propagation through an existing algorithm. Every changed
native file must be listed in an audited manifest. The original SpinalHDL
algorithm remains authoritative; no primitive may be reimplemented in MorphHDL.

Concrete compatibility is mandatory. Existing `Int`/`Boolean` overloads remain
parameter-free. There is no implicit `ElabInt => Int` or `ElabBool => Boolean`
conversion. Witness extraction is explicit and may collapse a derived typed
expression only when its complete bounded domain proves it constant.

Natural Scala syntax such as `if (depth == 1)` may be retained by a small
compiler syntax bridge that lowers only expressions already proven typed as
`ElabInt`/`ElabBool`. It must not reconstruct provenance from a plain native
`Int`, scan component names, instrument arbitrary Scala integer code, or replay
branches in a graph already typed for another witness.

The production path from Increment 53d onward forbids component/file/module/
port/signal-name recognition, equal-witness or rendered-text identity guesses,
parser-wide native-`Int` shadow propagation, post-erasure branch replay, and
separately authored replacements for native primitives.

A typed migration completes only after Scala 2.12.18 and 2.13.12 pass under SBT
and Mill, ordinary concrete generation remains compatible, all admitted
parameter overrides lint and synthesize, and applicable independent formal and
mutation controls pass.

'''
if "## Authoritative typed-elaboration architecture from Increment 53d onward" not in text:
    marker = "## Roadmap discipline\n"
    if marker not in text:
        raise SystemExit("roadmap discipline marker not found")
    text = text.replace(marker, architecture + marker, 1)

old = '''- Upstream-owned SpinalHDL source must remain unchanged by the preservation
  increments. MorphHDL-specific files currently located under native `core`,
  `lib` or `idslplugin` source trees are also scheduled for extraction.
- If an increment cannot satisfy its contract without changing an
  upstream-owned SpinalHDL file, implementation must stop before that change.
  The exact minimal hook, alternatives and compatibility impact must be
  presented for explicit approval; no native-source exception is implicit.
'''
new = '''- Increments 38 through 53c retain their historical source-preservation
  evidence. From Increment 53d onward, the authoritative typed-elaboration
  section permits small audited `core`/`lib` type, helper and mechanical
  propagation changes while prohibiting algorithm replacement.
- Any broader native semantic or algorithm change remains approval-gated. Stop
  and present its exact necessity, alternatives and compatibility impact before
  making such a change.
'''
if old in text:
    text = text.replace(old, new, 1)

old = '''- Every preservation increment must retain the applicable concrete parity,
  simulation, lint, synthesis, mutation, determinism, strict Verilog-2001 and
  dual-Scala gates already established by Increments 29 through 37.
'''
new = '''- Every typed migration must retain applicable concrete parity, simulation,
  lint, synthesis, mutation, determinism, strict Verilog-2001 and dual-Scala
  gates, and must validate both SBT and Mill build paths.
'''
if old in text:
    text = text.replace(old, new, 1)

old = '''- Increment 54 requires the merged Increments 53a, 53b.1 and 53c.
  Increments 54 through 58 then form a strict sequential closure chain.
'''
new = '''- Increment 53d is the approved architecture pivot and depends on the merged
  Increments 53a, 53b.1 and 53c. It supersedes the shadow-native-`Int`
  production strategy without invalidating historical regression evidence.
- Increment 54 depends on merged typed Increment 53d. Increments 54 through 58
  form a strict typed-migration and retirement chain.
'''
if old in text:
    text = text.replace(old, new, 1)

entry = '''- [ ] **Increment 53d — Typed elaboration pivot and native StreamWidthAdapter closure**

  **Dependencies:** Increments 53a, 53b.1 and 53c implemented and merged.

  Establish neutral `ElabInt` and `ElabBool` carriers below the SpinalHDL
  library layer. Preserve a concrete witness, bounded symbolic expression and
  exact parameter identity. Keep existing primitive overloads concrete and
  parameter-free, and extend the natural-control compiler bridge only for
  statically proven typed expressions.

  Migrate the existing native `StreamWidthAdapter` algorithm to typed payload
  width expressions through a generic `widthOfExpr` or equivalent helper.
  Preserve equal-width, downsize and upsize alternatives, arithmetic, counter,
  resize, slicing and backpressure without a MorphHDL-authored adapter. Allowed
  native changes are limited to formal types, helper calls, explicit annotations
  and mechanical typed propagation and must be audited.

  Prove concrete `Int`/`widthOf` generation remains parameter-free. Prove typed
  paths on Scala 2.12.18 and 2.13.12 under SBT and Mill with deterministic strict
  Verilog-2001, simulation/backpressure, lint, synthesis and independent formal
  equivalence plus mutation counterexample. The production path must not depend
  on native-`Int` shadow reconstruction or component/source-name recognition.

'''
if "**Increment 53d — Typed elaboration pivot and native StreamWidthAdapter closure**" not in text:
    marker = "- [ ] **Increment 54 — MorphHDL module extraction and native-tree cleanup**"
    if marker not in text:
        raise SystemExit("Increment 54 marker not found")
    text = text.replace(marker, entry + marker, 1)

start = text.find("- [ ] **Increment 54 —")
end = text.find("## Completion target", start)
if start < 0 or end < 0:
    raise SystemExit("future increment range not found")
future = '''- [ ] **Increment 54 — Typed StreamFifo depth and structural-domain validation**

  **Dependencies:** Increment 53d implemented and merged.

  Change only the parameter-sensitive StreamFifo depth surface and generic
  helpers required by its existing algorithm to use `ElabInt`/`ElabBool`.
  Preserve bypass, depth-one, power-of-two, non-power-of-two, asynchronous and
  synchronous alternatives. Validate each captured alternative under its own
  narrowed domain rather than the default-witness graph. Retain concrete `Int`
  compatibility and rerun the independent depth 1, 3, 5 and 8 formal proofs.

- [ ] **Increment 55 — Typed geometry helper and primitive migration matrix**

  **Dependencies:** Increment 54 implemented and merged.

  Generalize typed overloads for log/address helpers, power-of-two predicates,
  ranges, Counter, Mem, Vec, bit counts, resize, slices and hierarchy actuals.
  Migrate representative components only through parameter-sensitive types,
  helpers and mechanical propagation. Add an audited inventory of typed versus
  host-side primitive arguments.

- [ ] **Increment 56 — Shadow-native-Int production retirement**

  **Dependencies:** Increment 55 implemented and merged.

  Remove native-`Int` shadow provenance, source-position alias reconstruction,
  component-specific constructor state and post-erasure branch replay from the
  production path. Keep historical fixtures only as compatibility or negative
  regression evidence. Reduce the compiler plugin to typed syntax lowering.

- [ ] **Increment 57 — Native-library typed migration and compatibility proof**

  **Dependencies:** Increment 56 implemented and merged.

  Apply the typed surface to the reviewed native library set, including Counter,
  Stream/Flow pipelines, StreamFifoCC and AXI/register-map geometry where useful.
  `Int` calls stay concrete; `HdlInt`/`ElabInt` calls select typed overloads.
  Preserve original algorithms and prove simulation, lint, synthesis,
  determinism and formal equivalence for each migrated family.

- [ ] **Increment 58 — Stable typed API, release boundary and legacy cleanup**

  **Dependencies:** Increment 57 implemented and merged.

  Freeze the low-level `ElabInt`/`ElabBool` ABI, user-facing construction API and
  post-parameterization IR handoff. Audit all allowed native edits, retire
  superseded adapters and shadow pathways, publish migration guidance and run
  the complete dual-Scala SBT/Mill regression and formal inventory.

'''
text = text[:start] + future + text[end:]

old_completion = '''The roadmap is complete when normal, unmodified SpinalHDL component and library
source can retain typed public parameters through MorphHDL-owned integration,
including parameter-dependent native Scala expressions and structural
alternatives, producing one readable parameterized Verilog-2001 definition per
logical component without separately handwritten ParamRTL implementations,
component-name rewrites or unapproved native-source modifications. Application
RTL must be able to use native-looking SpinalHDL library construction without
requiring `MorphCounter`, `MorphStream`, `MorphFlow` or equivalent
MorphHDL-prefixed constructor aliases.
'''
new_completion = '''The roadmap is complete when parameter-sensitive values remain typed from the
application boundary through the original SpinalHDL algorithms into one
readable parameterized Verilog-2001 definition per logical component. Ordinary
`Int`/`Boolean` calls remain source-compatible and parameter-free. Typed calls
need no post-erasure provenance reconstruction, component-name rewrite or
separately authored primitive. All native edits are small, audited type/helper/
mechanical changes, and both Scala versions pass under SBT and Mill with the
applicable simulation, lint, synthesis and formal gates.
'''
if old_completion in text:
    text = text.replace(old_completion, new_completion, 1)

roadmap.write_text(text, encoding="utf-8")
