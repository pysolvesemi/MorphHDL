#!/usr/bin/env python3
"""Fail-closed controls for the current/historical source audit boundary."""
import contextlib
import importlib.util
import io
import json
import re
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SOURCE = Path(__file__).with_name("run-parameterized-inherited-audit.py")
SPEC = importlib.util.spec_from_file_location("integrated_audit", SOURCE)
A = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(A)


class IntegratedAuditTests(unittest.TestCase):
    def test_historical_anchor_is_the_actual_two_file_seal(self):
        contract = json.loads(A.git(A.ROOT, "show", A.PREDECESSOR +
            ":morphhdl/contracts/increment-59i-production-successor.json"))
        # A historical receipt must authenticate its own exact source and seal,
        # not a later repair that happens to descend from the same certificate.
        self.assertNotEqual(A.PREDECESSOR, A.INTEGRATION_PARENT)
        A.git(A.ROOT, "merge-base", "--is-ancestor", A.PREDECESSOR, A.INTEGRATION_PARENT)
        changed = A.git(A.ROOT, "diff-tree", "--no-commit-id", "--name-only", "-r", A.PREDECESSOR).decode().splitlines()
        self.assertEqual(set(changed), {
            "morphhdl/contracts/increment-59i-production-successor.json",
            "morphhdl/scripts/check-increment-59i-production-successor.py"})
        self.assertEqual(A.git(A.ROOT, "rev-parse", A.PREDECESSOR + "^").decode().strip(),
            contract["source_commit"])
        self.assertEqual(A.git(A.ROOT, "rev-parse", contract["source_commit"] + "^{tree}").decode().strip(),
            contract["source_tree"])

    def test_workflow_transition_preserves_all_existing_gates(self):
        baseline = "b613b09917155cc29dd2ed00b21656d9559931b9"
        counts = {
            "increment-59c-named-field-vectors": 2,
            "increment-59h-nested-owners": 6,
            "increment-59i-combined-closure": 7,
            "increment-60c-signed-declarations": 2,
            "increment-60d-pure-sint-casts": 2,
            "increment-60e-signedness-boundaries": 2,
            "increment-60f-equivalence-closure": 5,
            "morphhdl-native-source-guard": 1,
        }
        for name, count in counts.items():
            with self.subTest(workflow=name):
                path = ".github/workflows/" + name + ".yml"
                source = (A.ROOT / path).read_text()
                self.assertEqual(source.count("run-parameterized-inherited-audit.py "), count)
                if name.startswith(('increment-60c-', 'increment-60d-', 'increment-60e-')):
                    parallel = '          python3 morphhdl/scripts/run-parameterized-inherited-audit.py morphhdl/scripts/check-increment-61-source-review.py &\n          review_pid=$!\n          python3 morphhdl/scripts/run-parameterized-inherited-audit.py morphhdl/scripts/check-increment-61-source-review.py --self-test &\n          controls_pid=$!\n          audit_status=0\n          wait "$review_pid" || audit_status=1\n          wait "$controls_pid" || audit_status=1\n          test "$audit_status" -eq 0\n'
                    serial = '          python3 morphhdl/scripts/run-parameterized-inherited-audit.py morphhdl/scripts/check-increment-61-source-review.py\n          python3 morphhdl/scripts/run-parameterized-inherited-audit.py morphhdl/scripts/check-increment-61-source-review.py --self-test\n'
                    self.assertEqual(source.count(parallel), 1)
                    source = source.replace(parallel, serial)
                if name == 'increment-59c-named-field-vectors':
                    documentation = '      - name: Qualify generic documentation region markers on this Scala lane\n        shell: bash\n        run: |\n          set -euo pipefail\n          sbt -batch "++${{ matrix.scala }}" idslplugin/packageBin \\\n            \'idslplugin/testOnly spinal.idslplugin.DocumentationPluginTests\' \\\n            \'morph/testOnly morphhdl.RtlDocumentationTests\'\n          python3 - <<\'PYDOC\'\n          from pathlib import Path\n          import xml.etree.ElementTree as ET\n          for project, suite, count in [(\'idslplugin\', \'spinal.idslplugin.DocumentationPluginTests\', 7),\n                                        (\'morphhdl\', \'morphhdl.RtlDocumentationTests\', 29)]:\n              report = ET.parse(Path(project) / \'target/test-reports\' / (\'TEST-\' + suite + \'.xml\')).getroot()\n              assert int(report.get(\'tests\')) == count, report.attrib\n              assert all(int(report.get(key)) == 0 for key in (\'failures\', \'errors\', \'skipped\')), report.attrib\n          PYDOC\n'
                    self.assertEqual(source.count(documentation), 1)
                    source = source.replace(documentation, '')
                restored = source.replace("morphhdl/scripts/run-parameterized-inherited-audit.py ", "")
                added = (
                    "          python3 morphhdl/scripts/check-parameterized-integration-source.py\n",
                    "          python3 morphhdl/scripts/check-parameterized-integration-source.py --self-test\n",
                )
                for line in added:
                    self.assertEqual(restored.count(line), 1)
                    restored = restored.replace(line, "", 1)
                if name == "morphhdl-native-source-guard":
                    line = "          python3 morphhdl/scripts/test-parameterized-inherited-audit.py\n"
                    self.assertEqual(restored.count(line), 1)
                    restored = restored.replace(line, "", 1)
                if name == "increment-59i-combined-closure":
                    line = "          python3 morphhdl/scripts/check-native-source-preservation.py\n"
                    self.assertEqual(restored.count(line), 1)
                    restored = restored.replace(line, "", 1)
                jobs = ["source", "inherited_source"] if name == "increment-59i-combined-closure" else \
                    ["source"] if name == "increment-59h-nested-owners" else \
                    ["guard"] if name == "morphhdl-native-source-guard" else ["qualify"]
                for job in jobs:
                    step = ("      - name: Retain current and historical source audit receipts\n"
                        "        if: always()\n        uses: actions/upload-artifact@v4\n        with:\n"
                        "          name: integrated-source-" + name + "-" + job +
                        "-${{ matrix.scala || 'source' }}-${{ github.run_attempt }}\n"
                        "          path: target/parameterized-inherited-audits\n"
                        "          if-no-files-found: error\n          retention-days: 30\n\n")
                    if restored.endswith(step.rstrip()+"\n"):
                        step = step.rstrip()+"\n"
                    self.assertEqual(restored.count(step), 1)
                    restored = restored.replace(step, "", 1)
                self.assertEqual(restored.encode(), A.git(A.ROOT, "show", baseline + ":" + path),
                    "a trigger, job, command, matrix, proof, timeout or prior receipt changed")

    def test_new_reduction_workflows_preserve_behavioral_steps(self):
        baseline = "3e535c97d409fbdb0b2c49c33845d1c8cb75ca2e"
        for name, count in (("increment-59d-widening", 2),
                            ("increment-59f-callback-graphs", 5),
                            ("increment-59g-register-bridges", 6)):
            path = ".github/workflows/" + name + ".yml"
            current = (A.ROOT / path).read_text()
            original = A.git(A.ROOT, "show", baseline + ":" + path).decode()
            self.assertEqual(current.count("run-parameterized-inherited-audit.py "), count)
            # Only the source-review step and its receipt may differ. Preserve
            # every later build, simulation, lint, synthesis and artifact step.
            marker = "      - name: Bootstrap"
            old_tail = original[original.index(marker):]
            new_tail = current[current.index(marker):]
            receipt = new_tail.find("\n      - name: Retain inherited source audit receipts")
            if receipt >= 0:
                end = new_tail.find("\n  validate:\n", receipt)
                new_tail = new_tail[:receipt] + (new_tail[end:] if end >= 0 else "")
            # The post-build bridge review also runs at the authenticated
            # historical seal; all behavioral gates remain byte-for-byte.
            new_tail = new_tail.replace(
                "              import subprocess\n              subprocess.run(['python3', 'morphhdl/scripts/run-parameterized-inherited-audit.py', str(bridge_review)], check=True)",
                "              import runpy\n              runpy.run_path(str(bridge_review))['verify'](Path.cwd())")
            self.assertEqual(new_tail.rstrip(), old_tail.rstrip())
            for token in ("timeout-minutes:", "runs-on:", "container:", "matrix:"):
                self.assertEqual([line for line in current.splitlines() if token in line],
                                 [line for line in original.splitlines() if token in line])

    def test_only_exact_source_commands_are_admitted(self):
        for entry in A.COMMANDS:
            command = ["morphhdl/scripts/" + entry[0], *entry[1:]]
            self.assertEqual(A.command(command)[2:], command)
        for command in ([], ["/tmp/check-increment-61-source-review.py"],
                ["morphhdl/scripts/../scripts/check-increment-61-source-review.py"],
                ["morphhdl/scripts/check-increment-60f-equivalence-closure.py", "target/rtl"],
                ["morphhdl/scripts/check-increment-61-source-review.py", "--repo-root", "/tmp"],
                ["morphhdl/scripts/check-increment-61-source-review.py;true"],
                ["morphhdl/scripts/check-wa11-boolean-width.py"]):
            with self.subTest(command=command), self.assertRaises(RuntimeError):
                A.command(command)

    def test_unreviewed_current_source_prevents_historical_execution(self):
        with patch.object(A, "authenticate", side_effect=RuntimeError("dirty candidate")), \
                patch.object(A.subprocess, "run") as run:
            with self.assertRaisesRegex(RuntimeError, "dirty candidate"):
                A.run(Path("."), ["morphhdl/scripts/check-increment-61-source-review.py"])
            run.assert_not_called()

    def test_moving_candidate_cannot_borrow_historical_success(self):
        before = {"head": "first", "tree": "tree"}
        for after in ({"head": "second", "tree": "tree"}, {"head": "first", "tree": "other"}):
            with patch.object(A, "authenticate", return_value=after), self.assertRaises(RuntimeError):
                A.unchanged(Path("."), before)

    def exercise(self, body, expected_exit):
        with tempfile.TemporaryDirectory(prefix="integrated-audit-control-") as directory:
            root = Path(directory)
            subprocess.run(["git", "init", "-q", directory], check=True)
            A.git(root, "config", "user.name", "Source review control")
            A.git(root, "config", "user.email", "source-review@example.invalid")
            script = "morphhdl/scripts/check-increment-61-source-review.py"
            path = root / script
            path.parent.mkdir(parents=True)
            path.write_text(body)
            A.git(root, "add", script)
            A.git(root, "commit", "-qm", "immutable synthetic audit")
            before = A.identity(root)
            with patch.object(A, "PREDECESSOR", before["head"]), \
                    patch.object(A, "authenticate", return_value=before), \
                    contextlib.redirect_stdout(io.StringIO()):
                if expected_exit:
                    with self.assertRaisesRegex(RuntimeError, "historical audit failed"):
                        A.run(root, [script])
                else:
                    A.run(root, [script])
            receipts = list((root / "target").rglob("receipt.json"))
            self.assertEqual(len(receipts), 1)
            receipt = json.loads(receipts[0].read_text())
            self.assertEqual(receipt["exit"], expected_exit)
            self.assertEqual(receipt["current_source"], before)
            self.assertEqual(receipt["historical_source"], before["head"])
            self.assertEqual(receipt["status"], "fail" if expected_exit else "pass")
            self.assertFalse(receipt["rtl_qualification"])
            self.assertEqual(A.identity(root), before)
            self.assertEqual(A.git(root, "worktree", "list", "--porcelain").count(b"worktree "), 1)

    def test_historical_receipt_is_separate_from_current_identity(self):
        self.exercise("print('historical source control passed')\n", 0)

    def test_nonzero_historical_control_is_never_a_pass(self):
        self.exercise("raise SystemExit(7)\n", 7)


if __name__ == "__main__":
    unittest.main(verbosity=2)
