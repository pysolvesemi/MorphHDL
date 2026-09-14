# Increment 61 — Parameterized `oneFilePerComponent` publication

## Dependency review

Increment 61 is publication-path work and is not architecturally blocked by
Increment 59i. The canonical native component identity map, parameterized module
rewrite path and completed signed-Verilog behavior already exist on
`parameterized-verilog`. Increment 59i changes balanced-reduction admission,
shape replay and qualification; it does not supply module-file ownership or
publication infrastructure.

Increment 61 therefore proceeds in parallel from the merged Increment 60 base.
If it merges before 59i, 59i must rebase and qualify its combined cases in both
consolidated and per-component modes before 59i is marked complete.

## Implementation

The implementation uses native SpinalHDL `oneFilePerComponent` emission as the
source of file boundaries and the existing exact emitter canonical-component
map as identity authority. It does not concatenate generated files and does not
parse a consolidated Verilog file to reconstruct components.

The parameterized expression/hierarchy rewrite and enum-localization passes now
rewrite each canonical component file independently. A known component filename
is validated to contain exactly its expected module before publication. External
`BlackBox` definitions remain external. Module-local parameters, localparams,
functions, memories, signed declarations and generate regions remain in the
owning native component file.

Final publication is copied from a private workspace only after all rewrites
succeed. Output names are deterministic (`<definitionName>.v`), the top file is
reported last, and `<top>.lst` records the relative source order. A hidden V2
SHA-256 ownership manifest is paired with one deterministic hidden marker per
managed output. A previous manifest entry is trusted only when its marker names
the same path and hash; missing, extra, modified or orphaned markers fail before
any public file changes. This prevents a modified manifest from claiming and
deleting an unrelated user file. Stale generated files are deleted only when
both ownership evidence and file bytes remain unchanged. Unowned filename
collisions, user-modified managed files and symlinked target-parent paths fail
closed before public output bytes are changed.

`netlistFileName` is rejected with `oneFilePerComponent = true` because one name
cannot identify several logical component definitions. Consolidated publication
and ordinary concrete `SpinalVerilog` remain unchanged.

## Qualification status and continuation

The implementation is qualified at commit
`244616e856bfb217987884b76f694fe7ec66e0ea`, tree
`61c8f05d8f14025919c642b609859ec1c19769b6`. That source includes the target
`f06d9c412924b99cf2c76422549c375a571757dc`, WA-10 general-expression inlining,
and the authenticated Increment 61 pass-workflow signature. Its applicable
workflows completed successfully, including the full permanent pass workspace.

The roadmap records the implementation requirements as satisfied by that
qualification. This closeout also integrates target
`3d582c281975732b32fbf6fafef9afb5549223d9`, including WA-11 symbolic
Boolean-width normalization. All six Increment 61 production files and all
three test files are byte-identical to the qualified source. The complete
WA-11 target is preserved, with the pass-workflow integration adapter and its
single authenticated signature composed explicitly.

The evidence below applies to `244616e...`; it does not qualify the changed
closeout tree or WA-11 integration. Every applicable workflow, including the
new WA-11 workflow, must pass on the exact published closeout commit before
[PR #179](https://github.com/pysolvesemi/MorphHDL/pull/179) is merged. A roadmap
checkbox or this document is not merge evidence. Merge closure additionally
requires the live PR's `merged=true`, `merged_at`, and verified target ancestry.
The combined inherited catalog must retain 2,012 tests in 197 suites plus
Increment 61's unchanged 20 tests in two suites: 2,032 tests in 199 suites per
Scala version. These are qualification obligations for the integrated tree,
not results attributed to the earlier run.

## Completed qualification at 244616e

The GitHub Actions inventory for the exact qualified commit contains 59 runs:
44 successful runs and 15 skipped historical workflows, with no failed or
pending runs. The 44 successful runs comprise 41 pull-request runs and three
push runs. These counts distinguish workflow runs from individual jobs.

| Workflow | Run | Successful jobs | Evidence |
| --- | --- | ---: | --- |
| Increment 61 publication | [34773074358](https://github.com/pysolvesemi/MorphHDL/actions/runs/34773074358) | 4 | Source/audit composition, both Scala publication/tool/proof lanes, generated-byte comparison |
| Increment 61 compatibility matrix | [34773074449](https://github.com/pysolvesemi/MorphHDL/actions/runs/34773074449) | 3 | Both Scala compatibility/tool lanes and generated-byte comparison |
| Increment 60f equivalence closure | [34773074120](https://github.com/pysolvesemi/MorphHDL/actions/runs/34773074120) | 5 | Both Scala inherited-regression and signedness-closure lanes, deterministic signedness artifacts |
| Permanent MorphHDL IR pass workspace | [34773074451](https://github.com/pysolvesemi/MorphHDL/actions/runs/34773074451) | 24 | Boundary checks, both Scala pass and WA-10 production lanes, native generation, WA-10 byte comparison, 16 formal shards, aggregate |

Additional applicable exact-source workflows also passed:

| Workflow | Event | Successful run |
| --- | --- | --- |
| Increment 55 concrete compatibility and approved-native-change audit | pull_request | [34773074467](https://github.com/pysolvesemi/MorphHDL/actions/runs/34773074467) |
| Increment 56 native typed library-call surface | pull_request | [34773074453](https://github.com/pysolvesemi/MorphHDL/actions/runs/34773074453) |
| Increment 57 broad native library migration and proof | pull_request | [34773074283](https://github.com/pysolvesemi/MorphHDL/actions/runs/34773074283) |
| Increment 57a typed native StreamFifoCC depth and CDC proof | pull_request | [34773074378](https://github.com/pysolvesemi/MorphHDL/actions/runs/34773074378) |
| Increment 57b StreamFifoCC payload-width formal proof | pull_request | [34773074377](https://github.com/pysolvesemi/MorphHDL/actions/runs/34773074377) |
| Increment 58 legacy adapter and shadow-path retirement | pull_request | [34773074239](https://github.com/pysolvesemi/MorphHDL/actions/runs/34773074239) |
| Increment 59 typed BlackBox parameter and generic binding | pull_request | [34773074297](https://github.com/pysolvesemi/MorphHDL/actions/runs/34773074297) |
| Increment 59a bounded recursive Verilog module | pull_request | [34773074323](https://github.com/pysolvesemi/MorphHDL/actions/runs/34773074323) |
| Increment 59c named field vectors | pull_request | [34773074501](https://github.com/pysolvesemi/MorphHDL/actions/runs/34773074501) |
| Increment 59e composite balanced reductions | pull_request | [34773074456](https://github.com/pysolvesemi/MorphHDL/actions/runs/34773074456) |
| Increment 59g native register bridge qualification | pull_request | [34773074121](https://github.com/pysolvesemi/MorphHDL/actions/runs/34773074121) |
| Increment 60a SInt baseline capture | pull_request | [34773074125](https://github.com/pysolvesemi/MorphHDL/actions/runs/34773074125) |
| Increment 60c signed declarations | pull_request | [34773074389](https://github.com/pysolvesemi/MorphHDL/actions/runs/34773074389) |
| Increment 60d pure SInt casts | pull_request | [34773074303](https://github.com/pysolvesemi/MorphHDL/actions/runs/34773074303) |
| Increment 60e signedness boundaries | pull_request | [34773074477](https://github.com/pysolvesemi/MorphHDL/actions/runs/34773074477) |
| Increment 60g default signed Verilog | pull_request | [34773074302](https://github.com/pysolvesemi/MorphHDL/actions/runs/34773074302) |
| Increment 61 one-file-per-component publication | push | [34773071709](https://github.com/pysolvesemi/MorphHDL/actions/runs/34773071709) |
| Increment 61 per-component compatibility matrix | push | [34773071772](https://github.com/pysolvesemi/MorphHDL/actions/runs/34773071772) |
| Increment 62 WA-08 inherited workflow closure | pull_request | [34773074293](https://github.com/pysolvesemi/MorphHDL/actions/runs/34773074293) |
| MorphHDL Mill | pull_request | [34773074422](https://github.com/pysolvesemi/MorphHDL/actions/runs/34773074422) |
| MorphHDL SCREAMING_SNAKE_CASE module-local SpinalEnum parameters and formal equivalence | pull_request | [34773074285](https://github.com/pysolvesemi/MorphHDL/actions/runs/34773074285) |
| MorphHDL StreamFifoCC CDC and formal proof | pull_request | [34773074440](https://github.com/pysolvesemi/MorphHDL/actions/runs/34773074440) |
| MorphHDL baseline | pull_request | [34773074315](https://github.com/pysolvesemi/MorphHDL/actions/runs/34773074315) |
| MorphHDL external SpinalHDL boundary | pull_request | [34773074122](https://github.com/pysolvesemi/MorphHDL/actions/runs/34773074122) |
| MorphHDL external native Int formalization | pull_request | [34773074258](https://github.com/pysolvesemi/MorphHDL/actions/runs/34773074258) |
| MorphHDL external native memory | pull_request | [34773074123](https://github.com/pysolvesemi/MorphHDL/actions/runs/34773074123) |
| MorphHDL external structural and process capture | pull_request | [34773074399](https://github.com/pysolvesemi/MorphHDL/actions/runs/34773074399) |
| MorphHDL external symbolic width | pull_request | [34773074524](https://github.com/pysolvesemi/MorphHDL/actions/runs/34773074524) |
| MorphHDL native AXI4 Slave Factory formal equivalence | pull_request | [34773074290](https://github.com/pysolvesemi/MorphHDL/actions/runs/34773074290) |
| MorphHDL native AXI4 Slave Factory parameterized offsets | pull_request | [34773074414](https://github.com/pysolvesemi/MorphHDL/actions/runs/34773074414) |
| MorphHDL native Int nested symbolic control flow | pull_request | [34773074402](https://github.com/pysolvesemi/MorphHDL/actions/runs/34773074402) |
| MorphHDL native Int shadow expressions | pull_request | [34773074479](https://github.com/pysolvesemi/MorphHDL/actions/runs/34773074479) |
| MorphHDL native Int shadow provenance | pull_request | [34773074458](https://github.com/pysolvesemi/MorphHDL/actions/runs/34773074458) |
| MorphHDL native Int symbolic conditionals | pull_request | [34773074473](https://github.com/pysolvesemi/MorphHDL/actions/runs/34773074473) |
| MorphHDL native StreamFifo formal equivalence | pull_request | [34773074266](https://github.com/pysolvesemi/MorphHDL/actions/runs/34773074266) |
| MorphHDL native StreamFifo parameter structure | pull_request | [34773074312](https://github.com/pysolvesemi/MorphHDL/actions/runs/34773074312) |
| MorphHDL native StreamWidthAdapter parameterization | pull_request | [34773074341](https://github.com/pysolvesemi/MorphHDL/actions/runs/34773074341) |
| MorphHDL native source guard | pull_request | [34773074295](https://github.com/pysolvesemi/MorphHDL/actions/runs/34773074295) |
| MorphHDL native source guard | push | [34773071707](https://github.com/pysolvesemi/MorphHDL/actions/runs/34773071707) |
| MorphHDL typed elaboration values | pull_request | [34773074394](https://github.com/pysolvesemi/MorphHDL/actions/runs/34773074394) |

The 15 skipped historical workflows are deliberately gated to their earlier
increment branches; their inherited obligations are covered by the applicable
successor workflows above. They are not counted as successful runs. No required
job skipped because of a failed prerequisite is accepted as qualification.

The full pass workspace has **24** successful jobs: one boundary job, two
Scala pass jobs, two WA-10 production jobs, one WA-04 through WA-09 native
generation job, one WA-10 cross-Scala job, 16 formal shards, and one aggregate
job. No full-domain shard or aggregate was skipped.

The aggregate artifact `morphhdl-wa07a-equivalence-evidence` (artifact ID
`10328839571`) reports `PASS` and binds `source_commit` to the full qualified
commit above. It covers 11 pass configurations, 512 bindings per configuration,
16 disjoint shards, and two complete repeated runs: **11,264 equivalence proofs
and 11,264 comparison-reachability proofs**. Complete-domain coverage, exact
disjoint coverage, determinism and mutation detection all pass. Its source
signature registry digest is
`0a97dab713652adb8809191ea8ddb6fb239bce7d65069e6e0745aa510b90881b` and fixture
manifest digest is
`f6a10f5326372a0e44b0de537db2a2222d1c8d4ab3eac8241d59660c11a1c650`.
These are the inherited WA-04 through WA-09 proof counts; WA-10 production
qualification is reported separately below.

| Qualification | Scala 2.12.18 | Scala 2.13.12 |
| --- | --- | --- |
| Increment 61 publication suite | 16 tests passed | 16 tests passed |
| Increment 61 compatibility suite | 4 tests passed | 4 tests passed |
| Full inherited and Increment 61 inventory | 2,016 tests / 197 suites; zero failures, errors or skips | 2,016 tests / 197 suites; zero failures, errors or skips |
| WA-10 focused core and production suites | 27 core + 14 production tests passed | 27 core + 14 production tests passed |
| Publication and compatibility tools | Icarus Verilog-2001, Verilator and Yosys passed | Icarus Verilog-2001, Verilator and Yosys passed |

The complete test inventory consists of 1,996 inherited tests in 195 suites plus
Increment 61's 20 tests in two suites. The focused WA-10 counts are separate
executions and are not added again to the complete inventory.

Publication generates the split and consolidated hierarchy twice independently
on each Scala version; both complete four-file generated sets match. The same
four files also match across Scala versions. The 15-case compatibility corpus
contains 41 generated files, including source lists and explicit external/tool
support, and those complete sets match across Scala versions. Each publication
and compatibility artifact's `head.txt` records the exact qualified source.

WA-10 production checks pass for the timing and general-expression fixtures at
PPC4 values 0 and 1 in default, enabled and disabled modes. They include strict
Verilog-2001 compilation, numeric/four-state oracle and trace comparisons,
sequential equivalence, underflow-oracle mutation rejection and deterministic
generation. Each timing parameter case has 848 trace samples; each
general-expression parameter case has 784. These fixtures qualify the public
general-expression behavior independently of the inherited full-domain pass
counts above.

The inherited signedness closure also passes on both Scala versions, retaining
99 independently generated files, 64 boundary-equivalence tuples, the exact
60a mutation counterexample and witness replay, four memory widths, eight
bounded memory steps and eight independent-initial-state counterexamples. The
213-file RTL manifests bind to the qualified commit and match across Scala
versions.

## Executed publication example

The Scala fixture and publication entrypoint below are copied verbatim from
[`Increment61PerComponentPublicationTests.scala` at the qualified source](https://github.com/pysolvesemi/MorphHDL/blob/244616e856bfb217987884b76f694fe7ec66e0ea/morphhdl/src/test/scala/morphhdl/Increment61PerComponentPublicationTests.scala).
The displayed Verilog and source list come from the successful publication
workflow's `increment-61-2.12.18` artifact (ID `10324341658`), under
`generated-a/split/`. The corresponding Scala 2.13.12 artifact (ID
`10323300937`) contains identical generated bytes. Only trailing whitespace is
trimmed for the Markdown display; the original downloaded artifacts and their
digests retain the exact emitted bytes.

### Actual Scala fixture and entrypoint

```scala
package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest

import scala.collection.JavaConverters._

import nativeapplication.{BoundedRecursivePowerFixture, TypedBlackBoxGenericBindingFixture}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.internals.MorphHdlSignednessAnalysis

import morphhdl.frontend.{formalParam, HdlInt}

object Increment61PerComponentFixture {
  final class Leaf(actualWidth: HdlInt) extends Component {
    setDefinitionName("Increment61Leaf")
    addAttribute("keep_hierarchy", "TRUE")

    @dontName private val width = formalParam(
      actualWidth,
      "WIDTH",
      minimum = BigInt(1),
      maximum = BigInt(64)
    )
    val din = in(morphhdl.frontend.Bits(width bits))
    val dout = out(morphhdl.frontend.Bits(width bits))
    dout := din
  }

  final class Top(leftWidth: HdlInt, rightWidth: HdlInt) extends Component {
    setDefinitionName("Increment61Top")

    val leftIn = in(morphhdl.frontend.Bits(leftWidth bits))
    val leftOut = out(morphhdl.frontend.Bits(leftWidth bits))
    val rightIn = in(morphhdl.frontend.Bits(rightWidth bits))
    val rightOut = out(morphhdl.frontend.Bits(rightWidth bits))

    val left = new Leaf(leftWidth)
    left.setName("left")
    val right = new Leaf(rightWidth)
    right.setName("right")
    left.din := leftIn
    leftOut := left.dout
    right.din := rightIn
    rightOut := right.dout
  }

  final class FlatTop(width: HdlInt) extends Component {
    setDefinitionName("Increment61Top")
    val din = in(morphhdl.frontend.Bits(width bits))
    val dout = out(morphhdl.frontend.Bits(width bits))
    dout := din
  }

  def hierarchical(): Component = {
    val leftWidth = HdlInt.param("LEFT_WIDTH", default = 5, min = 1, max = 64)
    val rightWidth = HdlInt.param("RIGHT_WIDTH", default = 5, min = 1, max = 64)
    new Top(leftWidth, rightWidth)
  }

  def flat(): Component = {
    val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 64)
    new FlatTop(width)
  }
}

object Increment61PerComponentPublicationArtifacts {
  def main(args: Array[String]): Unit = {
    if (args.length != 1) {
      throw new IllegalArgumentException(
        "Usage: Increment61PerComponentPublicationArtifacts <output-directory>"
      )
    }
    val root = Paths.get(args(0))
    val split = root.resolve("split")
    val consolidated = root.resolve("consolidated")
    Files.createDirectories(split)
    Files.createDirectories(consolidated)

    MorphVerilog(
      SpinalConfig(
        targetDirectory = split.toString,
        oneFilePerComponent = true
      )
    )(Increment61PerComponentFixture.hierarchical())

    val consolidatedConfig = SpinalConfig(targetDirectory = consolidated.toString)
    consolidatedConfig.netlistFileName = "Increment61Top.v"
    MorphVerilog(consolidatedConfig)(
      Increment61PerComponentFixture.hierarchical()
    )
  }
}
```

### Increment61Leaf.v

Original file SHA-256: `9fd055735dd1846fe6cdf1c67c1d657f461e967fd1554f9b74f885e5a185bff3`.

```verilog
// Generator : SpinalHDL dev    git head : 244616e856bfb217987884b76f694fe7ec66e0ea
// Component : Increment61Leaf
// Git hash  : 244616e856bfb217987884b76f694fe7ec66e0ea

`timescale 1ns/1ps
module Increment61Leaf #(
  parameter integer WIDTH = 5
) (
  input  wire [WIDTH-1:0] din,
  output wire [WIDTH-1:0] dout
);

  assign dout = din;

endmodule
```

### Increment61Top.v

Original file SHA-256: `f119d10c007ca7185c64965a353fb983a812dc9a1467a89b065723aeb98497c6`.

```verilog
// Generator : SpinalHDL dev    git head : 244616e856bfb217987884b76f694fe7ec66e0ea
// Component : Increment61Top
// Git hash  : 244616e856bfb217987884b76f694fe7ec66e0ea

`timescale 1ns/1ps
module Increment61Top #(
  parameter integer LEFT_WIDTH = 5,
  parameter integer RIGHT_WIDTH = 5
) (
  input  wire [LEFT_WIDTH-1:0]    leftIn,
  output wire [LEFT_WIDTH-1:0]    leftOut,
  input  wire [RIGHT_WIDTH-1:0]    rightIn,
  output wire [RIGHT_WIDTH-1:0]    rightOut
);

  wire       [LEFT_WIDTH-1:0]    left_dout;
  wire       [RIGHT_WIDTH-1:0]    right_dout;

  (* keep_hierarchy = "TRUE" *) Increment61Leaf #(
    .WIDTH(LEFT_WIDTH)
  ) left (
    .din  (leftIn[LEFT_WIDTH-1:0]   ), //i
    .dout (left_dout[LEFT_WIDTH-1:0])  //o
  );
  (* keep_hierarchy = "TRUE" *) Increment61Leaf #(
    .WIDTH(RIGHT_WIDTH)
  ) right (
    .din  (rightIn[RIGHT_WIDTH-1:0]   ), //i
    .dout (right_dout[RIGHT_WIDTH-1:0])  //o
  );
  assign leftOut = left_dout;
  assign rightOut = right_dout;

endmodule
```

### Increment61Top.lst

Original file SHA-256: `a10fca915fcfe3179b91355dd8e74ccd8f94faac345c83bbfc102a985f466142`.

```text
Increment61Leaf.v
Increment61Top.v
```

## Qualification scope and publication limits

The compatibility matrix includes named-field access, nested fields, storage
and streams; StreamFifo and StreamFifoCC; a signed-memory hierarchy; bounded
recursive generation; widening, composite, callback-graph, register-bridge,
balanced and nested-hierarchy reductions; and typed BlackBox generic bindings.
These are the representative currently merged surfaces covered by Increment
61. Their combined feature interactions remain Increment 59i's independent
integration responsibility, including both publication modes once Increment 61
is merged.

The compatibility workflow compiles all reported sources and explicitly
declared external/tool support with Icarus Verilog-2001, lints with Verilator,
and synthesizes and checks with Yosys. Compatibility lint retains the existing
`-Wno-fatal` setting; it is not a warning-free lint claim. The dedicated
publication hierarchy also passes strict split and consolidated tool checks,
plus Yosys `equiv_status -assert` at independent LEFT_WIDTH/RIGHT_WIDTH pairs
(1,1), (5,5), (7,13) and (32,3). Missing-child, duplicate-definition and
wrong-file negative controls are detected on both Scala versions. Formal
split/consolidated equivalence is qualified for that representative hierarchy,
not for every compatibility-corpus family.

All generation and rewriting finish privately before publication. Existing
outputs are preflight-checked against paired ownership markers and SHA-256
hashes. Each public file replacement uses a same-directory `ATOMIC_MOVE`,
without a non-atomic fallback. This provides atomic replacement of each file;
the complete output set is not a transaction. Replacements, stale deletions,
owner markers and the final manifest are updated sequentially. An I/O failure
after publication starts can leave a partial set, with no transactional
output-set rollback. Concurrent publication and external file changes during
publication are not qualified. Source ordering is alphabetical with the top
last, not a general topological ordering.

Consolidated publication remains available through
`oneFilePerComponent = false`. External BlackBox implementations continue to be
supplied separately, and the split option does not copy or regenerate them.
The combination of `netlistFileName` and `oneFilePerComponent = true` is rejected
before elaboration. The final continuation must retain these contracts and
pass all applicable checks, including the newly integrated WA-11 workflow,
before the branch is merged.
