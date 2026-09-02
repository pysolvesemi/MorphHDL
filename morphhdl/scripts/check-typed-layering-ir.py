#!/usr/bin/env python3
"""Enforce Increment 54 typed layering and canonical-IR ownership."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path
from typing import Any, Dict, Iterable, List, Mapping, Optional, Sequence, Tuple


DEFAULT_MANIFEST = "morphhdl/contracts/increment-54-typed-layering-ir.contract"
EXPECTED_REPOSITORY = "pysolvesemi/MorphHDL"
EXPECTED_CONTRACT_SHA256 = "44e1df89aaafccc843c9fb696441940b20232f95d751b1fb9c973f6df8edb09c"
EXPECTED_PLUGIN_COMPONENTS = (
    "MorphHdlTypedElaborationControlComponent",
    "MorphHdlNaturalSymbolicConditionalComponent",
    "MorphHdlFrontendSymbolicEqualitySafetyComponent",
)
EXPECTED_TOP_LEVEL_KEYS = {
    "schema_version",
    "repository",
    "production_source_roots",
    "source_extensions",
    "low_level_source_roots",
    "build_graph",
    "required_files",
    "single_owner_declarations",
    "removed_paths",
    "forbidden_source_rules",
    "expected_plugin_components",
    "plugin_descriptor",
    "forbidden_jar_class_prefixes",
    "idslplugin_compiler_isolation",
}
EXPECTED_REQUIRED_FILE_KEYS = {"path", "required_markers"}
EXPECTED_OWNER_KEYS = {"id", "pattern", "owner_path", "path_prefixes"}
EXPECTED_BUILD_GRAPH_KEYS = {
    "canonical_ir_artifact",
    "sbt_file",
    "mill_file",
    "modules",
    "publication_contracts",
}
EXPECTED_CANONICAL_IR_ARTIFACT_KEYS = {
    "id",
    "artifact_name",
    "compile_external_dependencies",
    "test_external_dependencies",
}
EXPECTED_BUILD_MODULE_KEYS = {
    "id",
    "compile_dependencies",
    "test_dependencies",
    "published",
}
EXPECTED_PUBLICATION_KEYS = {
    "id",
    "sbt_name",
    "mill_artifact_name",
    "version_marker",
}
EXPECTED_RULE_KEYS = {
    "id",
    "description",
    "pattern",
    "self_test_source",
    "path_prefixes",
}
EXPECTED_IDSLPLUGIN_ISOLATION_KEYS = {
    "identity_class_entry",
    "forbidden_class_byte_fragments",
}
EXPECTED_CLASS_BYTE_FRAGMENT_KEYS = {"id", "utf8"}
EXPECTED_IDSLPLUGIN_CLASS_BYTE_FRAGMENTS = (
    ("elab-bool-carrier", "ElabBool"),
    ("elab-int-carrier", "ElabInt"),
    ("frontend-gen-index-carrier", "GenIndex"),
    ("frontend-hdl-bool-carrier", "HdlBool"),
    ("frontend-hdl-int-carrier", "HdlInt"),
    ("morph-diagnostic", "MORPH-"),
    ("morphhdl-dotted-package", "morphhdl."),
    ("morphhdl-internal-package", "morphhdl/"),
)
CANONICAL_IR_MODEL_PATH = "morphir/src/main/scala/morphhdl/ir/v1/Model.scala"
CANONICAL_IR_VALIDATION_PATH = (
    "morphir/src/main/scala/morphhdl/ir/v1/Validation.scala"
)
EXPECTED_DRIVER_METADATA_MARKERS = (
    "attributes: Vector[IrAttribute] = Vector.empty",
    "comments: Vector[IrComment] = Vector.empty",
)
EXPECTED_VALIDATION_PUBLIC_MARKERS = (
    "val DefaultMaximumDiagnostics: Int = 256",
    'val DiagnosticLimitReached = "MORPH-IR-V1-DIAGNOSTIC-LIMIT-REACHED"',
    'val DriverIdInvalid = "MORPH-IR-V1-DRIVER-ID-INVALID"',
    'val DriverIdMissing = "MORPH-IR-V1-DRIVER-ID-MISSING"',
    'val DriverScopeUnresolved = "MORPH-IR-V1-DRIVER-SCOPE-UNRESOLVED"',
    'val DriverTargetNotVisible = "MORPH-IR-V1-DRIVER-TARGET-NOT-VISIBLE"',
    'val ExactEvaluationLimitReached = "MORPH-IR-V1-EXACT-EVALUATION-LIMIT-REACHED"',
    "val MaximumExactEvaluationCases: Int = 65536",
    "val MaximumParameterDomainSize: Int = 65536",
    'val ParameterDomainTooLarge = "MORPH-IR-V1-PARAMETER-DOMAIN-TOO-LARGE"',
    'val ReferenceIdInvalid = "MORPH-IR-V1-REFERENCE-ID-INVALID"',
    'val ReferenceIdMissing = "MORPH-IR-V1-REFERENCE-ID-MISSING"',
    'val RtlReferenceNotVisible = "MORPH-IR-V1-RTL-REFERENCE-NOT-VISIBLE"',
)
IGNORED_DISCOVERY_DIRECTORIES = {
    ".bloop",
    ".git",
    ".metals",
    ".mill-ammonite",
    ".scala-build",
    "out",
    "target",
}


class LayeringError(RuntimeError):
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
        raise LayeringError(result.stderr.strip() or "not inside a Git repository")
    return Path(result.stdout.strip()).resolve()


def clean_path(value: Any, role: str) -> str:
    if not isinstance(value, str) or not value:
        raise LayeringError(f"{role} must be a non-empty string")
    path = Path(value)
    if path.is_absolute() or value.startswith("./") or ".." in path.parts:
        raise LayeringError(f"{role} is not repository-relative: {value}")
    if path.as_posix() != value or value.endswith("/"):
        raise LayeringError(f"{role} is not normalized POSIX spelling: {value}")
    return value


def string_list(
    value: Any,
    role: str,
    *,
    paths: bool = False,
    path_prefixes: bool = False,
    allow_empty: bool = False,
) -> List[str]:
    if not isinstance(value, list) or (not value and not allow_empty):
        suffix = "array" if allow_empty else "non-empty array"
        raise LayeringError(f"{role} must be a {suffix}")
    result: List[str] = []
    for index, item in enumerate(value):
        if not isinstance(item, str) or not item:
            raise LayeringError(f"{role}[{index}] must be a non-empty string")
        if path_prefixes:
            normalized = item[:-1] if item.endswith("/") else item
            clean_path(normalized, f"{role}[{index}]")
            if not item.endswith("/"):
                raise LayeringError(f"{role}[{index}] must end in '/': {item}")
            result.append(item)
        else:
            result.append(clean_path(item, f"{role}[{index}]") if paths else item)
    if result != sorted(set(result)):
        raise LayeringError(f"{role} must be sorted and contain no duplicates")
    return result


def load_manifest(path: Path) -> Mapping[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as error:
        raise LayeringError(f"typed-layering manifest is missing: {path}") from error
    except json.JSONDecodeError as error:
        raise LayeringError(f"typed-layering manifest is invalid JSON: {error}") from error
    if not isinstance(value, dict):
        raise LayeringError("typed-layering manifest root must be an object")
    return value


def manifest_digest(manifest: Mapping[str, Any]) -> str:
    canonical = json.dumps(
        manifest,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=True,
    ).encode("utf-8")
    return hashlib.sha256(canonical).hexdigest()


def source_prefix(path: str, roots: Sequence[str]) -> Optional[str]:
    for root in roots:
        if path == root or path.startswith(root + "/"):
            return root
    return None


def compile_contract(manifest: Mapping[str, Any]) -> Dict[str, Any]:
    if set(manifest) != EXPECTED_TOP_LEVEL_KEYS:
        raise LayeringError(
            "typed-layering manifest keys differ from the closed schema: "
            f"{sorted(set(manifest) ^ EXPECTED_TOP_LEVEL_KEYS)}"
        )
    if manifest.get("schema_version") != 1:
        raise LayeringError("schema_version must be 1")
    if manifest.get("repository") != EXPECTED_REPOSITORY:
        raise LayeringError(f"repository must be {EXPECTED_REPOSITORY}")
    if manifest_digest(manifest) != EXPECTED_CONTRACT_SHA256:
        raise LayeringError(
            "typed-layering manifest differs from the closed Increment 54 contract"
        )

    roots = string_list(
        manifest.get("production_source_roots"),
        "production_source_roots",
        paths=True,
    )
    extensions = string_list(manifest.get("source_extensions"), "source_extensions")
    if extensions != [".java", ".scala"]:
        raise LayeringError("source_extensions must be exactly .java and .scala")
    low_level_roots = string_list(
        manifest.get("low_level_source_roots"),
        "low_level_source_roots",
        paths=True,
    )
    unknown_low_level = sorted(set(low_level_roots) - set(roots))
    if unknown_low_level:
        raise LayeringError(
            "low_level_source_roots contains unscanned roots: "
            + ", ".join(unknown_low_level)
        )

    raw_build = manifest.get("build_graph")
    if not isinstance(raw_build, dict) or set(raw_build) != EXPECTED_BUILD_GRAPH_KEYS:
        raise LayeringError("build_graph does not match the closed build schema")
    sbt_file = clean_path(raw_build.get("sbt_file"), "build_graph.sbt_file")
    mill_file = clean_path(raw_build.get("mill_file"), "build_graph.mill_file")
    if sbt_file == mill_file:
        raise LayeringError("SBT and Mill build files must be distinct")

    raw_modules = raw_build.get("modules")
    if not isinstance(raw_modules, list) or not raw_modules:
        raise LayeringError("build_graph.modules must be a non-empty array")
    modules: List[Dict[str, Any]] = []
    module_ids: List[str] = []
    for index, raw in enumerate(raw_modules):
        if not isinstance(raw, dict) or set(raw) != EXPECTED_BUILD_MODULE_KEYS:
            raise LayeringError(
                f"build_graph.modules[{index}] does not match the closed module schema"
            )
        identifier = raw.get("id")
        if not isinstance(identifier, str) or not re.fullmatch(
            r"[A-Za-z_][A-Za-z0-9_]*", identifier
        ):
            raise LayeringError(f"build_graph.modules[{index}].id is invalid")
        compile_dependencies = string_list(
            raw.get("compile_dependencies"),
            f"build_graph.modules[{index}].compile_dependencies",
            allow_empty=True,
        )
        test_dependencies = string_list(
            raw.get("test_dependencies"),
            f"build_graph.modules[{index}].test_dependencies",
            allow_empty=True,
        )
        published = raw.get("published")
        if not isinstance(published, bool):
            raise LayeringError(
                f"build_graph.modules[{index}].published must be Boolean"
            )
        module_ids.append(identifier)
        modules.append(
            {
                "id": identifier,
                "compile": compile_dependencies,
                "test": test_dependencies,
                "published": published,
            }
        )
    if module_ids != sorted(set(module_ids)):
        raise LayeringError("build_graph.modules must be sorted by unique id")
    known_modules = set(module_ids)
    for module in modules:
        dependencies = set(module["compile"]) | set(module["test"])
        unknown_dependencies = sorted(dependencies - known_modules)
        if unknown_dependencies:
            raise LayeringError(
                f"build module {module['id']} has unknown dependencies: "
                + ", ".join(unknown_dependencies)
            )
        if module["id"] in dependencies:
            raise LayeringError(f"build module {module['id']} depends on itself")

    raw_publications = raw_build.get("publication_contracts")
    if not isinstance(raw_publications, list) or not raw_publications:
        raise LayeringError("build_graph.publication_contracts must be non-empty")
    publications: List[Dict[str, str]] = []
    publication_ids: List[str] = []
    for index, raw in enumerate(raw_publications):
        if not isinstance(raw, dict) or set(raw) != EXPECTED_PUBLICATION_KEYS:
            raise LayeringError(
                f"build_graph.publication_contracts[{index}] has invalid schema"
            )
        values = {
            key: raw.get(key)
            for key in ("id", "sbt_name", "mill_artifact_name", "version_marker")
        }
        if not all(isinstance(value, str) and value for value in values.values()):
            raise LayeringError(
                f"build_graph.publication_contracts[{index}] has an empty field"
            )
        if values["id"] not in known_modules:
            raise LayeringError(
                f"publication contract names an unknown module: {values['id']}"
            )
        module = modules[module_ids.index(values["id"])]
        if not module["published"]:
            raise LayeringError(
                f"publication contract names unpublished module: {values['id']}"
            )
        publication_ids.append(values["id"])
        publications.append(values)
    if publication_ids != sorted(set(publication_ids)):
        raise LayeringError(
            "build_graph.publication_contracts must be sorted by unique id"
        )

    raw_ir_artifact = raw_build.get("canonical_ir_artifact")
    if (
        not isinstance(raw_ir_artifact, dict)
        or set(raw_ir_artifact) != EXPECTED_CANONICAL_IR_ARTIFACT_KEYS
    ):
        raise LayeringError(
            "build_graph.canonical_ir_artifact does not match the closed schema"
        )
    ir_artifact_id = raw_ir_artifact.get("id")
    ir_artifact_name = raw_ir_artifact.get("artifact_name")
    if ir_artifact_id != "morphir":
        raise LayeringError("canonical_ir_artifact.id must be morphir")
    if not isinstance(ir_artifact_name, str) or re.fullmatch(
        r"[a-z0-9]+(?:-[a-z0-9]+)*", ir_artifact_name
    ) is None:
        raise LayeringError(
            "canonical_ir_artifact.artifact_name must be explicit lowercase Maven spelling"
        )
    ir_compile_external_dependencies = string_list(
        raw_ir_artifact.get("compile_external_dependencies"),
        "build_graph.canonical_ir_artifact.compile_external_dependencies",
    )
    ir_test_external_dependencies = string_list(
        raw_ir_artifact.get("test_external_dependencies"),
        "build_graph.canonical_ir_artifact.test_external_dependencies",
    )
    if ir_compile_external_dependencies != ["org.scala-lang:scala-library"]:
        raise LayeringError(
            "canonical IR compile dependency must be exactly scala-library"
        )
    if ir_test_external_dependencies != ["org.scalatest::scalatest"]:
        raise LayeringError(
            "canonical IR test dependency must be exactly cross-built ScalaTest"
        )
    ir_publication = next(
        (value for value in publications if value["id"] == ir_artifact_id),
        None,
    )
    if ir_publication is None:
        raise LayeringError("canonical IR artifact has no publication contract")
    if (
        ir_publication["sbt_name"] != ir_artifact_name
        or ir_publication["mill_artifact_name"] != ir_artifact_name
    ):
        raise LayeringError(
            "canonical IR SBT and Mill publication identities must match artifact_name"
        )
    canonical_ir_artifact = {
        "id": ir_artifact_id,
        "name": ir_artifact_name,
        "compile_external": ir_compile_external_dependencies,
        "test_external": ir_test_external_dependencies,
    }

    raw_required = manifest.get("required_files")
    if not isinstance(raw_required, list) or not raw_required:
        raise LayeringError("required_files must be a non-empty array")
    required_files: List[Dict[str, Any]] = []
    required_paths: List[str] = []
    for index, raw in enumerate(raw_required):
        if not isinstance(raw, dict) or set(raw) != EXPECTED_REQUIRED_FILE_KEYS:
            raise LayeringError(
                f"required_files[{index}] does not match the closed file schema"
            )
        path = clean_path(raw.get("path"), f"required_files[{index}].path")
        if source_prefix(path, roots) is None:
            raise LayeringError(f"required file is outside production roots: {path}")
        if Path(path).suffix not in extensions:
            raise LayeringError(f"required file has an unscanned extension: {path}")
        markers = string_list(
            raw.get("required_markers"),
            f"required_files[{index}].required_markers",
        )
        required_paths.append(path)
        required_files.append({"path": path, "markers": markers})
    if required_paths != sorted(set(required_paths)):
        raise LayeringError("required_files must be sorted by unique path")
    required_by_path = {
        value["path"]: set(value["markers"]) for value in required_files
    }
    missing_driver_markers = set(EXPECTED_DRIVER_METADATA_MARKERS) - required_by_path.get(
        CANONICAL_IR_MODEL_PATH,
        set(),
    )
    if missing_driver_markers:
        raise LayeringError(
            "canonical IR model contract is missing Driver metadata markers: "
            + ", ".join(sorted(missing_driver_markers))
        )
    missing_validation_markers = set(
        EXPECTED_VALIDATION_PUBLIC_MARKERS
    ) - required_by_path.get(CANONICAL_IR_VALIDATION_PATH, set())
    if missing_validation_markers:
        raise LayeringError(
            "canonical IR validation contract is missing selected public limits or diagnostics: "
            + ", ".join(sorted(missing_validation_markers))
        )

    raw_owners = manifest.get("single_owner_declarations")
    if not isinstance(raw_owners, list) or not raw_owners:
        raise LayeringError("single_owner_declarations must be a non-empty array")
    owners: List[Dict[str, Any]] = []
    owner_ids: List[str] = []
    for index, raw in enumerate(raw_owners):
        if not isinstance(raw, dict) or set(raw) != EXPECTED_OWNER_KEYS:
            raise LayeringError(
                f"single_owner_declarations[{index}] does not match the closed owner schema"
            )
        identifier = raw.get("id")
        pattern = raw.get("pattern")
        if not isinstance(identifier, str) or not identifier:
            raise LayeringError(f"single_owner_declarations[{index}].id is empty")
        if not isinstance(pattern, str) or not pattern:
            raise LayeringError(f"single_owner_declarations[{index}].pattern is empty")
        try:
            regex = re.compile(pattern)
        except re.error as error:
            raise LayeringError(f"invalid owner regex {identifier}: {error}") from error
        owner_path = clean_path(
            raw.get("owner_path"),
            f"single_owner_declarations[{index}].owner_path",
        )
        if owner_path not in required_paths:
            raise LayeringError(
                f"single owner {identifier} is not anchored by required_files: {owner_path}"
            )
        prefixes = string_list(
            raw.get("path_prefixes"),
            f"single_owner_declarations[{index}].path_prefixes",
            path_prefixes=True,
            allow_empty=True,
        )
        production_prefixes = {root + "/" for root in roots}
        if not set(prefixes).issubset(production_prefixes):
            raise LayeringError(f"single owner {identifier} has an unscanned path prefix")
        owner_ids.append(identifier)
        owners.append(
            {
                "id": identifier,
                "regex": regex,
                "owner_path": owner_path,
                "prefixes": prefixes,
            }
        )
    if owner_ids != sorted(set(owner_ids)):
        raise LayeringError("single_owner_declarations must be sorted by unique id")

    removed = string_list(manifest.get("removed_paths"), "removed_paths", paths=True)
    for path in removed:
        if source_prefix(path, roots) is None:
            raise LayeringError(f"removed path is outside production roots: {path}")

    raw_rules = manifest.get("forbidden_source_rules")
    if not isinstance(raw_rules, list) or not raw_rules:
        raise LayeringError("forbidden_source_rules must be a non-empty array")
    rules: List[Dict[str, Any]] = []
    rule_ids: List[str] = []
    production_prefixes = {root + "/" for root in roots}
    for index, raw in enumerate(raw_rules):
        if not isinstance(raw, dict) or set(raw) != EXPECTED_RULE_KEYS:
            raise LayeringError(
                f"forbidden_source_rules[{index}] does not match the closed rule schema"
            )
        identifier = raw.get("id")
        description = raw.get("description")
        pattern = raw.get("pattern")
        fixture = raw.get("self_test_source")
        if not all(
            isinstance(item, str) and item.strip()
            for item in (identifier, description, pattern, fixture)
        ):
            raise LayeringError(f"forbidden_source_rules[{index}] has an empty field")
        try:
            regex = re.compile(pattern, re.MULTILINE)
        except re.error as error:
            raise LayeringError(f"invalid source regex {identifier}: {error}") from error
        if regex.search(fixture) is None:
            raise LayeringError(f"source rule {identifier} misses its self-test fixture")
        prefixes = string_list(
            raw.get("path_prefixes"),
            f"forbidden_source_rules[{index}].path_prefixes",
            path_prefixes=True,
        )
        if not set(prefixes).issubset(production_prefixes):
            raise LayeringError(f"source rule {identifier} has an unscanned path prefix")
        rule_ids.append(identifier)
        rules.append(
            {
                "id": identifier,
                "description": description,
                "regex": regex,
                "fixture": fixture,
                "prefixes": prefixes,
            }
        )
    if len(rule_ids) != len(set(rule_ids)):
        raise LayeringError("forbidden_source_rules contains duplicate IDs")
    rule_by_id = {rule["id"]: rule for rule in rules}
    low_edge = rule_by_id.get("low-level-frontend-edge")
    expected_low_prefixes = [root + "/" for root in low_level_roots]
    if low_edge is None or low_edge["prefixes"] != expected_low_prefixes:
        raise LayeringError(
            "low-level-frontend-edge must cover every declared low-level source root"
        )
    compiler_isolation_rule = rule_by_id.get(
        "idslplugin-morph-compiler-isolation"
    )
    if (
        compiler_isolation_rule is None
        or compiler_isolation_rule["prefixes"] != ["idslplugin/src/main/"]
    ):
        raise LayeringError(
            "idslplugin-morph-compiler-isolation must cover exactly idslplugin/src/main/"
        )
    for label, fixture in (
        ("MorphHDL package", "import morphhdl.compiler.Leak"),
        ("Morph-specific diagnostic", 'val code = "MORPH-COMPILER-LEAK"'),
        ("symbolic carrier", "val value: spinal.core.ElabInt = null"),
    ):
        if compiler_isolation_rule["regex"].search(fixture) is None:
            raise LayeringError(
                "idslplugin-morph-compiler-isolation misses " + label
            )
    sidecar = rule_by_id.get("obsolete-parameterized-sidecar-symbol")
    if sidecar is None or sidecar["prefixes"] != [root + "/" for root in roots]:
        raise LayeringError(
            "obsolete-parameterized-sidecar-symbol must cover every production root"
        )

    components = manifest.get("expected_plugin_components")
    if components != list(EXPECTED_PLUGIN_COMPONENTS):
        raise LayeringError(
            "expected_plugin_components must be the three ordered Increment 54 phases"
        )
    descriptor = clean_path(manifest.get("plugin_descriptor"), "plugin_descriptor")
    if source_prefix(descriptor, roots) is None:
        raise LayeringError("plugin_descriptor is outside production roots")

    jar_prefixes = string_list(
        manifest.get("forbidden_jar_class_prefixes"),
        "forbidden_jar_class_prefixes",
    )
    for index, prefix in enumerate(jar_prefixes):
        if prefix.startswith("/") or prefix.endswith("/") or ".." in prefix.split("/"):
            raise LayeringError(
                f"forbidden_jar_class_prefixes[{index}] is not a class prefix: {prefix}"
            )
        if not re.fullmatch(r"[A-Za-z0-9_$/]+", prefix):
            raise LayeringError(
                f"forbidden_jar_class_prefixes[{index}] contains invalid characters: {prefix}"
            )

    raw_compiler_isolation = manifest.get("idslplugin_compiler_isolation")
    if (
        not isinstance(raw_compiler_isolation, dict)
        or set(raw_compiler_isolation) != EXPECTED_IDSLPLUGIN_ISOLATION_KEYS
    ):
        raise LayeringError(
            "idslplugin_compiler_isolation does not match the closed schema"
        )
    identity_class_entry = clean_path(
        raw_compiler_isolation.get("identity_class_entry"),
        "idslplugin_compiler_isolation.identity_class_entry",
    )
    if (
        identity_class_entry != "spinal/idslplugin/IdslPlugin.class"
        or not identity_class_entry.endswith(".class")
    ):
        raise LayeringError(
            "idslplugin compiler isolation must use the canonical plugin identity class"
        )
    raw_fragments = raw_compiler_isolation.get(
        "forbidden_class_byte_fragments"
    )
    if not isinstance(raw_fragments, list) or not raw_fragments:
        raise LayeringError(
            "idslplugin forbidden_class_byte_fragments must be a non-empty array"
        )
    class_byte_fragments: List[Dict[str, Any]] = []
    fragment_pairs: List[Tuple[str, str]] = []
    for index, raw in enumerate(raw_fragments):
        if (
            not isinstance(raw, dict)
            or set(raw) != EXPECTED_CLASS_BYTE_FRAGMENT_KEYS
        ):
            raise LayeringError(
                "idslplugin forbidden class-byte fragment schema differs at "
                f"index {index}"
            )
        identifier = raw.get("id")
        value = raw.get("utf8")
        if not isinstance(identifier, str) or re.fullmatch(
            r"[a-z0-9]+(?:-[a-z0-9]+)*", identifier
        ) is None:
            raise LayeringError(
                f"idslplugin class-byte fragment {index} has an invalid id"
            )
        if not isinstance(value, str) or not value or "\x00" in value:
            raise LayeringError(
                f"idslplugin class-byte fragment {identifier} is empty or unsafe"
            )
        try:
            encoded = value.encode("ascii")
        except UnicodeEncodeError as error:
            raise LayeringError(
                f"idslplugin class-byte fragment {identifier} must be ASCII/UTF-8 stable"
            ) from error
        fragment_pairs.append((identifier, value))
        class_byte_fragments.append(
            {"id": identifier, "text": value, "bytes": encoded}
        )
    if tuple(fragment_pairs) != EXPECTED_IDSLPLUGIN_CLASS_BYTE_FRAGMENTS:
        raise LayeringError(
            "idslplugin forbidden class-byte fragments differ from the closed compiler-isolation set"
        )

    return {
        "roots": roots,
        "extensions": set(extensions),
        "low_level_roots": low_level_roots,
        "build": {
            "sbt_file": sbt_file,
            "mill_file": mill_file,
            "modules": modules,
            "publications": publications,
            "canonical_ir_artifact": canonical_ir_artifact,
        },
        "required_files": required_files,
        "owners": owners,
        "removed": removed,
        "rules": rules,
        "descriptor": descriptor,
        "jar_prefixes": jar_prefixes,
        "idslplugin_isolation": {
            "identity_entry": identity_class_entry,
            "fragments": class_byte_fragments,
        },
    }


def discover_source_roots(root: Path) -> List[str]:
    discovered: List[str] = []
    for directory, child_names, file_names in os.walk(str(root), followlinks=False):
        path = Path(directory)
        # A checked-out Git submodule is a separate repository. In particular,
        # test fixtures may be submodules and may contain their own src/main.
        if path != root and ".git" in file_names:
            child_names[:] = []
            continue
        child_names[:] = sorted(
            child
            for child in child_names
            if child not in IGNORED_DISCOVERY_DIRECTORIES
        )
        if path.name == "main" and path.parent.name == "src":
            discovered.append(path.relative_to(root).as_posix())
            child_names[:] = []
    return sorted(set(discovered))


def production_files(root: Path, contract: Mapping[str, Any]) -> List[Tuple[str, Path]]:
    discovered = discover_source_roots(root)
    expected = list(contract["roots"])
    if discovered != expected:
        unscanned = sorted(set(discovered) - set(expected))
        missing = sorted(set(expected) - set(discovered))
        details: List[str] = []
        if unscanned:
            details.append("unscanned production roots: " + ", ".join(unscanned))
        if missing:
            details.append("missing production roots: " + ", ".join(missing))
        raise LayeringError("closed production-root inventory differs: " + "; ".join(details))

    result: Dict[str, Path] = {}
    extensions = contract["extensions"]
    for source_root in expected:
        directory = root / source_root
        for path in directory.rglob("*"):
            if not path.is_file() or path.suffix not in extensions:
                continue
            relative = path.relative_to(root).as_posix()
            if relative in result:
                raise LayeringError(f"production source discovered twice: {relative}")
            result[relative] = path
    return sorted(result.items())


def path_selected(path: str, prefixes: Iterable[str]) -> bool:
    prefixes = list(prefixes)
    return not prefixes or any(path.startswith(prefix) for prefix in prefixes)


def plugin_components(source: str) -> List[str]:
    marker = "override val components: List[PluginComponent]"
    start = source.find(marker)
    if start < 0:
        raise LayeringError("MorphHdlPlugin has no explicit components declaration")
    list_start = source.find("List(", start)
    if list_start < 0:
        raise LayeringError("MorphHdlPlugin components are not an explicit List")
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
        raise LayeringError("MorphHdlPlugin components List is unterminated")
    body = source[list_start : end + 1]
    components = re.findall(
        r"\bnew\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(\s*global\s*\)",
        body,
    )
    residue = re.sub(
        r"\bnew\s+[A-Za-z_][A-Za-z0-9_]*\s*\(\s*global\s*\)\s*,?",
        "",
        body[len("List(") : -1],
    )
    residue = re.sub(r"\s|,", "", residue)
    if residue:
        raise LayeringError(
            "MorphHdlPlugin components List contains a non-canonical entry: " + residue
        )
    return components


def balanced_content(source: str, open_index: int, role: str) -> Tuple[str, int]:
    opening = source[open_index] if 0 <= open_index < len(source) else ""
    closing_by_opening = {"(": ")", "{": "}", "[": "]"}
    closing = closing_by_opening.get(opening)
    if closing is None:
        raise LayeringError(f"{role} has no balanced opening delimiter")
    depth = 1
    index = open_index + 1
    while index < len(source):
        if source.startswith("//", index):
            newline = source.find("\n", index + 2)
            index = len(source) if newline < 0 else newline + 1
            continue
        if source.startswith("/*", index):
            end_comment = source.find("*/", index + 2)
            if end_comment < 0:
                raise LayeringError(f"{role} contains an unterminated block comment")
            index = end_comment + 2
            continue
        if source.startswith('"""', index):
            end_string = source.find('"""', index + 3)
            if end_string < 0:
                raise LayeringError(f"{role} contains an unterminated triple string")
            index = end_string + 3
            continue
        if source[index] in {'"', "'"}:
            quote = source[index]
            index += 1
            while index < len(source):
                if source[index] == "\\":
                    index += 2
                elif source[index] == quote:
                    index += 1
                    break
                else:
                    index += 1
            else:
                raise LayeringError(f"{role} contains an unterminated string")
            continue
        if source[index] == opening:
            depth += 1
        elif source[index] == closing:
            depth -= 1
            if depth == 0:
                return source[open_index + 1 : index], index
        index += 1
    raise LayeringError(f"{role} is unterminated")


def split_top_level_commas(value: str, role: str) -> List[str]:
    items: List[str] = []
    start = 0
    depths = {"(": 0, "[": 0, "{": 0}
    closing_to_opening = {")": "(", "]": "[", "}": "{"}
    index = 0
    quote: Optional[str] = None
    while index < len(value):
        character = value[index]
        if quote is not None:
            if character == "\\":
                index += 2
                continue
            if character == quote:
                quote = None
        elif character in {'"', "'"}:
            quote = character
        elif character in depths:
            depths[character] += 1
        elif character in closing_to_opening:
            opening = closing_to_opening[character]
            depths[opening] -= 1
            if depths[opening] < 0:
                raise LayeringError(f"{role} has an unmatched delimiter")
        elif character == "," and not any(depths.values()):
            item = value[start:index].strip()
            if item:
                items.append(item)
            start = index + 1
        index += 1
    if quote is not None or any(depths.values()):
        raise LayeringError(f"{role} has an unterminated value")
    final = value[start:].strip()
    if final:
        items.append(final)
    return items


def unique_dependencies(values: Sequence[str], role: str) -> List[str]:
    if len(values) != len(set(values)):
        raise LayeringError(f"{role} contains duplicate dependencies")
    return sorted(values)


def sbt_external_dependencies(
    block: str,
    module: str,
) -> Optional[Dict[str, List[str]]]:
    declarations = list(
        re.finditer(
            r"\blibraryDependencies\s*(\+\+=|\+=|:=)\s*",
            block,
        )
    )
    if not declarations:
        return None
    if len(declarations) != 1 or declarations[0].group(1) != ":=":
        raise LayeringError(
            f"SBT {module} external dependency boundary must be one exact := Seq(...)"
        )
    sequence_start = declarations[0].end()
    sequence = re.match(r"Seq\s*\(", block[sequence_start:])
    if sequence is None:
        raise LayeringError(
            f"SBT {module} external dependency boundary must use Seq(...)"
        )
    open_index = block.find("(", sequence_start, sequence_start + sequence.end())
    content, _ = balanced_content(
        block,
        open_index,
        f"SBT {module}.libraryDependencies",
    )
    compile_dependencies: List[str] = []
    test_dependencies: List[str] = []
    for item in split_top_level_commas(
        content,
        f"SBT {module}.libraryDependencies",
    ):
        dependency = re.fullmatch(
            r'"([A-Za-z0-9_.-]+)"\s*(%%|%)\s*'
            r'"([A-Za-z0-9_.-]+)"\s*%\s*'
            r'([A-Za-z_][A-Za-z0-9_.]*)'
            r'(?:\s*%\s*(Test|"test"))?',
            item,
        )
        if dependency is None:
            raise LayeringError(
                f"SBT {module} has a non-canonical external dependency: {item}"
            )
        group, cross, artifact, version, configuration = dependency.groups()
        coordinate = group + ("::" if cross == "%%" else ":") + artifact
        if coordinate == "org.scala-lang:scala-library" and version != "scalaVersion.value":
            raise LayeringError(
                f"SBT {module} scala-library must follow scalaVersion.value"
            )
        if coordinate == "org.scalatest::scalatest" and version != "scalatestVersion":
            raise LayeringError(
                f"SBT {module} ScalaTest must follow scalatestVersion"
            )
        target = test_dependencies if configuration is not None else compile_dependencies
        target.append(coordinate)
    return {
        "compile": unique_dependencies(
            compile_dependencies,
            f"SBT {module} external compile dependencies",
        ),
        "test": unique_dependencies(
            test_dependencies,
            f"SBT {module} external test dependencies",
        ),
    }


def parse_sbt_build(source: str) -> Dict[str, Dict[str, Any]]:
    project_pattern = re.compile(
        r"(?m)^lazy\s+val\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*\(project\b"
    )
    matches = list(project_pattern.finditer(source))
    modules: Dict[str, Dict[str, Any]] = {}
    for index, match in enumerate(matches):
        identifier = match.group(1)
        if identifier == "all":
            continue
        end = matches[index + 1].start() if index + 1 < len(matches) else len(source)
        block = source[match.start() : end]
        compile_dependencies: List[str] = []
        test_dependencies: List[str] = []
        for dependency_call in re.finditer(r"\.dependsOn\s*\(", block):
            open_index = block.find("(", dependency_call.start())
            content, _ = balanced_content(
                block,
                open_index,
                f"SBT {identifier}.dependsOn",
            )
            for item in split_top_level_commas(content, f"SBT {identifier}.dependsOn"):
                dependency_match = re.match(r"^([A-Za-z_][A-Za-z0-9_]*)\b(.*)$", item)
                if dependency_match is None:
                    raise LayeringError(
                        f"SBT {identifier} has an unrecognized dependency: {item}"
                    )
                dependency = dependency_match.group(1)
                configuration = dependency_match.group(2).strip()
                if not configuration:
                    compile_dependencies.append(dependency)
                elif re.fullmatch(r"%\s*\"test->compile\"", configuration):
                    test_dependencies.append(dependency)
                else:
                    raise LayeringError(
                        f"SBT {identifier}->{dependency} has unsupported configuration: "
                        + configuration
                    )
        name_matches = re.findall(r"\bname\s*:=\s*\"([^\"]+)\"", block)
        version_matches = re.findall(
            r"\bversion\s*:=\s*([A-Za-z_][A-Za-z0-9_.]*)",
            block,
        )
        module_name_matches = re.findall(
            r'\bmoduleName\s*:=\s*"([^"]+)"',
            block,
        )
        external_dependencies = (
            sbt_external_dependencies(block, identifier)
            if identifier == "morphir"
            else None
        )
        if identifier in modules:
            raise LayeringError(f"SBT declares project twice: {identifier}")
        modules[identifier] = {
            "compile": unique_dependencies(
                compile_dependencies,
                f"SBT {identifier} compile graph",
            ),
            "test": unique_dependencies(
                test_dependencies,
                f"SBT {identifier} test graph",
            ),
            "published": re.search(
                r"\bpublish\s*/\s*skip\s*:=\s*true\b",
                block,
            )
            is None,
            "name": name_matches[-1] if name_matches else None,
            "module_name": (
                module_name_matches[-1] if module_name_matches else None
            ),
            "version": version_matches[-1] if version_matches else None,
            "external_compile": (
                external_dependencies["compile"]
                if external_dependencies is not None
                else None
            ),
            "external_test": (
                external_dependencies["test"]
                if external_dependencies is not None
                else None
            ),
        }
    return modules


def mill_sequence_dependencies(block: str, method: str, module: str) -> List[str]:
    declaration = re.compile(
        rf"(?m)^\s*(?:override\s+)?def\s+{re.escape(method)}\s*=\s*Seq\s*\("
    )
    matches = list(declaration.finditer(block))
    if len(matches) > 1:
        raise LayeringError(f"Mill {module} declares {method} more than once")
    if not matches:
        return []
    open_index = block.find("(", matches[0].start())
    content, _ = balanced_content(block, open_index, f"Mill {module}.{method}")
    dependencies: List[str] = []
    for item in split_top_level_commas(content, f"Mill {module}.{method}"):
        direct = re.fullmatch(
            r"([A-Za-z_][A-Za-z0-9_]*)\s*\(\s*crossScalaVersion\s*\)",
            item,
        )
        reference = re.fullmatch(
            r"moduleRefs\s*\(\s*\"([A-Za-z_][A-Za-z0-9_]*)\"\s*\)",
            item,
        )
        if direct is not None:
            dependencies.append(direct.group(1))
        elif reference is not None:
            dependencies.append(reference.group(1))
        else:
            raise LayeringError(
                f"Mill {module}.{method} has an unrecognized dependency: {item}"
            )
    return unique_dependencies(dependencies, f"Mill {module}.{method}")


def mill_mvn_dependencies(block: str, role: str) -> Optional[List[str]]:
    declaration = re.compile(
        r"(?m)^\s*(?:override\s+)?def\s+mvnDeps\s*=\s*Seq\s*\("
    )
    matches = list(declaration.finditer(block))
    if len(matches) > 1:
        raise LayeringError(f"Mill {role} declares mvnDeps more than once")
    if not matches:
        return None
    open_index = block.find("(", matches[0].start(), matches[0].end())
    content, _ = balanced_content(block, open_index, f"Mill {role}.mvnDeps")
    dependencies: List[str] = []
    for item in split_top_level_commas(content, f"Mill {role}.mvnDeps"):
        dependency = re.fullmatch(
            r'mvn"([A-Za-z0-9_.-]+)(::?)([A-Za-z0-9_.-]+)'
            r'(::?)\$\{([A-Za-z_][A-Za-z0-9_.]*)\}"',
            item,
        )
        if dependency is None:
            raise LayeringError(
                f"Mill {role} has a non-canonical Maven dependency: {item}"
            )
        group, cross, artifact, version_cross, version = dependency.groups()
        if cross != version_cross:
            raise LayeringError(
                f"Mill {role} Maven dependency has inconsistent cross separators: {item}"
            )
        coordinate = group + cross + artifact
        if coordinate == "org.scala-lang:scala-library" and version != "scalaVersion":
            raise LayeringError(
                f"Mill {role} scala-library must follow scalaVersion"
            )
        if coordinate == "org.scalatest::scalatest" and version != "scalatestVersion":
            raise LayeringError(
                f"Mill {role} ScalaTest must follow scalatestVersion"
            )
        dependencies.append(coordinate)
    return unique_dependencies(dependencies, f"Mill {role} Maven dependencies")


def mill_shared_test_dependencies(source: str) -> List[str]:
    module_matches = list(re.finditer(r"(?m)^trait\s+SpinalModule\b[^\n]*\{", source))
    if len(module_matches) != 1:
        raise LayeringError("Mill must declare exactly one SpinalModule test boundary")
    module_open = source.find(
        "{",
        module_matches[0].start(),
        module_matches[0].end(),
    )
    module_block, _ = balanced_content(
        source,
        module_open,
        "Mill SpinalModule",
    )
    test_matches = list(
        re.finditer(r"(?m)^\s*object\s+test\b[^\n]*\{", module_block)
    )
    if len(test_matches) != 1:
        raise LayeringError(
            "Mill SpinalModule must declare exactly one shared test module"
        )
    test_open = module_block.find(
        "{",
        test_matches[0].start(),
        test_matches[0].end(),
    )
    test_block, _ = balanced_content(
        module_block,
        test_open,
        "Mill SpinalModule.test",
    )
    dependencies = mill_mvn_dependencies(test_block, "SpinalModule.test")
    if dependencies is None:
        raise LayeringError("Mill SpinalModule.test has no exact Maven dependency list")
    return dependencies


def parse_mill_build(source: str) -> Dict[str, Dict[str, Any]]:
    cross_pattern = re.compile(
        r"(?m)^object\s+([A-Za-z_][A-Za-z0-9_]*)\s+extends\s+"
        r"Cross\[([A-Za-z_][A-Za-z0-9_]*)\]"
    )
    modules: Dict[str, Dict[str, Any]] = {}
    for match in cross_pattern.finditer(source):
        identifier = match.group(1)
        trait_name = match.group(2)
        trait_match = re.search(
            rf"(?m)^trait\s+{re.escape(trait_name)}\b([^\n]*)",
            source,
        )
        if trait_match is None:
            raise LayeringError(
                f"Mill module {identifier} references missing trait {trait_name}"
            )
        header = trait_match.group(0)
        line_end = source.find("\n", trait_match.start())
        if line_end < 0:
            line_end = len(source)
        open_brace = source.find("{", trait_match.start(), line_end)
        if open_brace >= 0:
            block, _ = balanced_content(
                source,
                open_brace,
                f"Mill trait {trait_name}",
            )
        else:
            block = ""
        artifact_matches = re.findall(
            r"\b(?:override\s+)?def\s+artifactName\s*=\s*\"([^\"]+)\"",
            block,
        )
        version_matches = re.findall(
            r"\b(?:override\s+)?def\s+publishVersion\s*=\s*"
            r"([A-Za-z_][A-Za-z0-9_.]*)",
            block,
        )
        external_dependencies = mill_mvn_dependencies(block, trait_name)
        if identifier in modules:
            raise LayeringError(f"Mill declares module twice: {identifier}")
        modules[identifier] = {
            "compile": mill_sequence_dependencies(block, "moduleDeps", identifier),
            "test": mill_sequence_dependencies(block, "testModuleDeps", identifier),
            "published": re.search(r"\bSpinalPublishModule\b", header) is not None,
            "artifact": artifact_matches[-1] if artifact_matches else None,
            "version": version_matches[-1] if version_matches else None,
            "external_compile": external_dependencies,
        }
    return modules


def dependency_cycle(modules: Mapping[str, Mapping[str, Any]]) -> Optional[List[str]]:
    states: Dict[str, int] = {}
    stack: List[str] = []

    def visit(identifier: str) -> Optional[List[str]]:
        state = states.get(identifier, 0)
        if state == 2:
            return None
        if state == 1:
            start = stack.index(identifier)
            return stack[start:] + [identifier]
        states[identifier] = 1
        stack.append(identifier)
        module = modules[identifier]
        for dependency in sorted(set(module["compile"]) | set(module["test"])):
            if dependency not in modules:
                return [identifier, dependency]
            cycle = visit(dependency)
            if cycle is not None:
                return cycle
        stack.pop()
        states[identifier] = 2
        return None

    for identifier in sorted(modules):
        cycle = visit(identifier)
        if cycle is not None:
            return cycle
    return None


def validate_build_graph(root: Path, build: Mapping[str, Any]) -> int:
    sources: Dict[str, str] = {}
    for kind, key in (("SBT", "sbt_file"), ("Mill", "mill_file")):
        relative = build[key]
        try:
            sources[kind] = (root / relative).read_text(encoding="utf-8")
        except FileNotFoundError as error:
            raise LayeringError(f"{kind} build definition is missing: {relative}") from error
        except UnicodeDecodeError as error:
            raise LayeringError(f"{kind} build definition is not UTF-8: {relative}") from error

    parsed = {
        "SBT": parse_sbt_build(sources["SBT"]),
        "Mill": parse_mill_build(sources["Mill"]),
    }
    expected = {module["id"]: module for module in build["modules"]}
    expected_ids = sorted(expected)
    for kind, actual in parsed.items():
        actual_ids = sorted(actual)
        if actual_ids != expected_ids:
            missing = sorted(set(expected_ids) - set(actual_ids))
            extra = sorted(set(actual_ids) - set(expected_ids))
            details: List[str] = []
            if missing:
                details.append("missing " + ", ".join(missing))
            if extra:
                details.append("unexpected " + ", ".join(extra))
            raise LayeringError(f"{kind} controlled module inventory differs: {'; '.join(details)}")
        for identifier in expected_ids:
            for scope in ("compile", "test"):
                if actual[identifier][scope] != expected[identifier][scope]:
                    raise LayeringError(
                        f"{kind} {identifier} {scope} dependencies differ: expected "
                        f"{expected[identifier][scope]}, found {actual[identifier][scope]}"
                    )
            if actual[identifier]["published"] != expected[identifier]["published"]:
                raise LayeringError(
                    f"{kind} {identifier} publication differs: expected "
                    f"{expected[identifier]['published']}, found "
                    f"{actual[identifier]['published']}"
                )
        cycle = dependency_cycle(actual)
        if cycle is not None:
            raise LayeringError(f"{kind} module graph is cyclic: {' -> '.join(cycle)}")

    for identifier in expected_ids:
        for scope in ("compile", "test"):
            if parsed["SBT"][identifier][scope] != parsed["Mill"][identifier][scope]:
                raise LayeringError(
                    f"SBT/Mill graph parity differs for {identifier} {scope} dependencies"
                )

    for publication in build["publications"]:
        identifier = publication["id"]
        sbt_module = parsed["SBT"][identifier]
        mill_module = parsed["Mill"][identifier]
        if sbt_module["name"] != publication["sbt_name"]:
            raise LayeringError(
                f"SBT {identifier} publication name differs: {sbt_module['name']}"
            )
        if sbt_module["version"] != publication["version_marker"]:
            raise LayeringError(
                f"SBT {identifier} publication version differs: {sbt_module['version']}"
            )
        if mill_module["artifact"] != publication["mill_artifact_name"]:
            raise LayeringError(
                f"Mill {identifier} publication artifact differs: {mill_module['artifact']}"
            )
        if mill_module["version"] != publication["version_marker"]:
            raise LayeringError(
                f"Mill {identifier} publication version differs: {mill_module['version']}"
            )

    ir_artifact = build["canonical_ir_artifact"]
    ir_identifier = ir_artifact["id"]
    sbt_ir = parsed["SBT"][ir_identifier]
    mill_ir = parsed["Mill"][ir_identifier]
    if sbt_ir["module_name"] != ir_artifact["name"]:
        raise LayeringError(
            "SBT morphir must declare explicit lowercase moduleName "
            f"{ir_artifact['name']}; found {sbt_ir['module_name']}"
        )
    if sbt_ir["external_compile"] != ir_artifact["compile_external"]:
        raise LayeringError(
            "SBT morphir compile dependencies must be exactly scala-library; "
            f"found {sbt_ir['external_compile']}"
        )
    if sbt_ir["external_test"] != ir_artifact["test_external"]:
        raise LayeringError(
            "SBT morphir test dependencies must be exactly ScalaTest; "
            f"found {sbt_ir['external_test']}"
        )
    if mill_ir["external_compile"] != ir_artifact["compile_external"]:
        raise LayeringError(
            "Mill morphir compile dependencies must be exactly scala-library; "
            f"found {mill_ir['external_compile']}"
        )
    mill_test_dependencies = mill_shared_test_dependencies(sources["Mill"])
    if mill_test_dependencies != ir_artifact["test_external"]:
        raise LayeringError(
            "Mill morphir inherited test dependencies must be exactly ScalaTest; "
            f"found {mill_test_dependencies}"
        )
    return len(expected_ids)


def read_sources(files: Sequence[Tuple[str, Path]]) -> Dict[str, str]:
    sources: Dict[str, str] = {}
    for relative, path in files:
        try:
            sources[relative] = path.read_text(encoding="utf-8")
        except UnicodeDecodeError as error:
            raise LayeringError(f"production source is not UTF-8: {relative}") from error
    return sources


def validate_driver_metadata_surface(source: str) -> None:
    declarations = list(
        re.finditer(r"(?m)^\s*final\s+case\s+class\s+Driver\s*\(", source)
    )
    if len(declarations) != 1:
        raise LayeringError(
            "canonical IR must declare exactly one public Driver case class"
        )
    open_index = source.find(
        "(",
        declarations[0].start(),
        declarations[0].end(),
    )
    parameters, _ = balanced_content(
        source,
        open_index,
        "canonical IR Driver parameters",
    )
    missing = [
        marker
        for marker in EXPECTED_DRIVER_METADATA_MARKERS
        if marker not in parameters
    ]
    if missing:
        raise LayeringError(
            "canonical IR Driver is missing public attributes/comments metadata: "
            + ", ".join(missing)
        )


def validate_sources(root: Path, contract: Mapping[str, Any]) -> int:
    validate_build_graph(root, contract["build"])

    for relative in contract["removed"]:
        if (root / relative).exists():
            raise LayeringError(f"obsolete production sidecar still exists: {relative}")

    files = production_files(root, contract)
    sources = read_sources(files)

    for required in contract["required_files"]:
        relative = required["path"]
        source = sources.get(relative)
        if source is None:
            raise LayeringError(f"required typed-layering source is missing: {relative}")
        missing_markers = [
            marker for marker in required["markers"] if marker not in source
        ]
        if missing_markers:
            raise LayeringError(
                f"required IR/typed metadata is missing from {relative}: "
                + ", ".join(missing_markers)
            )

    model_source = sources.get(CANONICAL_IR_MODEL_PATH)
    if model_source is None:
        raise LayeringError("canonical IR model source is missing")
    validate_driver_metadata_surface(model_source)

    for owner in contract["owners"]:
        occurrences: List[Tuple[str, int]] = []
        for relative, source in sources.items():
            if not path_selected(relative, owner["prefixes"]):
                continue
            for match in owner["regex"].finditer(source):
                occurrences.append((relative, source.count("\n", 0, match.start()) + 1))
        if len(occurrences) != 1 or occurrences[0][0] != owner["owner_path"]:
            rendered = ", ".join(f"{path}:{line}" for path, line in occurrences)
            raise LayeringError(
                f"single owner {owner['id']} must occur exactly once in "
                f"{owner['owner_path']}; found {rendered or 'nothing'}"
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
        raise LayeringError(
            "forbidden layering or canonical-IR reconstruction was found:\n  "
            + "\n  ".join(violations)
        )

    descriptor = contract["descriptor"]
    descriptor_source = sources.get(descriptor)
    if descriptor_source is None:
        raise LayeringError(f"plugin descriptor is missing: {descriptor}")
    actual_components = plugin_components(descriptor_source)
    if actual_components != list(EXPECTED_PLUGIN_COMPONENTS):
        raise LayeringError(
            "default Morph plugin phases must be exactly typed-control, "
            "natural-symbolic, then frontend symbolic-equality safety; "
            f"found {actual_components}"
        )
    return len(files)


def forbidden_jar_entry(entry: str, prefixes: Sequence[str]) -> Optional[str]:
    normalized = entry.lstrip("/")
    if not normalized.endswith(".class"):
        return None
    stem = normalized[: -len(".class")]
    for prefix in prefixes:
        if stem.startswith(prefix):
            return prefix
    return None


def validate_jars(jars: Sequence[Path], contract: Mapping[str, Any]) -> int:
    obsolete_violations: List[str] = []
    isolation_violations: List[str] = []
    isolation = contract["idslplugin_isolation"]
    identity_entry = isolation["identity_entry"]
    isolated_jar_count = 0
    for jar in sorted(jars, key=lambda value: value.as_posix()):
        if not jar.is_file():
            raise LayeringError(f"JAR does not exist: {jar}")
        try:
            with zipfile.ZipFile(str(jar)) as archive:
                entries = sorted(archive.namelist())
                for entry in entries:
                    prefix = forbidden_jar_entry(entry, contract["jar_prefixes"])
                    if prefix is not None:
                        obsolete_violations.append(
                            f"{jar}: {entry} (obsolete prefix {prefix})"
                        )

                normalized_entries = {entry.lstrip("/"): entry for entry in entries}
                if identity_entry not in normalized_entries:
                    continue
                isolated_jar_count += 1
                for normalized_entry, archive_entry in sorted(
                    normalized_entries.items()
                ):
                    if not normalized_entry.endswith(".class"):
                        continue
                    entry_bytes = normalized_entry.encode("utf-8")
                    class_bytes = archive.read(archive_entry)
                    for fragment in isolation["fragments"]:
                        if fragment["bytes"] in entry_bytes:
                            isolation_violations.append(
                                f"{jar}: {normalized_entry}: entry-name fragment "
                                f"{fragment['id']}"
                            )
                        if fragment["bytes"] in class_bytes:
                            isolation_violations.append(
                                f"{jar}: {normalized_entry}: class-byte fragment "
                                f"{fragment['id']}"
                            )
        except zipfile.BadZipFile as error:
            raise LayeringError(f"invalid JAR/ZIP archive: {jar}") from error
    errors: List[str] = []
    if jars and isolated_jar_count == 0:
        errors.append(
            "packaged JAR set contains no IDSL plugin identity class "
            f"{identity_entry}"
        )
    if obsolete_violations:
        errors.append(
            "obsolete typed sidecar classes were found in packaged JARs:\n  "
            + "\n  ".join(sorted(obsolete_violations))
        )
    if isolation_violations:
        errors.append(
            "MorphHDL compiler-isolation fragments were found in packaged "
            "idslplugin class bytes or names:\n  "
            + "\n  ".join(sorted(isolation_violations))
        )
    if errors:
        raise LayeringError("\n".join(errors))
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


def write_build_fixtures(root: Path, build: Mapping[str, Any]) -> None:
    publications = {value["id"]: value for value in build["publications"]}
    ir_artifact = build["canonical_ir_artifact"]
    sbt_blocks: List[str] = []
    mill_blocks: List[str] = []
    for module in build["modules"]:
        identifier = module["id"]
        publication = publications.get(identifier)
        dependencies = list(module["compile"]) + [
            dependency + ' % "test->compile"' for dependency in module["test"]
        ]
        depends_on = (
            "\n  .dependsOn(" + ", ".join(dependencies) + ")"
            if dependencies
            else ""
        )
        settings = [
            f'name := "{publication["sbt_name"] if publication else "Fixture-" + identifier}"',
            f'version := {publication["version_marker"] if publication else "SpinalVersion.all"}',
        ]
        if identifier == ir_artifact["id"]:
            settings.extend(
                [
                    f'moduleName := "{ir_artifact["name"]}"',
                    "libraryDependencies := Seq(\n"
                    '      "org.scala-lang" % "scala-library" % scalaVersion.value,\n'
                    '      "org.scalatest" %% "scalatest" % scalatestVersion % Test\n'
                    "    )",
                ]
            )
        if not module["published"]:
            settings.append("publish / skip := true")
        sbt_blocks.append(
            f'lazy val {identifier} = (project in file("{identifier}"))'
            + depends_on
            + "\n  .settings(\n    "
            + ",\n    ".join(settings)
            + "\n  )\n"
        )

        trait_name = identifier[0].upper() + identifier[1:]
        inheritance = "SpinalModule"
        if module["published"]:
            inheritance += " with SpinalPublishModule"
        mill_lines = [f"trait {trait_name} extends {inheritance} {{"]
        if module["compile"]:
            entries = ", ".join(
                f"{dependency}(crossScalaVersion)"
                for dependency in module["compile"]
            )
            mill_lines.append(f"  def moduleDeps = Seq({entries})")
        if module["test"]:
            entries = ", ".join(
                f"{dependency}(crossScalaVersion)" for dependency in module["test"]
            )
            mill_lines.append(f"  override def testModuleDeps = Seq({entries})")
        if publication is not None:
            mill_lines.append(
                f'  override def artifactName = "{publication["mill_artifact_name"]}"'
            )
            mill_lines.append(
                f'  override def publishVersion = {publication["version_marker"]}'
            )
        if identifier == ir_artifact["id"]:
            mill_lines.extend(
                [
                    "  override def mvnDeps = Seq(",
                    '    mvn"org.scala-lang:scala-library:${scalaVersion}"',
                    "  )",
                ]
            )
        mill_lines.append("}")
        mill_blocks.append(
            f"object {identifier} extends Cross[{trait_name}](SpinalVersion.compilers)\n"
            + "\n".join(mill_lines)
            + "\n"
        )

    (root / build["sbt_file"]).write_text("\n".join(sbt_blocks), encoding="utf-8")
    mill_prelude = (
        "trait SpinalModule {\n"
        "  object test {\n"
        "    def mvnDeps = Seq(\n"
        '      mvn"org.scalatest::scalatest::${scalatestVersion}"\n'
        "    )\n"
        "  }\n"
        "}\n"
    )
    (root / build["mill_file"]).write_text(
        mill_prelude + "\n".join(mill_blocks),
        encoding="utf-8",
    )


def write_required_files(root: Path, contract: Mapping[str, Any]) -> None:
    for required in contract["required_files"]:
        path = root / required["path"]
        path.parent.mkdir(parents=True, exist_ok=True)
        markers = list(required["markers"])
        if required["path"] == CANONICAL_IR_MODEL_PATH:
            markers = [
                marker
                for marker in markers
                if marker != "final case class Driver"
                and marker not in EXPECTED_DRIVER_METADATA_MARKERS
            ]
            markers.extend(
                [
                    "final case class DriverMetadataDecoy(",
                    "  " + EXPECTED_DRIVER_METADATA_MARKERS[0] + ",",
                    "  " + EXPECTED_DRIVER_METADATA_MARKERS[1],
                    ")",
                    "final case class Driver(",
                    "  " + EXPECTED_DRIVER_METADATA_MARKERS[0] + ",",
                    "  " + EXPECTED_DRIVER_METADATA_MARKERS[1],
                    ")",
                ]
            )
        path.write_text("\n".join(markers) + "\n", encoding="utf-8")
    write_plugin(root, EXPECTED_PLUGIN_COMPONENTS)


def expect_source_failure(
    root: Path,
    contract: Mapping[str, Any],
    label: str,
) -> None:
    try:
        validate_sources(root, contract)
    except LayeringError:
        return
    raise LayeringError(f"self-test expected source failure: {label}")


def self_test(manifest: Mapping[str, Any]) -> None:
    contract = compile_contract(manifest)

    narrowed = json.loads(json.dumps(manifest))
    narrowed["low_level_source_roots"] = ["morphir/src/main"]
    try:
        compile_contract(narrowed)
    except LayeringError:
        pass
    else:
        raise LayeringError("self-test expected closed-manifest narrowing rejection")

    with tempfile.TemporaryDirectory(prefix="morphhdl-typed-layering-ir-") as directory:
        root = Path(directory)
        for source_root in contract["roots"]:
            (root / source_root).mkdir(parents=True, exist_ok=True)
        write_build_fixtures(root, contract["build"])
        write_required_files(root, contract)
        validate_sources(root, contract)

        sbt_build = root / contract["build"]["sbt_file"]
        sbt_source = sbt_build.read_text(encoding="utf-8")
        sbt_plugin_header = (
            'lazy val morphplugin = (project in file("morphplugin"))\n  .settings('
        )
        if sbt_plugin_header not in sbt_source:
            raise LayeringError("self-test could not locate the SBT morphplugin fixture")
        sbt_build.write_text(
            sbt_source.replace(
                sbt_plugin_header,
                'lazy val morphplugin = (project in file("morphplugin"))\n'
                "  .dependsOn(morphruntime)\n  .settings(",
                1,
            ),
            encoding="utf-8",
        )
        expect_source_failure(root, contract, "SBT morphplugin runtime dependency")
        sbt_build.write_text(sbt_source, encoding="utf-8")

        sbt_ir_module_name = 'moduleName := "morphhdl-ir"'
        if sbt_ir_module_name not in sbt_source:
            raise LayeringError(
                "self-test could not locate the SBT morphir moduleName fixture"
            )
        sbt_build.write_text(
            sbt_source.replace(
                sbt_ir_module_name,
                'moduleName := "MorphHDL-ir"',
                1,
            ),
            encoding="utf-8",
        )
        expect_source_failure(root, contract, "SBT morphir lowercase artifact identity")
        sbt_build.write_text(sbt_source, encoding="utf-8")

        sbt_scala_library = (
            '"org.scala-lang" % "scala-library" % scalaVersion.value'
        )
        if sbt_scala_library not in sbt_source:
            raise LayeringError(
                "self-test could not locate the SBT morphir scala-library fixture"
            )
        sbt_build.write_text(
            sbt_source.replace(
                sbt_scala_library,
                sbt_scala_library
                + ',\n      "com.example" % "compile-leak" % scalaVersion.value',
                1,
            ),
            encoding="utf-8",
        )
        expect_source_failure(root, contract, "SBT morphir compile dependency leak")
        sbt_build.write_text(sbt_source, encoding="utf-8")

        sbt_scalatest = (
            '"org.scalatest" %% "scalatest" % scalatestVersion % Test'
        )
        if sbt_scalatest not in sbt_source:
            raise LayeringError(
                "self-test could not locate the SBT morphir ScalaTest fixture"
            )
        sbt_build.write_text(
            sbt_source.replace(
                sbt_scalatest,
                '"org.scalatest" %% "scalatest" % scalatestVersion',
                1,
            ),
            encoding="utf-8",
        )
        expect_source_failure(root, contract, "SBT morphir ScalaTest compile leak")
        sbt_build.write_text(sbt_source, encoding="utf-8")

        mill_build = root / contract["build"]["mill_file"]
        mill_source = mill_build.read_text(encoding="utf-8")
        mill_plugin_header = "trait Morphplugin extends SpinalModule {"
        if mill_plugin_header not in mill_source:
            raise LayeringError("self-test could not locate the Mill morphplugin fixture")
        mill_build.write_text(
            mill_source.replace(
                mill_plugin_header,
                mill_plugin_header
                + "\n  def moduleDeps = Seq(morphruntime(crossScalaVersion))",
                1,
            ),
            encoding="utf-8",
        )
        expect_source_failure(root, contract, "Mill morphplugin runtime dependency")
        mill_build.write_text(mill_source, encoding="utf-8")

        mill_ir_artifact = 'override def artifactName = "morphhdl-ir"'
        if mill_ir_artifact not in mill_source:
            raise LayeringError(
                "self-test could not locate the Mill morphir artifact fixture"
            )
        mill_build.write_text(
            mill_source.replace(
                mill_ir_artifact,
                'override def artifactName = "MorphHDL-ir"',
                1,
            ),
            encoding="utf-8",
        )
        expect_source_failure(root, contract, "Mill morphir lowercase artifact identity")
        mill_build.write_text(mill_source, encoding="utf-8")

        mill_scala_library = (
            'mvn"org.scala-lang:scala-library:${scalaVersion}"'
        )
        if mill_scala_library not in mill_source:
            raise LayeringError(
                "self-test could not locate the Mill morphir scala-library fixture"
            )
        mill_build.write_text(
            mill_source.replace(
                mill_scala_library,
                mill_scala_library
                + ',\n    mvn"org.scalatest::scalatest::${scalatestVersion}"',
                1,
            ),
            encoding="utf-8",
        )
        expect_source_failure(root, contract, "Mill morphir ScalaTest compile leak")
        mill_build.write_text(mill_source, encoding="utf-8")

        mill_scalatest = (
            'mvn"org.scalatest::scalatest::${scalatestVersion}"'
        )
        if mill_scalatest not in mill_source:
            raise LayeringError(
                "self-test could not locate the Mill shared ScalaTest fixture"
            )
        mill_build.write_text(
            mill_source.replace(
                mill_scalatest,
                mill_scalatest + ',\n      mvn"com.example:test-leak:${scalaVersion}"',
                1,
            ),
            encoding="utf-8",
        )
        expect_source_failure(root, contract, "Mill morphir test dependency leak")
        mill_build.write_text(mill_source, encoding="utf-8")

        idsl_isolation_fixture = (
            root / "idslplugin/src/main/scala/CompilerIsolationFixture.scala"
        )
        idsl_isolation_fixture.parent.mkdir(parents=True, exist_ok=True)
        for label, source in (
            (
                "IDSL plugin MorphHDL package source reference",
                "package morphhdl.compilerleak\nobject Forbidden\n",
            ),
            (
                "IDSL plugin Morph diagnostic source reference",
                'object Forbidden { val code = "MORPH-COMPILER-LEAK" }\n',
            ),
            (
                "IDSL plugin symbolic carrier source reference",
                "object Forbidden { val value: spinal.core.ElabInt = null }\n",
            ),
        ):
            idsl_isolation_fixture.write_text(source, encoding="utf-8")
            expect_source_failure(root, contract, label)
        idsl_isolation_fixture.write_text(
            "object GenericIdslBehavior {\n"
            '  val diagnostic = "MISSING EXTENDS COMPONENT"\n'
            '  val genericType = "spinal.core.Bundle"\n'
            "}\n",
            encoding="utf-8",
        )
        validate_sources(root, contract)
        idsl_isolation_fixture.unlink()

        low_level_fixture = root / "core/src/main/scala/FrontendEdge.scala"
        low_level_fixture.write_text(
            "import morphhdl.frontend.HdlInt\n",
            encoding="utf-8",
        )
        expect_source_failure(root, contract, "low-level frontend dependency")
        low_level_fixture.unlink()

        duplicate_owner = root / "lib/src/main/scala/DuplicateElabInt.scala"
        duplicate_owner.parent.mkdir(parents=True, exist_ok=True)
        duplicate_owner.write_text("final class ElabInt\n", encoding="utf-8")
        expect_source_failure(root, contract, "duplicate typed-carrier owner")
        duplicate_owner.unlink()

        obsolete_path = root / contract["removed"][0]
        obsolete_path.parent.mkdir(parents=True, exist_ok=True)
        obsolete_path.write_text("object ObsoleteSidecar\n", encoding="utf-8")
        expect_source_failure(root, contract, "obsolete sidecar path")
        obsolete_path.unlink()

        obsolete_symbol = root / "core/src/main/scala/ObsoleteSidecar.scala"
        obsolete_symbol.write_text(
            "object ExternalParameterizedMemoryRegistry\n",
            encoding="utf-8",
        )
        expect_source_failure(root, contract, "obsolete sidecar symbol")
        obsolete_symbol.unlink()

        unscanned = root / "escape/src/main/scala/Unscanned.scala"
        unscanned.parent.mkdir(parents=True, exist_ok=True)
        unscanned.write_text("object Unscanned\n", encoding="utf-8")
        expect_source_failure(root, contract, "unscanned production root")
        unscanned.unlink()
        unscanned.parent.rmdir()
        unscanned.parent.parent.rmdir()
        unscanned.parent.parent.parent.rmdir()
        unscanned.parent.parent.parent.parent.rmdir()

        model = root / "morphir/src/main/scala/morphhdl/ir/v1/Model.scala"
        model_source = model.read_text(encoding="utf-8")
        metadata_marker = "valueSemantics: PackedValueSemantics"
        model.write_text(model_source.replace(metadata_marker, ""), encoding="utf-8")
        expect_source_failure(root, contract, "missing canonical-IR metadata marker")
        model.write_text(model_source, encoding="utf-8")

        driver_start = model_source.find("final case class Driver(")
        if driver_start < 0:
            raise LayeringError(
                "self-test could not locate the canonical IR Driver fixture"
            )
        for marker in EXPECTED_DRIVER_METADATA_MARKERS:
            driver_tail = model_source[driver_start:]
            if marker not in driver_tail:
                raise LayeringError(
                    "self-test could not locate Driver metadata marker: " + marker
                )
            model.write_text(
                model_source[:driver_start] + driver_tail.replace(marker, "", 1),
                encoding="utf-8",
            )
            expect_source_failure(
                root,
                contract,
                "missing canonical IR Driver metadata marker: " + marker,
            )
            model.write_text(model_source, encoding="utf-8")

        validation = root / CANONICAL_IR_VALIDATION_PATH
        validation_source = validation.read_text(encoding="utf-8")
        for marker in EXPECTED_VALIDATION_PUBLIC_MARKERS:
            if marker not in validation_source:
                raise LayeringError(
                    "self-test could not locate validation public marker: " + marker
                )
            validation.write_text(
                validation_source.replace(marker, "", 1),
                encoding="utf-8",
            )
            expect_source_failure(
                root,
                contract,
                "missing canonical IR validation public marker: " + marker,
            )
            validation.write_text(validation_source, encoding="utf-8")

        generated_parser = root / "morphir/src/main/scala/morphhdl/ir/v1/Parser.scala"
        generated_parser.write_text(
            "object GeneratedVerilogParser { def parseGeneratedVerilog = () }\n",
            encoding="utf-8",
        )
        expect_source_failure(root, contract, "generated-Verilog parsing")
        generated_parser.unlink()

        emitted_names = root / "morphir/src/main/scala/morphhdl/ir/v1/Names.scala"
        emitted_names.write_text(
            "object Names { val owner = records.find(_.getName() == emittedName) }\n",
            encoding="utf-8",
        )
        expect_source_failure(root, contract, "emitted-name recognition")
        emitted_names.unlink()

        upward_edge = root / "morphir/src/main/scala/morphhdl/ir/v1/Upward.scala"
        upward_edge.write_text("import spinal.core.Component\n", encoding="utf-8")
        expect_source_failure(root, contract, "canonical-IR upward dependency")
        upward_edge.unlink()

        write_plugin(root, list(reversed(EXPECTED_PLUGIN_COMPONENTS)))
        expect_source_failure(root, contract, "reordered default plugin phases")
        write_plugin(root, EXPECTED_PLUGIN_COMPONENTS)
        validate_sources(root, contract)

        identity_entry = contract["idslplugin_isolation"]["identity_entry"]
        good_idsl_jar = root / "good-idslplugin.jar"
        with zipfile.ZipFile(str(good_idsl_jar), "w") as archive:
            archive.writestr(identity_entry, b"generic idsl compiler plugin")
            archive.writestr(
                "spinal/idslplugin/components/MainTransformer.class",
                b"spinal/core/Bundle MISSING EXTENDS COMPONENT",
            )
        good_other_jar = root / "good-other.jar"
        with zipfile.ZipFile(str(good_other_jar), "w") as archive:
            archive.writestr("morphhdl/ir/v1/Design.class", b"canonical")
            archive.writestr("spinal/core/ElabInt.class", b"typed")
        validate_jars([good_other_jar, good_idsl_jar], contract)

        try:
            validate_jars([good_other_jar], contract)
        except LayeringError:
            pass
        else:
            raise LayeringError(
                "self-test expected JAR failure: missing IDSL plugin identity"
            )

        for index, fragment in enumerate(
            contract["idslplugin_isolation"]["fragments"]
        ):
            bad_bytes_jar = root / f"bad-idsl-bytes-{index}.jar"
            with zipfile.ZipFile(str(bad_bytes_jar), "w") as archive:
                archive.writestr(identity_entry, b"generic idsl compiler plugin")
                archive.writestr(
                    f"spinal/idslplugin/ByteLeak{index}.class",
                    b"clean-prefix:" + fragment["bytes"] + b":clean-suffix",
                )
            try:
                validate_jars([bad_bytes_jar], contract)
            except LayeringError:
                pass
            else:
                raise LayeringError(
                    "self-test expected JAR class-byte failure: "
                    + fragment["id"]
                )

        bad_entry_jar = root / "bad-idsl-entry.jar"
        with zipfile.ZipFile(str(bad_entry_jar), "w") as archive:
            archive.writestr(identity_entry, b"generic idsl compiler plugin")
            archive.writestr("morphhdl/compiler/Leak.class", b"generic")
        try:
            validate_jars([bad_entry_jar], contract)
        except LayeringError:
            pass
        else:
            raise LayeringError(
                "self-test expected JAR entry-name compiler-isolation failure"
            )

        for index, prefix in enumerate(contract["jar_prefixes"]):
            bad_jar = root / f"bad-{index}.jar"
            with zipfile.ZipFile(str(bad_jar), "w") as archive:
                archive.writestr(identity_entry, b"generic idsl compiler plugin")
                archive.writestr(prefix + "$Obsolete.class", b"obsolete")
            try:
                validate_jars([bad_jar], contract)
            except LayeringError:
                pass
            else:
                raise LayeringError(
                    f"self-test expected JAR failure: obsolete prefix {prefix}"
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
            print("Increment 54 typed-layering/IR guard self-test passed")
            return 0

        contract = compile_contract(manifest)
        source_count = validate_sources(root, contract)
        jars = [Path(value).resolve() for value in arguments.jar]
        if arguments.require_jar and not jars:
            raise LayeringError("--require-jar needs at least one --jar argument")
        jar_count = validate_jars(jars, contract)
        print("Increment 54 typed-layering and canonical-IR boundary is valid")
        print(f"  production sources checked: {source_count}")
        print(f"  production roots closed: {len(contract['roots'])}")
        print(f"  SBT/Mill module edges closed: {len(contract['build']['modules'])}")
        print(f"  single owners enforced: {len(contract['owners'])}")
        print(f"  required typed/IR sources checked: {len(contract['required_files'])}")
        print(f"  negative source rules enforced: {len(contract['rules'])}")
        print(f"  packaged JARs checked: {jar_count}")
        return 0
    except LayeringError as error:
        print(f"Increment 54 typed-layering/IR guard failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
