#!/usr/bin/env python3
"""Keep every inherited audit intact while budgeting the complete 59i traversal.

Subprocess results here are synthetic scheduling controls, not source or RTL
qualification. Real inherited harnesses must run separately on the sealed head.
"""
from __future__ import annotations

import ast
import copy
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import contextlib

ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = ROOT / "morphhdl/scripts"
SUCCESSOR = "morphhdl/contracts/increment-59i-production-successor.json"
PARENT = "morphhdl/contracts/increment-59i-target-integration.json"
PASS = "inherited native audits PASS"
# Whole-module fingerprints at immutable source 214774fb. The normalizer below
# reverses only each reviewed budget-selector edit; all fixtures and checks are
# covered, not merely their names or counts.
CASES = (
    ("test-increment-59c-inherited-source-scope.py", "checked", 600, 120,
     "48838a945cee07b461843a01cef2673497c93f0b018e50c4b8f18899dffb049e"),
    ("test-increment-59f-source-scope.py", "check", 600, 120,
     "f348c359030c9a00719074b33c647fe1103ae3b9b7511edfa72b93035f19ab25"),
    ("test-increment-59g-source-review.py", "check", 180, 180,
     "54ab94d7d9f34f2ffbb4842720ae9ac6565cd1feca64d591c66e50f7507c634e"),
    ("test-increment-59h-inherited-source-scope.py", "checked", 600, 180,
     "fabe57e5c75d0d58f9cdffebe5218bc38c0089f4d9fbd89f5f91b20f8508a227"),
)


def load(filename: str):
    spec = importlib.util.spec_from_file_location(filename.replace("-", "_"), SCRIPTS / filename)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def canonical_ast(node):
    # Use explicit fields, not version-dependent ast.dump display formatting.
    # Only absent/empty type_params are equivalent for these non-generic files.
    # Retain every statement, other field, list item and nonempty type parameter.
    if isinstance(node, ast.AST):
        return [type(node).__name__, [[name, canonical_ast(value)]
            for name, value in ast.iter_fields(node)
            if not (name == "type_params" and value == [])]]
    if isinstance(node, list):
        return [canonical_ast(item) for item in node]
    if isinstance(node, bytes):
        return ["bytes", node.hex()]
    if isinstance(node, complex):
        return ["complex", repr(node)]
    if node is Ellipsis:
        return ["ellipsis"]
    return node


def dump(node: ast.AST) -> str:
    return json.dumps(canonical_ast(node), ensure_ascii=True, separators=(",", ":"))


def selector(default: int) -> ast.FunctionDef:
    return ast.parse(f'''def current_positive_timeout(root: Path) -> int:
    if (root / "{SUCCESSOR}").is_file():
        return 3600
    return 900 if (root / "{PARENT}").is_file() else {default}
''').body[0]


def restore_reviewed_59h_diagnostics(tree: ast.Module) -> ast.Module:
    """Reverse only PR189's exact current-before-history rejection routing.

    The complete original AST hash below remains unchanged. Neither timeouts,
    mutation payloads, exception checks nor any other diagnostic may change.
    """
    reviewed = ast.parse('''if label == "changed native printer" and mutation == "suffix" and relative in overlay_paths:
    expected = "WA-08 source overlay: unreviewed production delta: current reviewed bytes differ: " + relative
elif mutation in ("suffix", "inside") and relative in overlay_paths:
    expected = "WA-08 source overlay: unreviewed bytes cannot enter historical projection: " + relative
''').body[0]
    original = ast.parse('''if mutation == "suffix" and relative in overlay_paths:
    expected = "WA-08 source overlay: unreviewed bytes cannot enter historical projection: " + relative
''').body[0]

    class RestoreDiagnostics(ast.NodeTransformer):
        count = 0

        def visit_If(self, node):
            # Preserve the untouched tail (including all 59d/59f diagnostics).
            if dump(node.test) == dump(reviewed.test):
                candidate = copy.deepcopy(node)
                if len(candidate.orelse) != 1 or not isinstance(candidate.orelse[0], ast.If):
                    raise AssertionError("unexpected PR189 diagnostic routing")
                tail = candidate.orelse[0].orelse
                candidate.orelse[0].orelse = []
                if dump(candidate) != dump(reviewed):
                    raise AssertionError("unexpected PR189 diagnostic routing")
                restored = copy.deepcopy(original)
                restored.orelse = tail
                self.count += 1
                return restored
            return self.generic_visit(node)

    restorer = RestoreDiagnostics()
    tree = restorer.visit(tree)
    if restorer.count != 1:
        raise AssertionError("expected exactly one reviewed PR189 diagnostic routing")
    return tree


def restore_reviewed_59h_checkout_identity(tree: ast.Module) -> ast.Module:
    """Reverse only schema 7's exact inherited-checkout rejection route."""
    assignment = ast.parse('successor_tree = successor.tree(ROOT, "HEAD")').body[0]
    reviewed = ast.parse('''if mutation in ("suffix", "inside") and relative in successor_tree:
    expected = "59i production successor: HEAD/index/worktree identity differs: " + relative
''').body[0]

    class RestoreCheckoutIdentity(ast.NodeTransformer):
        assignment_count = 0
        route_count = 0

        def visit_Assign(self, node):
            if dump(node) == dump(assignment):
                self.assignment_count += 1
                return None
            return self.generic_visit(node)

        def visit_If(self, node):
            if dump(node.test) == dump(reviewed.test):
                candidate = copy.deepcopy(node)
                tail = candidate.orelse
                candidate.orelse = []
                if dump(candidate) != dump(reviewed) or len(tail) != 1 or not isinstance(tail[0], ast.If):
                    raise AssertionError("unexpected schema-7 checkout-identity routing")
                self.route_count += 1
                return self.visit(tail[0])
            return self.generic_visit(node)

    restorer = RestoreCheckoutIdentity()
    tree = restorer.visit(tree)
    if restorer.assignment_count != 1 or restorer.route_count != 1:
        raise AssertionError("expected exactly one reviewed schema-7 checkout-identity route")
    return tree


def historical_ast(filename: str, text: str) -> ast.Module:
    default = next(row[2] for row in CASES if row[0] == filename)
    tree = ast.parse(text)
    if filename == "test-increment-59h-inherited-source-scope.py":
        tree = restore_reviewed_59h_checkout_identity(tree)
        tree = restore_reviewed_59h_diagnostics(tree)
    if filename == "test-increment-59g-source-review.py":
        joined = dump(ast.parse("max(600, current_positive_timeout(ROOT))", mode="eval").body)
        unjoined = ast.parse("current_positive_timeout(ROOT)", mode="eval").body

        class RestoreJoinedBudget(ast.NodeTransformer):
            count = 0

            def visit_Call(self, node):
                if dump(node) == joined:
                    self.count += 1
                    return copy.deepcopy(unjoined)
                return self.generic_visit(node)

        restorer = RestoreJoinedBudget()
        tree = restorer.visit(tree)
        if restorer.count != 1:
            raise AssertionError("expected exactly one reviewed 600s/59i maximum budget")
    helpers = [node for node in tree.body if isinstance(node, ast.FunctionDef)
               and node.name == "current_positive_timeout"]
    if len(helpers) != 1 or dump(helpers[0]) != dump(selector(default)):
        raise AssertionError("unexpected current-positive selector")
    helper = helpers[0]
    if filename == "test-increment-59h-inherited-source-scope.py":
        helper.body = helper.body[1:]
    else:
        tree.body.remove(helper)
        expected_call = dump(ast.parse("current_positive_timeout(ROOT)", mode="eval").body)
        original = ast.parse(f'900 if (ROOT / "{PARENT}").is_file() else {default}', mode="eval").body

        class RestoreOnlyOneCall(ast.NodeTransformer):
            count = 0

            def visit_Call(self, node):
                if dump(node) == expected_call:
                    self.count += 1
                    return copy.deepcopy(original)
                return self.generic_visit(node)

        restorer = RestoreOnlyOneCall()
        tree = restorer.visit(tree)
        if restorer.count != 1:
            raise AssertionError("expected exactly one current-positive selector call")
    return tree


def write_marker(root: Path, name: str) -> None:
    file = root / name
    file.parent.mkdir(parents=True, exist_ok=True)
    file.write_text("Presence chooses time only; the actual source audit authenticates the file.\n")


class InheritedAuditBudgetTests(unittest.TestCase):
    def test_joined_59g_budget_preserves_all_three_positive_cases(self):
        for present, expected in (((), 600), ((PARENT,), 900), ((SUCCESSOR,), 3600)):
            with self.subTest(present=present), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                for name in present:
                    write_marker(root, name)
                actual = max(600, load("test-increment-59g-source-review.py").current_positive_timeout(root))
                self.assertEqual(actual, expected)

    def test_empty_parser_type_params_are_portable(self):
        tree = ast.parse("def probe():\n    pass\n")
        expected = dump(tree)
        function = tree.body[0]
        if "type_params" not in function._fields:
            function._fields += ("type_params",)
        function.type_params = []
        self.assertEqual(dump(tree), expected)

    def test_nonempty_type_params_are_not_discarded(self):
        tree = ast.parse("def probe():\n    pass\n")
        expected = dump(tree)
        function = tree.body[0]
        if "type_params" not in function._fields:
            function._fields += ("type_params",)
        function.type_params = [ast.Name(id="T", ctx=ast.Load())]
        self.assertNotEqual(dump(tree), expected)

    def test_byte_literals_are_not_confused_with_strings(self):
        self.assertNotEqual(dump(ast.parse("x = b'a'")), dump(ast.parse("x = 'a'")))
        self.assertNotEqual(dump(ast.parse("x = b'a'")), dump(ast.parse("x = b'b'")))

    def test_complex_and_ellipsis_literals_remain_distinct(self):
        self.assertNotEqual(dump(ast.parse("x = 1j")), dump(ast.parse("x = 2j")))
        self.assertNotEqual(dump(ast.parse("x = ...")), dump(ast.parse("x = None")))

    def test_target_and_historical_budgets_unchanged(self):
        for filename, _, default, _, _ in CASES:
            with self.subTest(filename=filename), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                module = load(filename)
                self.assertEqual(module.current_positive_timeout(root), default)
                write_marker(root, PARENT)
                self.assertEqual(module.current_positive_timeout(root), 900)

    def test_successor_budget_with_and_without_parent_marker(self):
        for filename, _, default, _, _ in CASES:
            with self.subTest(filename=filename), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                module = load(filename)
                write_marker(root, SUCCESSOR)
                self.assertEqual(module.current_positive_timeout(root), 3600)
                write_marker(root, PARENT)
                self.assertEqual(module.current_positive_timeout(root), 3600)
                (root / SUCCESSOR).unlink()
                self.assertEqual(module.current_positive_timeout(root), 900)
                (root / PARENT).unlink()
                self.assertEqual(module.current_positive_timeout(root), default)

    def test_successor_directory_is_not_a_source_manifest(self):
        for filename, _, default, _, _ in CASES:
            with self.subTest(filename=filename), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                (root / SUCCESSOR).mkdir(parents=True)
                self.assertEqual(load(filename).current_positive_timeout(root), default)

    @staticmethod
    def invoke(module, name, root, expected=None, timeout=None):
        caller = getattr(module, name)
        args = ([root, module.CLOSURE, "synthetic audit", expected]
                if hasattr(module, "CLOSURE") else [root, "synthetic audit", expected])
        kwargs = {} if timeout is None else {"timeout_seconds": timeout}
        return caller(*args, **kwargs)

    def test_negative_wrapper_defaults_remain_120_or_180(self):
        for filename, caller, _, negative_budget, _ in CASES:
            module = load(filename)
            result = SimpleNamespace(returncode=1, stdout="precise rejection")
            with self.subTest(filename=filename), contextlib.redirect_stdout(io.StringIO()), \
                    patch.object(module, "git", return_value="head"), \
                    patch.object(module.subprocess, "run", return_value=result) as run:
                self.invoke(module, caller, ROOT, "precise rejection")
                self.assertEqual(run.call_args.kwargs["timeout"], negative_budget)

    def test_git_commands_remain_120_seconds(self):
        for filename, _, _, _, _ in CASES:
            module = load(filename)
            # 59f uses check_output; its subprocess module still delegates to
            # run internally, so isolate the actual public call used there.
            operation = "check_output" if hasattr(module, "CLOSURE") else "run"
            value = "head" if operation == "check_output" else SimpleNamespace(returncode=0, stdout="head")
            with self.subTest(filename=filename), patch.object(module.subprocess, operation, return_value=value) as run:
                module.git(ROOT, "rev-parse", "HEAD")
                self.assertEqual(run.call_args.kwargs["timeout"], 120)

    def test_positive_budget_reaches_same_complete_checker(self):
        for filename, caller, _, _, _ in CASES:
            module = load(filename)
            with self.subTest(filename=filename), contextlib.redirect_stdout(io.StringIO()), \
                    patch.object(module, "git", return_value="head"), \
                    patch.object(module.subprocess, "run", return_value=SimpleNamespace(returncode=0, stdout=PASS)) as run:
                self.invoke(module, caller, ROOT, timeout=3600)
                self.assertEqual(run.call_args.kwargs["timeout"], 3600)
                self.assertTrue(any("check-increment-60f-equivalence-closure.py" in str(arg)
                                    for arg in run.call_args.args[0]))

    def test_actual_main_selects_budget_before_any_fixture(self):
        class StopBeforeFixtures(Exception):
            pass

        for filename, _, _, _, _ in CASES:
            module = load(filename)
            with self.subTest(filename=filename), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                for name in (SUCCESSOR, PARENT, "morphhdl/contracts/increment-59h-source-review.json",
                             "morphhdl/contracts/increment-59c-source-review.json"):
                    write_marker(root, name)
                # The historical-run adapter calls the real current callback,
                # then stops before invoking the unchanged historical suite.
                def frozen(root, relative, out, checks, marker):
                    checks()
                    raise StopBeforeFixtures
                adapter = SimpleNamespace(frozen_inherited_fixture=frozen)
                spec = SimpleNamespace(loader=SimpleNamespace(exec_module=lambda _: None))
                with patch.object(module, "ROOT", root), patch.object(module, "git", return_value="head"), \
                        patch.object(module.subprocess, "run", return_value=SimpleNamespace(returncode=0, stdout=PASS)) as run, \
                        contextlib.redirect_stdout(io.StringIO()):
                    if filename in (CASES[0][0], CASES[1][0]):
                        with patch.object(module.importlib.util, "spec_from_file_location", return_value=spec), \
                                patch.object(module.importlib.util, "module_from_spec", return_value=adapter), \
                                self.assertRaises(StopBeforeFixtures):
                            module.main()
                    else:
                        with patch.object(module.importlib.util, "spec_from_file_location", side_effect=StopBeforeFixtures), \
                                self.assertRaises(StopBeforeFixtures):
                            module.main()
                    expected = [120, 3600] if filename == CASES[1][0] else [3600]
                    self.assertEqual([call.kwargs["timeout"] for call in run.call_args_list], expected)

    def test_timeout_never_counts_as_positive_or_negative(self):
        for filename, caller, _, negative_budget, _ in CASES:
            module = load(filename)
            for expected, budget in ((None, 3600), ("precise rejection", negative_budget)):
                with self.subTest(filename=filename, expected=expected), \
                        patch.object(module.subprocess, "run", side_effect=subprocess.TimeoutExpired("audit", budget)), \
                        self.assertRaises(subprocess.TimeoutExpired):
                    self.invoke(module, caller, ROOT, expected, budget)

    def test_success_marker_does_not_hide_failure(self):
        for filename, caller, _, _, _ in CASES:
            module = load(filename)
            with self.subTest(filename=filename), \
                    patch.object(module.subprocess, "run", return_value=SimpleNamespace(returncode=1, stdout=PASS)), \
                    self.assertRaises(RuntimeError):
                self.invoke(module, caller, ROOT, timeout=3600)

    def test_rejection_marker_does_not_hide_success(self):
        for filename, caller, _, _, _ in CASES:
            module = load(filename)
            with self.subTest(filename=filename), \
                    patch.object(module.subprocess, "run", return_value=SimpleNamespace(returncode=0, stdout="precise rejection")), \
                    self.assertRaises(RuntimeError):
                self.invoke(module, caller, ROOT, "precise rejection")

    def test_complete_original_harness_ast_is_retained(self):
        for filename, _, _, _, expected in CASES:
            with self.subTest(filename=filename):
                restored = historical_ast(filename, (SCRIPTS / filename).read_text())
                self.assertEqual(hashlib.sha256(dump(restored).encode()).hexdigest(), expected)

    def test_reviewed_59h_diagnostic_mutations_do_not_escape_original_fingerprint(self):
        name, _, _, _, expected = CASES[-1]
        text = (SCRIPTS / name).read_text()
        for before, after in (
            ('label == "changed native printer"', 'label != "changed native printer"'),
            ('mutation in ("suffix", "inside")', 'mutation in ("suffix", "inside", "anything")'),
            ('current reviewed bytes differ: ', 'different failure: '),
            ('unreviewed bytes cannot enter historical projection: ', 'accepted: '),
            ('elif mutation in ("suffix", "inside")', 'elif True'),
            ('relative in successor_tree', 'relative not in successor_tree'),
            ('HEAD/index/worktree identity differs: ', 'accepted checkout: '),
            ('successor_tree = successor.tree(ROOT, "HEAD")', 'successor_tree = {}'),
        ):
            self.assertIn(before, text)
            with self.subTest(before=before):
                changed = text.replace(before, after, 1)
                try:
                    value = historical_ast(name, changed)
                except AssertionError:
                    continue
                self.assertNotEqual(hashlib.sha256(dump(value).encode()).hexdigest(), expected)

    def test_duplicate_or_missing_reviewed_diagnostic_route_rejects(self):
        text = (SCRIPTS / CASES[-1][0]).read_text()
        for changed in (text.replace('label == "changed native printer"', 'label == "renamed"', 1),
                        text + '\n' + ast.unparse(ast.parse(text).body[-1]) + '\n'):
            # Missing route must fail. Appended unrelated statements must either
            # fail exact normalization or change the whole-module fingerprint.
            try:
                value = historical_ast(CASES[-1][0], changed)
            except AssertionError:
                continue
            self.assertNotEqual(hashlib.sha256(dump(value).encode()).hexdigest(), CASES[-1][-1])

    def test_selector_mutations_are_rejected(self):
        for filename, _, _, _, _ in CASES:
            text = (SCRIPTS / filename).read_text()
            for replacement in ("return 3599", "return 0", "return None"):
                with self.subTest(filename=filename, replacement=replacement), \
                        self.assertRaisesRegex(AssertionError, "unexpected current-positive selector"):
                    historical_ast(filename, text.replace("return 3600", replacement, 1))

    def test_hidden_git_limit_mutation_changes_fingerprint(self):
        for filename, _, _, _, expected in CASES:
            text = (SCRIPTS / filename).read_text()
            self.assertIn("timeout=120", text)
            restored = historical_ast(filename, text.replace("timeout=120", "timeout=121", 1))
            self.assertNotEqual(hashlib.sha256(dump(restored).encode()).hexdigest(), expected)

    def test_repeated_or_missing_selector_call_is_rejected(self):
        for filename, _, _, _, _ in CASES[:3]:
            text = (SCRIPTS / filename).read_text()
            for replacement in ("1", "max(current_positive_timeout(ROOT), current_positive_timeout(ROOT))"):
                with self.subTest(filename=filename, replacement=replacement), \
                        self.assertRaisesRegex(AssertionError, "exactly one"):
                    historical_ast(filename, text.replace("current_positive_timeout(ROOT)", replacement, 1))

    def test_enclosing_job_limits_include_current_audit_and_other_steps(self):
        for workflow, budget in (("increment-59b-inherited-source-scope.yml", 90),
                                 ("increment-59c-named-field-vectors.yml", 120)):
            text = (ROOT / ".github/workflows" / workflow).read_text()
            self.assertIn("timeout-minutes: " + str(budget), text)
            self.assertGreater(budget * 60, 3600 + 900)
            self.assertNotIn("continue-on-error: true", text)

    def test_new_controls_are_additively_enrolled(self):
        for workflow in ("increment-59i-inherited-source-qualification.yml",
                         "increment-60f-equivalence-closure.yml"):
            text = (ROOT / ".github/workflows" / workflow).read_text()
            for script in (Path(__file__).name, "test-increment-60f-source-budget.py",
                           "test-increment-60f-source-scheduling.py"):
                self.assertIn("python3 morphhdl/scripts/" + script, text)


if __name__ == "__main__":
    unittest.main(verbosity=2)
