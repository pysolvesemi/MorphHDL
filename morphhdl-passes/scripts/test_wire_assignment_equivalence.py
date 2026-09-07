#!/usr/bin/env python3
"""Proof scheduling/activation tests. Mock proofs are not RTL proof evidence."""

from __future__ import annotations

import contextlib
import copy
import importlib.util
import io
import re
import shlex
import subprocess
import tempfile
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from unittest.mock import patch

import validate_wire_assignment_equivalence as gate
import prove_wire_assignment_cones as cones


class ScalarOutputPropertyTests(unittest.TestCase):
    def test_every_bit_has_the_original_guard_clock_and_reset(self):
        for width in (1, 3, 64):
            outputs = ({"name": "valid", "width": 1},
                       {"name": "payload", "width": {"parameter": "WIDTH"}, "compare_when": ("valid",)},
                       {"name": "count", "width": 4})
            args = (({"name": "clk", "width": 1}, {"name": "reset", "width": 1}),
                    outputs, {"WIDTH": width}, "Ref", "Candidate", "Miter", "clk", "reset", False)
            whole = gate.generated_miter(*args)
            scalar = gate.generated_miter(*args, split_output_bits=True)
            self.assertEqual(whole.count("assert("), 3)
            self.assertEqual(scalar.count("assert("), width + 5)
            # Clock/reset/assumption logic and the comparison region are exact;
            # each output's complete bit group shares its original guard.
            marker = "      cover(!reset);\n"
            self.assertEqual(whole.split(marker)[0], scalar.split(marker)[0])
            guard = "      if (reference_valid && candidate_valid) begin\n"
            expected_group = guard
            for bit in range(width):
                selected = f"[{bit}]" if width > 1 else ""
                expected = f"assert(reference_payload{selected} == candidate_payload{selected});"
                self.assertEqual(scalar.count(expected), 1)
                expected_group += "        " + expected + "\n"
            self.assertEqual(scalar.count(expected_group + "      end"), 1)
            observed = re.findall(r"assert\(reference_count\[([0-9]+)\] == candidate_count\[\1\]\);", scalar)
            self.assertEqual(observed, ["0", "1", "2", "3"])

    def test_mutation_of_vector_output_changes_only_its_low_bit(self):
        scalar = gate.generated_miter((), ({"name": "value", "width": 3},), {},
            "Ref", "Candidate", "Miter", None, None, True, split_output_bits=True)
        self.assertEqual(scalar.count("^ 1'b1"), 1)
        self.assertIn("assert(reference_value[0] == (candidate_value[0] ^ 1'b1));", scalar)
        for bit in (1, 2):
            self.assertIn(f"assert(reference_value[{bit}] == candidate_value[{bit}]);", scalar)


class OrderedWorkerTests(unittest.TestCase):
    def test_serial_and_parallel_results_preserve_input_order(self):
        released = threading.Event()
        finished = []
        lock = threading.Lock()

        def task(item):
            if item == 0:
                if not released.wait(10):
                    raise AssertionError("second worker did not run")
            with lock:
                finished.append(item)
            if item == 1:
                released.set()
            return item * 3

        self.assertEqual(gate.run_bounded_ordered([0, 1], 2, task), [0, 3])
        self.assertEqual(finished, [1, 0])
        self.assertEqual(gate.run_bounded_ordered([2, 0, 1], 1, lambda x: x * 3), [6, 0, 3])
        self.assertEqual(gate.run_bounded_ordered([], 4, lambda x: x), [])

    def test_worker_count_is_bounded_and_every_task_runs_once(self):
        lock = threading.Lock()
        barrier = threading.Barrier(4, timeout=10)
        state = {"active": 0, "peak": 0}
        visited = []

        def task(item):
            with lock:
                state["active"] += 1
                state["peak"] = max(state["peak"], state["active"])
                visited.append(item)
            try:
                barrier.wait()
                return item
            finally:
                with lock:
                    state["active"] -= 1

        self.assertEqual(gate.run_bounded_ordered(list(range(12)), 4, task), list(range(12)))
        self.assertEqual(sorted(visited), list(range(12)))
        self.assertEqual(state, {"active": 0, "peak": 4})

    def test_worker_failure_propagates_in_serial_and_parallel(self):
        def task(item):
            if item == 2:
                raise gate.ValidationError("injected proof failure")
            return item

        for jobs in (1, 4):
            with self.subTest(jobs=jobs), self.assertRaisesRegex(gate.ValidationError, "injected"):
                gate.run_bounded_ordered(list(range(6)), jobs, task)

    def assert_later_failure_stops_submission_and_drains(self, scheduler):
        first_started = threading.Event()
        release_first = threading.Event()
        first_drained = threading.Event()
        scheduler_observed_failure = threading.Event()
        scheduler_checkpoint = threading.Event()
        scheduler_finished = threading.Event()
        submitted = []
        failures = []

        class ObservedExecutor(ThreadPoolExecutor):
            def submit(self, fn, item):
                submitted.append(item)
                if len(submitted) > 2:
                    scheduler_checkpoint.set()
                future = super().submit(fn, item)
                if item == 1:
                    original_result = future.result
                    original_exception = future.exception

                    def observe(method, *args, **kwargs):
                        if future.done():
                            scheduler_observed_failure.set()
                            scheduler_checkpoint.set()
                        return method(*args, **kwargs)

                    future.result = lambda *args, **kwargs: observe(original_result, *args, **kwargs)
                    future.exception = lambda *args, **kwargs: observe(original_exception, *args, **kwargs)
                return future

        def task(item):
            if item == 0:
                first_started.set()
                try:
                    if not release_first.wait(10):
                        raise AssertionError("test did not release first worker")
                finally:
                    first_drained.set()
            elif item == 1:
                if not first_started.wait(10):
                    raise AssertionError("first worker did not start")
                raise gate.ValidationError("later binding failed")
            return item

        def run():
            try:
                scheduler(list(range(8)), 2, task)
            except BaseException as error:
                failures.append(error)
            finally:
                scheduler_finished.set()

        with patch.object(gate, "ThreadPoolExecutor", ObservedExecutor):
            runner = threading.Thread(target=run)
            runner.start()
            try:
                self.assertTrue(scheduler_checkpoint.wait(10), "scheduler never checked failure or submitted work")
                self.assertTrue(
                    scheduler_observed_failure.is_set(),
                    "failure must be observed before waiting for the first binding or submitting more work",
                )
                self.assertEqual(submitted, [0, 1])
                self.assertFalse(scheduler_finished.is_set(), "running worker must drain before return")
            finally:
                release_first.set()
                runner.join(10)
            self.assertFalse(runner.is_alive(), "scheduler did not drain and terminate")
        self.assertTrue(first_drained.is_set())
        self.assertEqual(submitted, [0, 1])
        self.assertEqual(len(failures), 1)
        self.assertIsInstance(failures[0], gate.ValidationError)
        self.assertEqual(str(failures[0]), "later binding failed")

    def test_completed_failure_stops_submission_without_waiting_for_earlier_binding(self):
        self.assert_later_failure_stops_submission_and_drains(gate.run_bounded_ordered)

    def test_scheduler_regression_rejects_old_eager_ordered_map(self):
        def old_scheduler(items, jobs, task):
            with gate.ThreadPoolExecutor(max_workers=jobs) as executor:
                return list(executor.map(task, items))

        with self.assertRaisesRegex(AssertionError, "failure must be observed"):
            self.assert_later_failure_stops_submission_and_drains(old_scheduler)

    def test_invalid_worker_limits_are_rejected_even_with_no_tasks(self):
        for jobs in (0, -1, True, 1.5):
            with self.subTest(jobs=jobs), self.assertRaises(gate.ValidationError):
                gate.run_bounded_ordered([], jobs, lambda x: x)
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(gate.main(["--formal-jobs", "0", "--self-test"]), 1)


class PendingProofTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="wa07-proof-tests-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.roadmap = self.root / "morphhdl-passes" / "morphhdl-ir-wire-assignment-passes-todo.md"
        self.roadmap.parent.mkdir()
        self.roadmap.write_text(
            "- [x] **WA-04 — unnamed**\n"
            "- [x] **WA-05 — named**\n"
            "- [x] **WA-06 — combined**\n"
            "- [ ] **WA-07 — expressions**\n"
            "- [ ] **WA-08 — production**\n",
            encoding="utf-8",
        )
        self.completion = gate.roadmap_completion(self.roadmap)
        self.witness = self.root / "parameterized_stream_fifo.v"
        self.witness.write_text("// synthetic scheduler fixture, not RTL proof\n", encoding="utf-8")
        pass_ids = (
            ("WA-04", "wire-alias-unnamed"),
            ("WA-05", "wire-alias-named"),
            ("WA-06", "wire-alias-unnamed+wire-alias-named"),
            ("WA-07", "wire-expression-unnamed"),
            ("WA-07", "wire-alias-unnamed+wire-alias-named+wire-expression-unnamed"),
        )
        slots = []
        for index, (activation, pass_id) in enumerate(pass_ids):
            candidate = self.root / f"candidate-{index}.v"
            candidate.write_text(f"// synthetic candidate {index}\n", encoding="utf-8")
            slots.append({"activation_item": activation, "pass_id": pass_id, "candidate": str(candidate)})
        self.shared = {
            "domains": {"WIDTH": tuple(range(1, 65)), "DEPTH": tuple(range(1, 9))},
            "future_outputs": tuple(slots),
            "generated_file_name": self.witness.name,
            "common_capture": "common-pre-pass/reference.v",
            "reference_top": "SyntheticFixture",
            "testbench": self.root / "unused-testbench.v",
            "testbench_top": "SyntheticTestbench",
            "inputs": (), "outputs": (), "simulations": (),
            "clock": "clk", "reset": "reset",
        }

    def test_preflight_failure_removes_stale_pass_without_discarding_failure_logs(self):
        output = self.root / "proof-output"
        output.mkdir()
        status = output / "gate-status.json"
        gate.write_json(status, {"status": "PASS"})
        retained_log = output / "prior-proof.log"
        retained_log.write_text("retained diagnostic\n")
        with contextlib.redirect_stderr(io.StringIO()) as errors:
            result = gate.main([
                "--repo-root", str(self.root),
                "--shared-witness", str(self.witness),
                "--manifest", str(self.root / "missing-manifest.json"),
                "--output", str(output),
            ])
        self.assertEqual(result, 1)
        self.assertIn("unable to load JSON", errors.getvalue())
        self.assertFalse(status.exists())
        self.assertEqual(retained_log.read_text(), "retained diagnostic\n")

    def test_explicit_pending_requires_both_new_slots_and_keeps_completed_slots(self):
        before = self.roadmap.read_bytes()
        plan = gate.plan_shared_slots(self.shared, self.completion, ["WA-07"])
        self.assertEqual(len(plan), 5)
        self.assertTrue(all(slot["required"] for slot in plan))
        self.assertEqual([slot["roadmap_completed"] for slot in plan], [True, True, True, False, False])
        self.assertEqual(self.roadmap.read_bytes(), before)
        self.assertFalse(self.completion["WA-07"])

    def test_lettered_successor_keeps_all_historical_legs_and_requires_both_new_legs(self):
        text = self.roadmap.read_text().replace("- [ ] **WA-07 —", "- [x] **WA-07 —")
        text = text.replace("- [ ] **WA-08", "- [ ] **WA-07a — constants**\n- [ ] **WA-08")
        self.roadmap.write_text(text)
        completion = gate.roadmap_completion(self.roadmap)
        self.assertTrue(completion["WA-07"])
        self.assertFalse(completion["WA-07a"])
        extra = []
        for index, pass_id in enumerate(("constant-operand-simplification", "wire-alias-unnamed+wire-alias-named+wire-expression-unnamed+constant-operand-simplification")):
            candidate = self.root / f"constant-{index}.v"
            candidate.write_text("// synthetic scheduler input, NOT proof evidence\n")
            extra.append({"activation_item": "WA-07a", "pass_id": pass_id, "candidate": str(candidate)})
        shared = dict(self.shared, future_outputs=self.shared["future_outputs"] + tuple(extra))
        plan = gate.plan_shared_slots(shared, completion, ["WA-07a"])
        self.assertEqual(len(plan), 7)
        self.assertTrue(all(slot["required"] for slot in plan))
        self.assertEqual([slot["roadmap_completed"] for slot in plan], [True] * 5 + [False] * 2)
        self.assertEqual(len(gate.parameter_bindings(shared["domains"])), 512)
        self.assertEqual(self.roadmap.read_text(), text)
        for slot in extra:
            candidate = Path(slot["candidate"])
            data = candidate.read_bytes()
            candidate.unlink()
            with self.assertRaisesRegex(gate.ValidationError, "published no candidate"):
                gate.plan_shared_slots(shared, completion, ["WA-07a"])
            candidate.write_bytes(data)
        with self.assertRaises(gate.ValidationError):
            gate.plan_shared_slots(shared, completion, ["WA-07a", "WA-07a"])
        with self.assertRaisesRegex(gate.ValidationError, "inactive future pass slot"):
            gate.plan_shared_slots(shared, completion)

    def test_default_does_not_silently_accept_an_unrequested_candidate(self):
        with self.assertRaisesRegex(gate.ValidationError, "inactive future pass slot"):
            gate.plan_shared_slots(self.shared, self.completion)
        for slot in self.shared["future_outputs"][3:]:
            Path(slot["candidate"]).unlink()
        plan = gate.plan_shared_slots(self.shared, self.completion)
        self.assertEqual([slot["required"] for slot in plan], [True, True, True, False, False])

    def test_unknown_duplicate_or_slotless_pending_items_fail(self):
        for pending in (["WA-99"], ["WA-07", "WA-07"], ["WA-08"]):
            with self.subTest(pending=pending), self.assertRaises(gate.ValidationError):
                gate.plan_shared_slots(self.shared, self.completion, pending)

    def test_missing_any_required_candidate_fails_preflight(self):
        for index in (0, 3, 4):
            candidate = Path(self.shared["future_outputs"][index]["candidate"])
            original = candidate.read_bytes()
            candidate.unlink()
            with self.subTest(index=index), self.assertRaisesRegex(gate.ValidationError, "published no candidate"):
                gate.plan_shared_slots(self.shared, self.completion, ["WA-07"])
            candidate.write_bytes(original)

    def test_proof_directory_collisions_are_rejected(self):
        shared = copy.deepcopy(self.shared)
        shared["future_outputs"][0]["pass_id"] = "collision+alias"
        shared["future_outputs"][1]["pass_id"] = "collision_alias"
        with self.assertRaisesRegex(gate.ValidationError, "not unique"):
            gate.plan_shared_slots(shared, self.completion, ["WA-07"])

    def test_pending_argument_remains_valid_after_roadmap_closure(self):
        closed = dict(self.completion, **{"WA-07": True})
        default_plan = gate.plan_shared_slots(self.shared, closed)
        explicit_plan = gate.plan_shared_slots(self.shared, closed, ["WA-07"])
        self.assertEqual(default_plan, explicit_plan)
        self.assertTrue(all(slot["required"] for slot in default_plan))

    def test_all_five_full_domains_and_artifacts_match_between_worker_counts(self):
        visited = []
        lock = threading.Lock()
        progress = io.StringIO()

        def simulated_proof(*args):
            binding, directory = args[6], args[11]
            directory.mkdir(parents=True)
            gate.write_json(directory / "binding.json", binding)
            with lock:
                visited.append((args[0], args[1], gate.binding_key(binding), directory))
            return {"binding": dict(sorted(binding.items())), "status": "PASS", "comparison_reachable": True}

        before = self.roadmap.read_bytes()
        with patch.object(gate, "strict_design_checks", return_value={"mock": "PASS"}), \
             patch.object(gate, "run_mutation_control", return_value={"status": "EXPECTED_FAIL", "mock": True}), \
             patch.object(gate, "run_formal_binding", side_effect=simulated_proof), \
             contextlib.redirect_stdout(progress):
            serial = gate.run_shared_witness(self.root, self.shared, self.witness, self.root / "serial", 1, ["WA-07"])
            parallel = gate.run_shared_witness(self.root, self.shared, self.witness, self.root / "parallel", 4, ["WA-07"])
        self.assertEqual(serial, parallel)
        expected = gate.parameter_bindings(self.shared["domains"])
        self.assertEqual(len(expected), 512)
        self.assertEqual(len(visited), 2 * 5 * 512)
        binding_progress = [line for line in progress.getvalue().splitlines()
                            if line.startswith("Proved ") and "DEPTH-" in line]
        self.assertEqual(len(binding_progress), len(visited))
        for slot in parallel["future_pass_slots"]:
            for binding in expected:
                line = f"Proved {slot['pass_id']}: {gate.binding_key(binding)} PASS (shard 1/1)"
                self.assertEqual(binding_progress.count(line), 2)
        self.assertEqual(len({entry[3] for entry in visited}), len(visited))
        for slot in parallel["future_pass_slots"]:
            self.assertEqual(slot["binding_count"], 512)
            self.assertTrue(slot["complete_domain"])
            self.assertEqual([proof["binding"] for proof in slot["proofs"]], list(expected))
            self.assertEqual(slot["common_reference_sha256"], parallel["capture_sha256"])
        comparison = gate.compare_deterministic_runs(self.root / "serial", self.root / "parallel", self.root / "comparison.json")
        self.assertTrue(comparison["runs_identical"])
        self.assertEqual(self.roadmap.read_bytes(), before)

    def test_failed_final_pending_slot_cannot_publish_success(self):
        shared = copy.deepcopy(self.shared)
        # This small synthetic scheduler test does not modify the real manifest.
        shared["domains"] = {"WIDTH": (1, 2), "DEPTH": (1,)}
        last_candidate = Path(shared["future_outputs"][-1]["candidate"])

        def simulated_proof(*args):
            if args[1] == last_candidate and args[6]["WIDTH"] == 2:
                raise gate.ValidationError("injected final-slot failure")
            return {"binding": dict(args[6]), "status": "PASS", "comparison_reachable": True}

        output = self.root / "failure"
        with patch.object(gate, "strict_design_checks", return_value={"mock": "PASS"}), \
             patch.object(gate, "run_mutation_control", return_value={"status": "EXPECTED_FAIL", "mock": True}), \
             patch.object(gate, "run_formal_binding", side_effect=simulated_proof), \
             contextlib.redirect_stdout(io.StringIO()), \
             self.assertRaisesRegex(gate.ValidationError, "final-slot failure"):
            gate.run_shared_witness(self.root, shared, self.witness, output, 4, ["WA-07"])
        self.assertFalse((output / "shared-witness" / "witness-evidence.json").exists())
        self.assertFalse((output / "gate-status.json").exists())

    def test_pass_status_without_reachability_cannot_publish_success(self):
        shared = copy.deepcopy(self.shared)
        shared["domains"] = {"WIDTH": (1,), "DEPTH": (1,)}
        for reachability in (None, False):
            output = self.root / ("missing-cover" if reachability is None else "false-cover")
            proof = {"binding": {"WIDTH": 1, "DEPTH": 1}, "status": "PASS"}
            if reachability is not None:
                proof["comparison_reachable"] = reachability
            with self.subTest(reachability=reachability), \
                 patch.object(gate, "strict_design_checks", return_value={"mock": "PASS"}), \
                 patch.object(gate, "run_mutation_control", return_value={"status": "EXPECTED_FAIL", "mock": True}), \
                 patch.object(gate, "run_formal_binding", return_value=proof), \
                 contextlib.redirect_stdout(io.StringIO()), \
                 self.assertRaisesRegex(gate.ValidationError, "incomplete or misordered"):
                gate.run_shared_witness(self.root, shared, self.witness, output, 1, ["WA-07"])
            self.assertFalse((output / "shared-witness/witness-evidence.json").exists())


class ClockModelContracts(unittest.TestCase):
    def test_cone_failure_identifies_candidate_binding_and_retained_evidence(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp) / "formal" / "DEPTH-8__WIDTH-64"
            candidate = Path(tmp) / "wire-assignment-four-pass.v"
            binding = {"WIDTH": 64, "DEPTH": 8}
            for message in ("TIMEOUT: property-0002-prove", "command log hash mismatch: compile"):
                cause = cones.ConeProofError(message)
                with self.subTest(message=message), patch.object(gate, "prepare_leg"), \
                     patch.object(gate, "prove_comparison_reachable"), \
                     patch.object(cones, "run_proof", side_effect=cause):
                    with self.assertRaises(gate.ValidationError) as raised:
                        gate.run_formal_binding(Path(tmp) / "reference.v", candidate, "Ref", "Candidate",
                            ({"name": "a", "width": 1},), ({"name": "q", "width": 1},),
                            binding, None, None, "abc pdr", 120, directory)
                    diagnostic = str(raised.exception)
                    for context in (candidate.name, gate.binding_key(binding), str(directory), message):
                        self.assertIn(context, diagnostic)
                    self.assertIs(raised.exception.__cause__, cause)

    def test_every_solver_mode_preserves_explicit_clock_edges(self):
        for mode in ("prove", "cover", "bmc"):
            config = gate.sby_configuration("Miter", "PASS", mode, "smtbmc yices", 120)
            self.assertIn("multiclock on", config)
            self.assertNotIn("multiclock off", config)

    def test_miter_cover_reaches_comparisons_after_reset_is_released(self):
        text = gate.generated_miter(
            ({"name": "clk", "width": 1}, {"name": "reset", "width": 1}),
            ({"name": "q", "width": 1},), {}, "Ref", "Candidate", "Miter",
            "clk", "reset", False)
        self.assertIn("always @($global_clock)", text)
        self.assertIn("assume(!clk)", text)
        self.assertIn("assume(clk)", text)
        enabled = text.split("if (wa03_reset_phase == 2'd2) begin", 1)[1]
        self.assertIn("cover(!reset);", enabled)
        self.assertIn("assert(reference_q == candidate_q);", enabled)

    def test_unreachable_comparison_stops_before_equivalence_proof(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            with patch.object(gate, "prepare_leg"), \
                 patch.object(gate, "prove_comparison_reachable", side_effect=gate.ValidationError("unreachable")), \
                 patch.object(gate, "run_command") as run:
                with self.assertRaisesRegex(gate.ValidationError, "unreachable"):
                    gate.run_formal_binding(directory/"a.v", directory/"b.v", "Ref", "Candidate",
                        ({"name": "a", "width": 1},), ({"name": "q", "width": 1},),
                        {}, None, None, "abc pdr", 120, directory)
                run.assert_not_called()

    def test_reachability_status_without_cover_trace_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            status = gate.ProofStatus("PASS", directory/"status", directory/"reachability")
            with patch.object(gate, "run_command"), patch.object(gate, "read_sby_status", return_value=status):
                with self.assertRaisesRegex(gate.ValidationError, "without a retained cover trace"):
                    gate.prove_comparison_reachable(directory, "Miter")



class NativeFixtureContractTests(unittest.TestCase):
    """Source regression only; fresh native generation remains mandatory."""
    @classmethod
    def setUpClass(cls):
        root = Path(__file__).resolve().parents[2]
        spec = importlib.util.spec_from_file_location(
            'wa07a_native_fixture_contract',
            root / 'morphhdl-passes/scripts/check-wa07a-constant-pass.py')
        cls.contract = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.contract)
        cls.fixture = (root / cls.contract.FIFO).read_text()

    def test_native_fixture_preserves_fixed_count_output_abi(self):
        self.assertEqual(self.contract.text_failures(self.contract.FIFO, self.fixture), [])

    def test_deferred_child_count_clone_mutation_is_rejected(self):
        for output in ('occupancy', 'availability'):
            original = f'io.{output} := fifo.io.{output}.resize(4)'
            mutant = self.fixture.replace(original, f'io.{output} := fifo.io.{output}.resized')
            self.assertNotEqual(mutant, self.fixture)
            self.assertTrue(self.contract.text_failures(self.contract.FIFO, mutant))

    def test_changed_count_output_or_resize_width_is_rejected(self):
        for output in ('occupancy', 'availability'):
            for original, replacement in (
                    (f'val {output} = out UInt (4 bits)', f'val {output} = out UInt (3 bits)'),
                    (f'io.{output} := fifo.io.{output}.resize(4)',
                     f'io.{output} := fifo.io.{output}.resize(3)')):
                mutant = self.fixture.replace(original, replacement)
                self.assertNotEqual(mutant, self.fixture)
                self.assertTrue(self.contract.text_failures(self.contract.FIFO, mutant))


class RunnerPathTests(unittest.TestCase):
    """Exercise actual shell argument construction without pretending to run SBT."""
    def capture_native_paths(self, text, root, cwd):
        # Execute the runner's actual variable assignments and SBT launch block.
        # The stub records arguments only; no mocked proof result is accepted.
        start = text.index('\nroot=') + 1
        stop = text.index('\ncmp -s ')
        if '\ntest -s "${reference}"' in text:
            stop = text.index('\ntest -s "${reference}"')
        script = ('set -euo pipefail\nrepo_root="$1"\n'
                  'sbt() { printf "%s\\0" "$@"; }\n' + text[start:stop])
        result = subprocess.run(['bash', '-c', script, 'runner-path-test', str(root)],
                                cwd=cwd, check=True, capture_output=True)
        calls = [shlex.split(arg.split('runMain ', 1)[1])
                 for arg in result.stdout.decode().split('\0') if 'runMain ' in arg]
        self.assertEqual(len(calls), 7)
        paths = []
        for call in calls:
            for value in (call[2], call[-1]):
                path = Path(value)
                self.assertTrue(path.is_absolute(), 'relative native artifact path: ' + value)
                self.assertEqual((cwd/path).resolve(), path.resolve())
                self.assertTrue(path.is_relative_to(root/'morphhdl-passes/build'))
                paths.append(path)
        return paths

    def test_native_artifact_paths_ignore_forked_subproject_working_directory(self):
        text = Path(__file__).with_name('run-wa07a-regression.sh').read_text()
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)/'repository'
            subproject = root/'morphhdl'
            subproject.mkdir(parents=True)
            self.assertEqual(self.capture_native_paths(text, root, root),
                             self.capture_native_paths(text, root, subproject))

    def test_relative_native_artifact_path_mutation_is_rejected(self):
        text = Path(__file__).with_name('run-wa07a-regression.sh').read_text()
        text = text.replace('root="${repo_root}/morphhdl-passes/build"',
                            'root=morphhdl-passes/build')
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)/'repository'
            subproject = root/'morphhdl'
            subproject.mkdir(parents=True)
            with self.assertRaisesRegex(AssertionError, 'relative native artifact path'):
                self.capture_native_paths(text, root, subproject)


if __name__ == "__main__":
    unittest.main()
