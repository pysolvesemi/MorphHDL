#!/usr/bin/env python3
"""Narrow exact-head dispatcher for Increment 59i's 16 affected workflows.

This wrapper reuses the already-reviewed intent-before-POST dispatcher while
replacing every candidate, evidence, workflow-ID, and historical-run binding.
It cannot update refs, PRs, or dispatch any workflow outside the 16-row plan.
"""
from __future__ import annotations

import hashlib
import importlib.util
import json
import os
from pathlib import Path
import sys


HERE = Path(__file__).resolve().parent
BASE_PATH = Path(os.getenv('INCREMENT_59I_BASE_DISPATCH',
    str(HERE.parent / '59i-repair-failed-first' / 'dispatch.py')))
OLD_MANIFEST_SHA256 = 'e9b461d622f16cacd955103503044704ee7cc73b25e7a58d9a7e2f3ef1c21c40'
PLAN_SHA256 = 'ff1041e8702bb968feeb13577a54e5becb23c8de7350aab90527774cff04eb4c'

FEATURE = 'agent/increment-59i-combined-reduction-closure'
RECOVERY = 'recovery/increment-59i-history-20260914'
TARGET = 'ce4a02c11b5ec19777c3d900e7fdc06ebbf6d7cd'
SOURCE_TIME_TARGET = 'bbae646ba43e6189c69feb308f8decb9b677b15f'
PREDECESSOR = '883c5d8f088a0e2eab35592cf171d87792d30bf4'
SOURCE = 'dafc1c73658f0c0539068001c22dfbb5ff84b2b1'
SOURCE_TREE = '4bf6bbcd1f84223e623b1a272594177550d75422'
CANDIDATE = '9d6d738d32c71ff4359384ae9d87f715bf20ec55'
CANDIDATE_TREE = '3498988dd26e3ee8c64209dec3dc57814dcf4111'
CONTROLLER = 'increment-59i-targeted-9d6d.yml'

WORKFLOW_IDS = {
    'increment-59i-local-enable-committed-head.yml': 358545233,
    'lane-when-expression-diagnostic.yml': 357516223,
    'independent-parameter-domains.yml': 357674356,
    'increment-62-wa08-source-overlay.yml': 354645379,
    'increment-61-one-file-per-component.yml': 353947848,
    'increment-61-compatibility-matrix.yml': 354063673,
    'increment-60b-signedness-authority.yml': 350709339,
    'increment-59i-combined-closure.yml': 352827927,
    'cdc-independent-parameter-consumers.yml': 358619655,
    'morphhdl-mill.yml': 332476711,
    'morphhdl-baseline.yml': 332279971,
    'increment-60f-equivalence-closure.yml': 351327055,
    'increment-59h-nested-owners.yml': 351996892,
    'increment-59g-register-bridges.yml': 351947632,
    'increment-59f-callback-graphs.yml': 351397918,
    'increment-59e-composite-reduction.yml': 351360305,
}

HISTORICAL = (
    ('increment-59i-local-enable-committed-head.yml', 35527087094, 'success'),
    ('lane-when-expression-diagnostic.yml', 35527089532, 'failure'),
    ('independent-parameter-domains.yml', 35527091795, 'success'),
    ('increment-62-wa08-source-overlay.yml', 35527094027, 'success'),
    ('increment-61-one-file-per-component.yml', 35527096567, 'success'),
    ('increment-61-compatibility-matrix.yml', 35527099055, 'success'),
    ('increment-60b-signedness-authority.yml', 35527101369, 'success'),
    ('increment-59i-combined-closure.yml', 35527103629, 'failure'),
    ('cdc-independent-parameter-consumers.yml', 35527106176, 'success'),
    ('morphhdl-mill.yml', 35527109122, 'success'),
    ('morphhdl-baseline.yml', 35527111981, 'success'),
    ('increment-60f-equivalence-closure.yml', 35527114989, 'cancelled'),
    ('increment-59h-nested-owners.yml', 35527118755, 'cancelled'),
    ('increment-59g-register-bridges.yml', 35527122356, 'failure'),
    ('increment-59f-callback-graphs.yml', 35527126237, 'success'),
    ('increment-59e-composite-reduction.yml', 35527129879, 'success'),
)

CHANGED_HASHES = {
    'increment-59i-combined-closure.yml': '9c0807f40d175ebe79ec81ba2bdf4a9894b985c76178fc01e664df0d07f4fac8',
    'increment-60f-equivalence-closure.yml': '6d95c34b07db577ef6fa9e09dac3d5f516eb7f06de413a42f1089187e417ba79',
    'increment-59h-nested-owners.yml': 'c29048bc747a6604e87e34bbf8e69e9991cc03732f3a2f0dabbdf2206698afab',
}

SOURCE_MEMBERS = {
    *(f'{index:02d}.log' for index in range(1, 26)),
    'identity.txt', 'live-refs.txt', 'results.tsv', 'runtime-repair-paths.txt',
    'seal-paths.txt', 'started.txt',
}
RECON_MEMBERS = {
    'audit.log', 'current-live-refs.txt', 'reconciliation.json', 'source.zip',
    'unit-tests.log',
}

REPAIR_PATHS = {
    '.github/workflows/increment-59h-nested-owners.yml',
    '.github/workflows/increment-59i-combined-closure.yml',
    '.github/workflows/increment-60f-equivalence-closure.yml',
    'morphhdl/scripts/check-cdc-successor-source.py',
    'morphhdl/scripts/check-increment-59i-local-enable-source-review.py',
    'morphhdl/scripts/check-increment-59i-pr190-integration.py',
    'morphhdl/scripts/check-increment-59i-production-successor.py',
    'morphhdl/scripts/check-increment-59i-regression-inventory.py',
    'morphhdl/scripts/check-increment-59i-rollout-composition.py',
    'morphhdl/scripts/check-increment-59i-target-integration.py',
    'morphhdl/scripts/check-increment-59i-widening-source-review.py',
    'morphhdl/scripts/check-increment-60b-signedness-authority.py',
    'morphhdl/scripts/check-increment-61-source-review.py',
    'morphhdl/scripts/check-increment-62-wa08-source-overlay.py',
    'morphhdl/scripts/check-pr190-pr189-source-sync.py',
    'morphhdl/scripts/test-increment-59g-report-inventory.py',
    'morphhdl/scripts/test-increment-59i-continuation.py',
    'morphhdl/scripts/test-increment-59i-pr189-sync.py',
    'morphhdl/scripts/test-increment-59i-pr190-integration.py',
    'morphhdl/scripts/test-increment-59i-regression-inventory.py',
    'morphhdl/src/main/scala/spinal/core/internals/ParameterizedVerilogStructural.scala',
    'morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionBackend.scala',
    'morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionBridgeReplay.scala',
    'morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCapture.scala',
    'morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionClosedGraph.scala',
    'morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeReplay.scala',
    'morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionStageReplay.scala',
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
b.PRIOR = HISTORICAL[1:]
b.WORKFLOWS = tuple(name for name, _, _ in HISTORICAL)


def normalized_plan(value):
    return {
        'schema': 2,
        'candidate': CANDIDATE,
        'candidate_tree': CANDIDATE_TREE,
        'source': SOURCE,
        'source_tree': SOURCE_TREE,
        'predecessor': PREDECESSOR,
        'target': TARGET,
        'source_time_target': SOURCE_TIME_TARGET,
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
    value = json.loads(raw)
    b.require(tuple(row['workflow'] for row in value['workflows']) == b.WORKFLOWS,
              'reviewed workflow order changed')
    value.update(candidate=CANDIDATE, source=SOURCE, target=TARGET, full_ci=False)
    for row in value['workflows']:
        name = row['workflow']
        row['workflow_id'] = WORKFLOW_IDS[name]
        if name in CHANGED_HASHES:
            row['workflow_sha256'] = CHANGED_HASHES[name]
        if name == 'increment-59h-nested-owners.yml':
            source_name = 'Nested reduction owner source audits'
            row['all_job_names'] = [source_name, *row['all_job_names']]
            row['required_job_names'] = [source_name, *row['required_job_names']]
            row['jobs'] = [{'id': 'source', 'if': 'true', 'names': [source_name],
                            'timeout_minutes': 180}, *row['jobs']]
    value['historical_runs'] = [dict(workflow=name, run_id=identity,
        conclusion=conclusion, head=PREDECESSOR, branch=FEATURE, run_attempt=1)
        for name, identity, conclusion in HISTORICAL]
    plan_raw = json.dumps(normalized_plan(value), sort_keys=True,
                          separators=(',', ':')).encode()
    b.require(b.digest(plan_raw) == PLAN_SHA256, 'normalized exact-head plan changed')
    b.require(len(value['workflows']) == 16
              and sum(len(row['required_job_names']) for row in value['workflows']) == 56,
              'expected 16 workflows and 56 required jobs')
    return value


def clean_source(root, manifest):
    b.require(b.git(root, 'rev-parse', 'HEAD').decode().strip() == CANDIDATE,
              'checkout is not exact candidate')
    b.require(b.git(root, 'rev-parse', 'HEAD^{tree}').decode().strip() == CANDIDATE_TREE,
              'candidate tree differs')
    b.require(b.git(root, 'show', '-s', '--format=%P', CANDIDATE).decode().split() == [SOURCE],
              'seal is not the direct source child')
    b.require(b.git(root, 'show', '-s', '--format=%P', SOURCE).decode().split() == [PREDECESSOR],
              'source is not the direct predecessor child')
    b.require(b.git(root, 'rev-parse', SOURCE + '^{tree}').decode().strip() == SOURCE_TREE,
              'source tree differs')
    b.require(not b.git(root, 'status', '--porcelain', '--untracked-files=normal'),
              'candidate checkout is dirty')
    b.git(root, 'merge-base', '--is-ancestor', SOURCE_TIME_TARGET, CANDIDATE)
    for row in manifest['workflows']:
        b.require(row['path'] == '.github/workflows/' + row['workflow'], 'invalid workflow path')
        expected = row['workflow_sha256']
        b.require(b.digest(b.git(root, 'show', CANDIDATE + ':' + row['path'])) == expected,
                  'candidate workflow differs: ' + row['workflow'])
        b.require(b.digest((root / row['path']).read_bytes()) == expected,
                  'checkout workflow differs: ' + row['workflow'])
        b.require(row['workflow_id'] == WORKFLOW_IDS[row['workflow']], 'workflow ID differs')
        b.require(row['inputs'] == ({'mode': 'checks'} if row['workflow'].startswith('cdc-') else {}),
                  'unreviewed dispatch inputs')


def guard(api):
    pr = api.get('/pulls/177')
    feature = api.get('/git/ref/heads/' + FEATURE)
    target = api.get('/git/ref/heads/parameterized-verilog')
    b.require(pr['state'] == 'open' and not pr['merged'] and pr['draft']
        and pr['head']['sha'] == CANDIDATE and pr['head']['ref'] == FEATURE
        and pr['head']['repo']['full_name'] == b.REPOSITORY
        and pr['base']['ref'] == 'parameterized-verilog'
        and feature['object']['sha'] == CANDIDATE and target['object']['sha'] == TARGET,
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
            'Run rejection controls and exact merge-tree audit',
        })
    return source_run


def require_stage(api, gate, root, output):
    source_run = stage_run(api, gate)
    source_gate = gate['source']
    source_raw = _artifact(api, source_run, source_gate['artifact_id'],
        'increment-59i-runtime-successor-source-35556994859-1',
        source_gate['artifact_sha256'])
    source_files = b.zip_files(source_raw)
    b.require(set(source_files) == SOURCE_MEMBERS, 'source artifact member inventory differs')
    b.require(source_files['identity.txt'].decode().splitlines() == [
        CANDIDATE, SOURCE, PREDECESSOR, CANDIDATE_TREE, 'f2219f49ef53ea3defc3526b0df76e5a67df4993'],
        'source artifact Git identity differs')
    results = source_files['results.tsv'].decode().splitlines()
    b.require(len(results) == 25 and all(line.startswith('PASS\t0\t') for line in results),
              'source artifact does not retain 25 successful commands')
    b.require(set(source_files['runtime-repair-paths.txt'].decode().splitlines()) == REPAIR_PATHS,
              '27-path repair inventory differs')
    b.require(source_files['seal-paths.txt'].decode().splitlines() == [
        'morphhdl/contracts/increment-59i-production-successor.json',
        'morphhdl/scripts/check-increment-59i-production-successor.py'],
        'seal path inventory differs')

    recon_gate = gate['reconciliation']
    recon_run = api.get('/actions/runs/' + str(recon_gate['run_id']))
    recon_raw = _artifact(api, recon_run, recon_gate['artifact_id'],
        'increment-59i-target-doc-reconciliation-35569822767-1',
        recon_gate['artifact_sha256'])
    recon_files = b.zip_files(recon_raw)
    b.require(set(recon_files) == RECON_MEMBERS, 'reconciliation artifact member inventory differs')
    b.require(b.digest(recon_files['source.zip']) == source_gate['artifact_sha256'],
              'reconciliation embedded source artifact differs')
    receipt = json.loads(recon_files['reconciliation.json'])
    expected = {
        'agents_blob': '36abb9e910357b79ab3cd72fac4276b51d6ba9d5',
        'agents_sha256': '1f20a687842ecd9f0f3cc2d7a26c43627fc42468b9011a9ae9b8bec949a2bca3',
        'current_target': TARGET,
        'current_target_tree': 'd59b08a6cc850d35532aaf98856031a2da4ee862',
        'feature_predecessor': PREDECESSOR,
        'feature_ref_updated': False,
        'full_ci_started': False,
        'prospective_merge_tree': '7cf398c5e5bb13a75ed0f0b88ce6606b89ca187d',
        'qualification_claimed': False,
        'schema': 1,
        'seal': CANDIDATE,
        'seal_tree': CANDIDATE_TREE,
        'source': SOURCE,
        'source_artifact': source_gate['artifact_id'],
        'source_artifact_sha256': source_gate['artifact_sha256'],
        'source_commands_passed': 25,
        'source_run': source_gate['run_id'],
        'source_time_target': SOURCE_TIME_TARGET,
        'source_tree': SOURCE_TREE,
        'target_change': ['AGENTS.md'],
    }
    b.require(receipt == expected and recon_files['audit.log'].decode().strip()
              == 'INCREMENT_59I_TARGET_DOCUMENTATION_RECONCILIATION_PASS',
              'reconciliation receipt differs')
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
            and run['path'] == '.github/workflows/' + name and run['event'] == 'workflow_dispatch'
            and run['run_attempt'] == 1 and run['status'] == 'completed'
            and run['conclusion'] == conclusion,
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
    b.require(gate['source']['run_id'] == 35556994859
              and gate['source']['artifact_id'] == 10622841322
              and gate['reconciliation']['run_id'] == 35569822767
              and gate['reconciliation']['artifact_id'] == 10625745351,
              'gate identity differs')
    print('INCREMENT_59I_TARGETED_9D6D_CONTROLLER_SELF_TEST_PASS')


if __name__ == '__main__':
    if len(sys.argv) == 5 and sys.argv[1] == '--self-test':
        self_test(Path(sys.argv[2]).resolve(), Path(sys.argv[3]).resolve(),
                  Path(sys.argv[4]).resolve())
    else:
        b.main()
