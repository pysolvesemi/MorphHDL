from pathlib import Path

path = Path("docs/morphhdl/parameterized-verilog-todo.md")
text = path.read_text(encoding="utf-8")

section = '''## Architecture supersession — typed elaboration values (approved 2026-08-29)

This section supersedes every earlier roadmap requirement that parameterized
Verilog must be achieved with zero changes to upstream-owned SpinalHDL APIs.
The zero-diff experiments remain useful regression evidence, but they are no
longer the production architecture or a constraint on future increments.

MorphHDL will use a small typed elaboration-value layer instead of attempting
to reconstruct symbolic meaning after Scala has erased it to ordinary `Int` or
`Boolean` values:

- Neutral low-level `ElabInt` and `ElabBool` carriers (final names may be
  refined) retain a concrete witness, bounded symbolic expression and source
  identity.
- Existing `Int` and `Boolean` overloads remain available and elaborate as
  ordinary parameter-free SpinalHDL.
- Parameter-sensitive overloads accept typed elaboration values. `Int` may be
  lifted to a literal carrier, but no implicit symbolic-to-`Int` or
  symbolic-to-`Boolean` conversion is permitted.
- Selected SpinalHDL `core` and `lib` APIs may receive small reviewed type,
  signature, helper-overload and mechanical compatibility changes. The native
  hardware algorithms remain authoritative and must not be reimplemented in
  MorphHDL.
- Typed arithmetic, comparisons, equality, log/address helpers, ranges,
  widths, depths, offsets and structural predicates remain symbolic until
  lowering. Unsupported operations fail at the typed boundary.
- Natural syntax such as `if (depth == 1)` is retained by a small compiler
  bridge that rewrites only statically proven `ElabBool` conditions. It lowers
  syntax; it does not reconstruct provenance from source positions or equal
  concrete witnesses.
- Concrete `widthOf(Data): Int` remains for compatibility. A typed helper such
  as `widthOfExpr(Data): ElabInt`, or an equivalent type-directed call-site
  bridge, retains symbolic width.
- Captured non-witness alternatives are validated in their narrowed parameter
  domains and are not inserted as simultaneously active statements into a
  concrete witness graph.
- The native-source manifest distinguishes approved typed carrier/helper API
  adaptations from algorithm changes. Unreviewed algorithm edits,
  component-specific reconstruction and emitted-name recognition remain
  prohibited; byte-for-byte zero diff is no longer required for approved typed
  API adaptations.
- The existing native-`Int` shadow/provenance machinery is compatibility
  scaffolding only. New component support must use typed elaboration values,
  and the shadow path will be retired after typed parity is proven.

This typed architecture controls Increment 53d and every later
parameterization increment. When an older description conflicts with this
section, this section wins.

'''
marker = "## Roadmap discipline\n"
if "## Architecture supersession — typed elaboration values" not in text:
    if marker not in text:
        raise SystemExit("roadmap discipline marker missing")
    text = text.replace(marker, section + marker, 1)

old = """- Upstream-owned SpinalHDL source must remain unchanged by the preservation
  increments. MorphHDL-specific files currently located under native `core`,
  `lib` or `idslplugin` source trees are also scheduled for extraction.
- If an increment cannot satisfy its contract without changing an
  upstream-owned SpinalHDL file, implementation must stop before that change.
  The exact minimal hook, alternatives and compatibility impact must be
  presented for explicit approval; no native-source exception is implicit.
"""
new = """- Controlled SpinalHDL changes are permitted only for neutral typed
  elaboration carriers, parameter-sensitive signatures/overloads, generic
  helpers and minimum mechanical compatibility. The existing hardware
  algorithm must remain authoritative.
- Concrete `Int`/`Boolean` calls must preserve ordinary SpinalHDL elaboration
  and parameter-free Verilog. Typed calls must retain exact symbolic identity,
  bounded domains and hierarchy bindings without implicit witness extraction.
"""
if old in text:
    text = text.replace(old, new, 1)

insertion = '''- [ ] **Increment 53d — Typed elaboration values and native StreamWidthAdapter migration**

  **Dependencies:** Increment 53c implemented and merged.

  Replace native-`Int`/`widthOf` shadow reconstruction with the approved typed
  architecture. Introduce neutral low-level `ElabInt` and `ElabBool` carriers,
  preserve concrete `Int`/`Boolean` overloads, and prohibit implicit
  symbolic-to-concrete conversion. Add typed arithmetic, comparison/equality,
  Boolean, `widthOfExpr`, bit-count, resize, subdivision and Counter helpers
  required by the existing native `StreamWidthAdapter` algorithm. The native
  algorithm may receive only type/signature/helper-mechanical edits; it must
  not be copied or reimplemented in MorphHDL. Simplify natural conditional
  lowering to statically proven typed conditions. Prove equal-width, downsize
  and upsize behavior, backpressure, deterministic dual-Scala Verilog-2001,
  lint, synthesis and sequential formal equivalence against independently
  generated concrete witnesses. Component-name/source-file recognition is not
  permitted in the typed production path.

- [ ] **Increment 53e — Typed StreamFifo and StreamFifoCC migration**

  **Dependencies:** Increment 53d implemented and merged.

  Migrate native StreamFifo and StreamFifoCC parameter-sensitive depth and
  geometry APIs to typed elaboration values while preserving their algorithms.
  Add generic typed overloads for Mem, Vec, ranges, log/address helpers,
  counters, mixed hardware/elaboration arithmetic and structural alternatives.
  Validate each captured branch in its narrowed domain rather than inside a
  concrete witness graph. Preserve ordinary concrete calls and prove literal,
  power-of-two, non-power-of-two and cross-clock configurations.

- [ ] **Increment 53f — Typed native-library parameter surface**

  **Dependencies:** Increment 53e implemented and merged.

  Audit parameter-sensitive `Int`/`Boolean` APIs in supported SpinalHDL core
  and library components and migrate reusable surfaces to typed elaboration
  values. Limit changes to signatures, overloads and generic helpers; never
  change the hardware algorithms. Add compile-time diagnostics for accidental
  witness extraction and representative Counter, memory, Stream/Flow, AXI and
  hierarchy proofs on both supported Scala versions.

- [ ] **Increment 53g — Native-shadow compatibility retirement**

  **Dependencies:** Increment 53f implemented and merged.

  Move production parameterization to typed elaboration values, retain the
  native-`Int` shadow path only behind an explicit legacy compatibility switch,
  and remove component/source-name recognizers from default compilation. Prove
  every migrated contract with the legacy path disabled, then deprecate the
  compatibility API with deterministic diagnostics.

'''
marker54 = "- [ ] **Increment 54 — MorphHDL module extraction and native-tree cleanup**\n"
if "**Increment 53d — Typed elaboration values and native StreamWidthAdapter migration**" not in text:
    if marker54 not in text:
        raise SystemExit("Increment 54 marker missing")
    text = text.replace(marker54, insertion + marker54, 1)

text = text.replace(
    "- Increment 54 requires the merged Increments 53a, 53b.1 and 53c.\n",
    "- Increment 53d starts after the merged Increment 53c; Increments 53d through 53g form the typed migration chain.\n- Increment 54 requires the merged Increment 53g.\n",
    1,
)
text = text.replace(
    "- [ ] **Increment 54 — MorphHDL module extraction and native-tree cleanup**",
    "- [ ] **Increment 54 — Typed elaboration module layering and cleanup**",
    1,
)
text = text.replace(
    "  **Dependencies:** Increments 53a, 53b.1 and 53c implemented and merged.\n",
    "  **Dependencies:** Increment 53g implemented and merged.\n",
    1,
)
text = text.replace(
    "- [ ] **Increment 55 — Upstream parity and complete zero-diff proof**",
    "- [ ] **Increment 55 — Upstream parity and approved typed-patch manifest**",
    1,
)
text = text.replace(
    "The roadmap is complete when normal, unmodified SpinalHDL component and library\nsource can retain typed public parameters through MorphHDL-owned integration,",
    "The roadmap is complete when ordinary concrete SpinalHDL calls remain compatible\nand parameter-free while typed elaboration values retain public parameters through the real algorithms,",
    1,
)

path.write_text(text, encoding="utf-8")
