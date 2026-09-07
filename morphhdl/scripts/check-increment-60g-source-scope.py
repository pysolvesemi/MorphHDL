#!/usr/bin/env python3
"""Exact 60g publication-only scope; restore sealed legacy oracle configuration.

This never restores arithmetic edits, widens native authority, edits an RTL
oracle, or treats an earlier CI run as current qualification.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import subprocess
from pathlib import Path

BASE = "cba4717abc9192917d819e1f84cb246162488286"
NATIVE_MANIFEST_SHA256 = "d4f3a0d62bfaaab2cc32e6e95baa194926f5e5b869324b429fb26b79562923b0"
CONTRACT = "morphhdl/contracts/increment-60g-publication-edits.json"
CONTRACT_SHA256 = "80bdb0cf42fb656f4dc8a4124f52fdcd238ebde84fc11035842797ea501ddb32"
PATHS = frozenset(['morphhdl/scripts/check-increment-59f-source-scope.py', 'morphhdl/scripts/check-increment-60c-signed-declarations.py', 'morphhdl/scripts/check-increment-60d-pure-sint-casts.py', 'morphhdl/scripts/check-increment-60e-signedness-boundaries.py', 'morphhdl/src/test/scala/nativeapplication/SIntSignedDeclarationsFixture.scala', 'morphhdl/src/test/scala/nativeapplication/SIntSignedVerilogBaselineFixture.scala', 'morphhdl/src/main/scala/spinal/core/internals/MorphHdlSignednessAnalysis.scala', 'morphhdl/src/main/scala/spinal/core/internals/MorphHdlSignedDeclarationPolicy.scala', 'morphhdl/src/main/scala/morphhdl/MorphVerilog.scala', 'morphhdl/src/main/scala/morphhdl/MorphSignedCasts.scala', 'morphhdl/src/main/scala/morphhdl/MorphSignedDeclarations.scala', 'core/src/main/scala/spinal/core/internals/Phase.scala', 'morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedNativeResize.scala', 'morphhdl/src/main/scala/spinal/core/internals/ParameterizedVerilogStructural.scala', 'morphhdl/scripts/check-increment-60f-artifacts.py', 'morphhdl/scripts/check-increment-60f-equivalence-closure.py', 'morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala', 'morphhdl/src/test/scala/morphhdl/SignednessBoundaryTests.scala', 'morphhdl/contracts/increment-55-native-change-review.json', 'morphhdl/contracts/native-source-preservation.json', 'morphhdl/scripts/check-increment-59c-source-review.py', 'morphhdl/scripts/test-increment-59c-inherited-source-scope.py', 'morphhdl/scripts/check-increment-59h-source-review.py', 'morphhdl/scripts/test-increment-59h-inherited-source-scope.py'])
PRODUCTION = {
    "morphhdl/src/main/scala/spinal/core/internals/MorphHdlSignednessAnalysis.scala": "7411eceb769d5b8fc2b7effd1a02a0d8a0f9dfddcee9602a06907778d4cf59e7",
    "morphhdl/src/main/scala/spinal/core/internals/MorphHdlSignedDeclarationPolicy.scala": "160923bb2910191ba097fcc85ba0a6dd813e9d6c1acd9176ab11d516bdec915d",
    "morphhdl/src/main/scala/morphhdl/MorphVerilog.scala": "934a5920ad1bf7c93a6862848c86155e545abc96795646d3059a3667bb336529",
    "morphhdl/src/main/scala/morphhdl/MorphSignedCasts.scala": "98321693cca463acf87989b44ad6ea5bc187b53a8ef88491fc1b3853aa5de9b9",
    "morphhdl/src/main/scala/morphhdl/MorphSignedDeclarations.scala": "3085816ba26dbceb899ed08270ff9cc00fede099e7bcb6cfbb29f14ac671b139",
    "core/src/main/scala/spinal/core/internals/Phase.scala": "07f1edef284e5fad1a701d00bd813b2e85cdaac2262d84660b4273581bfb6200",
    "morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedNativeResize.scala": "c01a92f6d9e8d80a889e44590b6db8496450d1a7b8aff4773b6b6e9e5874638a",
    "morphhdl/src/main/scala/spinal/core/internals/ParameterizedVerilogStructural.scala": "4a194fb5a5e535a2df3cbc5c27683bc90ada678ac249f085d4cf9cc7ae4629bb",
    "morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala": "3caae393cd8ca763f48ef10a2859b02f7384d9fb8fd434ca489804e8b51b6eaa"
}
QUALIFICATION = {
    "morphhdl/src/test/scala/morphhdl/SignednessCompatibilityTests.scala": "e689e64573c4a3b803388b27f36a8fb7a22c54deba54cf15ae62a7f851bf047a",
    "morphhdl/src/test/scala/nativeapplication/DefaultSignedVerilogArtifactWriter.scala": "24ce6b6491ede2da141c2fbf7f4f6ebfda827c141242adc9f3c33b024f3a6a29",
    "morphhdl/src/test/scala/spinal/core/internals/ParameterizedVerilogStructuralLexicalTests.scala": "15d7398426924bb8f74b50208625fce5c0e9c3d036a8b03cc1e8e0ad8fd33864",
    "morphhdl/scripts/check-increment-59c-source-review.py": "c3ffb80ae6cd0b6200a77fdea4f755b178bfe288a039c912f9128db90c64ec9c",
    "morphhdl/scripts/test-increment-59c-inherited-source-scope.py": "1c80072cd56a3518387459d1582d78a6c192d8c59090b2ef5473fba044fee3bd",
    "morphhdl/scripts/check-increment-59h-source-review.py": "12561d165c793574ed68ad096c144c102c97514a9902d527478c901f6e914202",
    "morphhdl/scripts/test-increment-59h-inherited-source-scope.py": "2191cb92fd901fc6ad7e24e66adc63c4ae8991cd97a14d87525d284c3af8f25b"
}
# Separately qualified sibling merged after 60g's implementation closeout.
# This is the same complete three-file profile already sealed by 60f, not an
# allowance for arbitrary pass-workspace changes or a new production authority.
WA07A_MERGED = "5db83983b42c71df4f43d6a7c37c5bd552cd96c5"
WA07A_PRODUCTION_SHA256 = {
    "morphhdl-passes/src/main/scala/morphhdl/passes/api/PassContracts.scala":
        "1946882af38c058829564faa5d0f7967209e8efd1ab8cfe3d26060ec206a2cda",
    "morphhdl-passes/src/main/scala/morphhdl/passes/pipeline/WireAliasPassPipeline.scala":
        "e8ae9bdd4ae8bfb9ffd168a62a7a77578ae54b14cee3291b199d90899d1a4f1e",
    "morphhdl-passes/src/main/scala/morphhdl/passes/transform/ConstantOperandSimplificationPass.scala":
        "40a754b3b8029b9cbe047a92e35ef850f644f2b6a941f15cb69786c2b4b30b71",
}


ORACLE = "morphhdl/src/test/scala/nativeapplication/SIntSignedVerilogBaselineFixture.scala"


def require(ok: bool, detail: str) -> None:
    if not ok:
        raise RuntimeError(detail)


def digest(source: str) -> str:
    return hashlib.sha256(source.encode()).hexdigest()


def contract(root: Path) -> dict:
    raw = (root / CONTRACT).read_bytes()
    require(hashlib.sha256(raw).hexdigest() == CONTRACT_SHA256,
            "60g reviewed publication manifest changed")
    data = json.loads(raw)
    require(set(data) == {"base", "files"} and data["base"] == BASE,
            "60g restoration baseline/schema changed")
    require(len(data["files"]) == len(PATHS) and
            {entry["path"] for entry in data["files"]} == PATHS,
            "60g publication path inventory changed")
    return data


def restore_entry(entry: dict, source: str) -> str:
    # Nested historical guards may pass an already-restored complete blob.
    # The top-level 60g gate below still requires every actual file's after hash.
    if digest(source) == entry["before_sha256"]:
        return source
    require(digest(source) == entry["after_sha256"],
            "sealed oracle/authority/contract changed: sealed writer/checker changed: "
            "unreviewed source change outside 59f spans "
            "or 60g publication spans: " + entry["path"])
    for edit in reversed(entry["edits"]):
        require(edit["before"] and edit["after"] and source.count(edit["after"]) == 1,
                "missing/duplicate 60g publication span: " + entry["path"])
        source = source.replace(edit["after"], edit["before"], 1)
    require(digest(source) == entry["before_sha256"],
            "60g restored complete source differs: " + entry["path"])
    return source


def restore_60g_source(root: Path, path: str, source: str) -> str:
    if path not in PATHS:
        return source
    entry = next(e for e in contract(root)["files"] if e["path"] == path)
    return restore_entry(entry, source)


def oracle_only(root: Path) -> None:
    source = restore_60g_source(root, ORACLE, (root / ORACLE).read_text())
    actual = subprocess.check_output(["git", "hash-object", "--stdin"],
                                     input=source, text=True, cwd=root).strip()
    require(actual == "84ed2baf743d2c47f07b6e76ddc9843fbb5fe910",
            "independent 60a fixture changed")
    print("60g explicit legacy selection restores the exact immutable 60a fixture PASS", flush=True)


def sibling_scope(root: Path, extra: set[str]) -> None:
    """Admit only the complete committed WA-07a sibling with exact source bytes."""
    inherited = subprocess.run(
        ["git", "merge-base", "--is-ancestor", WA07A_MERGED, "HEAD"],
        cwd=root, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0
    if not inherited:
        require(not extra, "unreviewed production outside 60g: " + str(sorted(extra)))
        return
    require(extra == set(WA07A_PRODUCTION_SHA256),
            "merged WA-07a must retain its exact three-file production delta: " + str(sorted(extra)))
    for path, expected in WA07A_PRODUCTION_SHA256.items():
        file = root / path
        require(file.is_file() and not file.is_symlink() and not file.stat().st_mode & 0o111,
                "WA-07a source must be a regular non-executable file: " + path)
        raw = file.read_bytes()
        require(hashlib.sha256(raw).hexdigest() == expected,
                "reviewed WA-07a source bytes differ: " + path)
        stage = subprocess.check_output(
            ["git", "ls-files", "--stage", "--", path], cwd=root, text=True).split()
        require(len(stage) == 4 and stage[0] == "100644" and stage[2] == "0" and stage[3] == path,
                "WA-07a source must be uniquely tracked: " + path)
        current = hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest()
        committed = subprocess.check_output(
            ["git", "rev-parse", "HEAD:" + path], cwd=root, text=True).strip()
        require(stage[1] == current == committed,
                "WA-07a index, worktree and committed source differ: " + path)


def source_scope(root: Path) -> None:
    def git(*args: str) -> str:
        return subprocess.check_output(["git", *args], cwd=root, text=True)
    subprocess.run(["git", "merge-base", "--is-ancestor", BASE, "HEAD"], cwd=root, check=True)
    changed = {p for p in git("diff", "--no-renames", "--name-only", BASE).splitlines()
               if "/src/main/" in "/" + p}
    require(set(PRODUCTION) <= changed,
            "60g publication/serialization files or scheduler lifecycle hook disappeared: " + str(sorted(changed)))
    sibling_scope(root, changed - set(PRODUCTION))
    untracked = {p for p in git("ls-files", "--others").splitlines() if "/src/main/" in "/" + p}
    require(not untracked, "untracked 60g production source: " + str(sorted(untracked)))
    for path, expected in {**PRODUCTION, **QUALIFICATION}.items():
        file = root / path
        require(file.is_file() and not file.is_symlink(), "missing/linked reviewed 60g source: " + path)
        require(digest(file.read_text()) == expected, "60g reviewed source bytes differ: " + path)
        stage = git("ls-files", "--stage", "--", path).split()
        require(len(stage) == 4 and stage[0] == "100644" and stage[2] == "0" and stage[3] == path,
                "60g reviewed source must be uniquely tracked: " + path)
    for entry in contract(root)["files"]:
        path = entry["path"]
        source = (root / path).read_text()
        require(digest(source) == entry["after_sha256"], "60g reviewed current source differs: " + path)
        require(restore_entry(entry, source) == git("show", BASE + ":" + path),
                "60g source restoration differs from its recorded base: " + path)
    native = git("diff", "--name-only", BASE, "--", "core/src/main", "lib/src/main",
                 "idslplugin/src/main", "sim/src/main", "morphhdl/contracts/native-source-preservation.json",
                 "morphhdl/contracts/typed-native-source-overlay.json")
    require(set(native.splitlines()) == {
        "core/src/main/scala/spinal/core/internals/Phase.scala",
        "morphhdl/contracts/native-source-preservation.json"
    }, "60g native delta exceeds the exact lifecycle hook and its approved manifest: " + native)
    manifest_path = "morphhdl/contracts/native-source-preservation.json"
    require(digest((root / manifest_path).read_text()) == NATIVE_MANIFEST_SHA256,
            "60g reviewed native manifest changed")
    oracle_only(root)
    print("60g eight-file publication/serialization policy, sealed fixture selection and exact native lifecycle hook PASS", flush=True)


def sibling_scope_self_test(repository: Path) -> None:
    """Real Git/index/filesystem attack cases; synthetic bytes are not RTL proof."""
    import ast
    import tempfile
    from unittest import mock

    # The outer rollout must not diverge from the inherited closed union.
    parsed = ast.parse((repository / "morphhdl/scripts/check-increment-60f-equivalence-closure.py").read_text())
    maps = [ast.literal_eval(node.value) for node in parsed.body if isinstance(node, ast.Assign)
            and any(isinstance(name, ast.Name) and name.id == "WA07A_PRODUCTION_SHA256"
                    for name in node.targets)]
    require(maps == [WA07A_PRODUCTION_SHA256], "60g and inherited 60f WA-07a source hashes differ")
    require(not set(PRODUCTION) & set(WA07A_PRODUCTION_SHA256), "sibling overlaps 60g production")
    rejected = 0
    with tempfile.TemporaryDirectory(prefix="morphhdl-60g-wa07a-scope-") as directory:
        root = Path(directory)

        def git(*args: str) -> str:
            return subprocess.check_output(["git", *args], cwd=root, text=True,
                                           stderr=subprocess.PIPE).strip()

        def commit(message: str) -> str:
            git("add", ".")
            git("-c", "core.hooksPath=/dev/null", "commit", "-qm", message)
            return git("rev-parse", "HEAD")

        def reject(extra: set[str], label: str) -> None:
            nonlocal rejected
            try:
                sibling_scope(root, extra)
            except RuntimeError:
                rejected += 1
                return
            raise RuntimeError("sibling source gate accepted " + label)

        git("init", "-q")
        git("config", "user.name", "Source control fixture")
        git("config", "user.email", "scope@example.invalid")
        (root / "README").write_text("synthetic Git fixture; not HDL qualification\n")
        baseline = commit("baseline")
        contents = {path: ("synthetic reviewed sibling " + path + "\n").encode()
                    for path in WA07A_PRODUCTION_SHA256}
        hashes = {path: hashlib.sha256(raw).hexdigest() for path, raw in contents.items()}
        paths = set(contents)
        with mock.patch.dict(globals(), WA07A_MERGED="0" * 40, WA07A_PRODUCTION_SHA256=hashes):
            sibling_scope(root, set())
            for path, raw in contents.items():
                file = root / path
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_bytes(raw)
            qualified = commit("complete exact sibling")
            reject(paths, "unmerged lookalike source")
            with mock.patch.dict(globals(), WA07A_MERGED=qualified):
                sibling_scope(root, paths)
                reject(set(), "complete profile disappearance")
                reject(paths | {"other/src/main/Extra.scala"}, "unreviewed extra production")
                for path, raw in contents.items():
                    file = root / path
                    reject(paths - {path}, "partial sibling inventory")
                    file.write_bytes(raw + b"unreviewed mutation\n")
                    reject(paths, "changed working source")
                    git("add", path)
                    file.write_bytes(raw)
                    reject(paths, "staged mutation hidden by restored working source")
                    git("reset", "-q", "HEAD", "--", path)
                    file.unlink()
                    reject(paths, "missing source")
                    file.write_bytes(raw)
                    file.chmod(0o755)
                    reject(paths, "executable source")
                    file.chmod(0o644)
                    file.unlink()
                    referent = root / "referent"
                    referent.write_bytes(raw)
                    file.symlink_to(referent)
                    reject(paths, "symlink source")
                    file.unlink()
                    referent.unlink()
                    file.write_bytes(raw)
                    git("rm", "--cached", "--", path)
                    reject(paths, "untracked exact bytes")
                    git("add", path)
                    sibling_scope(root, paths)
                # A committed replacement cannot claim the old source contract.
                path = sorted(paths)[0]
                (root / path).write_bytes(contents[path] + b"committed mutation\n")
                commit("bad committed sibling")
                reject(paths, "committed hash drift")
                # Restoring the exact index/worktree without committing is also rejected.
                (root / path).write_bytes(contents[path])
                git("add", path)
                reject(paths, "index/worktree restored over different committed bytes")
                commit("restore exact sibling")
                sibling_scope(root, paths)
                for path in paths:
                    git("rm", "--", path)
                commit("remove completed sibling")
                actual = {path for path in git("diff", "--name-only", baseline, "HEAD").splitlines()
                          if "/src/main/" in "/" + path}
                reject(actual, "committed full reversion with completion ancestry")
    require(rejected == 27, "missing sibling source rejection controls: " + str(rejected))
    print(f"60g sibling source controls: clean standalone/combined profiles and {rejected} rejections PASS (not RTL proof)", flush=True)


def self_test(root: Path) -> None:
    rejected = 0
    for entry in contract(root)["files"]:
        after = (root / entry["path"]).read_text()
        before = restore_entry(entry, after)
        require(restore_entry(entry, before) == before, "nested restoration is not idempotent")
        for bad in (after + "\n// unrelated edit\n", after + entry["edits"][0]["after"],
                    after.replace(entry["edits"][0]["after"], "MUTATED", 1)):
            try:
                restore_entry(entry, bad)
            except RuntimeError:
                rejected += 1
            else:
                raise RuntimeError("60g restoration accepted mutation: " + entry["path"])
    require(rejected == 3 * len(PATHS), "incomplete source mutation controls")
    print(f"60g {len(PATHS)} exact restorations and {rejected} source mutation rejections PASS", flush=True)
    sibling_scope_self_test(root)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--oracle-only", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test(args.root)
    elif args.oracle_only:
        oracle_only(args.root)
    else:
        source_scope(args.root)
