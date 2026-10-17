#!/usr/bin/env python3
"""Make the frozen 60f native audit exact on a joined 59i checkout.

Standalone 60f keeps its original current-tree native audit. When the exact
59i/60g composition reviewer and contract are present, validate that joined
source first and run the immutable native-source and typed-overlay audits in
worktrees at the reviewer's exact feature and target parents.
"""
from pathlib import Path

CHECKER = Path("morphhdl/scripts/check-increment-60f-equivalence-closure.py")

CONSTANT_ANCHOR = '''# Exact composite production delta qualified with 59f; it cannot select a profile without 59f.
'''
CONSTANT_REPLACEMENT = '''ROLLOUT_COMPOSITION_CHECKER = "morphhdl/scripts/check-increment-59i-rollout-composition.py"
ROLLOUT_COMPOSITION_CONTRACT = "morphhdl/contracts/increment-59i-rollout-composition.json"

# Exact composite production delta qualified with 59f; it cannot select a profile without 59f.
'''

HELPER_ANCHOR = '''    return module.restore_60g_source(root, path, source)


def source_scope(root: Path) -> None:
'''
HELPER_REPLACEMENT = '''    return module.restore_60g_source(root, path, source)


def rollout_composition(root: Path):
    checker = root / ROLLOUT_COMPOSITION_CHECKER
    contract = root / ROLLOUT_COMPOSITION_CONTRACT
    present = checker.exists() or checker.is_symlink() or contract.exists() or contract.is_symlink()
    if not present:
        return None
    require(checker.is_file() and not checker.is_symlink() and
            contract.is_file() and not contract.is_symlink() and
            not checker.stat().st_mode & 0o111 and not contract.stat().st_mode & 0o111,
            "joined 59i native audit requires regular composition reviewer and contract")
    spec = importlib.util.spec_from_file_location("increment_59i_rollout_native_scope", checker)
    require(spec is not None and spec.loader is not None,
            "cannot load exact 59i rollout-composition reviewer")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    module.verify(root)
    require(all(re.fullmatch(r"[0-9a-f]{40}", revision) is not None
                for revision in (module.FEATURE_PARENT, module.TARGET_PARENT)) and
            module.FEATURE_PARENT != module.TARGET_PARENT,
            "59i native parent anchors are invalid")
    return module


def verify_native_source_scope(root: Path) -> None:
    composition = rollout_composition(root)
    if composition is None:
        # Preserve the exact historical standalone 60f behavior.
        subprocess.run(["python3", "morphhdl/scripts/check-native-source-preservation.py"],
                       cwd=root, check=True)
        return

    for label, revision in (("feature", composition.FEATURE_PARENT),
                            ("target", composition.TARGET_PARENT)):
        with tempfile.TemporaryDirectory(prefix="morphhdl-60f-native-" + label + "-") as directory:
            worktree = Path(directory) / "source"
            subprocess.run(["git", "worktree", "add", "--quiet", "--detach",
                            str(worktree), revision], cwd=root, check=True)
            try:
                subprocess.run(["python3", "morphhdl/scripts/check-native-source-preservation.py"],
                               cwd=worktree, check=True)
                overlay = worktree / "morphhdl/scripts/check-typed-native-source-overlay.py"
                require(overlay.is_file() and not overlay.is_symlink(),
                        "missing exact typed-native overlay checker at " + label + " parent")
                subprocess.run(["python3", "morphhdl/scripts/check-typed-native-source-overlay.py"],
                               cwd=worktree, check=True)
            finally:
                subprocess.run(["git", "worktree", "remove", "--force", str(worktree)],
                               cwd=root, check=True)
    print("60f joined native source audits PASS at exact 59i feature and target parents", flush=True)


def source_scope(root: Path) -> None:
'''

CALL_ANCHOR = '''    subprocess.run(["python3", "morphhdl/scripts/check-native-source-preservation.py"], cwd=root, check=True)
'''
CALL_REPLACEMENT = '''    verify_native_source_scope(root)
'''


def main() -> None:
    text = CHECKER.read_text()
    if (CONSTANT_REPLACEMENT in text and HELPER_REPLACEMENT in text and
            CALL_REPLACEMENT in text and CALL_ANCHOR not in text):
        print("60f joined native audit already composed")
        return
    if text.count(CONSTANT_ANCHOR) != 1:
        raise RuntimeError("60f composition constant anchor changed")
    if text.count(HELPER_ANCHOR) != 1:
        raise RuntimeError("60f composition helper anchor changed")
    if text.count(CALL_ANCHOR) != 1:
        raise RuntimeError("60f native audit invocation anchor changed")
    updated = text.replace(CONSTANT_ANCHOR, CONSTANT_REPLACEMENT, 1)
    updated = updated.replace(HELPER_ANCHOR, HELPER_REPLACEMENT, 1)
    updated = updated.replace(CALL_ANCHOR, CALL_REPLACEMENT, 1)
    compile(updated, str(CHECKER), "exec")
    CHECKER.write_text(updated)
    print("60f joined native audit composed")


if __name__ == "__main__":
    main()
