#!/usr/bin/env python3
"""Compose the exact 59i and 60g/WA-07b source-review layers after merge.

This script is used only by the guarded target reconciliation workflow. Git's
`-X ours` preserves the already reviewed 59i side of genuinely overlapping
checker hunks. This script then restores the later target's outer 60g view in a
fixed order: 60g publication -> 59i join -> historical register/WA-07b review.
It changes no HDL production source and cannot widen any reviewed inventory.
"""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CHECK_59G = ROOT / "morphhdl/scripts/check-increment-59g-source-review.py"
CHECK_WA07B = ROOT / "morphhdl/scripts/check-wa07b-inherited-review.py"


def require(condition: bool, detail: str) -> None:
    if not condition:
        raise RuntimeError(detail)


def replace_function(text: str, name: str, replacement: str) -> str:
    pattern = re.compile(r"(?ms)^def " + re.escape(name) + r"\([^\n]*\).*?(?=^def |\Z)")
    matches = list(pattern.finditer(text))
    require(len(matches) == 1, f"{name} function count changed: {len(matches)}")
    return text[:matches[0].start()] + replacement.rstrip() + "\n\n\n" + text[matches[0].end():]


def insert_before(text: str, anchor: str, value: str, label: str) -> str:
    require(text.count(anchor) == 1, f"{label} anchor count changed: {text.count(anchor)}")
    return text.replace(anchor, value.rstrip() + "\n\n\n" + anchor, 1)


def compose_59g() -> None:
    text = CHECK_59G.read_text()
    require("def join_source_review(root: Path):" in text,
            "59i join reviewer disappeared from 59g composition")

    text = replace_function(text, "production_changes", '''def production_changes(root: Path, revision: str) -> set[str]:
    tracked = subprocess.check_output(["git", "diff", "--name-only", revision], cwd=root, text=True).splitlines()
    untracked = subprocess.check_output(["git", "ls-files", "--others", "--exclude-standard"],
                                        cwd=root, text=True).splitlines()
    paths = {path for path in tracked + untracked if re.search(r"(?:^|/)src/main/", path)}
    rollout = rollout_scope(root)
    if rollout is not None:
        prior = subprocess.check_output(["git", "diff", "--name-only", revision, rollout.BASE],
                                        cwd=root, text=True).splitlines()
        paths -= set(rollout.PRODUCTION) - set(prior)
    return paths''')

    if "def rollout_scope(root: Path):" not in text:
        text = insert_before(text, "def restore_source(root: Path, path: str, source: str) -> str:\n", '''def rollout_scope(root: Path):
    """Load the separately pinned outer publication contract, when present."""
    helper = root / "morphhdl/scripts/check-increment-60g-source-scope.py"
    if not helper.is_file():
        return None
    spec = importlib.util.spec_from_file_location("rollout_60g_scope", helper)
    require(spec is not None and spec.loader is not None, "cannot import reviewed 60g source scope")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def restore_rollout(root: Path, path: str, source: str) -> str:
    rollout = rollout_scope(root)
    return source if rollout is None else rollout.restore_60g_source(root, path, source)''',
            "59g rollout insertion")

    text = replace_function(text, "restore_source", '''def restore_source(root: Path, path: str, source: str) -> str:
    source = restore_rollout(root, path, source)
    join = join_source_review(root)
    if join is not None:
        source = join.restore_source(root, path, source)
    ternary = boolean_ternary_review(root)
    if ternary is not None:
        source = ternary.restore_adapter(root, path, source)
    entries = load_contract(root)
    if path not in entries:
        return source
    return restore_reviewed(entries[path], baseline_source(root, path), source.encode()).decode()''')

    text = replace_function(text, "verify_spans", '''def verify_spans(root: Path) -> None:
    subprocess.run(["git", "merge-base", "--is-ancestor", BASE, "HEAD"], cwd=root, check=True)
    join = join_source_review(root)
    if join is not None:
        join.verify_spans(root)
    entries = load_contract(root)
    for relative in (CONTRACT, *PATHS):
        source = root / relative
        require(source.is_file() and not source.is_symlink() and not source.stat().st_mode & 0o111,
                "59g reviewed source must be a regular non-executable file: " + relative)
        stage = subprocess.check_output(["git", "ls-files", "--stage", "--", relative],
                                        cwd=root, text=True).split()
        require(len(stage) == 4 and stage[0] == "100644" and stage[2] == "0" and stage[3] == relative,
                "59g reviewed source is not uniquely tracked: " + relative)
        if relative in entries:
            current = restore_rollout(root, relative, source.read_text())
            if join is not None:
                current = join.restore_source(root, relative, current)
            ternary = boolean_ternary_review(root)
            if ternary is not None:
                current = ternary.restore_adapter(root, relative, current)
            restore_reviewed(entries[relative], baseline_source(root, relative), current.encode())''')

    text = replace_function(text, "verify", '''def verify(root: Path) -> None:
    rollout = rollout_scope(root)
    if rollout is not None:
        rollout.source_scope(root)
    paths = production_changes(root, BASE)
    join = join_source_review(root)
    if join is not None:
        # Verify the complete joined source before removing only its delta.
        # The independent ternary audit below still checks its full inventory.
        paths = join.inherited_inventory(root, paths, BASE)
    ternary = boolean_ternary_review(root)
    if ternary is not None:
        paths = ternary.inherited_inventory(root, paths, BASE)
    require_production_inventory(paths)
    verify_spans(root)
    print("59g complete production inventory and exact source spans restore the merged baseline PASS")''')

    require(text.count("def rollout_scope(root: Path):") == 1,
            "59g rollout helper was duplicated")
    require(text.index("source = restore_rollout(root, path, source)") <
            text.index("join = join_source_review(root)", text.index("def restore_source")),
            "59g restoration order is not 60g before 59i")
    CHECK_59G.write_text(text)


def compose_wa07b() -> None:
    text = CHECK_WA07B.read_text()
    if "def restore_rollout(root: Path, path: str, source: str) -> str:" not in text:
        text = insert_before(text, "def joined_adapter_source(root: Path, path: str, source: bytes) -> bytes:\n", '''def restore_rollout(root: Path, path: str, source: str) -> str:
    """Reverse only the separately pinned outer publication layer, if present.

    This is byte restoration, not a validation shortcut: verify() still checks
    the complete current pass tree and index, and restore_bytes() still binds
    all original WA-07b adapter spans to their immutable baseline.
    """
    helper = root / "morphhdl/scripts/check-increment-60g-source-scope.py"
    if not (helper.exists() or helper.is_symlink()):
        return source
    require(helper.is_file() and not helper.is_symlink(), "missing regular 60g reviewer")
    spec = importlib.util.spec_from_file_location("rollout_60g_scope", helper)
    require(spec is not None and spec.loader is not None, "cannot import 60g reviewer")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.restore_60g_source(root, path, source)''', "WA-07b rollout insertion")

    text = replace_function(text, "joined_adapter_source", '''def joined_adapter_source(root: Path, path: str, source: bytes) -> bytes:
    """Unwrap exact 60g and 59i successors before the frozen WA-07b view.

    The frozen WA-07b adapter digests stay authoritative. Already restored
    adapter bytes need no second unwrap; every other byte sequence must reverse
    through the outer 60g contract and then the full 59i span certificate before
    the original WA-07b checks. This cannot authorize pass production or tests.
    """
    source = restore_rollout(root, path, source.decode()).encode()
    entry = next((entry for entry in load_contract(root)["checker_adapters"]
                  if entry["path"] == path), None)
    if entry is None or digest(source) == entry["after_sha256"]:
        return source
    checker = root / "morphhdl/scripts/check-increment-59i-source-review.py"
    contract = root / "morphhdl/contracts/increment-59i-source-review.json"
    if not (checker.exists() or checker.is_symlink() or contract.exists() or contract.is_symlink()):
        return source  # The unchanged WA-07b digest check must reject it.
    require(checker.is_file() and not checker.is_symlink() and
            contract.is_file() and not contract.is_symlink(), "missing regular 59i successor review")
    spec = importlib.util.spec_from_file_location("wa07b_join_source_review", checker)
    require(spec is not None and spec.loader is not None, "cannot load 59i successor review")
    join = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(join)
    return join.restore_source(root, path, source.decode()).encode()''')

    text = replace_function(text, "restore_adapter", '''def restore_adapter(root: Path, path: str, source: str) -> str:
    if path not in ADAPTER_PATHS:
        return source
    value = load_contract(root)
    entry = next(x for x in value["checker_adapters"] if x["path"] == path)
    return restore_bytes(entry, frozen_source(root.resolve(), BASE, path),
                         joined_adapter_source(root, path, source.encode())).decode()''')

    require(text.count("def restore_rollout(root: Path, path: str, source: str) -> str:") == 1,
            "WA-07b rollout helper was duplicated")
    require("joined_adapter_source(root, path, current)" in text,
            "WA-07b real checkout no longer uses the composed adapter view")
    CHECK_WA07B.write_text(text)


def main() -> None:
    compose_59g()
    compose_wa07b()
    print("59i/60g/WA-07b source-review restoration order composed")


if __name__ == "__main__":
    main()
