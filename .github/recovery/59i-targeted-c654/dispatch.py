#!/usr/bin/env python3
"""Dispatch only the four schema-12-affected Increment 59i workflows.

This wrapper reuses the reviewed intent-before-POST dispatcher.  It binds the
exact qualified seal, source/reconciliation evidence, workflow bytes and four
historical failures.  It cannot update refs, merge, or dispatch full CI.
"""
from __future__ import annotations

import hashlib
import importlib.util
import json
import os
from pathlib import Path
import sys


HERE = Path(__file__).resolve().parent
BASE_PATH = Path(os.getenv(
    'INCREMENT_59I_BASE_DISPATCH',
    str(HERE.parent / '59i-repair-failed-first' / 'dispatch.py'),
))
OLD_MANIFEST_SHA256 = 'e9b461d622f16cacd955103503044704ee7cc73b25e7a58d9a7e2f3ef1c21c40'
PLAN_SHA256 = '98344f5d3d586f1303c2933ee7e1a346838f117ab9436a545eb9a3bd170d97dd'

FEATURE = 'agent/increment-59i-combined-reduction-closure'
RECOVERY = 'recovery/increment-59i-history-20260914'
TARGET = '09880c538c4cf83022f4a1bb1dd16b43ea81a751'
PREDECESSOR = '9d6d738d32c71ff4359384ae9d87f715bf20ec55'
SOURCE_PARENT = '1eb63e57d41fb1f707da8ca7f176b1ecc609aebf'
SOURCE = '2797acc2fbeb0733c29d8c05d64857801de32ae2'
SOURCE_TREE = 'bad8f069942e54c0e4736076ebe5ff336dec26d6'
CANDIDATE = 'c654f43c24d86ca99c056dd4cb74b7a18d9f41e3'
CANDIDATE_TREE = '815a381426e9507363ab91bd3a258ab9668fb87a'
CONTROLLER = 'increment-59i-targeted-c654.yml'

WORKFLOW_IDS = {
    'increment-62-wa08-source-overlay.yml': 354645379,
    'increment-59i-combined-closure.yml': 352827927,
    'increment-59h-nested-owners.yml': 351996892,
    'increment-59f-callback-graphs.yml': 351397918,
}
WORKFLOW_HASHES = {
    'increment-62-wa08-source-overlay.yml':
        '250382848e77e10067f1ac5c9af7428e9a7727a1ec0053b6d5c7249e5fcbaa77',
    'increment-59i-combined-closure.yml':
        '9c0807f40d175ebe79ec81ba2bdf4a9894b985c76178fc01e664df0d07f4fac8',
    'increment-59h-nested-owners.yml':
        'c29048bc747a6604e87e34bbf8e69e9991cc03732f3a2f0dabbdf2206698afab',
    'increment-59f-callback-graphs.yml':
        '851c8e0027f2b5128f88149b7c01b73144697b4b04926963f62086d955c84ebc',
}
HISTORICAL = (
    ('increment-62-wa08-source-overlay.yml', 35570777721, 'failure'),
    ('increment-59i-combined-closure.yml', 35570802194, 'failure'),
    ('increment-59h-nested-owners.yml', 35570833758, 'failure'),
    ('increment-59f-callback-graphs.yml', 35570847879, 'failure'),
)

SOURCE_MEMBERS = {
    *(f'{index:02d}.log' for index in range(1, 26)),
    'audit-repair-paths.txt', 'identity.txt', 'live-refs.txt', 'results.tsv',
    'seal-paths.txt', 'started.txt', 'target-reconciliation-paths.txt',
}
SOURCE_FILE_DIGESTS = {
    'audit-repair-paths.txt': 'b1f2d30334dadeaeff8a04f5e4bc8b8054522f89f34cb9e9ccdea9ad41ede8c5',
    'identity.txt': 'bc2c586e9b5d0ce4f08adbed65ffc2b9470ee9c4870da1a4088d3a7ffbce55a9',
    'live-refs.txt': 'b34b05d425054d647f18147f3af7931c50c77eaaab6c91fc79dabff11817a760',
    'results.tsv': 'd48da4d251fc91ef1f5f3db847bf5eac0692864cc7ce175ee9b801fbf6a9a90a',
    'seal-paths.txt': 'c58d9b81b014b084e2b6eabbba343022f9f986abda8ea2b4ec1b092e5838cc1d',
    'started.txt': 'eff64b343dcb2b1dc113648e7089b9ce9f8a7f6c7808a03a2cffb4ad7302f606',
    'target-reconciliation-paths.txt': '2e37239599b93dd85d5f0584b9f81b8b1fc94377e03b2ba7e47e3de2726dca6b',
}
RECON_MEMBERS = {
    'audit.log', 'current-live-refs.txt', 'reconciliation.json', 'source.zip',
    'unit-tests.log',
}


def _load_base():
    spec = importlib.util.spec_from_file_location('increment_59i_reviewed_dispatch', BASE_PATH)
    if spec is None or spec.loader is None:
        raise RuntimeError('cannot load reviewed failed-first dispatcher')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


b = _load_base()
b.TARGET = TARGET
b.CANDIDATE = CANDIDATE
b.CANDIDATE_TREE = CANDIDATE_TREE
b.SOURCE = SOURCE
b.BASE = PREDECESSOR
b.CONTROLLER = CONTROLLER
b.MANIFEST_SHA256 = PLAN_SHA256
b.PRIOR = HISTORICAL
b.WORKFLOWS = tuple(name for name, _, _ in HISTORICAL)


def normalized_plan(value):
    return {
        'schema': 3,
        'candidate': CANDIDATE,
        'candidate_tree': CANDIDATE_TREE,
        'source': SOURCE,
        'source_tree': SOURCE_TREE,
        'source_parent': SOURCE_PARENT,
        'predecessor': PREDECESSOR,
        'target': TARGET,
        'workflows': [{
            'workflow': row['workflow'],
            'path': row['path'],
            'workflow_id': row['workflow_id'],
            'workflow_sha256': row['workflow_sha256'],
            'inputs': row['inputs'],
            'all_job_names': row['all_job_names'],
            'required_job_names': row['required_job_names'],
        } for row in value['workflows']],
        'historical': [list(row) for row in HISTORICAL],
        'full_ci': False,
    }


def load_manifest(path):
    raw = path.read_bytes()
    b.require(b.digest(raw) == OLD_MANIFEST_SHA256, 'base 16-workflow manifest changed')
    base = json.loads(raw)
    rows = {row['workflow']: row for row in base['workflows']}
    value = dict(base)
    value.update(candidate=CANDIDATE, source=SOURCE, target=TARGET, full_ci=False)
    value['workflows'] = []
    for name, _, _ in HISTORICAL:
        b.require(name in rows, 'affected workflow absent from reviewed base manifest')
        row = dict(rows[name])
        row['jobs'] = [dict(job) for job in row['jobs']]
        row['workflow_id'] = WORKFLOW_IDS[name]
        row['workflow_sha256'] = WORKFLOW_HASHES[name]
        if name == 'increment-59h-nested-owners.yml':
            source_name = 'Nested reduction owner source audits'
            row['all_job_names'] = [source_name, *row['all_job_names']]
            row['required_job_names'] = [source_name, *row['required_job_names']]
            row['jobs'] = [{'id': 'source', 'if': 'true', 'names': [source_name],
                            'timeout_minutes': 180}, *row['jobs']]
        value['workflows'].append(row)
    value['historical_runs'] = [dict(
        workflow=name, run_id=identity, conclusion=conclusion,
        head=PREDECESSOR, branch=FEATURE, run_attempt=1,
    ) for name, identity, conclusion in HISTORICAL]
    plan_raw = json.dumps(normalized_plan(value), sort_keys=True,
                          separators=(',', ':')).encode()
    b.require(b.digest(plan_raw) == PLAN_SHA256, 'normalized four-workflow plan changed')
    b.require(len(value['workflows']) == 4
              and sum(len(row['required_job_names']) for row in value['workflows']) == 12,
              'expected four workflows and twelve required jobs')
    return value


def clean_source(root, manifest):
    b.require(b.git(root, 'rev-parse', 'HEAD').decode().strip() == CANDIDATE,
              'checkout is not exact candidate')
    b.require(b.git(root, 'rev-parse', 'HEAD^{tree}').decode().strip() == CANDIDATE_TREE,
              'candidate tree differs')
    b.require(b.git(root, 'show', '-s', '--format=%P', CANDIDATE).decode().split() == [SOURCE],
              'seal is not the direct source child')
    b.require(b.git(root, 'show', '-s', '--format=%P', SOURCE).decode().split() == [SOURCE_PARENT],
              'source parent differs')
    b.require(b.git(root, 'rev-parse', SOURCE + '^{tree}').decode().strip() == SOURCE_TREE,
              'source tree differs')
    b.require(not b.git(root, 'status', '--porcelain', '--untracked-files=normal'),
              'candidate checkout is dirty')
    b.git(root, 'merge-base', '--is-ancestor', TARGET, CANDIDATE)
    b.git(root, 'merge-base', '--is-ancestor', PREDECESSOR, CANDIDATE)
    for row in manifest['workflows']:
        name = row['workflow']
        b.require(row['path'] == '.github/workflows/' + name, 'invalid workflow path')
        b.require(row['workflow_id'] == WORKFLOW_IDS[name], 'workflow ID differs')
        b.require(row['workflow_sha256'] == WORKFLOW_HASHES[name], 'workflow hash binding differs')
        expected = row['workflow_sha256']
        b.require(b.digest(b.git(root, 'show', CANDIDATE + ':' + row['path'])) == expected,
                  'candidate workflow differs: ' + name)
        b.require(b.digest((root / row['path']).read_bytes()) == expected,
                  'checkout workflow differs: ' + name)
        b.require(row['inputs'] == {}, 'unreviewed dispatch inputs')


def guard(api):
    pr = api.get('/pulls/177')
    feature = api.get('/git/ref/heads/' + FEATURE)
    target = api.get('/git/ref/heads/parameterized-verilog')
    b.require(pr['state'] == 'open' and not pr['merged'] and pr['draft']
        and pr['head']['sha'] == CANDIDATE and pr['head']['ref'] == FEATURE
        and pr['head']['repo']['full_name'] == b.REPOSITORY
        and pr['base']['ref'] == 'parameterized-verilog'
        and pr['base']['repo']['full_name'] == b.REPOSITORY
        and feature['object']['sha'] == CANDIDATE
        and target['object']['sha'] == TARGET,
        'PR177 draft state or live feature/target changed')


def _passed_run(api, run_id, head, workflow, job_name, required_steps):
    run = api.get('/actions/runs/' + str(run_id))
    b.require(run['status'] == 'completed' and run['conclusion'] == 'success'
        and run['head_sha'] == head and run['head_branch'] == RECOVERY
        and run['repository']['full_name'] == b.REPOSITORY and run['event'] == 'push'
        and run['path'] == '.github/workflows/' + workflow and run['run_attempt'] == 1,
        'evidence run identity differs: ' + workflow)
    jobs = api.collection('/actions/runs/%s/attempts/1/jobs' % run_id, 'jobs')
    b.require(len(jobs) == 1 and jobs[0]['name'] == job_name
        and jobs[0]['status'] == 'completed' and jobs[0]['conclusion'] == 'success',
        'evidence job did not pass: ' + job_name)
    passed = {step['name'] for step in jobs[0]['steps'] if step['conclusion'] == 'success'}
    b.require(required_steps <= passed, 'required evidence step missing: ' + job_name)
    return run


def _artifact(api, run, identity, name, sha256):
    artifact = api.get('/actions/artifacts/' + str(identity))
    b.require(artifact['name'] == name and artifact['workflow_run']['id'] == run['id']
        and artifact['workflow_run']['head_sha'] == run['head_sha']
        and artifact['digest'] == 'sha256:' + sha256 and not artifact['expired'],
        'evidence artifact identity differs: ' + name)
    raw = api.artifact_bytes(artifact)
    b.require(b.digest(raw) == sha256, 'downloaded artifact digest differs')
    return raw


def stage_run(api, gate):
    source = gate['source']
    recon = gate['reconciliation']
    source_run = _passed_run(api, source['run_id'], source['controller_sha'],
        'increment-59i-runtime-successor-source.yml', 'source', {
            'Authenticate exact sealed successor and unchanged live refs',
            'Run all 25 retained source gates',
        })
    _passed_run(api, recon['run_id'], recon['controller_sha'],
        'increment-59i-target-doc-reconciliation.yml', 'reconcile', {
            'Authenticate live refs and original successful source artifact',
            'Run rejection controls and exact current-target audit',
        })
    return source_run


def require_stage(api, gate, root, output):
    source_gate = gate['source']
    source_run = stage_run(api, gate)
    source_raw = _artifact(api, source_run, source_gate['artifact_id'],
        'increment-59i-runtime-successor-source-35814557330-1',
        source_gate['artifact_sha256'])
    source_files = b.zip_files(source_raw)
    b.require(set(source_files) == SOURCE_MEMBERS, 'source artifact member inventory differs')
    for name, expected in SOURCE_FILE_DIGESTS.items():
        b.require(b.digest(source_files[name]) == expected,
                  'source artifact member differs: ' + name)
    results = source_files['results.tsv'].decode().splitlines()
    b.require(len(results) == 25 and all(line.startswith('PASS\t0\t') for line in results),
              'source artifact does not retain 25 successful commands')
    b.require(all(source_files[f'{index:02d}.log'] for index in range(1, 26)),
              'source artifact contains an empty command log')

    recon_gate = gate['reconciliation']
    recon_run = api.get('/actions/runs/' + str(recon_gate['run_id']))
    recon_raw = _artifact(api, recon_run, recon_gate['artifact_id'],
        'increment-59i-schema12-target-reconciliation-35836365057-1',
        recon_gate['artifact_sha256'])
    recon_files = b.zip_files(recon_raw)
    b.require(set(recon_files) == RECON_MEMBERS, 'reconciliation member inventory differs')
    b.require(b.digest(recon_files['source.zip']) == source_gate['artifact_sha256'],
              'reconciliation embedded source artifact differs')
    receipt = json.loads(recon_files['reconciliation.json'])
    expected = {
        'current_target': TARGET,
        'current_target_tree': '6216cf799cc51c5a5f815d08f16e435c6b48ddc7',
        'feature_predecessor': PREDECESSOR,
        'feature_ref_updated': False,
        'full_ci_started': False,
        'prospective_merge_tree': CANDIDATE_TREE,
        'qualification_claimed': False,
        'schema': 2,
        'seal': CANDIDATE,
        'seal_tree': CANDIDATE_TREE,
        'source': SOURCE,
        'source_artifact': source_gate['artifact_id'],
        'source_artifact_sha256': source_gate['artifact_sha256'],
        'source_commands_passed': 25,
        'source_run': source_gate['run_id'],
        'source_tree': SOURCE_TREE,
        'target_blob': '5739586b9271dee22df84126f83a26cb07e0b1db',
        'target_change': ['morphhdl-passes/morphhdl-ir-wire-assignment-passes-todo.md'],
        'target_is_ancestor': True,
    }
    b.require(receipt == expected, 'reconciliation receipt differs')
    b.require(recon_files['audit.log'].decode().strip()
              == 'INCREMENT_59I_SCHEMA12_TARGET_RECONCILIATION_PASS',
              'reconciliation audit marker differs')
    verified = {
        'source_run': source_gate['run_id'],
        'source_artifact': source_gate['artifact_id'],
        'source_artifact_sha256': source_gate['artifact_sha256'],
        'source_commands_passed': 25,
        'reconciliation_run': recon_gate['run_id'],
        'reconciliation_artifact': recon_gate['artifact_id'],
        'reconciliation_artifact_sha256': recon_gate['artifact_sha256'],
        'candidate': CANDIDATE,
        'target': TARGET,
        'full_ci': False,
    }
    b.write(output / 'verified-evidence.json', verified)
    return verified


def prior_failures(api):
    for name, identity, conclusion in HISTORICAL:
        run = api.get('/actions/runs/' + str(identity))
        b.require(run['id'] == identity and run['head_sha'] == PREDECESSOR
            and run['head_branch'] == FEATURE and run['repository']['full_name'] == b.REPOSITORY
            and run['path'] == '.github/workflows/' + name
            and run['event'] == 'workflow_dispatch' and run['run_attempt'] == 1
            and run['status'] == 'completed' and run['conclusion'] == conclusion,
            'historical affected-run identity differs: ' + name)


_collection = b.Api.collection
def checked_collection(self, suffix, key, **query):
    rows = _collection(self, suffix, key, **query)
    if suffix == '/actions/workflows':
        actual = {Path(row['path']).name: row['id'] for row in rows if row.get('state') == 'active'}
        b.require(all(actual.get(name) == identity for name, identity in WORKFLOW_IDS.items()),
                  'allowlisted workflow ID/path mapping changed')
    return rows


def journal_name(run_id, attempt):
    return '59i-targeted-dispatch-journal-%s-%s-%s' % (CANDIDATE[:12], run_id, attempt)


b.load_manifest = load_manifest
b.clean_source = clean_source
b.guard = guard
b.stage_run = stage_run
b.require_stage = require_stage
b.prior_failures = prior_failures
b.Api.collection = checked_collection
b.journal_name = journal_name


def self_test(repo_root, manifest_path, gate_path):
    manifest = load_manifest(manifest_path)
    clean_source(repo_root, manifest)
    gate = json.loads(gate_path.read_text())
    b.require(gate['source']['run_id'] == 35814557330
              and gate['source']['artifact_id'] == 10737543172
              and gate['reconciliation']['run_id'] == 35836365057
              and gate['reconciliation']['artifact_id'] == 10740776415,
              'gate identity differs')
    print('INCREMENT_59I_TARGETED_C654_CONTROLLER_SELF_TEST_PASS')


if __name__ == '__main__':
    if len(sys.argv) == 5 and sys.argv[1] == '--self-test':
        self_test(Path(sys.argv[2]).resolve(), Path(sys.argv[3]).resolve(),
                  Path(sys.argv[4]).resolve())
    else:
        b.main()
