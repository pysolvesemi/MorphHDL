Fix the remaining recursive wire-cleanup limitation in MorphHDL, if the native parameterized publication architecture permits it, and fix the separate unwanted naming of retained resize temporaries. Work in the MorphHDL repository, follow its current AGENTS.md and qualification rules, and use the current integration target as the starting point. This is a generic compiler repair, not a Display Controller RTL rewrite.

Problem
=======
The CDC-WIRE-01 fixed-point cleanup was merged at 155df6eb0e38ecce04a37de2067b0794702fcb83, but regeneration of a real application still retains apparently removable aliases, a parameter-width zero constant, and a mux-output carrier:

  wire [(FIFO_LOG_DEPTH + 2)-1:0] _zz_live_write_fill_1;
  wire [(FIFO_LOG_DEPTH + 2)-1:0] _zz_live_write_fill_2;
  wire [(FIFO_LOG_DEPTH + 2)-1:0] _zz_live_read_fill_1;

  assign _zz_live_write_fill_1 = morphhdl_resize_1;
  assign _zz_live_write_fill_2 = {(FIFO_LOG_DEPTH + 2){1'b0}};
  assign live_write_fill = (_zz_live_write_fill ? _zz_live_write_fill_2 : _zz_live_write_fill_1);
  assign _zz_live_read_fill_1 = ((_zz_live_write_fill || (live_fifo_capacity < _zz__zz_live_read_fill_1)) ? _zz_live_write_fill_2 : _zz_live_read_fill);
  assign live_read_fill = _zz_live_read_fill_1;

The expected recursive behavior is to substitute eligible expressions into every receiver and repeat until only genuinely necessary or explicitly protected carriers remain. Reaching a fixed point under overly restrictive eligibility rules does not establish that all removable wires have been eliminated.

Investigate before changing code
===============================
Static source inspection identified possible barriers, NOT a confirmed per-wire diagnosis:

- morphhdl/src/main/scala/spinal/core/internals/NativeWireAssignmentMetadata.scala: retains(alias).
- morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedAutoResize.scala: retainsWireIdentity and clearGraph.
- morphhdl-passes/examples/NamedWireAliasNativeBridge.scala: expressionRemovalBlocker, REGISTERED-IDENTITY and REGISTERED-RECEIVER.
- morphhdl-passes/examples/NamedWireExpressionNativeBridge.scala: symbolic receiver packed-boundary checks, expression capture/copy and expansion budgets.
- morphhdl/src/main/scala/morphhdl/examples/WireAssignmentProductionBridge.scala: production fixed-point loop and phase placement.
- morphhdl/src/main/scala/morphhdl/MorphVerilog.scala: publication and resize-record cleanup ordering.

Here "registered identity/receiver" refers to stored compiler publication metadata, not necessarily a hardware register.

First reproduce the current output and capture the actual candidate disposition for each surviving alias, constant and mux carrier. Include candidates excluded before proveCandidate, not only candidates counted as rejected. Distinguish symbolic-width proof failures, publication ownership, explicit preservation, scope/receiver restrictions, expansion limits and late emitter-created wrappers. Do not infer a rejection reason or unnamed provenance from the emitted _zz spelling. Names and suffixes will change between builds.

Standalone reproduction
=======================
This source mirrors the application's parameterized occupancy expressions. It needs only MorphHDL and its native libraries: no Display Controller checkout, Dan IP, private inputs or production leaf stubs. It is a proposed focused reproduction; compile it and confirm which residual expressions it actually exposes before claiming an exact match to the application report. If necessary, minimize/refine the fixture while retaining the real symbolic-width and resize path, and record why.

Save as /tmp/RecursiveFillCleanupRepro.scala:

```scala
package roadmap

import spinal.core._
import morphhdl.{MorphVerilog, MorphWireAssignmentPasses}
import morphhdl.frontend.HdlInt

object RecursiveFillCleanupRepro extends App {
  require(args.length == 2, "Expected output-directory and passes-enabled")
  val config = MorphWireAssignmentPasses(
    SpinalConfig(
      targetDirectory = args(0),
      oneFilePerComponent = true,
      headerWithDate = false,
      headerWithRepoHash = true
    ),
    enabled = args(1).toBoolean
  )

  MorphVerilog(config) {
    new Component {
      setDefinitionName("RecursiveFillCleanupRepro")
      val depth: ElabInt = HdlInt.param("FIFO_LOG_DEPTH", 3, 2, 16).asElabInt
      val countBits: ElabInt = depth + 2
      val capacity: ElabInt = depth.pow2 + 1

      val bypass = in Bool()
      val upper, lower = in UInt(countBits bits)
      val writeFill, readFill = out UInt(countBits bits)

      // Preserve the real path: symbolic capacity in a fixed 18-bit carrier,
      // fixed-width selection, then a symbolic narrowing resize.
      val capacityValue = ElabValue.uintLike(
        capacity, U(0, 18 bits), "fifo_capacity")
      val upperWide = UInt(18 bits)
      upperWide := upper.resize(18)
      val selectedUpper = UInt(18 bits)
      selectedUpper := Mux(upper > capacityValue, capacityValue, upperWide)
      val clampedUpper = UInt(countBits bits)
      clampedUpper := selectedUpper.resize(countBits)
      val zeroFill = UInt(countBits bits)
      zeroFill := 0

      writeFill := Mux(bypass, zeroFill, clampedUpper)
      readFill := Mux(bypass || lower > capacityValue, zeroFill, lower)
    }
  }
}
```

From the MorphHDL repository root, run:

```sh
sbt 'set morph / Test / unmanagedSources := Seq(file("/tmp/RecursiveFillCleanupRepro.scala"))' \
  'morph/Test/runMain roadmap.RecursiveFillCleanupRepro /tmp/fill-cleanup-on true' \
  'morph/Test/runMain roadmap.RecursiveFillCleanupRepro /tmp/fill-cleanup-off false'

iverilog -g2012 -s RecursiveFillCleanupRepro \
  -o /tmp/fill-cleanup-on.vvp \
  /tmp/fill-cleanup-on/RecursiveFillCleanupRepro.v
iverilog -g2012 -s RecursiveFillCleanupRepro \
  -o /tmp/fill-cleanup-off.vvp \
  /tmp/fill-cleanup-off/RecursiveFillCleanupRepro.v
```

These commands compile HDL only; they do not demonstrate behavioral correctness. The temporary SBT source override is for this standalone run. Do not use it to exclude inherited tests from qualification. Confirm both required compiler plugins remain active. Use the supported native typed MorphVerilog path; do not fall back to a concrete-only emitter.

Expected transformation
=======================
For the reported fragment, a conservative intermediate improvement is:

  assign live_write_fill = (_zz_live_write_fill
      ? {(FIFO_LOG_DEPTH + 2){1'b0}} : morphhdl_resize_1);
  assign live_read_fill = ((_zz_live_write_fill || (live_fifo_capacity < _zz__zz_live_read_fill_1))
      ? {(FIFO_LOG_DEPTH + 2){1'b0}} : _zz_live_read_fill);

This demonstrates removal of the direct alias, zero wire and mux-output wire WITHOUT presuming that the resize carrier or other dependencies are already safe to remove. Continue recursively into those dependencies only when their native proof succeeds. The standalone reproduction uses writeFill/readFill and different generated names; assert structural properties using provenance and driver/receiver identities rather than exact spellings.

Possible repair directions—not predetermined answers
====================================================
1. Prove that selected metadata references are incidental and transport/update all affected records atomically with a native substitution, preserving the owning publication validator's obligations.
2. Transfer publication ownership to a typed representation that permits the normal cleanup engine to operate after the relevant identities are consumed.
3. Add a validated cleanup stage at the appropriate native/canonical publication boundary, if necessary and compatible with the established architecture.

Choose based on the actual rejection trace. Do not simply delete preservation checks, clear records early, waive validators, or run the existing pass against a stale graph. Preserve precise diagnostics for invalid/corrupted/inactive resize records, structural branches, and Vec-owned expressions. No generated-Verilog parsing, regex rewriting, hardcoded _zz matching, component-specific recognizers, witness-width reconstruction or default-only specialization is allowed.

Acceptance and regression requirements
======================================
- The alias, shared zero constant and mux-output carrier disappear when their sole purpose is the removable combinational expression. Preserve public ports and explicit user keep/dontSimplify/vital/debug requests, even when their names look generated.
- A single enabled generation reaches the intended fixed point; repeated processing is idempotent, deterministic and terminating. Explain any remaining carrier with the exact unsatisfied proof or preservation requirement and a corresponding regression.
- Preserve symbolic declarations and resize semantics over the same emitted artifact for FIFO_LOG_DEPTH=2,3,4,8,16. Never use the default width of five bits to justify a transformation for the entire domain.
- Add a port-driven self-checking testbench. For each depth, the independent oracle is:
    capacity = (1 << FIFO_LOG_DEPTH) + 1;
    writeFill = bypass ? 0 : min(upper, capacity);
    readFill = (bypass || lower > capacity) ? 0 : lower;
  Exercise bypass on/off; zero; capacity-1, capacity, capacity+1; maximum count value; randomized upper/lower values; and four-state controls. The fixture contains no FIFO memory, so the maximum-width case does not require elaborating a huge RAM.
- Prove enabled-versus-disabled equivalence and independent-oracle agreement. For X/Z tests, preserve Verilog's exact condition/ternary merging and comparison behavior; do not treat a two-state arithmetic oracle as four-state proof.
- Include fanout, alias chains, symbolic/fixed-width boundaries, signedness and truncation negative controls, scopes/hierarchy, protected identities, publication-record validity, and late backend wrapper creation. Retain expansion budgets or justify and qualify a bounded alternative.
- Run HDL compilation, applicable lint/synthesis/formal checks, determinism and both supported Scala/build lanes as required by the repository. No skipped or unrelated fixture result counts as acceptance.
- Keep the existing CDC-WIRE-01 historical evidence intact. Record this as a focused follow-up under its existing cleanup scope rather than splitting aliases, constants and mux carriers into unrelated optimization increments.
- Follow AGENTS.md for exact-source review, targeted CI before full CI, publication and merge. If the Display Controller workspace is available, regenerate the real CDC artifact as supplementary evidence; standalone compiler acceptance must not depend on it.

Deliverables
============
Provide the actual baseline output and per-candidate rejection trace, the generic root-cause repair, permanent standalone regressions, emitted before/after evidence, exact commands and source identities, and an honest list of remaining limitations. If a proposed elimination is not safely possible at the current boundary, explain precisely why and what architectural change would enable it; do not call the surviving wire irreducible merely because today's guard rejects it.


Additional issue: preserve normal names for retained resize signals
==================================================================
Keep every cleanup requirement and the standalone occupancy example above. This naming requirement is additional; fixing names alone does not close the recursive-cleanup issue.

Observed output:

  wire [(FIFO_LOG_DEPTH + 2)-1:0] morphhdl_resize_source_2;

The source of this specific DDR wire is the expression `writes.count - readsSeen` feeding `upper.resize(18)`. Source inspection at the reported compiler commit directly identified these calls in:

  morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedNativeResize.scala

  target.dontSimplifyIt().addTag(noBackendCombMerge)
  source.dontSimplifyIt().addTag(noBackendCombMerge)
  if (!target.isNamed) target.setWeakName("morphhdl_resize")
  if (!source.isNamed) source.setWeakName("morphhdl_resize_source")

Unlike the unconfirmed per-wire cleanup diagnosis above, the naming calls are directly observed. They name previously unnamed nodes during resize capture; they are not a rename operation triggered by an optimization rejection. Backend allocation supplies numeric suffixes such as _2.

Required behavior:

- `dontSimplifyIt()` may legitimately retain a wire. It must not imply a change to its name or naming provenance.
- Preserve existing user/reflected names and the ordinary backend-generated naming convention for unnamed signals, including normal _zz names where applicable.
- Do not inject `morphhdl_resize` / `morphhdl_resize_source` names merely because a source/target is involved in symbolic resize publication or cannot be optimized.
- Preserve unnamed/generated provenance until normal backend name allocation. Do not substitute another custom prefix, hardcode an _zz name, promote compiler names into user names, or rename generated Verilog text.
- Keep genuinely necessary preservation tags and resize ownership checks. Removing naming calls must not be used as an excuse to remove `dontSimplifyIt`, noBackendCombMerge, source-width/truncation boundaries or publication validation indiscriminately.
- Investigate any publication references that currently rely on an early name. Carry stable native identity through validation and use the normal final allocated name when publishing the declaration/reference. Do not break declaration lookup, collision handling, hierarchy references or deterministic output.
- Apply this naming behavior with wire optimization enabled and disabled. If the disabled artifact necessarily changes its spelling because this is an explicit naming correction, record that narrow intentional baseline change; preserve behavior and all unrelated disabled-pass policies.
- Do not ban a user-authored name merely because it contains `morphhdl_resize` or `_zz`. The restriction concerns names injected by this compiler path.

Standalone naming/protection reproduction
=========================================
Save as /tmp/ResizeTemporaryNamingRepro.scala. This is an additional focused fixture; compile and inspect it before claiming reproduction. The inline subtraction creates an unnamed resize source, while the two explicitly named/dontSimplify controls must survive with their names intact.

```scala
package roadmap

import spinal.core._
import morphhdl.{MorphVerilog, MorphWireAssignmentPasses}
import morphhdl.frontend.HdlInt

object ResizeTemporaryNamingRepro extends App {
  require(args.length == 2, "Expected output-directory and passes-enabled")
  val config = MorphWireAssignmentPasses(
    SpinalConfig(
      targetDirectory = args(0),
      oneFilePerComponent = true,
      headerWithDate = false,
      headerWithRepoHash = true
    ),
    enabled = args(1).toBoolean
  )
  MorphVerilog(config) {
    new Component {
      setDefinitionName("ResizeTemporaryNamingRepro")
      val depth: ElabInt = HdlInt.param("FIFO_LOG_DEPTH", 3, 2, 16).asElabInt
      val width: ElabInt = depth + 2
      val a, b = in UInt(width bits)
      val wide, namedWide, zzNamedWide = out UInt(18 bits)
      val roundTrip = out UInt(width bits)

      // No application-defined name for this subtraction result.
      wide := (a - b).resize(18)
      roundTrip := wide.resize(width)

      // Explicit user preservation and names are independent of cleanup.
      val kept = UInt(width bits)
      kept.setName("kept_difference")
      kept.dontSimplifyIt()
      kept := a - b
      namedWide := kept.resize(18)

      val zzKept = UInt(width bits)
      zzKept.setName("_zz_user_kept")
      zzKept.dontSimplifyIt()
      zzKept := a - b
      zzNamedWide := zzKept.resize(18)
    }
  }
}
```

Run from the MorphHDL root:

```sh
sbt 'set morph / Test / unmanagedSources := Seq(file("/tmp/ResizeTemporaryNamingRepro.scala"))' \
  'morph/Test/runMain roadmap.ResizeTemporaryNamingRepro /tmp/resize-naming-on true' \
  'morph/Test/runMain roadmap.ResizeTemporaryNamingRepro /tmp/resize-naming-off false'

iverilog -g2012 -s ResizeTemporaryNamingRepro \
  -o /tmp/resize-naming-on.vvp \
  /tmp/resize-naming-on/ResizeTemporaryNamingRepro.v
iverilog -g2012 -s ResizeTemporaryNamingRepro \
  -o /tmp/resize-naming-off.vvp \
  /tmp/resize-naming-off/ResizeTemporaryNamingRepro.v
```

Add tests establishing that:

1. No compiler-injected morphhdl_resize/source naming occurs in this fixture. An unnamed carrier may be removed if safe or remain under ordinary backend naming. Do not require an arbitrary numeric _zz suffix across unrelated graph changes.
2. `kept_difference` and `_zz_user_kept` remain explicitly protected and keep their exact names; a generated-looking spelling does not make a user signal removable. Add an intentionally user-named morphhdl_resize_source control to distinguish user names from injected names.
3. Both modes preserve the modular subtraction width before widening. For width W=FIFO_LOG_DEPTH+2, the oracle is D=(a-b) mod 2^W; all three wide outputs are zero-extended D, and roundTrip equals D. In particular, a=0,b=1 must produce 2^W-1, not an 18-bit subtraction result before truncation.
4. Exercise FIFO_LOG_DEPTH=2,3,4,8,16, underflow, zero/max, random values, X/Z equivalence, collisions, multiple resizes and hierarchy. Check determinism and valid final references as well as output behavior.
5. The original occupancy fixture still meets its independent cleanup acceptance criteria. Retaining fewer cosmetic names without eliminating safe aliases/constants/mux carriers is not completion.

The earlier illustrative optimized fragment used `morphhdl_resize_1` only to show an intermediate cleanup step against the old artifact. It is not approval to retain that compiler-assigned prefix in the final repair. If the resize carrier is still required, its final name must follow the normal naming rules above.

Extend the final root-cause report and permanent regression suite to cover BOTH issues: safe recursive cleanup and preservation of normal names/provenance for signals that remain.
