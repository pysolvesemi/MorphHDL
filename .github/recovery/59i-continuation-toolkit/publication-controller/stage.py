#!/usr/bin/env python3
"""Stage exact existing 59i history and dispatch only its local-enable gate.

No remote ref mutation is implemented. Blobs and commits are staged with the
Actions token; the already authorized connector creates exact trees and makes
one non-force update of the existing feature ref after reviewing receipts.
"""
from __future__ import annotations
import argparse
import base64
from datetime import datetime, timedelta, timezone
import hashlib
import json
import lzma
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request

REPO = 'pysolvesemi/MorphHDL'
BASE = '90b7fc8f13f2c53dbb6f7f8ab51f4e43cd486be6'
TARGET = 'e0e9f1d7089d3aa513677a2b94c63eb4a7a7791d'
FEATURE = 'agent/increment-59i-combined-reduction-closure'
TARGET_BRANCH = 'parameterized-verilog'
WORKFLOW = 'increment-59i-local-enable-committed-head.yml'
CONTRACT = 'morphhdl/contracts/increment-59i-production-successor.json'
HELPER = 'morphhdl/scripts/check-increment-59i-production-successor.py'
CHECKS = [
    ['check-increment-59i-production-successor.py'],
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
# These existing test routers already apply their authenticated 3600-second
# subprocess limits. Do not add a shorter aggregate 1200-second cut-off around
# them or alter their individual budgets. The Actions job remains bounded.
EXISTING_BOUNDED_ROUTERS = {
    'test-increment-59i-continuation.py',
    'test-increment-59i-pr189-sync.py',
    'test-increment-59i-inherited-audit-budgets.py',
}


def require(ok, why):
    if not ok:
        raise RuntimeError('59i exact publication: ' + why)


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + '\n')


def git(root, *args, input=None):
    result = subprocess.run(['git', '--literal-pathspecs', *args], cwd=root,
                            input=input, capture_output=True, timeout=180)
    require(result.returncode == 0, 'git ' + str(args) + ': ' + result.stderr.decode(errors='replace'))
    return result.stdout


def runtime_paths(root, ref):
    paths = git(root, 'ls-tree', '-r', '--name-only', ref).decode().splitlines()
    return {path for path in paths if path.endswith('.scala') or path in ('build.sbt', '.gitmodules')
        or path.startswith('project/') or path in (
            'morphhdl/scripts/check-increment-59i-composite-local-enable.py',
            'morphhdl/scripts/check-increment-59i-local-enable-combined.py',
            'morphhdl/scripts/check-increment-59i-local-enable-results.py')}


def runtime_gitlinks(root, ref):
    result = {}
    for row in git(root, 'ls-tree', '-rz', ref).split(b'\0'):
        if row:
            metadata, path = row.split(b'\t', 1)
            mode, kind, sha = metadata.decode().split()
            if mode == '160000':
                require(kind == 'commit', 'unexpected gitlink type')
                result[path.decode()] = sha
    return result


def commit_body(raw):
    """Preserve author, committer, raw timezone, message, and ordered parents."""
    header, message = raw.decode('utf-8').split('\n\n', 1)
    body = dict(message=message, parents=[])
    seen = []
    for row in header.splitlines():
        key, value = row.split(' ', 1)
        seen.append(key)
        if key == 'tree':
            require(key not in body, 'duplicate tree')
            require(re.fullmatch('[0-9a-f]{40}', value), 'invalid tree SHA')
            body[key] = value
        elif key == 'parent':
            require(re.fullmatch('[0-9a-f]{40}', value), 'invalid parent SHA')
            body['parents'].append(value)
        else:
            require(key in ('author', 'committer') and key not in body, 'unsupported commit metadata')
            match = re.fullmatch(r'(.*) <([^<>]+)> ([0-9]+) ([+-])([0-9]{2})([0-9]{2})', value)
            require(match is not None, 'invalid raw identity')
            hours, minutes = int(match[5]), int(match[6])
            require(hours < 24 and minutes < 60, 'invalid timezone offset')
            offset = (hours * 60 + minutes) * (-1 if match[4] == '-' else 1)
            # Do not convert to UTC: timezone bytes participate in the Git SHA.
            date = datetime.fromtimestamp(int(match[3]), timezone(timedelta(minutes=offset))).isoformat()
            body[key] = dict(name=match[1], email=match[2], date=date)
    require(seen == ['tree'] + ['parent'] * len(body['parents']) + ['author', 'committer'],
            'noncanonical commit header order')
    require(body['parents'] and '[skip ci]' in message, 'non-root skip-ci commit required')
    return body


def load(here):
    path = here / 'payload.json'
    require(path.is_file() and not path.is_symlink(), 'regular payload required')
    value = json.loads(path.read_text())
    require(value['schema'] == 1 and value['repository'] == REPO and value['base'] == BASE
            and value['target'] == TARGET and value['feature'] == FEATURE
            and value['workflow'] == WORKFLOW, 'wrong immutable publication scope')
    require(value['checks'] == CHECKS, 'source gate inventory changed')
    require(value['commits'] and value['commits'][-1]['sha'] == value['seal'], 'seal not final commit')
    require(value['commits'][-1]['parents'] == [value['source']], 'seal must directly follow source')
    for item in value['commits']:
        raw = item['raw'].encode()
        expected = hashlib.sha1(b'commit ' + str(len(raw)).encode() + b'\0' + raw).hexdigest()
        body = commit_body(raw)
        require(expected == item['sha'] and body['tree'] == item['tree']
                and body['parents'] == item['parents'], 'exact raw commit mismatch')
    return value


class Remote:
    def __init__(self, value):
        self.value = value
        self.runs = '/actions/workflows/' + WORKFLOW + '/runs?head_sha=' + value['seal'] + '&event=workflow_dispatch&per_page=100'
        self.dispatch = '/actions/workflows/' + WORKFLOW + '/dispatches'
        self.reads = {
            '/git/ref/heads/' + FEATURE,
            '/git/ref/heads/' + TARGET_BRANCH,
            '/pulls/177',
            '/actions/runs/' + str(value['diagnostic']['run_id']),
            '/actions/runs/' + str(value['diagnostic']['run_id']) + '/jobs?filter=latest&per_page=100',
            self.runs,
        } | {'/git/trees/' + item['tree'] for item in value['commits']}
        self.blobs = {item['sha'] for item in value['blobs']}
        self.commit_bodies = [commit_body(item['raw'].encode()) for item in value['commits']]

    def api(self, method, path, body=None):
        allowed = method == 'GET' and path in self.reads and body is None
        if method == 'POST' and path == '/git/blobs':
            allowed = isinstance(body, dict) and set(body) == {'content', 'encoding'} and body['encoding'] == 'base64'
            if allowed:
                raw = base64.b64decode(body['content'], validate=True)
                allowed = hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest() in self.blobs
        elif method == 'POST' and path == '/git/commits':
            allowed = body in self.commit_bodies
        elif method == 'POST' and path == self.dispatch:
            allowed = body == {'ref': FEATURE}
        require(allowed, 'remote operation outside exact allowlist')
        require(os.environ.get('GITHUB_REPOSITORY') == REPO, 'wrong authorized repository')
        request = urllib.request.Request('https://api.github.com/repos/' + REPO + path,
            method=method, data=None if body is None else json.dumps(body).encode(), headers={
                'Authorization': 'Bearer ' + os.environ['GH_TOKEN'],
                'Accept': 'application/vnd.github+json', 'Content-Type': 'application/json',
                'X-GitHub-Api-Version': '2022-11-28'})
        with urllib.request.urlopen(request, timeout=120) as response:
            raw = response.read()
            return json.loads(raw) if raw else None

    def identity(self, head):
        require(self.api('GET', '/git/ref/heads/' + FEATURE)['object']['sha'] == head, 'feature changed')
        require(self.api('GET', '/git/ref/heads/' + TARGET_BRANCH)['object']['sha'] == TARGET, 'target changed')
        pr = self.api('GET', '/pulls/177')
        require(pr['state'] == 'open' and pr['draft'] and not pr['merged'] and pr['head']['sha'] == head
                and pr['head']['ref'] == FEATURE and pr['base']['sha'] == TARGET
                and pr['base']['ref'] == TARGET_BRANCH, 'PR identity or draft state changed')

    def diagnostic(self):
        expected = self.value['diagnostic']
        run = self.api('GET', '/actions/runs/' + str(expected['run_id']))
        require(run['id'] == expected['run_id'] and run['head_sha'] == expected['controller_sha']
                and run['path'] == '.github/workflows/increment-59i-local-enable-development-probe.yml'
                and run['status'] == 'completed' and run['conclusion'] == 'success',
                'expanded runtime diagnostic has not passed')
        jobs = self.api('GET', '/actions/runs/' + str(expected['run_id']) + '/jobs?filter=latest&per_page=100')
        require(jobs['total_count'] == len(jobs['jobs']) == 2, 'diagnostic job set differs')
        require(sorted(item['name'] for item in jobs['jobs']) ==
                ['Development only Scala 2.12.18', 'Development only Scala 2.13.12'], 'diagnostic Scala lanes differ')
        require(all(item['status'] == 'completed' and item['conclusion'] == 'success' for item in jobs['jobs']),
                'diagnostic lane has not passed')
        return dict(run=run['id'], attempt=run['run_attempt'], controller_sha=run['head_sha'],
                    source_sha=expected['source_sha'], jobs=[item['id'] for item in jobs['jobs']])

    def wait_tree(self, sha):
        deadline = time.monotonic() + 3600
        while True:
            self.identity(BASE)
            try:
                value = self.api('GET', '/git/trees/' + sha)
            except urllib.error.HTTPError as error:
                if error.code != 404:
                    raise
                require(time.monotonic() < deadline, 'connector tree not available: ' + sha)
                time.sleep(10)
            else:
                require(value['sha'] == sha, 'remote tree mismatch')
                return


def require_clean(root, seal):
    require(git(root, 'rev-parse', 'HEAD').decode().strip() == seal, 'checkout head changed')
    require(not git(root, 'status', '--porcelain', '--untracked-files=all'), 'source is dirty')


def reconstruct(repository, root, out, here, value):
    require(not root.exists(), 'destination already exists')
    parts = [here / name for name in value['parts']]
    require(all(part.is_file() and not part.is_symlink() for part in parts), 'regular bundle parts required')
    compressed = base64.b64decode(b''.join(part.read_bytes() for part in parts), validate=True)
    require(digest(compressed) == value['compressed_sha256'], 'compressed bundle hash differs')
    bundle = lzma.decompress(compressed)
    require(digest(bundle) == value['bundle_sha256'], 'bundle hash differs')
    archive = out / 'exact-source.bundle'
    archive.write_bytes(bundle)
    git(repository, 'worktree', 'add', '--detach', str(root), BASE)
    with (out / 'qualified-parent-review.log').open('wb') as log:
        result = subprocess.run([sys.executable, '-B', HELPER], cwd=root, stdout=log,
                                stderr=subprocess.STDOUT, timeout=1200)
    require(result.returncode == 0, 'qualified parent source check failed')
    git(root, 'bundle', 'verify', str(archive))
    git(root, 'fetch', '--no-tags', str(archive), value['seal'])
    commits = git(root, 'rev-list', '--reverse', '--topo-order', value['seal'], '^' + BASE).decode().splitlines()
    require(commits == [item['sha'] for item in value['commits']], 'history inventory differs')
    for item in value['commits']:
        require(git(root, 'cat-file', 'commit', item['sha']) == item['raw'].encode(), 'raw history differs')
    git(root, 'merge-base', '--is-ancestor', BASE, value['seal'])
    git(root, 'merge-base', '--is-ancestor', 'd76fbd5f84869ac56186b36f35dfc3c480a80cbb', value['seal'])
    git(root, 'merge-base', '--is-ancestor', value['diagnostic']['source_sha'], value['source'])
    require(git(root, 'diff', '--name-only', value['source'], value['seal']).decode().splitlines()
            == sorted([CONTRACT, HELPER]), 'seal delta is not exactly two files')
    expected_paths = set(value['diagnostic']['runtime_files'])
    require(runtime_paths(root, value['seal']) == expected_paths
            == runtime_paths(root, value['diagnostic']['source_sha']),
            'runtime file path set changed after diagnostic')
    require(runtime_gitlinks(root, value['seal']) == value['diagnostic']['runtime_gitlinks']
            == runtime_gitlinks(root, value['diagnostic']['source_sha']),
            'runtime gitlinks changed after diagnostic')
    for path, expected in value['diagnostic']['runtime_files'].items():
        require(digest(git(root, 'show', value['diagnostic']['source_sha'] + ':' + path)) == expected
                and digest(git(root, 'show', value['seal'] + ':' + path)) == expected,
                'qualified diagnostic runtime changed: ' + path)
    git(root, 'reset', '--hard', value['seal'])
    require_clean(root, value['seal'])
    write_json(out / 'reconstruction.json', dict(source=value['source'], seal=value['seal'],
               seal_tree=value['commits'][-1]['tree'], preserved_commits=commits,
               refs_updated=False, full_ci=False))


def validate_receipt(root, out, value):
    require_clean(root, value['seal'])
    receipt = json.loads((out / 'source-checks.json').read_text())
    require(receipt['seal'] == value['seal'] and [item['command'] for item in receipt['checks']] == CHECKS,
            'source receipt inventory differs')
    for item in receipt['checks']:
        require(item['returncode'] == 0 and digest((out / item['log']).read_bytes()) == item['sha256'],
                'source receipt failed or changed')
    return receipt


def prepare(root, out, value, remote):
    remote.identity(BASE)
    diagnostic = remote.diagnostic()
    # The offline reconstruction path stays network-free. Actual preparation
    # initializes the committed gitlinks before every current-source gate.
    with (out / 'submodule-init.log').open('wb') as log:
        result = subprocess.run(['git', 'submodule', 'update', '--init', '--recursive'],
            cwd=root, stdout=log, stderr=subprocess.STDOUT, timeout=1200)
    require(result.returncode == 0, 'pinned submodule initialization failed')
    submodule_status = git(root, 'submodule', 'status', '--recursive').decode().splitlines()
    require(all(line.startswith(' ') for line in submodule_status), 'submodule is not at its committed revision')
    (out / 'submodule-status.txt').write_text('\n'.join(submodule_status) + '\n')
    require_clean(root, value['seal'])
    receipt = dict(source=value['source'], seal=value['seal'], checks=[], diagnostic=diagnostic,
                   refs_updated=False, full_ci=False)
    for index, command in enumerate(CHECKS):
        name = '%02d-%s.log' % (index + 1, command[0])
        started = time.monotonic()
        with (out / name).open('wb') as log:
            result = subprocess.run([sys.executable, '-B', 'morphhdl/scripts/' + command[0], *command[1:]],
                cwd=root, env=dict(os.environ, PYTHONDONTWRITEBYTECODE='1'),
                stdout=log, stderr=subprocess.STDOUT,
                timeout=None if command[0] in EXISTING_BOUNDED_ROUTERS else 1200)
        receipt['checks'].append(dict(command=command, returncode=result.returncode, log=name,
            sha256=digest((out / name).read_bytes()), seconds=round(time.monotonic() - started, 3)))
        write_json(out / 'source-checks.json', receipt)
        require(result.returncode == 0, 'source check failed: ' + command[0])
        require_clean(root, value['seal'])
    remote.identity(BASE)
    remote.diagnostic()
    for item in value['blobs']:
        raw = git(root, 'cat-file', 'blob', item['sha'])
        require(digest(raw) == item['sha256'], 'local blob hash changed')
        uploaded = remote.api('POST', '/git/blobs', dict(content=base64.b64encode(raw).decode(), encoding='base64'))
        require(uploaded['sha'] == item['sha'], 'uploaded blob SHA differs')
    write_json(out / 'connector-tree-requests.json', value['tree_requests'])
    print('SOURCE_GATES_PASSED: create each exact connector tree in the listed order; do not update feature yet.', flush=True)


def stage_commits(root, out, value, remote):
    validate_receipt(root, out, value)
    remote.identity(BASE)
    remote.diagnostic()
    receipts = []
    for item in value['commits']:
        remote.wait_tree(item['tree'])
        actual = remote.api('POST', '/git/commits', commit_body(item['raw'].encode()))
        require(actual['sha'] == item['sha'] and actual['tree']['sha'] == item['tree']
                and [parent['sha'] for parent in actual['parents']] == item['parents'], 'remote exact commit differs')
        receipts.append(dict(sha=actual['sha'], tree=actual['tree']['sha'], parents=item['parents']))
    remote.identity(BASE)
    write_json(out / 'commit-staging.json', dict(source=value['source'], seal=value['seal'],
        commits=receipts, refs_updated=False, full_ci=False,
        next_action=dict(branch_name=FEATURE, expected_old_sha=BASE, sha=value['seal'], force=False)))
    print('EXACT_COMMITS_STAGED: connector may now fast-forward existing feature from ' + BASE + ' to ' + value['seal'], flush=True)


def dispatch(root, out, value, remote):
    validate_receipt(root, out, value)
    receipt = json.loads((out / 'commit-staging.json').read_text())
    require(receipt['seal'] == value['seal'] and [item['sha'] for item in receipt['commits']]
            == [item['sha'] for item in value['commits']], 'commit receipt mismatch')
    deadline = time.monotonic() + 3600
    while True:
        head = remote.api('GET', '/git/ref/heads/' + FEATURE)['object']['sha']
        require(head in (BASE, value['seal']), 'feature moved to unexpected source')
        if head == value['seal']:
            break
        remote.identity(BASE)
        require(time.monotonic() < deadline, 'connector feature update not available')
        time.sleep(10)
    remote.identity(value['seal'])
    remote.diagnostic()
    runs = remote.api('GET', remote.runs)
    require(runs['total_count'] == len(runs['workflow_runs']), 'truncated same-head run inventory')
    require(all(item['head_sha'] == value['seal'] and item['event'] == 'workflow_dispatch'
                and item['path'] == '.github/workflows/' + WORKFLOW for item in runs['workflow_runs']), 'foreign run inventory')
    # Any existing attempt is retained. Failed attempts require explicit diagnosis,
    # never an automatic rerun disguised as first dispatch.
    if not runs['workflow_runs']:
        remote.api('POST', remote.dispatch, {'ref': FEATURE})
    write_json(out / 'failed-first-dispatch.json', dict(source=value['source'], seal=value['seal'],
        workflow=WORKFLOW, branch=FEATURE, dispatched=not runs['workflow_runs'],
        existing_runs=[item['id'] for item in runs['workflow_runs']], full_ci=False,
        refs_updated_by_controller=False))
    require_clean(root, value['seal'])
    print('ONLY_LOCAL_ENABLE_QUALIFICATION_DISPATCHED; full CI remains gated on its success.', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase', choices=['reconstruct', 'prepare', 'commits', 'dispatch'])
    for name in ('repository', 'destination', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    root, out = args.destination.resolve(), args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    here = Path(__file__).resolve().parent
    value = load(here)
    remote = Remote(value)
    if args.phase in ('reconstruct', 'prepare'):
        reconstruct(args.repository.resolve(), root, out, here, value)
        if args.phase == 'prepare':
            prepare(root, out, value, remote)
    elif args.phase == 'commits':
        stage_commits(root, out, value, remote)
    else:
        dispatch(root, out, value, remote)


if __name__ == '__main__':
    main()
