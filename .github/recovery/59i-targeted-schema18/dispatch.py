#!/usr/bin/env python3
"""Dispatch only the three still-failed schema-18 Increment 59i workflows."""
from __future__ import annotations

import importlib.util
import json
import os
from pathlib import Path
import sys


HERE = Path(__file__).resolve().parent
BASE_PATH = HERE.parent / '59i-targeted-schema17b' / 'dispatch.py'
spec = importlib.util.spec_from_file_location('increment_59i_schema17_dispatch', BASE_PATH)
if spec is None or spec.loader is None:
    raise RuntimeError('cannot load reviewed schema-17 dispatcher')
p = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)
m = p.m
b = m.b
BASE_GUARD = m.guard

m.FEATURE = 'agent/increment-59i-combined-reduction-closure'
m.RECOVERY = 'recovery/increment-59i-history-20260914'
m.TARGET = 'db54d01e5b21c7664f7a0de3795f061d77a3d259'
m.PREDECESSOR = 'b1c8183face8761e14746cf0aed62e654f6a3bee'
m.SOURCE_PARENT = 'd8b89e5a9a2c0fd08a51391b5d4487237bb7d234'
m.SOURCE = 'eeedb70bd3667a779cba58f89ff3b5367bdceb4b'
m.SOURCE_TREE = 'ea3c6a7e1d002dfec1053911ccebb0540027408f'
m.CANDIDATE = 'fb49648d8b7f05310d0907381c364c2e11882645'
m.CANDIDATE_TREE = 'eea3ee75833a9fa418c0da1ff47b0ba0c95ff7e2'
m.CONTROLLER = 'increment-59i-targeted-schema18.yml'
m.PLAN_SHA256 = '7c9f14cc3a3391df58188ed06c3d21601b0a7110919b2a9010ca11880b6b7a7a'
m.WORKFLOW_HASHES = {
    'increment-62-wa08-source-overlay.yml':
        '250382848e77e10067f1ac5c9af7428e9a7727a1ec0053b6d5c7249e5fcbaa77',
    'increment-59i-combined-closure.yml':
        'd183330757c8f3933679b40a418faed2240517668b482da45198bcd130fe5437',
    'increment-59h-nested-owners.yml':
        'c29048bc747a6604e87e34bbf8e69e9991cc03732f3a2f0dabbdf2206698afab',
}
m.HISTORICAL = (
    ('increment-62-wa08-source-overlay.yml', 35998532806, 'failure'),
    ('increment-59i-combined-closure.yml', 35998543315, 'failure'),
    ('increment-59h-nested-owners.yml', 35998553451, 'failure'),
)
m.HISTORICAL_EVENTS = {name: 'workflow_dispatch' for name, _, _ in m.HISTORICAL}
m.SOURCE_MEMBERS = {
    *(f'{index:02d}.log' for index in range(1, 26)),
    'audit-repair-paths.txt', 'identity.txt', 'live-refs.txt', 'results.tsv',
    'seal-paths.txt', 'started.txt', 'target-composition-paths.txt',
}
m.SOURCE_FILE_DIGESTS = {
    'audit-repair-paths.txt': '83376f2bcc7c4b4a4281d34ba7cd95e422f36bfd18ece8ab1d611ef6736db6bd',
    'identity.txt': '4160f5edb6ed4ed8aa20cfde6710c89a718960661fab62b1752756a4970525b4',
    'live-refs.txt': 'ceb3ee4271d3416eb0c8aa7c86d06e0e41c46ccddbc5a254e543e7059a00da59',
    'results.tsv': '09729e94bfa1d45720e6ae126de7e0efaa855be0363c12c8715f8be4e8f7678f',
    'seal-paths.txt': 'c58d9b81b014b084e2b6eabbba343022f9f986abda8ea2b4ec1b092e5838cc1d',
    'started.txt': 'eff64b343dcb2b1dc113648e7089b9ce9f8a7f6c7808a03a2cffb4ad7302f606',
    'target-composition-paths.txt': '457b5b136a39546eb68ccb12463f920787cbbc01921c4c7ca3bc4dafa4ee3029',
}

m.b.TARGET = m.TARGET
m.b.CANDIDATE = m.CANDIDATE
m.b.CANDIDATE_TREE = m.CANDIDATE_TREE
m.b.SOURCE = m.SOURCE
m.b.BASE = m.PREDECESSOR
m.b.CONTROLLER = m.CONTROLLER
m.b.PRIOR = m.HISTORICAL
m.b.WORKFLOWS = tuple(name for name, _, _ in m.HISTORICAL)


def normalized_plan(value):
    return {
        'schema': 18,
        'candidate': m.CANDIDATE,
        'candidate_tree': m.CANDIDATE_TREE,
        'source': m.SOURCE,
        'source_tree': m.SOURCE_TREE,
        'source_parent': m.SOURCE_PARENT,
        'predecessor': m.PREDECESSOR,
        'target': m.TARGET,
        'workflows': [{
            'workflow': row['workflow'],
            'path': row['path'],
            'workflow_id': row['workflow_id'],
            'workflow_sha256': row['workflow_sha256'],
            'inputs': row['inputs'],
            'all_job_names': row['all_job_names'],
            'required_job_names': row['required_job_names'],
            'jobs': row['jobs'],
        } for row in value['workflows']],
        'historical': [list(row) for row in m.HISTORICAL],
        'full_ci': False,
    }


def load_manifest(path):
    raw = path.read_bytes()
    b.require(b.digest(raw) == m.OLD_MANIFEST_SHA256,
              'base 16-workflow manifest changed')
    base = json.loads(raw)
    rows = {row['workflow']: row for row in base['workflows']}
    value = dict(base)
    value.update(candidate=m.CANDIDATE, source=m.SOURCE,
                 target=m.TARGET, full_ci=False)
    value['workflows'] = []
    for name, _, _ in m.HISTORICAL:
        b.require(name in rows, 'affected workflow absent from reviewed base manifest')
        row = dict(rows[name])
        row['jobs'] = [dict(job) for job in row['jobs']]
        row['workflow_id'] = m.WORKFLOW_IDS[name]
        row['workflow_sha256'] = m.WORKFLOW_HASHES[name]
        if name == 'increment-59i-combined-closure.yml':
            source_name = 'Composite integration source audits'
            inherited_name = 'Composite integration inherited source audits'
            row['all_job_names'] = [source_name, inherited_name, *row['all_job_names']]
            row['required_job_names'] = [source_name, inherited_name, *row['required_job_names']]
            row['jobs'] = [
                {'id': 'source', 'if': 'true', 'names': [source_name], 'timeout_minutes': 360},
                {'id': 'inherited_source', 'if': 'true', 'names': [inherited_name], 'timeout_minutes': 360},
                *row['jobs'],
            ]
            row['jobs'][2]['timeout_minutes'] = 360
        if name == 'increment-59h-nested-owners.yml':
            source_name = 'Nested reduction owner source audits'
            row['all_job_names'] = [source_name, *row['all_job_names']]
            row['required_job_names'] = [source_name, *row['required_job_names']]
            row['jobs'] = [{'id': 'source', 'if': 'true', 'names': [source_name],
                            'timeout_minutes': 360}, *row['jobs']]
        value['workflows'].append(row)
    value['historical_runs'] = [dict(
        workflow=name, run_id=identity, conclusion=conclusion,
        head=m.PREDECESSOR, branch=m.FEATURE, run_attempt=1,
    ) for name, identity, conclusion in m.HISTORICAL]
    plan_raw = json.dumps(normalized_plan(value), sort_keys=True,
                          separators=(',', ':')).encode()
    b.require(b.digest(plan_raw) == m.PLAN_SHA256,
              'normalized three-workflow plan changed')
    b.require(len(value['workflows']) == 3
              and sum(len(row['required_job_names']) for row in value['workflows']) == 12,
              'expected three workflows and twelve required jobs')
    return value


def guard(api):
    BASE_GUARD(api)
    recovery = api.get('/git/ref/heads/' + m.RECOVERY)
    b.require(recovery['object']['sha'] == os.getenv('GITHUB_SHA'),
              'live recovery ref changed during dispatch')


def require_stage(api, gate, root, output):
    source_gate = gate['source']
    source_run = m._passed_run(api, source_gate['run_id'], source_gate['controller_sha'],
        'increment-59i-runtime-successor-source.yml', 'source', {
            'Authenticate exact composed successor and unchanged live refs',
            'Run all 25 retained source gates',
        })
    source_raw = m._artifact(api, source_run, source_gate['artifact_id'],
        'increment-59i-runtime-successor-source-36112328748-1',
        source_gate['artifact_sha256'])
    source_files = b.zip_files(source_raw)
    b.require(set(source_files) == m.SOURCE_MEMBERS,
              'source artifact member inventory differs')
    for name, expected in m.SOURCE_FILE_DIGESTS.items():
        b.require(b.digest(source_files[name]) == expected,
                  'source artifact member differs: ' + name)
    results = source_files['results.tsv'].decode().splitlines()
    b.require(len(results) == 25 and all(line.startswith('PASS\t0\t') for line in results),
              'source artifact does not retain 25 successful commands')
    b.require(all(source_files[f'{index:02d}.log'] for index in range(1, 26)),
              'source artifact contains an empty command log')

    recon_gate = gate['reconciliation']
    recon_run = m._passed_run(api, recon_gate['run_id'], recon_gate['controller_sha'],
        'increment-59i-target-doc-reconciliation.yml', 'reconcile', {
            'Authenticate live refs and original successful source artifact',
            'Run rejection controls and exact current-target audit',
        })
    recon_raw = m._artifact(api, recon_run, recon_gate['artifact_id'],
        'increment-59i-schema18-target-reconciliation-36134293312-1',
        recon_gate['artifact_sha256'])
    recon_files = b.zip_files(recon_raw)
    b.require(set(recon_files) == m.RECON_MEMBERS,
              'reconciliation member inventory differs')
    b.require(b.digest(recon_files['source.zip']) == source_gate['artifact_sha256'],
              'reconciliation embedded source artifact differs')
    expected = {
        'artifact_sha256': source_gate['artifact_sha256'],
        'feature': m.PREDECESSOR,
        'prospective_tree': m.CANDIDATE_TREE,
        'schema': 18,
        'seal': m.CANDIDATE,
        'source': m.SOURCE,
        'source_commands': 25,
        'source_records': 427,
        'target': m.TARGET,
        'target_records': 26,
    }
    b.require(json.loads(recon_files['reconciliation.json']) == expected,
              'reconciliation receipt differs')
    b.require(recon_files['audit.log'].decode().strip()
              == 'SCHEMA18_TARGET_RECONCILIATION_PASS source=25 records=427 '
                 'target_records=26 tree=' + m.CANDIDATE_TREE,
              'reconciliation audit marker differs')
    verified = {
        'source_run': source_gate['run_id'],
        'source_artifact': source_gate['artifact_id'],
        'source_artifact_sha256': source_gate['artifact_sha256'],
        'source_commands_passed': 25,
        'reconciliation_run': recon_gate['run_id'],
        'reconciliation_artifact': recon_gate['artifact_id'],
        'reconciliation_artifact_sha256': recon_gate['artifact_sha256'],
        'candidate': m.CANDIDATE,
        'target': m.TARGET,
        'full_ci': False,
    }
    b.write(output / 'verified-evidence.json', verified)
    return verified


def prior_failures(api):
    for name, identity, conclusion in m.HISTORICAL:
        run = api.get('/actions/runs/' + str(identity))
        b.require(run['id'] == identity and run['head_sha'] == m.PREDECESSOR
            and run['head_branch'] == m.FEATURE
            and run['repository']['full_name'] == b.REPOSITORY
            and run['path'] == '.github/workflows/' + name
            and run['event'] == m.HISTORICAL_EVENTS[name]
            and run['run_attempt'] == 1 and run['status'] == 'completed'
            and run['conclusion'] == conclusion,
            'historical affected-run identity differs: ' + name)


def stage_run(api, gate):
    source = gate['source']
    reconciliation = gate['reconciliation']
    source_run = m._passed_run(api, source['run_id'], source['controller_sha'],
        'increment-59i-runtime-successor-source.yml', 'source', {
            'Authenticate exact composed successor and unchanged live refs',
            'Run all 25 retained source gates',
        })
    m._passed_run(api, reconciliation['run_id'], reconciliation['controller_sha'],
        'increment-59i-target-doc-reconciliation.yml', 'reconcile', {
            'Authenticate live refs and original successful source artifact',
            'Run rejection controls and exact current-target audit',
        })
    return source_run


def journal_name(run_id, attempt):
    return '59i-targeted-dispatch-journal-%s-%s-%s' % (
        m.CANDIDATE[:12], run_id, attempt)


def self_test(repo_root, manifest_path, gate_path):
    manifest = load_manifest(manifest_path)
    m.clean_source(repo_root, manifest)
    gate = json.loads(gate_path.read_text())
    b.require(gate['source']['run_id'] == 36112328748
              and gate['source']['artifact_id'] == 10861343692
              and gate['reconciliation']['run_id'] == 36134293312
              and gate['reconciliation']['artifact_id'] == 10863069418,
              'gate identity differs')
    print('INCREMENT_59I_TARGETED_SCHEMA18_CONTROLLER_SELF_TEST_PASS')


m.normalized_plan = normalized_plan
m.load_manifest = load_manifest
m.guard = guard
m.require_stage = require_stage
m.stage_run = stage_run
m.prior_failures = prior_failures
m.journal_name = journal_name
m.b.load_manifest = load_manifest
m.b.guard = guard
m.b.require_stage = require_stage
m.b.stage_run = stage_run
m.b.prior_failures = prior_failures
m.b.journal_name = journal_name
m.b.MANIFEST_SHA256 = m.PLAN_SHA256


if __name__ == '__main__':
    if len(sys.argv) == 5 and sys.argv[1] == '--self-test':
        self_test(Path(sys.argv[2]).resolve(), Path(sys.argv[3]).resolve(),
                  Path(sys.argv[4]).resolve())
    else:
        m.b.main()
