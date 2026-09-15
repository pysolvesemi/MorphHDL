#!/usr/bin/env python3
"""Additive lane/when review controls; inherited audit controls remain intact."""
from __future__ import annotations
import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import re
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[2]
def load(name: str, path: str):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module

scope = load("lane_scope_controls", "morphhdl/scripts/check-lane-when-source-scope.py")
catalog = load("lane_catalog_controls", "morphhdl/scripts/check-increment-60f-artifacts.py")
rtl = load("lane_rtl_controls", "morphhdl/scripts/check-lane-when-inlining.py")
PROFILE = "60f-with-wa07a-and-59d-and-59e-and-59f-and-59c-and-59g-and-59h-and-wa07b-and-60g"

class LaneWhenReviewTests(unittest.TestCase):
    def setUp(self):
        self.actual = {p.decode() for p in subprocess.check_output([
            "git", "diff", "--no-renames", "--name-only", "-z", scope.PREDECESSOR,
            scope.IMPLEMENTATION], cwd=ROOT).split(b"\0") if p}
        self.valid = {"schema_version": 1, "predecessor": scope.PREDECESSOR,
                      "implementation_source": scope.IMPLEMENTATION,
                      "implementation_paths": sorted(self.actual), "review_paths": sorted(scope.REVIEW_PATHS)}

    def test_immutable_introduction_and_exact_review_paths(self):
        scope.validate_inventory(self.valid, self.actual)
        self.assertEqual(len(self.actual), 18)

    def test_changed_anchors_and_schema_are_rejected(self):
        for field, val in (("schema_version", 2), ("predecessor", "0"*40),
                           ("implementation_source", "0"*40)):
            with self.subTest(field=field):
                changed = copy.deepcopy(self.valid); changed[field] = val
                with self.assertRaises(RuntimeError): scope.validate_inventory(changed, self.actual)
        changed = copy.deepcopy(self.valid); changed["allow_unreviewed"] = True
        with self.assertRaises(RuntimeError): scope.validate_inventory(changed, self.actual)

    def test_missing_extra_duplicate_unsorted_and_noncanonical_paths_are_rejected(self):
        for field in ("implementation_paths", "review_paths"):
            for paths in ([], [None], self.valid[field][:-1], list(reversed(self.valid[field])),
                          self.valid[field]*2, sorted(self.valid[field] + ["core/src/main/Other.scala"]),
                          ["../Other.scala"], ["/Other.scala"], ["./Other.scala"], [""]):
                with self.subTest(field=field, paths=paths):
                    changed = copy.deepcopy(self.valid); changed[field] = paths
                    with self.assertRaises(RuntimeError): scope.validate_inventory(changed, self.actual)

    def test_every_current_production_safety_marker_is_live(self):
        controls = 0
        for path, markers in scope.MARKERS.items():
            text = (ROOT / path).read_text()
            self.assertEqual(scope.safety_failures(path, text), [], path)
            for marker in markers:
                with self.subTest(path=path, marker=marker):
                    self.assertTrue(scope.safety_failures(path, text.replace(marker, "REMOVED_LANE_GUARD")))
                    controls += 1
        self.assertGreaterEqual(controls, 40)
        print("LANE_WHEN_SAFETY_MUTATIONS_PASS controls=" + str(controls))

    def test_generated_identifier_regex_and_text_rewriting_are_rejected(self):
        for path in scope.MARKERS:
            text = (ROOT / path).read_text().split("object ParameterizedStreamFifoNamedExpressionPassWitness", 1)[0].split("object ParameterizedStreamFifoExpressionPassWitness", 1)[0]
            for injection in ('val x = "_zz_laneDe"', 'val x = "when_example_l42"',
                              'val x = "DisplayControllerProgressiveTimingGenerator"',
                              'val x = Pattern.compile(name)', 'val x = parseVerilog(text)'):
                with self.subTest(path=path, injection=injection):
                    self.assertTrue(scope.safety_failures(path, text + "\n" + injection))

    def test_exact_additive_catalog_preserves_all_2012_inherited_tests(self):
        old = catalog.catalog_for_profile(PROFILE, True, True, True, True, True)
        new = catalog.catalog_for_profile(PROFILE, True, True, True, True, True, True)
        self.assertEqual(tuple(map(sum, zip(*old[0].values()))), (2012, 197))
        self.assertEqual(tuple(map(sum, zip(*new[0].values()))), (2029, 198))
        for project in old[0]:
            if project != "morphhdl": self.assertEqual(old[0][project], new[0][project])
            self.assertTrue(old[1][project] <= new[1][project])
            for name, count in old[2].get(project, {}).items():
                self.assertEqual(new[2][project][name], count)
        self.assertEqual(new[2]["morphhdl"]["morphhdl.LaneWhenInliningRegressionTests"], 17)
        self.assertEqual(new[1]["morphhdl"] - old[1]["morphhdl"], {"morphhdl.LaneWhenInliningRegressionTests"})

    def test_partial_source_enrollment_and_changed_added_identity_are_rejected(self):
        entries = {path: {"before_sha256": None} for path in catalog.LANE_WHEN_SUITE_SOURCES}
        self.assertTrue(catalog.lane_when_suite_flag(entries, (True,)*4))
        self.assertFalse(catalog.lane_when_suite_flag({}, (True,)*4))
        for path in entries:
            changed = copy.deepcopy(entries); del changed[path]
            with self.subTest(missing=path), self.assertRaises(RuntimeError):
                catalog.lane_when_suite_flag(changed, (True,)*4)
            changed = copy.deepcopy(entries); changed[path]["before_sha256"] = "0"*64
            with self.subTest(identity=path), self.assertRaises(RuntimeError):
                catalog.lane_when_suite_flag(changed, (True,)*4)

    def test_missing_any_predecessor_catalog_is_rejected(self):
        entries = {path: {"before_sha256": None} for path in catalog.LANE_WHEN_SUITE_SOURCES}
        for index in range(4):
            flags = [True]*4; flags[index] = False
            with self.subTest(index=index), self.assertRaises(RuntimeError):
                catalog.lane_when_suite_flag(entries, tuple(flags))
            with self.subTest(catalog_index=index), self.assertRaises(RuntimeError):
                catalog.catalog_for_profile(PROFILE, True, *flags, lane_when=True)

    def test_pass_workflow_routes_this_exact_branch_without_removing_old_routes(self):
        text = (ROOT / '.github/workflows/morphhdl-passes.yml').read_text()
        self.assertEqual(text.count("|| github.head_ref == 'agent/lane-when-expression-inlining'"), 7)
        self.assertEqual(text.count("startsWith(github.head_ref, 'agent/wa-')"), 7)
        self.assertIn("test-lane-when-source-scope.py", text)
        for predecessor in ("59d", "59e", "59f", "59g", "59h"):
            # A route must not be broadened to another unfinished increment.
            self.assertNotIn("github.head_ref == 'agent/increment-" + predecessor, text)

    def qualification_job(self, name):
        text = (ROOT / '.github/workflows/lane-when-expression-diagnostic.yml').read_text()
        match = re.search(r'(?ms)^  ' + re.escape(name) + r':\n(.*?)(?=^  [A-Za-z][\w-]*:\n|\Z)', text)
        self.assertIsNotNone(match, 'missing mandatory lane job: ' + name)
        return match.group(1)

    def test_compatibility_and_full_builds_have_no_branch_skip_route(self):
        for name in ('compatibility', 'sbt-full', 'mill-full'):
            with self.subTest(job=name):
                job = self.qualification_job(name)
                self.assertNotRegex(job, r'(?m)^    (?:if|needs):')
                self.assertNotIn('continue-on-error', job)
                self.assertNotRegex(job.replace('if: always()', ''), r'(?m)^\s+if:')
                self.assertIn("- 2.12.18", job)
                self.assertIn("- 2.13.12", job)
                self.assertIn('fail-fast: false', job)

    def test_new_gate_jobs_authenticate_exact_head_and_retain_evidence(self):
        for name in ('compatibility', 'sbt-full', 'mill-full'):
            with self.subTest(job=name):
                job = self.qualification_job(name)
                self.assertIn('ref: ${{ github.event.pull_request.head.sha || github.sha }}', job)
                self.assertIn('fetch-depth: 0', job)
                self.assertIn('submodules: recursive', job)
                self.assertIn('check-increment-62-wa08-source-overlay.py', job)
                self.assertIn('check-lane-when-source-scope.py', job)
                self.assertIn('source-identity.txt', job)
                self.assertIn('set -euo pipefail', job)
                self.assertIn('actions/upload-artifact@v4', job)
                self.assertIn('if-no-files-found: error', job)

    def test_real_binary_source_and_report_abi_commands_are_mandatory(self):
        job = self.qualification_job('compatibility')
        for command in ('bash morphhdl/scripts/check-binary-compatibility.sh',
                        'python3 morphhdl/scripts/check-jvm-binary-compatibility.py',
                        'bash morphhdl/scripts/check-concrete-spinalverilog-parity.sh',
                        'bash morphhdl/scripts/check-external-spinal-boundary.sh'):
            self.assertIn(command, job)
        self.assertIn('BASE_COMMIT: 61d1fe0dcac0b52856620944a2d7426fd1390a48', job)
        self.assertIn('--baseline "$baseline_jar"', job)
        self.assertIn('--current "$current_jar"', job)
        self.assertNotIn('--skip-binary-linkage', job)
        self.assertNotIn('|| true', job.split('Prove source linkage', 1)[1])

    def test_full_sbt_and_mill_retain_native_simulations_and_packaging(self):
        sbt = self.qualification_job('sbt-full')
        mill = self.qualification_job('mill-full')
        self.assertIn('compile Test/compile', sbt)
        for gate in ('check-increment-58-retirement.py', 'check-production-retirement.py',
                     'check-typed-layering-ir.py'):
            self.assertIn(gate, sbt)
        self.assertEqual(sbt.count('--require-jar'), 3)
        for name in ('spinal.lib.CounterTester', 'spinal.lib.SpinalSimStreamFifoTester',
                     'spinal.lib.SpinalSimStreamFifoCCTester',
                     'spinal.lib.SpinalSimStreamWidthAdapterTester'):
            self.assertIn(name, sbt)
            self.assertIn(name, mill)
        self.assertIn('mill-dist/1.1.0/', mill)
        self.assertIn('timeout-minutes: 360', sbt)
        self.assertIn('timeout-minutes: 360', mill)

    def test_final_lane_gate_requires_each_real_job_success(self):
        job = self.qualification_job('qualification')
        self.assertIn('if: always()', job)
        for name in ('qualify', 'cross-scala', 'compatibility', 'sbt-full', 'mill-full'):
            self.assertIn('- ' + name, job)
        script = textwrap.dedent(job.split('run: |\n', 1)[1])
        names = ('QUALIFY', 'CROSS_SCALA', 'COMPATIBILITY', 'SBT_FULL', 'MILL_FULL')
        good = {name: 'success' for name in names}
        def execute(values):
            return subprocess.run(['bash', '-c', script], env=values, text=True,
                                  stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10)
        self.assertEqual(execute(good).returncode, 0)
        controls = 0
        for name in names:
            for status in ('skipped', 'cancelled', 'failure', 'pending', ''):
                with self.subTest(job=name, result=status):
                    values = dict(good); values[name] = status
                    result = execute(values)
                    self.assertNotEqual(result.returncode, 0)
                    self.assertNotIn('LANE_COMPLETE_QUALIFICATION_PASS', result.stdout)
                    controls += 1
            values = dict(good); del values[name]
            self.assertNotEqual(execute(values).returncode, 0)
            controls += 1
        print('LANE_COMPLETE_GATE_REJECTIONS_PASS controls=' + str(controls))

    def test_preflight_failure_removes_stale_success_receipt(self):
        with tempfile.TemporaryDirectory(prefix="lane-stale-receipt-") as name:
            root = Path(name); output = root/'output'; output.mkdir()
            summary = output/'summary.json'; summary.write_text('{"status":"passed"}\n')
            result = subprocess.run([sys.executable, str(ROOT/'morphhdl/scripts/check-lane-when-inlining.py'),
                '--before', str(root/'missing-before'), '--after', str(root/'missing-after'),
                '--output', str(output)], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('incomplete or unexpected RTL inventory', result.stderr)
            self.assertFalse(summary.exists(), 'failed preflight retained a stale success')

    def test_formal_harness_lowers_both_designs_before_constructing_miter(self):
        script = rtl.comparison_script(Path('before.v'), Path('after.v'), 'Example', 1)
        before_miter = script.split('miter -equiv', 1)[0]
        self.assertEqual(before_miter.splitlines().count('proc'), 2)
        self.assertEqual(before_miter.count('design -stash'), 2)
        self.assertIn('design -reset', before_miter)
        self.assertIn('sat -verify -prove-asserts', script)
        mutant = rtl.comparison_script(Path('before.v'), Path('after.v'), 'Example', 1, Path('witness.json'))
        self.assertIn('sat -dump_json "witness.json" -prove-asserts', mutant)

if __name__ == '__main__':
    unittest.main(verbosity=2)
