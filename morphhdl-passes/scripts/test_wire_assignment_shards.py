#!/usr/bin/env python3
"""Scheduling/aggregation mutation tests. Synthetic records are NOT RTL proofs."""
from __future__ import annotations

import contextlib
import copy
import io
import shutil
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import aggregate_wire_assignment_equivalence as aggregate
import validate_wire_assignment_equivalence as gate
import test_wire_assignment_equivalence as historical


class ShardSchedulingTests(unittest.TestCase):
    def test_every_binding_has_exactly_one_owner_without_domain_sampling(self):
        bindings = gate.parameter_bindings({"WIDTH": tuple(range(1, 65)), "DEPTH": tuple(range(1, 9))})
        for count in (1, 2, 3, 7, 16, 32, 512):
            groups = [gate.select_proof_bindings(bindings, index, count) for index in range(count)]
            actual = [gate.binding_key(binding) for group in groups for binding in group]
            self.assertEqual(sorted(actual), sorted(map(gate.binding_key, bindings)))
            self.assertEqual(len(set(actual)), 512)
            self.assertTrue(all(groups))
        self.assertEqual(gate.select_proof_bindings(bindings), bindings)

    def test_invalid_or_empty_shards_are_rejected(self):
        for index, count in ((0, 0), (-1, 2), (2, 2), (0, 5), (True, 2), (0, True), (0, 1.5)):
            with self.subTest(index=index, count=count), self.assertRaises(gate.ValidationError):
                gate.select_proof_bindings([{}] * 4, index, count)
        with self.assertRaises(gate.ValidationError):
            gate.select_proof_bindings([])

    def test_actual_shared_driver_runs_only_owned_bindings_and_cannot_claim_full_success(self):
        fixture = historical.PendingProofTests('test_pending_argument_remains_valid_after_roadmap_closure')
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        shared = dict(fixture.shared, domains={"WIDTH": (1, 2, 3, 4), "DEPTH": (1,)})
        visited = []
        def proof(*args):
            visited.append((str(args[1]), dict(args[6])))
            return {"binding": dict(args[6]), "status": "PASS", "comparison_reachable": True}
        with patch.object(gate, 'strict_design_checks', return_value={'mock': 'PASS'}), \
             patch.object(gate, 'run_mutation_control', return_value={'status': 'EXPECTED_FAIL', 'mock': True}), \
             patch.object(gate, 'run_formal_binding', side_effect=proof), \
             contextlib.redirect_stdout(io.StringIO()):
            result = gate.run_shared_witness(fixture.root, shared, fixture.witness,
                fixture.root / 'partial', 2, ['WA-07'], 1, 2)
        self.assertEqual(len(visited), 5 * 2)
        self.assertEqual({binding['WIDTH'] for _, binding in visited}, {2, 4})
        for item in result['future_pass_slots']:
            self.assertEqual(item['status'], 'SHARD_PASS')
            self.assertFalse(item['complete_domain'])
            self.assertEqual(item['binding_count'], 2)
            self.assertEqual(item['required_binding_count'], 4)


class AggregationTests(unittest.TestCase):
    """Synthetic aggregation records; cone validation is mocked, not a proof."""
    def setUp(self):
        cone_patch = patch.object(aggregate.cones, 'validate_proof', return_value=None)
        self.cone_validator = cone_patch.start()
        self.addCleanup(cone_patch.stop)
        self.temp = tempfile.TemporaryDirectory(prefix='wa07a-shard-unit-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.shards = self.root / 'shards'
        self.output = self.root / 'aggregate'
        self.witness = self.root / 'reference.v'
        self.witness.write_text('// synthetic aggregation fixture, NOT proved RTL\n')
        self.identity = {'source_commit': 'a' * 40, 'manifest_sha256': 'b' * 64,
                         'signature_registry_sha256': 'c' * 64}
        inputs = [{'name': 'clk', 'width': 1}, {'name': 'reset', 'width': 1}, {'name': 'd', 'width': 1}]
        outputs = [{'name': 'q', 'width': 1}]
        self.shared = {'domains': {'WIDTH': (1, 2, 3, 4), 'DEPTH': (1,)},
                       'reference_top': 'SyntheticShared',
                       'inputs': inputs, 'outputs': outputs, 'clock': 'clk', 'reset': 'reset',
                       'common_capture': 'common-pre-pass/reference.v', 'simulations': []}
        self.slots = []
        for name in ('constant-only', 'ordered-pipeline'):
            candidate = self.root / (name + '.v')
            candidate.write_text('// synthetic candidate ' + name + '\n')
            self.slots.append({'pass_id': name, 'directory_name': name,
                               'activation_item': 'WA-07a', 'required': True, 'candidate': candidate})
        cases = []
        for index in range(2):
            source = self.root / f'generic-{index}.v'
            source.write_text('// synthetic generic fixture\n')
            cases.append({'id': f'generic-{index}', 'inputs': inputs, 'outputs': outputs,
                          'reference_top': 'SyntheticReference', 'candidate_top': 'SyntheticCandidate',
                          'clock': 'clk' if index else None, 'reset': 'reset' if index else None,
                          'domains': {'WIDTH': (1,)}, 'reference': source, 'candidate': source,
                          'engine': 'abc pdr', 'timeout_seconds': 120, 'simulations': []})
        self.manifest = {'shared': self.shared, 'cases': cases}
        for index in range(2):
            self.make_shard(index)

    def write(self, path, text):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)

    def tool_record(self, directory, case, binding, reference, candidate, mutation=False):
        self.write(directory / 'reference.v', reference.read_text())
        self.write(directory / 'candidate.v', candidate.read_text())
        prefix = 'Wa03Mutation' if mutation else 'Wa03'
        top = 'Wa03MutationMiter' if mutation else 'Wa03EquivalenceMiter'
        for stem in ('reference', 'candidate'):
            source_top = case.get(stem + '_top', case['reference_top'])
            prepared_top = prefix + stem.title() + 'Prepared'
            script = directory / f'prepare-{stem}.ys'
            self.write(script, aggregate.preparation_script(source_top, prepared_top, binding, stem))
            rtlil = directory / f'{stem}.il'
            self.write(rtlil, f'# synthetic RTLIL, NOT a proof: {stem} {binding} {prepared_top}\n')
            gate.write_json(directory / f'prepare-{stem}-evidence.json', {
                'schema_version': 1, 'source_top': source_top, 'prepared_top': prepared_top,
                'binding': dict(sorted(binding.items())),
                'source_sha256': gate.sha256_file(directory / f'{stem}.v'),
                'script_sha256': gate.sha256_file(script), 'rtlil_sha256': gate.sha256_file(rtlil)})
        self.write(directory / 'miter.v', gate.generated_miter(case['inputs'], case['outputs'], binding,
            prefix + 'ReferencePrepared', prefix + 'CandidatePrepared', top,
            case['clock'], case['reset'], mutation))
        if mutation:
            self.write(directory / 'proof.sby', gate.sby_configuration(top,
                'FAIL', 'bmc', 'smtbmc yices', 120, depth=3))
            self.write(directory / 'proof/status', 'FAIL 0 1\n')
            self.solver_inputs(directory, 'proof')
            self.write(directory / 'proof/engine_0/trace.vcd', '$comment synthetic unit record $end\n')
        else:
            self.write(directory / 'reachability.sby', gate.sby_configuration(top, 'PASS', 'cover',
                'smtbmc yices', 120, depth=4))
            self.write(directory / 'reachability/status', 'PASS 0 1\n')
            self.solver_inputs(directory, 'reachability')
            self.write(directory / 'reachability/engine_0/trace.vcd', '$comment synthetic unit record $end\n')
            gate.write_json(directory / 'reachability-evidence.json',
                {'status': 'PASS', 'comparison_region_reached': True, 'cover_trace_count': 1})

    def solver_inputs(self, directory, stem):
        self.write(directory / stem / 'config.sby', (directory / f'{stem}.sby').read_text())
        for name in ('reference.il', 'candidate.il', 'miter.v'):
            self.write(directory / stem / 'src' / name, (directory / name).read_text())

    def mutation(self, directory, case, reference, candidate):
        binding = gate.parameter_bindings(case['domains'])[0]
        self.tool_record(directory / 'mutation-control', case, binding, reference, candidate, True)
        return {'binding': binding, 'status': 'EXPECTED_FAIL', 'counterexample_count': 1}

    def make_shard(self, index):
        folder = self.shards / f'shard-{index}'
        bindings = gate.parameter_bindings(self.shared['domains'])
        selected = gate.select_proof_bindings(bindings, index, 2)
        shard = {'index': index, 'count': 2, 'domain_binding_count': len(bindings),
                 'domain_sha256': gate.sha256_bytes(gate.canonical_json(bindings).encode())}
        for run in ('run-a', 'run-b'):
            base = folder / run
            shared_root = base / 'shared-witness'
            self.write(shared_root / self.shared['common_capture'], self.witness.read_text())
            items = []
            for slot in self.slots:
                directory = shared_root / 'future-pass-formal' / slot['directory_name']
                proofs = []
                for binding in selected:
                    proof = {'binding': binding, 'status': 'PASS', 'comparison_reachable': True}
                    location = directory / gate.binding_key(binding)
                    self.tool_record(location, self.shared, binding, self.witness, slot['candidate'])
                    gate.write_json(location / 'binding-evidence.json', proof)
                    proofs.append(proof)
                item = {'pass_id': slot['pass_id'], 'activation_item': slot['activation_item'],
                        'status': 'SHARD_PASS', 'formal_shard': shard, 'complete_domain': False,
                        'binding_count': len(selected), 'required_binding_count': len(bindings),
                        'common_reference_sha256': gate.sha256_file(self.witness),
                        'candidate_sha256': gate.sha256_file(slot['candidate']), 'proofs': proofs,
                        'mutation_control': self.mutation(directory, self.shared, self.witness, slot['candidate'])}
                gate.write_json(directory / 'pass-evidence.json', item)
                items.append(item)
            witness = {'formal_shard': shard, 'domain_audit': gate.audit_shared_domain(self.shared),
                       'strict': {'compile': 'PASS', 'lint': 'PASS', 'synthesis': 'PASS'}, 'simulations': [],
                       'future_pass_slots': items}
            gate.write_json(shared_root / 'witness-evidence.json', witness)
            generic = []
            for case in self.manifest['cases']:
                proofs = []
                for binding in gate.parameter_bindings(case['domains']):
                    self.tool_record(base / 'generic' / case['id'] / 'formal' / gate.binding_key(binding),
                                     case, binding, case['reference'], case['candidate'])
                    proofs.append({'binding': binding, 'status': 'PASS', 'comparison_reachable': True})
                item = {'id': case['id'], 'formal': {'complete_domain': True,
                        'binding_count': len(proofs), 'proofs': proofs},
                        'strict': {side: {'compile': 'PASS', 'lint': 'PASS', 'synthesis': 'PASS'}
                                   for side in ('reference', 'candidate')}, 'simulations': []}
                gate.write_json(base / 'generic' / case['id'] / 'case-evidence.json', item)
                generic.append(item)
            suite = {'status': 'SHARD_PASS', 'tool_versions': {'synthetic': 'unit-test-not-proof'},
                     'generic_cases': generic, 'shared_witness': witness}
            for location, key, case in (
                (base, 'mutation_control', self.manifest['cases'][0]),
                (base / 'sequential-control', 'sequential_mutation_control', self.manifest['cases'][1])):
                suite[key] = self.mutation(location, case, case['reference'], case['candidate'])
            gate.write_json(base / 'suite-evidence.json', suite)
        gate.compare_deterministic_runs(folder / 'run-a', folder / 'run-b', folder / 'determinism.json')
        gate.write_json(folder / 'gate-status.json', {'status': 'SHARD_PASS', **self.identity,
            'formal_shard': {'index': index, 'count': 2}, 'determinism_checked': True,
            'common_reference_sha256': gate.sha256_file(self.witness)})

    def run_gate(self):
        with contextlib.redirect_stdout(io.StringIO()):
            return aggregate.aggregate(self.root, self.shards, self.output, 2,
                self.manifest, self.slots, self.identity, self.witness)

    def summary_mutation(self, key, value):
        path = self.shards / 'shard-1/gate-status.json'
        data = gate.load_json(path); data[key] = value; gate.write_json(path, data)

    def binding_path(self):
        return self.shards / 'shard-0/run-a/shared-witness/future-pass-formal/constant-only/DEPTH-1__WIDTH-1'

    def test_exact_disjoint_coverage_of_both_runs_and_all_passes_is_accepted(self):
        result = self.run_gate()
        self.assertEqual(result['status'], 'PASS')
        self.assertEqual(result['equivalence_proof_count'], 16)
        self.assertTrue(result['exact_disjoint_coverage'])
        self.assertTrue(result['determinism_checked'])
        self.assertEqual(self.cone_validator.call_count, 24)
        self.assertFalse((self.binding_path() / 'proof/status').exists())

    def test_cone_validator_receives_every_output_bit_and_exact_guarded_miter(self):
        directory = self.root / 'synthetic-multibit-binding'
        case = dict(self.shared, outputs=[{'name': 'valid', 'width': 1},
                    {'name': 'payload', 'width': {'parameter': 'WIDTH'}, 'compare_when': ('valid',)}],
                    timeout_seconds=321)
        binding = {'DEPTH': 1, 'WIDTH': 3}
        self.tool_record(directory, case, binding, self.witness, self.slots[0]['candidate'])
        aggregate.check_tool_proof(directory, case, binding, gate.sha256_file(self.witness),
                                    gate.sha256_file(self.slots[0]['candidate']))
        self.cone_validator.assert_called_once_with(directory=directory,
            miter_top='Wa03EquivalenceMiter', scalar_miter_text=gate.generated_miter(
                case['inputs'], case['outputs'], binding, 'Wa03ReferencePrepared',
                'Wa03CandidatePrepared', 'Wa03EquivalenceMiter', 'clk', 'reset', False,
                split_output_bits=True), expected_property_count=4, timeout_seconds=321,
            expected_assumption_count=4)

    def test_missing_shard_cannot_publish_success(self):
        shutil.rmtree(self.shards / 'shard-1')
        with self.assertRaisesRegex(gate.ValidationError, 'missing or extra'):
            self.run_gate()
        self.assertFalse((self.output / 'gate-status.json').exists())

    def test_duplicate_shard_index_is_rejected(self):
        self.summary_mutation('formal_shard', {'index': 0, 'count': 2})
        with self.assertRaisesRegex(gate.ValidationError, 'duplicate shard'):
            self.run_gate()

    def test_stale_commit_registry_manifest_or_reference_is_rejected(self):
        for key in ('source_commit', 'signature_registry_sha256', 'manifest_sha256', 'common_reference_sha256'):
            path = self.shards / 'shard-1/gate-status.json'; before = path.read_bytes()
            with self.subTest(key=key):
                self.summary_mutation(key, '0' * len(self.identity.get(key, '0' * 64)))
                with self.assertRaises(gate.ValidationError): self.run_gate()
            path.write_bytes(before)

    def test_partial_shard_masquerading_as_full_pass_is_rejected(self):
        self.summary_mutation('status', 'PASS')
        with self.assertRaisesRegex(gate.ValidationError, 'incomplete shard'):
            self.run_gate()

    def test_omitted_repeated_run_is_rejected(self):
        shutil.rmtree(self.shards / 'shard-1/run-b')
        with self.assertRaises(gate.ValidationError): self.run_gate()

    def test_solver_failure_cannot_be_hidden_by_pass_summary(self):
        self.cone_validator.side_effect = aggregate.cones.ConeProofError('synthetic failed cone')
        with self.assertRaisesRegex(gate.ValidationError, 'output-cone proof validation failed'):
            self.run_gate()
        self.assertFalse((self.output / 'gate-status.json').exists())

    def test_mutation_solver_pass_cannot_be_hidden_by_expected_fail_summary(self):
        self.write(self.binding_path().parent / 'mutation-control/proof/status', 'PASS 0 1\n')
        with self.assertRaisesRegex(gate.ValidationError, 'expected FAIL, observed PASS'):
            self.run_gate()

    def test_missing_cover_trace_is_rejected_despite_pass_status(self):
        (self.binding_path() / 'reachability/engine_0/trace.vcd').unlink()
        with self.assertRaisesRegex(gate.ValidationError, 'no retained trace'):
            self.run_gate()

    def test_unsafe_clock_configuration_is_rejected(self):
        path = self.binding_path() / 'reachability.sby'
        path.write_text(path.read_text().replace('multiclock on', 'multiclock off'))
        with self.assertRaisesRegex(gate.ValidationError, 'reachability configuration changed'):
            self.run_gate()

    def test_changed_miter_assumptions_or_candidate_are_rejected(self):
        for filename in ('miter.v', 'candidate.v'):
            path = self.binding_path() / filename; original = path.read_bytes()
            with self.subTest(filename=filename):
                path.write_text('// changed fixture\n')
                with self.assertRaises(gate.ValidationError): self.run_gate()
            path.write_bytes(original)

    def test_prepared_rtlil_cannot_change_with_correct_verilog_and_pass_status(self):
        for stem in ('reference', 'candidate'):
            path = self.binding_path() / f'{stem}.il'
            original = path.read_bytes()
            with self.subTest(stem=stem):
                path.write_text('# a different netlist despite unchanged source Verilog\n')
                with self.assertRaisesRegex(gate.ValidationError, 'RTLIL provenance'):
                    self.run_gate()
            path.write_bytes(original)

    def test_wrong_parameter_preparation_is_rejected_with_matching_fingerprints(self):
        directory = self.binding_path()
        script = directory / 'prepare-reference.ys'
        script.write_text(script.read_text().replace('chparam -set WIDTH 1 ', 'chparam -set WIDTH 2 '))
        ledger = directory / 'prepare-reference-evidence.json'
        evidence = gate.load_json(ledger)
        evidence['script_sha256'] = gate.sha256_file(script)
        gate.write_json(ledger, evidence)
        with self.assertRaisesRegex(gate.ValidationError, 'preparation or parameter binding'):
            self.run_gate()

    def test_missing_generation_fingerprint_is_rejected(self):
        (self.binding_path() / 'prepare-candidate-evidence.json').unlink()
        with self.assertRaises(gate.ValidationError):
            self.run_gate()

    def test_stale_solver_inputs_are_rejected_for_proof_and_reachability(self):
        for directory, work in ((self.binding_path().parent / 'mutation-control', 'proof'),
                                (self.binding_path(), 'reachability')):
            for name in ('reference.il', 'candidate.il', 'miter.v'):
                path = directory / work / 'src' / name
                original = path.read_bytes()
                with self.subTest(work=work, name=name):
                    path.write_text('// a retained solver run for different inputs\n')
                    with self.assertRaisesRegex(gate.ValidationError, 'solver input'):
                        self.run_gate()
                path.write_bytes(original)

    def test_stale_solver_configuration_is_rejected(self):
        for directory, work in ((self.binding_path().parent / 'mutation-control', 'proof'),
                                (self.binding_path(), 'reachability')):
            path = directory / work / 'config.sby'
            original = path.read_bytes()
            with self.subTest(work=work):
                path.write_text(path.read_text().replace('multiclock on', 'multiclock off'))
                with self.assertRaisesRegex(gate.ValidationError, 'retained solver configuration'):
                    self.run_gate()
            path.write_bytes(original)

    def test_symlinked_repeated_run_cannot_count_as_independent_evidence(self):
        repeated = self.shards / 'shard-0/run-b'
        shutil.rmtree(repeated)
        repeated.symlink_to('run-a', target_is_directory=True)
        with self.assertRaisesRegex(gate.ValidationError, 'independent repeated runs'):
            self.run_gate()

    def test_missing_functional_mutation_counterexample_is_rejected(self):
        path = self.binding_path().parent / 'mutation-control/proof/engine_0/trace.vcd'
        path.unlink()
        with self.assertRaisesRegex(gate.ValidationError, 'mutation has no retained counterexample'):
            self.run_gate()

    def test_stale_pass_file_is_removed_before_a_failing_rerun(self):
        self.run_gate()
        self.summary_mutation('status', 'FAIL')
        with self.assertRaises(gate.ValidationError): self.run_gate()
        self.assertFalse((self.output / 'gate-status.json').exists())

    def test_cli_preflight_failure_removes_previous_pass(self):
        self.run_gate()
        argv = ['aggregate', '--repo-root', str(self.root), '--shards', str(self.shards),
                '--output', str(self.output)]
        with patch('sys.argv', argv), contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(aggregate.main(), 1)
        self.assertFalse((self.output / 'gate-status.json').exists())

    def test_artifact_modification_outside_summary_is_detected_by_determinism(self):
        path = self.shards / 'shard-0/run-b/new-evidence.json'
        self.write(path, '{"synthetic_mutation":true}\n')
        with self.assertRaisesRegex(gate.ValidationError, 'not deterministic'):
            self.run_gate()

    def test_missing_duplicate_reordered_and_unreachable_binding_records_are_rejected(self):
        expected = [{'WIDTH': 1}, {'WIDTH': 2}]
        good = [{'binding': binding, 'status': 'PASS', 'comparison_reachable': True} for binding in expected]
        variants = [good[:1], [good[0], good[0]], list(reversed(good)),
                    [good[0], dict(good[1], comparison_reachable=False)]]
        for records in variants:
            with self.subTest(records=records), self.assertRaises(gate.ValidationError):
                aggregate.check_binding_list(records, expected, 'synthetic records')


if __name__ == '__main__':
    unittest.main()
