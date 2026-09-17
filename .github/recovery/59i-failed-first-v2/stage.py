#!/usr/bin/env python3
"""Stage an exact reviewed repair and dispatch only previously failed workflows.

No feature/target refs are changed. Full CI is never dispatched by this tool.
No check results or checkout authorization are inherited from old executions.
"""
from __future__ import annotations
import argparse, base64, hashlib, json, os, re, subprocess, sys, time
import urllib.request, urllib.error
from datetime import datetime, timezone
from pathlib import Path

REPO = 'pysolvesemi/MorphHDL'
PARENT = '14dc0d2bb7274d9e91b60f112619a78b237936ab'
TARGET = '27af65abbee0d2334d6be7a6e4e2408b8af32fd9'
SOURCE = 'c7d2bd016b9643f90d76c40c4ac234835eb3b57b'
SEAL = 'ddf61ef25f927d646027cebbcaca6d724ad8a5fa'
SOURCE_TREE = '15bb6fb0bebd19d2b318cb2fd4c60da189e8e8cc'
SEAL_TREE = '3799df87efb1f09cde3ace13fd5a0a0d6cf661be'
BRANCH = 'recovery/increment-59i-failed-first-ddf61ef2'
BUNDLE_SHA = '2e5c4a7d11dcb55436985b267fd4c5044fd85f10812eb7d5a9807379025a13bb'
BUNDLE_REF = 'refs/heads/local/59i-failed-first-sealed-v2'
SELECTED = {332279971: 35138263753, 332476711: 35138263721,
            353947848: 35138263771, 357674356: 35138263755,
            351947632: 35138263930}
CHECKS = ['check-increment-59i-production-successor.py',
    'check-increment-61-source-review.py', 'check-native-source-preservation.py',
    'check-typed-native-source-overlay.py', 'check-increment-59i-target-integration.py',
    'check-increment-62-wa08-source-overlay.py', 'check-wa10-source-scope.py',
    'check-increment-59i-widening-source-review.py',
    'check-increment-59i-rollout-composition.py',
    'check-increment-60b-signedness-authority.py',
    'test-increment-59i-continuation.py']

def require(ok, why):
    if not ok:
        raise RuntimeError('59i failed-only staging: ' + why)

def digest(raw):
    return hashlib.sha256(raw).hexdigest()

def git(root, *args, input=None):
    result = subprocess.run(['git', '--literal-pathspecs', *args], cwd=root,
        input=input, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=180)
    require(result.returncode == 0, result.stderr.decode(errors='replace'))
    return result.stdout

def api(method, path, body=None):
    fixed = {('POST', '/git/blobs'), ('POST', '/git/commits'),
             ('GET', '/git/ref/heads/' + BRANCH)}
    allowed = (method, path) in fixed
    allowed |= method == 'GET' and bool(re.fullmatch(r'/git/trees/[0-9a-f]{40}', path))
    allowed |= method == 'GET' and path in {'/actions/runs/' + str(n) for n in SELECTED.values()}
    for workflow in SELECTED:
        allowed |= (method, path) in {
            ('POST', '/actions/workflows/' + str(workflow) + '/dispatches'),
            ('GET', '/actions/workflows/' + str(workflow) + '/runs?head_sha=' + SEAL + '&event=workflow_dispatch&per_page=100')}
    require(allowed and os.environ.get('GITHUB_REPOSITORY') == REPO, 'unauthorized operation')
    if method == 'POST' and path.endswith('/dispatches'):
        require(body == {'ref': BRANCH}, 'unexpected workflow ref')
    request = urllib.request.Request('https://api.github.com/repos/' + REPO + path,
        method=method, data=None if body is None else json.dumps(body).encode(),
        headers={'Authorization': 'Bearer ' + os.environ['GH_TOKEN'],
                 'Accept': 'application/vnd.github+json', 'Content-Type': 'application/json',
                 'X-GitHub-Api-Version': '2022-11-28'})
    with urllib.request.urlopen(request, timeout=120) as response:
        raw = response.read()
        return json.loads(raw) if raw else None

def tree(root, ref):
    result = {}
    for line in git(root, 'ls-tree', '-rz', ref).split(b'\0'):
        if line:
            meta, path = line.split(b'\t', 1)
            result[path.decode()] = meta.decode().split()
    return result

def request_tree(root, before, after, expected, uploaded):
    a, b = tree(root, before), tree(root, after)
    entries = []
    for path in sorted(set(a) | set(b)):
        if a.get(path) == b.get(path):
            continue
        require(b.get(path) is not None, 'unexpected removed file')
        mode, kind, sha = b[path]
        require(kind == 'blob' and mode in ('100644', '100755'), 'unexpected changed object')
        if sha not in uploaded:
            raw = git(root, 'cat-file', 'blob', sha)
            value = api('POST', '/git/blobs', {'content': base64.b64encode(raw).decode(), 'encoding': 'base64'})
            require(value['sha'] == sha, 'remote blob identity mismatch')
            uploaded.add(sha)
        entries.append({'path': path, 'mode': mode, 'type': kind, 'sha': sha})
    return {'base_tree': git(root, 'rev-parse', before + '^{tree}').decode().strip(),
            'expected_tree': expected, 'tree': entries}

def stage_commit(root, ref):
    raw = git(root, 'cat-file', 'commit', ref).decode()
    header, message = raw.split('\n\n', 1)
    body = {'message': message, 'parents': []}
    for line in header.splitlines():
        key, value = line.split(' ', 1)
        if key == 'tree':
            body[key] = value
        elif key == 'parent':
            body['parents'].append(value)
        else:
            require(key in ('author', 'committer'), 'unsupported commit header')
            m = re.fullmatch(r'(.*) <([^<>]+)> ([0-9]+) \+0000', value)
            require(m is not None, 'unsupported commit identity')
            body[key] = {'name': m[1], 'email': m[2],
                'date': datetime.fromtimestamp(int(m[3]), timezone.utc).isoformat().replace('+00:00', 'Z')}
    actual = api('POST', '/git/commits', body)
    require(actual['sha'] == ref, 'remote raw commit identity mismatch')
    return {'sha': ref, 'tree': actual['tree']['sha'], 'parents': [p['sha'] for p in actual['parents']]}

def check_clean(root):
    require(git(root, 'rev-parse', 'HEAD').decode().strip() == SEAL, 'changed checkout head')
    require(not git(root, 'status', '--porcelain', '--untracked-files=all'), 'changed checkout contents')

def prepare(repository, root, out, bundle):
    require(digest(bundle.read_bytes()) == BUNDLE_SHA, 'transport bundle checksum mismatch')
    require(not root.exists(), 'refusing to overwrite checkout')
    git(repository, 'bundle', 'verify', str(bundle))
    git(repository, 'fetch', str(bundle), BUNDLE_REF)
    git(repository, 'worktree', 'add', '--detach', str(root), SEAL)
    require(git(root, 'rev-parse', 'HEAD^{tree}').decode().strip() == SEAL_TREE, 'wrong seal tree')
    require(git(root, 'rev-parse', 'HEAD^').decode().strip() == SOURCE, 'wrong source parent')
    require(git(root, 'rev-parse', SOURCE + '^{tree}').decode().strip() == SOURCE_TREE, 'wrong source tree')
    require(git(root, 'rev-list', '--parents', '-n', '1', SOURCE).decode().split() == [SOURCE, PARENT, TARGET], 'wrong ordered parents')
    check_clean(root)
    receipt = {'source': SOURCE, 'seal': SEAL, 'checks': [], 'pr_updated': False, 'full_ci_dispatched': False}
    for script in CHECKS:
        started = time.monotonic()
        with (out / (script + '.log')).open('wb') as log:
            result = subprocess.run([sys.executable, '-B', 'morphhdl/scripts/' + script], cwd=root,
                env=dict(os.environ, PYTHONDONTWRITEBYTECODE='1'), stdout=log,
                stderr=subprocess.STDOUT, timeout=900)
        receipt['checks'].append({'script': script, 'returncode': result.returncode,
            'seconds': round(time.monotonic()-started, 3),
            'log_sha256': digest((out / (script + '.log')).read_bytes())})
        (out / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
        require(result.returncode == 0, 'source gate failed: ' + script)
    check_clean(root)
    uploaded = set()
    requests = {'source': request_tree(root, PARENT, SOURCE, SOURCE_TREE, uploaded),
                'seal': request_tree(root, SOURCE, SEAL, SEAL_TREE, uploaded)}
    (out / 'connector-tree-requests.json').write_text(json.dumps(requests, indent=2) + '\n')
    for name, ref in [('source', SOURCE), ('seal', SEAL)]:
        (out / (name + '-commit.txt')).write_bytes(git(root, 'cat-file', 'commit', ref))
    print('Exact source checks passed; connector tree publication required', flush=True)

def publish(root, out):
    receipt = json.loads((out / 'receipt.json').read_text())
    require(receipt['source'] == SOURCE and receipt['seal'] == SEAL and
            [c['script'] for c in receipt['checks']] == CHECKS, 'source receipt mismatch')
    for c in receipt['checks']:
        require(c['returncode'] == 0 and digest((out / (c['script'] + '.log')).read_bytes()) == c['log_sha256'], 'invalid source evidence')
    check_clean(root)
    for sha in [SOURCE_TREE, SEAL_TREE]:
        deadline = time.monotonic() + 600
        while True:
            try:
                value = api('GET', '/git/trees/' + sha)
            except urllib.error.HTTPError as error:
                if error.code != 404:
                    raise
                require(time.monotonic() < deadline, 'connector tree not available')
                time.sleep(10)
            else:
                require(value['sha'] == sha and not value.get('truncated', False), 'wrong tree')
                break
    receipt['objects'] = [stage_commit(root, ref) for ref in [SOURCE, SEAL]]
    (out / 'commit-staging.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print('Exact commit objects staged; authorized connector must create ' + BRANCH, flush=True)
    deadline = time.monotonic() + 600
    while True:
        try:
            branch = api('GET', '/git/ref/heads/' + BRANCH)
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise
            require(time.monotonic() < deadline, 'connector validation ref not available')
            time.sleep(10)
        else:
            require(branch['object']['sha'] == SEAL, 'validation ref contains other work')
            break
    receipt['branch'] = BRANCH
    receipt['dispatches'] = []
    for workflow, failed_run in SELECTED.items():
        failed = api('GET', '/actions/runs/' + str(failed_run))
        require(failed['head_sha'] == PARENT and failed['conclusion'] == 'failure' and
                failed['workflow_id'] == workflow, 'selected workflow is not an original failure')
        runs = api('GET', '/actions/workflows/' + str(workflow) + '/runs?head_sha=' + SEAL + '&event=workflow_dispatch&per_page=100')['workflow_runs']
        require(all(r['head_sha'] == SEAL for r in runs), 'unexpected run inventory')
        if not runs:
            api('POST', '/actions/workflows/' + str(workflow) + '/dispatches', {'ref': BRANCH})
        receipt['dispatches'].append({'workflow': workflow, 'original_failure': failed_run,
            'dispatched': not runs, 'existing_runs': [r['id'] for r in runs]})
        (out / 'publication.json').write_text(json.dumps(receipt, indent=2) + '\n')
    check_clean(root)
    print('Exact sealed repair published; only five previously failed workflows dispatched', flush=True)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase', choices=('prepare', 'publish'))
    for name in ('repository', 'destination', 'output', 'bundle'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    out = args.output.resolve(); out.mkdir(parents=True, exist_ok=True)
    if args.phase == 'prepare':
        prepare(args.repository.resolve(), args.destination.resolve(), out, args.bundle.resolve())
    else:
        publish(args.destination.resolve(), out)

if __name__ == '__main__':
    main()
