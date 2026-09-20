#!/usr/bin/env python3
"""Compare a fixed public ABI corpus with a separately reviewed golden contract.

Snapshot produces an unreviewed candidate only. Check never creates or rewrites
expectations. Native provenance removal is limited to the exact leading header
grammar; every remaining byte and both independent full emissions stay bound.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
from pathlib import Path

SCOPE = "59i-publication-abi-defaults"
DEFAULT_CONTRACT = Path(__file__).resolve().parents[1] / "contracts/increment-59i-publication-golden.json"
PROFILES = (
    ("default", "parameterized", "default", "default", 5),
    ("packed", "parameterized", "packed", "default", 5),
    ("fields", "parameterized", "fields", "default", 5),
    ("fields-legacy", "parameterized", "fields", "legacy", 5),
    ("fields-declarations", "parameterized", "fields", "declarations", 5),
    ("fields-casts", "parameterized", "fields", "casts", 5),
    ("child-packed", "child", "packed", "default", 5),
    ("child-fields", "child", "fields", "default", 5),
    ("concrete-singleton", "concrete", "native", "native", 1),
    ("concrete-unequal", "concrete", "native", "native", 5),
)
FORMALS = ("WIDTH", "TAG_WIDTH", "COORD_WIDTH", "COUNT", "MODE")
IDENTIFIER = re.compile(r"[A-Za-z_$][A-Za-z0-9_$]*\Z")
LEXER = re.compile(r'\s+|//[^\n]*(?:\n|$)|/\*.*?\*/|"(?:\\.|[^"\\])*"|'
    r"\\[^\s]+|[A-Za-z_$][A-Za-z0-9_$]*|(?:[0-9]+)?'[sS]?[bBoOdDhH][0-9a-fA-F_xXzZ?]+|[0-9]+|.", re.S)


def require(condition: bool, detail: str) -> None:
    if not condition:
        raise RuntimeError("59i publication golden: " + detail)


def digest(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def serialized(value: dict) -> bytes:
    return (json.dumps(value, indent=2, sort_keys=True) + "\n").encode()


def json_value(raw: bytes) -> dict:
    def pairs(items):
        value = {}
        for key, item in items:
            require(key not in value, "duplicate JSON key: " + key)
            value[key] = item
        return value
    try:
        value = json.loads(raw, object_pairs_hook=pairs)
    except (ValueError, UnicodeDecodeError) as error:
        raise RuntimeError("59i publication golden: invalid JSON") from error
    require(isinstance(value, dict), "JSON root must be an object")
    return value


def unlinked(path: Path) -> Path:
    path = Path(os.path.abspath(path))
    require(all(not ancestor.is_symlink() for ancestor in (path, *path.parents)), "linked path: " + str(path))
    return path


def regular(path: Path) -> bytes:
    path = unlinked(path)
    require(path.is_file() and not path.stat().st_mode & 0o111, "missing regular non-executable file: " + str(path))
    return path.read_bytes()


def expected_metadata() -> list[dict]:
    result = []
    for profile, kind, layout, signed, count in PROFILES:
        module = {"parameterized": "PublicationGoldenRecords", "child": "PublicationGoldenChildTop",
            "concrete": "PublicationGoldenNative"}[kind]
        result.append({"profile": profile, "file": profile + "/" + module + ".v", "module": module,
            "kind": kind, "layout": layout, "signed_mode": signed, "default_count": count})
    return result


def native_body(raw: bytes, module: str) -> bytes:
    lines = raw.splitlines(keepends=True)
    require(len(lines) >= 4, "native leading header is missing")
    require(re.fullmatch(rb"// Generator : SpinalHDL [A-Za-z0-9][A-Za-z0-9_.+\-]*    git head : [0-9a-f]{40}\n",
        lines[0]) is not None, "invalid native Generator header")
    require(lines[1] == ("// Component : " + module + "\n").encode(), "invalid native Component header")
    index = 2
    if lines[index].startswith(b"// Git hash"):
        require(re.fullmatch(rb"// Git hash  : [0-9a-f]{40}\n", lines[index]) is not None,
            "invalid native Git header")
        index += 1
    require(index < len(lines) and lines[index] == b"\n", "unknown or malformed leading native header line")
    body = b"".join(lines[index + 1:])
    require(bool(body), "native body is empty")
    require(re.search(rb"(?m)^\s*//\s*(?:Generator|Component|Git hash|Date)\s*:", body) is None,
        "native provenance header is duplicated or nonleading")
    return body


def tokens(text: str) -> list[tuple[str, int, int]]:
    result = []
    for match in LEXER.finditer(text):
        item = match.group()
        if not item.isspace() and not item.startswith(("//", "/*")):
            result.append((item, match.start(), match.end()))
    return result


def closing(items: list, start: int) -> int:
    pairs = {"(": ")", "[": "]", "{": "}"}
    require(start < len(items) and items[start][0] in pairs, "expected balanced delimiter")
    stack = []
    for index in range(start, len(items)):
        value = items[index][0]
        if value in pairs:
            stack.append(pairs[value])
        elif value in pairs.values():
            require(bool(stack) and stack.pop() == value, "mismatched Verilog delimiter")
            if not stack:
                return index
    raise RuntimeError("59i publication golden: unterminated Verilog delimiter")


def segments(items: list) -> list[list]:
    result, start, index = [], 0, 0
    while index < len(items):
        if items[index][0] in ("(", "[", "{"):
            index = closing(items, index)
        elif items[index][0] == ",":
            require(index > start, "empty declaration or binding")
            result.append(items[start:index])
            start = index + 1
        index += 1
    if start < len(items):
        result.append(items[start:])
    else:
        require(not items, "trailing empty declaration or binding")
    return result


def exact(text: str, items: list) -> str:
    require(bool(items), "empty declaration")
    return text[items[0][1]:items[-1][2]]


def parameter(text: str, items: list) -> dict:
    values = [item[0] for item in items]
    require(values[0] == "parameter" and values.count("=") == 1, "unsupported formal parameter declaration")
    equal = values.index("=")
    require(equal >= 2 and equal + 1 < len(values) and IDENTIFIER.fullmatch(values[equal - 1]) is not None,
        "invalid formal parameter name/default")
    return {"name": values[equal - 1], "declaration": exact(text, items),
        "default": exact(text, items[equal + 1:])}


def port(text: str, items: list) -> dict:
    require(items[0][0] in ("input", "output", "inout"), "unsupported non-ANSI port declaration")
    direction, storage, signed, name = items[0][0], "implicit", "implicit-unsigned", None
    packed, unpacked = [], []
    index = 1
    while index < len(items):
        value = items[index][0]
        if value == "[":
            end = closing(items, index)
            (packed if name is None else unpacked).append(exact(text, items[index:end + 1]))
            index = end + 1
            continue
        if name is None and value in ("wire", "reg", "logic"):
            require(storage == "implicit", "duplicate port storage qualifier")
            storage = value
        elif name is None and value in ("signed", "unsigned"):
            require(signed == "implicit-unsigned", "duplicate port signedness qualifier")
            signed = value
        else:
            require(name is None and IDENTIFIER.fullmatch(value) is not None, "unsupported port shape")
            name = value
        index += 1
    require(name is not None, "port name is missing")
    return {"name": name, "direction": direction, "storage": storage, "signedness": signed,
        "packed_dimensions": packed, "unpacked_dimensions": unpacked, "declaration": exact(text, items)}


def modules(text: str) -> tuple[list[dict], dict[str, list]]:
    items = tokens(text)
    result, bodies = [], {}
    index = 0
    while index < len(items):
        if items[index][0] != "module":
            index += 1
            continue
        require(index + 1 < len(items) and IDENTIFIER.fullmatch(items[index + 1][0]) is not None,
            "module name is missing")
        name = items[index + 1][0]
        require(name not in bodies, "duplicate module: " + name)
        index += 2
        parameters = []
        if index < len(items) and items[index][0] == "#":
            end = closing(items, index + 1)
            parameters = [parameter(text, part) for part in segments(items[index + 2:end])]
            index = end + 1
        require(index < len(items) and items[index][0] == "(", "module port header is missing")
        end = closing(items, index)
        ports = [port(text, part) for part in segments(items[index + 1:end])]
        require(ports and len({item["name"] for item in ports}) == len(ports), "missing or duplicate ports")
        require(len({item["name"] for item in parameters}) == len(parameters), "duplicate formal parameters")
        require(end + 1 < len(items) and items[end + 1][0] == ";", "module header terminator is missing")
        start = end + 2
        index = start
        while index < len(items) and items[index][0] != "endmodule":
            require(items[index][0] != "module", "nested or unterminated module")
            index += 1
        require(index < len(items), "endmodule is missing")
        bodies[name] = items[start:index]
        result.append({"module": name, "parameters": parameters, "ports": ports})
        index += 1
    require(bool(result), "no Verilog modules")
    return result, bodies


def integer_default(expression: str) -> int:
    values = [item[0] for item in tokens(expression) if item[0] not in ("(", ")")]
    require(len(values) == 1, "formal default is not one integer literal")
    if re.fullmatch(r"[0-9]+", values[0]):
        return int(values[0])
    match = re.fullmatch(r"[0-9]+'[sS]?[dD]([0-9_]+)", values[0])
    require(match is not None, "unsupported formal default literal")
    return int(match.group(1).replace("_", ""))


def child_bindings(text: str, items: list) -> list[dict]:
    candidates = [index for index, item in enumerate(items) if item[0] == "PublicationGoldenChild"]
    require(len(candidates) == 1, "missing or duplicated generated child instance")
    start = candidates[0]
    require(start + 2 < len(items) and items[start + 1][0] == "#", "child actual parameters are missing")
    end = closing(items, start + 2)
    require(end + 2 < len(items) and IDENTIFIER.fullmatch(items[end + 1][0]) is not None and
        items[end + 2][0] == "(", "child instance identity/ports are missing")
    result = []
    for part in segments(items[start + 3:end]):
        require(len(part) >= 4 and part[0][0] == "." and IDENTIFIER.fullmatch(part[1][0]) is not None and
            part[2][0] == "(" and closing(part, 2) == len(part) - 1, "invalid named child actual")
        formal = part[1][0]
        actual = part[3:-1]
        compact = [item[0] for item in actual if item[0] not in ("(", ")")]
        require(formal in FORMALS and compact == (["MODE", "+", "1"] if formal == "MODE" else [formal]),
            "incorrect generated child actual: " + formal)
        result.append({"formal": formal, "actual": exact(text, actual)})
    require(len(result) == len(FORMALS) and {item["formal"] for item in result} == set(FORMALS),
        "child formal binding inventory changed")
    return result


def profile_snapshot(metadata: dict, raw: bytes) -> dict:
    body = native_body(raw, metadata["module"]) if metadata["kind"] == "concrete" else raw
    try:
        text = body.decode("utf-8")
    except UnicodeDecodeError as error:
        raise RuntimeError("59i publication golden: RTL is not UTF-8") from error
    declarations, bodies = modules(text)
    expected = ({metadata["module"], "PublicationGoldenChild"} if metadata["kind"] == "child" else {metadata["module"]})
    require({item["module"] for item in declarations} == expected, "generated module inventory changed")
    for item in declarations:
        params = item["parameters"]
        if metadata["kind"] == "concrete":
            require(not params and not any(token[0] == "parameter" for token in bodies[item["module"]]),
                "ordinary native consumer unexpectedly parameterized")
        else:
            require(len(params) == len(FORMALS) and {param["name"] for param in params} == set(FORMALS),
                "formal parameter inventory changed")
            defaults = dict(zip(FORMALS,
                (5, 3, 7, metadata["default_count"], 1 if item["module"] == "PublicationGoldenChild" else 0)))
            require({param["name"]: integer_default(param["default"]) for param in params} == defaults,
                "public parameter defaults changed")
    bindings = child_bindings(text, bodies[metadata["module"]]) if metadata["kind"] == "child" else []
    return dict(metadata, rtl_hash_scope="native-body" if metadata["kind"] == "concrete" else "full-rtl",
        rtl_sha256=digest(body), modules=declarations, child_bindings=bindings)


def validate_field_modes(profiles: list[dict], contents: dict[str, bytes]) -> None:
    """Prove the three explicit modes in the same fixed signed fixture.

    Inspect scalar output declarations. Declaration-only retains native casts; cleanup
    removes some proven redundant casts without requiring real boundaries to
    become cast-free. Count lexer tokens so comments and strings are not proof.
    """
    counts = {}
    by_profile = {item["profile"]: item for item in profiles}
    for mode in ("legacy", "declarations", "casts"):
        profile = "fields-" + mode
        declarations = by_profile[profile]["modules"]
        require(len(declarations) == 1, "signed-mode fixture must contain one module")
        ports = {item["name"]: item for item in declarations[0]["ports"]}
        for name in ("signedSelected_real", "signedSelected_imag"):
            item = ports.get(name)
            require(item is not None and item["direction"] == "output" and
                (item["signedness"] == "signed") == (mode != "legacy"),
                "incorrect signed output declaration for " + profile + ": " + name)
        items = tokens(contents[profile].decode("utf-8"))
        counts[mode] = sum(left[0] == "$signed" and right[0] == "("
            for left, right in zip(items, items[1:]))
    require(counts["legacy"] == counts["declarations"] and counts["legacy"] > 0,
        "legacy/declaration-only fixtures must retain the same positive native signed-cast count")
    require(counts["casts"] < counts["declarations"],
        "signed-cast cleanup fixture did not remove any redundant native casts")


def collect(root: Path) -> tuple[dict, dict]:
    root = unlinked(root)
    require(root.is_dir(), "artifact root is missing: " + str(root))
    manifest_raw = regular(root / "manifest.json")
    manifest = json_value(manifest_raw)
    require(set(manifest) == {"schema", "scope", "profiles"} and type(manifest["schema"]) is int and
        manifest["schema"] == 1 and manifest["scope"] == SCOPE, "invalid artifact manifest schema")
    metadata = expected_metadata()
    require(manifest["profiles"] == metadata and all(type(item.get("default_count")) is int
        for item in manifest["profiles"]), "fixed publication profile metadata/inventory changed")
    expected_files = {"manifest.json"} | {item["file"] for item in metadata}
    actual_files = set()
    for path in root.rglob("*"):
        require(not path.is_symlink(), "linked artifact inventory: " + str(path))
        if path.is_file() and path != root / "evidence.json":
            actual_files.add(path.relative_to(root).as_posix())
    require(actual_files == expected_files, "artifact file inventory changed; missing=" +
        repr(sorted(expected_files - actual_files)) + "; extra=" + repr(sorted(actual_files - expected_files)))
    profiles, raw_hashes, contents = [], {}, {}
    for item in metadata:
        raw = regular(root / item["file"])
        contents[item["profile"]] = raw
        raw_hashes[item["file"]] = digest(raw)
        profiles.append(profile_snapshot(item, raw))
    validate_field_modes(profiles, contents)
    require(contents["default"] == contents["packed"], "default publication differs from explicit packed layout")
    return {"schema": 1, "scope": SCOPE, "profiles": profiles}, {
        "manifest_sha256": digest(manifest_raw), "raw_sha256": raw_hashes}


def paired(root: Path, duplicate: Path) -> tuple[dict, dict]:
    require(unlinked(root) != unlinked(duplicate), "independent emission directories must differ")
    left, left_raw = collect(root)
    right, right_raw = collect(duplicate)
    require(left_raw == right_raw, "independent emissions differ in full raw RTL or manifest bytes")
    require(left == right, "independent publication ABI snapshots differ")
    return left, {"a": left_raw, "b": right_raw}


def check(root: Path, duplicate: Path, contract: Path) -> dict:
    raw = regular(contract)
    expected = json_value(raw)
    actual, runs = paired(root, duplicate)
    require(serialized(expected) == serialized(actual),
        "reviewed publication golden contract differs from actual ABI/RTL snapshot")
    return {"schema": 1, "scope": SCOPE, "status": "pass", "profile_count": len(PROFILES),
        "contract_sha256": digest(raw), "actual_snapshot_sha256": digest(serialized(actual)),
        "profiles": [item[0] for item in PROFILES], "runs": runs, "snapshot": actual}


def output_file(path: Path, value: dict, roots: tuple[Path, Path], contract: Path | None,
        candidate: bool = False) -> None:
    path = unlinked(path)
    require(path != unlinked(DEFAULT_CONTRACT) and (contract is None or path != unlinked(contract)),
        "output must not rewrite a reviewed expectation")
    for root in roots:
        root = unlinked(root)
        require(not path.is_relative_to(root) or path == root / "evidence.json",
            "output must not overwrite or add generated source artifacts")
    require(path.parent.is_dir(), "output parent directory is missing")
    require(not candidate or not path.exists(), "snapshot must create a new unreviewed candidate file")
    path.write_bytes(serialized(value))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for command in ("snapshot", "check"):
        child = commands.add_parser(command)
        child.add_argument("root", type=Path)
        child.add_argument("duplicate", type=Path)
        child.add_argument("--output", type=Path, required=True)
        if command == "check":
            child.add_argument("--contract", type=Path, default=DEFAULT_CONTRACT)
    args = parser.parse_args()
    if args.command == "snapshot":
        value, _ = paired(args.root, args.duplicate)
        output_file(args.output, value, (args.root, args.duplicate), None, candidate=True)
        print("59I_PUBLICATION_GOLDEN_UNREVIEWED_CANDIDATE profiles=10")
    else:
        value = check(args.root, args.duplicate, args.contract)
        output_file(args.output, value, (args.root, args.duplicate), args.contract)
        print("59I_PUBLICATION_GOLDEN_PASS profiles=10")


if __name__ == "__main__":
    main()
