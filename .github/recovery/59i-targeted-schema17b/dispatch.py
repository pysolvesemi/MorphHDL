#!/usr/bin/env python3
"""Dispatch only the four schema-17-affected Increment 59i workflows."""
from __future__ import annotations

import hashlib
import importlib.util
import json
import os
from pathlib import Path
import sys


HERE = Path(__file__).resolve().parent
BASE_PATH = HERE.parent / '59i-targeted-c654' / 'dispatch.py'
spec = importlib.util.spec_from_file_location('increment_59i_schema12_dispatch', BASE_PATH)
if spec is None or spec.loader is None:
    raise RuntimeError('cannot load reviewed schema-12 dispatcher')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

m.OLD_MANIFEST_SHA256 = 'e9b461d622f16cacd955103503044704ee7cc73b25e7a58d9a7e2f3ef1c21c40'
m.PLAN_SHA256 = 'c553f476615f05052a3e676eea380063384f7e6fb04fc921a79857817da2a99e'
m.FEATURE = 'agent/increment-59i-combined-reduction-closure'
m.RECOVERY = 'recovery/increment-59i-history-20260914'
m.TARGET = '09880c538c4cf83022f4a1bb1dd16b43ea81a751'
m.PREDECESSOR = 'c48bad51b9857570e65fbd7c1be5dec465a3e8fa'
m.SOURCE_PARENT = 'f9805ccfc3c1d9d73a38d1e0e6dd5676cc21cbb6'
m.SOURCE = 'a11891bb879333f83a2469bd8c529265a58725fd'
m.SOURCE_TREE = '6712f79b63220f38ee7cadc86be589a31eff5d13'
m.CANDIDATE = 'b1c8183face8761e14746cf0aed62e654f6a3bee'
m.CANDIDATE_TREE = '3116fa7575150fc0e7567df158006c97b052ec08'
m.CONTROLLER = 'increment-59i-targeted-schema17b.yml'
m.WORKFLOW_HASHES = {
    'increment-62-wa08-source-overlay.yml':
        '250382848e77e10067f1ac5c9af7428e9a7727a1ec0053b6d5c7249e5fcbaa77',
    'increment-59i-combined-closure.yml':
        '9c0807f40d175ebe79ec81ba2bdf4a9894b985c76178fc01e664df0d07f4fac8',
    'increment-59h-nested-owners.yml':
        'c29048bc747a6604e87e34bbf8e69e9991cc03732f3a2f0dabbdf2206698afab',
    'increment-59f-callback-graphs.yml':
        'bb47eaeca764e1c92b4c3d64e72d4137af4067bf15ff284cece3039873ca7266',
}
m.HISTORICAL = (
    ('increment-62-wa08-source-overlay.yml', 35932294600, 'failure'),
    ('increment-59i-combined-closure.yml', 35932289750, 'failure'),
    ('increment-59h-nested-owners.yml', 35932294902, 'failure'),
    ('increment-59f-callback-graphs.yml', 35932294818, 'cancelled'),
)
m.HISTORICAL_EVENTS = {
    'increment-62-wa08-source-overlay.yml': 'pull_request',
    'increment-59i-combined-closure.yml': 'push',
    'increment-59h-nested-owners.yml': 'pull_request',
    'increment-59f-callback-graphs.yml': 'pull_request',
}
m.SOURCE_FILE_DIGESTS = {
    'audit-repair-paths.txt': '5b26ef90530a90e38d39548d9418b0296334e18be4c343d5076ee22e462c9398',
    'identity.txt': '2aad8d8272314037d10781ded3cf61c9c5addc231a3bf4cfd6ea35c21ef4555c',
    'live-refs.txt': '1cf6fdf67a027564706aca857c5e9178c096f6f4ab3b09d6a4bba06484d67181',
    'results.tsv': '5629c37bd68cd29a4244331677386f79ba7c2ca44baa2c0a4bf1d925cee14ffa',
    'seal-paths.txt': 'c58d9b81b014b084e2b6eabbba343022f9f986abda8ea2b4ec1b092e5838cc1d',
    'started.txt': 'eff64b343dcb2b1dc113648e7089b9ce9f8a7f6c7808a03a2cffb4ad7302f606',
    'target-reconciliation-paths.txt': '2e37239599b93dd85d5f0584b9f81b8b1fc94377e03b2ba7e47e3de2726dca6b',
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
        'schema': 17,
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
    m.b.require(m.b.digest(raw) == m.OLD_MANIFEST_SHA256,
                'base 16-workflow manifest changed')
    base = json.loads(raw)
    rows = {row['workflow']: row for row in base['workflows']}
    value = dict(base)
    value.update(candidate=m.CANDIDATE, source=m.SOURCE,
                 target=m.TARGET, full_ci=False)
    value['workflows'] = []
    for name, _, _ in m.HISTORICAL:
        m.b.require(name in rows, 'affected workflow absent from reviewed base manifest')
        row = dict(rows[name])
        row['jobs'] = [dict(job) for job in row['jobs']]
        row['workflow_id'] = m.WORKFLOW_IDS[name]
        row['workflow_sha256'] = m.WORKFLOW_HASHES[name]
        if name == 'increment-59i-combined-closure.yml':
            row['jobs'][0]['timeout_minutes'] = 360
        if name == 'increment-59h-nested-owners.yml':
            source_name = 'Nested reduction owner source audits'
            row['all_job_names'] = [source_name, *row['all_job_names']]
            row['required_job_names'] = [source_name, *row['required_job_names']]
            row['jobs'] = [{'id': 'source', 'if': 'true', 'names': [source_name],
                            'timeout_minutes': 360}, *row['jobs']]
        if name == 'increment-59f-callback-graphs.yml':
            source_name = 'Safe callback graph source audits'
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
    m.b.require(m.b.digest(plan_raw) == m.PLAN_SHA256,
                'normalized four-workflow plan changed')
    m.b.require(len(value['workflows']) == 4
                and sum(len(row['required_job_names']) for row in value['workflows']) == 13,
                'expected four workflows and thirteen required jobs')
    return value


def require_stage(api, gate, root, output):
    source_gate = gate['source']
    source_run = m.stage_run(api, gate)
    source_raw = m._artifact(api, source_run, source_gate['artifact_id'],
        'increment-59i-runtime-successor-source-35969094981-1',
        source_gate['artifact_sha256'])
    source_files = m.b.zip_files(source_raw)
    m.b.require(set(source_files) == m.SOURCE_MEMBERS,
                'source artifact member inventory differs')
    for name, expected in m.SOURCE_FILE_DIGESTS.items():
        m.b.require(m.b.digest(source_files[name]) == expected,
                    'source artifact member differs: ' + name)
    results = source_files['results.tsv'].decode().splitlines()
    m.b.require(len(results) == 25 and all(line.startswith('PASS\t0\t') for line in results),
                'source artifact does not retain 25 successful commands')
    m.b.require(all(source_files[f'{index:02d}.log'] for index in range(1, 26)),
                'source artifact contains an empty command log')

    recon_gate = gate['reconciliation']
    recon_run = api.get('/actions/runs/' + str(recon_gate['run_id']))
    recon_raw = m._artifact(api, recon_run, recon_gate['artifact_id'],
        'increment-59i-schema17-target-reconciliation-35991929973-1',
        recon_gate['artifact_sha256'])
    recon_files = m.b.zip_files(recon_raw)
    m.b.require(set(recon_files) == m.RECON_MEMBERS,
                'reconciliation member inventory differs')
    m.b.require(m.b.digest(recon_files['source.zip']) == source_gate['artifact_sha256'],
                'reconciliation embedded source artifact differs')
    receipt = json.loads(recon_files['reconciliation.json'])
    expected = {
        'current_target': m.TARGET,
        'current_target_tree': '6216cf799cc51c5a5f815d08f16e435c6b48ddc7',
        'feature_predecessor': m.PREDECESSOR,
        'feature_ref_updated': False,
        'full_ci_started': False,
        'prospective_merge_tree': m.CANDIDATE_TREE,
        'qualification_claimed': False,
        'schema': 17,
        'seal': m.CANDIDATE,
        'seal_tree': m.CANDIDATE_TREE,
        'source': m.SOURCE,
        'source_artifact': source_gate['artifact_id'],
        'source_artifact_sha256': source_gate['artifact_sha256'],
        'source_commands_passed': 25,
        'source_run': source_gate['run_id'],
        'source_tree': m.SOURCE_TREE,
        'target_blob': '5739586b9271dee22df84126f83a26cb07e0b1db',
        'target_change': ['morphhdl-passes/morphhdl-ir-wire-assignment-passes-todo.md'],
        'target_is_ancestor': True,
    }
    m.b.require(receipt == expected, 'reconciliation receipt differs')
    m.b.require(recon_files['audit.log'].decode().strip()
                == 'INCREMENT_59I_SCHEMA17_TARGET_RECONCILIATION_PASS',
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
    m.b.write(output / 'verified-evidence.json', verified)
    return verified


def journal_name(run_id, attempt):
    return '59i-targeted-dispatch-journal-%s-%s-%s' % (
        m.CANDIDATE[:12], run_id, attempt)


def prior_failures(api):
    for name, identity, conclusion in m.HISTORICAL:
        run = api.get('/actions/runs/' + str(identity))
        m.b.require(run['id'] == identity and run['head_sha'] == m.PREDECESSOR
            and run['head_branch'] == m.FEATURE
            and run['repository']['full_name'] == m.b.REPOSITORY
            and run['path'] == '.github/workflows/' + name
            and run['event'] == m.HISTORICAL_EVENTS[name]
            and run['run_attempt'] == 1 and run['status'] == 'completed'
            and run['conclusion'] == conclusion,
            'historical affected-run identity differs: ' + name)


def self_test(repo_root, manifest_path, gate_path):
    manifest = load_manifest(manifest_path)
    m.clean_source(repo_root, manifest)
    gate = json.loads(gate_path.read_text())
    m.b.require(gate['source']['run_id'] == 35969094981
                and gate['source']['artifact_id'] == 10804655467
                and gate['reconciliation']['run_id'] == 35991929973
                and gate['reconciliation']['artifact_id'] == 10803784552,
                'gate identity differs')
    print('INCREMENT_59I_TARGETED_SCHEMA17_CONTROLLER_SELF_TEST_PASS')


m.normalized_plan = normalized_plan
m.load_manifest = load_manifest
m.require_stage = require_stage
m.journal_name = journal_name
m.b.load_manifest = load_manifest
m.b.clean_source = m.clean_source
m.b.guard = m.guard
m.b.stage_run = m.stage_run
m.b.require_stage = require_stage
m.b.prior_failures = prior_failures
m.b.Api.collection = m.checked_collection
m.b.journal_name = journal_name
m.b.MANIFEST_SHA256 = m.PLAN_SHA256


if __name__ == '__main__':
    if len(sys.argv) == 5 and sys.argv[1] == '--self-test':
        self_test(Path(sys.argv[2]).resolve(), Path(sys.argv[3]).resolve(),
                  Path(sys.argv[4]).resolve())
    else:
        m.b.main()
