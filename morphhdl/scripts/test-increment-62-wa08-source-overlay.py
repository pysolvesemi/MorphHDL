#!/usr/bin/env python3
"""Exercise actual Git, disk and restoration attacks in a disposable worktree."""
import importlib.util
import os
import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HELPER = "morphhdl/scripts/check-increment-62-wa08-source-overlay.py"
spec = importlib.util.spec_from_file_location("wa08_overlay_test", ROOT / HELPER)
overlay = importlib.util.module_from_spec(spec)
spec.loader.exec_module(overlay)


def git(root, *args):
    return subprocess.check_output(["git", "-c", "user.name=WA08 source validation",
        "-c", "user.email=wa08-validation@example.invalid", *args], cwd=root,
        stderr=subprocess.PIPE)


def rejected(label, action):
    try:
        action()
    except RuntimeError as error:
        assert "WA-08" in str(error), (label, error)
    else:
        raise AssertionError("accepted attack: " + label)


def main():
    value = overlay.verify(ROOT)
    entries = value["files"]
    count = 0
    for entry in entries:
        path = entry["path"]
        raw = (ROOT / path).read_bytes()
        before = overlay.frozen(ROOT, overlay.BASE, path) or b""
        assert overlay.restore_source(ROOT, path, raw) == before
        rejected("changed restoration " + path,
                 lambda: overlay.restore_source(ROOT, path, raw + b"\nchanged\n"))
        count += 1
    unrelated = "core/src/main/scala/spinal/core/Bits.scala"
    assert unrelated not in {entry["path"] for entry in entries}
    raw = (ROOT / unrelated).read_bytes() + b"\n// historical mutation\n"
    assert overlay.restore_source(ROOT, unrelated, raw) == raw
    assert unrelated in overlay.inherited_inventory(ROOT, {unrelated}, overlay.BASE)
    # A historical checker may already have removed an independently verified
    # rollout delta. The overlay must compose with that projection without
    # reintroducing its overlap, while still restoring genuinely canceled
    # historical changes and preserving unrelated input paths.
    reviewed = {entry["path"] for entry in entries}
    production = {path for path in reviewed if "/src/main/" in "/" + path}
    overlap = "morphhdl/src/main/scala/morphhdl/MorphVerilog.scala"
    # Read the immutable predecessor used by the register inventory instead of
    # maintaining a second guessed source-history anchor in this test.
    register_spec = importlib.util.spec_from_file_location(
        "wa08_register_projection_test", ROOT / "morphhdl/scripts/check-increment-59g-source-review.py")
    register = importlib.util.module_from_spec(register_spec)
    register_spec.loader.exec_module(register)
    older = register.BASE
    current = {p.decode() for p in git(ROOT, "diff", "--no-renames", "--name-only",
                                     "-z", older, "HEAD").split(b"\0") if p}
    historical = {p.decode() for p in git(ROOT, "diff", "--no-renames", "--name-only",
                                        "-z", older, overlay.BASE).split(b"\0") if p}
    assert overlap in production & current & historical
    complete = {path for path in current if "/src/main/" in "/" + path}
    assert overlap in overlay.inherited_inventory(ROOT, complete, older)
    projected = overlay.inherited_inventory(ROOT, (complete - {overlap}) | {unrelated}, older)
    assert overlap not in projected and unrelated in projected
    assert not (projected & reviewed) - production
    # Against HEAD, every reviewed delta to the overlay base is a real
    # cancellation in the current inventory, so those production paths return.
    canceled = overlay.inherited_inventory(ROOT, {unrelated}, "HEAD")
    assert production <= canceled and unrelated in canceled
    assert not (canceled & reviewed) - production
    rollout = register.rollout_scope(ROOT)
    restored_publication = rollout.restore_60g_source(
        ROOT, overlap, (ROOT / overlap).read_text()).encode()
    assert restored_publication == git(ROOT, "show", rollout.BASE + ":" + overlap)
    assert rollout.restore_60g_source(ROOT, overlap, restored_publication.decode()).encode() == restored_publication
    rejected("changed already-restored publication source",
             lambda: rollout.restore_60g_source(
                 ROOT, overlap, restored_publication.decode() + "\n// historical mutation\n"))
    count += 1
    ternary = register.boolean_ternary_review(ROOT)
    assert overlap not in ternary.load_contract(ROOT)["production_delta"]
    assert ternary.restore_pass_source(ROOT, overlap, restored_publication) == restored_publication
    # A layer must not consume or normalize bytes outside its own ownership.
    # The enclosing historical source checker still validates those bytes.
    changed_publication = restored_publication + b"\n// historical mutation\n"
    assert ternary.restore_pass_source(ROOT, overlap, changed_publication) == changed_publication
    with tempfile.TemporaryDirectory(prefix="wa08-source-controls-") as temporary:
        fixture = Path(temporary) / "repo"
        git(ROOT, "worktree", "add", "--quiet", "--detach", str(fixture), "HEAD")
        head = git(fixture, "rev-parse", "HEAD").decode().strip()
        victim = "morphhdl/src/main/scala/morphhdl/MorphWireAssignmentPasses.scala"
        try:
            def reset():
                # A linked parent can cause Git to restore a tracked child into
                # its link target. Remove fixture links before restoring paths.
                for file in fixture.rglob("*"):
                    if file.is_symlink():
                        file.unlink()
                for path, entry in overlay.tree(fixture, head).items():
                    directory = fixture / path
                    if entry[0] == "160000" and directory.exists() and not (directory / ".git").exists():
                        shutil.rmtree(directory)
                git(fixture, "reset", "--hard", head)
                git(fixture, "clean", "-fdx")

            def attack(label, mutate, committed=False):
                nonlocal count
                reset()
                overlay.verify(fixture)  # Same-process checks cannot reuse a stale green cache.
                mutate()
                if committed:
                    git(fixture, "add", "-A")
                    git(fixture, "commit", "-qm", "source attack fixture")
                rejected(label, lambda: overlay.verify(fixture))
                count += 1

            def append(path):
                file = fixture / path
                file.write_bytes(file.read_bytes() + b"\n// mutation\n")

            def linked():
                (fixture / victim).unlink()
                (fixture / victim).symlink_to("/dev/null")

            def hidden_index():
                old = (fixture / victim).read_bytes()
                append(victim)
                git(fixture, "add", victim)
                (fixture / victim).write_bytes(old)

            def unknown():
                (fixture / "morphhdl/src/main/scala/morphhdl/Unknown.scala").write_text("// added\n")

            def ignored():
                unknown()
                (fixture / ".gitignore").write_text("Unknown.scala\n")

            def linked_parent():
                parent = fixture / "morphhdl/src/main/scala/morphhdl/examples"
                parent.rename(parent.with_name("examples-copy"))
                parent.symlink_to("examples-copy", target_is_directory=True)

            def partial():
                path = "morphir/src/main/scala/morphhdl/ir/v1/Handoff.scala"
                (fixture / path).write_bytes(overlay.frozen(ROOT, overlay.BASE, path))

            def uninitialized_submodule():
                path = next(p for p, entry in overlay.tree(fixture, head).items()
                            if entry[0] == "160000")
                (fixture / path).mkdir(parents=True, exist_ok=True)
                (fixture / path / "Unexpected.scala").write_text("// unreviewed\n")

            for label, mutate, committed in (
                ("changed bytes", lambda: append(victim), False),
                ("missing source", lambda: (fixture / victim).unlink(), False),
                ("committed source mutation", lambda: append(victim), True),
                ("committed unknown production", unknown, True),
                ("historical source mutation remains visible", lambda: append(unrelated), True),
                ("executable bit", lambda: os.chmod(fixture / victim, 0o755), False),
                ("symlink", linked, False),
                ("linked ancestor", linked_parent, False),
                ("hidden staged bytes", hidden_index, False),
                ("untracked source", unknown, False),
                ("ignored source", ignored, False),
                ("partial profile rollout", partial, True),
                ("manifest mutation", lambda: append(overlay.CONTRACT), False),
                ("helper mutation", lambda: append(HELPER), True),
                ("uninitialized submodule source", uninitialized_submodule, False),
            ):
                attack(label, mutate, committed)
            reset()
            overlay.verify(fixture)
        finally:
            git(ROOT, "worktree", "remove", "--force", str(fixture))
    print("WA08_OVERLAY_MUTATIONS_PASS rejected=" + str(count))


if __name__ == "__main__":
    main()
