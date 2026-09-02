#!/usr/bin/env python3
"""Reject retired Increment 53g production machinery in sources and JARs."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path
from typing import Any, Dict, Iterable, List, Mapping, Optional, Sequence, Tuple


DEFAULT_MANIFEST = "morphhdl/contracts/increment-53g-production-retirement.contract"
EXPECTED_REPOSITORY = "pysolvesemi/MorphHDL"
EXPECTED_SOURCE_ROOTS = (
    "backends/verilog/src/main",
    "core/src/main",
    "frontend/src/main",
    "idslpayload/src/main",
    "idslplugin/src/main",
    "lib/src/main",
    "morphhdl-passes/src/main",
    "morphhdl/src/main",
    "morphir/src/main",
    "morphplugin/src/main",
    "morphruntime/src/main",
    "paramrtl/src/main",
    "scalaplugin/src/main",
    "sim/src/main",
    "tester/src/main",
)
EXPECTED_REMOVED_PATHS = (
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
EXPECTED_RULE_IDS = (
    "retired-native-int-production-symbol",
    "retired-constructor-boundary-api",
    "file-specific-compiler-eligibility",
    "source-position-alias-reconstruction",
    "witness-value-inference",
    "emitted-name-recognition",
)
EXPECTED_RULES_SHA256 = (
    "6f4456f583684b4fbac29a4e620233225d802cb7abe498171ec33649bd308966"
)
EXPECTED_PLUGIN_COMPONENTS = (
    "MorphHdlTypedElaborationControlComponent",
    "MorphHdlNaturalSymbolicConditionalComponent",
    "MorphHdlFrontendSymbolicEqualitySafetyComponent",
)
EXPECTED_JAR_PREFIXES = (
    "morphhdl/compiler/MorphHdlNativeAxi4SlaveFactoryParameterizationComponent",
    "morphhdl/compiler/MorphHdlNativeIntShadowExpressionComponent",
    "morphhdl/frontend/NativeIntShadow",
    "morphhdl/frontend/NativeIntSymbolicConditional",
    "morphhdl/frontend/NativeMemAutoProvenance",
    "morphhdl/frontend/formalComponent",
    "morphhdl/frontend/formalRegion",
    "spinal/core/ExternalAnalyzedNativeIntFormalCapture",
    "spinal/core/ExternalAnalyzedNativeIntFormalizationPublisher",
    "spinal/core/ExternalFormalParameterRegistry$PreparedComponentAttachment",
    "spinal/core/ExternalFormalParameterRegistry$PreparedLeafAttachment",
    "spinal/core/ExternalNativeAxi4",
    "spinal/core/ExternalNativeAxi4SlaveFactoryParameterization",
    "spinal/core/ExternalNativeInt",
    "spinal/core/ExternalNativeIntCompilerRuntime",
    "spinal/core/ExternalNativeIntFormalComponent",
    "spinal/core/ExternalNativeIntFormalizationRegistry",
    "spinal/core/ExternalNativeIntFormalizationToken",
    "spinal/core/ExternalNativeIntShadow",
    "spinal/core/ExternalNativeIntStructuralPublisher",
    "spinal/core/ExternalParameterizedResize",
    "spinal/core/ExternalParameterizedResizeRegistry",
    "spinal/lib/ExternalCounterIdentityRef",
    "spinal/lib/ExternalParameterizedCounterMetadata",
    "spinal/lib/ExternalParameterizedCounterRegistry",
)
EXPECTED_TOP_LEVEL_KEYS = {
    "schema_version",
    "repository",
    "production_source_roots",
    "source_extensions",
    "removed_paths",
    "forbidden_source_rules",
    "expected_plugin_components",
    "plugin_descriptor",
    "forbidden_jar_class_prefixes",
}
EXPECTED_RULE_KEYS = {
    "id",
    "description",
    "pattern",
    "self_test_source",
    "path_prefixes",
    "allow_paths",
}


class RetirementError(RuntimeError):
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
        raise RetirementError(result.stderr.strip() or "not inside a Git repository")
    return Path(result.stdout.strip()).resolve()


def clean_path(value: Any, role: str) -> str:
    if not isinstance(value, str) or not value:
        raise RetirementError(f"{role} must be a non-empty string")
    path = Path(value)
    if path.is_absolute() or value.startswith("./") or ".." in path.parts:
        raise RetirementError(f"{role} is not repository-relative: {value}")
    if path.as_posix() != value or value.endswith("/"):
        raise RetirementError(f"{role} is not normalized POSIX spelling: {value}")
    return value


def string_list(
    value: Any,
    role: str,
    *,
    paths: bool = False,
    path_prefixes: bool = False,
) -> List[str]:
    if not isinstance(value, list) or not value:
        raise RetirementError(f"{role} must be a non-empty array")
    result: List[str] = []
    for index, item in enumerate(value):
        if not isinstance(item, str) or not item:
            raise RetirementError(f"{role}[{index}] must be a non-empty string")
        if path_prefixes:
            normalized = item[:-1] if item.endswith("/") else item
            clean_path(normalized, f"{role}[{index}]")
            result.append(item)
        else:
            result.append(clean_path(item, f"{role}[{index}]") if paths else item)
    if result != sorted(set(result)):
        raise RetirementError(f"{role} must be sorted and contain no duplicates")
    return result


def load_manifest(path: Path) -> Mapping[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as error:
        raise RetirementError(f"retirement manifest is missing: {path}") from error
    except json.JSONDecodeError as error:
        raise RetirementError(f"retirement manifest is invalid JSON: {error}") from error
    if not isinstance(value, dict):
        raise RetirementError("retirement manifest root must be an object")
    return value


def compile_contract(manifest: Mapping[str, Any]) -> Dict[str, Any]:
    if set(manifest) != EXPECTED_TOP_LEVEL_KEYS:
        raise RetirementError(
            "retirement manifest keys differ from the closed schema: "
            f"{sorted(set(manifest) ^ EXPECTED_TOP_LEVEL_KEYS)}"
        )
    if manifest.get("schema_version") != 1:
        raise RetirementError("schema_version must be 1")
    if manifest.get("repository") != EXPECTED_REPOSITORY:
        raise RetirementError(f"repository must be {EXPECTED_REPOSITORY}")

    roots = string_list(
        manifest.get("production_source_roots"),
        "production_source_roots",
        paths=True,
    )
    if tuple(roots) != EXPECTED_SOURCE_ROOTS:
        raise RetirementError("production_source_roots cannot be narrowed or reordered")

    extensions = string_list(manifest.get("source_extensions"), "source_extensions")
    if extensions != [".java", ".scala"]:
        raise RetirementError("source_extensions must be exactly .java and .scala")

    removed = string_list(manifest.get("removed_paths"), "removed_paths", paths=True)
    if tuple(removed) != EXPECTED_REMOVED_PATHS:
        raise RetirementError("removed_paths differs from the canonical 53g deletion set")

    raw_rules = manifest.get("forbidden_source_rules")
    if not isinstance(raw_rules, list) or not raw_rules:
        raise RetirementError("forbidden_source_rules must be a non-empty array")
    canonical_rules = json.dumps(
        raw_rules,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=True,
    ).encode("utf-8")
    if hashlib.sha256(canonical_rules).hexdigest() != EXPECTED_RULES_SHA256:
        raise RetirementError(
            "forbidden_source_rules differs from the canonical closed 53g rule set"
        )
    rules: List[Dict[str, Any]] = []
    identifiers: List[str] = []
    for index, raw_rule in enumerate(raw_rules):
        if not isinstance(raw_rule, dict) or set(raw_rule) != EXPECTED_RULE_KEYS:
            raise RetirementError(
                f"forbidden_source_rules[{index}] does not match the closed rule schema"
            )
        identifier = raw_rule.get("id")
        description = raw_rule.get("description")
        pattern = raw_rule.get("pattern")
        fixture = raw_rule.get("self_test_source")
        if not all(isinstance(item, str) and item.strip() for item in (
            identifier,
            description,
            pattern,
            fixture,
        )):
            raise RetirementError(
                f"forbidden_source_rules[{index}] has an empty string field"
            )
        try:
            compiled = re.compile(pattern, re.MULTILINE)
        except re.error as error:
            raise RetirementError(f"invalid regex for rule {identifier}: {error}") from error
        if compiled.search(fixture) is None:
            raise RetirementError(f"rule {identifier} does not reject its self-test fixture")
        prefixes = string_list(
            raw_rule.get("path_prefixes"),
            f"forbidden_source_rules[{index}].path_prefixes",
            path_prefixes=True,
        )
        allow_paths = raw_rule.get("allow_paths")
        if not isinstance(allow_paths, list):
            raise RetirementError(f"rule {identifier} allow_paths must be an array")
        if allow_paths:
            raise RetirementError(
                f"rule {identifier} may not exempt production files; historical oracles belong outside src/main"
            )
        identifiers.append(identifier)
        rules.append(
            {
                "id": identifier,
                "description": description,
                "regex": compiled,
                "fixture": fixture,
                "prefixes": prefixes,
            }
        )
    if tuple(identifiers) != EXPECTED_RULE_IDS:
        raise RetirementError("forbidden rule IDs differ from the canonical 53g rule order")

    components = manifest.get("expected_plugin_components")
    if components != list(EXPECTED_PLUGIN_COMPONENTS):
        raise RetirementError("expected_plugin_components must be the three ordered typed phases")
    descriptor = clean_path(manifest.get("plugin_descriptor"), "plugin_descriptor")

    jar_prefixes = string_list(
        manifest.get("forbidden_jar_class_prefixes"),
        "forbidden_jar_class_prefixes",
    )
    if tuple(jar_prefixes) != EXPECTED_JAR_PREFIXES:
        raise RetirementError(
            "forbidden_jar_class_prefixes differs from the canonical 53g class set"
        )
    for index, prefix in enumerate(jar_prefixes):
        if prefix.startswith("/") or prefix.endswith("/") or ".." in prefix.split("/"):
            raise RetirementError(
                f"forbidden_jar_class_prefixes[{index}] is not a class prefix: {prefix}"
            )
        if not re.fullmatch(r"[A-Za-z0-9_$/]+", prefix):
            raise RetirementError(
                f"forbidden_jar_class_prefixes[{index}] contains invalid characters: {prefix}"
            )

    return {
        "roots": roots,
        "extensions": set(extensions),
        "removed": removed,
        "rules": rules,
        "descriptor": descriptor,
        "jar_prefixes": jar_prefixes,
    }


def production_files(root: Path, contract: Mapping[str, Any]) -> List[Tuple[str, Path]]:
    result: Dict[str, Path] = {}
    extensions = contract["extensions"]
    for source_root in contract["roots"]:
        directory = root / source_root
        if not directory.is_dir():
            raise RetirementError(f"production source root is missing: {source_root}")
        for path in directory.rglob("*"):
            if not path.is_file() or path.suffix not in extensions:
                continue
            relative = path.relative_to(root).as_posix()
            if relative in result:
                raise RetirementError(f"production source discovered twice: {relative}")
            result[relative] = path
    return sorted(result.items())


def path_selected(path: str, prefixes: Iterable[str]) -> bool:
    for prefix in prefixes:
        if prefix.endswith("/") and path.startswith(prefix):
            return True
        if not prefix.endswith("/") and path == prefix:
            return True
    return False


def plugin_components(source: str) -> List[str]:
    marker = "override val components: List[PluginComponent]"
    start = source.find(marker)
    if start < 0:
        raise RetirementError("MorphHdlPlugin has no explicit components declaration")
    list_start = source.find("List(", start)
    if list_start < 0:
        raise RetirementError("MorphHdlPlugin components are not an explicit List")
    index = list_start + len("List")
    depth = 0
    end = -1
    while index < len(source):
        character = source[index]
        if character == "(":
            depth += 1
        elif character == ")":
            depth -= 1
            if depth == 0:
                end = index
                break
        index += 1
    if end < 0:
        raise RetirementError("MorphHdlPlugin components List is unterminated")
    body = source[list_start:end + 1]
    components = re.findall(r"\bnew\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(\s*global\s*\)", body)
    residue = re.sub(
        r"\bnew\s+[A-Za-z_][A-Za-z0-9_]*\s*\(\s*global\s*\)\s*,?",
        "",
        body[len("List("):-1],
    )
    residue = re.sub(r"\s|,", "", residue)
    if residue:
        raise RetirementError(
            "MorphHdlPlugin components List contains a non-canonical entry: " + residue
        )
    return components


def validate_sources(root: Path, contract: Mapping[str, Any]) -> int:
    for relative in contract["removed"]:
        if (root / relative).exists():
            raise RetirementError(f"retired production path still exists: {relative}")

    files = production_files(root, contract)
    violations: List[str] = []
    for relative, path in files:
        try:
            source = path.read_text(encoding="utf-8")
        except UnicodeDecodeError as error:
            raise RetirementError(f"production source is not UTF-8: {relative}") from error
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
        raise RetirementError(
            "forbidden production reconstruction was found:\n  " + "\n  ".join(violations)
        )

    descriptor_path = root / contract["descriptor"]
    try:
        descriptor_source = descriptor_path.read_text(encoding="utf-8")
    except FileNotFoundError as error:
        raise RetirementError(f"plugin descriptor is missing: {contract['descriptor']}") from error
    actual_components = plugin_components(descriptor_source)
    if actual_components != list(EXPECTED_PLUGIN_COMPONENTS):
        raise RetirementError(
            "default plugin phases must be exactly typed-control, natural-symbolic, "
            "then frontend symbolic-equality safety; "
            f"found {actual_components}"
        )
    return len(files)


def forbidden_jar_entry(entry: str, prefixes: Sequence[str]) -> Optional[str]:
    normalized = entry.lstrip("/")
    if not normalized.endswith(".class"):
        return None
    stem = normalized[:-len(".class")]
    for prefix in prefixes:
        # These are deliberately prefixes rather than exact class names:
        # Scala emits companion/nested classes and several deleted files also
        # declared package-private top-level identity records.
        if stem.startswith(prefix):
            return prefix
    return None


def validate_jars(jars: Sequence[Path], contract: Mapping[str, Any]) -> int:
    violations: List[str] = []
    for jar in jars:
        if not jar.is_file():
            raise RetirementError(f"JAR does not exist: {jar}")
        try:
            with zipfile.ZipFile(str(jar)) as archive:
                for entry in archive.namelist():
                    prefix = forbidden_jar_entry(entry, contract["jar_prefixes"])
                    if prefix is not None:
                        violations.append(f"{jar}: {entry} (retired prefix {prefix})")
        except zipfile.BadZipFile as error:
            raise RetirementError(f"invalid JAR/ZIP archive: {jar}") from error
    if violations:
        raise RetirementError(
            "retired production classes were found in packaged JARs:\n  "
            + "\n  ".join(violations)
        )
    return len(jars)


def write_plugin(root: Path, components: Sequence[str]) -> None:
    path = root / "morphplugin/src/main/scala/morphhdl/compiler/MorphHdlPlugin.scala"
    path.parent.mkdir(parents=True, exist_ok=True)
    entries = ",\n      ".join(f"new {component}(global)" for component in components)
    path.write_text(
        "package morphhdl.compiler\n"
        "final class MorphHdlPlugin {\n"
        "  override val components: List[PluginComponent] =\n"
        f"    List(\n      {entries}\n    )\n"
        "}\n",
        encoding="utf-8",
    )


def fixture_path(prefix: str, identifier: str) -> str:
    if prefix.endswith("/"):
        return prefix + "scala/RetirementGuard" + re.sub(r"[^A-Za-z0-9]", "", identifier) + ".scala"
    return prefix


def expect_source_failure(root: Path, contract: Mapping[str, Any], label: str) -> None:
    try:
        validate_sources(root, contract)
    except RetirementError:
        return
    raise RetirementError(f"self-test expected source failure: {label}")


def self_test(manifest: Mapping[str, Any]) -> None:
    contract = compile_contract(manifest)
    narrowed_manifest = json.loads(json.dumps(manifest))
    narrowed_manifest["forbidden_source_rules"][0]["path_prefixes"] = [
        "morphplugin/src/main/"
    ]
    try:
        compile_contract(narrowed_manifest)
    except RetirementError:
        pass
    else:
        raise RetirementError(
            "self-test expected closed-rule rejection after narrowing a source prefix"
        )
    with tempfile.TemporaryDirectory(prefix="morphhdl-production-retirement-") as directory:
        root = Path(directory)
        for source_root in contract["roots"]:
            (root / source_root).mkdir(parents=True, exist_ok=True)
        safe = root / "core/src/main/scala/SafeTypedCarrier.scala"
        safe.parent.mkdir(parents=True, exist_ok=True)
        safe.write_text("object SafeTypedCarrier\n", encoding="utf-8")
        historical = root / "morphhdl/src/test/scala/HistoricalNativeIntShadow.scala"
        historical.parent.mkdir(parents=True, exist_ok=True)
        historical.write_text(
            "object HistoricalNativeIntShadow { val shadowInt = true }\n",
            encoding="utf-8",
        )
        write_plugin(root, EXPECTED_PLUGIN_COMPONENTS)
        validate_sources(root, contract)

        for relative in contract["removed"]:
            removed = root / relative
            removed.parent.mkdir(parents=True, exist_ok=True)
            removed.write_text("object Retired\n", encoding="utf-8")
            expect_source_failure(root, contract, f"retired file resurrection: {relative}")
            removed.unlink()

        for rule in contract["rules"]:
            path = root / fixture_path(rule["prefixes"][0], rule["id"])
            path.parent.mkdir(parents=True, exist_ok=True)
            previous = path.read_text(encoding="utf-8") if path.exists() else None
            path.write_text(rule["fixture"] + "\n", encoding="utf-8")
            expect_source_failure(root, contract, rule["id"])
            if previous is None:
                path.unlink()
            else:
                path.write_text(previous, encoding="utf-8")

        write_plugin(root, list(EXPECTED_PLUGIN_COMPONENTS) + ["UnexpectedLegacyPhase"])
        expect_source_failure(root, contract, "extra default plugin phase")
        write_plugin(root, EXPECTED_PLUGIN_COMPONENTS)
        validate_sources(root, contract)

        good_jar = root / "good.jar"
        with zipfile.ZipFile(str(good_jar), "w") as archive:
            archive.writestr("spinal/core/ElabInt.class", b"typed")
        validate_jars([good_jar], contract)

        for index, prefix in enumerate(contract["jar_prefixes"]):
            bad_jar = root / f"bad-{index}.jar"
            with zipfile.ZipFile(str(bad_jar), "w") as archive:
                archive.writestr(prefix + "$Retired.class", b"retired")
            try:
                validate_jars([bad_jar], contract)
            except RetirementError:
                pass
            else:
                raise RetirementError(
                    f"self-test expected JAR failure: retired prefix {prefix}"
                )


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
        if arguments.self_test:
            self_test(manifest)
            print("Increment 53g production-retirement guard self-test passed")
            return 0

        contract = compile_contract(manifest)
        source_count = validate_sources(root, contract)
        jars = [Path(value).resolve() for value in arguments.jar]
        if arguments.require_jar and not jars:
            raise RetirementError("--require-jar needs at least one --jar argument")
        jar_count = validate_jars(jars, contract)
        print("Increment 53g production-retirement boundary is valid")
        print(f"  production sources checked: {source_count}")
        print(f"  packaged JARs checked: {jar_count}")
        print(f"  retired paths absent: {len(contract['removed'])}")
        print(f"  negative rules enforced: {len(contract['rules'])}")
        return 0
    except RetirementError as error:
        print(f"Increment 53g production-retirement guard failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
