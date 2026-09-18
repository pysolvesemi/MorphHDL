#!/usr/bin/env python3
"""Stage the exact saved sharding repair and dispatch only its failed workflow.

The newer live target is observed and pinned, NOT included in this historical
failed-workflow candidate. Final integration with that target remains required.
Neither this controller nor its pinned stager writes feature or target refs.
"""
from __future__ import annotations
import argparse
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import urllib.error
import urllib.request

ORIGINAL = '.github/recovery/59i-failed-first-v2/stage.py'
ORIGINAL_BLOB = '9d087c67ccae7a5594c554a9e89637ce81059e50'
PARENT = '969af59a0378fca5b964d04d8f2af49b123dd804'
SOURCE = '8933bb4d8bed35b420f8b14c88636c2d513d7672'
SEAL = '37d1629f9b78c4d9cd6646abee9962fdd046e413'
SOURCE_TREE = '635964c7db181afd63fdb573ca8b90f25a07eecc'
SEAL_TREE = '8d3bd63ada3bdb4ad7db1ed5311260972922c7cb'
INTEGRATED_TARGET = '27af65abbee0d2334d6be7a6e4e2408b8af32fd9'
OBSERVED_TARGET = 'f5049ae2abfe5a47cd1fac3574ea08d630bd183f'
FEATURE = '14dc0d2bb7274d9e91b60f112619a78b237936ab'
BRANCH = 'recovery/increment-59i-audit-shards-37d1629f'
REPO = 'pysolvesemi/MorphHDL'
WORKFLOW = 358545231
FAILED_RUN = 35306242993
BUNDLE_SHA = 'c7c554dd51fc2a3058bc7d1320bd3334c49bb1c9b9a9c2e6f0f51c6ca5b13173'
PARTS = tuple('chunk-%02d.b64' % n for n in range(1, 6))
EXTRA_READS = frozenset(('/pulls/177', '/git/ref/heads/parameterized-verilog',
    '/git/ref/heads/agent/increment-59i-combined-reduction-closure'))
RUNS = '/actions/workflows/%d/runs?head_sha=%s&event=workflow_dispatch&per_page=100' % (WORKFLOW, SEAL)
DISPATCH = '/actions/workflows/%d/dispatches' % WORKFLOW


def require(ok, message):
    if not ok:
        raise RuntimeError('59i sharding transport: ' + message)


def checked_file(path):
    require(path.is_file() and not path.is_symlink(), 'missing regular input: ' + str(path))
    return path.read_bytes()


def bundle_bytes(payload):
    raw = base64.b64decode(b''.join(checked_file(payload / name) for name in PARTS), validate=True)
    require(hashlib.sha256(raw).hexdigest() == BUNDLE_SHA, 'transport checksum mismatch')
    return raw


def route_allowed(method, path, body=None):
    if method == 'POST':
        return path in ('/git/blobs', '/git/commits') or (path == DISPATCH and body == {'ref': BRANCH})
    if method != 'GET' or body is not None:
        return False
    if path in EXTRA_READS or path in (RUNS, '/actions/runs/' + str(FAILED_RUN), '/git/ref/heads/' + BRANCH):
        return True
    return path in ('/git/trees/' + SOURCE_TREE, '/git/trees/' + SEAL_TREE)


def configure(module):
    module.PARENT = PARENT
    module.TARGET = INTEGRATED_TARGET
    module.SOURCE = SOURCE
    module.SEAL = SEAL
    module.SOURCE_TREE = SOURCE_TREE
    module.SEAL_TREE = SEAL_TREE
    module.BRANCH = BRANCH
    module.BUNDLE_SHA = BUNDLE_SHA
    module.BUNDLE_REF = 'refs/heads/local/59i-audit-shards-sealed'
    module.SELECTED = {WORKFLOW: FAILED_RUN}
    module.CHECKS = list(module.CHECKS) + [
        'test-increment-59g-report-inventory.py',
        'test-increment-59i-source-io.py', 'test-increment-59i-audit-shards.py']
    original_api = module.api
    def api(method, path, body=None):
        require(route_allowed(method, path, body), 'operation outside exact failed-workflow allowlist')
        if path not in EXTRA_READS:
            return original_api(method, path, body)
        require(os.environ.get('GITHUB_REPOSITORY') == REPO, 'wrong repository')
        request = urllib.request.Request('https://api.github.com/repos/' + REPO + path,
            headers={'Authorization': 'Bearer ' + os.environ['GH_TOKEN'],
                'Accept': 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28'})
        with urllib.request.urlopen(request, timeout=120) as response:
            return json.load(response)
    module.api = api


def remote_preflight(m, root):
    pr = m.api('GET', '/pulls/177')
    require(pr['state'] == 'open' and not pr['merged'] and pr['head']['sha'] == FEATURE and
            pr['head']['ref'] == 'agent/increment-59i-combined-reduction-closure' and
            pr['head']['repo']['full_name'] == REPO and pr['base']['ref'] == 'parameterized-verilog',
            'PR identity changed')
    require(m.api('GET', '/git/ref/heads/agent/increment-59i-combined-reduction-closure')['object']['sha'] == FEATURE,
            'feature ref moved')
    require(m.api('GET', '/git/ref/heads/parameterized-verilog')['object']['sha'] == OBSERVED_TARGET,
            'live target moved beyond independently observed PR187 merge')
    require(m.git(root, 'rev-parse', OBSERVED_TARGET + '^').decode().strip() == INTEGRATED_TARGET,
            'observed target is not the recorded direct successor')
    failed = m.api('GET', '/actions/runs/' + str(FAILED_RUN))
    require(failed['workflow_id'] == WORKFLOW and failed['head_sha'] == PARENT and
            failed['status'] == 'completed' and failed['conclusion'] == 'cancelled',
            'original outer-timeout evidence changed')
    return dict(observed_target=OBSERVED_TARGET, integrated_target=INTEGRATED_TARGET,
                target_not_integrated=True, feature=FEATURE, final_head_qualification=False,
                original_run=FAILED_RUN, original_conclusion=failed['conclusion'])


def checked_receipt(m, out):
    receipt = json.loads((out / 'receipt.json').read_text())
    require(receipt['source'] == SOURCE and receipt['seal'] == SEAL and
            [c['script'] for c in receipt['checks']] == m.CHECKS, 'source receipt mismatch')
    for c in receipt['checks']:
        require(c['returncode'] == 0 and m.digest((out / (c['script'] + '.log')).read_bytes()) == c['log_sha256'],
                'altered source evidence')
    return receipt


def wait_for(m, path, predicate):
    deadline = time.monotonic() + 600
    while True:
        try:
            value = m.api('GET', path)
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise
            require(time.monotonic() < deadline, 'connector object/ref not available: ' + path)
            time.sleep(10)
        else:
            require(predicate(value), 'unexpected connector object/ref: ' + path)
            return value


def dispatch_once(m, root, out, receipt):
    snapshot = remote_preflight(m, root)
    require(m.api('GET', '/git/ref/heads/' + BRANCH)['object']['sha'] == SEAL, 'wrong validation ref')
    inventory = m.api('GET', RUNS)
    runs = inventory['workflow_runs']
    require(inventory['total_count'] == len(runs), 'truncated workflow inventory')
    require(all(r['head_sha'] == SEAL and r['workflow_id'] == WORKFLOW and
                r['event'] == 'workflow_dispatch' for r in runs), 'unexpected run inventory')
    dispatched = not runs
    if dispatched:
        remote_preflight(m, root)
        require(m.api('GET', '/git/ref/heads/' + BRANCH)['object']['sha'] == SEAL, 'validation ref moved')
        m.api('POST', DISPATCH, {'ref': BRANCH})
    receipt.update(branch=BRANCH, target_observation=snapshot, dispatches=[dict(
        workflow=WORKFLOW, original_failure=FAILED_RUN, dispatched=dispatched,
        existing_runs=[r['id'] for r in runs])], pr_updated=False, full_ci_dispatched=False)
    (out / 'publication.json').write_text(json.dumps(receipt, indent=2) + '\n')
    return receipt


def publish(m, root, out):
    receipt = checked_receipt(m, out)
    m.check_clean(root)
    receipt['target_observation'] = remote_preflight(m, root)
    for sha in (SOURCE_TREE, SEAL_TREE):
        wait_for(m, '/git/trees/' + sha, lambda v, expected=sha: v['sha'] == expected and not v.get('truncated', False))
    receipt['objects'] = [m.stage_commit(root, ref) for ref in (SOURCE, SEAL)]
    (out / 'commit-staging.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print('Exact commits staged; connector must create ' + BRANCH, flush=True)
    wait_for(m, '/git/ref/heads/' + BRANCH, lambda v: v['object']['sha'] == SEAL)
    dispatch_once(m, root, out, receipt)
    m.check_clean(root)
    print('Only failed inherited-source workflow dispatched or existing run retained; full CI not started', flush=True)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('phase', choices=('prepare', 'publish'))
    for name in ('repository', 'destination', 'output', 'bundle'):
        p.add_argument('--' + name, type=Path, required=True)
    a = p.parse_args()
    repository = a.repository.resolve()
    path = repository / ORIGINAL
    raw = checked_file(path)
    require(hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest() == ORIGINAL_BLOB,
            'original staging implementation changed')
    spec = importlib.util.spec_from_file_location('pinned_59i_sharding_stager', path)
    require(spec is not None and spec.loader is not None, 'invalid stager import')
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    configure(m)
    out = a.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    expected = bundle_bytes(Path(__file__).resolve().parent)
    if a.phase == 'prepare':
        require(not a.bundle.exists(), 'refusing to overwrite bundle')
        a.bundle.write_bytes(expected)
        (out / 'target-observation.json').write_text(json.dumps(remote_preflight(m, repository), indent=2) + '\n')
        m.prepare(repository, a.destination.resolve(), out, a.bundle.resolve())
    else:
        require(checked_file(a.bundle) == expected, 'prepared bundle changed')
        publish(m, a.destination.resolve(), out)


if __name__ == '__main__':
    main()
