#!/usr/bin/env python3
"""Stage exact existing 59i repair history without ref updates or CI dispatch.

No remote ref mutation is implemented. Blobs and commits are staged with the
Actions token; the already authorized connector creates exact trees and makes
any separately authorized feature update after reviewing receipts.
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
BASE = 'b6f1fefb531ca4cb5aca266628dc29093f6bbafe'
# Current target is a transport/ref guard, independent of historical audit pins.
TARGET = 'bbae646ba43e6189c69feb308f8decb9b677b15f'
TARGET_TREE = 'c2f6e2abd588a131c5e6909173659935c62e77b7'
DOCS_CHECKPOINT = '771b02d9c5669f7e3a3cc3732b393dd083947ea6'
DOCS_CHECKPOINT_TREE = '8eab276de28956f977fe4089e00318ea924813f3'
DIAGNOSTIC_SOURCE = BASE
REFERENCE_RUN = 35491000802
REFERENCE_ATTEMPT = 1
REFERENCE_JOBS = {
    'Exact committed source and review': 106025794061,
    'Committed local-enable Scala 2.12.18': 106028583481,
    'Committed local-enable Scala 2.13.12': 106028583508,
    'Committed local-enable cross-Scala identity': 106042588737,
}
SOURCE_HELPER_SHA256 = 'aadb2209a95947e8d86bf7c6cb34075b4b1f376f8894b809d20a52f56ffa7dbe'
FEATURE = 'agent/increment-59i-combined-reduction-closure'
TARGET_BRANCH = 'parameterized-verilog'
WORKFLOW = 'increment-59i-local-enable-committed-head.yml'
CONTRACT = 'morphhdl/contracts/increment-59i-production-successor.json'
HELPER = 'morphhdl/scripts/check-increment-59i-production-successor.py'
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
    return {path for path in paths if '/src/main/' in path or '/src/test/' in path
        or path.endswith(('.scala', '.sbt')) or path in ('build.sc', '.gitmodules')
        or path.startswith('project/') or path in (
            'morphhdl/scripts/check-increment-59i-composite-local-enable.py',
            'morphhdl/scripts/check-increment-59i-local-enable-combined.py',
            'morphhdl/scripts/check-increment-59i-local-enable-results.py')} - set(runtime_gitlinks(root, ref))


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
    require(value['diagnostic'] == dict(source_sha=DIAGNOSTIC_SOURCE, run_id=REFERENCE_RUN,
        controller_sha=BASE, run_attempt=REFERENCE_ATTEMPT,
        qualification_scope='historical-runtime-reference-only',
        runtime_files=value['diagnostic']['runtime_files'],
        runtime_gitlinks=value['diagnostic']['runtime_gitlinks']), 'wrong committed reference identity')
    require(value['commits'] and value['commits'][-1]['sha'] == value['seal'], 'seal not final commit')
    require(value['commits'][-1]['parents'] == [value['source']], 'seal must directly follow source')
    require([item['sha'] for item in value['commits']] ==
        [DOCS_CHECKPOINT, value['source'], value['seal']], 'exact three-commit inventory required')
    require(value['commits'][0]['tree'] == DOCS_CHECKPOINT_TREE
        and value['commits'][0]['parents'] == [BASE, TARGET]
        and value['commits'][1]['parents'] == [DOCS_CHECKPOINT], 'repair checkpoint topology differs')
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
        self.reads = {
            '/git/ref/heads/' + FEATURE,
            '/git/ref/heads/' + TARGET_BRANCH,
            '/pulls/177',
            '/actions/runs/' + str(value['diagnostic']['run_id']),
            '/actions/runs/' + str(value['diagnostic']['run_id']) + '/jobs?filter=latest&per_page=100',
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
                and pr['head']['ref'] == FEATURE
                and pr['head']['repo']['full_name'] == REPO
                and pr['base']['ref'] == TARGET_BRANCH
                and pr['base']['repo']['full_name'] == REPO, 'PR identity or draft state changed')
        # PR base.sha can lag the branch after a target merge. The direct
        # target-ref GET above is authoritative and still pins exact TARGET.

    def diagnostic(self):
        # This is genuine historical qualification of BASE. It never qualifies
        # the new source/seal, whose source gates and final-head CI remain required.
        expected = self.value['diagnostic']
        run = self.api('GET', '/actions/runs/' + str(REFERENCE_RUN))
        require(run['id'] == REFERENCE_RUN and run['head_sha'] == BASE
                and run['run_attempt'] == REFERENCE_ATTEMPT
                and run['path'] == '.github/workflows/' + WORKFLOW
                and run['status'] == 'completed' and run['conclusion'] == 'success',
                'historical committed-head qualification identity/status differs')
        jobs = self.api('GET', '/actions/runs/' + str(REFERENCE_RUN) + '/jobs?filter=latest&per_page=100')
        require(jobs['total_count'] == len(jobs['jobs']) == len(REFERENCE_JOBS),
                'historical committed job set differs')
        require({item['name']: item['id'] for item in jobs['jobs']} == REFERENCE_JOBS,
                'historical committed job identities differ')
        require(all(item['status'] == 'completed' and item['conclusion'] == 'success'
                    for item in jobs['jobs']), 'historical committed job has not passed')
        return dict(run=run['id'], attempt=run['run_attempt'], controller_sha=run['head_sha'],
                    source_sha=expected['source_sha'], jobs=[item['id'] for item in jobs['jobs']],
                    qualification_scope='historical-runtime-reference-only',
                    new_head_qualification=False)

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
    require(git(root, 'rev-parse', TARGET + '^{tree}').decode().strip() == TARGET_TREE,
            'required immutable target history is unavailable')
    git(root, 'bundle', 'verify', str(archive))
    git(root, 'fetch', '--no-tags', str(archive), value['seal'])
    commits = git(root, 'rev-list', '--reverse', '--topo-order', value['seal'], '^' + BASE, '^' + TARGET).decode().splitlines()
    require(commits == [item['sha'] for item in value['commits']], 'history inventory differs')
    for item in value['commits']:
        require(git(root, 'cat-file', 'commit', item['sha']) == item['raw'].encode(), 'raw history differs')
    git(root, 'merge-base', '--is-ancestor', BASE, value['seal'])
    git(root, 'merge-base', '--is-ancestor', 'd76fbd5f84869ac56186b36f35dfc3c480a80cbb', value['seal'])
    git(root, 'merge-base', '--is-ancestor', value['diagnostic']['source_sha'], value['source'])
    require(git(root, 'rev-parse', DOCS_CHECKPOINT + '^{tree}').decode().strip() == DOCS_CHECKPOINT_TREE,
            'docs checkpoint tree differs')
    require(git(root, 'rev-list', '--parents', '-n', '1', DOCS_CHECKPOINT).decode().split()[1:]
            == [BASE, TARGET], 'docs checkpoint parents differ')
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
    raw_helper = (root / HELPER).read_bytes()
    normalized, count = re.subn(rb'^CONTRACT_SHA256 = "[^"\n]+"$',
        b'CONTRACT_SHA256 = "MANIFEST_HASH"', raw_helper, flags=re.M)
    require(re.fullmatch('[0-9a-f]{64}', SOURCE_HELPER_SHA256) is not None
            and count == 1 and digest(normalized) == SOURCE_HELPER_SHA256,
            'unreviewed current schema5 source verifier')
    require(json.loads((root / CONTRACT).read_bytes())['schema_version'] == 5,
            'wrong current source lifecycle')
    require_clean(root, value['seal'])
    write_json(out / 'reconstruction.json', dict(source=value['source'], seal=value['seal'],
               seal_tree=value['commits'][-1]['tree'], preserved_commits=commits, target=TARGET, target_tree=TARGET_TREE,
               preserved_target_history='Existing immutable target commits are prerequisites, never recreated.',
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
        print('SOURCE_GATE_START %02d/%02d %s' %
              (index + 1, len(CHECKS), ' '.join(command)), flush=True)
        started = time.monotonic()
        with (out / name).open('wb') as log:
            result = subprocess.run([sys.executable, '-B', 'morphhdl/scripts/' + command[0], *command[1:]],
                cwd=root, env=dict(os.environ, PYTHONDONTWRITEBYTECODE='1'),
                stdout=log, stderr=subprocess.STDOUT,
                timeout=None if command[0] in EXISTING_BOUNDED_ROUTERS else 1200)
        receipt['checks'].append(dict(command=command, returncode=result.returncode, log=name,
            sha256=digest((out / name).read_bytes()), seconds=round(time.monotonic() - started, 3)))
        write_json(out / 'source-checks.json', receipt)
        print('SOURCE_GATE_END %02d rc=%s seconds=%.3f' %
              (index + 1, result.returncode, time.monotonic() - started), flush=True)
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase', choices=['reconstruct', 'prepare', 'commits'])
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


if __name__ == '__main__':
    main()
