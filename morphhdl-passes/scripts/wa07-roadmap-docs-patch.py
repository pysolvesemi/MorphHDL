#!/usr/bin/env python3
from pathlib import Path
import re
import subprocess

root = Path(__file__).resolve().parents[2]
roadmap_path = root / "morphhdl-passes/morphhdl-ir-wire-assignment-passes-todo.md"
readme_path = root / "morphhdl-passes/README.md"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one exact replacement, found {count}")
    return text.replace(old, new)

roadmap = roadmap_path.read_text(encoding="utf-8")
roadmap = replace_once(
    roadmap,
    """This is the controlling checklist for exactly two optional,
behavior-preserving passes over the canonical MorphHDL-owned IR after
parameterization/capture and before Verilog-2001 emission:

1. remove eligible simple wire aliases represented by unnamed internal
   signals; and
2. remove eligible simple wire aliases represented by explicitly named
   internal signals.

No signal-renaming, formatting, generated-Verilog parsing or broader
optimization pass is authorized by this roadmap.
""",
    """This is the controlling checklist for exactly three optional,
behavior-preserving passes over the canonical MorphHDL-owned IR after
parameterization/capture and before Verilog-2001 emission:

1. remove eligible direct wire aliases represented by unnamed internal
   signals;
2. remove eligible direct wire aliases represented by explicitly named
   internal signals; and
3. inline the pure right-hand-side expression of an eligible unnamed
   continuous wire assignment into every continuous receiver, then remove the
   temporary declaration and its assignment.

Product code has one all-or-none `enabled` flag. `false` executes no pass;
`true` executes all three in the fixed order above. Internal proof fixtures may
select historical stages directly, but those selections are not product flags.
No signal-renaming, formatting, generated-Verilog parsing or broader
optimization pass is authorized by this roadmap.
""",
    "roadmap introduction",
)
roadmap = replace_once(
    roadmap,
    """        +--> unnamed simple-wire alias elimination
        |
        +--> named simple-wire alias elimination
        |
        v
structured Verilog-2001 lowering and emission
""",
    """        +--> unnamed direct-wire alias elimination
        |
        +--> named direct-wire alias elimination
        |
        +--> unnamed continuous wire-expression inlining
        |
        v
structured Verilog-2001 lowering and emission
""",
    "roadmap architecture",
)
roadmap = replace_once(
    roadmap,
    """The pass and writeback arrows in the diagram remain
the roadmap target: neither pass executes in production until WA-07.
""",
    """The pass and writeback arrows in the diagram remain
the roadmap target: WA-07 implements and proves the third standalone pass and
the one-flag pipeline; none executes in production until WA-08.
""",
    "roadmap handoff statement",
)
roadmap = replace_once(
    roadmap,
    """The first implementation boundary does not inline operators, literals, slices,
indexes, concatenations, casts, resizes, muxes, function calls or arbitrary
expressions. Expanding beyond direct wire-to-wire aliases requires a separate
reviewed roadmap update.
""",
    """WA-04 and WA-05 remain bounded to direct wire-to-wire aliases. They do not
inline operators, literals, slices, indexes, concatenations, casts, resizes,
muxes or other expression trees.

## Bounded unnamed continuous wire-expression contract

WA-07 adds a distinct pass for an unnamed internal combinational temporary
whose sole full-object driver is a continuous assignment from any pure
combinational `RtlExpr` currently represented by the canonical IR: literal,
unary or binary operator, mux, concatenation, bit or part select, resize, cast,
or a nesting of those forms. A direct signal reference remains WA-04 scope.

The expression pass must prove all of the following before changing the IR:

- the temporary is classified as unnamed by retained source/elaboration
  provenance, never by matching `_zz_*` or another emitted identifier;
- exactly one full-object continuous assignment drives the temporary;
- the right-hand side is complete canonical expression IR and does not
  reference the temporary itself;
- every reference used by the expression is resolved, legally visible from
  every receiver, and cannot introduce a combinational cycle;
- at least one receiver exists and every receiver is also a continuous
  assignment;
- neither the temporary assignment nor any receiver is procedural; therefore
  no assignment emitted in an `always` block is changed;
- the temporary has complete packed type and observability metadata and no
  preservation, comment, attribute, public, probe or hierarchy contract; and
- cloning the expression at each receiver preserves the removed assignment's
  packed width and signedness through an explicit type fence.

For example:

```verilog
wire [WIDTH-1:0] temporary;
assign temporary = (left ^ ~right);
assign sink_a = temporary;
assign sink_b = temporary;
```

may become:

```verilog
assign sink_a = (left ^ ~right);
assign sink_b = (left ^ ~right);
```

The exact temporary declaration and sole assignment are removed. The pass does
not simplify, reassociate, fold or otherwise change the cloned expression. If
any receiver is procedural, the temporary and every use remain unchanged.
""",
    "roadmap expression contract",
)
roadmap = replace_once(
    roadmap,
    """Because this removes an internal waveform/debug point, the named pass is
separately selectable and reports every removed name and available source
location deterministically.
""",
    """Because this removes an internal waveform/debug point, the named pass reports
every removed name and available source location deterministically. Product
execution is controlled only by the common all-or-none flag; there is no public
per-pass Boolean.
""",
    "roadmap common flag",
)
roadmap = roadmap.replace(
    "Apart from the exact alias substitution and deletion described above, the two\npasses must not change:",
    "Apart from the exact direct-alias substitution or expression-temporary\ninlining described above, the three passes must not change:",
)
roadmap = roadmap.replace(
    "- parameters, expressions, constraints, widths or signedness;\n- literals, operators, slices, indexes, concatenations, casts or resizes;",
    "- parameters, constraints, widths or signedness;\n- expression structure or semantics except for cloning the approved RHS at an\n  eligible continuous receiver and adding its assignment type fence;",
)
roadmap = roadmap.replace(
    "- dead-code elimination beyond the exact removed alias;",
    "- dead-code elimination beyond the exact approved alias or expression temporary;",
)
roadmap = roadmap.replace("- any third pass.", "- any fourth pass.")
roadmap = roadmap.replace(
    "The\nfinal WA-07 increment may add only the minimum optional handoff in",
    "The\nfinal WA-08 increment may add only the minimum optional handoff in",
)
roadmap = roadmap.replace(
    "configuration, result, diagnostic and elimination-report contracts for only\n  the two authorized passes.",
    "configuration, result, diagnostic and elimination-report contracts for the\n  authorized wire-assignment passes.",
)
roadmap = roadmap.replace(
    "paths for an eligible WA-07 branch after WA-06 and PV-58 are checked.",
    "paths for an eligible WA-08 branch after WA-07 and PV-58 are checked.",
)
roadmap = roadmap.replace(
    "Add validation shared by both passes.",
    "Add validation shared by both direct-alias passes.",
)
roadmap = replace_once(
    roadmap,
    """  Added one optional MorphHDL-IR pipeline entrypoint that can enable either pass independently or run unnamed then named. Both remain disabled by default.
  Validated alias chains and fanout without parsing emitted Verilog, including
""",
    """  Added one optional MorphHDL-IR pipeline entrypoint and proved the historical
  unnamed-only, named-only and unnamed-then-named stages. WA-07 replaces the
  product-facing independent switches with one all-or-none flag while retaining
  those selections only inside regression code. Validated alias chains and
  fanout without parsing emitted Verilog, including
""",
    "WA-06 transition",
)

new_tail = """- [ ] **WA-07 — Unnamed continuous wire-expression elimination and common pass flag**

  **Dependencies:** WA-06 implemented and merged.

  **Status:** `IN PROGRESS`.

  Replace the product-facing per-pass Booleans with one `enabled` flag. When
  disabled, execute no wire-assignment pass. When enabled, execute unnamed
  direct aliases, named direct aliases, then unnamed continuous expression
  temporaries in that fixed order. Retain direct stage selection only as a
  package-private regression facility.

  Add a component-generic canonical-IR pass for unnamed internal combinational
  temporaries driven by any pure canonical RHS expression. Clone the exact
  expression into every continuous receiver, preserve the removed assignment's
  width and signedness through an explicit type fence, then remove only the
  temporary declaration and sole assignment. Do not infer unnamed status from
  `_zz_*`. Reject candidate or receiver assignments represented by procedural
  drivers, so assignments emitted in `always` blocks remain unchanged.

  Add direct, nested, literal, fanout, cycle, scope, observability, procedural
  source, procedural receiver, deterministic, fixed-point, idempotence and
  fail-closed tests on Scala 2.12.18 and 2.13.12. Apply the expression-only pass
  and the common-flag all-pass pipeline to the shared parameterized StreamFifo.
  Emit both candidates through the existing structured backend and formally
  compare each directly with the one unchanged pre-pass reference over all 512
  `WIDTH=1..64` by `DEPTH=1..8` bindings. Retain strict Verilog-2001, lint,
  synthesis, representative simulation, mutation and repeated-emission gates.

- [ ] **WA-08 — Final MorphHDL IR-stage production handoff**

  **Dependencies:** WA-07 and PV-58 implemented and merged.

  **Status:** `BLOCKED` by WA-07.

  Expand PV-58's validated publication profile to carry the approved pure
  expression algebra and connect the one-flag pipeline to the MorphHDL
  single-source production path after parameterization/capture and before
  Verilog lowering. PV-58 currently publishes a read-only bounded snapshot; it
  does not execute or write back any pass. Keep pass implementation under
  `morphhdl-passes/` and add only minimum MorphHDL-owned integration,
  configuration and validated writeback glue. Existing generation remains
  unchanged unless the one common flag is enabled. Do not add a generated-
  Verilog parser, file postprocessor, signal-renaming pass, formatting pass or
  broader optimization pass.

## Completion target

This roadmap completes at WA-08 when MorphHDL can optionally run all three
wire-assignment transformations from one flag on its canonical post-
parameterization IR and write the validated result back into the structured
Verilog-2001 production path while preserving parameterized RTL behavior and
every surviving identifier. Signal renaming remains future work.
"""
roadmap, count = re.subn(
    r"- \[ \] \*\*WA-07 — Final MorphHDL IR-stage production handoff\*\*[\s\S]*?\Z",
    new_tail,
    roadmap,
    count=1,
)
if count != 1:
    raise SystemExit("roadmap tail: old WA-07 entry was not replaced")
roadmap_path.write_text(roadmap, encoding="utf-8")

readme = readme_path.read_text(encoding="utf-8")
readme = readme.replace(
    "This is the standalone MorphHDL-owned workspace for the two optional\nwire-assignment passes",
    "This is the standalone MorphHDL-owned workspace for the three optional\nwire-assignment passes",
)
readme = readme.replace(
    "Both pass selections are disabled by default.",
    "One public `enabled` flag controls the complete pipeline and is disabled by default.",
)
readme = replace_once(
    readme,
    """`WireAliasPassPipeline` is the single optional canonical MorphHDL-IR entrypoint
for these transforms. Both remain disabled by default. Configuration may enable either pass independently; when both are enabled, the
fixed order is unnamed then named. The result retains one ordered report per executed stage, so named
and unnamed decisions cannot be conflated.
""",
    """`WireAliasPassPipeline` is the single optional canonical MorphHDL-IR entrypoint
for these transforms. WA-06 proved the historical direct-alias stages and their
unnamed-then-named order. WA-07 exposes only one product-facing `enabled` flag:
`false` executes no pass; `true` executes unnamed direct aliases, named direct
aliases, then unnamed continuous expression temporaries. Package-private stage
selection exists only for regression evidence. The result retains one ordered
report per executed stage.
""",
    "README pipeline control",
)
marker = "## Common witness and formal-equivalence baseline\n"
wa07_section = """## WA-07 — unnamed continuous expressions and one common flag

`UnnamedWireExpressionEliminationPass` accepts only canonical
`NameOrigin.Unnamed` internal combinational temporaries with one full-object
continuous driver whose right-hand side is not a direct reference. It supports
all pure `RtlExpr` forms represented by canonical v1, including literals,
unary and binary operators, muxes, concatenations, selections, resizes and
casts. The pass never recognizes `_zz_*` text.

Every source reference must resolve and be legally visible from every receiver.
Every receiver must be a continuous driver. A procedural source assignment or
any procedural receiver causes a fail-closed rejection, so no assignment in a
Verilog `always` block is rewritten. At each accepted receiver the complete RHS
is cloned with fresh reference identities and wrapped in the removed alias's
packed width and signedness before the exact temporary declaration and its sole
assignment are deleted.

The public `WireAliasPassConfiguration(enabled = true)` executes all three
passes in the fixed order. `enabled = false` executes none. Tests cover literal,
nested and fanout expressions, exact identity, type fences, cycles, scopes,
metadata, procedural source and receiver exclusions, determinism, atomic
failure, fixed points and idempotence on both supported Scala versions.

`ParameterizedStreamFifoExpressionPassWitness` emits the expression-only
candidate. `ParameterizedStreamFifoAllPassWitness` emits the common-flag
candidate after all three native identity rewrites. Both are test-only bridges;
WA-08 owns production publication and writeback.

`run-wa07-regression.sh` generates the unchanged common reference, every
historical direct candidate, the expression-only candidate and the all-pass
candidate. It requires non-empty transformations, zero procedural rewrites,
byte-identical repeated Verilog and reports, strict Verilog-2001 compilation,
lint, synthesis and representative simulations. The formal harness compares
the expression-only and all-pass candidates directly against the same common
pre-pass StreamFifo capture over all 512 admitted `WIDTH`/`DEPTH` bindings.

"""
if marker not in readme:
    raise SystemExit("README common-witness marker missing")
readme = readme.replace(marker, wa07_section + marker, 1)
readme = readme.replace(
    "python3 morphhdl-passes/scripts/check-wa06-pipeline.py\n",
    "python3 morphhdl-passes/scripts/check-wa06-pipeline.py\n"
    "python3 morphhdl-passes/scripts/check-wa07-expression-pass.py --self-test\n"
    "python3 morphhdl-passes/scripts/check-wa07-expression-pass.py\n",
)
readme = readme.replace(
    "bash morphhdl-passes/scripts/run-wa06-regression.sh",
    "bash morphhdl-passes/scripts/run-wa07-regression.sh",
)
readme = readme.replace(
    "- `morphhdl-passes/build/pass-outputs/wire-alias-combined.v`.\n",
    "- `morphhdl-passes/build/pass-outputs/wire-alias-combined.v`;\n"
    "- `morphhdl-passes/build/pass-outputs/wire-expression-unnamed.v`; and\n"
    "- `morphhdl-passes/build/pass-outputs/wire-assignment-all.v`.\n",
)
readme = readme.replace(
    "All three are compared to the same captured pre-pass design. WA-06 completes\nstandalone pipeline orchestration and regression closure. WA-07 remains the\nseparately reviewed production handoff into MorphHDL-owned generation flow.",
    "All five are compared to the same captured pre-pass design. WA-07 completes\n"
    "the one-flag standalone pipeline and expression-inlining proof. WA-08 remains\n"
    "the separately reviewed production handoff into MorphHDL-owned generation flow.",
)
readme_path.write_text(readme, encoding="utf-8")

# Reserve MorphHDL-owned production changes for WA-08 now that WA-07 is a
# standalone pass-workspace increment.
boundary = root / "morphhdl-passes/scripts/check-boundary.sh"
text = boundary.read_text(encoding="utf-8")
text = text.replace("is_wa07", "is_wa08")
text = text.replace("wa07_dependencies_satisfied", "wa08_dependencies_satisfied")
text = text.replace("agent/wa-07-*|wa-07-*", "agent/wa-08-*|wa-08-*")
text = text.replace(
    "grep -Eq '^- \\[x\\] \\\*\\\*WA-06[[:space:]]+—'",
    "grep -Eq '^- \\[x\\] \\\*\\\*WA-07[[:space:]]+—'",
)
text = text.replace("WA-07 MorphHDL-owned", "WA-08 MorphHDL-owned")
text = text.replace("after WA-06 and PV-58", "after WA-07 and PV-58")
text = text.replace("agent/wa-07-*", "agent/wa-08-*")
boundary.write_text(text, encoding="utf-8")

boundary_test = root / "morphhdl-passes/scripts/test-boundary-guard.sh"
text = boundary_test.read_text(encoding="utf-8")
text = text.replace("wa07_manifest", "wa08_manifest")
text = text.replace("wa07.txt", "wa08.txt")
text = text.replace("WA-07 handoff", "WA-08 handoff")
text = text.replace("agent/wa-07-final-handoff", "agent/wa-08-final-handoff")
text = text.replace("completed WA-06", "completed WA-07")
text = text.replace("completed WA-06 and PV-58", "completed WA-07 and PV-58")
text = text.replace(
    "- [ ] **WA-06 — Ordered two-pass pipeline and regression closure**",
    "- [ ] **WA-07 — Unnamed continuous wire-expression elimination and common pass flag**",
)
text = text.replace(
    "- [x] **WA-06 — Ordered two-pass pipeline and regression closure**",
    "- [x] **WA-07 — Unnamed continuous wire-expression elimination and common pass flag**",
)
boundary_test.write_text(text, encoding="utf-8")

for temporary in (
    root / ".github/workflows/wa07-roadmap-docs-patch.yml",
    root / "morphhdl-passes/scripts/wa07-roadmap-docs-patch.py",
):
    if temporary.exists():
        temporary.unlink()

subprocess.run(["git", "config", "user.name", "morphhdl-wa07-bot"], cwd=root, check=True)
subprocess.run(["git", "config", "user.email", "morphhdl-wa07-bot@users.noreply.github.com"], cwd=root, check=True)
subprocess.run(["git", "add", "-A"], cwd=root, check=True)
subprocess.run(["git", "commit", "-m", "WA-07: insert expression pass and move production handoff to WA-08"], cwd=root, check=True)
subprocess.run(["git", "push", "origin", "HEAD:agent/wa-07-unified-expression-alias-pass"], cwd=root, check=True)
