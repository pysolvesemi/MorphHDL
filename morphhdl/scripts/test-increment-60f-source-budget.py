#!/usr/bin/env python3
"""Preserve the complete inherited 60f harness while extending current budgets.

These are scheduling/result-classification controls, not execution of the
inherited source audits. All 18 original audit cases remain in the harness.
"""
from __future__ import annotations
import ast
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
HARNESS = ROOT / "morphhdl/scripts/test-increment-60f-inherited-source-scope.py"
FROZEN_HARNESS_AST_SHA256 = "72eb06302169fdc72edaf8ecbef15693dd3a265988728b60f3d300da67c7810b"


def canonical_ast(node):
    """Stable across Python AST display versions, without dropping semantics.

    Python 3.12 added empty type_params fields; Python 3.13 changed ast.dump's
    empty-list formatting. Serialize explicit fields instead. Only the absent
    versus empty type-parameter list is equivalent for this non-generic source.
    Nonempty type parameters, every statement and every other field are kept.
    """
    if isinstance(node, ast.AST):
        fields = []
        for name, value in ast.iter_fields(node):
            if name == "type_params" and value == []:
                continue
            fields.append([name, canonical_ast(value)])
        return [type(node).__name__, fields]
    if isinstance(node, list):
        return [canonical_ast(item) for item in node]
    return node


def ast_digest(node):
    raw = json.dumps(canonical_ast(node), ensure_ascii=True,
                     separators=(",", ":")).encode()
    return hashlib.sha256(raw).hexdigest()


def load_harness():
    spec = importlib.util.spec_from_file_location("inherited_60f_budget_control", HARNESS)
    if spec is None or spec.loader is None:
        raise RuntimeError("cannot load inherited source harness")
    module = importlib.util.module_from_spec(spec)
    exec(compile(HARNESS.read_bytes(), str(HARNESS), "exec"), module.__dict__)
    return module


def historical_harness(tree):
    """Reverse exactly the reviewed scheduling helpers, never an arbitrary edit.

    Authenticate both the complete helper AST and its sole call site before
    removing that one layer. The whole restored module must still match the
    original portable AST fingerprint; cases, diagnostics and subprocess
    handling are not filtered out of that fingerprint.
    """
    result = copy.deepcopy(tree)
    functions = [n for n in result.body if isinstance(n, ast.FunctionDef)
                 and n.name == "current_positive_timeout"]
    expected = ast.parse('''def current_positive_timeout(root: Path) -> int:
    if (root / "morphhdl/contracts/increment-59i-production-successor.json").is_file():
        return 3600
    return 900 if (root / "morphhdl/contracts/increment-59i-target-integration.json").is_file() else 600
''').body[0]
    if len(functions) != 1 or canonical_ast(functions[0]) != canonical_ast(expected):
        raise RuntimeError("current-positive scheduling helper changed")
    negative_functions = [n for n in result.body if isinstance(n, ast.FunctionDef)
                          and n.name == "current_negative_timeout"]
    negative_expected = ast.parse('''def current_negative_timeout(root: Path) -> int:
    if (root / "morphhdl/contracts/increment-59i-production-successor.json").is_file():
        return 600
    return 120
''').body[0]
    if (len(negative_functions) != 1 or
            canonical_ast(negative_functions[0]) != canonical_ast(negative_expected)):
        raise RuntimeError("current-negative scheduling helper changed")
    main = next(n for n in result.body if isinstance(n, ast.FunctionDef) and n.name == "main")
    rejection_functions = [n for n in main.body if isinstance(n, ast.FunctionDef)
                           and n.name == "current_successor_rejection"]
    rejection_expected = ast.parse('''def current_successor_rejection(path: str, state: str) -> str:
    contract = ROOT / "morphhdl/contracts/increment-59i-production-successor.json"
    schema = json.loads(contract.read_text()).get("schema_version", 0) if contract.is_file() else 0
    if schema >= 7:
        if state == "committed":
            return "59i production successor: sealed route tree differs from immutable source plus exact seal"
        if state == "uncommitted":
            return "59i production successor: HEAD/index/worktree identity differs: " + path
        if state == "staged":
            return "59i production successor: HEAD/index identity differs"
        raise RuntimeError("unknown current-successor fixture state: " + state)
    if state == "staged":
        return "WA-08 source overlay: HEAD/index/worktree identity differs: " + path
    return changed_successor(path)
''').body[0]
    if (len(rejection_functions) != 1 or
            canonical_ast(rejection_functions[0]) != canonical_ast(rejection_expected)):
        raise RuntimeError("current-successor rejection helper changed")
    rejection_calls = [n for n in ast.walk(main) if isinstance(n, ast.Call)
                       and isinstance(n.func, ast.Name)
                       and n.func.id == "current_successor_rejection"]
    expected_rejection_calls = {
        ("core/src/main/scala/spinal/core/internals/ComponentEmitterVerilog.scala", "committed"),
        ("core/src/main/scala/spinal/core/internals/ComponentEmitterVerilog.scala", "uncommitted"),
        ("core/src/main/scala/spinal/core/internals/ComponentEmitterVerilog.scala", "staged"),
        ("morphhdl-passes/src/main/scala/morphhdl/passes/api/PassContracts.scala", "committed"),
        ("morphhdl-passes/src/main/scala/morphhdl/passes/api/PassContracts.scala", "staged"),
    }
    actual_rejection_calls = {
        (n.args[0].value, n.args[1].value) for n in rejection_calls
        if len(n.args) == 2 and all(isinstance(arg, ast.Constant) for arg in n.args)
    }
    if (len(rejection_calls) != len(expected_rejection_calls) or
            actual_rejection_calls != expected_rejection_calls):
        raise RuntimeError("current-successor rejection calls changed")
    budgets = [n for n in main.body if isinstance(n, ast.Assign)
               and any(isinstance(t, ast.Name) and t.id == "timeout" for t in n.targets)]
    call = ast.parse("current_positive_timeout(ROOT)", mode="eval").body
    if len(budgets) != 1 or canonical_ast(budgets[0].value) != canonical_ast(call):
        raise RuntimeError("current-positive scheduling call changed")
    negative_call = ast.parse("current_negative_timeout(fixture)", mode="eval").body
    negative_keywords = [keyword for node in ast.walk(main)
                         if isinstance(node, ast.Call) and isinstance(node.func, ast.Name)
                         and node.func.id == "check" for keyword in node.keywords
                         if keyword.arg == "timeout_seconds"
                         and canonical_ast(keyword.value) == canonical_ast(negative_call)]
    if len(negative_keywords) != 1:
        raise RuntimeError("current-negative scheduling call changed")
    result.body.remove(functions[0])
    result.body.remove(negative_functions[0])
    main.body.remove(rejection_functions[0])
    budgets[0].value = ast.parse(
        '900 if (ROOT / "morphhdl/contracts/increment-59i-target-integration.json").is_file() else 600',
        mode="eval").body
    for node in ast.walk(main):
        if isinstance(node, ast.Call) and negative_keywords[0] in node.keywords:
            node.keywords.remove(negative_keywords[0])
            break
    class RestoreHistoricalRejections(ast.NodeTransformer):
        def visit_Call(self, node):
            node = self.generic_visit(node)
            if not (isinstance(node.func, ast.Name) and
                    node.func.id == "current_successor_rejection"):
                return node
            path, state = (arg.value for arg in node.args)
            if state == "staged":
                return ast.copy_location(ast.Constant(
                    value="WA-08 source overlay: HEAD/index/worktree identity differs: " + path), node)
            return ast.copy_location(ast.Call(func=ast.Name(id="changed_successor", ctx=ast.Load()),
                                              args=[ast.Constant(value=path)], keywords=[]), node)
    RestoreHistoricalRejections().visit(main)
    return result


class SourceBudgetTests(unittest.TestCase):
    def setUp(self):
        self.harness = load_harness()
        tree = ast.parse(HARNESS.read_bytes())
        main = next(n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == "main")
        budgets = [n for n in main.body if isinstance(n, ast.Assign) and
                   any(isinstance(t, ast.Name) and t.id == "timeout" for t in n.targets)]
        self.assertEqual(len(budgets), 1)
        self.expression = budgets[0].value
        self.tree = tree

    def budget(self, root):
        return eval(compile(ast.Expression(self.expression), str(HARNESS), "eval"),
                    {"ROOT": root, "current_positive_timeout": self.harness.current_positive_timeout})

    def test_only_current_timeouts_change_in_complete_harness(self):
        restored = historical_harness(self.tree)
        self.assertEqual(ast_digest(restored), FROZEN_HARNESS_AST_SHA256)

    def test_empty_legacy_type_parameter_fields_have_the_same_fingerprint(self):
        original = ast_digest(self.tree)
        legacy = copy.deepcopy(self.tree)
        for node in ast.walk(legacy):
            if hasattr(node, "type_params") and node.type_params == []:
                delattr(node, "type_params")
        self.assertEqual(ast_digest(legacy), original)

    def test_nonempty_type_parameters_are_not_erased(self):
        changed = copy.deepcopy(self.tree)
        function = next(n for n in changed.body if isinstance(n, ast.FunctionDef))
        if "type_params" not in function._fields:
            function._fields = function._fields + ("type_params",)
        function.type_params = [ast.Name(id="UnexpectedGeneric", ctx=ast.Load())]
        self.assertNotEqual(ast_digest(changed), ast_digest(self.tree))

    def test_original_case_mutation_changes_fingerprint(self):
        changed = copy.deepcopy(self.tree)
        constant = next(n for n in ast.walk(changed) if isinstance(n, ast.Constant)
                        and n.value == "qualified 60f must be an ancestor of HEAD")
        constant.value = "unrelated failure"
        self.assertNotEqual(ast_digest(changed), ast_digest(self.tree))

    def test_current_join_receives_complete_audit_budget(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            marker = root / "morphhdl/contracts/increment-59i-production-successor.json"
            marker.parent.mkdir(parents=True)
            marker.write_text("{}")
            self.assertEqual(self.budget(root), 3600)

    def test_legacy_current_positive_budget_is_unchanged(self):
        with tempfile.TemporaryDirectory() as directory:
            self.assertEqual(self.budget(Path(directory)), 600)

    def test_current_successor_negative_budget_has_bounded_headroom(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            marker = root / "morphhdl/contracts/increment-59i-production-successor.json"
            marker.parent.mkdir(parents=True)
            marker.write_text("{}")
            self.assertEqual(self.harness.current_negative_timeout(root), 600)

    def test_historical_negative_budget_is_unchanged(self):
        with tempfile.TemporaryDirectory() as directory:
            self.assertEqual(self.harness.current_negative_timeout(Path(directory)), 120)

    def test_historical_positive_still_has_120_seconds(self):
        result = subprocess.CompletedProcess([], 0, "inherited native audits PASS")
        with patch.object(self.harness.subprocess, "run", return_value=result) as run:
            self.harness.check(ROOT, "historical positive")
        self.assertEqual(run.call_args.kwargs["timeout"], 120)

    def test_negative_case_still_has_120_seconds_and_requires_rejection(self):
        result = subprocess.CompletedProcess([], 1, "exact frozen rejection")
        with patch.object(self.harness.subprocess, "run", return_value=result) as run:
            self.harness.check(ROOT, "negative", "exact frozen rejection")
        self.assertEqual(run.call_args.kwargs["timeout"], 120)

    def test_fixture_git_still_has_120_seconds(self):
        result = subprocess.CompletedProcess([], 0, "fixture result")
        with patch.object(self.harness.subprocess, "run", return_value=result) as run:
            self.harness.git(ROOT, "rev-parse", "HEAD")
        self.assertEqual(run.call_args.kwargs["timeout"], 120)

    def test_timeout_never_becomes_success(self):
        with patch.object(self.harness.subprocess, "run",
                          side_effect=subprocess.TimeoutExpired("audit", 3600)):
            with self.assertRaises(subprocess.TimeoutExpired):
                self.harness.check(ROOT, "current positive", timeout_seconds=3600)

    def test_positive_marker_does_not_override_nonzero_exit(self):
        result = subprocess.CompletedProcess([], 1, "inherited native audits PASS")
        with patch.object(self.harness.subprocess, "run", return_value=result):
            with self.assertRaisesRegex(RuntimeError, "did not pass"):
                self.harness.check(ROOT, "positive", timeout_seconds=3600)

    def test_negative_marker_does_not_override_success_exit(self):
        result = subprocess.CompletedProcess([], 0, "exact frozen rejection")
        with patch.object(self.harness.subprocess, "run", return_value=result):
            with self.assertRaisesRegex(RuntimeError, "did not reject"):
                self.harness.check(ROOT, "negative", "exact frozen rejection")

    def test_unrelated_rejection_does_not_pass(self):
        result = subprocess.CompletedProcess([], 1, "unrelated failure")
        with patch.object(self.harness.subprocess, "run", return_value=result):
            with self.assertRaisesRegex(RuntimeError, "did not reject"):
                self.harness.check(ROOT, "negative", "exact frozen rejection")


    def test_historical_join_without_production_successor_keeps_900_seconds(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            marker = root / "morphhdl/contracts/increment-59i-target-integration.json"
            marker.parent.mkdir(parents=True)
            marker.write_text("{}")
            self.assertEqual(self.budget(root), 900)

    def test_altered_helper_is_rejected_before_historical_projection(self):
        changed = copy.deepcopy(self.tree)
        helper = next(n for n in changed.body if isinstance(n, ast.FunctionDef)
                      and n.name == "current_positive_timeout")
        next(n for n in ast.walk(helper) if isinstance(n, ast.Constant) and n.value == 3600).value = 3601
        with self.assertRaisesRegex(RuntimeError, "scheduling helper changed"):
            historical_harness(changed)

    def test_altered_negative_helper_is_rejected_before_historical_projection(self):
        changed = copy.deepcopy(self.tree)
        helper = next(n for n in changed.body if isinstance(n, ast.FunctionDef)
                      and n.name == "current_negative_timeout")
        next(n for n in ast.walk(helper) if isinstance(n, ast.Constant) and n.value == 600).value = 601
        with self.assertRaisesRegex(RuntimeError, "current-negative scheduling helper changed"):
            historical_harness(changed)

    def test_altered_current_rejection_helper_is_rejected_before_projection(self):
        changed = copy.deepcopy(self.tree)
        main = next(n for n in changed.body if isinstance(n, ast.FunctionDef) and n.name == "main")
        helper = next(n for n in main.body if isinstance(n, ast.FunctionDef)
                      and n.name == "current_successor_rejection")
        next(n for n in ast.walk(helper) if isinstance(n, ast.Constant)
             and n.value == "59i production successor: HEAD/index identity differs").value = "weaker"
        with self.assertRaisesRegex(RuntimeError, "current-successor rejection helper changed"):
            historical_harness(changed)

    def test_changed_current_rejection_call_is_rejected_before_projection(self):
        changed = copy.deepcopy(self.tree)
        call = next(n for n in ast.walk(changed) if isinstance(n, ast.Call)
                    and isinstance(n.func, ast.Name)
                    and n.func.id == "current_successor_rejection")
        call.args[1] = ast.Constant(value="unexpected")
        with self.assertRaisesRegex(RuntimeError, "current-successor rejection calls changed"):
            historical_harness(changed)

    def test_changed_call_cannot_be_hidden_by_projection(self):
        changed = copy.deepcopy(self.tree)
        main = next(n for n in changed.body if isinstance(n, ast.FunctionDef) and n.name == "main")
        budget = next(n for n in main.body if isinstance(n, ast.Assign)
                      and any(isinstance(t, ast.Name) and t.id == "timeout" for t in n.targets))
        budget.value = ast.Constant(value=3600)
        with self.assertRaisesRegex(RuntimeError, "scheduling call changed"):
            historical_harness(changed)

    def test_original_rejection_change_survives_scheduling_projection(self):
        changed = copy.deepcopy(self.tree)
        diagnostic = next(n for n in ast.walk(changed) if isinstance(n, ast.Constant)
                          and n.value == "qualified 60f must be an ancestor of HEAD")
        diagnostic.value = "unrelated failure"
        self.assertNotEqual(ast_digest(historical_harness(changed)), FROZEN_HARNESS_AST_SHA256)

    def test_both_scheduling_suites_are_enrolled_without_dropping_source_audits(self):
        for name in ("increment-60f-equivalence-closure.yml", "increment-59i-inherited-source-qualification.yml"):
            source = (ROOT / ".github/workflows" / name).read_text()
            for command in ("test-increment-60f-source-budget.py", "test-increment-60f-source-scheduling.py",
                            "test-increment-60f-inherited-source-scope.py",
                            "check-increment-60f-equivalence-closure.py --source-only"):
                self.assertIn("python3 morphhdl/scripts/" + command, source)


if __name__ == "__main__":
    unittest.main(verbosity=2)
