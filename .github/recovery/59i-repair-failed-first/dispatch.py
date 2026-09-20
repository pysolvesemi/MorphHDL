#!/usr/bin/env python3
"""Dispatch only PR177's 15 diagnosed failures/cancellations plus committed-head qualification.

No source/ref/PR mutations, implicit retries, or full-CI dispatches are supported.
An intent is fsynced before POST; an uncertain request must be reconciled. On
another Actions run the prior journal is restored from its digest-checked artifact.
A missing journal after the controller step started blocks all further POSTs.
"""
from __future__ import annotations
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile

REPOSITORY = 'pysolvesemi/MorphHDL'
FEATURE = 'agent/increment-59i-combined-reduction-closure'
RECOVERY = 'recovery/increment-59i-history-20260914'
TARGET = 'bbae646ba43e6189c69feb308f8decb9b677b15f'
CANDIDATE = '883c5d8f088a0e2eab35592cf171d87792d30bf4'
CANDIDATE_TREE = 'f2219f49ef53ea3defc3526b0df76e5a67df4993'
SOURCE = '44313610eb2d72759808f348a552134e97805474'
BASE = 'b6f1fefb531ca4cb5aca266628dc29093f6bbafe'
CHECKPOINT = '771b02d9c5669f7e3a3cc3732b393dd083947ea6'
LOCAL = 'increment-59i-local-enable-committed-head.yml'
CONTROLLER = 'increment-59i-repair-failed-first-controller.yml'
STAGE = 'increment-59i-local-enable-publication-stage.yml'
MANIFEST_SHA256 = 'e9b461d622f16cacd955103503044704ee7cc73b25e7a58d9a7e2f3ef1c21c40'
PRIOR = (
    ('lane-when-expression-diagnostic.yml', 35499173209, 'failure'),
    ('independent-parameter-domains.yml', 35499171285, 'failure'),
    ('increment-62-wa08-source-overlay.yml', 35499169370, 'failure'),
    ('increment-61-one-file-per-component.yml', 35499167334, 'failure'),
    ('increment-61-compatibility-matrix.yml', 35499165569, 'failure'),
    ('increment-60b-signedness-authority.yml', 35499153709, 'failure'),
    ('increment-59i-combined-closure.yml', 35499141350, 'failure'),
    ('cdc-independent-parameter-consumers.yml', 35499115467, 'failure'),
    ('morphhdl-mill.yml', 35499191135, 'cancelled'),
    ('morphhdl-baseline.yml', 35499175068, 'cancelled'),
    ('increment-60f-equivalence-closure.yml', 35499161822, 'cancelled'),
    ('increment-59h-nested-owners.yml', 35499139515, 'cancelled'),
    ('increment-59g-register-bridges.yml', 35499137471, 'cancelled'),
    ('increment-59f-callback-graphs.yml', 35499135500, 'cancelled'),
    ('increment-59e-composite-reduction.yml', 35499133452, 'cancelled'),
)
WORKFLOWS = (LOCAL, *(row[0] for row in PRIOR))
CHECKS = [
    ['check-increment-59i-production-successor.py'],
    ['check-increment-59i-pr190-integration.py'],
    ['test-increment-59i-pr190-integration.py'],
    ['test-pr190-pr189-source-sync.py'],
    ['test-sequential-wire-source-review.py'],
    ['check-increment-59i-local-enable-source-review.py', '--self-test'],
    ['test-increment-59i-local-enable-source-review.py'],
    ['test-increment-59i-local-enable-successor.py'],
    ['test-increment-59i-local-enable-results.py'],
    ['check-increment-61-source-review.py'],
    ['check-native-source-preservation.py'],
    ['check-typed-native-source-overlay.py'],
    ['check-increment-59i-target-integration.py'],
    ['check-increment-62-wa08-source-overlay.py'],
    ['check-wa10-source-scope.py'],
    ['check-increment-59i-widening-source-review.py'],
    ['check-increment-59i-rollout-composition.py'],
    ['check-increment-60b-signedness-authority.py'],
    ['check-cdc-successor-source.py'],
    ['check-lane-when-source-scope.py'],
    ['test-increment-59i-inherited-audit-budgets.py'],
    ['test-increment-59i-regression-inventory.py'],
    ['test-increment-59i-production-successor.py'],
    ['test-increment-59i-continuation.py'],
    ['test-increment-59i-pr189-sync.py'],
]
PENDING = {'dispatch-intent', 'dispatch-requested', 'dispatch-uncertain'}


def require(ok, detail):
    if not ok:
        raise RuntimeError('59i failed-first: ' + detail)


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def git(root, *args):
    return subprocess.check_output(['git', '--literal-pathspecs', *args], cwd=root,
        stderr=subprocess.PIPE, timeout=120)


def write(path, value):
    """Atomic replacement plus file/directory fsync before external writes."""
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + '.tmp')
    with temporary.open('w') as output:
        json.dump(value, output, indent=2, sort_keys=True)
        output.write('\n'); output.flush(); os.fsync(output.fileno())
    temporary.replace(path)
    descriptor = os.open(path.parent, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def load_manifest(path):
    raw = path.read_bytes()
    require(digest(raw) == MANIFEST_SHA256, 'manifest bytes differ from prepared controller')
    value = json.loads(raw)
    require(value['schema'] == 1 and value['repository'] == REPOSITORY and value['pr'] == 177
        and value['candidate'] == CANDIDATE and value['source'] == SOURCE
        and value['target'] == TARGET and value['feature'] == FEATURE
        and value['recovery'] == RECOVERY and value['full_ci'] is False, 'manifest identity differs')
    require(tuple(row['workflow'] for row in value['workflows']) == WORKFLOWS
        and len(WORKFLOWS) == 16, 'reviewed 16-workflow inventory changed')
    require(value['historical_runs'] == [dict(workflow=n, run_id=i, conclusion=c,
        head=BASE, branch=FEATURE, run_attempt=1) for n, i, c in PRIOR], 'prior run inventory changed')
    return value


def clean_source(root, manifest):
    require(git(root, 'rev-parse', 'HEAD').decode().strip() == CANDIDATE, 'checkout is not exact candidate')
    require(git(root, 'rev-parse', 'HEAD^{tree}').decode().strip() == CANDIDATE_TREE, 'candidate tree differs')
    require(git(root, 'show', '-s', '--format=%P', CANDIDATE).decode().strip() == SOURCE,
        'candidate is not direct source seal')
    require(not git(root, 'status', '--porcelain', '--untracked-files=normal'), 'candidate checkout is dirty')
    git(root, 'merge-base', '--is-ancestor', TARGET, CANDIDATE)
    for row in manifest['workflows']:
        require(row['path'] == '.github/workflows/' + row['workflow'], 'invalid workflow path')
        require(digest(git(root, 'show', CANDIDATE + ':' + row['path'])) == row['workflow_sha256'],
            'candidate workflow differs: ' + row['workflow'])
        require(digest((root / row['path']).read_bytes()) == row['workflow_sha256'],
            'checkout workflow differs: ' + row['workflow'])
        require(row['inputs'] == ({'mode': 'checks'} if row['workflow'].startswith('cdc-') else {}),
            'unreviewed dispatch inputs')


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        return None


class Api:
    def __init__(self, token):
        self.token = token
        self.allowed_dispatch = {}

    def request(self, suffix, method='GET', payload=None):
        require(suffix.startswith('/') and not suffix.startswith('//') and '://' not in suffix,
            'API suffix escaped repository')
        if method == 'POST':
            match = re.fullmatch(r'/actions/workflows/([0-9]+)/dispatches', suffix)
            require(match is not None and int(match[1]) in self.allowed_dispatch, 'forbidden remote write')
            expected = {'ref': FEATURE}
            inputs = self.allowed_dispatch[int(match[1])]
            if inputs:
                expected['inputs'] = inputs
            require(payload == expected, 'dispatch ref or inputs differ')
        else:
            require(method == 'GET' and payload is None, 'only reads and allowlisted dispatch are supported')
        return urllib.request.Request('https://api.github.com/repos/' + REPOSITORY + suffix,
            method=method, data=None if payload is None else json.dumps(payload).encode(),
            headers={'Authorization': 'Bearer ' + self.token, 'Accept': 'application/vnd.github+json',
                'X-GitHub-Api-Version': '2022-11-28', 'Content-Type': 'application/json',
                'User-Agent': 'MorphHDL-59i-failed-first-controller'})

    def get(self, suffix, **query):
        if query:
            suffix += '?' + urllib.parse.urlencode(query)
        with urllib.request.urlopen(self.request(suffix), timeout=60) as response:
            return json.load(response)

    def post(self, suffix, payload):
        # One POST only. A lost response is never interpreted as a rejection.
        with urllib.request.urlopen(self.request(suffix, 'POST', payload), timeout=60) as response:
            require(response.status == 204, 'unexpected dispatch response; reconcile')

    def collection(self, suffix, key, **query):
        rows = []
        for page in range(1, 51):
            value = self.get(suffix, per_page=100, page=page, **query)
            require(isinstance(value.get(key), list), 'unexpected collection response')
            rows.extend(value[key])
            if len(value[key]) < 100 or len(rows) >= value.get('total_count', 10**9):
                return rows
        raise RuntimeError('bounded pagination exhausted: ' + suffix)

    def artifact_bytes(self, artifact):
        require(not artifact['expired'] and re.fullmatch(r'sha256:[0-9a-f]{64}', artifact['digest']),
            'artifact has expired or lacks API digest')
        request = self.request('/actions/artifacts/' + str(artifact['id']) + '/zip')
        opener = urllib.request.build_opener(NoRedirect)
        try:
            with opener.open(request, timeout=60) as response:
                raw = response.read()
        except urllib.error.HTTPError as error:
            require(error.code in (301, 302, 303, 307, 308), 'artifact download did not redirect normally')
            location = error.headers.get('Location', '')
            require(urllib.parse.urlsplit(location).scheme == 'https', 'non-HTTPS artifact redirect')
            # Follow the API-issued signed URL with NO GitHub Authorization header.
            with urllib.request.urlopen(location, timeout=60) as response:
                raw = response.read()
        require(len(raw) == artifact['size_in_bytes'] and 'sha256:' + digest(raw) == artifact['digest'],
            'download differs from API-bound artifact')
        return raw


def guard(api):
    pr = api.get('/pulls/177')
    feature = api.get('/git/ref/heads/' + FEATURE)
    target = api.get('/git/ref/heads/parameterized-verilog')
    require(pr['state'] == 'open' and not pr['merged'] and pr['draft']
        and pr['head']['sha'] == CANDIDATE and pr['head']['ref'] == FEATURE
        and pr['head']['repo']['full_name'] == REPOSITORY
        and pr['base']['ref'] == 'parameterized-verilog'
        and pr['base']['repo']['full_name'] == REPOSITORY
        and feature['object']['sha'] == CANDIDATE and target['object']['sha'] == TARGET,
        'PR177 draft state or exact feature/target identity changed')


def stage_run(api, gate):
    require(type(gate.get('run_id')) is int and gate['run_id'] > 0
        and type(gate.get('run_attempt')) is int and gate['run_attempt'] > 0
        and type(gate.get('artifact_id')) is int and gate['artifact_id'] > 0
        and isinstance(gate.get('controller_sha'), str)
        and re.fullmatch('[0-9a-f]{40}', gate['controller_sha'])
        and isinstance(gate.get('artifact_sha256'), str)
        and re.fullmatch('[0-9a-f]{64}', gate['artifact_sha256']), 'staging identifiers are not filled in')
    run = api.get('/actions/runs/' + str(gate['run_id']))
    require(run['status'] == 'completed' and run['conclusion'] == 'success'
        and run['head_sha'] == gate['controller_sha'] and run['head_branch'] == RECOVERY
        and run['repository']['full_name'] == REPOSITORY and run['event'] == 'push'
        and run['path'] == '.github/workflows/' + STAGE
        and run['run_attempt'] == gate['run_attempt'], 'source-staging run is not terminal successful exact attempt')
    return run


def zip_files(raw):
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), 'duplicate artifact members')
        require(all(not name.startswith('/') and '..' not in Path(name).parts for name in names),
            'unsafe artifact member')
        return {name: archive.read(name) for name in names if not name.endswith('/')}


def require_stage(api, gate, root, output):
    run = stage_run(api, gate)
    jobs = api.collection('/actions/runs/%s/attempts/%s/jobs' % (run['id'], run['run_attempt']), 'jobs')
    required_steps = {'Reconstruct sealed repair, verify historical runtime reference, and run source gates',
        'Await connector-created trees and stage exact history without ref updates'}
    require(len(jobs) == 1 and jobs[0]['name'] == 'stage' and jobs[0]['conclusion'] == 'success',
        'staging job did not pass')
    passed = {step['name'] for step in jobs[0]['steps'] if step['conclusion'] == 'success'}
    require(required_steps <= passed, 'staging source or commit phase was skipped')
    artifact = api.get('/actions/artifacts/' + str(gate['artifact_id']))
    require(artifact['name'] == 'increment-59i-local-enable-exact-commit-staging-' + str(run['run_attempt'])
        and artifact['workflow_run']['id'] == run['id']
        and artifact['workflow_run']['head_sha'] == gate['controller_sha']
        and artifact['digest'] == 'sha256:' + gate['artifact_sha256'], 'wrong staging artifact identity')
    files = zip_files(api.artifact_bytes(artifact))
    checks = json.loads(files['source-checks.json'])
    require(checks['source'] == SOURCE and checks['seal'] == CANDIDATE
        and checks['refs_updated'] is False and checks['full_ci'] is False
        and [item['command'] for item in checks['checks']] == CHECKS, 'source-stage evidence inventory differs')
    for index, item in enumerate(checks['checks'], 1):
        expected_name = '%02d-%s.log' % (index, item['command'][0])
        require(item['log'] == expected_name and item['returncode'] == 0
            and digest(files[expected_name]) == item['sha256'], 'failed/missing/changed source-stage log')
    commits = json.loads(files['commit-staging.json'])
    expected_commits = [dict(sha=ref,
        tree=git(root, 'rev-parse', ref + '^{tree}').decode().strip(),
        parents=git(root, 'show', '-s', '--format=%P', ref).decode().split())
        for ref in (CHECKPOINT, SOURCE, CANDIDATE)]
    require(commits['source'] == SOURCE and commits['seal'] == CANDIDATE
        and commits['commits'] == expected_commits and commits['refs_updated'] is False
        and commits['full_ci'] is False and commits['next_action'] == dict(
            branch_name=FEATURE, expected_old_sha=BASE, sha=CANDIDATE, force=False),
        'staged commit/source identities differ')
    receipt = dict(run_id=run['id'], attempt=run['run_attempt'], controller_sha=run['head_sha'],
        artifact_id=artifact['id'], artifact_digest=artifact['digest'], verified_source_checks=25,
        source=SOURCE, candidate=CANDIDATE, source_check_receipt_sha256=digest(files['source-checks.json']),
        commit_staging_sha256=digest(files['commit-staging.json']))
    write(output / 'verified-stage.json', receipt)
    return receipt


def prior_failures(api):
    for name, identity, conclusion in PRIOR:
        run = api.get('/actions/runs/' + str(identity))
        require(run['id'] == identity and run['head_sha'] == BASE and run['head_branch'] == FEATURE
            and run['repository']['full_name'] == REPOSITORY
            and run['path'] == '.github/workflows/' + name and run['run_attempt'] == 1
            and run['status'] == 'completed' and run['conclusion'] == conclusion,
            'diagnosed historical failure/cancellation changed: ' + name)


def same_head_runs(api, row):
    runs = api.collection('/actions/workflows/' + str(row['workflow_id']) + '/runs',
        'workflow_runs', head_sha=CANDIDATE)
    for run in runs:
        require(run['head_sha'] == CANDIDATE and run['head_branch'] == FEATURE
            and run['path'] == row['path'] and run['repository']['full_name'] == REPOSITORY
            and run['event'] in ('push', 'pull_request', 'workflow_dispatch'),
            'unexpected exact-head run identity')
    return sorted(runs, key=lambda run: (run['created_at'], run['id']), reverse=True)


def select_existing(runs):
    active = [run for run in runs if run['status'] != 'completed']
    require(len(active) <= 1, 'multiple active exact-head runs require reconciliation')
    if active:
        return active[0]
    # Never hide a newer same-head failure behind an older successful run.
    return runs[0] if runs else None


def executed_success(api, run, definition):
    jobs = api.collection('/actions/runs/%s/attempts/%s/jobs' % (run['id'], run['run_attempt']), 'jobs')
    actual = {job['name']: job for job in jobs}
    require(len(actual) == len(jobs) and set(actual) == set(definition['all_job_names']),
        'missing, unexpected or duplicate exact-head jobs: ' + definition['workflow'])
    for name in definition['required_job_names']:
        job = actual[name]
        require(job['status'] == 'completed' and job['conclusion'] == 'success'
            and len(job.get('steps', [])) > 2
            and any(step['conclusion'] == 'success' for step in job['steps']),
            'successful workflow did not execute required job: ' + name)
    require(all(job['conclusion'] in ('success', 'skipped') for job in jobs),
        'unsuccessful job hidden in successful workflow')
    return [dict(id=actual[name]['id'], name=name, conclusion='success')
        for name in definition['required_job_names']]


def journal_name(run_id, attempt):
    return '59i-repair-dispatch-journal-%s-%s-%s' % (CANDIDATE[:12], run_id, attempt)


def validate_journal(value):
    require(value['schema'] == 1 and value['repository'] == REPOSITORY
        and value['candidate'] == CANDIDATE and value['source'] == SOURCE
        and value['target'] == TARGET and value['manifest_sha256'] == MANIFEST_SHA256
        and value['full_ci'] is False and value['refs_updated'] is False
        and tuple(row['workflow'] for row in value['workflows']) == WORKFLOWS,
        'journal identity or workflow inventory differs')
    return value


def restore_previous(api, output):
    local = output / 'dispatch-journal.json'
    if local.exists():
        return validate_journal(json.loads(local.read_text()))
    run_id = os.getenv('GITHUB_RUN_ID')
    if not run_id:
        return None
    current = api.get('/actions/runs/' + run_id)
    require(current['path'] == '.github/workflows/' + CONTROLLER
        and current['head_branch'] == RECOVERY and current['head_sha'] == os.getenv('GITHUB_SHA')
        and current['run_attempt'] == int(os.getenv('GITHUB_RUN_ATTEMPT', '0')),
        'controller is not running from exact existing recovery branch')
    if current['run_attempt'] > 1:
        previous, attempt = current, current['run_attempt'] - 1
    else:
        rows = api.collection('/actions/workflows/' + CONTROLLER + '/runs', 'workflow_runs', branch=RECOVERY)
        earlier = [run for run in rows if (run['created_at'], run['id']) < (current['created_at'], current['id'])]
        if not earlier:
            return None
        previous = max(earlier, key=lambda run: (run['created_at'], run['id']))
        require(previous['status'] == 'completed', 'earlier controller is still active')
        attempt = previous['run_attempt']
    artifacts = api.collection('/actions/runs/' + str(previous['id']) + '/artifacts', 'artifacts')
    matches = [artifact for artifact in artifacts if artifact['name'] == journal_name(previous['id'], attempt)]
    if not matches:
        jobs = api.collection('/actions/runs/%s/attempts/%s/jobs' % (previous['id'], attempt), 'jobs')
        possible = [step for job in jobs for step in job.get('steps', [])
            if step['name'] == 'Run failed-first controller' and step['conclusion'] != 'skipped']
        require(not possible, 'prior controller started but its journal is missing; recover evidence, do not redispatch')
        return None
    require(len(matches) == 1, 'ambiguous prior journal artifacts')
    artifact = matches[0]
    files = zip_files(api.artifact_bytes(artifact))
    old = validate_journal(json.loads(files['dispatch-journal.json']))
    require(str(old['controller_run_id']) == str(previous['id'])
        and int(old['controller_attempt']) == attempt, 'journal controller attempt differs')
    write(output / 'restored-prior-journal.json', old)
    return old


def reconcile_record(record, runs, definition, api):
    if record['action'] == 'reuse-existing' and record.get('run_id'):
        require(any(run['id'] == record['run_id'] for run in runs),
            'previously bound run disappeared; recover evidence, do not redispatch')
    if record['action'] in PENDING:
        matches = [run for run in runs if run['id'] not in record['before_run_ids']]
        require(len(matches) <= 1, 'multiple runs appeared after intent; manually reconcile')
        if not matches:
            return None
        selected = matches[0]
    else:
        selected = select_existing(runs)
    if selected:
        record.update(action='reuse-existing', run_id=selected['id'], run_attempt=selected['run_attempt'],
            status=selected['status'], conclusion=selected['conclusion'], url=selected['html_url'])
        if selected['status'] == 'completed' and selected['conclusion'] == 'success':
            record['executed_required_jobs'] = executed_success(api, selected, definition)
    record['all_existing_run_ids'] = [run['id'] for run in runs]
    return selected


def controller(args):
    root, output = args.repo_root.resolve(), args.output.resolve()
    require(root not in output.parents and root != output, 'evidence must be outside qualification checkout')
    manifest = load_manifest(args.manifest)
    clean_source(root, manifest)
    token = os.getenv('GITHUB_TOKEN')
    require(bool(token), 'Actions GITHUB_TOKEN is required')
    require(os.getenv('GITHUB_REPOSITORY', REPOSITORY) == REPOSITORY, 'wrong Actions repository')
    if args.mode == 'dispatch':
        require(os.getenv('GITHUB_REF') == 'refs/heads/' + RECOVERY and os.getenv('GITHUB_RUN_ID'),
            'dispatch is supported only by the existing recovery-branch controller')
    api = Api(token)
    old = restore_previous(api, output)
    journal = old or dict(schema=1, repository=REPOSITORY, candidate=CANDIDATE, source=SOURCE,
        target=TARGET, manifest_sha256=MANIFEST_SHA256, full_ci=False, refs_updated=False,
        targeted_qualification_pass=False,
        workflows=[dict(workflow=name, action='unrequested') for name in WORKFLOWS])
    journal.update(controller_run_id=os.getenv('GITHUB_RUN_ID'),
        controller_attempt=os.getenv('GITHUB_RUN_ATTEMPT'), mode=args.mode)
    journal.pop('error', None)
    write(output / 'dispatch-journal.json', journal)
    try:
        guard(api)
        gate = json.loads(args.stage_gate.read_text())
        journal['stage_evidence'] = require_stage(api, gate, root, output)
        prior_failures(api)
        definitions = api.collection('/actions/workflows', 'workflows')
        planned = []
        for definition, record in zip(manifest['workflows'], journal['workflows']):
            found = [item for item in definitions if item['path'] == definition['path'] and item['state'] == 'active']
            require(len(found) == 1, 'missing/ambiguous active workflow: ' + definition['workflow'])
            row = dict(definition, workflow_id=found[0]['id'])
            record.update(path=row['path'], workflow_id=row['workflow_id'])
            api.allowed_dispatch[row['workflow_id']] = row['inputs']
            selected = reconcile_record(record, same_head_runs(api, row), row, api)
            planned.append((row, record, selected))
        # Inspect all 16 before the first write. Never broaden after a failure.
        write(output / 'dispatch-journal.json', journal)
        if args.mode == 'dispatch':
            require(not any(record['action'] in PENDING for _, record, _ in planned),
                'unresolved prior dispatch intent; reconcile until a run is visible')
            require(not any(run and run['status'] == 'completed' and run['conclusion'] != 'success'
                for _, _, run in planned), 'exact-candidate failure/cancellation requires diagnosis before dispatch')
        for row, record, previous in planned:
            guard(api)
            if args.mode != 'dispatch':
                continue
            # A run may have appeared after preflight. Reuse it without dispatch.
            runs = same_head_runs(api, row)
            selected = reconcile_record(record, runs, row, api)
            if selected:
                require(selected['status'] != 'completed' or selected['conclusion'] == 'success',
                    'exact-head failure appeared during dispatch; stop for diagnosis')
                write(output / 'dispatch-journal.json', journal)
                continue
            stage_run(api, gate)
            clean_source(root, manifest)
            guard(api)
            record.update(action='dispatch-intent', before_run_ids=[run['id'] for run in runs],
                requested_at=time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()))
            write(output / 'dispatch-journal.json', journal)
            payload = {'ref': FEATURE}
            if row['inputs']:
                payload['inputs'] = row['inputs']
            try:
                api.post('/actions/workflows/' + str(row['workflow_id']) + '/dispatches', payload)
            except BaseException:
                record['action'] = 'dispatch-uncertain'
                write(output / 'dispatch-journal.json', journal)
                raise
            record['action'] = 'dispatch-requested'
            write(output / 'dispatch-journal.json', journal)
            guard(api)
            print(json.dumps(dict(workflow=row['workflow'], action=record['action'])), flush=True)
        guard(api)
        clean_source(root, manifest)
        journal['targeted_qualification_pass'] = all(record.get('status') == 'completed'
            and record.get('conclusion') == 'success' and record.get('executed_required_jobs')
            for record in journal['workflows'])
        journal['controller_completed'] = True
        write(output / 'dispatch-journal.json', journal)
        print('Only targeted receipts recorded. No full CI, merge, or increment completion.', flush=True)
    except BaseException as error:
        journal['error'] = str(error)
        write(output / 'dispatch-journal.json', journal)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo-root', type=Path, required=True)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--stage-gate', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--mode', choices=['plan', 'dispatch', 'reconcile'], default='plan')
    controller(parser.parse_args())


if __name__ == '__main__':
    main()
