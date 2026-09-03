#!/usr/bin/env python3
"""Fail-closed source guard for the WA-02 canonical IR adapter boundary."""

from __future__ import annotations

import argparse
import re
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Sequence


@dataclass(frozen=True)
class Rule:
    code: str
    message: str
    pattern: re.Pattern[str]


IMPLEMENTATION_RULES: tuple[Rule, ...] = (
    Rule(
        "WA02-COMPONENT-SPECIAL-CASE",
        "pass implementation must not mention StreamFifo, StreamFifoCC, or the shared witness class",
        re.compile(r"\b(?:StreamFifo(?:CC)?|ParameterizedStreamFifo)\b"),
    ),
    Rule(
        "WA02-MODULE-NAME-RECOGNITION",
        "pass implementation must not inspect canonical module logical names",
        re.compile(r"\blogicalName\b"),
    ),
    Rule(
        "WA02-SPINAL-IMPLEMENTATION-DEPENDENCY",
        "pass implementation must depend on canonical MorphHDL IR, not spinal implementation classes",
        re.compile(r"^\s*import\s+spinal\.", re.MULTILINE),
    ),
    Rule(
        "WA02-FILE-TEXT-INGRESS",
        "pass implementation must not read files or text streams",
        re.compile(
            r"\b(?:scala\.io\.Source|java\.io\.(?:File|Reader|InputStream|BufferedReader)|"
            r"java\.nio\.file\.(?:Path|Paths|Files))\b"
        ),
    ),
    Rule(
        "WA02-REGEX-MATCHING",
        "pass implementation must not use regular expressions for candidate discovery",
        re.compile(
            r"\b(?:scala\.util\.matching\.Regex|java\.util\.regex|Pattern\.compile)\b|"
            r"(?:\"\"\".*?\"\"\"|\"(?:\\.|[^\"\\])*\")\s*\.r\b",
            re.DOTALL,
        ),
    ),
    Rule(
        "WA02-GENERATED-HDL-PARSER",
        "pass implementation must not parse or reconstruct generated HDL text",
        re.compile(
            r"\b(?:parseVerilog|parseGeneratedHdl|generatedVerilog|emittedVerilog|verilogText)\b",
            re.IGNORECASE,
        ),
    ),
    Rule(
        "WA02-EMITTED-NAME-RECOGNITION",
        "pass implementation must not recognize emitted temporary names",
        re.compile(r"_zz_"),
    ),
)

ADAPTER_ONLY_RULES: tuple[Rule, ...] = (
    Rule(
        "WA02-DUPLICATE-CANONICAL-IR",
        "adapter must not declare a duplicate canonical IR model",
        re.compile(
            r"\b(?:sealed\s+)?(?:case\s+)?(?:class|trait|object)\s+"
            r"(?:Design|Module|Declaration|Driver|RtlExpr|PackedType|Parameter|Scope|Observability)\b"
        ),
    ),
)

REQUIRED_ADAPTER_MARKERS: tuple[str, ...] = (
    "morphhdl.ir.v1.CanonicalIrSchema",
    "morphhdl.ir.v1.CanonicalIrValidator",
    "CanonicalIrValidator.validate",
    "design: Design",
)

# These expressions validate the meaning of the roadmap rule rather than one
# punctuation-sensitive spelling. Line wrapping and equivalent wording must not
# make the guard itself component-specific or brittle.
REQUIRED_ROADMAP_RULES: tuple[tuple[str, re.Pattern[str]], ...] = (
    (
        "a component-generic canonical-IR rule",
        re.compile(r"(?:component-generic|generic(?:ally)?\s+over\s+(?:the\s+)?canonical\s+IR)", re.IGNORECASE),
    ),
    (
        "an explicit StreamFifo non-special-case rule",
        re.compile(
            r"(?:must\s+not|may\s+not|without\s+recognizing)[\s\S]{0,320}\bStreamFifo(?:CC)?\b",
            re.IGNORECASE,
        ),
    ),
    (
        "a module/component-name non-recognition rule",
        re.compile(
            r"(?:must\s+not|may\s+not|without\s+recognizing)[\s\S]{0,320}"
            r"\b(?:module(?:/class|\s+or\s+component)?\s+name|component\s+name)\b",
            re.IGNORECASE,
        ),
    ),
    (
        "a source-filename non-recognition rule",
        re.compile(
            r"(?:must\s+not|may\s+not|without\s+recognizing)[\s\S]{0,320}\bsource\s+filename\b",
            re.IGNORECASE,
        ),
    ),
)

REQUIRED_WITNESS_MARKERS: tuple[str, ...] = (
    "final class ParameterizedStreamFifo",
    "StreamFifo(",
    "depth.asElabInt",
    "HdlInt.param(",
    '"WIDTH"',
    '"DEPTH"',
    "MorphVerilog",
)


def scan_text(path: Path, text: str, rules: Sequence[Rule]) -> list[str]:
    failures: list[str] = []
    for rule in rules:
        match = rule.pattern.search(text)
        if match is not None:
            line = text.count("\n", 0, match.start()) + 1
            failures.append(f"{path}:{line}: {rule.code}: {rule.message}")
    return failures


def scala_sources(root: Path) -> list[Path]:
    source_root = root / "morphhdl-passes" / "src" / "main" / "scala" / "morphhdl" / "passes"
    return sorted(path for path in source_root.rglob("*.scala") if "/api/" not in path.as_posix())


def adapter_sources(root: Path) -> list[Path]:
    adapter_root = (
        root
        / "morphhdl-passes"
        / "src"
        / "main"
        / "scala"
        / "morphhdl"
        / "passes"
        / "adapter"
    )
    return sorted(adapter_root.rglob("*.scala"))


def check_roadmap(root: Path) -> list[str]:
    roadmap = root / "morphhdl-passes" / "morphhdl-ir-wire-assignment-passes-todo.md"
    if not roadmap.is_file():
        return [f"WA02-ROADMAP-MISSING: {roadmap.relative_to(root)}"]

    roadmap_text = roadmap.read_text(encoding="utf-8")
    failures: list[str] = []
    for description, pattern in REQUIRED_ROADMAP_RULES:
        if pattern.search(roadmap_text) is None:
            failures.append(
                f"WA02-GENERICITY-RULE-MISSING: roadmap is missing {description}"
            )
    return failures


def check_witness(root: Path) -> list[str]:
    witness = root / "morphhdl-passes" / "examples" / "ParameterizedStreamFifo.scala"
    if not witness.is_file():
        return [f"WA02-WITNESS-MISSING: {witness.relative_to(root)}"]

    witness_text = witness.read_text(encoding="utf-8")
    failures: list[str] = []
    for marker in REQUIRED_WITNESS_MARKERS:
        if marker not in witness_text:
            failures.append(
                f"WA02-WITNESS-CONTRACT-MISSING: shared witness is missing required marker {marker!r}"
            )

    implementation_root = (
        root / "morphhdl-passes" / "src" / "main" / "scala" / "morphhdl" / "passes"
    )
    try:
        witness.relative_to(implementation_root)
    except ValueError:
        pass
    else:
        failures.append(
            "WA02-WITNESS-IN-IMPLEMENTATION: component fixture must remain outside pass implementation sources"
        )
    return failures


def check_repository(root: Path) -> list[str]:
    failures: list[str] = []
    implementation_paths = scala_sources(root)
    adapter_paths = adapter_sources(root)

    if not adapter_paths:
        failures.append("WA02-ADAPTER-MISSING: no canonical adapter Scala source was found")
        return failures

    for path in implementation_paths:
        text = path.read_text(encoding="utf-8")
        failures.extend(scan_text(path.relative_to(root), text, IMPLEMENTATION_RULES))

    combined_adapter = "\n".join(path.read_text(encoding="utf-8") for path in adapter_paths)
    for path in adapter_paths:
        text = path.read_text(encoding="utf-8")
        failures.extend(scan_text(path.relative_to(root), text, ADAPTER_ONLY_RULES))

    for marker in REQUIRED_ADAPTER_MARKERS:
        if marker not in combined_adapter:
            failures.append(
                f"WA02-CANONICAL-BINDING-MISSING: adapter source is missing required marker {marker!r}"
            )

    failures.extend(check_roadmap(root))
    failures.extend(check_witness(root))

    workflow = root / ".github" / "workflows" / "morphhdl-passes.yml"
    if not workflow.is_file():
        failures.append(f"WA02-WORKFLOW-MISSING: {workflow.relative_to(root)}")
    else:
        workflow_text = workflow.read_text(encoding="utf-8")
        script_name = "check-wa02-adapter-boundary.py"
        if workflow_text.count(script_name) < 2 or "--self-test" not in workflow_text:
            failures.append(
                "WA02-WORKFLOW-GUARD-MISSING: workflow must run the source guard and its self-test"
            )

    return sorted(failures)


def expect_clean(text: str) -> None:
    failures = scan_text(Path("Allowed.scala"), text, IMPLEMENTATION_RULES + ADAPTER_ONLY_RULES)
    if failures:
        raise AssertionError("allowed adapter source was rejected:\n" + "\n".join(failures))


def expect_rejected(text: str, code: str) -> None:
    failures = scan_text(Path("Mutant.scala"), text, IMPLEMENTATION_RULES + ADAPTER_ONLY_RULES)
    if not any(code in failure for failure in failures):
        raise AssertionError(f"mutation was not rejected by {code}: {text!r}")


def expect_roadmap_rule(text: str, description: str) -> None:
    rule = next(pattern for label, pattern in REQUIRED_ROADMAP_RULES if label == description)
    if rule.search(text) is None:
        raise AssertionError(f"valid roadmap wording was rejected for {description}: {text!r}")


def run_self_test() -> None:
    expect_clean(
        """package morphhdl.passes.adapter
import morphhdl.ir.v1.Design
object Adapter { def bind(design: Design) = design.modules.map(_.id) }
"""
    )
    mutations = (
        ("val selected = StreamFifo", "WA02-COMPONENT-SPECIAL-CASE"),
        ("val selected = ParameterizedStreamFifo", "WA02-COMPONENT-SPECIAL-CASE"),
        ("design.modules.filter(_.logicalName == \"special\")", "WA02-MODULE-NAME-RECOGNITION"),
        ("import spinal.core.Component", "WA02-SPINAL-IMPLEMENTATION-DEPENDENCY"),
        ("scala.io.Source.fromFile(\"out.v\")", "WA02-FILE-TEXT-INGRESS"),
        ("val matcher = \"assign.*\".r", "WA02-REGEX-MATCHING"),
        ("parseVerilog(verilogText)", "WA02-GENERATED-HDL-PARSER"),
        ("val emitted = \"_zz_1\"", "WA02-EMITTED-NAME-RECOGNITION"),
        ("final case class Declaration(name: String)", "WA02-DUPLICATE-CANONICAL-IR"),
    )
    for text, code in mutations:
        expect_rejected(text, code)

    expect_roadmap_rule(
        "Passes are component-generic over canonical IR.",
        "a component-generic canonical-IR rule",
    )
    expect_roadmap_rule(
        "The implementation must not special-case StreamFifo.",
        "an explicit StreamFifo non-special-case rule",
    )
    expect_roadmap_rule(
        "The implementation must not inspect a module or component name.",
        "a module/component-name non-recognition rule",
    )
    expect_roadmap_rule(
        "The implementation must not inspect a source filename.",
        "a source-filename non-recognition rule",
    )

    with tempfile.TemporaryDirectory(prefix="morphhdl-wa02-") as directory:
        root = Path(directory)
        adapter = (
            root
            / "morphhdl-passes"
            / "src"
            / "main"
            / "scala"
            / "morphhdl"
            / "passes"
            / "adapter"
        )
        adapter.mkdir(parents=True)
        (adapter / "Allowed.scala").write_text(
            "package morphhdl.passes.adapter\nimport morphhdl.ir.v1.Design\n",
            encoding="utf-8",
        )
        if not adapter_sources(root):
            raise AssertionError("adapter source discovery failed")

    print("WA-02 adapter boundary self-tests passed.")


def parse_args(argv: Iterable[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", type=Path)
    parser.add_argument("--self-test", action="store_true")
    return parser.parse_args(list(argv))


def main(argv: Iterable[str] = sys.argv[1:]) -> int:
    args = parse_args(argv)
    if args.self_test:
        run_self_test()
        return 0

    if args.repo_root is None:
        root = Path(__file__).resolve().parents[2]
    else:
        root = args.repo_root.resolve()

    failures = check_repository(root)
    if failures:
        print("WA-02 canonical adapter boundary failed:", file=sys.stderr)
        for failure in failures:
            print(f"  - {failure}", file=sys.stderr)
        return 1

    print("WA-02 canonical adapter boundary passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
