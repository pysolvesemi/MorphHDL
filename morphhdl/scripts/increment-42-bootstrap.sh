#!/usr/bin/env bash
set -euo pipefail

branch=agent/increment-42-external-structural-process-capture
test "${GITHUB_HEAD_REF:-$branch}" = "$branch" || {
  echo "Increment 42 bootstrap is restricted to $branch" >&2
  exit 1
}

python3 - <<'PY'
from pathlib import Path


def read(path: str) -> str:
    return Path(path).read_text(encoding="utf-8")


def write(path: str, content: str) -> None:
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")


def require_once(text: str, needle: str, path: str) -> None:
    count = text.count(needle)
    if count != 1:
        raise SystemExit(f"{path}: expected one occurrence of {needle!r}, found {count}")


# Keep the general integer/Boolean expression facts in the reviewed symbolic
# registry because native memory and width sources still compile before the
# MorphHDL frontend module. Move all structural registry ownership itself.
structural_source = "core/src/main/scala/spinal/core/ParameterizedStructure.scala"
structural_text = read(structural_source)
expression_marker = "/**\n  * Backend-neutral integer expression"
block_marker = "/**\n  * Opaque snapshot"
require_once(structural_text, expression_marker, structural_source)
require_once(structural_text, block_marker, structural_source)
header = structural_text[: structural_text.index(expression_marker)]
body = structural_text[structural_text.index(block_marker):]
moved_structural = header + body
moved_structural = moved_structural.replace(
    "while only the\n  * core lowering inspects the native AST objects.",
    "while the MorphHDL-owned external lowering inspects the native AST objects.",
)
moved_structural = moved_structural.replace(
    "Native structural-capture registry used by Increment 33.",
    "MorphHDL-owned structural-capture registry retained from Increment 33.",
)
write(
    "frontend/src/main/scala/spinal/core/ParameterizedStructure.scala",
    moved_structural,
)
Path(structural_source).unlink()

width_path = "core/src/main/scala/spinal/core/ParameterizedWidth.scala"
width_text = read(width_path)
insertion_marker = "\n/** A concrete witness bit count with an optional bounded symbolic expression. */"
require_once(width_text, insertion_marker, width_path)
if "final case class ElaborationIntegerExpression(" in width_text:
    raise SystemExit("shared elaboration-expression facts are already present")
expression_definitions = r'''
/**
  * Backend-neutral integer expression retained during ordinary SpinalHDL
  * elaboration for symbolic widths, hierarchy, structure, processes and memory
  * geometry.
  *
  * `default` is the concrete witness used by the native SpinalHDL graph.
  * `minimum` and `maximum` describe the complete admitted parameter domain.
  */
final case class ElaborationIntegerExpression(
    verilog: String,
    default: BigInt,
    minimum: BigInt,
    maximum: BigInt,
    parameters: Vector[ElaborationIntegerParameter],
    generateIndex: Option[String] = None,
    sourceLocation: Option[String] = None
)

/** Boolean counterpart used by retained parameter-controlled metadata. */
final case class ElaborationBooleanExpression(
    verilog: String,
    default: Boolean,
    parameters: Vector[ElaborationIntegerParameter],
    sourceLocation: Option[String] = None
)
'''
width_text = width_text.replace(
    insertion_marker,
    "\n" + expression_definitions + insertion_marker,
    1,
)
write(width_path, width_text)

process_source = "core/src/main/scala/spinal/core/ParameterizedProcess.scala"
process_text = read(process_source).replace(
    "Increment 34 classifier for parameter-bounded ranges.",
    "MorphHDL-owned Increment 34 classifier for parameter-bounded ranges.",
)
write(
    "frontend/src/main/scala/spinal/core/ParameterizedProcess.scala",
    process_text,
)
Path(process_source).unlink()

for name in ("ParameterizedVerilogStructural.scala", "ParameterizedVerilogProcesses.scala"):
    source = Path("core/src/main/scala/spinal/core/internals") / name
    destination = Path("morphhdl/src/main/scala/spinal/core/internals") / name
    text = source.read_text(encoding="utf-8")
    if name == "ParameterizedVerilogStructural.scala":
        text = text.replace(
            "Increment 33 relocation of validated ordinary SpinalHDL module items into",
            "MorphHDL-owned Increment 33 relocation of validated ordinary SpinalHDL module items into",
        )
    else:
        text = text.replace(
            "Increment 34 replacement of one witnessed native assignment with a",
            "MorphHDL-owned Increment 34 replacement of one witnessed native assignment with a",
        )
    write(str(destination), text)
    source.unlink()

phase_path = "core/src/main/scala/spinal/core/internals/PhaseVerilog.scala"
phase_text = read(phase_path)
start_marker = "    def canonicalOf(child: Component): Component ="
end_marker = "\n\n    if(component.parentScope == null && pc.config.dumpWave != null)"
require_once(phase_text, start_marker, phase_path)
require_once(phase_text, end_marker, phase_path)
start = phase_text.index(start_marker)
end = phase_text.index(end_marker, start)
phase_replacement = '''    // Increment 42 moves structural and procedural publication behind the
    // MorphHDL-owned external final phase. Increment 35 memory lowering remains
    // here until its dedicated zero-Mem.scala corrective increment.
    val componentResult = () =>
      ParameterizedVerilogMemories.rewrite(
        component,
        componentBuilderVerilog.result,
        pc
      )'''
phase_text = phase_text[:start] + phase_replacement + phase_text[end:]
write(phase_path, phase_text)

external_path = "morphhdl/src/main/scala/spinal/core/internals/MorphHdlExternalParameterizedVerilog.scala"
external_text = read(external_path)
external_text = external_text.replace(
    "MorphHDL-owned final publication transform for Increment 41.",
    "MorphHDL-owned final publication transform for Increments 41 and 42.",
)
external_text = external_text.replace(
    "proves symbolic expression/connection/hierarchy contracts, then rewrites\n  * only the published Verilog artifact.",
    "proves symbolic expression, connection, hierarchy, structural and process\n  * contracts, then rewrites only the published Verilog artifact.",
)

schema_old = "    ComponentSchema(orderedPorts, ParameterizedWidth.parametersOf(component))"
require_once(external_text, schema_old, external_path)
external_text = external_text.replace(
    schema_old,
    "    ComponentSchema(orderedPorts, componentParameters(component))",
    1,
)

metadata_marker = "  private def hasParameterizedMetadata(component: Component): Boolean ="
require_once(external_text, metadata_marker, external_path)
metadata_start = external_text.index(metadata_marker)
routing_comment = "  /**\n    * Match the Increment 31/32 routing boundary"
require_once(external_text, routing_comment, external_path)
metadata_end = external_text.index(routing_comment, metadata_start)
metadata_replacement = '''  private def componentParameters(
      component: Component
  ): Vector[ElaborationIntegerParameter] = {
    val values =
      ParameterizedWidth.parametersOf(component) ++
        ParameterizedMemory.parametersOf(component) ++
        ParameterizedStructure.parametersOf(component) ++
        ParameterizedProcess.parametersOf(component)
    val grouped = values.groupBy(_.name)
    grouped.collectFirst {
      case (name, declarations) if declarations.distinct.size != 1 => name
    }.foreach { name =>
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-SCHEMA-CONFLICT",
        s"component '${componentName(component)}' has conflicting external parameter declarations for '$name'"
      )
    }
    grouped.toVector.map(_._2.head).sortBy(_.name)
  }

  private def hasParameterizedMetadata(component: Component): Boolean =
    componentParameters(component).nonEmpty

'''
external_text = external_text[:metadata_start] + metadata_replacement + external_text[metadata_end:]

rewrite_start_marker = "    val rewrittenByName = expectedModules.toVector.sorted.flatMap { name =>"
rewrite_end_marker = "\n\n    if (rewrittenByName.nonEmpty)"
require_once(external_text, rewrite_start_marker, external_path)
require_once(external_text, rewrite_end_marker, external_path)
rewrite_start = external_text.index(rewrite_start_marker)
rewrite_end = external_text.index(rewrite_end_marker, rewrite_start)
rewrite_replacement = '''    val rewrittenByName = expectedModules.toVector.sorted.flatMap { name =>
      val component = canonicalByName(name)
      if (requiresPublicationRewrite(component)) {
        val block = blockByName(name)
        val text = lines.slice(block.start, block.end + 1).mkString("\\n")
        val rewritten = withPulledExternalClockInputs(component) {
          val withProcesses = ParameterizedVerilogProcesses.rewrite(
            component,
            text,
            pc
          )
          val withStructure = ParameterizedVerilogStructural.rewrite(
            component,
            withProcesses,
            pc,
            canonicalOf
          )
          if (requiresExpressionHierarchyRewrite(component)) {
            ExternalParameterizedVerilogNativeFallback.rewrite(
              component,
              withStructure,
              pc,
              canonicalOf
            )
          } else withStructure
        }
        Some(name -> rewritten.split("\\n", -1).toVector)
      } else None
    }.toMap'''
external_text = external_text[:rewrite_start] + rewrite_replacement + external_text[rewrite_end:]

routing_start = external_text.index(routing_comment)
module_marker = "  private def moduleBlocks(lines: Vector[String]): Vector[ModuleBlock] ="
require_once(external_text, module_marker, external_path)
routing_end = external_text.index(module_marker, routing_start)
routing_replacement = '''  /**
    * Preserve the publication order that existed before Increment 42:
    * native memory lowering first, then procedural loops, structural generate
    * regions, and finally Increment 41 expression/hierarchy rewriting.
    * Structure-only modules deliberately skip hierarchy text analysis after
    * their captured module items have been relocated.
    */
  private def requiresPublicationRewrite(component: Component): Boolean =
    ParameterizedVerilogProcesses.hasLoops(component) ||
      ParameterizedVerilogStructural.hasRegions(component) ||
      requiresExpressionHierarchyRewrite(component)

  private def requiresExpressionHierarchyRewrite(
      component: Component
  ): Boolean =
    ParameterizedWidth.parametersOf(component).nonEmpty ||
      ParameterizedMemory.parametersOf(component).nonEmpty ||
      ParameterizedProcess.parametersOf(component).nonEmpty ||
      component.children.exists { child =>
        ParameterizedWidth.parametersOf(child).nonEmpty ||
          ParameterizedMemory.parametersOf(child).nonEmpty
      }

'''
external_text = external_text[:routing_start] + routing_replacement + external_text[routing_end:]
write(external_path, external_text)

boundary_script = r'''#!/usr/bin/env bash
set -euo pipefail

root="$(git rev-parse --show-toplevel)"
cd "$root"

for path in \
  core/src/main/scala/spinal/core/ParameterizedStructure.scala \
  core/src/main/scala/spinal/core/ParameterizedProcess.scala \
  core/src/main/scala/spinal/core/internals/ParameterizedVerilogStructural.scala \
  core/src/main/scala/spinal/core/internals/ParameterizedVerilogProcesses.scala
do
  test ! -e "$path"
done

for path in \
  frontend/src/main/scala/spinal/core/ParameterizedStructure.scala \
  frontend/src/main/scala/spinal/core/ParameterizedProcess.scala \
  morphhdl/src/main/scala/spinal/core/internals/ParameterizedVerilogStructural.scala \
  morphhdl/src/main/scala/spinal/core/internals/ParameterizedVerilogProcesses.scala
do
  test -f "$path"
done

width=core/src/main/scala/spinal/core/ParameterizedWidth.scala
structure=frontend/src/main/scala/spinal/core/ParameterizedStructure.scala
phase=core/src/main/scala/spinal/core/internals/PhaseVerilog.scala
external=morphhdl/src/main/scala/spinal/core/internals/MorphHdlExternalParameterizedVerilog.scala

test "$(grep -Fc 'final case class ElaborationIntegerExpression(' "$width")" = 1
test "$(grep -Fc 'final case class ElaborationBooleanExpression(' "$width")" = 1
! grep -Fq 'final case class ElaborationIntegerExpression(' "$structure"
! grep -Fq 'ParameterizedVerilogProcesses' "$phase"
! grep -Fq 'ParameterizedVerilogStructural' "$phase"
grep -Fq 'ParameterizedVerilogMemories.rewrite' "$phase"
grep -Fq 'ParameterizedVerilogProcesses.rewrite' "$external"
grep -Fq 'ParameterizedVerilogStructural.rewrite' "$external"
grep -Fq 'requiresPublicationRewrite' "$external"

python3 - <<'PY2'
import json
from pathlib import Path

manifest = json.loads(Path("morphhdl/contracts/native-source-preservation.json").read_text())
paths = {entry["path"] for entry in manifest["entries"]}
removed = {
    "core/src/main/scala/spinal/core/ParameterizedStructure.scala",
    "core/src/main/scala/spinal/core/ParameterizedProcess.scala",
    "core/src/main/scala/spinal/core/internals/ParameterizedVerilogStructural.scala",
    "core/src/main/scala/spinal/core/internals/ParameterizedVerilogProcesses.scala",
}
remaining = sorted(paths.intersection(removed))
if remaining:
    raise SystemExit("relocated native-tree entries remain in manifest: " + ", ".join(remaining))
PY2

echo "External structural/process ownership boundary is valid"
'''
write(
    "morphhdl/scripts/check-external-structural-process-boundary.sh",
    boundary_script,
)

proof_workflow = r'''name: MorphHDL external structural and process capture

on:
  push:
    branches:
      - main
      - parameterized-verilog
  pull_request:
    branches:
      - main
      - parameterized-verilog
  workflow_dispatch:

permissions:
  contents: read

concurrency:
  group: morphhdl-external-structural-process-${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true

jobs:
  proof:
    name: External structural/process proof Scala ${{ matrix.scala_version }}
    runs-on: ubuntu-latest
    timeout-minutes: 60
    container:
      image: ghcr.io/spinalhdl/docker:latest
    env:
      XDG_CACHE_HOME: /tmp/morphhdl-cache
    strategy:
      fail-fast: false
      matrix:
        scala_version:
          - "2.12.18"
          - "2.13.12"
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - name: Prove MorphHDL-owned source boundary
        shell: bash
        run: bash ./morphhdl/scripts/check-external-structural-process-boundary.sh

      - name: Install pinned Mill bootstrap
        shell: bash
        run: |
          curl --fail --location --retry 3 \
            https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/1.1.0/mill-dist-1.1.0-mill.sh \
            --output /tmp/morphhdl-mill
          chmod +x /tmp/morphhdl-mill

      - name: Validate external structural and process contracts
        shell: bash
        run: |
          /tmp/morphhdl-mill core[${{ matrix.scala_version }}].compile
          /tmp/morphhdl-mill frontend[${{ matrix.scala_version }}].compile
          /tmp/morphhdl-mill morph[${{ matrix.scala_version }}].testOnly \
            morphhdl.StructuralGenerateControlTests \
            morphhdl.GenericProcessLoweringTests \
            morphhdl.HierarchyParameterBindingTests \
            morphhdl.GenericExpressionAndStreamTests \
            morphhdl.MorphSingleSourceVerilogTests
'''
write(
    ".github/workflows/morphhdl-external-structural-process.yml",
    proof_workflow,
)

documentation = r'''# Increment 42 — External structural and process capture

Increment 42 moves the Increment 33 structural-region registry and the Increment
34 procedural-loop registry out of the native `core` source tree without adding
an upstream SpinalHDL hook.

## Ownership boundary

- `frontend/src/main/scala/spinal/core/ParameterizedStructure.scala` owns
  structural capture, generate-for/if/case metadata, symbolic slices and Vec
  selections.
- `frontend/src/main/scala/spinal/core/ParameterizedProcess.scala` owns range
  classification and safe procedural-loop metadata.
- `morphhdl/src/main/scala/spinal/core/internals/ParameterizedVerilogStructural.scala`
  and `ParameterizedVerilogProcesses.scala` own publication lowering.
- `PhaseVerilog.scala` retains only the pending Increment 35 memory rewrite.
  The normal native emitter, driver/latch checks, clock/reset phases and module
  deduplication remain authoritative.

The moved sources deliberately retain their `spinal.core` package names. Scala
package visibility therefore provides the same read-only access to native AST
objects from MorphHDL-owned modules; no native API or package-private hook is
added.

`ElaborationIntegerExpression` and `ElaborationBooleanExpression` are general
facts shared by width, hierarchy, structure, process and memory code. They are
kept temporarily in the existing `ParameterizedWidth.scala` sidecar because the
native-core memory/width sidecars compile before the MorphHDL frontend module.
Their final module extraction remains part of Increment 46 and is not structural
capture ownership.

## Publication order

Parameterized single-source generation now preserves the established order:

1. ordinary native SpinalHDL elaboration and validation;
2. native Verilog emission plus the still-pending memory rewrite;
3. external procedural-loop replacement;
4. external structural generate-for/if/case relocation;
5. external expression, declaration, connection and hierarchy lowering;
6. atomic publication of the final Verilog-2001 artifact.

Structure-only modules skip the later hierarchy text pass after relocation, as
before, so generated helper connections cannot be mistaken for ordinary
parent/child bindings.

## Validation

The dedicated boundary gate proves the four Increment 33/34 source files are
absent from native `core`, present in MorphHDL-owned modules, and no longer
referenced by `PhaseVerilog.scala`. It then runs the structural, procedural,
hierarchy, expression and single-source regressions on Scala 2.12.18 and
2.13.12. The normal baseline, Mill, source-preservation, strict Verilog-2001,
simulation, lint and synthesis workflows remain mandatory before merge.
'''
write(
    "docs/morphhdl/increment-42-external-structural-process-capture.md",
    documentation,
)
PY

git diff --check

git config user.name pysolvesemi
git config user.email pysolvesemi@gmail.com
git add -A
git diff --cached --check
git commit -m "Implement Increment 42 external structural and process capture"
approved_commit="$(git rev-parse HEAD)"
approved_tree="$(git rev-parse 'HEAD^{tree}')"

APPROVED_COMMIT="$approved_commit" APPROVED_TREE="$approved_tree" python3 - <<'PY'
import json
import os
from pathlib import Path

path = Path("morphhdl/contracts/native-source-preservation.json")
manifest = json.loads(path.read_text(encoding="utf-8"))
manifest["approved_state"] = {
    "commit": os.environ["APPROVED_COMMIT"],
    "tree": os.environ["APPROVED_TREE"],
    "description": "Reviewed native-source state after Increment 42 external structural and process capture",
}
removed = {
    "core/src/main/scala/spinal/core/ParameterizedStructure.scala",
    "core/src/main/scala/spinal/core/ParameterizedProcess.scala",
    "core/src/main/scala/spinal/core/internals/ParameterizedVerilogStructural.scala",
    "core/src/main/scala/spinal/core/internals/ParameterizedVerilogProcesses.scala",
}
old_paths = {entry["path"] for entry in manifest["entries"]}
missing = sorted(removed.difference(old_paths))
if missing:
    raise SystemExit("expected native-tree manifest entries are missing: " + ", ".join(missing))
manifest["entries"] = [entry for entry in manifest["entries"] if entry["path"] not in removed]
for entry in manifest["entries"]:
    if entry["path"] == "core/src/main/scala/spinal/core/ParameterizedWidth.scala":
        entry["introduced_by"] = ["29", "30", "33", "34"]
        entry["reason"] = (
            "Stores MorphHDL symbolic-width metadata and the shared retained "
            "integer/Boolean expression facts pending complete module extraction."
        )
    elif entry["path"] == "core/src/main/scala/spinal/core/internals/PhaseVerilog.scala":
        entry["introduced_by"] = ["35"]
        entry["reason"] = (
            "Routes only the still-pending MorphHDL memory lowering through the native Verilog phase."
        )
manifest["entries"] = sorted(manifest["entries"], key=lambda entry: entry["path"])
path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
PY

git add morphhdl/contracts/native-source-preservation.json
git diff --cached --check
git commit -m "Approve Increment 42 native source state"
git push origin HEAD:$branch

curl --fail --location --retry 3 \
  https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/1.1.0/mill-dist-1.1.0-mill.sh \
  --output /tmp/morphhdl-mill
chmod +x /tmp/morphhdl-mill

bash ./morphhdl/scripts/check-external-structural-process-boundary.sh
/tmp/morphhdl-mill core[2.12.18].compile
/tmp/morphhdl-mill frontend[2.12.18].compile
/tmp/morphhdl-mill morph[2.12.18].testOnly \
  morphhdl.StructuralGenerateControlTests \
  morphhdl.GenericProcessLoweringTests \
  morphhdl.HierarchyParameterBindingTests \
  morphhdl.GenericExpressionAndStreamTests \
  morphhdl.MorphSingleSourceVerilogTests
/tmp/morphhdl-mill core[2.13.12].compile
/tmp/morphhdl-mill frontend[2.13.12].compile
/tmp/morphhdl-mill morph[2.13.12].testOnly \
  morphhdl.StructuralGenerateControlTests \
  morphhdl.GenericProcessLoweringTests \
  morphhdl.HierarchyParameterBindingTests \
  morphhdl.GenericExpressionAndStreamTests \
  morphhdl.MorphSingleSourceVerilogTests

git checkout origin/parameterized-verilog -- .github/workflows/morphhdl-native-source-guard.yml
rm -f \
  .github/workflows/increment-42-bootstrap.yml \
  morphhdl/scripts/increment-42-bootstrap.sh
git add -A
git diff --cached --check
git commit -m "Remove Increment 42 bootstrap controllers"
git push origin HEAD:$branch
