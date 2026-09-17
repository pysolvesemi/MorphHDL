#!/usr/bin/env python3
"""Run the exact reviewed pass repair in CI and hand its workflow edits to the
workflow-capable repository connection. Actions does not create workflow trees
or move refs. All source checks must pass before the handoff is printed.
"""
import hashlib
import json
from pathlib import Path
import subprocess

PIN = "e2b952601fbb231fff82b616c19b7a675c850807"
SCRIPT = "repro/cdc-independent-parameters/repair_pass_routes.py"
BLOB = "47aecfd91c4bef37b5a9dbd54f6ced1c64b3adb0"
ROOT = Path(__file__).resolve().parents[2]


def git(*args):
    return subprocess.check_output(["git", "--literal-pathspecs", *args], cwd=ROOT, timeout=120)


def main():
    git("merge-base", "--is-ancestor", PIN, "HEAD")
    raw = git("show", PIN + ":" + SCRIPT)
    assert hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest() == BLOB
    namespace = {"__name__": "pinned_pass_route_repair", "__file__": str(ROOT / SCRIPT)}
    exec(compile(raw, PIN + ":" + SCRIPT, "exec"), namespace)

    def checked_source_handoff(input_head):
        commits = git("rev-list", "--reverse", input_head + "..HEAD").decode().splitlines()
        assert len(commits) == 2, "Expected separate source and metadata commits"
        source = commits[0]
        paths = namespace["changed"](input_head, source)
        expected = {namespace[k] for k in ("WORKFLOW", "BOUNDARY", "REGISTRY", "SCOPE")}
        assert paths == expected, "Unexpected workflow-source handoff"
        entries = []
        for path in sorted(paths):
            row = git("ls-tree", "-z", source, "--", path)
            metadata, returned = row[:-1].split(b"\t", 1)
            mode, kind, blob = metadata.decode().split()
            assert kind == "blob" and returned.decode() == path
            entries.append({"path": path, "mode": mode, "type": "blob", "sha": blob})
        identity = {"input": input_head, "checked_candidate": commits[-1],
                    "source_tree": git("rev-parse", source + "^{tree}").decode().strip(),
                    "base_tree": git("rev-parse", input_head + "^{tree}").decode().strip(),
                    "entries": entries,
                    "scope": "All source controls passed; workflow source needs explicit publication and a fresh exact seal. No remote tree, commit or ref was written."}
        (namespace["EVIDENCE"] / "workflow-source-handoff.json").write_text(json.dumps(identity, indent=2) + "\n")
        print("PR189_CHECKED_WORKFLOW_SOURCE_HANDOFF " + json.dumps(identity, sort_keys=True), flush=True)

    namespace["export_objects"] = checked_source_handoff
    namespace["main"]()


if __name__ == "__main__":
    main()
