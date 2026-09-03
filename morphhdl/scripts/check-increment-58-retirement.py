#!/usr/bin/env python3
"""Enforce Increment 58 compatibility retirement and canonical handoff boundaries."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path
from typing import Any, Dict, Iterable, List, Mapping, Optional, Sequence, Set, Tuple


DEFAULT_MANIFEST = "morphhdl/contracts/increment-58-retirement.contract"
EXPECTED_REPOSITORY = "pysolvesemi/MorphHDL"
EXPECTED_CONTRACT_SHA256 = "c7d8df45dbd7096e1003606bc53e7586694b91de07bb9cee1c8e43c275ee3cd1"
EXPECTED_TOP_LEVEL_KEYS = {
    "schema_version",
    "repository",
    "source_discovery",
    "frozen_53g",
    "required_deprecations",
    "required_files",
    "forbidden_source_rules",
    "required_jar_class_entries",
}
EXPECTED_DISCOVERY_KEYS = {
    "suffix",
    "extensions",
    "ignored_directory_names",
}
EXPECTED_FROZEN_KEYS = {"checker", "manifest", "removed_paths"}
EXPECTED_DEPRECATION_KEYS = {
    "id",
    "path",
    "declaration_pattern",
    "expected_count",
    "required_message_terms",
}
EXPECTED_REQUIRED_FILE_KEYS = {"path", "required_markers"}
EXPECTED_RULE_KEYS = {
    "id",
    "description",
    "pattern",
    "self_test_source",
    "path_prefixes",
}
EXPECTED_IGNORED_DIRECTORIES = (
    ".bloop",
    ".git",
    ".metals",
    ".mill-ammonite",
    ".scala-build",
    "out",
    "target",
)
EXPECTED_REMOVED_53G_PATHS = (
    "frontend/src/main/scala/morphhdl/frontend/NativeIntShadow.scala",
    "frontend/src/main/scala/morphhdl/frontend/NativeIntSymbolicConditional.scala",
    "frontend/src/main/scala/morphhdl/frontend/NativeMemAutoProvenance.scala",
    "frontend/src/main/scala/morphhdl/frontend/formalComponent.scala",
    "frontend/src/main/scala/morphhdl/frontend/formalRegion.scala",
    "frontend/src/main/scala/spinal/core/ExternalAnalyzedNativeIntFormalizationPublisher.scala",
    "frontend/src/main/scala/spinal/lib/ExternalParameterizedCounterRegistry.scala",
    "morphplugin/src/main/scala/morphhdl/compiler/MorphHdlNativeAxi4SlaveFactoryParameterizationComponent.scala",
    "morphplugin/src/main/scala/morphhdl/compiler/MorphHdlNativeIntShadowExpressionComponent.scala",
    "morphruntime/src/main/scala/spinal/core/ExternalNativeAxi4SlaveFactoryParameterization.scala",
    "morphruntime/src/main/scala/spinal/core/ExternalNativeIntCompilerRuntime.scala",
    "morphruntime/src/main/scala/spinal/core/ExternalNativeIntFormalComponent.scala",
    "morphruntime/src/main/scala/spinal/core/ExternalNativeIntFormalizationRegistry.scala",
    "morphruntime/src/main/scala/spinal/core/ExternalNativeIntShadowExpression.scala",
    "morphruntime/src/main/scala/spinal/core/ExternalNativeIntShadowRegistry.scala",
    "morphruntime/src/main/scala/spinal/core/ExternalNativeIntStructuralPublisher.scala",
    "morphruntime/src/main/scala/spinal/core/ExternalParameterizedResizeRegistry.scala",
)
EXPECTED_DEPRECATION_IDS = (
    "core-bits-shadow-factories",
    "core-sint-shadow-factories",
    "core-uint-shadow-factories",
    "dual-morph-program-class",
    "dual-morph-program-companion",
    "dual-morph-verilog-either-entry",
    "dual-morph-verilog-report-entries",
    "dual-morph-verilog-report-type",
    "frontend-bits-aliases",
    "frontend-cloneof-alias",
    "frontend-flow-alias",
    "frontend-hardtype-alias",
    "frontend-mem-alias",
    "frontend-paramrtl-lowering",
    "frontend-reg-alias",
    "frontend-sint-aliases",
    "frontend-stream-alias",
    "frontend-streamfifo-alias",
    "frontend-uint-aliases",
    "frontend-vec-aliases",
    "paramrtl-report-apply-view",
    "paramrtl-report-constructor-view",
    "paramrtl-report-copy-view",
    "paramrtl-report-parameters-view",
    "paramrtl-report-unapply-view",
    "pass-raw-design-binding",
    "pass-validated-design-binding",
    "post-publication-canonical-transform",
    "post-publication-enum-rewrite",
    "post-publication-parameterized-rewrite",
    "post-publication-transform",
)
EXPECTED_REQUIRED_FILES = (
    "morphhdl-passes/src/main/scala/morphhdl/passes/adapter/CanonicalIrPassAdapter.scala",
    "morphhdl/src/main/scala/morphhdl/MorphCanonicalIrReport.scala",
    "morphhdl/src/main/scala/morphhdl/MorphSingleSourceVerilogReport.scala",
    "morphhdl/src/main/scala/morphhdl/MorphVerilog.scala",
    "morphhdl/src/main/scala/spinal/core/internals/MorphHdlCanonicalIrProducer.scala",
    "morphir/src/main/scala/morphhdl/ir/v1/Handoff.scala",
    "morphir/src/main/scala/morphhdl/ir/v1/Model.scala",
    "morphir/src/main/scala/morphhdl/ir/v1/Validation.scala",
)
EXPECTED_RULE_IDS = (
    "frontend-parameterized-width-shadow-factory",
    "producer-filesystem-access",
    "producer-generated-verilog-access",
    "producer-name-semantic-inference",
    "producer-regex-parsing",
    "producer-rendered-expression-inference",
)
EXPECTED_JAR_ENTRIES = (
    "morphhdl/MorphCanonicalIrReport.class",
    "morphhdl/ir/v1/CanonicalIrHandoff.class",
    "morphhdl/ir/v1/CanonicalIrPublisher.class",
    "spinal/core/internals/MorphHdlCanonicalIrProducer$.class",
)
DEPRECATION = re.compile(
    r'@deprecated\s*\(\s*"(?P<message>(?:\\.|[^"\\])*)"\s*,\s*'
    r'"Increment 58"\s*\)',
    re.MULTILINE,
)
PHASE_ORDER = re.compile(
    r"val\s+crossClock\s*=.*?classOf\[PhaseCheckCrossClock\].*?"
    r"val\s+boundary\s*=\s*crossClock\.head\s*\+\s*1.*?"
    r"val\s+later\s*=\s*phases\.drop\(boundary\).*?"
    r"PhasePropagateNames.*?PhaseAllocateNames.*?PhaseVerilog.*?"
    r"val\s+reflection\s*=.*?PhaseNameNodesByReflection.*?"
    r"val\s+widthInference\s*=.*?PhaseInferWidth.*?"
    r"val\s+normalization\s*=.*?PhaseNormalizeNodeInputs.*?"
    r"val\s+simplification\s*=.*?PhaseSimplifyNodes.*?"
    r"val\s+aliasRemoval\s*=.*?PhaseRemoveIntermediateUnnameds.*?"
    r"phases\.insert\(aliasRemoval\.head,\s*new\s+GraphSnapshotPhase\(snapshot\)\).*?"
    r"val\s+captureBoundary\s*=\s*phases\.indexWhere\(.*?"
    r"classOf\[PhaseCheckCrossClock\].*?\)\s*\+\s*1.*?"
    r"phases\.insert\(\s*captureBoundary,\s*new\s+CapturePhase\(\s*capture,\s*"
    r"snapshot,\s*\(\)\s*=>\s*phases\.toVector\.map\(_\.getClass\.getName\)\s*\)\s*\)",
    re.DOTALL,
)
CAPTURE_FINAL_PLAN = re.compile(
    r"private\s+final\s+class\s+CapturePhase\(\s*"
    r"capture:\s*MorphHdlCanonicalIrCapture,\s*"
    r"snapshot:\s*GraphSnapshot,\s*"
    r"phasePlan:\s*\(\)\s*=>\s*Vector\[String\]\s*\).*?"
    r"val\s+retainedPlan\s*=\s*phasePlan\(\).*?"
    r"validateFinalPhasePlan\(retainedPlan\).*?"
    r"capture\.retainPlan\(retainedPlan\).*?"
    r"capture\.complete\(snapshot\.handoff\)",
    re.DOTALL,
)
PUBLISH_ORDER = re.compile(
    r"val\s+report\s*=\s*generateWithCanonicalIr\(config\)\(component\).*?"
    r"publisher\.publish\(report\.handoff\).*?\breport\b",
    re.DOTALL,
)


class Increment58Error(RuntimeError):
    pass


def repository_root(explicit: Optional[str]) -> Path:
    if explicit:
        return Path(explicit).resolve()
    result = subprocess.run(
        ["git", "rev-parse", "--show-toplevel"],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if result.returncode != 0:
        raise Increment58Error(result.stderr.strip() or "not inside a Git repository")
    return Path(result.stdout.strip()).resolve()


def clean_path(value: Any, role: str) -> str:
    if not isinstance(value, str) or not value:
        raise Increment58Error(f"{role} must be a non-empty string")
    path = Path(value)
    if path.is_absolute() or value.startswith("./") or ".." in path.parts:
        raise Increment58Error(f"{role} is not repository-relative: {value}")
    if path.as_posix() != value or value.endswith("/"):
        raise Increment58Error(f"{role} is not normalized POSIX spelling: {value}")
    return value


def strings(
    value: Any,
    role: str,
    *,
    paths: bool = False,
    allow_empty: bool = False,
    sorted_values: bool = True,
) -> List[str]:
    if not isinstance(value, list) or (not value and not allow_empty):
        expectation = "an array" if allow_empty else "a non-empty array"
        raise Increment58Error(f"{role} must be {expectation}")
    result: List[str] = []
    for index, item in enumerate(value):
        if not isinstance(item, str) or not item:
            raise Increment58Error(f"{role}[{index}] must be a non-empty string")
        result.append(clean_path(item, f"{role}[{index}]") if paths else item)
    if len(result) != len(set(result)):
        raise Increment58Error(f"{role} must contain no duplicates")
    if sorted_values and result != sorted(result):
        raise Increment58Error(f"{role} must be sorted")
    return result


def load_manifest(path: Path) -> Mapping[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as error:
        raise Increment58Error(f"Increment 58 contract is missing: {path}") from error
    except json.JSONDecodeError as error:
        raise Increment58Error(f"Increment 58 contract is invalid JSON: {error}") from error
    if not isinstance(value, dict):
        raise Increment58Error("Increment 58 contract root must be an object")
    return value


def manifest_digest(manifest: Mapping[str, Any]) -> str:
    canonical = json.dumps(
        manifest,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=True,
    ).encode("utf-8")
    return hashlib.sha256(canonical).hexdigest()


def source_scoped_path(value: Any, role: str) -> str:
    path = clean_path(value, role)
    wrapped = "/" + path
    if "/src/main/" not in wrapped and not wrapped.endswith("/src/main"):
        raise Increment58Error(f"{role} is outside dynamically discovered */src/main: {path}")
    return path


def compile_contract(manifest: Mapping[str, Any]) -> Dict[str, Any]:
    if set(manifest) != EXPECTED_TOP_LEVEL_KEYS:
        raise Increment58Error(
            "contract keys differ from the closed Increment 58 schema: "
            f"{sorted(set(manifest) ^ EXPECTED_TOP_LEVEL_KEYS)}"
        )
    if manifest.get("schema_version") != 1:
        raise Increment58Error("schema_version must be 1")
    if manifest.get("repository") != EXPECTED_REPOSITORY:
        raise Increment58Error(f"repository must be {EXPECTED_REPOSITORY}")
    if manifest_digest(manifest) != EXPECTED_CONTRACT_SHA256:
        raise Increment58Error(
            "retirement manifest differs from the closed Increment 58 contract"
        )

    raw_discovery = manifest.get("source_discovery")
    if not isinstance(raw_discovery, dict) or set(raw_discovery) != EXPECTED_DISCOVERY_KEYS:
        raise Increment58Error("source_discovery does not match the closed schema")
    if raw_discovery.get("suffix") != "src/main":
        raise Increment58Error("production discovery suffix must be exactly src/main")
    extensions = strings(raw_discovery.get("extensions"), "source_discovery.extensions")
    if extensions != [".java", ".scala"]:
        raise Increment58Error("source extensions must be exactly .java and .scala")
    ignored = strings(
        raw_discovery.get("ignored_directory_names"),
        "source_discovery.ignored_directory_names",
    )
    if tuple(ignored) != EXPECTED_IGNORED_DIRECTORIES:
        raise Increment58Error("source discovery ignore set cannot be changed")

    raw_frozen = manifest.get("frozen_53g")
    if not isinstance(raw_frozen, dict) or set(raw_frozen) != EXPECTED_FROZEN_KEYS:
        raise Increment58Error("frozen_53g does not match the closed schema")
    checker = clean_path(raw_frozen.get("checker"), "frozen_53g.checker")
    frozen_manifest = clean_path(raw_frozen.get("manifest"), "frozen_53g.manifest")
    if checker != "morphhdl/scripts/check-production-retirement.py":
        raise Increment58Error("the frozen 53g checker cannot be replaced")
    if frozen_manifest != "morphhdl/contracts/increment-53g-production-retirement.contract":
        raise Increment58Error("the frozen 53g manifest cannot be replaced")
    removed = strings(
        raw_frozen.get("removed_paths"),
        "frozen_53g.removed_paths",
        paths=True,
    )
    if tuple(removed) != EXPECTED_REMOVED_53G_PATHS:
        raise Increment58Error("the exact 17-path Increment 53g absence set changed")

    raw_deprecations = manifest.get("required_deprecations")
    if not isinstance(raw_deprecations, list) or not raw_deprecations:
        raise Increment58Error("required_deprecations must be a non-empty array")
    deprecations: List[Dict[str, Any]] = []
    deprecation_ids: List[str] = []
    for index, raw in enumerate(raw_deprecations):
        if not isinstance(raw, dict) or set(raw) != EXPECTED_DEPRECATION_KEYS:
            raise Increment58Error(
                f"required_deprecations[{index}] does not match the closed schema"
            )
        identifier = raw.get("id")
        if not isinstance(identifier, str) or not re.fullmatch(
            r"[a-z][a-z0-9-]*", identifier
        ):
            raise Increment58Error(f"required_deprecations[{index}].id is invalid")
        path = source_scoped_path(raw.get("path"), f"required_deprecations[{index}].path")
        pattern = raw.get("declaration_pattern")
        if not isinstance(pattern, str) or not pattern:
            raise Increment58Error(
                f"required_deprecations[{index}].declaration_pattern is empty"
            )
        try:
            compiled_pattern = re.compile(pattern)
        except re.error as error:
            raise Increment58Error(
                f"invalid declaration pattern for {identifier}: {error}"
            ) from error
        count = raw.get("expected_count")
        if not isinstance(count, int) or isinstance(count, bool) or count <= 0:
            raise Increment58Error(f"{identifier}.expected_count must be a positive integer")
        terms = strings(
            raw.get("required_message_terms"),
            f"{identifier}.required_message_terms",
        )
        if any(term != term.lower() for term in terms):
            raise Increment58Error(f"{identifier} message terms must be lowercase")
        deprecation_ids.append(identifier)
        deprecations.append(
            {
                "id": identifier,
                "path": path,
                "regex": compiled_pattern,
                "expected_count": count,
                "terms": terms,
            }
        )
    if tuple(deprecation_ids) != EXPECTED_DEPRECATION_IDS:
        raise Increment58Error("required deprecation IDs changed or were reordered")

    raw_required = manifest.get("required_files")
    if not isinstance(raw_required, list) or not raw_required:
        raise Increment58Error("required_files must be a non-empty array")
    required: List[Dict[str, Any]] = []
    required_paths: List[str] = []
    for index, raw in enumerate(raw_required):
        if not isinstance(raw, dict) or set(raw) != EXPECTED_REQUIRED_FILE_KEYS:
            raise Increment58Error(f"required_files[{index}] does not match the closed schema")
        path = source_scoped_path(raw.get("path"), f"required_files[{index}].path")
        markers = strings(
            raw.get("required_markers"),
            f"required_files[{index}].required_markers",
            sorted_values=False,
        )
        required_paths.append(path)
        required.append({"path": path, "markers": markers})
    if tuple(required_paths) != EXPECTED_REQUIRED_FILES:
        raise Increment58Error("required canonical handoff files changed or were reordered")

    raw_rules = manifest.get("forbidden_source_rules")
    if not isinstance(raw_rules, list) or not raw_rules:
        raise Increment58Error("forbidden_source_rules must be a non-empty array")
    rules: List[Dict[str, Any]] = []
    rule_ids: List[str] = []
    for index, raw in enumerate(raw_rules):
        if not isinstance(raw, dict) or set(raw) != EXPECTED_RULE_KEYS:
            raise Increment58Error(
                f"forbidden_source_rules[{index}] does not match the closed schema"
            )
        identifier = raw.get("id")
        description = raw.get("description")
        pattern = raw.get("pattern")
        fixture = raw.get("self_test_source")
        if not all(
            isinstance(value, str) and value.strip()
            for value in (identifier, description, pattern, fixture)
        ):
            raise Increment58Error(f"forbidden_source_rules[{index}] has an empty field")
        try:
            compiled_pattern = re.compile(pattern, re.MULTILINE)
        except re.error as error:
            raise Increment58Error(f"invalid regex for {identifier}: {error}") from error
        if compiled_pattern.search(fixture) is None:
            raise Increment58Error(f"rule {identifier} does not reject its self-test fixture")
        prefixes = strings(
            raw.get("path_prefixes"),
            f"{identifier}.path_prefixes",
            sorted_values=True,
        )
        for prefix in prefixes:
            normalized = prefix[:-1] if prefix.endswith("/") else prefix
            source_scoped_path(normalized, f"{identifier}.path_prefixes")
        rule_ids.append(identifier)
        rules.append(
            {
                "id": identifier,
                "description": description,
                "regex": compiled_pattern,
                "fixture": fixture,
                "prefixes": prefixes,
            }
        )
    if tuple(rule_ids) != EXPECTED_RULE_IDS:
        raise Increment58Error("forbidden source rule IDs changed or were reordered")

    jar_entries = strings(
        manifest.get("required_jar_class_entries"),
        "required_jar_class_entries",
    )
    if tuple(jar_entries) != EXPECTED_JAR_ENTRIES:
        raise Increment58Error("required packaged handoff classes changed")
    for index, entry in enumerate(jar_entries):
        if not entry.endswith(".class") or not re.fullmatch(
            r"[A-Za-z0-9_$/.]+\.class", entry
        ):
            raise Increment58Error(
                f"required_jar_class_entries[{index}] is not a class entry: {entry}"
            )

    return {
        "extensions": set(extensions),
        "ignored": set(ignored),
        "frozen_checker": checker,
        "frozen_manifest": frozen_manifest,
        "removed": removed,
        "deprecations": deprecations,
        "required": required,
        "rules": rules,
        "jar_entries": jar_entries,
    }


def discover_source_roots(root: Path, ignored: Set[str]) -> List[Tuple[str, Path]]:
    discovered: Dict[str, Path] = {}
    for current, directory_names, _ in os.walk(str(root)):
        directory_names[:] = sorted(
            name for name in directory_names if name not in ignored
        )
        current_path = Path(current)
        if current_path.name != "src" or "main" not in directory_names:
            continue
        candidate = current_path / "main"
        relative = candidate.relative_to(root).as_posix()
        if relative in discovered:
            raise Increment58Error(f"production source root discovered twice: {relative}")
        discovered[relative] = candidate
    if not discovered:
        raise Increment58Error("dynamic */src/main discovery found no production roots")
    return sorted(discovered.items())


def production_files(
    root: Path, contract: Mapping[str, Any]
) -> Tuple[List[Tuple[str, Path]], List[str]]:
    roots = discover_source_roots(root, contract["ignored"])
    result: Dict[str, Path] = {}
    for source_root, directory in roots:
        for path in directory.rglob("*"):
            if not path.is_file() or path.suffix not in contract["extensions"]:
                continue
            relative = path.relative_to(root).as_posix()
            if relative in result:
                raise Increment58Error(f"production source discovered twice: {relative}")
            result[relative] = path
    if not result:
        raise Increment58Error("dynamic */src/main discovery found no Scala or Java sources")
    return sorted(result.items()), [value[0] for value in roots]


def path_selected(path: str, prefixes: Iterable[str]) -> bool:
    return any(
        path.startswith(prefix) if prefix.endswith("/") else path == prefix
        for prefix in prefixes
    )


def annotation_for_declaration(
    source: str, declaration: re.Match[str]
) -> Optional[re.Match[str]]:
    annotations = [
        match for match in DEPRECATION.finditer(source, 0, declaration.start())
    ]
    if not annotations:
        return None
    candidate = annotations[-1]
    if source[candidate.end() : declaration.start()].strip():
        return None
    return candidate


def validate_structural_handoff(sources: Mapping[str, str]) -> None:
    producer_path = (
        "morphhdl/src/main/scala/spinal/core/internals/"
        "MorphHdlCanonicalIrProducer.scala"
    )
    producer = sources[producer_path]
    if PHASE_ORDER.search(producer) is None:
        raise Increment58Error(
            "canonical capture is not installed exactly after PhaseCheckCrossClock "
            "and before name propagation, allocation and Verilog emission"
        )
    if CAPTURE_FINAL_PLAN.search(producer) is None:
        raise Increment58Error(
            "canonical capture must snapshot the final phase plan at execution "
            "through the exact read-only plan-supplier closure"
        )
    morph_verilog = sources["morphhdl/src/main/scala/morphhdl/MorphVerilog.scala"]
    if PUBLISH_ORDER.search(morph_verilog) is None:
        raise Increment58Error(
            "canonical publisher must receive the validated handoff only after generation succeeds"
        )


def validate_sources(
    root: Path, contract: Mapping[str, Any]
) -> Tuple[int, List[str]]:
    for relative in contract["removed"]:
        if (root / relative).exists():
            raise Increment58Error(f"retired Increment 53g path returned: {relative}")

    files, roots = production_files(root, contract)
    sources: Dict[str, str] = {}
    for relative, path in files:
        try:
            sources[relative] = path.read_text(encoding="utf-8")
        except UnicodeDecodeError as error:
            raise Increment58Error(f"production source is not UTF-8: {relative}") from error

    required_paths = {
        entry["path"] for entry in contract["required"]
    } | {entry["path"] for entry in contract["deprecations"]}
    undiscovered = sorted(required_paths - set(sources))
    if undiscovered:
        raise Increment58Error(
            "required retirement sources are missing from dynamic discovery: "
            + ", ".join(undiscovered)
        )

    for entry in contract["required"]:
        source = sources[entry["path"]]
        for marker in entry["markers"]:
            if marker not in source:
                raise Increment58Error(
                    f"required canonical handoff marker is missing from {entry['path']}: {marker}"
                )

    for entry in contract["deprecations"]:
        source = sources[entry["path"]]
        declarations = list(entry["regex"].finditer(source))
        if len(declarations) != entry["expected_count"]:
            raise Increment58Error(
                f"{entry['id']} expected {entry['expected_count']} declarations in "
                f"{entry['path']}, found {len(declarations)}"
            )
        for declaration in declarations:
            annotation = annotation_for_declaration(source, declaration)
            line = source.count("\n", 0, declaration.start()) + 1
            if annotation is None:
                raise Increment58Error(
                    f"{entry['id']} is not directly @deprecated for Increment 58: "
                    f"{entry['path']}:{line}"
                )
            message = annotation.group("message").lower()
            missing_terms = [term for term in entry["terms"] if term not in message]
            if missing_terms:
                raise Increment58Error(
                    f"{entry['id']} deprecation does not identify its compatibility role "
                    f"at {entry['path']}:{line}: {', '.join(missing_terms)}"
                )

    violations: List[str] = []
    for relative, source in sources.items():
        for rule in contract["rules"]:
            if not path_selected(relative, rule["prefixes"]):
                continue
            match = rule["regex"].search(source)
            if match is None:
                continue
            line = source.count("\n", 0, match.start()) + 1
            excerpt = " ".join(match.group(0).split())
            violations.append(f"{rule['id']}: {relative}:{line}: {excerpt}")
    if violations:
        raise Increment58Error(
            "retired shadow inference was found in production:\n  "
            + "\n  ".join(violations)
        )

    validate_structural_handoff(sources)
    return len(files), roots


def validate_jars(
    jars: Sequence[Path], contract: Mapping[str, Any], require_jars: bool
) -> int:
    if require_jars and not jars:
        raise Increment58Error("--require-jar needs at least one --jar argument")
    observed: Set[str] = set()
    for jar in jars:
        if not jar.is_file():
            raise Increment58Error(f"JAR does not exist: {jar}")
        try:
            with zipfile.ZipFile(str(jar)) as archive:
                observed.update(name.lstrip("/") for name in archive.namelist())
        except zipfile.BadZipFile as error:
            raise Increment58Error(f"invalid JAR/ZIP archive: {jar}") from error
    if require_jars:
        missing = sorted(set(contract["jar_entries"]) - observed)
        if missing:
            raise Increment58Error(
                "packaged Increment 58 handoff classes are missing: " + ", ".join(missing)
            )
    return len(jars)


def run_frozen_guard(
    root: Path,
    contract: Mapping[str, Any],
    jars: Sequence[Path],
    *,
    require_jars: bool = False,
    self_test: bool = False,
) -> None:
    checker = root / contract["frozen_checker"]
    manifest = root / contract["frozen_manifest"]
    if not checker.is_file() or not manifest.is_file():
        raise Increment58Error("frozen Increment 53g checker or manifest is missing")
    command = [
        sys.executable,
        str(checker),
        "--repo-root",
        str(root),
        "--manifest",
        str(manifest),
    ]
    if self_test:
        command.append("--self-test")
    else:
        for jar in jars:
            command.extend(["--jar", str(jar)])
        if require_jars:
            command.append("--require-jar")
    result = subprocess.run(
        command,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if result.returncode != 0:
        detail = (result.stderr or result.stdout).strip()
        raise Increment58Error(
            "frozen Increment 53g production-retirement guard failed conjunctively"
            + (f": {detail}" if detail else "")
        )


def expect_source_failure(
    root: Path, contract: Mapping[str, Any], label: str
) -> None:
    try:
        validate_sources(root, contract)
    except Increment58Error:
        return
    raise Increment58Error(f"self-test expected source failure: {label}")


def expect_jar_failure(
    jars: Sequence[Path], contract: Mapping[str, Any], label: str
) -> None:
    try:
        validate_jars(jars, contract, True)
    except Increment58Error:
        return
    raise Increment58Error(f"self-test expected JAR failure: {label}")


def copy_self_test_tree(
    source_root: Path, destination: Path, contract: Mapping[str, Any]
) -> None:
    _, production_roots = production_files(source_root, contract)
    source_paths = {
        entry["path"] for entry in contract["required"]
    } | {entry["path"] for entry in contract["deprecations"]}
    for relative in sorted(source_paths):
        source = source_root / relative
        if not source.is_file():
            raise Increment58Error(f"self-test fixture source is missing: {relative}")
        target = destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(str(source), str(target))
    for index, production_root in enumerate(production_roots):
        directory = destination / production_root / "scala"
        directory.mkdir(parents=True, exist_ok=True)
        safe = directory / f"Increment58SafeRoot{index}.scala"
        if not safe.exists():
            safe.write_text(f"object Increment58SafeRoot{index}\n", encoding="utf-8")


def fixture_path(prefix: str, identifier: str) -> str:
    if not prefix.endswith("/"):
        return prefix
    class_name = "".join(part.title() for part in identifier.split("-"))
    return prefix + f"scala/Increment58{class_name}.scala"


def self_test(
    repository: Path, manifest: Mapping[str, Any], contract: Mapping[str, Any]
) -> None:
    # The prior boundary is executable, not copied into or weakened by this one.
    run_frozen_guard(repository, contract, [], self_test=True)
    validate_sources(repository, contract)

    narrowed = json.loads(json.dumps(manifest))
    narrowed["forbidden_source_rules"] = narrowed["forbidden_source_rules"][:-1]
    try:
        compile_contract(narrowed)
    except Increment58Error:
        pass
    else:
        raise Increment58Error("self-test expected closed-contract narrowing failure")

    with tempfile.TemporaryDirectory(prefix="morphhdl-increment-58-retirement-") as directory:
        root = Path(directory)
        copy_self_test_tree(repository, root, contract)
        validate_sources(root, contract)

        # Every dynamically discovered root must be read, and a newly added module
        # must enter the scan without changing this contract.
        _, source_roots = production_files(root, contract)
        for index, source_root in enumerate(source_roots):
            probe = root / source_root / f"Increment58InvalidUtf8{index}.scala"
            probe.write_bytes(b"\xff")
            expect_source_failure(root, contract, f"dynamic source root {source_root}")
            probe.unlink()
        future_probe = root / "future-module/src/main/scala/Increment58Future.scala"
        future_probe.parent.mkdir(parents=True, exist_ok=True)
        future_probe.write_bytes(b"\xff")
        expect_source_failure(root, contract, "newly added dynamic source root")
        shutil.rmtree(str(root / "future-module"))

        for relative in contract["removed"]:
            retired = root / relative
            retired.parent.mkdir(parents=True, exist_ok=True)
            retired.write_text("object RetiredIncrement53g\n", encoding="utf-8")
            expect_source_failure(root, contract, f"53g retired path {relative}")
            retired.unlink()

        for entry in contract["deprecations"]:
            path = root / entry["path"]
            original = path.read_text(encoding="utf-8")
            declarations = list(entry["regex"].finditer(original))
            annotation = annotation_for_declaration(original, declarations[0])
            if annotation is None:
                raise Increment58Error(
                    f"self-test cannot locate baseline annotation for {entry['id']}"
                )
            path.write_text(
                original[: annotation.start()] + original[annotation.end() :],
                encoding="utf-8",
            )
            expect_source_failure(root, contract, f"missing deprecation {entry['id']}")
            path.write_text(original, encoding="utf-8")

            message = annotation.group("message")
            mutated_message = message.replace("Compatibility", "Production").replace(
                "compatibility", "production"
            )
            if mutated_message == message:
                raise Increment58Error(
                    f"self-test annotation has no compatibility term: {entry['id']}"
                )
            path.write_text(
                original[: annotation.start("message")]
                + mutated_message
                + original[annotation.end("message") :],
                encoding="utf-8",
            )
            expect_source_failure(root, contract, f"deprecation role {entry['id']}")
            path.write_text(original, encoding="utf-8")

        for entry in contract["required"]:
            path = root / entry["path"]
            original = path.read_text(encoding="utf-8")
            for marker in entry["markers"]:
                if marker not in original:
                    raise Increment58Error(
                        f"self-test baseline marker is missing from {entry['path']}: {marker}"
                    )
                path.write_text(original.replace(marker, ""), encoding="utf-8")
                expect_source_failure(
                    root, contract, f"canonical marker {entry['path']}::{marker}"
                )
                path.write_text(original, encoding="utf-8")

        for rule in contract["rules"]:
            relative = fixture_path(rule["prefixes"][0], rule["id"])
            path = root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            original = path.read_text(encoding="utf-8") if path.exists() else None
            if original is None:
                path.write_text(rule["fixture"] + "\n", encoding="utf-8")
            else:
                path.write_text(original + "\n" + rule["fixture"] + "\n", encoding="utf-8")
            expect_source_failure(root, contract, f"forbidden rule {rule['id']}")
            if original is None:
                path.unlink()
            else:
                path.write_text(original, encoding="utf-8")

        good_jar = root / "good.jar"
        with zipfile.ZipFile(str(good_jar), "w") as archive:
            archive.writestr("spinal/core/ElabInt.class", b"typed")
            for entry in contract["jar_entries"]:
                archive.writestr(entry, b"handoff")
        validate_jars([good_jar], contract, True)
        for index, missing in enumerate(contract["jar_entries"]):
            jar = root / f"missing-{index}.jar"
            with zipfile.ZipFile(str(jar), "w") as archive:
                for entry in contract["jar_entries"]:
                    if entry != missing:
                        archive.writestr(entry, b"handoff")
            expect_jar_failure([jar], contract, f"required class {missing}")
        expect_jar_failure([], contract, "--require-jar without --jar")


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root")
    parser.add_argument("--manifest", default=DEFAULT_MANIFEST)
    parser.add_argument("--jar", action="append", default=[])
    parser.add_argument("--require-jar", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    arguments = parser.parse_args(argv)

    try:
        root = repository_root(arguments.repo_root)
        manifest_path = Path(arguments.manifest)
        if not manifest_path.is_absolute():
            manifest_path = root / manifest_path
        manifest = load_manifest(manifest_path)
        contract = compile_contract(manifest)
        if arguments.self_test:
            if arguments.jar or arguments.require_jar:
                raise Increment58Error("--self-test cannot be combined with JAR options")
            self_test(root, manifest, contract)
            print("Increment 58 retirement guard self-test passed")
            return 0

        jars = [Path(value).resolve() for value in arguments.jar]
        run_frozen_guard(
            root,
            contract,
            jars,
            require_jars=arguments.require_jar,
        )
        source_count, source_roots = validate_sources(root, contract)
        jar_count = validate_jars(jars, contract, arguments.require_jar)
        print("Increment 58 legacy-retirement boundary is valid")
        print(f"  dynamically discovered production roots: {len(source_roots)}")
        print(f"  production sources checked: {source_count}")
        print(f"  compatibility deprecations checked: {len(contract['deprecations'])}")
        print(f"  canonical handoff files checked: {len(contract['required'])}")
        print(f"  forbidden producer/frontend rules enforced: {len(contract['rules'])}")
        print(f"  frozen 53g retired paths retained absent: {len(contract['removed'])}")
        print(f"  packaged JARs checked: {jar_count}")
        return 0
    except Increment58Error as error:
        print(f"Increment 58 retirement guard failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
