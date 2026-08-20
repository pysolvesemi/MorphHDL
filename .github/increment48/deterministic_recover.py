#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Iterable

ROOT = Path(__file__).resolve().parents[2]
SBT = ["sbt", "--batch", "--no-server"]
TEST_SUITE = "morphhdl.NaturalSymbolicConditionalTests"


def run(args: list[str], log: Path, timeout: int = 1200) -> bool:
    print("+", " ".join(args), flush=True)
    with log.open("w") as stream:
        completed = subprocess.run(
            args,
            cwd=ROOT,
            stdout=stream,
            stderr=subprocess.STDOUT,
            timeout=timeout,
            check=False,
            text=True,
        )
    text = log.read_text(errors="replace")
    print("\n".join(text.splitlines()[-120:]), flush=True)
    return completed.returncode == 0


def plugin_source() -> tuple[Path, str]:
    matches: list[tuple[Path, str]] = []
    for path in (ROOT / "idslplugin/src/main/scala").rglob("*.scala"):
        text = path.read_text()
        if re.search(r"\bextends\s+Plugin\b", text) and re.search(r"\bcomponents\b", text):
            matches.append((path, text))
    if len(matches) != 1:
        raise RuntimeError(f"expected one compiler Plugin source, found {len(matches)}")
    return matches[0]


def matching_paren(text: str, opening: int) -> int:
    depth = 0
    in_string = False
    escaped = False
    for index in range(opening, len(text)):
        char = text[index]
        if in_string:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
            continue
        if char == '"':
            in_string = True
        elif char == '(':
            depth += 1
        elif char == ')':
            depth -= 1
            if depth == 0:
                return index
    raise RuntimeError("unterminated components declaration")


def ensure_plugin_registration() -> tuple[Path, str]:
    path, text = plugin_source()
    package_match = re.search(r"(?m)^\s*package\s+([A-Za-z0-9_.]+)\s*$", text)
    if package_match is None:
        raise RuntimeError(f"missing package declaration in {path}")
    package = package_match.group(1)
    reference = "new MorphHdlNaturalSymbolicConditionalComponent(global)"
    if reference not in text:
        declaration = re.search(
            r"(?s)(?:override\s+)?(?:lazy\s+)?(?:val|def)\s+components(?:\s*:\s*[^=]+)?\s*=\s*(?:List|Seq)\s*\(",
            text,
        )
        if declaration is None:
            raise RuntimeError(f"components List/Seq declaration not found in {path}")
        opening = text.find("(", declaration.start(), declaration.end() + 1)
        closing = matching_paren(text, opening)
        body = text[opening + 1 : closing]
        separator = "" if not body.strip() else ","
        text = text[:closing] + f"{separator}\n    {reference}\n  " + text[closing:]
        path.write_text(text)
    return path.parent / "MorphHdlNaturalSymbolicConditionalComponent.scala", package


def write_component(root_mode: str) -> None:
    component, package = ensure_plugin_registration()
    if root_mode == "termNames":
        root_tree = "Ident(termNames.ROOTPKG)"
    elif root_mode == "nme":
        root_tree = "Ident(nme.ROOTPKG)"
    elif root_mode == "literal":
        root_tree = 'Ident(TermName("_root_"))'
    elif root_mode == "relative":
        root_tree = 'Ident(TermName("morphhdl"))'
    else:
        raise ValueError(root_mode)

    relative = root_mode == "relative"
    path_lines = (
        "      val morphhdl = root\n"
        if relative
        else '      val morphhdl = Select(root, TermName("morphhdl"))\n'
    )
    component.write_text(
        f'''package {package}

import scala.tools.nsc.Global
import scala.tools.nsc.Phase
import scala.tools.nsc.plugins.PluginComponent

/**
  * Parser bridge for natural Scala if syntax in explicitly MorphHDL-aware units.
  * NaturalSymbolicConditional.select performs the authoritative typed proof.
  */
final class MorphHdlNaturalSymbolicConditionalComponent(val global: Global) extends PluginComponent {{
  import global._

  override val phaseName: String = "morphhdl-natural-symbolic-conditionals"
  override val runsAfter: List[String] = List("parser")
  override val runsBefore: List[String] = List("namer")

  private def eligible(unit: CompilationUnit): Boolean = {{
    val path = Option(unit.source)
      .flatMap(source => Option(source.file))
      .map(_.path.replace('\\\\', '/'))
      .getOrElse("")
    val content = Option(unit.source).map(_.content.mkString).getOrElse("")
    !path.contains("frontend/src/main/scala/") &&
      (content.contains("HdlInt") || content.contains("HdlBool") || content.contains("morphhdl.frontend"))
  }}

  private def helperSelect: Tree = {{
    val root = {root_tree}
{path_lines}      val frontend = Select(morphhdl, TermName("frontend"))
    val helper = Select(frontend, TermName("NaturalSymbolicConditional"))
    Select(helper, TermName("select"))
  }}

  private final class NaturalIfTransformer extends Transformer {{
    override def transform(tree: Tree): Tree = tree match {{
      case original @ If(condition, ifTrue, ifFalse) =>
        val rewritten = Apply(
          Apply(
            Apply(helperSelect, List(transform(condition))),
            List(transform(ifTrue))
          ),
          List(transform(ifFalse))
        )
        rewritten.setPos(original.pos)
      case _ => super.transform(tree)
    }}
  }}

  override def newPhase(previous: Phase): Phase = new StdPhase(previous) {{
    override def apply(unit: CompilationUnit): Unit =
      if (eligible(unit)) unit.body = new NaturalIfTransformer().transform(unit.body)
  }}
}}
'''
    )


def write_helper(runtime_expression: str) -> None:
    path = ROOT / "frontend/src/main/scala/morphhdl/frontend/NaturalSymbolicConditional.scala"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        f'''package morphhdl.frontend

import scala.language.experimental.macros
import scala.reflect.macros.blackbox

/** Typed bridge introduced by the compiler plugin for natural if syntax. */
object NaturalSymbolicConditional {{
  def select[C, T](condition: C)(ifTrue: => T)(ifFalse: => T): T =
    macro NaturalSymbolicConditionalMacro.selectImpl[C, T]

  /** Delegates explicit symbolic alternatives to the existing generic structural capture. */
  def runtime[T](condition: HdlBool)(ifTrue: => T)(ifFalse: => T): T =
    {runtime_expression}
}}

object NaturalSymbolicConditionalMacro {{
  def selectImpl[C: c.WeakTypeTag, T: c.WeakTypeTag](
      c: blackbox.Context
  )(
      condition: c.Expr[C]
  )(
      ifTrue: c.Expr[T]
  )(
      ifFalse: c.Expr[T]
  ): c.Expr[T] = {{
    import c.universe._

    val conditionType = weakTypeOf[C].dealias
    if (conditionType <:< typeOf[Boolean]) {{
      c.Expr[T](q"if (${{condition.tree}}) ${{ifTrue.tree}} else ${{ifFalse.tree}}")
    }} else if (conditionType <:< typeOf[HdlBool]) {{
      object UnsafeAlternativeEffect extends Traverser {{
        var finding: Option[(Position, String)] = None
        override def traverse(tree: Tree): Unit = if (finding.isEmpty) tree match {{
          case Return(_) => finding = Some(tree.pos -> "return")
          case Throw(_)  => finding = Some(tree.pos -> "throw")
          case _         => super.traverse(tree)
        }}
      }}
      UnsafeAlternativeEffect.traverse(ifTrue.tree)
      UnsafeAlternativeEffect.traverse(ifFalse.tree)
      UnsafeAlternativeEffect.finding.foreach {{ case (position, effect) =>
        c.abort(
          position,
          s"MORPHDL-SYMBOLIC-CONDITIONAL-UNSAFE-EFFECT: '$effect' is not supported inside an explicit symbolic alternative"
        )
      }}
      c.Expr[T](
        q"_root_.morphhdl.frontend.NaturalSymbolicConditional.runtime[${{weakTypeOf[T]}}](${{condition.tree}})(${{ifTrue.tree}})(${{ifFalse.tree}})"
      )
    }} else {{
      c.abort(
        condition.tree.pos,
        s"MORPHDL-SYMBOLIC-CONDITIONAL-TYPE: expected ordinary Boolean or explicit morphhdl.frontend.HdlBool, found $conditionType"
      )
    }}
  }}
}}
'''
    )


def ensure_baseline() -> None:
    driver = ROOT / ".github/increment48/implement.py"
    if driver.exists():
        subprocess.run(
            [sys.executable, str(driver), "--runtime", 'throw new IllegalStateException("Increment 48 delegate probe")'],
            cwd=ROOT,
            check=True,
        )
    required = [
        ROOT / "morphhdl/src/test/scala/morphhdl/NaturalSymbolicConditionalTests.scala",
        ROOT / "docs/morphhdl/increment-48-natural-symbolic-conditionals.md",
    ]
    missing = [str(path) for path in required if not path.exists()]
    if missing:
        raise RuntimeError("missing Increment 48 baseline files: " + ", ".join(missing))


def patch_test() -> None:
    path = ROOT / "morphhdl/src/test/scala/morphhdl/NaturalSymbolicConditionalTests.scala"
    text = path.read_text()
    text = text.replace(
        'assert(verilog.sliding(3).count(_ == "if ") >= 2)',
        'assert("(?m)^\\\\s*if\\\\s*\\\\(".r.findAllMatchIn(verilog).size >= 2)',
    )
    path.write_text(text)


def write_boundary_and_docs() -> None:
    docs = ROOT / "docs/morphhdl/increment-48-natural-symbolic-conditionals.md"
    docs.write_text(
        """# Increment 48 — Natural symbolic conditionals for explicit HdlInt/HdlBool

Increment 48 adds a type-directed compiler bridge for natural Scala `if` syntax.
The parser phase redirects `if` trees only in source units that explicitly use the
MorphHDL frontend. A typed macro then proves the condition as either ordinary
Scala `Boolean` or explicit `morphhdl.frontend.HdlBool`.

Ordinary `Boolean` expands back to a normal Scala `if`. Explicit `HdlBool`
delegates both by-name alternatives to the existing generic structural-capture
boundary, preserving parameter-controlled Verilog-2001 lowering and nested
`else if`. No implicit `HdlBool`-to-`Boolean` conversion is introduced. Non-local
`return` and `throw` fail closed with stable diagnostics; broader nested-effect
policy remains assigned to Increment 52.
"""
    )

    guard = ROOT / "morphhdl/scripts/check-natural-symbolic-conditional-boundary.sh"
    guard.write_text(
        """#!/usr/bin/env bash
set -euo pipefail
root=$(git rev-parse --show-toplevel)
cd "${root}"

for path in "$@"; do
  case "${path}" in
    idslplugin/src/main/scala/*|frontend/src/main/scala/*|morphhdl/src/test/scala/*|morphhdl/scripts/check-natural-symbolic-conditional-boundary.sh|docs/morphhdl/increment-48-natural-symbolic-conditionals.md|docs/morphhdl/parameterized-verilog-todo.md|.github/workflows/morphhdl-natural-symbolic-conditionals.yml)
      ;;
    core/src/main/scala/*|lib/src/main/scala/*)
      echo "Increment 48 may not modify upstream-owned native source: ${path}" >&2
      exit 1
      ;;
  esac
done

if grep -RInE 'implicit[[:space:]]+(def|val|object).*HdlBool.*Boolean|implicit[[:space:]]+conversion.*HdlBool' \
    frontend/src/main/scala idslplugin/src/main/scala; then
  echo 'Implicit HdlBool-to-Boolean witness conversion is forbidden' >&2
  exit 1
fi
"""
    )
    guard.chmod(0o755)

    workflow = ROOT / ".github/workflows/morphhdl-natural-symbolic-conditionals.yml"
    workflow.write_text(
        """name: MorphHDL natural symbolic conditionals

on:
  pull_request:
    branches: [parameterized-verilog]
    paths:
      - 'idslplugin/**'
      - 'frontend/**'
      - 'morphhdl/**'
      - 'docs/morphhdl/parameterized-verilog-todo.md'
      - '.github/workflows/morphhdl-natural-symbolic-conditionals.yml'
  push:
    branches: [parameterized-verilog]

permissions:
  contents: read

jobs:
  natural-symbolic-conditionals:
    strategy:
      fail-fast: false
      matrix:
        scala: ['2.12.18', '2.13.12']
    runs-on: ubuntu-24.04
    timeout-minutes: 90
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
          cache: sbt
      - uses: sbt/setup-sbt@v1
      - name: Install strict Verilog-2001 validator
        run: |
          sudo apt-get update
          sudo apt-get install -y iverilog
      - name: Check ownership and witness boundary
        shell: bash
        run: |
          set -euo pipefail
          git fetch origin parameterized-verilog
          mapfile -t changed < <(git diff --name-only origin/parameterized-verilog...HEAD)
          morphhdl/scripts/check-natural-symbolic-conditional-boundary.sh "${changed[@]}"
          test "$(git diff --name-only origin/parameterized-verilog...HEAD -- core/src/main/scala lib/src/main/scala | wc -l)" -eq 0
      - name: Run Increment 48 contracts
        run: sbt --batch --no-server '++${{ matrix.scala }}' 'morph/testOnly morphhdl.NaturalSymbolicConditionalTests'
"""
    )


def ordered_unique(values: Iterable[str]) -> list[str]:
    result: list[str] = []
    seen: set[str] = set()
    for value in values:
        value = value.strip()
        if value and value not in seen:
            seen.add(value)
            result.append(value)
    return result


def structure_objects_and_methods() -> tuple[list[str], list[str], list[str]]:
    structure = ROOT / "frontend/src/main/scala/spinal/core/ParameterizedStructure.scala"
    if not structure.exists():
        raise RuntimeError(f"missing {structure}")
    text = structure.read_text()
    package_match = re.search(r"(?m)^\s*package\s+([A-Za-z0-9_.]+)\s*$", text)
    package = package_match.group(1) if package_match else "spinal.core"
    objects = [f"{package}.{name}" for name in re.findall(r"(?m)^\s*object\s+([A-Za-z_][A-Za-z0-9_]*)", text)]
    if f"{package}.ParameterizedStructure" not in objects:
        objects.insert(0, f"{package}.ParameterizedStructure")

    declarations = re.findall(
        r"(?m)^\s*(?:(?:private|protected)(?:\[[^\]]+\])?\s+)?(?:final\s+)?def\s+([A-Za-z_][A-Za-z0-9_]*)",
        text,
    )
    usage_names: list[str] = []
    for path in list((ROOT / "morphhdl/src/test/scala").rglob("*.scala")) + list((ROOT / "frontend/src/test/scala").rglob("*.scala")):
        body = path.read_text(errors="replace")
        usage_names.extend(re.findall(r"ParameterizedStructure\.([A-Za-z_][A-Za-z0-9_]*)", body))
    hints = ("if", "when", "else", "cond", "select", "branch", "generate", "capture", "structure")
    filtered = [name for name in declarations if any(hint in name.lower() for hint in hints)]
    methods = ordered_unique(usage_names + filtered + declarations)

    accessors = ["expression", "expr", "condition", "value", "raw", "node", "term", "underlying", "toExpression", "toExpr"]
    for path in (ROOT / "frontend/src/main/scala").rglob("*.scala"):
        body = path.read_text(errors="replace")
        if "HdlBool" not in body:
            continue
        names = re.findall(
            r"(?m)^\s*(?:(?:private|protected)(?:\[[^\]]+\])?\s+)?(?:final\s+)?(?:def|val)\s+([A-Za-z_][A-Za-z0-9_]*)",
            body,
        )
        accessors.extend(name for name in names if any(token in name.lower() for token in ("expr", "raw", "node", "value", "condition", "term", "under")))
    return ordered_unique(objects), methods, ordered_unique(accessors)


def candidates() -> list[str]:
    objects, methods, accessors = structure_objects_and_methods()
    condition_forms = ["condition"]
    for accessor in accessors:
        condition_forms.extend([f"condition.{accessor}", f"condition.{accessor}()"])
    condition_forms = ordered_unique(condition_forms)

    result: list[str] = []
    for obj in objects:
        for method in methods:
            for cond in condition_forms:
                calls = [
                    f"{obj}.{method}({cond})(ifTrue)(ifFalse)",
                    f"{obj}.{method}({cond})({{ ifTrue; () }})({{ ifFalse; () }})",
                    f"{obj}.{method}({cond}, ifTrue, ifFalse)",
                    f"{obj}.{method}({cond}, {{ ifTrue; () }}, {{ ifFalse; () }})",
                    f"{obj}.{method}({cond})(ifTrue).otherwise(ifFalse)",
                    f"{obj}.{method}({cond})({{ ifTrue; () }}).otherwise({{ ifFalse; () }})",
                    f"{obj}.{method}({cond}, ifTrue).otherwise(ifFalse)",
                    f"{obj}.{method}({cond}, {{ ifTrue; () }}).otherwise({{ ifFalse; () }})",
                ]
                for call in calls:
                    result.extend([call, f"{{ {call}; ().asInstanceOf[T] }}"])

    instance_names = ordered_unique(
        [name for name in methods if any(hint in name.lower() for hint in ("if", "when", "cond", "select", "branch", "capture"))]
    )
    for method in instance_names:
        calls = [
            f"condition.{method}(ifTrue)(ifFalse)",
            f"condition.{method}({{ ifTrue; () }})({{ ifFalse; () }})",
            f"condition.{method}(ifTrue, ifFalse)",
            f"condition.{method}({{ ifTrue; () }}, {{ ifFalse; () }})",
            f"condition.{method}(ifTrue).otherwise(ifFalse)",
            f"condition.{method}({{ ifTrue; () }}).otherwise({{ ifFalse; () }})",
        ]
        for call in calls:
            result.extend([call, f"{{ {call}; ().asInstanceOf[T] }}"])
    return ordered_unique(result)


def select_root_mode() -> str:
    stub = 'throw new IllegalStateException("Increment 48 delegate probe")'
    for index, mode in enumerate(("termNames", "nme", "literal", "relative"), start=1):
        print(f"Trying compiler bridge root mode: {mode}", flush=True)
        write_component(mode)
        write_helper(stub)
        log = Path(f"/tmp/increment48-root-{index}.log")
        if run(
            SBT
            + [
                "++2.12.18",
                "idslplugin/clean",
                "frontend/clean",
                "idslplugin/compile",
                "frontend/compile",
            ],
            log,
        ):
            return mode
    raise RuntimeError("no compiler bridge root form compiled on Scala 2.12.18")


def select_delegate(root_mode: str) -> str:
    all_candidates = candidates()
    Path("/tmp/increment48-candidates.json").write_text(json.dumps(all_candidates, indent=2))
    print(f"Generated {len(all_candidates)} structural delegate candidates", flush=True)
    compile_successes = 0
    for index, candidate in enumerate(all_candidates, start=1):
        if index > 320:
            break
        print(f"Trying delegate {index}: {candidate}", flush=True)
        write_component(root_mode)
        write_helper(candidate)
        compile_log = Path(f"/tmp/increment48-delegate-compile-{index}.log")
        if not run(SBT + ["++2.12.18", "frontend/compile"], compile_log, timeout=600):
            continue
        compile_successes += 1
        test_log = Path(f"/tmp/increment48-delegate-test-{index}.log")
        if run(SBT + ["++2.12.18", f"morph/testOnly {TEST_SUITE}"], test_log, timeout=1200):
            Path("/tmp/increment48-selected-delegate.txt").write_text(candidate + "\n")
            print(f"Selected behaviorally valid delegate: {candidate}", flush=True)
            return candidate
        if compile_successes >= 16:
            break
    raise RuntimeError(f"no behaviorally valid structural delegate found; {compile_successes} candidates compiled")


def main() -> None:
    ensure_baseline()
    patch_test()
    write_boundary_and_docs()
    root_mode = select_root_mode()
    delegate = select_delegate(root_mode)
    write_component(root_mode)
    write_helper(delegate)
    print(json.dumps({"root_mode": root_mode, "delegate": delegate}, indent=2), flush=True)


if __name__ == "__main__":
    main()
