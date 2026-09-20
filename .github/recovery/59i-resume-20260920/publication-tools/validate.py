#!/usr/bin/env python3
"""Offline controller controls; not source, hardware, or CI qualification."""
import argparse
import ast
import copy
from datetime import datetime
import importlib.util
from pathlib import Path
import subprocess
import yaml

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('stage_controls', HERE / 'stage.py')
stage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(stage)


def rejects(action):
    try:
        action()
    except RuntimeError:
        return
    raise AssertionError('invalid control was accepted')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--repo-root', type=Path, required=True)
    args = parser.parse_args()
    repo = args.repo_root.resolve()
    for path in HERE.glob('*.py'):
        ast.parse(path.read_text(), filename=str(path))
    flow = yaml.safe_load((HERE / 'stage.yml').read_text())
    assert flow['permissions'] == {'contents': 'write', 'actions': 'read'}
    assert len(stage.CHECKS) == 25
    assert flow['concurrency']['cancel-in-progress'] is False
    for job in flow['jobs'].values():
        for step in job['steps']:
            if 'run' in step:
                subprocess.run(['bash', '-n'], input=step['run'].encode(), check=True)
                assert 'dispatch' not in step['run']
    assert stage.git(repo, 'rev-parse', stage.TARGET + '^{tree}').decode().strip() == stage.TARGET_TREE
    assert stage.git(repo, 'rev-parse', stage.DOCS_CHECKPOINT + '^{tree}').decode().strip() == stage.DOCS_CHECKPOINT_TREE
    assert stage.git(repo, 'rev-list', '--parents', '-n', '1', stage.DOCS_CHECKPOINT).decode().split()[1:] == [stage.BASE, stage.TARGET]
    assert len(stage.runtime_paths(repo, stage.BASE)) == 1847
    assert len(stage.runtime_gitlinks(repo, stage.BASE)) == 1
    # Exercise actual immutable metadata including both timezone offsets and the
    # ordered two-parent documentation checkpoint. No fake source pass is used.
    for sha in (stage.BASE, stage.TARGET, stage.DOCS_CHECKPOINT):
        raw = stage.git(repo, 'cat-file', 'commit', sha)
        body = stage.commit_body(raw)
        rows = ['tree ' + body['tree']] + ['parent ' + p for p in body['parents']]
        for key in ('author', 'committer'):
            person = body[key]
            date = datetime.fromisoformat(person['date'])
            rows.append('%s %s <%s> %d %s' % (key, person['name'], person['email'],
                date.timestamp(), date.strftime('%z')))
        rebuilt = ('\n'.join(rows) + '\n\n' + body['message']).encode()
        assert rebuilt == raw
        assert stage.git(repo, 'hash-object', '-t', 'commit', '--stdin', input=rebuilt).decode().strip() == sha
    rejects(lambda: stage.commit_body(raw.replace(b'\ncommitter ', b'\ngpgsig unsupported\ncommitter ', 1)))
    good = {
        '/git/ref/heads/' + stage.FEATURE: {'object': {'sha': stage.BASE}},
        '/git/ref/heads/' + stage.TARGET_BRANCH: {'object': {'sha': stage.TARGET}},
        '/pulls/177': {'state': 'open', 'draft': True, 'merged': False,
            'head': {'sha': stage.BASE, 'ref': stage.FEATURE, 'repo': {'full_name': stage.REPO}},
            'base': {'sha': 'stale-cache-is-not-authoritative', 'ref': stage.TARGET_BRANCH,
                     'repo': {'full_name': stage.REPO}}},
    }
    def identity(data):
        remote = object.__new__(stage.Remote)
        remote.api = lambda method, path: copy.deepcopy(data[path])
        remote.identity(stage.BASE)
    identity(good)
    mutations = [
        ('/git/ref/heads/' + stage.FEATURE, ['object', 'sha'], '0' * 40),
        ('/git/ref/heads/' + stage.TARGET_BRANCH, ['object', 'sha'], '0' * 40),
        ('/pulls/177', ['head', 'sha'], '0' * 40),
        ('/pulls/177', ['head', 'ref'], 'main'),
        ('/pulls/177', ['base', 'ref'], 'main'),
        ('/pulls/177', ['head', 'repo', 'full_name'], 'foreign/repo'),
        ('/pulls/177', ['base', 'repo', 'full_name'], 'foreign/repo'),
        ('/pulls/177', ['state'], 'closed'),
        ('/pulls/177', ['draft'], False),
        ('/pulls/177', ['merged'], True),
    ]
    for endpoint, keys, replacement in mutations:
        bad = copy.deepcopy(good)
        item = bad[endpoint]
        for key in keys[:-1]:
            item = item[key]
        item[keys[-1]] = replacement
        rejects(lambda: identity(bad))
    run = dict(id=stage.REFERENCE_RUN, head_sha=stage.BASE, run_attempt=stage.REFERENCE_ATTEMPT,
        path='.github/workflows/' + stage.WORKFLOW, status='completed', conclusion='success')
    jobs = dict(total_count=4, jobs=[dict(id=identifier, name=name, status='completed', conclusion='success')
        for name, identifier in stage.REFERENCE_JOBS.items()])
    def reference(r, j):
        remote = object.__new__(stage.Remote)
        remote.value = {'diagnostic': {'source_sha': stage.BASE}}
        remote.api = lambda method, path: copy.deepcopy(j if '/jobs?' in path else r)
        receipt = remote.diagnostic()
        assert receipt['new_head_qualification'] is False
        assert receipt['qualification_scope'] == 'historical-runtime-reference-only'
    reference(run, jobs)
    for field, value in [('id', 1), ('head_sha', '0' * 40), ('run_attempt', 2),
                         ('conclusion', 'failure'), ('path', 'other.yml')]:
        bad = dict(run, **{field: value})
        rejects(lambda: reference(bad, jobs))
    for field, value in [('conclusion', 'skipped'), ('status', 'queued'), ('id', 1), ('name', 'foreign')]:
        bad = copy.deepcopy(jobs)
        bad['jobs'][0][field] = value
        rejects(lambda: reference(run, bad))
    remote = object.__new__(stage.Remote)
    remote.reads = set()
    remote.blobs = set()
    remote.commit_bodies = []
    for method, path, body in [
        ('PATCH', '/git/refs/heads/' + stage.FEATURE, {'sha': stage.BASE, 'force': False}),
        ('POST', '/actions/workflows/' + stage.WORKFLOW + '/dispatches', {'ref': stage.FEATURE}),
        ('POST', '/git/commits', {'message': 'unlisted'}),
        ('POST', '/git/blobs', {'encoding': 'base64', 'content': 'dW5saXN0ZWQ='}),
        ('GET', '/arbitrary', None),
    ]:
        rejects(lambda: remote.api(method, path, body))
    print('PASS: offline syntax, exact metadata, 10 ref/PR mutations, 9 reference mutations, 5 denied remote operations.')
    print('NO source qualification, CI pass, ref update, remote request, or new-head hardware credit is claimed.')


if __name__ == '__main__':
    main()
