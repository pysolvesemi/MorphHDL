#!/usr/bin/env python3
"""Exact 60g publication-only scope; restore sealed legacy oracle configuration.

This never restores arithmetic edits, widens native authority, edits an RTL
oracle, or treats an earlier CI run as current qualification.
"""
from __future__ import annotations
import argparse
import hashlib
import importlib.util
import json
import subprocess
from pathlib import Path

BASE = "64e8fddc432e859b6b532540bee96c5608d46efa"
SIBLING_BASE = "cba4717abc9192917d819e1f84cb246162488286"
NATIVE_MANIFEST_SHA256 = "d4f3a0d62bfaaab2cc32e6e95baa194926f5e5b869324b429fb26b79562923b0"
CONTRACT = "morphhdl/contracts/increment-60g-publication-edits.json"
CONTRACT_SHA256 = "2d7678a1c11d55bfa9f51a2559dce54f9ab9a8e71bd209139b9b30a21be22386"
PATHS = frozenset(['morphhdl/scripts/check-increment-59f-source-scope.py', 'morphhdl/scripts/check-increment-60c-signed-declarations.py', 'morphhdl/scripts/check-increment-60d-pure-sint-casts.py', 'morphhdl/scripts/check-increment-60e-signedness-boundaries.py', 'morphhdl/src/test/scala/nativeapplication/SIntSignedDeclarationsFixture.scala', 'morphhdl/src/test/scala/nativeapplication/SIntSignedVerilogBaselineFixture.scala', 'morphhdl/src/main/scala/spinal/core/internals/MorphHdlSignednessAnalysis.scala', 'morphhdl/src/main/scala/spinal/core/internals/MorphHdlSignedDeclarationPolicy.scala', 'morphhdl/src/main/scala/morphhdl/MorphVerilog.scala', 'morphhdl/src/main/scala/morphhdl/MorphSignedCasts.scala', 'morphhdl/src/main/scala/morphhdl/MorphSignedDeclarations.scala', 'core/src/main/scala/spinal/core/internals/Phase.scala', 'morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedNativeResize.scala', 'morphhdl/src/main/scala/spinal/core/internals/ParameterizedVerilogStructural.scala', 'morphhdl/scripts/check-increment-60f-artifacts.py', 'morphhdl/scripts/check-increment-60f-equivalence-closure.py', 'morphhdl/src/test/scala/morphhdl/SignednessBoundaryTests.scala', 'morphhdl/contracts/increment-55-native-change-review.json', 'morphhdl/contracts/native-source-preservation.json', 'morphhdl/scripts/check-increment-59c-source-review.py', 'morphhdl/scripts/test-increment-59c-inherited-source-scope.py', 'morphhdl/scripts/check-increment-59h-source-review.py', 'morphhdl/scripts/test-increment-59h-inherited-source-scope.py', 'morphhdl/scripts/check-increment-59g-source-review.py', 'morphhdl/scripts/test-increment-59g-source-review.py', 'morphhdl/scripts/check-wa07b-inherited-review.py', 'morphhdl/scripts/test-wa07b-inherited-review.py'])
PRODUCTION = {
    "morphhdl/src/main/scala/spinal/core/internals/MorphHdlSignednessAnalysis.scala": "7411eceb769d5b8fc2b7effd1a02a0d8a0f9dfddcee9602a06907778d4cf59e7",
    "morphhdl/src/main/scala/spinal/core/internals/MorphHdlSignedDeclarationPolicy.scala": "160923bb2910191ba097fcc85ba0a6dd813e9d6c1acd9176ab11d516bdec915d",
    "morphhdl/src/main/scala/morphhdl/MorphVerilog.scala": "934a5920ad1bf7c93a6862848c86155e545abc96795646d3059a3667bb336529",
    "morphhdl/src/main/scala/morphhdl/MorphSignedCasts.scala": "98321693cca463acf87989b44ad6ea5bc187b53a8ef88491fc1b3853aa5de9b9",
    "morphhdl/src/main/scala/morphhdl/MorphSignedDeclarations.scala": "3085816ba26dbceb899ed08270ff9cc00fede099e7bcb6cfbb29f14ac671b139",
    "core/src/main/scala/spinal/core/internals/Phase.scala": "07f1edef284e5fad1a701d00bd813b2e85cdaac2262d84660b4273581bfb6200",
    "morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedNativeResize.scala": "c01a92f6d9e8d80a889e44590b6db8496450d1a7b8aff4773b6b6e9e5874638a",
    "morphhdl/src/main/scala/spinal/core/internals/ParameterizedVerilogStructural.scala": "4a194fb5a5e535a2df3cbc5c27683bc90ada678ac249f085d4cf9cc7ae4629bb"
}
QUALIFICATION = {
    "morphhdl/src/test/scala/morphhdl/SignednessCompatibilityTests.scala": "e689e64573c4a3b803388b27f36a8fb7a22c54deba54cf15ae62a7f851bf047a",
    "morphhdl/src/test/scala/nativeapplication/DefaultSignedVerilogArtifactWriter.scala": "24ce6b6491ede2da141c2fbf7f4f6ebfda827c141242adc9f3c33b024f3a6a29",
    "morphhdl/src/test/scala/spinal/core/internals/ParameterizedVerilogStructuralLexicalTests.scala": "15d7398426924bb8f74b50208625fce5c0e9c3d036a8b03cc1e8e0ad8fd33864",
    "morphhdl/scripts/check-increment-59c-source-review.py": "d4d396ff6b20d68659048abe891a1440e502e543b0c9058266937cdb525a2aa2",
    "morphhdl/scripts/test-increment-59c-inherited-source-scope.py": "1c80072cd56a3518387459d1582d78a6c192d8c59090b2ef5473fba044fee3bd",
    "morphhdl/scripts/check-increment-59h-source-review.py": "828d8cbe5fa4aa29cbf82fcb006325d1078c9c1181413e4ca06ba5a4206b39cc",
    "morphhdl/scripts/test-increment-59h-inherited-source-scope.py": "0acd3aaf2997b77eb63f029064087b91a2da335ee604d9e926f0b2870b2786db",
    "morphhdl/scripts/check-increment-59g-source-review.py": "a27f4a25c506914f30b4f4eb51f57064dfcb5784942f91985c6dd6a114803d93",
    "morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala": "2f5617afcf9f97c5ace1afd71e7c37fde0efd12f1573990bdff78882679476f9",
    "morphhdl/scripts/test-increment-59g-source-review.py": "9ca0cbba29b323a9853ff73f704c086fbe1950198996192e02f65091fd7853be",
    "morphhdl/scripts/check-wa07b-inherited-review.py": "166272d092b418e4252abf70841c05774afe0213967e4ac35ee8988f2515b806",
    "morphhdl/scripts/test-wa07b-inherited-review.py": "a5de0d39119acd2f24a77114806a25f2fdce8c09534d3d851e5e20cf8bcfd023"
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

WA08_OVERLAY = "morphhdl/scripts/check-increment-62-wa08-source-overlay.py"


def wa08_overlay(root: Path):
    path = root / WA08_OVERLAY
    if not (path.exists() or path.is_symlink()):
        return None
    require(path.is_file() and not path.is_symlink(), "missing regular WA-08 overlay")
    spec = importlib.util.spec_from_file_location("wa08_rollout_overlay", path)
    require(spec is not None and spec.loader is not None, "cannot load WA-08 overlay")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def restore_wa08_text(root: Path, path: str, source: str) -> str:
    overlay = wa08_overlay(root)
    return source if overlay is None else overlay.restore_text(root, path, source)



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
    entry = (next(e for e in contract(root)["files"] if e["path"] == path)
             if path in PATHS else None)
    # Nested historical audits can revisit this layer after its exact reversal.
    # Honor only the existing sealed before-hash idempotence before consulting
    # an outer overlay, which correctly accepts only its own before/after bytes.
    # source_scope still verifies every physical file against the current tree.
    if entry is not None and digest(source) == entry["before_sha256"]:
        return restore_entry(entry, source)
    source = restore_wa08_text(root, path, source)
    if path not in PATHS:
        return source
    return restore_entry(entry, source)


def oracle_only(root: Path) -> None:
    source = restore_60g_source(root, ORACLE, (root / ORACLE).read_text())
    actual = subprocess.check_output(["git", "hash-object", "--stdin"],
                                     input=source, text=True, cwd=root).strip()
    require(actual == "84ed2baf743d2c47f07b6e76ddc9843fbb5fe910",
            "independent 60a fixture changed")
    print("60g explicit legacy selection restores the exact immutable 60a fixture PASS", flush=True)


def boolean_ternary_review(root: Path):
    """Load the independently sealed pass adapter without recursing into this gate."""
    path = root / "morphhdl/scripts/check-wa07b-inherited-review.py"
    if not (path.exists() or path.is_symlink()):
        return None
    require(path.is_file() and not path.is_symlink(), "missing regular WA-07b inherited reviewer")
    spec = importlib.util.spec_from_file_location("rollout_ternary_review", path)
    require(spec is not None and spec.loader is not None, "cannot import WA-07b inherited reviewer")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


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
    ternary = boolean_ternary_review(root)
    ternary_enabled = ternary.verify(root) if ternary is not None else False
    for path, expected in WA07A_PRODUCTION_SHA256.items():
        file = root / path
        require(file.is_file() and not file.is_symlink() and not file.stat().st_mode & 0o111,
                "WA-07a source must be a regular non-executable file: " + path)
        raw = file.read_bytes()
        inherited_raw = ternary.restore_pass_source(root, path, raw) if ternary_enabled else raw
        require(hashlib.sha256(inherited_raw).hexdigest() == expected,
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



def without_sibling_delta(root: Path, paths: set[str], revision: str) -> set[str]:
    """Project only the verified sibling out of an inherited increment's view.

    The outer source union still retains and checks WA-07a. The 59c/59h local
    inventories describe their own deltas and must not absorb this sibling.
    Restore the pre-sibling view relative to the caller's exact baseline;
    never drop unrelated changes or any historically overlapping path.
    """
    overlay = wa08_overlay(root)
    if overlay is not None:
        paths = overlay.inherited_inventory(root, paths, revision)

    def changed(older: str, *newer: str) -> set[str]:
        output = subprocess.check_output(
            ["git", "diff", "--no-renames", "--name-only", "-z", older, *newer], cwd=root)
        return {path.decode("utf-8") for path in output.split(b"\0") if path}

    sibling = changed(SIBLING_BASE) & set(WA07A_PRODUCTION_SHA256)
    # An ancestor/name match alone grants nothing: validate complete inventory,
    # hashes, file modes, tracking and HEAD/index/worktree before projection.
    sibling_scope(root, sibling)
    if subprocess.run(["git", "merge-base", "--is-ancestor", WA07A_MERGED, revision],
                      cwd=root, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0:
        return paths
    historical = changed(revision, SIBLING_BASE) & sibling if sibling else set()
    return (paths - sibling) | historical


def reviewed_sources(root: Path) -> dict[str, str]:
    """One closed inventory for production, qualification, ledger files and ledger."""
    expected = {CONTRACT: CONTRACT_SHA256}
    entries = [*PRODUCTION.items(), *QUALIFICATION.items(),
               *((entry["path"], entry["after_sha256"]) for entry in contract(root)["files"])]
    for path, fingerprint in entries:
        require(path not in expected or expected[path] == fingerprint,
                "conflicting reviewed 60g source fingerprints: " + path)
        expected[path] = fingerprint
    require(set(expected) == set(PRODUCTION) | set(QUALIFICATION) | set(PATHS) | {CONTRACT},
            "incomplete reviewed 60g blob inventory")
    return expected


def reviewed_blob_scope(root: Path, expected: dict[str, str]) -> None:
    """Bind exact raw worktree bytes to regular blobs in both HEAD and the index.

    Inspect the index directly, not git diff: skip-worktree/assume-unchanged or
    restoring only visible bytes must not hide staged or committed corruption.
    Batch the Git reads so this adds no per-file subprocesses to inherited gates.
    """
    overlay = wa08_overlay(root)
    if overlay is not None:
        overlay.verify(root)

    def git(*args: str) -> bytes:
        return subprocess.check_output(["git", "--literal-pathspecs", *args], cwd=root)

    require(bool(expected), "empty reviewed 60g blob inventory")
    paths = sorted(expected)
    head = git("rev-parse", "HEAD").decode().strip()
    committed, indexed = {}, {}
    for record in git("ls-tree", "-r", "-z", head, "--", *paths).split(b"\0"):
        if not record:
            continue
        metadata, name = record.split(b"\t", 1)
        mode, kind, oid = metadata.decode().split()
        path = name.decode()
        require(path not in committed and mode == "100644" and kind == "blob",
                "60g reviewed HEAD source must be one regular non-executable blob: " + path)
        committed[path] = oid
    for record in git("ls-files", "--stage", "-z", "--", *paths).split(b"\0"):
        if not record:
            continue
        metadata, name = record.split(b"\t", 1)
        mode, oid, stage = metadata.decode().split()
        path = name.decode()
        require(path not in indexed and mode == "100644" and stage == "0",
                "60g reviewed index source must be uniquely tracked without conflicts: " + path)
        indexed[path] = oid
    require(set(committed) == set(expected) and set(indexed) == set(expected),
            "60g reviewed source inventory differs in HEAD or index")
    for path, fingerprint in expected.items():
        file = root / path
        require(file.is_file() and not file.is_symlink() and not file.stat().st_mode & 0o111,
                "60g reviewed worktree source must be a regular non-executable file: " + path)
        relative = Path(path)
        require(not relative.is_absolute() and ".." not in relative.parts,
                "invalid reviewed 60g source path: " + path)
        require(all(not (root / Path(*relative.parts[:length])).is_symlink()
                    for length in range(1, len(relative.parts))),
                "60g reviewed source has a symlinked ancestor: " + path)
        actual = file.read_bytes()
        raw = actual if overlay is None else overlay.restore_source(root, path, actual)
        require(hashlib.sha256(raw).hexdigest() == fingerprint,
                "60g reviewed raw source bytes differ: " + path)
        oid = hashlib.sha1(b"blob " + str(len(actual)).encode() + b"\0" + actual).hexdigest()
        # Preserve the inherited diagnostic when this stronger check rejects
        # actual staged production drift before the older diff-based gate.
        staged = ("; staged production sources" if indexed[path] != committed[path]
                  and "/src/main/" in "/" + path else "")
        require(indexed[path] == oid == committed[path],
                "60g reviewed index, worktree and committed source differ: " + path + staged)
    require(git("rev-parse", "HEAD").decode().strip() == head,
            "60g HEAD changed while binding reviewed sources")


def source_scope(root: Path) -> None:
    overlay = wa08_overlay(root)
    if overlay is not None:
        overlay.verify(root)

    def git(*args: str) -> str:
        return subprocess.check_output(["git", *args], cwd=root, text=True)
    subprocess.run(["git", "merge-base", "--is-ancestor", BASE, "HEAD"], cwd=root, check=True)
    changed = {p for p in git("diff", "--no-renames", "--name-only", BASE).splitlines()
               if "/src/main/" in "/" + p}
    ternary = boolean_ternary_review(root)
    if ternary is not None:
        changed = ternary.inherited_inventory(root, changed, BASE)
    if overlay is not None:
        changed = overlay.inherited_inventory(root, changed, BASE)
    require(changed == set(PRODUCTION),
            "60g publication/serialization delta differs from the merged baseline: " + str(sorted(changed)))
    subprocess.run(["git", "merge-base", "--is-ancestor", WA07A_MERGED, BASE], cwd=root, check=True)
    sibling_scope(root, set(WA07A_PRODUCTION_SHA256))
    untracked = {p for p in git("ls-files", "--others").splitlines() if "/src/main/" in "/" + p}
    require(not untracked, "untracked 60g production source: " + str(sorted(untracked)))
    for path, expected in {**PRODUCTION, **QUALIFICATION}.items():
        file = root / path
        require(file.is_file() and not file.is_symlink(), "missing/linked reviewed 60g source: " + path)
        source = file.read_text()
        if overlay is not None:
            source = overlay.restore_text(root, path, source)
        require(digest(source) == expected, "60g reviewed source bytes differ: " + path)
        stage = git("ls-files", "--stage", "--", path).split()
        require(len(stage) == 4 and stage[0] == "100644" and stage[2] == "0" and stage[3] == path,
                "60g reviewed source must be uniquely tracked: " + path)
    for entry in contract(root)["files"]:
        path = entry["path"]
        source = (root / path).read_text()
        if overlay is not None:
            source = overlay.restore_text(root, path, source)
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
    reviewed_blob_scope(root, reviewed_sources(root))
    oracle_only(root)
    print("60g seven-file publication/serialization policy, sealed fixture selection and exact native lifecycle hook PASS", flush=True)


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



def inherited_projection_self_test(repository: Path) -> None:
    """Exercise both real inherited inventory adapters, not just the outer gate."""
    import importlib.util
    import tempfile
    from types import SimpleNamespace
    from unittest import mock

    adapters = []
    for name in ("59c", "59h"):
        path = repository / ("morphhdl/scripts/check-increment-" + name + "-source-review.py")
        spec = importlib.util.spec_from_file_location("projection_" + name, path)
        require(spec is not None and spec.loader is not None, "missing inherited adapter: " + name)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        adapters.append((name, module))
    rejections = 0
    with tempfile.TemporaryDirectory(prefix="morphhdl-60g-inherited-projection-") as directory:
        root = Path(directory)

        def git(*args: str) -> str:
            return subprocess.check_output(["git", *args], cwd=root, text=True,
                                           stderr=subprocess.PIPE).strip()

        def commit(message: str) -> str:
            git("add", ".")
            git("-c", "core.hooksPath=/dev/null", "commit", "-qm", message)
            return git("rev-parse", "HEAD")

        paths = set(WA07A_PRODUCTION_SHA256)
        first = sorted(paths)[0]
        git("init", "-q")
        git("config", "user.name", "Inherited inventory fixture")
        git("config", "user.email", "scope@example.invalid")
        (root / first).parent.mkdir(parents=True)
        (root / first).write_text("synthetic original source\n")
        oldest = commit("original sibling path")
        (root / first).write_text("synthetic inherited source\n")
        baseline = commit("pre-sibling baseline")
        own = "example/src/main/Owner.scala"
        (root / own).parent.mkdir(parents=True)
        (root / own).write_text("synthetic owning-increment source\n")
        commit("owning increment")
        contents = {path: ("synthetic qualified sibling " + path + "\n").encode() for path in paths}
        hashes = {path: hashlib.sha256(raw).hexdigest() for path, raw in contents.items()}
        outer = SimpleNamespace(BASE=baseline, PRODUCTION={}, without_sibling_delta=without_sibling_delta)

        def inventories(expected=None) -> None:
            nonlocal rejections
            for name, adapter in adapters:
                with mock.patch.object(adapter, "rollout_scope", return_value=outer):
                    try:
                        actual = adapter.production_changes(root, baseline)
                    except RuntimeError:
                        require(expected is None, name + " rejected a clean projection")
                        rejections += 1
                    else:
                        require(expected is not None and actual == expected,
                                name + " accepted a bad projection: " + str(sorted(actual)))

        with mock.patch.dict(globals(), BASE=baseline, SIBLING_BASE=baseline, WA07A_MERGED="0" * 40,
                             WA07A_PRODUCTION_SHA256=hashes):
            inventories({own})
            for path, raw in contents.items():
                file = root / path
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_bytes(raw)
            qualified = commit("complete sibling")
            inventories()  # Correct bytes without the sibling's ancestry are insufficient.
            with mock.patch.dict(globals(), WA07A_MERGED=qualified):
                inventories({own})
                require(without_sibling_delta(root, paths | {own}, oldest) == {own, first},
                        "projection discarded an inherited overlapping path")
                extra = "other/src/main/Unexpected.scala"
                (root / extra).parent.mkdir(parents=True)
                (root / extra).write_text("unreviewed source\n")
                inventories({own, extra})  # The caller's exact inventory must still reject this.
                (root / extra).unlink()
                for path, raw in contents.items():
                    file = root / path
                    file.write_bytes(raw + b"changed\n")
                    inventories()
                    git("add", "--", path)
                    file.write_bytes(raw)
                    inventories()  # A restored worktree cannot hide staged drift.
                    git("reset", "-q", "HEAD", "--", path)
                    file.unlink()
                    inventories()
                    file.write_bytes(raw)
                    file.chmod(0o755)
                    inventories()
                    file.chmod(0o644)
                    file.unlink()
                    file.symlink_to(root / own)
                    inventories()
                    file.unlink()
                    file.write_bytes(raw)
                    git("rm", "--cached", "--", path)
                    inventories()
                    git("add", "--", path)
                    inventories({own})
                (root / first).write_bytes(contents[first] + b"committed change\n")
                commit("corrupt committed sibling")
                inventories()
                (root / first).write_bytes(contents[first])
                git("add", "--", first)
                inventories()
                commit("restore sibling")
                inventories({own})
                # Keep completion ancestry while reverting the entire sibling tree.
                git("checkout", baseline, "--", first)
                for path in paths - {first}:
                    git("rm", "--", path)
                commit("revert completed sibling")
                inventories()
    require(rejections == 44, "missing inherited projection rejection controls")
    print("60g inherited inventory controls: both 59c/59h adapters, historical overlap, "
          "unrelated-path preservation and 44 rejections PASS (not RTL proof)", flush=True)


def reviewed_blob_self_test(repository: Path) -> None:
    """Real Git controls for every reviewed path; not compiler or RTL evidence."""
    import tempfile

    expected = reviewed_sources(repository)
    # Exercise the historical blob inventory on exactly restored bytes. The
    # outer production gate separately verifies the complete current overlay.
    overlay = wa08_overlay(repository)
    if overlay is not None:
        overlay.verify(repository)
    contents = {path: restore_wa08_text(repository, path,
                (repository / path).read_text()).encode() for path in expected}
    rejected = 0
    with tempfile.TemporaryDirectory(prefix="morphhdl-60g-reviewed-blobs-") as directory:
        root = Path(directory)

        def git(*args: str, data: bytes | None = None) -> str:
            return subprocess.check_output(["git", *args], cwd=root, input=data,
                                           stderr=subprocess.PIPE).decode().strip()

        def reject(label: str, diagnostic: str | None = None) -> None:
            nonlocal rejected
            try:
                reviewed_blob_scope(root, expected)
            except RuntimeError as error:
                require(str(error).startswith("60g reviewed"), "wrong blob rejection: " + str(error))
                require(diagnostic is None or diagnostic in str(error),
                        "missing inherited blob diagnostic: " + str(error))
                rejected += 1
                return
            raise RuntimeError("60g reviewed blob gate accepted " + label)

        git("init", "-q")
        git("config", "user.name", "Reviewed blob fixture")
        git("config", "user.email", "scope@example.invalid")
        for path, raw in contents.items():
            file = root / path
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_bytes(raw)
        git("add", ".")
        git("-c", "core.hooksPath=/dev/null", "commit", "-qm", "exact reviewed fixture")
        original = git("rev-parse", "HEAD")
        reviewed_blob_scope(root, expected)
        for path, raw in contents.items():
            file = root / path
            good = git("rev-parse", "HEAD:" + path)
            bad_raw = raw + b"\n// hidden blob corruption control\n"
            bad = git("hash-object", "-w", "--stdin", data=bad_raw)
            git("update-index", "--add", "--cacheinfo", "100644," + bad + "," + path)
            reject("staged mutation behind exact worktree: " + path,
                   "staged production sources" if "/src/main/" in "/" + path else None)
            tree = git("write-tree")
            changed_head = git("commit-tree", tree, "-p", original, "-m", "altered committed fixture")
            git("update-ref", "HEAD", changed_head, original)
            reject("altered HEAD/index behind exact worktree: " + path)
            git("update-index", "--add", "--cacheinfo", "100644," + good + "," + path)
            reject("altered HEAD behind exact index/worktree: " + path)
            git("update-ref", "HEAD", original, changed_head)
            git("update-index", "--add", "--cacheinfo", "100755," + good + "," + path)
            reject("staged executable mode behind exact worktree: " + path)
            git("update-index", "--add", "--cacheinfo", "100644," + good + "," + path)
            git("update-index", "--force-remove", "--", path)
            reject("missing index entry behind exact worktree: " + path)
            removed_head = git("commit-tree", git("write-tree"), "-p", original,
                               "-m", "missing committed fixture")
            git("update-ref", "HEAD", removed_head, original)
            git("update-index", "--add", "--cacheinfo", "100644," + good + "," + path)
            reject("missing HEAD entry behind exact index/worktree: " + path)
            git("update-ref", "HEAD", original, removed_head)
            # Matching all three blobs still cannot replace reviewed bytes.
            git("update-index", "--add", "--cacheinfo", "100644," + bad + "," + path)
            git("update-ref", "HEAD", changed_head, original)
            file.write_bytes(bad_raw)
            reject("matching but unreviewed committed bytes: " + path)
            file.write_bytes(raw)
            git("update-ref", "HEAD", original, changed_head)
            git("update-index", "--add", "--cacheinfo", "100644," + good + "," + path)
            reviewed_blob_scope(root, expected)

        first = sorted(expected)[0]
        file, raw = root / first, contents[first]
        for flag in ("assume-unchanged", "skip-worktree"):
            git("update-index", "--" + flag, "--", first)
            file.write_bytes(raw + b"\n// hidden worktree corruption\n")
            reject(flag + " cannot hide raw worktree mutation")
            file.write_bytes(raw)
            git("update-index", "--no-" + flag, "--", first)
        file.chmod(0o755)
        reject("executable physical source")
        file.chmod(0o644)
        copy = root / "identical-referent"
        copy.write_bytes(raw)
        file.unlink()
        file.symlink_to(copy)
        reject("symlink to identical content")
        file.unlink()
        file.write_bytes(raw)
        # A regular file below a symlinked directory is not a regular source path.
        parent = file.parent
        moved = parent.with_name(parent.name + "-referent")
        parent.rename(moved)
        parent.symlink_to(moved, target_is_directory=True)
        reject("symlinked ancestor with identical content")
        parent.unlink()
        moved.rename(parent)
        # Byte checks do not normalize CRLF through read_text().
        file.write_bytes(raw.replace(b"\n", b"\r\n"))
        git("add", "--", first)
        crlf = git("commit-tree", git("write-tree"), "-p", original, "-m", "CRLF fixture")
        git("update-ref", "HEAD", crlf, original)
        reject("matching blobs with unreviewed line endings")
        file.write_bytes(raw)
        git("update-ref", "HEAD", original, crlf)
        git("add", "--", first)
        reviewed_blob_scope(root, expected)
        require(not git("diff", "HEAD", "--", *sorted(expected)), "blob fixture did not restore")
    require(rejected == 7 * len(expected) + 6, "missing reviewed blob rejection controls")
    print(f"60g reviewed blob controls: all {len(expected)} paths, {rejected} Git/index/worktree "
          "rejections and restored positive cases PASS (not RTL proof)", flush=True)


def self_test(root: Path) -> None:
    rejected = 0
    for entry in contract(root)["files"]:
        after = restore_wa08_text(root, entry["path"], (root / entry["path"]).read_text())
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
    reviewed_blob_self_test(root)
    sibling_scope_self_test(root)
    inherited_projection_self_test(root)


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
