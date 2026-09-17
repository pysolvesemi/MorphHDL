#!/usr/bin/env python3
"""Reproduce one bounded development repair and dispatch only the failed baseline.

No PR/target ref or source certificate is changed. The original baseline workflow
runs on an exact clean development commit. Full CI is deliberately not dispatched.
"""
from __future__ import annotations
import argparse
import base64
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.error
import urllib.request

REPO = 'pysolvesemi/MorphHDL'
PARENT = '14dc0d2bb7274d9e91b60f112619a78b237936ab'
TARGET = '27af65abbee0d2334d6be7a6e4e2408b8af32fd9'
SOURCE = '68e0edbeb5c5fda0069ca11b11ef99d4dae42d3a'
TREE = '5f3fe8b2621a32493171defd301d77786e053e54'
BLOB = 'e365e196e4eb14d81a388b457851be9de81320c7'
PATH = 'morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala'
SHA256 = 'b3bda09fe2a7a56c4126bdc27efdf2d1030b484465229c2c55e66243d44abbe4'
BRANCH = 'recovery/increment-59i-failed-baseline-68e0edbe'
FAILED_RUN = 35138263753
WORKFLOW = 332279971
MESSAGE = ('fix(59i): restore merged symbolic publication behavior [skip ci]\n\n'
 'Restore Increment 61 target native-legality discovery and authenticated product-domain width enclosures accidentally dropped during integration. Preserve all five 59i geometry/declaration-prefilter changes. Existing source certificates remain unchanged: this development checkpoint requires reviewed resealing before source-audit qualification.\n\n'
 'Run failed workflows only before full CI.\n')
EPOCH = 1789613452

def require(ok, message):
    if not ok:
        raise RuntimeError(message)

def git(root, *args, input=None):
    p = subprocess.run(['git', '--literal-pathspecs', *args], cwd=root, input=input,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120)
    require(p.returncode == 0, p.stderr.decode(errors='replace'))
    return p.stdout

def reconstruct(repository: Path, root: Path, out: Path):
    require(not root.exists(), 'Refusing to overwrite an existing checkout')
    git(repository, 'worktree', 'add', '--detach', str(root), PARENT)
    target = git(root, 'show', TARGET + ':' + PATH)
    differences = git(root, 'diff', TARGET, PARENT, '--', PATH).decode()
    parts = re.split(r'(?=^@@ )', differences, flags=re.M)
    retained = [h for h in parts[1:] if 'ExternalParameterizedNativeGeometry' in h
                or 'if (!current.contains(name))' in h]
    require(len(retained) == 5, 'The five reviewed 59i integration hunks changed')
    (root / PATH).write_bytes(target)
    git(root, 'apply', '--recount', '-', input=''.join(parts[:1] + retained).encode())
    raw = (root / PATH).read_bytes()
    require(hashlib.sha256(raw).hexdigest() == SHA256, 'Repaired source bytes differ')
    require(git(root, 'diff', '--name-only').decode().splitlines() == [PATH], 'Unexpected source change')
    git(root, 'add', '--', PATH)
    require(git(root, 'write-tree').decode().strip() == TREE, 'Repaired tree differs')
    identity = 'MorphHDL continuation <morphhdl-continuation@example.invalid> ' + str(EPOCH) + ' +0000'
    commit = ('tree ' + TREE + '\nparent ' + PARENT + '\nauthor ' + identity + '\ncommitter '
              + identity + '\n\n' + MESSAGE).encode()
    require(git(root, 'hash-object', '-t', 'commit', '-w', '--stdin', input=commit).decode().strip()
            == SOURCE, 'Development commit differs')
    git(root, 'reset', '--hard', SOURCE)
    require(not git(root, 'status', '--porcelain', '--untracked-files=all'), 'Dirty candidate')
    (out / 'repair.patch').write_bytes(git(root, 'diff', '--binary', '--full-index', PARENT, SOURCE))
    (out / 'commit.txt').write_bytes(commit)
    return raw

def api(method, path, payload=None):
    allowed = {
        ('GET', '/actions/runs/' + str(FAILED_RUN)),
        ('GET', '/git/ref/heads/' + BRANCH),
        ('GET', '/actions/workflows/' + str(WORKFLOW) + '/runs?head_sha=' + SOURCE + '&event=workflow_dispatch&per_page=100'),
        ('POST', '/git/blobs'), ('POST', '/git/trees'), ('POST', '/git/commits'),
        ('POST', '/git/refs'), ('POST', '/actions/workflows/' + str(WORKFLOW) + '/dispatches')}
    require((method, path) in allowed, 'Disallowed API operation')
    require(os.environ.get('GITHUB_REPOSITORY') == REPO, 'Wrong repository')
    request = urllib.request.Request('https://api.github.com/repos/' + REPO + path,
        method=method, data=None if payload is None else json.dumps(payload).encode(),
        headers={'Authorization': 'Bearer ' + os.environ['GH_TOKEN'],
                 'Accept': 'application/vnd.github+json', 'Content-Type': 'application/json',
                 'X-GitHub-Api-Version': '2022-11-28'})
    with urllib.request.urlopen(request, timeout=120) as result:
        body = result.read()
        return json.loads(body) if body else None

def publish_and_dispatch(raw, out):
    failure = api('GET', '/actions/runs/' + str(FAILED_RUN))
    require(failure['head_sha'] == PARENT and failure['conclusion'] == 'failure'
            and failure['workflow_id'] == WORKFLOW, 'Original baseline is not the expected failed run')
    require(api('POST', '/git/blobs', {'content': base64.b64encode(raw).decode(), 'encoding': 'base64'})['sha']
            == BLOB, 'Remote source blob differs')
    require(api('POST', '/git/trees', {'base_tree': '93594357906ec1f71485aff354b94bcaaaf3da67',
        'tree': [{'path': PATH, 'mode': '100644', 'type': 'blob', 'sha': BLOB}]})['sha']
            == TREE, 'Remote source tree differs')
    identity = {'name': 'MorphHDL continuation', 'email': 'morphhdl-continuation@example.invalid',
        'date': datetime.fromtimestamp(EPOCH, timezone.utc).isoformat().replace('+00:00', 'Z')}
    require(api('POST', '/git/commits', {'tree': TREE, 'parents': [PARENT], 'message': MESSAGE,
        'author': identity, 'committer': identity})['sha'] == SOURCE, 'Remote commit differs')
    try:
        ref = api('GET', '/git/ref/heads/' + BRANCH)
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise
        ref = api('POST', '/git/refs', {'ref': 'refs/heads/' + BRANCH, 'sha': SOURCE})
    require(ref['object']['sha'] == SOURCE, 'Existing validation branch has different work')
    runs = api('GET', '/actions/workflows/' + str(WORKFLOW) + '/runs?head_sha=' + SOURCE
               + '&event=workflow_dispatch&per_page=100')['workflow_runs']
    require(all(run['head_sha'] == SOURCE for run in runs), 'Unexpected workflow inventory')
    dispatched = not runs
    if dispatched:
        api('POST', '/actions/workflows/' + str(WORKFLOW) + '/dispatches', {'ref': BRANCH})
    (out / 'dispatch.json').write_text(json.dumps({'source': SOURCE, 'tree': TREE,
        'original_failed_run': FAILED_RUN, 'workflow_id': WORKFLOW, 'branch': BRANCH,
        'dispatched': dispatched, 'existing_run_ids': [run['id'] for run in runs],
        'full_ci_dispatched': False, 'pr_updated': False, 'sealed': False}, indent=2) + '\n')

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repository', type=Path, required=True)
    parser.add_argument('--destination', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--dispatch', action='store_true')
    args = parser.parse_args()
    out = args.output.resolve(); out.mkdir(parents=True, exist_ok=True)
    raw = reconstruct(args.repository.resolve(), args.destination.resolve(), out)
    if args.dispatch:
        publish_and_dispatch(raw, out)
    print('Exact baseline repair reproduced; full CI not dispatched')

if __name__ == '__main__':
    main()
